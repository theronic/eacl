# EACL 8.0.0 candidate release notes

EACL v8 replaces the proof-per-hit cache candidate with a client-private
current-generation cache, adds recoverable query-scoped cursors, and makes the
current DB visible to the local backend the default consistency contract.
Exact snapshot pinning remains available on history-capable backends; DataScript
is deliberately current-only and rejects `at-exact-snapshot`. These are
deliberate pre-release breaking changes.

V8 uses database-visible native schema/relation generations, authenticated
native-revision tokens, and confidential authenticated-encryption cursors, and moves the DataScript/Datahike ports to
the v8 Relay list/count contract. The earlier v8 candidate's mutation graph,
random mutation records, anchor membership, and global graph-head CAS are
superseded and are not installed on new databases. The relationship storage
version stays at 7: Datomic
keeps its v7 layout, Datahike uses the same two-tuple physical layout, and
DataScript uses the same logical two-endpoint representation with indexed
ordinary vector values. Upgraded databases expose an explicit additive
`prepare-cache-coherence!` step that initializes missing physical relation
generations before managed v4 readers start.

## Modular artifacts

EACL v8.0 is a workspace with independently consumable modules:

- `modules/eacl` contains the backend-neutral protocol, schema/parser model,
  the shared stable-discovery engine, and the validated v8 backend adapter
  contract (`eacl.backend.v8`).
- `modules/eacl-datomic` contains the complete v8 Datomic implementation.
- `modules/eacl-datascript` contains the CLJ/CLJS DataScript adapter.
- `modules/eacl-datahike` contains the CLJ Datahike adapter and supports both
  keyword and numeric attribute-reference representations. Its relationship
  storage now matches Datomic's two cardinality-many heterogeneous endpoint
  tuples. DataScript uses the same component order in ordinary vectors because
  DataScript 1.7.8 does not support heterogeneous tuple declarations.
- `modules/eacl-datalevin` contains a CLJ-only adapter for a qualified
  embedded, one-JVM, one-connection, one-writer Datalevin topology. It owns a
  public explicit native read snapshot per request. Publication remains
  blocked until the maintained `dev.eacl/datalevin-embedded-eacl` fork is
  available from immutable public SCM and passes packaged Linux ARM64
  qualification.

Existing Datomic namespace imports do not change. Consumers replace the root
Git dependency with `:deps/root "modules/eacl-datomic"`; this packaging change
does not alter v7 relationship storage. Tokens, cursors, cache envelopes, and
the additive native-generation schema are deliberately new v8 formats; no
downgrade or dual-format cache/token mode is provided. Third-party adapters
implement the validated v8 operation/capability contract (`eacl.backend.v8`);
the pre-v8 six-function SPI is gone. The four built-in adapters share
permission-plan compilation, the stable-discovery reducer, pagination,
counting, and portable cache validation.

DataScript and Datahike now expose the v8 Relay list/count API and portable
cache; this is a breaking request/response change from their v7 ports. See the
[backend and upgrade guide](v8-backend-modules-and-upgrade.md).

Published module dependency maps do not select Logback or another logging
implementation. Applications own their logging backend and configuration.

It adds certified schema/relation generations and commit-time endpoint identity
guards. No authorization answers are persisted in the application database.
A managed relationship mutation stamps every distinct affected relation in the
same transaction, so answers survive writes outside their complete dependency
closure without a listener, transaction-log read, or database-global CAS.

## Aggregate authorization

`eacl/check-permissions` evaluates an ordered vector of point demands with one
selected snapshot, request context, absolute deadline, cancellation token, and
cumulative aggregate budget. Its decisions retain scalar order, cardinality,
value, evaluation mode, and cache provenance. Any demand failure rejects the
whole batch with its index; there is no partial publication.

Permission-filtered relationship pagination has two explicit shared-core
routes. `read-relationships` accepts an `:authorization` clause and scans the
matching relationship set. `lookup-resources` and `lookup-subjects` accept a
direct `:relationship` filter, enumerate the authorized set, and use one
certified direct-match probe per candidate without re-evaluating permission.
Callers choose the smaller side; EACL does not make an adaptive cardinality
guess.

Both routes stop at physical exhaustion, the accepted-row sentinel, or the
configured `:aggregate-limits {:candidate-window ...}`. A window boundary may
return a valid short page with `:has-next-page? true` and `:bounded? true`.
Encrypted cursors bind the route, complete clause, direction, page demand,
window, source/lifecycle/basis, schema generation, dependency proof, and order
ABI; scan and enumerate cursors are not interchangeable. Aggregate pages use
ordinary exact/proof-backed cache provenance. See [Aggregate
authorization](aggregate-authorization.md) for examples and the route cost
table.

