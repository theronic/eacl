## Why

Phases 1 and 2 provide a single v9 Relationship stream, sparse immutable qualifiers, shared Caveat definitions, and a formally specified evaluator, but qualified Relationships still cannot participate in authorization. EACL now needs one coherent edge-qualification seam that activates Caveats and exclusive `valid-until` semantics without invalidating its proof-backed caches or adding work to the common `qualifier-eid = nil` path.

Time can change an authorization answer without a database transaction, including changing a denial into a grant when an expiring subtracting Relationship disappears. Qualifier content and request context likewise affect answers beyond Relation tuple membership. This phase therefore models temporal/conditional traversal and cache reuse first, then certifies the implementation against those models before routing is enabled.

## What Changes

- Require completion of `2026-09-04-01-adopt-v9-qualifier-reference-storage` and `2026-09-04-02-build-caveat-qualifier-foundation`.
- **FORMAL GATE:** extend EACL's permission/traversal/cache models with sparse qualifier resolution, Caveat outcome algebra, exclusive expiry, decisive temporal witnesses, and cursor context before production engine changes begin.
- Activate non-`nil` qualifier refs through one shared edge-qualification seam only on backends whose Phase 2 qualified-writer publication strategy is certified. `nil` continues directly with zero qualifier entity reads, zero clock-dependent work beyond the request's one captured time, and no Caveat evaluation.
- Resolve each distinct non-`nil` qualifier at most once per request/batch and support bounded immutable qualifier caching keyed by source lifecycle, qualifier eid, and its certified creation `t`/version. Unknown-writer paths remain exact or content-proof backed; a dangling non-`nil` ref never aliases the `nil` fast path.
- Treat a missing, malformed, wrong-version, disallowed, or otherwise invalid qualifier as an authoritative error/fail-closed condition, never as an unconditional Relationship. Single ownership and immutability are enforced by admitted writes and certification/offline integrity; the ordinary hot path does not scan the graph to rediscover those invariants.
- Enable Caveat evaluation using Relationship-bound context overlaid on request context, three-state permissionship (`has`, `no`, `conditional`), and correct composition through union, intersection, exclusion, arrows, and recursion.
- Add public Caveat request context and detailed permissionship results. Boolean `can?` remains backward-compatible and fail-closed: only definite `has-permission` returns true.
- Enable optional `valid-until-ms` with the half-open rule `evaluation-time < valid-until`. Capture trusted evaluation time once per top-level operation or explicit snapshot.
- Annotate reusable authorization results and subproblems with a formally justified temporal stability certificate. Relation/schema proofs must still match, and wall-clock eviction/listeners are never correctness mechanisms.
- Give qualified cursors explicit temporal modes. Client-targeted live cursors capture fresh trusted time on resume and may reuse state only inside a complete certified temporal interval; at a boundary they fail with a typed restart requirement. Cursors from explicit EACL snapshots remain pinned to their selected time. Caveat request context is authenticated and cannot change within either series.
- Keep physical expired-Relationship collection outside this phase. Expiry correctness is read-time semantics; retained expired data may be removed by a later bounded maintenance proposal.
- Benchmark 0%, 5%, and 10% qualified workloads and enforce that ordinary nil-qualified paths remain allocation/read neutral within recorded budgets. Formal compliance must not add runtime model interpreters, shadow traversal, or redundant full-graph checks.

## Capabilities

### New Capabilities

- `qualified-relationship-evaluation`: one-stream qualifier resolution, Caveat permissionship, conditional permission algebra, public request context, fail-closed faults, mutation semantics, and fast nil path.
- `expiring-relationships`: trusted exclusive `valid-until`, positive/negative evidence behavior, renewal, stored-versus-active inspection, and correctness independent of collection.

### Modified Capabilities

- `public-authorization`: captured evaluation time, request Caveat context, detailed permissionship, conditional lookup/count behavior, and Boolean compatibility.
- `dependency-validated-authorization-cache`: immutable qualifier cache identity, Caveat/context-complete result keys, and temporal stability certificates.
- `cursor-dependency-validity`: authenticated pinned/live temporal mode, Caveat context, complete reuse deadlines, and restart-safe continuation semantics.

## Impact

This is the first phase that changes permission evaluation. It affects backend scan response shape, direct membership, shared traversal and operator algebra, public checks/lookups/counts, Relationship writes/reads, qualifier lifecycle, trusted request context, caches and proof frames, cursor formats, formal models, differential oracles, performance instrumentation, and documentation.

Caveat definitions remain shared schema data; Relationship-bound context and `valid-until` remain sparse qualifier data. One logical Relationship still exists per subject/Relation/resource identity. No `valid-from` scheduling, multiple grant assertions, supplemental tuple source, Relationship entity, or mandatory expiry job is introduced.
