# The stable-discovery engine

Published contract documentation for the engine implemented by the
`adopt-stable-discovery-enumeration` change (task 9.4). Normative sources:
the change's three specs under
`openspec/changes/adopt-stable-discovery-enumeration/specs/`; this document
is the operator/consumer summary.

## Order ABI

Order ABI v2 selects the public order per sealed plan
(`acyclic-keyset-pagination`): an **acyclic** root paginates in
**least-derivation-path order** — results ordered by the
lexicographically least per-scan coordinate sequence deriving them
(sealed rule ordinals interleaved with ascending scan eids; a pure
function of plan and snapshot, proved by `LeastPathOrder.dfy` /
`LeastPathEnumeration.dfy` / `LeastPathResume.dfy`) — with
self-contained keyset cursors, no continuation checkpoints, and no
replay. A **recursive** root keeps the **stable first-discovery order**
of the sealed-plan reducer described below: deterministic for one exact
basis, normalized query, composite plan fingerprint, and adapter
scan-order contract. Neither order is a global entity-ID sort, and the
selected mode plus the recursiveness classification are folded into the
plan fingerprint, so cursors can never cross regimes.

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

The public clients wrap that edge in their own authenticated-encryption envelopes and
pin the exact basis, query scope and schema generation there: the Datomic
client's `eacl4_` AEAD page token and the shared Datahike/DataScript
client's `eacl_c5_` Relay envelope (`eacl.relay`, `eacl.cursor`). Their
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

After the public cursor has authenticated, selected and accepted its basis,
continuation resolves through a **latest-only client-private checkpoint** or
**governed deterministic replay**. The checkpoint key is
`[lineage frame plan-fingerprint traversal subject-type anchor page-size]`;
native revision is intentionally absent. The frame covers every relation the
sealed reducer may scan, so an equal frame in one lineage excludes the
changed-slice hazard and makes the history-free state reusable across an
unrelated write. The entry contains complete history-free reducer state, the
undelivered lookahead segment, and the authenticated boundary identity. It is
replaced only by strictly greater transition progress and remains bounded by a
standard-cache entry count. Only a checkpoint whose ordinal and boundary match
the authenticated cursor records an ordinary cache hit; a rejected candidate
does not become hot. A miss always replays and validates the boundary before publishing a
page.

`:populate-cache? false` permits checkpoint lookup but suppresses publication;
the next page replays correctly. Relay has no visited-page routes, boundary
index, or aliases. With non-expiring cursors, one complete exact-basis
transport page may be retained under the complete raw request and cursor-key
policy. A hit returns before cursor decode, identity conversion, proof work, or
token construction. TTL-enabled cursors keep using the authenticated semantic
path. Cursor query and edge identities must be metadata-free portable data.
The standalone `eacl_sd1.` token and its private checkpoint key remain
exact-basis-bound.

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
- `:eacl.pagination/unsupported-cursor-identity` — a cursor-bearing query
  scope or emitted public edge contains metadata-bearing identity data or a
  record, list, subvector, map entry, alternate integer/collection
  representation, or signed zero that canonical transport would erase;
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
own client options (`:security-key` / page-token keyrings). Public portable
cursors use random 96-bit nonces with independently derived AES-256-CTR and
HMAC-SHA-256 keys; rotate an authenticated-encryption key before 2^32 cursor
encryptions, retaining an old verification key only for the intended cursor
lifetime. EACL does not maintain the invocation count.

## Point checks

`can?` is decided by a membership-probe search over the sealed plan's
reverse index (`eacl.engine.stable-route/check-eids`): the resource's
intermediates are enumerated (its account, teams, vpc — typically a
handful), and the subject is always looked up by one exact-bound probe (the
scan strictly after `subject − 1` with limit one equals the subject iff the
tuple exists). The Boolean equals membership of the subject in the
exhaustive reverse-discovery denotation (the reverse-enumeration form is
retained as `enumeration-check-eids`, the test oracle), and the cost is
bounded by reachable intermediates and the reducer budgets — never by the
number of subjects that hold the permission (a denied check on a resource
with 5,000 owners went from 16 ms to 24 µs). Probes and enumerations run
through the routed read boundary below, under the same typed limits.
`formal/stable-discovery/MembershipProbeCheck.dfy` proves the probe answer
equal to reverse-denotation membership; `eacl.engine.point-check-test` is
the executable oracle differential.

