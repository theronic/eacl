## Context

The current recursive list engine emits results incrementally but discards its traversal state at
the end of every request. A following page reconstructs the same historical Datomic value and
replays all earlier work, making complete enumeration `O(N²/page-size)`.

The earlier effective-grant plan replaces replay with two permanent indexed tuple datoms per
effective grant. That would duplicate subject/resource identifiers, amplify every grant through the
Datomic log and indexes, retain grant history, and require costly write-side maintenance.

EACL already owns the supported mutation boundaries: definitions change through `write-schema!`
and relationships change through `write-relationships!` and its helpers. Datomic `basis-t` changes
for unrelated application transactions and therefore cannot be a live cache invalidation key.

## Goals / Non-Goals

**Goals:**

- Make sequential recursive pagination linear in emitted/traversed work when continuation state is
  available.
- Preserve exact behavior through ordinal replay against the cursor's historical basis whenever a
  continuation is missing.
- Cache non-recursive lookup pages only in a coherence scope that observes every EACL relationship
  write.
- Keep cache storage bounded, ephemeral, configurable, and optional.
- Add no permanent Datomic schema or effective-grant data.
- Avoid invalidation or cache-key churn for unrelated Datomic transactions.

**Non-Goals:**

- Making arbitrary direct Datomic relationship tuple writes cache-coherent.
- Making low-level arbitrary `db`, `d/with`, filtered, or speculative values cacheable.
- Requiring a global cache for single-Peer deployments.
- Caching arbitrary domain entity attributes or caller ID-coercion results.

## Decisions

### Separate immutable cursor correctness from live lookup coherence

Every public page token remains authoritative and contains the operation, canonical internal query
shape, schema generation, historical `basis-t`, traversal engine version, ordinal, and result
boundary. An optional opaque continuation key is only a performance hint.

A continuation hit resumes traversal state created from that exact historical basis. A miss,
eviction, restart, disabled cache, or alternate Peer reconstructs `d/as-of` from `basis-t`, replays
to the ordinal, verifies the result boundary, and continues. Relationship writes after page one do
not invalidate the continuation because the cursor intentionally observes its original immutable
basis.

This keeps cache availability out of the correctness proof.

### Coordinate live caches at EACL's relationship write interaction points

A cache coordinator owns:

- a relationship mutation clock and per-relation-definition epochs;
- a read/write mutation barrier.

Each client owns its configured bounded cache store. Clients may instead receive the same custom
store when sharing entries is desirable; correctness does not require the stores themselves to be
shared.

Public live result snapshots are captured under the coordinator's read side. Every relationship
helper holds the write side around its Datomic transaction and advances the epochs named by actual
changed tuple datoms. This removes the commit-to-invalidation race: no snapshot can pair a new
relationship database value with the old dependency token.

The local coordinator is an ordinary value created by `eacl.datomic.cache/local-context` and passed
to every client in its coherence scope. There is no process-global database-to-coordinator
registry. This makes ownership and lifecycle explicit while requiring no Datomic transaction-log
or per-`db` schema scan.

A multi-process deployment may provide a coordinator whose barrier and dependency epochs are shared
by all EACL writers and readers. Configuring live caching without an explicit coordinator is an
error. Without such a coordinator, live cross-request result caching is disabled; basis-pinned
cursor continuations remain safe and useful locally.

This distributed restriction is fundamental: separate processes cannot invalidate one another's
ephemeral memory without a communication channel. The design chooses explicit shared coordination
or no live memoization rather than probabilistic TTL correctness.

### One bounded cache store with typed namespaces

The cache store has typed key namespaces under one bounded capacity budget and metric set:

- `:recursive-continuation` maps a cursor continuation identity to opaque traversal state;
- `:result` maps an exact logical EACL generation and internal lookup/count request to its result;
- `:cursor-page` may retain completed internal pages for retry/backward-navigation hits.

The same client store and capacity controller serve recursive/acyclic lookups and counts, avoiding
independent uncoordinated caches. The existing schema-plan generation remains in the client schema
state because its lifecycle and size are different.

The built-in store is a synchronized weighted LRU with TTL, maximum estimated total weight,
maximum estimated entry weight, and an exact entry-count bound. Weight is an admission heuristic,
not a measured JVM byte count or heap guarantee. Admission includes the retained cache-key shape so
large caller identifiers cannot bypass the bound by living only in keys. Recursive traversal
counters and queue limits are the hard work/cardinality ceilings. A custom store can implement the
public cache-store protocol; separate in-memory Datomic or shared-store adapters do not require
attributes in the consumer database.

Cache keys include database identity, schema generation, the mutation epochs of the permission's
relation-definition dependencies where applicable, operation, traversal engine version, resolved
internal anchor EIDs, permission/type filters, direction, page size, and internal cursor edge.
Cached lookup values contain internal EIDs; caller-specific ID coercion still runs against the
selected database value. Count values contain only the validated count response.

