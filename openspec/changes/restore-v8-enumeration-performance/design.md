## Context

EACL v8 routes list and count enumeration through the generated indexed fixed-point engine for every permission root. The routing certificate already distinguishes roots that transitively depend on a recursive strongly connected component from acyclic roots, but production execution currently uses that distinction only for constraints and telemetry. Consequently, the acyclic EACL Explorer schema enters the recursive traversal machine, multiplies work across overlapping grant paths, and can exhaust the default `advanced-datoms` limit at 50,000 resources.

Pagination has a separate integration defect. Datomic can retain authenticated, client-private continuation state, while the DataScript and Datahike adapters invoke the engine without a continuation context. Correct results are reconstructed by replaying from page one, so work and latency grow with page depth. Exact counts similarly drive the complete recursive closure and deduplicate only after traversing overlapping paths, producing large regressions for owner and super-user subjects.

The correction must preserve public Relay pagination and exact count semantics, cursor authentication, snapshot consistency, fail-closed behavior, Clojure/ClojureScript equivalence, and the generated-authority boundary. Raising recursive limits, weakening exact counts, or hand-maintaining an optimized ClojureScript implementation would hide rather than correct the defects.

## Goals / Non-Goals

**Goals**

- Make the verified routing certificate the execution boundary: acyclic permission roots use a verified acyclic enumerator, while roots that depend on recursive SCCs continue to use the recursive fixed-point engine.
- Ensure acyclic list and count operations never consume recursive traversal budgets or report recursive-limit failures.
- Reuse authenticated, client-private continuation state for first visits to adjacent pages on Datomic, DataScript, and Datahike, with correct replay on cache misses.
- Preserve exact, deduplicated enumeration and count results across overlapping grant paths, forward and reverse traversal, all supported backends, and CLJ/CLJS runtimes.
- Restore v7-class performance on matched 10,000- and 50,000-resource workloads, with deterministic logical-work gates as the primary regression signal and normalized latency gates as supporting evidence.
- Update the formal models first, regenerate derived JavaScript/JVM authority, and pass formal verification, clean-generation, refinement, differential, and mutation-control checks.

**Non-Goals**

- Changing the public pagination, cursor, count, or authorization APIs.
- Introducing approximate counts or silently returning partial results.
- Increasing recursive traversal limits as the remedy for acyclic workloads.
- Replacing or weakening the recursive engine for genuinely recursive schemas.
- Moving continuation state into public cursors or provider-owned shared state.
- Treating Explorer UI scheduling changes as a substitute for correcting EACL engine work. Explorer may receive separate defensive UI improvements, but the acceptance workload must be fast without them.

## Decisions

### 1. Make certified schema routing mandatory

The shared enumeration entry point will consult the generated routing certificate for the requested permission root and operation. A root that cannot transitively reach a recursive SCC will be dispatched to the acyclic engine. A root that can reach a recursive SCC will be dispatched to the existing recursive fixed-point engine.

The certificate identity will be bound to the normalized schema and generated authority used by the request. Missing, stale, or inconsistent classification will fail closed rather than guessing an execution path. Forward listing, reverse listing, and exact counting will all use the same classification rule.

This uses the proof boundary already represented by `RoutingCertificate.dfy` and makes the production implementation match the distinction assumed by the formal models. Continuing to route all roots through the recursive engine was rejected because it makes acyclic cost depend on recursive fixed-point behavior and limits. A hand-written adapter heuristic was rejected because it would create an unverified second definition of recursion reachability.

### 2. Promote the verified acyclic engine to generated production authority

The existing `AcyclicEngine.dfy` model will be extended as necessary to define production forward and reverse enumeration, continuation state, ordered merge, deduplication, and exact counting for certified acyclic roots. The implementation will be generated for JVM and browser runtimes; Clojure and ClojureScript integration code will adapt backend indexes to that authority rather than reimplement its semantics.

For multipath grants, the acyclic engine will merge ordered indexed streams and deduplicate resource identities before emission and counting. Its result must equal the denotational authorization set, independent of path overlap or traversal order. Page construction will stop after the requested window and bounded lookahead; exact count will consume the merged unique stream once rather than materializing recursive fixed-point rounds.

Maintaining the current recursive path with larger limits was rejected because the 50,000-resource super-user case already demonstrates linear valid work crossing a recursion-specific ceiling, and higher limits still leave multi-second traversal. Adding backend-specific fast paths was rejected because correctness and performance would diverge across runtimes.

