# Final assurance-claim audit

Date: 2026-08-04

## Decision

EACL v8.0 is **not formally verified as a complete public authorization
engine**. The release manifest must remain `:not-verified`, and the validator
must return nonzero.

The implemented proof and runtime evidence supports narrower claims:

1. the Dafny semantics, direct/acyclic algorithms, recursive algorithms,
   pagination window, cache-decision, cursor-decision, strict wire-format, and
   temporal predicates satisfy the theorems named in the manifest;
2. the generated Java and JavaScript kernels agree with the executable
   semantics on the recorded fixtures and generated campaigns;
3. Datomic, DataScript, and Datahike passed the recorded adapter certification
   tests for the exercised finite fixtures;
4. decoded relationship-page, cursor-continuation, authenticated
   cache-validation, and snapshot-selection plan/postcondition decisions are
   routed through generated decision kernels in the internal verified modes;
   and
5. strict generated Java and JavaScript boundaries evaluate complete
   materialized `can?`, lookup, and count requests, and the Java reference
   agrees with cached and uncached public state traces on all three backends.

These claims remain conditional on the trusted toolchain, generated-code
compilers, runtimes, FFI conversion, adapter contracts, canonicalization,
cryptography, collision resistance, entropy/key management, clocks, and
configured resource limits listed in the manifest and trusted-boundary
documents.

The strict conversion ledger in
`formal/verification/conversion-boundary.edn` machine-checks that both runtime
boundaries implement the required schema, relationship, query, callback,
cache/cursor, result, and typed-error converter families. Boundary tests check
strict validation and differential behavior. This closes the implementation
inventory for the CLJ-to-Java and CLJS-to-JavaScript conversions; it does not
promote handwritten FFI conversion code, either host runtime, or either
generated-code compiler out of the trusted computing base.

## Claims that are deliberately withheld

- The indexed generated-to-adapter callback boundary is implemented for CLJ
  and CLJS. In internal `verified-authoritative` mode it owns opaque traversal
  state for permission roots that transitively depend on a recursive SCC. It
  is not yet the supported/default release engine.
- Cache-disabled public calls now preserve the engine selection in Datomic,
  Datahike, and DataScript. Earlier state-trace evidence did not detect that
  bypassed calls were falling back to legacy traversal; the corrected trace
  asserts generated calls occur on every backend.
- Generated worklist discovery order is not globally EID ordered for acyclic
  multipath permissions. The minimized `owner + viewer` interleaving witness
  is retained as a regression, and unsafe generated all-root routing was
  removed. Dafny now proves ordered single-step and bounded-chunk
  reconstruction, and generated Java/JavaScript act as executable oracles for
  the optimized CLJ/CLJS merge. Per-EID and generated-sequence hot-path
  prototypes failed latency gates. The source specialization has no
  engine-selection overhead, but formal source refinement and independent
  review remain release gates.
- Complete recursive page, count, Boolean, dimensional resource-counter,
  retained-logical-state, traversal-limit, and portable typed-error outcomes
  are compared in `verified-shadow` mode on all JVM adapters. Separate
  complete-public traces compare cache provenance and selected graph identity
  between legacy and generated authority on Datomic, Datahike, DataScript/JVM,
  and DataScript/JavaScript. Non-portable exception data is observable as
  comparison-unavailable rather than passing. The complete non-benchmark and
  heavy CLJ suites now pass under forced generated authority on Datomic,
  Datahike, and DataScript, and the complete DataScript CLJS suite passes under
  forced generated authority. Independent production rollout volumes, source
  refinement, and release cutover gates remain incomplete.
- The current-generation cache, exact/arbitrary-DB bypass, scalar stamp law,
  least-fixed-point managed frame, and selected-snapshot rendering are proved
  and integrated for all three adapters. This is a conditional cache
  refinement claim, not complete public-engine verification.
- Lore's historical `A, B, A` report prompted an implementation-level
  recursive ownership audit; Lore itself is not accepted as evidence. EACL's
  own minimized regression, mutation control, and proof work independently
  confirmed that `resolve!` recorded
  `(lifecycle, tier, semantic-key)` while `lookup!` checked only
  `(tier, semantic-key)`. A recursive lookup could therefore attempt to join
  its own in-flight computation. The production key shape is now identical on
  both paths, with CLJ/CLJS regression, Dafny lifecycle lemmas,
  counterexample replay, and mutation control.
