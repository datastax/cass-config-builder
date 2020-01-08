(ns com.datastax.configbuilder.definitions-test
  (:require [clojure.test :refer :all]
            [clojure.data :as data]
            [clojure.string :refer [split split-lines]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [lcm.utils.yaml :as yaml]
            [lcm.utils.version :as version]
            [slingshot.test :refer :all]
            [com.datastax.configbuilder.test-data :refer [definitions-location]]
            [com.datastax.configbuilder.test-helpers :as helper]
            [com.datastax.configbuilder.definitions :refer :all]))

;; These fields are not in upstream, but we must support them anyway. :(
(def undocumented-fields #{:disk_access_mode})

(deftest test-pull-up-conditional
  (let [parent {:child1 {:type "string"
                         :default_value "foo"}
                :child2 {:type "string"
                         :depends :child1
                         :conditional [{:eq "foo" :default_value 1}
                                       {:eq "bar" :default_value 2}]}
                :child3 {:type "string"
                         :depends :child1
                         :conditional [{:eq "bar"}]}}
        child1 (-> parent :child1)
        child2 (-> parent :child2)
        child3 (-> parent :child3)]
    (is (= 1 (-> (pull-up-conditional parent [:child2 child2]) :child2 :default_value)))
    (is (nil? (-> (pull-up-conditional parent [:child3 child3]) :child3)))
    (is (= parent (pull-up-conditional parent [:child1 child1])))))

(deftest test-order-transitive-dependencies
  (let [fields {:b {:depends :a}
                :d {:depends :c}
                :e {:depends :c}
                :c {:depends :b}
                :a {}}
        results (order-transitive-dependencies fields)]
    (is (= :a (ffirst results)))
    (is (= :b (ffirst (drop 1 results))))
    (is (= :c (ffirst (drop 2 results))))
    (is (= [:d :e] (map first (drop 3 results)))))
  (testing "cycle detection"
    (let [fields {:d {}
                  :b {:depends :a}
                  :c {:depends :b}
                  :a {:depends :c}}]
      (is (thrown+? [:type :CyclicDependency]
                    (order-transitive-dependencies fields)))))
  (testing "a real example"
    (let [fields {:enabled {:type "boolean", :default_value false},
                  :keystore
                           {:type "string",
                            :default_value "resources/dse/conf/.keystore",
                            :depends :enabled},
                  :cipher_suites
                           {:type "list",
                            :value_type "string"
                            :default_value
                            ["TLS_RSA_WITH_AES_128_CBC_SHA"
                             "TLS_RSA_WITH_AES_256_CBC_SHA"
                             "TLS_DHE_RSA_WITH_AES_128_CBC_SHA"
                             "TLS_DHE_RSA_WITH_AES_256_CBC_SHA"
                             "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"
                             "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"],
                            :depends :enabled},
                  :keystore_password
                           {:type "string",
                            :password true,
                            :default_value "cassandra",
                            :depends :enabled},
                  :store_type
                           {:type "string",
                            :default_value "JKS",
                            :depends :enabled},
                  :algorithm
                           {:type "string",
                            :default_value "SunX509",
                            :depends :enabled},
                  :protocol
                           {:type "string",
                            :default_value "TLS",
                            :depends :enabled},
                  :require_client_auth
                           {:type "boolean",
                            :default_value false,
                            :depends :enabled},
                  :truststore
                           {:type "string",
                            :depends :require_client_auth,
                            :default_value "resources/dse/conf/.truststore"}
                  :truststore_password
                           {:type "string",
                            :depends :require_client_auth,
                            :password true,
                            :default_value "cassandra"}}
          ordered (order-transitive-dependencies fields)]
      (is (= :enabled (ffirst ordered))))))

(deftest test-sanitize
  (let [definition
        {:properties
         {:a {:type "string"
              :default_value "a"}
          :b {:type "dict"
              :depends :a
              :conditional [{:eq "a"
                             :fields {:aa {:type "int"
                                           :default_value 2}
                                      :ab {:type "boolean"
                                           :default_value true
                                           :depends :aa
                                           :conditional [{:eq 2}]}
                                      :ac {:type "boolean"
                                           :default_value false
                                           :depends :aa
                                           :conditional [{:eq 6}]}}}
                            {:eq "b"
                             :fields {:ba {:type "int"
                                           :default_value 5}
                                      :bb {:type "float"
                                           :default_value 0.4}}}]}
          :c {:type "dict"
              :fields {:ca {:type "string"
                            :default_value "c"}}
              :depends :a
              :conditional [{:eq "a"}]}
          :d {:type "string"
              :default_value "d"
              :depends :a
              :conditional [{:eq "a"}]}
          :e {:type "string"
              :default_value "e"
              :depends :a
              :conditional [{:eq "xxxx"}]}}}
        sanitized (sanitize definition)]
    (is (= 2 (-> sanitized :properties :b :fields :aa :default_value)))
    (is (true? (-> sanitized :properties :b :fields :ab :default_value)))
    (is (= "c" (-> sanitized :properties :c :fields :ca :default_value)))
    (is (= "d" (-> sanitized :properties :d :default_value)))
    (is (nil? (-> sanitized :properties :e)))
    (is (nil? (-> sanitized :properties :b :fields :ba)))
    (is (nil? (-> sanitized :properties :b :fields :ac))))
  (testing "deeply nested conditionals"
    (let [definition
          {:properties
           {:a {:type "dict"
                :fields
                      {:enabled {:type "boolean" :default_value false}
                       :not_shown {:type "string" :default_value "Hulk"
                                   :depends :enabled
                                   :conditional [{:eq true}]}
                       :also_not_shown {:type "string" :default_value "Fury"
                                        :depends :not_shown
                                        :conditional [{:eq "Loki"}]}}}
            :b {:type "dict"
                :fields
                      {:enabled {:type "boolean" :default_value true}
                       :shown {:type "string" :default_value "Thor"
                               :depends :enabled
                               :conditional [{:eq true}]}
                       :also_shown {:type "string" :default_value "Iron Man"
                                    :depends :shown
                                    :conditional [{:eq "Thor"}]}
                       :not_shown {:type "string" :default_value "Captain America"
                                   :depends :shown
                                   :conditional [{:eq "Black Widow"}]}}}}}
          sanitized (sanitize definition)]
      (is (nil? (-> sanitized :properties :a :not_shown)))
      (is (nil? (-> sanitized :properties :a :also_not_shown)))
      (is (= "Thor" (-> sanitized :properties :b :fields :shown :default_value)))
      (is (= "Iron Man" (-> sanitized :properties :b :fields :also_shown :default_value)))
      (is (nil? (-> sanitized :properties :b :fields :not_shown))))))

(deftest test-replace-with-default
  (let [thing {:type "list"
               :value_type "dict"
               :fields {:a {:type "string" :default_value "foo"}
                        :b {:type "int" :default_value 1}}
               :default_value [{:a "blah" :b 42}]}]
    (is (= [{:a "blah" :b 42}] (replace-with-default thing)))))

(deftest test-metadata-valid?
  (is (= (metadata-valid? {})
         false))
  (is (= (metadata-valid? {:foo :bar})
         true))
  (is (= (metadata-valid? {})
         false))
  (is (= (metadata-valid? {:foo :bar})
         true)))

(deftest test-definition-defaults
  (testing "get defaults for cassandra-yaml (for mixing with config profiles)"
    (let [definition (get-field-metadata definitions-location :cassandra-yaml helper/default-dse-version)
          defaults (definition-defaults definition)]

      (testing "top-level defaults"
        (is (= 2 (:max_hints_delivery_threads defaults)))
        (is (= "stop" (:commit_failure_policy defaults)))
        (is (false? (:cross_node_timeout defaults)))
        (is (= ["/var/lib/cassandra/data"] (:data_file_directories defaults)))
        (is (= 0.1 (:dynamic_snitch_badness_threshold defaults))))

      (testing "nested defaults"
        (is (false?
              (-> defaults :client_encryption_options :enabled))))

      (testing "conditionals"
        (is (= "periodic" (:commitlog_sync defaults)))
        (is (not (contains? defaults :commitlog_sync_batch_window_in_ms)))
        (is (= 10000 (:commitlog_sync_period_in_ms defaults)))

        (is (= "org.apache.cassandra.cache.OHCProvider" (:row_cache_class_name defaults)))
        (is (not (contains? defaults :otc_coalescing_window_us))))

      (testing "parents and children"
        ;; 2 types of parent. One has parent_options, the other appears to assume the parent field is a boolean.
        (is (not (contains? (:client_encryption_options defaults) :require_client_auth)))
        (is (not (contains? (:client_encryption_options defaults) :truststore)))
        (is (not (contains? (:client_encryption_options defaults) :truststore_password)))
        (is (= "periodic" (:commitlog_sync defaults)))
        (is (not (contains? defaults :commitlog_sync_batch_window_in_ms))))))

  (testing "get defaults for dse-yaml"
    (let [definition (get-field-metadata definitions-location :dse-yaml helper/default-dse-version)
          defaults (definition-defaults definition)]
      (testing "top-level defaults"
        (is (= 1024 (:back_pressure_threshold_per_core defaults)))
        (is (= "off" (:cql_solr_query_paging defaults))))
      (testing "nested defaults"
        (is (true? (-> defaults :cql_slow_log_options :enabled)))
        (is (false? (-> defaults :audit_logging_options :enabled))))
      (testing "conditionals"
        (is (nil? (-> defaults :audit_logging_options :cassandra_audit_writer_options)))
        (is (nil? (:config_encryption_key_name defaults))))
      (testing "complex defaults"
        (let [defaults (definition-defaults
                         {:properties
                          {:data_directories
                           {:type "list"
                            :value_type "dict"
                            :default_value [{:dir "/var/lib/dsefs/data",
                                             :storage_weight 1.0,
                                             :min_free_space 5368709120}]
                            :fields
                            {:dir {:type "string"},
                             :storage_weight {:type "float" :default_value 1.0},
                             :min_free_space {:type "int", :default_value 5368709120}}}}})]
          (is (= [{:dir "/var/lib/dsefs/data"
                   :storage_weight 1.0
                   :min_free_space 5368709120}]
                 (:data_directories defaults))))))))

(deftest test-get-field-metadata
  (is (map?
        (get-field-metadata definitions-location :cassandra-yaml helper/default-dse-version)))
  (is (map?
        (get-field-metadata definitions-location :dse-yaml helper/default-dse-version)))
  (is (nil? (get-field-metadata definitions-location :cassandra-yaml helper/invalid-dse-version)))
  (is (thrown+? [:type :DefinitionException]
        (get-field-metadata definitions-location :foo-yaml helper/default-dse-version))))

(deftest test-get-defaults
  (let [defaults (get-defaults definitions-location helper/default-dse-version)]
    (is (map? (:cassandra-yaml defaults)))
    (is (map? (:dse-yaml defaults)))))

(deftest test-sanitize-definition-parameter
  (is (= (sanitize-definition-parameter :dse)
         "dse"))
  (is (= (sanitize-definition-parameter :DSE)
         "dse"))
  (is (= (sanitize-definition-parameter ":dse")
         "dse"))
  (is (= (sanitize-definition-parameter ":DSE")
         "dse"))
  (is (= (sanitize-definition-parameter "dse")
         "dse"))
  (is (= (sanitize-definition-parameter "DSE")
         "dse")))

(deftest test-get-definitions-file-suffix
  (is (= (get-definitions-file-suffix :template)
         ".template"))
  (is (= (get-definitions-file-suffix :field-metadata)
         ".edn"))
  (is (= (get-definitions-file-suffix :transforms)
         "-transforms.edn")))

(deftest test-build-definitions-filename
  (is (= (build-definitions-filename
           :cassandra-rackdc-properties
           "1.2.3"
           :template)
         "cassandra-rackdc-properties-dse-1.2.3.template"))
  (is (= (build-definitions-filename
           :cassandra-yaml
           helper/default-dse-version
           :field-metadata)
         (str "cassandra-yaml-dse-"
              helper/default-dse-version
              ".edn"))))

(deftest test-get-definitions-file
  (is (.contains (str
                   (get-definitions-file
                     definitions-location
                     :cassandra-yaml
                     helper/default-dse-version
                     :field-metadata))
                 (str "definitions/resources/cassandra-yaml/dse/cassandra-yaml-dse-"
                      helper/default-dse-version
                      ".edn"))))

(deftest test-get-template-filename
  (is (thrown+?
        [:type :DefinitionException]
        (get-template-filename
          {:datastax-version helper/default-dse-version
           :definitions {:cassandra-yaml {:renderer {:renderer-type :yaml}}}}
          :cassandra-yaml))))

(deftest test-get-template
  (is (thrown+?
        [:type :DefinitionException]
        (get-template
          {:datastax-version helper/default-dse-version
           :definitions-location definitions-location
           :definitions {:cassandra-yaml {:renderer {:renderer-type :yaml}}}}
          :cassandra-yaml)))
  (is (seq (get-template
             {:datastax-version helper/default-dse-version
              :definitions-location definitions-location
              :definitions (get-all-definitions-for-version definitions-location helper/default-dse-version)}
             :cassandra-env-sh))))

(deftest test-get-all-definitions-for-version
  (testing "all definitions for default dse version"
    (let [all-defs (get-all-definitions-for-version definitions-location helper/default-dse-version)]
      (is (> (count all-defs) 0))
      (is (contains? all-defs :cassandra-yaml))
      ;; Ensure that a def with a () transform does not appear
      (is (not (contains? all-defs :10-statsd-conf)))
      (let [all-vals (vals all-defs)]
        (is (every?
              #(every? (set (keys %)) [:renderer])
              all-vals)
            (str "A definition for DSE version " helper/default-dse-version " is missing a renderer."))
        (is (every?
              #(every? (set (keys %)) [:properties])
              all-vals)
            (str "A definition for DSE version " helper/default-dse-version " is missing properties"))
        (is (every?
              #(every? (set (keys %)) [:groupings])
              all-vals)
            (str "A definition for DSE version " helper/default-dse-version " is missing groupings"))))))

(defn test-yaml-for-version
  "Common test method for cassandra.yaml and dse.yaml"
  [datastax-version config-id ignore-fields]
  ;; We should test that...
  ;; - all keys present in yaml file are present in definition
  ;; - verify required fields (top-level)
  (let [filenames {:cassandra-yaml "cassandra.yaml"
                   :dse-yaml "dse.yaml"}
        definition
        (get-field-metadata definitions-location
                            config-id
                            datastax-version)
        yaml-string (slurp (format "test/data/configs/dse-%s/%s" datastax-version (filenames config-id)))
        yaml-data (yaml/parse yaml-string :keywords true)
        yaml-keys (set (keys yaml-data))
        diffs (data/diff (set (keys (:properties definition))) yaml-keys)]
    ;; Make sure there are no fields in yaml that aren't in definition
    ;; We must make sure to remove any ignored fields
    (testing (format "uncommented fields in %s appear in the definition" config-id)
      (let [missing-fields (apply disj (second diffs) ignore-fields)]
        (is (empty? missing-fields)
            (str "There are fields missing from the definition file: " missing-fields))))
    ;; Check things not in yaml data. See if there is a commented property.
    (testing (format "make sure optional fields are commented in the %s file" config-id)
      (doseq [prop (filter (complement (set ignore-fields)) (first diffs))]
        (when (not (contains? undocumented-fields prop))
          (let [re (re-pattern (str "^(#\\s*)?" (name prop) ":"))]
            (is (not-empty
                  (filter #(re-find re %)
                          (split-lines yaml-string)))
                (str "could not find commented field: " (name prop)))))))))

(deftest test-dse-6-7-0-yaml
  (test-yaml-for-version "6.7.0" :cassandra-yaml #{:seeds :seed_provider :listen_address :rpc_address :commitlog_sync_period_in_ms :cluster_name})
  (test-yaml-for-version "6.7.0" :dse-yaml #{:max_memory_to_lock_fraction :config_encryption_key_name}))

(deftest test-default-dse-version-dse-yaml-definition
  (let [definition
        (get-field-metadata definitions-location
                            :dse-yaml
                            helper/default-dse-version)]
    (is (nil? (-> definition :properties :node_health :fields :enabled)))
    (is (= 100 (-> definition
                    :properties
                    :solr_slow_sub_query_log_options
                    :fields
                    :threshold_ms
                    :default_value)))))

;; Helper fns for checking all definitions files for correctness

(defn check-transform-versions
  "Checks that a list of config-ids each have a transform file and that the
  versions for the transforms align with versions.edn"
  [config-ids opsc-version dse-versions all-opsc-dse-versions]
  (let [;; Transform filenames aren't dependent on version-number, so use a
        ;; bogus dse version
        all-transforms-filenames
        (map (fn [config-id]
               (build-definitions-filename config-id
                                           :bogus-dse-version
                                           :transforms))
             config-ids)

        all-transforms-paths
        (map (fn [config-id transforms-filename]
               (.getCanonicalFile (io/file definitions-location
                                           (name config-id)
                                           "dse"
                                           transforms-filename)))
             config-ids
             all-transforms-filenames)
        transforms-paths-by-config-id (zipmap config-ids all-transforms-paths)

        all-transforms
        (map (fn [transform-path]
               (->> transform-path
                    slurp
                    edn/read-string
                    (apply array-map)))
             all-transforms-paths)

        transforms-by-config-id
        (zipmap config-ids all-transforms)

        version-id-tuples (for [dse-version dse-versions
                                config-id config-ids]
                            [dse-version config-id])

        all-config-file-valids
        (map (fn [tuple]
               (let [dse-version (first tuple)
                     config-id (second tuple)]
                 (config-file-valid? definitions-location config-id dse-version)))
             version-id-tuples)

        config-valid-by-version-id-tuple
        (zipmap version-id-tuples all-config-file-valids)]
    (doseq [config-id config-ids]
      (let [transforms-path (get transforms-paths-by-config-id config-id)
            transforms (get transforms-by-config-id config-id)]
        (testing "transforms exist"
          (is (.isDirectory (io/file definitions-location))
              (str "Expected the definitions directory to exist and be a directory: "
                   definitions-location))
          (is (.isFile (io/file transforms-path))
              (str "Expected the transforms file to exist and be a plain file: "
                   transforms-path)))
        (testing "Every transform has a versions.edn version"
          (let [all-dse-versions (->> all-opsc-dse-versions
                                      vals
                                      (map :dse)
                                      flatten
                                      set)]
            (doseq [transform-version (keys transforms)]
              ;; Not every version of opscenter will make use of every transform.
              ;; But every transform should be used by SOME version of OpsCenter
              ;; or it's cruft and should be removed.
              (is (contains? all-dse-versions transform-version)
                  (format (str "Found transform for version %s in %s but "
                               "that version isn't present in versions.edn")
                          transform-version
                          transforms-path)))))))))

(defmulti check-renderer (fn [metadata config-id version]
                           (-> metadata :renderer :renderer-type)))

(defmethod check-renderer :yaml
  [metadata config-id version])

(defmethod check-renderer :template
  [metadata config-id version]
  ;; ensure all metadata properties are being used in the template
  ;; helps catch typos.
  (let [exclusions #{} ;; exclude these from the checks.
        property-fields (keys (:properties metadata))
        profile-context {:definitions {config-id metadata}
                         :definitions-location definitions-location
                         :datastax-version version}
        template-file (get-template-filename profile-context config-id)
        template (get-template profile-context config-id)]
    ;; Only ensure all fields are used if template_iterables is not used
    (if (not (.contains template "template_iterables"))
      (doseq [field property-fields]
        (is (.contains template (name field))
            (format "Template '%s' does not reference property '%s' for version '%s'"
                    template-file (name field) version))))))

(defmethod check-renderer :default
  [metadata config-id version]
  (is nil (format "Definition file for %s version %s does not have a renderer-type specified!"
                  config-id version)))

(defn check-groups
  "Checks the top-level keys against a flattened set of the fields in groups. Should be the same."
  [metadata config-id version]
  (let [group-fields (->> (:groupings metadata)
                          (mapcat :list)
                          (map keyword))
        property-fields (keys (:properties metadata))
        [missing-from-group missing-properties] (data/diff (set property-fields) (set group-fields))]
    (is (empty? missing-from-group)
        (format "Definition %s v%s has properties that are not assigned to a group"
                config-id version))
    (is (empty? missing-properties)
        (format "Definition %s v%s is missing properties: %s"
                config-id version missing-properties))
    (is (= (count group-fields) (count (set group-fields)))
        (format "Some property is repeated more than once in the groupings for definition %s v%s"
                config-id version))
    (is (= (count property-fields) (count (set property-fields)))
        (format "Some property is defined more than once for definition %s v%s"
                config-id version))))

(defn make-check-recursive
  "Any assertions that need to be applied to all fields (even
  deeply nested ones) can leverage this The return value will be
  a normal check-* style function. The provided function must
  take the following arguments:

  [metadata config-id dse-version field-name field-metadata]"
  [check-fn]
  (fn [metadata config-id dse-version]
    (letfn [(check-at-level [fields]
              (doseq [[field-name field-metadata] fields]
                ;; Run the check-fn on each field at this level
                (check-fn metadata config-id dse-version field-name field-metadata))
              (doseq [next-level (filter :fields (vals fields))]
                ;; Do the same thing for every field at the next level
                (check-at-level (:fields next-level))))]
      (check-at-level (:properties metadata)))))

(defn check-dependencies [metadata config-id version]
  (letfn [(check-depends-at-level [fields]
            (doseq [[name field-metadata] (filter dependent-field? fields)]
              (is (contains? fields (:depends field-metadata))
                  (format "Field %s depends on non-existent field %s for definition %s v%s"
                          name (:depends field-metadata) config-id version)))
            (doseq [parent (filter :fields (vals fields))]
              (check-depends-at-level (:fields parent))))]
    (check-depends-at-level (:properties metadata))))

(defn check-conf-paths [metadata config-id version]
  "This ensures each version has package-path and tarball-path."
  (is (contains? metadata :package-path)
      (format ":package-path is not defined for definition %s v%s"
              config-id version))
  (is (contains? metadata :tarball-path)
      (format ":tarball-path is not defined for definition %s v%s"
              config-id version)))

(defn check-ui-visibility [metadata config-id version]
  (is (contains? metadata :ui-visibility)
      (format ":ui-visibility is not defined for definition %s v%s"
              config-id version))
  (is ((set [:editable :hidden]) (:ui-visibility metadata))
      (format ":ui-visibility values %s is not allowed for definition %s v%s"
              (:ui-visibility metadata) config-id version)))

(defn check-auth [metadata config-id version]
  "Run various authentication sanity-checks.
  The main purpose is to assert that DSE has authentication enabled by default.
  "
  (let [def-defaults (definition-defaults metadata)]
    (cond
      ;; DSE 5.1.2 and greater should default to auth being enabled
      (version/version-is-at-least "5.1.2" version)
      (cond
        (when (= config-id :dse-yaml)
          (is (= true (get-in def-defaults [:authentication_options :enabled]))
              (format "DSE version %s does not have :authentication_options :enabled defaulted to true in :dse-yaml."
                      version)))
        (when (= config-id :cassandra-yaml)
          (is (= "com.datastax.bdp.cassandra.auth.DseAuthenticator" (get-in def-defaults [:authenticator]))
              (format "DSE version %s does not have :authenticator defaulted to com.datastax.bdp.cassandra.auth.DseAuthenticator in cassandra-yaml."
                      version))))

      ;; Sadly, versions 5.1.0 and 5.1.1 do not have authentication on by default
      ;; This cannot be fixed because it could negatively impact customers
      ;; with existing clusters.  See OPSC-12443 for more details.
      (version/version-is-at-least "5.1.0" version)
      (cond
        (when (= config-id :dse-yaml)
          (is (= false (get-in def-defaults [:authentication_options :enabled]))
              (format "DSE version %s does not have :authentication_options :enabled defaulted to false in :dse-yaml and this version should be false."
                      version)))
        (when (= config-id :cassandra-yaml)
          (is (= "com.datastax.bdp.cassandra.auth.DseAuthenticator" (get-in def-defaults [:authenticator]))
              (format "DSE version %s does not have :authenticator defaulted to com.datastax.bdp.cassandra.auth.DseAuthenticator in cassandra-yaml."
                      version))))

      ;; DSE 5.0.1 up to but not including 5.1.0, have auth by default
      (version/version-is-at-least "5.0.1" version)
      (cond
        (when (= config-id :dse-yaml)
          (is (= true (get-in def-defaults [:authentication_options :enabled]))
              (format "DSE version %s does not have :authentication_options :enabled defaulted to true in :dse-yaml."
                      version)))
        (when (= config-id :cassandra-yaml)
          (is (= "com.datastax.bdp.cassandra.auth.DseAuthenticator" (get-in def-defaults [:authenticator]))
              (format "DSE version %s does not have :authenticator defaulted to com.datastax.bdp.cassandra.auth.DseAuthenticator in cassandra-yaml."
                      version))))

      ;; DSE versions below 5.0.1 should have PasswordAuthenticator enabled
      :else
      (when (= config-id :cassandra-yaml)
        (is (= "PasswordAuthenticator" (get-in def-defaults [:authenticator]))
            (format "DSE version %s does not have :authenticator defaulted to PasswordAuthenticator in cassandra-yaml."
                    version))))))

(defn check-field-order
  "Checks that fields mentioned in the :order key actually exist (no typos)"
  [metadata config-id version]
  (letfn [(check-order-at-level [fields]
            (doseq [[name field-metadata] (filter (comp :order second) fields)]
              (is (contains? field-metadata :fields)
                  (format "Field %s has an :order key but no :fields key! for definition %s v%s"
                          name config-id version))
              (let [field-key? (set (keys (:fields field-metadata)))]
                (doseq [order-field (:order field-metadata)]
                  (is (field-key? order-field)
                      (format "Field %s lists %s in it's :order, but does not have a child by that name. For definition %s v%s"
                              name order-field config-id version)))))
            (doseq [parent (filter :fields (vals fields))]
              (check-order-at-level (:fields parent))))]
    (check-order-at-level (:properties metadata))))

(defn check-has-order
  "Checks that all dict fields have an :order defined with at least 1 item.
  At some point we may require that all fields appear in :order vectors, but
  that makes transforms awkward when a new field is added and we don't care if
  it falls to the end of the order."
  [metadata config-id version]
  (letfn [(check-dict-has-order [field-name dict-meta]
            (is (seq (:order dict-meta))
                (format "Field %s must have an :order key with at least one entry for definition %s v%s.\nHere is a alphabetical ordering: %s"
                        field-name config-id version
                        (-> dict-meta :fields keys sort vec))))
          (check-has-order-at-level [fields]
            ;; Any field that has :fields (children) should have an :order defined
            (doseq [[name field-metadata] (filter (comp :fields second) fields)]
              (check-dict-has-order name field-metadata))
            (doseq [parent (filter :fields (vals fields))]
              (check-has-order-at-level (:fields parent))))]
    (check-has-order-at-level (:properties metadata))))

(defn check-property-types
  "Checks that the 'type' attribute is valid everywhere."
  [metadata config-id version]
  (letfn [(check-property-types-at-level [fields]
            (doseq [[name field-metadata] fields]
              (is (valid-property-type? (:type field-metadata))
                  (format "Field %s has an invalid type: %s. For definition %s v%s"
                          name (:type field-metadata) config-id version))
              (when (#{"user_defined" "list"} (:type field-metadata))
                (is (:value_type field-metadata)
                    (format "Field %s must have a value_type (required for list and user_defined fields). For definition %s v%s"
                            name config-id version)))
              (when (:value_type field-metadata)
                (is (valid-property-type? (:value_type field-metadata))
                    (format "Field %s has an invalid value_type: %s. For definition %s v%s"
                            name (:value_type field-metadata) config-id version))))
            (doseq [parent (filter :fields (vals fields))]
              (check-property-types-at-level (:fields parent))))]
    (check-property-types-at-level (:properties metadata))))

(defn check-for-ternary-booleans
  "Checks that there are no old-style ternary booleans (required is false)."
  [metadata config-id version field-name field-metadata]
  (is (false? (and (= "boolean" (:type field-metadata))
                   (false? (:required field-metadata))))
      (format "Unexpected ternary boolean %s found in config file %s for version %s"
              field-name
              config-id
              version)))

(defn check-for-directories-with-descriptions
  "Checks that every directory field has a description."
  [metadata config-id version]
  (doseq [[field-name field-properties] (:properties metadata)]
    (is (false? (and (= true (:is_directory field-properties))
                     (nil? (:description field-properties))))
        (format "is_directory=true field %s does not have description set in config file %s for version %s"
                field-name
                config-id
                version))))

(defn check-group-names
  "Enforce the consistency of capitalization of the group names."
  [metadata config-id version]
  (doseq [group-information (:groupings metadata)]
    (let [group-name (:name group-information)]
      (is (nil? (re-find #"\b[a-z]" group-name))
          (format "Group name contains uncapitalized word: %s found in config file %s for version %s"
                  group-name
                  config-id
                  version)))))

(defn check-for-invalid-attributes
  "Checks that list of attributes for each property includes only valid keys."
  [metadata config-id version]
  (letfn [(check-for-invalid-properties-at-level [fields]
            (doseq [[name field-metadata] fields]
              (is (nil? (:default field-metadata))
                  (format (str "Property %s has a :default attribute, which is "
                               "not valid, you almost certainly meant "
                               ":default_value. For definition %s v%s")
                          name  config-id version))
              (let [property-keys (-> field-metadata keys set)
                    invalid-keys (set/difference property-keys field-key?)]
                (is (empty? invalid-keys)
                    (format (str "Property %s in definition %s v%s has one or "
                                 "or more invalid attributes: %s")
                            name config-id version invalid-keys))))
            (doseq [parent (filter :fields (vals fields))]
              (check-for-invalid-properties-at-level (:fields parent))))]
    (check-for-invalid-properties-at-level (:properties metadata))))

(defn check-defaults
  "Ensures that generating defaults produces *something* without error."
  [metadata config-id dse-version]
  (testing (format "Testing defaults for DSE=%s config-id=%s"
                   dse-version
                   config-id)
    (let [def-defaults (definition-defaults metadata)]
      (is def-defaults))))

(defmulti check-tarball-paths
  "Makes sure that :tarball-path is present and either empty string or
  else it must be a relative path. Which one is required depends on the
  config file."
  (fn [_ config-key _]
    (if (contains? #{:java-setup
                     :package-proxy
                     :install-options
                     :10-write-prom-conf
                     :10-write-graphite-conf
                     :10-statsd-conf}
                   config-key)
      :no-file
      config-key)))

(defmethod check-tarball-paths :default
  [{:keys [tarball-path]} config-key dse-version]
  (testing (format "Testing :tarball-path for DSE=%s config-key=%s" dse-version config-key)
    (is (and
         (not (empty? tarball-path))
         (not (.isAbsolute (io/file tarball-path)))
         (not (.startsWith tarball-path "./"))
         (not (.startsWith tarball-path "../")))
        (format ":tarball-path '%s' is not relative" tarball-path))))

(defmethod check-tarball-paths :no-file
  [{:keys [tarball-path]} config-key dse-version]
  (is (= "" tarball-path)
      (format ":tarball-path should be empty for %s in DSE %s"
              config-key dse-version)))

(defmulti check-package-paths
  "Makes sure that :package-path is present and either empty string or
  else it must be an absolute path. Which one is required depends on the
  config file. "
  (fn [_ config-key _]
    (if (contains? #{:java-setup
                     :package-proxy
                     :install-options
                     :datastax-env-sh}
                   config-key)
      :no-file
      config-key)))

(defmethod check-package-paths :default
  [{:keys [package-path]} config-key dse-version]
  (testing (format "Testing :package-path for DSE=%s config-key=%s" dse-version config-key)
    ;; Empty string is valid, see datastax-env-sh-dse-6.0.0.edn
    (is (and (not (empty? package-path)) (.isAbsolute (io/file package-path)))
        (format ":package-path '%s' is not absolute" package-path))))

(defmethod check-package-paths :no-file
  [{:keys [package-path]} config-key dse-version]
  (is (= "" package-path)
      (format ":package-path should be empty for %s in DSE %s"
              config-key dse-version)))


(defn check-tarball-defaults-for-paths
  "Makes sure that all file and directory properties have a
  :tarball_default value."
  [_ config-id dse-version field-name field-metadata]
  (when (and
         (version/version-is-at-least "6.0.0" dse-version)
         (or (:is_file field-metadata)
             (:is_directory field-metadata))
         (seq (:default_value field-metadata)))
    (is (seq (:tarball_default field-metadata))
        (format "File or directory property %s has a :default_value but is missing :tarball_default. Config: %s, DSE version %s"
                field-name config-id dse-version))))

(deftest check-all-definitions
  "Check some properties of the definitions using versions.edn"
  (let [versions-edn-parsed (get-all-versions definitions-location)
        modern-opsc-dse-versions (:opsc-versions versions-edn-parsed)
        all-opsc-dse-versions modern-opsc-dse-versions
        all-opsc-versions (keys all-opsc-dse-versions)
        all-dse-versions (->> (map (fn [opsc-version]
                                     (get-in all-opsc-dse-versions
                                             [opsc-version :dse]))
                                   all-opsc-versions)
                              flatten
                              set
                              (sort-by identity version/version-is-at-least))
        definitions-by-dse-version (->> all-dse-versions
                                        (map (partial get-all-definitions-for-version definitions-location))
                                        (zipmap all-dse-versions))]
    (doseq [opsc-version all-opsc-versions]
      (let [dse-versions (get-in all-opsc-dse-versions [opsc-version :dse])]
        (check-transform-versions (get-config-file-ids definitions-location)
                                  opsc-version
                                  dse-versions
                                  all-opsc-dse-versions)))
    (doseq [dse-version all-dse-versions]
      (doseq [[config-id metadata] (get definitions-by-dse-version dse-version)
              check-fn [(make-check-recursive check-for-ternary-booleans)
                        check-for-directories-with-descriptions
                        check-group-names
                        check-for-invalid-attributes
                        check-property-types
                        check-groups
                        check-field-order
                        check-has-order
                        check-dependencies
                        check-ui-visibility
                        check-auth
                        check-renderer
                        check-package-paths
                        check-tarball-paths
                        (make-check-recursive check-tarball-defaults-for-paths)
                        check-defaults]]
        (check-fn metadata config-id dse-version)))))

(deftest test-check-depends?
  (is (check-depends? {:a 1}
                      {}))
  (is (check-depends? {:a 1}
                      {:depends :a
                       :conditional [{:eq 1}]}))
  (is (check-depends? {:a 1}
                      {:depends :a
                       :conditional [{:eq 6}
                                     {:eq 1}]}))
  (is (not (check-depends? {:a 1}
                           {:depends :a
                            :conditional [{:eq 2}]})))
  (is (not (check-depends? {}
                           {:depends :a
                            :conditional [{:eq 1}]}))))

(deftest test-fill-in-defaults
  (testing "top-level defaults, no deps"
    (is (= {:a 1 :b 2}
           (fill-in-defaults
             {:a 1}
             {:properties
              {:a {:type "int" :default_value 5}
               :b {:type "int" :default_value 2}}})))
    (is (= {:a 5 :b 2}
           (fill-in-defaults
             nil
             {:properties
              {:a {:type "int" :default_value 5}
               :b {:type "int" :default_value 2}}}))))

  (testing "top-level defaults, with deps"
    (is (= {:a false}
           (fill-in-defaults
             {:a false}
             {:properties
              {:a {:type "boolean" :default_value true}
               :b {:type "int" :default_value 1
                   :depends :a
                   :conditional [{:eq true}]}}})))
    (is (= {:a true :b 1}
           (fill-in-defaults
             {:a true}
             {:properties
              {:a {:type "boolean" :default_value true}
               :b {:type "int" :default_value 1
                   :depends :a
                   :conditional [{:eq true}]}}})))
    (is (= {:a true :b 1}
           (fill-in-defaults
             {}
             {:properties
              {:a {:type "boolean" :default_value true}
               :b {:type "int" :default_value 1
                   :depends :a
                   :conditional [{:eq true}]}}})))
    (is (= {:a true :b 1}
           (fill-in-defaults
             {:a true}
             {:properties
              {:a {:type "boolean" :default_value false}
               :b {:type "int" :default_value 1
                   :depends :a
                   :conditional [{:eq true}]}}})))
    (is (= {:a false}
           (fill-in-defaults
             {}
             {:properties
              {:a {:type "boolean" :default_value false}
               :b {:type "int" :default_value 1
                   :depends :a
                   :conditional [{:eq true}]}}}))))

  (testing "dependency chain"
    (is (= {:a true :b 1 :c 3}
           (fill-in-defaults
             {}
             {:properties
              {:b {:type "int" :default_value 1
                   :depends :a
                   :conditional [{:eq true}]}
               :c {:type "int" :default_value 3
                   :depends :b
                   :conditional [{:eq 1}]}
               :a {:type "boolean" :default_value true}}})))
    (is (= {:a true :b 1 :c 3}
           (fill-in-defaults
             {:a true}
             {:properties
              {:c {:type "int" :default_value 3
                   :depends :b
                   :conditional [{:eq 1}]}
               :b {:type "int" :default_value 1
                   :depends :a
                   :conditional [{:eq true}]}
               :a {:type "boolean" :default_value false}}}))))

  (testing "deep properties"
    (let [definition
          {:properties
           {:a {:type "dict"
                :fields
                      {:bb {:type "int" :default_value 1
                            :depends :aa
                            :conditional [{:eq true}]}
                       :cc {:type "int" :default_value 3
                            :depends :bb
                            :conditional [{:eq 1}]}
                       :aa {:type "boolean" :default_value true}}}}}]
      (is (= {:a {:aa true
                  :bb 1
                  :cc 3}}
             (fill-in-defaults {} definition)))
      (is (= {:a {:aa false}}
             (fill-in-defaults {:a {:aa false}} definition)))
      (is (= {:a {:aa true
                  :bb 4
                  :cc 5}}
             (fill-in-defaults {:a {:aa true
                                    :bb 4
                                    :cc 5}} definition)))
      (is (= {}
             (fill-in-defaults {}
                               {:properties
                                {:a {:type "dict"
                                     :fields
                                           {:bb {:type "int"
                                                 :depends :aa
                                                 :conditional [{:eq true}]}
                                            :cc {:type "int"
                                                 :depends :bb
                                                 :conditional [{:eq 1}]}
                                            :aa {:type "boolean"}}}}})))))

  (testing "config-file-valid?"
    ;; this has [:all]
    (is (config-file-valid? definitions-location :cassandra-yaml "6.0.0"))
    (is (config-file-valid? definitions-location :cassandra-yaml "6.0.8"))

    ;; jvm-options is not valid after 6.8.0
    (is (false? (config-file-valid? definitions-location :jvm-options "6.8.0")))
    (is (config-file-valid? definitions-location :jvm-options "6.0.0")))

  (testing "user_defined type"
    (let [definition
          {:properties
           {:a {:type "user_defined"
                :value_type "dict"
                :fields
                {:c {:type "string"}
                 :b {:type "int" :default_value 1
                     :depends :c
                     :conditional [{:eq "blah"}]}}}}}]
      (is (= {}
             (fill-in-defaults {} definition)))
      (is (= {:a {:foo {:b 1 :c "blah"}}}
             (fill-in-defaults {:a {:foo {:c "blah"}}}
                               definition)))
      (is (= {:a {:foo {:c "blarg"}}}
             (fill-in-defaults {:a {:foo {:c "blarg"}}}
                               definition)))))

  (testing "complex defaults dict type"
    (let [definition
          {:properties
           {:a {:type "dict"
                :fields
                      {:b {:type "string"
                           :default_value "b_default"}
                       :c {:type "int" :default_value 1}}
                :default_value
                      {:b "foobie"
                       :c 42}}}}]
      (is (= {:a {:b "foobie" :c 42}}
             (fill-in-defaults {} definition)))
      (is (= {:a {:b "blue"
                  :c 1}}
             (fill-in-defaults {:a {:b "blue"}} definition)))))

  (testing "complex defaults list type"
    (let [definition
          {:properties
           {:a {:type "list"
                :value_type "dict"
                :fields
                {:b {:type "string"
                     :default_value "b_default"}
                 :c {:type "int"
                     :default_value 1}}
                :default_value
                [{:b "foobie" :c 42} {:b "blah" :c 22}]}}}]
      (is (= {:a [{:b "foobie" :c 42} {:b "blah" :c 22}]}
             (fill-in-defaults {} definition)))
      (is (= {:a [{:b "scotty" :c 4}]}
             (fill-in-defaults {:a [{:b "scotty" :c 4}]} definition))))))

(deftest test-consistent-dse-major-version-lists
  ;; For each adjacent pair of OPSC version key (such as 6.0.x and 6.1.0), group by the first two
  ;; version places - basically group by major DSE release. If either one has an empty list for
  ;; a given DSE major release, move on. Else, test that the DSE version lists are equal.
  (let [opsc-versions (:opsc-versions (get-all-versions definitions-location))
        adjacent-versions (->> opsc-versions keys sort
                               (partition 2 1))
        major-version (partial re-find #"^\d+\.\d+")

        dse-major-versions #(group-by major-version (get-in opsc-versions [% :dse]))

        compare-versions
        (fn [[opsc-v1 opsc-v2]]
          (let [[major-version-map-1
                 major-version-map-2] (map dse-major-versions [opsc-v1 opsc-v2])
                ;; we only care about major versions supported by both opsc versions,
                ;; so diff the major-version-sets and take what is in both
                [_ _ common-major-versions] (data/diff (-> major-version-map-1 keys set) (-> major-version-map-2 keys set))]
            (doseq [major-version common-major-versions]
              (is (= (get major-version-map-1 major-version)
                     (get major-version-map-2 major-version))
                  (format "DSE major version list difference between OPSC version %s and %s"
                          opsc-v1 opsc-v2)))))]
    (doseq [version-pair adjacent-versions]
      (compare-versions version-pair))))

(deftest test-property-path?
  (is (property-path? [:properties :a]))
  (is (property-path? [:properties :a :fields :b]))
  (is (property-path? [:properties :a :fields :b :fields :c]))
  (is (not (property-path? [])))
  (is (not (property-path? [:a :b :c])))
  (is (not (property-path? [:properties])))
  (is (not (property-path? [:properties :a :b])))
  (is (not (property-path? [:properties :a :b :c])))
  (is (not (property-path? [:properties :a :fields]))))


(deftest test-preference-path-matcher
  (let [path-matcher (preference-path-matcher [:properties :a :fields :b :fields :c]
                                              {:properties
                                               {:a {:type "dict"
                                                    :fields {:b {:type "list"
                                                                 :value_type "dict"
                                                                 :fields {:c {:type "string"}}}}}}})]
    (is (= [:a :b 13 :c] (path-matcher [:a :b 13 :c :d :e])))
    (is (nil? (path-matcher [:a :b :c])))
    (is (nil? (path-matcher [:b 2 :e])))
    (is (nil? (path-matcher [])))))

(deftest test-property-path->
  (is (= [:a :b :c] (property-path->key-path [:properties :a :fields :b :fields :c])))
  (is (= :a.b.c (property-path->keyword [:properties :a :fields :b :fields :c]))))

(deftest test-key-path->
  (is (= "a.b.c" (key-path->str [:a :b :c])))
  (is (= :a.b.c (key-path->keyword [:a :b :c]))))

(deftest test-use-tarball-defaults
  (let [all-defs (get-all-definitions-for-version definitions-location "6.0.0")
        with-tarball (use-tarball-defaults all-defs)
        check-default (fn [field-path]
                        ;; field-path is everything up to, but not including, :default_value
                        (is (= (get-in with-tarball (conj field-path :default_value))
                               (get-in with-tarball (conj field-path :tarball_default)))))]
    (doseq [field-path (map (comp vec butlast)
                            (lcm.utils.data/map-paths
                             (fn [k _] (= :tarball_default k))
                             all-defs))]
      (check-default field-path))))