## Failure semantics

Every routed adapter read has exactly three outcomes
(`eacl.engine.physical/classified-fetch-fn`, installed on the public path by
`eacl.engine.v8`):

- **complete** — validated values, possibly legitimately empty;
- **failure** — a foreign adapter exception is classified `:retryable`
  (or `:terminal` when the adapter says so) with a cause class; the chunk is
  realized inside the classification boundary, so partial output is
  discarded atomically and reducer state is untouched;
- **cancelled** — a thread interrupt inside the read.

`nil` is never an outcome. Missing storage is never an empty scan. Typed
EACL errors raised inside a read (`:eacl/backend-contract-violation` from
the runtime guards, limits, deadlines, cooperative cancellation) are already
verdicts: they pass through the boundary unwrapped and are never retried.
Retries (`retrying-fetch-fn`) reuse the exact descriptor, run under the
request's original absolute deadline, stop after three attempts, and are
counted separately from logical commands (`:adapter-attempts` in the
traversal work stats); the exhausted failure surfaces as
`:eacl.scan/failure` with `:classification`, `:cause-class` and the original
exception as its cause. Exact-basis selection follows the same discipline in
the Datomic and Datahike backends (`:eacl.basis/selection-failure` with
classification; genuine absence maps to the contractual unavailable signal).
Semantic limits fail typed and uncommitted (`:eacl.reducer/limit-exceeded`,
surfaced publicly as `:eacl.recursive-traversal/limit-exceeded`);
cancellation and the absolute deadline are checked cooperatively at every
reducer transition (`execution-cut-point`) and never publish a partial page,
child cursor, or checkpoint — the parent cursor stays reusable.

Service protection is a bulkhead, not a scheduler: the client option
`:service-admission {:max-concurrent n :max-replays n :max-replays-per-key n}`
installs bounded concurrent enumerations with slots held for the full
synchronous call chain (`:eacl.service/admission-rejected`) and a replay
ledger keyed by continuation identity that governs checkpoint-miss replays,
backward runs and last windows (`:eacl.service/replay-rejected`). An
omitted option installs no bulkhead.

## Cache artifacts and metrics

The stable engine keeps three cross-request artifacts: the latest progress
checkpoint per execution identity, completed semantic answers whose flat
composite key includes the plan fingerprint, and one exact-basis transport
page per complete raw page request when cursor expiry is disabled. All use the
client's standard cache boundary. The transport value is process-local and
contains no request object. Fetched relationship chunks and incomplete
traversals remain request-local and are never published.

The finished reducer state carries every observable cost layer of one run:
`:transitions`, logical scan `:commands`, `:fetched-values`, logical
`:admissions`, `:discovered` results, `:maximum-stack`, and the
retained-buffer high-water marks `:maximum-sidecar-buffers` and
`:maximum-sidecar-values`; the routed fetch seam counts adapter attempts
separately when its counting retry wrapper is installed.
Storage-layer counters are observed at the storage layer and never inferred.
The continuation store reports hits, publications, replacements, approximate
evictions, errors, actual entry count/capacity, and classified misses such as
`:absent` and `:boundary-mismatch`. Eviction affects only acceleration; the
request and subsequent deterministic replay remain correct.

## Topology qualification

A topology runs stable discovery only when its closed capability record
(`eacl.engine.physical/topology-capabilities`) certifies: immutable basis,
strict scan order and uniqueness, replayability, strict progress, atomic
responses, and failure-classification fidelity. The record is derived from
the adapter's declared execution profile (`eacl.backend.v8/traversal-execution`)
plus the engine's read boundary (`adapter-topology-capabilities`), and both
public clients check it once at construction
(`require-qualified-topology!`, failing closed with
`:eacl.topology/unqualified`); the three bundled adapters declare the strict
sequential profile and qualify. Deployment width is one for every topology
in this change; semantic concurrent-read safety and physical cancellability
are recorded conservatively as the SPI seam for the future concurrency
change. Datahike/DynamoDB remains unqualified operationally until the
upstream Konserve failure-cause collapse is repaired or wrapped.
