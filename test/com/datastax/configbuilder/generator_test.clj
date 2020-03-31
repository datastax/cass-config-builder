;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.generator-test
  (:require [com.datastax.configbuilder.generator :refer :all]
            [clojure.data :as data]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [slingshot.test :refer :all]
            [clojure.test :refer :all]))

(def definition-json-path "target/test/definitions")

(use-fixtures :once (fn [t]
                      (doseq [f (file-seq (io/file definition-json-path))]
                        (io/delete-file f true))
                      (t)))

(deftest test-key-path->property-path
  (is (= [:properties :foo :fields :bar :fields :blam]
         (key-path->property-path [:foo :bar :blam])))
  (is (= [:properties :foo :fields :bar]
         (key-path->property-path [:foo 0 :bar]))))

(deftest test-str->property-path
  (is (= [:properties :foo] (str->property-path :foo)))
  (is (= [:properties :foo :fields :bar] (str->property-path :foo.bar)))
  (is (= [:properties :foo :fields :bar :fields :blam]
         (str->property-path "foo.bar.blam"))))

(deftest test-update-field
  (let [definition
        {:properties

         {:field1
          {:type "int"
           :default_value 5}

          :field2
          {:type "dict"

           :fields
           {:field3
            {:type "string"
             :default_value "poop"}}}}}]

    (testing "update non-existent field"
      (is (thrown+? [:type :UpdateFieldException]
                    (update-field definition :bogus-field
                                  {:default_value 1}))))

    (testing "top-level field"
      (is (= 4
             (-> (update-field definition :field1 {:default_value 4})
                 :properties
                 :field1
                 :default_value))))

    (testing "nested field"
      (is (= "pee"
             (-> (update-field definition :field2.field3 {:default_value "pee"})
                 :properties
                 :field2
                 :fields
                 :field3
                 :default_value))))))

(deftest test-delete-field
  (testing "top-level field"
    (let [original-definition {:properties {:field1 {}}
                               :groupings  [{:name "g1"
                                             :list ["field1"]}]}
          modified-definition (delete-field original-definition :field1)]
      (is (not
           (contains? (:properties modified-definition) :field1)))
      (is (= [] (-> modified-definition :groupings first :list)))))

  (testing "nested field"
    (let [original-definition {:properties {:field2
                                            {:type "dict"
                                             :fields
                                             {:field3 {}
                                              :field4 {}}}}
                               :groupings  [{:name "g1"
                                             :list ["field2"]}]}
          modified-definition (delete-field original-definition :field2.field3)]
      (is (not
           (contains? (-> modified-definition
                          :properties :field2 :fields) :field3)))
      (is (= ["field2"]
             (-> modified-definition :groupings first :list)))))

  (testing "undefined field"
    (let [original-definition {:properties {:field1 {}}
                               :groupings  [{:name "g1"
                                             :list ["field1"]}]}]
      (is (thrown+? [:type :DeleteFieldException]
                    (delete-field original-definition :bogus-field))))))

(deftest test-delete-group
  (let [original-definition
        {:properties {:f1 {}}
         :groupings [{:name "asdf" :list []} {:name "g1" :list ["f1"]}]}]
    (testing "delete group"
      (let [modified-definition
            (delete-group original-definition "asdf")]
        (is (= 1 (count (-> modified-definition :groupings))))))))

