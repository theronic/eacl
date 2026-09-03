# `eacl-datascript`

DataScript adapter for EACL.

This module implements the EACL public contract in Clojure and
ClojureScript. Sealed-plan compilation, the stable-discovery reducer, Relay
windowing, counts, cache proof validation, and common errors live in `eacl`;
this adapter contains DataScript access and transaction mechanics.

Responsibilities:

- DataScript schema installation and canonical schema storage
- DataScript SPI implementation for CLJ and CLJS
- two-datom endpoint-pair relationship storage and guarded EAVT/AVET traversal
- explicit offline dangling-half detection in `eacl.datascript.integrity`
- current immutable-snapshot selection and object/reference conversion
- proof-equivalent authenticated Relay cursors on the selected current DB
- database-visible, globally ordered native schema/relation generations
- client-private exact-first and automatic proof-backed caching
- DataScript contract tests and adapter-specific edge cases

Public prospective testing uses `eacl/tx-relationship` plus `eacl/with`, or
`eacl/with-schema` for permission-schema replacement. Caller-owned DataScript
database values have no public EACL wrapper. EACL-created speculative snapshots
are immutable, publication-free readers; complete committed proofs may be read
only for disjoint dependencies. `:orphan-policy :retain-inert` is
speculative-only and uses bounded presence diagnostics. Internal raw-database
injection forfeits coherence guarantees.

Missing, malformed, oversized, or exceptional ordered-generation proof data
falls back to exact immutable-snapshot evaluation. After unsupported raw authorization mutation, quiesce
callers, repair the data, and call
`eacl.datascript.core/expire-cache!` on every affected client in every process.
Expiry never repairs ghost vectors. Custom identity codecs are exact-only and
client-local unless configured with a portable `:adapter-fingerprint`,
`:adapter-deterministic? true`, and a certified injective round trip.
Proof-equivalent cursors additionally require `:identity-immutable? true`;
otherwise they remain exact-basis-bound. The built-in `:eacl/id` codec assumes
IDs never change for an entity; configure `false` if reassignment is allowed.

DataScript supports serialized connection-head `:fully-consistent`, local
`:minimize-latency`, and connection-lifecycle causal at-least selection. It intentionally
does not advertise `:at-exact-snapshot` or retain historical DB values;
Unsupported exact requests fail before cache access or authorization
traversal. DataScript does not claim an external replication mechanism.
Exact lookup runs first and automatic proof-backed reuse may survive unrelated
transactions. All authorization-relevant mutations must use EACL APIs or
documented transaction data/functions; raw mutation can leave stale cache
state and requires repair plus expiry of every affected client.
Native-revision tokens are independent of completed-answer caching and are scoped to
the originating client/connection lifecycle.
List operations use `:first`/`:after` and `:last`/`:before`; see the
[backend guide](../../docs/v8-backend-modules-and-upgrade.md).

Serverless hosts may persist completed authorization entries with
`export-cache-snapshot`, `restore-cache-snapshot!`, and
`cache-content-revision`. The host owns authentication and the encoded-byte
bound before decoding. EACL validates the trusted decoded snapshot and its
count bound before atomically replacing the visible cache; snapshots exclude
DataScript database values and process-local identity.

`expand-permission-tree` uses the shared portable shallow-expansion kernel and
returns a token for the same selected immutable DataScript DB as the tree.
DataScript CLJ and CLJS have identical topology/error contracts. Native scan
order is not semantic, and exact historical replay remains unsupported; use a
returned token as a causal floor within its connection lifecycle. Configure
structural ceilings with client-level `:permission-tree-limits`.

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

Relationship `:create` is decided inside the transaction: the client plans it
as a `:db.fn/call` of `eacl.datascript.impl/create-relationship-at-commit`,
which re-checks the relationship against the transaction-time database, so a
racing duplicate `:create` fails with `:eacl/relationship-conflict` instead
of committing a redundant datom (CLJ and CLJS alike). `:touch` stays
idempotent.

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

The installed `:eacl.fn/retractEntity` computes the target's native component
closure, retracts peer halves discovered from the two endpoint attributes,
stamps each distinct affected relation with `:db/current-tx`, and applies
ordinary `:db.fn/retractEntity` in one transaction.
`direct-retract-entity-tx-data` provides the equivalent in-process
`:db.fn/call` form after `prepare!`.

Arbitrary IFn values are not assumed to survive DataScript serialization or a
cross-runtime restore. Re-run `install!` after restore unless the chosen
serializer explicitly preserves them. Rollback is to stop emitting invocations
and return to `delete-object!`; the installed function is inert while unused.
Multiple and repeated safe invocations compose in one transaction. Do not
combine relationship additions involving a target with its retraction in the
same application transaction. Use batched `delete-object!` for high-degree
targets. A missing valid lookup ref is a no-op; a known numeric retracted eid
repairs old peer-only ghosts by enumerating relation definitions and making
exact index probes. Use the integrity report when the old eid is unknown.

```clojure
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-SNAPSHOT"}}}
```

Its POM depends on `dev.eacl/eacl` at the exact same version, so consumers do
not declare core separately. EACL targets Java 25 by default; explicit
source/custom builds can target older Java, subject to DataScript's own runtime
requirements. Build this module in isolation with `clojure -T:build jar`; Git and `:local/root`
development must first follow the explicitly opt-in
[core source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers install no formal tools.
