# `eacl-datascript`

DataScript adapter for EACL.

This module implements the EACL v8 public contract in Clojure and
ClojureScript. Permission compilation, recursive fixed-point traversal,
direction-scoped frontiers, Relay windowing, counts, cache validation, and
common errors live in `eacl`; this adapter contains DataScript access and
transaction mechanics.

Responsibilities:

- DataScript schema installation and canonical schema storage
- DataScript SPI implementation for CLJ and CLJS
- two-datom endpoint-pair relationship storage and guarded EAVT/AVET traversal
- explicit offline dangling-half detection in `eacl.datascript.integrity`
- current immutable-snapshot selection and object/reference conversion
- proof-equivalent authenticated Relay cursors on the selected current DB
- database-visible mutation identities plus schema/relation content proofs
- portable authenticated completed-answer caching
- DataScript contract tests and adapter-specific edge cases

DataScript supports serialized connection-head `:fully-consistent`, local
`:minimize-latency`, and managed causal at-least selection. It intentionally
does not advertise `:at-exact-snapshot` or retain historical DB values;
`:exact-snapshot-registry-size` is removed and rejected. Unsupported exact
requests fail before cache access or authorization traversal. DataScript does
not claim an external replication mechanism.
DataScript clients assume by default that every EACL schema and relationship
mutation uses the client APIs, selecting managed mutation proofs and
relation-stamp reuse. Applications that write authorization-relevant datoms
directly must opt out with `{:coherence-authority :unknown}`; domain-object
transactions that do not alter EACL schema or relationships do not require an
opt-out.
The v7 `:limit`/`:cursor` API is replaced by v8 `:first`/`:after` and
`:last`/`:before`; see the
[upgrade guide](../../docs/v8-backend-modules-and-upgrade.md).

## Relationship storage

DataScript stores a relationship as two cardinality-many indexed ordinary
vector values, with the same logical order as the Datomic Pro and Datahike
heterogeneous tuples:

```clojure
[subject-eid
 :eacl.v7.relationship/subject-type+relation+resource-type+resource
 [subject-type relation-eid resource-type resource-eid]]

[resource-eid
 :eacl.v7.relationship/resource-type+relation+subject-type+subject
 [resource-type relation-eid subject-type subject-eid]]
```

The peer eid inside an ordinary vector is a value, not a DataScript ref.
Consumers must remove relationships through EACL before retracting an endpoint
entity. `delete-object!` removes both halves but retains the endpoint entity;
`eacl.datascript.integrity/dangling-relationship-report` detects peer halves
left by direct `:db/retractEntity`.

The unreleased relationship-entity representation is not migrated or
dual-read. Recreate explorer/demo databases, or reload every relationship
through the EACL API, after upgrading to this v8 candidate.

Useful workspace test commands:

- `clj-nrepl-eval -p <port> "(do (require 'eacl.datascript.contract-test :reload-all) (clojure.test/run-tests 'eacl.datascript.contract-test))"`
- `clj-nrepl-eval -p <port> "(do (require '[cljs.main :as cljs] :reload) (cljs/-main \"-re\" \"node\" \"-m\" \"eacl.datascript.cljs-test-runner\"))"`
- `clj-nrepl-eval -p <port> "(do (require 'eacl.bench.datascript-relationship-storage :reload) (eacl.bench.datascript-relationship-storage/run-benchmark!))"`
