(ns eacl.formal.executed-mutation-controls
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.string :as str]
            [eacl.backend.v8 :as backend]
            [eacl.backend.direct-membership :as direct]
            [eacl.authorization.batch :as batch]
            [eacl.cache :as cache]
            [eacl.engine.portable-decisions :as portable]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as stable-reducer]
            [eacl.engine.v8 :as engine]
            [eacl.operator.evaluator :as operator-evaluator]
            [eacl.operator.lookup :as operator-lookup]
            [eacl.operator.plan :as operator-plan]
            [eacl.operator.recursive :as operator-recursive]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.context :as request-context]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-graph :as expression-graph]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.spicedb.parser :as parser]
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

;;; ---------------------------------------------------------------------------
;;; Operator engine controls
;;;
;;; Every operator control mutates a named production definition and detects
;;; the mutation through a full production consumer: schema validation, plan
;;; sealing, acyclic point evaluation, recursive stratified evaluation, or
;;; filtered pagination. Expectations are independent truth tables derived
;;; from the documented operator semantics, never from the code under
;;; mutation, and every control additionally proves its baseline observation
;;; executed the mutated definition at least once.

(defn- operator-probe-adapter-from-validated
  "Builds a v8 adapter over `validated` schema whose relationship tuples are
  exactly `relationships`: a set of
  `[subject-type subject-eid relation-name resource-type resource-eid]`.
  Relation names resolve to the deterministic relation ids the sealed plan
  sees, and both scan directions honor strict eid order and exclusive or
  inclusive bounds."
  [validated relationships]
  (let [candidate (persistence/candidate-schema validated)
        relation-key (juxt :eacl.relation/resource-type
                           :eacl.relation/relation-name
                           :eacl.relation/subject-type)
        rows (->> (:relations candidate)
                  (sort-by relation-key)
                  (map-indexed
                   (fn [index relation]
                     {:relation-id (+ 100 index)
                      :resource-type (:eacl.relation/resource-type relation)
                      :relation-name (:eacl.relation/relation-name relation)
                      :subject-type (:eacl.relation/subject-type relation)}))
                  vec)
        relation-ids (into {}
                           (map (fn [row]
                                  [[(:resource-type row)
                                    (:relation-name row)]
                                   (:relation-id row)]))
                           rows)
        relations (group-by (juxt :resource-type :relation-name) rows)
        expressions (into {}
                          (map (fn [entity]
                                 [[(:eacl.permission/resource-type entity)
                                   (:eacl.permission/permission-name entity)]
                                  entity]))
                          (:permissions candidate))
        tuples (into #{}
                     (map (fn [[subject-type subject-eid relation-name
                                resource-type resource-eid]]
                            [subject-type subject-eid
                             (get relation-ids
                                  [resource-type relation-name])
                             resource-type resource-eid]))
                     relationships)
        scan (fn [match-fn extract-fn]
               (fn [type-a eid-a relation-id type-b
                    {:keys [direction bound-eid inclusive-bound?]}]
                 (let [eids (->> tuples
                                 (filter #(match-fn % type-a eid-a
                                                    relation-id type-b))
                                 (map extract-fn)
                                 sort
                                 vec)
                       eids (if (= :desc direction)
                              (vec (reverse eids))
                              eids)]
                   (cond->> eids
                     (some? bound-eid)
                     (filterv
                      (fn [eid]
                        (if (= :desc direction)
                          (if inclusive-bound?
                            (<= eid bound-eid)
                            (< eid bound-eid))
                          (if inclusive-bound?
                            (>= eid bound-eid)
                            (> eid bound-eid)))))))))]
    (backend/make-adapter
     {:id :operator-mutation-control
      :capabilities backend/empty-capabilities
      :operations
      (merge
       (operation-map)
       {:snapshot-id (constantly {:snapshot :operator-mutation-control})
        :basis-kind (constantly :ordinary)
        :native-revision (constantly {:revision 1})
        :order-hint (constantly 1)
        :exact-locator (constantly nil)
        :object-id->internal identity
        :internal-id->object identity
        :relation-defs
        (fn [resource-type relation-name]
          (mapv #(select-keys % [:relation-id :resource-type
                                 :relation-name :subject-type])
                (get relations [resource-type relation-name] [])))
        :permission-expression
        (fn [resource-type permission-name]
          (get expressions [resource-type permission-name]))
        :permission-defs
        (fn [resource-type permission-name]
          (when-let [entity (get expressions
                                 [resource-type permission-name])]
            (persistence/union-compatible-definitions
             (:eacl/id entity)
             (persistence/decode-entity entity))))
        :subject->resources
        (scan (fn [[subject-type subject-eid relation resource-type _]
                   type-a eid-a relation-id type-b]
                (and (= subject-type type-a) (= subject-eid eid-a)
                     (= relation relation-id) (= resource-type type-b)))
              (fn [[_ _ _ _ resource-eid]] resource-eid))
        :resource->subjects
        (scan (fn [[subject-type _ relation resource-type resource-eid]
                   type-a eid-a relation-id type-b]
                (and (= resource-type type-a) (= resource-eid eid-a)
                     (= relation relation-id) (= subject-type type-b)))
              (fn [[_ subject-eid _ _ _]] subject-eid))
        :direct-match?
        (fn [subject-type subject-eid relation-eid
             resource-type resource-eid]
          (contains? tuples [subject-type subject-eid relation-eid
                             resource-type resource-eid]))
        :all-permission-nodes (constantly (set (keys expressions)))})})))

