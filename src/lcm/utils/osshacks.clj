(ns lcm.utils.osshacks
  (:require [com.datastax.configbuilder.definitions :as d]
            [clojure.set :as set]
            [clojure.java.io :as io]))

(def definitions-location "../definitions/resources")


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

(defn build-oss-cassandra-yaml-from-dse [dse-version oss-cassandra-yaml-url]
  (let [content (slurp oss-cassandra-yaml-url)
        top-level-keys (top-level-keys-from-cassandra-yaml content)
        dse-definition (:cassandra-yaml (d/get-all-definitions-for-version
                                          definitions-location
                                          "dse"
                                          dse-version))
        dse-top-level-keys (keys (:properties dse-definition))
        missing-keys (set/difference (set top-level-keys) (set dse-top-level-keys))
        oss-properties (select-keys (:properties dse-definition) top-level-keys)
        oss-groupings (remove-missing-properties-from-groupings (:groupings dse-definition)
                                                                top-level-keys)
        cassandra-yaml {:display-name        "cassandra.yaml"
                        :package-path        "/etc/cassandra/cassandra.yaml"
                        :workload-file-group "cassandra"
                        :ui-visibility       :editable
                        :renderer            {:renderer-type :yaml}
                        :properties          oss-properties
                        :groupings           oss-groupings}]
    {:keys-with-dse-defaults (find-top-level-keys-with-dse-defaults cassandra-yaml)
     :keys-missing           missing-keys
     :cassandra-yaml         cassandra-yaml}))

(defn build-oss-cassandra-4_0_0-cassandra-yaml []
  (let [allowed-keys-dse-defaults  #{}
        ;; TODO: This needs to be reviewed
        allowed-keys-missing #{:concurrent_writes
                               :unlogged_batch_across_partitions_warn_threshold
                               :compaction_large_partition_warning_threshold_mb
                               :concurrent_counter_writes
                               :credentials_validity_in_ms
                               :batch_size_fail_threshold_in_kb
                               :tombstone_warn_threshold
                               :concurrent_materialized_view_writes
                               :rpc_port
                               :key_cache_save_period
                               :rpc_server_type
                               :thrift_prepared_statements_cache_size_mb
                               :seed_provider
                               :listen_address
                               :tombstone_failure_threshold
                               :index_summary_capacity_in_mb
                               :key_cache_size_in_mb
                               :batch_size_warn_threshold_in_kb
                               :start_rpc
                               :rpc_address
                               :cluster_name
                               :thrift_framed_transport_size_in_mb
                               :enable_sasi_indexes
                               :enable_materialized_views
                               :request_scheduler
                               :concurrent_reads
                               :index_summary_resize_interval_in_minutes}
        definition-prop-fixes {:authorizer
                               {:type          "string",
                                :required      true,
                                :options
                                               [{:label "AllowAllAuthorizer", :value "AllowAllAuthorizer"}
                                                {:label "CassandraAuthorizer", :value "CassandraAuthorizer"}],
                                :default_value "AllowAllAuthorizer"}

                               :authenticator
                               {:type          "string",
                                :required      true,
                                :options
                                               [{:label "AllowAllAuthenticator", :value "AllowAllAuthenticator"}
                                                {:label "PasswordAuthenticator", :value "PasswordAuthenticator"}],
                                :default_value "AllowAllAuthenticator"}

                               :role_manager
                               {:type          "string",
                                :required      true,
                                :options
                                               [{:label "CassandraRoleManager",
                                                 :value "org.apache.cassandra.auth.CassandraRoleManager"}],
                                :default_value "org.apache.cassandra.auth.CassandraRoleManager"}}
        result (build-oss-cassandra-yaml-from-dse "6.8.0" "https://raw.githubusercontent.com/apache/cassandra/cassandra-3.11.5/conf/cassandra.yaml")]

    (when-let [delta (seq (set/difference (set (:keys-with-dse-defaults result))
                                          (set (keys definition-prop-fixes))
                                          allowed-keys-dse-defaults))]
      (throw (ex-info "Found keys with DSE defaults" {:definition-key :cassandra-yaml
                                                      :keys-with-dse-defaults delta})))
    (when-let [delta (seq (set/difference (set (:keys-missing result))
                                          (set (keys definition-prop-fixes))
                                          allowed-keys-missing))]
      (throw (ex-info "Expected keys missing" {:definition-key :cassandra-yaml
                                               :keys-missing delta})))
    (dissoc (update-in result [:cassandra-yaml :properties] merge definition-prop-fixes)
            :keys-missing
            :keys-with-dse-defaults)))

(defn build-and-write-oss-cassandra-4_0_0-cassandra-yaml []
  (let [definition-file (io/file definitions-location "cassandra-yaml" "cassandra" "cassandra-yaml-cassandra-4.0.0.edn")]
    (with-open [writer (io/writer definition-file)]
      (io/copy ";; Generated by lcm.utils.osshacks/build-and-write-oss-cassandra-4_0_0-cassandra-yaml\n\n" writer)
      (clojure.pprint/pprint (:cassandra-yaml (build-oss-cassandra-4_0_0-cassandra-yaml))
                             writer))))
