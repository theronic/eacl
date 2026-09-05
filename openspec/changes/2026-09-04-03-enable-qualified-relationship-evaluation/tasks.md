## 1. Complete temporal/conditional formal models before engine code

- [x] 1.1 Extend edge and permission denotation models with optional qualifier refs, prepared-but-unattached qualifier states, atomic publication, authoritative qualifier faults, and one captured evaluation time; verify unattached qualifiers have no denotation and all finite results are total and deterministic.
- [x] 1.2 Model Caveat true/false/conditional evidence through union, intersection, exclusion, arrows, and supported recursion; verify residual algebra and fault propagation against exhaustive truth tables.
- [x] 1.3 Model exclusive `valid-until`, including expiring positive, intermediate, and subtracting evidence; verify counterexamples kill any monotonic “time only removes permission” assumption.
- [x] 1.4 Prove witness-aware temporal stability intervals for every operator/result outcome and no-publication fallback when completeness is unavailable; verify killed horizon/minimum rules fail.
- [x] 1.5 Model cache acceptance and cursor continuation with source/schema/Relation/qualifier proofs, request context, evaluator identity, pinned/live temporal mode, certified time interval, and result kind; verify stale grants, expired bans, and conditional aliases are rejected.
- [x] 1.6 Register models, resource pins, mutation controls, and assurance mappings; verify `bin/formal fast` and every affected formal gate are green before editing production engine/cache/cursor code.

## 2. Carry qualifier refs through one scan engine

- [x] 2.1 Extend the backend scan contract to expose opposite eid plus optional qualifier eid in an allocation-minimal measured representation; verify order, bounds, uniqueness, and public result shape remain unchanged.
- [x] 2.2 Update Datomic, Datahike, DataScript, and Datalevin forward/reverse/direct scan primitives to return aligned qualifier data from the single v9 stream; verify no adapter reads a second Relationship attribute.
- [x] 2.3 Add one shared edge-qualification seam and retain the existing nil-eid fast path; verify instrumentation records zero qualifier reads and no per-edge qualifier map allocation for ordinary fixtures.
- [x] 2.4 Preserve deadlines, cancellation, dimensional work accounting, scan-response cache scope, and continuation heads with the new compact edge shape; verify filtered qualified candidates count against existing bounds and no fill-to-page loop hides incomplete work.

## 3. Resolve and cache immutable qualifiers

- [x] 3.1 Implement exact qualifier fetch/decode/validation across bundled backends, including format, certified creation `t`/version, Caveat allowance, context, and expiry; verify malformed/dangling fixtures fault while managed hot-path instrumentation performs no reverse graph scan for writer-certified ownership.
- [x] 3.2 Add one bounded request-local qid cache shared by recursive/operator paths; verify one distinct non-`nil` qid causes at most one qualifier fetch per top-level operation.
- [x] 3.3 Add optional longer-lived qualifier decode caching keyed by source lifecycle, qid, certified creation `t`/version, and format, conditioned on owning Relation/supported-writer proof with exact/content-proof fallback for unknown writers; verify eid reuse/reset, deletion, and in-place mutation traces cannot reuse stale data.
- [x] 3.4 Ensure qualifier-cache values are decoded data only and perform expiry/Caveat evaluation per request; verify different times/contexts reuse structure but not final authorization.

## 4. Activate Caveat evaluation and public permissionship

- [x] 4.1 Integrate Phase 2 context merge, partial evaluator, and JVM cel-parser adapter at the qualification seam; verify complete, short-circuit, conditional, wrong-type, overload, and budget cases.
- [x] 4.2 Implement one internal evidence type for true, false, conditional residual/missing fields, and fault; verify canonical bounded encoding/equality across CLJ and supported CLJS paths.
- [x] 4.3 Extend union, intersection, exclusion, arrow, and recursive evaluators to compose evidence per the green model without duplicating traversal; verify production-vs-model generated differentials.
- [x] 4.4 Add detailed check/lookup/count result policies and request Caveat context while preserving `can?` true-only-on-definite behavior; verify conditional and fault values never appear as Boolean grants.
- [x] 4.5 Enforce evaluator/profile and qualified-writer publication capabilities before serving Caveated schema; verify JVM default, CLJS absent, custom mismatch, prepared-reference backend, and unsupported-writer fixtures.

## 5. Activate trusted exclusive expiry

- [x] 5.1 Add one trusted clock sample plus process-local non-decreasing high-water mark to the top-level request/snapshot context and prohibit per-edge ambient clock reads; verify a request crossing expiry is consistent and a backward raw-clock step cannot revive access.
- [x] 5.2 Evaluate `evaluation-time-ms < valid-until-ms` before Caveat program work; verify before/equal/after boundaries and expired Caveat compile suppression.
- [x] 5.3 Apply expiry uniformly to grant, group, arrow, recursion, exclusion, and deny evidence; verify an expiring ban can change denial to grant on a later request.
- [x] 5.4 Add stored-versus-active Relationship inspection and renewal/shortening/removal through immutable qualifier `:touch`; verify create still conflicts with retained expired identity.

