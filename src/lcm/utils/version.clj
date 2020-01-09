(ns lcm.utils.version
  "Logic for working with versions such as: 6.7.0
   This namespace contains functions for comparing versions."
  (:require [clojure.string :as string]))

(def version-pattern #"(\d+\.\d+(\.\d+)*)(-.*)?")

(defprotocol Version
  "Extracts a version number as a vector of ints.
  The nice thing about representing the version this way is
  that it can be properly sorted (and used as a key in a
  sorted-map).
  Example: 'cassandra-yaml-2.0.10.13.json' -> [2 0 10 13]"
  (version-vec [this]))

(extend-protocol Version

  java.lang.String
  (version-vec [this]
    ;; uses re-matches for strict match - only version strings match
    (when-let [good-version (second (re-matches version-pattern this))]
      (->> (string/split good-version #"\.")
           (map #(Integer/parseInt %))
           (vec))))

  java.io.File
  (version-vec [this]
    ;; matches filenames containing version string
    ;; strips out the version string and calls the string implementation of version-vec
    (when-let [version-string (first (re-find version-pattern (.getName this)))]
      (version-vec version-string))))

(defn version-str
  "Converts a version vector to it's string representation"
  [version-vec]
  (string/join "." version-vec))

(defn possibly-pad-vector
  "If the vector is not max-vector-length size,
   then pad it on the end with zeros."
  [original-vector max-vector-length]
  (let [padding-length (- max-vector-length (count original-vector))]
    (if (= padding-length 0)
      original-vector
      (vec (apply conj original-vector
                  (vec (replicate padding-length (int 0))))))))

(def version-comparator
  ;; Returns a java.util.Comparator capable of comparing two version strings.
  (reify java.util.Comparator
    (compare [this version-x version-y]
      (let [vector-x (version-vec version-x)
            vector-y (version-vec version-y)
            max-vector-length (max (count vector-x) (count vector-y))]
        (loop [vector-a (possibly-pad-vector vector-x max-vector-length)
               vector-b (possibly-pad-vector vector-y max-vector-length)]
          ;; Because of the padding, now both vectors are the same length
          (cond
            (or (empty? vector-a) (empty? vector-b))
            0 ;; the versions are equal!

            ;; Do not recur if the current elements differ
            (not (= (first vector-a) (first vector-b)))
            (.compareTo (first vector-a) (first vector-b))

            :else
            (recur (rest vector-a) (rest vector-b))))))))

(defn version-is-at-least
  "Check if a dse-version is greater-than-or-equal-to
   a target version."
  [target-version-string version-string-to-compare]
  (>= 0 (.compare version-comparator target-version-string version-string-to-compare)))

(defn version-not-greater-than
  "Check if a dse-version is less-than-or-equal-to a target version.

  Be thoughtful about whether you want an inclusive or exclusive version
  comparison. If you're in a case where a new major version of DSE drops
  support for an existing feature, you want a less-than comparison, while this
  function provides a less-than-or-equal-to comparison."
  [target-version-string version-string-to-compare]
  (<= 0 (.compare version-comparator target-version-string version-string-to-compare)))

(defn version-matches?
  "Given a version pattern such as '6.0.1' or '6.0.x', tests whether the given
   version-string matches. The .x suffix matches any sub-version, including the
   absence of the sub-version number. Example: '6.0.x' matches '6.0' as well as
   '6.0.0'"
  [version-pattern version-string]
  (if (string/ends-with? version-pattern ".x")
    (or
     ;; exact match up to the .x => '6.0' matches '6.0.x'
     (= version-string (apply str (drop-last 2 version-pattern)))
     ;; ensures the last '.' is used in the match, preventing false matches
     ;; on double-digit versions, ie. '6.1.x' matches '6.1.1', not '6.12.1'
     (string/starts-with? version-string (apply str (drop-last version-pattern))))
    ;; with no .x suffix, do exact match only
    (= version-pattern version-string)))

(defn fallback
  "Returns the maximum version from the collection that is less than or equal
  to target-version. Returns nil if nothing from the collection matches this
  criteria."
  [target-version versions]
  ;; Drop versions that are greater than target-version, then sort in
  ;; reverse order and the fallback version will be the first item.
  (first
   (sort version-not-greater-than
         (filter (partial version-not-greater-than target-version) versions))))

(defn get-fallback
  "Functions like get called on a map, but assumes keys are versions and will
  match fallback version. "
  ;; Example: (get {"6.0.0" 5} "6.0.12") => 5
  [version-map target-version]
  (or (get version-map target-version)
      (get version-map (fallback target-version (keys version-map)))))
