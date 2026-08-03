(ns eacl.consistency-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency]
            [eacl.spicedb.consistency :as public-consistency]))

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
  [{:keys [backend-id source-id branch head order locator anchors modes
           selected-current selected-authoritative selected-at-least
           selected-exact]}]
  (let [self (atom nil)
        selected (fn [candidate]
                   (or (some-> candidate deref) @self))
        base-operations
        (into {}
              (map (fn [operation]
                     [operation (fn [& _] nil)]))
              backend/required-snapshot-operations)
        result
        (backend/make-adapter
         {:id (or backend-id :test)
          :capabilities
          {:consistency (or modes
                            #{:fully-consistent
                              :minimize-latency
                              :at-least-as-fresh
                              :at-exact-snapshot})
           :snapshots #{:current :authoritative :causal :exact}
           :source #{:stable-scope :graph-head
                     :anchor-membership :order-hint :exact-locator}
           :cursor #{}
           :transactions #{}
           :cache-proofs #{}
           :runtime #{#?(:clj :clj :cljs :cljs)}}
          :operations
          (merge
           base-operations
           {:snapshot-id (fn [] [source-id branch order])
            :source-scope
            (fn [] {:source-id source-id :branch branch})
            :graph-head
            (fn [] {:graph-anchor head
                    :order-hint order
                    :exact-locator locator})
            :contains-anchor? (fn [anchor]
                               (contains? anchors anchor))
            :order-hint (fn [] order)
            :exact-locator (fn [] locator)
            :select-current
            (fn [] (selected selected-current))
            :select-authoritative
            (fn [_] (selected selected-authoritative))
            :select-at-least
            (fn [_ _] (selected selected-at-least))
            :select-exact
            (fn [_ _]
              (when-not (= ::unavailable selected-exact)
                (selected selected-exact)))})})]
    (reset! self result)
    result))

(defn- token
  ([anchor order locator]
   (token "source" nil anchor order locator))
  ([source-id branch anchor order locator]
   (causal-token/issue
    format-options
    {:backend :test
     :source-id source-id
     :branch branch
     :graph-anchor anchor
     :order-hint order
     :exact-locator locator})))

