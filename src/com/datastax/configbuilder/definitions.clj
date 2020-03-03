(ns com.datastax.configbuilder.definitions
    (:require [clojure.java.io :as io]
              [clojure.string :as str]
              [clojure.walk :refer [postwalk]]
              [clojure.core.match :refer [match]]
              [clojure.pprint :refer [pprint]]
              [clojure.edn :as edn]
              [lcm.utils.data :as data]
              [lcm.utils.version :as v]
              [slingshot.slingshot :refer [throw+ try+]]
              [com.datastax.configbuilder.generator :as gen]))

;; DefinitionsData record is intented to hold meta information
;; related to definitions files.
;; Caching these records avoids the expense of loading
;; defintions from disk over and over again.
(defrecord DefinitionsData [definitions-location
                            product
                            datastax-version
                            definitions])

(def valid-property-type? #{"int" "float" "string" "boolean" "list" "dict" "user_defined" "ternary_boolean"})

;; The meaning of these keys are documented in spock/doc/definitions.md
;; Please update that doc when changing this list.
(def field-key? #{:type :default_value :value_type :unit :conditional
                  :validators :depends :options :order :key_name :description
                  :password :label :constant :add-export
                  :render-without-quotes :fields :summary_fields :render_as
                  :format :readonly :static_constant :suppress-equal-sign
                  :is_directory :exclude-from-template-iterables
                  :is_file :tarball_default})

;; Filters dependent fields. Accepts a map-entry of [field-name field-metadata].
(def dependent-field? (comp :depends second))

(defn get-all-versions
  "Reads all of versions.edn and returns the result as edn"
  [definitions-location]
  (let [versions-file (io/file definitions-location "versions.edn")]
    (edn/read-string (slurp versions-file))))

(defn flatten-versions
  "Primarily for testing definitions - takes the map from get-all-versions and
  returns a flattened sorted set of all the DSE versions in versions.edn."
  [all-versions-map]
  (let [all-opsc-versions (keys (:opsc-versions all-versions-map))]
    (->> (map (fn [opsc-version]
                (get-in all-versions-map [:opsc-versions opsc-version :dse]))
              all-opsc-versions)
         flatten
         (apply sorted-set-by v/version-comparator))))

(defn get-definitions-file-suffix
  [definition-type]
  (case definition-type
    :template       ".template"
    :field-metadata ".edn"
    :transforms     "-transforms.edn"))

