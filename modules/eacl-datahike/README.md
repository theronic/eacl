# `eacl-datahike`

Datahike adapter for EACL v8.

The reviewed v7 port from PR #81 supplies the Datahike storage primitives.
EACL v8 routes permission compilation, direct/arrow traversal, recursive
fixed-point evaluation, Relay pagination, counts, cache validation, and common
errors through the same backend-neutral engine used by Datomic and DataScript.

Responsibilities:

- Datahike schema installation and Datomic-compatible relationship transactions
- current immutable-snapshot selection and object/reference conversion
- ordered adjacency in both keyword and numeric `:attribute-refs?` modes
- proof-equivalent authenticated Relay cursors with exact fallback
- database-visible mutation identities plus schema/relation proofs
- `delete-object!` relationship cleanup

Relationships use the same physical layout as EACL's Datomic Pro adapter. One
logical relationship is two cardinality-many heterogeneous tuple datoms:

```clojure
[subject-eid :eacl.v7.relationship/subject-type+relation+resource-type+resource
 [subject-type relation-eid resource-type resource-eid]]

[resource-eid :eacl.v7.relationship/resource-type+relation+subject-type+subject
 [resource-type relation-eid subject-type subject-eid]]
```

This avoids a relationship entity and five derived composite indexes. As with
Datomic, retracting an endpoint directly can leave its peer tuple behind.
Consumers must remove relationships through EACL before retracting a
permissioned entity. `eacl.datahike.integrity/dangling-relationship-report`
provides an explicit offline audit for violations of that contract.

This replaces the unreleased v8 Datahike relationship-entity layout. Recreate
pre-release Datahike databases rather than carrying both physical models; no
rollback or dual-read migration is included.

Datahike supports local `:minimize-latency`, managed causal at-least selection,
and exact reconstruction from retained commits or temporal history. It
advertises `:fully-consistent` only for a direct `:self` writer with an
authoritative branch-head barrier; lagging/replicated sources reject it.
Unsupported configuration/mode combinations fail before authorization.
The v7 `:limit`/`:cursor` API is replaced by v8 `:first`/`:after` and
`:last`/`:before`; see the
[upgrade guide](../../docs/v8-backend-modules-and-upgrade.md).

Run its tests through a module-local nREPL and build it from this directory:

```shell
clojure -M:test:nrepl
clojure -T:build jar
```
