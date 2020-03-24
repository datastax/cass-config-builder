(ns com.datastax.configbuilder.operator
  (:require [com.datastax.configbuilder.build-config :as bc]
            [com.datastax.configbuilder.definitions :as d]
            [com.datastax.configbuilder.render :as r]
            [cheshire.core :as json])
  (:gen-class
   :name com.datastax.configbuilder.K8SOperatorConfigBuilder))


(defn -main
  "Entry point"
  [& args]
  (try
    (let [pod-ip                   (System/getenv "POD_IP")
          config-file-data         (System/getenv "CONFIG_FILE_DATA")
          product-version          (System/getenv "PRODUCT_VERSION")
          rack-name                (System/getenv "RACK_NAME")
          product-name             (or (System/getenv "PRODUCT_NAME") "dse")
          config-output-directory  (or (System/getenv "CONFIG_OUTPUT_DIRECTORY")
                                       "/config")
          ;; This is defined by the build and under our control
          definitions-location     (or (System/getenv "DEFINITIONS_LOCATION")
                                       "/definitions")
          ;; We have to unjson the config-file-data, change it, and re-serialize it to json
          config-file-data-parsed  (json/parse-string config-file-data)

          ;; Set the addresses
          config-file-data-ips-1   (assoc-in config-file-data-parsed
                                             ["node-info" "listen_address"]
                                             pod-ip)
          config-file-data-ips-2   (assoc-in config-file-data-ips-1
                                             ["node-info" "native_transport_broadcast_address"]
                                             pod-ip)
          config-file-data-ips-3   (assoc-in config-file-data-ips-2
                                             ["node-info" "native_transport_address"]
                                             "0.0.0.0")

          updated-config-file-data (assoc-in config-file-data-ips-3
                                             ["node-info" "rack"]
                                             rack-name)
          definitions-data         (d/get-definitions-data definitions-location
                                                           product-name
                                                           product-version)
          enriched-input           (bc/build-configs definitions-data
                                                  updated-config-file-data)
          output                   (r/render-configs definitions-data enriched-input)
          parsed-output            (json/parse-string output)]
      ;; Note: docker-images/base/files/base-checks.sh symlinks the conf files
      ;; to their actual directory, so we just need to output the files to /config
      (doseq [current-file parsed-output]
        ;; Some of the config files don't have a path, like install-options
        ;; In this case, current-file will be nil.
        (when (not (nil? (get current-file "file-path")))
          (let [full-filepath   (get current-file "file-path")
                the-file        (java.io.File. full-filepath)
                base-filename   (.getName the-file)
                target-filename (str config-output-directory "/" base-filename)]
            (spit target-filename
                  (get current-file "rendered-contents"))))))
    (catch Exception e
      (do
        (.printStackTrace e)
        (println (str "Error processing request: "
                  (.getMessage e)))
        (System/exit 1)))))
