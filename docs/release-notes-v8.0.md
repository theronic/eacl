# EACL 8.0.0 candidate release notes

EACL v8 replaces the proof-per-hit cache candidate with a client-private
current-generation cache, adds recoverable query-scoped cursors, and makes the
current DB visible to the local backend the default consistency contract. Exact
snapshot pinning remains available through `at-exact-snapshot`. These are
deliberate pre-release breaking changes.

V8 also adds the database-visible v3 mutation journal and authenticated causal
tokens/cursors, and moves the DataScript/Datahike ports to the v8 Relay
list/count contract. The relationship storage version stays at 7: Datomic
keeps its v7 layout, Datahike uses the same two-tuple physical layout, and
DataScript uses the same logical two-endpoint representation with indexed
ordinary vector values. Client construction performs an idempotent additive
migration that creates the graph family/head, schema mutation identity, and
identities for existing relations.

## Modular artifacts

EACL v8.0 is a workspace with independently consumable modules:

- `modules/eacl` contains the backend-neutral protocol, schema/parser model,
  shared engine, and stable six-function backend SPI.
- `modules/eacl-datomic` contains the complete v8 Datomic implementation.
- `modules/eacl-datascript` contains the CLJ/CLJS DataScript adapter.
- `modules/eacl-datahike` contains the CLJ Datahike adapter and supports both
  keyword and numeric attribute-reference representations. Its relationship
  storage now matches Datomic's two cardinality-many heterogeneous endpoint
  tuples. DataScript uses the same component order in ordinary vectors because
  DataScript 1.7.8 does not support heterogeneous tuple declarations.

Existing Datomic namespace imports do not change. Consumers replace the root
Git dependency with `:deps/root "modules/eacl-datomic"`; this packaging change
does not alter v7 relationship storage. Tokens, cursors, cache envelopes, and
the additive mutation-journal schema are deliberately new v8 formats; no
downgrade or dual-format cache/token mode is provided. The original
six-function SPI remains compatible for third-party adapters, but it does not
advertise v8 causal selection or proof lifting. The three built-in adapters use
the validated v8 operation/capability contract and share permission
compilation, recursive traversal, pagination, counting, and portable cache
validation.

DataScript and Datahike now expose the v8 Relay list/count API and portable
cache; this is a breaking request/response change from their v7 ports. See the
[backend and upgrade guide](v8-backend-modules-and-upgrade.md).

Published module dependency maps do not select Logback or another logging
implementation. Applications own their logging backend and configuration.

It adds mutation-journal, graph-head, schema-proof, and per-relation mutation
identity attributes plus the existing transactor-side relation-removal guard.
No authorization answers are persisted in the application database. A managed
graph mutation appends its random mutation record and updates graph/schema/
relation identities atomically, so answers survive writes outside their
complete dependency closure without relying on listener state or transaction
number equality.

## Consistency

- The default authorization mode is `:minimize-latency`.
- The redundant pre-release names `:local-snapshot` and
  `:synchronized-head` are no longer accepted.
- Datomic ordinary reads call `d/db` once and do not call `d/sync` or
  `d/as-of`.
- `:fully-consistent` explicitly requests a backend synchronization barrier.
- `:at-least-as-fresh` performs targeted freshness selection and validates the
  authenticated graph anchor.
- `:at-exact-snapshot` performs exact selection and bypasses completed-answer
  caching.
- Low-level operations accepting an arbitrary `db`, including caller-created
  `d/as-of`, `d/with`, prospective, or filtered views, bypass completed-answer
  caching.
- Read operations no longer issue unused Zed tokens. Mutation responses retain
  authenticated tokens for explicit at-least/exact workflows.

The application owns remote freshness policy. It may call `d/sync` before
authorization when needed; EACL does not silently pay that cost on every
permission check.

## Mutation discipline

- Client construction idempotently establishes one causal family and mutation
  graph when absent. Concurrent migrations converge through transactional
  compare-and-swap.
- `write-schema!` publishes the schema mutation identity and graph head in the
  same committed transaction as the schema delta and expires the complete
  client cache generation.
- `:coherence-authority :managed` means every relationship writer atomically
  publishes the relation transaction stamps used by the cache. Unknown or
  mixed writers must remain `:unknown`, which permits exact-current reuse only.
- Every managed relationship helper publishes one v3 mutation record, advances
  the graph head, and stamps every affected relation in the same transaction as
  the tuple change. Batched deletion does this separately for each committed
  batch and mints its response token from the final `db-after`.
