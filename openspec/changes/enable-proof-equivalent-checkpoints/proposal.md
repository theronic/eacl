## Why

The stable engine keeps one latest-only checkpoint per execution identity: history-free reducer state (`:stack :admitted :admissions :transitions :commands :fetched-values :discovered :maximum-stack`), the undelivered one-element lookahead, and the constant-size boundary. Its key pins the native revision: `[(:fingerprint plan) (backend/invoke db :native-revision) traversal subject-type anchor-eid size]`. The Datomic client wraps that key in a `:proof-equivalent` continuation scope, the shared clients in an exact one, and both miss after any write — the inner key differs — so a proof-equivalent cursor continuation (`enable-proof-equivalent-cursor-streams`) is followed by a full replay of every preceding page. Replay is correct and bounded, but it is the cost the checkpoint exists to remove.

Keying the checkpoint by the frame is sound for the same reason cursor continuation is: the reducer reads only the plan closure's slices, its state after consuming through a boundary is a deterministic function of the plan, those slices, and the boundary, and equal frames in one lineage make the slices identical. The stored state already contains nothing basis-specific beyond semantic data — buffers, the fetch function, delivered results, and configuration are excluded — and the resource counters needed to enforce cumulative limits on resume are part of it.

## What Changes

- Checkpoint identity becomes `[lineage frame plan-fingerprint traversal subject-type anchor-eid page-size]` in every client; the continuation scope's `:snapshot-identity` and the Datomic-only `:proof-equivalent` case are deleted. A checkpoint hit still requires the authenticated boundary ordinal and identity to match.
- The pipeline order is unchanged and stated: authenticate the cursor, select the basis, accept continuation (equal frame or exact fallback by identity), validate the boundary, then consult the checkpoint; a miss replays from the boundary.
- The history-free state's closure is pinned structurally: a test asserts the checkpoint contains exactly the semantic keys, no function, reader, database value, lazy sequence, or delivered result, and that resource counters carry across resume so limits are cumulative.
- Visited pages (externalized public pages) remain keyed by exact basis: their rendering depends on public identity, which the frame does not cover.
- `:populate-cache? false` suppresses checkpoint publication.
- The checkpoint layer gains ClojureScript coverage (today only the decision kernel has it) and miss-reason telemetry.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enumeration-continuation-reuse`: frame-scoped keys, state closure, cumulative counters, miss reasons, CLJS parity.
- `snapshot-stable-pagination`: checkpoint consultation strictly after public continuation acceptance; visited pages stay exact.
- `cross-backend-conformance`: checkpoint hits after unrelated writes equal replay on every backend.
- `formally-verified-authorization-engine`: resume-equals-replay composed with the frame rule and the read-scope bridge.

## Impact

- Modules: `eacl.engine.stable-page` (key, publication, telemetry), `eacl.engine.v8` (checkpoint key construction), `eacl.continuation` (scope digest, dead API removal), Datomic client (special case deleted or converged), CLJS test runner.
- Depends on `introduce-proof-carrying-semantic-equivalence` and `enable-proof-equivalent-cursor-streams`. No public format changes.

## Related changes

Already applied or archived; this change modifies their outcomes rather than their artifacts:

- `archive/2026-08-15-restore-v8-enumeration-performance`: origin of `enumeration-continuation-reuse` and the client-private continuation store, including the `:proof-equivalent` scope the Datomic client sets without effect.
- `adopt-stable-discovery-enumeration` (in progress): the latest-only checkpoint, `history-free` reducer state, and the basis-bearing `execution-binding`/`checkpoint-key` (basis was added to the key by the 2026-08-15 audit fix); the key is re-scoped here.
- `acyclic-keyset-pagination` (in progress): least-path plans carry self-contained keyset cursors and no checkpoints; unaffected.