(ns eacl.subproblem-cache-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as lru]
            [eacl.execution :as execution]
            [eacl.subproblem-cache :as subproblem]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test])))

(def small-options
  {:denotation-max-entries 2
   :answer-max-entries 2})

(def accept-any-publication
  {:valid? (constantly true)})

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
           error
      (ex-data error))))

(defn- page
  [n]
  {:data (vec (range n))
   :page-info {}})

(defn- exact-key-fn
  [revision]
  (fn [semantic]
    (let [identity {:tier :denotation
                    :source-lifecycle {:source :primary :lifecycle :one}
                    :abi :test-authorization-v2
                    :semantic semantic
                    :reuse [:basis revision]}]
      (cache-key/exact-denotation-key identity))))

(defn- storage-key
  [tier semantic]
  (let [identity {:tier tier
                  :source-lifecycle {:source :primary :lifecycle :one}
                  :abi :test-authorization-v2
                  :semantic semantic
                  :reuse [:basis 1]}]
    (if (= :answer tier)
      (cache-key/exact-answer-key identity)
      (cache-key/exact-denotation-key identity))))

(defn- test-snapshot-entry-valid?
  [{:keys [tier key]}]
  (and (= tier (get-in key [2 0]))
       (= :exact (get-in key [2 1]))))

(deftest count-capacity-tier-isolation-and-hot-lru-test
  (let [store (subproblem/store small-options)]
    (subproblem/publish!
     store :denotation (storage-key :denotation :hot)
     accept-any-publication :hot)
    (subproblem/publish!
     store :denotation (storage-key :denotation :cold)
     accept-any-publication :cold)
    (subproblem/publish!
     store :answer (storage-key :answer :a1)
     accept-any-publication :d1)
    (dotimes [_ 100]
      (is (= :hot (:value (subproblem/lookup!
                           store :denotation
                           (storage-key :denotation :hot))))))
    (subproblem/publish!
     store :denotation (storage-key :denotation :new)
     accept-any-publication :new)
    (let [resident (subproblem/resident-tier-entries store :denotation)
          values (set (map :value resident))]
      (is (= 2 (count resident)))
      (is (contains? values :hot))
      (is (= 1 (count (filter values [:cold :new])))
          "adaptive admission may retain either cold candidate"))
    (is (= :d1 (:value (subproblem/lookup!
                        store :answer
                        (storage-key :answer :a1)))))
    (is (= {:entries 2 :max-entries 2}
           (get-in (subproblem/stats store) [:tiers :denotation])))
    (is (= {:entries 1 :max-entries 2}
           (get-in (subproblem/stats store) [:tiers :answer])))))

