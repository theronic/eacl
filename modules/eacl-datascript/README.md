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
DataScript defaults to `{:coherence-authority :unknown}`, so cache reuse is
limited to the exact current immutable DB value and remains sound when callers
write authorization-relevant datoms directly. Applications may explicitly opt
in to `{:coherence-authority :managed}` only when every EACL schema and
relationship mutation uses the EACL APIs. That writer contract permits
relation-stamp reuse across unrelated forward transactions.
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

### Optional atomic entity retraction

An embedded DataScript connection may explicitly install the safe transaction
function; it is never part of `datascript-schema`:

```clojure
(require '[datascript.core :as ds]
         '[eacl.datascript.safe-retraction :as safe-retraction])

(safe-retraction/install! conn)
(ds/transact!
 conn
 (safe-retraction/retract-entity-tx-data [:eacl/id "account-1"]))
```

The installed `:eacl.fn/retractEntity` reads the target's two endpoint
attributes from transaction-start state, retracts peer halves, advances v3
mutation/relation proofs, and applies ordinary `:db.fn/retractEntity` in one
transaction. `direct-retract-entity-tx-data` provides the equivalent
in-process `:db.fn/call` form after `prepare!`.

Arbitrary IFn values are not assumed to survive DataScript serialization or a
cross-runtime restore. Re-run `install!` after restore unless the chosen
serializer explicitly preserves them. Rollback is to stop emitting invocations
and return to `delete-object!`; the installed function is inert while unused.
Use one safe invocation per transaction, do not combine it with sibling
relationship additions, and use batched `delete-object!` for high-degree
targets. A missing target is a bounded no-op and does not repair an old ghost;
use the integrity report for detection.

The unreleased relationship-entity representation is not migrated or
dual-read. Recreate explorer/demo databases, or reload every relationship
through the EACL API, after upgrading to this v8 candidate.

```clojure
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-SNAPSHOT"}}}
```

Its POM depends on `dev.eacl/eacl` at the exact same version, so consumers do
not declare core separately. EACL targets Java 26 by default; explicit
source/custom builds can target older Java, subject to DataScript's own runtime
requirements. Build this module in isolation with `clojure -T:build jar`; Git and `:local/root`
development must first follow the explicitly opt-in
[core source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers install no formal tools.

Useful workspace test commands:

- `clj-nrepl-eval -p <port> "(do (require 'eacl.datascript.contract-test :reload-all) (clojure.test/run-tests 'eacl.datascript.contract-test))"`
- `clj-nrepl-eval -p <port> "(do (require '[cljs.main :as cljs] :reload) (cljs/-main \"-re\" \"node\" \"-m\" \"eacl.datascript.cljs-test-runner\"))"`
- `clj-nrepl-eval -p <port> "(do (require 'eacl.bench.datascript-relationship-storage :reload) (eacl.bench.datascript-relationship-storage/run-benchmark!))"`
