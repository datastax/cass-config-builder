(ns com.datastax.configbuilder.render.config-file-renderer-test
  (:require [com.datastax.configbuilder.render.config-file-renderer :as renderer]
            [com.datastax.configbuilder.test-data :refer [definitions-location]]
            [com.datastax.configbuilder.test-helpers :as helper]
            [com.datastax.configbuilder.definitions :as d]
            [selmer.parser :refer [render]]
            [clojure.string :as str]
            [slingshot.test :refer :all]
            [clojure.test :refer :all]))

(deftest test-sanitize-bash-env-var-value
  (is (= (render "{{foo|sanitize-bash-env-var-value}}" {:foo "test"}) "test"))
  (is (= (render "{{foo|sanitize-bash-env-var-value}}" {:foo "test#"}) "test"))
  (is (= (render "{{foo|sanitize-bash-env-var-value}}" {:foo "123AbC"}) "123AbC"))
  (is (= (render "{{foo|sanitize-bash-env-var-value}}" {:foo "1 @_-:&,./+&!+="}) "1 _-:,./++=")))

(deftest test-coerce-types
  (is (= (renderer/coerce-types
          {:properties
           {:a {:type "boolean" :default_value false :render_as "int"}
            :b {:type "boolean" :default_value true}}}
          {:a false
           :b true})
         {:a 0 :b true})))

(def test-properties-1
  {:properties
   {:a {:default_value 10}
    :b {:default_value 14}}})

(def test-properties-2
  {:properties
   {:a {:type "int" :constant "TEST" :default_value 20}
    :b {:default_value 24}}})

(deftest test-process-constants
  (is (= (renderer/process-constants
           test-properties-2
           {:b 24})
         ;; :a should not be rendered because it was not passed in
         ;; field-values
         {:b 24}))
  (is (= (renderer/process-constants
           {:properties
            {:max-heap-size
             {:type "string"
              :constant "MAX_HEAP_SIZE"
              :required false
              :default_value "4G"
              :disabled true},
             :not-a-constant
             {:type "string"
              :default_value "abc"}
             :heap-newsize
             {:type "string"
              :constant "HEAP_NEWSIZE"
              :required false
              :default_value "800M"
              :disabled true}}}
           {:max-heap-size "4G"
            :heap-newsize "800M"
            :not-a-constant "def"})
         {:max-heap-size "#MAX_HEAP_SIZE=\"4G\""
          :heap-newsize  "#HEAP_NEWSIZE=\"800M\""
          :not-a-constant "def"})))

(deftest test-render-to-template
  (testing "simple values"
    (with-redefs [com.datastax.configbuilder.definitions/get-template (fn [& args] "{{a}} {{b}}")]
      (is (= (renderer/render-to-template
              {:definitions {:foobie test-properties-1}}
              :foobie
              {:foobie {:a 15 :b 14}})
             "15 14"))))
  (testing "boolean values"
    (with-redefs [com.datastax.configbuilder.definitions/get-template (fn [& args] "{{a}} {{b}} {{c}} {{d}}")]
      (is (= (renderer/render-to-template
              {:definitions {:foobie test-properties-1}}
              :foobie
              {:foobie {:a false :b true :c "false" :d "true"}})
             "false true false true")))))

(deftest test-render-config-file
  (testing "template render"
    (let [result (renderer/render-config-file
                  {:datastax-version helper/default-dse-version
                   :definitions-location definitions-location
                   :definitions (d/get-all-definitions-for-version definitions-location helper/default-dse-version)}
                  :cassandra-rackdc-properties
                  {:cassandra-rackdc-properties {:rack "rack4" :dc "dc3"}})]
      (is (.contains result "dc=dc3"))
      (is (.contains result "rack=rack4"))))
  (testing "yaml render"
    (let [result (renderer/render-config-file
                  {:datastax-version helper/default-dse-version
                   :definitions-location definitions-location
                   :definitions (d/get-all-definitions-for-version definitions-location helper/default-dse-version)}
                  :dse-yaml
                  {:dse-yaml {:max_memory_to_lock_mb 2048}})]
      (is (.contains result "max_memory_to_lock_mb: 2048")))))