- Datomic still emits `:eacl/relation-version` CAS datoms to serialize
  competing relationship writes. The transaction component of the current
  relation-version datom is the preferred managed cache stamp; the relation
  mutation datom is the never-written initialization fallback.
- `integrity/repair-tx-batches` keeps each dangling-half repair and its
  dependency stamps in one transaction. Custom repair or relationship writers
  must either use the managed mutation builder or keep
  `:coherence-authority :unknown`.
- Consumers should call `delete-relationships!` before retracting an entity.
  The Datomic, Datahike, and DataScript integrity reports detect ghost endpoint
  halves left by an incorrect deletion sequence.
- Relationship update operations are validated before endpoint resolution. `delete-object!`
  reports actual committed relationship-datom retractions across all batches.

## Completed-answer cache

Each Datomic, Datahike, and DataScript client owns a bounded native cache. It
has two sound reuse rules:

1. **Exact-current:** accept an entry only for the identical immutable selected
   DB generation.
2. **Managed-current:** under an explicit stamped-writer contract, accept an
   entry when its schema generation and relevant dependency stamp still match.

The cache is an optimization over the cache-free evaluator. It does not define
authorization and does not support time travel.

### Exact-current tier

- Datomic uses the selected current basis as its generation identity.
- Datahike uses immutable DB object identity.
- DataScript uses a private opaque identity handle for the immutable DB object,
  avoiding numeric `max-tx` collisions after reset.
- A transaction replaces the exact generation. No schema/relationship proof
  calculation occurs on an exact hit.
- Publication captures the generation/lifecycle. A delayed computation cannot
  repopulate a newer or explicitly expired lifecycle.

### Managed-current tier

DataScript selects `:coherence-authority :managed` by default and therefore
assumes all authorization-affecting relationship writes use EACL's writers.
DataScript applications that cannot make that guarantee must select
`:coherence-authority :unknown`. Datomic and Datahike require managed authority
to be selected explicitly.

- The semantic query key contains normalized internal object IDs, operation,
  permission, result kind, and relevant configuration.
- A schema-generation object owns all managed entries. A real schema update
  discards the complete old generation.
- Each entry is keyed by the maximum current transaction stamp over its
  complete compiled relation dependency set.
- Under ordinary forward transactions, a relevant write raises that maximum;
  an unrelated write leaves it unchanged.
- Missing/malformed stamps disable managed reuse rather than becoming a
  reusable zero value.
- Datomic reads the current `:eacl/relation-version` datom transaction, with
  the schema-created relation mutation datom as the never-written fallback.
  Datahike and DataScript use their current relation mutation datom
  transactions.
- Custom object-ID codecs remain exact-current-only unless they supply the
  additional deterministic dependency contract.

DataScript defaults to managed authority. Datomic and Datahike default to
`:coherence-authority :unknown`, which is exact-current-only and remains sound
with uninstrumented writers.

### Cache operations

- `eacl.datomic.core/expire-cache!`
- `eacl.datahike.core/expire-cache!`
- `eacl.datascript.core/expire-cache!`

These atomically replace the entire client lifecycle. Use them after reset,
restore, branch force, manual history manipulation, or unstamped bulk repair.
Async Datomic excision is outside this v8 contract.

The corresponding `cache-stats` functions report native exact/managed hits,
misses, bypasses, stamp failures, publications, expirations, and entry counts.
`:cache? false` bypasses caching for one request. Both global `cache/no-cache`
and request-local bypass now branch directly to engine evaluation before
semantic cache-key construction, dependency-stamp capture, provider calls,
snapshot-token calculation, canonicalization, or result-envelope creation.
Cache-disabled callers therefore do not pay the expensive parts of the cache
strategy.

Caller-supplied portable providers are not trusted for completed native
authorization answers. Provider corruption/failure cannot produce an allow.
Continuation state is isolated in a separate bounded private store.

## Cursor redesign

Portable cursor payloads are v10 inside the compact `eacl_c4_` authenticated
frame. Cursors bind the backend/source, operation, complete semantic query
(including principal and consistency), result kind, semantic/configuration
identity, graph anchor, and exact snapshot locator. Relay window size and
direction remain caller-controlled so the same boundary supports forward and
backward navigation.

- Both enumeration routes — acyclic and recursive — emit one cursor kind:
  a keyset boundary on the internal result EID. Recursive enumeration
  presents the canonical strictly-ascending EID order of its completed
  denotation; the previous ordinal cursors over worklist derivation order
  no longer exist, and cursors survive a schema edit that re-routes a
  permission between the acyclic and recursive engines.
- Continuation on the same current immutable snapshot is direct. For a
  recursive walk, the first page resolves (and, with the subproblem cache,
  publishes) the complete sorted denotation; every later page is a
  logarithmic slice with zero backend work.
