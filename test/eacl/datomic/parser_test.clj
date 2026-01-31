(ns eacl.datomic.parser_test
    (:require [clojure.test :as t :refer [deftest testing is]]
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

(def parsed-example-schema
  [:schema
   [:definition "user" [:definition-body]]
   [:definition "platform"
    [:definition-body
     [:relation [:relation-name "super_admin"] [:relation-subject-type "user"]]]]
   [:definition "account"
    [:definition-body
     [:relation [:relation-name "platform"]
      [:relation-subject-type "platform"]]
     [:relation [:relation-name "owner"]
      [:relation-subject-type "user"]]
     [:permission "admin"
      [:permission-expr
       [:direct-permission "owner"]
       [:permission-operator "+"]
       [:arrow-permission "platform" "super_admin"]]]
     [:permission "view"
      [:permission-expr
       [:direct-permission "owner"]
       [:permission-operator "+"]
       [:direct-permission "admin"]]]
     [:permission "update"
      [:permission-expr
       [:direct-permission "admin"]]]]]])

(deftest spicedb-schema-parsing-tests

  (testing "we can parse an empty resource definition"
    (is (= [:schema
            [:definition "user"
             [:definition-body]]] (parser/parse-schema "definition user {}"))))

  (is (= [:permission-expr
          [:direct-permission "owner"]
          [:permission-operator "+"]
          [:direct-permission "admin"]] (parser/parse-permission-expression "owner + admin")))

  (testing "we can parse arrow permissions with one level of nesting"
    (is (= [:permission-expr
            [:direct-permission "owner"]
            [:permission-operator "+"]
            [:arrow-permission "account" "admin"]]
           (parser/parse-permission-expression "owner + account->admin"))))

  (testing "we can parse Spice schema DSL using Instaparse"
    (let [parse-tree (parser/parse-schema example-schema-string)]
      (is (= parsed-example-schema parse-tree))

      (testing "we can extract resource definitions w/relations + permissions"
        (let [definitions (parser/extract-definitions parse-tree)]
          (is (= 3 (count definitions)))
          (is (contains? definitions "user"))
          (is (contains? definitions "platform"))
          (is (contains? definitions "account"))))

      (testing "we can coerce definitions to EACL schema maps"
         (let [eacl-schema (parser/->eacl-schema parse-tree)
               relations (:relations eacl-schema)
               permissions (:permissions eacl-schema)]
           (is (= 3 (count relations)))
           (is (some #(= (impl/Relation :platform :super_admin :user) %) relations))
           (is (some #(= (impl/Relation :account :platform :platform) %) relations))
           (is (some #(= (impl/Relation :account :owner :user) %) relations))

           (is (= 5 (count permissions)))
           ;; Check a few permissions
           (is (some #(= (impl/Permission :account :admin {:relation :owner}) %) permissions))
           (is (some #(= (impl/Permission :account :admin {:arrow :platform :relation :super_admin}) %) permissions))
           (is (some #(= (impl/Permission :account :view {:relation :owner}) %) permissions))
           (is (some #(= (impl/Permission :account :view {:permission :admin}) %) permissions))
           (is (some #(= (impl/Permission :account :update {:permission :admin}) %) permissions))))))

  (testing "ensure we warn against unsupported Spice schema like exclusion permissions"))