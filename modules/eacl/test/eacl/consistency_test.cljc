(ns eacl.consistency-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
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
  [{:keys [backend-id source-id branch head order locator anchors modes
           selected-current selected-authoritative selected-at-least
           selected-exact counts]}]
  (let [self (atom nil)
        selected (fn [candidate]
                   (if (some? candidate)
                     @candidate
                     @self))
        base-operations
        (into {}
              (map (fn [operation]
                     [operation (fn [& _] nil)]))
              backend/required-snapshot-operations)
        operations
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
              (selected selected-exact)))})
        operations
        (if counts
          (into
           {}
           (map
            (fn [[operation f]]
              [operation
               (fn [& args]
                 (swap! counts update operation (fnil inc 0))
                 (apply f args))]))
           operations)
          operations)
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
          :operations operations})]
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

(defn- expected-kernel-decision
  [operation input]
  (case operation
    :consistency-plan
    (cond
      (not (:capability-supported? input))
      (case (:mode input)
        (:local-snapshot :minimize-latency) :unsupported-capability
        :at-exact-snapshot :exact-snapshot-unavailable
        :unsupported-head-barrier)
      (and
       (#{:at-least-as-fresh :at-exact-snapshot} (:mode input))
       (not (:managed-authority? input)))
      :unsupported-head-barrier
      :else
      (case (:mode input)
        (:local-snapshot :minimize-latency) :select-current
        (:fully-consistent :synchronized-head) :select-authoritative
        :at-least-as-fresh :authenticate-and-select-at-least
        :at-exact-snapshot :authenticate-and-select-exact))

    :consistency-validation
    (cond
      (not (:selection-present? input))
      (if (= :exact (:kind input))
        :exact-snapshot-unavailable
        :invalid-selected-adapter)
      (not (:selected-adapter? input)) :invalid-selected-adapter
      (not (:same-source-scope? input)) :incomparable-scope
      (and
       (#{:at-least :exact} (:kind input))
       (not (:anchor-satisfied? input)))
      :history-divergence
      :else :accept)))

(defrecord RecordingKernel [calls]
  verified/DecisionKernel
  (-decide [_ operation input]
    (swap! calls conj [operation input])
    (expected-kernel-decision operation input)))

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
                  :modes backend/known-consistency-modes
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

(deftest verified-consistency-decisions-route-production-selection
  (let [current (adapter {:source-id "source"
                          :branch nil
                          :head "current"
                          :order 20
                          :locator 20
                          :anchors #{"old" "current"}})
        current-ref (atom current)
        source
        (adapter {:source-id "source"
                  :branch nil
                  :head "source"
                  :order 1
                  :locator 1
                  :anchors #{"source"}
                  :modes backend/known-consistency-modes
                  :selected-current current-ref
                  :selected-authoritative current-ref
                  :selected-at-least current-ref})
        calls (atom [])
        options {:format-options format-options
                 :coherence-authority :managed
                 :timeout-ms 1000
                 :engine-selection
                 {:mode :verified-authoritative
                  :kernel (->RecordingKernel calls)}}]
    (is (identical?
         source
         (:adapter
          (consistency/captured-current-selection
           source public-consistency/local-snapshot options))))
    (is (identical?
         current
         (:adapter
          (consistency/select
           source public-consistency/fully-consistent options))))
    (is (identical?
         current
         (:adapter
          (consistency/select
           source
           (public-consistency/at-least-as-fresh
            (token "old" 10 10))
           options))))
    (is (= [:consistency-plan
            :consistency-plan
            :consistency-validation
            :consistency-plan
            :consistency-validation]
           (mapv first @calls)))))

(defn- observed-plan-outcome
  [source mode options]
  (try
    [:planned
     (consistency/selection-plan source {:mode mode} options)]
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           error
      [:rejected (:type (ex-data error))])))

(defn- expected-plan-outcome
  [input]
  (let [decision
        (expected-kernel-decision :consistency-plan input)]
    (if (contains?
         #{:select-current
           :select-authoritative
           :authenticate-and-select-at-least
           :authenticate-and-select-exact}
         decision)
      [:planned decision]
      [:rejected
       (case decision
         :unsupported-capability
         :eacl/unsupported-capability

         :exact-snapshot-unavailable
         :eacl.consistency/exact-snapshot-unavailable

         :eacl.consistency/unsupported-head-barrier)])))

(deftest production-plan-observations-refine-the-complete-formal-matrix
  (doseq [mode
          [:local-snapshot :minimize-latency
           :fully-consistent :synchronized-head
           :at-least-as-fresh :at-exact-snapshot]
          capability-supported? [false true]
          managed-authority? [false true]]
    (let [source
          (adapter
           {:source-id "source"
            :branch nil
            :head "head"
            :order 1
            :locator 1
            :anchors #{"head"}
            :modes (if capability-supported? #{mode} #{})})
          input
          {:mode mode
           :capability-supported? capability-supported?
           :managed-authority? managed-authority?}
          expected (expected-plan-outcome input)
          options
          {:coherence-authority
           (if managed-authority? :managed :unknown)}
          calls (atom [])
          verified-options
          (assoc
           options
           :engine-selection
           {:mode :verified-authoritative
            :kernel (->RecordingKernel calls)})]
      (is (= expected
             (observed-plan-outcome source mode options)))
      (is (= expected
             (observed-plan-outcome source mode verified-options)))
      (is (= [[:consistency-plan input]] @calls)))))

(deftest production-selection-observations-refine-reachable-formal-states
  (let [same-pass
        (adapter {:source-id "source"
                  :branch nil
                  :head "anchor"
                  :order 20
                  :locator 20
                  :anchors #{"anchor"}})
        same-fail
        (adapter {:source-id "source"
                  :branch nil
                  :head "different"
                  :order 20
                  :locator 20
                  :anchors #{}})
        different
        (adapter {:source-id "different"
                  :branch nil
                  :head "anchor"
                  :order 20
                  :locator 20
                  :anchors #{"anchor"}})
        request-token (token "anchor" 20 20)
        consistency-value
        {:current public-consistency/local-snapshot
         :authoritative public-consistency/fully-consistent
         :at-least
         (public-consistency/at-least-as-fresh request-token)
         :exact
         (public-consistency/at-exact-snapshot request-token)}
        selection-option
        {:current :selected-current
         :authoritative :selected-authoritative
         :at-least :selected-at-least
         :exact :selected-exact}
        causal-kind? #{:at-least :exact}
        candidate-value
        {:absent nil
         :malformed {}
         :same-pass same-pass
         :same-fail same-fail
         :different different}]
    (doseq [kind [:current :authoritative :at-least :exact]
            candidate
            [:absent :malformed :identical
             :same-pass :same-fail :different]]
      (let [selection-ref
            (when-not (= :identical candidate)
              (atom (get candidate-value candidate)))
            source-options
            (cond->
             {:source-id "source"
              :branch nil
              :head "anchor"
              :order 20
              :locator 20
              :anchors #{"anchor"}
              :modes backend/known-consistency-modes}
              selection-ref
              (assoc (get selection-option kind) selection-ref))
            source (adapter source-options)
            calls (atom [])
            options
            {:format-options format-options
             :coherence-authority :managed
             :issue-token? false
             :timeout-ms 1000
             :engine-selection
             {:mode :verified-authoritative
              :kernel (->RecordingKernel calls)}}
            selection-present? (not= :absent candidate)
            selected-adapter?
            (contains?
             #{:identical :same-pass :same-fail :different}
             candidate)
            same-source-scope?
            (and selected-adapter? (not= :different candidate))
            anchor-satisfied?
            (if (causal-kind? kind)
              (and same-source-scope?
                   (not= :same-fail candidate))
              true)
            expected
            {:kind kind
             :selection-present? selection-present?
             :selected-adapter? selected-adapter?
             :same-source-scope? same-source-scope?
             :anchor-satisfied? anchor-satisfied?}]
        (try
          (consistency/select
           source
           (get consistency-value kind)
           options)
          (catch #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo)
                 _
            nil))
        (let [observed
              (into
               []
               (comp
                (filter
                 (fn [[operation]]
                   (= :consistency-validation operation)))
                (map second))
               @calls)]
          (is (= [expected] observed)
              (str "fact extraction mismatch for "
                   (name kind) "/" (name candidate))))))))

(deftest consistency-selection-logical-work-matches-formal-path-bounds
  (let [backend-calls (atom {})
        current
        (adapter {:source-id "source"
                  :branch nil
                  :head "anchor"
                  :order 20
                  :locator 20
                  :anchors #{"anchor"}
                  :counts backend-calls})
        current-ref (atom current)
        source
        (adapter {:source-id "source"
                  :branch nil
                  :head "source"
                  :order 1
                  :locator 1
                  :anchors #{"source"}
                  :modes backend/known-consistency-modes
                  :selected-current current-ref
                  :selected-authoritative current-ref
                  :selected-at-least current-ref
                  :selected-exact current-ref
                  :counts backend-calls})
        kernel-calls (atom [])
        options {:format-options format-options
                 :coherence-authority :managed
                 :timeout-ms 1000
                 :engine-selection
                 {:mode :verified-authoritative
                  :kernel (->RecordingKernel kernel-calls)}}
        request-token (token "anchor" 20 20)
        run!
        (fn [f expected-backend-work]
          (reset! backend-calls {})
          (reset! kernel-calls [])
          (f)
          (is (= (:expected-kernel-operations expected-backend-work)
                 (mapv first @kernel-calls)))
          (let [expected-backend-work'
                (dissoc expected-backend-work
                        :expected-kernel-operations)]
            (is (= expected-backend-work' @backend-calls))))]
    (doseq [issue-response-token? [false true]]
      (let [run-options
            (assoc options :issue-token? issue-response-token?)
            response-scope (if issue-response-token? 1 0)]
        (testing
         (str "captured current path, response token "
              issue-response-token?)
          (run!
           #(consistency/captured-current-selection
             source public-consistency/local-snapshot run-options)
           {:expected-kernel-operations [:consistency-plan]}))
        (testing
         (str "selected current path, response token "
              issue-response-token?)
          (run!
           #(consistency/select
             source public-consistency/local-snapshot run-options)
           {:select-current 1
            :expected-kernel-operations
            [:consistency-plan :consistency-validation]
            :source-scope (+ 2 response-scope)
            :graph-head 1
            :order-hint 1
            :exact-locator 1}))
        (testing
         (str "authoritative path, response token "
              issue-response-token?)
          (run!
           #(consistency/select
             source public-consistency/fully-consistent run-options)
           {:select-authoritative 1
            :expected-kernel-operations
            [:consistency-plan :consistency-validation]
            :source-scope (+ 2 response-scope)
            :graph-head 1
            :order-hint 1
            :exact-locator 1}))
        (testing
         (str "at-least path, response token "
              issue-response-token?)
          (run!
           #(consistency/select
             source
             (public-consistency/at-least-as-fresh request-token)
             run-options)
           {:source-scope (+ 3 response-scope)
            :expected-kernel-operations
            [:consistency-plan :consistency-validation]
            :select-at-least 1
            :contains-anchor? 1
            :graph-head 1
            :order-hint 1
            :exact-locator 1}))
        (testing
         (str "exact path, response token " issue-response-token?)
          (run!
           #(consistency/select
             source
             (public-consistency/at-exact-snapshot request-token)
             run-options)
           {:source-scope (+ 3 response-scope)
            :expected-kernel-operations
            [:consistency-plan :consistency-validation]
            :select-exact 1
            :graph-head 2
            :order-hint 2
            :exact-locator 2}))))))

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
    (testing "a malformed present exact selection is not reported as absence"
      (let [invalid-source
            (assoc-in
             source
             [::backend/operations :select-exact]
             (fn [& _] {:not :an-adapter}))]
        (is (= :eacl/invalid-backend-adapter
               (:type
                (error-data
                 #(consistency/select
                   invalid-source
                   (public-consistency/at-exact-snapshot
                    (token "current" 20 20))
                   options)))))))
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
