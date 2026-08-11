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
