(ns com.datastax.configbuilder.build-config-test
  (:require [clojure.test :refer :all]
            [com.datastax.configbuilder.test-data :as test-data]
            [com.datastax.configbuilder.build-config :as bc]
            [com.datastax.configbuilder.definitions :as d]
            [slingshot.test :refer :all]))

(deftest test-with-defaults
  (let [configs
        (bc/with-defaults (test-data/get-definitions-data "6.0.2")
          {})]
    ;; Check the total number of config files
    (is (= 20 (count configs)))
    ;; Check some random default values
    (is (= "/var/lib/cassandra/commitlog"
           (get-in configs [:cassandra-yaml :commitlog_directory])))
    (is (= 1.0 (get-in configs [:cassandra-yaml :seed_gossip_probability])))))

(deftest test-build-configs-cassandra-yaml
  (let [node-info {:name                               "node-1"
                   :rack                               "rack-1"
                   :listen_address                     "1.1.1.1"
                   :broadcast_address                  "1.1.1.2"
                   :native_transport_address           "1.1.1.3"
                   :native_transport_broadcast_address "1.1.1.4"
                   :initial_token                      "123XYZ"
                   :auto_bootstrap                     true}
        cluster-info {:name  "test-cluster-1"
                      :seeds "1,2,3"}]

    (testing "cassandra.yaml for DSE 5.1.0"
      (let [built-configs
            (bc/build-configs (test-data/get-definitions-data "5.1.0")
                              {:cluster-info (assoc cluster-info :datastax-version "5.1.0")
                               :node-info    node-info})]
        (testing "old *_address field name conversion"
          (is (= "1.1.1.3" (get-in built-configs [:cassandra-yaml :rpc_address])))
          (is (= "1.1.1.4" (get-in built-configs [:cassandra-yaml :broadcast_rpc_address])))
          (is (every? nil? (map (:cassandra-yaml built-configs)
                                [:native_transport_address :native_transport_broadcast_address]))))))

    (testing "cassandra.yaml for DSE 6.0.2"
      (let [built-configs
            (bc/build-configs (test-data/get-definitions-data "6.0.2")
                              {:cluster-info (assoc cluster-info :datastax-version "6.0.2")
                               :node-info    node-info})]
        (testing "default values"
          (is (= 1.0 (get-in built-configs [:cassandra-yaml :seed_gossip_probability]))))
        (testing "ignored fields"
          (is (nil? (get-in built-configs [:cassandra-yaml :rack]))))
        (testing "enriched fields"
          (doseq [[field-name field-val] (dissoc node-info :name :rack)]
            (is (= field-val (get-in built-configs [:cassandra-yaml field-name]))
                (format "Missing or incorrect value for field %s" field-name)))
          (is (= "test-cluster-1" (get-in built-configs [:cassandra-yaml :cluster_name])))
          (is (= "1,2,3" (get-in built-configs [:cassandra-yaml :seed_provider 0 :parameters 0 :seeds])))
          (is (= "1.1.1.3" (get-in built-configs [:cassandra-yaml :native_transport_address])))
          (is (= "1.1.1.4" (get-in built-configs [:cassandra-yaml :native_transport_broadcast_address])))
          (is (every? nil? (map (:cassandra-yaml built-configs) [:rpc_address :broadcast_rpc_address]))))))))

(deftest test-build-configs-cassandra-env-sh
  (testing "for package installs"
    (let [
          built-configs
          (bc/build-configs (test-data/get-definitions-data "6.0.2")
                            ;; an empty config should inherit the default :jvm-options and :jmx-port
                            {})]
      (is (= "/var/log/cassandra" (get-in built-configs [:cassandra-env-sh :cassandra-log-dir])))
      (is (= 7199
             (get-in built-configs [:jvm-options :jmx-port])  ;; source
             (get-in built-configs [:cassandra-env-sh :jmx-port]))))) ;; destination
  (testing "for tarball installs"
    (let [datacenter-info {:graph-enabled 1
                           :spark-enabled 0
                           :solr-enabled  0}
          built-configs
          (bc/build-configs (test-data/get-definitions-data "6.0.2")
                            {:datacenter-info datacenter-info
                             :install-options {:install-type "tarball"
                                               :install-directory "/opt/dse"}})]
      (is (= datacenter-info
             (select-keys (:cassandra-env-sh built-configs) bc/workload-keys)))
      (is (= "/opt/dse/var/log/cassandra" (get-in built-configs [:cassandra-env-sh :cassandra-log-dir]))))))

