(ns com.datastax.configbuilder.build-config
  (:require [com.datastax.configbuilder.definitions :as d]
            [slingshot.slingshot :refer [throw+]]
            [lcm.utils.data :as data]))

;; The input config-data will contain some model-info. These contain
;; data that is not defined in the definitions, such as listen_address.
(def model-info-keys #{:cluster-info :datacenter-info :node-info})
(defrecord ClusterInfo [name seeds])
(defrecord DatacenterInfo [name
                           graph-enabled
                           solr-enabled
                           spark-enabled])
;; Note, we are using the current _address field names as of DSE 6.0.
;; Namely native_transport_address and native_transport_rpc_address.
;; Clients should not be passing in the old names.
(defrecord NodeInfo [name
                     rack
                     listen_address
                     broadcast_address
                     native_transport_address
                     native_transport_broadcast_address
                     initial_token
                     auto_bootstrap
                     agent_version])

(defn valid-config-keys?
  "Are the keys in config-data valid for this version of DSE?"
  [{:keys [definitions datastax-version]}
   config-data]
  (let [valid-key? (into (conj model-info-keys :address-yaml)
                         (keys definitions))
        invalid-keys (remove valid-key? (keys config-data))]
    (if (seq invalid-keys)
      (throw+ {:type :InvalidConfigKeys
               :message (format "Invalid config keys for DSE version %s: %s"
                                datastax-version
                                (data/format-seq invalid-keys))})
      config-data)))

(defn with-defaults
  "Fills in defaults from definitions where there is no user value.
   This will also add in missing config-data keys (for example, if
   :cassandra-env-sh is missing, it will be created with all default
   values)."
  [{:keys [definitions]} config-data]
  (reduce
    (fn [config-data [config-key config-definitions]]
      (update config-data config-key
              d/fill-in-defaults config-definitions))
    config-data
    definitions))

(defmulti enrich-config
          "Enriches the config-data for a given config-key with data from the
          model-info attributes."
          (fn [_ config-key _] config-key))

(defmethod enrich-config :default
  [_ _ config-data]
  ;; default behavior is to do no enrichment
  config-data)

(defn dse-version-60-or-greater?
  "Given a version string, returns true if the version string is 6.0.0 or
  greater and false otherwise."
  [version]
  (when-let [major-version-string (re-find #"^[0-9]+" (or version ""))]
    (>= (Integer/parseInt major-version-string) 6)))

(def ^:private pre-60-field-mappings {:native_transport_address           :rpc_address
                                      :native_transport_broadcast_address :broadcast_rpc_address})

(defn ensure-correct-address-field-names
  "DSE 6.0 renamed rpc_address to native_transport_address and
  broadcast_rpc_address to native_transport_broadcast_address.
  We need to make sure we are using the old names if the version
  is < 6.0."
  [datastax-version fields]
  (if (dse-version-60-or-greater? datastax-version)
    fields
    (reduce (fn [new-fields [from-field-name to-field-name]]
              (dissoc (assoc new-fields to-field-name (get new-fields from-field-name))
                      from-field-name))
            fields
            pre-60-field-mappings)))

;; Note, this includes both old and new address field names. We use this
;; to extract the node private properties, regardless of DSE version. When
;; writing these to cassandra-yaml, we use the ensure-correct-address-fields
;; fn above.
(def node-private-props #{:listen_address
                          :broadcast_address
                          :native_transport_address
                          :native_transport_broadcast_address
                          :rpc_address
                          :broadcast_rpc_address
                          :seed_provider
                          :initial_token
                          :auto_bootstrap})

(defmethod enrich-config :cassandra-yaml
  [{:keys [datastax-version]}
   config-key
   {:keys [cluster-info node-info] :as config-data}]
  (let [seed-provider [{:class_name "org.apache.cassandra.locator.SimpleSeedProvider"
                        :parameters [{:seeds (:seeds cluster-info)}]}]

        ;; Note, we are using the new address field names since DSE 6.0+
        additional-cassandra-yaml-fields
        (ensure-correct-address-field-names
          datastax-version
          (-> node-info
              (select-keys node-private-props)
              (assoc :cluster_name (:name cluster-info)
                     :seed_provider seed-provider)))]
    (-> config-data
        ;; make sure no pre-existing values leak into the config output
        (update config-key #(apply dissoc % node-private-props))
        ;; Merge the data into :cassandra-yaml
        (update config-key merge additional-cassandra-yaml-fields))))

(defmethod enrich-config :cassandra-env-sh
  [_ config-key {:keys [jvm-options] :as config-data}]
  (update config-data config-key merge
          (select-keys jvm-options [:jmx-port])))

(def workload-keys [:graph-enabled :spark-enabled :solr-enabled])

(defn- get-workload-vars
  [datacenter]
  (data/map-values
    data/as-int
    (select-keys datacenter workload-keys)))

(defmethod enrich-config :dse-default
  [_ config-key {:keys [datacenter-info] :as config-data}]
  (update config-data config-key merge (get-workload-vars datacenter-info)))

(defmethod enrich-config :cassandra-rackdc-properties
  [_ config-key {:keys [datacenter-info node-info] :as config-data}]
  (update config-data config-key merge
          {:dc   (:name datacenter-info)
           :rack (:rack node-info)}))

(defmulti generate-file-path
          "Generate the absolute file path for the config."
          (fn [_ config-key _] config-key))

(defmethod generate-file-path :default
  [{:keys [definitions]} config-key config-data]
  ;; Needs to be modified when tarball paths are a thing...
  (let [{:keys [package-path]}
        (get definitions config-key)]
    (if (not (seq package-path))
      config-data
      (assoc-in config-data [:node-info :file-paths config-key] package-path))))

(def address-yaml-path "/var/lib/datastax-agent/conf/address.yaml")

(defmethod generate-file-path :address-yaml
  [_ config-key config-data]
  (assoc-in config-data [:node-info :file-paths config-key] address-yaml-path))


(defn- is-directory?
  "Predicate for map-paths that will filter through definition tree paths
  for fields that have {:is_directory true} in their metadata."
  [k v]
  (and (= :is_directory k) (true? v)))

(defn- get-custom-dirs*
  "Given a config key, returns a reduce fn that compares a field's
  :default_value from definitions to the actual user config value.
  If they are different, the config value is added to the
  :config-custom-dirs map in the profile context."
  [definitions config-key]
  (fn [config-data
       directory-property-path]
    (let [field-metadata (get-in definitions (cons config-key directory-property-path))
          config-key-path (d/property-path->key-path
                           directory-property-path)

          ;; if the field type is list, convert it to a set
          as-set (fn [default-value]
                   (if (= "list" (:type field-metadata))
                     ;; convert list to set
                     (set default-value)
                     ;; wrap single value in set, unless nil
                     (if (nil? default-value) #{} (hash-set default-value))))
          default-values (as-set (:default_value field-metadata))
          actual-values (as-set (get-in config-data
                                        (cons config-key config-key-path)))

          ;; Remove default values from the actual values. What's left
          ;; are custom values
          custom-values (remove default-values actual-values)]

      (if (empty? custom-values)
        config-data ;; no custom values
        (assoc-in config-data
                  [:node-info :config-custom-dirs config-key config-key-path]
                  ;; We provide all this data to meld so it can create
                  ;; friendly event messages.
                  {:config-file (get-in definitions [config-key :display-name])
                   :key config-key-path
                   :dirs custom-values})))))

(defn get-custom-dirs
  "For the given config-key, finds user-specified non-default directory
  paths in the config and adds them to the node-info."
  [{:keys [definitions]} config-key config-data]
  (if-let [definitions-for-config
           (select-keys (get definitions config-key) [:properties])]
    (let [;; Get path to a definition properties that represent directories
          ;; Note that the call to map paths will return a path with :is_directory
          ;; at the end, so mapping butlast over the seq will trim that.
          ;; Example entry before trimming:
          ;; [:properties :data_file_directories :is_directory]
          directory-property-paths
          (map butlast (data/map-paths is-directory? definitions-for-config))]
      ;; For each directory property-path, get the :default_value and the actual
      ;; value in config. If they are different, add the actual value to the context.
      ;; This will result in meld checking that directory for existence, etc.
      (reduce
        (get-custom-dirs* definitions config-key)
        config-data
        directory-property-paths))
    config-data))

(defn build-configs*
  "Perform data enrichment for each config key in the config-data."
  [definitions-data config-data]
  (reduce (fn [config-data config-key]
            (->> config-data
                 (enrich-config definitions-data config-key)
                 (generate-file-path definitions-data config-key)
                 (get-custom-dirs definitions-data config-key))) config-data (keys config-data)))

(defn build-configs
  "Enriches the config-data by merging in defaults (where there are no
   user-configured values, sometimes referred to as 'overrides'),
   model-info (things that aren't defined in definition files, like
   listen_address), and other special snowflakes like :address-yaml."
  [definitions-data config-data]
  (->> config-data
       (valid-config-keys? definitions-data)
       (with-defaults definitions-data)
       (build-configs* definitions-data)))
