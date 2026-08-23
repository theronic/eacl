(ns eacl.formal.executed-mutation-controls
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.authorization.batch :as batch]
            [eacl.cache :as cache]
            [eacl.engine.portable-decisions :as portable]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as stable-reducer]
            [eacl.engine.v8 :as engine]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.context :as request-context]
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

(def arrow-authorization-input
  {:objects [{:type "user" :id "u1"}
             {:type "folder" :id "f0"}
             {:type "folder" :id "f1"}]
   :schema
   {:relations [{:resource-type "folder"
                 :relation "reader"
                 :subject-type "user"}
                {:resource-type "folder"
                 :relation "parent"
                 :subject-type "folder"}]
    :permissions [{:resource-type "folder" :permission "read"}]
    :definitions [{:kind :direct-relation
                   :resource-type "folder"
                   :permission "read"
                   :relation "reader"
                   :subject-type "user"}
                  {:kind :arrow-permission
                   :resource-type "folder"
                   :permission "read"
                   :via-relation "parent"
                   :target-permission "read"}]}
   :relationships
   [{:resource {:type "folder" :id "f0"}
     :relation "reader"
     :subject {:type "user" :id "u1"}}
    {:resource {:type "folder" :id "f1"}
     :relation "parent"
     :subject {:type "folder" :id "f0"}}]
   :request {:operation :can?
             :subject {:type "user" :id "u1"}
             :permission "read"
             :resource {:type "folder" :id "f1"}}
   :limits {:max-derived-grants 100
            :max-advanced-datoms 100
            :max-queued-work 100}})

(defn wrong-arrow-direction-killed?
  []
  (let [expected {:status :complete
                  :operation :can?
                  :allowed? true
                  :counters {:derived-grants 2
                             :advanced-datoms 2
                             :queued-work 1}}]
    (portable-mutation-killed?
     portable/decide
     :authorization-evaluation
     arrow-authorization-input
     expected
     (assoc expected :allowed? false))))

(defn premature-cycle-cut-killed?
  []
  (let [input
        (update
         arrow-authorization-input
         :relationships
         conj
         {:resource {:type "folder" :id "f0"}
          :relation "parent"
          :subject {:type "folder" :id "f1"}})
        expected {:status :complete
                  :operation :can?
                  :allowed? true
                  :counters {:derived-grants 2
                             :advanced-datoms 3
                             :queued-work 1}}]
    (portable-mutation-killed?
     portable/decide
     :authorization-evaluation
     input
     expected
     (assoc expected :allowed? false))))

(defn missing-de-duplication-killed?
  []
  (let [input
        (-> arrow-authorization-input
            (assoc :request {:operation :lookup-resources
                             :subject {:type "user" :id "u1"}
                             :permission "read"
                             :resource-type "folder"})
            (update-in [:schema :definitions]
                       #(conj % (first %))))
        expected {:status :complete
                  :operation :lookup-resources
                  :items [{:type "folder" :id "f0"}
                          {:type "folder" :id "f1"}]
                  :counters {:derived-grants 2
                             :advanced-datoms 2
                             :queued-work 1}}]
    (portable-mutation-killed?
     portable/decide
     :authorization-evaluation
     input
     expected
     (update expected :items conj {:type "folder" :id "f1"}))))

(def incomplete-indexed-plan-input
  {:relations
   [{:resource-type "folder" :relation "reader" :subject-type "user"}
    {:resource-type "folder" :relation "parent" :subject-type "folder"}]
   :permissions [{:resource-type "folder" :permission "read"}]
   :definitions
   [{:kind :direct-relation
     :resource-type "folder" :permission "read"
     :relation "reader" :subject-type "user"}
    {:kind :arrow-permission
     :resource-type "folder" :permission "read"
     :via-relation "parent" :target-permission "read"}]
   :relation-bindings
   [{:eid 1
     :relation {:resource-type "folder"
                :relation "reader" :subject-type "user"}}
    {:eid 2
     :relation {:resource-type "folder"
                :relation "parent" :subject-type "folder"}}]
   ;; The arrow rule is deliberately omitted from the candidate proof scope.
   :indexed-rules
   [{:kind :relation
     :head {:resource-type "folder" :permission "read"}
     :relation-eid 1
     :subject-type "user"}]})

(defn incomplete-dependency-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :indexed-plan-certification
   incomplete-indexed-plan-input
   {:status :rejected :reason :compiled-rule-mismatch}
   {:status :certified}))

(defn continuation-race-killed?
  []
  (portable-mutation-killed?
   portable/decide
   :subproblem-cache-decision
   {:decision :publication
    :ticket-current? false
    :complete? true
    :valid? true
    :weight 1
    :budget 10}
   :drop-publication
   :retain-publication))

(defn- audit-snapshot-object
  []
  #?(:clj (Object.)
     :cljs (js-obj)))