(deftest test-build-configs-dse-default
  (let [built-configs
        (bc/build-configs (test-data/get-definitions-data "6.0.2")
                          {})]
    (is (= {:cassandra-user "cassandra"
            :cassandra-group "cassandra"}
           (select-keys (get built-configs :dse-default)
                        [:cassandra-user
                         :cassandra-group])))))

(deftest test-build-configs-cassandra-rackdc-properties
  (let [datacenter-info {:name "dc1"}
        node-info {:rack "rack1"}
        built-configs
        (bc/build-configs (test-data/get-definitions-data "6.0.2")
                          {:datacenter-info datacenter-info
                           :node-info       node-info})]
    (is (= {:dc "dc1" :rack "rack1"}
           (:cassandra-rackdc-properties built-configs)))))

(deftest test-build-configs-no-enrichment
  (testing "configs with no enrichment"
    (let [config-data {:cluster-info    {:name             "test-cluster-1"
                                         :datastax-version "6.0.2"
                                         :seeds            "1,2,3"}
                       :datacenter-info {:name          "test-dc-1"
                                         :graph-enabled true
                                         :spark-enabled false
                                         :solr-enabled  false}
                       :node-info       {:name                               "node-1"
                                         :rack                               "rack-1"
                                         :listen_address                     "1.1.1.1"
                                         :broadcast_address                  "1.1.1.2"
                                         :native_transport_address           "1.1.1.3"
                                         :native_transport_broadcast_address "1.1.1.4"
                                         :initial_token                      "123XYZ"
                                         :auto_bootstrap                     true}}
          enriched-keys #{:cassandra-yaml
                          :cassandra-env-sh
                          :dse-default
                          :cassandra-rackdc-properties
                          :dse-in-sh}
          definitions-data (test-data/get-definitions-data "6.0.2")
          config-data-with-defaults (bc/with-defaults definitions-data config-data)
          enriched-config-data (bc/build-configs definitions-data config-data)
          unmodified-configs (apply dissoc enriched-config-data
                                    (concat enriched-keys bc/model-info-keys))]
      (doseq [[config-key config-value] unmodified-configs]
        ;; If this fails, an enriched config may have been added. If this is the
        ;; case, add it's config-key to enriched-keys above.
        (is (= (get config-data-with-defaults config-key) config-value)
            (str "Expected config to be unmodified, but it has been enriched: " config-key)))
      ;; If this fails and the actual count is...
      ;; a) Greater than expected - a new config-key has likely been added to the config-data map, and
      ;;    that key is not being enriched. Either it should be enriched, or the expected count
      ;;    should be incremented.
      ;; b) Less than expected - a key that used to be unmodified has either been removed or is
      ;;    now an enriched config. In the former case, decrement the expected count. For the
      ;;    latter, add it's config-key to the enriched-keys set above.
      (is (= 16 (count unmodified-configs))))))

(deftest test-build-configs-bad-keys
  ;; What happens when a key exists in config-data for which there is no corresponding key
  ;; in definitions? The answer - an exception is thrown!
  (let [config-data {:cluster-info    {:name             "test-cluster-1"
                                       :datastax-version "6.0.2"
                                       :seeds            "1,2,3"}
                     :datacenter-info {:name          "test-dc-1"
                                       :graph-enabled true
                                       :spark-enabled false
                                       :solr-enabled  false}
                     :node-info       {:name                               "node-1"
                                       :rack                               "rack-1"
                                       :listen_address                     "1.1.1.1"
                                       :broadcast_address                  "1.1.1.2"
                                       :native_transport_address           "1.1.1.3"
                                       :native_transport_broadcast_address "1.1.1.4"
                                       :initial_token                      "123XYZ"
                                       :auto_bootstrap                     true}
                     :bad-config {:a 12}}
        definitions-data (test-data/get-definitions-data "6.0.2")]
    (is (thrown+? [:type :InvalidConfigKeys]
                  (bc/build-configs definitions-data config-data)))))

(deftest test-build-configs-file-paths
  (let [built-configs (bc/build-configs (test-data/get-definitions-data "6.0.2")
                                        {:cassandra-yaml {}
                                         :address-yaml   {}})]
    (is (= "/etc/dse/cassandra/cassandra.yaml"
           (get-in built-configs [:node-info :file-paths :cassandra-yaml])))
    (is (= (:package-path bc/address-yaml-paths)
           (get-in built-configs [:node-info :file-paths :address-yaml])))))

