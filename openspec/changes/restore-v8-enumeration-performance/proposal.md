## Why

EACL v8 preserves authorization results but regresses cold enumeration latency by roughly an order of magnitude versus v7, replays traversal prefixes during DataScript pagination, and rejects a valid 50,000-resource query under recursive traversal limits even though the Explorer schema is acyclic. EACL must retain its verified correctness boundary while restoring bounded, predictable performance for ordinary acyclic authorization graphs.

## What Changes

- Route permission roots that do not transitively depend on a recursive strongly connected component through a verified acyclic enumeration path; recursive traversal limits apply only to genuinely recursive authorization graphs.
- Treat recursive schema arrows as inactive when every cycle-enabling relationship prefix is empty in the selected snapshot, so recursive syntax without recursive data remains page-bounded.
- Retain authenticated, client-private generated continuation state for DataScript and Datahike so each first visit to an adjacent page resumes prior work instead of replaying every preceding page.
- Provide an exact count path for acyclic permissions that deduplicates overlapping grants without driving the recursive fixed-point machine or silently weakening count semantics.
- Preserve the public Relay pagination and count APIs, cursor authentication, snapshot consistency, authorization semantics, and fail-closed behavior.
- Extend the formal models and generated authority for routing, pagination continuation, acyclic counting, work bounds, and cross-runtime equivalence; generated Clojure/JavaScript artifacts remain derived outputs rather than hand-maintained implementations.
- Add deterministic performance and work-budget gates comparing v8 with representative v7 baselines at 10,000 and 50,000 resources across Datomic, DataScript, and Datahike, including the EACL Explorer multipath schema.

## Capabilities

### New Capabilities

- `schema-aware-traversal-routing`: Verified classification and routing of acyclic and recursive permission roots, including the requirement that acyclic requests do not consume recursive traversal budgets.
- `enumeration-continuation-reuse`: Authenticated client-private continuation reuse for forward and reverse Relay pagination across supported backends, with deterministic replay retained as a safe cache-miss fallback.
- `verified-enumeration-performance`: Correct, exact, deduplicated list and count enumeration with explicit work and latency regression gates against v7-scale workloads.

### Modified Capabilities

<!-- none — openspec/specs/ has no existing capability specifications -->

## Impact

- **Formal authority**: `formal/dafny/` routing, indexed traversal/rendering, pagination, count, cost, and refinement models; cross-runtime vectors, mutation controls, counterexamples, and generated-boundary manifests.
- **Generated artifacts**: verified Clojure kernel and browser/JavaScript authority must be regenerated from the accepted formal source and pass clean-generation checks.
- **Shared engine**: v8 routing, pagination continuation, exact counting, work telemetry, and traversal-limit enforcement.
- **Backends**: DataScript and Datahike continuation ownership/wiring, with Datomic behavior used as the authenticated client-private reference; all three backends receive differential correctness and performance coverage.
- **Consumers**: EACL Explorer is the primary acceptance workload. Existing API shapes and valid cursor/count behavior remain compatible; no breaking public API change is intended.
- **Explorer diagnostics**: the schema editor exposes matched Non-recursive and Recursive presets so the inactive-recursion case is reproducible without hand-editing the fixture.
