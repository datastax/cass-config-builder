(ns com.datastax.configbuilder.golden-files-test
  (:require  [clojure.test :refer :all]
             [clojure.java.io :as io]
             [clojure.java.shell :refer [sh]]
             [clojure.string :as st]
             [clojure.data :as data]
             [com.datastax.configbuilder.definitions :as d]
             [com.datastax.configbuilder.test-data :as test-data]
             [com.datastax.configbuilder.test-helpers :as test-helpers]
             [com.datastax.configbuilder.build-config :as bc]
             [com.datastax.configbuilder.render :as r]
             [lcm.utils.yaml :as yaml]
             [lcm.utils.data :refer [map-paths]]))

;; This namespace checks generated default config output against golden files which originated
;; from the DSE repo. Some of these golden files have been modified to match the slight
;; differences in the output caused by LCM's implementation (examples are whitespace,
;; superfluous comments, and additional comments warning users not to hand edit these files,
;; etc...)
;;
;; Several functions are provided at the top of this file for updating golden files. WARNING!
;; These should only be used after careful consideration of the differences caught by the
;; tests. You must be supremely confident that the new output is as it should be and that the
;; only reason the test is failing is because the current golden file is not up to date with
;; reality! Only then may you update the golden files. Then look carefully at the git diffs
;; and sit on your hands until they are numb before checking them in. YOU'VE BEEN WARNED!
;;
;; NOTE: The functions use test-data/get-definitions-data, which caches the definitions.
;; If you are iterating on definitions changes, you will need to call
;; test-data/reset-definitions-data!
;;
;; NOTE: This namespace does it's own caching around build-configs, since it gets called
;; repeatedly and each call only needs one config out of the big map that is produced.
;; However, we use a dynamic var and memoize the function in an :each test fixture, so
;; the cache should be evicted just prior to each test.

(def ^{:dynamic true} build-configs (memoize bc/build-configs))

(use-fixtures :each
  (fn [t]
    (binding [build-configs (memoize bc/build-configs)]
      (t))))

(defn golden-file
  "Gets a java.io.File for the golden file for the given DSE version and config."
  [dse-version config-file-id]
  (let [definitions-data (test-data/get-definitions-data dse-version)
        package-path (get-in definitions-data [:definitions config-file-id :package-path])
        file-name (.getName (io/file package-path))]
    (io/file (format "test/data/configs/dse-%s/%s" dse-version file-name))))

(defn generate-default-file
  "Generates rendered output for the given DSE version and config file using only
  default values."
  [dse-version config-file-id]
  (let [definitions-data (test-data/get-definitions-data dse-version)
        model-info {:cluster-info {:name "foobie-cluster"}
                    :datacenter-info {:name "dc1"
                                      :graph-enabled false
                                      :solr-enabled false
                                      :spark-enabled false}
                    :node-info {:name "node1"
                                :rack "rack1"
                                :facts {}}}
        defaults (build-configs definitions-data model-info)
        config (r/render-config definitions-data config-file-id defaults)
        new-file (io/file (.toFile @test-helpers/temp-dir) dse-version (name config-file-id))]
    (io/make-parents new-file)
    (spit new-file
          (:rendered-contents config))
    new-file))

