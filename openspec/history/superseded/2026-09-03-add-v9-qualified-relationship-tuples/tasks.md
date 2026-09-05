> **DEPRECATED / SUPERSEDED — 2026-09-04.** This unimplemented proposal is retained for historical review only. Its requirements and unchecked tasks are withdrawn from the implementation plan; do not apply or sync these deltas into the main specifications. Use the [replacement v9 proposal](../2026-09-04-add-v9-caveats-and-expiring-relationships/proposal.md).
>
> The replacement uses **seven-slot Caveat + expiry-only endpoint tuples**, removes scheduled activation and the mandatory expiration index, and retains time-aware cache/cursor checks. See the [review findings and preserved REPL evidence](review-2026-09-04.md). The original artifact follows unchanged.

## 1. Freeze the v9 contracts

- [ ] 1.1 Add relationship storage version 9 and bump engine, adapter, cache, subproblem, and cursor ABI identifiers; verify old artifacts and old storage stamps are rejected in contract tests.
- [ ] 1.2 Add shared constants for the two v9 attribute names, value arity eight, endpoint-local identity arity four plus the owning endpoint eid, and qualifier positions; verify every backend imports the shared definitions rather than duplicating slot numbers.
- [ ] 1.3 Document the canonical order `[endpoint identity ×4, caveat, caveat-context, valid-from, valid-until]`; verify schema docs, API docs, tests, and secure-format fixtures use the same order.
- [ ] 1.4 Add startup qualification that rejects any v7 relationship datom or mixed v7/v9 data; verify a populated v7 database fails before serving while a fresh/rebuilt v9 database opens.
- [ ] 1.5 Remove any proposed dual-read, automatic migration, or per-relation fallback path from v9 code and docs; verify instrumentation observes no v7 relationship seek during v9 authorization.

- [ ] 1.6 Persist a separate semantic capability epoch and fence Phase 2 activation at current-basis selection and mutation commit, draining old pinned clients or using a coordinated cutover; verify long-lived Phase 1 clients, cache/token restore, and speculative operations cannot bypass the epoch.

## 2. Phase 1 — install fixed eight-slot storage

- [ ] 2.1 Replace Datomic forward/reverse relationship schema attributes with fixed eight-component heterogeneous tuples and verify installed `:db/tupleTypes` match the OpenSpec exactly.
- [ ] 2.2 Replace Datahike forward/reverse relationship schema attributes with the same eight-component types and verify physical-schema qualification rejects drift.
- [ ] 2.3 Replace Datalevin relationship schema and write-policy stamp rules for the v9 attributes and verify admitted writes advance only the affected relation generation.
- [ ] 2.4 Replace DataScript relationship attributes with fixed eight-element vectors and verify JVM/Node schema tests use complete arity.
- [ ] 2.5 Update clean database bootstrap and storage-version stamping for v9 and verify no v7 attribute is read as an authoritative relationship source.
- [ ] 2.6 Add Phase 1 admission rules requiring slots five and six to be nil; verify encountered out-of-band non-nil Caveat qualifiers fail the affected operation with a typed error until Phase 2 is enabled.

## 3. Phase 1 — replace the endpoint codec and scans

- [ ] 3.1 Replace four-slot constructors, decoders, validators, peer-half construction, and retractions with eight-slot equivalents; verify round-trip, symmetry, nil normalization, and malformed-arity tests.
- [ ] 3.2 Add owner-qualified logical-identity extraction and qualifier extraction; verify Caveat/context/validity changes do not alter identity.
- [ ] 3.3 Update forward and reverse endpoint scans to page by component four while carrying components five through eight; verify ascending and descending order across adjacent relation prefixes.
- [ ] 3.4 Implement full-eight-slot direction-specific low/high bounds for exact identity probes and vector seeks; verify nil-low, descending-high, negative/extreme timestamps, whole-identity exclusion, and zero/one/multiple qualifier variants.
- [ ] 3.5 Update direct membership to obtain qualified candidates from one endpoint seek and verify permanent positive, permanent negative, future, active, and expired checks use one authoritative candidate stream, with separately measured integrity/proof reads and duplicate-group lookahead.
- [ ] 3.6 Update relationship filters, reads, counts, relation-in-use checks, and schema-orphan probes for v9 values; verify future and expired assertions remain visible to stored-data safety checks.
- [ ] 3.7 Update secure digests, canonical records, content proofs, and benchmark fixtures to include all eight components; verify each qualifier component changes the digest.

## 4. Phase 1 — make mutation and cleanup identity-aware

