(ns eacl.mutation-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.mutation :as mutation]))

(deftest mutation-identities-and-idempotency-test
  (let [mutation-id (mutation/new-id)
        other-id (mutation/new-id)
        data {:operation :write
              :objects ["a" "b"]}]
    (testing "ids are independent portable 256-bit values"
      (is (not= mutation-id other-id))
      (is (= 43 (count mutation-id))))
    (testing "the fingerprint binds an id to complete canonical mutation data"
      (let [record (mutation/mutation-record
                    {:mutation-id mutation-id
                     :kind :relationships
                     :canonical-data data
                     :issued-at 10})]
        (is (mutation/mutation-data-matches?
             record mutation-id data))
        (is (not (mutation/mutation-data-matches?
                  record mutation-id
                  (assoc data :objects ["different"]))))))
    (testing "malformed mutation ids cannot act as idempotency keys"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo)
                   (mutation/mutation-fingerprint
                    "not-a-256-bit-id"
                    data))))))

(deftest atomic-mutation-transaction-data-test
  (let [mutation-id (mutation/new-id)
        previous-id (mutation/new-id)
        tx-data
        (mutation/transaction-data
         {:mutation-id mutation-id
          :kind :relationships
          :canonical-data {:operation :batch}
          :family-id "family"
          :previous-head-id previous-id
          :order-value :db/current-tx
          :schema-change? true
          :relation-ids [11 11 12]
          :dependency-ids [21 21 22]
          :issued-at 100
          :token-ttl-seconds 30
          :retention-grace-seconds 5})]
    (is (some #(= mutation-id
                  (get % mutation/mutation-id-attr))
              (filter map? tx-data)))
    (is (some #(= [:db.fn/cas
                   [:eacl/id mutation/graph-entity-id]
                   mutation/graph-head-id-attr
                   previous-id
                   mutation-id]
                  %)
              tx-data))
    (is (= #{11 12}
           (into #{}
                 (keep (fn [entry]
                         (when (and (map? entry)
                                    (= mutation-id
                                       (get entry
                                            mutation/relation-mutation-id-attr)))
                           (:db/id entry))))
                 tx-data)))
    (is (= #{21 22}
           (into #{}
                 (keep (fn [entry]
                         (when (and (map? entry)
                                    (= mutation-id
                                       (get entry
                                            mutation/dependency-mutation-id-attr)))
                           (:db/id entry))))
                 tx-data)))
    (is (= 135
           (some (fn [entry]
                   (when (and (map? entry)
                              (= [mutation/mutation-id-attr previous-id]
                                 (:db/id entry)))
                     (get entry mutation/mutation-expires-at-attr)))
                 tx-data)))))

(deftest retention-boundary-test
  (is (= 135
         (mutation/retention-expiry
          {:issued-at 100
           :token-ttl-seconds 30
           :retention-grace-seconds 5})))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                  :cljs cljs.core.ExceptionInfo)
               (mutation/retention-expiry
                {:issued-at 100
                 :token-ttl-seconds 0
                 :retention-grace-seconds 5}))))
