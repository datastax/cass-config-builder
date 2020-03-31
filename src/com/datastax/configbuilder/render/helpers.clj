;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.render.helpers)

(defn render-non-constant-using-metadata
  [single-field-metadata override-value]
  (let [value        (if (nil? override-value)
                       (get single-field-metadata :default_value)
                       override-value)]
    value))

(defn render-static-constant-using-metadata
  "If the field is boolean true, then render the static_constant property"
  [single-field-metadata override-value]
  (let [static-value (:static_constant single-field-metadata)
        enabled?     (if (nil? override-value)
                       (get single-field-metadata :default_value false)
                       override-value)]
    (if enabled?
      static-value
      "")))

(defn render-constant-using-metadata
  "This is the interface for rendering constants.

  The name of the constant will be the :constant property.

  The value of the constant will be quoted if the :type is string

  If :override-value and :default_value are both nil, we render nothing.

  If :render-without-quotes is defined and true,
  strings will be rendered without their surrounding quotes

  If :suppress-equal-sign is defined and true,
  no equal sign will be rendered.

  If :add-export is defined and true, export will be prepended
  "
  [single-field-metadata override-value]
  (let [should-quote-value    (and (= (:type single-field-metadata) "string")
                                   (not (and (contains? single-field-metadata :render-without-quotes)
                                             (get single-field-metadata :render-without-quotes))))
        constant-value        (if (nil? override-value)
                                (get single-field-metadata :default_value)
                                override-value)
        constant-name         (:constant single-field-metadata)
        add-export-prefix     (if (and (contains? single-field-metadata :add-export)
                                       (get single-field-metadata :add-export))
                                "export "
                                "")
        equal-sign            (if (get single-field-metadata :suppress-equal-sign false)
                                ""
                                "=")
        value-quoting         (if should-quote-value
                                "\""
                                "")]
    (if (nil? constant-value)
      ""
      (str add-export-prefix
           constant-name
           equal-sign
           value-quoting
           constant-value
           value-quoting))))
