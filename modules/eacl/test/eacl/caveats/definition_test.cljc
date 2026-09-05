(ns eacl.caveats.definition-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.caveats.definition :as definition]
            [eacl.spicedb.parser :as parser]
            [eacl.schema.expression-resolver :as resolver]))

(def source
  "caveat in_region(region string, accepted list<string>) {\n region in accepted\n}\ndefinition user {}\ndefinition doc {\n relation viewer: user\n permission view = viewer\n}")

(deftest top-level-typed-caveat-declarations
  (let [schema (resolver/validate-schema source)
        entity (first (:caveats schema))
        decoded (definition/decode-entity entity)]
    (is (= 1 (count (:caveats schema))))
    (is (= "in_region" (:name decoded)))
    (is (= [["accepted" [:list :string]] ["region" :string]] (:parameters decoded)))
    (is (= [:in [:param "region"] [:param "accepted"]] (:plan decoded)))
    (is (= entity (definition/entity (:name decoded) (:parameters decoded) (:source decoded))))))

(defn error-type [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:type (ex-data e)))))

(deftest caveat-schema-rejections
  (doseq [source ["caveat c(a bool, a int) { a }\ndefinition user {}"
                  "caveat c(a string) { a }\ndefinition user {}"
                  "caveat c(a bool) { missing }\ndefinition user {}"
                  "caveat c(a list<list<int>>) { true }\ndefinition user {}"
                  "caveat c(a bool) { a }\ncaveat c(a bool) { a }\ndefinition user {}"]]
    (is (keyword? (error-type #(resolver/validate-schema source)))))
  (is (= :eacl.schema/unsupported-feature
         (error-type #(resolver/validate-schema
                       "caveat c(a bool) { a }\ndefinition user {}\ndefinition doc {\n relation viewer: user with c\n}")))))

(deftest braces-in-caveat-literals-and-comments
  (let [schema (resolver/validate-schema
                "caveat c(a string) {\n // a } comment\n a == \"}\"\n}\ndefinition user {}")]
    (is (= [:eq [:param "a"] [:literal :string "}"]]
           (:plan (definition/decode-entity (first (:caveats schema))))))))

(defn staged [branches]
  (-> (str "caveat c(a bool) { a }\ndefinition user {}\ndefinition doc {\n relation viewer: " branches "\n}")
      parser/parse-schema parser/transform-schema parser/staged-relation-entities))

(deftest required-and-optional-staged-branches
  (is (false? (:eacl.relation/allows-unqualified? (first (staged "user with c")))))
  (is (true? (:eacl.relation/allows-unqualified? (first (staged "user | user with c")))))
  (is (= 1 (count (staged "user | user with c"))))
  (is (= [[:eacl.caveat/name "c"]] (:eacl.relation/caveats (first (staged "user with c")))))
  (is (= :eacl.schema/duplicate-relation-branch (error-type #(staged "user with c | user with c"))))
  (is (= :eacl.schema/invalid-caveat-reference (error-type #(staged "user with missing")))))