The performance qualification uses paired core series, deterministic
amplification counters, retained-resource gates, and a separately reported
loopback HTTP no-op control. Absolute ceilings are host-class-specific and do
not establish a portable sub-millisecond SLA.

## Consistency

`expand-permission-tree` is now implemented on Datomic, Datahike, DataScript
CLJ, and DataScript CLJS. It is an additive protocol behavior change: the
existing method no longer throws `:eacl/not-implemented`. Requests accept
`:resource`, `:permission`, optional `:consistency`, and optional
`:timeout-ms`; responses contain `:expanded-at` and a shallow annotated
`:tree-root`. The tree and token are bound to one selected immutable snapshot.

Expansion preserves empty branches, nested permission/arrow boundaries, and
duplicate multiplicity. Vector order is not semantic. Client construction now
accepts `:permission-tree-limits` for depth, schema-component,
relationship-value, node, and leaf-subject work. Typed failures are
all-or-error and do not expose a partial tree or internal backend identity.

Compatibility was checked as black-box behavior against a live SpiceDB v1.56.0
Docker image pinned by digest. This is a scoped topology claim for EACL's
supported union/direct/single-arrow schema subset, not a claim of full SpiceDB
feature or byte-order equivalence. The Dafny model proves the backend-neutral
shallow topology, denotation, cycle, and limit properties; the handwritten
CLJ/CLJS implementation is differentially tested rather than mechanically
extracted.

No database migration or persisted attribute is required. Rollback by first
stopping callers that depend on successful expansion, then deploy the prior
code and remove `:permission-tree-limits` from client configuration. Existing
schemas, relationships, caches, and tokens require no rewrite.

- The default authorization mode is `:minimize-latency`.
- The redundant pre-release names `:local-snapshot` and
  `:synchronized-head` are no longer accepted.
- Datomic ordinary reads call `d/db` once and do not call `d/sync` or
  `d/as-of`.
- `:fully-consistent` explicitly requests a backend synchronization barrier.
- `:at-least-as-fresh` performs targeted selection and validates the
  authenticated native revision floor within one source lifecycle.
- `:at-exact-snapshot` performs exact selection and may reuse only a completed
  answer bound to the identical canonical snapshot and semantic request. It
  never uses managed proof-backed lifting. Datomic catches a lagging Peer up
  to authenticated `T` with bounded two-argument `d/sync` before exact
  `d/as-of T`; DataScript rejects exact mode before cache access because it has
  no EACL time-travel registry.
- Datalevin supports minimize-latency, fully-consistent local head, and
  at-least-as-fresh revision-floor selection through fresh explicit readers.
  It rejects exact selection. Its initial adapter advertises no ordered
  generations and no proof frame, so it never lifts a cached answer across a
  different revision.
- Low-level operations accepting an arbitrary `db`, including caller-created
  `d/as-of`, `d/with`, prospective, or filtered views, bypass completed-answer
  caching.
- Read operations no longer issue unused Zed tokens. Mutation responses retain
  authenticated tokens for explicit at-least/exact workflows.

The application owns remote freshness policy. It may call `d/sync` before
authorization when needed; EACL does not silently pay that cost on every
permission check.

## Mutation discipline

- Client construction does not install or migrate a mutation journal.
- `write-schema!` publishes the certified schema generation and initializes
  every added relation generation in the same transaction as the schema delta.
- Every supported relationship writer atomically publishes the physical
  relation generations used by the cache; proof-backed reuse is automatic and
  there is no selectable coherence authority (`remove-unknown-cache-coherence`).
  Writers outside the EACL APIs must follow the recovery procedure in
  [cache behavior and recovery](cache.md).
- Every managed relationship helper stamps each distinct affected relation in
  the same transaction as the tuple change. There is no global graph CAS.
- Relationship additions carry commit-time endpoint identity CAS guards, so a
  stale plan cannot recreate a tuple after safe endpoint deletion. Cache
  correctness itself depends on generations, not CAS.
- `integrity/repair-tx-batches` keeps each dangling-half repair and its
  dependency stamps in one transaction. Custom repair or relationship writers
  must use the managed mutation builder.
- Consumers should call `delete-relationships!` before retracting an entity.
  Alternatively, install/prepare the optional target-only
  `:eacl.fn/retractEntity`; it supports multiple invocations and numeric-eid
  ghost repair. Integrity reports detect damage when the old eid is unknown.
