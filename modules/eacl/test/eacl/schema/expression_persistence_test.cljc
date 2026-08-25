(ns eacl.schema.expression-persistence-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]))

(def schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = base - banned
   }")

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest canonical-expression-entity-round-trip-test
  (let [{:keys [permissions] :as candidate}
        (persistence/candidate-schema (resolver/validate-schema schema))
        decoded (persistence/validate-entities permissions)]
    (is (= 3 (count (:relations candidate))))
    (is (= 2 (count permissions)))
    (is (= [:base :view]
           (mapv (comp :permission-name :expression) decoded)))
    (is (every? #(not-any? (fn [attribute] (contains? % attribute))
                           persistence/legacy-flat-attributes)
                permissions))))

(deftest expression-replacement-is-not-an-entity-deletion-test
  (let [old {:eacl/id "eacl.permission-expression::document::view"
             :value :old}
        replacement (assoc old :value :new)
        removed {:eacl/id "eacl.permission-expression::document::edit"}
        deltas {:additions #{replacement}
                :retractions #{old removed}}]
    (is (= [removed]
           (persistence/entity-deletions deltas)))))

(deftest corrupt-flat-mixed-and-metadata-storage-fails-closed-test
  (let [entity
        (first
          (:permissions
            (persistence/candidate-schema (resolver/validate-schema schema))))]
    (is (= :flat-only-representation
           (:reason
             (error-data
               #(persistence/validate-entities
                  [(select-keys entity
                     [:eacl/id
                      :eacl.permission/resource-type
                      :eacl.permission/permission-name])])))))
    (is (= :mixed-flat-and-expression
           (:reason
             (error-data
               #(persistence/validate-entities
                  [(assoc entity
                          :eacl.permission/target-type :relation)])))))
    (is (= :field-mismatch
           (:reason
             (error-data
               #(persistence/validate-entities
                  [(update entity :eacl.permission/source-node-count inc)])))))
    (is (= :duplicate-expression
           (:reason
             (error-data
               #(persistence/validate-entities [entity entity])))))))

(deftest union-compatible-projection-retains-existing-plan-shape-test
  (let [candidate (persistence/candidate-schema
                    (resolver/validate-schema schema))
        base (first (:permissions candidate))
        expression (persistence/decode-entity base)
        definitions
        (persistence/union-compatible-definitions 42 expression)]
    (is (= 2 (count definitions)))
    (is (= #{:reader :writer} (set (map :target-name definitions))))
    (is (every? #(= 42 (:permission-id %)) definitions)))
  (let [candidate (persistence/candidate-schema
                    (resolver/validate-schema schema))
        view (second (:permissions candidate))
        data
        (error-data
          #(persistence/union-compatible-definitions
             43 (persistence/decode-entity view)))]
    (is (= :eacl.schema/operator-plan-required (:type data)))))

(deftest union-compatible-projection-preserves-flat-set-semantics-test
  (let [candidate
        (persistence/candidate-schema
         (resolver/validate-schema
          "definition user {}
           definition document {
             relation reader: user
             permission view = reader + reader
           }"))
        permission (first (:permissions candidate))
        definitions
        (persistence/union-compatible-definitions
         7 (persistence/decode-entity permission))]
    (is (= 1 (count definitions)))
    (is (= :reader (:target-name (first definitions))))))
