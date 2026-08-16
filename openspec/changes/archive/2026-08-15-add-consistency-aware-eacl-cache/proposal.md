## Why

Recursive lookup pagination currently repeats earlier traversal work and can degrade to
`O(N²/page-size)`, while repeated lookups, counts, and permission checks recompute answers whose
schema and relevant relationships have not changed. EACL needs one bounded, optional cache that
accelerates these operations without tying validity to every Datomic basis change or weakening
authorization correctness.

## What Changes

- Add one typed cache for recursive continuations, lookup pages, counts, and `can?` Boolean results.
- Make relationship-aware cache validity advance only at EACL relationship mutation boundaries,
  using Datomic transaction revisions for the relation definitions actually changed.
- Support `fully-consistent`, `minimize-latency`, `at-least-as-fresh`, and cache-resident
  `at-exact-snapshot` selection through versioned `:zed/token` revisions.
- Allow optional, bounded revision checkpoints so callers can construct an
  `at-least-as-fresh` token representing a snapshot observed at least N seconds ago, without making
  revision quantization part of normal cache invalidation.
- Cache only internal entity IDs and resolve/coerce external IDs at the API boundary; missing input
  entities return false or empty results without entering the result cache.
- Keep schema generation in cache keys and rotate it only through `write-schema!`; ordinary DB
  values do not trigger schema reads or cache invalidation.
- Keep caching optional and correct when disabled, add no permanent Datomic data, and permit custom
  memory, RocksDB, Apache Kvrocks, Redis, or other stores through the cache protocol without adding
  those dependencies to EACL core.
- Treat direct mutation of EACL-owned Datomic schema or relationship data as unsupported; cache
  coherence is guaranteed only for EACL mutation APIs participating in the configured coordinator.
- Preserve the existing connection-oriented public authorization API and DB-oriented internal
  evaluators; no alternate lookup APIs are introduced.

## Capabilities

### New Capabilities

- `consistency-aware-authorization-cache`: Defines the complete correctness, consistency,
  pagination, storage, invalidation, schema, token, and pluggable-backend contract for EACL caching.

### Modified Capabilities

None.

## Impact

- Affects `eacl.datomic.cache`, `eacl.datomic.core`, indexed traversal and pagination, consistency
  descriptors, `:zed/token` handling, cache configuration, tests, and performance benchmarks.
- Extends caching to `can?` while retaining the existing lookup and count APIs.
- Requires no additional Datomic attributes or persistent tuples.
- Keeps RocksDB, Apache Kvrocks, Redis client, and serialization libraries optional and outside the
  core dependency graph.
- Supersedes the narrower cache design with a single consistency-aware contract suitable for the
  v8.0 implementation while remaining eligible for a v7.3 patch release.