(defn- operator-probe-adapter
  [schema-source relationships]
  (operator-probe-adapter-from-validated
   (resolver/validate-schema schema-source)
   relationships))

(defn- operator-typed-or
  "Runs `probe`, returning its value or `{:typed <:eacl/error>}` when it
  throws. The recursive engine's internal bounded-versus-exact differential
  converts several mutations into typed integrity errors instead of silent
  wrong answers; both observations are production output."
  [probe]
  (try
    (probe)
    (catch #?(:clj Exception :cljs :default) error
      {:typed (:type (ex-data error))})))

(def ^:private operator-precedence-schema
  "definition user {}
definition doc {
  relation reader: user
  relation writer: user
  relation approved: user
  relation banned: user
  permission view = reader + writer & approved - banned
}")

(def ^:private operator-precedence-relationships
  "Subjects on one shared document: 1 reader; 2 writer; 3 writer+approved;
  4 reader+banned; 5 reader+approved; 6 writer+approved+banned;
  7 approved."
  #{[:user 1 :reader :doc 30]
    [:user 2 :writer :doc 30]
    [:user 3 :writer :doc 30] [:user 3 :approved :doc 30]
    [:user 4 :reader :doc 30] [:user 4 :banned :doc 30]
    [:user 5 :reader :doc 30] [:user 5 :approved :doc 30]
    [:user 6 :writer :doc 30] [:user 6 :approved :doc 30]
    [:user 6 :banned :doc 30]
    [:user 7 :approved :doc 30]})

(def ^:private operator-precedence-expected
  "Digest-pinned SpiceDB semantics of
  `reader + writer & approved - banned` read as
  `((reader + writer) & approved) - banned` for subjects 1..7."
  [false false true false true false false])

(defn- operator-precedence-decisions
  "Validates, seals, and point-evaluates the mixed-operator schema through
  the production pipeline from an already observed parser result."
  [parse-tree]
  (operator-typed-or
   (fn []
     (let [validated (resolver/resolve-parse-tree parse-tree)
           adapter (operator-probe-adapter-from-validated
                    validated operator-precedence-relationships)
           plan (operator-plan/seal-plan adapter [:doc :view])]
       (mapv (fn [subject-eid]
               (operator-evaluator/check-eids
                {:adapter adapter :plan plan :subject-type :user
                 :subject-eid subject-eid :resource-eid 30}))
             [1 2 3 4 5 6 7])))))

(defn- operator-precedence-parse
  []
  ;; `apply` keeps the parser invocation first-class under advanced CLJS
  ;; optimization, so the production mutation is actually executed instead
  ;; of bypassed by a statically dispatched arity call.
  (apply parser/parse-schema [operator-precedence-schema]))

