(ns eacl.consistency-source-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency]
            [eacl.spicedb.consistency :as public-consistency]
            [eacl.verified-kernel :as verified]))

(def format-options
  {:current-kid :test
   :keyring {:test (vec (range 32))}
   :token-ttl-seconds 60})

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
           error
      (ex-data error))))

(defn- adapter
  [{:keys [backend-id source-id lifecycle branch revision]
    :or {backend-id :test
         source-id "source"
         lifecycle "life"
         branch nil
         revision 1}}]
  (backend/make-adapter
   {:id backend-id
    :capabilities
    {:consistency backend/known-consistency-modes
     :snapshots #{:current :authoritative :causal :exact}
     :source #{:stable-scope :source-lifecycle :native-revision}
     :cursor #{}
     :transactions #{}
     :cache-proofs #{}
     :runtime #{#?(:clj :clj :cljs :cljs)}}
    :traversal-execution backend/strict-sequential-traversal-execution
    :operations
    (merge
     (into {}
           (map (fn [operation-key]
                  [operation-key (fn [& _] nil)]))
           backend/required-snapshot-operations)
     {:snapshot-id
      (constantly
       {:database-id source-id :basis-t revision})
      :basis-kind (constantly :ordinary)
      :native-revision
      (constantly {:revision revision :exact-locator revision})
      :order-hint (constantly revision)
      :exact-locator (constantly revision)})}))

(defn- next-candidate!
  [!candidates]
  (let [candidate (first @!candidates)]
    (swap! !candidates #(vec (rest %)))
    candidate))

(defn- test-source
  [{:keys [candidates release-calls acquire-calls modes source-id lifecycle]
    :or {release-calls (atom [])
         acquire-calls (atom [])
         modes backend/known-consistency-modes
         source-id "source"
         lifecycle "life"}}]
  (let [acquire
        (fn [operation-key & args]
          (swap! acquire-calls conj [operation-key args])
          (let [candidate (next-candidate! candidates)
                revision
                (when candidate
                  (:revision
                   (backend/invoke candidate :native-revision)))]
            {:adapter candidate
             :ownership :owned
             :release-token revision}))]
    (source/make-source
     {:id :test
      :capabilities
      {:consistency modes
       :snapshots #{:current :authoritative :causal :exact}
       :source #{:stable-scope :source-lifecycle :native-revision}
       :cursor #{}
       :transactions #{}
       :cache-proofs #{}
       :runtime #{#?(:clj :clj :cljs :cljs)}}
      :traversal-execution backend/strict-sequential-traversal-execution
      :topology {:deployment :embedded :writer :sole}
      :execution-constraints source/default-execution-constraints
      :basis-ownership :owned
      :operations
      {:source-scope
       (constantly {:source-id source-id :branch nil})
       :source-lifecycle (constantly lifecycle)
       :acquire-current!
       #(acquire :acquire-current!)
       :acquire-authoritative!
       #(acquire :acquire-authoritative! %)
       :acquire-at-least!
       #(acquire :acquire-at-least! %1 %2)
       :acquire-exact!
       #(acquire :acquire-exact! %1 %2)
       :release! #(swap! release-calls conj %)}})))

(defn- token
  [revision]
  (causal-token/issue
   format-options
   {:backend :test
    :source-id "source"
    :source-lifecycle "life"
    :branch nil
    :revision revision
    :exact-locator revision}))

(deftest source-current-and-authoritative-selection-transfer-ownership-test
  (doseq [[consistency-value expected-operation]
          [[public-consistency/minimize-latency :acquire-current!]
           [public-consistency/fully-consistent :acquire-authoritative!]]]
    (let [release-calls (atom [])
          acquire-calls (atom [])
          selected-adapter (adapter {:revision 11})
          source
          (test-source {:candidates (atom [selected-adapter])
                     :release-calls release-calls
                     :acquire-calls acquire-calls})
          selection
          (consistency/select
           source
           consistency-value
           {:format-options format-options
            :timeout-ms 1000})
          selected (:selected-snapshot selection)]
      (is (identical? selected-adapter (:adapter selection)))
      (is (identical? (source/adapter selected) (:adapter selection)))
      (is (source/selected-basis? selected))
      (is (= expected-operation (ffirst @acquire-calls)))
      (is (empty? @release-calls))
      (is (true? (source/release! selected)))
      (is (= [11] @release-calls)))))

(deftest successful-selection-logical-work-matches-formal-path-counts
  (doseq [[consistency-value acquisition-operation]
          [[public-consistency/minimize-latency :acquire-current!]
           [public-consistency/fully-consistent :acquire-authoritative!]
           [(public-consistency/at-least-as-fresh (token 11))
            :acquire-at-least!]
           [(public-consistency/at-exact-snapshot (token 11))
            :acquire-exact!]]
          issue-token? [false true]]
    (let [selected-adapter (adapter {:revision 11})
          basis-source
          (reduce
           (fn [candidate operation-key]
             (assoc-in
              candidate
              [::source/operations operation-key]
              (fn [& _]
                {:adapter selected-adapter
                 :ownership :owned
                 :release-token 11})))
           (test-source {:candidates (atom [])})
           [:acquire-current! :acquire-authoritative!
            :acquire-at-least! :acquire-exact!])
          source-operations (atom {})
          adapter-operations (atom {})
          kernel-crossings (atom {})
          selection
          (binding [source/*source-op-stats* source-operations
                    backend/*backend-op-stats* adapter-operations
                    verified/*kernel-crossing-stats* kernel-crossings]
            (consistency/select
             basis-source
             consistency-value
             {:format-options format-options
              :timeout-ms 1000
              :issue-token? issue-token?}))
          token-mode?
          (contains? #{:acquire-at-least! :acquire-exact!}
                     acquisition-operation)]
      ;; Host-native consistency authority: selection and validation run
      ;; the portable decision procedure with zero generated crossings
      ;; (kernel-boundary-efficiency delta).
      (is (= {} @kernel-crossings))
      (is (= {acquisition-operation 1
              :source-scope (if token-mode? 2 1)
              :source-lifecycle (if token-mode? 2 1)}
             @source-operations))
      (is (= (if token-mode? 1 0)
             (if (:request-token selection) 1 0)))
      (is (= {:snapshot-id 1
              :basis-kind 1
              :native-revision 1
              :order-hint 1
              :exact-locator 1}
             @adapter-operations))
      (is (= issue-token? (some? (:response-token selection))))
      (is (true? (source/release! (:selected-snapshot selection)))))))

(deftest source-at-least-closes-insufficient-candidates-test
  (let [release-calls (atom [])
        acquire-calls (atom [])
        source
        (test-source
         {:candidates
          (atom [(adapter {:revision 5})
                 (adapter {:revision 7})
                 (adapter {:revision 10})])
          :release-calls release-calls
          :acquire-calls acquire-calls})
        selection
        (consistency/select
         source
         (public-consistency/at-least-as-fresh (token 10))
         {:format-options format-options
          :timeout-ms 1000})
        selected (:selected-snapshot selection)
        remaining-values
        (mapv (comp second second) @acquire-calls)]
    (is (= 10 (get-in selection [:native-revision :revision])))
    (is (= [5 7] @release-calls))
    (is (= 3 (count @acquire-calls)))
    (is (every? pos? remaining-values))
    (is (apply >= remaining-values))
    (is (true? (source/release! selected)))
    (is (= [5 7 10] @release-calls))))

(deftest source-at-least-uses-captured-revision-without-a-late-observation-test
  (let [native-revision-calls (atom 0)
        release-calls (atom [])
        candidate
        (assoc-in
         (adapter {:revision 10})
         [::backend/operations :native-revision]
         (fn []
           (let [call (swap! native-revision-calls inc)]
             (if (= 1 call)
               {:revision 10 :exact-locator 10}
               (throw (ex-info "late native observation"
                               {:type :test/late-observation}))))))
        source (test-source {:candidates (atom [])
                          :release-calls release-calls})
        source
        (assoc-in
         source
         [::source/operations :acquire-at-least!]
         (fn [_payload _remaining-ms]
           {:adapter candidate
            :ownership :owned
            :release-token 10}))
        selection
        (consistency/select
         source
         (public-consistency/at-least-as-fresh (token 10))
         {:format-options format-options :timeout-ms 1000})
        selected (:selected-snapshot selection)]
    (is (= 10 (get-in selection [:native-revision :revision])))
    (is (= 1 @native-revision-calls))
    (is (empty? @release-calls))
    (is (true? (source/release! selected)))
    (is (= [10] @release-calls))))

#?(:clj
   (deftest source-at-least-rejects-candidate-returned-after-deadline-test
     (let [release-calls (atom [])
           candidate (adapter {:revision 10})
           source (test-source {:candidates (atom [])
                             :release-calls release-calls})
           slow-source
           (assoc-in
            source
            [::source/operations :acquire-at-least!]
            (fn [_payload _remaining-ms]
              (Thread/sleep 15)
              {:adapter candidate
               :ownership :owned
               :release-token 10}))]
       (is (= :eacl.consistency/freshness-timeout
              (:type
               (error-data
                #(consistency/select
                  slow-source
                  (public-consistency/at-least-as-fresh (token 10))
                  {:format-options format-options :timeout-ms 1})))))
       (is (= [10] @release-calls)))))

(deftest source-selection-releases-before-propagating-failure-test
  (testing "backend mismatch closes the acquired candidate"
    (let [release-calls (atom [])
          source
          (test-source
           {:candidates (atom [(adapter {:backend-id :other :revision 2})])
            :release-calls release-calls})]
      (is (= :eacl/invalid-selected-basis
             (:type
              (error-data
               #(consistency/select
                 source
                 public-consistency/minimize-latency
                 {:format-options format-options :timeout-ms 1000})))))
      (is (= [2] @release-calls))))
  (testing "cancellation after acquisition closes the candidate"
    (let [release-calls (atom [])
          source
          (test-source
           {:candidates (atom [(adapter {:revision 3})])
            :release-calls release-calls})]
      (is (= :test/cancelled
             (:type
              (error-data
               #(consistency/select
                 source
                 public-consistency/minimize-latency
                 {:format-options format-options
                  :timeout-ms 1000
                  :selection-check!
                  (fn [phase]
                    (when (= :after-snapshot-acquisition phase)
                      (throw (ex-info "cancelled" {:type :test/cancelled}))))})))))
      (is (= [3] @release-calls)))))

(deftest token-selection-fails-closed-across-a-concurrent-lifecycle-rotation-test
  (let [lifecycle (atom "life")
        release-calls (atom [])
        source (test-source {:candidates (atom [])
                          :release-calls release-calls})
        source
        (-> source
            (assoc-in
             [::source/operations :source-lifecycle]
             #(deref lifecycle))
            (assoc-in
             [::source/operations :acquire-at-least!]
             (fn [_payload _remaining-ms]
               (reset! lifecycle "rotated-life")
               {:adapter (adapter {:revision 10
                                   :lifecycle "rotated-life"})
                :ownership :owned
                :release-token 10})))]
    (is (= :eacl.consistency/incomparable-scope
           (:type
            (error-data
             #(consistency/select
               source
               (public-consistency/at-least-as-fresh (token 10))
               {:format-options format-options :timeout-ms 1000})))))
    (is (= [10] @release-calls))))

(deftest unsupported-source-mode-does-not-acquire-test
  (let [acquire-calls (atom [])
        source
        (test-source
         {:candidates (atom [])
          :acquire-calls acquire-calls
          :modes #{:minimize-latency}})]
    (is (= :eacl.consistency/exact-snapshot-unavailable
           (:type
            (error-data
             #(consistency/select
               source
               (public-consistency/at-exact-snapshot (token 1))
               {:format-options format-options :timeout-ms 1000})))))
    (is (empty? @acquire-calls))))
