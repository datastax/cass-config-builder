(ns com.datastax.configbuilder.generator
  (:require [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [postwalk]]
            [cheshire.core :as json]
            [lcm.utils.data :as data]
            [lcm.utils.edn :as lcm-edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.core.memoize :as memo]
            [slingshot.slingshot :refer [throw+ try+]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DSL functions for transforms ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn key-path->property-path
  "Convert a key path into a property path.
  Example: {:a {:b {:c 1}}}, key-path: [:a :b :c]
  => property-path: [:properties :a :fields :b :fields :c]"
  [key-path]
  (->> key-path
       (filter (complement number?)) ;; remove vector indices from key-path
       (interpose :fields)
       (cons :properties)))

(defn str->property-path
  "Convert a string/keyword field name into a property path, using
  '.' as the separator."
  [field-name]
  (let [field-path (str/split (name field-name) #"\.")]
    (key-path->property-path (map keyword field-path))))

(defn update-template
  "Changes the template in the renderer"
  [definition new-template]
  (assoc-in definition [:renderer :template] new-template))

(defn update-field
  "Takes a fieldname such as :listen_address or :parent.child
  and merges the map of attributes with the existing options
  for that field.
  Examples: (update-field :listen_address {:required false})
  (update-field :foo.bar {:default_value \"blue\"})"
  [definition field-key attrs]
  (let [property-path (str->property-path field-key)]
    (when (nil? (get-in definition property-path))
      (throw+ {:type :UpdateFieldException
               :message (format (str "Failed to transform %s definition. Attempted "
                                     "to update-field for a key that doesn't "
                                     "exist. Contact DataStax support, this error "
                                     "should not occur on customer-owned systems.")
                                field-key)}))
    (update-in definition property-path merge attrs)))

(defn- remove-from-group
  "Remove a field-name from a group, if it exists in the :list
  Returns the modified group map.
  Example: (remove-from-group {:name \"a\" :list [\"b\"]} \"b\")
           -> {:name \"a\" :list []}"
  [group field-name]
  (assoc group :list
         (filterv (complement #{field-name})
                  (:list group))))

(defn- remove-from-groupings
  "Remove a field-name from any groupings that contain it.
  Returns the modified group map.
  Example: (remove-from-groupings {:groupings {:name \"a\" :list [\"b\"]}} \"b\")
           -> {:groupings {:name \"a\" :list []}}"
  [definition field-name]
  (update-in definition [:groupings]
             (fn [groups]
               (mapv #(remove-from-group % field-name) groups))))

(defn delete-field
  "Takes a fieldname such as :listen_address or :parent.child
  and removes it from the definition. This includes the properties
  entry and the groupings entry."
  [definition field-key]
  (let [property-path (str->property-path field-key)
        field-key-name (name field-key)]
    (when (nil? (get-in definition property-path))
      (throw+ {:type :DeleteFieldException
               :message (format (str "Failed to transform %s definition. Attempted "
                                     "to delete-field for a key that doesn't "
                                     "exist. Contact DataStax support, this error "
                                     "should not occur on customer-owned systems.")
                                field-key)}))
    (-> definition
        (update-in (butlast property-path) dissoc (keyword (last property-path)))
        (remove-from-groupings field-key-name))))

(defn- add-to-group
  "Adds a field-name to a grouping, throwing an exception if the group
  does not exist."
  [definition field-name {:keys [group at after]
                          :or {at :end}}]
  (let [group-index (data/find-index #(= (name group) (:name %))
                                     (:groupings definition))
        group-path [:groupings group-index :list]]
    ;; Make sure the group exists...
    (when-not group-index
      (throw+ {:type :GroupException
               :message (format "Group %s does not exist" group)}))

    ;; We have a group, now where in the :list do we insert it?
    (cond

     after
     (if-let [after-index
              (data/find-index #{(name after)}
                               (get-in definition group-path))]
       (update-in definition group-path
                  data/insert-into-vector (inc after-index) field-name)
       ;; couldn't find the :after field in the group's :list
       (throw+ {:type :GroupException
                :message (format "Bad :after field %s for group %s"
                               after group)}))

     (= :start at)
     (update-in definition group-path #(vec (cons field-name %)))

     (= :end at)
     (update-in definition group-path conj field-name)

     :else
     (throw+ {:type :GroupException
              :message (format "Unrecognized :at option %" at)}))))

(defn add-field
  "Adds a new field definition. The optional keyword arguments
  are for specifying which grouping the field is put into (for
  the UI) and where in the grouping it should be located. Nested
  fields should not need this, as only their top-level parent
  can be included in a group."
  [definition field-key field-definition & {:keys [group] :as group-map}]
  (let [property-path (str->property-path field-key)]
    ;; Make sure arguments are valid first
    (cond
      (not (nil? (get-in definition property-path)))
      (throw+ {:type :AddFieldException
               :message (format (str "Failed to transform %s definition. Attempted "
                                     "to add-field for a key that already exists. "
                                     "Contact DataStax support, this error should "
                                     "not occur on customer-owned systems.")
                                field-key)
               :field field-key})

      (and group (< 2 (count property-path)))
      (throw+ {:type :AddFieldException
               :message (format (str "Failed to transform %s definition. Attempted "
                                     "to add-field, but a group was specified for "
                                     "a nested field. Groups may only be specified "
                                     "for top-level fields. Contact DataStax "
                                     "support, this error should not occur on "
                                     "customer-owned systems.")
                                field-key)
               :field field-key})

      (and (not group) (= 2 (count property-path)))
      (throw+ {:type :AddFieldException
               :message (format (str "Failed to transform %s definition. Attempted "
                                     "to add-field, but no group was specified for "
                                     "a top-level key. Contact DataStax support, "
                                     "this error should not occur on customer-owned "
                                     "systems.")
                                field-key)
               :field field-key})

      group
      (-> definition
          (assoc-in property-path field-definition)
          (add-to-group (name field-key) group-map))

      :else
      (assoc-in definition property-path field-definition))))

(defn delete-group
  "Given a group name as as string, removes the group.
  This can be useful for transformations that remove the last field
  from a group."
  [definition group-name]
  (update-in definition
             [:groupings]
             (fn [groupings]
               (remove
                (fn [group]
                  (= (:name group) group-name))
                groupings))))

(defn add-group
  "Adds a new definition group (affects UI only). Will add it to the end of the
  list of groups unless :at :start or :after \"group label\" is specified"
  [definition group-name & {:keys [at after]
                            :or {at :end}}]
  (update-in definition
             [:groupings]
             (fn [groupings]
               (let [group {:name group-name :list []}]
                 (cond
                   after
                     (if-let [after-index (data/find-index #(= after (:name %)) groupings)]
                       (data/insert-into-vector groupings (inc after-index) group)
                       ;; couldn't find the :after group
                       (throw+ {:type :GroupException
                                :message (format "Bad :after group %s for adding group %s"
                                             after group-name)}))

                   (= :start at)
                   (vec (cons group groupings))

                   (= :end at)
                   (conj groupings group)

                   :else
                     (throw+ {:type :GroupException
                              :message (format "Unrecognized :at option %" at)}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Definition Transformer implementation ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Will hold the current working directory for the metadata transformer.
(def ^{:private true
       :dynamic true}
  *cwd*)

(defn get-base-field-metadata
  "Attempts to read the file from the *cwd* directory"
  [filename]
  (when-not *cwd*
    (throw+ {:type :DefinitionException :message "Current directory is not set for getting base metadata"}))
  (when-not (.isDirectory *cwd*)
    (throw+ {:type :DefinitionException :message (format "Invalid directory for reading base metadata: %s" *cwd*)}))
  (let [base-file (io/file *cwd* filename)]
    (when-not (.canRead base-file)
      (throw+ {:type :DefinitionException :message (format "Base metadata file does not exist or is not readable: %s" base-file)}))
    (try+
     (lcm-edn/cached-read-string (slurp base-file))
     (catch Object o
       (throw+ {:type :DefinitionException :message (format "Could not parse EDN file: %s" (.getName base-file))})))))

(defprotocol MetadataTransformer
  "Common protocol for various types of metadata transforms"
  (apply-transform [this metadata]))

(extend-protocol MetadataTransformer
  java.lang.String ;; "cassandra-yaml-2.0.0.edn"
  (apply-transform
    ;;"Ignores metadata and reads in a new one from a file."
    [this metadata]
    (get-base-field-metadata this))

  clojure.lang.Keyword
  (apply-transform
    ;;"Primarily for no-change transform values. Simply returns the previous metadata version."
    [this metadata]
    (if (= this :no-change)
      metadata
      (throw+ {:type :DefinitionException :message (format "Unrecognized keyword %s" this)})))

  java.util.List
  (apply-transform
    ;; "Applys transforms contained in the vector, dispatching them based on type:
    ;; add-field, update-field, delete-field. Also does validation here, ensuring
    ;; that only these transform types are allowed."
    [this metadata]
    (let [transforms (rest this)]
      ;; validation first
      ;; TODO: Improve the exception information...
      ;; all elements should be IPersistentList
      (when (and (= 'transforms (first this))
                 (not-every? #(instance? java.util.List %) transforms))
        (throw+ {:type :DefinitionException :message "Metadata transforms should be wrapped in (transforms)"}))

      ;; only symbols in the following set are allowed
      (when (not-every? #{'add-field 'update-field 'update-template 'delete-field 'add-group 'delete-group} (map first transforms))
        (throw+ {:type :DefinitionException :message "Valid metadata transforms are: add-field, update-field, delete-field, add-group"}))

      ;; If the transform is empty, clear out the metadata
      (if (empty? this)
        {}
        ;; MAYBE: check the args for each symbol and provide user-friendly errors?
        ;; first convert each transform to [fn-symbol args]
        (let [fns-and-args (map (juxt
                                 #(ns-resolve 'com.datastax.configbuilder.generator
                                              (first %)) ;; resolves the symbol (add-field, etc)
                                 rest)                                           ;; seq of the arguments
                                transforms)]
          (reduce (fn [modified-metadata [transform-fn args]]
                    (apply transform-fn modified-metadata args)) metadata fns-and-args))))))

(defn apply-transforms
  "Applies metadata transform functions in sequential order."
  [transforms]
  (reduce (fn [results [version transform]]
            (let [[_ previous] (last results)]
              (conj results [version (apply-transform transform previous)])))
          [] transforms))

;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generator functions ;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-metadata
  "Uses a transform file to generate metadata."
  [^java.io.File transforms-file]
  (when-not (.canRead transforms-file)
    (throw+ {:type :DefinitionException
             :message (format "Transforms file does not exist or cannot be read: %s" transforms-file)}))
  (binding [*cwd* (.getParentFile transforms-file)]
    (apply array-map
           (apply concat
                  (apply-transforms
                   (apply array-map
                          (lcm-edn/cached-read-string
                           (slurp transforms-file))))))))

(defn unsweeten-conditional-value
  "Takes the syntactic sugar form of a conditional value and makes it
  more bitterer (expands it). For example:

  1. {:type 'string', :depends :foo, :conditional 'bar'}
     becomes...
     {:type 'string', :depends :foo, :conditional [{:eq 'bar'}]}

  2. {:type 'string', :depends :foo}
     becomes...
     {:type 'string', :depends :foo, :conditional [{:eq true}]}

  3. {:type 'string', :depends :foo, :conditional {'bar' {:default_value '5'}}
     becomes...
     {:type 'string', :depends :foo, :conditional [{:eq 'bar', :default_value '5'}]}

  See the unit test for more examples."
  [conditional-value]
  (cond
    ;; if it's a vector, do nothing to it.
    (vector? conditional-value) conditional-value

    ;; in the nil case, we assume a simple boolean truth check
    ;; see example 2 above.
    (nil? conditional-value) [{:eq true}]

    ;; if it's a map, we must convert to a vector with :eq entry
    ;; see example 3 above.
    (map? conditional-value) (vec (map (fn [[parent-value mixin-map]]
                                         (assoc (if (map? mixin-map) mixin-map {})
                                                :eq parent-value)) conditional-value))

    ;; if it's anything else, assume it is a scalar value
    ;; see example 1 above.
    :else [{:eq conditional-value}]))

(defn unsweeten-conditionals-uncached
  "Takes the syntax sugar form of all conditionals and expands them."
  [definitions]
  (postwalk (fn [thing]
              (if (:depends thing)
                (assoc thing
                       :conditional
                       (unsweeten-conditional-value (:conditional thing)))
                thing))
            definitions))

(def unsweeten-conditionals
  "Experimentally, unsweetening conditionals is the most expensive part of
  definitions generation, accounting for over 50% of our CPU during unit
  tests. Memoize the process and cache the results for 1 hour."
  (memo/ttl unsweeten-conditionals-uncached :ttl/threshold 3600000))

(def generate-unsweetened-metadata
  (comp unsweeten-conditionals generate-metadata))

;; Legacy generators for old OPSC JSON files...
(defn generate-cassandra-yaml-metadata
  "Gets the generated metadata for cassandra-yaml"
  []
  (generate-metadata (io/file "resources"
                              "definitions"
                              "cassandra-yaml"
                              "dse"
                              (format "cassandra-yaml-dse-transforms.edn"))))

(defn write-metadata
  "Writes definition output to files in the specified directory"
  ([metadata base-path base-filename]
   (write-metadata metadata base-path base-filename :json))
  ([metadata base-path base-filename format]
   (when-not (#{:json :edn} format) (throw+ {:type :DefinitionException
                                             :message (str "Unsupported format: " (name format))}))
   (let [base-dir (io/file base-path)]
     ;; Make sure the parent directory exists
     (doto (io/file base-dir base-filename)
       (io/make-parents))
     (doseq [[version data] metadata]
       (let [filename (str base-filename
                           "-" version "." (name format))]
         (println "Writing" filename "to" (str base-dir))
         (with-open [w (io/writer (io/file base-dir filename))]
          (condp = format
            :json (json/generate-stream data w {:pretty true})
            :edn  (pprint data w)))))))
  ([base-path]
   (write-metadata (generate-cassandra-yaml-metadata) base-path "cassandra-yaml")
    ;;(write-metadata (generate-cassandra-yaml-metadata :dsc) base-path "cassandra-yaml")
   (println "Done.")))


;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions ;;
;;;;;;;;;;;;;;;;;;;;;;;

(defn filter-transforms
  "Filters only transforms of the given type"
  [transform-type transforms]
  {:pre [(or (= :no-change transforms)
             (= 'transforms (first transforms)))
         (symbol? transform-type)]}
  (when (seq? transforms)
    (filter (comp #{transform-type} first)
            (rest transforms))))