(defn- parse-schema-result
  [expected-source parse-tree executed]
  (fn
    ([source]
     (vswap! executed inc)
     (if (= expected-source source)
       parse-tree
       (throw (ex-info "Unexpected mutation-control schema source."
                       {:expected expected-source :actual source}))))
    ([source options]
     (vswap! executed inc)
     (if (and (= expected-source source) (empty? options))
       parse-tree
       (throw (ex-info "Unexpected mutation-control schema invocation."
                       {:expected expected-source :actual source
                        :options options}))))))

(defn- rewritten-parse-schema
  [original from to executed]
  (parse-schema-result
   operator-precedence-schema
   (original (str/replace operator-precedence-schema from to))
   executed))

(defn operator-wrong-precedence-killed?
  []
  (let [original parser/parse-schema
        baseline (original operator-precedence-schema)
        executed (volatile! 0)
        mutant (rewritten-parse-schema
                original
                "reader + writer & approved - banned"
                "(reader + (writer & approved)) - banned"
                executed)]
    (and
     (= operator-precedence-expected
        (operator-precedence-decisions baseline))
     ;; Conventional intersection-before-union precedence: subject 1, a bare
     ;; reader without approval, becomes authorized.
     (not= operator-precedence-expected
           (with-redefs [parser/parse-schema mutant]
             (operator-precedence-decisions
              (operator-precedence-parse))))
     (pos? @executed))))

(defn operator-swapped-exclusion-killed?
  []
  (let [original parser/parse-schema
        baseline (original operator-precedence-schema)
        executed (volatile! 0)
        mutant (rewritten-parse-schema
                original
                "reader + writer & approved - banned"
                "banned - (reader + writer & approved)"
                executed)]
    (and
     (= operator-precedence-expected
        (operator-precedence-decisions baseline))
     ;; Swapped exclusion operands authorize exactly the banned subjects.
     (not= operator-precedence-expected
           (with-redefs [parser/parse-schema mutant]
             (operator-precedence-decisions
              (operator-precedence-parse))))
     (pos? @executed))))

(defn operator-unsigned-dependency-killed?
  []
  (let [expressions
        [(expression/expression
          :document :view
          (expression/exclusion
           (expression/relation :reader [:user])
           (expression/permission :blocked)))
         (expression/expression
          :document :blocked
          (expression/relation :banned [:user]))]
        negative?
        #(= :negative
            (->> (expression-graph/build-certificate expressions)
                 :edges
                 (filter (fn [edge]
                           (= [[:document :view] [:document :blocked]]
                              [(:from edge) (:to edge)])))
                 first
                 :sign))
        original expression-graph/signed-dependencies]
    (and
     (negative?)
     (false?
      (with-redefs
       [expression-graph/signed-dependencies
        (fn [candidate-expressions]
          (mapv #(assoc % :sign :positive)
                (original candidate-expressions)))]
       (negative?))))))

(defn operator-missing-join-slot-killed?
  []
  (let [rule {:key :parent :anchor-slot 0}
        initial
        {:join-states
         {:parent {:width 2 :words [0] :satisfied 0}}}
        complete?
        #(operator-recursive/join-complete?
          (-> initial
              (operator-recursive/update-join rule 0)
              (operator-recursive/update-join rule 1))
          rule)
        original operator-recursive/update-join]
    (and
     (complete?)
     (false?
      (with-redefs
       [operator-recursive/update-join
        (fn [state candidate-rule slot]
          (if (= 1 slot) state (original state candidate-rule slot)))]
       (complete?))))))

(def ^:private operator-recursive-schema
  "definition user {}
definition doc {
  relation sdir: user
  relation eligible: user
  relation direct: user
  relation rdir: user
  relation banned: user
  permission seed = sdir
  permission via = seed & eligible
  permission rec = member & rdir
  permission member = (via + direct + rec) - banned
}")

(def ^:private operator-recursive-relationships
  "Subject 1 reaches `member` through `via`; subject 2 through `direct`
  (its member fact then arrives at `via`'s join as a non-anchor child whose
  anchor never does); subject 3 holds only the join anchor; subject 4 is
  banned."
  #{[:user 1 :sdir :doc 10] [:user 1 :eligible :doc 10]
    [:user 2 :direct :doc 11]
    [:user 3 :eligible :doc 12]
    [:user 4 :direct :doc 13] [:user 4 :banned :doc 13]})

