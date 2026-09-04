# Prerequisite: migrating v6 Relationship entities to storage 7

This explicit Datomic migration converts v6 Relationship entities into the
four-slot storage-7 source format. Current EACL v8 clients require a subsequent
[storage 7-to-9 migration](migration-v7-to-v9.md). Keep readers and writers
quiesced across both steps. Neither step preserves an older runtime reader.

## Storage change

v6 stored one entity per relationship, including five scalar attributes and
two derived composite tuples. The intermediate storage-7 layout stores two
cardinality-many heterogeneous tuple datoms:

```clojure
[:db/add subject-eid
 :eacl.v7.relationship/subject-type+relation+resource-type+resource
 [subject-type relation-eid resource-type resource-eid]]

[:db/add resource-eid
 :eacl.v7.relationship/resource-type+relation+subject-type+subject
 [resource-type relation-eid subject-type subject-eid]]
```

The `eacl.v7.relationship` keyword namespace is the persisted storage ABI. It
does not select a v7 engine. The next migration converts these attributes into storage-9 endpoint pairs.

The relation component is the entity id of the matching Relation definition,
not the relation-name keyword. Both tuple halves must exist.

## Run the migration

Back up and rehearse against a restored database, pause
authorization-relevant writes, then run:

```clojure
(require '[eacl.migrations.v6-to-v7 :as migration])

(migration/migrate!
 conn
 {:schema "definition user {} ..."})
```

`migrate!`:

1. installs the current schema and relationship attributes;
2. adds canonical `:eacl/id` values to old schema entities that lack them;
3. validates and writes the supplied SpiceDB schema, when present;
4. creates both tuple halves for every v6 relationship;
5. verifies that every v6 row has both tuple halves;
6. retracts the superseded v6 relationship entities; and
7. records `:eacl/storage-version 7`.

The operation is idempotent and can be rerun after interruption. It never
retracts v6 entities until verification succeeds. Continue with the explicit
7-to-9 migration before constructing clients or resuming traffic. Client
construction rejects every `:auto-migrate-*` option. Restore the complete
pre-migration backup if rollback is required.

## Detect and verify

```clojure
(migration/detect-storage-version (datomic.api/db conn))
;; :v6    v6 relationship entities only
;; :mixed v6 entities and tuple data, usually an interrupted migration
;; :v7    tuple data only
;; :none  no relationship data

(migration/verify-backfill (datomic.api/db conn))
;; {:complete? true, ...}
```

`make-client` fails with `{:type :eacl/storage-version}` when v6 relationship
entities remain without a completed migration stamp. Failing closed prevents
the v8 tuple reader from silently returning false or empty authorization
answers against v6 storage.

## Schema input

Supplying `:schema` is recommended. The migration does not infer a current
SpiceDB schema from historical Permission entities; it routes the supplied
schema through `eacl/write-schema!` so current validation and schema-delta
rules apply.

If a relationship refers to a Relation definition absent from that schema,
the migration throws `{:type :eacl.migration/missing-relation}` before
cleanup. Correct the schema or remove the dead v6 row, then rerun.

`:batch-size` controls the number of relationships converted per transaction:

```clojure
(migration/migrate! conn {:schema schema-string
                          :batch-size 250})
```

Pause all relationship writes for the migration. A concurrent old-format
delete could race after its tuple was backfilled and leave a stale grant. The
migration deliberately does not implement dual-write or reverse
synchronization because v8 has no old-engine rollout mode.

## Operational checks

After both the v6-to-v7 and v7-to-v9 migrations:

```clojure
(def acl (eacl.datomic.core/make-client conn options))

(eacl.core/can? acl known-subject :view known-resource)
(eacl.core/lookup-resources
 acl
 {:subject known-subject
  :permission :view
  :resource/type :document
  :first 20})
```

Discard cursors and cache data created by older library versions. Recreate
clients after migration and use `eacl/write-schema!` for subsequent schema
changes.

Low-level helpers such as `backfill-relationship-tuples!`,
`verify-backfill`, and `missing-tuples` exist for diagnosis and custom
operations, but `migrate!` is the supported end-to-end path.