- EACL's continued source audit found that recursive `resolve!` self-bypass
  invoked a callback
  directly. A child `future` inherits the parent's resolving-key bindings but
  is a different execution context, so that callback escaped the coordinator's
  active count. Self-bypass now crosses the context-aware slot wrapper:
  same-context recursion reuses its permit and a child context acquires one.
  EACL-FORMAL-014, the JVM concurrency regression, Dafny accounting lemmas,
  and mutation control retain the failure.
- A subsequent lifecycle audit found that recursive-self identity was captured
  before the store's selection critical section. `clear!` could advance the
  lifecycle between that capture and flight selection, causing a callback to
  own a new-lifecycle flight under an old-lifecycle recursion marker.
  EACL-FORMAL-015 moved lifecycle capture, self-detection, entry lookup, and
  flight lookup into one store-lock selection.
- EACL-FORMAL-016 showed that generated lookup authority was initially
  cosmetic: the host installed a flight before asking the generated kernel
  which lookup transition was legal. Selection now invokes the generated
  action from stable pre-mutation state, and contradictory generated actions
  fail closed.
- EACL-FORMAL-017 then showed that represented tier entries were not the whole
  candidate state. Admission can reject a candidate while its
  lifecycle-qualified coordinator flight remains registered. Generated lookup
  now sees both represented entries and registered flights and must choose
  `join-computation` for the unrepresented-flight case.
- EACL-FORMAL-018 found a narrower refinement defect: completion removed a
  flight outside the lock used by lifecycle-stable selection. The observed
  delay still made the runtime behavior safe, but the claimed single serial
  order was false. Ticket-qualified removal now takes the same store lock.
  A 64-fold represented-entry scaling gate measured a 1.017x miss-finalization
  p50 ratio, so the miss-only lock does not introduce an entry scan.
- EACL-FORMAL-019 was found by expanding the ordered-merge source mapping from
  its public wrappers to the specialized helpers that execute on the EID hot
  path. The descending helper used the runtime maximum integer as its
  uninitialized `last-key`; a legitimate maximum EID was therefore discarded.
  The source now carries an explicit `has-last?` bit, and portable CLJ/CLJS
  regressions cover unique and duplicated maximum EIDs. The independent
  source-refinement review gate remains open.
- EACL-FORMAL-020 extended that audit to the generic merge helper. It used
  `nil` as both “no previous key” and a legal host sort key, so it omitted the
  first nil-keyed value. The helper now uses the same explicit presence bit.
  This is covered by portable regression and mutation control; Dafny's integer
  value domain proves the optional-state shape for EIDs, not arbitrary host
  comparator semantics.
- EACL-FORMAL-040 found that the ordered-merge theorem still modeled only the
  canonical merge and an abstract balanced fold, not production's explicit
  `has-last?` state, exhausted-tail `drop-while`, empty-stream filtering, or
  adjacent pairwise round schedule. Dafny now models those source-control
  states directly, proves that the exact ascending and descending source
  recursions equal the canonical merge for finite strictly ordered streams,
  proves the filtered balanced fold preserves strict order and the complete
  union, proves that strict order plus set equality determines the exact
  sequence and hence that the production fold equals the canonical fold, and
  bounds two-stream comparison iterations by `|left|+|right|`.
  Generated Java and JavaScript each agree with the actual host source on all
  8,192 pairs of subsets of a six-value safe-natural domain in both
  directions, plus 100 JVM and 50 JavaScript multi-stream folds containing
  empty streams. Clojure language/sequence semantics and independent review
  remain trusted; the report does not call executable correspondence a proof
  of Clojure itself.
- EACL-FORMAL-041 found that the acyclic leapfrog specialization compared only
  the Boolean answer and aggregate reseek count. A mutant could retain both
  while seeking the wrong stream or requesting the wrong boundary, changing
  backend work and potentially omitting candidates. Dafny now emits the exact
  ordered `[stream-side, target]` trace and proves its length equals the reseek
  count; generated Java and JavaScript compare that trace with callbacks from
  the actual CLJ/CLJS source on all 4,100 fixtures per runtime. The
  wrong-target and wrong-side mutants are killed. Clojure language semantics
  and the backend inclusive-seek contract remain explicit trusted refinements.