(def ^:private operator-recursive-expected
  "Stratified least-fixed-point truth for subjects 1..4 on their documents."
  [true true false false])

(defn- operator-recursive-observation
  "Evaluates the recursive fixture through stratified production evaluation,
  returning the aligned decisions plus the retained anchor-state count the
  engine accounts against its D11 bound."
  []
  (operator-typed-or
   (fn []
     (let [adapter (operator-probe-adapter
                    operator-recursive-schema
                    operator-recursive-relationships)
           plan (operator-plan/seal-plan adapter [:doc :member])
           result (operator-recursive/evaluate-many
                   {:adapter adapter :plan plan :permission [:doc :member]
                    :candidates
                    [{:direction :forward :subject-type :user
                      :subject-eid 1 :resource-eid 10}
                     {:direction :forward :subject-type :user
                      :subject-eid 2 :resource-eid 11}
                     {:direction :forward :subject-type :user
                      :subject-eid 3 :resource-eid 12}
                     {:direction :forward :subject-type :user
                      :subject-eid 4 :resource-eid 13}]})]
       {:decisions (:decisions result)
        :anchor-states (get-in result [:counters :anchor-states])}))))

(def ^:private operator-duplicate-schema
  "One cyclic component {b, both} in which `adir` (the join anchor) and `b`
  are both in the join's facts when it reserves, so `b`'s in-component
  delivery then re-admits an already-satisfied slot."
  "definition user {}
definition doc {
  relation adir: user
  relation bdir: user
  relation cdir: user
  permission cperm = cdir
  permission b = bdir + both
  permission both = adir & b & cperm
  permission member = both
}")

(def ^:private operator-duplicate-relationships
  "Subject 5 lacks `cdir`, so its three-way join must stay incomplete;
  subject 6 holds all three."
  #{[:user 5 :adir :doc 20] [:user 5 :bdir :doc 20]
    [:user 6 :adir :doc 21] [:user 6 :bdir :doc 21]
    [:user 6 :cdir :doc 21]})

(def ^:private operator-duplicate-expected
  [false true])

(defn- operator-duplicate-decisions
  []
  (operator-typed-or
   (fn []
     (let [adapter (operator-probe-adapter
                    operator-duplicate-schema
                    operator-duplicate-relationships)
           plan (operator-plan/seal-plan adapter [:doc :member])]
       (:decisions
        (operator-recursive/evaluate-many
         {:adapter adapter :plan plan :permission [:doc :member]
          :candidates
          [{:direction :forward :subject-type :user
            :subject-eid 5 :resource-eid 20}
           {:direction :forward :subject-type :user
            :subject-eid 6 :resource-eid 21}]}))))))

(defn operator-duplicate-satisfaction-count-killed?
  []
  (let [original operator-recursive/update-join
        executed (volatile! 0)
        counted (fn [& args]
                  (vswap! executed inc)
                  (apply original args))]
    (and
     (= operator-duplicate-expected
        (with-redefs [operator-recursive/update-join counted]
          (operator-duplicate-decisions)))
     (pos? @executed)
     ;; Counting a duplicate slot admission drives `satisfied` to the join
     ;; width before the third child exists; production surfaces the breach
     ;; instead of authorizing subject 5.
     (not= operator-duplicate-expected
           (with-redefs [operator-recursive/update-join
                         (fn [state rule slot]
                           (update-in (original state rule slot)
                                      [:join-states (:key rule) :satisfied]
                                      inc))]
             (operator-duplicate-decisions))))))

(defn operator-partial-negative-killed?
  []
  (let [original operator-recursive/exclusion-decision
        executed (volatile! 0)
        counted (fn [& args]
                  (vswap! executed inc)
                  (apply original args))
        expected {:decisions operator-recursive-expected
                  :anchor-states 2}]
    (and
     (= expected
        (with-redefs [operator-recursive/exclusion-decision counted]
          (operator-recursive-observation)))
     (pos? @executed)
     ;; Deciding exclusion from left-side presence alone, as if the negative
     ;; component were absent before completion, authorizes banned subject 4;
     ;; production refuses to publish the divergent evaluation.
     (not= expected
           (with-redefs [operator-recursive/exclusion-decision
                         (fn [completed right-component facts left right]
                           (if (contains? facts left) :authorize :deny))]
             (operator-recursive-observation))))))

