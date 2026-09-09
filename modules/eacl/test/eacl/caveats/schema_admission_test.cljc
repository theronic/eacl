(ns eacl.caveats.schema-admission-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.caveats.definition-test :as errors]
            [eacl.schema.expression-resolver :as resolver]))

(defn source [branches]
  (str "caveat enabled(flag bool) { flag }\n"
       "definition user {}\ndefinition doc {\n relation viewer: " branches
       "\n permission view = viewer\n}"))

(deftest qualified-syntax-requires-explicit-schema-admission
  (is (= :eacl.schema/unsupported-feature
         (errors/error-type #(resolver/validate-schema (source "user with enabled")))))
  (doseq [[branches plain?] [["user with enabled" false]
                             ["user | user with enabled" true]]]
    (let [schema (resolver/validate-schema (source branches) nil {:allow-caveats? true})
          relation (first (:relations schema))]
      (is (= 1 (count (:relations schema))))
      (is (= plain? (:eacl.relation/allows-unqualified? relation)))
      (is (= [[:eacl.caveat/name "enabled"]] (:eacl.relation/caveats relation)))
      (is (= 1 (count (:expressions schema)))))))

(deftest qualified-admission-retains-reference-and-branch-validation
  (doseq [[branches error] [["user with missing" :eacl.schema/invalid-caveat-reference]
                            ["user with enabled | user with enabled" :eacl.schema/duplicate-relation-branch]
                            ["user | user" :eacl.schema/duplicate-relation-branch]
                            ["user:* with enabled" :eacl.schema/unsupported-feature]
                            ["user#member with enabled" :eacl.schema/unsupported-feature]]]
    (is (= error (errors/error-type #(resolver/validate-schema (source branches) nil {:allow-caveats? true}))))))

(deftest ordinary-schema-shape-does-not-gain-qualifier-fields
  (is (= (select-keys (resolver/validate-schema (source "user")) [:relations :expressions])
         (select-keys (resolver/validate-schema (source "user") nil {:allow-caveats? true}) [:relations :expressions]))))
