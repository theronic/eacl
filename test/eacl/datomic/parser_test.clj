(ns eacl.datomic.parser_test
    (:require [clojure.test :as t :refer [deftest testing is]]
              [eacl.datomic.spice-parser :as parser]))

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

(deftest spicedb-schema-parsing-tests

  (testing "we can parse an empty resource definition"
    (is (= [:schema
            [:definition "user"
             [:definition-body]]] (parser/parse-schema "definition user {}"))))

  (is (= [:permission-expr
          [:direct-permission "owner"]
          [:direct-permission "admin"]] (parser/parse-permission-expression "owner + admin")))

  (testing "we can parse arrow permissions with one level of nesting"
    (is (= [:permission-expr
            [:direct-permission "owner"]
            [:arrow-permission "account" "admin"]]
           (parser/parse-permission-expression "owner + account->admin"))))

  ;(testing "parsing permission definitions"
  ;  (doseq [expr ["owner + admin"
  ;                "platform->super_admin"
  ;                "owner + platform->super_admin"
  ;                "account->admin + vpc->admin + shared_admin"]]
  ;    (let [parsed-expr (parser/parse-permission-expression "owner + admin")]
  ;      (if parsed-expr
  ;        (do
  ;          (println (str "Expression: " expr))
  ;          (println (str "Parsed as: " (format-expression (transform-expression parsed-expr))))
  ;          (println (str "Structure: " (transform-expression parsed-expr)))
  ;          (println))
  ;        (println (str "Failed to parse: " expr))))))

  (testing "we can parse Spice schema DSL to EACL Datomic entities"
    (let [parse-tree (parser/parse-schema example-schema-string)]
      (is (= {} parse-tree))

      (testing "we can extract resource definitions"
        (is (= {} (parser/extract-definitions parse-tree))))

      (is (= {} (parser/transform-schema parse-tree)))))

  (testing "ensure we warn against unsupported Spice schema like exclusion permissions"))