(deftest test-get-custom-dirs
  (testing "no user-defined dirs"
    (is
      (empty?
        (get-in
          (bc/get-custom-dirs {:definitions
                               {:foobie
                                {:display-name "foobie.yaml"
                                 :properties
                                               {:blah_dirs {:type          "list"
                                                            :value_type    "string"
                                                            :default_value "/blah/default"
                                                            :is_directory  true}
                                                :foo_dir   {:type          "string"
                                                            :default_value "/foo/default"
                                                            :is_directory  true}
                                                :bar_dir   {:type "string"}}}}}
                              :foobie
                              {:foobie {:bar_dir "/oh/my/dir"}})
          [:node-info :config-custom-dirs]))))
  (testing "has user-defined dirs"
    (let [config-custom-dirs
          (get-in
            (bc/get-custom-dirs {:definitions
                                 {:foobie
                                  {:display-name "foobie.yaml"
                                   :properties
                                                 {:blah_dirs {:type          "list"
                                                              :value_type    "string"
                                                              :default_value "/blah/default"
                                                              :is_directory  true}
                                                  :foo_dir   {:type          "string"
                                                              :default_value "/foo/default"
                                                              :is_directory  true}
                                                  :bar_dir   {:type         "string"
                                                              :is_directory true}}}}}
                                :foobie
                                {:foobie {:blah_dirs ["/a/b/c" "/d/e/f"]
                                          :foo_dir   "/g/h/i"
                                          :bar_dir   "/j/k/l"}})
            [:node-info :config-custom-dirs])]
      (is (= #{"/a/b/c" "/d/e/f"}
             (set (get-in config-custom-dirs [:foobie [:blah_dirs] :dirs]))))
      (is (= ["/g/h/i"] (get-in config-custom-dirs [:foobie [:foo_dir] :dirs])))
      (is (= ["/j/k/l"] (get-in config-custom-dirs [:foobie [:bar_dir] :dirs])))))
  (testing "for tarball installs"
    (let [definitions-data (update (test-data/get-definitions-data "6.7.2")
                                   :definitions
                                   d/use-tarball-defaults)
          config-custom-dirs
          (get-in
           (bc/get-custom-dirs definitions-data
                               :cassandra-env-sh
                               {:cassandra-env-sh {:cassandra-log-dir "var/log/cassandra"}})
           [:node-info :config-custom-dirs])]
      (is (nil? config-custom-dirs)))))

(deftest test-dse-version-60-or-greater?
  (is (bc/dse-version-60-or-greater? "6.0.0"))
  (is (bc/dse-version-60-or-greater? "7.1.1"))
  (is (bc/dse-version-60-or-greater? "51.0"))
  (is (bc/dse-version-60-or-greater? "7.0-fedex"))
  (is (not (bc/dse-version-60-or-greater? "5.1.1")))
  (is (not (bc/dse-version-60-or-greater? "some-string-7.0")))
  (is (not (bc/dse-version-60-or-greater? nil)))
  (is (not (bc/dse-version-60-or-greater? ""))))


(deftest test-fully-qualify-paths
  (let [definitions-data (test-data/get-definitions-data)
        config-key :cassandra-yaml]
    (testing "No-op for package installs"
      (let [config-data {:install-options
                         {:install-type "package"}
                         :cassandra-yaml
                         {:data_file_directories ["/var/data1" "/var/data2"]
                          :commitlog_directory "/var/commitlog"
                          :client_encryption_options
                          {:enabled true
                           :keystore "/etc/dse/keystore"}}}
            result (bc/fully-qualify-paths definitions-data config-key config-data)]
        (is (= result config-data)
            "Should not modify paths for package install")))
    (testing "Tarball installs"
      (let [config-data {:install-options
                         {:install-type "tarball"
                          :install-directory "/opt/dse"}
                         :cassandra-yaml
                         {:data_file_directories ["var/data1" "var/data2" "/var/data3"]
                          :commitlog_directory "var/commitlog"
                          :client_encryption_options
                          {:enabled true
                           :keystore "etc/dse/keystore"
                           :truststore "/etc/dse/truststore"}}}
            result (bc/fully-qualify-paths definitions-data config-key config-data)]
        (is (= ["/opt/dse/var/data1"
                "/opt/dse/var/data2"
                "/var/data3"]
               (get-in result [:cassandra-yaml :data_file_directories]))
            "Should fully-qualify relative paths in vectors")
        (is (= "/opt/dse/var/commitlog"
               (get-in result [:cassandra-yaml :commitlog_directory]))
            "Should fully-qualify relative directory paths")
        (is (= "/opt/dse/etc/dse/keystore"
               (get-in result [:cassandra-yaml :client_encryption_options :keystore]))
            "Should fully-qualify relative file paths")
        (is (= "/etc/dse/truststore"
               (get-in result [:cassandra-yaml :client_encryption_options :truststore]))
            "Should not transform paths that are already absolute")))))