- EACL-FORMAL-042 was found by comparing the first source-shaped acyclic arrow
  control model with actual CLJ/CLJS execution. Production's singleton
  shortcut required a truthy intermediate stream, so an empty arrow entered
  the wide branch, calculated direct-grant relations, and could open
  subject-side scans before returning false. Production now returns false
  immediately. Dafny proves that zero or one intermediate performs no
  direct-intersection phase, full fallback checks at most the intermediate
  count, and the optimized decision equals complete far-side evaluation under
  explicit direct-subset and exhaustiveness contracts. Generated Java and
  JavaScript agree with the source on eight Boolean/work traces; the
  empty-enters-wide mutant is killed.
- EACL-FORMAL-021 found that Datomic's separate cache-compatibility normalizer
  rejected and failed to forward the shared `:subproblem-cache` configuration.
  DataScript and Datahike honored those projection, denotation, proof-atom,
  callback, and disable settings, while Datomic silently remained constrained
  only by defaults after the rejected request was removed. Datomic now forwards
  the exact nested map to the shared constructor, which validates it before
  returning a client.
- EACL-FORMAL-022 found that generated recursive limit errors omitted the
  configured numeric `:limit`, while shadow comparison discarded every
  non-keyword error field and therefore concealed the public `ExceptionInfo`
  divergence. Generated adaptation now restores the exact validated limit and
  the redacted shadow view compares bounded numeric limit fields internally.
  Diagnostics still expose only changed field names and safe result variants.
- EACL-FORMAL-023 found that generated recursive render rejection added a
  generated-only `:direction` field to the established stale-cursor public
  error shape. The shadow comparator would report the difference, but no
  campaign invalidated a retained result between raw-engine pages. Generated
  adaptation now preserves the legacy shape, and JVM plus JavaScript shadow
  traces exercise the rejected-render branch.
- EACL-FORMAL-024 found that the materialized Dafny reference compared
  production's instantaneous `:max-queued-work` limit with cumulative
  fixed-point-round enqueues. Two sequential singleton rounds were therefore
  rejected even though queue depth never exceeded one. The model now records
  maximum pending-set cardinality; cumulative enqueues remain a separate
  directly instrumented resource dimension.
- EACL-FORMAL-025 found a second invalid resource substitution: the
  materializing oracle closes the whole finite graph, while production seeds
  query-local indexed work. An unrelated subject-type grant can therefore
  trip the model limit without consuming production queue depth. Reports now
  compare completed authorization values only; operational limits and
  counters refine production solely through the generated indexed engine.
- EACL-FORMAL-026 found that EACL-FORMAL-023 had normalized only the redacted
  stale-cursor shadow view. Full public exception data still differed between
  legacy `:bound`/`:actual` maps and generated `:render-error` data. Both paths
  now expose one minimal typed stale-cursor map, and JVM/JavaScript regressions
  compare the entire map.
- EACL-FORMAL-027 found an input-domain mismatch between the source adapter
  contract and every generated/Dafny EID boundary. Optional adapter guards
  accepted signed exact-integer object IDs, order hints, and ordered scan
  values, while the formal engines require safe naturals. The three shipped
  stores allocate nonnegative persistent EIDs, but a third-party adapter could
  invalidate source/generated refinement. Nonnegativity is now an explicit
  adapter obligation, runtime guards fail closed, and portable certification
  rejects negative visible-object identities.
- EACL-FORMAL-028 found that the generated-artifact-size dimension was marked
  passed from stale foundation numbers without measuring the current rebuilt
  full kernel. The current Java sources, Java classes, JavaScript runtime, and
  browser bundle already exceeded all four old foundation maxima. Formal CI
  now rebuilds and independently byte-counts all four artifact forms against
  reviewed full-kernel baselines, writes a machine-readable observation, and
  fails above 125 percent growth.
- EACL-FORMAL-029 found that the counterexample corpus checked only required
  field names, not values against its committed schema. Nine values in six
  entries had drifted into undeclared taxonomies. Replay now interprets and
  enforces every scalar, enumeration, union, vector, and relative-path field;
  existing entries were normalized without weakening the schema.