- [ ] 4.1 Change `:create` conflict detection to serialize on the owning endpoint plus the first four components at commit time; verify concurrent creates with different validity bounds yield exactly one winner on each writer topology.
- [ ] 4.2 Implement `:touch` as canonical old-pair replacement and verify an unchanged touch is idempotent, stale concurrent touch/delete plans replan or fail, and a changed interval leaves one pair.
- [ ] 4.3 Implement `:delete` by logical identity without qualifier input and verify it removes future, active, expired, partial, and duplicate corrupt variants without touching adjacent identities.
- [ ] 4.4 Update batch normalization so identical updates deduplicate and semantically different updates for one logical identity fail before submission; verify typed error data.
- [ ] 4.5 Update object deletion to remove both v9 halves and owned auxiliary values; verify self-relationships, missing endpoints, and one-sided corruption.
- [ ] 4.6 Update integrity reports for arity, peer symmetry, owner-qualified identity uniqueness, qualifier agreement, and invalid slot-five/slot-six combinations; verify encountered authoritative faults abort the operation, including when used by an exclusion.
- [ ] 4.7 Ensure every effective relationship/qualifier mutation advances each affected relation generation once per admitted transaction; verify unrelated relation generations remain unchanged.

## 5. Phase 1 — implement native validity

- [ ] 5.1 Add public relationship validity input and normalize supported instant forms to exact UTC epoch-millisecond integers; verify omitted, start-only, end-only, bounded, zero-width, reversed, and out-of-range cases.
- [ ] 5.2 Preserve the public three-field logical `Relationship` value while carrying qualifiers in update options; verify permanent writes emit four trailing nils.
- [ ] 5.3 Add one trusted clock sample to each top-level authorization view and verify one operation cannot observe two wall-clock instants.
- [ ] 5.4 Capture a fresh valid-time for each client-targeted operation even when reusing a minimize-latency database pin; verify explicit snapshots freeze both basis and time.
- [ ] 5.5 Preserve valid-time through `eacl/with`, `eacl/with-schema`, and chained speculative snapshots; verify prospective scheduled grants are deterministic.
- [ ] 5.6 Add an explicit EACL administrative temporal-view API without accepting caller-owned native database values; verify transaction time and valid time remain distinct.
- [ ] 5.7 Apply `[valid-from, valid-until)` filtering before every direct, union, intersection, exclusion, arrow, recursion, lookup, count, and expansion contribution; verify exact boundary matrices.
- [ ] 5.8 Add a regression where expiry of a subtracting relationship changes `viewer - banned` from no-permission to has-permission without a database write.
- [ ] 5.9 Extend `read-relationships` with stored/effective status for permanent, scheduled, active, and expired relationships; verify create conflicts still see inactive stored assertions.

## 6. Phase 1 — temporal cache, cursor, and collection

- [ ] 6.1 Extend leaf results with conservative temporal stability intervals; verify future-start, active-expiry, already-expired, and permanent certificates.
- [ ] 6.2 Compose temporal stability through every permission operator, including negative proofs and exclusions; verify no result is reused across a possible boundary.
- [ ] 6.3 Gate exact and managed cache reuse on ordinary dependency proofs plus valid-time interval membership; verify unchanged relation versions cannot carry a result across time.
- [ ] 6.4 Implement a safe exact-valid-at key or no-publication fallback when temporal proof is incomplete; verify cached and uncached semantics agree.
- [ ] 6.5 Authenticate temporal mode, selected valid-time, and both stability bounds in continuations; verify pinned resumption ignores wall-clock validity crossings, live crossings require explicit restart, and skipped candidates/frontier/lookahead are certified.
- [ ] 6.6 Add the specified eight-slot subject-owned expiration-index value for each finite `valid-until` and none for permanent/start-only relationships; verify create, touch, delete, cleanup, and speculative parity.
- [ ] 6.7 Implement bounded exact-value expiration collection after a retention cutoff; verify authorization never consults the index and a stale collector cannot delete a rescheduled relationship.
- [ ] 6.8 Add expiration-index reconciliation and metrics; verify missing/stale derived entries affect diagnostics or collection only, never authorization.

## 7. Phase 1 — rebuild-only release qualification