(def audit-source-scope
  {:backend :mutation-control
   :source-id :source
   :branch nil})

(def audit-lineage
  {:source-scope audit-source-scope
   :source-lifecycle :lifecycle})

(defn- audit-basis-key
  [revision]
  {:key-version 2
   :backend :mutation-control
   :basis-identity
   (assoc audit-source-scope
          :source-lifecycle :lifecycle
          :basis-kind :ordinary
          :revision revision
          :exact-locator revision
          :backend-snapshot-id revision)
   :adapter-fingerprint :mutation-control
   :identity-contract :mutation-control/v1})

(defn- audit-basis-context
  [revision]
  {:snapshot (audit-snapshot-object)
   :snapshot-order revision
   :exact-basis-key (audit-basis-key revision)
   :cache-basis revision
   :managed-subproblem-scope audit-lineage
   :managed-key-fn
   (constantly {:schema-generation 10 :dependency-stamp 20})})

(defn numeric-ancestry-killed?
  "Kills the obsolete order-as-ancestry rule against the replacement contract:
  equal complete frames permit reuse in either revision direction."
  []
  (let [run
        (fn []
          (let [store (cache/basis-cache)
                computations (atom 0)
                resolve
                (fn [revision value]
                  (cache/resolve-basis!
                   store (audit-basis-context revision)
                   :same-query :decision boolean?
                   (fn []
                     (swap! computations inc)
                     value)))
                _ (resolve 2 true)
                older (resolve 1 false)]
            {:older-value (:value older)
             :older-tier (:cache-tier older)
             :computations @computations}))
        expected {:older-value true
                  :older-tier :managed-current
                  :computations 1}
        original cache/resolve-basis!]
    (and
     (= expected (run))
     (not=
      expected
      (with-redefs [cache/resolve-basis!
                    (fn [store context semantic-key kind valid-value? compute]
                      (original
                       store
                       (if (= 1 (:snapshot-order context))
                         (dissoc context :managed-key-fn)
                         context)
                       semantic-key kind valid-value? compute))]
        (run))))))

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

(defn- ordered-generation-adapter
  [revision provider]
  (backend/make-adapter
   {:id :mutation-control
    :capabilities {:cache-proofs #{:ordered-generations}}
    :operations
    (merge
     (operation-map)
     {:snapshot-id (constantly {:revision revision})
      :basis-kind (constantly :ordinary)
      :native-revision
      (constantly {:revision revision :exact-locator revision})
      :order-hint (constantly revision)
      :exact-locator (constantly revision)
      :schema-generation (constantly 0)
      :proof-frame provider})}))

(defn adapter-generation-domain-killed?
  []
  (let [adapter
        (ordered-generation-adapter 5 (constantly [[1 "not-a-generation"]]))
        gate
        #(= :contract-violation
            (:status
             (proof-frame/resolve!
              (proof-frame/request-frame adapter) [1])))
        original proof-frame/generation?]
    (and
     (gate)
     (false?
      (with-redefs [proof-frame/generation? (constantly true)]
        (gate)))
     (fn? original))))

(defn adapter-generation-ceiling-killed?
  []
  (let [adapter (ordered-generation-adapter 5 (constantly [[1 6]]))
        gate
        #(= :contract-violation
            (:status
             (proof-frame/resolve!
              (proof-frame/request-frame adapter) [1])))
        original proof-frame/revision]
    (and
     (gate)
     (false?
      (with-redefs [proof-frame/revision (constantly 6)]
        (gate)))
     (fn? original))))

(defn non-durable-live-source-id-collision-killed?
  []
  (let [first-adapter
        (ordered-generation-adapter 5 (constantly []))
        second-adapter
        (assoc-in first-adapter
                  [:eacl.backend.v8/operations :snapshot-id]
                  (constantly {:revision 5 :source-id :second-live-source}))
        first-adapter
        (assoc-in first-adapter
                  [:eacl.backend.v8/operations :snapshot-id]
                  (constantly {:revision 5 :source-id :first-live-source}))
        identity
        (fn [adapter]
          {:backend (backend/backend-id adapter)
           :source-id (:source-id (backend/invoke adapter :snapshot-id))
           :branch nil
           :source-lifecycle "eacl/initial"
           :basis-kind :ordinary
           :revision 5
           :exact-locator 5
           :backend-snapshot-id (backend/invoke adapter :snapshot-id)})
        separated?
        #(not= (request-context/lineage-for-basis
                (identity first-adapter))
               (request-context/lineage-for-basis
                (identity second-adapter)))
        original request-context/lineage-for-basis]
    (and
     (separated?)
     (false?
      (with-redefs [request-context/lineage-for-basis
                    (fn [basis-identity]
                      (assoc-in
                       (original basis-identity)
                       [:source-scope :source-id]
                       :collided-live-source))]
        (separated?))))))

