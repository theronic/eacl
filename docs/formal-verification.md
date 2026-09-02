# EACL formal verification guide

EACL's formal work proves a backend-neutral authorization kernel under named
adapter, runtime, and cryptographic assumptions. It does not verify Clojure,
ClojureScript, storage engines, compilers, cryptographic primitives, or a
customer's policy intent. The current release manifest reports
`:conditionally-verified`: production routing, cross-adapter campaigns, and
performance gates pass, while independent security/formal-methods review
remains an explicit unmet release obligation. Two verified bodies now coexist.
Enumeration, point checks, and counts run on the hand-written CLJC
stable-discovery engine (`eacl.engine.sealed-plan`, `stable-reducer`,
`stable-page`, `stable-route`) on both targets; its evidence is the
release-assurance tree under `formal/stable-discovery/` (the Dafny leaves, two
TLC families, executable refinement bridges, mutation controls; see
[docs/stable-discovery-engine.md](stable-discovery-engine.md)). The generated
Dafny kernel remains the production authority for the remaining pure decisions
that surround the engine — consistency planning, cursor continuation, and
page-request normalization — through `eacl.verified-kernel`
on the JVM and its portable CLJC decision twin on ClojureScript, differentially
certified against the generated JavaScript oracle. The generated JavaScript
adapter is formal-smoke-only and no runtime option can select an alternate
engine. The former generated current-cache availability decision has been
deleted; cache storage is modeled as an ordinary partial map and cannot define
an authorization result. Browser answers are advisory and deployments must re-check
authorization on the server.

The measured performance consequences and recommended cache-free reference,
consistency, cache, cursor, and backend architecture are recorded in the
[v8 sound cache and cursor redesign](reports/2026-08-02-eacl-v8-sound-cache-redesign.md)
and the normative
[adversarial strategy review](reports/2026-08-02-eacl-v8-strategy-adversarial-review.md).
Their completed-cache scope is superseded by the authoritative
[single-database current-snapshot cache design](reports/2026-08-02-eacl-v8-single-db-current-cache-design.md).

## Local setup

Install the checksum-locked Dafny/Boogie/Z3, Apalache, and TLA+ tools:

```sh
bin/formal bootstrap
```

The generated-artifact gate additionally requires Babashka 1.12.213. Formal
CI installs that exact gate runtime before rebuilding and measuring artifacts;
the committed gate configuration and regression test reject version or
workflow drift.

The bootstrap installs only under `target/formal-tools/`. Tool versions,
platform artifacts, licenses, upstream URLs, and SHA-256 values are committed
in `formal/toolchain.lock.json`. That lock also carries the Dafny
per-assertion-batch time ceiling and deterministic Z3 resource limit. `verify`
writes one CSV per Dafny module plus
`target/formal/dafny-verification.json`; any failed effort, timeout, or effort
over the locked resource limit fails the command. The solver resource count is
a proof-pipeline measure, not evidence about EACL request latency, heap, or
backend work.

Run proof and model targets independently:

```sh
bin/formal source-closure
bin/formal format
bin/formal verify
bin/formal build-java
bin/formal build-js
bin/formal browser-bundle
bin/formal artifact-size
bin/formal tla-typecheck
bin/formal apalache-check
bin/formal apalache-invariant
```

`artifact-size` must run after all generated forms are rebuilt. It measures
uncompressed Java source bytes, Java class bytes, JavaScript-with-runtime
bytes, and browser-bundle bytes separately against the reviewed full-kernel
policy in `formal/policy/generated-artifact-size.edn`; it does not
substitute one representation, solver effort, allocation, heap, or latency
for another.

`source-closure` derives
`target/formal/verification/public-source-closure.json` with the exact
clj-kondo version in the toolchain lock. It closes the named shared,
proof-provider, and backend roots declared in `bin/public-source-closure.mjs`,
including unattributed usages assigned to their exact containing `defrecord`
spans. It is static completeness evidence only: it does not prove Clojure
source or adapter semantics. A source test independently derives every
CLJ/CLJS `backend/invoke` site and compares its literal operation keys with
the executable backend contract.