- Relationship update operations are validated before endpoint resolution. `delete-object!`
  reports actual committed relationship-datom retractions across all batches.
- Datalevin commits matching forward/reverse tuples, relation stamps, and the
  schema write fence atomically through its serialized writer. Object deletion
  rescans inside the commit transaction. These claims apply only to the
  certified direct synchronous sole-writer topology.

## Completed-answer cache

Each Datomic, Datahike, DataScript, and Datalevin client owns a bounded native cache. It
has three sound reuse rules:

1. **Exact-current:** accept an entry only for the identical immutable selected
   DB generation.
2. **Managed-current:** under an explicit stamped-writer contract, accept an
   entry when its schema generation and relevant dependency stamp still match.
3. **Snapshot-exact:** after authenticated exact selection, accept only the
   complete answer with the identical source/lifecycle, native locator,
   ordinary-view, adapter/identity, engine, request, result, demand, and limit
   identity.

The cache is an optimization over the cache-free evaluator. It does not define
authorization; snapshot-exact retention accelerates, but never creates,
backend time travel.

### Exact-current tier

- Datomic uses the selected current basis as its generation identity.
- Datahike uses immutable DB object identity.
- DataScript uses a private opaque identity handle for the immutable DB object,
  avoiding numeric `max-tx` collisions after reset.
- Datalevin uses backend/source/lifecycle/revision identity and performs exact
  selected-revision reuse only. It does not fingerprint physical schema.
- A transaction replaces the exact generation. No schema/relationship proof
  calculation occurs on an exact hit.
- Publication captures the generation/lifecycle. A delayed computation cannot
  repopulate a newer or explicitly expired lifecycle.
- Retained historical exact answers share one bounded weighted/LRU composite
  store. Exact requests never bind this as a partial traversal store and never
  consult managed relation/schema proof.

### Managed-current tier

Proof-backed ("managed") reuse is enabled only when the adapter advertises
certified ordered generations. Datomic, Datahike, and DataScript writers
publish the relation generations the proof needs, and the
`:coherence-authority` option that once selected between `:unknown` and
`:managed` no longer exists (supplying it is invalid configuration). The
contract is that every authorization-affecting relationship and schema write
goes through EACL's writers or documented transaction data; a raw backend
transaction outside that contract requires the recovery procedure in
[cache behavior and recovery](cache.md). Native-revision token issuance and
selection are independent of the cache.

Managed reuse covers completed answers (and, for page rendering, identity
projections) under one relation-stamp framing:

- The semantic query key contains normalized internal object IDs, operation,
  permission, result kind, and relevant configuration.
- A schema-generation object owns all managed entries. A real schema update
  discards the complete old generation.
- Each entry is keyed by the schema generation plus the scalar dependency
  frontier derived from the complete relation-generation closure of its
  compiled dependencies (`ScalarFrontierCoherence.dfy`; the proof frame reads
  the complete canonical vector and derives the scalar), and plan compilation
  fails closed if a compiled rule could reference a relation outside that
  closure.
- Under ordinary forward transactions, a relevant write advances the
  frontier; an unrelated write leaves it unchanged.
- Missing/malformed stamps disable managed reuse rather than becoming a
  reusable zero value.
- All proof-capable backends read the current physical `:eacl/relation-version` assertion;
  a missing assertion is a fail-closed miss and has no fallback.
- Custom object-ID codecs remain exact-current-only unless they supply the
  additional deterministic dependency contract.
- Randomized cached-versus-cache-free differential oracles run with the
  managed tier active on the three ordered-proof backends, interleaving EACL-API writes
  with checks, lookups, and counts.
- Datalevin maintains relation versions for mutation fencing and a future
  proof-capable design, but does not expose those values through a proof frame:
  persistent datom transaction identity is not certified as an ordered
  generation.

### Cache operations

- `eacl.datomic.core/expire-cache!`
- `eacl.datahike.core/expire-cache!`
- `eacl.datascript.core/expire-cache!`

Datalevin lifecycle rotation is external and durable;
`eacl.datalevin.core/expire-cache!` rejects process-local rotation.

These rotate source/token scope and replace the entire client lifecycle. Use them after reset,
restore, branch force, manual history manipulation, or unstamped bulk repair.
Datomic excision and Datahike purge/cutoff or branch replacement are outside
the unchanged-lifecycle contract: quiesce traffic, complete the operation,
rotate the shared lifecycle and every client/cache, apply deliberate key/wire
retirement policy, and then resume.

