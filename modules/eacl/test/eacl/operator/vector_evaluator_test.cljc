(ns eacl.operator.vector-evaluator-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as datascript-impl]
            [eacl.datascript.schema :as datascript-schema]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.plan :as plan]
            [eacl.operator.vector-evaluator :as vector-evaluator]))

(def schema
  "definition user {}
   definition document {
     relation a: user
     relation b: user
     relation c: user
     relation banned: user
     permission view = ((a & b) + (a & c)) - banned
   }")

(defn- object [type id]
  (eacl/spice-object type [:eacl/id id]))

(defn- fixture []
  (let [conn (datascript/create-conn)
        users [(object :user "u1") (object :user "u2")]
        documents (mapv #(object :document (str "d" %)) (range 40))
        objects (into users documents)]
    (datascript-schema/write-schema! conn schema)
    (ds/transact!
     conn
     (map-indexed (fn [index value]
                    {:db/id (- (inc index))
                     :eacl/id (second (:id value))})
                  objects))
    (doseq [[index document] (map-indexed vector documents)
            relationship
            (cond-> [(eacl/->Relationship (first users) :a document)]
              (even? index)
              (conj (eacl/->Relationship (first users) :b document))
              (zero? (mod index 3))
              (conj (eacl/->Relationship (first users) :c document))
              (zero? (mod index 5))
              (conj (eacl/->Relationship (first users) :banned document)))]
      (ds/transact!
       conn
       (datascript-impl/tx-update-relationship
        (ds/db conn) {:operation :touch :relationship relationship})))
    (let [db (ds/db conn)
          eid #(ds/entid db (:id %))]
      {:adapter (datascript-backend/basis-adapter db {})
       :user (first users)
       :documents documents
       :eid eid})))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest vector-equals-scalar-and-uses-aligned-masks-test
  (let [{:keys [adapter user documents eid]} (fixture)
        operator-plan (plan/seal-plan adapter [:document :view])
        candidates
        (mapv (fn [document]
                {:direction :forward
                 :subject-type :user :subject-eid (eid user)
                 :resource-type :document :resource-eid (eid document)})
              documents)
        expected
        (mapv (fn [candidate]
                (scalar/check-eids
                 {:adapter adapter :plan operator-plan
                  :subject-type (:subject-type candidate)
                  :subject-eid (:subject-eid candidate)
                  :resource-eid (:resource-eid candidate)}))
              candidates)
        stats (atom {})
        actual
        (binding [vector-evaluator/*vector-stats* stats]
          (vector-evaluator/check-many-eids
           {:adapter adapter :plan operator-plan
            :candidates candidates}))]
    (is (= expected actual))
    (is (= 40 (:candidate-count @stats)))
    (is (= 8 (:mask-word-count @stats)))
    (is (= (set (keep-indexed #(when %2 %1) actual))
           (set (for [index (range 40)
                      :let [word (quot index 32)
                            bit (mod index 32)]
                      :when (not (zero?
                                  (bit-and
                                   (nth (get-in @stats
                                                [:root-masks :known-true
                                                 :words]) word)
                                   (bit-shift-left 1 bit))))]
                  index))))))

(deftest reverse-witness-boundary-and-malformed-vector-test
  (let [{:keys [adapter user documents eid]} (fixture)
        operator-plan (plan/seal-plan adapter [:document :view])
        root-key [[:document :view]
                  (get-in operator-plan [:expressions 0 :root])]
        candidate {:direction :reverse
                   :subject-type :user :subject-eid (eid user)
                   :resource-type :document
                   :resource-eid (eid (first documents))}]
    (is (= [true]
           (vector-evaluator/check-many-eids
            {:adapter adapter :plan operator-plan
             :candidates [(assoc candidate :true-nodes #{root-key})]})))
    (is (= :duplicate-candidate
           (:reason
            (error-data
             #(vector-evaluator/check-many-eids
               {:adapter adapter :plan operator-plan
                :candidates [candidate candidate]})))))
    (is (= :candidate-width
           (:reason
            (error-data
             #(vector-evaluator/check-many-eids
               {:adapter adapter :plan operator-plan
                :candidates (vec (repeat 257 candidate))})))))))