(defn sanitize-definition-parameter
  "The codebase sometimes passes bare strings,
   sometimes uppercase strings, sometimes keywords,
   and sometimes strings that start with :
   This function produces a lowercase string from
   any of the above inputs"
  [definition-parameter]
  (str/lower-case
    (if (keyword? definition-parameter)
      (name definition-parameter)
      (if (re-find #"^:" definition-parameter)
        (clojure.string/replace definition-parameter #"^:" "")
        definition-parameter))))

(defn build-definitions-filename
  "Build a definition file's filename.
  The format is:

     <definitions file id>-<product class>-<product version><suffix>

  config-file-id will be a keyword.  Example:

     :cassandra-yaml

  product-version will be a string:

     \"4.7.0\"

  definition-type will be a keyword that maps to a suffix

     :template       .template
     :field-metadata .edn
     :transforms     -transforms.edn
  "
  ([config-file-id product-version definition-type]
   (build-definitions-filename config-file-id "dse" product-version definition-type))
  ([config-file-id product product-version definition-type]
   ;; ensure input is sanitized
   (let [sane-config-file-id (sanitize-definition-parameter config-file-id)]
     (if (= :transforms definition-type)
       (str sane-config-file-id
            "-" product "-transforms.edn")
       (str sane-config-file-id
            "-" product "-" product-version
            (get-definitions-file-suffix definition-type))))))

(defn build-definitions-directory
  "Returns a java.io.File that represents the definitions file directory
  for the given parameters."
  ([definitions-location config-file-id]
   (build-definitions-directory definitions-location "dse" config-file-id))
  ([definitions-location product config-file-id]
   (let [sane-config-file-id (sanitize-definition-parameter config-file-id)]
     (io/file definitions-location sane-config-file-id product))))

(defn get-definitions-file
  "Returns a java.io.File for the given parameters. Uses the definitions component to
  find the right file."
  ([definitions-location config-file-id product-version definition-type]
   (get-definitions-file definitions-location config-file-id "dse" product-version definition-type))
  ([definitions-location config-file-id product product-version definition-type]
   (let [definitions-filename (build-definitions-filename
                                config-file-id
                                product
                                product-version
                                definition-type)
         definitions-directory (build-definitions-directory
                                 definitions-location
                                 product
                                 config-file-id)]
     (io/file definitions-directory definitions-filename))))

(defn metadata-valid?
  "Returns true if the given field-metadata is valid.
  Since OPSC-13582, we do not use dse-version and consider
  the metadata valid if it is not empty."
  [field-metadata]
  (not (empty? field-metadata)))

(defn get-field-metadata
  "Get field metadata for the given parameters or the
   empty string."
  ([definitions-location config-file-id product-version]
   (get-field-metadata definitions-location config-file-id "dse" product-version))
  ([definitions-location config-file-id product product-version]
   (let [transforms-file (get-definitions-file
                           definitions-location
                           config-file-id
                           product
                           product-version
                           :transforms)]
     (if (or (nil? transforms-file) (not (.exists transforms-file)))
       (throw+ {:type
                :DefinitionException
                :message
                (str "There is no definition transforms file for "
                     (name config-file-id)
                     " for version " product-version)})
       (let [all-metadata (gen/generate-unsweetened-metadata transforms-file)]
         (v/get-fallback all-metadata product-version))))))

(defn config-file-valid?
  "Returns true if this configuration file is valid
   for the given version of DSE."
  [definitions-location configuration-file-id dse-version]
  (let [field-metadata (get-field-metadata definitions-location
                                           configuration-file-id
                                           dse-version)]
    (metadata-valid? field-metadata)))

(defn get-config-file-ids
      "Scans the definitions directory to collect config file ids. The
      directories must be named correctly for this to work."
  ([definitions-location]
   (get-config-file-ids definitions-location "dse"))
  ([definitions-location product]
   (let [top-dir (io/file definitions-location)]
     (->> (file-seq top-dir)
          (filter #(and (.isDirectory %)
                        (.isDirectory (io/file % product))))
          (map #(.getName %))
          (map keyword)))))

(defn get-all-definitions-for-version
      "Gets all the definition field metadata for a given version."
  ([definitions-location version]
   (get-all-definitions-for-version definitions-location "dse" version))
  ([definitions-location product version]
   (let [all-files (into {}
                         (for [config-file-id (get-config-file-ids definitions-location product)
                               :let [field-metadata (get-field-metadata
                                                      definitions-location
                                                      config-file-id
                                                      product
                                                      version)]
                               :when field-metadata]
                           [config-file-id field-metadata]))]
     (into {} (filter #(metadata-valid? (second %))
                      all-files)))))

(defn get-definitions-data
  "Gets a DefinitionsData record for a given version."
  ([definitions-location version]
   (get-definitions-data definitions-location "dse" version))
  ([definitions-location product version]
   (->DefinitionsData
     definitions-location
     product
     version
     (get-all-definitions-for-version
       definitions-location
       product
       version))))

(defn order-transitive-dependencies
  "Orders depends fields such that transitive dependencies (A->B->C) work
  properly. This is a Directed Acyclic Graph problem and an implementation of
  Khan's algorithm (see https://en.wikipedia.org/wiki/Topological_sorting).

  In this implementation, the 'fields' map is the 'graph' and :depends represents an
  incoming edge to the vertex. So for {:a {}, :b {:depends :a}}, if :b is the vertex
  the directed edge is a->b."
  [fields]
  (let [{:keys [graph sorted-graph]}
        (loop [{:keys [graph independent-fields] :as data}
               {:graph fields
                :sorted-graph []             ;; This is L in Khan's algorithm
                :independent-fields          ;; This is S in Khan's algorithm
                (into (array-map) (filter (complement dependent-field?) fields))}]
          (if (empty? independent-fields)
            data
            (let [[parent-name metadata] (first independent-fields)
                  children (filter #(= parent-name (:depends (second %))) graph)]
              (recur (-> data
                         ;; we can now 'move' the parent from the old graph into
                         ;; the sorted-graph.
                         (update :sorted-graph conj [parent-name metadata])
                         (update :graph dissoc parent-name)

                         ;; done with this parent, so remove it from independent fields.
                         (update :independent-fields dissoc parent-name)
                         ;; the children have no more incoming edges (because they can only
                         ;; depend on one parent field), so in the new graph, they are now
                         ;; 'independent'.
                         (update :independent-fields into children))))))]
    (if (empty? graph)
      sorted-graph
      (throw+ {:type :CyclicDependency
               :message (format "There appears to be a cyclic dependency among the following fields: %s"
                                (prn-str (map first fields)))}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tarball default values   ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn use-tarball-defaults
  "Any field which has a :tarball_default will have that value copied
  to the :default_value. Input is a map of config-key -> definitions."
  [all-defs]
  (let [property-paths-with-tarball-defaults
        (data/map-paths (fn [k _] (= :tarball_default k))
                        all-defs)]
    (reduce (fn [defs property-path]
              (assoc-in defs
                        ;; Replace default value...
                        (conj property-path :default_value)
                        ;; With tarball default
                        (get-in defs (conj property-path :tarball_default))))
            all-defs
            ;; butlast will give us the path to the map that contains the default
            ;; by removing :tarball_default from the end
            ;; we also need it as a vector so we can conj :default_value onto the end
            ;; in the reducer.
            (map (comp vec butlast) property-paths-with-tarball-defaults))))

(defn get-template-filename
  "Get the filename of the template file for the given
   parameters.  Notice that this filename might be for
   a file that does not really exist.  This function
   returns the filename that would be used for a template
   for the given parameters. This function expects the
   caller to pass in a map containing the datastax-version
   and definitions for that version."
  [{:keys [definitions product datastax-version] :or {product "dse"}} config-key]
  (let [field-metadata (get definitions config-key)
        template-name  (get-in field-metadata
                               [:renderer :template])]
    (if (nil? template-name)
      (throw+ {:type
               :DefinitionException
               :message
               (str "There is no template definition file for "
                    (name config-key)
                    " for version " datastax-version)})
      template-name)))

(defn get-template
  "Get template string for the given parameters. Throws DefinitionException
  if the template cannot be found."
  [{:keys [datastax-version product definitions-location] :or {product "dse"} :as definitions-data}
   config-key]
  (let [template-filename (get-template-filename definitions-data config-key)
        definitions-directory (build-definitions-directory definitions-location product config-key)
        template-file      (io/file definitions-directory template-filename)]
    (if (not (.exists template-file))
      (throw+ {:type :DefinitionException
               :message (str "There is no template definition file for "
                             (name config-key)
                             " for version " datastax-version
                             ": " template-file " not found.")
               :file-path (str template-file)})
      (slurp template-file))))



;; Defaults and dependent fields

;; mapping of conditional key to operator
(def operators {:eq =
                ;; "ne" added in OpsCenter 6.1, it should not be used in definitions
                ;; for earlier OpsCenter versions.
                :ne #(not (= %1 %2))})

(defn condition-fn
  "Takes a condition map and returns a partially
  applied fn that takes 1 arg - the value being
  tested. For now we are limiting this to just 1
  operator (we take the first one we find)."
  [condition]
  (let [op-key (some (set (keys condition)) (keys operators))]
    (assert op-key (str "Field dependency conditions require a valid operator in condition: " condition))
    ;; example of the following expression:
    ;; [{:eq 5}] => (partial = 5)
    (partial (get operators op-key) (get condition op-key))))


(defn check-depends?
  "Takes a config map and a definition field-map and checks
  the value of the parent field in the config against the
  conditionals. Returns true if the dependency is satisfied
  (it also returns true if there is no dependency).
  Note: conditional attribute of the field-meta is a vector of
  the form [{:eq value1} {:eq value2}]. The dependency is satisfied
  if any of the conditions match."
  [config {:keys [depends conditional] :as field-meta}]
  (or
    (nil? depends) ;; no dependency, return true
    (and (not-empty conditional)
         (contains? config depends) ;; use contains? because the value may be literal false
         (some (fn [condition]
                 ((condition-fn condition) (get config depends)))
               conditional))))

(defn fill-in-default
  "Reducing fn for fill-in-defaults. Takes the current config
  and a single field-map entry and returns the config either
  unchanged, or with the field assoc'd with the appropriate
  default"
  [cfg [field-name field-meta]]
  (if (and
        (not (contains? cfg field-name))
        (contains? field-meta :default_value)
        (check-depends? cfg field-meta))
    (assoc cfg field-name (:default_value field-meta))
    cfg))

(defn fill-in-defaults
  "Takes a config map and a definition and fills in the missing
  defaults. Takes into account field dependencies and whether
  their conditions are satisfied in the config. Returns a new
  config map with defaults filled in."
  [config definition]
  (letfn [(fill-in-at-current-level
            [config fields]
            (reduce fill-in-default
                    config
                    (order-transitive-dependencies fields)))

          (dict?
            [{:keys [type] :as field-meta}]
            (= "dict" type))

          (user-defined-dict?
            [{:keys [type value_type] :as field-meta}]
            (and (= "user_defined" type)
                 (= "dict" value_type)))

          (child-filter
            [config [field-name field-meta]]
            ;; Need a filter function that takes a config-data map (at the appropriate depth) and
            ;; a [k v] pair from definitions fields-map. The v must have a :fields entry and it must
            ;; pass the check-depends? fn. This means either it has no dependency, or it's dependency
            ;; is satisfied in config-data.
            (and (:fields field-meta)
                 (check-depends? config field-meta)))

          (fill-in-deep
            [config fields-map]
            (reduce
              (fn [config [field-name field-meta]]
                (let [child-config
                      (cond
                        (dict? field-meta)
                        (fill-in-deep (get config field-name)
                                      (:fields field-meta))

                        (user-defined-dict? field-meta)
                        (data/map-values
                          #(fill-in-deep % (:fields field-meta))
                          (get config field-name)) ;; this is the map with user-defined keys

                        :else nil)]
                  (if (not-empty child-config)
                    (assoc config field-name child-config)
                    config)))

              ;; first, fill in defaults at this level
              ;; and use that as the starting config for
              ;; the recursive reducer.
              (fill-in-at-current-level config fields-map)

              ;; only recur for dict type fields
              (filter (partial child-filter config) fields-map)))]
    (fill-in-deep config (:properties definition))))



;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions ;;
;;;;;;;;;;;;;;;;;;;;;;;

;; these are generally useful functions outside of the definitions generator...
(def key-path->property-path gen/key-path->property-path)
(def str->property-path gen/str->property-path)

(defn property-path?
  [property-path]
  (and (= :properties (first property-path))
       (every? #{:fields}
               (remove nil? (map-indexed #(if (odd? %1) %2 nil) (rest property-path))))
       (even? (count property-path))))

(defn property-path->key-path
  "Converts definitions property paths to key paths within the config data.
  The :properties key must be included as the first path element.
  Example: [:properties :a :fields :b :fields :c] => [:a :b :c]"
  [property-path]
  {:pre [(property-path? property-path)]}
  (filterv (complement #{:properties :fields}) property-path))

(defn key-path->str
  "Converts a key path to a string representation:
  [:a :b :c] => 'a.b.c'"
  [key-path]
  {:pre [(sequential? key-path)
         (not (property-path? key-path))]}
  (apply str (interpose "." (map name key-path))))

(def key-path->keyword (comp keyword key-path->str))

(def property-path->keyword (comp key-path->keyword property-path->key-path))

(defn- path-matcher-fns
  "Returns a sequence of fns used to match config data paths.
  Example (where :b is a list type):
  [:properties :a :fields :b :fields :c] => [#{:a} #{:b} integer? #{:c}]"
  [property-path definitions]
  (loop [match-fns []
         path (rest property-path) ;; start at first field key, skip :properties
         metadata (:properties definitions)]
    (if (seq path)
      (let [field-key (first path)
            {children :fields field-type :type} (get metadata field-key)
            new-match-fns (concat match-fns
                                  [#(= field-key %)] ;; matches the field itself
                                  ;; We must inject matchers for list and user_defined
                                  ;; field types.
                                  (condp = field-type
                                    ;; list paths will have integer indices after the field key
                                    "list" [integer?]
                                    ;; user_defineds will have keyword or string keys after the field key
                                    "user_defined" [#(or (keyword? %) (string? %))]
                                    ;; no path injection for other field types
                                    []))]
        (recur new-match-fns
               (drop 2 path) ;; drop <current-field-key> and :fields
               children))    ;; if the field type or value_type is dict, this will be non-nil
      match-fns)))

(defn preference-path-matcher
  "Creates a function for matching a config path based on definitions metadata.
  This accounts for the existence of list and user_defined types in the path.
  Example (where :b is a list type):
  [:properties :a :fields :b :fields :c] => [#{:a} #{:b} integer? #{:c}]
  What is returned is a matching function that will return true given a path such
  as: [:a :b 4 :c].
  NOTE: the config path passed to the matcher function should be at the config
  file level, thus passing a whole config-profile entity will fail to match."
  [property-path definitions]
  {:pre [(property-path? property-path)
         (:properties definitions)]}
  ;; preference-path should not include [:json :dse-yaml], for example.
  (when-not (get-in definitions property-path)
    (throw+ {:type :DefinitionException
             :message (format "Property path '%s' can not be found in definitions for %s"
                              property-path (:display-name definitions))}))
  (fn [preference-path]
    (let [match-fns (path-matcher-fns property-path definitions)]
      (when (and (<= (count match-fns) (count preference-path))
                 (every? identity (map #(%1 %2) match-fns preference-path)))
        (take (count match-fns) preference-path)))))

(defn convert-to-basefile
  "For converting a specific version of a transform to a new base EDN file.
  Creates the new base file (does not update the transforms file).
  This intended for use at the REPL or wrapped for command line usage.
  It is not production code and therefore not covered by unit tests."
  ([definitions-location file-id version]
   (convert-to-basefile definitions-location file-id "dse" version))
  ([definitions-location file-id product version]
   (let [field-metadata (get-field-metadata definitions-location file-id product version)
         new-metadata-file (get-definitions-file definitions-location file-id product version :field-metadata)]
     (with-open [w (io/writer new-metadata-file)]
       (pprint field-metadata w)))))

