# `eacl-datahike`

Datahike adapter for EACL v8.

The reviewed v7 port from PR #81 supplies the Datahike storage primitives.
EACL v8 routes permission compilation, direct/arrow traversal, recursive
fixed-point evaluation, Relay pagination, counts, cache validation, and common
errors through the same backend-neutral engine used by Datomic and DataScript.

Responsibilities:

- Datahike schema installation and relationship transactions
- current immutable-snapshot selection and object/reference conversion
- ordered adjacency in both keyword and numeric `:attribute-refs?` modes
- proof-equivalent authenticated Relay cursors with exact fallback
- database-visible mutation identities plus schema/relation proofs
- `delete-object!` relationship cleanup

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