(deftest test-add-field
  (let [original-definition
        {:properties {:f1 {}}
         :groupings [{:name "g1" :list ["f1"]}]}]

    (testing "top-level field with group"
      (let [modified-definition
            (add-field original-definition :f2
                       {:type "int"}
                       :group "g1")]
        (is (= {:type "int"}
               (-> modified-definition :properties :f2)))
        (is (= ["f1" "f2"]
               (-> modified-definition :groupings first :list)))))

    (testing "add top-level field that's already defined"
      (is (thrown+? [:type :AddFieldException]
                    (add-field original-definition :f1
                               {:type "int"}
                               :group "g1"))))

    (testing "top-level field without group"
      (is (thrown+? [:type :AddFieldException]
                    (add-field {} :f2 {:type "int"}))))
    (testing "top-level field with invalid group"
      (is (thrown+? [:type :GroupException]
                    (add-field {} :f2 {:type "int"}
                               :group "g2"))))

    (testing "top-level field at start of group"
      (let [modified-definition
            (add-field original-definition :f2
                       {:type "int"}
                       :group "g1"
                       :at :start)]
        (is (= ["f2" "f1"]
               (-> modified-definition :groupings first :list)))))

    (testing "top-level field after named field in group"
      (let [modified-definition
            (-> original-definition
                (add-field :f3 {} :group "g1")
                ;; keywords work for the values as well (automatically converted)
                (add-field :f2 {} :group :g1 :after :f1))]
        (is (= ["f1" "f2" "f3"]
               (-> modified-definition :groupings first :list))))))

  (testing "nested field"
    (let [original-definition
          {:properties {:f1 {:type "dict"
                             :fields
                             {:f1 {:type "int"}}}}
           :groupings [{:name "g1" :list ["f1"]}]}
          modified-definition
          (add-field original-definition :f1.f2 {:type "string"})]
      (is (= {:type "string"}
             (-> modified-definition
                 :properties :f1 :fields :f2)))
      (is (= ["f1"]
             (-> modified-definition :groupings first :list))))))

(defn load-definition
  "Loads definition file and parses the JSON"
  ([location base-filename version]
   (load-definition (io/file location (str base-filename "-" version ".json"))))
  ([definition-file]
   (with-open [r (io/reader definition-file)]
     (json/parse-stream r true))))

(defn check-transform
   "Compares the transformed definition file to the original JSON file."
   [base-filename transform-map version]
   (let [transform-data (get transform-map version)
         file-data (load-definition "../definitions/resources" base-filename version)]
     (butlast (data/diff transform-data file-data))))

(defn check-all-transforms
  "Compares each transformed definition file to the original JSON file."
  [base-filename transform-map]
  (for [version (keys transform-map)
        :let [diffs (check-transform base-filename transform-map version)]
        :when (not-every? nil? diffs)]
    [version diffs]))


(deftest test-unsweeten-conditional-value
  (testing "no value"
    (is (= [{:eq true}] (unsweeten-conditional-value nil))))
  (testing "scalar value"
    (is (= [{:eq "bar"}] (unsweeten-conditional-value "bar"))))
  (testing "map value"
    (is (= [{:eq "bar" :a 1}
            {:eq "foo" :b 2}] (unsweeten-conditional-value
                               {"bar" {:a 1}
                                "foo" {:b 2}}))))
  (testing "vector value"
    (is (= [{:eq "blah"}] (unsweeten-conditional-value [{:eq "blah"}])))))

(deftest test-unsweeten-conditionals
  (let [input {:foo {:type "string"}
               :a {:type "string", :depends :foo}
               :b {:type "string", :depends :foo, :conditional "blah"}
               :c {:type "string", :depends :foo, :conditional {"bar" {:a 1}
                                                                "foo" {:b 2}}}
               :d {:type "string", :depends :foo, :conditional [{:eq "blah"}]}}

        expected {:foo {:type "string"}
                  :a {:type "string", :depends :foo,
                      :conditional [{:eq true}]}
                  :b {:type "string", :depends :foo,
                      :conditional [{:eq "blah"}]}
                  :c {:type "string", :depends :foo,
                      :conditional  [{:eq "bar" :a 1}
                                     {:eq "foo" :b 2}]}
                  :d {:type "string", :depends :foo,
                      :conditional [{:eq "blah"}]}}]
    (testing "one level"
      (is (= expected (unsweeten-conditionals input))))
    (testing "nested"
      (let [nested {:nested-foo
                    {:type "dict"
                     :fields input}}]
        (is (= expected (-> nested
                            unsweeten-conditionals
                            :nested-foo
                            :fields)))))))

(comment
  (run-tests))
