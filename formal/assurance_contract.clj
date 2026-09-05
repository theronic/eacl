(ns formal.assurance-contract
  "Authored release-assurance policy.

  This file contains human decisions: which public operations are covered by
  which formal models, the residual adapter obligations, proof-count ratchets,
  and the obligations that keep verified status withheld. Current proof
  results, source closure, digests, benchmark observations, and pass/fail state
  are generated under target/formal and are deliberately absent here.")

(def theorem-policies
  {:caveat-qualifier-foundation
   {:sources ["formal/dafny/CaveatOutcomes.dfy"
              "formal/dafny/CaveatProfile.dfy"
              "formal/dafny/CaveatSchema.dfy"
              "formal/dafny/QualifierLifecycle.dfy"]
    :claim :proof-only-typed-profile-partial-outcomes-and-atomic-qualifier-lifecycle
    :minimum-proof-efforts 78}
   :abstract-operator-engine-phase-a
   {:sources
    ["formal/dafny/PermissionSetAlgebra.dfy"
     "formal/dafny/SignedDependencyStratification.dfy"
     "formal/dafny/CandidateCover.dfy"
     "formal/dafny/WitnessPredicate.dfy"
     "formal/dafny/VectorPredicate.dfy"
     "formal/dafny/AdaptiveBatching.dfy"
     "formal/dafny/OperatorLeastPath.dfy"
     "formal/dafny/SeekableSetKernels.dfy"
     "formal/dafny/DensityBoundedBatch.dfy"
     "formal/dafny/AnchorGatedConjunction.dfy"
     "formal/dafny/StratifiedExclusion.dfy"
     "formal/dafny/OperatorCacheRefinement.dfy"
     "formal/dafny/ExpressionPlanRefinement.dfy"
     "formal/dafny/OperatorGeneratedPolicy.dfy"
     "formal/dafny/OperatorGeneratedPolicyRefinement.dfy"
     "formal/dafny/OperatorProofKernel.dfy"]
    :claim
    :conditional-finite-stratified-set-algebra-cover-predicate-batching-pagination-recursion-and-cache-refinement
    :minimum-proof-efforts 533}
   :abstract-snapshot-oracle
   {:source "formal/dafny/SnapshotOracle.dfy"
    :claim :conditional-interface-contract
    :minimum-proof-efforts 16}
   :basis-first-cache
   {:source "formal/dafny/CurrentCache.dfy"
    :claim :conditional-basis-first-cache-refinement
    :minimum-proof-efforts 32}
   :cache-cursor-foundation
   {:source "formal/dafny/TemporalSafety.dfy"
    :minimum-proof-efforts 24}
   :complete-public-engine
   {:claim
    :conditional-composition-of-generated-authority-and-source-specializations-under-documented-tcb
    ;; The 2026-08-31 ConsistencyDecision.dfy revision retired one obligation;
    ;; the locked whole-tree run verifies 9384.
    :minimum-proof-efforts 9384}
   :cursor-codec-cost-model
   {:source "formal/dafny/CursorCost.dfy"
    :claim :conditional-operation-count-bound
    :minimum-proof-efforts 7}
   :direct-and-acyclic-engine
   {:source "formal/dafny/AcyclicEngine.dfy"
    :minimum-proof-efforts 95}
   :filtered-pagination
   {:source "formal/dafny/FilteredPagination.dfy"
    :claim :conditional-filter-window-sentinel-budget-and-deadline-model
    :minimum-proof-efforts 16}
   :indexed-recursive-public-engine
   {:sources
    ["formal/dafny/IndexedTraversal.dfy"
     "formal/dafny/IndexedBatching.dfy"
     "formal/dafny/IndexedBatchCompleteness.dfy"
     "formal/dafny/IndexedRefinement.dfy"
     "formal/dafny/IndexedForwardCompleteness.dfy"
     "formal/dafny/IndexedReverseCompleteness.dfy"
     "formal/dafny/IndexedRendering.dfy"
     "formal/dafny/IndexedCertification.dfy"
     "formal/dafny/RootDenotation.dfy"
     "formal/dafny/IndexedRootDenotation.dfy"]
    :claim :conditional-least-fixed-point-and-traversal-order-refinement
    :minimum-proof-efforts 7985}
   :normalized-execution-contract
   {:source "formal/dafny/ExecutionContract.dfy"
    :claim :proof-only-host-control-model-cross-checked-against-production
    :minimum-proof-efforts 23}
   :operator-engine-phase-b
   {:sources
    ["formal/dafny/OperatorRecursiveGeneratedPolicy.dfy"
     "formal/dafny/OperatorRecursiveGeneratedPolicyRefinement.dfy"]
    :claim :conditional-generated-command-and-executable-production-refinement
    :minimum-proof-efforts 35}
   :ordered-merge
   {:source "formal/dafny/OrderedMerge.dfy"
    :minimum-proof-efforts 82}
   :pagination-and-cursor-kernel
   {:source "formal/dafny/PageWindow.dfy"
    :minimum-proof-efforts 40}
   :permission-tree-expansion
   {:source "formal/dafny/PermissionTree.dfy"
    :claim :conditional-shallow-tree-topology-cycle-and-limit-model
    :minimum-proof-efforts 62}
   :recursive-engine
   {:source "formal/dafny/RecursiveEngine.dfy"
    :minimum-proof-efforts 73}
   :recursive-engine-cost-model
   {:source "formal/dafny/SchemaPlanCost.dfy"
    :claim :conditional-operation-count-bound
    :minimum-proof-efforts 55}
   :recursive-routing-certificate
   {:source "formal/dafny/RoutingCertificate.dfy"
    :claim :conditional-exact-path-derivation-and-linear-logical-checker-work
    :minimum-proof-efforts 52}
   :semantic-foundation
   {:source "formal/dafny/Semantics.dfy"
    :minimum-proof-efforts 21}
   :snapshot-consistency-decision
   {:source "formal/dafny/ConsistencyDecision.dfy"
    :claim :conditional-finite-selection-decision-and-logical-call-bound
    :minimum-proof-efforts 20}
   :speculative-cache-coherence
   {:source "formal/dafny/SpeculativeCacheCoherence.dfy"
    :claim :conditional-provenance-publication-and-cursor-isolation
    :minimum-proof-efforts 11}
   :strict-wire-format
   {:source "formal/dafny/WireFormat.dfy"
    :minimum-proof-efforts 10}
   :subproblem-cache
   {:source "formal/dafny/SubproblemCache.dfy"
    :claim :conditional-exact-and-managed-atomic-projection-refinement
    :minimum-proof-efforts 73}})

(def operation-contracts
  [{:operation :staged-caveat-qualifier-foundation
    :entry-points ['eacl.relationships.qualifier/normalize
                   'eacl.relationships.qualifier/decode
                   'eacl.caveats.values/encode-context
                   'eacl.caveats.values/decode-context
                   'eacl.caveats.plan/compile-plan
                   'eacl.caveats.plan/decode-plan
                   'eacl.caveats.partial/evaluate
                   'eacl.caveats.evaluator/evaluate
                   'eacl.caveats.jvm/evaluator
                   'eacl.relationships.qualifier-integrity/proof-input
                   'eacl.relationships.qualifier-integrity/report
                   'eacl.relationships.staged/prepare!
                   'eacl.relationships.staged/plan-current
                   'eacl.relationships.staged/cleanup!]
    :theorems [:atomic-qualifier-pair-publication
               :prepared-qualifiers-have-no-authorization-effect
               :immutable-single-owner-qualifier-replacement
               :non-nil-missing-qualifier-is-a-fault
               :schema-generation-cas-and-retained-caveat-references
               :bound-context-overrides-request-context
               :four-valued-logical-composition
               :typed-profile-and-bounded-progress]
    :dafny ["formal/dafny/CaveatOutcomes.dfy"
            "formal/dafny/CaveatProfile.dfy"
            "formal/dafny/CaveatSchema.dfy"
            "formal/dafny/QualifierLifecycle.dfy"]
    :adapter-obligations [:native-nested-ref-publication
                          :immutable-snapshot-and-qualifier-history-evidence
                          :canonical-context-and-plan-encoding
                          :bounded-cel-value-and-error-conversion]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining [:phase-2-production-refinement
                :phase-3-serving-activation
                :independent-review]}
   {:operation :execution-contract
    :entry-points
    ['eacl.execution/normalize 'eacl.engine.v8/lookup-resources]
    :theorems
    [:deadline-dominates-quantum-and-adapter-boundaries
     :demand-page-stops-at-n-plus-one
     :bounded-count-stops-at-l-plus-one
     :complete-evaluation-ignores-demand-sentinels
     :positive-point-demand-stops-when-target-derived
     :every-execution-boundary-forwards-identical-traversal-limits]
    :dafny
    ["formal/dafny/ExecutionContract.dfy"
     "formal/dafny/IndexedTraversal.dfy"]
    :adapter-obligations
    [:monotonic-clock
     :immutable-normalized-request
     :normalized-traversal-limit-transport-through-every-backend-facade
     :adapter-command-boundary-check]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:mechanized-host-control-source-refinement
     :trusted-monotonic-clock-platform-contract
     :independent-review]}
   {:operation :expand-permission-tree
    :entry-points ['eacl.permission-tree/expand]
    :theorems
    [:tree-node-oneof-and-annotation-well-formedness
     :direct-leaf-exactness
     :union-denotation-and-child-permutation-invariance
     :absent-resource-empty-topology
     :active-path-cycle-rejection
     :budget-addition-monotonicity
     :successful-limit-preservation
     :failure-carries-no-partial-tree
     :typed-object-identity
     :sum-typed-relation-declaration-exactness
     :every-emitted-child-consumes-depth]
    :dafny ["formal/dafny/PermissionTree.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :complete-and-well-formed-normalized-schema
     :complete-direct-relationship-scans
     :typed-identity-round-trip
     :selected-snapshot-rendering
     :selected-snapshot-causal-token]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:adapter-query-and-codec-source-refinement
     :deadline-and-host-integer-platform-contracts
     :causal-token-authentication
     :independent-review]}
   {:operation :can?
    :entry-points ['eacl.core/can?]
    :theorems
    [:authorization-membership-iff
     :permission-path-materialization-refines-raw-typed-definitions
     :direct-grant-positive-is-sound
     :direct-grant-exhaustive-is-complete
     :acyclic-recursion-guard-performs-zero-path-work
     :acyclic-outer-path-fold-short-circuits-in-source-order
     :acyclic-outer-path-and-callback-counts-are-linear
     :acyclic-arrow-source-control-refines-full-far-side-evaluation
     :acyclic-arrow-full-candidate-checks-are-linear
     :public-can-root-classification-hoist-preserves-result
     :public-generated-can-performs-one-root-classification
     :reverse-transition-semantic-soundness
     :reverse-least-fixed-point-completeness
     :completed-boolean-render-read-determinism
     :limit-fails-closed
     :one-recursive-plan-compilation-per-root-generation]
    :dafny
    ["formal/dafny/AcyclicEngine.dfy"
     "formal/dafny/RecursiveEngine.dfy"
     "formal/dafny/RoutingCertificate.dfy"
     "formal/dafny/SchemaPlanCost.dfy"
     "formal/dafny/Semantics.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :identity-round-trip
     :schema-completeness
     :direct-scan-equivalence]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:acyclic-direct-callback-semantic-refinement
     :acyclic-optimized-clojure-language-refinement-and-independent-review
     :backend-permission-path-to-indexed-routing-edge-source-refinement
     :independent-review]}
   {:operation :lookup
    :entry-points ['eacl.core/lookup-resources 'eacl.core/lookup-subjects]
    :theorems
    [:forward-projection-exact
     :reverse-projection-exact
     :unique-deterministic-sequence
     :ordered-merge-step-refines-definition
     :bounded-merge-chunk-reconstructs-complete-merge
     :balanced-fold-merge-preserves-order-and-complete-union
     :production-merge-control-refines-canonical-merge
     :production-balanced-fold-preserves-order-and-complete-union
     :production-two-stream-merge-comparisons-are-linear
     :relationship-keyset-page-decision-exact
     :limit-fails-closed
     :bounded-page-stream-prefetch
     :one-recursive-plan-compilation-per-root-generation]
    :dafny
    ["formal/dafny/AcyclicEngine.dfy"
     "formal/dafny/OrderedMerge.dfy"
     "formal/dafny/PageWindow.dfy"
     "formal/dafny/RecursiveEngine.dfy"
     "formal/dafny/RoutingCertificate.dfy"
     "formal/dafny/SchemaPlanCost.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :identity-round-trip
     :schema-completeness
     :ordered-complete-scans
     :permission-node-completeness]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:acyclic-optimized-clojure-language-refinement-and-independent-review
     :backend-permission-path-to-indexed-routing-edge-source-refinement
     :independent-review]}
   {:operation :count
    :entry-points ['eacl.core/count-resources 'eacl.core/count-subjects]
    :theorems
    [:count-equals-lookup-cardinality
     :bounded-count-law
     :limit-fails-closed
     :one-recursive-plan-compilation-per-root-generation]
    :dafny
    ["formal/dafny/AcyclicEngine.dfy"
     "formal/dafny/RecursiveEngine.dfy"
     "formal/dafny/RoutingCertificate.dfy"
     "formal/dafny/SchemaPlanCost.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :identity-round-trip
     :schema-completeness
     :ordered-complete-scans]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:acyclic-optimized-clojure-language-refinement-and-independent-review
     :backend-permission-path-to-indexed-routing-edge-source-refinement
     :independent-review]}
   {:operation :snapshot-consistency-selection
    :entry-points
    ['eacl.consistency/selection-plan
     'eacl.consistency/select-from-source
     'eacl.consistency/select
     ;; Host-native production authority; the generated ConsistencyDecision
     ;; model below remains its offline differential oracle.
     'eacl.engine.portable-decisions/decide]
    :theorems
    [:unsupported-exact-plan-is-exact-snapshot-unavailable
     :at-least-acceptance-requires-ancestor
     :exact-acceptance-requires-pinned-graph
     :present-malformed-selection-is-not-snapshot-absence
     :selection-plan-needs-no-acquisition-or-validation
     :response-token-uses-closed-selected-identity
     :selected-current-has-one-selection-without-duplicate-validation
     :successful-selection-logical-work-is-constant-bounded]
    :dafny
    ["formal/dafny/ConsistencyDecision.dfy"
     "formal/dafny/SnapshotOracle.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :same-source-selection
     :authoritative-or-fails-closed
     :at-least-anchor-completeness
     :exact-locator-and-snapshot-identity]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:backend-selection-source-refinement
     :authenticated-token-decoder-refinement
     :independent-review]}
   {:operation :lookup-cursor-continuation
    :entry-points ['eacl.client.orchestration/cursor-options 'eacl.relay]
    :theorems
    [:cursor-scope-before-influence
     :single-permitted-graph
     :cursor-minting-does-no-relationship-content-scan
     :no-cache-cursor-minting-does-no-generation-scan
     :automatic-current-requests-use-ordered-generation-proofs
     :current-continuation-requires-equal-lineage-frame-and-closure
     :equal-scalar-frame-preserves-every-closure-slice
     :sealed-plan-descriptors-stay-inside-certified-closure
     :equal-closure-slices-preserve-adaptive-stream-and-boundaries
     :least-path-resume-is-exact-suffix-or-reverse-prefix
     :stable-boundary-resume-is-exact-residual
     :exact-continuation-requires-authenticated-original-basis-identity
     :changed-frame-never-continues-on-current
     :changed-frame-without-exact-selection-is-rejected
     :datascript-exact-frame-continues-only-at-current-basis
     :datascript-changed-basis-cannot-yield-cursor-page]
    :dafny
    ["formal/dafny/PageWindow.dfy"
     "formal/dafny/TemporalSafety.dfy"
     "formal/dafny/ScalarFrontierCoherence.dfy"
     "formal/dafny/CursorCost.dfy"
     "formal/stable-discovery/ReducerReadScope.dfy"
     "formal/stable-discovery/LeastPathResume.dfy"
     "formal/stable-discovery/PaginationComposition.dfy"
     "formal/stable-discovery/RuntimeCheckpointComposition.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :exact-selection
     :source-fingerprint
     :exact-snapshot-identity-collision-resistance
     :ordered-generation-proof-completeness
     :complete-dependency-and-order-proof]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:mechanized-host-cursor-proof-strategy-source-refinement
     :backend-proof-construction-refinement
     :independent-review]}
   {:operation :frame-keyed-checkpoint-resume
    :entry-points
    ['eacl.engine.v8/checkpoint-key
     'eacl.engine.stable-page/state-at-boundary
     'eacl.engine.stable-page/checkpoint-put!
     'eacl.engine.stable-reducer/history-free
     'eacl.engine.stable-reducer/resume]
    :theorems
    [:checkpoint-is-history-free-state-plus-undelivered-lookahead
     :checkpoint-resume-equals-same-basis-replay
     :sealed-plan-descriptors-stay-inside-certified-closure
     :equal-scalar-frame-preserves-every-closure-slice
     :equal-frame-resume-preserves-next-page-state-boundary-and-resource-outcome
     :native-revision-is-not-checkpoint-semantic-identity
     :visited-public-pages-remain-exact-basis-keyed]
    :dafny
    ["formal/stable-discovery/ReducerCheckpoint.dfy"
     "formal/stable-discovery/RuntimeCheckpointComposition.dfy"
     "formal/stable-discovery/ReducerReadScope.dfy"
     "formal/dafny/ScalarFrontierCoherence.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :durable-source-lineage-truthfulness
     :supported-writer-ordered-generation-stamping
     :ordered-generation-proof-completeness
     :deterministic-complete-exclusive-scans]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:mechanized-host-checkpoint-key-source-refinement
     :backend-proof-construction-refinement
     :independent-review]}
   {:operation :acyclic-frontier-alias-canonicalization
    :entry-points ['eacl.engine.sealed-plan/derive-execution-frontier]
    :theorems
    [:canonical-permission-alias-preserves-denotation
     :canonical-frontier-deduplication-cannot-add-traversal-streams
     :canonical-frontier-sequence-is-unique-and-keeps-first-path]
    :dafny ["formal/dafny/SchemaPlanCost.dfy"]
    :adapter-obligations
    [:complete-permission-body-materialization
     :same-resource-self-permission-semantics
     :stable-first-occurrence-frontier-order]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:mechanized-host-alias-canonicalization-source-refinement
     :backend-permission-body-materialization-refinement
     :independent-review]}
   {:operation :relationship-pagination
    :entry-points
    ['eacl.engine.relationships/execute-page
     'eacl.engine.relationships/execute-filtered-window
     'eacl.engine.v8/execute-filtered-lookup-window
     'eacl.relay/externalize-relationship-page]
    :theorems
    [:relationship-keyset-page-decision-exact
     :single-permitted-graph
     :matching-relationship-page-scope-reuses-exact-page
     :relationship-page-scope-mismatch-cannot-hit
     :arbitrary-window-concatenation-is-exact
     :unbounded-has-next-is-exact
     :deadline-cut-publishes-no-page]
    :dafny
    ["formal/dafny/PageWindow.dfy"
     "formal/dafny/FilteredPagination.dfy"
     "formal/dafny/TemporalSafety.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :ordered-complete-scans
     :exact-selection
     :source-fingerprint]
    :runtime-targets [:clj-java :cljs-javascript]}
   {:operation :recursive-scc-routing
    :entry-points ['eacl.engine.sealed-plan/seal-plan]
    :theorems
    [:typed-permission-dependency-edge-identity
     :routing-step-monotonicity
     :routing-least-fixed-point-reachability
     :routing-strong-component-exactness
     :singleton-self-edge-is-recursive
     :acyclic-ancestors-of-recursive-components-are-routed
     :accepted-certificate-component-partition-is-exact
     :accepted-certificate-recursion-witness-is-exact
     :accepted-certificate-traversal-vector-is-sound-and-complete
     :accepted-path-descriptors-derive-the-exact-indexed-edge-vector
     :accepted-certificate-checker-performs-exactly-one-path-pass-two-node-passes-and-one-edge-pass]
    :dafny
    ["formal/dafny/RecursiveEngine.dfy"
     "formal/dafny/RoutingCertificate.dfy"]
    :adapter-obligations
    [:complete-permission-node-enumeration
     :complete-materialized-permission-dependencies
     :full-resource-type-and-permission-node-identity]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:backend-permission-path-materialization-source-refinement
     :proofless-and-raw-snapshot-generated-authority
     :independent-host-source-refinement-review
     :allocation-retained-heap-and-latency-platform-contracts]}
   {:operation :indexed-traversal-transition
    :entry-points
    ['eacl.verified-kernel/initialize-indexed
     'eacl.verified-kernel/drive-indexed
     'eacl.verified-kernel/resume-indexed
     'eacl.verified-kernel/continue-indexed-page
     'eacl.verified-kernel/read-indexed-result]
    :theorems
    [:malformed-indexed-scan-response-rejected
     :lifecycle-local-request-identity
     :pending-traversal-scope-and-local-request-identity
     :data-valued-continuation-state
     :fifo-forward-transition-invariant
     :fifo-reverse-transition-invariant
     :exact-object-and-relation-eid-catalogs
     :exact-ordered-chunk-adapter-contract
     :compiled-indexed-rule-soundness
     :forward-transition-semantic-soundness
     :reverse-transition-semantic-soundness
     :forward-least-fixed-point-completeness
     :reverse-least-fixed-point-completeness
     :bounded-ordered-scan-waves
     :fuel-cut-wave-publishes-current-progress-without-request-loss
     :pending-scan-ghost-view-is-exact
     :batched-crossing-law
     :deterministic-render-prefix-independent-of-chunk-boundary
     :identical-complete-denotations-render-identically
     :page-continuation-authenticates-ordinal-and-eid
     :page-continuation-preserves-semantic-frontier
     :page-continuation-carries-lookahead-without-prefix-replay
     :source-plan-certification-implies-exact-compiled-rules
     :seed-certification-implies-exact-forward-seed-bucket
     :equal-semantic-root-rule-bodies-have-equal-fixed-point-grants
     :equal-indexed-root-rule-bodies-refine-equal-semantic-root-bodies
     :instantaneous-queue-depth-bound
     :unique-grant-bound
     :dimensionally-separated-resource-counters]
    :dafny
    ["formal/dafny/IndexedTraversal.dfy"
     "formal/dafny/IndexedBatching.dfy"
     "formal/dafny/IndexedBatchCompleteness.dfy"
     "formal/dafny/IndexedRefinement.dfy"
     "formal/dafny/IndexedForwardCompleteness.dfy"
     "formal/dafny/IndexedReverseCompleteness.dfy"
     "formal/dafny/IndexedRendering.dfy"
     "formal/dafny/IndexedCertification.dfy"
     "formal/dafny/RootDenotation.dfy"
     "formal/dafny/IndexedRootDenotation.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :ordered-complete-exclusive-chunks
     :identity-round-trip
     :compiled-plan-refinement]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining [:production-adapter-source-refinement :independent-review]}
   {:operation :abstract-operator-engine-phase-a
    :entry-points
    ["EaclKernel.__default/DecideOperatorBatch"
     "EaclKernel.__default/DecideOperatorSignedGraph"
     "formal/smoke/clj/eacl/formal/java_operator_decision.clj"
     "formal/smoke/cljs/eacl/formal/operator_decision_test.cljs"]
    :theorems
    [:finite-typed-stratified-expression-semantics
     :exact-expression-table-to-plan-denotation
     :executable-exact-scc-certificate-and-canonical-negative-cycle-rejection
     :recursive-candidate-cover-containment-and-exact-emission
     :exact-derivation-scoped-witness-predicates
     :aligned-atomic-mask-schedule-and-vector-refinement
     :bounded-adaptive-batching
     :logical-cursor-suffix-equivalence
     :anchor-preserving-max-head-k-way-seekable-sequence-refinement
     :demand-stopping-k-way-prefix-and-dimensional-seek-bounds
     :density-bounded-prefix-selection
     :arrival-independent-operational-anchor-gated-recursive-conjunction
     :completed-lower-stratum-exclusion
     :complete-signed-snapshot-projection-generation-cache-refinement
     :generated-policy-refinement]
    :dafny
    ["formal/dafny/PermissionSetAlgebra.dfy"
     "formal/dafny/SignedDependencyStratification.dfy"
     "formal/dafny/CandidateCover.dfy"
     "formal/dafny/WitnessPredicate.dfy"
     "formal/dafny/VectorPredicate.dfy"
     "formal/dafny/AdaptiveBatching.dfy"
     "formal/dafny/OperatorLeastPath.dfy"
     "formal/dafny/SeekableSetKernels.dfy"
     "formal/dafny/DensityBoundedBatch.dfy"
     "formal/dafny/AnchorGatedConjunction.dfy"
     "formal/dafny/StratifiedExclusion.dfy"
     "formal/dafny/OperatorCacheRefinement.dfy"
     "formal/dafny/ExpressionPlanRefinement.dfy"
     "formal/dafny/OperatorGeneratedPolicy.dfy"
     "formal/dafny/OperatorGeneratedPolicyRefinement.dfy"
     "formal/dafny/OperatorProofKernel.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :strictly-ordered-unique-eid-scans
     :inclusive-reseek
     :complete-signed-dependency-generations
     :atomic-vector-response-or-failure
     :truthful-dimensional-work-counters]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:production-per-node-lower-stratum-fact-context-stability-refinement
     :production-expression-to-recursive-join-rule-refinement
     :production-parser-codec-and-storage-refinement
     :production-plan-evaluator-cache-and-backend-refinement
     :production-performance-acceptance]}
   {:operation :operator-engine-phase-b
    :entry-points
    ["EaclKernel.__default/DecideOperatorRecursiveCommand"
     "eacl.operator.plan/seal-plan"
     "eacl.operator.evaluator/check-eids"
     "eacl.operator.vector-evaluator/check-cached-many-eids"
     "eacl.operator.lookup/lookup-page"
     "eacl.operator.recursive/evaluate-cached-many"]
    :theorems
    [:generated-typed-fact-command-authority
     :generated-intersection-slot-to-anchor-model-refinement
     :generated-completed-negative-to-stratified-exclusion-refinement
     :generated-incomplete-negative-fails-closed
     :typed-fact-identity-separation]
    :dafny
    ["formal/dafny/OperatorRecursiveGeneratedPolicy.dfy"
     "formal/dafny/OperatorRecursiveGeneratedPolicyRefinement.dfy"]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining []}
   {:operation :cache-reuse
    :entry-points ['eacl.cache 'eacl.subproblem-cache]
    :theorems
    [:exact-basis-hit-is-same-selected-basis
     :exact-only-hit-requires-available-entry
     :exact-only-miss-computes-without-managed-fallback
     :numeric-revision-alone-cannot-establish-exact-identity
     :managed-atomic-relation-projection-frame
     :managed-source-schema-scalar-frontier-key-separation
     :equal-scalar-proof-preserves-every-deterministic-denotation
     :equal-scalar-proof-also-preserves-an-older-selected-snapshot
     :changed-relation-slice-requires-changed-stamp
     :forward-scalar-stamp-invalidation
     :late-publication-unreachable
     :inadmissible-basis-bypass-and-complete-identity-admission
     :projection-chunk-concatenation
     :equal-root-rule-bodies-have-equal-semantic-denotations
     :acyclic-subproblem-call-stack-independence
     :partial-recursive-publication-rejection
     :subproblem-weight-bound
     :subproblem-represented-candidate-bound
     :incomplete-candidate-computes-independently
     :request-owned-miss-computation
     :bounded-best-effort-publication
     :compatible-publication-winner-retained
     :linearized-lifecycle-lookup-and-publication-selection]
    :dafny
    ["formal/dafny/CurrentCache.dfy"
     "formal/dafny/NativeGenerationCoherence.dfy"
     "formal/dafny/SpeculativeCacheCoherence.dfy"
     "formal/dafny/RootDenotation.dfy"
     "formal/dafny/IndexedRootDenotation.dfy"
     "formal/dafny/ScalarFrontierCoherence.dfy"
     "formal/dafny/SubproblemCache.dfy"
     "formal/dafny/TemporalSafety.dfy"]
    :adapter-obligations
    [:immutable-snapshot
     :truthful-complete-exact-basis-key
     :complete-source-scope-plus-lifecycle-lineage
     :fresh-source-id-per-non-durable-live-source
     :durable-history-when-advertised
     :targeted-sync-and-exact-as-of-effects
     :provider-future-cancellation
     :complete-compiled-dependencies
     :proof-generations-share-native-revision-domain
     :proof-generations-at-or-below-selected-revision
     :atomic-relation-stamped-writer-contract
     :selected-snapshot-rendering
     :source-fingerprint
     :request-owned-computation-accounting]
    :runtime-targets [:clj-java :cljs-javascript]}
   {:operation :generated-conversion-boundary
    :entry-points
    ['eacl.formal.production-kernel/GeneratedJavaKernel
     'eacl.formal.production-kernel-cljs/default-selection]
    :theorems
    [:strict-boundary-variant-validation
     :safe-integer-validation
     :bounded-collection-validation
     :complete-portable-error-comparison]
    :converter-categories
    '{:schema-ir
     [object->dafny dafny-object->object permission-node relation-node
      rule-definition]
     :relationships [relationship->dafny]
     :queries
     [authorization-inputs traversal-limits page-presence indexed-render-mode]
     :adapter-callbacks
     [indexed-projection indexed-scan-decision indexed-rule relation-binding
      indexed-plan-decision indexed-seed-decision indexed-limits
      indexed-projection-value indexed-command-value indexed-counters-value
      compile-indexed-plan indexed-init indexed-drive indexed-continue-page
     indexed-resume]
     :cache-and-cursors
     [exact-selection continuation-decision]
     :results
     [work-counters sequence-outcome boolean-outcome count-outcome
      authorization-outcome page-decision keyset-page-decision
      consistency-plan-decision consistency-selection-decision
      ordered-merge-decision ordered-merge-chunk indexed-public-result]
     :typed-errors
     [limit-kind page-error consistency-error indexed-scan-rejection-reason
      indexed-plan-rejection-reason indexed-limit-kind indexed-render-error]}
    :runtime-sources
    {:clj-java "modules/eacl/src/eacl/formal/production_kernel.clj"
     :cljs-javascript
     "formal/smoke/cljs/eacl/formal/production_kernel_js.cljs"}
    :boundary-invariants
    [:input-validation :result-validation :unknown-field-rejection
     :safe-integer-validation :bounded-collection-validation
     :complete-portable-error-comparison]
    :dafny
    ["formal/dafny/EaclKernel.dfy"
     "formal/dafny/WireFormat.dfy"]
    :adapter-obligations
    [:generated-code-compiler-correctness
     :host-ffi-conversion-correctness
     :runtime-integer-semantics]
    :runtime-targets [:clj-java :cljs-javascript]
    :remaining
    [:formal-proof-of-clojure-and-clojurescript-conversion-source
     :runtime-and-compiler-correctness]}])

(def release-policy
  {:assurance-status :conditionally-verified
   :verified-status-allowed? false
   :external-certification
   {:status :unsigned
    :procedure "formal/verification/external-certifier-procedure.md"}
   :unmet-required-obligations
   [:mechanized-host-control-source-refinement
    :mechanized-clj-cache-transition-source-refinement
    :mechanized-cljs-production-authority-refinement
    :mechanized-backend-adapter-conversion-refinement
    :independent-security-formal-review]
   :residual-assumptions
   [:verification-toolchain
    :generated-code-compilers
    :clj-cljs-runtimes
    :host-source-specializations
    :ffi-boundaries
    :adapter-contract
    :cryptography
    :canonicalization
    :hash-collision-resistance
    :entropy-and-key-management
    :clocks
    :authenticated-snapshot-selection-facts
    :configured-limits]})

(def contract
  {:schema-version 1
   :semantics-version 1
   :theorem-policies theorem-policies
   :operation-contracts operation-contracts
   :release-policy release-policy})

contract
