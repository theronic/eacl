# Migrating Datomic or Datahike permissions from v7 to v8

EACL v8 reuses the released v7 relationship storage model. The forward and
reverse tuple attributes, relation entity identities, and relationship datoms
are not rebuilt. The migration changes only permission definitions and the
schema/version singleton.

New Datomic databases install `eacl.datomic.schema/v8-schema`. The legacy
`v7-schema` name remains a compatibility installer for existing code, but it
also installs the retired flat-permission attribute definitions. Datomic does
not remove installed attribute entities; after an upgrade those legacy
attributes remain inert history and no permission row asserts them.

Stop authorization schema writers, take the normal Datomic backup required by
your deployment, and run the explicit permission migration:

```clojure
(require '[eacl.migrations.v7-to-v8 :as v7-to-v8])

(v7-to-v8/migrate!
 conn
 {:schema released-v8-schema-string
  :expression-limits
  {:maximum-source-nodes 32768
   :maximum-source-depth 64}})
```

For Datahike, back up the store using the backend's supported mechanism, then
use the Datahike migration namespace. It uses the stored schema source when
`:schema` is omitted:

```clojure
(require '[eacl.datahike.migrations.v7-to-v8 :as v7-to-v8])

(v7-to-v8/migrate!
 conn
 {:schema released-v8-schema-string
  :expression-limits
  {:maximum-source-nodes 32768
   :maximum-source-depth 64}})
```

For Datomic, the schema string is optional only when the released v7 permission
rows can be converted unambiguously. Supplying the complete intended v8 schema
is the preferred upgrade path. Relation definitions must retain their released
v7 identities because existing relationship tuples contain those relation
entity IDs. Datomic permission definitions may change as part of the supplied
schema.

Datahike is intentionally stricter: a supplied schema must retain exact
relation identities and exact flat permission denotation. Change policy in a
subsequent ordinary v8 schema transaction, after the storage migration has
completed. Released-v7 Datahike stores created by EACL normally contain the
authoritative `:eacl/schema-string`, so the no-options form is sufficient.

Both migrations first read and validate the bounded definition rows, validate
the released v7 permission representation, parse and resolve the candidate,
enforce expression and stratification limits, check authoritative attribute
shapes, and reject relation additions or retractions. They then install missing
additive authoritative permission attributes. One final transaction retracts
the flat permission entities, writes canonical expression entities, and
updates the schema text and generation. Datomic additionally stamps
`:eacl/permission-storage-version` as `8`; Datahike's strict row-shape gate is
the storage-version authority.

`:expression-limits` is optional client/maintenance-process configuration. It
is merged with EACL's calibrated defaults and applies during preflight and the
final authoritative reread. It is not transacted. A separate process can use
a stricter profile without changing the database or the semantics seen by a
process that accepts the schema.

A parse, reference, limit, negative-cycle, attribute-conflict, transaction, or
CAS failure leaves the old permission rows active. Datomic does not publish the
v8 permission-storage stamp; Datahike continues to classify the rows as flat.
Additive attribute definitions installed before a failed final transaction are
inert for v7 readers. Mixed flat/expression storage is rejected rather than
guessed or repaired.

Client construction is fail-closed until migration succeeds:

```clojure
(eacl.datomic.core/make-client conn {})
;; throws :eacl/permission-storage-version on released-v7 permission rows
```

The equivalent Datahike call is `eacl.datahike.core/make-client` and reports
the same typed `:eacl/permission-storage-version` error.

An application may opt into the same migration during construction:

```clojure
(eacl.datomic.core/make-client
 conn
 {:expression-limits {:maximum-source-nodes 32768
                      :maximum-source-depth 64}
  :auto-migrate-v7 {:schema released-v8-schema-string}})
```

For Datahike, use `{:auto-migrate-v7 true}` to consume the stored schema, or
`{:auto-migrate-v7 {:schema released-v8-schema-string}}` to provide it
explicitly.

Do not use automatic migration when several processes may start concurrently;
run the explicit maintenance step once, then start ordinary v8 clients. A
concurrent schema replacement fails with `:eacl.schema/concurrent-write`; retry
the migration against the new current database value after resolving which
schema is authoritative.

Derived expression metrics are not part of the migrated data. Node counts,
depth, fan-in, encoded size, normalized DAG dimensions, word counts, and
checkpoint weights are recomputed from canonical payloads and cached by EACL.
Relationship observations are populated organically at immutable
high-watermarks. Neither category adds metric datoms to the Datomic tx-log.

The v8 permission row contains only `:eacl/id`, resource type, permission name,
and the canonical versioned expression payload. The payload carries its codec
format. Separate experimental expression-format, expression-digest, and
expression-policy-digest attributes are neither installed by a clean v8 schema
nor asserted by migration. If an unreleased experimental database already
installed them, Datomic retains their historical attribute definitions and
old datoms, but v8 ignores them as inert data.