The corresponding `cache-stats` functions report native exact/managed hits,
misses, bypasses, stamp failures, publications, expirations, and entry counts.
`:cache? false` bypasses caching for one request. Both global `cache/no-cache`
and request-local bypass now branch directly to engine evaluation before
semantic cache-key construction, dependency-stamp capture, snapshot-token
calculation, canonicalization, or result-envelope creation.
Cache-disabled callers therefore do not pay the expensive parts of the cache
strategy.

Caller-supplied cache providers are rejected at construction because they do
not control the native completed-answer or continuation stores. Continuation
state remains isolated in a bounded private store.

`:cache-attempt` now names only controls the live private-cache path consumes:
`:evaluation-reserve-ms` (default `10`) and
`:maximum-atomic-attempts` (default `4`). Decorative stage-timeout,
encoded-byte, decoded-weight, and candidate-count controls are rejected. Native
per-tier/per-entry weights remain construction-time cache limits, while the
single request `:timeout-ms` remains the end-to-end deadline.

Bounded reads also accept a per-request `:cancellation-token` created by
`eacl.core/cancellation-token`. `eacl.core/cancel!` is idempotent; the next
cooperative orchestration, cache, cursor, traversal-quantum, or adapter-boundary
check throws `:eacl.execution/cancelled` and returns no partial answer.
Tokens never participate in semantic cache, continuation, or authenticated
cursor identity. An adapter call already executing remains synchronous and
must return before cancellation can be observed; a completed result may also
win a race with a late cancellation. Deadlines and bounded admission remain
required.

## Cursor redesign

Portable cursor payloads are v12 inside the compact `eacl_c5_` authenticated
and encrypted frame. Cursors bind the backend/source, operation, complete semantic query
(including principal and consistency), result kind, semantic/configuration
identity, source lifecycle, native revision, and exact snapshot locator. Relay window size and
direction remain caller-controlled so the same boundary supports forward and
backward navigation.

Cursor expiry is off by default on every backend. A positive
`:cursor-ttl-seconds` adds explicit policy expiry; answer, navigation, and
checkpoint eviction only trigger deterministic replay. The v12 query-scope
digest excludes mutable current schema proof so a changed schema can reach
proof comparison and exact fallback. v11 decoding remains supported for
compatible existing envelopes.

- Every permission lookup page uses one boundary cursor bound to the sealed
  plan's composite fingerprint; order ABI v2 selects the boundary kind per
  plan (`acyclic-keyset-pagination`). A recursive root mints a
  `:stable-edge` containing traversal direction, the boundary result's
  one-based ordinal, and its identity, in the plan's stable first-discovery
  order (`adopt-stable-discovery-enumeration`); page size, adapter chunking,
  cache hits, and runtime do not define that order, and a page-size change
  is rejected as an incompatible cursor. An acyclic root mints a
  `:least-path-edge` carrying the boundary result's full per-scan
  derivation coordinates in least-derivation-path order — a self-contained
  keyset boundary whose resume is a per-level seek: no continuation
  checkpoints, no replay, and per-page cost that does not grow with the
  page ordinal even with caching disabled (previously the cache-off walk
  cost grew quadratically in the ordinal).
- Default `:evaluation :demand` computes only the requested page plus one
  lookahead result. The client-private checkpoint store may retain the
  latest history-free reducer state for that exact snapshot. If it is absent
  or evicted, EACL replays the authenticated prefix on the same selected
  immutable snapshot and then demands only the next page plus lookahead.
- `:evaluation :complete-denotation` is the only public opt-in to a bare
  `:last` window on a recursive schema. Completed pages validate both cursor
  ordinal and result identity before slicing; a mismatch is stale, never a
  restart.
- Default content/no-proof cursors bind the exact selected immutable snapshot;
  cursor minting does not scan relationship content. Datomic and Datahike may
  reconstruct that authenticated exact snapshot after the current head moves.
  DataScript is current-basis-only, so every later basis is a typed stale
  cursor in these modes, even after an unrelated write. Explicit managed
  mutation-stamp mode may continue on a newer current basis only when its
  complete dependency and ordering stamps remain equal. A newer
  `at-least-as-fresh` floor that excludes the cursor snapshot returns a typed
  cursor-consistency conflict.
- Continuation-store eviction, a deleted boundary, a schema change, and a
  relevant relationship change never silently switch the walk to current or
  restart page one.
- Relationship cursors follow the same graph rule: equal proof may continue
  on current; changed proof requires verified exact reconstruction or fails
  closed.
