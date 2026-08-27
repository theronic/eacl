(ns eacl.spicedb.parser-portability-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [instaparse.core :as insta]
            [eacl.spicedb.parser :as parser]))

(deftest declaration-line-termination-is-portable-test
  (testing "SpiceDB-compatible empty and multiline definitions parse"
    (is (not (insta/failure?
               (parser/parse-schema "definition user {}"))))
    (is (not (insta/failure?
               (parser/parse-schema
                "definition user {}
                 definition folder { relation viewer: user
                 }"))))
    (is (not (insta/failure?
               (parser/parse-schema
                "definition user {}
                 definition folder {
                   relation viewer: user // trailing comment
                   permission view = viewer
                 }")))))

  (testing "declarations sharing a line with another declaration or } fail"
    (doseq [schema
            ["definition user {}
              definition folder { relation viewer: user }"
             "definition user {}
              definition folder {
                relation viewer: user permission view = viewer
              }"
             "definition user {}
              definition folder {
                relation viewer: user
                permission view = viewer }"]]
      (is (insta/failure? (parser/parse-schema schema))))))

(deftest operator-precedence-and-grouping-ast-test
  (testing "+ binds before &, and & binds before -"
    (is (= {:op :exclusion
            :left {:op :intersection
                   :children
                   [{:op :union
                     :children [{:op :identifier :name "a"}
                                {:op :identifier :name "b"}]}
                    {:op :identifier :name "c"}]}
            :right {:op :identifier :name "d"}}
           (parser/permission-expression->source-ast
             (parser/parse-permission-expression "a + b & c - d")))))

  (testing "repeated exclusion is an ordered left fold"
    (is (= {:op :exclusion
            :left {:op :exclusion
                   :left {:op :identifier :name "a"}
                   :right {:op :identifier :name "b"}}
            :right {:op :identifier :name "c"}}
           (parser/permission-expression->source-ast
             (parser/parse-permission-expression "a - b - c")))))

  (testing "parentheses preserve otherwise-normalizable source grouping"
    (is (= {:op :union
            :children
            [{:op :identifier :name "a"}
             {:op :union
              :children [{:op :identifier :name "b"}
                         {:op :identifier :name "c"}]
              :grouped? true}]}
           (parser/permission-expression->source-ast
             (parser/parse-permission-expression "a + (b + c)"))))))