- For non-exact modes after a write, the keyset boundary is re-validated by
  membership in the freshly evaluated denotation. A surviving boundary
  resumes exclusively after the same EID — surviving results are never
  skipped or duplicated, because an entity's EID cannot move in the order.
  A revoked or deleted boundary drops the bound, restarts in the requested
  page direction, and reports `:cursor-recovery :restarted`.
- Raw (cache-free) recursive first pages keep streaming early-stop
  economics while the result fits the page; a larger result materializes
  its closure once (probe-then-continue on the same verified machine
  state, no replay) — the irreducible price of sorted first pages. A
  denotation beyond `:max-derived-grants` fails with the typed
  recursive-limit error on every raw page; attach the subproblem store,
  raise the limit, or use `:count-limit` for bounded counts.
- `at-exact-snapshot` retains exact continuation and returns a typed
  snapshot-expired failure if that explicit snapshot is unavailable.
- Relationship cursors bind their selected graph anchor rather than hashing
  the complete item sequence; non-exact continuation may rebase the
  authenticated physical edge against a newer selected graph.
- Portable cursors use HMAC authenticity, not encryption. Datomic retains its
  compact AES-GCM codec for cursor-content confidentiality.
- DataScript and Datahike relationship pages now seek from an authenticated
  physical tuple-index edge and resolve only the selected page's public IDs.
  They read at most `page-size + 1` matching internal rows instead of
  materializing and sorting every match before every page.

Permission enumeration presents ascending internal-EID order on both
routes: one deterministic sequence for a fixed query on the selected
snapshot, stable under writes for all surviving results. It is not a
lexical, domain, or cross-backend order (internal EIDs differ per
backend). Relationship pages use each backend's tuple-index order; that
order is an internal pagination contract, not a presentation-order API.

Under concurrent mutation, results granted below a keyset boundary
between pages are not revisited and revoked results disappear — ordinary
keyset semantics; surviving results appear exactly once across a walk.
Exact walks require the explicit exact-snapshot consistency mode.

All old cursor/cache/token candidate envelopes are intentionally incompatible
with the final v8 formats.

## Explorer enumeration performance

V8 enumeration now dispatches from the verified routing certificate. Certified
acyclic roots use the ordered indexed merge/count engine; genuinely active
recursive roots retain the bounded fixed-point engine. Recursive permission
syntax whose in-cycle arrow relations are empty is also executed by the
acyclic engine. Empty recursive guards contribute no denotation and therefore
must not consume recursive traversal limits.

DataScript and Datahike relationship pages reuse an exact, bounded,
client-private page-navigation cache after cursor authentication and immutable
snapshot selection. Repeating a page returns additive `:cached?` and
`:cache-basis` telemetry. A request with `:cache? false`, an exact historical
snapshot, a changed query scope, or a different client cannot reuse the page.

DataScript graph-head selection now reads the single managed head-order datom
directly from EAVT. The previous general Datalog query was on every adapter
construction path, including a relationship-page cache hit, and dominated
browser hit latency as the database grew.

The matched v7/cache-bypassed performance gate passes:

- 10k user-1 page: v8 median 0.79ms versus v7 1.43ms.
- 10k owner-0001 exact 2k count: v8 2.60ms versus v7 2.48ms.
- 50k super-user exact count: v8 117.81ms versus v7 86.82ms
  (1.36x, below the 2.0x release bound).
- The same 50k count with the Explorer recursive schema and no parent
  relationships takes 111.73ms, reports 50,003 merge advances, and performs
  zero recursive work.

The local CLJS Explorer acceptance run keeps repeated nested page hits around
1–3ms, completes recursive-schema view/admin switching at 10k, and completes
the 50k recursive-schema exact count without recursive-limit or retained
snapshot errors. Full evidence is recorded in
`formal/verification/explorer-v8-release.edn`.

## Correctness findings closed

- **Datomic raw writer stamp mismatch.** The first managed-cache implementation
  read only `:eacl.relation/mutation-id`. The documented
  `eacl.datomic.impl/tx-relationship` helper updates
  `:eacl/relation-version`; a managed entry could therefore remain stale.
  Managed Datomic validation now prefers the relation-version datom
  transaction and uses the mutation datom only as the initialization fallback.
- **DataScript exact-snapshot ABA.** Numeric `max-tx` is not a unique database
  identity across `reset-conn!`. Exact snapshot/cursor identity now uses a
  bounded registry of opaque immutable-DB handles.