- Portable cursors use AES-256-CTR with a random 96-bit nonce and
  encrypt-then-HMAC-SHA-256 under independently domain-derived keys. The key
  id, nonce, and ciphertext are authenticated before the payload is decrypted
  or parsed. Datomic retains its compact AES-GCM codec. Rotate either kind of
  authenticated-encryption key before 2^32 cursor encryptions. At high cursor
  volume plan key rotation accordingly (`:security-keyring` supports staged
  rotation); EACL does not count invocations for you.
- Constructing a client without explicit token key material warns at
  startup: defaulted keys are process-local and random, so cursors and
  tokens do not survive restarts and are not portable across peers or
  load-balanced nodes.
- DataScript and Datahike relationship pages now seek from an authenticated
  physical tuple-index edge and resolve only the selected page's public IDs.
  They read at most `page-size + 1` matching internal rows instead of
  materializing and sorting every match before every page.

Permission enumeration presents one deterministic sequence for a fixed query
on the selected snapshot: a recursive root the sealed plan's stable
first-discovery order, an acyclic root the plan's least-derivation-path
order (proved by the `LeastPath*` Dafny leaves). Relationship pages use
their certified index order. None of these is a lexical, domain, or
cross-backend presentation order.
The cursor query and navigation digests include emission-order version 2, so a
future ordering change cannot silently resume an older traversal state.

Adopting order ABI v2 changes the enumeration order of acyclic roots once:
cursors minted under the previous ABI are bound to the old plan
fingerprint, so they fail as typed invalid-cursor errors rather than
resuming out of order — restart the affected walk from page one. Recursive
roots keep their order and their cursors.

Under concurrent mutation, results granted below a keyset boundary
between pages are not revisited and revoked results disappear — ordinary
keyset semantics; surviving results appear exactly once across a walk.
Exact walks require the explicit exact-snapshot consistency mode.

All old cursor/cache/token candidate envelopes are intentionally incompatible
with the final v8 formats.

## Explorer enumeration performance

V8 enumeration runs every permission root through the stable-discovery
engine (this replaced the interim routing certificate, entity-ID merge and
fixed-point engines on 2026-08-14; see
[docs/stable-discovery-engine.md](stable-discovery-engine.md)). A first page
follows the plan's cheapest certified path to its first results instead of
realizing every union branch, which removed the measured 148 s cold first
page on the deployed 1M-resource store (now ~5 storage reads).

Relationship pages and permission pages reuse exact, bounded, client-private
page artifacts after cursor authentication and immutable snapshot selection.
Engine checkpoint state remains opaque and private; the reducer validates the
boundary before any resumed page publishes. A request with `:cache? false`,
a changed query/snapshot proof or ordering ABI, or a different client cannot
reuse the artifact.

DataScript native-revision selection reads immutable `:max-tx` directly; it
does not query a managed graph entity on adapter construction.

Default DataScript authorization cursors now use exact current-basis identity
instead of computing a content digest over their relationship dependency
closure. This removes graph-linear work from a first page, including with
`:cache? false`, without adding historical retention or allowing cross-basis
results. Managed mutation-stamp clients retain bounded dependency-scoped
continuation.

The acyclic frontier builder also canonicalizes exact pure permission aliases
before its existing first-occurrence identity deduplication. In the Explorer
schema, `account.view = admin` therefore makes `account->view` and
`account->admin` one semantic traversal stream. Composite bodies remain
untouched; exact values and public order are unchanged.

The matched v7/cache-bypassed performance gate passes:

- 10k user-1 page: v8 median 0.70ms versus v7 1.43ms.
- 10k owner-0001 exact 2k count: v8 1.98ms versus v7 2.48ms.
- 50k super-user exact count: v8 76.31ms versus v7 86.82ms
  (0.88x, below the 2.0x release bound).
- The same 50k count with the Explorer recursive schema and no parent
  relationships takes 91.90ms, reports 50,003 merge advances, and performs
  zero recursive work.
- The 100k exact count retains the existing 512-scan ceiling and now performs
  462 scans across four canonical permission paths; its warmed median is
  154.36ms.

The local CLJS Explorer acceptance run keeps repeated nested page hits around
1–3ms, completes recursive-schema view/admin switching at 10k, and completes
the 50k recursive-schema exact count without recursive-limit or retained
snapshot errors. Full evidence is recorded in
`formal/verification/explorer-v8-release.edn`.