(deftest managed-eligibility-touches-only-an-accepted-retrieval-test
  (testing "causal rejection does not keep an unusable mapping hot"
    (let [store (subproblem/store small-options)
          future-key (storage-key :answer :future)
          usable-key (storage-key :answer :usable)
          new-key (storage-key :answer :new)]
      (subproblem/publish!
       store :answer future-key accept-any-publication {:revision 9})
      (subproblem/publish!
       store :answer usable-key accept-any-publication {:revision 1})
      (is (nil? (subproblem/lookup-eligible!
                 store :answer future-key
                 #(<= (:revision %) 5))))
      (subproblem/publish!
       store :answer new-key accept-any-publication {:revision 2})
      ;; Rejection is a semantic miss, not a policy touch. Window TinyLFU does
      ;; not promise the strict-LRU victim selected after the later insertion.
      (is (= 2 (count (subproblem/resident-tier-entries store :answer))))))
  (testing "an eligible retrieval does refresh recency"
    (let [store (subproblem/store small-options)
          hot-key (storage-key :answer :hot)
          cold-key (storage-key :answer :cold)
          new-key (storage-key :answer :new)]
      (subproblem/publish!
       store :answer hot-key accept-any-publication {:revision 1})
      (subproblem/publish!
       store :answer cold-key accept-any-publication {:revision 2})
      (let [touches (atom [])
            original-hit-if-value! lru/hit-if-value!]
        (is (= {:revision 1}
               (:value
                (with-redefs
                 [lru/hit-if-value!
                  (fn [tier-store key value]
                    (swap! touches conj [key value])
                    (original-hit-if-value! tier-store key value))]
                 (subproblem/lookup-eligible!
                  store :answer hot-key (constantly true))))))
        (is (= [[hot-key {:revision 1}]] @touches)))
      (subproblem/publish!
       store :answer new-key accept-any-publication {:revision 3})
      (is (= 2 (count (subproblem/resident-tier-entries
                       store :answer)))))))

(deftest explicit-membership-retains-nil-and-false-test
  (let [store (subproblem/store small-options)]
    (is (:published? (subproblem/publish!
                      store :denotation
                      (storage-key :denotation :nil)
                      accept-any-publication nil)))
    (is (:published? (subproblem/publish!
                      store :answer
                      (storage-key :answer :false)
                      accept-any-publication false)))
    (let [nil-hit (subproblem/lookup!
                   store :denotation (storage-key :denotation :nil))
          false-hit (subproblem/lookup!
                     store :answer (storage-key :answer :false))]
      (is (:cached? nil-hit))
      (is (contains? nil-hit :value))
      (is (nil? (:value nil-hit)))
      (is (:cached? false-hit))
      (is (false? (:value false-hit))))))

(deftest concurrent-lookup-telemetry-counts-every-hit-test
  #?(:clj
     (let [store (subproblem/store small-options)
           key (storage-key :answer :concurrent-hot)
           workers 8
           iterations 1000]
       (subproblem/publish!
        store :answer key accept-any-publication :resident)
       (run!
        deref
        (doall
         (repeatedly
          workers
          #(future
             (dotimes [_ iterations]
               (when-not (= :resident
                            (:value
                             (subproblem/lookup! store :answer key)))
                 (throw (ex-info "Concurrent cache hit changed value." {}))))))))
       (let [expected (* workers iterations)
             metrics (subproblem/stats store)]
         (is (= expected (:lookup-probes metrics)))
         (is (= expected (:hits metrics)))
         (is (= expected (:answer-hits metrics)))
         (is (zero? (:lookup-misses metrics)))
         (is (zero? (:denotation-hits metrics)))))
     :cljs
     (is true)))

(deftest invalid-values-never-publish-test
  (let [store (subproblem/store small-options)]
    (is (= {:published? false :reason :invalid-value}
           (subproblem/publish!
            store :denotation (storage-key :denotation :invalid)
            {:valid? integer?}
            :invalid)))
    (is (nil? (subproblem/lookup!
               store :denotation (storage-key :denotation :invalid))))
    (is (= {:published? false :reason :invalid-value}
           (subproblem/publish!
            store :denotation (storage-key :denotation :lax)
            {:valid? integer?}
            :not-an-integer)))
    (is (nil? (subproblem/lookup!
               store :denotation (storage-key :denotation :lax))))
    (is (= 2 (:invalid-results (subproblem/stats store))))))

(deftest request-ineligible-publication-is-rejected-before-validation-test
  (let [store (subproblem/store small-options)
        clock (atom 0)
        token (execution/cancellation-token)
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           {:execution-timeout-ms 1}
           :can?
           {:cancellation-token token}))
        validations (atom 0)
        options {:valid? (fn [_] (swap! validations inc) true)}]
    (execution/cancel! token)
    (is (= {:published? false :reason :cancelled}
           (binding [execution/*contract* contract
                     execution/*monotonic-nanos* #(deref clock)]
             (subproblem/publish!
              store :denotation (storage-key :denotation :cancelled)
              options true))))
    (reset! clock 1000000)
    (is (= {:published? false :reason :deadline-expired}
           (binding [execution/*contract* contract
                     execution/*monotonic-nanos* #(deref clock)]
             (subproblem/publish!
              store :denotation (storage-key :denotation :expired)
              options true))))
    (is (zero? @validations)
        "request eligibility is checked before artifact validation")
    (is (nil? (subproblem/lookup!
               store :denotation (storage-key :denotation :cancelled))))
    (is (nil? (subproblem/lookup!
               store :denotation (storage-key :denotation :expired))))
    (testing "cancellation observed during validation wins before LRU mutation"
      (let [late-token (execution/cancellation-token)
            late-contract
            (binding [execution/*monotonic-nanos* (constantly 0)]
              (execution/normalize
               {:execution-timeout-ms 100}
               :can?
               {:cancellation-token late-token}))
            key (storage-key :denotation :cancelled-during-validation)]
        (is (= {:published? false :reason :cancelled}
               (binding [execution/*contract* late-contract
                         execution/*monotonic-nanos* (constantly 0)]
                 (subproblem/publish!
                  store :denotation key
                  {:valid? (fn [_]
                             (execution/cancel! late-token)
                             true)}
                  true))))
        (is (nil? (subproblem/lookup! store :denotation key)))))))

(deftest request-ineligible-lookup-skips-store-metrics-and-recency-test
  (doseq [mode [:cancelled :deadline-expired]]
    (testing (name mode)
      (let [store (subproblem/store small-options)
            hot-key (storage-key :answer [mode :hot])
            cold-key (storage-key :answer [mode :cold])
            new-key (storage-key :answer [mode :new])
            clock (atom 0)
            token (execution/cancellation-token)
            contract
            (binding [execution/*monotonic-nanos* #(deref clock)]
              (execution/normalize
               {:execution-timeout-ms 1}
               :can?
               {:cancellation-token token}))
            store-probes (atom 0)
            eligibility-probes (atom 0)]
        (subproblem/publish!
         store :answer hot-key accept-any-publication :hot)
        (subproblem/publish!
         store :answer cold-key accept-any-publication :cold)
        (if (= :cancelled mode)
          (execution/cancel! token)
          (reset! clock 1000000))
        (let [stats-before (subproblem/stats store)
              no-probe
              (fn [& _]
                (swap! store-probes inc)
                (throw (ex-info "ineligible lookup reached LRU" {})))]
          (with-redefs [lru/lookup! no-probe
                        lru/peek-entry no-probe
                        lru/hit-if-value! no-probe]
            (binding [execution/*contract* contract
                      execution/*monotonic-nanos* #(deref clock)]
              (is (nil? (subproblem/lookup! store :answer hot-key)))
              (is (nil?
                   (subproblem/lookup-eligible!
                    store :answer hot-key
                    (fn [_]
                      (swap! eligibility-probes inc)
                      true))))))
          (is (zero? @store-probes))
          (is (zero? @eligibility-probes))
          (is (= stats-before (subproblem/stats store))
              "an unavailable cache stage does not mutate telemetry"))
        (subproblem/publish!
         store :answer new-key accept-any-publication :new)
        ;; The raw storage non-probe and unchanged telemetry above establish
        ;; the no-touch behavior. Victim identity is policy-specific.
        (is (= 2 (count (subproblem/resident-tier-entries
                         store :answer))))))))

(deftest publication-requires-an-explicit-callable-validator-test
  (let [store (subproblem/store small-options)
        key (storage-key :denotation :unchecked)
        missing (error-data #(subproblem/publish! store :denotation key {} 42))
        non-callable
        (error-data
         #(subproblem/publish!
           store :denotation key {:valid? 42} 42))]
    (is (= :eacl/invalid-config (:type missing)))
    (is (= :valid? (:required-key missing)))
    (is (= :eacl/invalid-config (:type non-callable)))
    (is (= 42 (:valid? non-callable)))
    (is (nil? (subproblem/lookup! store :denotation key)))))

(deftest publication-validation-runs-once-and-never-on-hit-test
  (let [store (subproblem/store small-options)
        validations (atom 0)
        options
        {:valid? (fn [value]
                   (swap! validations inc)
                   (= 42 value))}
        key (storage-key :denotation :value)]
    (is (:published? (subproblem/publish!
                      store :denotation key options 42)))
    (is (= 1 @validations))
    (is (= 42
           (:value (subproblem/lookup! store :denotation key))))
    (is (= 42
           (:value (subproblem/lookup! store :denotation key))))
    (is (= 1 @validations))
    (is (= 1 (:puts (subproblem/stats store))))))

(deftest content-change-callback-runs-on-mapping-changes-only-test
  (let [changes (atom 0)
        store (subproblem/store small-options #(swap! changes inc))]
    (subproblem/publish!
     store :denotation (storage-key :denotation :key)
     accept-any-publication :value)
    (subproblem/lookup!
     store :denotation (storage-key :denotation :key))
    (subproblem/publish!
     store :denotation (storage-key :denotation :key)
     accept-any-publication :value)
    (is (= 1 @changes))))

(deftest optional-content-change-callback-cannot-fail-a-request-test
  (let [store (subproblem/store
               small-options
               #(throw (ex-info "dirty tracker failed" {})))]
    (is (:published?
         (subproblem/publish!
          store :denotation (storage-key :denotation :key)
          accept-any-publication :value)))
    (is (= :value
           (:value (subproblem/lookup!
                    store :denotation
                    (storage-key :denotation :key)))))))

(deftest private-store-failure-degrades-to-miss-or-rejected-publication-test
  (let [store (subproblem/store small-options)
        key (storage-key :denotation :store-failure)
        [lookup publication]
        (with-redefs [lru/lookup!
                      (fn [_ _]
                        (throw (ex-info "lookup failed" {})))
                      lru/put-if-absent!
                      (fn [_ _ _]
                        (throw (ex-info "publication failed" {})))]
          [(subproblem/lookup! store :denotation key)
           (subproblem/publish!
            store :denotation key accept-any-publication :computed)])]
    (is (nil? lookup))
    (is (= {:published? false :reason :store-error} publication))
    (is (= 2 (:store-errors (subproblem/stats store))))))

(deftest bound-direct-operations-use-complete-exact-key-test
  (let [store (subproblem/store small-options)
        key-fn (exact-key-fn 10)
        expected (key-fn :semantic)]
    (binding [subproblem/*store* store
              subproblem/*exact-denotation-key-fn* key-fn]
      (is (:published?
           (subproblem/publish-denotation!
            :semantic {:valid? keyword?} :answer)))
      (is (= :answer
             (:value
              (subproblem/lookup-denotation! :semantic))))
      (is (= :answer
             (:value (subproblem/lookup!
                      store :denotation expected))))
      (is (= [expected]
             (mapv :key
                   (subproblem/snapshot-tier-entries
                    store :denotation)))))))

(deftest incomplete-bound-exact-key-fails-cache-closed-test
  (let [store (subproblem/store small-options)]
    (binding [subproblem/*store* store
              subproblem/*exact-denotation-key-fn* (fn [_] :incomplete)]
      (is (nil? (subproblem/lookup-denotation! :semantic)))
      (is (= {:published? false :reason :incomplete-key}
             (subproblem/publish-denotation!
              :semantic {:valid? keyword?} :forbidden))))
    (is (zero? (get-in (subproblem/stats store)
                       [:tiers :denotation :entries])))))

(deftest nil-bound-exact-key-fails-cache-closed-test
  (let [store (subproblem/store small-options)]
    (binding [subproblem/*store* store
              subproblem/*exact-denotation-key-fn* nil]
      (is (nil? (subproblem/lookup-denotation! :semantic)))
      (is (= {:published? false :reason :incomplete-key}
             (subproblem/publish-denotation!
              :semantic {} :forbidden))))
    (is (empty? (subproblem/snapshot-tier-entries
                 store :denotation)))))

(deftest completed-page-retention-boundary-test
  (let [store (subproblem/store
               {:answer-max-entries 8
                :denotation-max-entries 2})
        p1000 (page 1000)
        p1001 (page 1001)
        p10000 (page 10000)]
    (is (:published?
         (subproblem/publish!
          store :answer (storage-key :answer :p1000)
          accept-any-publication p1000)))
    (is (= :page-too-large
           (:reason
            (subproblem/publish!
             store :answer (storage-key :answer :p1001)
             accept-any-publication
             {:format :eacl.cache/completed-answer-v2
              :value p1001
              :cache-basis {:basis 1}
              :computed-revision 1
              :computed-exact-locator 1}))))
    (is (= :page-too-large
           (:reason
            (subproblem/publish!
             store :answer (storage-key :answer :p10000)
             accept-any-publication
             {:format :eacl.cache/completed-answer-v2
              :value p10000
              :cache-basis {:basis 1}
              :computed-revision 1
              :computed-exact-locator 1}))))
    (is (= p1000 (:value (subproblem/lookup!
                          store :answer
                          (storage-key :answer :p1000)))))
    (is (nil? (subproblem/lookup!
               store :answer (storage-key :answer :p1001))))
    (is (nil? (subproblem/lookup!
               store :answer (storage-key :answer :p10000))))
    (testing "the guard does not apply to scalar, tree, or denotation values"
      (is (:published?
           (subproblem/publish!
            store :answer (storage-key :answer :count)
            accept-any-publication 10000)))
      (is (:published?
           (subproblem/publish!
            store :answer (storage-key :answer :tree)
            accept-any-publication
            {:children (vec (range 1001))})))
      (is (:published?
           (subproblem/publish!
            store :answer (storage-key :answer :tree-with-value)
            accept-any-publication
            {:value p1001
             :children (vec (range 1001))})))
      (is (:published?
           (subproblem/publish!
            store :denotation (storage-key :denotation :large)
            accept-any-publication
            (vec (range 1001))))))))

(deftest flat-snapshot-is-deterministic-and-restorable-test
  (let [options {:denotation-max-entries 4
                 :answer-max-entries 4}
        left (subproblem/store options)
        right (subproblem/store options)]
    (doseq [[tier semantic value]
            [[:denotation :b nil]
             [:answer :c false]
             [:denotation :a 1]]]
      (subproblem/publish!
       left tier (storage-key tier semantic) accept-any-publication value))
    (doseq [[tier semantic value]
            [[:denotation :a 1]
             [:answer :c false]
             [:denotation :b nil]]]
      (subproblem/publish!
       right tier (storage-key tier semantic) accept-any-publication value))
    (let [snapshot (subproblem/export-snapshot left {:max-entries 12})
          validations (atom 0)
          restored
          (subproblem/restore-store
           snapshot options nil
           {:entry-valid?
            (fn [entry]
              (swap! validations inc)
              (test-snapshot-entry-valid? entry))})]
      (is (= snapshot
             (subproblem/export-snapshot right {:max-entries 12})))
      (is (= #{:format :entries :entry-count} (set (keys snapshot))))
      (is (= 3 (:entry-count snapshot)))
      (is (= 3 @validations))
      (is (nil? (:value (subproblem/lookup!
                         restored :denotation
                         (storage-key :denotation :b)))))
      (is (false? (:value (subproblem/lookup!
                           restored :answer
                           (storage-key :answer :c)))))
      (is (= 1 (:value (subproblem/lookup!
                        restored :denotation
                        (storage-key :denotation :a))))))))

(deftest malformed-old-and-over-capacity-snapshots-are-rejected-test
  (let [options {:denotation-max-entries 1
                 :answer-max-entries 1}
        error-type
        (fn [snapshot]
          (try
            (subproblem/restore-store
             snapshot options nil
             {:entry-valid? test-snapshot-entry-valid?})
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo)
                   error
              (:type (ex-data error)))))]
    (is (= :eacl/cache-snapshot-incompatible
           (error-type {:format :eacl.subproblem-cache/snapshot-v1
                        :entries [] :entry-count 0})))
    (is (= :eacl/cache-snapshot-incompatible
           (error-type
            {:format subproblem/snapshot-format
             :entries
             [{:tier :denotation
               :key (storage-key :denotation :a) :value 1}
              {:tier :denotation
               :key (storage-key :denotation :b) :value 2}]
             :entry-count 2})))))

(deftest snapshot-restore-validator-and-page-guard-fail-closed-test
  (let [options {:denotation-max-entries 2
                 :answer-max-entries 2}
        one-entry
        {:format subproblem/snapshot-format
         :entries [{:tier :denotation
                    :key (storage-key :denotation :value)
                    :value 42}]
         :entry-count 1}
        failure-type
        (fn [snapshot validator]
          (try
            (subproblem/restore-store
             snapshot options nil {:entry-valid? validator})
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo)
                   error
              (:type (ex-data error)))))]
    (is (= :eacl/invalid-config
           (try
             (subproblem/restore-store one-entry options)
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core.ExceptionInfo)
                    error
               (:type (ex-data error))))))
    (is (= :eacl/cache-snapshot-incompatible
           (failure-type one-entry (constantly false))))
    (is (= :eacl/cache-snapshot-incompatible
           (failure-type one-entry #(throw (ex-info "invalid" {:entry %})))))
    (is (= :eacl/cache-snapshot-incompatible
           (failure-type
            {:format subproblem/snapshot-format
             :entries [{:tier :answer
                        :key (storage-key :answer :large-page)}
                       :value
                       {:format :eacl.cache/completed-answer-v2
                        :value (page 1001)
                        :cache-basis {:basis 1}
                        :computed-revision 1
                        :computed-exact-locator 1}]
             :entry-count 1}
            (constantly true))))))

(deftest removed-projection-and-weight-options-fail-closed-test
  (doseq [removed-option [:projection-max-entries
                          :projection-max-weight
                          :managed-proof-max-atoms]]
    (let [config-error
          (try
            (subproblem/store {removed-option 10})
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo)
                   error
              (ex-data error)))]
      (is (= :eacl/invalid-config (:type config-error)))
      (is (= [removed-option] (:unknown-keys config-error)))))
  (let [operation-error
        (try
          (subproblem/publish!
           (subproblem/store small-options)
           :denotation (storage-key :denotation :key)
           {:weight-fn (constantly 1)} :value)
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo)
                 error
            (ex-data error)))]
    (is (= :eacl/invalid-config (:type operation-error)))
    (is (= [:weight-fn] (:unknown-keys operation-error)))))
