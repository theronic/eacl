(ns eacl.formal.executed-mutation-controls
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.authorization.batch :as batch]
            [eacl.cache :as cache]
            [eacl.engine.portable-decisions :as portable]
            [eacl.verified-kernel :as verified]))

(defn- production-decision
  [operation input]
  (verified/decide portable/portable-decision-kernel operation input))

(defn- portable-mutation-killed?
  [original operation input expected mutant]
  (let [gate #(= expected (production-decision operation input))]
    (and
     (gate)
     (try
       (false?
        (with-redefs [portable/decide
                      (fn [candidate-operation candidate-input]
                        (if (= operation candidate-operation)
                          (if (fn? mutant)
                            (mutant candidate-input)
                            mutant)
                          (original candidate-operation candidate-input)))]
          (gate)))
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
              _
         true)))))

(defn wrong-frontier-killed?
  []
  (let [input {:length 4
               :request {:first 2 :last :absent
                         :after 1 :before :absent}
               :default-size 10
               :maximum-size 100}
        expected {:status :valid :direction :asc :size 2
                  :start 2 :end 4
                  :has-next? false :has-previous? true}]
    (portable-mutation-killed?
     portable/decide
     :relationship-page
     input
     expected
     (assoc expected :start 1))))

(defn cursor-scope-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :cursor-continuation
   {:authenticated? true
    :scope-matches? false
    :expired? false
    :source "datascript/source-a"
    :cursor-source "datascript/source-a"
    :current-proof "revision-7"
    :cursor-proof "revision-7"
    :cursor-graph 1
    :exact nil}
   :scope-mismatch
   :current))

(defn cache-fail-open-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :subproblem-cache-decision
   {:decision :lookup :candidate :failed}
   :start-independent-computation
   :use-completed-value))

(defn current-cache-missing-entry-hit-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :current-cache-decision
   {:stage :exact-entry :available? false}
   :probe-managed-entry
   :use-exact-entry))

(defn mismatched-indexed-request-scope-response-killed?
  []
  (let [input
        {:command
         {:request-scope 81
          :request-id 0
          :projection {:kind :subject->resources
                       :subject-type "user"
                       :subject-eid 1
                       :relation-eid 2
                       :resource-type "document"
                       :bound-eid nil}
          :chunk-size 2}
         :response {:request-scope 82
                    :request-id 0
                    :values [10]
                    :terminal? true
                    :fetched-values 1}}
        expected {:status :rejected :reason :mismatched-request-scope}
        mutant {:status :accepted
                :values [10]
                :terminal? true
                :fetched-values 1}]
    (portable-mutation-killed?
     portable/decide :indexed-scan-response input expected mutant)))

(defn ordered-merge-wrong-comparator-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :ordered-merge-step
   {:direction :asc :left-head 1 :right-head 2}
   :take-left
   :take-right))

(defn acyclic-merge-emits-overlap-twice-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :ordered-merge-chunk
   {:direction :asc :left [1 3] :right [1 2]}
   {:values [1 2] :left-consumed 1 :right-consumed 2}
   {:values [1 1 2] :left-consumed 1 :right-consumed 2}))

(defn adapter-negative-eid-admitted-killed?
  []
  (let [input
        {:command
         {:request-scope 1
          :request-id 0
          :projection {:kind :subject->resources
                       :subject-type "user"
                       :subject-eid 1
                       :relation-eid 2
                       :resource-type "document"
                       :bound-eid nil}
          :chunk-size 2}
         :response {:request-scope 1
                    :request-id 0
                    :values [-1]
                    :terminal? true
                    :fetched-values 1}}
        expected {:status :rejected :reason :invalid-eid}
        mutant {:status :accepted
                :values [1]
                :terminal? true
                :fetched-values 1}]
    (portable-mutation-killed?
     portable/decide :indexed-scan-response input expected mutant)))

(defn over-budget-publication-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :subproblem-cache-decision
   {:decision :publication
    :ticket-current? true
    :complete? true
    :valid? true
    :weight 11
    :budget 10}
   :drop-publication
   :retain-publication))

(defn enumeration-route-forces-recursive-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :enumeration-route
   {:schema-identity "schema"
    :certificate-schema-identity "schema"
    :root-defined? true
    :recursive? true
    :recursive-data-active? false}
   {:status :accepted :route :acyclic}
   {:status :accepted :route :recursive}))

(defn acyclic-work-allows-recursive-budget-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :acyclic-work
   {:requested-window 20
    :merge-advances 20
    :emitted-results 20
    :recursive-work 1}
   :rejected
   :accepted))

(defn consistency-malformed-exact-treated-absent-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :consistency-validation
   {:kind :exact
    :selection-present? true
    :selected-adapter? false
    :same-source-scope? true
    :revision-satisfied? true}
   :invalid-selected-adapter
   :exact-snapshot-unavailable))

