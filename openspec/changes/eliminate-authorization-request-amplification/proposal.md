## Why

On the retained Datalevin fixture the public scalar path, not the adapter, is the cost: a ten-row permission-filtered relationship page costs 4.8 ms and 9.1 MB core-only, an all-denied page 68 ms and 186 MB, and 35–57 % of that time is re-sealing the permission plan on every point check, because schema-derived state is keyed to a relationship-generation proof that Datalevin (and any database without ordered generations) cannot supply. The rest is fixed per-call orchestration — a completed-answer cache hit costs 482 µs and 403 KB, more than an uncached evaluation with a memoized plan (238 µs) — and a filtering algorithm whose cost grows with candidates rather than with answers. EACL v8 is unreleased, so these roots are fixed now, without compatibility shims, instead of being wrapped in a faster loop.

## What Changes

- **BREAKING** Schema-derived state (sealed plans, validation catalogs, permission paths, dependency closures, routing analysis) is keyed by a certified EACL schema generation that every backend reads from the snapshot with one cheap operation, independent of relationship-generation proofs, and is reused across requests on every backend including Datalevin. A request-local memo is the floor when no generation is certified, so a request seals a root at most once anywhere. Datalevin's physical attribute-schema fingerprint and the `:schema-identity` basis field are removed. This supersedes the sealed-plan and validation-catalog decisions (D1, D3) of `stable-engine-request-path-performance`.
- Every public read — scalar or aggregate — executes inside one request execution context that owns one selected snapshot, one execution contract, one proof frame, one schema memo, request-local root/dependency/cursor memos, owner-thread affinity, and exactly-once release.
- The scalar path's fixed per-call cost is profiled and gated: snapshot acquisition reads no physical schema, a cache hit must cost less than a cache-bypass evaluation of the same direct-relation demand, and cursor minting and result rendering get ratcheted allocation ceilings.
- Add `check-permissions`: ordered, bounded batch point checks on one snapshot with shared invariant work, request-wide controls, non-resetting aggregate limits, and whole-batch failure that names the failing demand.
- Add authorized relationship pagination as two explicit routes over one page contract: `read-relationships` with an `:authorization` clause (scan relationships, check each — for small relationship sets) and `lookup-resources`/`lookup-subjects` with a `:relationship` filter (enumerate authorized objects, probe each with one direct-match — for small authorized sets). Both paginate in bounded candidate windows with confidential progress cursors; a window that finds fewer than `N` rows returns a short page and a cursor instead of an error.
- **BREAKING** The portable cursor codec becomes authenticated encryption on every backend, as `cursor-dependency-validity` already requires; the authenticated-plaintext compact envelope is removed and existing cursor bytes are not preserved.
- Performance gates become paired same-process comparisons (scalar loop vs aggregate route, cache hit vs bypass, acquisition before vs after) plus deterministic amplification counters; checked-in numbers are absolute ceilings per host class only. HTTP measurements are reported with framework overhead isolated and are not ratio-gated.
- Reconcile overlapping changes: `stable-engine-request-path-performance` drops its §2–3 tasks in favour of this change and keeps leaf probing, non-collecting counts, reducer bookkeeping, and store telemetry; `add-authorization-views` keys its runtime-owned plan/schema registries by the certified generation and keeps Datalevin's explicit persisted source lifecycle. This change does not wait for `add-authorization-views`: the request context is built from a runtime, a basis adapter, and snapshot ownership, which is exactly what that rewrite preserves.
- The Datalevin demo's permission-filtered relationship endpoint moves to the new routes; its per-row scalar loop survives only as the benchmark oracle.

## Capabilities

### New Capabilities

- `batched-authorization-execution`: Ordered, bounded multi-demand point authorization over one immutable snapshot with shared invariant work, refinement-equivalence to scalar evaluation, and whole-batch failure that names the demand.
- `authorized-relationship-pagination`: One page contract for relationships filtered by authorization, served by an explicit scan route and an explicit enumerate route, with bounded candidate windows, exact-or-bounded page information, and confidential progress cursors.

### Modified Capabilities

- `backend-unification`: Certified schema generation as a cheap snapshot operation on every backend, independent of relationship proofs; basis identity without physical schema fingerprints; direct-match probes certified for filtered enumeration; aggregate orchestration in shared core only.
- `authorization-deadlines`: Sub-demands, candidate windows, probes, lookahead, rendering, and publication of an aggregate operation consume the original budget; a candidate budget is not a deadline and a deadline is never a short page.
- `cross-backend-conformance`: Aggregate conformance, amplification counters, a paired benchmark harness, and aggregation fault models for every backend including Datalevin.
- `implementation-simplicity-and-performance`: One request execution context, schema-derived work bounded by distinct roots per generation on every backend, scalar fixed-cost gates, paired performance attribution, and release ratios against the pre-change baseline.

## Impact

- Public API: `eacl/check-permissions`; `:authorization` clause on `read-relationships`; `:resource/relationship` on `lookup-resources` and `:subject/relationship` on `lookup-subjects`; `:bounded?` in page information; cursor bytes change (AEAD); `:schema-identity` leaves basis identity and cache/token identities. No compatibility period — v8 is unreleased.
- Backend SPI: new `:schema-generation` operation on every adapter (Datomic `:eacl/schema-version`; Datahike, DataScript, and Datalevin `:eacl/schema-generation` guarded by the write fence); Datalevin acquisition reads revision metadata only (maintained-fork change); `:direct-match?` certification reused by the enumerate route; no backend-private batch or page evaluators.
- Shared core: engine plan/catalog/path keying and the request-local floor, client orchestration (request context, scalar migration), relay and cursor codec (AEAD envelope, progress anchors), the completed-answer hit path, relationship and stable-discovery page routes, execution-contract aggregate limits, instrumentation counters.
- Backend modules: Datomic, Datahike, DataScript (CLJ/CLJS), and Datalevin conformance wiring; `eacl-spicedb` (separate repository) must implement the new reader operations when it is recut.
- Verification and operations: filtered-pagination formal model, batch oracle and property tests, mutation controls, paired benchmark harness, environment metadata, demo integration, documentation of route selection and of the absence of a sub-millisecond SLA.
- Other changes: `stable-engine-request-path-performance` and `add-authorization-views` are amended as described above.
