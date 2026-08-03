# EACL v8.0 candidate

The major version increments because v8.0 adds the database-visible v3 mutation
journal and authenticated causal tokens/cache entries/cursors, and moves the
DataScript/Datahike ports to the v8 Relay list/count contract. The relationship
storage version stays at 7: Datomic keeps its v7 layout, Datahike now uses that
same two-tuple physical layout, and DataScript keeps its backend-specific
entity/composite representation. Client construction performs an idempotent
additive migration that creates the graph family/head, schema mutation
identity, and identities for existing relations.

## Modular artifacts

EACL v8.0 is a workspace with independently consumable modules:

- `modules/eacl` contains the backend-neutral protocol, schema/parser model,
  shared engine, and stable six-function backend SPI.
- `modules/eacl-datomic` contains the complete v8 Datomic implementation.
- `modules/eacl-datascript` contains the CLJ/CLJS DataScript adapter.
- `modules/eacl-datahike` contains the CLJ Datahike adapter and supports both
  keyword and numeric attribute-reference representations. Its relationship
  storage now matches Datomic's two cardinality-many heterogeneous endpoint
  tuples; DataScript retains its entity/composite-index storage adapter.

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

## Authorization cache

- Completed answers use versioned authenticated v3 envelopes. The semantic
  key binds source scope, backend/engine/configuration identity, operation,
  public and internal query identity, result kind, and complete dependencies.
- A candidate is reusable only when the selected snapshot contains its
  computation mutation anchor and its complete schema/relationship proof is
  equal. Numeric transaction ordering, listener observations, TTLs, and
  “latest” pointers are never correctness evidence.
- `:proof-mode :mutation` compares atomically published mutation identities and
  requires `:coherence-authority :managed`. `:proof-mode :content` commits
  canonical selected-snapshot content and remains safe with out-of-band
  writers. Datomic content proof includes both physical relationship halves
  and endpoint identity mappings.
- Cache/provider corruption, races, format mismatch, or proof failure become a
  miss on the already selected immutable snapshot. Token/freshness/exact
  failures remain request errors.
- `:cache? false` bypasses retention for one request. Use
  `eacl.cache/no-cache` for portable adapters or
  `eacl.datomic.cache/no-cache` for Datomic to disable it globally.
- The v7 process-local recursive-continuation and latest-result side caches are
  not trusted by v8. Recursive pages replay deterministically from the
  authenticated cursor until continuation state is protected by the same v3
  proof envelope.

## Recursive pagination and counts

- A cursor authenticates source/branch scope, graph head and exact locator,
  operation/query/configuration identity, complete dependency/proof digests,
  stable position, and expiry.
- Continuation first rederives the full closure and proof on the request's
  selected snapshot. Equal proof continues there and rebases the next cursor;
  changed proof uses the authenticated original exact value only when a newer
  at-least floor does not forbid moving backward.
- A missing exact value is a typed snapshot-expired failure. A changed proof
  plus an incompatible newer floor is a typed cursor-consistency conflict.
- Database identity is authenticated and rejected before historical selection; shared page-token
  keys do not permit cursor replay against another logical Datomic database.
- Acyclic count misses advance through bounded frontier pages; recursive counts use one explicit,
  hard-capped traversal state. Neither count direction retains a full-cardinality lazy result head.

## Consistency

- Read and write tokens are bounded v3 envelopes authenticated with
  domain-separated keys. They bind backend, database/store identity, causal
  family, Datahike branch, random graph mutation anchor, order hint, exact
  locator, issue time, and expiry. Older listener/revision token formats are
  rejected.
- Authorization reads support `fully-consistent`, `minimize-latency`, `at-least-as-fresh`, and
  historical `at-exact-snapshot`.
- Datomic `fully-consistent` performs a bounded zero-argument `d/sync`
  authoritative barrier. At-least uses bounded two-argument `d/sync` only as a
  waiting hint, then requires the selected DB to contain the token mutation
  anchor.
- Exact cache entries remain accelerators; on a miss or provider failure, exact reads evaluate
  against verified `d/as-of` with schema state reconstructed at the requested
  basis and the expected graph identity.
- Optional bounded revision checkpoints construct age-based lower-bound tokens without arithmetic
  on `t`, background timers, retained DB values, or implicit synchronization.
- `:zed-token-key`, `:zed-token-keyring`, and `:zed-token-kid` support stable multi-instance keys
  and overlap-based rotation. When omitted, domain-separated signing keys derive from the page-token
  keyring; the random default is deliberately client-instance-local.
- Token authentication prevents claim forgery but not replay and is not
  authorization. Numeric basis/order equality never substitutes for mutation
  anchor membership.

## Schema and mutation lifecycle

- Client construction idempotently establishes one causal family and mutation
  graph when absent. Concurrent migrations converge through transactional
  compare-and-swap.
- `write-schema!` publishes the schema mutation identity and graph head in the
  same committed transaction as the schema delta.
- Completed answers are keyed by their complete semantic identity and carry
  authenticated computation/validation points, dependency closure, and
  schema/relation proof. A selected snapshot can lift an older answer only
  when it contains the computation mutation and all complete proofs match.
- `:coherence-authority :managed` means every schema, relationship, mutable
  identity, caveat, and custom dependency writer participates in the mutation
  protocol. Unknown or mixed writers must remain `:unknown`; `:proof-mode
  :auto` then uses full-content proof rather than mutation identity.
- Every managed relationship helper publishes one v3 mutation record, advances
  the graph head, and stamps every affected relation mutation identity in the
  same transaction as the tuple change. Batched deletion does this separately
  for each committed batch and mints its response token from the final
  `db-after`.
