;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.operator-test
  (:require [com.datastax.configbuilder.operator :as sut]
            [com.datastax.configbuilder.test-helpers :as helper]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer :all]))

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
        :definitions-location "cass-config-definitions/resources"})
      (is false "Malformed input was not detected")
      (catch Exception e
        (is (= "Unexpected end-of-input within/between Object entries\n at [Source: (StringReader); line: 1, column: 31]"
               (.getMessage e))
            "Wrong error thrown"))))
  (testing "simple rendering test reading input from the environment"
    (sut/make-configs
     {:pod-ip "127.0.0.1"
      :config-file-data "{\"cluster-info\":{\"name\":\"test-cluster\",\"seeds\":\"1.2.3.1,1.2.3.2\"},\"datacenter-info\":{\"name\":\"test-dc\",\"graph-enabled\":false,\"solr-enabled\":false,\"spark-enabled\":false},\"node-info\":{\"name\":\"test-node\",\"rack\":\"test-rack\",\"listen_address\":\"1.2.3.4\",\"native_transport_address\":\"1.2.3.6\",\"native_transport_broadcast_address\":\"1.2.3.7\",\"initial_token\":\"12345\",\"auto_bootstrap\":false,\"agent_version\":\"6.8.0SNAPSHOT\"},\"cassandra-yaml\":{\"disk_access_mode\":\"foo\"}}"
      :product-name "dse"
      :product-version "6.0.11"
      :rack-name "default"
      :config-output-directory (str @helper/temp-dir)
      :definitions-location "cass-config-definitions/resources"})
    (loop [files (.list (.toFile @helper/temp-dir))]
      (let [current-file (first files)
            diff-result (sh "diff"
                            (str @helper/temp-dir "/" current-file)
                            (str "test/data/configs/dse-6.0.11/" current-file))]
        (is (= 0 (:exit diff-result))
            (str "Rendered output for " current-file " does not match expected output.\nDiff:\n" (:out diff-result)))))))
