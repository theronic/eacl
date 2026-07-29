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

Public live lookup cache reads occur under the coordinator's read side. Every relationship helper
holds the write side around its Datomic transaction and advances the epochs named by actual changed
tuple datoms. This removes the commit-to-invalidation race: no cache read can observe a new
relationship database value under the old dependency token.

The default coordinator is process-local and shared by all EACL clients for the same captured
Datomic database identity. It covers the expected same-Peer deployment without inspecting the
Datomic transaction log or checking schema/relationships on every new `db` value.

A multi-process deployment may provide a coordinator whose barrier and dependency epochs are shared by
all EACL writers and readers. Without such a coordinator, live cross-request result caching is
disabled; basis-pinned cursor continuations remain safe and useful locally.

This distributed restriction is fundamental: separate processes cannot invalidate one another's
ephemeral memory without a communication channel. The design chooses explicit shared coordination
or no live memoization rather than probabilistic TTL correctness.

### One bounded cache store with typed namespaces

The cache store has typed key namespaces under one bounded capacity budget and metric set:

- `:recursive-continuation` maps a cursor continuation identity to opaque traversal state;
- `:lookup-page` maps an exact logical EACL generation and internal page request to an internal page;
- `:cursor-page` may retain completed internal pages for retry/backward-navigation hits.

The same client store and capacity controller serve both recursive and non-recursive lookups,
avoiding independent uncoordinated caches. The existing schema-plan generation remains in the
client schema state because its lifecycle and size are different.

The built-in store is a synchronized weighted LRU with TTL, maximum total weight, maximum entry
weight, and entry-count protection. A custom store can implement the public cache-store protocol;
separate in-memory Datomic or shared-store adapters do not require attributes in the consumer
database.

Cache keys include database identity, schema generation, the mutation epochs of the permission's
relation-definition dependencies where applicable, operation, traversal engine version, resolved
internal anchor EIDs, permission/type filters, direction, page size, and internal cursor edge.
Cached values contain internal EIDs; caller-specific ID coercion still runs against the selected
database value.

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

The lookup and traversal implementations remain complete without a cache. Cache access wraps,
rather than replaces, the existing computation:

1. validate and resolve the request;
2. attempt a cache lookup when the current coordinator permits it;
3. compute through the existing indexed/traversal engine on a miss;
4. publish only after the generation/barrier is revalidated;
5. return the computed result even if cache publication fails.

Cache provider exceptions disable that cache operation and are never interpreted as authorization
answers.

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
- **A continuation retains significant traversal state** → Enforce traversal ceilings, maximum
  entry weight, total weight, TTL, and admission rejection; never remove the replay fallback.
- **Cache bookkeeping slows already-fast acyclic lookups** → Benchmark hit and miss paths against
  v7.3 and keep live page caching configurable.
- **Concurrent requests race on one cursor** → Store immutable continuation snapshots and use
  compute-once publication; duplicate computation is acceptable, divergent answers are not.
- **A cache provider returns corrupt or mismatched data** → Validate namespace, database identity,
  schema/relationship generation, engine version, query shape, basis, and cursor boundary before
  use; discard on any mismatch.
- **Old dependency-token entries accumulate after writes** → Weighted LRU and TTL reclaim them;
  exact-basis entries remain temporarily useful for historical cursors.

## Migration Plan

1. Ship without new Datomic schema.
2. Default to the bounded local continuation cache; allow `:cache false`.
3. Enable live non-recursive page caching only for a coherent coordinator scope.
4. Retain existing page-token replay so deployment rollback or cache removal requires no data
   migration.
5. Remove the persisted effective-grant plan from the remediation recommendation.

## Open Questions

None required for the initial local implementation. Shared coordinator and portable cache adapters
can be added independently behind the same protocols.