- [ ] 7.1 Write export/re-transact/reseed guidance for populated v7 installations and verify it contains no automatic conversion or mixed-runtime promise.
- [ ] 7.2 Add startup tests for fresh, rebuilt, schema-only-legacy, populated-v7, mixed, and wrong-stamp databases; verify failures occur before request service.
- [ ] 7.3 Benchmark v7 versus Phase 1 v9 direct checks, negative checks, adjacency, arrows, lookup, count, and pagination; report candidate-stream seeks, pair/ownership/content validation, advanced datoms, and total request work separately.
- [ ] 7.4 Benchmark permanent-heavy, 1%-temporal, and temporal-dense million-relationship datasets on Datomic Pro; include adversarial expired/future prefixes and suffixes, both scan directions, endpoint-degree distributions, exact-page exhaustion, and default-limit failures; report latency, segment density, peer-cache pressure, and scan throughput.
- [ ] 7.5 Benchmark durable Datahike/Datalevin size and write amplification plus DataScript JVM/Node allocation and ordering; verify accepted release thresholds.
- [ ] 7.6 Run the CI-equivalent JVM suite through nREPL, the DataScript ClojureScript build last, heavy storage benchmarks explicitly, and `bin/formal source-closure`; verify Phase 1 gates are clean.

- [ ] 7.7 Record numerical latency, storage, allocation, and scan-work acceptance budgets before paired qualification; distinguish raw index probes from complete public authorization and warm-peer evidence from cold/storage evidence.

## 8. Phase 2 — add Caveat schema and canonical IR

- [ ] 8.1 Extend the SpiceDB parser with top-level Caveat definitions and verify source positions, comments, duplicate names, parameters, and expression boundaries.
- [ ] 8.2 Define a versioned EACL SpiceDB Caveat compatibility profile and canonical typed IR; pin the reference SpiceDB release/commit and define every admitted type/coercion/function; verify a typed encoding for doubles and exact int/uint through the existing secure-format envelope, including stable CLJ/CLJS bytes.
- [ ] 8.3 Implement static name, type, overload, return-type, recursion/complexity, and size validation; verify unsupported CEL constructs fail schema writes explicitly.
- [ ] 8.4 Persist Caveat definitions as schema entities and include them in read, compare, replacement, generation, and content-proof logic; verify changing a definition invalidates derived plans.
- [ ] 8.5 Extend relation type references to allow `with caveat` and verify optional versus required Caveat branches for direct, wildcard, and subject-relation subjects.
- [ ] 8.6 Add schema replacement guards for Caveat definitions referenced by relation branches or stored relationships; verify removal and incompatible parameter/type changes cannot invalidate stored context or relation branches, including races with relationship writes.
- [ ] 8.7 Build a shared Caveat schema conformance corpus from the declared SpiceDB profile and verify every supported runtime accepts/rejects the same definitions.

## 9. Phase 2 — enable tuple Caveat and context qualifiers

- [ ] 9.1 Enable slot five as the schema-level Caveat definition eid and verify a relationship may contain zero or one Caveat allowed by its exact relation branch.
- [ ] 9.2 Add `:eacl.caveat-context/payload` for sparse internal context entities and verify an empty bound context uses nil slot six and allocates no entity.
- [ ] 9.3 Canonically encode and type-check non-empty relationship-bound context into one payload datom; verify round-trip for every supported Caveat parameter type and configured limit.
- [ ] 9.4 Enforce immutable, singly-owned, non-deduplicated context lifecycle; verify touch replaces context and delete retracts it atomically.
- [ ] 9.5 Reject context-without-Caveat, disallowed Caveats, unknown context keys, wrong types, oversize values, and multiple Caveats; verify no partial endpoint/context data commits.
- [ ] 9.6 Extend relationship reads to return Caveat name and bound context while preserving logical identity; verify empty and non-empty context representations.
- [ ] 9.7 Update `:create`, `:touch`, and `:delete` tests to match SpiceDB identity semantics; verify Caveat/context differences never create parallel relationships.
- [ ] 9.8 Extend cleanup, integrity, relation/Caveat in-use checks, and unknown-writer proofs over slots five/six and context payloads; verify missing/shared context raises typed authoritative-state errors and in-place payload changes invalidate prior content proofs.

## 10. Phase 2 — implement Caveat evaluation and public results

- [ ] 10.1 Add bounded canonical request-context admission to Caveat-aware permission and lookup requests; verify caller mutation after admission cannot alter an in-flight request.
- [ ] 10.2 Implement relationship-bound-over-request context precedence and parameter type checking; verify shallow parameter replacement, Caveat type checks after precedence, and rejection of nested-map request injection.
- [ ] 10.3 Implement deterministic bounded CEL compilation/evaluation for the declared compatibility profile; verify true, false, partial, error, and resource-limit outcomes.
- [ ] 10.4 Return residual conditions and canonical missing field names for partial evaluation; verify missing context is not prematurely collapsed to false inside the engine.
- [ ] 10.5 Compose definite and conditional memberships through union, intersection, exclusion, arrows, subject relations, and recursion; verify SpiceDB-derived truth tables, bound-context-sensitive residual identity, conditional cycles, terminating fixed points, and typed work-limit failures.
- [ ] 10.6 Add a public three-state check returning `:has-permission`, `:no-permission`, or `:conditional-permission`; verify conditional responses include missing fields.
- [ ] 10.7 Keep `can?` fail-closed by returning true only for has-permission; verify conditional results return false while typed evaluation/integrity failures propagate and cannot become false subtracting operands.
- [ ] 10.8 Add Caveat-aware lookup results and explicit definite-only compatibility behavior; verify conditional candidates are classified, not silently granted.
- [ ] 10.9 Ensure temporal inactivity is checked before context entity reads and evaluator invocation; verify expired/future Caveated edges perform no Caveat evaluation.