### 3. Standardize authenticated client-private continuation ownership

The engine-facing API will expose an adapter-neutral continuation context with the same trust boundary as the existing Datomic design. Datomic, DataScript, and Datahike will each provide bounded client-private storage. Continuation entries will be keyed by all semantics that can change the emitted sequence, including source/client identity, adapter, normalized schema or certificate identity, operation and direction, subject, resource type, permission, constraints, snapshot, and authenticated query lineage.

Only opaque authenticated cursor material remains public. Traversal stacks, merge positions, visited state, and engine internals will never be serialized into cursors or placed in provider-owned global state. A missing or evicted continuation deterministically replays from authenticated cursor lineage and returns the same page; it is a safe slow path, not a correctness failure. Mutation, snapshot, and rebasing behavior retain the existing public contract.

Serializing the continuation into the cursor was rejected because it exposes internal state and expands the authentication surface. Treating replay as the normal DataScript/Datahike behavior was rejected because its work grows with page ordinal even though the API already has a safe private continuation model.

### 4. Separate recursive safety limits from acyclic work accounting

Recursive traversal limits remain enforced on the recursive route with their existing fail-closed semantics. Certified acyclic requests will not increment recursive counters or throw `:eacl.recursive-traversal/limit-exceeded`. They will expose separate deterministic work telemetry for indexed datoms/scans, merge advances, duplicate suppression, continuation hits and misses, and emitted resources.

Explicit public bounds such as `count-limit`, page size, cursor validity, and backend scan safety remain enforced. Exact count continues either to return the exact authorized cardinality or the documented bounded failure; it will not silently approximate.

Acyclic telemetry provides a stable performance contract that can be tested across hardware and runtimes. It also prevents a future latency optimization from masking superlinear logical work.

### 5. Use formal-first derivation and evidence

Changes to routing, acyclic enumeration, continuation, counting, or shared core semantics will begin in the Dafny authority. Verification obligations will cover:

- sound and complete classification of roots that can reach recursive SCCs;
- equivalence of acyclic enumeration and exact counting to denotational authorization;
- stable ordering and duplicate-free forward/reverse pagination;
- continuation-resume equivalence to deterministic replay;
- separation of recursive limits from certified acyclic work;
- bounded page work and stated acyclic count-work relationships; and
- refinement parity between JVM and browser generated outputs.

The accepted formal source will regenerate the JVM and browser artifacts. Clean-generation checks must show no hand edits or stale output. Cross-runtime vectors, backend differential tests, mutation controls, and the counterexample ledger will be extended so the DataScript/Datahike continuation defect and the unreachable acyclic route cannot recur unnoticed.

### 6. Gate performance with matched baselines and deterministic work

The benchmark suite will retain checked-in workload definitions and expected logical-work envelopes for the Explorer multipath schema at 10,000 and 50,000 servers. It will measure cold first page, first visits to adjacent pages, exact owner counts, and exact super-user counts on Datomic, DataScript, and Datahike where the backend/runtime is supported.

Release gates will require:

- identical authorization sets, ordering, page boundaries, and exact counts against the denotational oracle and v7 fixtures;
- no growth in resumed page traversal work as page ordinal increases, apart from the requested page window and bounded lookahead;
- no recursive counter activity or recursive-limit failure for certified acyclic Explorer roots at 50,000 resources;
- acyclic count work within the checked-in linear envelope derived from indexed inputs and unique outputs, including overlapping grant paths; and
- matched-host warmed median latency no worse than 2.0 times the recorded v7 baseline for the named scenarios, with logical-work gates remaining authoritative when timing variance exceeds the harness tolerance.

Baselines will be produced by the same harness, dataset seed, query sequence, runtime mode, and host process isolation. Raw milliseconds alone were rejected as the primary gate because they are host-sensitive; work counters alone were rejected because user-visible latency must also be restored.

Applicability is exact and fail-closed: operating system, architecture,
operating-system version, CPU model, logical processor count, physical or
container memory, maximum JVM heap, JDK, VM implementation/vendor,
backend/runtime, and measurement method must all match the baseline. A missing
field is a harness error. A mismatched runner records the raw candidate samples
and an explicit `not-applicable` result; it cannot turn incomparable raw
milliseconds into either a pass or a regression. Portable CI continues to
enforce correctness and deterministic work, while release qualification needs
separately applicable matched-host latency evidence.

### 7. Certify data-dependent recursion activation

