(ns eacl.formal.qualified.production-mutations
  "Mutation controls at actual implementation seams, mapped to independent
   conformance tests. Every control first checks its unmodified gate."
  (:require [clojure.test :as t :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.data :as data]
            [eacl.authorization.context :as context]
            [eacl.authorization.context-test :as context-test]
            [eacl.authorization.result :as result]
            [eacl.authorization.result-test :as result-test]
            [eacl.core :as core]
            [eacl.core-test :as core-test]
            [eacl.engine.v8 :as engine]
            [eacl.authorization.batch :as batch]
            [eacl.authorization.data-test :as data-test]
            [eacl.request.counters :as counters]
            [eacl.authorization.clock :as clock]
            [eacl.authorization.evidence-test :as evidence-test]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as qualification-test]
            [eacl.engine.scan-cache :as scan-cache]
            [eacl.engine.scan-cache-test :as scan-test]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-reducer-evidence-test :as discovery-test]
            [eacl.engine.stable-page :as stable-page]
            [eacl.engine.stable-page-evidence-test :as page-test]
            [eacl.engine.stable-route :as stable-route]
            [eacl.engine.stable-route-evidence-test :as stable-route-test]
            [eacl.engine.least-path :as least-path]
            [eacl.engine.least-path-evidence-test :as legacy-lookup-test]
            [eacl.engine.stable-route-native-evidence-test :as native-test]
            [eacl.datascript.evaluation-clock-test :as clock-test]
            [eacl.datascript.caveat-context-test :as public-context-test]
            [eacl.datascript.qualified-check-test :as public-point-test]
            [eacl.datascript.qualified-lookup-test :as public-lookup-test]
            [eacl.cache :as cache]
            [eacl.client.orchestration :as orchestration]
            [eacl.formal.qualified.recursive-bridge :as recursive-bridge]
            [eacl.formal.qualified.seekable-bridge :as seekable-bridge]
            [eacl.formal.qualified.arrow-bridge :as arrow-bridge]
            [eacl.operator.seekable :as seekable]
            [eacl.operator.lookup :as lookup]
            [eacl.operator.lookup-evidence-test :as lookup-test]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.arrow-evidence-test :as arrow-test]
            [eacl.operator.seekable-evidence-test :as seekable-test]
            [eacl.operator.recursive :as recursive]
            [eacl.operator.vector-evaluator :as vector]
            [eacl.operator.vector-evaluator-test :as vector-test]))

