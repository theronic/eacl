# EACL formal verification

This tree contains the source of EACL's verification evidence. It does not
claim that an operation is verified merely because a tool ran successfully.
Claims are scoped by the model, its production mapping, executable differential
tests, and the trusted-boundary documentation.

## Layout

- `stable-discovery/` contains the semantic models, executable refinement
  bridges, and randomized refinement campaign for the shipped enumeration
  engine.
- `dafny/` contains the executable mathematical semantics, the generated
  decision kernels, and proof lemmas. The `AcyclicEngine`, `RecursiveEngine`,
  `OrderedMerge`, `RoutingCertificate`, `CursorCost` and `Indexed*` leaves
  model the engines the stable-discovery engine replaced; they remain
  verified regression models until the formal cut recorded as task 9.2 of
  `openspec/changes/adopt-stable-discovery-enumeration/tasks.md`.
- `tla/` contains bounded temporal models used to discover hostile cache,
  cursor, snapshot, continuation, subproblem-publication, proof-frame, and
  source-switch histories. `EaclCacheStorage.tla` is the consolidated
  partial-map cache authority. Apalache checks bounded histories and retained
  partial-publication, partial-hit fail-open,
  detached-store-publication, retired-store-identity ABA, and
  managed-proof-bypass controls.
  `EaclOperatorSafety.tla` adds the abstract operator
  publication, logical-cursor, checkpoint, cache-lifecycle, and completed
  negative-premise histories.
- `counterexamples/` retains minimized witnesses and their bug ledger.
- `verification/` contains the production decision inventory, trusted
  boundary, temporal-model scope, and review notes. Generated pass/fail
  ledgers live under ignored `target/formal/verification/`.
- `verification/temporal-model.md` records the detailed transition scope,
  bounded configurations, induction obligations, and claim boundary.
- `smoke/` contains handwritten boundary programs that exercise generated
  Java and JavaScript.

Generated sources, binaries, solver output, and downloaded tools live under
ignored `target/`. They are reproducible build output and must never be edited
as source.

## Commands

Run `bin/formal bootstrap` once, then the individual verification/build/model
commands listed by `bin/formal`. `bin/formal all` runs the semantic proofs,
generated-runtime builds, source inventory, bounded temporal checks, and
negative controls. It does not run release manifests, generated-byte checks,
or exhaustive global exploration. The larger bounded temporal campaign is
available as `bin/formal apalache-scheduled`.

`dafny/NativeGenerationCoherence.dfy` supersedes mutation-graph ancestry as
the managed-cache coherence argument. It proves the forward-history frame from
physical schema/relation generations, including empty dependency closures,
component-safe deletion, stale endpoint guards, and source-lifecycle
isolation. Older graph-oriented temporal artifacts remain bounded legacy
regression models; they are not authority for the v4 cache or token protocol.

`dafny/ScalarFrontierCoherence.dfy` refines that complete relation-generation
frame to a scalar dependency frontier. It retains the independently monotone
maximum collision as a checked counterexample, then proves soundness under the
stronger supported-writer obligation that every affected relation is stamped
atomically with the globally later committed transaction. The proof also binds
the frontier to deterministic complete dependency extraction, immutable
adapter/lifecycle-scoped proof frames, normalized demand, completed-only
publication, exact fallback for incomplete evidence, and selected-snapshot
identity. Backend certification remains the trusted boundary for native
transaction ordering and atomic stamp publication.

`dafny/PermissionTree.dfy` is the proof-only shallow permission-tree model. It
defines typed object identity, normalized relation/permission components,
annotated leaf/intermediate nodes, active-path cycle state, structural budgets,
and success-or-error outcomes. Its 62 locked proof obligations cover node
oneof/annotation well-formedness, exact direct leaves, union denotation and
child permutation invariance, absent-resource topology, active-path rejection,
budget monotonicity, successful limit preservation, failure non-publication,
type-preserving identity, sum-typed relation declarations, and emitted-child
depth accounting. Run `bin/formal format` and `bin/formal verify`;
the aggregate report is `target/formal/dafny-verification.json`.