A recursive permission SCC does not by itself require fixed-point data work. The generated route decision therefore combines the schema certificate with a snapshot-bound activity bit. For each requested root, shared core derives the complete set of in-SCC arrow-permission guards. Every backend implements an exact indexed `relation-populated?` prefix probe. When all guards are empty, the recursive contribution is empty and the generated route selects the bounded acyclic evaluator; the first populated guard selects the recursive fixed-point evaluator.

Same-resource permission aliases need no data guard: they are positive unions, and the acyclic evaluator's visited-state rule computes their reachable base grants. The Dafny authority models the empty guarded recursive contribution and owns the final route decision. Backend prefix equivalence remains an explicit adapter obligation with portable certification tests.

The Explorer schema editor exposes Non-recursive and Recursive preset tabs. Selecting a tab changes only the unsaved draft; users still explicitly press Write Schema, preserving the existing mutation boundary.

### 8. Size cached projections to the bounded consumer window

The ordinary list path retains small relationship projection prefixes because
it needs only one page plus lookahead. Exact count already consumes results in
bounded count windows, but a fixed 32-item projection prefix makes a cold
cached count reopen the same indexed adjacency many times. This is especially
costly in ClojureScript, where every DataScript seek crosses more runtime
machinery than on the JVM.

For the duration of one acyclic count page, shared core raises the projection
prefix to at least the count window plus its has-next sentinel and realizes
that bounded window before restoring the ordinary prefix. The cache key
includes the selected prefix size, so count projections cannot be confused
with list-page projections. `SchemaPlanCost.dfy` proves that the chosen count
prefix covers the window and sentinel and never weakens an explicitly larger
configured bound.

Disabling the cache for counts was rejected because it would discard reusable
subproblems and make enabled-cache behavior unexpectedly slower than uncached
behavior. Raising the global projection prefix was rejected because it would
inflate retained list-page state even when callers request only a small page.

## Risks / Trade-offs

- **Incorrect route classification could bypass required fixed-point work.** Mitigation: bind the certificate to normalized schema identity, prove recursive reachability classification, fail closed on mismatch, and run differential recursive/acyclic mutation controls.
- **A backend could misreport a cycle guard as empty.** Mitigation: make relation-prefix population a required snapshot operation, certify it against forward index membership on all adapters, and retain mutation controls that force an active recursive edge onto the acyclic route.
- **Ordered multipath merging can retain per-path state.** Mitigation: bound state by active indexed streams and page/count operation, prove duplicate-free equivalence, and benchmark high-overlap schemas.
- **Continuation caches consume client memory and may be evicted.** Mitigation: retain bounded LRU/TTL behavior, instrument occupancy and eviction, and preserve replay as a correct fallback.
- **Exact acyclic counts still require reading relevant indexed grants.** Mitigation: avoid recursive rounds and duplicate materialization, expose linear work counters, and keep explicit public count bounds.
- **Generated browser artifacts may grow.** Mitigation: track bundle size in generation checks and keep backend integration outside the verified kernel where it does not define authorization semantics.
- **Latency gates can be noisy.** Mitigation: compare v7 and v8 in the same isolated harness, use warmed medians and tolerance rules, and make deterministic logical-work envelopes the primary CI failure signal.

## Migration Plan

1. Add failing formal counterexamples and executable regression tests for acyclic roots entering recursive traversal, DataScript/Datahike page replay, overlapping-path counts, and the 50,000-server Explorer workload.
2. Extend and verify routing, acyclic enumeration, continuation, count, and cost models.
3. Regenerate JVM and browser authority and pass clean-generation and refinement checks.
4. Wire the shared engine and all three backends to certified routing and the standardized continuation context.
5. Run unit, differential, cross-runtime, formal smoke, mutation, and 10,000-resource performance suites before the heavier 50,000-resource acceptance suite.
6. Validate EACL Explorer subject/permission switching, page traversal, owner counts, and super-user counts against the local v8 integration.

The public API and cursor contract remain compatible, so no consumer migration is expected. The change can be rolled back as one engine/backend release if acceptance gates fail; no persisted data or public cursor format migration is introduced.

## Open Questions

- The implementation may place the bounded continuation store in shared core or behind a small backend protocol. The choice will be made during implementation based on dependency direction, but the authenticated key, ownership, eviction, and replay semantics above are fixed.
- The exact checked-in logical-work constants will be calibrated from the verified algorithm and v7/v8 harness before the implementation PR is accepted; they may tighten the stated envelopes but may not relax the 50,000-resource, no-recursive-budget, or 2.0-times-v7 requirements.