(deftest test-render-spark-alwayson-sql-conf
  (let [result (renderer/render-config-file
                {:datastax-version helper/default-dse-version
                 :definitions (d/get-all-definitions-for-version definitions-location helper/default-dse-version)
                 :definitions-location definitions-location}
                :spark-alwayson-sql-conf
                {:spark-alwayson-sql-conf
                  {:spark_cassandra_connection_factory
                   "com.datastax.bdp.spark.DseCassandraConnectionFactory"

                   :spark_cassandra_auth_conf_factory
                   "com.datastax.bdp.spark.ha.alwaysonsql.auth.AlwaysOnSqlInClusterAuthConfFactory"

                   :spark_extraListeners
                   "com.datastax.bdp.spark.reporting.DseSparkListener,org.apache.spark.sql.hive.thriftserver.AlwaysOnSqlSparkListener"

                   :spark_SparkHadoopUtil
                   "org.apache.hadoop.security.DseSparkHadoopUtil"

                   :spark_hadoop_com_datastax_bdp_fs_client_authentication_factory
                   "com.datastax.bdp.spark.ha.alwaysonsql.auth.DseFsRestClientAuthProviderFactory"

                   :spark_sql_catalogImplementation
                   "hive"

                   :spark_sql_extensions
                   "org.apache.spark.sql.hive.thriftserver.AlwaysOnSqlExtensions"

                   :spark_sql_hive_metastore_barrierPrefixes
                   "org.apache.spark.sql.cassandra"

                   :spark_hive_alwaysOnSql
                   true

                   :spark_authenticate
                   false

                   :spark_network_crypto_enabled
                   false

                   :spark_network_crypto_saslFallback
                   false

                   :spark_authenticate_secretBitLength
                   256

                   :spark_authenticate_enableSaslEncryption
                   false

                   :spark_network_sasl_serverAlwaysEncrypt
                   true

                   :spark_redaction_regex
                   "(?i)secret|password|token"

                   :spark_dse_configuration_fetch_retries
                   2

                   :spark_task_maxFailures
                   10

                   :spark_additional_configs
                   {"key1" "value1"}

                   :hive_additional_configs
                   {"hive-key1" "hive-value1"}

                   :resource_manager_additional_configs
                   {"rm-key1" "rm-value1"}}})]
     ;; We trim the trailing whitespace because it is difficult to perfectly match the rendered template.
    (is     (= (str/trim result)
               (str/trim
               "# This file is managed by DataStax OpsCenter LifeCycle Manager.
# Manual edits will be overwritten by the next install or configure
# job that runs on this system.

# AlwaysOn SQL spark configuration property file
# DSE related Settings
spark.cassandra.connection.factory          com.datastax.bdp.spark.DseCassandraConnectionFactory
spark.cassandra.auth.conf.factory           com.datastax.bdp.spark.ha.alwaysonsql.auth.AlwaysOnSqlInClusterAuthConfFactory
spark.extraListeners                        com.datastax.bdp.spark.reporting.DseSparkListener,org.apache.spark.sql.hive.thriftserver.AlwaysOnSqlSparkListener
spark.SparkHadoopUtil                       org.apache.hadoop.security.DseSparkHadoopUtil
spark.hadoop.com.datastax.bdp.fs.client.authentication.factory     com.datastax.bdp.spark.ha.alwaysonsql.auth.DseFsRestClientAuthProviderFactory

spark.sql.catalogImplementation             hive
spark.sql.extensions                        org.apache.spark.sql.hive.thriftserver.AlwaysOnSqlExtensions
spark.sql.hive.metastore.barrierPrefixes    org.apache.spark.sql.cassandra
spark.hive.alwaysOnSql                      true


# Mutual authentication and encryption for Spark application. Unlike in standalone mode, those
# settings are per application. Authentication key is generated automatically using secure random
# number generator whenever spark.authenticate is set to true. The secret key is different for each
# single application. It is allowed to have applications running with authentication enabled and
# disabled at the same time.
spark.authenticate                          false
spark.network.crypto.enabled                false
spark.network.crypto.saslFallback           false
spark.authenticate.secretBitLength          256
spark.authenticate.enableSaslEncryption     false
spark.network.sasl.serverAlwaysEncrypt      true

# Regex to decide which Spark configuration properties and environment variables contain sensitive
# information and therefore should be redacted when they are listed in any way.
spark.redaction.regex                       (?i)secret|password|token

# When DSE Spark is started, it fetches some configuration settings from DSE node
# This operation can be retried a couple of times, as defined by this property
# The delay between subsequent retries starts from 1 second and is doubled with each trial (limited at 10 seconds)
spark.dse.configuration.fetch.retries   2

# Increase retry threshold for greater stability
spark.task.maxFailures	10

key1 value1


# Hive configuration settings in key-value pairs. It starts with spark.hive.
# spark.hive.

hive-key1 hive-value1


# Resource manager configuration settings. connection.host, connection.port, connection.local_dc,
# connection.auth.conf.factory, connection.factory, connection.ssl.*  should be set to local DC where
# AlwaysOn SQL locates.

rm-key1 rm-value1

 ")))))

(deftest test-render-jvm-options
  (let [result (renderer/render-config-file
                {:datastax-version helper/default-dse-version
                 :definitions (d/get-all-definitions-for-version definitions-location helper/default-dse-version)
                 :definitions-location definitions-location}
                :jvm-options
                {:jvm-options
                  ;; These are the stock field-values from a 6.0.0 run
                  {:additional-jvm-opts []
                   :log_gc false
                   :thread_priority_policy_42 true
                   :use_gc_log_file_rotation true
                   :initiating_heap_occupancy_percent ""
                   :string_table_size 1000003
                   :print_tenuring_distribution true
                   :resize_tlb true
                   :cassandra_join_ring true
                   :use_tlb true
                   :perf_disable_shared_mem true
                   :always_pre_touch true
                   :unlock_commercial_features false
                   :cassandra_disable_auth_caches_remote_configuration false
                   :heap_dump_on_out_of_memory_error true
                   :initial_heap_size "auto"
                   :g1r_set_updating_pause_time_percent 5
                   :java_net_prefer_ipv4_stack true
                   :cassandra_load_ring_state true
                   :per_thread_stack_size "256k"
                   :print_flss_statistics false
                   :print_heap_at_gc true
                   :cassandra_write_survey false
                   :print_gc_application_stopped_time true
                   :garbage_collector "G1GC"
                   :print_promotion_failure true
                   :parallel_gc_threads ""
                   :jmx-connection-type "local-no-auth"
                   :jmx-remote-ssl false
                   :gc_log_file_size "10M"
                   :conc_gc_threads ""
                   :max_heap_size "auto"
                   :use_thread_priorities true
                   :enable_assertions true
                   :print_gc_date_stamps true
                   :cassandra_force_default_indexing_page_size false
                   :flight_recorder false
                   :agent_lib_jdwp false
                   :jmx-port 7199
                   :number_of_gc_log_files 10
                   :print_gc_details true
                   :max_gc_pause_millis 500}})]
    (is     (= (str/trim result)
               "# This file is managed by DataStax OpsCenter LifeCycle Manager.
# Manual edits will be overwritten by the next install or configure
# job that runs on this system.

-XX:+AlwaysPreTouch
-Dcassandra.disable_auth_caches_remote_configuration=false
-Dcassandra.force_default_indexing_page_size=false
-Dcassandra.join_ring=true
-Dcassandra.load_ring_state=true
-Dcassandra.write_survey=false
#-XX:ConcGCThreads=
-ea
-XX:G1RSetUpdatingPauseTimePercent=5
-XX:GCLogFileSize=10M
-XX:+HeapDumpOnOutOfMemoryError
#-Xmsauto
#-XX:InitiatingHeapOccupancyPercent=
-Djava.net.preferIPv4Stack=true
-XX:MaxGCPauseMillis=500
#-Xmxauto
-XX:NumberOfGCLogFiles=10
#-XX:ParallelGCThreads=
-Xss256k
-XX:+PerfDisableSharedMem
-XX:+PrintGCApplicationStoppedTime
-XX:+PrintGCDateStamps
-XX:+PrintGCDetails
-XX:+PrintHeapAtGC
-XX:+PrintPromotionFailure
-XX:+PrintTenuringDistribution
-XX:+ResizeTLAB
-XX:StringTableSize=1000003
-XX:ThreadPriorityPolicy=42
-XX:+UseGCLogFileRotation
-XX:+UseThreadPriorities
-XX:+UseTLAB





-XX:+UseG1GC




-Dcom.sun.management.jmxremote.authenticate=false
-Dcassandra.jmx.local.port=7199"))))

(deftest test-render-unsupported-configuration-file
  ;; Unknown configuration files are verbotten. Instead of returning nil here, like we
  ;; used to, we should find the source of the problem and remove the source of the
  ;; bad config-key.
  (is (thrown+?
       [:type :MissingDefinitions]
       (renderer/render-config-file
        {:datastax-version helper/invalid-dse-version
         :definitions (d/get-all-definitions-for-version definitions-location helper/invalid-dse-version)
         :definitions-location definitions-location}
        :jvm-options
        {}))))
