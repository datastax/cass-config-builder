(ns com.datastax.configbuilder.test-data
  (:require [com.datastax.configbuilder.definitions :as d]))

;; Test namespaces should primarily make use of the
;; get-definitions-data function. This will store the requested
;; definitions in an internal cache so that subsequent
;; requests are fast.
;;
;; If it is necessary to evict the cache, the test should
;; call reset-definitions-datas.

(def definitions-location "../definitions/resources")

(defn- load-definitions-data
  [definitions-datas datastax-version]
  (if (contains? definitions-datas datastax-version)
    definitions-datas ;; already cached - return unmodified
    (assoc definitions-datas
           datastax-version
           {:definitions-location definitions-location
            :datastax-version datastax-version
            :definitions (d/get-all-definitions-for-version
                          definitions-location
                          datastax-version)})))

(def ^{:private true}
  definitions-datas
  (atom {}))

(defn get-definitions-data
  "Gets a cached definitions-data map for the given dse version"
  [datastax-version]
  (get (swap! definitions-datas load-definitions-data datastax-version)
       datastax-version))

(defn reset-definitions-datas
  "Clears the builder state map and will force the next
  request for each version to load definitons from the
  filesystem."
  []
  (reset! definitions-datas {}))
