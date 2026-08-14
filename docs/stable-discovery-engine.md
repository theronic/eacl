# The stable-discovery engine

Published contract documentation for the engine implemented by the
`adopt-stable-discovery-enumeration` change (task 9.4). Normative sources:
the change's three specs under
`openspec/changes/adopt-stable-discovery-enumeration/specs/`; this document
is the operator/consumer summary.

## Order ABI

Public enumeration order is the **stable first-discovery order** of the
sealed-plan reducer: deterministic for one exact basis, normalized query,
composite plan fingerprint, and adapter scan-order contract. It is not a
global entity-ID sort and not identical across adapters.

Order inputs, all sealed at plan compilation (`eacl.engine.sealed-plan`):
dense canonical rule ordinals (canonical-encoding sort of the semantic
rules), the certified static 0/1 storage-read-distance rank (untrusted
generator, linear trusted checker), alternative vectors in
`(rank, ordinal)` order, and the adapter's strict-ascending-eid scan
contract. Logical release width is fixed at **one value per reducer
transition** and is not configurable; physical chunk width and buffer
retention provably cannot change the sequence (width/retention-invariance
gates). The complete order contract — including admission-key granularity
and the release width — is folded into one composite fingerprint
(`eacl.sealed-plan.v1` domain); any order-affecting change is a new
fingerprint and invalidates outstanding cursors explicitly.

Result uniqueness holds by construction: the single root emission point is
keyed by the emitted entity (forward: `[:grant root-node eid]`; reverse:
`[:reverse-subject type eid]`); interior merge points are keyed by target
node + entity, never by producing rule.

## Cursor trust boundary

A cursor is one bounded HMAC edge token (`eacl_sd1.` prefix, domain
`eacl/stable-page/v1`, domain-separated key derivation, constant-time tag
comparison) binding: format version, order ABI, composite plan
fingerprint, source lifecycle, exact basis, anchor, direction, subject
type, fixed page size, the boundary result's one-based ordinal and
external identity, and optional expiry. It contains no reducer state, no
cache pointer, no seen set, and no rolling commitment. Navigation mode
(`after`/`before`) is request input, not cursor identity.

Continuation resolves through a **latest-only in-process checkpoint**
(complete history-free reducer state plus the undelivered lookahead
segment and constant-size boundary identity, replaced only on a strictly
greater scalar transition ordinal, bounded by entry count and per-entry
weight with overweight drop) or **governed deterministic replay** that
validates the boundary ordinal and identity before any page publishes.

Rejection classes (all typed, never silent):

- `:eacl.page/invalid-cursor` — tamper, malformed, wrong key, or any bound
  field (fingerprint, lifecycle, direction, anchor, page size) differing
  from the executing context, or a replay boundary mismatch;
- `:eacl.page/expired-cursor` — past `:expires-at`;
- `:eacl.page/stale-cursor` — the exact basis is no longer selectable
  (current-only topologies reject continuation across any write until the
  certified full-read-scope dependency proof ships);
- `:eacl.page/cursor-consistency-conflict` — the request's consistency
  mode (`:fully-consistent`/`:at-least-as-fresh`) demands fresher than the
  pinned basis;
- `:eacl.page/resource-exhausted` — replay/continuation budgets make the
  page unreachable; **distinct from stale** by design.

Keys: supply `:security-key` (≥ 32 bytes); the process-local random
default is warned about and does not survive restarts or load balancing.

## Failure semantics

Every adapter read has exactly three outcomes
(`eacl.engine.physical/classified-fetch-fn`):

- **complete** — validated values, possibly legitimately empty;
- **failure** — classified `:retryable` or `:terminal` with a cause code;
  the chunk realizes inside the classification boundary, so partial output
  is discarded atomically and reducer state is untouched;
- **cancelled**.

`nil` is never an outcome. Missing storage is never an empty scan.
Retries (`retrying-fetch-fn`) reuse the exact descriptor under the
original absolute deadline and are counted separately from logical
commands. Exact-basis selection follows the same discipline in the
Datomic and Datahike backends (`:eacl.basis/selection-failure` with
classification; genuine absence maps to the contractual unavailable
signal). Semantic limits fail typed and uncommitted
(`:eacl.reducer/limit-exceeded`); cancellation is cooperative at every
reducer transition and never publishes a partial page, child cursor, or
checkpoint — the parent cursor stays reusable.

Service protection is a bulkhead, not a scheduler: bounded concurrent
enumerations with slots held for the full synchronous call chain
(`:eacl.service/admission-rejected`) and a replay ledger with total and
per-key quotas (`:eacl.service/replay-rejected`).

## Cache artifacts and metrics

The engine keeps exactly two cache artifacts: the latest-only progress
checkpoint per execution identity, and completed answers (the existing
answer cache; its key gains the composite fingerprint at the public
routing step). Byte and node caching belongs to the storage layer.
Partial traversals and flat subproblem denotations are never reused.

Per-layer telemetry (`eacl.engine.physical/telemetry`) reports reducer
transitions, logical scan commands, values fetched, logical admissions,
results discovered, maximum stack, and retained-buffer high-water marks —
plus adapter attempts when the counting retry wrapper is installed.
Storage-layer counters (node-cache hits/misses, remote GETs/PUTs) are
observed at the storage layer and never inferred. Checkpoint stores
report entry count and per-entry admission weight; overweight publication
drops are silent by contract (the request itself is unaffected).

## Topology qualification

A topology runs stable discovery only when its closed capability record
(`eacl.engine.physical/topology-capabilities`) certifies: immutable basis,
strict scan order and uniqueness, replayability, strict progress, atomic
responses, and failure-classification fidelity. Deployment width is one
for every topology in this change; semantic concurrent-read safety is
recorded separately as the SPI seam for the future concurrency change.
Datahike/DynamoDB remains unqualified until the upstream Konserve
failure-cause collapse is repaired or wrapped.
