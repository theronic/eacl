(ns eacl.request.context-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.snapshot-provider :as snapshot-provider]
            [eacl.backend.v8 :as backend]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.context :as context]
            [eacl.request.counters :as counters]))

(defn- test-adapter
  ([schema-generation-calls]
   (test-adapter schema-generation-calls 3))
  ([schema-generation-calls generation]
   (backend/make-adapter
    {:id :request-context-test
     :capabilities
     (assoc backend/empty-capabilities
            :cache-proofs #{:ordered-generations})
     :traversal-execution backend/strict-sequential-traversal-execution
     :operations
     (merge
      (into {}
            (map (fn [operation]
                   [operation (fn [& _] nil)]))
            backend/required-snapshot-operations)
      {:snapshot-id (constantly {:database-id ::database :basis-t 7})
       :source-scope (constantly {:source-id ::source :branch nil})
       :source-lifecycle (constantly ::lifecycle)
       :native-revision (constantly {:revision 7 :exact-locator 7})
       :order-hint (constantly 7)
       :exact-locator (constantly 7)
       :schema-generation
       (fn []
         (swap! schema-generation-calls inc)
         generation)
       :proof-frame
       (fn [relation-ids]
         {:schema-stamp 3
          :relation-stamps (mapv (fn [relation-id] [relation-id 5])
                                 relation-ids)})})})))

(defn- test-provider
  ([adapter release-fn]
   (test-provider adapter release-fn nil))
  ([adapter release-fn acquire-calls]
   (let [acquire
         (fn [& _]
           (when acquire-calls
             (swap! acquire-calls inc))
           {:adapter adapter
            :ownership :owned
            :release-token ::reader})]
     (snapshot-provider/make-provider
      {:id :request-context-test
       :capabilities
       (assoc backend/empty-capabilities
              :cache-proofs #{:ordered-generations})
       :traversal-execution backend/strict-sequential-traversal-execution
       :topology {:deployment :test}
       :execution-constraints
       snapshot-provider/default-execution-constraints
       :snapshot-ownership :owned
       :operations
       {:source-scope (constantly {:source-id ::source :branch nil})
        :source-lifecycle (constantly ::lifecycle)
        :acquire-current! acquire
        :acquire-authoritative! acquire
        :acquire-at-least! acquire
        :acquire-exact! acquire
        :release! release-fn}}))))

(defn- contract
  []
  (execution/normalize {} :check-permission {}))

(defn- make-selected-context
  [{:keys [release-fn ledger registry]
    :or {release-fn (fn [_])
         ledger (counters/make-ledger)
         registry (atom {})}}]
  (let [schema-generation-calls (atom 0)
        acquire-calls (atom 0)
        release-calls (atom 0)
        adapter (test-adapter schema-generation-calls)
        provider
        (test-provider
         adapter
         (fn [token]
           (swap! release-calls inc)
           (release-fn token))
         acquire-calls)
        selected (snapshot-provider/acquire! provider :current)]
    {:context
     (context/make-context
      {:runtime {:derived-schema-caches registry}
       :adapter adapter
       :selected-snapshot selected
       :contract (contract)
       :counter-ledger ledger})
     :adapter adapter
     :selected selected
     :ledger ledger
     :registry registry
     :acquire-calls acquire-calls
     :release-calls release-calls
     :schema-generation-calls schema-generation-calls}))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest context-owns-one-lazy-request-invariant-state-test
  (let [release-tokens (atom [])
        {:keys [context adapter ledger registry acquire-calls release-calls
                schema-generation-calls]}
        (make-selected-context
         {:release-fn #(swap! release-tokens conj %)})]
    (is (context/context? context))
    (is (identical? adapter (context/adapter context)))
    (is (identical? registry
                    (:derived-schema-caches (context/runtime context))))
    (is (= :owned (context/ownership context)))
    (is (= :check-permission (:operation (context/contract context))))
    (is (= {:backend :request-context-test
            :source-id ::source
            :branch nil
            :source-lifecycle ::lifecycle
            :revision 7
            :exact-locator 7
            :backend-snapshot-id
            {:database-id ::database :basis-t 7}}
           (context/basis-identity context)))
    (is (zero? @schema-generation-calls)
        "construction keeps schema generation lazy")
    (is (identical? (context/derived context)
                    (context/derived context)))
    (is (= 3 (context/schema-generation context)))
    (is (= 1 @schema-generation-calls))
    (is (= 1 (count @registry)))
    (is (= :complete
           (:status
            (proof-frame/resolve! (context/proof-frame context) [11]))))
    (is (= 1 @schema-generation-calls)
        "the proof frame shares the context's generation resolution")
    (is (= 1 (:context-constructions (counters/snapshot ledger))))
    (is (= 1 (:generation-reads (counters/snapshot ledger))))
    (is (true?
         (context/call-with-context
          context
          (fn [active]
            (and (identical? context active)
                 (identical? (context/contract context)
                             execution/*contract*)
                 (identical? ledger counters/*ledger*))))))

    (context/buffer-publication! context {:kind :answer})
    (context/buffer-publication! context {:kind :cursor})
    (is (= [{:kind :answer} {:kind :cursor}]
           (context/take-publications! context)))
    (is (= [] (context/take-publications! context)))

    (is (true? (context/close! context)))
    (is (true? (context/closed? context)))
    (is (false? (context/close! context)))
    (is (= 1 @acquire-calls))
    (is (= 1 @release-calls))
    (is (= [::reader] @release-tokens))
    (is (= 1 (:releases (counters/snapshot ledger))))
    (is (= :eacl.request/context-closed
           (:type (error-data #(context/adapter context)))))))

(deftest request-local-memos-retain-false-and-nil-test
  (let [{request-context :context}
        (make-selected-context {})
        builds (atom 0)]
    (try
      (is (false?
           (context/memoized!
            request-context :decisions :deny
            #(do (swap! builds inc) false))))
      (is (false?
           (context/memoized!
            request-context :decisions :deny
            #(do (swap! builds inc) true))))
      (is (nil?
           (context/memoized!
            request-context :cursor-proofs :missing
            #(do (swap! builds inc) nil))))
      (is (nil?
           (context/memoized!
            request-context :cursor-proofs :missing
            #(do (swap! builds inc) :unexpected))))
      (is (= 2 @builds))
      (finally
        (context/close! request-context)))))

(deftest uncertified-generation-uses-one-request-local-floor-test
  (let [generation-calls (atom 0)
        adapter (test-adapter generation-calls nil)
        registry (atom {})
        basis-identity
        {:backend :request-context-test
         :source-id ::source
         :branch nil
         :source-lifecycle ::lifecycle
         :revision 7
         :exact-locator 7
         :backend-snapshot-id {:database-id ::database :basis-t 7}}
        request-context
        (context/make-context
         {:runtime {:derived-schema-caches registry}
          :adapter adapter
          :basis-identity basis-identity
          :contract (contract)})]
    (try
      (is (nil? (context/schema-generation request-context)))
      (is (true? (:request-local? (context/derived request-context))))
      (is (identical? (context/derived request-context)
                      (context/derived request-context)))
      (is (= 1 @generation-calls))
      (is (empty? @registry))
      (is (= :borrowed (context/ownership request-context)))
      (finally
        (context/close! request-context)))))

(deftest construction-failure-releases-transferred-snapshot-test
  (let [release-tokens (atom [])
        schema-generation-calls (atom 0)
        adapter (test-adapter schema-generation-calls)
        provider (test-provider adapter #(swap! release-tokens conj %))
        selected (snapshot-provider/acquire! provider :current)
        failure
        (error-data
         #(context/make-context
           {:runtime {}
            :adapter adapter
            :selected-snapshot selected
            :basis-identity {:not :closed}
            :contract (contract)}))]
    (is (= :eacl.request/invalid-context (:type failure)))
    (is (= [::reader] @release-tokens))
    (is (snapshot-provider/released? selected))))

(deftest failed-close-is-retryable-and-discards-publications-test
  (let [attempts (atom 0)
        {:keys [context selected]}
        (make-selected-context
         {:release-fn
          (fn [_]
            (when (= 1 (swap! attempts inc))
              (throw (ex-info "injected" {:type :test/release}))))})]
    (context/buffer-publication! context {:kind :answer})
    (is (= :eacl/snapshot-release-failed
           (:type (error-data #(context/close! context)))))
    (is (false? (context/closed? context)))
    (is (false? (snapshot-provider/released? selected)))
    (is (= [] (context/take-publications! context)))
    (is (true? (context/close! context)))
    (is (= 2 @attempts))))

#?(:clj
   (deftest context-rejects-thread-escape-test
     (let [{request-context :context
            acquire-calls :acquire-calls
            release-calls :release-calls}
           (make-selected-context {})
           access-failure
           @(future
              (error-data #(context/adapter request-context)))
           close-failure
           @(future
              (error-data #(context/close! request-context)))]
       (is (= :eacl.request/context-thread-violation
              (:type access-failure)))
       (is (= :context-access (:operation access-failure)))
       (is (= :eacl.request/context-thread-violation
              (:type close-failure)))
       (is (= :context-close (:operation close-failure)))
       (is (= 1 @acquire-calls))
       (is (false? (context/closed? request-context)))
       (is (true? (context/close! request-context)))
       (is (= 1 @release-calls)))))
