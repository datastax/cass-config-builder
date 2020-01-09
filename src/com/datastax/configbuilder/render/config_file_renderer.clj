(ns com.datastax.configbuilder.render.config-file-renderer
  (:require [com.datastax.configbuilder.definitions :as d]
            [com.datastax.configbuilder.render.helpers :as rh]
            [lcm.utils.data :refer [as-int]]
            [lcm.utils.yaml :as yaml]
            [selmer.parser :refer [render]]
            [selmer.util :refer [without-escaping]]
            [selmer.filters :as filters]
            [slingshot.slingshot :refer [throw+]]
            [clojure.string :as string])
  (:import [org.apache.commons.codec.net URLCodec]))

(defn urlencode-filter
  [original-string]
  ;; This encodes special characters, but replaces space with +
  ;; This doesn't work when a literal space is in the userinfo
  ;; portion of the URL, as it will be left as a + instead of
  ;; being converted back into space. Thus, we must replace
  ;; + after encoding with %20.
  (.replace
    (.encode (URLCodec. "UTF-8") original-string)
    "+" "%20"))

(defn get-java-minor-version-filter
  "A filter for selmer templates to extract the minor number from a java version
  For example '1.7.0' -> 7 and '1.8.0' -> 8."
  [version-string]
  (-> version-string
      (string/split #"\.")
      second
      as-int))

(defn sanitize-bash-env-var-value
  "A filter to sanitize the value of a bash environment variable so that it cannot
  be used as a shell-code injection.
  This will use a whitelist of:

  - letters
  - numbers
  - single space
  - dash and underscore
  - colon, comma, and period
  - forward slash
  - plus and equal signs"
  [raw-value]
  (string/replace raw-value
                  #"[^-a-zA-Z0-9 _:,./+=]"
                  ""))

;; Adds filters to the selmer renderer globally
;; These functions can be used inside selmer templates to process the
;; values passed into the template. For example:
;; (selmer/render "{{version|get-java-minor-version}}"
;;                {:version "1.7.0" })
;; => "7"
(filters/add-filter! :get-java-minor-version get-java-minor-version-filter)
(filters/add-filter! :urlencode urlencode-filter)
(filters/add-filter! :sanitize-bash-env-var-value sanitize-bash-env-var-value)

;; These filters allow us to iterate over maps
;; See: https://stackoverflow.com/a/35074049
(filters/add-filter! :key key)
(filters/add-filter! :val val)

(defn process-constants
  "For every field that has a :constant name, determine if we render
  a line that is commented out or a name and the value.

  For every metadata field that has a :static-constant property,
  render that property if the metadata field is boolean true.

  Also, we will go ahead and combine the defaults of the nonconstants
  with their field values.

  The result of this function will be a map of properties.

  The value of a constant will be its rendered version,
  and the value of a non-constant will be either its default
  or field value.

  Constants will be ommitted if they have no field-value and no default_value.

  Notice that fields that do not appear in field-values will not be
  added to the result. This is because filtering of dependent fields
  should have already been done for field-values but not for field-metadata."
  [field-metadata field-values]
  (let [properties             (:properties field-metadata)
        constants              (filter
                                 (fn [[k v]] (and (:constant v)
                                                  (contains? field-values k)))
                                 properties)
        static-constants       (filter
                                 (fn [[k v]] (and (:static_constant v)
                                                  (contains? field-values k)))
                                 properties)
        non-constants          (filter
                                 (fn [[k v]] (and (not (or (:constant v)
                                                           (:static_constant v)))
                                                  (contains? field-values k)))
                                 properties)
        rendered-non-constants (reduce (fn [result [k single-field-metadata]]
                                         (assoc result
                                           k
                                           (rh/render-non-constant-using-metadata
                                             single-field-metadata
                                             (get field-values k nil))))
                                       field-values
                                       non-constants)
        rendered-constants     (reduce (fn [result [k single-field-metadata]]
                                         (assoc result
                                           k
                                           (rh/render-constant-using-metadata
                                             single-field-metadata
                                             (get field-values k nil))))
                                       rendered-non-constants
                                       constants)
        rendered-all-constants (reduce (fn [result [k single-field-metadata]]
                                         (assoc result
                                           k
                                           (rh/render-static-constant-using-metadata
                                             single-field-metadata
                                             (get field-values k nil))))
                                       rendered-constants
                                       static-constants)]
    rendered-all-constants))

(defn coerce-types
  "Certain fields may be stored in one data format, but must be rendered to another.
  As an example, :spark-enabled is a boolean, but must be rendered as a 1 or 0."
  [field-metadata values]
  (let [properties (:properties field-metadata)
        coercers   (reduce
                     (fn [m [k v]]
                       (condp = (:render_as v)
                         "int"  (assoc m k as-int)
                         m)) ;; else, there is no coercer func
                     {} properties)]
    (reduce (fn [m [k coerce]]
              (if (not (nil? (get m k)))
                (assoc m k (coerce (get m k)))
                m))
            values
            coercers)))

(defn determine-render-values
  "Determine the render values using field metadata
   along with a map of field-values."
  [field-metadata field-values]
  (coerce-types field-metadata (process-constants field-metadata field-values)))

(defn render-to-template
  "Takes the contents of a field metadata .edn file, a map of
   field-values with key => keyword of variable in template,
   and a string that is the contents of a template and renders
   the template with the field metadata defaults and field values."
  [{:keys [definitions] :as definitions-data}
   config-key
   config-data]
  (without-escaping
    (let [field-metadata (get definitions config-key)
          field-values (get config-data config-key)
          properties (:properties field-metadata)
          render-values (determine-render-values field-metadata field-values)
          template (d/get-template definitions-data config-key)

          ;; Work around a selmer issue that ignores boolean false values
          ;; https://github.com/yogthos/Selmer/issues/122
          stringified-render-values (reduce-kv (fn [m k v]
                                                 (if (false? v)
                                                   (assoc m k (str v))
                                                   (assoc m k v)))
                                               {}
                                               render-values)

          ;; Some templates loop over all the rendered values,
          ;; so we gather them into template-iterables for those situations.
          ;; A field can be excluded from this list by setting
          ;; :exclude-from-template-iterables to true
          ;; Note that we sort the render-values by key to always get the values
          ;; in the same order.  The unit tests rely on this ordering.
          template-iterables (vals
                               (into
                                 (sorted-map)
                                 (reduce-kv (fn [m k v]
                                              (if (get-in properties
                                                          [k :exclude-from-template-iterables]
                                                          false)
                                                m
                                                (assoc m k v)))
                                            {}
                                            stringified-render-values)))
          result (render template
                         (assoc stringified-render-values
                           :template_iterables
                           template-iterables))]
      result)))

(defn render-config-file
  "This will render the configuration file to either yaml
   or a template.  This function does not determine what
   the field values are, those are processed by the
   caller."
  [{:keys [definitions datastax-version] :as definitions-data}
   config-key
   config-data]
  (let [field-metadata (get definitions config-key)
        field-values (get config-data config-key)]
    (if (empty? field-metadata)
      (throw+
        {:type    :MissingDefinitions
         :message (str "There is no field metadata available for "
                       "configuration file: "
                       (name config-key)
                       " for version "
                       datastax-version)})
      (case (get-in field-metadata [:renderer :renderer-type])
        :yaml (yaml/dump field-values)
        :template (render-to-template definitions-data config-key config-data)))))
