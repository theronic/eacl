# Relationship storage 7 → 9 in EACL v8

EACL **v8** uses permission storage **8** and Relationship storage **9**.
Storage 9 adds a fifth, nullable qualifier reference to each endpoint tuple.
V8 writes `nil` qualifiers; a non-`nil` qualifier encountered by a v8
authorization or public Relationship read raises `:eacl/unsupported-qualifier`.
V9 [Caveats and expiring Relationships](caveats.md) build on this completed
storage migration with a separate coordinated serving activation. Complete this
migration before allowing any qualified writes.

Every logical Relationship remains exactly two datoms:

```clojure
[subject-eid :eacl.v9.relationship/subject-type+relation+resource-type+resource+qualifier
 [subject-type relation-eid resource-type resource-eid nil]]
[resource-eid :eacl.v9.relationship/resource-type+relation+subject-type+subject+qualifier
 [resource-type relation-eid subject-type subject-eid nil]]
```

Datomic, Datahike, and Datalevin use heterogeneous tuple types
`[keyword ref keyword ref ref]`. DataScript uses indexed ordinary vectors.
The owner plus the first four components identifies a Relationship;
qualifiers do not create additional identities. Component four still orders
the opposite endpoints. Immutable old schema attributes remain installed,
but current v7 Relationship datoms are removed during conversion.

## Prepare the maintenance window

1. Back up the complete native database and rehearse restoration and migration
   on an isolated copy, with the same backend version and writer configuration.
2. Stop every authorization reader and writer, including application transactions,
   background jobs, and schema administration. Keep that fence in place through
   verification and cutover. `:quiesced? true` is an operator assertion, not a
   distributed lock. Each migration transaction also checks the calculation basis.
3. For Datomic v6 Relationship entities, run
   `eacl.migrations.v6-to-v7/migrate!` first. For released flat permissions on
   Datomic or Datahike, run the corresponding
   [permission migration](migration-v7-to-v8.md). Neither prerequisite starts
   an ordinary client. Relationship migration preserves relation identities.
4. Use a native connection to run the storage migration. Client construction
   intentionally refuses a legacy or interrupted store and accepts no
   `:auto-migrate-*` option.

## Invoke the native entry point

Require only the adapter used by your application:

```clojure
;; Datomic
(require '[eacl.datomic.migrations.v7-to-v9 :as upgrade])
;; Datahike
(require '[eacl.datahike.migrations.v7-to-v9 :as upgrade])
;; DataScript, JVM or ClojureScript
(require '[eacl.datascript.migrations.v7-to-v9 :as upgrade])
;; Datalevin, supported embedded JVM topology
(require '[eacl.datalevin.migrations.v7-to-v9 :as upgrade])
```

Evaluate the following after selecting one namespace:

```clojure
(upgrade/migrate!
 conn
 {:quiesced? true
  :batch-size 1000
  :on-progress #(prn (select-keys % [:state :converted :source-count :source-digest]))})
```

Omit `:on-progress`, or replace it with your maintenance logger. A batch size
from 1 to 10,000 controls logical pairs per transaction. Each converted pair
adds both five-slot halves, retracts both four-slot halves, and stamps the
affected Relation in the same transaction. Datalevin migration upgrades its
persisted write policy using the admitted native writer. Datahike requires a
supported local writer; it does not ship closures through a remote writer.

## Progress, verification, and recovery

The schema singleton stores canonical migration metadata under
`:eacl.storage/migration-state`. Durable phases are `:preflight`, `:converting`,
`:verifying`, `:cleaning`, and `:complete`. Preflight validates physical source
schema, references, exact pair parity, and logical uniqueness. It records a
canonical source count and digest before conversion. Diagnostics include a
bounded sample when pair parity fails.

The final verification independently scans both target directions, checks
that the source is empty, compares count and digest with preflight, and checks
affected Relation generations. Only the final atomic completion transaction
sets `:eacl/storage-version` to 9. Startup checks metadata, physical target
shape, and bounded legacy-existence probes; it does not repeat graph verification.

After an interruption, keep the maintenance fence and call the same entry point
again. It reconciles the surviving source and target pairs with the original
certificate and resumes. A changed database head, malformed pair, unexpected
target, or digest mismatch fails closed. Investigate that failure; do not edit
the certificate or manufacture a completion stamp. Rerunning a complete store
returns `:already-complete? true` without rewriting Relationships or advancing
the native transaction counter.

## Cut over or restore

Confirm the report is `:complete`, its count matches your rehearsal, and native
backup/operational checks pass. Start new v8 clients, run application permission
smoke checks, then restore traffic. Discard persisted authorization caches and
cursors from the old storage ABI. Adapter and ordering identities changed;
old cursors are rejected and old cache entries cannot authorize new requests.

Rollback restores the whole pre-migration backup with the previous application
build while traffic remains stopped. There is no reverse migration or runtime
dual reader. Do not switch an old binary onto a partly converted database.

For a fresh database, use `eacl.datomic.schema/install!` on Datomic or the
adapter's `create-conn` helper on Datahike, DataScript, or Datalevin. These
explicit bootstrap operations install a completed storage-9 marker. Ordinary
`make-client` validates an existing installation and performs no Relationship
migration.

## Errors

| Error | Meaning |
| --- | --- |
| `:eacl/storage-version` | Legacy, incomplete, mixed, wrongly stamped, or physically incompatible store; includes the backend and migration guidance. |
| `:eacl/unsupported-qualifier` | Serving encountered a qualifier that this phase cannot evaluate. |
| `:eacl/invalid-relationship-storage` | Malformed endpoint data or duplicate first-four identity. |
| `:eacl.storage/upgrade-failed` | Migration precondition, basis, content, reference, or verification failure; inspect `:reason`. |
| `:eacl/invalid-config` | Automatic migration options are no longer accepted by client construction. |
