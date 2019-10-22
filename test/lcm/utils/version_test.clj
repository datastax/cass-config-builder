(ns lcm.utils.version-test
  (:require [lcm.utils.version :as version]
            [slingshot.test :refer :all]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(deftest test-version-vec
  (testing "from strings"
    (is (= (version/version-vec "6.7.0.1")
           [6 7 0 1]))
    (is (= (version/version-vec "6.7.0")
           [6 7 0]))
    (is (= (version/version-vec "4.8")
           [4 8])))
  (testing "from file"
    (is (= (version/version-vec (io/file "foobie-4.3.21.tgz"))
           [4 3 21])))
  (testing "bad versions, should return nil"
    (is (every? nil?
                (map version/version-vec
                     ["vvdfvd12.3.5"
                      "..."
                      "a.b.c"
                      "12.a.5"])))))

(deftest test-version-str
  (is (= (version/version-str [4 8 0])
         "4.8.0")))

(deftest test-possibly-pad-vector
  (is (= (version/possibly-pad-vector [1 2] 2)
         [1 2]))
  (is (= (version/possibly-pad-vector [1 2] 3)
         [1 2 0]))
  (is (= (version/possibly-pad-vector [1 2] 4)
         [1 2 0 0]))
  (is (= (version/possibly-pad-vector [4 7 1] 4)
         [4 7 1 0])))

(deftest test-version-is-at-least
  (is (version/version-is-at-least "6.7.0" "6.7.0"))
  (is (version/version-is-at-least "6.7.0" "6.7.1"))
  (is (version/version-is-at-least "6.7.1" "6.8.0"))
  (is (not (version/version-is-at-least "6.7.1" "6.7.0")))
  (testing "Hotfix version suffix"
    (is (version/version-is-at-least "6.7.0" "6.7.4-latest")))
  (testing "When the version number is 2 digits or less"
    (is (version/version-is-at-least "6.7.0" "6.7"))
    (is (version/version-is-at-least "6.7" "6.7.0"))
    (is (version/version-is-at-least "6.7" "6.7-latest"))
    (is (not (version/version-is-at-least "6.7.0" "6.6")))
    (is (not (version/version-is-at-least "6.7" "6.6.9"))))
  (testing "When the version numbers are not the same length"
    (is (not (version/version-is-at-least "6.7.1" "6.7.0.1")))
    (is (version/version-is-at-least "6.7.0" "6.7.0.1"))
    (is (version/version-is-at-least "6.7.1.0" "6.7.1"))
    (is (not (version/version-is-at-least "6.7.1.1" "6.7.1")))))

(deftest test-version-not-greater-than
  (is (version/version-not-greater-than "6.7.0" "6.7.0"))
  (is (not (version/version-not-greater-than "6.7.0" "6.7.1")))
  (is (version/version-not-greater-than "6.7.1" "6.7.0"))
  (is (version/version-not-greater-than "6.8.0" "6.7.1")))

(deftest test-version-matches?
  (is (version/version-matches? "6.1.x" "6.1"))
  (is (version/version-matches? "6.1.x" "6.1.0"))
  (is (version/version-matches? "6.1.x" "6.1.15"))
  (is (not (version/version-matches? "6.1.x" "6.13.1")))
  (is (version/version-matches? "6.1" "6.1"))
  (is (not (version/version-matches? "6.1" "6.1.2"))))
