(ns eacl.relationships.safe-retraction-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.mutation :as mutation]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def fixed-mutation-id
  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

(deftest support-descriptor-contract-test
  (doseq [mode safe/supported-modes]
    (is (= mode
           (:mode (safe/support-descriptor
                   {:backend :test :mode mode :reason :test-mode})))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                  :cljs cljs.core.ExceptionInfo)
               (safe/support-descriptor
                {:backend :test :mode :imaginary :reason :bad})))
  (is (= {:backend :spicedb
          :mode :unsupported
          :reason :no-transaction-functions
          :alternative :delete-object!}
         (safe/support-descriptor
          {:backend :spicedb
           :mode :unsupported
           :reason :no-transaction-functions
           :alternative :delete-object!}))))

(deftest mutation-envelope-round-trip-and-validation-test
  (let [target [:eacl/id "account-1"]
        envelope (safe/mutation-envelope
                  target
                  {:mutation-id fixed-mutation-id
                   :issued-at 100
                   :token-ttl-seconds 30
                   :retention-grace-seconds 5})]
    (is (= envelope (safe/validate-envelope target envelope)))
    (is (= 135 (:previous-expires-at envelope)))
    (is (= (safe/canonical-request target) (:canonical-data envelope)))
    (doseq [bad [(assoc envelope :version 2)
                 (assoc envelope :canonical-data
                        (safe/canonical-request [:eacl/id "other"]))
                 (assoc envelope :fingerprint fixed-mutation-id)
                 (assoc envelope :unexpected true)]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo)
                   (safe/validate-envelope target bad))))))

(deftest local-half-planning-is-exact-and-bounded-test
  (let [target 10
        forward [[:account 20 :server 30]
                 [:account 21 :account target]
                 [:account 20 :server 30]]
        reverse [[:account 22 :user 40]
                 [:account 21 :account target]]
        plan (safe/plan-local-halves target forward reverse)]
    (is (= 5 (:local-half-count plan)))
    (is (= [20 21 22] (:relation-ids plan)))
    (is (= #{[:db/retract 30 storage/reverse-attribute
              [:server 20 :account target]]
             [:db/retract 40 storage/forward-attribute
              [:user 22 :account target]]}
           (set (:peer-retractions plan))))
    (testing "output depends on unique peers and relations, not unrelated data"
      (is (= 2 (count (:peer-retractions plan))))
      (is (= 3 (count (:relation-ids plan)))))))

(deftest malformed-halves-fail-before-emitting-cleanup-test
  (doseq [[forwards reverses]
          [[[[:account 2 :server]] []]
           [[] [[:account "relation" :user 4]]]
           [[[:account 2 :server -1]] []]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo)
                 (safe/plan-local-halves 1 forwards reverses)))))

(deftest portable-mutation-proof-data-test
  (let [target 10
        envelope (safe/mutation-envelope
                  target
                  {:mutation-id fixed-mutation-id
                   :issued-at 100
                   :token-ttl-seconds 30
                   :retention-grace-seconds 5})
        tx-data (safe/mutation-tx-data
                 {:head-id "previous"}
                 [20 21 20]
                 :db/current-tx
                 envelope)]
    (is (some #(= [:db.fn/cas
                   [:eacl/id mutation/graph-entity-id]
                   mutation/graph-head-id-attr
                   "previous"
                   fixed-mutation-id]
                  %)
              tx-data))
    (is (= #{20 21}
           (into #{}
                 (keep (fn [entry]
                         (when (map? entry) (:db/id entry))))
                 (drop 4 tx-data))))))

(deftest expansion-count-is-linear-in-local-degree-test
  (doseq [degree [0 1 10 100 1000]]
    (let [target 1
          relation-count 7
          forward
          (mapv (fn [index]
                  [:user
                   (+ 2 (mod index relation-count))
                   :account
                   (+ 1000 index)])
                (range degree))
          plan (safe/plan-local-halves target forward [])]
      (is (= degree (:local-half-count plan)))
      (is (= degree (count (:peer-retractions plan))))
      (is (= (min degree relation-count)
             (count (:relation-ids plan))))
      (is (= degree
             (- (count (:peer-retractions plan)) 0))
          "no term depends on unrelated entities or schema relations"))))
