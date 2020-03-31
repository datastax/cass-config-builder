;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns lcm.utils.edn
  (:require [clojure.edn :as edn]
            [clojure.core.memoize :as memo]))

(def cached-read-string
  "We parse EDN strings a lot, cache the results for an hour."
  (memo/ttl edn/read-string :ttl/threshold 3600000))