- EACL-FORMAL-030 found that the older formal permission-dependency abstraction
  drops the resolved resource type from arrow-permission targets. Reusing it as
  an exact SCC-routing oracle can spuriously connect an unrelated same-named
  permission to a recursive component. Production already retains the complete
  `[resource-type permission]` node; a new `PermissionDependencyEdge` model now
  does the same and proves the typed least closure, strong-component
  classification, singleton self-loop rule, and inclusion of every transitive
  acyclic ancestor. Generated Java and JavaScript agree with the actual shared
  production analysis on seven adversarial graph shapes and all 512 labeled
  directed graphs over three typed nodes. A subsequent proof-carrying
  `RoutingCertificate` checker now verifies the host partition, mutual
  reachability forests, component ranks, recursion witnesses, and traversal
  propagation before accepting the traversal vector. It also stream-checks
  that all materialized path descriptors derive exactly the supplied ordered
  dependency-edge vector: relation paths emit none and permission paths emit
  one directed edge. The generated vector is authoritative for stamped schema
  generations on Datomic, Datahike, and DataScript. Source-shaped
  path-materialization plus adapter certification v2 now cover the preceding
  raw-definition-to-path-map boundary. Clojure language semantics, arbitrary
  adapter states, independent review, and the host path-map-to-descriptor
  translation remain trusted.
- Lore prompted the resource-accounting question for that routing result, but
  its historical analyser is outdated and untrusted. EACL does not run it as
  an oracle or release gate. Production deterministic indexing is
  `O(V log V)` comparison work, followed by `O(V+P+E)`
  path/graph/certificate work. The generated certificate checker proves
  exactly `P+2V+E` certified loop iterations on
  acceptance. Those statements still do not bound string-comparison cost,
  allocation, retained heap, CPU, wall time, or backend work. JVM/Node
  measurements gate those dimensions separately; no Lore result or Dafny
  logical counter substitutes for a runtime peak.
- EACL-FORMAL-031 found that the post-build artifact gate was correctly
  fail-closed but could not execute in GitHub Actions: its Babashka shebang had
  no installed `bb` runtime. The previous CI run completed all 9,207 proof
  efforts and generated builds, then failed with `bb: No such file or
  directory` before size measurement. Formal CI now installs Babashka
  1.12.213 explicitly, and the retained regression checks both that dependency
  and that measurement follows the browser build.
- EACL-FORMAL-032 found that recursive shadow comparison still projected
  `ExceptionInfo` data through a keyword/integer allowlist. Public string,
  boolean, vector, and nested-map fields could therefore diverge while rollout
  telemetry reported equality. The portable boundary now canonicalizes and
  compares the complete portable error-data map internally. Diagnostics expose
  only changed top-level field names and typed error keywords; non-portable or
  untyped exceptions produce an explicit comparison-unavailable diagnostic
  and cannot count toward a rollout gate. This closes a comparator defect, not
  the remaining obligation to exercise every documented public error variant
  on every runtime and backend.
- EACL-FORMAL-033 classified the first complete-public graph-identity
  divergence as a harness defect. DataScript clients reading the same immutable
  graph mint distinct exact-registry locator strings. Those strings are
  reconstruction capabilities, not graph identity. Cross-backend shadow
  comparison now uses source scope, snapshot identity, graph anchor, and order
  hint; exact-locator resolution retains a separate authenticated
  postcondition.
- EACL-FORMAL-034 found that `formal/smoke/cljs/run` invoked
  `cljs.main/-main` inside the required persistent nREPL. The CLI lifecycle
  terminated Clojure's global agent executors, so later concurrency
  counterexamples failed with `RejectedExecutionException` depending on test
  order. The launcher now uses `cljs.build.api/build`, runs the Node suite, and
  verifies through the same nREPL that a new `future` completes. The CLJS smoke
  suite and all 35 counterexamples now pass sequentially in one nREPL.
- EACL-FORMAL-035 first found reflective variant, destructor, and numeric
  method calls on every generated indexed drive/resume round trip. A complete
  compile-time audit then found the same defect class elsewhere in the
  handwritten CLJ-to-generated-Java conversion boundary. JFR attribution
  localized the hot-path cost to handwritten FFI code, not Dafny's opaque
  traversal state. Concrete generated-class type hints reduced the minimized
  recursive p95 allocation premium from 3,677,688 to 343,576 bytes and the
  cursor premium from 4,283,960 to 706,496 bytes. A compile-time audit now
  requires zero reflection warnings across the complete generated-Java
  boundary. These are host measurements and source checks, not formal heap
  bounds.
