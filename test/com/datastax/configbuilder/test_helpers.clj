(ns com.datastax.configbuilder.test-helpers
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files SimpleFileVisitor FileVisitResult]
           [java.nio.file.attribute FileAttribute]))

(def default-dse-version "6.0.0")
(def invalid-dse-version "3.0.1")

(def temp-dir (atom nil))

(defn temp-dir-fixture
  [prefix]
  (fn [t]
    (reset! temp-dir (Files/createTempDirectory
                      "configbuilder_"
                      (into-array FileAttribute [])))
    (t)

    ;; To remove the directory, we must empty it out first!
    (Files/walkFileTree
     @temp-dir
     (proxy [SimpleFileVisitor] []
       (visitFile [file attrs]
         (Files/delete file)
         FileVisitResult/CONTINUE)
       (postVisitDirectory [dir ex]
         (if ex
           (throw ex)
           (do (Files/delete dir)
               FileVisitResult/CONTINUE)))))))