(defn plan-read-scope-escape-killed?
  []
  (let [adapter
        (backend/make-adapter
         {:id :mutation-control
          :capabilities backend/empty-capabilities
          :operations
          (merge
           (operation-map)
           {:permission-defs
            (fn [resource-type permission]
              (when (= [:document :view]
                       [resource-type permission])
                [{:source-relation-name :self
                  :target-type :relation
                  :target-name :viewer}]))
            :relation-defs
            (fn [resource-type relation]
              (when (= [:document :viewer]
                       [resource-type relation])
                [{:relation-id 1 :subject-type :user}]))})})
        compiled (sealed-plan/seal-plan adapter [:document :view])
        escaped
        (update compiled :rules conj
                {:rule :relation
                 :node [:document :view]
                 :resource-type :document
                 :permission :view
                 :relation-eid 2
                 :subject-type :user
                 :ordinal 1
                 :rank 1})
        admitted?
        (fn [plan]
          (try
            (identical?
             plan (engine/certify-plan-read-scope! plan [1]))
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) _
              false)))]
    (and (admitted? compiled)
         (false? (admitted? escaped)))))

(defn checkpoint-native-revision-key-killed?
  []
  (let [lineage {:source-scope
                 {:backend :mutation-control
                  :source-id "checkpoint-source"
                  :branch nil}
                 :source-lifecycle "checkpoint-lifecycle"}
        plan {:fingerprint "checkpoint-plan"
              :rules [{:relation-eid 1}]}
        key-at
        (fn [revision]
          (let [adapter
                (ordered-generation-adapter
                 revision (constantly [[1 4]]))
                frame
                (proof-frame/descriptor
                 (proof-frame/resolve!
                  (proof-frame/request-frame adapter) [1]))]
            (binding [engine/*request-lineage* lineage
                      engine/*request-frame* frame
                      engine/*proof-frame*
                      (proof-frame/request-frame adapter)]
              (engine/checkpoint-key
               plan :forward :user 10 20))))
        gate #(= (key-at 5) (key-at 6))
        original engine/checkpoint-key]
    (and
     (gate)
     (false?
      (with-redefs
       [engine/checkpoint-key
        (fn [& args]
          (conj
           (apply original args)
           (backend/invoke
            (:adapter engine/*proof-frame*) :native-revision)))]
        (gate))))))

(defn checkpoint-admissions-counter-drop-killed?
  []
  (let [finished
        {:stack []
         :admitted #{}
         :admissions 9
         :transitions 10
         :commands 4
         :fetched-values 7
         :discovered 3
         :maximum-stack 2}
        next-work {:kind :grant
                   :rule {:node 7}
                   :resource-eid 19}
        cumulative-limit-fails?
        (fn []
          (let [checkpoint (stable-reducer/history-free finished)
                resumed
                (merge
                 {:stack []
                  :admitted (transient #{})
                  :admissions 0
                  :max-admissions 9
                  :max-stack 100
                  :maximum-stack 0}
                 checkpoint
                 {:admitted (transient (:admitted checkpoint))})]
            (try
              (stable-reducer/schedule resumed nil [next-work])
              false
              (catch #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core.ExceptionInfo) error
                (= :max-admissions (:limit (ex-data error)))))))
        gate cumulative-limit-fails?
        original stable-reducer/history-free]
    (and
     (gate)
     (false?
      (with-redefs [stable-reducer/history-free
                    #(dissoc (original %) :admissions)]
        (gate))))))

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
  {:wrong-arrow-direction wrong-arrow-direction-killed?
   :premature-cycle-cut premature-cycle-cut-killed?
   :missing-de-duplication missing-de-duplication-killed?
   :incomplete-dependency incomplete-dependency-killed?
   :numeric-ancestry numeric-ancestry-killed?
   :continuation-race continuation-race-killed?
   :wrong-frontier wrong-frontier-killed?
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
   :adapter-generation-domain adapter-generation-domain-killed?
   :adapter-generation-ceiling adapter-generation-ceiling-killed?
   :non-durable-live-source-id-collision
   non-durable-live-source-id-collision-killed?
   :plan-read-scope-escape plan-read-scope-escape-killed?
   :checkpoint-native-revision-key checkpoint-native-revision-key-killed?
   :checkpoint-admissions-counter-drop
   checkpoint-admissions-counter-drop-killed?
   :aggregate-counter-reset aggregate-counter-reset-killed?
   :batch-cross-demand-contamination batch-cross-demand-contamination-killed?
   :aggregate-deadline-renewal aggregate-deadline-renewal-killed?})

(deftest every-portable-production-mutant-is-killed-test
  (doseq [[id detector] controls]
    (testing (name id)
      (is (true? (detector)) (str "surviving production mutant: " id)))))
