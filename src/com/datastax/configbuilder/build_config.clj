(ns com.datastax.configbuilder.build-config
  (:require [com.datastax.configbuilder.definitions :as d]
            [slingshot.slingshot :refer [throw+]]
            [lcm.utils.data :as data]
            [clojure.java.io :as io]
            [lcm.utils.version :as v]))

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

(def tarball? #{"tarball"})
(def package? #{"package"})

(defn tarball-config?
  "Does this config represent a tarball installation?"
  [config-data]
  (tarball? (get-in config-data [:install-options :install-type])))

(defn maybe-use-tarball-defaults
  "Returns definitions with the default-values potentially swapped out
  for tarball-defaults in the case of a tarball install-type."
  [{:keys [definitions] :as definitions-data} config-data]
  (if (tarball-config? config-data)
    (update definitions-data :definitions d/use-tarball-defaults)
    definitions-data))

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

(def ^:private pre-60-field-mappings {:native_transport_address           :rpc_address
                                      :native_transport_broadcast_address :broadcast_rpc_address})

(defn ensure-correct-address-field-names
  "DSE 6.0 renamed rpc_address to native_transport_address and
  broadcast_rpc_address to native_transport_broadcast_address.
  We need to make sure we are using the old names if the version
  is < 6.0."
  [datastax-version fields]
  (if (v/version-is-at-least "6.0" datastax-version)
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
  [{:keys [datastax-version]}
   config-key
   {:keys [jvm-options jvm-server-options] :as config-data}]
  (update config-data config-key merge
          (select-keys
           ;; Since DSE 6.8, jvm.options has been replaced by jvm-server.options
           ;; and version-specific files jvm8-server.options and jvm11-server.options
           ;; Thus, the location of the jmx-port option is now dependent on the
           ;; DSE version.
           (if (v/version-is-at-least "6.8" datastax-version)
             jvm-server-options
             jvm-options)
           [:jmx-port])))

(def workload-keys [:graph-enabled :spark-enabled :solr-enabled])

(defn- get-workload-vars
  [datacenter]
  (data/map-values
    data/as-int
    (select-keys datacenter workload-keys)))

(defn- get-dse-run-as
  "Returns a vector of the [user, group] that cassandra should run as.
  This information comes from the install-options config."
  [config-data]
  (let [{:keys [install-type install-privileges run-dse-as-user run-dse-as-group]}
        (get config-data :install-options {})]
    (if (tarball? install-type)
      (if (= "root" install-privileges)
        [(or run-dse-as-user "cassandra") (or run-dse-as-group "cassandra")]
        ;; Doesn't really make sense for non-root tarballs. We have to use
        ;; the ssh login user/group.
        [nil nil])
      ["cassandra" "cassandra"])))

(defmethod enrich-config :dse-default
  [_ config-key {:keys [datacenter-info] :as config-data}]
  (let [workload-vars (get-workload-vars datacenter-info)
        run-as-vars (zipmap [:cassandra-user :cassandra-group]
                            (get-dse-run-as config-data))]
    (update config-data config-key merge
            workload-vars
            run-as-vars)))

;; this only applies to tarball and is removed when NOT tarball
(defmethod enrich-config :datastax-env-sh
  [_ config-key {:keys [java-setup install-options] :as config-data}]
  (let [install-directory (:install-directory install-options)
        manage-java (:manage-java java-setup)
        java-vendor (:java-vendor java-setup)]
    (update config-data config-key merge
            {:manage-java manage-java
             :install-directory install-directory
             :java-vendor java-vendor})))

(defmethod enrich-config :cassandra-rackdc-properties
  [_ config-key {:keys [datacenter-info node-info] :as config-data}]
  (update config-data config-key merge
          {:dc   (:name datacenter-info)
           :rack (:rack node-info)}))

(defmulti generate-file-path
  "Generate the absolute file path for the config. Based on instal-type
  (package v tarball) and related settings."
          (fn [_ config-key _] config-key))


(defmethod generate-file-path :default
  [{:keys [definitions]} config-key config-data]
  (let [{:keys [install-type install-directory] :or {install-type "package"}}
        (get config-data :install-options)

        {:keys [package-path tarball-path]}
        (get definitions config-key)

        empty-path? (or (and (package? install-type)
                             (not (seq package-path)))
                        (and (tarball? install-type)
                             (not (seq tarball-path))))]
    (if empty-path?
      config-data
      (assoc-in config-data [:node-info :file-paths config-key]
                (case install-type
                  "package" package-path
                  "tarball" (str (io/file install-directory "dse" tarball-path)))))))

(def address-yaml-paths {:package-path "/var/lib/datastax-agent/conf/address.yaml"
                         :tarball-path "datastax-agent/conf/address.yaml"})

(defmethod generate-file-path :address-yaml
  [_ config-key config-data]
  (let [{:keys [install-type install-directory] :or {install-type "package"}}
        (:install-options config-data)]
    (assoc-in config-data [:node-info :file-paths config-key]
              (if (tarball? install-type)
                (str (io/file install-directory (:tarball-path address-yaml-paths)))
                (:package-path address-yaml-paths)))))

(defn- is-directory?
  "Predicate for map-paths that will filter through definition tree paths
  for fields that have {:is_directory true} in their metadata."
  [k v]
  (and (= :is_directory k) (true? v)))

(defn- is-file?
  "Predicate for map-paths that will filter through definition tree paths
  for fields that have {:is_file true} in their metadata."
  [k v]
  (and (= :is_file k) (true? v)))

(defn- is-path?
  "Predicate for map-paths that matches either file or directory fields."
  [k v]
  (or (is-directory? k v) (is-file? k v)))

(defn make-absolute
  "If path is not absolute, prepend it with base-path."
  [base-path path]
  (if (.isAbsolute (io/file path))
    path
    ;; DSE is installed under the "dse" subdirectory under base-path
    (str (io/file base-path path))))

(defn fully-qualify-fn
  "For tarball installations, paths may need to be fully-qualified by
  prepending the install-directory.
  This function takes a map of config data and returns a function that
  fully qualifies paths."
  [config-data]
  (if (tarball-config? config-data)
    (partial make-absolute (get-in config-data [:install-options :install-directory]))

    ;; don't qualify paths unless it's a tarball install
    identity))

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
          fully-qualify (fully-qualify-fn config-data)

          ;; if the field type is list, convert it to a set
          as-set (fn [default-value]
                   (if (= "list" (:type field-metadata))
                     ;; convert list to set
                     (set (map fully-qualify default-value))
                     ;; wrap single value in set, unless nil
                     (if (nil? default-value) #{} (hash-set (fully-qualify default-value)))))
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

(defn fully-qualify-paths
  "Ensures that tarball paths are fully qualified."
  [{:keys [definitions]} config-key config-data]
  (if (tarball-config? config-data)
    (let [fully-qualify (fully-qualify-fn config-data)

          definitions-for-config
          (select-keys (get definitions config-key) [:properties])

          ;; Get the property paths for config properties that represent
          ;; files and directories.
          path-matchers
          (map (comp #(d/preference-path-matcher % definitions-for-config)
                     butlast)
               (data/map-paths is-path? definitions-for-config))]
      ;; As long as the path matches one of the path-matchers predicates,
      ;; it is a value that needs to be fully-qualified.
      (if-let [is-path-value? (and (seq path-matchers) (apply some-fn path-matchers))]
        ;; Reduce over all paths to leaf nodes in the config.
        ;; The preference path is tested against the path matchers to see if the leaf
        ;; value needs to be transformed into a fully-qualified path.
        ;;
        ;; However, if there are no path-matchers (because there are no path-like values
        ;; in this particular config), nothing needs to be done.
        (reduce
         (fn [config-data preference-path]
           (if (is-path-value? preference-path)
             (update-in config-data
                        (cons config-key preference-path)
                        fully-qualify)
             config-data))
         config-data
         (data/all-paths (get config-data config-key)))
        ;; No path-matchers means this config has no directory or file properties
        config-data))

    ;; Change nothing if it's a package install
    config-data))

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
                 (get-custom-dirs definitions-data config-key)
                 (fully-qualify-paths definitions-data config-key)))
          config-data (keys config-data)))

(defn prune-config-keys
  "Remove config keys that are not applicable to the target installation
  method (package v tarball)."
  [config-data]
  (if (tarball-config? config-data)
    (dissoc config-data :dse-default)
    (dissoc config-data :datastax-env-sh) ))

(defn build-configs
  "Enriches the config-data by merging in defaults (where there are no
   user-configured values, sometimes referred to as 'overrides'),
   model-info (things that aren't defined in definition files, like
   listen_address), and other special snowflakes like :address-yaml."
  [definitions-data config-data]
  (let [definitions-data (maybe-use-tarball-defaults definitions-data config-data)]
    (->> config-data
         (valid-config-keys? definitions-data)
         (with-defaults definitions-data)
         (prune-config-keys)
         (build-configs* definitions-data))))