(deftest shared-selection-postconditions-test
  (let [current (adapter {:source-id "source"
                          :branch nil
                          :head "current"
                          :order 20
                          :locator 20
                          :anchors #{"old" "current"}})
        exact (adapter {:source-id "source"
                        :branch nil
                        :head "exact"
                        :order 10
                        :locator 10
                        :anchors #{"exact"}})
        current-ref (atom current)
        exact-ref (atom exact)
        source
        (adapter {:source-id "source"
                  :branch nil
                  :head "source"
                  :order 1
                  :locator 1
                  :anchors #{"source"}
                  :selected-current current-ref
                  :selected-authoritative current-ref
                  :selected-at-least current-ref
                  :selected-exact exact-ref})
        options {:format-options format-options
                 :coherence-authority :managed
                 :issue-token? true
                 :timeout-ms 1000}]
    (testing "fully consistent uses the authoritative barrier"
      (let [selection
            (consistency/select
             source public-consistency/fully-consistent options)]
        (is (identical? current (:adapter selection)))
        (is (= "current" (get-in selection
                                [:graph-head :graph-anchor])))))
    (testing "minimize latency selects the local complete snapshot"
      (is (identical?
           current
           (:adapter
            (consistency/select
             source public-consistency/minimize-latency options)))))
    (testing "at least selection verifies anchor membership after selection"
      (let [request (token "old" 10 10)
            selection
            (consistency/select
             source
             (public-consistency/at-least-as-fresh request)
             options)]
        (is (identical? current (:adapter selection)))
        (is (= "old"
               (get-in selection
                       [:request-token :graph-anchor])))))
    (testing "exact selection verifies the resolved graph identity"
      (let [request (token "exact" 10 10)]
        (is (identical?
             exact
             (:adapter
              (consistency/select
               source
               (public-consistency/at-exact-snapshot request)
               options))))))
    (testing "the response token is minted from the selected immutable head"
      (let [response
            (:response-token
             (consistency/select
              source public-consistency/fully-consistent options))
            payload
            (causal-token/token-data
             format-options
             {:backend :test
              :source-id "source"
              :branch nil}
             response)]
        (is (= "current" (:graph-anchor payload)))
        (is (= 20 (:order-hint payload)))))))

(deftest selection-fails-closed-test
  (let [selected (adapter {:source-id "source"
                           :branch nil
                           :head "current"
                           :order 20
                           :locator 20
                           :anchors #{"current"}})
        selected-ref (atom selected)
        source
        (adapter {:source-id "source"
                  :branch nil
                  :head "source"
                  :order 1
                  :locator 1
                  :anchors #{"source"}
                  :selected-current selected-ref
                  :selected-authoritative selected-ref
                  :selected-at-least selected-ref
                  :selected-exact ::unavailable})
        options {:format-options format-options
                 :coherence-authority :managed
                 :timeout-ms 1000}]
    (testing "an absent causal anchor is history divergence, not freshness"
      (is (= :eacl.consistency/history-divergence
             (:type
              (error-data
               #(consistency/select
                 source
                 (public-consistency/at-least-as-fresh
                  (token "missing" 10 10))
                 options))))))
    (testing "source and branch mismatch is incomparable"
      (is (= :eacl.consistency/incomparable-scope
             (:type
              (error-data
               #(consistency/select
                 source
                 (public-consistency/at-least-as-fresh
                  (token "other" nil "source" 10 10))
                 options))))))
    (testing "expired tokens have their own error"
      (let [expired
            (causal-token/issue
             format-options
             {:backend :test
              :source-id "source"
              :branch nil
              :graph-anchor "current"
              :order-hint 20
              :exact-locator 20
              :issued-at 1
              :expires-at 2})]
        (is (= :eacl.consistency/token-expired
               (:type
                (error-data
                 #(consistency/select
                   source
                   (public-consistency/at-least-as-fresh expired)
                   options)))))))
    (testing "exact reconstruction absence is typed"
      (is (= :eacl.consistency/exact-snapshot-unavailable
             (:type
              (error-data
               #(consistency/select
                 source
                 (public-consistency/at-exact-snapshot
                  (token "current" 20 20))
                 options))))))
    (testing "unknown writer authority fails before causal selection"
      (is (= :eacl.consistency/unsupported-head-barrier
             (:type
              (error-data
               #(consistency/select
                 source
                 (public-consistency/at-least-as-fresh
                  (token "current" 20 20))
                 (assoc options
                        :coherence-authority :unknown)))))))))

(deftest capability-matrix-and-legacy-restriction-test
  (doseq [[mode descriptor]
          [[:fully-consistent public-consistency/fully-consistent]
           [:synchronized-head public-consistency/synchronized-head]
           [:local-snapshot public-consistency/local-snapshot]
           [:minimize-latency public-consistency/minimize-latency]
           [:at-least-as-fresh
            (public-consistency/at-least-as-fresh
             (token "head" 1 1))]
           [:at-exact-snapshot
            (public-consistency/at-exact-snapshot
             (token "head" 1 1))]]]
    (let [only-mode
          (adapter {:source-id "source"
                    :branch nil
                    :head "head"
                    :order 1
                    :locator 1
                    :anchors #{"head"}
                    :modes #{mode}})
          options {:format-options format-options
                   :coherence-authority :managed}]
      (is (backend/adapter?
           (:adapter
            (consistency/select only-mode descriptor options))))
      (doseq [other-mode
              (disj backend/known-consistency-modes mode)]
        (let [other-descriptor
              (case other-mode
                :fully-consistent public-consistency/fully-consistent
                :synchronized-head public-consistency/synchronized-head
                :local-snapshot public-consistency/local-snapshot
                :minimize-latency public-consistency/minimize-latency
                :at-least-as-fresh
                (public-consistency/at-least-as-fresh
                 (token "head" 1 1))
                :at-exact-snapshot
                (public-consistency/at-exact-snapshot
                 (token "head" 1 1)))]
          (is (some? (error-data
                      #(consistency/select
                        only-mode other-descriptor options))))))))
  (let [legacy
        {:cache-stamp (constantly :stamp)
         :relation-defs (fn [& _])
         :permission-defs (fn [& _])
         :subject->resources (fn [& _])
         :resource->subjects (fn [& _])
         :direct-match? (fn [& _])}]
    (is (identical?
         legacy
         (backend/require-legacy-evaluation!
          legacy
          {:explicit-snapshot? true
           :cache? false
           :consistency-mode :snapshot-only})))
    (is (= :eacl/unsupported-capability
           (:type
            (error-data
             #(backend/require-legacy-evaluation!
               legacy
               {:explicit-snapshot? true
                :cache? true
                :consistency-mode :snapshot-only})))))))

(deftest selection-error-taxonomy-test
  (let [selected (adapter {:source-id "source"
                           :head "head"
                           :order 1
                           :locator 1
                           :anchors #{"head"}})
        selected-ref (atom selected)
        source
        (adapter {:source-id "source"
                  :head "head"
                  :order 1
                  :locator 1
                  :anchors #{"head"}
                  :selected-current selected-ref
                  :selected-authoritative selected-ref
                  :selected-at-least selected-ref
                  :selected-exact selected-ref})
        request (token "head" 1 1)
        options {:format-options format-options
                 :coherence-authority :managed}]
    (testing "backend freshness deadlines remain distinguishable"
      (let [timeout-source
            (assoc-in
             source
             [::backend/operations :select-at-least]
             (fn [& _]
               (consistency/fail!
                :freshness-unavailable
                "deadline"
                {:timeout-ms 1})))]
        (is (= :eacl.consistency/freshness-unavailable
               (:type
                (error-data
                 #(consistency/select
                   timeout-source
                   (public-consistency/at-least-as-fresh request)
                   options)))))))
    (testing "cursor/freshness incompatibility is typed"
      (is (= :eacl.consistency/cursor-consistency-conflict
             (:type
              (error-data
               #(consistency/cursor-conflict!
                 {:cursor "old" :requested "new"}))))))))