The stable reducer releases exactly one scan value per logical transition
regardless of render mode; physical chunk width (default 64) is a pure
acceleration knob and provably cannot change the sequence. There is no fuel
quantum: progress is preserved by history-free checkpoints and governed
replay. EACL-FORMAL-066 and EACL-FORMAL-067 (broad-fanout livelock and
page-size-dependent ordering in the retired generated traversal) are retained
as replayed counterexamples against the stable engine.

## Correctness findings closed

- **Datomic raw writer stamp mismatch.** Managed Datomic validation now uses
  only the physical `:eacl/relation-version` assertion written by the public
  and documented low-level helpers. Every relation is initialized on schema
  write/migration; there is no mutation-ID fallback.
- **DataScript exact-snapshot ABA (superseded by the final current-only
  contract).** Numeric `max-tx` cannot identify immutable DB values across
  `reset-conn!`. Instead of retaining a second historical registry and its
  lifecycle, v8 removes DataScript exact selection. Each request uses the
  current immutable DB, current continuation requires proof equivalence, and
  unsupported exact requests fail before cache access.
- **Mixed-snapshot cursor complexity.** Proof-equivalent lifting made a page
  depend on validation across two graphs and converted retention eviction into
  an availability failure. Non-exact continuation now discards graph-specific
  state and re-evaluates on one selected current graph; only explicit exact
  walks on history-capable backends remain graph-pinned.
- **Late publication after expiry.** In-flight work could conceptually publish
  after a cache reset if publication resolved “current cache” twice. The new
  resolver captures the lifecycle/generation; old publication is unreachable.
- **Datahike/DataScript request bypass ignored.** Public operations discarded
  `:cache? false` before reaching the engine, so callers still performed cache
  validation and publication. `can?`, lookup/count, and relationship reads now
  validate and honor the flag, and the direct branch is covered by a
  throwing-resolver regression.
- **Formal arrow-rule domain omission.** The Dafny model's arrow relation and
  arrow permission rules omitted the grant resource-type equality guard. The
  production engine already scopes the query by resource type; the formal
  semantics now states the same domain restriction, allowing the frame theorem
  to be derived rather than assumed.
- **Root names prevented shared-subgraph cache reuse (EACL-FORMAL-046).**
  Denotation keys retained the queried permission name, so distinct roots with
  equal certified indexed bodies could not share the same fixed-point result.
  Keys now use the exact portable root-rule bodies accepted by generated plan
  certification. Dafny proves the equal-body denotation law; JVM/CLJS
  regressions reject relation and target-node collisions.
- **Ordinary lookup cursors falsely reported rebasing
  (EACL-FORMAL-047, superseded by the v8 final cursor contract).** The earlier
  correction still allowed a walk to switch to a changed current proof. The
  final contract deletes rebase/restart entirely: equal proof continues on
  current, a changed proof requires verified exact reconstruction, and an
  unavailable exact snapshot fails closed.
- **The routing resource gate measured JVM history (EACL-FORMAL-048).** Its
  first measured size could still be in HotSpot tiered compilation, and the
  gate ran after two ClojureScript compiler builds. Routing is now measured
  first in a fresh 1 GiB JVM after 40 warmups, with 11 samples per size and the
  full observation printed on failure. The exact `P + 2V + E` logical check
  and every allocation/latency ceiling are unchanged.
- **The cursor-rebase resource gate compared different operation shapes
  (EACL-FORMAL-049; historical).** That benchmark correctly exposed a defect
  in the former rebase implementation and its harness, but the final v8 cursor
  contract deletes rebase/restart altogether. The old measurements remain
  labeled historical evidence; they are not an active production or release
  claim. Current continuation gates cover equal-proof current continuation,
  verified exact-snapshot fallback, and typed stale rejection.
- **The warm permission gate could not distinguish a transitional batch from
  sustained latency (EACL-FORMAL-050).** Its 2,000-call warmup and single
  measured batch made one observation decide the release gate. The gate now
  warms 15,000 calls and uses the median of three 5,000-call batch medians.
  The corrected harness subsequently reproduced a stable regression and
  opened EACL-FORMAL-051 instead of dismissing it as compilation noise.
- **Generated `can?` classified the permission root twice
  (EACL-FORMAL-051).** The public entry point checked that the permission root
  existed, then generated-authoritative `can*` immediately repeated the same
  schema-generation lookup. Public generated authority now reuses the first
  classification, dispatches a defined root directly, and returns the
  established `false` result for an undefined root. Dafny proves Boolean
  result preservation and one root classification for authoritative calls; a
  public-client regression observes the same bound. The 1,000 µs ceiling was
  not relaxed.
