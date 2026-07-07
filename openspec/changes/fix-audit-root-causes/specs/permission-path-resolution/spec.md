# permission-path-resolution

Resolving schema edges (relations and permissions) for permission evaluation: correct for all legal keyword type names, and cache-fresh for every schema mutation, on every peer, for every db view — with no writer-side contract. Covers `relation-datoms`, `calc-permission-paths`, and the path/plan caches in `eacl.datomic.impl.indexed`.

## ADDED Requirements

### Requirement: Relation lookup supports all legal keyword type names
`relation-datoms` (and everything built on it: `calc-permission-paths`, `find-relation-def`, `resolve-self-relation`, the recursive query planner) SHALL return the relation entities for a `(resource-type, relation-name)` pair regardless of how the subject-type keyword collates — including uppercase-initial, `z`-prefixed, and namespaced keywords. Permission evaluation SHALL NOT silently ignore relations because of their subject-type name.

#### Scenario: Subject type sorting after :z
- **WHEN** the schema defines `(Relation :zone :owner :zebra)` with `(Permission :zone :admin {:relation :owner})`, and a `:zebra` subject has an `:owner` relationship to a `:zone` resource
- **THEN** `can?` returns `true` and `lookup-resources` returns the zone

#### Scenario: Uppercase and namespaced subject types
- **WHEN** relations exist with subject types `:Admin` and `:my.app/user`
- **THEN** `relation-datoms` returns their datoms and permission paths include them

#### Scenario: Prefix scan does not leak into other relations or attributes
- **WHEN** relations exist for both `(:zone :owner)` and `(:zone :ownerx)` and other indexed attributes follow the relation tuple index
- **THEN** `relation-datoms` for `(:zone :owner)` returns only exact `(:zone :owner *)` datoms

### Requirement: Cache keys are derived from the schema history of the queried db value
The permission-path and recursive-query-plan cache keys SHALL be derived from the db value being queried: the database id plus a content digest of the visible history of the relation and permission composite tuple attributes, filtered to the db's as-of point when present (`d/as-of-t`, not `d/basis-t`, which returns the underlying basis for as-of views). Any schema mutation — `write-schema!`, a programmatic transaction, entity retraction, or excision — SHALL change the digest and therefore the key. Correctness SHALL NOT require any writer-side signal, helper call, or eviction, on any peer.

#### Scenario: Programmatic schema change is picked up with no signal
- **WHEN** a Permission entity is retracted via plain `d/transact` (no helper, no eviction, no write-schema!) and `get-permission-paths` / `can?` are called on a db basis that includes the retraction
- **THEN** the revoked permission no longer grants access

#### Scenario: write-schema! invalidates on every peer
- **WHEN** peer A performs `write-schema!` removing a permission, and peer B (which has cached paths for it) queries a db basis that includes A's transaction
- **THEN** peer B's `can?` reflects the removal without any process-local eviction on B

#### Scenario: as-of views resolve historical paths
- **WHEN** a permission existed at basis T1 and was removed at T2
- **THEN** `get-permission-paths` against `(d/as-of db T1)` returns the path, and against the current db returns none — even when both are queried from the same process after caching

#### Scenario: Speculative dbs cannot poison the shared cache
- **WHEN** paths are computed against `(:db-after (d/with db tx))` where `tx` speculatively alters schema entities, and a real transaction later lands at the same `t`
- **THEN** queries against the real db value never receive paths computed from the speculative view

#### Scenario: Unchanged schema keeps hitting the cache
- **WHEN** relationship (non-schema) writes occur between queries
- **THEN** path lookups for the same `(resource-type, permission)` are served from cache without recomputation (observable via a `calc-permission-paths` call counter)

### Requirement: Only positively classified db views share the cache
The digest SHALL be computed only for db values positively classified as plain or as-of views (`d/as-of-t`, `d/since-t`, `d/is-filtered`, `d/is-history` predicates). Any other view — `d/filter`, `d/since`, history dbs, or unrecognized types — SHALL receive a unique cache key, computing paths fresh from that view and never sharing entries with other views. In particular, a `d/filter` db that hides schema datoms SHALL NOT publish its paths under the plain db's key: filter predicates are arbitrary functions (possibly impure or time-dependent), so digests of filtered views are not trustworthy as shared keys.

#### Scenario: Filtered db cannot poison the plain db's cache
- **WHEN** paths are computed against a `d/filter` db whose predicate hides some permission entities, and the plain db is queried afterwards
- **THEN** the plain db's paths include the hidden permissions (computed fresh or from its own entry — never from the filtered view's computation)

### Requirement: Cache-key derivation failures degrade to misses, never staleness
If computing the schema digest fails for any reason (unsupported db view type, internal API change), the cache key SHALL be unique for that lookup, forcing a recomputation from the queried db value. No failure mode SHALL serve a previously cached entry for a db whose schema state cannot be established.

#### Scenario: Digest failure forces recomputation
- **WHEN** the digest computation throws for a given db value
- **THEN** paths are computed directly from that db value and the result is correct for it (at worst uncached)

### Requirement: Manual eviction remains available
`evict-permission-paths-cache!` SHALL remain public and SHALL clear both the permission-path and query-plan caches; `write-schema!` SHALL continue to invoke it (immediate local effect, though no longer required for correctness).

#### Scenario: Manual eviction clears both caches
- **WHEN** `evict-permission-paths-cache!` is called
- **THEN** subsequent path and plan lookups recompute (observable via call counters)