(defn- operator-direct-adapter []
  (backend/make-adapter
   {:id :operator-mutation-control
    :capabilities backend/empty-capabilities
    :operations
    (merge
     (operation-map)
     {:direct-match?
      (fn [_subject-type _subject-eid _relation-eid
           _resource-type resource-eid]
        (= 10 resource-eid))})}))

(defn operator-vector-misalignment-killed?
  []
  (let [adapter (operator-direct-adapter)
        probes
        [{:direction :forward
          :descriptor {:subject-type :user :subject-eid 1
                       :relation-eid 2 :resource-type :document}
          :candidate [:document 20]}
         {:direction :forward
          :descriptor {:subject-type :user :subject-eid 1
                       :relation-eid 2 :resource-type :document}
          :candidate [:document 10]}]
        evaluate #(direct/dispatch adapter probes)
        original direct/direct-match-many?]
    (and
     (= [false true] (evaluate))
     (not=
      [false true]
      (with-redefs
       [direct/direct-match-many?
        (fn [candidate-adapter request]
          (vec (reverse (original candidate-adapter request))))]
       (evaluate))))))

(def ^:private operator-lookup-schema
  "definition user {}
definition doc {
  relation reader: user
  relation banned: user
  permission view = reader - banned
}")

(def ^:private operator-lookup-relationships
  "Subject 1 reads documents 10..17; 11..13 are banned, so accepted
  results interleave with physically examined rejections and every page
  boundary sits between a selected result and an overread suffix."
  (into #{[:user 1 :banned :doc 11]
          [:user 1 :banned :doc 12]
          [:user 1 :banned :doc 13]}
        (map (fn [resource-eid] [:user 1 :reader :doc resource-eid]))
        [10 11 12 13 14 15 16 17]))

(def ^:private operator-lookup-expected
  "Every authorized resource in ascending order, independent of paging."
  [10 14 15 16 17])

(defn- operator-lookup-sweep
  "Pages the filtered lookup two results at a time, resuming each page from
  the previous page's public resume coordinate, and returns every emitted
  resource eid in order."
  []
  (operator-typed-or
   (fn []
     (let [adapter (operator-probe-adapter
                    operator-lookup-schema
                    operator-lookup-relationships)
           plan (operator-plan/seal-plan adapter [:doc :view])]
       (loop [boundary nil
              emitted []
              pages 0]
         (let [page (operator-lookup/lookup-page
                     {:adapter adapter :plan plan :traversal :forward
                      :subject-type :user :anchor-eid 1 :page-size 2
                      :boundary boundary :permission [:doc :view]})
               emitted (into emitted (map :value) (:emissions page))]
           (if (and (:has-more? page) (< pages 8))
             (recur (:resume-coords page) emitted (inc pages))
             emitted)))))))

(defn operator-overread-cursor-advance-killed?
  []
  (let [original operator-lookup/resume-coordinate
        executed (volatile! 0)
        counted (fn [& args]
                  (vswap! executed inc)
                  (apply original args))]
    (and
     (= operator-lookup-expected
        (with-redefs [operator-lookup/resume-coordinate counted]
          (operator-lookup-sweep)))
     (pos? @executed)
     ;; Advancing the public cursor to the physically examined coordinate
     ;; skips the sentinel result on resume, so the paged sweep loses an
     ;; authorized resource.
     (not= operator-lookup-expected
           (with-redefs [operator-lookup/resume-coordinate
                         (fn [sentinel? selected last-examined]
                           last-examined)]
             (operator-lookup-sweep))))))

(defn operator-any-child-allocation-killed?
  []
  (let [original operator-recursive/join-transition-action
        executed (volatile! 0)
        counted (fn [& args]
                  (vswap! executed inc)
                  (apply original args))
        expected {:decisions operator-recursive-expected
                  :anchor-states 2}]
    (and
     (= expected
        (with-redefs [operator-recursive/join-transition-action counted]
          (operator-recursive-observation)))
     (pos? @executed)
     ;; Reserving parent join state for a non-anchor arrival retains state
     ;; for joins whose anchor never fires, which the engine's accounted
     ;; anchor-state bound makes visible.
     (not= expected
           (with-redefs [operator-recursive/join-transition-action
                         (fn [state rule slot]
                           (if (contains? (:join-states state) (:key rule))
                             :update
                             :reserve))]
             (operator-recursive-observation))))))

