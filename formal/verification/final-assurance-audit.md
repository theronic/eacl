# Final assurance-claim audit

Date: 2026-08-03

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
4. decoded relationship-page, cursor-continuation, and authenticated
   cache-validation decisions are routed through the generated decision
   kernels in the internal verified modes; and
5. strict generated Java and JavaScript boundaries evaluate complete
   materialized `can?`, lookup, and count requests, and the Java reference
   agrees with cached and uncached public state traces on all three backends.

These claims remain conditional on the trusted toolchain, generated-code
compilers, runtimes, FFI conversion, adapter contracts, canonicalization,
cryptography, collision resistance, entropy/key management, clocks, and
configured resource limits listed in the manifest and trusted-boundary
documents.

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
  retained-logical-state, and traversal-limit error outcomes are now compared
  in `verified-shadow` mode on all JVM adapters; the same DataScript public
  trace passes against generated JavaScript. Complete public error,
  provenance, graph-identity, cross-backend generated-authority, and release
  cutover gates remain incomplete.
- The current-generation cache, exact/arbitrary-DB bypass, scalar stamp law,
  least-fixed-point managed frame, and selected-snapshot rendering are proved
  and integrated for all three adapters. This is a conditional cache
  refinement claim, not complete public-engine verification.
- Lore's `A, B, A` analysis also exposed an implementation-level recursive
  ownership mismatch: `resolve!` recorded
  `(lifecycle, tier, semantic-key)` while `lookup!` checked only
  `(tier, semantic-key)`. A recursive lookup could therefore attempt to join
  its own in-flight computation. The production key shape is now identical on
  both paths, with CLJ/CLJS regression, Dafny lifecycle lemmas,
  counterexample replay, and mutation control.
- The same audit found that recursive `resolve!` self-bypass invoked a callback
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
  maximum pending-set cardinality; cumulative enqueues remain a separate Lore
  resource dimension.
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
- Production shadow comparison covers recursive traversal values, ordering,
  page flags, counts, Boolean decisions, dimensionally matching cache-free
  resource counters, logical retained-state units, and typed traversal-limit
  failures including configured numeric limits, and recursive stale-cursor
  render rejection. It does not yet cover the complete public typed-error surface,
  provenance, graph identity, or required rollout volumes. Shadow diagnostics
  are fail-open with respect to legacy authority and redact request/result
  values without emitting guessable hashes.
- No independent security/formal-methods review has been obtained.
- The current-cache performance gate passes. On the routed 100,000-result
  recursive-chain fixture, the retained raw gate measured generated authority
  at 1.49x legacy p50 and 1.82x legacy p95. A 2026-08-03 dimensional recheck
  measured 1.17x p50 and 1.38x p95, with identical page results and all twelve
  logical resource measures equal, inside the existing 2.0x p95 gate. Full
  cutover remains blocked by acyclic ordered-merge source refinement, shadow
  coverage, and review rather than recursive cache-hit cost.
- The optimized ordered merge passes the source-specialization non-regression
  gate for both a 20-value page prefix and complete 20,000-value consumption.
  After the explicit-presence fixes, ascending/descending median trial-level
  p95 ratios were 0.983/0.998 for the prefix and 0.974/0.970 for complete
  consumption against the identical legacy selection. These are wall-time
  benchmarks over a pure in-memory merge; they do not establish heap or
  backend-operation theorems.

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

- Clean checksum-locked Dafny cache: 9,193 proof efforts across 21
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
- Counterexample corpus: 26 minimized entries replayed by 28 tests and 545
  assertions, zero failures/errors.
- Mutation controls: 35 Clojure detectors and 8 Apalache counterexample
  controls; all 43 registered mutants killed.
- Public non-benchmark CLJ suite: 464 tests, 16,921 assertions, zero failures/errors.
- DataScript CLJS suite: 135 tests, 4,227 assertions, zero failures/errors.
- Generated Java suite: 37 tests, 7,591 assertions, zero failures/errors.
- Generated JavaScript suite: 25 tests, 847 assertions, zero failures/errors.
- Locked CLJ/CLJS source closure: 60 named shared/backend roots, 1,287 unique
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
  benchmark-noise rules. Those dimensions pass. Retained live heap remains
  `:not-established`, so the evaluator and release manifest refuse performance
  cutover instead of substituting logical cache weight or the noise-dominated
  GC micro-measurement for a heap bound.
- Lore revision `dabb5634` reanalyzed immutable EACL revision
  `08ec9c74496ca27173cb4fb185f39fd505ad613a` (tree
  `fe7d2d7bbeba74ac95c354d64e01272c5ac9f2ae`, snapshot SHA-256
  `284f462ac6d2b95aac3f63aaafb410582f638103212d0c3c4d0ead855fc67991`).
  It found all 22 named source targets, but zero fit Lore's strict Core; all
  remain source-structural candidates (maximum nested traversal depth 3, 142
  unique unsupported operators and 331 per-function operator occurrences).
  Lore also correctly rejected its old
  PR #101 production-refutation witness as revision-invalid rather than
  applying it to the changed cache. Consequently it proves no current
  production heap, elapsed-time, backend-operation, or whole-engine resource
  claim.
- A full verifier replay found that the transparent recursive
  `DriveReverseSpec` made the iterative reverse-driver invariant exceed its
  60-second assertion-batch budget. Raising the timeout would have hidden a
  proof-resource regression. Forward and reverse drive specifications are now
  opaque outside explicit one-step unfolding lemmas. The locked pipeline also
  applies a deterministic Z3 resource limit to every proof effort and emits
  per-module CSV plus an aggregate JSON report. The exact 21-module replay
  passed 9,193 proof efforts in 1,114.31 local wall seconds; its maximum effort
  used 34,908,028 of the 50,000,000-unit limit. Explicit unfolding reduced the
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