- EACL-FORMAL-036 found that generated authority returned correct recursive
  answers while bypassing the production denotation, recursive-page, and
  process-private continuation caches. It also discarded the opaque generated
  frontier after every page and failed to project generated dimensional
  counters into compatibility telemetry. The first forced-authority suite
  produced 13 failures and 3 errors. Dafny now verifies forward and reverse
  page-continuation transitions: the transition authenticates the public
  ordinal and EID, preserves the semantic frontier and cumulative counters,
  and carries the consumed lookahead into the next page without prefix replay.
  The Clojure engine stores that state only in the client-private cache and
  deterministically replays the authenticated prefix on any miss, eviction,
  malformed value, or generated rejection. Cache-enabled point checks and
  unbounded counts now publish or reuse complete generated fixed-point
  denotations. The forced CLJ suites exercised 2,525 Datomic generated
  continuations. Datahike and DataScript retain deterministic replay where no
  process-private continuation store is available.
- EACL-FORMAL-037 found that the Datomic indexed schema cache omitted the
  shared traversal-analysis generation slot used by Datahike and DataScript.
  Datomic therefore retained the host per-root recursive classifier in
  verified-authoritative mode. The cache shape and eviction path now match the
  shared engine, and the forced-authority harness requires every backend to
  invoke the generated routing-certificate operation. The closing
  nonbenchmark run observed 206 Datomic, 32 Datahike, and 30 DataScript
  certificate calls.
- EACL-FORMAL-038 found that strict routing-result validation checked map
  shape and scalar representations but did not relate the accepted vector or
  work counters to the request. The CLJ/CLJS boundary now requires accepted
  traversal length `V`, path checks `P`, node checks `2V`, and edge checks
  `E`; rejected counters may stop early but cannot exceed those
  request-derived maxima.
- EACL-FORMAL-039 found that the first routing certificate trusted an indexed
  edge vector independently supplied by Clojure. A path-translation bug could
  therefore prove the wrong graph perfectly. The generated boundary now
  consumes every materialized path descriptor and accepts only the exact
  ordered edge derivation before checking the SCC certificate. Production
  constructs its graph from that same edge vector; three new mutants cover
  omitted permission edges, invented relation edges, and edge permutations.
- Snapshot-consistency planning and post-selection validation now route
  through `ConsistencyDecision.dfy` in verified modes. Its 24 plan states and
  48 well-formed validation states are exhausted in generated Java and
  JavaScript. A first model draft conflated an absent exact selection with a
  present malformed adapter; production distinguishes the former as
  `exact-snapshot-unavailable` and the latter as
  `invalid-backend-adapter`. The model now carries presence and adapter
  validity separately, with regression and mutation controls. This defect was
  caught before the decision was routed, so it is recorded as a model-fidelity
  regression rather than assigned a production counterexample identifier.
  Token authentication, scope/ancestry truthfulness, exact reconstruction,
  backend exceptions, and the host fact-extraction code remain explicit
  refinement obligations.
- A separate CLJ/CLJS production-observation matrix now checks the handwritten
  post-selection fact extraction over 24 scenarios: absent, malformed,
  identical, same-scope anchor pass, same-scope anchor failure, and
  different-scope selections in all four selection kinds. This checks the
  reachable Clojure-to-Dafny input map; it does not turn adapter-returned scope
  or ancestry assertions into proved facts.
- The same source audit rejected the first consistency work-counter model
  because it represented the optimized backend-client captured-current path
  but omitted the still-supported direct `eacl.consistency/select` current
  path. The corrected datatype treats them separately: captured current has no
  selection, validation, scope, or head calls; selected current has one backend
  selection, one validation, at most three scope reads when issuing a response
  token, and one each of graph-head, order-hint, and exact-locator reads.
  Naming the latter two is necessary because production validates every graph
  head against those separate adapter operations. Source instrumentation
  exercises all five successful path kinds with response-token issuance both
  disabled and enabled. The selected-path source-scope figures are upper
  bounds matched by non-identity selection fixtures; an identity-preserving
  backend selection can perform fewer scope reads.
- Production shadow comparison covers recursive traversal values, ordering,
  page flags, counts, Boolean decisions, dimensionally matching cache-free
  resource counters, logical retained-state units, typed traversal-limit
  failures including configured numeric limits, recursive stale-cursor render
  rejection, complete portable `ExceptionInfo` data, cache provenance, and
  selected graph identity. Shadow diagnostics are fail-open with respect to
  legacy authority and redact request/result values without emitting guessable
  hashes. Required rollout volumes remain incomplete.
