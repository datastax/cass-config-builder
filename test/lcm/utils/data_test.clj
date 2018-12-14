(ns lcm.utils.data-test
  (:refer-clojure :exclude [uuid?])
  (:require [lcm.utils.data :refer :all]
            [slingshot.test :refer :all]
            [clojure.test :refer :all]))

(deftest test-truncate-string
  (is (= "hell..." (truncate-string "hello" 4)))
  (is (= "hello" (truncate-string "hello" 9)))
  (is (= "h..." (truncate-string "hello" 1)))
  (is (nil? (truncate-string nil 10))))

(deftest test-uuid?
  (is (uuid? "352f86d8-9019-46e9-8a4a-37d6cce8de65"))
  (is (not (uuid? "I'm not a uuid")))
  (is (not (uuid? {:me "neither"})))
  (is (not (uuid? nil))))

(deftest test-insert-into-vector
  (is (= [:a :b :c] (insert-into-vector [:a :c] 1 :b)))
  (is (= [:a :b :c] (insert-into-vector [:b :c] 0 :a)))
  (is (= [:a :b :c] (insert-into-vector [:a :b] 2 :c)))
  (is (thrown+? java.lang.IndexOutOfBoundsException
                (insert-into-vector [:a :b] 42 :c))))

(deftest test-remove-from-vector
  (is (= [:a :b] (remove-from-vector [:a :b :c] 2)))
  (is (= [:a :c] (remove-from-vector [:a :b :c] 1)))
  (is (= [:b :c] (remove-from-vector [:a :b :c] 0)))
  (is (thrown+? java.lang.IndexOutOfBoundsException
                (remove-from-vector [:a :b :c] 42))))

(deftest test-add-to-vector
  (is (= [:a :b :c]
         (add-to-vector [:a :b] :c)))
  (is (= [:a :b :c]
         (add-to-vector [:a :b] :c :at :end)))
  (is (= [:a :b :c]
         (add-to-vector [:b :c] :a :at :start)))
  (is (= [:a :b :c]
         (add-to-vector [:a :c] :b :after :a)))
  (is (= [:a :b :c]
         (add-to-vector [:a :b] :c :after :b))))

