(ns eacl.datomic.parser-test
  ;; ns renamed from eacl.datomic.parser_test: the cognitect test-runner default
  ;; pattern #".*-test$" did not match the underscore name, so these tests were
  ;; silently excluded from `clj -X:test` runs.
  (:require [clojure.test :as t :refer [deftest testing is]]
            [eacl.spicedb.parser :as parser]
            [eacl.datomic.impl :as impl]))

(defn- ex-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- parse-error? [schema]
  (= :eacl.schema/parse-error (ex-type #(parser/parse-schema schema))))

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
       [:exclusion-expr
        [:intersect-expr
         [:union-expr
          [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "owner"]]]]
          [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "platform"]] [:base-expr [:identifier "super_admin"]]]]]]]]]
     [:permission [:identifier "view"]
      [:permission-expr
       [:exclusion-expr
        [:intersect-expr
         [:union-expr
          [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "owner"]]]]
          [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "admin"]]]]]]]]]
     [:permission [:identifier "update"]
      [:permission-expr
       [:exclusion-expr
        [:intersect-expr
         [:union-expr
          [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "admin"]]]]]]]]]]]])

(deftest spicedb-schema-parsing-tests

  (testing "we can parse an empty resource definition"
    (is (= [:schema
            [:definition [:type-path [:identifier "user"]]
             [:definition-body]]] (parser/parse-schema "definition user {}"))))

  ;; Permission expressions have new structure with union/intersect/exclusion/arrow hierarchy
  (testing "we can parse permission expressions with union"
    (let [parsed (parser/parse-permission-expression "owner + admin")]
      (is (= :permission-expr (first parsed)))
      (is (= :exclusion-expr (first (second parsed))))
      (is (some #(= :union-expr (first %)) (tree-seq vector? rest parsed)))))

  (testing "we can parse arrow permissions with one level of nesting"
    (let [parsed (parser/parse-permission-expression "owner + account->admin")]
      (is (= :permission-expr (first parsed)))
      ;; Verify arrow structure exists
      (is (some #(= :simple-arrow-expr (first %)) (tree-seq vector? rest parsed)))))

  (testing "we can parse the SpiceDB schema language"
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

(deftest operator-storage-boundary-tests
  (testing "exclusion syntax is accepted but cannot silently enter flat storage"
    (let [schema "definition user {}
                  definition account {
                    relation owner: user
                    relation guest: user
                    permission admin = owner - guest
                  }"]
      (is (= :eacl.schema/operator-storage-disabled
             (ex-type #(parser/->eacl-schema (parser/parse-schema schema)))))))

  (testing "intersection syntax is accepted but cannot silently enter flat storage"
    (let [schema "definition user {}
                  definition account {
                    relation owner: user
                    relation guest: user
                    permission admin = owner & guest
                  }"]
      (is (= :eacl.schema/operator-storage-disabled
             (ex-type #(parser/->eacl-schema (parser/parse-schema schema))))))))

(deftest unsupported-features-tests

  (testing "chained arrows are a parse error, as in SpiceDB"
    (let [schema "definition user {}
                  definition team {
                    relation member: user
                  }
                  definition folder {
                    relation parent: folder
                    relation team: team
                    permission read = parent->team->member
                  }"]
      (is (= {:type :eacl.schema/parse-error :reason :nested-arrow}
             (select-keys (try (parser/parse-schema schema) nil
                               (catch clojure.lang.ExceptionInfo e (ex-data e)))
                          [:type :reason])))))

  (testing "wildcard relations are rejected during validation"
    (let [schema "definition user {}
                  definition doc {
                    relation viewer: user:*
                  }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported feature: Wildcard relation"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "subject relations are rejected during validation"
    (let [schema "definition user {}
                  definition group {
                    relation member: user
                  }
                  definition doc {
                    relation owner: group#member
                  }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported feature: Subject relation"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "caveats are rejected during validation"
    (let [schema "caveat ip_check(allowed bool) { allowed }
                  definition user {}
                  definition doc {
                    relation viewer: user with ip_check
                  }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported feature: Caveat"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "nil keyword is rejected during validation"
    (let [schema "definition doc {
                    permission view = nil
                  }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported keyword: 'nil'"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing "self keyword is rejected during validation"
    ;; `self` is a keyword only after `use self`; before it, it is a name.
    (let [schema "use self
                  definition user {
                    permission view = self
                  }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported keyword: 'self'"
            (parser/->eacl-schema (parser/parse-schema schema))))))

  (testing ".all() arrow function is rejected during validation"
    (let [schema "definition user {}
                  definition group {
                    relation member: user
                  }
                  definition doc {
                    relation group: group
                    permission view = group.all(member)
                  }"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported function: \.all\(\)"
            (parser/->eacl-schema (parser/parse-schema schema)))))))

(deftest declaration-line-termination-tests
  (testing "empty definitions retain SpiceDB's compact form"
    (is (not (parse-error? "definition user {}"))))

  (testing "a declaration may share the opening-brace line"
    (is (not (parse-error? "definition user {}
                            definition folder { relation viewer: user
                            }"))))

  (testing "a declaration cannot share its line with the closing brace"
    (doseq [schema ["definition user {}
                    definition folder { relation viewer: user }"
                   "definition user {}
                    definition folder {
                      relation viewer: user
                      permission view = viewer }"]]
      (is (parse-error? schema))
      (is (= :eacl.schema/parse-error
             (ex-type #(parser/->eacl-schema (parser/parse-schema schema)))))))

  (testing "separate declarations require separate lines"
    (is (parse-error? "definition user {}
                       definition folder {
                         relation viewer: user permission view = viewer
                       }")))

  (testing "a semicolon ends a declaration, as a line end does"
    (is (= (parser/->eacl-schema
            (parser/parse-schema "definition user {}
                                  definition folder {
                                    relation viewer: user
                                    permission view = viewer
                                  }"))
           (parser/->eacl-schema
            (parser/parse-schema "definition user {}; definition folder { relation viewer: user; permission view = viewer; }")))))

  (testing "a trailing line comment still terminates at its newline"
    (is (not (parse-error? "definition user {}
                            definition folder {
                              relation viewer: user // owner access
                            }")))))

(deftest parse-failure-safety-tests
  (testing "a failed parse throws a typed error and never coerces to an empty schema"
    (is (= :eacl.schema/parse-error
           (ex-type #(parser/->eacl-schema (parser/parse-schema "definition user {")))))
    (testing "the error carries the failure position (line/column)"
      (try
        (parser/->eacl-schema (parser/parse-schema "definition user { relation owner user }"))
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= {:type :eacl.schema/parse-error :line 1 :column 34}
                 (select-keys (ex-data e) [:type :line :column])))))))

  (testing "transform-schema throws on non-schema input instead of returning nil"
    (doseq [input [nil [] [:definition] "definition user {}"]]
      (is (= :eacl.schema/parse-error (ex-type #(parser/transform-schema input)))))))

(deftest comment-support-tests
  (testing "// line comments and /* */ block comments are whitespace"
    (let [commented "// leading comment
                     definition user {}
                     /* block
                        comment */
                     definition account {
                       relation owner: user // trailing comment
                       permission admin = owner /* inline */ + owner
                     }"
          plain     "definition user {}
                     definition account {
                       relation owner: user
                       permission admin = owner + owner
                     }"
          parse     #(parser/->eacl-schema (parser/parse-schema %))]
      (is (= (parse plain) (parse commented)))))

  (testing "comment-only input is an empty schema, as in SpiceDB"
    ;; write-schema! still refuses to wipe a non-empty schema with it (see
    ;; eacl.datomic.schema-test/write-schema-empty-guard-test).
    (is (= {:definitions [] :relations [] :permissions []}
           (parser/->eacl-schema (parser/parse-schema "// nothing here"))))))

(deftest duplicate-declaration-tests
  (testing "duplicate definition blocks are rejected, not silently last-won"
    (is (= :eacl.schema/duplicate-definition
           (ex-type #(parser/->eacl-schema
                       (parser/parse-schema "definition user {}
                                             definition account {
                                               relation owner: user
                                             }
                                             definition account {
                                               relation viewer: user
                                             }"))))))

  (testing "duplicate relation declarations within a definition are rejected"
    (is (= :eacl.schema/duplicate-relation
           (ex-type #(parser/->eacl-schema
                       (parser/parse-schema "definition user {}
                                             definition doc {
                                               relation owner: user
                                               relation owner: user
                                             }"))))))

  (testing "a multi-type relation declared once with | is not a duplicate"
    (is (= 2 (count (:relations (parser/->eacl-schema
                                  (parser/parse-schema "definition user {}
                                                        definition group {}
                                                        definition doc {
                                                          relation owner: user | group
                                                        }")))))))

  (testing "a permission sharing a name with a relation on the same definition is rejected"
    (is (= :eacl.schema/name-collision
           (ex-type #(parser/->eacl-schema
                       (parser/parse-schema "definition user {}
                                             definition doc {
                                               relation owner: user
                                               permission owner = owner
                                             }")))))))

(deftest paren-expression-tests
  (testing "parenthesized union operands flatten to their components"
    (let [parse #(parser/->eacl-schema (parser/parse-schema %))
          paren (parse "definition user {}
                        definition doc {
                          relation owner: user
                          relation editor: user
                          permission manage = (owner + editor)
                        }")
          plain (parse "definition user {}
                        definition doc {
                          relation owner: user
                          relation editor: user
                          permission manage = owner + editor
                        }")]
      (is (= (set (:permissions plain)) (set (:permissions paren))))
      (testing "nested parens and mixed operands also flatten"
        (is (= (set (:permissions plain))
               (set (:permissions (parse "definition user {}
                                          definition doc {
                                            relation owner: user
                                            relation editor: user
                                            permission manage = ((owner)) + (editor)
                                          }"))))))))

  (testing "a parenthesized arrow base is a parse error, as in SpiceDB"
    (is (parse-error? "definition user {}
                       definition doc {
                         relation owner: user
                         relation editor: user
                         permission manage = (owner + editor)->member
                       }"))))

(deftest arrow-target-kind-tests
  (testing "arrow target resolving to mixed kinds across subject types is rejected"
    ;; mgmt is a RELATION on user but a PERMISSION on group.
    (is (= :eacl.schema/mixed-arrow-target
           (ex-type #(parser/->eacl-schema
                       (parser/parse-schema "definition user {
                                               relation mgmt: user
                                             }
                                             definition group {
                                               relation lead: user
                                               permission mgmt = lead
                                             }
                                             definition account {
                                               relation owner: user | group
                                               permission admin = owner->mgmt
                                             }")))))))
(deftest keyword-prefix-identifier-tests
  (testing "identifiers that merely START with a reserved word parse (SpiceDB-compatible)"
    (doseq [permission-name ["allowed" "anytime" "selfie" "nilable" "without_x" "definitely"]]
      (is (not (parse-error?
                (str "definition user {}
                      definition doc {
                        relation owner: user
                        permission " permission-name " = owner
                      }")))
          (str "permission '" permission-name "' should parse")))
    (doseq [relation-name ["allocation" "relationship" "anywhere" "self_serve"]]
      (is (not (parse-error?
                (str "definition user {}
                      definition doc {
                        relation " relation-name ": user
                        permission view = " relation-name "
                      }")))
          (str "relation '" relation-name "' should parse"))))

  (let [schema (fn [permission-name]
                 (str "definition user {}
                       definition doc {
                         relation owner: user
                         permission " permission-name " = owner
                       }"))]
    (testing "SpiceDB keywords are not names"
      (doseq [reserved ["nil" "definition" "caveat" "relation" "permission" "with"]]
        (is (parse-error? (schema reserved))
            (str "keyword '" reserved "' should not parse as a permission name"))))

    (testing "`any`, `all` and, without `use self`, `self` are names in SpiceDB"
      (doseq [permission-name ["any" "all"]]
        (is (= #{(impl/Permission :doc (keyword permission-name) {:relation :owner})}
               (set (:permissions (parser/->eacl-schema (parser/parse-schema (schema permission-name))))))))
      (is (= :eacl.schema/unsupported-feature
             (ex-type #(parser/->eacl-schema (parser/parse-schema (schema "self")))))
          "EACL reserves the name `self`")
      (is (parse-error? (str "use self\n" (schema "self")))
          "`use self` makes `self` a keyword"))))

(deftest duplicate-permission-declaration-tests
  (testing "duplicate permission names on one definition throw instead of silently unioning"
    (is (= :eacl.schema/duplicate-permission
           (ex-type #(parser/->eacl-schema
                       (parser/parse-schema
                         "definition user {}
                          definition doc {
                            relation owner: user
                            relation editor: user
                            permission view = owner
                            permission view = editor
                          }"))))))
  (testing "the same permission name on different definitions is fine"
    (is (some? (parser/->eacl-schema
                 (parser/parse-schema
                   "definition user {}
                    definition doc {
                      relation owner: user
                      permission view = owner
                    }
                    definition folder {
                      relation owner: user
                      permission view = owner
                    }"))))))
