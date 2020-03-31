;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder
  (:require [cheshire.core :as json]
            [com.datastax.configbuilder.definitions :as d]
            [com.datastax.configbuilder.build-config :as bc]
            [com.datastax.configbuilder.render :as r])
  (:import [clojure.lang IFn])
  (:gen-class
    :name com.datastax.ConfigBuilder
    :init init
    :state state
    :prefix "configbuilder-"
    :main false
    :constructors {[String String] []                       ;; definitions-location, datastax-version
                   [String String String] []}               ;; definitions-location, product, datastax-version
    :methods [[getDefinitions [] String]
              [getDatastaxVersion [] String]
              [getProduct [] String]
              [buildConfigs [String] String]
              [renderConfigs [String] String]]) )


;; This namespace is to serve mainly as the public interface for consumers of this
;; library. A Java class is also provided as an access point for Java clients.
;; Other namespaces beneath this should be considered private and subject to change.

(defn get-definitions-data
  "Gets all the definitions files for the given version of DSE"
  [definitions-location datastax-version]
  (d/get-definitions-data definitions-location datastax-version))

(defn build-configs
  "Takes the given sparse config data and enriches it with defaults (where
  there is no override) and other derived data (address-yaml, etc). The output
  looks very much like the input, only more so.
  See com.datastax.configbuilder/DefinitionsData record."
  [definitions-data config-data]
  (bc/build-configs definitions-data config-data))

(defn render-configs
  "Takes the config-data and the templates from definitions and renders the
  config file output. The ouput data is a map of RenderedConfigInfo records (see
  RenderedConfigInfo in the render namespace for details)."
  [definitions-data config-data]
  (r/render-configs definitions-data
                    (bc/build-configs definitions-data config-data)))

(defn configbuilder-init
  ([definitions-location datastax-version]
   [[]                                                      ;; super constructor takes no params
    ;; Note, this state is immutable. Hopefully we can keep it that way!
    (d/get-definitions-data definitions-location datastax-version)])
  ([definitions-location product datastax-version]
    [[]
     (d/get-definitions-data definitions-location product datastax-version)]))

(defn- call-api [definitions-data
                 ^IFn api-fn
                 ^String configJson]
  (let [config-data (json/parse-string configJson keyword)
        return-data
        (api-fn definitions-data config-data)]
    ;; the return-data must be converted back to JSON String
    (json/generate-string return-data)))

(defn configbuilder-getDefinitions [this]
  (json/generate-string (:definitions (.state this))))

(defn configbuilder-getProduct [this]
  (:product (.state this)))

(defn configbuilder-getDatastaxVersion [this]
  (:datastax-version (.state this)))

(defn configbuilder-buildConfigs
  "Accepts a JSON String of sparse config data."
  [this ^String configJson]
  (call-api (.state this) build-configs configJson))

(defn configbuilder-renderConfigs [this ^String configJson]
  (call-api (.state this) render-configs configJson))