(deftest test-find-index
  (is (= 0 (find-index #{:a} [:a :b :c])))
  (is (= 1 (find-index #(= "foo" (:name %))
                       [{:name "bar"} {:name "foo"}]))))

(deftest test-deep-merge
  (is (= {:a 1
          :one {:b 2
                :two {:c 3
                      :three {:d 4
                              :four 4}}}}
         (deep-merge {:one {:two {:three {:four 4}}}}
                     {:a 1
                      :one {:b 2
                            :two {:c 3
                                  :three {:d 4}}}}))))

(deftest test-two-level-merge
  (is (= {:a 1
          :one {:b 2
                :two {:c 3}}} ;; replace map at :two

         (two-level-merge
          {:one {:two {:three {:four 4}}}}
          {:a 1
           :one {:b 2
                 :two {:c 3}}}))))


(deftest test-map-by
  (let [maps [{:id 1 :name "foo"} {:id 2 :name "bar"}]]
    (is (= {1 {:id 1 :name "foo"} 2 {:id 2 :name "bar"}}
           (map-by :id maps)))))

(deftest test-asymmetric-interleave
  (is (= [] (asymmetric-interleave)))
  (is (= [] (asymmetric-interleave [])))
  (is (= [1 2] (asymmetric-interleave [1 2])))
  (is (= [1 2] (asymmetric-interleave [1 2] [])))
  (is (= [1 3 2] (asymmetric-interleave [1 2] [3])))
  (is (= [3 1 2] (asymmetric-interleave [3] [1 2]))))

(deftest get-by-val-test
  (let [searchme [{:k1 1} {:k2 2} {"string-key" 3}]]
    (is (= {:k1 1}
           (get-by-val searchme :k1 1))
        "Successfully get a map by value")
    (is (= {"string-key" 3}
           (get-by-val searchme "string-key" 3))
        "Able to get get keys that aren't keywords")
    (is (nil? (get-by-val searchme :k3 3))
        "Return nil if key is missing")
    (is (nil? (get-by-val searchme :k1 10))
        "Return nil if value doesn't match"))
  (is (= {:k1 1, :k2 2}
         (get-by-val [{:k1 1, :k2 2}
                      {:k1 1, :k2 20}]
                     :k1 1))
      "If there are multiple matches, return the first"))

(deftest test-format-map
  (is (= "key1=\"val1\" key3=\"val3\" key4=\"\\\"val4\\\"\""
         (format-map {:key1 "val1"
                      :key2 nil
                      :key3 "val3"
                      :key4 "\"val4\""}))))

(deftest test-map-values
  (is (= {:a 2 :b 3 :c 4}
         (map-values inc {:a 1 :b 2 :c 3})))
  (is (= {:a 2 :b 3 :c 4 :d 5}
         (map-values inc (hash-map :a 1 :b 2 :c 3 :d 4)))))

(deftest test-as-boolean
  (is (true? (as-boolean 1)))
  (is (false? (as-boolean 0)))
  (is (true? (as-boolean true)))
  (is (false? (as-boolean false)))
  (is (true? (as-boolean "True")))
  (is (true? (as-boolean "true")))
  (is (true? (as-boolean "1")))
  (is (false? (as-boolean "False")))
  (is (false? (as-boolean "false")))
  (is (false? (as-boolean "0")))
  (is (thrown+? IllegalArgumentException
                (as-boolean 5)))
  (is (thrown+? IllegalArgumentException
                (as-boolean "foobie"))))

(deftest test-as-int
  (is (= 5 (as-int 5)))
  (is (= 1 (as-int true)))
  (is (= 0 (as-int false)))
  (is (= 5 (as-int "5"))))

(deftest test-sliding-map
  (is (= [3 5 7 9] (sliding-map 2 + [1 2 3 4 5])))
  (is (= [15] (sliding-map 8 + [1 2 3 4 5])))
  (is (= [1 2 3 4 5] (sliding-map 1 + [1 2 3 4 5])))
  (is (= [1 2 3 4 5] (sliding-map 0 + [1 2 3 4 5]))))

(deftest test-map-paths
  (is (= [[:a :b :c]] (map-paths {:a {:b {:c 1}}})))
  (is (= [[:a :b :c] [:a :b :d]] (map-paths {:a {:b {:c 1 :d "foo"}}})))
  (is (= [[:a :b :c] [:a :d]] (map-paths {:a {:b {:c 1} :d 4}})))
  (is (= [[:a]] (map-paths {:a [1 2 3]})))
  (is (= [] (map-paths #(even? %2) {:a {:b {:c 1}}})))
  (is (= [[:a :b :c]] (map-paths #(odd? %2) {:a {:b {:c 1}}}))))

(deftest test-all-paths
  (is (= [[:a 0] [:a 1] [:a 2]] (all-paths {:a [1 2 3]}))))

(deftest test-patch-vector
  (is (= [1 4 5] (patch-vector [nil 2] [nil 4] [1 nil 5])))
  (is (= [1 2 3] (patch-vector nil nil [1 2 3])))
  (is (= [1 2 3] (patch-vector [] [] [1 2 3])))
  (is (= [1 2 3] (patch-vector [1 2 3 4] nil [1 2 3 nil]))))

(deftest test-prune
  (is (= {} (prune {:a {}})))
  (is (= {} (prune {:a []})))
  (is (= [] (prune [{} []])))
  (is (= {:a [:b]} (prune {:a [{} :b []]})))
  (is (= {:a {:b {:c [1]}}}
         (prune {:a {:b {:c [[] 1 {}]}
                     :d {}
                     :e []}})))
  (is (= {} (prune {:a [{}]}))))

(deftest test-find-index-of-item-by-pred
  (let [idx-value-fn (comp keyword str)
        data (map idx-value-fn (range 50))
        test-fn (fn [data]
                  (testing "returns the index of the first item for which pred returns thruthy"
                    (is (= 5 (find-index-of-item-by-pred #{:5 :50 :71}
                                                         data)))
                    (doseq [i (range 50)]
                      (is (= i (find-index-of-item-by-pred #(= % (idx-value-fn i))
                                                           data)))))
                  (testing "returns nil if pred does not return truthy for any item in the collection"
                    (is (= nil (find-index-of-item-by-pred #{:does-not-exist}
                                                           data)))))]
    (testing "Given a LazySeq"
      (test-fn data))
    (testing "Given a vector"
      (test-fn (vec data)))
    (testing "Given a list"
      (test-fn (apply list data)))))

(deftest test-extract-item-from-vector-by-pred
  (let [idx-value-fn (comp keyword str)
        data (into [] (map idx-value-fn (range 10)))]
    (testing "Returns the first item matching pred plus vector without item"
      (is (= [:3 [:0 :1 :2 :4 :5 :6 :7 :8 :9]]
             (extract-item-from-vector-by-pred #{:4 :3 :5 :10} data)))
      (doseq [i data]
        (let [[match remaining] (extract-item-from-vector-by-pred #{i} data)]
          (is (= i match))
          (is (vector? remaining))
          (is (= (remove #{i} data)
                 remaining)))))
    (testing "Returns nil for match and given vector if pred does not match any element"
      (is (= [nil data]
             (extract-item-from-vector-by-pred #{:does-not-exist} data))))))

(comment
  (run-tests))
