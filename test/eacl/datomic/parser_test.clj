(ns eacl.datomic.parser_test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [instaparse.core :as insta]
            [eacl.datomic.spice-parser :as parser]
            [eacl.datomic.impl :as impl]))

(def example-schema-string
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user

     permission admin = owner + platform->super_admin
     permission view = owner + admin
     permission update = admin
   }")

;; Expected parse tree for the new full SpiceDB grammar
(def parsed-example-schema
  [:schema
   [:definition [:type-path [:identifier "user"]] [:definition-body]]
   [:definition [:type-path [:identifier "platform"]]
    [:definition-body
     [:relation [:relation-name [:identifier "super_admin"]]
      [:relation-type-expr [:relation-type-ref [:type-path [:identifier "user"]]]]]]]
   [:definition [:type-path [:identifier "account"]]
    [:definition-body
     [:relation [:relation-name [:identifier "platform"]]
      [:relation-type-expr [:relation-type-ref [:type-path [:identifier "platform"]]]]]
     [:relation [:relation-name [:identifier "owner"]]
      [:relation-type-expr [:relation-type-ref [:type-path [:identifier "user"]]]]]
     [:permission [:identifier "admin"]
      [:permission-expr
       [:union-expr
        [:intersect-expr [:exclusion-expr [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "owner"]]]]]]
        [:intersect-expr [:exclusion-expr [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "platform"]] [:base-expr [:identifier "super_admin"]]]]]]]]]
     [:permission [:identifier "view"]
      [:permission-expr
       [:union-expr
        [:intersect-expr [:exclusion-expr [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "owner"]]]]]]
        [:intersect-expr [:exclusion-expr [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "admin"]]]]]]]]]
     [:permission [:identifier "update"]
      [:permission-expr
       [:union-expr
        [:intersect-expr [:exclusion-expr [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "admin"]]]]]]]]]]]])

(deftest spicedb-schema-parsing-tests

  (testing "we can parse an empty resource definition"
    (is (= [:schema
            [:definition [:type-path [:identifier "user"]]
             [:definition-body]]] (parser/parse-schema "definition user {}"))))

  ;; Permission expressions have new structure with union/intersect/exclusion/arrow hierarchy
  (testing "we can parse permission expressions with union"
    (let [parsed (parser/parse-permission-expression "owner + admin")]
      ;; Just verify it parses and has the expected shape
      (is (= :permission-expr (first parsed)))
      (is (= :union-expr (first (second parsed))))))

  (testing "we can parse arrow permissions with one level of nesting"
    (let [parsed (parser/parse-permission-expression "owner + account->admin")]
      (is (= :permission-expr (first parsed)))
      ;; Verify arrow structure exists
      (is (some #(= :simple-arrow-expr (first %)) (tree-seq vector? rest parsed)))))

  (testing "we can parse Spice schema DSL using Instaparse"
    (let [parse-tree (parser/parse-schema example-schema-string)]
      (is (= parsed-example-schema parse-tree))

      (testing "we can extract resource definitions w/relations + permissions"
        (let [definitions (parser/extract-definitions parse-tree)]
          (is (= #{"user" "platform" "account"} (set (keys definitions))))))

      (testing "we can coerce definitions to EACL schema maps"
        (let [eacl-schema (parser/->eacl-schema parse-tree)
              relations   (:relations eacl-schema)
              permissions (:permissions eacl-schema)]
          (is (= #{(impl/Relation :platform :super_admin :user)
                   (impl/Relation :account :platform :platform)
                   (impl/Relation :account :owner :user)}
                (set relations)))

          (is (= #{(impl/Permission :account :admin {:relation :owner})
                   (impl/Permission :account :admin {:arrow :platform :relation :super_admin})
                   (impl/Permission :account :view {:relation :owner})
                   (impl/Permission :account :view {:permission :admin})
                   (impl/Permission :account :update {:permission :admin})}
                (set permissions)))))))

  (testing "ensure we warn against unsupported Spice schema like exclusion permissions"))

(deftest unsupported-features-tests
  (testing "exclusion operator (-) is rejected during validation"
    (let [schema "definition user {}
                  definition account {
                    relation owner: user
                    relation guest: user
                    permission admin = owner - guest
                  }"]
      ;; Grammar now parses it, but validation rejects it
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported operator: Exclusion"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "intersection operator (&) is rejected during validation"
    (let [schema "definition a {
                    relation b: user
                    relation c: user  
                    permission p = b & c
                  }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported operator: Intersection"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "multi-level arrow is rejected during validation"
    ;; Grammar now parses multi-arrows, but validation rejects them
    (let [schema "definition a { relation b: b }
                  definition b { relation c: c }
                  definition c { permission p = b->c->x }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported feature: Multi-level arrows"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "wildcard relations are rejected during validation"
    (let [schema "definition doc { relation viewer: user:* }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported feature: Wildcard relation"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "subject relations are rejected during validation"
    (let [schema "definition doc { relation owner: group#member }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported feature: Subject relation"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "caveats are rejected during validation"
    (let [schema "definition doc { relation viewer: user with ip_check }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported feature: Caveat"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "nil keyword is rejected during validation"
    (let [schema "definition doc { permission p = nil }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported keyword: 'nil'"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "self keyword is rejected during validation"
    (let [schema "definition user { permission view = self }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported keyword: 'self'"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing ".all() arrow function is rejected during validation"
    (let [schema "definition doc { relation group: group permission view = group.all(member) }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported function: \.all\(\)"
            (parser/->eacl-schema (parser/parse-schema schema)))))))