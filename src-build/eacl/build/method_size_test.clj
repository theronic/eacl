(ns eacl.build.method-size-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [eacl.build.method-size :as method-size])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- compile-forms!
  "Compiles each form into class files under a fresh directory and returns
  the directory."
  [& forms]
  (let [directory (.toFile (Files/createTempDirectory
                            "eacl-method-size" (make-array FileAttribute 0)))]
    ;; The compiler names a class after the current namespace.
    (binding [*ns* (the-ns 'eacl.build.method-size-test)
              *compile-files* true
              *compile-path* (str directory)]
      (run! eval forms))
    directory))

(defn- delete-tree!
  [directory]
  (run! io/delete-file (reverse (file-seq directory))))

(deftest a-method-the-jvm-would-not-compile-is-found-test
  (let [directory
        (compile-forms!
         ;; Seven operator strings with distant hash codes: `case` emits a
         ;; table spanning them.
         '(fn string-switch [op]
            (case op
              ("==" "!=") :equality
              ("<" "<=" ">" ">=") :ordering
              "in" :membership))
         '(fn keyword-lookup [op]
            (get {"==" :equality "in" :membership} op)))
        prefix "eacl/build/method_size_test"]
    (try
      (let [found (method-size/oversized directory prefix)
            every-method (method-size/oversized directory prefix 0)]
        (testing "the table switch is reported with its size"
          (is (= 1 (count found)))
          (is (re-find #"string_switch" (:class (first found))))
          (is (= "invoke" (:method (first found))))
          (is (< method-size/huge-method-limit (:bytes (first found)))))
        (testing "the lookup, constructors and static initializers are not"
          (is (some #(re-find #"keyword_lookup" (:class %)) every-method))
          (is (not-any? #(re-find #"keyword_lookup" (:class %)) found)))
        (testing "another prefix selects other classes"
          (is (empty? (method-size/oversized directory "datahike/"))))
        (testing "a lower limit reports more, largest first"
          (let [sizes (map :bytes (method-size/oversized directory prefix 4))]
            (is (< 1 (count sizes)))
            (is (= sizes (reverse (sort sizes)))))))
      (finally
        (delete-tree! directory)))))