## 11. Phase 2 — complete cache, cursor, and adversarial safety

- [ ] 11.1 Separate pre-evaluation lookup scope, dependency certificates, and authenticated value data for request context, definitions, bound payloads, evaluator identity, permissionship, residuals, and missing fields; verify each answer-affecting change causes a miss.
- [ ] 11.2 Authenticate conditional result values and prevent conditional/definite substitution by shared cache providers; verify adversarial replay tests.
- [ ] 11.3 Extend static dependency closure and runtime witnesses for all possible Caveat paths; verify an unvisited conditional branch cannot make cached authorization unsound.
- [ ] 11.4 Authenticate request context, evaluator identity, and conditional lookup policy in cursors; verify context changes between pages require mismatch/restart.
- [ ] 11.5 Cover relationship-bound context payloads in unknown-writer proofs and verify in-place out-of-band mutation invalidates prior answers.
- [ ] 11.6 Add fuzz/property tests for nested context, numeric boundaries, timestamps, durations, IP addresses, lists/maps, residual algebra, and cache canonicalization.
- [ ] 11.7 Add regression fixtures inspired by published SpiceDB Caveat cache/conditional-result advisories and verify EACL cannot upgrade a conditional or malformed path to a grant.
- [ ] 11.8 Differentially execute the supported corpus against a pinned SpiceDB reference and every EACL runtime; block release on semantic divergence.

## 12. Documentation, performance, formalization, and OpenSpec gates

- [ ] 12.1 Update schema, relationship write/read, Caveat context, valid-time, permission result, lookup, cache, cursor, cleanup, and operations documentation; verify examples cover a 6–9 September grant with an IP Caveat.
- [ ] 12.2 Document that bound context overrides request context and that Caveat/context/validity do not create duplicate relationships; verify examples use touch to replace qualifiers.
- [ ] 12.3 Document the eight-slot ceiling and the future first-class grant-assertion boundary; verify no docs imply support for multiple qualifier variants.
- [ ] 12.4 Benchmark uncaveated, Caveated-empty-context, Caveated-bound-context, true, false, and conditional paths across backends/runtimes; report context point-read and evaluator overhead.
- [ ] 12.5 Update formal models or executable decision inventories for validity, conditional permissionship, context precedence, and owner-qualified identity uniqueness; verify generated artifacts and refinement tests are clean.
- [ ] 12.6 Run Phase 2 JVM and ClojureScript suites using the project-prescribed nREPL workflow, heavy Caveat/storage benchmarks explicitly, and `bin/formal source-closure`; verify all release gates pass.
- [ ] 12.7 Run strict OpenSpec validation and verify proposal, design, tasks, and all six capability delta specs are accepted.


## 13. Adversarial regression gates from the 2026-09-04 review

- [ ] 13.1 Exercise corrupt/missing/shared context, duplicate qualifiers, malformed validity, and unsupported Phase 1 qualifiers under `viewer - banned`; verify typed whole-operation failure and no successful cache/page publication.
- [ ] 13.2 Verify owner-qualified identity with equal tuple prefixes on different subjects/resources; exercise concurrent creates/touches/deletes, unknown-writer qualification, bounded repair, and ambiguous context ownership on every supported writer topology.
- [ ] 13.3 Verify a stable union answer does not widen an unstable child denotation's certificate; test backward administrative valid-time, pinned cursor resumption, and newly active candidates before a live cursor position.
- [ ] 13.4 Verify expiration-index-only repair changes maintenance diagnostics without changing authorization content proofs; round-trip the physical index and race collection with rescheduling.
- [ ] 13.5 Verify clock uncertainty cannot grant by expiring a subtracting edge early; explicit temporal views still use exact selected time.
- [ ] 13.6 Verify every admitted Caveat type, shallow context merge, type conversion after precedence, reference/profile pin, recursive residual termination, and conditional/error distinction on CLJ and Node before Phase 2 activation.
