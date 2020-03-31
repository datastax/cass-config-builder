;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.render-test
  (:require [com.datastax.configbuilder.render :as r]
            [com.datastax.configbuilder.test-data :as test-data]
            [com.datastax.configbuilder.test-helpers :as helper]
            [slingshot.test :refer :all]
            [clojure.test :refer :all]))

(deftest test-config-keys-to-render
  (let [config-data {:datacenter-info {:spark-enabled false}
                     :spark-env-sh {:a 1}
                     :dse-yaml {:b 2}}]
    (testing "spark not enabled"
      (is (= #{:dse-yaml :datacenter-info}
             (set (r/config-keys-to-render config-data)))))
    (testing "spark enabled"
      (is (= #{:spark-env-sh
               :dse-yaml
               :datacenter-info}
             (set (r/config-keys-to-render
                   (assoc-in config-data [:datacenter-info :spark-enabled] true))))))))

(defn simple-config-test
  [rendered-config-info pattern]
  (testing (str "Renders " (:config-key rendered-config-info))
    (let [contents (:rendered-contents rendered-config-info)]
      (is (-> (re-matcher pattern contents)
              re-find
              boolean)
          (str "expected_to_match=" pattern
               " rendered-contents=" contents)))))

(deftest test-render-config-default
  (testing "YAML renderer"
    (let [rendered-info (r/render-config
                         (test-data/get-definitions-data helper/default-dse-version)
                         :dse-yaml
                         {:node-info {:file-paths {:dse-yaml "/etc/dse/dse.yaml"}}
                          :dse-yaml {:a 1}})]
      (is (= :dse-yaml (:config-key rendered-info)))
      (is (= "dse.yaml" (:display-name rendered-info)))
      (is (= "/etc/dse/dse.yaml" (:file-path rendered-info)))
      (is (= {:a 1} (:contents rendered-info)))
      (simple-config-test rendered-info #"a: 1"))))


(deftest test-render-config-address-yaml
  (let [rendered-info
        (r/render-config
         {:definitions {}}
         :address-yaml
         {:node-info {:agent_version "1.2.3"
                      :file-path "/foo/bar"}
          :address-yaml {:use-ssl true
                         :stomp-interface "1.2.3.4"}})]
    (is (= :address-yaml (:config-key rendered-info)))
    (is (= "address.yaml" (:display-name rendered-info)))
    (is (= {:use-ssl true
            :stomp-interface "1.2.3.4"}
           (:contents rendered-info)))
    (is (= "1.2.3" (:opscd-agent-version rendered-info)))
    (simple-config-test rendered-info #"stomp-interface: 1.2.3.4")
    (simple-config-test rendered-info #"use-ssl: true")))

(deftest test-render-config-package-proxy
  (let [definitions-data (test-data/get-definitions-data helper/default-dse-version)
        rendered-info
        (r/render-config definitions-data :package-proxy
                         {:package-proxy
                          {:enabled true
                           :protocol "http"
                           :host "example.com"
                           :port "80"
                           :authentication-required false}})]
    (is (= "http://example.com:80" (:rendered-contents rendered-info)))))

(deftest test-render-configs-bad-key
  ;; What happens when a key exists in config-data for which there is no corresponding key
  ;; in definitions? The answer - an exception is thrown!
  (let [definitions-data {:definitions {}
                          :definitions-location "/tmp"
                          :datastax-version helper/default-dse-version}
        config-data {:bad-key {:a 12}}]
    (is (thrown+? [:type :MissingDefinitions]
                  (doall (r/render-configs definitions-data config-data)))))
  )
