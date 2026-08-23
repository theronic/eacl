(ns eacl.backend.source-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]))

(defn- adapter
  ([] (adapter :test backend/strict-sequential-traversal-execution))
  ([backend-id traversal-execution]
   (adapter backend-id traversal-execution {}))
  ([backend-id traversal-execution
    {:keys [source-id branch lifecycle revision exact-locator
            snapshot-database-id basis-kind]
     :or {source-id ::source
          branch nil
          lifecycle ::lifecycle
          revision 1
          exact-locator 1
          basis-kind :ordinary}}]
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
       :basis-kind (constantly basis-kind)
       :native-revision
       (constantly {:revision revision :exact-locator exact-locator})
       :order-hint (constantly revision)
       :exact-locator (constantly exact-locator)})})))

(defn- test-source
  [{:keys [candidate ownership-policy release-calls
           traversal-execution backend-id execution-constraints
           acquire-calls source-id branch lifecycle]
    :or {candidate (adapter)
         ownership-policy :owned
         release-calls (atom [])
         traversal-execution backend/strict-sequential-traversal-execution
         backend-id :test
         source-id ::source
         branch nil
         lifecycle ::lifecycle}}]
  (let [acquire
        (fn [& _]
          (when acquire-calls
            (swap! acquire-calls inc))
          {:adapter candidate
           :ownership (if (= :borrowed ownership-policy)
                        :borrowed
                        :owned)
           :release-token ::reader})]
    (source/make-source
     {:id backend-id
      :capabilities backend/empty-capabilities
      :traversal-execution traversal-execution
      :topology {:deployment :embedded}
      :execution-constraints
      (or execution-constraints source/default-execution-constraints)
      :basis-ownership ownership-policy
      :operations
      {:source-scope
       (constantly {:source-id source-id :branch branch})
       :source-lifecycle (constantly lifecycle)
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

(deftest source-contract-is-closed-test
  (is (= source/required-source-operations
         (set (keys source/source-obligations))))
  (testing "unknown source fields fail validation"
    (is (= [:surprise]
           (:unknown-keys
            (error-data
             #(source/make-source
               {:id :test
                :capabilities backend/empty-capabilities
                :traversal-execution
                backend/strict-sequential-traversal-execution
                :topology {}
                :execution-constraints
                source/default-execution-constraints
                :basis-ownership :borrowed
                :operations {}
                :surprise true}))))))
  (testing "every lifecycle operation is mandatory"
    (is (= source/required-source-operations
           (set
            (:missing-operations
             (error-data
              #(source/make-source
                {:id :test
                 :capabilities backend/empty-capabilities
                 :traversal-execution
                 backend/strict-sequential-traversal-execution
                 :topology {}
                 :execution-constraints
                 source/default-execution-constraints
                 :basis-ownership :borrowed
                 :operations {}})))))))
  (testing "execution constraints are closed enums"
    (is (= :eacl/invalid-source
           (:type
            (error-data
             #(source/make-source
               {:id :test
                :capabilities backend/empty-capabilities
                :traversal-execution
                backend/strict-sequential-traversal-execution
                :topology {}
                :execution-constraints
                {:virtual-threads :sometimes
                 :snapshot-thread :any
                 :release-thread :any}
                :basis-ownership :borrowed
                :operations
                (zipmap source/required-source-operations
                        (repeat (fn [& _] nil)))})))))))

(deftest source-static-profile-does-not-acquire-test
  (let [calls (atom {})
        source (test-source {})]
    (binding [source/*source-op-stats* calls]
      (is (= :test (:backend-id (source/static-profile source))))
      (is (= {:source-id ::source :branch nil}
             (source/source-scope source)))
      (is (= ::lifecycle (source/source-lifecycle source))))
    (is (= {:source-scope 1 :source-lifecycle 1} @calls))
    (is (not-any? #(contains? @calls %)
                  (vals source/acquisition-operations)))))

(deftest selected-snapshot-owns-one-idempotent-release-test
  (let [release-calls (atom [])
        source (test-source {:release-calls release-calls})
        selected (source/acquire! source :current)]
    (is (source/source? source))
    (is (source/selected-basis? selected))
    (is (= :owned (source/ownership selected)))
    (is (= :test (backend/backend-id (source/adapter selected))))
    (is (= {:backend :test
            :source-id ::source
            :branch nil
            :source-lifecycle ::lifecycle
            :basis-kind :ordinary
            :revision 1
            :exact-locator 1
            :backend-snapshot-id {:database-id :test :basis-t 1}}
           (source/semantic-identity selected)))
    (is (false? (source/released? selected)))
    (is (true? (source/release! selected)))
    (is (true? (source/released? selected)))
    (is (false? (source/release! selected)))
    (is (= [::reader] @release-calls))
    (is (= :eacl/snapshot-released
           (:type (error-data #(source/adapter selected)))))))

(deftest failed-release-is-classified-and-remains-retryable-test
  (let [attempts (atom 0)
        source (test-source {})
        retryable
        (assoc-in
         source
         [::source/operations :release!]
         (fn [_]
           (when (= 1 (swap! attempts inc))
             (throw (ex-info "injected release failure"
                             {:type :injected/release})))))
        selected (source/acquire! retryable :current)]
    (is (= :eacl/snapshot-release-failed
           (:type (error-data #(source/release! selected)))))
    (is (false? (source/released? selected)))
    (is (= :test (backend/backend-id (source/adapter selected))))
    (is (true? (source/release! selected)))
    (is (true? (source/released? selected)))
    (is (= 2 @attempts))
    (is (false? (source/release! selected)))))

(deftest semantic-identity-covers-every-independent-state-dimension-test
  (let [identity
        (fn [overrides]
          (let [selected-source
                (test-source
                 (merge
                  (select-keys overrides [:source-id :branch :lifecycle])
                  {:candidate
                  (adapter
                   :test backend/strict-sequential-traversal-execution
                   overrides)}))
                selected (source/acquire! selected-source :current)]
            (try
              (source/semantic-identity selected)
              (finally
                (source/release! selected)))))
        baseline (identity {})]
    (testing "separately acquired readers of the same semantic state compare equal"
      (is (= baseline (identity {}))))
    (doseq [[field overrides]
            [[:backend {:snapshot-database-id :other-database}]
             [:source {:source-id ::other-source}]
             [:branch {:branch "other-branch"}]
             [:lifecycle {:lifecycle ::other-lifecycle}]
             [:basis-kind {:basis-kind :as-of}]
             [:revision {:revision 2 :exact-locator 2}]
             [:exact-locator {:exact-locator :other-locator}]]]
      (testing (name field)
        (is (not= baseline (identity overrides)))))))

(deftest acquisition-validates-source-boundaries-test
  (testing "backend identity cannot change during acquisition"
    (let [release-calls (atom [])
          source
          (test-source
           {:candidate (adapter :other
                                backend/strict-sequential-traversal-execution)
            :release-calls release-calls})]
      (is (= :eacl/invalid-selected-basis
             (:type (error-data #(source/acquire! source :current)))))
      (is (= [::reader] @release-calls))))
  (testing "the adapter must retain the source-static traversal profile"
    (let [source
          (test-source
           {:candidate (adapter :test backend/default-traversal-execution)})]
      (is (= :eacl/invalid-selected-basis
             (:type (error-data #(source/acquire! source :current)))))))
  (testing "ownership cannot contradict the static policy"
    (let [source
          (test-source
           {:candidate (adapter)
            :ownership-policy :owned})
          broken
          (assoc-in
           source
           [::source/operations :acquire-current!]
           (fn []
             {:adapter (adapter)
              :ownership :borrowed
              :release-token nil}))]
      (is (= :eacl/invalid-selected-basis
             (:type (error-data #(source/acquire! broken :current)))))))
  (testing "acquisition result shape is closed"
    (let [release-calls (atom [])
          source (test-source {:release-calls release-calls})
          broken
          (assoc-in
           source
           [::source/operations :acquire-current!]
           (fn []
             {:adapter (adapter)
              :ownership :owned
              :release-token ::reader
              :native-handle :escaped}))]
      (is (= #{:adapter :ownership :release-token :native-handle}
             (:actual-keys
              (error-data #(source/acquire! broken :current)))))
      (is (= [::reader] @release-calls))))
  (testing "a malformed acquisition missing its token still gets one cleanup attempt"
    (let [release-calls (atom [])
          source (test-source {:release-calls release-calls})
          broken
          (assoc-in
           source
           [::source/operations :acquire-current!]
           (fn []
             {:adapter (adapter)
              :ownership :owned}))]
      (is (= :eacl/invalid-selected-basis
             (:type (error-data #(source/acquire! broken :current)))))
      (is (= [nil] @release-calls)))))

#?(:clj
   (deftest acquiring-thread-constraints-fail-before-access-or-release-test
     (let [release-calls (atom [])
           source
           (test-source
            {:release-calls release-calls
             :execution-constraints
             {:virtual-threads :supported
              :snapshot-thread :acquiring-thread
              :release-thread :acquiring-thread}})
           selected (source/acquire! source :current)
           access-error @(future (error-data #(source/adapter selected)))
           release-error @(future (error-data #(source/release! selected)))]
       (is (= :eacl/snapshot-thread-violation (:type access-error)))
       (is (= :snapshot-access (:phase access-error)))
       (is (= :eacl/snapshot-thread-violation (:type release-error)))
       (is (= :snapshot-release (:phase release-error)))
       (is (false? (source/released? selected)))
       (is (empty? @release-calls))
       (is (true? (source/release! selected)))
       (is (= [::reader] @release-calls)))))

#?(:clj
   (deftest access-is-rejected-while-release-is-in-progress-test
     (let [release-started (promise)
           finish-release (promise)
           source
           (assoc-in
            (test-source {})
            [::source/operations :release!]
            (fn [_]
              (deliver release-started true)
              @finish-release))
           selected (source/acquire! source :current)
           release-result (future (source/release! selected))]
       @release-started
       (is (= :eacl/snapshot-release-in-progress
              (:type (error-data #(source/adapter selected)))))
       (deliver finish-release true)
       (is (true? @release-result))
       (is (true? (source/released? selected))))))

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
               (test-source
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
                    (error-data #(source/acquire! source :current)))))
               thread
               (clojure.lang.Reflector/invokeInstanceMethod
                virtual-builder "start" (object-array [runnable]))]
           (.join ^Thread thread)
           (is (= :eacl/unsupported-runtime (:type @result)))
           (is (= :snapshot-acquisition (:phase @result)))
           (is (zero? @acquire-calls)))))))
