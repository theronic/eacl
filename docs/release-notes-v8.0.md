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

- The default authorization mode is `:local-snapshot`.
- Datomic ordinary reads call `d/db` once and do not call `d/sync` or
  `d/as-of`.
- `:synchronized-head` explicitly requests a backend synchronization barrier.
  `:fully-consistent` remains a compatibility name for that behavior.
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

Configure `:coherence-authority :managed` only when all authorization-affecting
relationship writes use EACL's writers or the documented stamped transaction
helper.

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

The default `:coherence-authority :unknown` is exact-current-only and remains
sound with uninstrumented writers.

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

Caller-supplied portable providers are no longer trusted for completed native
authorization answers. Provider corruption/failure cannot produce an allow.
Legacy provider types remain as compatibility and test surfaces, while
continuation state is isolated in a separate bounded private store.

## Cursor redesign

Portable cursor payloads are v10 inside the compact `eacl_c4_` authenticated
frame. Cursors bind the backend/source, operation, complete semantic query
(including principal and consistency), result kind, semantic/configuration
identity, graph anchor, and exact snapshot locator. Relay window size and
direction remain caller-controlled so the same boundary supports forward and
backward navigation.

- Continuation on the same current immutable snapshot is direct.
- For non-exact modes, a changed graph is re-evaluated against the selected
  current snapshot and reports `:cursor-recovery :rebased`.
- Graph-specific recursive state restarts from the current first page and
  reports `:cursor-recovery :restarted`.
- `at-exact-snapshot` retains exact continuation and returns a typed
  snapshot-expired failure if that explicit snapshot is unavailable.
- Relationship cursors bind their selected graph anchor rather than hashing
  the complete item sequence; non-exact continuation may re-evaluate the
  offset against a newer selected graph.
- Portable cursors use HMAC authenticity, not encryption. Datomic retains its
  compact AES-GCM codec for cursor-content confidentiality.

Recovery has ordinary weak-pagination behavior under concurrent mutation:
duplicates or omissions across page boundaries are possible, but every
returned page is freshly authorized on one selected graph. Exact walks require
the explicit exact-snapshot consistency mode.

All old cursor/cache/token candidate envelopes are intentionally incompatible
with the final v8 formats.

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

The full Dafny run verifies 242 obligations across 12 source files with zero
errors. This is a formal proof of the named models and refinement lemmas, not a
claim that every public Clojure/CLJS production path and adapter implementation
is end-to-end formally verified. The release manifest therefore remains
`:not-verified` until complete generated-kernel routing, shadow rollout,
independent review, and the remaining release gates are complete.

## Performance evidence

Machine-local nREPL measurements after the redesign:

| Backend/path | Cached | Cache-disabled | Approximate speedup |
| --- | ---: | ---: | ---: |
| Datomic repeated `can?` | 8.5 µs | 31.8 µs | 3.7× |
| DataScript repeated `can?` | 7.2 µs | 26.8 µs | 3.7× |
| Datahike repeated `can?` | 12.2 µs | 17.6 µs | 1.4× |

Datomic's private current-cache lookup itself measured about 1.5 µs. The full
heavy suite passed 9 tests and 3,403 assertions. On the same run:

- 15,000-resource first page median: 1.60 ms;
- forward max-page median: 2.00 ms;
- reverse max-page median: 1.79 ms;
- resource count: 27.23 ms cold and 0.010 ms hot;
- 4,000-node recursive walk: 68.73 ms with continuation versus 1,698.02 ms
  replaying prefixes.

These are comparative development measurements, not portable latency promises.
The decisive result is architectural: hot exact hits no longer calculate
dependency or content proofs.

## Migration

- Recreate every v8 pre-release client after upgrade.
- Discard old page cursors and pre-release tokens.
- Treat omitted consistency as local snapshot.
- Request `:synchronized-head` when a backend barrier is required.
- Use `:coherence-authority :managed` only after auditing every relationship
  writer for atomic stamp publication.
- Call `expire-cache!` around excluded history/reset operations.
- Keep `:cache? false` and `cache/no-cache` available for differential
  diagnostics and cache-free reference checks.

See [consistency and cache operations](v8-consistency-cache-operations.md) and
[backend modules and upgrade](v8-backend-modules-and-upgrade.md).