- **Map-form `can?` weakened malformed false consistency to the default mode
  (EACL-FORMAL-052).** The Datomic, Datahike, and DataScript records used
  `(or consistency :local-snapshot)` before shared descriptor validation.
  Omitted and nil values were intended to default, but explicit `false` was
  also falsey and therefore silently became a valid default request.
  All three map arities now forward the raw value: omission/nil still default
  in the descriptor, while false produces `:eacl/unsupported-consistency`.
  Dafny retains those public-input classes and cross-backend regressions close
  the source refinement.
- **Explicit completion was decorative for certified acyclic roots
  (EACL-FORMAL-063).** Point checks, pages, and counts returned the right value
  but silently selected the demand shortcut even with
  `:evaluation :complete-denotation`, so no reusable completed denotation was
  produced. One shared route selector now keeps the acyclic shortcut
  demand-only and sends explicit completion through the generated fixed-point
  evaluator for every defined root. Completed acyclic denotations are
  canonicalized once to the demand route's EID order and retain the acyclic
  keyset cursor ABI. The minimized regressions require invariant public order,
  cross-operation reuse, and zero backend work; the execution-contract model
  proves the route and order laws.
- **Default DataScript cursors scanned the entire relationship graph
  (EACL-FORMAL-064).** Cursor orchestration always supplied a permission's
  relation closure. Under default content proof mode, a seven-item demand page
  therefore hashed every matching forward and reverse relationship record just
  to mint a cursor; `:cache? false` did not avoid that work. Content/no-proof
  cursors now bind exact immutable snapshot identity and issue zero relation
  proof commands. Since DataScript cannot select history, any later basis is
  stale. Only explicit managed mutation stamps permit bounded proof-equivalent
  cross-basis continuation.
- **Pure permission aliases duplicated acyclic traversal streams
  (EACL-FORMAL-065).** Raw arrow target names kept `account->view` and
  `account->admin` distinct even when `view = admin`, pushing the 100k Explorer
  count to 515 backend scans above the unchanged 512-scan ceiling. The frontier
  builder now follows only cycle-guarded exact single self-permission bodies,
  canonicalizes arrow targets, and preserves the first path's order. The
  denotation is unchanged and composite permissions are not rewritten.
- **Token consistency descriptors admitted unknown fields
  (EACL-FORMAL-053).** The shared descriptor checked the required mode and
  token values but accepted additional fields, contradicting the formal
  malformed-input class and the documented strict boundary. Token descriptors
  now require exactly `:consistency/mode` and `:zed/token`; additional fields
  produce `:eacl/unsupported-consistency` before authentication or snapshot
  selection. The check uses cardinality and key membership rather than
  allocating a key set, and a shared CLJ/CLJS regression covers both token
  modes.
- **Immutable DataScript adapters claimed an authoritative head
  (EACL-FORMAL-054).** A snapshot adapter without a live connection advertised
  `:fully-consistent`, although its authoritative selector could only return
  the captured immutable DB. Such adapters now remove that capability;
  managed clients with a connection retain it.
- **Generated point checks traversed from the broad endpoint
  (EACL-FORMAL-055).** `can?` always started the generated forward driver at
  the subject, making a point query scale with unrelated resources reachable
  from that subject. It now starts the proved reverse driver at the named
  resource. The regression gate holds backend and logical work constant from
  16 to 1,040 reachable resources and keeps wall-time as a separate host gate.
  An exact direct-tuple probe preserves the documented raw-EID ghost behavior
  if a consumer bypasses EACL deletion and leaves only the subject-owned tuple;
  only that malformed state invokes a verified forward recovery.

## Formal verification

`formal/dafny/CurrentCache.dfy` proves:

- arbitrary-DB completed-cache bypass and canonical exact-snapshot admission;
- distinct current-exact, snapshot-exact, and managed hit/miss decisions;
- snapshot-exact identity equality rather than numeric-revision equality;
- late publication cannot repopulate an expired lifecycle;
- forward scalar-stamp invalidation;
- relevant relationship projection framing for direct, self, arrow-relation,
  and arrow-permission rules;
- equality of least fixed points for complete compiled dependencies;
- selected-snapshot internal-to-public result rendering.

The model does not prove Datomic I/O effects or future cancellation, Datahike
temporal-history retention, or the truthfulness of adapter-provided canonical
cache-key fields. Those are explicit certified adapter assumptions exercised
by deterministic effect and real-backend tests.