Every stored value is wrapped with a cache-entry version, kind, and full key. A mismatched wrapper
is a miss. Cache implementations remain trusted infrastructure for the bytes stored under a valid
key; EACL cannot prove an authorization answer from arbitrary maliciously forged cache contents
without recomputing it.

Result keys retain the canonical resolved request rather than relying only on its compact token
hash. Entry validation also checks count limits and page/cursor invariants. If a supported
relationship helper throws before it can report whether its transaction committed, the local
coordinator advances an uncertainty epoch included by every result key, conservatively
invalidating all older live results.

Recursive continuation state contains process-local lazy index streams and functions. It is
therefore accepted only when it retains the client's unforgeable identity token. Serialization or
cross-process transfer turns it into a safe miss. Portable stores can still share completed pages
and counts; a future portable recursive frontier requires a separate serializable state format.

### Cache relationship dependency epochs, not Datomic bases

Unrelated Datomic transactions do not acquire the EACL mutation barrier and do not change the
relationship mutation clock. Live page entries therefore remain reusable as the connection
advances.

Relationship helpers derive changed relation-definition eids from forward/reverse v7 tuple datoms
already present in the transaction report. Only those dependency epochs advance. Touching an
already-complete relationship, deleting a missing relationship, or changing a relation that the
cached permission cannot traverse does not invalidate that result.

Schema writes replace the client's schema generation as today. Schema generation is part of all
cache keys, so old entries become unreachable and expire naturally.

### Preserve an uncached execution path

The lookup, count, and traversal implementations remain complete without a cache. Cache access wraps,
rather than replaces, the existing computation:

1. validate and resolve the request;
2. attempt a cache lookup when the current coordinator permits it;
3. compute through the existing indexed/traversal engine on a miss;
4. publish under the captured immutable dependency-token key;
5. return the computed result even if cache publication fails.

Cache provider exceptions disable that cache operation and are never interpreted as authorization
answers.

### Capture live snapshots without holding writers behind queries

For live results, the coordinator read barrier covers only:

1. capture `(d/db conn)`;
2. resolve the permission's memoised relation dependencies;
3. capture their dependency-epoch token.

The barrier is released before cache I/O or query computation. A concurrent relationship write
advances the relevant token, so the old result can only be stored/read under its old key. Returning
the captured database result is linearizable at snapshot capture and does not block a writer for
the duration of a large count.

### Cache before choosing the traversal engine

Completed lookup pages and count responses have the same correctness key regardless of how they
were computed. The public client checks this uniform result cache before engine classification. On
a miss, the indexed engine alone calls `traversal-permission?` once to choose between its acyclic
seek engine and recursive closure engine. The distinction remains necessary for execution order,
cursor format, cycle handling, and bare reverse-page behavior; it is not a cache partition.

### Bound retained traversal state

Recursive continuations are admitted according to both the traversal safety counters and cache
weight limits. Oversized continuations are not cached. The entry TTL cannot exceed the page-token
TTL. Eviction is safe because replay remains available.

The initial implementation stores continuation state only in the local opaque-value store. Portable
stores may cache completed pages immediately; a portable continuation adapter must preserve the
engine version and validate the full continuation metadata before use.

## Risks / Trade-offs

- **A remote writer does not share the coordinator** → Live result memoization is disabled for that
  deployment; cursor continuations and uncached lookups remain correct.
- **A continuation retains significant traversal state** → Enforce traversal ceilings, estimated
  entry/total weight, exact entry count, TTL, and admission rejection; never describe the estimate
  as a heap guarantee and never remove replay fallback.
- **Cache bookkeeping slows already-fast acyclic lookups** → Benchmark hit and miss paths against
  v7.3 and keep live page caching configurable.
- **Concurrent requests race on one cursor** → Continuation state is persistent/immutable from the
  caller's perspective; duplicate computation/publication is acceptable, divergent answers are not.
- **A cache provider returns mismatched data** → Validate entry version, embedded full key, kind,
  database identity, schema/dependency token, query shape, basis, and cursor boundary before use;
  discard on any mismatch. A deliberately malicious provider is outside the trusted cache boundary.
- **Old dependency-token entries accumulate after writes** → Weighted LRU and TTL reclaim them;
  exact-basis entries remain temporarily useful for historical cursors.

## Migration Plan

1. Ship without new Datomic schema.
2. Default to the bounded local continuation/completed-cursor cache; allow `:cache false`.
3. Enable live lookup/count caching only with an explicitly supplied coherent coordinator.
4. Retain existing page-token replay so deployment rollback or cache removal requires no data
   migration.
5. Remove the persisted effective-grant plan from the remediation recommendation.

## Open Questions

None required for the initial local implementation. Shared coordinator and portable cache adapters
can be added independently behind the same protocols.
