;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.test-data
  (:require [com.datastax.configbuilder.definitions :as d]
            [com.datastax.configbuilder.test-helpers :as helper]))

;; Test namespaces should primarily make use of the
;; get-definitions-data function. This will store the requested
;; definitions in an internal cache so that subsequent
;; requests are fast.
;;
;; If it is necessary to evict the cache, the test should
;; call reset-definitions-data!.

(def definitions-location "cass-config-definitions/resources")

(defn- load-definitions-data
  [definitions-data product datastax-version]
  (if (contains? definitions-data (list product datastax-version))
    definitions-data ;; already cached - return unmodified
    (assoc definitions-data
           (list product datastax-version)
           {:definitions-location definitions-location
            :datastax-version datastax-version
            :product product
            :definitions (d/get-all-definitions-for-version
                          definitions-location
                          product
                          datastax-version)})))

(def ^{:private true}
  definitions-data
  (atom {}))

(defn get-definitions-data
  "Gets a cached definitions-data map for the given dse version"
  ([]
   (get-definitions-data helper/default-dse-version))
  ([datastax-version]
   (get-definitions-data "dse" datastax-version))
  ([product datastax-version]
   (get (swap! definitions-data load-definitions-data product datastax-version)
        (list product datastax-version))))

(defn reset-definitions-data!
  "Clears the builder state map and will force the next
  request for each version to load definitons from the
  filesystem."
  []
  (reset! definitions-data {}))