(defn failures [gate]
  (let [events (atom [])]
    (with-redefs [t/report (fn [event] (when (#{:fail :error} (:type event)) (swap! events conj event)))]
      (try (gate)
           (catch Throwable error (swap! events conj {:type :error :actual error}))))
    (count @events)))

(defn mutation-cases []
  (let [qualify qualification/qualify identity qualification/exact-reuse-identity
        fetch reducer/adapter-fetch-fn descriptor scan-cache/descriptor-key
        snapshot-opts @#'orchestration/snapshot-opts
        head-evidence @#'seekable/head-evidence
        count-categories @#'lookup/count-categories
        accepted-emission @#'least-path/accepted-emission
        stream-next @#'least-path/stream-next
        legacy-acceptor @#'least-path/legacy-node-acceptor
        least-env @#'least-path/make-env
        check-many vector/check-cached-many-eids
        check-stable stable-route/check-eids
        validate-stable @#'stable-route/validate-known-witness!
        aggregate batch/aggregate-counters
        collect data/collect
        can? core/can?
        check-evidence engine/check-evidence
        check-result result/check-result
        discovery-options stable-route/discovery-options
        buffer-id @#'reducer/buffer-id
        checkpoint-put stable-page/checkpoint-put!
        page-options @#'stable-page/qualified-page-options
        page-binding stable-page/execution-binding
        count-page-categories @#'engine/count-page-categories
        count-result result/count-result
        request-schema @#'orchestration/request-schema
        enqueue @#'recursive/enqueue-evidence!]
    {:public-counts-ignore-requested-policy
     {:gate #'public-lookup-test/public-qualified-counts-distinguish-conditional-results-and-expiring-bans
      :redefs {#'result/result-policy (constantly :detailed)}}
     :public-counts-drop-conditional-category
     {:gate #'public-lookup-test/public-qualified-counts-distinguish-conditional-results-and-expiring-bans
      :redefs {#'result/count-result
               (fn [value limit policy]
                 (dissoc (count-result value limit policy) :conditional-count))}}
     :recursive-cover-does-not-read-qualified-edges
     {:gate #'public-lookup-test/public-qualified-counts-distinguish-conditional-results-and-expiring-bans
      :redefs {#'engine/structural-cover-fetch clojure.core/identity}}
     :recursive-count-includes-lookahead-category
     {:gate #'public-lookup-test/public-qualified-counts-distinguish-conditional-results-and-expiring-bans
      :redefs {#'engine/count-page-categories
               (fn [categories page _] (count-page-categories categories page nil))}}
     :detailed-count-cache-accepts-inconsistent-categories
     {:gate #'public-lookup-test/detailed-count-cache-ingress-requires-consistent-closed-categories
      :redefs {#'cache/count-answer? (constantly true)}}
     :qualified-checkpoint-accepts-incomplete-evidence
     {:gate #'page-test/incomplete-or-faulty-qualified-checkpoints-fall-back-to-replay
      :redefs {#'stable-page/valid-qualified-checkpoint (fn [_ checkpoint] checkpoint)}}
     :qualified-lookahead-loses-its-evidence
     {:gate #'page-test/pending-qualified-lookahead-needs-no-repeat-probe
      :redefs {#'stable-page/checkpoint-put!
               (fn [store key checkpoint] (checkpoint-put store key (dissoc checkpoint :pending-evidence)))}}
     :qualified-checkpoint-key-omits-request-scope
     {:gate #'page-test/checkpoint-retention-partitions-qualified-request-scope
      :redefs {#'stable-page/qualified-page-options
               (fn [options] (assoc (page-options options) :checkpoint-key (:checkpoint-key options)))}}
     :qualified-standalone-token-omits-request-scope
     {:gate #'page-test/standalone-qualified-tokens-bind-time-context-and-policy
      :redefs {#'stable-page/execution-binding
               (fn [options] (dissoc (page-binding options) :qualification :result-policy))}}
     :discovery-reuses-a-buffer-from-an-earlier-prefix
     {:gate #'discovery-test/temporal-revisions-replay-a-buffered-prefix
      :redefs {#'reducer/buffer-id (fn [item] (buffer-id (dissoc item :revision)))}}
     :discovery-discards-propagated-prefix-evidence
     {:gate #'discovery-test/conditional-prefixes-revisit-cycles-without-duplicate-discoveries
      :redefs {#'reducer/inherit-evidence (fn [_ successors] successors)}}
     :discovery-partial-path-claims-the-whole-root
     {:gate #'discovery-test/conditional-prefixes-revisit-cycles-without-duplicate-discoveries
      :redefs {#'stable-route/discovery-options
               (fn [options direction]
                 (assoc (discovery-options options direction) :candidate-evidence-fn
                        (fn [_ item] (:evidence item true))))}}
     :discovery-omits-retained-evidence-bounds
     {:gate #'discovery-test/weighted-admission-and-resume-keep-operational-bounds
      :redefs {#'reducer/evidence-size (constantly 0)}}
     :discovery-repeats-a-known-direct-tuple
     {:gate #'discovery-test/direct-discovery-witnesses-avoid-repeating-the-known-tuple
      :redefs {#'stable-route/discovery-options
               (fn [options direction]
                 (let [configured (discovery-options options direction)
                       complete (:candidate-evidence-fn configured)]
                   (assoc configured :candidate-evidence-fn
                          (fn [eid item] (complete eid (dissoc item :direct-evidence))))))}}
     :expired-public-request-compiles-all-caveats
     {:gate #'public-point-test/expired-public-points-never-compile-undemanded-caveats
      :redefs {#'orchestration/request-schema
               (fn [& args]
                 (binding [engine/*qualification* nil] (apply request-schema args)))}}
     :public-conditional-result-becomes-a-grant
     {:gate #'result-test/detailed-results-preserve-membership-and-residuals
      :redefs {#'result/check-result (fn [value]
                                       (let [answer (check-result value)]
                                         (if (= :conditional-permission (:permissionship answer))
                                           (assoc answer :allowed? true) answer)))}}
     :boolean-compatibility-erases-operational-errors
     {:gate #'core-test/boolean-compatibility-requires-definite-grants-and-preserves-operational-errors
      :redefs {#'core/can? (fn [& args] (try (apply can? args) (catch Throwable _ false)))}}
     :public-point-routing-omits-qualification
     {:gate #'public-point-test/public-point-routes-preserve-conditional-evidence-and-expiring-bans
      :redefs {#'engine/check-evidence (fn [& args]
                                         (binding [engine/*qualification* nil]
                                           (apply check-evidence args)))}}
     :legacy-inactive-stream-path-becomes-active
     {:gate #'legacy-lookup-test/qualified-unions-keep-native-order-and-complete-node-evidence
      :redefs {#'least-path/stream-next
               (fn [ctx state]
                 (let [[value next-state] (stream-next ctx state)]
                   [value (cond-> next-state
                            (and value (:qualification ctx) (evidence/no? (:evidence next-state)))
                            (assoc :evidence true))]))}}
     :legacy-partial-path-claims-whole-node
     {:gate #'legacy-lookup-test/qualified-unions-keep-native-order-and-complete-node-evidence
      :redefs {#'least-path/legacy-node-acceptor
               (fn [options]
                 (let [accept (legacy-acceptor options)]
                   (fn [candidate]
                     (if-let [witness (:evidence-witness candidate)] (:evidence witness) (accept candidate)))))}}
     :legacy-nil-path-forces-qualified-context
     {:gate #'legacy-lookup-test/nil-qualifier-unions-retain-existing-coordinates-without-context-work
      :redefs {#'least-path/make-env
               (fn [options ctx]
                 (when (:qualification options) (qualification/exact-reuse-identity (:qualification options)))
                 (least-env options ctx))}}
     :stable-known-witness-scope-ignored
     {:gate #'stable-route-test/known-witness-scope-is-validated-before-a-definite-shortcut
      :redefs {#'stable-route/validate-known-witness! :known-witness}}
     :stable-known-path-is-reprobed
     {:gate #'stable-route-test/known-direct-and-child-witnesses-complete-only-remaining-alternatives
      :redefs {#'stable-route/check-eids (fn [options] (check-stable (dissoc options :known-witness)))}}
     :stable-known-arrow-binding-is-reprobed
     {:gate #'stable-route-test/known-arrow-binding-is-not-requalified-or-reprobed
      :redefs {#'stable-route/validate-known-witness!
               (fn [options] (some-> (validate-stable options) (assoc :intermediate -1)))}}
     :qualification-data-outside-aggregate-budget
     {:gate #'qualification-test/qualifier-data-consumes-aggregate-command-and-fact-budgets
      :redefs {#'batch/aggregate-counters
               (fn [& args] (assoc (apply aggregate args)
                                   :commands 0 :fetched-values 0 :allocation-proxy 1))}}
     :qualification-data-drops-assertion-version
     {:gate #'data-test/bounded-data-preserves-unknown-fields-and-same-read-version
      :redefs {#'data/collect (fn [& args] (assoc (apply collect args) :version nil))}}
     :qualification-data-hides-unknown-field
     {:gate #'data-test/bounded-data-preserves-unknown-fields-and-same-read-version
      :redefs {#'data/collect (fn [& args] (update (apply collect args) :entity dissoc :unexpected/field))}}
     :qualification-data-unmetered
     {:gate #'qualification-test/adapter-data-is-shared-and-metered-within-one-request
      :redefs {#'counters/add-fetched-values! (fn ([] nil) ([_] nil))}}
     :qualification-data-refetched-across-roles
     {:gate #'qualification-test/adapter-data-faults-remain-visible-and-do-not-refetch
      :redefs {#'qualification/entity-data (fn [request eid] ((:lookup request) eid))}}
     :qualifier-reference-ignored
     {:gate #'qualification-test/exclusive-expiry-precedes-program-work
      :redefs {#'qualification/qualify (fn [_ _ value] (some? value))}}
     :expiry-boundary-retains-permission
     {:gate #'qualification-test/exclusive-expiry-precedes-program-work
      :redefs {#'qualification/qualify (fn [request relation value]
                                         (qualify (update request :time dec) relation value))}}
     :authoritative-failure-becomes-plain
     {:gate #'qualification-test/authoritative-errors-survive-expiry-and-exclusion
      :redefs {#'qualification/qualify (fn [& args]
                                         (let [result (apply qualify args)]
                                           (if (evidence/fault? result) true result)))}}
     :conditional-becomes-truthy
     {:gate #'native-test/native-compact-scans-and-memos-preserve-qualified-point-evidence
      :redefs {#'evidence/has? (fn [value] (boolean (evidence/value value)))}}
     :fault-becomes-absence
     {:gate #'qualification-test/authoritative-errors-survive-expiry-and-exclusion
      :redefs {#'evidence/fault (fn [_ _] false)}}
     :latest-expiry-replaces-earliest
     {:gate #'evidence-test/temporal-certificate-uses-decisive-evidence
      :redefs {#'evidence/meet (fn [a b] (if (and a b) (max a b) (or a b)))}}
     :expired-evidence-reused
     {:gate #'evidence-test/temporal-certificate-uses-decisive-evidence
      :redefs {#'evidence/reusable? (fn [value start time]
                                      (and (evidence/complete? value) (not (evidence/fault? value)) (<= start time)))}}
     :scan-shape-omitted-from-cache
     {:gate #'scan-test/compact-and-ordinary-responses-never-share-memo-or-resident-prefixes-test
      :redefs {#'scan-cache/descriptor-key (fn [d] (descriptor (dissoc d :include-qualifier?)))}}
     :compact-flag-dropped-before-native-scan
     {:gate #'native-test/native-compact-scans-and-memos-preserve-qualified-point-evidence
      :redefs {#'reducer/adapter-fetch-fn (fn [adapter]
                                            (let [inner (fetch adapter)]
                                              (fn [d] (inner (dissoc d :include-qualifier?)))))}}
     :context-omitted-from-exact-point-scope
     {:gate #'vector-test/qualified-vectors-retain-alignment-and-exact-cache-scope
      :redefs {#'qualification/exact-reuse-identity (fn [request] (assoc (identity request) 3 nil))}}
     :unprojected-request-reaches-each-caveat
     {:gate #'qualification-test/each-caveat-projects-request-fields-without-weakening-bound-context
      :redefs {#'context/project (fn [prepared _] (context/value prepared))}}
     :unused-context-fields-omitted-from-identity
     {:gate #'context-test/whole-context-is-canonical-and-independent-of-one-parameter-set
      :redefs {#'context/identity (constantly "omitted")}}
     :public-context-validation-bypassed
     {:gate #'public-context-test/invalid-context-fails-before-selection-even-on-warm-or-empty-requests
      :redefs {#'context/prepare (let [empty-context (context/prepare {})] (constantly empty-context))}}
     :time-omitted-from-exact-point-scope
     {:gate #'vector-test/qualified-vectors-retain-alignment-and-exact-cache-scope
      :redefs {#'qualification/exact-reuse-identity (fn [request] (assoc (identity request) 2 nil))}}
     :evidence-witness-validation-bypassed
     {:gate #'vector-test/exact-evidence-witnesses-avoid-rechecking-proven-nodes
      :redefs {#'vector/validate-evidence-witnesses! (fn [& _] nil)}}
     :cached-grant-hides-encountered-witness-fault
     {:gate #'vector-test/exact-evidence-witnesses-avoid-rechecking-proven-nodes
      :redefs {#'vector/demanded-witness-fault (constantly nil)}}
     :raw-clock-regresses
     {:gate #'clock-test/client-samples-once-and-snapshots-pin-time
      :redefs {#'clock/clock clojure.core/identity}}
     :snapshot-resamples-time
     {:gate #'clock-test/client-samples-once-and-snapshots-pin-time
      :redefs {#'orchestration/snapshot-opts (fn [runtime basis]
                                               (dissoc (snapshot-opts runtime basis) :evaluation-time-ms))}}
     :conditional-seekable-head-becomes-definite
     {:gate #'seekable-test/direct-specializations-carry-exact-qualified-evidence
      :redefs {#'seekable/head-evidence (fn [cursor]
                                          (let [value (head-evidence cursor)]
                                            (evidence/with-certificate true (evidence/valid-until value)
                                              (evidence/complete? value))))}}
     :seekable-emission-loses-expiry-certificate
     {:gate #'seekable-bridge/direct-page-algebra-and-exhaustive-temporal-certificates
      :redefs {#'seekable/head-evidence (fn [cursor]
                                          (evidence/with-certificate (head-evidence cursor) nil true))}}
     :definite-lookup-includes-conditional-results
     {:gate #'seekable-test/lookup-and-count-project-exact-generator-evidence-without-rechecking
      :redefs {#'lookup/result-policy (constantly :detailed)}}
     :count-categories-include-lookahead-sentinel
     {:gate #'seekable-test/detailed-count-cap-excludes-the-sentinel-from-category-counts
      :redefs {#'lookup/count-categories (fn [categories entries remaining]
                                           (count-categories categories entries
                                                             (when remaining (inc remaining))))}}
     :general-cover-conditional-child-becomes-definite
     {:gate #'lookup-test/general-cover-completes-conditional-nodes-before-emission
      :redefs {#'least-path/accepted-emission
               (fn [env node rule subject resource value coords proof]
                 (accepted-emission env node rule subject resource value coords
                                    (evidence/with-certificate true (evidence/valid-until proof)
                                      (evidence/complete? proof))))}}
     :general-cover-discards-proven-node-evidence
     {:gate #'lookup-test/a-generated-direct-node-is-not-probed-again-by-its-parent
      :redefs {#'vector/check-cached-many-eids
               (fn [options]
                 (check-many (update options :candidates
                                     #(mapv (fn [candidate] (dissoc candidate :evidence-witnesses)) %))))}}
     :arrow-witness-scope-validation-bypassed
     {:gate #'arrow-test/a-known-arrow-binding-is-completed-without-rechecking-its-target
      :redefs {#'scalar/validate-arrow-witness! (fn [_ _ _ witness] witness)}}
     :arrow-rechecks-already-proven-binding
     {:gate #'arrow-test/a-known-arrow-binding-is-completed-without-rechecking-its-target
      :redefs {#'scalar/known-arrow-binding? (constantly false)}}
     :arrow-witness-ignores-joint-residual
     {:gate #'arrow-test/ordered-arrows-compose-whole-child-evidence-and-resume-bindings
      :redefs {#'least-path/path-hit? (fn [_ _ other] (and (some? other) (not (evidence/no? other))))}}
     :arrow-resume-drops-binding-evidence
     {:gate #'arrow-test/ordered-arrows-compose-whole-child-evidence-and-resume-bindings
      :redefs {#'least-path/resume-evidence! (constantly true)}}
     :witness-skips-expired-prefix-before-alternating
     {:gate #'arrow-bridge/expired-prefix-witness-work-is-bounded-by-the-physical-shorter-side
      :redefs {#'least-path/stream-next (fn [ctx state]
                                          (loop [state state]
                                            (let [[value next-state] (stream-next ctx state)]
                                              (if (and value (evidence/no? (get next-state :evidence true)))
                                                (recur next-state) [value next-state]))))}}
     :recursive-membership-stops-before-certificate-convergence
     {:gate #'recursive-bridge/qualified-positive-scc-refinement-and-temporal-stability
      :redefs {#'recursive/enqueue-evidence!
               (fn [state head value limits counters]
                 (if (= (evidence/value value) (evidence/value (get (:facts state) head false)))
                   state
                   (enqueue state head value limits counters)))}}}))

(deftest production-mutations-are-killed-by-conformance-gates
  (let [cases (mutation-cases)]
    (is (= 59 (count cases)))
    (doseq [[id {:keys [gate redefs]}] (sort-by key cases)]
      (is (zero? (failures gate)) (str id " unmodified gate must pass"))
      (is (pos? (with-redefs-fn redefs #(failures gate))) (str id " must be detected")))))
