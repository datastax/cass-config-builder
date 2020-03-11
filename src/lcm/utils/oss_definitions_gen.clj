(ns lcm.utils.oss-definitions-gen
  (:require [com.datastax.configbuilder.definitions :as d]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.util.regex Matcher)))

(def definitions-location "../definitions/resources")

(def oss-prop-fixes {:authorizer
                     {:type          "string",
                      :options
                                     [{:label "AllowAllAuthorizer", :value "AllowAllAuthorizer"}
                                      {:label "CassandraAuthorizer", :value "CassandraAuthorizer"}],
                      :default_value "AllowAllAuthorizer"}

                     :authenticator
                     {:type          "string",
                      :options
                                     [{:label "AllowAllAuthenticator", :value "AllowAllAuthenticator"}
                                      {:label "PasswordAuthenticator", :value "PasswordAuthenticator"}],
                      :default_value "AllowAllAuthenticator"}

                     :role_manager
                     {:type          "string",
                      :options
                                     [{:label "CassandraRoleManager",
                                       :value "org.apache.cassandra.auth.CassandraRoleManager"}],
                      :default_value "org.apache.cassandra.auth.CassandraRoleManager"}})

(defn- top-level-keys-from-cassandra-yaml [cassandra-yaml-raw-content]
  (->> cassandra-yaml-raw-content
       (re-seq #"(?m)^[#]?([a-z_]+)(?=[:])")
       (map second)
       (map keyword)))

(defn- remove-missing-properties-from-groupings [groupings properties]
  (let [properties (set (map name properties))
        grouping-has-items? (comp seq :list)
        remove-missing-items (fn [grouping]
                               (update-in grouping [:list] (partial filterv properties)))]
    (->> groupings
         (mapv remove-missing-items)
         (filterv grouping-has-items?))))

(defn- dse-value? [v]
  (let [v (if (keyword? v) (name v) v)]
    (and (string? v)
         (or (re-matches #"(?i).*datastax.*" v)
             (re-matches #".*Dse.*" v)
             (re-matches #".*DSE.*" v)))))

(defn- find-top-level-keys-with-dse-defaults
  [cassandra-yaml]
  (let [dse-default? (fn [m]
                       (some
                         (fn [node]
                           (and (coll? node)
                                (= (first node) :default_value)
                                (dse-value? (second node))))
                         (tree-seq coll? identity m)))]
    (->> (:properties cassandra-yaml)
         (filter (comp dse-default? second))
         (map first))))

(defn build-dse-subset-definition-schema [dse-version definition-key properties]
  (let [dse-definition (get (d/get-all-definitions-for-version
                              definitions-location
                              "dse"
                              dse-version)
                            definition-key)
        dse-top-level-keys (keys (:properties dse-definition))
        missing-keys (set/difference (set properties) (set dse-top-level-keys))
        properties (select-keys (:properties dse-definition) properties)

        groupings (remove-missing-properties-from-groupings (:groupings dse-definition) (keys properties))
        schema (assoc dse-definition
                 :properties properties
                 :groupings groupings)
        keys-with-dse-defaults (find-top-level-keys-with-dse-defaults schema)]
    {:keys-with-dse-defaults keys-with-dse-defaults
     :keys-missing           missing-keys
     :schema                 schema}))

(defn build-oss-cassandra-yaml-from-dse [dse-version oss-cassandra-yaml-url]
  (let [content (slurp oss-cassandra-yaml-url)
        top-level-keys (top-level-keys-from-cassandra-yaml content)

        result
        (build-dse-subset-definition-schema dse-version :cassandra-yaml top-level-keys)]
    (-> result
        (assoc-in [:schema :package-path] "/etc/cassandra/cassandra.yaml"))))

(defn replace-and-assert
  [s match replacement]
  (let [new-s (string/replace s match replacement)]
    (assert (not= new-s s) (str "Did not find match for: " match))
    new-s))

(defn apply-replacements [s replacements]
  (reduce (fn [content [match replacement]]
            (replace-and-assert content match replacement))
          s
          replacements))

(defn build-oss-cassandra-env-sh-schema-from-dse [oss-version dse-version template-parameters]
  (let [result
        (build-dse-subset-definition-schema dse-version :cassandra-env-sh template-parameters)

        schema (-> (:schema result)
                   (assoc-in [:renderer :template] (str "cassandra-env-sh-cassandra-" oss-version ".template"))
                   (assoc-in [:package-path] "/etc/cassandra/cassandra-env.sh"))]
    (assoc result :schema schema)))

(defn assert-no-keys-missing
  ([result]
   (assert-no-keys-missing result #{}))
  ([{:keys [keys-missing]} allowed-missing]
   (assert (not (seq (set/difference
                       (set keys-missing)
                       (set allowed-missing)))))))

(defn assert-no-keys-dse-defaults
  ([result]
   (assert-no-keys-dse-defaults result #{}))
  ([{:keys [keys-with-dse-defaults]} allowed-dse-defaults]
   (assert (not (seq (set/difference
                       (set keys-with-dse-defaults)
                       (set allowed-dse-defaults)))))))

(defn persist-definition!
  [{:keys [version config-key product schema-content template-content]}]
  (when schema-content
    (let [schema-file (io/file definitions-location (name config-key) product (str (name config-key) "-" product "-" version ".edn"))]
      (with-open [writer (io/writer schema-file)]
        (io/copy (str ";; Generated by " (str *ns*) "\n\n") writer)
        (clojure.pprint/pprint schema-content writer))))
  (when template-content
    (let [template-file (io/file definitions-location (name config-key) product (str (name config-key) "-" product "-" version ".template"))]
      (with-open [writer (io/writer template-file)]
        (io/copy (str "{% comment %}" " Generated by " (str *ns*) " {% endcomment %}\n\n") writer)
        (io/copy template-content writer)))))


;;
;; Cassandra 3.11.6
;;

(defn build-oss-cassandra-3_11_6-cassandra-yaml []
  (let [allowed-keys-dse-defaults (keys oss-prop-fixes)
        ;; TODO: This needs to be reviewed
        definition-prop-fixes (merge oss-prop-fixes
                                     {:enable_sasi_indexes
                                      {:type "boolean", :default_value true}
                                      :enable_materialized_views
                                      {:type "boolean", :default_value true}})
        allowed-keys-missing (into #{:seed_provider
                                     :listen_address
                                     :rpc_address
                                     :cluster_name}
                                   (keys definition-prop-fixes))
        result (build-oss-cassandra-yaml-from-dse "5.1.17" "https://raw.githubusercontent.com/apache/cassandra/cassandra-3.11.5/conf/cassandra.yaml")]
    (assert-no-keys-missing result allowed-keys-missing)
    (assert-no-keys-dse-defaults result allowed-keys-dse-defaults)
    {:schema-content (:schema (update-in result [:schema :properties] merge definition-prop-fixes))
     :version        "3.11.6"
     :config-key     :cassandra-yaml
     :product        "cassandra"}))

(def cassandra-3_11_6-cassandra-env-sh-template-replacements
  [[#"(?m)^[#]export MALLOC_ARENA_MAX=4\s*$" "{{malloc-arena-max}}"]
   [#"(?m)^[#] set jvm HeapDumpPath with CASSANDRA_HEAPDUMP_DIR\s*$" "{{heap-dump-dir}}"]
   [#"(?m)(?<=^JVM_ON_OUT_OF_MEMORY_ERROR_OPT=\"-XX:OnOutOfMemoryError=)kill -9 %p(?=\"\s*$)" "{{on_out_of_memory_error}}"]
   [#"(?m)(?<=^JMX_PORT=)\"7199\"(?=\s*$)" "{{jmx-port}}"]
   [#"(?m)\z" (Matcher/quoteReplacement
                (string/join "\n"
                             [""
                              "{% for jvm-opt in additional-jvm-opts %}"
                              "JVM_OPTS=\"$JVM_OPTS {{jvm-opt}}\""
                              "{% endfor %}"
                              ""]))]])

(defn build-oss-cassandra-3_11_6-cassandra-env-sh-schema [template-parameters]
  (let [{:keys [schema] :as result}
        (build-oss-cassandra-env-sh-schema-from-dse "3.11.6" "5.1.17" template-parameters)
        allowed-missing #{:jmx-port}]
    (assert-no-keys-missing result allowed-missing)
    (assert-no-keys-dse-defaults result)
    schema))

(defn build-oss-cassandra-3_11_6-cassandra-env-sh []
  (let [oss-cassandra-env-sh-url "https://raw.githubusercontent.com/apache/cassandra/cassandra-3.11.6/conf/cassandra-env.sh"
        oss-cassandra-env-sh-content (slurp oss-cassandra-env-sh-url)
        template (apply-replacements oss-cassandra-env-sh-content
                                     cassandra-3_11_6-cassandra-env-sh-template-replacements)
        parameters [:malloc-arena-max :heap-dump-dir :on_out_of_memory_error :jmx-port]
        schema (build-oss-cassandra-3_11_6-cassandra-env-sh-schema parameters)]
    {:version          "3.11.6"
     :config-key       :cassandra-env-sh
     :product          "cassandra"
     :schema-content   schema
     :template-content template}))

(defn build-oss-cassandra-3_11_6-logback-schema []
  (let [schema (:logback-xml (d/get-all-definitions-for-version
                               definitions-location
                               "dse"
                               "5.1.17"))
        schema (-> schema
                   (assoc-in [:renderer :template] (str "logback-xml-cassandra-" "3.11.6" ".template"))
                   (assoc-in [:package-path] "/etc/cassandra/logback.xml"))]
    schema))

(defn build-oss-cassandra-3_11_6-logback-xml []
  (let [schema (build-oss-cassandra-3_11_6-logback-schema)]
    {:version        "3.11.6"
     :config-key     :logback-xml
     :product        "cassandra"
     :schema-content schema}))

(defn remove-required [schema]
  (clojure.walk/postwalk (fn [form]
                           (if (map? form)
                             (dissoc (if-not (:required form)
                                       (dissoc form :default_value)
                                       form)
                                     :required)
                             form))
                         schema))

(defn strip-required []
  (doseq [config-key [:cassandra-env-sh :cassandra-rackdc-properties :cassandra-yaml :jvm-options :logback-xml]]
    (let [product "cassandra"
          version "3.11.6"
          schema-file (io/file definitions-location (name config-key) product (str (name config-key) "-" product "-" version ".edn"))
          schema (clojure.edn/read-string (slurp schema-file))
          fixed-schema (remove-required schema)]
      (with-open [writer (io/writer schema-file)]
        (io/copy (str ";; Generated by " (str *ns*) "\n\n") writer)
        (clojure.pprint/pprint fixed-schema writer)))))

;; (persist-definition! (build-oss-cassandra-3_11_6-cassandra-env-sh))
;; (persist-definition! (build-oss-cassandra-3_11_6-logback-xml))
;; (persist-definition! (build-oss-cassandra-3_11_6-cassandra-yaml))
;; (strip-required)