(def ^:private operator-generator-schema
  "definition user {}
definition doc {
  relation reader: user
  relation writer: user
  relation banned: user
  permission view = (reader & writer) - banned
}")

(defn- operator-sealed-plans
  "Seals the same intersection schema twice over independently built
  adapters. Plan identity across the two runs is the deterministic-selection
  contract: nothing observed at runtime may change the sealed generator."
  []
  (letfn [(seal []
            (operator-plan/seal-plan
             (operator-probe-adapter operator-generator-schema #{})
             [:doc :view]))]
    (= (seal) (seal))))

(defn operator-cache-selected-generator-killed?
  []
  (let [original operator-plan/select-intersection-anchor
        executed (volatile! 0)
        counted (fn [& args]
                  (vswap! executed inc)
                  (apply original args))
        observed-cache (atom false)]
    (and
     (true? (with-redefs [operator-plan/select-intersection-anchor counted]
              (operator-sealed-plans)))
     (pos? @executed)
     ;; A selector that consults observed state alternates anchors between
     ;; compilations, so two seals of one schema stop producing one plan.
     (false? (with-redefs [operator-plan/select-intersection-anchor
                           (fn [children costs]
                             (if (swap! observed-cache not)
                               (first (sort children))
                               (second (sort children))))]
               (operator-sealed-plans))))))

(defn operator-active-recursion-as-false-killed?
  []
  (let [original operator-evaluator/active-recursion-outcome
        executed (volatile! 0)
        counted (fn [& args]
                  (vswap! executed inc)
                  (apply original args))
        probe
        (fn []
          (operator-typed-or
           (fn []
             (let [adapter (operator-probe-adapter
                            operator-lookup-schema
                            #{[:user 1 :reader :doc 10]})
                   plan (operator-plan/seal-plan adapter [:doc :view])
                   rerouted-node
                   (first (keys (get-in plan
                                        [:predicate-programs [:doc :view]])))
                   ;; Reroute one sealed predicate back at its own root: the
                   ;; only execution that can make a point key active twice,
                   ;; since sealing rejects cyclic predicate graphs.
                   self-referential
                   (assoc-in plan
                             [:predicate-programs [:doc :view]
                              rerouted-node]
                             {:instruction :permission-membership
                              :target-node [:doc :view]})]
               (operator-evaluator/check-eids
                {:adapter adapter :plan self-referential
                 :subject-type :user :subject-eid 1 :resource-eid 10})))))
        expected {:typed :eacl.operator/active-recursion}]
    (and
     (= expected
        (with-redefs [operator-evaluator/active-recursion-outcome counted]
          (probe)))
     (pos? @executed)
     ;; Treating an active recursion marker as a false decision converts the
     ;; fail-closed invariant breach into an ordinary denial.
     (not= expected
           (with-redefs [operator-evaluator/active-recursion-outcome
                         (fn [data] false)]
             (probe))))))

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
   :aggregate-deadline-renewal aggregate-deadline-renewal-killed?
   :operator-wrong-precedence operator-wrong-precedence-killed?
   :operator-swapped-exclusion operator-swapped-exclusion-killed?
   :operator-unsigned-dependency operator-unsigned-dependency-killed?
   :operator-missing-join-slot operator-missing-join-slot-killed?
   :operator-duplicate-satisfaction-count
   operator-duplicate-satisfaction-count-killed?
   :operator-partial-negative operator-partial-negative-killed?
   :operator-vector-misalignment operator-vector-misalignment-killed?
   :operator-overread-cursor-advance operator-overread-cursor-advance-killed?
   :operator-any-child-allocation operator-any-child-allocation-killed?
   :operator-cache-selected-generator
   operator-cache-selected-generator-killed?
   :operator-active-recursion-as-false
   operator-active-recursion-as-false-killed?})

(deftest every-portable-production-mutant-is-killed-test
  (doseq [[id detector] controls]
    (testing (name id)
      (is (true? (detector)) (str "surviving production mutant: " id)))))