The locked Dafny run completes 8,793 proof efforts across 30 source-project
invocations with zero errors, admissions, warnings, or timeouts. The count
includes dependency obligations repeated by multiple top-level invocations; it
is pipeline work, not a count of unique theorems. Since 2026-08-14 the
generated kernel is authoritative for the pure decisions around the engine
(consistency plan, current-cache decision, cursor continuation, page-request
normalization); enumeration itself runs on the hand-written CLJC
stable-discovery engine on both targets, verified by the separate
`formal/stable-discovery/` assurance tree and differentially certified against
the independent fixed-point oracle. Host runtimes, collection semantics, cryptography, FFI
conversion, and backend adapter contracts remain explicitly trusted or
empirically certified boundaries.

The release manifest is therefore `:conditionally-verified`, not unqualified
`:verified`. It deliberately withholds verified release status until an
independent security/formal-methods review is recorded. Generated JavaScript
is retained only on the formal-smoke classpath as the oracle for the portable
CLJC decision twin. No runtime engine selector is shipped.

### ClojureScript production authority

The browser no longer executes the Dafny JavaScript runtime or BigNumber on
the authorization hot path. Certification passed 46 formal/oracle tests with
9,983 assertions, the full advanced DataScript/core suite passed 176 tests
with 9,693 assertions, and the current injected-authority suite passed 172
tests with 4,682 assertions across 79 client constructions while observing
every required traversal operation.

At the 16,384-result reference size the recorded three-process median is
5,335 ns/result,
below the 15,000 ns/result ceiling. The advanced portable-kernel payload adds
15,335 raw bytes and 3,409 Java-GZIP bytes over the empty runtime, within the
32 KiB raw / 8 KiB compressed budgets and more than an order of magnitude
below the retired 591,497-byte generated browser IIFE. CI also rejects
`BigNumber`, `EaclFormal`, and generated-adapter markers in the full production
bundle.

The final CI pass exposed a 15,068.85 ns/result run—0.46% beyond the absolute
ceiling. The ceiling was not relaxed. Portable traversal now replaces its
nested counter map once per logical queue/grant update instead of repeatedly
rewriting the same persistent state path. On matched fresh local Node
processes, the three-process median improved from 6,848 to 5,335 ns/result
(ratio 0.779) with identical logical counters and full generated-oracle,
DataScript, and injected-authority parity.

This changes the trust posture: browser authorization is advisory and must be
re-checked on the server. The portable kernel is strongly differentially
certified, not mechanically extracted from Dafny. If a future deployment
requires one mechanically generated engine on every target, the recorded
alternative is a native-number Dafny ESM build with widened `{:nativeType}`
coverage, explicit sequence accessors, and tree-shakeable exports.

## Performance evidence

Earlier machine-local point measurements after the current-generation cache
redesign:

| Backend/path | Cached | Cache-disabled | Approximate speedup |
| --- | ---: | ---: | ---: |
| Datomic repeated `can?` | 8.5 µs | 31.8 µs | 3.7× |
| DataScript repeated `can?` | 7.2 µs | 26.8 µs | 3.7× |
| Datahike repeated `can?` | 12.2 µs | 17.6 µs | 1.4× |

Datomic's private current-cache lookup itself measured about 1.5 µs. The
current forced-authority heavy suite passes 17 tests and 4,058 assertions. On
the latest fixed-heap run:

- 15,000-resource first page median: 0.24 ms;
- forward max-page median: 0.55 ms;
- reverse max-page median: 0.45 ms;
- 4,000-node recursive walk: 134.09 ms with cached continuation versus
  3,141.52 ms replaying prefixes;
- explicit-completion distinct-query shared-subgraph five-run median p50:
  0.140 ms versus 3.419 ms for completed-answer-only caching, with zero
  backend operations on the reused path and paired ratios from 0.039 to 0.044.
  This is opt-in `:complete-denotation` evidence; ordinary demand requests
  never traverse farther to warm this artifact.

These are comparative development measurements, not portable latency promises.
The decisive result is architectural: hot exact hits no longer calculate
dependency or content proofs.

## Migration

- Recreate every v8 pre-release client after upgrade.
- Discard old page cursors and pre-release tokens.
- Treat omitted consistency as `:minimize-latency`.
- Request `:fully-consistent` when a backend barrier is required.
- Route every authorization-affecting write through EACL's writers; there is
  no coherence-authority switch to fall back on.
- Call `expire-cache!` around excluded history/reset operations.
- Keep `:cache? false` and `cache/no-cache` available for differential
  diagnostics and cache-free reference checks.

See [consistency and cache operations](v8-consistency-cache-operations.md) and
[backend modules and upgrade](v8-backend-modules-and-upgrade.md).
