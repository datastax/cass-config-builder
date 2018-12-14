(ns com.datastax.configbuilder.public-api
  (:require [com.datastax.configbuilder.definitions :as d]))

;; This namespace is to serve mainly as the public interface for consumers of this
;; library. Other namespaces should be considered private and subject to change.

(defn get-definitions
  "Gets all the definitions files for the given version of DSE"
  [definitions-location dse-version]
  (d/get-all-definitions-for-version definitions-location dse-version))

