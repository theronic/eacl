# permission-path-resolution Specification

## Purpose
TBD - created by archiving change fix-audit-root-causes. Update Purpose after archive.
## Requirements
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

