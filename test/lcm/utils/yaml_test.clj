;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns lcm.utils.yaml-test
  (:require [lcm.utils.yaml :as yaml]
            [clojure.test :refer :all]))

(deftest test-parse
  ;; The wrapper is very thin. There is no need to test
  ;; snakeyaml itself.
  (is (= {"a" 1}
         (yaml/parse "a: 1")))
  (is (= {:a 1}
         (yaml/parse "a: 1" :keywords true)))
  (let [data (yaml/parse "
a:
  - 1
  - 2
  - 3" :keywords true)]
    (is (= {:a [1 2 3]} data))
    (is (instance? clojure.lang.APersistentVector (:a data))))

  (let [data (yaml/parse "
a: !!set
  ? 1
  ? 2
  ? 3" :keywords true)]
    (is (= {:a #{1 2 3}} data))
    (is (instance? clojure.lang.APersistentSet (:a data)))))

(deftest test-dump
  (is (= "a: 1\n"
         (yaml/dump {:a 1}))))

