;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.render.helpers-test
  (:require [com.datastax.configbuilder.render.helpers :as helpers]
            [slingshot.test :refer :all]
            [clojure.test :refer :all]))

(deftest test-render-static-constant-using-metadata
  (is (= (helpers/render-static-constant-using-metadata
          {:static_constant "-ea" :label "Foo" :type "boolean" :default_value true}
          nil)
         "-ea"))
  (is (= (helpers/render-static-constant-using-metadata
          {:static_constant "-ea" :label "Foo" :type "boolean" :default_value false}
          nil)
         ""))
  (is (= (helpers/render-static-constant-using-metadata
          {:static_constant "-ea" :label "Foo" :type "boolean" :default_value true}
          false)
         ""))
  (is (= (helpers/render-static-constant-using-metadata
          {:static_constant "-ea" :label "Foo" :type "boolean" :default_value false}
          true)
         "-ea")))

(deftest test-render-constant-using-metadata
  (is (= (helpers/render-constant-using-metadata
          {:constant "A" :label "Foo" :type "string"}
          nil)
         ""))
  (is (= (helpers/render-constant-using-metadata
           {:constant "A" :label "Foo" :type "string" :default_value "B"}
           nil)
         "A=\"B\""))
  (is (= (helpers/render-constant-using-metadata
           {:constant "A" :label "Foo" :type "string" :default_value "B"}
           "345")
         "A=\"345\""))

  (is (= (helpers/render-constant-using-metadata
           {:constant "A" :label "Foo" :type "string" :default_value "B"}
           "B")
         "A=\"B\""))
  (is (= (helpers/render-constant-using-metadata
           {:constant "-Xss" :label "Foo" :type "string"
            :default_value "64k" :suppress-equal-sign true :render-without-quotes true}
           "128k")
         "-Xss128k"))
  (is (= (helpers/render-constant-using-metadata
           {:constant "A" :label "Foo" :type "string" :default_value "B"
            :add-export true}
           "C")
         "export A=\"C\"")))

(deftest test-render-non-constant-using-metadata
  (is (true? (helpers/render-non-constant-using-metadata
              {:type "boolean" :default_value true}
              nil)))
  (is (false? (helpers/render-non-constant-using-metadata
               {:type "boolean" :default_value true}
               false))))
