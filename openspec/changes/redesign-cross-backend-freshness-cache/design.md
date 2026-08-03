## Context

EACL must answer two independent questions for every authorization request:

1. Which immutable snapshot satisfies the caller's consistency requirement?
2. Is an answer computed on another snapshot observationally equivalent on the
   selected snapshot?

A transaction position can help with the first question. It cannot answer the
second. If a selected Datomic basis or DataScript/Datahike `:max-tx` is newer
than a token, the intervening transaction could be an unrelated application
write or a permission revocation. A separate dependency proof is necessary to
distinguish those cases.

The first version of this design still made three assumptions that do not
survive adversarial counterexamples:

- It assumed transaction positions are globally monotonic inside a named
  database/branch. Datomic restore, DataScript `reset-conn!`, and Datahike
  branch/force operations can replace a head with another history.
- It assumed a current-transaction relation stamp uniquely identifies relation
  content. Two divergent histories can independently allocate the same
  transaction number to different data.
- It treated exact historical reconstruction as the only safe way to continue
  a cursor after any snapshot change. A complete equal dependency proof is
  sufficient and avoids history for unrelated changes.

The corrected design treats transaction positions as order/wait hints and
uses unique causal mutation identities for correctness.

### Verified backend facts

The current Datomic adapter already uses authenticated basis tokens and
database-visible `:eacl/relation-version` stamps. It does not use a listener
counter for completed-answer correctness. Datomic documents that:

- every database value is immutable and has a basis `t`;
- transactions for one database are totally ordered and every Peer sees a
  gap-free prefix;
- two-argument `d/sync` waits for a known `t`; and
- zero-argument `d/sync` communicates with the transactor and returns a value
  including every transaction complete when synchronization was requested.

The zero-argument contract was also verified from the loaded Datomic Peer API
metadata. Therefore true Datomic `:fully-consistent` selection requires
bounded zero-argument `d/sync`, not ordinary `d/db`.

The Datahike design was audited against the exact EACL dependency revision,
`779724b60d1e4292a39868b2e27bfff8bf7e0e69`:

- Datahike is single-writer/multiple-reader per branch and publishes immutable
  branch heads
  ([distributed operation](https://github.com/replikativ/datahike/blob/779724b60d1e4292a39868b2e27bfff8bf7e0e69/doc/distributed.md)).
- Non-streaming connection dereference reads the configured branch head from
  its store; streaming connections read their synchronized local value
  ([connector source](https://github.com/replikativ/datahike/blob/779724b60d1e4292a39868b2e27bfff8bf7e0e69/src/datahike/connector.cljc)).
- Database values expose `:max-tx`, commit id, and parent commit ids. Retained
  commits can be loaded with `commit-as-db`
  ([versioning](https://github.com/replikativ/datahike/blob/779724b60d1e4292a39868b2e27bfff8bf7e0e69/doc/versioning.md)).
- Temporal `as-of` requires `:keep-history? true`; EACL currently configures it
  false
  ([time variance](https://github.com/replikativ/datahike/blob/779724b60d1e4292a39868b2e27bfff8bf7e0e69/doc/time_variance.md),
  [configuration](https://github.com/replikativ/datahike/blob/779724b60d1e4292a39868b2e27bfff8bf7e0e69/doc/config.md)).
- Commit records can be unavailable when the commit graph is disabled or after
  garbage collection
  ([garbage collection](https://github.com/replikativ/datahike/blob/779724b60d1e4292a39868b2e27bfff8bf7e0e69/doc/gc.md)).
- `force-branch!` is an unconditional branch-head replacement and can publish a
  DB value with a lower `:max-tx`
  ([versioning source](https://github.com/replikativ/datahike/blob/779724b60d1e4292a39868b2e27bfff8bf7e0e69/src/datahike/versioning.cljc)).

Pinned-dependency nREPL experiments established:

- `:db/current-tx` can be written atomically with a Datahike relation mutation;
- `commit-as-db` recovers an old commit with `:keep-history? false`;
- `commit-as-db` is unavailable when `:commit-graph? false`;
- `as-of` cannot reliably recover superseded `:db/noHistory` stamps; and
- `force-branch!` can move a branch from `:max-tx 536870914` to a new commit at
  `:max-tx 536870913`.

DataScript source and nREPL experiments established:

- each transaction advances immutable DB `:max-tx`;
- `:db/current-tx` resolves to that transaction id;
- `reset-conn!` can replace a connection's DB value; and
- two transactions applied independently to the same base DB can produce
  identical `:max-tx` and identical current-transaction stamps but different
  relationship content.

The existing portable cursor is base64-encoded EDN, not authenticated. The new
format cannot rely on an existing portable cryptographic boundary.

### Terms

- **source scope**: backend plus stable native store/database identity and
  branch where applicable.
- **mutation id**: a cryptographically random identifier generated once for an
  EACL graph mutation and committed atomically with it.
- **causal anchor**: an append-only mutation id proving that a selected
  snapshot includes the token-issuing EACL graph mutation.
- **order hint**: a backend position such as basis `t` or `:max-tx` used to
  avoid unnecessary polling; it is not proof of lineage or graph equality.
- **exact locator**: a backend value capable of retrieving one exact immutable
  snapshot while retained.
- **dependency proof**: a canonical, snapshot-bound proof covering every input
  that can change an authorization answer.
- **dominance**: snapshot `S` dominates token `T` only when their source scope
  is compatible and `S` contains `T`'s causal anchor. Numeric comparison alone
  never establishes dominance.
- **proof lifting**: reusing an answer computed on `C` after proving that `C`
  causally precedes selected snapshot `S` and the complete dependency proofs
  are equal.

## Goals / Non-Goals

**Goals:**

- Implement causal `:at-least-as-fresh` behavior for Datomic, Datahike, and
  DataScript without listener counters.
- Remain correct across connection restarts, cloned immutable values, database
  restore, DataScript reset, and Datahike branch-head replacement.
- Preserve completed-answer and cursor reuse across unrelated transactions.
- Make fast mutation-identity proofs optional and fail closed to content proof
  or no-cache when writer participation is not guaranteed.
- Put consistency orchestration, proof validation, token/cursor security, and
  conformance tests in shared code.
- Advertise only snapshot/head guarantees actually available from a backend's
  configured source.

**Non-Goals:**

- Add replication or a remote revision service to DataScript.
- Guarantee indefinite Datahike commit or token-marker retention.
- Make an untrusted or Byzantine primary database safe.
- Cache adapter functions that are time-dependent, read external mutable state,
  or cannot declare all authorization dependencies.
- Preserve old decimal listener tokens, unauthenticated portable cursors, or
  old cache entries.

## Decisions

### 1. Define the cache theorem before the implementation

For semantic request `q`, let:

- `S` be the immutable snapshot selected for this request;
- `C` be the snapshot where candidate answer `A` was computed;
- `F(q, X)` be the deterministic authorization result on snapshot `X`;
- `D(q, X)` be the complete static dependency closure for `q` under `X`'s
  schema; and
- `P(D, X)` be the canonical proof of those dependencies on `X`.

EACL may return cached `A = F(q, C)` for selected snapshot `S` only if all of
the following hold:

1. the entry's authenticated source scope and semantic key match the request;
2. `C` is not causally after `S`; the selected snapshot contains the entry's
   computation anchor or is the exact computation snapshot;
3. the selected schema proof matches the entry schema proof;
4. `D(q, S)` is complete and equals the entry's dependency scope;
5. `P(D(q, S), S) = P(D(q, C), C)`;
6. engine, adapter, query normalization, result format, and every
   answer-affecting configuration fingerprint match; and
7. the cached value shape and entry authenticator are valid.

Under the adapter contract that `F` is a pure function of those inputs,
conditions 3–6 imply `F(q, S) = F(q, C)`. Conditions 1, 2, and 7 prevent
cross-source, backward-history, key-confusion, and cache-forgery substitutions.

This theorem explicitly rejects a candidate computed in a future or sibling
history merely because its relation proof happens to match. Proof lifting is
forward-only.

Every cross-revision return re-reads or recomputes the selected snapshot's
proof. `validated-at` is telemetry, not a lease that permits later proof reads
to be skipped.

### 2. Separate causal freshness from dependency equality

Every managed EACL graph mutation creates a cryptographically random mutation
id and, in the same transaction:

- appends an immutable mutation-journal record;
- updates the singleton graph-head id and backend order hint;
- updates the schema mutation id when schema changed; and
- updates every affected relation's mutation id.

Every change to data that can affect EACL's public authorization result belongs
to this protocol. That includes schema and relationships, plus mutable
object-identity mappings, caveat inputs, or custom adapter data when an adapter
declares them as dependencies. Without complete causal writer authority, EACL
may still evaluate current snapshots and use full-content cache proofs, but it
MUST NOT issue a read token claiming to represent the selected authorization
state or advertise causal `:at-least-as-fresh`.

This restriction is information-theoretic. A full-content read can establish
what a snapshot contains, but it cannot establish that a later divergent
snapshot descends from an unjournaled write. No cache algorithm can reconstruct
missing causality after the fact.

Conceptually:

```clojure
{:mutation/id <random-256-bit-id>
 :mutation/order <basis-t-or-max-tx>
 :mutation/kind :schema|:relationships
 :graph/head-id <same-id>
 :graph/head-order <same-order>
 :schema/version-id <same-id-if-schema>
 :relation/version-id <same-id-for-each-affected-relation>}
```

The storage representation may use UUID plus an HMAC-derived collision check or
an equivalent fixed-width cryptographic identifier. A current-transaction ref
may be stored alongside it as an order hint, but equality proofs use mutation
identity, never the transaction number alone.

The append-only journal closes restore/fork/reset ambiguity:

- a snapshot restored or cloned from before token `T` does not contain `T`'s
  mutation id, even if its numeric transaction counter later reaches the same
  value;
- a clone from after `T` contains the marker and legitimately causally follows
  `T`; and
- later graph mutations change only their affected relation identities, so
  unrelated cache entries remain valid.

The mutation id is generated before transaction submission and reused across
ambiguous retries. Writers reject or idempotently recover an already committed
mutation id rather than attaching the same id to different tx-data. This lets a
caller recover a committed causal token after a timeout without creating a
colliding dependency proof.

Tokens have an authenticated maximum lifetime. Mutation journal entries MUST
remain queryable for at least the maximum token lifetime. Missing retained
anchors produce a typed token-expired/history-diverged result, never numeric
fallback. An installation that chooses non-expiring tokens accepts unbounded
journal retention.

**Alternatives rejected:**

- `(database-id, transaction-number)` fails after restore or reset.
- `(database-id, branch, :max-tx)` fails after Datahike `force-branch!`.
- a current-transaction relation stamp collides across divergent branches.
- a persisted database UUID alone is copied by clone/restore and does not prove
  ancestry.

### 3. Use one authenticated token envelope with causal and exact fields

The public `:zed/token` remains opaque. Its decoded payload contains:

```clojure
{:version 3
 :backend :datomic|:datahike|:datascript
 :source-id <native-stable-scope>
 :branch <datahike-branch-or-nil>
 :graph-anchor <mutation-id>
 :order-hint <basis-t-or-max-tx>
 :exact-locator <basis-t|commit-id|retained-handle>
 :issued-at <bounded-time>
 :expires-at <bounded-time>}
```

At-least selection uses `graph-anchor` membership as the correctness
postcondition and `order-hint` only to decide whether waiting may be necessary.
Exact selection uses `exact-locator` and verifies the loaded snapshot's source
scope and graph-head identity.

The shared codec provides:

- canonical serialization with explicit type/range validation;
- strict field allowlists and size/depth limits;
- domain-separated HMAC keys for Zed tokens and completed cache entries;
- mandatory cursor authentication, with authenticated encryption when the
  configured runtime exposes a compatible synchronous/async API surface;
- key ids and read-keyrings for rotation;
- constant-time tag verification; and
- distinct token, cursor, and cache signing domains.

Portable signed cursors contain stable external identities or opaque digests,
not raw secret backend objects. Datomic retains encrypted pagination. A
synchronous ClojureScript client is not forced to call asynchronous WebCrypto;
it may use a vetted synchronous MAC implementation and advertise
confidentiality separately.

The CLJ and CLJS codecs use a portable exact representation for revisions.
They MUST reject values outside the backend runtime's exact integer range
rather than silently rounding them.

### 4. Make consistency modes name actual selection behavior

All modes select one immutable snapshot before authorization or cross-revision
cache validation.

#### Fully consistent

Select a snapshot containing every graph mutation complete at the selection
barrier:

- Datomic uses bounded zero-argument `d/sync`, which communicates with the
  transactor.
- Datahike uses an authoritative branch-head read. A lagging replicated or
  streaming client without an authoritative barrier MUST NOT advertise this
  capability until its writer protocol exposes one.
- DataScript uses the current value of its single-process connection; writes on
  that connection are serialized.

Ordinary Datomic `d/db`, a Datahike listener count, or a lagging local replica
is only latest-observed and cannot implement this mode.

#### Minimize latency

Select a complete immutable snapshot already available locally, without an
authoritative-head wait. It may be stale relative to another process. The cache
is validated on that selected snapshot; a result-only cache entry is not itself
silently treated as a database snapshot.

#### At least as fresh

Given token `T`:

1. authenticate and validate its source scope and expiry;
2. read a local/current snapshot;
3. use its order hint to avoid waiting when it is obviously behind;
4. refresh/wait within the deadline;
5. require the selected snapshot to contain `T.graph-anchor`; and
6. return history-diverged/token-expired if the numeric position is sufficient
   but the causal anchor is absent.

The response token is derived from the selected snapshot's current graph head.

#### At exact snapshot

Load only the exact locator named by the token and verify its source scope,
locator, and graph-head identity. If unavailable or mismatched, return
snapshot-expired. Proof-equivalent substitution is not called “exact.”

### 5. Keep backend selection specific and capability-gated

#### Datomic

- Source scope includes Datomic's native database id.
- Order hint and exact locator are basis `t`.
- Managed writes mint tokens from transaction report `db-after` and the
  mutation id committed in that transaction.
- `:at-least-as-fresh` uses bounded two-argument `d/sync` when the local basis
  is behind, then verifies mutation-anchor membership.
- `:fully-consistent` uses bounded zero-argument `d/sync`.
- Exact selection uses `d/as-of` after synchronization and verifies the graph
  head visible at that basis.
- Existing transaction-ref relation stamps may remain for diagnostics and
  linear-history optimization, but cache equality uses v3 mutation identities
  or content proofs.

#### Datahike

- Source scope includes stable store id and configured branch.
- Exact locator is commit id when retained.
- `:max-tx` is only an order hint. `force-branch!` and branch construction make
  it insufficient as a causal order.
- Managed transaction tokens contain the committed mutation id and the final
  committed `db-after`/commit id. If writer batching returns an over-fresh final
  commit to multiple reports, that remains valid.
- A non-streaming connection may refresh by dereferencing its branch head.
  Streaming/replicated sources advertise authoritative-head selection only when
  their writer/replication protocol supplies that barrier.
- Exact reconstruction prefers `commit-as-db`; temporal `as-of` is a
  capability-gated fallback.
- Missing commits or mutation anchors return expiry/divergence errors.
- Commit parent traversal may optimize or diagnose causal selection but does
  not replace mutation-anchor membership, because `force-branch!` can attach
  arbitrary parents to a DB value.

#### DataScript

- Source scope contains a durable random causal-family id stored with the
  database so tokens survive connection/process restart. Copying that id is not
  treated as proof of ancestry; mutation-anchor membership supplies that proof.
- A clone made after token `T` may accept `T` because it contains `T`'s marker.
  A clone/reset made before `T`, or an unrelated DB, rejects it by missing
  anchor or family mismatch.
- Order hint is immutable DB `:max-tx`.
- Managed writes mint tokens from transaction report `db-after` and their
  committed mutation id.
- A current connection satisfies an at-least token only when it contains the
  causal anchor. If it is behind, EACL may wait for that same connection to
  advance until the deadline. It does not claim replication.
- `reset-conn!` to a pre-token or divergent DB is detected by missing causal
  anchors, even if `:max-tx` collides.
- Exact selection requires the current exact DB or a bounded registry handle
  to a retained immutable DB. Numeric equality alone is not exact identity.

### 6. Use two explicit dependency-proof modes

#### Mutation-identity proof

The fast mode reads:

- current schema mutation id;
- the complete static transitive closure of relation-definition dependencies;
  and
- the current mutation id for every relation in that closure.

The closure is derived from schema, not from runtime paths visited by a
particular positive or negative evaluation. Short-circuiting, empty data, a
denied result, recursive cycles, and pagination boundaries MUST NOT omit a
relation that could change the answer.

This mode is enabled only when the client has an explicit coherence authority
guaranteeing that every schema/relationship writer uses the v3 mutation
protocol. Merely finding the metadata attributes is not evidence of writer
compliance.

#### Full-content proof

The conservative mode canonically hashes or compares all scoped schema
definitions and relationship tuples from the selected immutable snapshot. It
detects raw writers without relying on mutation metadata. It is potentially
expensive but remains the correctness oracle and safe fallback.

If neither proof is complete or affordable, EACL evaluates without retaining a
completed answer. It never silently selects fast stamps because metadata
happens to exist.

Every derived schema catalog, permission-path memo, recursive dependency
closure, and direct-grant memo is keyed by selected snapshot schema mutation id
or content proof. Listener callbacks may pre-evict these structures but cannot
make them authoritative.

Adapter query operations MUST be deterministic and snapshot-pure. If an object
id codec, caveat evaluator, or custom primitive reads external mutable state,
that state must be represented in the semantic/configuration fingerprint and
dependency proof or caching and proof-equivalent cursors are disabled.

For database-backed object identity, the semantic key contains both the
canonical public query identity and the selected internal identity. Cache
entries and cursors also carry an identity-boundary proof for query objects,
result objects, and ordering positions whose external identity may change.
Alternatively an adapter may declare and enforce those identities as immutable.
A function/configuration fingerprint alone is insufficient when the mapping
data itself is mutable.

### 7. Authenticate and fully scope completed cache entries

The lookup key and embedded entry include:

- cache format and engine algorithm versions;
- backend/source/branch scope;
- semantic operation and complete canonical internal query;
- pagination direction/position where applicable;
- recursion, count, traversal, codec, and adapter configuration fingerprint;
- result kind; and
- exact locator for exact-only entries.

The entry additionally includes:

- computation causal anchor and exact locator;
- most recent validation anchor for telemetry;
- complete dependency scope and proof;
- result value; and
- a domain-separated authenticator over the canonical entry.

An externally writable or shared cache can otherwise forge a Boolean grant or
lookup result while copying visible proof fields. Authentication makes
corruption a miss. The cache remains a trusted availability dependency only:
it may drop or delay entries but cannot create an accepted authorization value
without the cache key.

The same rule applies to authorization-affecting intermediate entries:
recursive continuations, traversal frontiers, schema/path materializations, and
latest-entry pointers. An intermediate from an external/shared provider is
authenticated and fully scoped, or it is treated as a miss and reconstructed.
A cursor MUST be self-sufficient enough to recompute its continuation after
intermediate-cache eviction; cache retention is never a pagination correctness
requirement.

A selected snapshot MUST dominate the computation anchor before lifting. An
entry computed on a numerically “future” sibling history is rejected even when
its dependency proof matches.

Concurrent validation updates use compare-and-set or monotonic merge where the
provider supports it. An older writer overwriting telemetry is harmless to
correctness because every later cross-revision hit validates again, but it
should not regress metrics unnecessarily.

### 8. Continue cursors on exact or proof-equivalent graphs

A first page cursor binds, under mandatory authentication and optional
authenticated encryption:

- format, engine, adapter, and configuration fingerprints;
- source/branch scope;
- original graph anchor and exact locator;
- canonical query scope and direction;
- complete dependency scope and proof digest;
- stable total-order position; and
- expiry.

Cursor size is bounded. The continuation rederives the complete dependency
closure from authenticated query scope and selected schema. The cursor may
carry a domain-separated cryptographic digest of the canonical complete proof
rather than the unbounded proof map. It never truncates a dependency set.
When a complete proof cannot be represented or recomputed within configured
resource limits, graph-equivalent continuation is unavailable and EACL uses an
exact locator or returns a typed stale/size error.

A continuation proceeds:

1. authenticate/decrypt and validate query/config scope;
2. if an additional at-least floor exists, select a snapshot satisfying it;
   otherwise select the current configured snapshot;
3. compute the complete dependency proof on that snapshot;
4. if the proof equals the cursor proof, continue on the selected snapshot;
5. otherwise try the original exact locator when doing so does not conflict
   with a newer requested floor; and
6. if neither path works, return stale-cursor/snapshot-expired.

After proof-equivalent continuation, newly emitted cursors are rebased to the
selected snapshot's graph anchor, locator, and proof while preserving the
deterministic enumeration.

Proof equality means the authorized, deterministically ordered result set is
the same, so inserts/deletes in unrelated relations cannot create duplicates
or omissions. Mutation-identity proofs conservatively reject a cursor after a
relevant change even when content later returns to its old value.

A newer at-least token is not automatically incompatible with a cursor. It is
compatible when a snapshot satisfying the token has the same dependency proof.
It conflicts only when the graph changed and exact historical continuation
would violate the newer floor.

Exact reconstruction remains useful when relevant dependencies changed and the
caller wants the original enumeration. It is not required for unrelated
transactions or proof-equivalent current state.

Stable pagination also requires a deterministic total order and cursor
position. Backend enumeration order, hash-map order, or an incomplete
tie-breaker cannot be used.

### 9. Classify failures by whether the contract can still be met

Request errors:

- token/cursor authentication or scope failure;
- expired/missing causal anchor;
- freshness deadline;
- unavailable or mismatched exact snapshot;
- incompatible cursor freshness after proof mismatch; and
- unsupported authoritative-head capability.

Safe cache misses:

- provider failure;
- malformed, unauthenticated, old-format, future-history, or mismatched entry;
- missing or incomplete dependency proof;
- proof mismatch; and
- non-deterministic/unfingerprinted adapter configuration.

A cache miss evaluates on the already selected immutable snapshot. An exact or
freshness failure never falls back to another snapshot.

### 10. Make the correctness argument executable

The implementation is not accepted solely because example tests pass. The
shared suite includes:

- a small reference authorization model using full-content proofs;
- generated schemas including unions, arrows, nested permissions, recursion,
  cycles, missing entities, and positive/negative answers;
- generated interleavings of unrelated and relevant mutations;
- clone, restore/reset, same-transaction-number divergence, Datahike branch,
  force-head, and commit-GC state transitions;
- cache poisoning, stale pointer, future candidate, wrong DB/branch, key
  rotation, malformed/deep token, and provider race faults;
- cursor mutations before/after the boundary and proof-equivalent continuation;
- differential comparison of every cached answer with uncached evaluation on
  the selected snapshot; and
- capability-matrix tests proving unsupported configurations fail before
  evaluation.

For every generated trace, the oracle property is:

```text
returned authorization result
= uncached deterministic result on the response token's selected graph
```

For cursor traces, concatenated pages must equal the ordered result set of
either the original exact graph or a graph with an equal complete dependency
proof.

## Risks / Trade-offs

- [Mutation journal grows] → Give tokens an authenticated maximum lifetime and
  retain journal anchors for at least that window. Infinite token validity
  explicitly means infinite anchor retention.
- [Custom writer omits v3 metadata] → Fast proofs require explicit coherence
  authority; otherwise use content proof or no completed-answer cache.
- [Dependency analysis omits a possible relation] → Use a static schema closure,
  fail closed on empty/unknown closure, compare against the full-content oracle,
  and mutation-test positive and negative recursive cases.
- [Datomic fully-consistent synchronization blocks] → Use zero-argument
  `d/sync` with a mandatory timeout and typed failure.
- [Datahike reader is a lagging replica] → Do not advertise authoritative-head
  consistency without a writer/store barrier; at-least remains bounded by a
  known mutation anchor.
- [Datahike commit is collected] → Continue on an equal dependency proof or
  return snapshot-expired; never substitute a changed graph.
- [DataScript connection is reset] → Causal-anchor membership detects
  pre-token/divergent state; exact handles still expire when their registry
  evicts the value.
- [Shared cache is malicious or corrupt] → Authenticate entries and embed the
  complete key; provider failure becomes a miss.
- [Cryptographic collision/key compromise] → Use standard 256-bit primitives,
  domain-separated derived keys, rotation, strict key handling, and treat key
  compromise as scope compromise.
- [Object codec or adapter reads mutable external state] → Include an explicit
  deterministic configuration and identity-boundary dependency proof, or
  declare identities immutable; otherwise disable caching, causal read tokens,
  and proof-equivalent continuation.
- [Clock skew affects expiry] → Expiry can reject early, but a token is never
  accepted without its causal anchor. Retention cleanup uses a conservative
  grace interval.

## Migration Plan

1. Introduce adapter version 3, causal-dominance operations, shared
   token/cursor/cache cryptography, and typed errors.
2. Install graph-head metadata, append-only mutation journal, schema mutation
   id, and per-relation mutation-id attributes. Initialize them atomically with
   a migration mutation.
3. Update every managed schema/relationship/delete helper to generate one
   mutation id and publish journal, head, and dependency identities in the same
   transaction.
4. Default existing/custom integrations to full-content proof or no-cache.
   Enable fast mutation proofs only after their writer authority is explicitly
   configured and contract-tested.
5. Replace DataScript/Datahike listener tokens and old Datomic token payloads
   with v3 causal tokens minted from committed `db-after`.
6. Add Datomic zero-argument sync for fully consistent reads, Datahike
   authoritative-head capability detection, and DataScript connection-lineage
   selection.
7. Deploy graph-equivalent encrypted cursors. Reject old unauthenticated
   portable cursor and decimal token formats.
8. Run the generated reference-model suite, backend conformance suite, nREPL
   regression suite, and fault-injection cases before enabling fast proofs.

No cache/token downgrade or dual-format mode is provided. Version-3 tokens,
cursors, and completed-answer entries are the only supported secure formats.

## Open Questions

- What finite default token lifetime balances durable causal workflows against
  mutation-journal retention?
- Which Datahike writer/replication configurations can expose a verified
  authoritative-head barrier, beyond direct shared-store branch reads?
- Should the optional DataScript exact-snapshot registry be client-local or an
  injectable bounded service?
- Is cache-entry authentication mandatory for every provider or only providers
  not explicitly declared process-trusted? The safest default is mandatory.