Generated Java classes must be tested in a fresh JVM after every regeneration:

```sh
clojure -M:dev:formal-smoke:formal-cljs-smoke:nrepl --port 0
```

Use the reported port with `clj-nrepl-eval`. All Clojure correctness tests,
including CI, execute through nREPL. `bin/ci-nrepl-eval` is the CI client; it
starts no test JVM and only evaluates a supplied form in an existing server.

## Proof navigation

| Source | Main responsibility |
| --- | --- |
| `formal/stable-discovery/*.dfy` | the shipped enumeration engine: grounding of the four rule forms, sealed vector order and read-rank certificate, the width-one reducer (soundness, completeness, exact uniqueness, history-free erasure, atomic admission), one-value scan normalization, bounded buffers, edge pagination, checkpoints, count composition, the membership-probe point check, and the adaptive reducer read-scope bridge; `AtomicAttempt.tla`/`ProgressCheckpoint.tla` bound the attempt/checkpoint histories (`formal/stable-discovery/verify-fast.sh`, 651 obligations), the exact scan-response cache (a served chunk equals the adapter's chunk for the same bound and limit; contiguous extension keeps a prefix of the scan), and range answer reuse (any window inside a retained page segment is the page from that boundary; a window past a segment is the segment's tail plus its continuation) |
| `Semantics.dfy` | typed rules, normalization, monotone consequence, finite least fixed point |
| `SnapshotOracle.dfy` | abstract immutable adapter contract |
| `AcyclicEngine.dfy` | **retired engine model** (path compilation, direct checks, acyclic projections and counts); kept as a regression model until task 9.2's formal cut |
| `RecursiveEngine.dfy` | **retired engine model** (typed SCC routing, recursive worklists, continuation replay); same disposition |
| `OrderedMerge.dfy` | **retired** ordered union and uniqueness of the entity-ID merge; same disposition |
| `PageWindow.dfy` | total page normalization, windows, keyset page decisions, cursor continuation decisions (live decisions) |
| `IndexedBatching.dfy` | **retired** bounded ordered scan waves and crossing law of the generated indexed traversal; same disposition |
| `IndexedBatchCompleteness.dfy` | **retired** proof-only pending-scan ghost views; same disposition |
| `CurrentCache.dfy` | exact-basis/managed admission, complete exact identity including backend snapshot/cache-basis equality, lifecycle isolation, scalar stamps, least-fixed-point dependency frame, selected-basis rendering |
| `NativeGenerationCoherence.dfy` | forward native-generation frame, empty dependencies, stale endpoint exclusion, component cleanup/stamping, and lifecycle isolation |
| `ScalarFrontierCoherence.dfy` | globally ordered native generations, full canonical dependency-generation identity, derived scalar-frontier soundness, complete proof frames, demand identity, and completed-only publication, and the singleton dependency frontier (one relation's generation) that scopes the shared scan-response cache |
| `SchemaPlanCost.dfy` | one recursive-plan compilation per permission root/schema generation and bounded page-sensitive stream batches |
| `TemporalSafety.dfy` | unbounded cache/cursor transition predicates |
| `WireFormat.dfy` | strict abstract boundary variants and bounds |
| `PermissionTree.dfy` | typed shallow expansion topology, denotation, active-path cycles, structural budgets, and all-or-error outcomes |

`formal/assurance_contract.clj` maps public operations to theorem sources,
adapter assumptions, runtime targets, proof-count ratchets, and remaining
obligations. A passing proof file is not by itself a public assurance claim.
`bin/formal manifest` derives `target/formal/verification/manifest.edn` from
that contract and live reports, and continues to refuse verified status while
any required obligation is incomplete.

### Operator set-algebra boundary

The Phase A models prove finite typed union/intersection/exclusion semantics,
strict signed-dependency stratification, candidate-cover soundness, scalar and
aligned-vector predicates, bounded batching, least-path pagination, direct
k-way leapfrog and anti-join kernels, anchor-gated positive recursion,
completed lower-stratum exclusion, and cache refinement. The direct n-ary
intersection proof uses an anchor-preserving max-head k-way leapfrog, not
repeated binary filtering.

Phase B adds the generated recursive command decision and digest-closes the
handwritten parser, canonical expression codec/storage, signed graph, plan,
evaluators, cursor/cache boundary, Datahike density-bounded batch, scalar
fallbacks, and four backend adapters. The module and obligation counts of the
clean whole-tree run are reported by CI (`bin/formal verify` writes
`target/formal/dafny-verification.json`) and are not recorded in this
document; generated Java and advanced JavaScript boundary
suites, fixed/random differentials, counterexample replay, formal and temporal
mutation controls, cross-backend conformance, storage, and matched-host
performance gates pass. Public expression writes and public operator routing
are enabled by default. CI executes the exact evidence and writes current
source digests to the ignored generated manifest; operational semantics and
measured limits are in [Permission set algebra](permission-set-algebra.md).

### Permission-tree assurance boundary

`PermissionTree.dfy` contributes 62 locked obligations. The theorem map covers
node oneof and annotation well-formedness, direct-leaf exactness, union
denotation and child-permutation invariance, absent-resource topology,
active-path cycle rejection, additive-budget monotonicity, successful limit
preservation, failure non-publication, typed identity, sum-typed relation
declarations, and emitted-child depth accounting. Executable witnesses
also reject unsound flattening, global-visited cycle detection, type-erasing
identity, partial success, and over-limit success.

The formal model is proof-only. Production
`modules/eacl/src/eacl/permission_tree.cljc` is handwritten and has no claimed
mechanical Dafny-to-Clojure refinement. Correspondence evidence lives in
`modules/eacl/test/eacl/permission_tree_test.cljc` (independent evaluator,
bounded generators, permutation and hostile-realization checks),
`modules/eacl/test/eacl/contract_support.cljc` plus each backend contract, and
`formal/fixtures/permission-tree/` (version-pinned black-box Docker topology).
CLJ and CLJS run the same portable kernel. Adapter schema/scan completeness,
codec round trips, immutable selection, causal-token authentication,
monotonic-clock behavior, host exact-integer/runtime semantics, and arbitrary
source states remain trusted or empirically certified rather than proved.

## Temporal models

`formal/tla/EaclTemporal.tla` is the compact safety model.
`EaclTemporalDetailed.tla` covers hostile cache, cursor, exact-selection,
retention, branch/reset/restore, provider-failure, tampering, and continuation
races. Bounded checks are bug-finding evidence. Separate initiation,
consecution, and safety-implication runs establish the configured inductive
invariants; the final unbounded state predicates are carried in Dafny.

## Adapter certification

The proof assumes adapters provide immutable coherent snapshots, injective
identity conversion, complete schema/scans/generation proofs, stable source
lifecycle scope, monotone native revision selection, and correct exact
selection. Managed cache correctness does not assume graph ancestry. Run the shared certification namespaces through a dev
nREPL:

- `eacl.datomic.adapter-certification-test`
- `eacl.datascript.adapter-certification-test` in CLJ and CLJS
- `eacl.datahike.adapter-certification-test`

These suites are the machine-readable result: CI fails on any failing
assertion and may upload the current test output as a run artifact. Optional
runtime guards check locally representable shape, order, uniqueness, bounds,
booleans, adapters, and nonnegative exact-integer internal EIDs. Global
completeness, ancestry, and generation-proof truthfulness remain certification
obligations.

## Counterexamples and mutation controls

Every discovered production defect has a directory under
`formal/counterexamples/EACL-FORMAL-NNN/` containing the ledger entry,
minimized fixture, expected result, and reproduction instructions.

Run the complete retained corpus:

```sh
EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay
```

Run all registered deliberately wrong implementations:

```sh
EACL_NREPL_PORT=<dev-port> bin/formal mutation-control
```

The registry is `formal/mutations/registry.edn`; a survivor is a release
blocker. Scheduled CI also runs coherent generated-schema campaigns and uploads
the exact seed, coverage, run metadata, and coherence-preserving minimized
fixture on failure.

## Cryptographic boundary

`formal/verification/cryptographic-assumptions.md` maps authentication,
canonicalization, proof equality, collision resistance, entropy/key management,
and clock axioms to production functions and tests. These remain assumptions,
not proved cryptographic claims.

## Generated authority and retained differential evidence

Generated Java and JavaScript providers implement the portable
`eacl.verified-kernel/DecisionKernel` boundary. Production clients always
install that generated provider; `:engine-selection` is rejected as an unknown
client option. Cursor continuation,
relationship request normalization, relationship keyset page flags/window
size, and decoded cache-entry decisions are routed through that boundary. The
indexed relationship engine retains only an authenticated
physical edge and consumes at most one page plus lookahead; executable
forward/backward walk tests establish stable, complete, duplicate-free
composition over certified adapter scans. This is deliberately not a theorem
of a global or cross-backend result order.

The pre-cutover shadow campaign and its minimized counterexamples remain
evidence, not executable production behavior. Test-only injection seams run
the generated provider, retained materialized oracle, and independent
reference implementations against the same fixtures without adding a
production rollback branch.

The same boundary now converts complete materialized schema IR, objects,
relationships, traversal limits, all five authorization request variants, and
typed results to generated Java and JavaScript. This is the executable
cache-free semantic reference used by differential tests. Its completed
authorization values are compared with completed indexed results. Its work
counters and typed limit outcomes are not production resource refinements:
the reference closes the whole finite fixture, while production is
query-local. Production limits and dimensionally matching counters are
compared against the generated indexed state machine. Cached and uncached
public-client state traces cover Datomic, Datahike, and DataScript, including
unrelated transactions and revocation.

The public `can?` dispatch and acyclic hot path also have source-shaped
submodels. The public model proves that reusing the already-computed
permission-root classification preserves the Boolean result under the
established undefined-root-denies contract and reduces a
generated-authoritative call to one root lookup. A public JVM fixture observes
that exact lookup count; the shared CLJC result path remains covered on CLJ and
CLJS. The acyclic models cover ordered EID merge, leapfrog intersection, and
arrow empty/singleton/wide selection. Dafny
proves their Boolean/set behavior and named logical bounds; generated
Java/JavaScript compare the exact source-control results and traces with
CLJ/CLJS. EACL-FORMAL-042 records the resulting production fix: an empty arrow
now returns false before direct-grant/intersection setup. These submodels do not
prove path materialization, nested callback meaning, storage-engine seek cost,
Clojure language semantics, allocation, retained heap, or wall time.

`can?` is anchored to the known resource: `eacl.engine.stable-route/check-eids`
decides membership by a probe search over the sealed plan's reverse index —
the resource's intermediates are explored depth-first (a visited set on
[node entity]) and the subject is looked up by one exact-bound scan per
direct rule, never by enumerating the subjects that hold the permission.
`MembershipProbeCheck.dfy` proves that answer equal to membership in the
exhaustive reverse-discovery denotation (`ProbeAnswerEqualsReachability`,
`ProbeCheckEqualsEnumerationCheck`), on top of the reverse transition
soundness and completeness proved by `EaclReverseProducer.dfy` and
`ReducerCompleteness.dfy`. Two-layer arrow arms — an arrow to a relation,
or an arrow to a permission whose every derivation is a base relation — are
decided bidirectionally: `BidirectionalArrowIntersection.dfy` proves the arm
answer equal to a nonempty intersection of the resource's via-set with the
subject's holdings whichever side is enumerated (`StrategiesAgree`,
`DecideEqualsArmAnswer`), and proves the interleaved decision's consumption
bounded by the SMALLER side (`RoundsBoundedByShorterSide`) — the complexity
property that makes a check on a widely shared resource cost the subject's
few holdings rather than the resource's fan-in. Acyclic plans paginate in
least-derivation-path order (order ABI v2): `LeastPathOrder.dfy` proves
the per-scan coordinate order strict, total, and a pure function of
(plan, snapshot); `LeastPathEnumeration.dfy` proves the smaller-witness
emission filter yields exactly the reachable denotation once per entity
(bridged onto `ReducerCompleteness`) and that pruning repeated interior
states drops nothing; `LeastPathResume.dfy` proves seek-past-boundary
resume equals the enumeration suffix and that descending windows agree
with ascending positions — the theorems behind self-contained keyset
cursors with no checkpoint state and no replay; `eacl.engine.point-check-test` is the executable
oracle differential against the retained reverse-enumeration form
(`enumeration-check-eids`). EACL-FORMAL-055 retains the historical
subject-forward scaling regression of the retired generated state machine as
a replayed counterexample against the stable engine.