- No independent security/formal-methods review has been obtained.
- The current-cache performance gate passes. On the routed 100,000-result
  recursive-chain fixture, the retained raw gate measured generated authority
  at 1.49x legacy p50 and 1.82x legacy p95. A 2026-08-03 dimensional recheck
  measured 1.17x p50 and 1.38x p95, with identical page results and all twelve
  logical resource measures equal, inside the existing 2.0x p95 gate. Full
  cutover remains blocked by the digest-locked Clojure-to-Dafny language
  correspondence for source specializations, shadow coverage, and independent
  review rather than recursive cache-hit cost.
- The representative public authority-mode gate passes on DataScript across
  direct, acyclic, recursive, cursor-continuation, and hot-cache calls. Median
  verified/legacy p95 latency ratios over five paired trials are 1.18x, 0.92x,
  1.57x, 1.42x, and 1.10x respectively; caller-thread allocation ratios are
  1.02x, 1.01x, 1.46x, 1.43x, and 1.02x. Recursive and cursor operations each
  use one fewer backend operation at p95. Exact public values match on every
  call. This gate does not establish retained heap, whole-process allocation,
  worst-case latency, or all-backend verified-authority cutover.
- The optimized ordered merge passes the source-specialization non-regression
  gate for both a 20-value page prefix and complete 20,000-value consumption.
  Its exact `has-last?`, exhausted-tail, empty-filter, and pairwise-fold
  control model is now proved separately from the canonical merge, and its
  two-stream comparison loop is formally linear in the combined input length.
  After the explicit-presence fixes, ascending/descending median trial-level
  p95 ratios were 0.983/0.998 for the prefix and 0.974/0.970 for complete
  consumption against the identical legacy selection. These are wall-time
  benchmarks over a pure in-memory merge; they do not establish heap or
  backend-operation theorems.
- The acyclic arrow intersection fast path now has a narrower formal
  specialization: for finite strictly ascending EID streams, Dafny proves
  leapfrog intersection equivalent to nonempty set intersection. Its exact
  probe/reseek control model bounds outer iterations by the sum of the input
  cardinalities, reseek calls by outer iterations, and probe-head examinations
  by seventeen times outer iterations. Generated Java and JavaScript agree
  with the actual private CLJ/CLJS function on 4,100 cases per runtime,
  including exact reseek counts, exact ordered stream-side/target traces, and a
  fixture that forces the 16-element probe/reseek branch. Equal-head,
  exclusive-reseek, probe-limit off-by-one, wrong-target, and wrong-side
  mutants are killed.
  These are dimensionally separate logical-control-flow bounds, not bounds on
  backend seek cost, lazy realization, allocation, heap, or latency.
  Correctness still assumes each adapter implements an inclusive
  first-EID-at-or-after-target seek. Independent source refinement and adapter
  review remain open.
- The acyclic arrow source specialization covers empty, singleton, wide direct
  hit, exhaustive hit/miss, and non-exhaustive fallback hit/miss control. The
  generated and source traces agree exactly on authorization,
  direct-intersection phases, and full-candidate checks in Java and JavaScript.
  This proves the source-shaped finite control model and its logical bounds.
  On its own it does not establish path materialization, nested permission
  callbacks, direct-subset/exhaustiveness facts, Clojure semantics, or
  backend/runtime costs; the next specialization discharges the
  path-materialization and direct/exhaustive portions.
- The path-materialization specialization removes the direct-subset and
  exhaustiveness facts from the assumed arrow-control interface. Dafny models
  raw typed relation/permission definitions, all four materialized path
  variants, missing source/target definitions, static cost ranking, exact
  subject-type filtering, and exhaustive iff every path is a relation. It
  proves direct positives are sound and exhaustive direct evaluation is
  complete. Generated Java and JavaScript match
  `calc-permission-paths`/`calc-direct-grant-relations` on 99 CLJ/CLJS fixtures
  each. Adapter certification v2 composes that calculation with actual Datomic,
  Datahike, and DataScript relation IDs. Host-language semantics, arbitrary
  backend states, nested callback truth, and independent review remain open.
