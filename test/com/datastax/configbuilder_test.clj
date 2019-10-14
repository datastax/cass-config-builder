(ns com.datastax.configbuilder-test
  (:require [com.datastax.configbuilder :as cb]
            [com.datastax.configbuilder.test-data :as test-data]
            [com.datastax.configbuilder.build-config :as bc]
            [com.datastax.configbuilder.test-helpers :as helper]
            [cheshire.core :as json]
            [clojure.test :refer :all])
  (:import [com.datastax ConfigBuilder]))

(deftest test-configbuilder
  (let [builder (ConfigBuilder.
                 test-data/definitions-location
                 helper/default-dse-version)
        config-data
        {:cluster-info (bc/->ClusterInfo
                        "test-cluster"
                        "1.2.3.1,1.2.3.2")
         :datacenter-info (bc/->DatacenterInfo
                           "test-dc"
                           false
                           false
                           true) ;; spark-enabled
         :node-info (bc/map->NodeInfo
                     {:name "test-node"
                      :rack "test-rack"
                      :listen_address "1.2.3.4"
                      :broadcast_address "1.2.3.5"
                      :native_transport_address "1.2.3.6"
                      :native_transport_broadcast_address "1.2.3.7"
                      :initial_token "12345"
                      :auto_bootstrap false
                      :agent_version "6.8.0SNAPSHOT"})
         :cassandra-yaml {:disk_access_mode "foo"}}
        config-data-json
        (json/generate-string config-data)]
    (testing "getDefinitions"
      (let [output (json/parse-string (.getDefinitions builder) keyword)]
        (is (every? (partial contains? output) [:dse-yaml :cassandra-yaml]))))
    (testing "buildConfigs"
      (let [output (json/parse-string (.buildConfigs builder config-data-json) keyword)]
        (is (= (get-in config-data [:node-info :listen_address])
               (get-in output [:cassandra-yaml :listen_address])))))
    (testing "renderConfigs"
      (let [output (json/parse-string (.renderConfigs builder config-data-json) keyword)
            cassandra-yaml (some #(when (= "cassandra-yaml" (:config-key %)) %) output)]
        (is (= (get-in config-data [:node-info :listen_address])
               (get-in cassandra-yaml [:contents :listen_address])))
        (is (.contains (:rendered-contents cassandra-yaml)
                       "disk_access_mode: foo"))
        (is (= 20 (count output)))))))
