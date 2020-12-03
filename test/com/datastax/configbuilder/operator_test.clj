;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.operator-test
  (:require [com.datastax.configbuilder.operator :as sut]
            [com.datastax.configbuilder.test-data :as test-data]
            [com.datastax.configbuilder.test-helpers :as helper]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer :all]
            [clojure.set :as set]))

(use-fixtures
  :each
  (helper/temp-dir-fixture "configbuilder-operator-"))

(deftest test-make-configs
  (testing "call make-configs with bad input"
    (try
      (sut/make-configs
       {:pod-ip "127.0.0.1"
        :config-file-data "{\"cluster-info\""
        :product-name "dse"
        :product-version "6.0.11"
        :rack-name "default"
        :config-output-directory (str @helper/temp-dir)
        :definitions-location test-data/definitions-location})
      (is false "Malformed input was not detected")
      (catch Exception e
        (is (= "Unexpected end-of-input within/between Object entries\n at [Source: (StringReader); line: 1, column: 31]"
               (.getMessage e))
            "Wrong error thrown"))))
  (testing "ensure all files generated"
    (sut/make-configs
     {:pod-ip "127.0.0.1"
      :config-file-data "{\"cluster-info\":{\"name\":\"test-cluster\",\"seeds\":\"1.2.3.1,1.2.3.2\"},\"datacenter-info\":{\"name\":\"test-dc\",\"graph-enabled\":false,\"solr-enabled\":false,\"spark-enabled\":false},\"node-info\":{\"name\":\"test-node\",\"rack\":\"test-rack\",\"listen_address\":\"1.2.3.4\",\"native_transport_address\":\"1.2.3.6\",\"native_transport_broadcast_address\":\"1.2.3.7\",\"initial_token\":\"12345\",\"auto_bootstrap\":false,\"agent_version\":\"6.8.0SNAPSHOT\"},\"cassandra-yaml\":{\"disk_access_mode\":\"foo\"}}"
      :product-name "dse"
      :product-version "6.0.11"
      :rack-name "default"
      :config-output-directory (str @helper/temp-dir)
      :definitions-location test-data/definitions-location})
    (is (= #{} (set/difference
      #{"logback.xml" "cassandra-rackdc.properties" "cassandra-env.sh" "dse-env.sh" "cassandra.yaml" "dse.yaml" "10-write-prom.conf" "jvm.options"}
      (into #{} (.list (.toFile @helper/temp-dir))))))))