- The outer acyclic path fold now preserves source order and work rather than
  reducing the permission union to an unordered Boolean. Dafny proves a
  matching recursion guard performs zero path/callback checks, a mismatched
  direct subject type performs no backend probe, evaluation stops at the first
  effective positive, and each path/callback counter is linear in materialized
  path count. Generated Java and JavaScript match actual CLJ/CLJS
  authorization, realized-path count, per-kind counts, and ordered
  `[path-kind,path-index]` traces on 407 fixtures each. Complete callback
  arguments, nested callback semantics, Clojure lazy-sequence semantics, and
  independent review remain open.

## Public wording audit

The current formal-verification guide defines “verified” only for a mapped
generated operation under its listed assumptions, identifies recursive
generated routing as internal and partial, and requires the manifest to remain
`not-verified`. The generated providers are formal smoke/integration artifacts,
not a shipped supported public engine.

Historical reports use “verified” to mean empirically reproduced or
test-confirmed. They are dated engineering records and are not release-level
formal-verification claims. No README or release-level public statement claims
that the complete v8.0 engine is formally verified.

## Gate evidence

- Clean checksum-locked Dafny cache: 9,367 proof efforts across 23
  source-project invocations, zero errors or timeouts, and no admitted lemmas,
  `assume`, `axiom`, `{:verify false}`, or extern declarations. The forward
  and reverse drive specification functions are opaque but defined, and are
  exposed only through verified one-step unfolding lemmas; opacity is not an
  assumption. The count includes dependency obligations repeated by multiple
  top-level verification invocations; it is pipeline work, not a count of
  unique theorems.
- TLA+/Apalache: all five models typechecked; compact length 12, detailed
  length 6, subproblem length 8, tiered-subproblem length 5, and managed
  projection length 8 passed. All fifteen
  initiation/consecution/implication obligations passed, and all eight
  temporal mutants produced the required counterexample.
- Counterexample corpus: 42 minimized entries replayed by 44 tests and 10,418
  assertions, zero failures/errors.
- Mutation controls: 82 Clojure detectors and 8 Apalache counterexample
  controls; all 90 registered mutants killed.
- Forced-authority non-benchmark CLJ suite: 492 tests, 18,851 assertions,
  zero failures/errors across Datomic, Datahike, and DataScript.
- Forced-authority heavy CLJ suite: 16 tests, 4,047 assertions, zero
  failures/errors.
- Ordinary and forced-authority DataScript CLJS suites: 152 tests, 4,499
  assertions each, zero failures/errors.
- Generated Java production-kernel namespace: 35 tests, 10,393 assertions,
  zero failures/errors.
- Generated JavaScript smoke suite: 55 tests, 10,945 assertions, zero
  failures/errors.
- Locked CLJ/CLJS source closure: 60 named shared/backend roots, 1,330 unique
  reachable definitions across 51 source files, with exact per-root internal
  and external call sets. Unattributed usages inside exact `defrecord` spans
  are assigned to the containing protocol implementation. This prevents silent
  decision-branch omission but is explicitly not a source-refinement proof.
  The separate dispatch ledger proves that all 56 CLJ and 56 CLJS
  `backend/invoke` sites are literal and their 21-key set equals the required
  snapshot-operation contract. Adapter semantics and per-definition theorem
  classification remain open.
- OpenSpec strict validation: passed.
- Heavy benchmark: 9 tests, 3,403 assertions, zero failures/errors; the
  current-cache measurements are recorded in
  `formal/verification/performance-gates.edn`.
- Layered subproblem-cache resource benchmark: 5 tests, 12 assertions, zero
  failures/errors, including separate backend-operation, latency, hit
  cardinality, cached-page cardinality, and miss-finalization dimensions.
- Cross-backend managed-proof resource benchmark: 1 test, 13 assertions, zero
  failures/errors. Growing an unrelated relation by 1,024 edges changed the
  reader-proof p50 by 0.600x on Datomic, 1.006x on Datahike, and 1.043x on
  DataScript. One logical create/delete produced 16/17, 13/13, and 14/14
  committed datom events respectively. These are deliberately separate
  resource dimensions; datom events are not byte or price measurements, and
  the timings isolate proof providers rather than complete managed-hit calls.