- Datomic still emits `:eacl/relation-version` CAS datoms to serialize
  competing relationship writes against the existing storage schema. They are
  not a v3 cache proof. Mutation mode trusts only the declared v3 writer
  protocol; content mode hashes complete scoped tuples and endpoint identities.
- `integrity/repair-tx-batches` keeps each dangling-half repair and its
  dependency publication in one transaction. Custom repair or relationship
  writers must either use the v3 mutation builder or keep
  `:coherence-authority :unknown`.
- Consumers should call `delete-relationships!` before retracting an entity.
  The Datomic and Datahike integrity reports detect ghost tuple halves left by
  an incorrect deletion sequence.
- Relationship update operations are validated before endpoint resolution. `delete-object!`
  reports actual committed relationship-datom retractions across all batches.

The cache can be disabled with `{:cache eacl.datomic.cache/no-cache}`.
Authorization remains correct and usable, with the expected loss of
cache-dependent performance. This does not create causal writer authority:
at-least/exact token guarantees still require `:coherence-authority :managed`.

Operational retention, writer-authority configuration, key rotation, cursor
proof lifting, failure containment, and typed diagnostics are documented in the
[v8 consistency and cache guide](v8-consistency-cache-operations.md).

## Fixes from the 2026-07-31 adversarial review

See [docs/reports/2026-07-31-eacl-v8.0-cache-adversarial-review.md](reports/2026-07-31-eacl-v8.0-cache-adversarial-review.md).
All of these were found in this candidate and fixed before release.

- Recursive page-cache keys are scoped by pagination direction. A `:last`/`:before` page could
  previously be served to a later `:first`/`:after` request naming the same cursor and size,
  silently returning the wrong page — or an empty one, which stops a paginating caller early.
- A relationship write that fails before submitting a transaction no longer invalidates cached
  results. A `:create` conflict or unknown object id commits nothing.
- A client constructed before the database was schema-stamped adopts the stamp when one appears,
  and cursors minted on an unstamped basis validate against that same basis. Such a client could
  previously mint page-one tokens it then rejected on page two.
- Lookup results naming objects with no external id raise `:eacl/unresolvable-object` listing every
  offending eid, instead of a cache-flavoured `:eacl.consistency/snapshot-unavailable` naming one.
- `delete-object!` takes the relationship barrier per batch, so a large deletion no longer blocks
  concurrent lookups for its whole multi-transaction run.
- `fully-consistent` performs its authoritative selection barrier before
  validating any candidate answer.
- Page tokens are length-bounded, reject hostile EDN without escaping a `StackOverflowError`, and
  report every cursor rejection as `:eacl.pagination/invalid-cursor` with a `:reason`.
- Cursor pages no longer publish live entries and latest-result pointers that nothing can read.
- The churn benchmark compares mutation-identity proof, complete-content
  proof, global invalidation, and no-cache under both unrelated and relevant
  writes. Proof validation is intentionally retained even where the small
  fixture makes uncached evaluation faster: correctness is the gate, not a
  benchmark-selected consistency model.
- Datomic content proofs are fixed-size streaming digests over deterministic
  schema records, both relationship tuple halves, and endpoint identities.
  Proof size therefore does not grow cursor or cache envelopes with graph
  cardinality.
- The old cached per-intermediate stream-head optimization is disabled because
  its state was outside the authenticated v3 envelope. Acyclic and recursive
  cursor pages deterministically replay the authenticated frontier.
- `can?` answers an arrow by intersecting two sorted intermediate streams instead of scanning the
  resource's side in full, so it no longer scales with arrow fan-out. A doc attached to N teams
  where the user belongs to one of them: 133us -> 13us at N=100, 1.34ms -> 13us at N=1000,
  7.16ms -> 16us at N=5000. An arrow with a single intermediate keeps the old point probe.
- Permission paths are ordered cheapest-to-check first, so a union short-circuits on a direct
  relation before paying for an arrow. Path order previously came out of a `clojure.set/difference`
  in `write-schema!` — `owner + team->access` versus `team->access + owner` measured 6.8ms versus
  3.4us on identical data, decided by hash order and invisible to the schema author.
- Page tokens moved from EDN to a compact binary payload (`eacl.datomic.codec`) with a reused
  AES-GCM cipher: encode ~41us -> ~2.4us, decode ~24us -> ~2.6us. A page mints two cursors and
  reads one, so `lookup-resources`/`lookup-subjects` are roughly 2x faster uncached and 3x faster
  with live results. The prefix is now `eacl4_`; `eacl3_` cursors from an earlier build are
  rejected as `:eacl.pagination/invalid-cursor`, the same way an expired cursor already is.

## Removed: `:live-results?` and the relationship coordinator

`:cache {:live-results? true}` and `:cache {:coordinator ...}` are gone, along with the
`RelationshipCoordinator` protocol, `local-coordinator`, `local-context`, the read and mutation
barriers, and the bounded reader catch-up loop. Both keys now throw `:eacl/invalid-config` rather
than being silently ignored.

Database-visible mutation identities and selected-snapshot content proofs make
the coordinator redundant. A process-local coordinator can observe only
participating writers and cannot prove an authoritative head, causal ancestry,
or dependency equality.

Migration: replace `:cache (assoc (cache/local-context) :live-results? true)` with
`:cache <adapter>` (or just omit it), and delete any coordinator plumbing. A writer-only client
configured as `{:store false :coordinator shared}` becomes `{:cache cache/no-cache}` — it no longer
needs to
participate in anything. `:eacl.consistency/coordinator-floor-unreachable` can no longer be raised.

`spiceomic-write-relationships!` no longer distinguishes a validation failure from a
possibly-committed throw. It does not need to: tx-data that never commits publishes nothing, so the
regression that distinction existed to prevent — one routine `:eacl/relationship-conflict` flushing
every cached result — is now structurally impossible.
