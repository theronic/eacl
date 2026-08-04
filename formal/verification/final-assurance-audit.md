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
- Production shadow comparison covers recursive traversal values, ordering,
  page flags, counts, Boolean decisions, dimensionally matching cache-free
  resource counters, logical retained-state units, and typed traversal-limit
  failures. It does not yet cover the complete public typed-error surface,
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

- Clean checksum-locked Dafny cache: 9,061 verifier obligations across 21
  source-project invocations, zero errors or timeouts, and no admitted lemmas,
  `assume`, `axiom`, `{:verify false}`, opaque, or extern declarations. The
  count includes dependency obligations repeated by multiple top-level
  verification invocations; it is pipeline work, not a count of unique
  theorems.
- TLA+/Apalache: all five models typechecked; compact length 12, detailed
  length 6, subproblem length 8, tiered-subproblem length 5, and managed
  projection length 8 passed. All fifteen
  initiation/consecution/implication obligations passed, and all eight
  temporal mutants produced the required counterexample.
- Counterexample corpus: 21 minimized entries replayed by 23 tests and 413
  assertions, zero failures/errors.
- Mutation controls: 29 Clojure detectors and 8 Apalache counterexample
  controls; all 37 registered mutants killed.
- Public non-benchmark CLJ suite: 455 tests, 16,613 assertions, zero failures/errors.
- DataScript CLJS suite: 135 tests, 4,227 assertions, zero failures/errors.
- Generated Java suite: 33 tests, 7,551 assertions, zero failures/errors.
- Generated JavaScript suite: 24 tests, 836 assertions, zero failures/errors.
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
- Lore revision `dabb5634` reanalyzed immutable synthetic EACL revision
  `7cace3f6febafa99fcf707be02af6bb7332e5de3` (tree
  `a035685ed7d6a9f303f3baf65318b849e49fa922`, snapshot SHA-256
  `329d40edd8ba3d718f012c0f38cb3234e1eee6d31c9d030f23676287c340ec06`).
  It found all 22 named source targets, but zero fit Lore's strict Core; all
  remain source-structural candidates. Lore also correctly rejected its old
  PR #101 production-refutation witness as revision-invalid rather than
  applying it to the changed cache. Consequently it proves no current
  production heap, elapsed-time, backend-operation, or whole-engine resource
  claim.

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
