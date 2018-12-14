(ns lcm.utils.data
  (:refer-clojure :exclude [uuid?])
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import [java.util UUID]
           [java.lang IllegalArgumentException]))

(defn sec->ms
  "Multiplies a number by 1000,
  and declares that the reason you're doing so is for a time conversion"
  [seconds]
  (* 1000 seconds))

(defn uuid
  "Returns a string representing a java.util.UUID/randomUUID"
  []
  (-> (UUID/randomUUID) str))

(defn uuid?
  "Returns true if value is a string representing a java.util.UUID"
  [value]
  (and (string? value)
       (try+
        (boolean (UUID/fromString value))
        (catch IllegalArgumentException _
          ;; String, but not a valid UUID
          false))))

(defn truncate-string
  "Caps a string to a given length (+3 characters for an ellipsis)"
  [s length]
  (cond
    (nil? s) nil
    (< (count s) length) s
    :else (str (subs s 0 length) "...")))

(defn sanitize-filename [filename]
  (string/replace filename #"[^a-zA-Z0-9\._]+" "_"))

(defn deep-merge
  "Recursively merges maps. If vals are not maps, the last value wins."
  [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))

(defn n-level-merge
  [n & vals]
  (if (and (< 0 n) (every? map? vals))
    (apply merge-with (partial n-level-merge (dec n)) vals)
    (last vals)))

(def two-level-merge (partial n-level-merge 2))

(defn remove-from-vector
  "Removes an item at the specified index and returns a new vector."
  [v idx]
  (vec (concat (subvec v 0 idx) (subvec v (inc idx)))))

(defn insert-into-vector
  "Inserts a value into a vector at the specified index."
  [v idx value]
  (vec (concat (subvec v 0 idx) [value] (subvec v idx))))

(defn add-to-vector
  "Adds a value to a vector.
  :at can be :start or :end, and will determine where the value will
  be added. Alternatively, :after can be passed with a value alread
  in the vector to insert the value somewhere in the middle.
  Examples:
  (add-to-vector [:a] :b) -> [:a :b]
  (add-to-vector [:a] :b :at :start) -> [:b :a]
  (add-to-vector [:a :c] :b :after :c) -> [:a :b :c]"
  [v x & {:keys [at after]
            :or {at :end}}]
  (cond

   after
   (insert-into-vector v (inc (.indexOf v after)) x)

   (= :start at)
   (vec (cons x v))

   :else
   (conj v x)))

(defn find-indexes
  "Given a predicate func, will return the indexes of the vector
  item that satisfies the condition."
  [pred v]
  (keep-indexed
   (fn [idx x]
     (when (pred x) idx)) v))

(defn find-index
  "Given a predicate func, will return the first index of the vector
  item that satisfies the condition."
  [pred v]
  (first (find-indexes pred v)))

(defn map-by
  "Converts a sequence of maps into a map of maps using the given key.
  Does not check for key uniqueness."
  [key s]
  (reduce conj {} (map #(vector (get % key) %) s)))

(defn map-values
  "Updates each value in a map using update-function."
  [update-function original-map]
  (zipmap (keys original-map) (map update-function (vals original-map))))

(defn select-values
  "Extracts values from a map by key

  Accepts:
    col: A map
    ks: A list of key-names in the map

  Returns: A vector containing the values of those keys

  For example:
    (select-values {:a 1 :b 2 :c 3} [:a :b])
    => [1 2]"
  [col ks]
  (-> col
      (select-keys ks)
      vals
      vec))

(defn format-map
  "Accepts a map, outputs a string with the MapEntries formatted at
  'key1=val1 key2=val2 ...'

  Ignores keys with a value of nil
  Doesn't currently have special handling for nesting"
  [m]
  (let [non-nil-entries (filter (fn [[_ v]] (not (nil? v))) m)
        strings (->> non-nil-entries
                     ;; Escape quotes
                     (map (fn [[k v]] [k (string/replace v "\"" "\\\"")]))
                     ;; Format each k/v pair, no recursion for now
                     (map (fn [[k v]] (format "%s=\"%s\"" (name k) v))))]
    (string/join " " strings)))

(defn format-seq
  "Converts things like #{:f1 :f2} to 'f1, f2' for error messages"
  [things]
  (string/join ", " (sort (map name things))))

(defn get-by-val
  "Convenience function that searches a list of maps to fetch one by key/val.
  Returns the first matching map,if your value isn't unique, consider using
  filter directly.

  Accepts:
    - A list containing maps that will be searched.
    - A key, maps will be filtered such that they must contain the key
    - A value, maps will be filtered such that the key must have this value

  Returns: The first map matching the key/val, or nil.

  For example:
  (let [searchme [{:id 1 :more-keys 10}
                  {:id 2 :more-keys 20}]]
    (get-by-val searchme :id 1))
  => Returns {:id 1 :more-keys 10}"
  [list key val]
  (first (filter #(= (get % key) val) list)))

(defn asymmetric-interleave
  ([] ())
  ([c1] c1)
  ([c1 & colls]
   (let [all-colls (cons c1 colls)
         max-len (reduce max 0 (map count all-colls))
         padded-colls (map
                       (fn [coll]
                         (take max-len (concat coll (repeat ::temp))))
                       all-colls)]
     (filter #(not= ::temp %)
             (apply interleave padded-colls)))))

(defprotocol AsInt
  (as-int [x]))

(extend-protocol AsInt
  java.lang.Boolean
  (as-int [x] (if x 1 0))

  java.lang.Integer
  (as-int [x] x)

  java.lang.Long
  (as-int [x] x)

  java.lang.String
  (as-int [x] (Integer/parseInt x)))


(defprotocol AsBoolean
  (as-boolean [x]))

(extend-protocol AsBoolean
  java.lang.Boolean
  (as-boolean [x] x)

  java.lang.Number
  (as-boolean [x]
    (cond (zero? x) false
          (= 1 x) true
          :else (throw (IllegalArgumentException.
                        (format "%d cannot be converted to a boolean" x)))))

  java.lang.String
  (as-boolean [x]
    (cond
      (#{"True" "true" "1"} x)   true
      (#{"False" "false" "0"} x) false
      :else (throw (IllegalArgumentException.
                    (format "%s can not be converted to a boolean" x))))))

(defn sliding-map
  "Takes a collection and effectively creates a sliding 'window' of size n,
  then maps over those windows. The following are all equivalent:

  (sliding-map 2 + [1 2 3 4 5]) => [3 5 7 9]

  ;; This is actually how it gets evaluated:
  (map + [1 2 3 4 5] [2 3 4 5]) => [3 5 7 9]

  ;; This is easier to think about, but is the same thing...
  (map #(apply + %) [[1 2] [2 3] [3 4] [4 5]]) => [3 5 7 9]"
  [n f coll]
  (cond
    ;; Treat a zero window size as a size of 1
    (zero? n)
    (map f coll)

    (< n (count coll))
    (apply map f (for [i (range n)] (drop i coll)))

    ;; if window size is gte the length of coll, just call f with
    ;; coll as the args and wrap in a sequence
    :else
    [(apply f coll)]))

(defn paths*
  "Returns paths to leaf nodes by using branch? fn to know when to traverse
  deeper into the tree, and include? to filter paths based on the leaf value.
  include? takes the key and value as parameters."
  ([branch? m]
   (paths* branch? (constantly true) m))
  ([branch? include? m]
   (letfn [(step [acc current-path x]
             (reduce-kv (fn [acc k v]
                          (let [path (conj current-path k)]
                            (if (branch? v)
                              (step acc path v)
                              (if (include? k v)
                                (conj acc path)
                                acc))))
                        acc, x))]
     (step [] [] m))))

(defn map-paths
  "Returns all paths to leaf nodes in m, but only traverses maps (does not
  traverse into vectors, lists, etc.)"
  ([include? m]
   (paths* map? include? m))
  ([m]
   (paths* map? m)))

(defn all-paths
  "Returns all paths to leaf nodes in m. Traverses anything that matches
  associative? - so it is like map-paths but also traverses vectors."
  ([include? m]
   (paths* associative? include? m))
  ([m]
   (paths* associative? m)))

(defn patch-vector
  "Reconciles diff results for vectors to rebuild the state of the new data,
  where 'new data' is the second argument to the diff call:
  (diff old-data new-data) => [things-in-old, things-in-new, things-in-both]
  This function reverses that expression to rebuild new-data.

  It is important to realize that things-in-old contains things that are *only*
  in old-data. The same goes for things-in-new.

  Example:
  (patch-vector [nil 2] [nil 4] [1 nil 5]) => [1 4 5]"
  [things-in-old things-in-new things-in-both]
  (cond
    ;; If things-in-new is nil, it means nothing new was added. If things-in-old
    ;; is not nil, then it means some things were removed in new-data. We Simply
    ;; need to return things-in-both with nils filtered out.
    (and things-in-old (empty? things-in-new))
    (filterv identity things-in-both)

    ;; When things-in-new is present, it may contain nils. These nils represent
    ;; that were present in both vectors, thus we must grab the value from
    ;; the same position in things-in-both and inject it in place of the nil.
    ;; Also, things-in-both might include items beyond the last index of
    ;; things-in-new, and these must be appended.
    things-in-new
    (into []
          (concat
           (map-indexed (fn [idx x]
                          (if (nil? x)
                            (get things-in-both idx)
                            x))
                        things-in-new)
           (drop (count things-in-new) things-in-both)))

    ;; No changes at all
    (and (empty? things-in-old) (empty? things-in-new))
    things-in-both

    :else nil))

(def empty-coll? #(and (coll? %) (empty? %)))

(defn prune
  "Prunes empty branches from the datastructure. This includes map
  entries whose values are empty collections. It will also remove empty collection
  items from sequential collections like vectors.
  Examples:
   {:a {}} => {}
   {:a []} => {}
   [{} []] => []
   {:a [:b {} []]} => {:a [:b]}"
  [data]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x) (reduce (fn [m [k v]]
                          (if (empty-coll? v)
                            (dissoc m k)
                            m))
                        x x)
       ;; Hacky, but works around a bug in postwalk where map entries are just
       ;; normal vectors, else map-entry? would work here.
       ;; See https://dev.clojure.org/jira/browse/CLJ-2031
       (and (vector? x)
            (= 2 (count x))
            (some identity ((juxt keyword? string?) (first x)))) x
       (sequential? x) (filterv (complement empty-coll?) x)
       :else x))
   data))

(defn find-index-of-item-by-pred
  "Gives the index of the first element of items for which pred is truthy.
  If no such element is found, returns nil."
  [pred items]
  {:pre [(sequential? items)]}
  (let [item-idx (reduce (fn [idx context]
                           (if (pred context)
                             (reduced idx)
                             (inc idx)))
                         0
                         items)]
    (if (< item-idx (count items))
      item-idx
      nil)))

(defn extract-item-from-vector-by-pred
  "Returns a vector [match remaining] where match is the first element of items
  for which pred returns truthy and remaining is a vector containing all
  elements of items, excluding match, in the same order.

  We require items to be a vector principally for performance reasons."
  [pred items]
  {:pre [(vector? items)]}
  (let [length (count items)
        item-idx (find-index-of-item-by-pred pred items)]
    (if item-idx
      [(nth items item-idx) (into (subvec items 0 item-idx)
                                  (subvec items (inc item-idx) length))]
      [nil items])))