(defn update-golden-file
  "Use this only after inspecting test failure diffs and iff you are satisfied that
  the diffs are inconsequential. Should be called with the :rendered-contents output
  from r/render-config"
  ([dse-version config-file-id]
   (let [generated-file (generate-default-file dse-version config-file-id)]
     (update-golden-file dse-version config-file-id (slurp generated-file))))
  ([dse-version config-file-id new-content-string]
   (assert (not (#{:cassandra-yaml :dse-yaml} config-file-id))
           "YAML golden files should come from the bdp repo, not generated output.")
   (let [gf (golden-file dse-version config-file-id)]
     (io/make-parents gf)
     (spit gf new-content-string))))

(defn diff-file
  [dse-version config-file-id]
  (doto (io/file "target/golden-file-results" dse-version
                 (str (name config-file-id) ".diff"))
    (io/make-parents)))

(defn spit-diff
  "Sometimes the test output can be hard to read, especially if it's a big diff.
  This will store the diff in the configbuilder/target/golden-file-results
  directory."
  [dse-version config-file-id diff]
  (doto (diff-file dse-version config-file-id)
    (spit diff)))

(use-fixtures :once
  (test-helpers/temp-dir-fixture "configbuilder-golden-files-"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CHECK THIS OUT!!!                                   ;;
;;                                                     ;;
;; If you install the "meld" package (not LCM meld,    ;;
;; but a graphical diff tool), the test will attempt   ;;
;; to open meld to compare the two files.              ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; If you want to opt out of the graphical diff,
;; set this to false.
(def use-graphical-diff true)
(defn view-diff
  [file-a file-b]
  (when use-graphical-diff
    (sh "meld" (str file-a) (str file-b))))

(defmulti diff-test
  (fn [dse-version config-file-id]
    (let [definitions-data (test-data/get-definitions-data dse-version)]
      (get-in definitions-data
              [:definitions config-file-id :renderer :renderer-type]
              :template))))

;; Some properties we know are different than the source config. In most
;; cases this is due to dependent fields that show up on one side and not
;; the other. For example, dse.yaml has multiple cases where the enabled
;; property is false, yet it's sibling properties are still present. In
;; LCM these dependent properties are not included in the output. If the
;; last element of the path is the keyword :*, all child properties are
;; skipped.
;;
;; Note: I am using a synthetic map here to generate the key paths without
;; having to do a whole lot of repetition. The values do not matter.
(def skip-properties
  (into #{}
        (map-paths
         {;;;; dse.yaml skips
          ;; we have this as a dependent field, so it doesn't render.
          "config_encryption_key_name" 1

          ;; dse.yaml default seems to be meant for tarball installs?
          "kerberos_options" {"keytab" 1}

          ;; dse.yaml has enabled: false for audit_logging_options, yet
          ;; it includes uncommented nested properties.
          "audit_logging_options"
          {"cassandra_audit_writer_options" {:* 1}}

          ;; dse.yaml has values for this tree even though runner_type
          ;; is 'default', which would disable these children
          "spark_process_runner" {"run_as_runner_options" {:* 1}}

          ;; dse.yaml has spark_application_info_options disabled by default
          "spark_application_info_options"
          {"refresh_rate_ms" 1
           "driver" {:* 1}
           "executor" {:* 1}}

          ;;;; cassandra.yaml skips
          ;; this property is empty (nil) in cassandra.yaml, but it's
          ;; datatype is int, so we can't default it to nil.
          "counter_cache_size_in_mb" 1
          "prepared_statements_cache_size_mb" 1

          ;; We customize node-to-node and client-to-node encryption options
          "server_encryption_options" {:* 1}
          "native_transport_address" 1
          "listen_address" 1
          "cluster_name" 1
          ;; We use GossipingPropertyFileSnitch instead of DSESimpleSnitch
          "endpoint_snitch" 1
          ;; We do our own thing with seeds and have test coverage
          "seed_provider" {:* 1}

          ;; cassandra.yaml includes this even though back_pressure_enabled is false
          "back_pressure_strategy" 1

          ;; remove this skip when OPSC-16412 is implemented
          "native_transport_keepalive" 1

          ;; commented out in cassandra.yaml, but defaults to true
          "zerocopy_streaming_enabled" 1

          ;; should be removed after OPSC-16390
          "zerocopy_max_sstables" 1
          "zerocopy_max_unused_metadata_in_mb" 1
          })))

(defn wildcard-skip?
  "For skipping an entire subtree of properties"
  [path]
  (let [wildcards (->> skip-properties
                       (filter (comp #{:*} last))
                       (map butlast))]
    (some
     (fn [wildcard]
       (= wildcard (take (count wildcard) path)))
     wildcards)))

(defn skip-property?
  [things-in-golden
   things-in-generated
   path]
  (or
   (contains? skip-properties path)

   ;; Check :* wildcard skips.
   (wildcard-skip? path)

   ;; Skip it if it exists in the LCM generated file, but not the
   ;; golden file. Most of these are non-ternary booleans. In many
   ;; cases, the boolean field is commented out in the source field
   ;; and the definitions have one or more dependent fields (and
   ;; the default for the boolean field is true).
   (and (not= :missing (get-in things-in-generated path :missing))
        (= :missing (get-in things-in-golden path :missing)))))

(defmethod diff-test :yaml [dse-version config-file-id]
  (let [generated-file (generate-default-file dse-version config-file-id)
        golden-file (golden-file dse-version config-file-id)
        generated-data (yaml/parse (slurp generated-file))
        golden-data (yaml/parse (slurp golden-file))

        ;; clojure.data/diff takes datastructure-A and datastructure-B
        ;; and returns [things-in-a, things-in-b, things-in-both].
        ;; note that the same property may exist in things-in-a and
        ;; things-in-b if the values are different.
        [things-in-golden
         things-in-generated
         things-in-both] (data/diff golden-data generated-data)]
    ;; Since these are deep maps, we should start by getting a seq of all
    ;; key paths to leaf nodes. Then iterate over that so we can make assertions
    ;; at the leaf level and report the full path to the property so the dev
    ;; can find and fix the error.

    ;; Also, some properties will have to be different, like listen_address. We
    ;; will make explicit exclusions for these.

    ;; From lcm.utils.data/map-paths - we do not descend into vectors.
    (doseq [path (map-paths things-in-golden)]
      ;; Quite often dse.yaml (maybe cassandra.yaml too) has sibling properties
      ;; with `enabled: false`. In definitions, we make these sibling fields
      ;; dependent because it cleans up the UI. We should check things-in-both
      ;; to see if enabled is false and, if so, we need to check to see if the
      ;; generated file also has this property. If not, we skip it, otherwise
      ;; it is a test failure because the values must be different.
      (let [parent-path (butlast path)]
        (when-not
            (or (skip-property? things-in-golden things-in-generated path)
                (and
                 parent-path
                 (false? (get-in things-in-both (conj (vec parent-path) "enabled")))
                 (= :dummy (get-in things-in-generated path :dummy))))
          (is false
              (format "Golden file %s v%s contains value %s for property %s. Generated file does not match."
                      config-file-id
                      dse-version
                      (pr-str (get-in golden-data path))
                      (st/join "->" path))))))
    (doseq [path (map-paths things-in-generated)]
      (when-not (skip-property? things-in-golden things-in-generated path)
        (is false
            (format "Generated file %s v%s contains value %s for property %s. Golden file does not match."
                    config-file-id
                    dse-version
                    (pr-str (get-in generated-data path))
                    (st/join "->" path)))))))

(defmethod diff-test :default [dse-version config-file-id]
  (let [generated-file (generate-default-file dse-version config-file-id)
        golden-file (golden-file dse-version config-file-id)
        {:keys [exit out]} (sh "diff"
                               (str golden-file)
                               (str generated-file))]
    (is (zero? exit)
        ;; Some side effects before we return the failure message...
        (do
          (let [diff-file (spit-diff dse-version config-file-id out)
                fail-msg (format "There are differences between rendered output and golden file for DSE %s, config key %s. See %s for the diffs."
                                 dse-version (name config-file-id) (str diff-file))]
            (when-not (zero? exit)
              (view-diff golden-file generated-file))
            fail-msg)))))

(deftest diff-against-golden-files
  (doseq [v ["6.0.11" "6.7.7"]
          config-key [:cassandra-env-sh
                      :logback-spark-executor-xml
                      :spark-defaults-conf
                      :spark-alwayson-sql-conf
                      :hive-site-xml
                      :dse-spark-env-sh
                      :logback-xml
                      :logback-sparkr-xml
                      :spark-env-sh
                      :logback-spark-server-xml
                      :logback-spark-xml
                      :spark-daemon-defaults-conf
                      :jvm-options
                      :dse-default
                      :dse-yaml
                      :cassandra-yaml]]
    (diff-test v config-key))

  ;; TODO needs to be updated when 6.8 releases!
  (diff-test "6.8.0" :dse-yaml)
  (diff-test "6.8.0" :cassandra-yaml)

  (diff-test "6.0.11" :cassandra-rackdc-properties))

(comment
  ;; Make sure you've looked at the diffs before you update the golden file!
  ;; Also, do not update the golden YAML files. These should come straight from BDP repo.
  ;; Example:
  ;; (update-golden-file "6.0.11" :cassandra-env-sh)
  (doseq [v ["6.0.11" "6.7.7"]]
    (update-golden-file v :dse-default))


  (test-data/reset-definitions-data!)

  )
