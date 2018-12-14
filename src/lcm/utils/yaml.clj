(ns lcm.utils.yaml
  (:require [clojure.walk :as walk])
  (:import [org.yaml.snakeyaml Yaml DumperOptions DumperOptions$FlowStyle]))

(defprotocol YamlReader
  (decode [data keywords?]))

(defn- decode-key
  [k keywords?]
  (if keywords?
    (keyword k)
    k))

(extend-protocol YamlReader
  java.util.LinkedHashMap
  (decode [data keywords?]
    (into (array-map)
          (for [[k v] data]
            [(decode-key k keywords?) (decode v keywords?)])))
  java.util.LinkedHashSet
  (decode [data keywords?]
    (into (hash-set) (map #(decode % keywords?) data)))
  java.util.ArrayList
  (decode [data keywords?]
    (into [] (map #(decode % keywords?) data)))
  Object
  (decode [data _] data)
  nil
  (decode [data _] data))

(defn parse
  "Converts a YAML string into data."
  [yaml-string & {:keys [keywords yaml-inst] :or {keywords false}}]
  (decode (.load (or yaml-inst (Yaml.))
                 yaml-string)
          keywords))

(defn dump
  "Converts object data into a YAML string"
  [data & {:keys [yaml-inst]}]
  (.dump (or yaml-inst
             (Yaml. (doto (DumperOptions.)
                      (.setDefaultFlowStyle DumperOptions$FlowStyle/BLOCK))))
         (walk/stringify-keys data)))

