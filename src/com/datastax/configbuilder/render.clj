(ns com.datastax.configbuilder.render
  (:require [com.datastax.configbuilder.build-config :as bc]
            [com.datastax.configbuilder.render.config-file-renderer :as renderer]
            [clojure.string :as str]))

;; In addition to the enriched config data, defaults, and other
;; metadata, contains a string of the rendered config file content
;; and it's path on the node filesystem.
(defrecord RenderedConfigInfo
  ;; This is the basis for the seq of maps that make up the API response that
  ;; meld will consume.
  [config-key                                               ;; The internal keyword we use to identify the config file.
   display-name                                             ;; This is :display-name from the definitions file.
   file-path                                                ;; The absolute path to the config file on disk.
   defaults                                                 ;; Just the defaults from definitions for this config file.
   contents                                                 ;; The template-data (or config data, if you prefer) - (:dse-yaml (:configs profile-context))
   rendered-contents                                        ;; The rendered config as a string.
   opscd-agent-version                                      ;; Special field for :address-yaml. Should be nil for all others.
   ])

(def spark-config-keys
  #{:spark-env-sh :dse-spark-env-sh :spark-defaults-conf
    :hive-site-xml :logback-spark-xml :logback-sparkr-xml
    :spark-daemon-defaults-conf :logback-spark-server-xml
    :logback-spark-executor-xml :spark-alwayson-sql-conf})

(defn config-keys
  "Extracts only the keys representing configs"
  [config-data]
  (keys config-data))

(defn config-keys-to-render
  "We don't render spark configs unless spark is enabled for the datacenter."
  [{:keys [datacenter-info] :as config-data}]
  (remove
    (partial contains? (if (:spark-enabled datacenter-info) #{} spark-config-keys))
    (config-keys config-data)))

(defmulti render-config
          "Renders the config file to a string (:rendered-contents) and wraps it all
          up in an ConfigInfo record that meld will consume for writing configs to
          the filesystem on the target node."
          (fn [_ config-key _] config-key))

(defn- render-config*
  "Default implementation for rendering configs. Extracted for reuse by
  implementations that may do some post-processing to the returned ConfigInfo."
  [{:keys [definitions] :as definitions-data}
   config-key
   {:keys [node-info] :as config-data}]
  (map->RenderedConfigInfo
    {:config-key        config-key
     :display-name      (get-in definitions [config-key :display-name])
     :file-path         (get-in node-info [:file-paths config-key])
     :contents          (get config-data config-key)
     :rendered-contents (renderer/render-config-file
                          definitions-data
                          config-key
                          config-data)}))

(defmethod render-config :default
  [definitions-data config-key config-data]
  (render-config* definitions-data config-key config-data))

(defmethod render-config :address-yaml
  ;; This one is necessary because address-yaml does not have a definitions file
  ;; and the default implementation requires definitions.
  [definitions-data config-key config-data]
  (assoc
    (render-config*
     ;; for this call only, we introduce a synthetic definitions
     ;; map for :address-yaml with the keys required by the
     ;; rendering logic.
     (assoc-in definitions-data [:definitions config-key]
               {:display-name "address.yaml"
                :renderer     {:renderer-type :yaml}})
     config-key
     config-data)
    ;; The following extra field on RenderedConfigInfo is used by agent install.
    :opscd-agent-version (get-in config-data [:node-info :agent_version])))

(defmethod render-config :package-proxy
  ;; This config is rendered for use in a command-line call, so we must
  ;; strip out the whitespace (newline at the end of file).
  [definitions-data config-key config-data]
  (update
    (render-config* definitions-data config-key config-data)
    :rendered-contents
    (comp str/trim-newline str/trim)))

(defn render-model-info
  [config-key config-data]
  (map->RenderedConfigInfo
   {:config-key config-key
    :display-name (str config-key)
    :contents (get config-data config-key)}))

(defmethod render-config :cluster-info
  [_ config-key config-data]
  (render-model-info config-key config-data))

(defmethod render-config :datacenter-info
  [_ config-key config-data]
  (render-model-info config-key config-data))

(defmethod render-config :node-info
  [_ config-key config-data]
  (render-model-info config-key config-data))

(defn render-configs
  "Renders all configs (with a few exceptions - see config-keys-to-render).
  Accepts a fully-enriched config-data map and returns a seq of ConfigInfo records."
  [definitions-data config-data]
  (map
    #(render-config definitions-data % config-data)
    (config-keys-to-render config-data)))