This model is not mechanically extracted into production. The corresponding
handwritten source is `modules/eacl/src/eacl/permission_tree.cljc`; bounded
reference/property tests are in
`modules/eacl/test/eacl/permission_tree_test.cljc` and
`modules/eacl-datascript/test/eacl/permission_tree_operator_test.cljc`, backend contracts in
`modules/eacl/test/eacl/contract_support.cljc`, and the pinned upstream fixture
in `formal/fixtures/permission-tree/`. Immutable/complete adapter reads,
identity conversion, selected-snapshot token authentication, monotonic clocks,
host integer/runtime semantics, and general Clojure source refinement remain
explicit trusted or empirically checked boundaries.

The abstract operator Phase A consists of
`PermissionSetAlgebra.dfy`, `SignedDependencyStratification.dfy`,
`CandidateCover.dfy`, `WitnessPredicate.dfy`, `VectorPredicate.dfy`,
`AdaptiveBatching.dfy`, `OperatorLeastPath.dfy`, `SeekableSetKernels.dfy`,
`DensityBoundedBatch.dfy`, `AnchorGatedConjunction.dfy`,
`StratifiedExclusion.dfy`, `OperatorCacheRefinement.dfy`,
`ExpressionPlanRefinement.dfy`, `OperatorGeneratedPolicy.dfy`,
`OperatorGeneratedPolicyRefinement.dfy`, and `OperatorProofKernel.dfy`.
Together they add 525 proof-leaf obligations plus six obligations at the
generated `EaclKernel` boundary; the locked whole-tree run verifies 9,325
obligations. The generated `DecideOperatorBatch` and
`DecideOperatorSignedGraph` boundaries are each exercised by Java and
JavaScript against fixed and 1,000-case randomized independent host oracles,
for 2,016 operator assertions per runtime. The proof-only aggregate is kept out
of generated runtime artifacts and is mechanically connected to the small
generated policy through `OperatorGeneratedPolicyRefinement.dfy`. The direct
n-ary intersection proof is an anchor-preserving max-head k-way leapfrog; it
does not use repeated binary filtering. Its demand-stopping result/work model
proves zero-demand silence, exact generic-prefix output, and dimensional
anchor-round, operand-seek, driver-seek, and combined-seek bounds. Its exact
per-round operand-seek trace stops at the first exhausted child rather than
opening later operands unnecessarily.

Phase A is abstract: production does not call its two generated decision
functions directly. Its executable differentials and mutation controls define
the useful claim boundary; generated counts and source digests do not.

Phase B adds the generated recursive command boundary and binds the handwritten
CLJ/CLJS parser, canonical expression storage, signed graph, plan, scalar and
vector evaluators, direct specializations, cursor progress, recursive state,
cache seam, and all four adapters through digest closure, independent-oracle
differentials, counterexample replay, backend certification, and killed
production mutants. The locked whole-tree run verifies 9,361 obligations.
Public intersection/exclusion schema writes and routing are covered by the
production differential, backend, storage, and performance tests.

The operational guide, theorem navigation, adapter certification,
counterexample workflow, generated-engine cutover policy, and assurance wording are in
[`../docs/formal-verification.md`](../docs/formal-verification.md). Behavior
changes discovered by this work are listed in
[`../docs/formal-verification-corrections.md`](../docs/formal-verification-corrections.md).
The issue-111 implementation/proof loophole loop and residual boundary are in
[`verification/permission-tree-final-audit.md`](verification/permission-tree-final-audit.md).

The tool bootstrap reads `toolchain.lock.json`, accepts only supported platform
artifacts, validates SHA-256 before extraction, and fails rather than silently
replacing an existing mismatched download. The same lock fixes the Dafny
assertion-batch time and deterministic Z3 resource ceilings. A successful
`bin/formal verify` emits `target/formal/dafny-verification.json` and the
per-module CSV inputs from which it was derived; these measure proof search,
not EACL runtime resources.

`bin/formal artifact-size` remains an optional generated-output diagnostic. It
is not a correctness or publication gate; runtime allocation, retained heap,
work, and latency are measured by their product workloads.

`bin/formal source-closure` checks the CLJ/CLJS static call closure for the
named shared, generated-provider, and backend roots declared in
`bin/public-source-closure.mjs`. It writes its diagnostic report under
`target/formal/verification/`. Enumerating every reachable definition prevents
silent omissions but does not establish source refinement or adapter
semantics. The executable source-closure test independently checks that every
CLJ and CLJS backend dispatch site uses a required literal operation key.

## Claim boundary

A semantic claim requires its proof, production mapping, backend assumptions,
cross-runtime differential tests, and negative controls to pass. Dependency
version strings, generated byte totals, evidence counts, and source digests do
not establish functional correctness.
