(ns eacl.backend.snapshot-provider-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.snapshot-provider :as provider]
            [eacl.backend.v8 :as backend]))

(defn- adapter
  ([] (adapter :test backend/strict-sequential-traversal-execution))
  ([backend-id traversal-execution]
   (adapter backend-id traversal-execution {}))
  ([backend-id traversal-execution
    {:keys [source-id branch lifecycle revision exact-locator
            snapshot-database-id]
     :or {source-id ::source
          branch nil
          lifecycle ::lifecycle
          revision 1
          exact-locator 1}}]
   (backend/make-adapter
    {:id backend-id
     :capabilities backend/empty-capabilities
     :traversal-execution traversal-execution
     :operations
     (merge
      (into {}
            (map (fn [operation-key]
                   [operation-key (fn [& _] nil)]))
            backend/required-snapshot-operations)
      {:snapshot-id
       (constantly
        {:database-id (or snapshot-database-id backend-id)
         :basis-t revision})
       :source-scope
       (constantly {:source-id source-id :branch branch})
       :source-lifecycle (constantly lifecycle)
       :native-revision
       (constantly {:revision revision :exact-locator exact-locator})
       :order-hint (constantly revision)
       :exact-locator (constantly exact-locator)})})))

(defn- snapshot-provider
  [{:keys [candidate ownership-policy release-calls
           traversal-execution backend-id execution-constraints
           acquire-calls]
    :or {candidate (adapter)
         ownership-policy :owned
         release-calls (atom [])
         traversal-execution backend/strict-sequential-traversal-execution
         backend-id :test}}]
  (let [acquire
        (fn [& _]
          (when acquire-calls
            (swap! acquire-calls inc))
          {:adapter candidate
           :ownership (if (= :borrowed ownership-policy)
                        :borrowed
                        :owned)
           :release-token ::reader})]
    (provider/make-provider
     {:id backend-id
      :capabilities backend/empty-capabilities
      :traversal-execution traversal-execution
      :topology {:deployment :embedded}
      :execution-constraints
      (or execution-constraints provider/default-execution-constraints)
      :snapshot-ownership ownership-policy
      :operations
      {:source-scope
       (constantly {:source-id ::source :branch nil})
       :source-lifecycle (constantly ::lifecycle)
       :acquire-current! acquire
       :acquire-authoritative! acquire
       :acquire-at-least! acquire
       :acquire-exact! acquire
       :release! #(swap! release-calls conj %)}})))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest provider-contract-is-closed-test
  (testing "unknown provider fields fail validation"
    (is (= [:surprise]
           (:unknown-keys
            (error-data
             #(provider/make-provider
               {:id :test
                :capabilities backend/empty-capabilities
                :traversal-execution
                backend/strict-sequential-traversal-execution
                :topology {}
                :execution-constraints
                provider/default-execution-constraints
                :snapshot-ownership :borrowed
                :operations {}
                :surprise true}))))))
  (testing "every lifecycle operation is mandatory"
    (is (= provider/required-provider-operations
           (set
            (:missing-operations
             (error-data
              #(provider/make-provider
                {:id :test
                 :capabilities backend/empty-capabilities
                 :traversal-execution
                 backend/strict-sequential-traversal-execution
                 :topology {}
                 :execution-constraints
                 provider/default-execution-constraints
                 :snapshot-ownership :borrowed
                 :operations {}})))))))
  (testing "execution constraints are closed enums"
    (is (= :eacl/invalid-snapshot-provider
           (:type
            (error-data
             #(provider/make-provider
               {:id :test
                :capabilities backend/empty-capabilities
                :traversal-execution
                backend/strict-sequential-traversal-execution
                :topology {}
                :execution-constraints
                {:virtual-threads :sometimes
                 :snapshot-thread :any
                 :release-thread :any}
                :snapshot-ownership :borrowed
                :operations
                (zipmap provider/required-provider-operations
                        (repeat (fn [& _] nil)))})))))))

(deftest provider-static-profile-does-not-acquire-test
  (let [calls (atom {})
        source (snapshot-provider {})]
    (binding [provider/*provider-op-stats* calls]
      (is (= :test (:backend-id (provider/static-profile source))))
      (is (= {:source-id ::source :branch nil}
             (provider/source-scope source)))
      (is (= ::lifecycle (provider/source-lifecycle source))))
    (is (= {:source-scope 1 :source-lifecycle 1} @calls))
    (is (not-any? #(contains? @calls %)
                  (vals provider/acquisition-operations)))))

(deftest selected-snapshot-owns-one-idempotent-release-test
  (let [release-calls (atom [])
        source (snapshot-provider {:release-calls release-calls})
        selected (provider/acquire! source :current)]
    (is (provider/provider? source))
    (is (provider/selected-snapshot? selected))
    (is (= :owned (provider/ownership selected)))
    (is (= :test (backend/backend-id (provider/adapter selected))))
    (is (= {:backend :test
            :source-id ::source
            :branch nil
            :source-lifecycle ::lifecycle
            :revision 1
            :exact-locator 1
            :backend-snapshot-id {:database-id :test :basis-t 1}}
           (provider/semantic-identity selected)))
    (is (false? (provider/released? selected)))
    (is (true? (provider/release! selected)))
    (is (true? (provider/released? selected)))
    (is (false? (provider/release! selected)))
    (is (= [::reader] @release-calls))
    (is (= :eacl/snapshot-released
           (:type (error-data #(provider/adapter selected)))))))

(deftest failed-release-is-classified-and-remains-retryable-test
  (let [attempts (atom 0)
        source (snapshot-provider {})
        retryable
        (assoc-in
         source
         [::provider/operations :release!]
         (fn [_]
           (when (= 1 (swap! attempts inc))
             (throw (ex-info "injected release failure"
                             {:type :injected/release})))))
        selected (provider/acquire! retryable :current)]
    (is (= :eacl/snapshot-release-failed
           (:type (error-data #(provider/release! selected)))))
    (is (false? (provider/released? selected)))
    (is (= :test (backend/backend-id (provider/adapter selected))))
    (is (true? (provider/release! selected)))
    (is (true? (provider/released? selected)))
    (is (= 2 @attempts))
    (is (false? (provider/release! selected)))))

(deftest semantic-identity-covers-every-independent-state-dimension-test
  (let [identity
        (fn [overrides]
          (let [source
                (snapshot-provider
                 {:candidate
                  (adapter
                   :test backend/strict-sequential-traversal-execution
                   overrides)})
                selected (provider/acquire! source :current)]
            (try
              (provider/semantic-identity selected)
              (finally
                (provider/release! selected)))))
        baseline (identity {})]
    (testing "separately acquired readers of the same semantic state compare equal"
      (is (= baseline (identity {}))))
    (doseq [[field overrides]
            [[:backend {:snapshot-database-id :other-database}]
             [:source {:source-id ::other-source}]
             [:branch {:branch "other-branch"}]
             [:lifecycle {:lifecycle ::other-lifecycle}]
             [:revision {:revision 2 :exact-locator 2}]
             [:exact-locator {:exact-locator :other-locator}]]]
      (testing (name field)
        (is (not= baseline (identity overrides)))))))

(deftest acquisition-validates-provider-boundaries-test
  (testing "backend identity cannot change during acquisition"
    (let [release-calls (atom [])
          source
          (snapshot-provider
           {:candidate (adapter :other
                                backend/strict-sequential-traversal-execution)
            :release-calls release-calls})]
      (is (= :eacl/invalid-selected-snapshot
             (:type (error-data #(provider/acquire! source :current)))))
      (is (= [::reader] @release-calls))))
  (testing "the adapter must retain the provider-static traversal profile"
    (let [source
          (snapshot-provider
           {:candidate (adapter :test backend/default-traversal-execution)})]
      (is (= :eacl/invalid-selected-snapshot
             (:type (error-data #(provider/acquire! source :current)))))))
  (testing "ownership cannot contradict the static policy"
    (let [source
          (snapshot-provider
           {:candidate (adapter)
            :ownership-policy :owned})
          broken
          (assoc-in
           source
           [::provider/operations :acquire-current!]
           (fn []
             {:adapter (adapter)
              :ownership :borrowed
              :release-token nil}))]
      (is (= :eacl/invalid-selected-snapshot
             (:type (error-data #(provider/acquire! broken :current)))))))
  (testing "acquisition result shape is closed"
    (let [release-calls (atom [])
          source (snapshot-provider {:release-calls release-calls})
          broken
          (assoc-in
           source
           [::provider/operations :acquire-current!]
           (fn []
             {:adapter (adapter)
              :ownership :owned
              :release-token ::reader
              :native-handle :escaped}))]
      (is (= #{:adapter :ownership :release-token :native-handle}
             (:actual-keys
              (error-data #(provider/acquire! broken :current)))))
      (is (= [::reader] @release-calls))))
  (testing "a malformed acquisition missing its token still gets one cleanup attempt"
    (let [release-calls (atom [])
          source (snapshot-provider {:release-calls release-calls})
          broken
          (assoc-in
           source
           [::provider/operations :acquire-current!]
           (fn []
             {:adapter (adapter)
              :ownership :owned}))]
      (is (= :eacl/invalid-selected-snapshot
             (:type (error-data #(provider/acquire! broken :current)))))
      (is (= [nil] @release-calls)))))

#?(:clj
   (deftest acquiring-thread-constraints-fail-before-access-or-release-test
     (let [release-calls (atom [])
           source
           (snapshot-provider
            {:release-calls release-calls
             :execution-constraints
             {:virtual-threads :supported
              :snapshot-thread :acquiring-thread
              :release-thread :acquiring-thread}})
           selected (provider/acquire! source :current)
           access-error @(future (error-data #(provider/adapter selected)))
           release-error @(future (error-data #(provider/release! selected)))]
       (is (= :eacl/snapshot-thread-violation (:type access-error)))
       (is (= :snapshot-access (:phase access-error)))
       (is (= :eacl/snapshot-thread-violation (:type release-error)))
       (is (= :snapshot-release (:phase release-error)))
       (is (false? (provider/released? selected)))
       (is (empty? @release-calls))
       (is (true? (provider/release! selected)))
       (is (= [::reader] @release-calls)))))

#?(:clj
   (deftest access-is-rejected-while-release-is-in-progress-test
     (let [release-started (promise)
           finish-release (promise)
           source
           (assoc-in
            (snapshot-provider {})
            [::provider/operations :release!]
            (fn [_]
              (deliver release-started true)
              @finish-release))
           selected (provider/acquire! source :current)
           release-result (future (provider/release! selected))]
       @release-started
       (is (= :eacl/snapshot-release-in-progress
              (:type (error-data #(provider/adapter selected)))))
       (deliver finish-release true)
       (is (true? @release-result))
       (is (true? (provider/released? selected))))))

#?(:clj
   (deftest rejected-virtual-thread-fails-before-native-acquisition-test
     (let [virtual-builder
           (try
             (clojure.lang.Reflector/invokeStaticMethod
              "java.lang.Thread" "ofVirtual" (object-array 0))
             (catch Throwable _ nil))]
       (when virtual-builder
         (let [acquire-calls (atom 0)
               result (promise)
               source
               (snapshot-provider
                {:acquire-calls acquire-calls
                 :execution-constraints
                 {:virtual-threads :rejected
                  :snapshot-thread :acquiring-thread
                  :release-thread :acquiring-thread}})
               runnable
               (reify Runnable
                 (run [_]
                   (deliver
                    result
                    (error-data #(provider/acquire! source :current)))))
               thread
               (clojure.lang.Reflector/invokeInstanceMethod
                virtual-builder "start" (object-array [runnable]))]
           (.join ^Thread thread)
           (is (= :eacl/unsupported-runtime (:type @result)))
           (is (= :snapshot-acquisition (:phase @result)))
           (is (zero? @acquire-calls)))))))
