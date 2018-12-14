(ns lcm.utils.version
  "Logic for working with versions such as: 4.7.0
   This namespace contains functions for comparing versions."
  (:require [clojure.string :as string]))

(def version-pattern #"\d+\.\d+(\.\d+)*")

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
    (when-let [good-version (first (re-matches version-pattern this))]
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
  (let [padding-length (- max-vector-length (count original-vector))
        padded-vec     (vec (replicate padding-length 0))]
    (if (= padded-vec 0)
      original-vector
      (vec (apply conj original-vector padded-vec)))))

(defn- version-comparator
  "Private function that will return false if the given comparison
   function returns false while comparing one of the sections
   of the dse version."
  [target-version-string version-string-to-compare comparison-function]
  (let [original-test-vector   (version-vec version-string-to-compare)
        original-target-vector (version-vec target-version-string)
        max-vector-length      (max (count original-test-vector) (count original-target-vector))]
    (loop [test-vector   (possibly-pad-vector original-test-vector max-vector-length)
           target-vector (possibly-pad-vector original-target-vector max-vector-length)]
      ;; Because of the padding, now both vectors are the same length
      (cond
        (or (empty? test-vector) (empty? target-vector))
        true

        ;; Do not recur if the current elements differ
        (not (= (first test-vector) (first target-vector)))
        (comparison-function (first test-vector) (first target-vector))

        :else
          (recur (rest test-vector) (rest target-vector))))))

(defn version-is-at-least
  "Check if a dse-version is greater-than-or-equal-to
   a target version."
  [target-version-string version-string-to-compare]
  (version-comparator target-version-string version-string-to-compare >))

(defn version-not-greater-than
  "Check if a dse-version is less-than-or-equal-to a target version.

  Be thoughtful about whether you want an inclusive or exclusive version
  comparison. If you're in a case where a new major version of DSE drops
  support for an existing feature, you want a less-than comparison, while this
  function provides a less-than-or-equal-to comparison."
  [target-version-string version-string-to-compare]
  (version-comparator target-version-string version-string-to-compare <))


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
