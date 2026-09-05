(ns eacl.authorization.data-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.data :as data]
            [eacl.execution :as execution]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.request.counters :as counters]))

(defn error-data [f]
  (try (f) nil (catch #?(:clj Throwable :cljs :default) error (ex-data error))))

(defn row [attribute value]
  {:e 10 :a attribute :v value :tx 7})

(deftest bounded-data-preserves-unknown-fields-and-same-read-version
  (let [rows [(row qualifier/marker-attribute 1) (row qualifier/expiration-attribute 100)
              (row :unexpected/field "retained")]
        ledger (counters/make-ledger)
        packet (counters/call-with-ledger ledger #(data/collect 10 rows identity true))]
    (is (= {:entity {:db/id 10 qualifier/marker-attribute 1 qualifier/expiration-attribute 100
                     :unexpected/field "retained"} :version 7 :fact-count 3} packet))
    (is (= :qualifier-unknown-field
           (:reason (error-data #(qualifier/decode (:entity packet) [])))))
    (is (= 3 (:fetched-values (counters/snapshot ledger))))
    (is (nil? (:version (data/collect 10 rows identity false))))
    (is (= {:entity nil :version nil :fact-count 0} (data/collect 10 [] identity true)))))

(deftest qualified-data-keeps-cardinality-and-entity-boundaries
  (is (= #{20 30} (get-in (data/collect 10 [(row :eacl.relation/caveats 20)
                                           (row :eacl.relation/caveats 30)] identity true)
                          [:entity :eacl.relation/caveats])))
  (is (= :nonfunctional-qualification-entity
         (:reason (error-data #(data/collect 10 [(row qualifier/marker-attribute 1)
                                                  (row qualifier/marker-attribute 2)] identity true)))))
  (is (= :qualification-entity-bound
         (:reason (error-data #(data/collect 11 [(row qualifier/marker-attribute 1)] identity true)))))
  (is (= :qualification-entity-id
         (:reason (error-data #(data/collect 0 [] identity true))))))

(deftest qualified-data-limits-and-cancellation-stop-decoding
  (let [ledger (counters/make-ledger)]
    (with-redefs [data/maximum-entity-facts 2]
      (is (= :qualification-entity-limit
             (:reason (error-data
                       #(counters/call-with-ledger
                         ledger (fn [] (data/collect 10 (repeat (row :unexpected/field 1)) identity true))))))))
    (is (= 3 (:fetched-values (counters/snapshot ledger)))))
  (let [token (execution/cancellation-token)
        contract (execution/normalize {} :check-permission {:cancellation-token token})]
    (execution/cancel! token)
    (binding [execution/*contract* contract]
      (is (= :eacl.execution/cancelled
             (:type (error-data #(data/collect 10 [(row qualifier/marker-attribute 1)]
                                               (fn [_] (throw (ex-info "Must not decode after cancellation" {}))) true))))))))
