(ns eacl.authorization.qualification-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.clock :as clock]
            [eacl.authorization.data :as data]
            [eacl.backend.v8 :as backend]
            [eacl.request.counters :as counters]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as q]
            [eacl.cache.standard-lru :as lru]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.partial :as partial]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.values :as values]
            [eacl.execution :as execution]
            [eacl.relationships.qualifier :as qualifier]))

(def parameters [["flag" :bool]])
(def named (definition/entity "enabled" parameters "flag"))
(def fixture
  {1 {:db/id 1 :eacl.relation/caveats #{2} :eacl.relation/allows-unqualified? true}
   2 (assoc named :db/id 2)
   3 (qualifier/entity-data 3 {:caveat 2 :valid-until-ms 100} parameters)
   4 (qualifier/entity-data 4 {:caveat 2 :caveat-context {"flag" true} :valid-until-ms 100} parameters)
   5 (qualifier/entity-data 5 {:valid-until-ms 100} [])})

(defn portable-evaluator [calls]
  (reify evaluator/Evaluator
    (descriptor [_] {:profile values/profile-id :profile-fingerprint evaluator/profile-fingerprint
                     :capability-version 1 :fingerprint "test/portable-qualified-v1"})
    (-evaluate [_ entity request bound]
      (swap! calls inc)
      (let [{:keys [parameters plan]} (definition/decode-entity entity)]
        (partial/evaluate parameters plan request (or bound {}))))))

(defn request
  ([] (request {}))
  ([{:keys [db reads calls] :as options}]
   (let [read-entity (fn [eid]
                       (when reads (swap! reads update eid (fnil inc 0)))
                       (get (or db fixture) eid))
         defaults {:time 99 :context {}
                   :evaluator (portable-evaluator (or calls (atom 0)))
                   :entity read-entity :version (constantly 7)
                   :basis {:source "s" :lifecycle "l" :revision 1}}]
     (q/request (merge defaults (dissoc options :db :reads :calls))))))

(defn data-adapter [db reads]
  (backend/make-adapter
   {:id :qualification-test :runtime-guards? true
    :capabilities {:qualification #{data/capability}}
    :operations
    (assoc (zipmap backend/required-snapshot-operations (repeat (constantly nil)))
           :qualification-data
           (fn [eid]
             (swap! reads update eid (fnil inc 0))
             (data/collect
              eid
              (for [[attribute value] (dissoc (get db eid) :db/id)
                    item (if (set? value) value [value])]
                {:e eid :a attribute :v item :tx 7})
              identity true)))}))

(defn data-request [db reads]
  (q/request-from-adapter
   (data-adapter db reads)
   {:time 99 :basis {:source "native-data" :revision 1}
    :evaluator (portable-evaluator (atom 0))}))

(deftest adapter-data-is-shared-and-metered-within-one-request
  (let [reads (atom {}) ledger (counters/make-ledger) request (data-request fixture reads)]
    (counters/call-with-ledger
     ledger
     (fn []
       (is (= {} @reads))
       (is (true? (q/qualify request 1 10)))
       (is (every? zero? (vals (counters/snapshot ledger))))
       (is (= :conditional-permission (evidence/permissionship (q/qualify request 1 [10 3]))))
       (let [before (counters/snapshot ledger)]
         (is (= :conditional-permission (evidence/permissionship (q/qualify request 1 [11 3]))))
         (is (= before (counters/snapshot ledger))))
       (is (= {1 1, 2 1, 3 1} @reads))
       (is (= 3 (:commands (counters/snapshot ledger))))
       (is (= 3 (:adapter-reads (counters/snapshot ledger))))
       (is (= (reduce + (map #(count (dissoc (get fixture %) :db/id)) [1 2 3]))
              (:fetched-values (counters/snapshot ledger))))))))

(deftest adapter-data-faults-remain-visible-and-do-not-refetch
  (doseq [db [(assoc-in fixture [3 :unknown/field] "private value")
              (assoc-in fixture [3 qualifier/caveat-attribute] 3)
              (dissoc fixture 3)]]
    (let [reads (atom {}) request (data-request db reads)]
      (is (evidence/fault? (q/qualify request 1 [10 3])))
      (let [before @reads]
        (is (evidence/fault? (q/qualify request 1 [11 3])))
        (is (= before @reads)))
      (is (= 1 (get @reads 3)))))
  (let [reads (atom {}) request (data-request fixture reads)
        token (execution/cancellation-token)
        contract (execution/normalize {} :check-permission {:cancellation-token token})]
    (execution/cancel! token)
    (binding [execution/*contract* contract]
      (is (= :eacl.execution/cancelled
             (try (q/qualify request 1 [10 3]) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                    (:type (ex-data error)))))))
    (is (= {} @reads))))

(deftest ordinary-edges-touch-no-request-state
  (is (true? (q/qualify nil nil 123)))
  (is (false? (q/qualify nil nil nil)))
  (let [r (request)]
    (dotimes [i 100] (is (true? (q/qualify r 1 i))))
    (is (not (realized? (:memos r))))))

(deftest request-cache-resolves-once-and-retains-faults
  (let [reads (atom {}) calls (atom 0) r (request {:reads reads :calls calls})]
    (dotimes [_ 5]
      (is (= :conditional-permission (evidence/permissionship (q/qualify r 1 [10 3]))))
      (is (= ["flag"] (evidence/missing-fields (q/qualify r 1 [10 3])))))
    (is (= {1 1 2 1 3 1} @reads))
    (is (= 10 @calls)))
  (let [reads (atom {}) r (request {:reads reads})]
    (dotimes [_ 5] (is (evidence/fault? (q/qualify r 1 [10 999]))))
    (is (= {999 1} @reads)))
  (with-redefs [q/maximum-request-entries 1]
    (is (evidence/fault? (q/qualify (request) 1 [10 3])))))

(deftest exclusive-expiry-precedes-program-work
  (doseq [time [99 100 101] qid [3 4 5]]
    (let [calls (atom 0) r (request {:time time :context {"flag" true} :calls calls})
          result (q/qualify r 1 [10 qid])]
      (is (= (< time 100) (evidence/has? result)))
      (is (= (when (< time 100) 100) (evidence/valid-until result)))
      (is (= (if (and (< time 100) (not= qid 5)) 1 0) @calls))))
  (with-redefs [plan/compile-plan (fn [& _] (throw (ex-info "Compilation must not run" {})))]
    (is (false? (q/qualify (request {:time 100}) 1 [10 3])))))

(deftest contextual-results-do-not-enter-the-decode-cache
  (let [cache (lru/store 16)
        reads (atom {})
        cases [[99 {"flag" true} :has-permission]
               [99 {"flag" false} :no-permission]
               [99 {} :conditional-permission]
               [100 {"flag" true} :no-permission]]]
    (doseq [[time context expected] cases]
      (let [r (request {:time time :context context :cache cache :reads reads})]
        (is (= expected (evidence/permissionship (q/qualify r 1 [10 3]))))))
    (is (= 1 (get @reads 3)))
    (is (= 1 (lru/entry-count cache)))
    (doseq [basis [{:source "s" :lifecycle "reset" :revision 1}
                  {:source "s" :lifecycle "l" :revision 2}]]
      (let [db (assoc-in fixture [3 :eacl.relationship-qualifier/valid-until-ms] 98)
            r (request {:db db :basis basis :cache cache :reads reads})]
        (is (false? (q/qualify r 1 [10 3])))))
    (is (= 3 (get @reads 3))))
  (let [bound-wins (request {:context {"flag" false}})
        invalid-request (request {:context {"flag" "wrong-type"}})]
    (is (evidence/has? (q/qualify bound-wins 1 [10 4])))
    (is (evidence/fault? (q/qualify invalid-request 1 [10 4])))))

(deftest authoritative-errors-survive-expiry-and-exclusion
  (doseq [db [(dissoc fixture 3)
              (assoc-in fixture [3 qualifier/marker-attribute] 99)
              (assoc-in fixture [3 qualifier/expiration-attribute] 1.5)
              (assoc-in fixture [1 :eacl.relation/caveats] #{77})
              (dissoc fixture 2)]]
    (let [result (q/qualify (request {:time 100 :db db}) 1 [10 3])]
      (is (evidence/fault? result))
      (is (evidence/fault? (evidence/combine :exclusion true result)))))
  (is (evidence/fault? (q/qualify (request {:evaluator nil}) 1 [10 3])))
  (is (false? (q/qualify (request {:time 100 :evaluator nil}) 1 [10 3])))
  (is (evidence/has? (q/qualify (request {:evaluator nil}) 1 [10 5]))))

(deftest cancellation-is-never-converted-to-absence
  (let [token (execution/cancellation-token)]
    (execution/cancel! token)
    (binding [execution/*contract* (execution/normalize {} :check-permission {:cancellation-token token})]
      (is (= :eacl.execution/cancelled
             (try (q/qualify (request) 1 [10 3]) nil
                  (catch #?(:clj Exception :cljs :default) error (:type (ex-data error)))))))))

(deftest trusted-clock-captures-a-nondecreasing-time
  (let [samples (atom [90 100 95 101]) calls (atom 0)
        sample (clock/clock #(let [v (first @samples)] (swap! calls inc) (swap! samples subvec 1) v))]
    (is (= [90 100 100 101] (vec (repeatedly 4 sample))))
    (is (= 4 @calls)))
  (let [sample (clock/clock (constantly 1.5))]
    (is (= :clock-time (try (sample) nil
                            (catch #?(:clj Exception :cljs :default) error (:reason (ex-data error))))))))