## 6. Make result and subproblem caches temporally coherent

- [x] 6.1 Carry certified temporal intervals through production evidence and operator results using the proven witness rules; verify every reused value's interval contains the request time.
- [ ] 6.2 Extend exact/managed cache keys and authenticated values with evaluator/profile, canonical request-context identity, result kind, qualifier proof inputs, and temporal certificate; verify no definite/conditional/time alias.
- [x] 6.3 Make resident-but-expired certificates ordinary misses independent of timers/eviction callbacks; verify disabled/delayed eviction cannot change results.
- [ ] 6.4 Update scan-response, range, answer, denotation, and recursive checkpoint reuse only where their semantic value depends on qualification; verify unrelated structural caches are not needlessly invalidated.
- [ ] 6.5 Add cached-vs-uncached differential traces spanning qualifier touch, Caveat schema change, context change, expiry without write, expired ban, and source lifecycle reset; verify exact equality or mapped fault.

## 7. Certify qualified pagination scope

- [ ] 7.1 Extend cursor envelopes/scopes with pinned/live temporal mode, original evaluation time, complete exclusive reuse interval, canonical Caveat context identity, evaluator/profile, result policy, and qualified storage/order ABI; verify tampering and mismatches fail before traversal.
- [ ] 7.2 Make explicit-snapshot cursors preserve their pinned basis/time and make client-targeted live cursors capture fresh time on resume; verify live reuse succeeds only inside the certified interval and equality with its deadline returns a typed restart requirement.
- [ ] 7.3 Cover examined emitted/skipped conditional/expired/subtracting evidence, frontier, lookahead, and residual state in continuation certificates; verify an expired ban before the boundary cannot cause silent omission.
- [ ] 7.4 Refuse cross-time continuation when a complete certificate is unavailable without scanning the remaining graph solely to create one; verify bounded-work outcomes remain honest.
- [ ] 7.5 Document and test the explicit “start a new lookup for now/new context” workflow; verify old cursor use never silently restarts or rebases.

## 8. Complete write, delete, integrity, and proof integration

- [x] 8.1 Extend public Relationship update normalization with optional Caveat/context/`valid-until` and first-four conflict identity; verify SpiceDB-like create/touch/delete cases and one-Caveat maximum.
- [x] 8.2 Implement the Phase 2 certified inline and prepared-reference publication paths: atomically attach/swap both exact tuple refs, Relation stamp, old qualifier cleanup, and caller-composed datoms at the semantic commit point; verify concurrent touch/delete, failed prepared publication, orphan cleanup, and one-sided repair cannot grant, leak a half-edge, or share qualifiers.
- [ ] 8.3 Extend object deletion, schema orphan checks, integrity reports, and unknown-writer content proofs through qualifier and Caveat dependencies; verify faults on subtracting evidence propagate.
- [x] 8.4 Keep expired Relationship collection absent from the authorization contract; verify no test or runtime requires a scheduler/GC write for expiry correctness.

## 9. Certify and qualify before activation

- [ ] 9.1 Add production refinement bridges for edge qualification, Caveat algebra, expiry boundary, temporal certificate, cache acceptance, and cursor scope; verify exhaustive finite and randomized comparisons are green.
- [ ] 9.2 Add mutation controls for omitted q lookup, missing-q-to-`nil`, non-atomic pair publication, unresolved qid publication, `<=` expiry, reversed context precedence, conditional-as-true, fault-as-absence, unsafe q cache, missing Relation stamp, and expired-ban reuse; verify every mutant is killed.
- [ ] 9.3 Benchmark 0%, 5%, and 10% qualified distributions plus concentrated qualified/expired endpoint prefixes across direct, arrow, recursive, page, count, cache-hit, and cold/warm workloads; verify recorded numerical release budgets.
- [ ] 9.4 Audit hot paths for runtime model calls, shadow authorization, redundant full traversals, unconditional qualifier pulls, and excess allocations; verify none were introduced to satisfy formal/CI gates.
- [ ] 9.5 Update public docs, schema examples, error/result reference, cache/cursor semantics, monotonic-clock/Peer-skew guidance, pinned-versus-live snapshot warning, CLJS evaluator boundary, and rollout instructions; verify examples execute against the qualified implementation.
- [ ] 9.6 Run CI-equivalent nREPL tests, CLJS suites, all affected formal gates, source-closure, performance gates, dependency audit, and strict OpenSpec validation; verify everything is green before enabling the qualified semantic epoch.
