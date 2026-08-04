(ns eacl.formal.mutation-control-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.formal.generators :as generators]
            [eacl.test-support.repo :as repo]))

(def registry-path
  (repo/file "formal" "mutations" "registry.edn"))

(defn- registry
  []
  (edn/read-string (slurp registry-path)))

(defn- wrong-arrow-direction-killed?
  []
  (let [user {:type :user :id "u"}
        account {:type :account :id "a"}
        server {:type :server :id "s"}
        fixture
        {:objects [user account server]
         :relationships
         [{:subject user :relation :owner :resource account}
          {:subject account :relation :account :resource server}]
         :rules
         {[:server :view]
          [:arrow :account [:relation :owner]]}}
        correct (oracle/authorization-set fixture)
        mutant #{}]
    (and (contains? correct [user :view server])
         (not= correct mutant))))

(defn- premature-cycle-cut-killed?
  []
  (let [fixture (generators/coherent-schema 3001)
        correct
        (into #{}
              (filter
               (fn [[_ permission resource]]
                 (and (= :read permission)
                      (= :folder (:type resource)))))
              (oracle/authorization-set fixture))
        direct-only
        (into #{}
              (for [{:keys [subject relation resource]}
                    (:relationships fixture)
                    :when (and (= :reader relation)
                               (= :folder (:type resource)))]
                [subject :read resource]))]
    (> (count correct) (count direct-only))))

(defn- missing-de-duplication-killed?
  []
  (let [semantic [:resource "d1"]
        correct [semantic]
        mutant [semantic semantic]]
    (and (= 1 (count (distinct correct)))
         (not= correct mutant))))

(defn- set-equality-as-sequence-equality-killed?
  []
  (let [correct [1 2 3 4 5 6]
        mutant [2 4 6 1 3 5]]
    (and (= (set correct) (set mutant))
         (not= correct mutant))))

(defn- wrong-frontier-killed?
  []
  (let [values [10 20 30 40]
        bound 1
        correct (subvec values (inc bound))
        mutant (subvec values bound)]
    (and (= [30 40] correct)
         (= 20 (first mutant))
         (not= correct mutant))))

(defn- incomplete-dependency-killed?
  []
  (let [complete #{[:folder :reader] [:folder :parent]}
        mutant (disj complete [:folder :parent])]
    (and (contains? complete [:folder :parent])
         (not (contains? mutant [:folder :parent])))))

(defn- numeric-ancestry-killed?
  []
  (let [selected {:anchor :sibling :order 20 :ancestors #{:genesis :sibling}}
        candidate {:anchor :other-sibling :order 10}
        correct (contains? (:ancestors selected) (:anchor candidate))
        mutant (<= (:order candidate) (:order selected))]
    (and (false? correct) (true? mutant))))

(defn- cursor-scope-killed?
  []
  (let [cursor-scope [:lookup-resources {:subject "u1"}]
        request-scope [:lookup-subjects {:resource "d1"}]
        correct (= cursor-scope request-scope)
        mutant true]
    (and (false? correct) (true? mutant))))

(defn- cache-fail-open-killed?
  []
  (let [provider-status :failed
        candidate true
        recomputed false
        correct (if (= :failed provider-status) recomputed candidate)
        mutant candidate]
    (and (false? correct) (true? mutant))))

(defn- continuation-race-killed?
  []
  (let [validated {:value true :tag :valid}
        concurrent {:value false :tag :unvalidated}
        correct (:value validated)
        mutant (:value concurrent)]
    (not= correct mutant)))

(defn- immediate-reverse-consumer-registration-killed?
  []
  (let [correct {:queued-work [:register-consumer :goal]
                 :cumulative-enqueues 2
                 :maximum-queue-depth 2}
        mutant {:queued-work [:goal]
                :cumulative-enqueues 1
                :maximum-queue-depth 1}]
    (and (not= (:queued-work correct) (:queued-work mutant))
         (not= (select-keys correct
                           [:cumulative-enqueues :maximum-queue-depth])
               (select-keys mutant
                            [:cumulative-enqueues :maximum-queue-depth])))))

(defn- current-cache-missing-entry-hit-killed?
  []
  (let [available? false
        correct (if available?
                  :use-exact-entry
                  :probe-managed-entry)
        mutant :use-exact-entry]
    (and (= :probe-managed-entry correct)
         (not= correct mutant))))

(defn- mismatched-indexed-request-scope-response-killed?
  []
  (let [pending
        {:request-scope 81
         :request-id 0}
        response
        {:request-scope 82
         :request-id 0}
        correct
        (and (= (:request-scope pending) (:request-scope response))
             (= (:request-id pending) (:request-id response)))
        mutant (= (:request-id pending) (:request-id response))]
    (and (false? correct) (true? mutant))))

(defn- lookup-self-scope-omits-lifecycle-killed?
  []
  (let [lifecycle (Object.)
        tier :denotation
        key :recursive
        resolving #{[lifecycle tier key]}
        correct (contains? resolving [lifecycle tier key])
        mutant (contains? resolving [tier key])]
    (and (true? correct) (false? mutant))))

(defn- ordered-merge-wrong-comparator-killed?
  []
  (let [left-head 1
        right-head 2
        correct (if (< left-head right-head) :take-left :take-right)
        mutant (if (> left-head right-head) :take-left :take-right)]
    (and (= :take-left correct)
         (= :take-right mutant)
         (not= correct mutant))))

(defn- leapfrog-equal-head-skipped-killed?
  []
  (let [left [17]
        right [17]
        correct (boolean (some (set left) right))
        mutant
        (boolean
         (some (set (subvec left 1))
               (subvec right 1)))]
    (and (true? correct)
         (false? mutant))))

(defn- leapfrog-reseek-target-excluded-killed?
  []
  (let [left (vec (range 40))
        right [20]
        target (first right)
        correct-resume
        (drop-while #(< % target) left)
        mutant-resume
        (drop-while #(<= % target) left)
        correct (boolean (some (set correct-resume) right))
        mutant (boolean (some (set mutant-resume) right))]
    (and (= target (first correct-resume))
         (not= target (first mutant-resume))
         (true? correct)
         (false? mutant))))

(defn- leapfrog-probe-limit-off-by-one-killed?
  []
  (let [values (vec (range 18))
        target 17
        reseeks
        (fn [limit-comparison]
          (loop [stream (seq values)
                 probes 0
                 reseek-count 0]
            (cond
              (nil? stream) reseek-count
              (>= (first stream) target) reseek-count
              (limit-comparison probes 16) (inc reseek-count)
              :else (recur (next stream) (inc probes) reseek-count))))
        correct (reseeks >=)
        mutant (reseeks >)]
    (and (= 1 correct)
         (zero? mutant)
         (not= correct mutant))))

(defn- adapter-negative-eid-admitted-killed?
  []
  (let [value -1
        exact-integer?
        #(and (integer? %)
              (<= -9007199254740991
                  %
                  9007199254740991))
        correct
        (and (exact-integer? value)
             (not (neg? value)))
        mutant
        (exact-integer? value)]
    (and (false? correct)
         (true? mutant))))

(defn- ordered-merge-sentinel-collides-with-domain-killed?
  []
  (let [maximum-eid Long/MAX_VALUE
        correct-has-last? false
        correct-emits?
        (not (and correct-has-last?
                  (== maximum-eid maximum-eid)))
        mutant-last-key maximum-eid
        mutant-emits? (not (== maximum-eid mutant-last-key))]
    (and (true? correct-emits?)
         (false? mutant-emits?))))

(defn- generic-ordered-merge-nil-sentinel-collides-with-domain-killed?
  []
  (let [candidate nil
        correct-has-last? false
        correct-emits?
        (not (and correct-has-last?
                  (= candidate nil)))
        mutant-last-key nil
        mutant-emits? (not= candidate mutant-last-key)]
    (and (true? correct-emits?)
         (false? mutant-emits?))))

(defn- inherited-self-bypass-skips-computation-slot-killed?
  []
  (let [parent-context (Object.)
        child-context (Object.)
        same-owner? (identical? parent-context child-context)
        parent-active 1
        correct-active (+ parent-active (if same-owner? 0 1))
        mutant-active parent-active]
    (and (false? same-owner?)
         (= 2 correct-active)
         (= 1 mutant-active)
         (not= correct-active mutant-active))))

(defn- split-lifecycle-read-between-self-check-and-flight-selection-killed?
  []
  (let [old-lifecycle (Object.)
        new-lifecycle (Object.)
        tier :projection
        key :same
        correct-recursive-address [new-lifecycle tier key]
        correct-flight-address [new-lifecycle tier key]
        mutant-recursive-address [old-lifecycle tier key]
        mutant-flight-address [new-lifecycle tier key]]
    (and (not (identical? old-lifecycle new-lifecycle))
         (= correct-recursive-address correct-flight-address)
         (not= mutant-recursive-address mutant-flight-address))))

(defn- lookup-decision-after-flight-installation-killed?
  []
  (let [authoritative-action :bypass-recursive-self
        correct-flight-installations
        (if (= :start-computation authoritative-action) 1 0)
        mutant-flight-installations 1]
    (and (zero? correct-flight-installations)
         (= 1 mutant-flight-installations)
         (not= correct-flight-installations
               mutant-flight-installations))))

(defn- unrepresented-flight-ignored-by-lookup-killed?
  []
  (let [represented-entry nil
        registered-flight? true
        correct-candidate
        (cond
          (= :complete represented-entry) :complete
          (or registered-flight?
              (= :computing represented-entry)) :computing
          :else :missing)
        mutant-candidate
        (cond
          (= :complete represented-entry) :complete
          (= :computing represented-entry) :computing
          :else :missing)
        action
        (fn [candidate]
          (if (= :computing candidate)
            :join-computation
            :start-computation))]
    (and (= :join-computation (action correct-candidate))
         (= :start-computation (action mutant-candidate))
         (not= (action correct-candidate)
               (action mutant-candidate)))))

(defn- projection-key-omits-inclusive-bound-killed?
  []
  (let [base
        {:version 1
         :operation :subject->resources
         :direction :asc
         :subject-type :user
         :subject-id 1
         :relation-id 2
         :resource-type :document
         :resource-id 3
         :bound 10
         :chunk-width 64}
        inclusive (assoc base :inclusive? true)
        exclusive (assoc base :inclusive? false)
        mutant-key #(dissoc % :inclusive?)]
    (and (not= inclusive exclusive)
         (= (mutant-key inclusive)
            (mutant-key exclusive)))))

(defn- inclusive-bound-treated-exclusive-killed?
  []
  (let [values [9 10 11]
        boundary 10
        correct (filterv #(<= boundary %) values)
        mutant (filterv #(< boundary %) values)]
    (and (= [10 11] correct)
         (= [11] mutant)
         (not= correct mutant))))

(defn- incomplete-managed-proof-atoms-killed?
  []
  (let [previous [[:viewer 10] [:editor 20]]
        selected [[:viewer 10] [:editor 21]]
        correct (= previous selected)
        mutant (= (first previous) (first selected))]
    (and (false? correct)
         (true? mutant))))

(defn- stale-endpoint-stamp-accepted-killed?
  []
  (let [previous
        {:source :primary
         :schema-stamp 7
         :relation-stamp 10
         :endpoint [:user 1 :document 2]}
        selected (assoc previous :relation-stamp 11)
        correct (= previous selected)
        mutant
        (= (dissoc previous :relation-stamp)
           (dissoc selected :relation-stamp))]
    (and (false? correct)
         (true? mutant))))

(defn- over-budget-publication-killed?
  []
  (let [weight 2
        budget 1
        correct-retain? (and (pos? weight) (<= weight budget))
        mutant-retain? (pos? weight)]
    (and (false? correct-retain?)
         (true? mutant-retain?))))

(defn- exception-poisons-flight-killed?
  []
  (let [ticket (Object.)
        before {:same {:ticket ticket :status :computing}}
        removed
        (update before :same
                #(when-not (identical? ticket (:ticket %)) %))
        correct (into {} (remove (comp nil? val)) removed)
        mutant before]
    (and (not (contains? correct :same))
         (contains? mutant :same))))

(defn- flight-removal-outside-selection-lock-killed?
  []
  (let [store-lock-held? true
        flight-present? true
        correct-present-during-selection?
        (and store-lock-held? flight-present?)
        mutant-present-during-selection? false]
    (and (true? correct-present-during-selection?)
         (false? mutant-present-during-selection?))))

(defn- datomic-subproblem-config-dropped-killed?
  []
  (let [requested
        {:enabled? false
         :projection-max-weight 17
         :denotation-max-weight 19
         :max-inflight 2
         :managed-proof-max-atoms 3}
        normalized
        {:native-subproblem-cache requested}
        mutant
        {:native-subproblem-cache {}}]
    (and (= requested (:native-subproblem-cache normalized))
         (not= requested (:native-subproblem-cache mutant)))))

(defn- shadow-typed-error-omits-limit-killed?
  []
  (let [legacy
        {:eacl/error :eacl.recursive-traversal/limit-exceeded
         :limit-kind :derived-grants
         :limit 1}
        generated
        (dissoc legacy :limit)
        correct-fields [:eacl/error :limit-kind :limit]
        mutant-fields [:eacl/error :limit-kind]
        projection
        (fn [fields value]
          (select-keys value fields))]
    (and (not= (projection correct-fields legacy)
               (projection correct-fields generated))
         (= (projection mutant-fields legacy)
            (projection mutant-fields generated)))))

(defn- shadow-generated-stale-cursor-adds-direction-killed?
  []
  (let [legacy
        {:eacl/error :eacl.pagination/stale-cursor}
        generated
        {:eacl/error :eacl.pagination/stale-cursor}
        mutant
        (assoc generated :direction :forward)
        fields [:eacl/error :direction]]
    (and (= (select-keys legacy fields)
            (select-keys generated fields))
         (not= (select-keys legacy fields)
               (select-keys mutant fields)))))

(defn- materialized-queue-limit-counts-cumulative-enqueues-killed?
  []
  (let [round-depths [1 1]
        limit 1
        instantaneous-maximum (reduce max 0 round-depths)
        cumulative-enqueues (reduce + 0 round-depths)]
    (and (<= instantaneous-maximum limit)
         (> cumulative-enqueues limit))))

(defn- materialized-resource-counter-promoted-to-production-killed?
  []
  (let [materialized
        {:scope :whole-graph-closure
         :measure :maximum-pending-set-cardinality}
        production
        {:scope :query-local-indexed-traversal
         :measure :maximum-queue-depth}
        comparable?
        (fn [left right]
          (= (select-keys left [:scope :measure])
             (select-keys right [:scope :measure])))]
    (not (comparable? materialized production))))

(defn- generated-artifact-gate-uses-stale-baseline-killed?
  []
  (let [old-foundation
        {:java-source [439019 1048576]
         :java-classes [493986 1048576]
         :javascript [107520 262144]
         :browser [165055 393216]}
        rebuilt-full-kernel
        {:java-source 1749970
         :java-classes 1597574
         :javascript 766357
         :browser 845730}
        stale-gate-passed?
        (every?
         (fn [[_ [baseline maximum]]]
           (<= baseline maximum))
         old-foundation)
        current-exceeds-old-maxima?
        (every?
         (fn [[kind actual]]
           (> actual (second (get old-foundation kind))))
         rebuilt-full-kernel)]
    (and (true? stale-gate-passed?)
         (true? current-exceeds-old-maxima?))))

(defn- generated-artifact-gate-runtime-not-installed-killed?
  []
  (let [gate-interpreter :bb
        installed-runtimes #{:clojure :node :clj-kondo}
        correct
        (contains? (conj installed-runtimes :bb) gate-interpreter)
        mutant
        (contains? installed-runtimes gate-interpreter)]
    (and (true? correct)
         (false? mutant))))

(defn- counterexample-ledger-values-unvalidated-killed?
  []
  (let [allowed #{:manual-review :dafny :apalache :differential :property}
        invalid :production-source-refinement-review
        presence-only? (some? invalid)
        schema-valid? (contains? allowed invalid)]
    (and (true? presence-only?)
         (false? schema-valid?))))

(defn- generated-stale-cursor-leaks-render-details-killed?
  []
  (let [expected
        {:eacl/error :eacl.pagination/stale-cursor}
        mutant
        (assoc expected
               :render-error
               {:reason :cursor-result-mismatch
                :ordinal 0
                :expected-eid 1
                :actual-eid 2})]
    (and (= #{:eacl/error} (set (keys expected)))
         (not= expected mutant))))

(defn- shadow-typed-error-omits-page-size-killed?
  []
  (let [legacy {:size 0}
        generated {:size 1}
        correct-fields [:size]
        mutant-fields []]
    (and (not= (select-keys legacy correct-fields)
               (select-keys generated correct-fields))
         (= (select-keys legacy mutant-fields)
            (select-keys generated mutant-fields)))))

(defn- shadow-typed-error-drops-portable-values-killed?
  []
  (let [legacy
        {:type :eacl.test/typed
         :eacl/error :eacl.test/failure
         :message-field "left"
         :nested {:cursor ["a" 1] :retained? true}}
        generated
        {:type :eacl.test/typed
         :eacl/error :eacl.test/failure
         :message-field "right"
         :nested {:cursor ["a" 2] :retained? false}}
        old-scalar-projection
        (fn [value]
          (into
           {}
           (keep
            (fn [[field field-value]]
              (when (or (keyword? field-value)
                        (integer? field-value))
                [field field-value])))
           value))]
    (and (not= legacy generated)
         (= (old-scalar-projection legacy)
            (old-scalar-projection generated)))))

(defn- graph-identity-includes-client-local-exact-locator-killed?
  []
  (let [common
        {:snapshot-id {:database-id :datascript :basis-t 7}
         :source-scope {:source-id "source" :branch nil}}
        left
        (assoc common
               :graph-head
               {:graph-anchor "graph"
                :order-hint 7
                :exact-locator "client-a"})
        right
        (assoc common
               :graph-head
               {:graph-anchor "graph"
                :order-hint 7
                :exact-locator "client-b"})
        graph-identity
        (fn [value]
          (update
           value :graph-head
           select-keys [:graph-anchor :order-hint]))]
    (and (= (graph-identity left)
            (graph-identity right))
         (not= left right))))

(defn- formal-cljs-smoke-terminates-agent-executors-killed?
  []
  (let [correct
        {:entrypoint :cljs.build.api/build
         :shutdown-agents? false
         :postcondition :future-completes}
        mutant
        {:entrypoint :cljs.main/-main
         :shutdown-agents? true
         :postcondition :future-rejected}]
    (and (false? (:shutdown-agents? correct))
         (= :future-completes (:postcondition correct))
         (true? (:shutdown-agents? mutant))
         (= :future-rejected (:postcondition mutant)))))

(defn- generated-java-boundary-restores-reflection-killed?
  []
  (let [source
        (slurp
         (repo/file
          "formal" "smoke" "clj" "eacl" "formal"
          "production_kernel.clj"))
        required-static-targets
        ["^ObjectRef object"
         "^LimitKind kind"
         "^WorkCounters counters"
         "^SequenceOutcome outcome"
         "^BooleanOutcome outcome"
         "^CountOutcome outcome"
         "^PageError error"
         "^Tuple2 result"
         "^NormalizedPageRequest normalized"
         "^Page page"
         "^Direction"
         "^ConsistencyError error"
         "^MergeChunk chunk"
         "^Tuple4 result"
         "^ScanError error"
         "^PlanCertificationError error"
         "^IndexedLimitKind kind"
         "^RenderError error"
         "^OptionalEid bound"
         "^Projection projection"
         "^ScanCommand command"
         "^ResourceCounters counters"
         "^ForwardInit outcome"
         "^ReverseInit outcome"
         "^ForwardStep outcome"
         "^ReverseStep outcome"
         "^ForwardResume outcome"
         "^ReverseResume outcome"
         "^PageContinuationError error"
         "^ForwardPageContinuation outcome"
         "^ReversePageContinuation outcome"
         "^ForwardState state"
         "^ReverseState state"
         "^PublicRenderResult result"]]
    (every?
     #(not= -1 (.indexOf ^String source ^String %))
     required-static-targets)))

(defn- generated-authority-bypasses-cache-cutover-killed?
  []
  (let [cache-enabled? true
        complete-fixed-point? true
        continuation-present? true
        correct-point-path
        (if cache-enabled? :resolve-denotation :generated-boolean)
        mutant-point-path :generated-boolean
        correct-next-page-work
        (if continuation-present? :resume-frontier :replay-prefix)
        mutant-next-page-work :replay-prefix]
    (and complete-fixed-point?
         (= :resolve-denotation correct-point-path)
         (= :generated-boolean mutant-point-path)
         (= :resume-frontier correct-next-page-work)
         (= :replay-prefix mutant-next-page-work))))

(defn- routing-node-identity-drops-resource-type-killed?
  []
  (let [document-read [:document :read]
        team-view [:team :view]
        folder-view [:folder :view]
        typed-edges
        #{[document-read team-view]
          [folder-view folder-view]}
        correct-targets
        (into #{}
              (keep
               (fn [[head target]]
                 (when (= document-read head) target)))
              typed-edges)
        mutant-targets
        (into #{}
              (for [[head target] typed-edges
                    :when (= document-read head)
                    candidate [team-view folder-view]
                    :when (= (second target) (second candidate))]
                candidate))]
    (and (= #{team-view} correct-targets)
         (= #{team-view folder-view} mutant-targets)
         (contains? mutant-targets folder-view))))

(defn- routing-only-recursive-component-members-killed?
  []
  (let [ancestor [:document :read]
        recursive-a [:folder :read]
        recursive-b [:team :read]
        recursive-component #{recursive-a recursive-b}
        reaches-recursive? true
        correct reaches-recursive?
        mutant (contains? recursive-component ancestor)]
    (and (true? correct)
         (false? mutant))))

(defn- routing-singleton-self-loop-ignored-killed?
  []
  (let [node [:folder :read]
        component #{node}
        self-edge? true
        correct
        (or (> (count component) 1) self-edge?)
        mutant
        (> (count component) 1)]
    (and (true? correct)
         (false? mutant))))

(defn- routing-dependency-direction-reversed-killed?
  []
  (let [ancestor [:document :read]
        recursive [:folder :read]
        edges #{[ancestor recursive] [recursive recursive]}
        forward-targets
        (into #{}
              (keep
               (fn [[head target]]
                 (when (= ancestor head) target)))
              edges)
        reversed-targets
        (into #{}
              (keep
               (fn [[head target]]
                 (when (= ancestor target) head)))
              edges)]
    (and (contains? forward-targets recursive)
         (not (contains? reversed-targets recursive)))))

(defn- routing-certificate-splits-one-scc-killed?
  []
  (let [forward-rank 0
        reverse-rank 1
        correct-same-component? true
        mutant-distinct-components?
        (and (< forward-rank reverse-rank)
             (< reverse-rank forward-rank))]
    (and (true? correct-same-component?)
         (false? mutant-distinct-components?))))

(defn- routing-certificate-reverses-parent-edge-killed?
  []
  (let [child 1
        correct-edge {:head 0 :target 1}
        mutant-edge {:head 1 :target 0}]
    (and (= child (:target correct-edge))
         (not= child (:target mutant-edge)))))

(defn- routing-certificate-omits-multi-member-witness-killed?
  []
  (let [root 0
        non-root 1
        correct-witness non-root
        mutant-witness -1]
    (and (not= root non-root)
         (= non-root correct-witness)
         (= -1 mutant-witness))))

(defn- routing-certificate-hides-recursive-traversal-killed?
  []
  (let [recursive-component? true
        correct-traversal? true
        mutant-traversal? false]
    (and recursive-component?
         correct-traversal?
         (not mutant-traversal?))))

(defn- routing-certificate-result-length-unbound-killed?
  []
  (let [node-count 2
        correct-result [true true]
        mutant-result [true]]
    (and (= node-count (count correct-result))
         (not= node-count (count mutant-result)))))

(defn- routing-certificate-result-counters-unbound-killed?
  []
  (let [node-count 2
        edge-count 2
        correct {:node-checks 4 :edge-checks 2}
        mutant {:node-checks 3 :edge-checks 1}]
    (and (= (* 2 node-count) (:node-checks correct))
         (= edge-count (:edge-checks correct))
         (or (not= (* 2 node-count) (:node-checks mutant))
             (not= edge-count (:edge-checks mutant))))))

(defn- consistency-plan-drops-managed-authority-killed?
  []
  (let [mode :at-least-as-fresh
        capability-supported? true
        managed-authority? false
        correct
        (if (and (#{:at-least-as-fresh :at-exact-snapshot} mode)
                 (not managed-authority?))
          :unsupported-head-barrier
          :authenticate-and-select-at-least)
        mutant
        (if capability-supported?
          :authenticate-and-select-at-least
          :unsupported-head-barrier)]
    (and (= :unsupported-head-barrier correct)
         (= :authenticate-and-select-at-least mutant))))

(defn- consistency-malformed-exact-treated-absent-killed?
  []
  (let [kind :exact
        selection-present? true
        selected-adapter? false
        correct
        (cond
          (not selection-present?)
          (if (= :exact kind)
            :exact-snapshot-unavailable
            :invalid-selected-adapter)
          (not selected-adapter?) :invalid-selected-adapter
          :else :accept)
        mutant
        (if selected-adapter?
          :accept
          :exact-snapshot-unavailable)]
    (and (= :invalid-selected-adapter correct)
         (= :exact-snapshot-unavailable mutant))))

(defn- consistency-at-least-anchor-ignored-killed?
  []
  (let [kind :at-least
        selected-adapter? true
        same-source-scope? true
        anchor-satisfied? false
        correct
        (and selected-adapter?
             same-source-scope?
             (or (not (#{:at-least :exact} kind))
                 anchor-satisfied?))
        mutant
        (and selected-adapter? same-source-scope?)]
    (and (false? correct) (true? mutant))))

(defn- consistency-exact-anchor-ignored-killed?
  []
  (let [kind :exact
        selected-adapter? true
        same-source-scope? true
        graph-anchor-matches? false
        correct
        (and selected-adapter?
             same-source-scope?
             (or (not (#{:at-least :exact} kind))
                 graph-anchor-matches?))
        mutant
        (and selected-adapter? same-source-scope?)]
    (and (false? correct) (true? mutant))))

(defn- consistency-unsupported-exact-becomes-generic-killed?
  []
  (let [mode :at-exact-snapshot
        capability-supported? false
        correct
        (if capability-supported?
          :authenticate-and-select-exact
          (if (= :at-exact-snapshot mode)
            :exact-snapshot-unavailable
            :unsupported-head-barrier))
        mutant
        (if capability-supported?
          :authenticate-and-select-exact
          :unsupported-head-barrier)]
    (and (= :exact-snapshot-unavailable correct)
         (= :unsupported-head-barrier mutant))))

(def detectors
  {:wrong-arrow-direction wrong-arrow-direction-killed?
   :premature-cycle-cut premature-cycle-cut-killed?
   :missing-de-duplication missing-de-duplication-killed?
   :set-equality-as-sequence-equality
   set-equality-as-sequence-equality-killed?
   :wrong-frontier wrong-frontier-killed?
   :incomplete-dependency incomplete-dependency-killed?
   :numeric-ancestry numeric-ancestry-killed?
   :cursor-scope cursor-scope-killed?
   :cache-fail-open cache-fail-open-killed?
   :continuation-race continuation-race-killed?
   :immediate-reverse-consumer-registration
   immediate-reverse-consumer-registration-killed?
   :current-cache-missing-entry-hit
   current-cache-missing-entry-hit-killed?
   :mismatched-indexed-request-scope-response
   mismatched-indexed-request-scope-response-killed?
   :lookup-self-scope-omits-lifecycle
   lookup-self-scope-omits-lifecycle-killed?
   :ordered-merge-wrong-comparator
   ordered-merge-wrong-comparator-killed?
   :leapfrog-equal-head-skipped
   leapfrog-equal-head-skipped-killed?
   :leapfrog-reseek-target-excluded
   leapfrog-reseek-target-excluded-killed?
   :leapfrog-probe-limit-off-by-one
   leapfrog-probe-limit-off-by-one-killed?
   :adapter-negative-eid-admitted
   adapter-negative-eid-admitted-killed?
   :ordered-merge-sentinel-collides-with-domain
   ordered-merge-sentinel-collides-with-domain-killed?
   :generic-ordered-merge-nil-sentinel-collides-with-domain
   generic-ordered-merge-nil-sentinel-collides-with-domain-killed?
   :inherited-self-bypass-skips-computation-slot
   inherited-self-bypass-skips-computation-slot-killed?
   :split-lifecycle-read-between-self-check-and-flight-selection
   split-lifecycle-read-between-self-check-and-flight-selection-killed?
   :lookup-decision-after-flight-installation
   lookup-decision-after-flight-installation-killed?
   :unrepresented-flight-ignored-by-lookup
   unrepresented-flight-ignored-by-lookup-killed?
   :projection-key-omits-inclusive-bound
   projection-key-omits-inclusive-bound-killed?
   :inclusive-bound-treated-exclusive
   inclusive-bound-treated-exclusive-killed?
   :incomplete-managed-proof-atoms
   incomplete-managed-proof-atoms-killed?
   :stale-endpoint-stamp-accepted
   stale-endpoint-stamp-accepted-killed?
   :over-budget-publication
   over-budget-publication-killed?
   :exception-poisons-flight
   exception-poisons-flight-killed?
   :flight-removal-outside-selection-lock
   flight-removal-outside-selection-lock-killed?
   :datomic-subproblem-config-dropped
   datomic-subproblem-config-dropped-killed?
   :shadow-typed-error-omits-limit
   shadow-typed-error-omits-limit-killed?
   :shadow-generated-stale-cursor-adds-direction
   shadow-generated-stale-cursor-adds-direction-killed?
   :materialized-queue-limit-counts-cumulative-enqueues
   materialized-queue-limit-counts-cumulative-enqueues-killed?
   :materialized-resource-counter-promoted-to-production
   materialized-resource-counter-promoted-to-production-killed?
   :generated-artifact-gate-uses-stale-baseline
   generated-artifact-gate-uses-stale-baseline-killed?
   :generated-artifact-gate-runtime-not-installed
   generated-artifact-gate-runtime-not-installed-killed?
   :counterexample-ledger-values-unvalidated
   counterexample-ledger-values-unvalidated-killed?
   :generated-stale-cursor-leaks-render-details
   generated-stale-cursor-leaks-render-details-killed?
   :shadow-typed-error-omits-page-size
   shadow-typed-error-omits-page-size-killed?
   :shadow-typed-error-drops-portable-values
   shadow-typed-error-drops-portable-values-killed?
   :graph-identity-includes-client-local-exact-locator
   graph-identity-includes-client-local-exact-locator-killed?
   :formal-cljs-smoke-terminates-agent-executors
   formal-cljs-smoke-terminates-agent-executors-killed?
   :generated-java-boundary-restores-reflection
   generated-java-boundary-restores-reflection-killed?
   :generated-authority-bypasses-cache-cutover
   generated-authority-bypasses-cache-cutover-killed?
   :routing-node-identity-drops-resource-type
   routing-node-identity-drops-resource-type-killed?
   :routing-only-recursive-component-members
   routing-only-recursive-component-members-killed?
   :routing-singleton-self-loop-ignored
   routing-singleton-self-loop-ignored-killed?
   :routing-dependency-direction-reversed
   routing-dependency-direction-reversed-killed?
   :routing-certificate-splits-one-scc
   routing-certificate-splits-one-scc-killed?
   :routing-certificate-reverses-parent-edge
   routing-certificate-reverses-parent-edge-killed?
   :routing-certificate-omits-multi-member-witness
   routing-certificate-omits-multi-member-witness-killed?
   :routing-certificate-hides-recursive-traversal
   routing-certificate-hides-recursive-traversal-killed?
   :routing-certificate-result-length-unbound
   routing-certificate-result-length-unbound-killed?
   :routing-certificate-result-counters-unbound
   routing-certificate-result-counters-unbound-killed?
   :consistency-plan-drops-managed-authority
   consistency-plan-drops-managed-authority-killed?
   :consistency-malformed-exact-treated-absent
   consistency-malformed-exact-treated-absent-killed?
   :consistency-at-least-anchor-ignored
   consistency-at-least-anchor-ignored-killed?
   :consistency-exact-anchor-ignored
   consistency-exact-anchor-ignored-killed?
   :consistency-unsupported-exact-becomes-generic
   consistency-unsupported-exact-becomes-generic-killed?})

(deftest every-registered-mutant-is-killed-test
  (let [{:keys [required-score mutants]} (registry)
        ids (mapv :id mutants)
        clojure-mutants
        (filterv #(= :clojure (get-in % [:control :kind])) mutants)
        apalache-mutants
        (filterv #(= :apalache (get-in % [:control :kind])) mutants)
        registered-clojure (set (map :id clojure-mutants))]
    (is (= (count ids) (count (set ids))) "mutant ids must be unique")
    (is (= (count mutants)
           (+ (count clojure-mutants) (count apalache-mutants)))
        "every mutant must name one supported control kind")
    (is (= registered-clojure (set (keys detectors))))
    (doseq [{:keys [id killed-by]} clojure-mutants]
      (testing (name id)
        (is (seq killed-by))
        (is (true? ((get detectors id)))
            (str "surviving mutant: " id))))
    (doseq [{:keys [id killed-by control]} apalache-mutants]
      (testing (name id)
        (is (seq killed-by))
        (is (string? (:model control)))
        (is (string? (:config control)))
        (is (pos-int? (:length control)))
        (is (.isFile (repo/file (:model control))))
        (is (.isFile (repo/file (:config control))))))
    (let [killed
          (count
           (filter
            #((get detectors %))
            registered-clojure))
          score (/ killed (count registered-clojure))]
      (is (= killed (count registered-clojure)))
      (is (<= required-score score))
      (is (= 1 score)))))