Permission-path materialization now has its own source-shaped boundary rather
than being assumed by the arrow theorem. Dafny models expansion of typed
relation definitions into direct, alias, arrow-relation, and arrow-permission
paths, missing-definition behavior, static cost ranking, subject-type filtering
for direct grants, and the exact meaning of `:exhaustive?`. Generated Java and
JavaScript match `calc-permission-paths` and `calc-direct-grant-relations` on 99
CLJ/CLJS fixtures each. Adapter certification v2 composes the same calculation
with actual Datomic, Datahike, and DataScript definition IDs. That is finite
executable refinement evidence; host-language semantics and arbitrary storage
engine states remain trusted.

The outer acyclic union fold is source-shaped as well. Dafny proves the
recursion guard performs zero path/callback work, direct paths with a
nonmatching declared subject type do not invoke the backend probe, evaluation
stops at the first effective positive, and path/callback checks are linear in
the materialized path count. Generated Java and JavaScript match the actual
CLJ/CLJS value, realized-path count, per-kind callback counts, and ordered
`[path-kind, path-index]` trace on 407 fixtures each. Complete callback argument
vectors and the meaning of nested callback results remain separate refinement
obligations.

There is no longer a routing decision between an acyclic and a recursive
engine: every permission root compiles to one sealed plan whose
`:recursive?` flag (Kahn's peel over the permission-dependency edges) only
governs the bare-`:last` complete-evaluation guard. `AcyclicEngine.dfy`,
`RecursiveEngine.dfy`, `OrderedMerge.dfy`, `RoutingCertificate.dfy` and the
`Indexed*` leaves under `formal/dafny/` model the retired engines; they still
verify on CI as historical regression models until the remaining formal cut
recorded in `openspec/changes/adopt-stable-discovery-enumeration/tasks.md`
(task 9.2) removes them and re-pins the manifest. EACL-FORMAL-030 retains the
same-permission-name counterexample.

Materializing an entire database remains unacceptable on large EACL graphs.
Production therefore drives certified ordered adapter scans one value at a
time from the sealed plan rather than running a whole-graph evaluator, on the
JVM and on ClojureScript from the same CLJC source, gated by the independent
naive-fixpoint oracle, frozen cross-engine baselines, randomized refinement,
counterexample replay, mutation controls, and full DataScript differentials.
The generated browser runtime stays outside the production classpath. There
is no runtime engine-selection branch. Independent review remains a separate
release-assurance obligation, so the manifest reports
`:conditionally-verified`, not an unqualified whole-deployment claim.

## Interpreting the assurance claim

“Verified” means a mapped generated JVM decision refines its formal
specification when its listed adapter and trusted-boundary assumptions hold,
and, for enumeration, that the CLJC stable engine's executable refinement
bridges, oracle differentials and mutation controls pass against the
stable-discovery proof leaves. Neither engine body is mechanically extracted
into production; the generated kernel is executed for its four decisions, the
stable engine is hand-written CLJC checked against its models. The evidence includes exact successful lookup/count/page behavior and
fail-closed limit, cache, and cursor decisions. It does not mean the entire
EACL deployment or backend is proved correct. Missing coverage, a failed
adapter obligation, a surviving mutant, a timeout, an undocumented axiom, or
an unmet performance gate withholds the claim.