- **Mixed-snapshot cursor complexity.** Proof-equivalent lifting made a page
  depend on validation across two graphs and converted retention eviction into
  an availability failure. Non-exact continuation now discards graph-specific
  state and re-evaluates on one selected current graph; only explicit
  `at-exact-snapshot` walks remain graph-pinned.
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
  (EACL-FORMAL-047).** The streaming path reused old per-path frontiers and
  reported `:rebased` without establishing that the cursor's result identity
  survived the current permission change. Generic and Datomic adapters now
  request stable-identity rebasing for `:lookup-eid`; the engine discards old
  frontiers, point-checks current membership, and restarts when the identity is
  absent.
- **The routing resource gate measured JVM history (EACL-FORMAL-048).** Its
  first measured size could still be in HotSpot tiered compilation, and the
  gate ran after two ClojureScript compiler builds. Routing is now measured
  first in a fresh 1 GiB JVM after 40 warmups, with 11 samples per size and the
  full observation printed on failure. The exact `P + 2V + E` logical check
  and every allocation/latency ceiling are unchanged.
- **The cursor resource gate compared different operation shapes
  (EACL-FORMAL-049).** Its smallest successful cases fit in one generated
  adapter chunk, while its largest case included intermediate
  `:restarted`-chunk results. The normalized endpoint ratio could therefore
  report a constant-factor chunk transition as super-linear growth. JVM and
  JavaScript gates now span fourfold sizes wholly inside their multi-chunk
  domains, reject invalid fixtures, and isolate the JVM gate from compiler
  history. No ceiling was relaxed, and the one-million-identity recovery gate
  remains in force.
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

- exact/historical/arbitrary-DB completed-cache bypass;
- exact-hit same-snapshot equality;
- late publication cannot repopulate an expired lifecycle;
- forward scalar-stamp invalidation;
- relevant relationship projection framing for direct, self, arrow-relation,
  and arrow-permission rules;
- equality of least fixed points for complete compiled dependencies;
- selected-snapshot internal-to-public result rendering.

The locked Dafny run completes 9,776 proof efforts across 25 source-project
invocations with zero errors, admissions, warnings, or timeouts. The count
includes dependency obligations repeated by multiple top-level invocations; it
is pipeline work, not a count of unique theorems. Generated authority routes
every defined permission root and public authorization operation, and forced
JVM/CLJS suites exercise that routing. Host runtimes, collection semantics,
cryptography, FFI conversion, and backend adapter contracts remain explicitly
trusted or empirically certified boundaries.

The release manifest is therefore `:conditionally-verified`, not unqualified
`:verified`. It deliberately withholds verified release status until an
independent security/formal-methods review is recorded. Generated authority is
the only packaged decision engine for Datomic, Datahike, and DataScript. The
former handwritten engine remains only as a test oracle outside production
source paths.

## Performance evidence

Earlier machine-local point measurements after the current-generation cache
redesign:

| Backend/path | Cached | Cache-disabled | Approximate speedup |
| --- | ---: | ---: | ---: |
| Datomic repeated `can?` | 8.5 µs | 31.8 µs | 3.7× |
| DataScript repeated `can?` | 7.2 µs | 26.8 µs | 3.7× |
| Datahike repeated `can?` | 12.2 µs | 17.6 µs | 1.4× |

Datomic's private current-cache lookup itself measured about 1.5 µs. The
current forced-authority heavy suite passes 17 tests and 4,062 assertions. On
the latest fixed-heap run:

- 15,000-resource first page median: 0.24 ms;
- forward max-page median: 0.55 ms;
- reverse max-page median: 0.45 ms;
- 4,000-node recursive walk: 134.09 ms with cached continuation versus
  3,141.52 ms replaying prefixes;
- distinct-query shared-subgraph p50: 0.182125 ms versus 0.789916 ms for
  completed-answer-only caching, with zero backend operations on the reused
  path.

These are comparative development measurements, not portable latency promises.
The decisive result is architectural: hot exact hits no longer calculate
dependency or content proofs.

## Migration

- Recreate every v8 pre-release client after upgrade.
- Discard old page cursors and pre-release tokens.
- Treat omitted consistency as `:minimize-latency`.
- Request `:fully-consistent` when a backend barrier is required.
- Use `:coherence-authority :managed` only after auditing every relationship
  writer for atomic stamp publication.
- Call `expire-cache!` around excluded history/reset operations.
- Keep `:cache? false` and `cache/no-cache` available for differential
  diagnostics and cache-free reference checks.

See [consistency and cache operations](v8-consistency-cache-operations.md) and
[backend modules and upgrade](v8-backend-modules-and-upgrade.md).
