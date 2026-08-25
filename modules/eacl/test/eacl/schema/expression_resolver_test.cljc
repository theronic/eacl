(ns eacl.schema.expression-resolver-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.spicedb.parser :as parser]))

(defn- error-data [schema]
  (try
    (resolver/resolve-parse-tree (parser/parse-schema schema))
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(def resolved-schema
  "definition user {}
   definition group {
     relation member: user
     permission active = member
   }
   definition team {
     relation member: user
     permission active = member
   }
   definition document {
     relation reader: user
     relation banned: user
     relation parent: team | group
     permission edit = reader
     permission view = edit & parent->active - banned
   }")

(deftest complete-schema-resolution-test
  (let [{:keys [definitions expressions]}
        (resolver/resolve-parse-tree (parser/parse-schema resolved-schema))
        view (some #(when (= :view (:permission-name %)) %) expressions)]
    (is (= [:document :group :team :user] definitions))
    (is (= 4 (count expressions)))
    (is (= :exclusion (get-in view [:root :op])))
    (is (= :permission (get-in view [:root :left :children 0 :op])))
    (is (= :edit (get-in view [:root :left :children 0 :name])))
    (is (= [:group :team]
           (mapv :subject-type
                 (get-in view [:root :left :children 1 :partitions]))))
    (is (= [:permission :permission]
           (mapv :target-kind
                 (get-in view [:root :left :children 1 :partitions]))))))

(deftest resolution-is-declaration-order-independent-test
  (let [reordered
        "definition user {}
         definition team {
           permission active = member
           relation member: user
         }
         definition group {
           permission active = member
           relation member: user
         }
         definition document {
           permission edit = reader
           relation parent: group | team
           relation banned: user
           relation reader: user
           permission view = edit & parent->active - banned
         }"
        left (:expressions
              (resolver/resolve-parse-tree
                (parser/parse-schema resolved-schema)))
        right (:expressions
               (resolver/resolve-parse-tree (parser/parse-schema reordered)))]
    (is (= left right))))

(deftest positive-recursive-references-remain-finite-test
  (let [schema
        "definition user {}
         definition document {
           relation seed: user
           relation editor: user
           permission a = seed + b
           permission b = a & editor
         }"
        {:keys [expressions]}
        (resolver/resolve-parse-tree (parser/parse-schema schema))]
    (is (= [:a :b] (mapv :permission-name expressions)))
    (is (= :permission
           (get-in (second expressions) [:root :children 0 :op])))
    (is (= :a
           (get-in (second expressions) [:root :children 0 :name])))))

(deftest deterministic-missing-and-type-invalid-errors-test
  (let [schema
        "definition user {}
         definition document {
           relation parent: missing_type
           permission base = missing
           permission p = base->target + parent->target
         }"
        {:keys [type errors]} (error-data schema)]
    (is (= :eacl.schema/expression-resolution-failed type))
    (is (= [:type-invalid-reference
            :missing-reference
            :type-invalid-reference
            :type-invalid-reference]
           (mapv :type errors)))
    (is (= errors (vec (sort-by (juxt (comp str :resource-type)
                                      (comp str :permission-name)
                                      (comp pr-str :path)
                                      (comp str :type))
                                errors))))))

(deftest mixed-arrow-target-kind-is-ambiguous-test
  (let [schema
        "definition user {}
         definition group {
           relation access: user
         }
         definition team {
           relation member: user
           permission access = member
         }
         definition document {
           relation parent: group | team
           permission view = parent->access
         }"
        {:keys [errors]} (error-data schema)
        ambiguous (some #(when (and (= :ambiguous-reference (:type %))
                                    (= :access (:name %))) %)
                        errors)]
    (is ambiguous)
    (is (= [:permission :relation] (:kinds ambiguous)))))
