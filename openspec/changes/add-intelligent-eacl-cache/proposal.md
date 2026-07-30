## Why

Recursive pagination currently replays its traversal prefix for every page, making a complete
enumeration `O(N²/page-size)`. Persisting effective-grant tuples would avoid replay but would impose
permanent Datomic schema, storage, history, indexing, and write-amplification costs on every EACL
consumer.

EACL owns the supported schema and relationship write paths, so cache validity can follow EACL
schema generations and relation-dependency mutation epochs instead of expiring on every unrelated
Datomic transaction.

## What Changes

- Add an optional, bounded cache owned by each EACL client and configurable through `make-client`.
- Make live-cache coordination an explicit argument shared by clients in one coherence scope;
  do not discover coordinators through process-global mutable state.
- Track dependency-scoped relationship epochs that advance only for relation definitions changed
  by EACL relationship write helpers.
- Coordinate cache reads and relationship writes at those owned interaction points. Clients in one
  JVM share the coordinator; multi-process deployments may supply a shared coordinator.
- Resume recursive pagination from cached traversal continuations while retaining ordinal replay
  against the cursor's exact historical basis as the authoritative cache-miss fallback.
- Cache completed `lookup-resources`, `lookup-subjects`, `count-resources`, and `count-subjects`
  results uniformly by schema generation, relationship dependency epochs, and resolved query.
- Keep recursive/non-recursive classification solely inside the indexed engine where it selects the
  execution algorithm; cache hits do not classify.
- Keep all public lookup APIs unchanged and preserve fully correct behavior when caching is disabled,
  evicted, unavailable, or too small for a traversal.
- Add no effective-grant tuples, cache marker datoms, or other permanent Datomic storage.

## Capabilities

### New Capabilities

- `intelligent-authorization-cache`: Correct invalidation, bounded storage, recursive continuation,
  non-recursive lookup caching, cache-miss replay, and configurable cache operation.

### Modified Capabilities

None.

## Impact

- Affected implementation: Datomic client lifecycle, relationship write wrappers, pagination token
  metadata, recursive traversal, and public lookup/count execution.
- Public APIs remain source-compatible; `make-client` gains optional cache configuration.
- The default continuation cache is ephemeral and Peer-local. Live result caching is used only
  within a coherent coordinator scope; no shared or global cache is required for correctness.
- No Datomic schema attributes or permanent consumer storage are added.
