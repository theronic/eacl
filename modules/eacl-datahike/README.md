# `eacl-datahike`

Datahike adapter for EACL.

EACL routes sealed-plan compilation, the stable-discovery reducer (point
checks, forward/reverse enumeration, counts), Relay pagination, cache
validation, and common errors through the same backend-neutral engine used by
Datomic and DataScript.

Responsibilities:

- Datahike schema installation and Datomic-compatible relationship transactions
- current immutable-snapshot selection and object/reference conversion
- ordered adjacency in both keyword and numeric `:attribute-refs?` modes
- proof-equivalent authenticated Relay cursors with exact fallback
- database-visible native schema/relation generations
- `delete-object!` relationship cleanup

Exact immutable-snapshot lookup runs first. Complete ordered-generation proof
reuse across unrelated forward transactions is automatic; unavailable proof
falls back to exact evaluation.
Public prospective testing uses `eacl/tx-relationship` plus `eacl/with`, or
`eacl/with-schema` for permission-schema replacement. Caller-owned Datahike
database values have no public EACL wrapper. EACL-created speculative snapshots
are immutable, publication-free readers; complete committed proofs may be read
only for disjoint dependencies. `:orphan-policy :retain-inert` is
speculative-only and reports bounded presence diagnostics without tuple counts.
Internal raw-database injection forfeits coherence guarantees.
All authorization-relevant mutations must use EACL APIs or EACL-produced
transaction data/functions transacted intact. After unsupported raw mutation,
quiesce callers, repair the data, and call
`eacl.datahike.core/expire-cache!` on every affected client in every process.
Expiry never repairs ghost tuples. Custom identity codecs are exact-only and
client-local unless configured with a portable `:adapter-fingerprint`,
`:adapter-deterministic? true`, and a certified injective round trip.
Proof-equivalent cursors additionally require `:identity-immutable? true`;
otherwise they remain exact-basis-bound. The built-in `:eacl/id` codec assumes
IDs never change for an entity; configure `false` if reassignment is allowed.

Serverless hosts may persist completed authorization entries with
`export-cache-snapshot`, `restore-cache-snapshot!`, and
`cache-content-revision`. The host owns authentication and the encoded-byte
bound before decoding; EACL validates the trusted decoded snapshot and its
count bound. Snapshots exclude Datahike database values and process-local
identity. Restore validates before atomically replacing the visible cache.

### Released-v7 permission migration

EACL v8 rejects released-v7 flat permission rows at client construction. Run
the explicit, bounded maintenance migration before starting ordinary v8
clients:

```clojure
(require '[eacl.datahike.migrations.v7-to-v8 :as v7-to-v8])

(v7-to-v8/migrate! conn)
```

The stored `:eacl/schema-string` is authoritative by default. A maintenance
process may instead supply `{:schema schema-string :expression-limits {...}}`.
Datahike migration requires exact relation identities and exact permission
denotation: it is a storage conversion, not an authorization policy change.
It reads and rewrites only schema-definition rows, never enumerates or rewrites
relationship tuples, and commits the expression rows plus version stamp behind
the existing schema-write fence. Legacy flat entities remain inert to avoid
S3 persistent-index deletion amplification. On released-v7 rows, ordinary
construction fails with
`:eacl/permission-storage-version`; `{:auto-migrate-v7 true}` explicitly opts
one writer into the same migration. Prefer a single maintenance writer over
automatic migration when several processes can start concurrently.

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

### Optional atomic entity retraction

Datahike support is selected from the actual schema flexibility, attribute
representation, and writer topology:

```clojure
(require '[datahike.api :as d]
         '[eacl.datahike.safe-retraction :as safe-retraction])

(safe-retraction/support-descriptor (d/db conn))
;; :schema-flexibility :read  + in-process writer => :named
;; default :write            + in-process writer => :direct
;; function-unsafe remote writer                => :unsupported

;; Named :read mode:
(safe-retraction/install! conn)

;; Direct :write mode (no named function is installed):
(safe-retraction/prepare! conn)
(d/transact
 conn
 (safe-retraction/retract-entity-tx-data
  (d/db conn) [:eacl/id "account-1"]))
```

Named-mode `install!` installs `:eacl.fn/retractEntity` and verifies that the
IFn value round-trips. Calling `install!` in direct mode fails with a
structured `:installation-unavailable` error and points to the direct API.
Default strict `:schema-flexibility :write` is not changed; `prepare!` retains
it and the constructor emits an in-process `:db.fn/call`.
Both keyword and numeric `:attribute-refs?` representations are supported.
Function values are not transported to remote writers, which receive a
structured `:unsupported` descriptor/error and must use `delete-object!`.

Relationship `:create` uses the same in-process boundary: with the default
`{:writer {:backend :self}}` the client plans the write as a transaction
function (`eacl.datahike.impl/create-relationship-at-commit`) that re-checks
the relationship against the transaction-time database, so a racing
duplicate `:create` fails with `:eacl/relationship-conflict` instead of
committing a redundant datom. Datahike reports a failing transaction
function wrapped; `eacl.datahike.core/typed-transaction-error` recovers the
typed error and the client's write path surfaces it. A remote writer keeps
the plan-time existence check only.

The function computes the target's native component closure, retracts exact
peer halves discovered from both endpoint indexes, stamps each distinct
affected relation with `:db/current-tx`, and delegates ordinary entity removal
in the same commit. Multiple and repeated invocations compose in one
transaction. Do not combine relationship additions involving a target with
its retraction in the same application transaction. Reinstall named functions
after persistence restore unless the store is known to preserve IFn values.
Rollback callers before removing a named installation. A missing valid lookup
ref is a no-op; a known numeric retracted eid repairs peer-only ghosts through
relation-schema enumeration and exact index probes. Prefer batched
`delete-object!` for high-degree entities to avoid monopolizing the writer.

Datahike supports local `:minimize-latency`, native-revision causal at-least selection,
and exact reconstruction from retained commits or temporal history. It
advertises `:fully-consistent` only for a direct `:self` writer with an
authoritative branch-head barrier; lagging/replicated sources reject it.
Unsupported configuration/mode combinations fail before authorization. List
operations use `:first`/`:after` and `:last`/`:before`; see the
[backend guide](../../docs/v8-backend-modules-and-upgrade.md).

`expand-permission-tree` uses the shared portable shallow-expansion kernel in
both keyword and numeric attribute-reference modes. The returned
`:expanded-at` names the same selected immutable DB as the tree. Exact replay
is available only when the configured store retains the named commit/history;
native child/subject order is not semantic. Configure structural ceilings with
client-level `:permission-tree-limits`.

```clojure
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-SNAPSHOT"}}}
```

Its POM depends on `dev.eacl/eacl` at the exact same version, so consumers do
not declare core separately. EACL targets Java 26 by default; explicit
source/custom builds can target older Java, subject to Datahike's own runtime
requirements. Git and `:local/root` development must first follow the explicitly opt-in
[core source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers install no formal tools.

Build this module in isolation with `clojure -T:build jar`.