(defn consistency-at-least-revision-floor-ignored-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :consistency-validation
   {:kind :at-least
    :selection-present? true
    :selected-adapter? true
    :same-source-scope? true
    :revision-satisfied? false}
   :history-divergence
   :accept))

(defn consistency-unsupported-exact-becomes-generic-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :consistency-plan
   {:mode :at-exact-snapshot :capability-supported? false}
   :exact-snapshot-unavailable
   :unsupported-head-barrier))

(defn- operation-map
  []
  (into {}
        (map (fn [operation]
               [operation (fn [& _] nil)]))
        backend/required-snapshot-operations))

(defn- basis-adapter
  []
  (backend/make-adapter
   {:id :mutation-control
    :capabilities backend/empty-capabilities
    :operations (operation-map)}))

(defn exact-basis-key-omits-lifecycle-killed?
  []
  (let [adapter (basis-adapter)
        base {:backend :mutation-control
              :source-id "source"
              :branch nil
              :basis-kind :ordinary
              :revision 7
              :exact-locator 7
              :backend-snapshot-id 7}
        separated?
        (fn []
          (not=
           (cache/exact-basis-key
            adapter (assoc base :source-lifecycle "before"))
           (cache/exact-basis-key
            adapter (assoc base :source-lifecycle "after"))))
        original cache/exact-basis-key]
    (and
     (separated?)
     (false?
      (with-redefs [cache/exact-basis-key
                    (fn [candidate-adapter identity]
                      (update
                       (original candidate-adapter identity)
                       :basis-identity
                       dissoc
                       :source-lifecycle))]
        (separated?))))))

(defn aggregate-counter-reset-killed?
  []
  (let [args [{:advanced-datoms 0 :queued-work 0 :fetched-values 0}
              {:advanced-datoms 6 :queued-work 4 :fetched-values 2}
              {:candidates-examined 0 :probes 0 :publications 0}
              {:candidates-examined 3 :probes 2 :publications 1}
              2]
        gate #(= 6 (:commands (apply batch/aggregate-counters args)))
        original batch/aggregate-counters]
    (and
     (gate)
     (false?
      (with-redefs [batch/aggregate-counters
                    (fn [& candidate-args]
                      (assoc (apply original candidate-args) :commands 3))]
        (gate))))))

(defn batch-cross-demand-contamination-killed?
  []
  (let [alice {:subject {:type :user :id "alice"}
               :permission :view
               :resource {:type :document :id "one"}}
        bob {:subject {:type :user :id "bob"}
             :permission :view
             :resource {:type :document :id "one"}}
        gate #(not= (batch/demand-key alice) (batch/demand-key bob))
        original batch/demand-key]
    (and
     (gate)
     (false?
      (with-redefs [batch/demand-key
                    (fn [demand]
                      (assoc (original demand)
                             :subject (:subject alice)))]
        (gate))))))

(defn aggregate-deadline-renewal-killed?
  []
  (let [contract {:operation :check-permissions
                  :deadline-nanos 110
                  :cancellation-token {:cancelled? false}}
        gate #(= 110 (:deadline-nanos (batch/scalar-contract contract)))
        original batch/scalar-contract]
    (and
     (gate)
     (false?
      (with-redefs [batch/scalar-contract
                    (fn [candidate]
                      (dissoc (original candidate) :deadline-nanos))]
        (gate))))))

(def controls
  {:wrong-frontier wrong-frontier-killed?
   :cursor-scope cursor-scope-killed?
   :cache-fail-open cache-fail-open-killed?
   :current-cache-missing-entry-hit current-cache-missing-entry-hit-killed?
   :mismatched-indexed-request-scope-response
   mismatched-indexed-request-scope-response-killed?
   :ordered-merge-wrong-comparator ordered-merge-wrong-comparator-killed?
   :acyclic-merge-emits-overlap-twice
   acyclic-merge-emits-overlap-twice-killed?
   :adapter-negative-eid-admitted adapter-negative-eid-admitted-killed?
   :over-budget-publication over-budget-publication-killed?
   :enumeration-route-forces-recursive enumeration-route-forces-recursive-killed?
   :acyclic-work-allows-recursive-budget acyclic-work-allows-recursive-budget-killed?
   :consistency-malformed-exact-treated-absent
   consistency-malformed-exact-treated-absent-killed?
   :consistency-at-least-revision-floor-ignored
   consistency-at-least-revision-floor-ignored-killed?
   :consistency-unsupported-exact-becomes-generic
   consistency-unsupported-exact-becomes-generic-killed?
   :exact-basis-key-omits-lifecycle exact-basis-key-omits-lifecycle-killed?
   :aggregate-counter-reset aggregate-counter-reset-killed?
   :batch-cross-demand-contamination batch-cross-demand-contamination-killed?
   :aggregate-deadline-renewal aggregate-deadline-renewal-killed?})

(deftest every-portable-production-mutant-is-killed-test
  (doseq [[id detector] controls]
    (testing (name id)
      (is (true? (detector)) (str "surviving production mutant: " id)))))
