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

The engine's boundary is one `:stable-edge` — the boundary result's
one-based ordinal plus its identity, bound to the composite plan
fingerprint, the order ABI and the traversal direction
(`eacl.engine.v8/stable-edge`, validated by `validate-stable-bound!`). It
contains no reducer state, no cache pointer, no seen set, and no rolling
commitment. Navigation mode (`after`/`before`) is request input, not
cursor identity.

The public clients wrap that edge in their own authenticated envelopes and
pin the exact basis, query scope and schema generation there: the Datomic
client's `eacl4_` AEAD page token and the shared Datahike/DataScript
client's `eacl_c4_` Relay envelope (`eacl.relay`, `eacl.cursor`). Their
rejections surface under the public `:eacl.pagination/*` keys
(`invalid-cursor`, `stale-cursor`, `wrong-cursor-kind`,
`complete-evaluation-required`) and their limits under
`:eacl.recursive-traversal/limit-exceeded` with `:limit-kind` and
`:limit`. The self-contained HMAC edge token described next
(`eacl_sd1.` prefix, `eacl.engine.stable-page/page`) is the engine's
standalone API; the public clients call `edge-page` directly and never
mint it.

The standalone token binds: format version, order ABI, composite plan
fingerprint, source lifecycle, exact basis, anchor, direction, subject
type, fixed page size, the boundary result's one-based ordinal and
external identity, and optional expiry (domain `eacl/stable-page/v1`,
domain-separated key derivation, constant-time tag comparison).

Continuation resolves through a **latest-only in-process checkpoint**
(complete history-free reducer state plus the undelivered lookahead
segment and constant-size boundary identity, replaced only on a strictly
greater scalar transition ordinal, bounded by entry count and per-entry
weight with overweight drop) or **governed deterministic replay** that
validates the boundary ordinal and identity before any page publishes.

Rejection classes (all typed, never silent). Public clients surface them
under the `:eacl.pagination/*` and `:eacl.recursive-traversal/*` keys;
the standalone token uses the `:eacl.page/*` keys in parentheses:

- `:eacl.pagination/invalid-cursor` (`:eacl.page/invalid-cursor`) —
  tamper, malformed, wrong key, or any bound field (fingerprint,
  lifecycle, direction, anchor, page size) differing from the executing
  context; `:eacl.pagination/wrong-cursor-kind` when the boundary is not a
  `:stable-edge` or names the other traversal direction;
- `:eacl.pagination/expired-cursor` (`:eacl.page/expired-cursor`) — past
  the token's expiry;
- `:eacl.pagination/stale-cursor` (`:eacl.page/stale-cursor`) — the
  authenticated boundary is no longer reproducible at the selected basis
  (a replay boundary mismatch), or, for the standalone token, the exact
  basis is no longer selectable. The public clients continue on an equal
  dependency proof or reconstruct the exact snapshot where the backend
  retains it before they reject;
- `:eacl.consistency/cursor-consistency-conflict`
  (`:eacl.page/cursor-consistency-conflict`) — the request's consistency
  mode (`:fully-consistent`/`:at-least-as-fresh`) demands fresher than
  the pinned basis;
- `:eacl.recursive-traversal/limit-exceeded` with `:limit-kind` and
  `:limit` (`:eacl.page/resource-exhausted` for continuation budgets) —
  replay/continuation budgets or the public traversal limits make the
  page unreachable; **distinct from stale** by design;
- `:eacl.pagination/complete-evaluation-required` — a bare `:last` window
  on a recursive schema under the default `:demand` evaluation mode.

Keys for the standalone token: supply `:security-key` (≥ 32 bytes); the
process-local random default is warned about and does not survive restarts
or load balancing. The public clients take their key material from their
own client options (`:security-key` / page-token keyrings).

## Failure semantics

The reducer's only effectful call is the adapter scan at the canonical
head (`eacl.engine.stable-reducer/adapter-fetch-fn`); its semantic state is
untouched until a complete chunk is realized, so a thrown adapter failure
leaves the state exactly where it was. Semantic limits fail typed and
uncommitted (`:eacl.reducer/limit-exceeded`, surfaced publicly as
`:eacl.recursive-traversal/limit-exceeded`); cancellation and the absolute
deadline are checked cooperatively at every reducer transition
(`eacl.engine.physical/execution-cut-point`) and never publish a partial
page, child cursor, or checkpoint — the parent cursor stays reusable.
Exact-basis selection classifies its failures in the Datomic and Datahike
backends (`:eacl.basis/selection-failure` with `:retryable`/`:cancelled`;
genuine absence maps to the contractual unavailable signal).

`eacl.engine.physical` additionally provides, as library components with
their own tests, the three-outcome read classification
(`classified-fetch-fn`: complete | classified failure with a cause code and
atomic partial discard | cancelled — `nil` is never an outcome), retry of
the exact descriptor under the original absolute deadline
(`retrying-fetch-fn`), and a service-edge bulkhead with a replay ledger
(`make-service-admission`, `with-admission`, `with-replay-admission`,
`:eacl.service/admission-rejected`, `:eacl.service/replay-rejected`). The
routed public path (`eacl.engine.v8`) currently installs only the
execution cut-point: adapter exceptions propagate unclassified and no read
is retried. Installing the wrappers is a `fetch-fn` option on
`edge-page`/`check-eids`; see the 2026-08-15 audit report for the open
decision.

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

`eacl.engine.physical/topology-capabilities` defines the closed capability
record a topology must certify before it is considered qualified for
stable discovery (`stable-discovery-qualified?`): immutable basis, strict
scan order and uniqueness, replayability, strict progress, atomic
responses, and failure-classification fidelity. Deployment width is one
for every topology in this change; semantic concurrent-read safety is
recorded separately as the SPI seam for the future concurrency change.
No backend module declares a capability record yet and the routed path
does not consult one; the adapters' scan obligations are instead stated
by `eacl.backend.v8/adapter-obligations` and exercised by the
certification suites. Datahike/DynamoDB remains unqualified until the
upstream Konserve failure-cause collapse is repaired or wrapped.