- Cross-backend workload matrix: 1 test, 619 assertions, zero failures/errors;
  1,188 raw wall-time samples and 1,188 caller-thread allocation samples across
  Datomic, Datahike, DataScript, cache-free, completed-answer-only, and layered
  modes. Shared-arrow layered/completed-answer p50 latency ratios were 0.167,
  0.090, and 0.147 respectively; caller-thread allocation ratios were 0.206,
  0.077, and 0.134. These measurements do not establish retained heap,
  whole-process allocation, CPU time, or asymptotic bounds.
- The fail-closed performance evaluator independently checks entry weight,
  proof operations, throughput, verifier time, generated artifact bytes, and
  benchmark-noise rules. The post-build artifact gate measured 2,025,531 Java
  source bytes, 1,792,438 Java class bytes, 895,015
  JavaScript-with-runtime bytes, and a 979,106-byte browser bundle against
  reviewed baselines of 1,749,970, 1,597,574, 766,357, and 845,730 bytes,
  respectively, with a 125-percent ceiling. Those dimensions pass. Retained
  live heap remains `:not-established`, so the
  evaluator and release manifest refuse performance cutover instead of
  substituting logical cache weight or the noise-dominated GC
  micro-measurement for a heap bound.
- The outdated and untrusted Lore revision
  `dabb5634b0d44e196e2b6ec63003917b3d445bec`
  reanalyzed immutable EACL revision
  `401d15c3d058a00770856d25f5328289cbcd7971` (tree
  `572a79347573dca2c8b1ff07f9e1156a10d978bd`, snapshot SHA-256
  `0c485a8e4bd48c15db7ebb1c147b8931f14939ca5d57d0a971e241cbc3233580`).
  It found all 22 named source targets, but zero fit Lore's strict Core; all
  remain source-structural candidates (maximum nested traversal depth 3, 142
  unique unsupported operators, and 331 per-function operator occurrences).
  The analyser rejected its pinned old production-refutation witness as
  revision-invalid instead of applying it to the changed implementation.
  The immutable result does not cover the current production or formal source.
  The analyser is not in the trusted computing base, has assurance status
  `none`, and no release gate depends on it. The diagnostic proves no
  production heap, elapsed-time, backend-operation, or whole-engine resource
  claim. EACL retains only the techniques of separating incompatible resource
  dimensions and constructing adversarial lifecycle schedules. Current
  semantic and logical-resource claims are reimplemented in Dafny; actual
  allocation, retained heap, CPU, and latency remain host measurements.
- The captured-current consistency boundary has a Dafny logical-work model
  matched by exact source-call instrumentation: one capability observation,
  one plan decision, and no authentication, backend selection, validation,
  source-scope, ancestry, graph-head, order-hint, or exact-locator call. Paired
  wall-time gates measured
  median trial p95 ratios of 5.237 on the JVM and 3.073 in Node, but absolute
  median p95 overheads of 690 ns and 1,817 ns respectively, within the
  host-specific gates. The ratio is amplified by a roughly 140 ns JVM legacy
  baseline. These measurements establish neither heap peaks nor worst-case
  latency.
- A full verifier replay found that the transparent recursive
  `DriveReverseSpec` made the iterative reverse-driver invariant exceed its
  60-second assertion-batch budget. Raising the timeout would have hidden a
  proof-resource regression. Forward and reverse drive specifications are now
  opaque outside explicit one-step unfolding lemmas. The locked pipeline also
  applies a deterministic Z3 resource limit to every proof effort and emits
  per-module CSV plus an aggregate JSON report. The exact current 23-module
  replay passed 9,367 proof efforts and consumed 3,654,378,786 deterministic
  Z3 resource units; its maximum effort used 34,908,028 of the
  50,000,000-unit limit. End-to-end wall time was not captured for that replay;
  the preceding 9,207-effort run took 1,129.21 local wall seconds. Explicit
  unfolding reduced the
  indexed-driver maximum from 161,654,668 to 10,270,077 resource units
  (15.74x). These are solver-cost gates, deliberately separate from production
  request latency, heap, and logical traversal work.

## Manifest audit result

The generated manifest is correct only if it:

- records `:assurance-status :not-verified`;
- records `:complete-public-engine` as incomplete;
- records the current-cache performance gate as passed while keeping the full
  verified-authoritative cutover pending;
- refuses `:verified-status-allowed?`;
- names complete-public-engine and rollout/performance work among the unmet
  release conditions;
- contains digests for all named source and report inputs and any generated
  artifacts that are present.
