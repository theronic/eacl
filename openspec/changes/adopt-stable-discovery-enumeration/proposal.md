## Why

EACL v8's acyclic lookup planner globally merges one entity-ID-ordered stream per permission path. It must observe the head or exhaustion of every candidate stream before it can prove the first result. On the deployed Datahike/S3 investigation, one cold super-user page opened approximately 4,536 relationship scans, incurred approximately 3,935 unique Datahike node-cache misses at ~37.7 ms per serial miss, and took 148.4 seconds; the same traversal took 214 ms with those nodes in RAM. The failure is the enumeration order, not the cache: to prove the globally smallest result the merge must pay total permission-path fanout on every cold basis.

EACL v8 is unreleased. It can stop promising global entity-ID order and replace the development cursor ABI without migration. The replacement must preserve the real semantic promises — exact authorization, stable order for one basis and plan, no duplicates across pages, deterministic replay, bounded resource use, and competitive local performance — with the smallest engine that delivers them.

This revision supersedes the earlier draft of this change. Two prior candidates are rejected with evidence: the global merge (the measured pathology above) and the symmetric fixed-point/byte-stable candidate (2,033 scans and ~203 MiB allocation on the 2,000-branch adversarial fixture, versus one scan for the accepted cost-ranked order). The earlier draft's bounded concurrent physical shell, speculative prefetch, descriptor coalescing, and four-tier cache architecture are removed from scope: every certified adapter today declares strict sequential execution, the best measured request-path concurrency gain was ~1.58x on cold direct S3 only under 10 ms injected latency (with read amplification; the one larger measured win, cold LMDB-tier fill, is a restart operation), and every headline performance number was produced by a width-one, shell-free prototype. Concurrency remains a designed-for seam, not shipped machinery.

## What Changes

- Replace global entity-ID merging and the rejected symmetric candidate with one versioned stable first-discovery order.
- Compile normalized positive permission programs into direction-specific sealed plans: static rule indexes, canonical ordinals, certified 0/1 storage-read-distance ranks, alternatives ordered by `(rank, ordinal)`, one composite fingerprint.
- Execute both directions with one generic pure reducer over the sealed plan's transition interface. Exact admission of logical work terminates cycles; the single root emission point is keyed by the emitted entity's identity, so result deduplication holds by construction.
- Release exactly one ordered scan value per logical transition (logical width is fixed at one and is not a tuning knob). Physical chunks are bounded request-local buffers that never affect order.
- Run all physical reads at width one on every topology. Keep the pure-step/`NeedRead` boundary and atomic staged integration as the seam where a future concurrency change can attach without touching the order ABI.
- Classify every adapter read as complete (possibly empty), failure (retryable or terminal, with a cause code; partial output discarded), or cancelled. Missing storage is never an empty scan. Repair EACL's own `select-exact` Throwable-to-nil swallowing.
- Replace rolling-prefix cursor commitments with authenticated result-edge cursors binding exact basis, normalized query, plan/order fingerprint, fixed page size, boundary ordinal, and boundary identity. Continuation is an exact latest-only checkpoint or governed deterministic replay; continuation must satisfy the request's consistency mode; current-only topologies may continue past unrelated writes only under a certified full-read-scope dependency proof.
- Keep exactly two engine cache artifacts: latest-only progress checkpoints (in the reshaped continuation store, byte-weight capped) and completed answers (keyed by the order fingerprint). Byte caching belongs to the storage layer. Partial traversals and flat subproblem denotations are never reused.
- Enforce the compact-representation contract that produced the measured memory wins: specialized admission keys, owned transients, right-edge stack, compact scan frames.
- Route every public v8 entry point — lookup-resources, lookup-subjects, can?, count-resources, count-subjects — through the new engine after local gates pass, then delete the old merge and symmetric engines. Remote topology qualification becomes follow-on performance work; it gates topology defaults and Datahike/DynamoDB enablement, not the engine swap.

## Capabilities

### New Capabilities

- `stable-discovery-enumeration`: exact, duplicate-free, stable paginated enumeration with bounded authenticated cursors, consistency-aware continuation, and replayable semantics.
- `bounded-physical-execution`: width-one physical execution refining the pure reducer, with atomic failure classification, cooperative cancellation, bounded chunk retention, latest-only checkpoints, and two closed cache artifacts. (Replaces the earlier `deterministic-concurrent-traversal` capability; concurrent execution is a documented future seam, not part of this change.)
- `remote-backend-enumeration-efficiency`: explicit adapter capability certification and per-layer cost telemetry, with performance qualification per storage topology.

### Modified Capabilities

- None. These capabilities replace development-only v8 behavior; no published compatibility contract changes.

## Impact

- Core lookup planning, generated forward/reverse traversal, pagination, cursor encoding, continuation storage, and cancellation change materially. The physical scheduler, service governor speculation, cross-request shared-read machinery, and the projection/denotation cache tiers are deleted rather than replaced.
- Public page order changes from global entity-ID order to the sealed plan's stable first-discovery order. Point authorization remains anchored; exact count exhausts the reducer by default.
- Cursor replay after checkpoint eviction can cost the full canonical prefix; this is explicit, governed, and has a typed failure when budgets make a page unreachable.
- The gitignored exploration evidence (accepted prototype, Dafny/TLA models, benchmark protocols) is archived into tracked storage before implementation begins; it is currently one `clean` away from destruction.
- No relationship-data or cursor migration is required because EACL v8 has not shipped.
