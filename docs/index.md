# 🦅 EACL: Enterprise Access ControL

EACL is a situated relationship-based authorization library for Clojure and
ClojureScript, backed by Datomic Pro, Datahike, DataScript, or a qualified
embedded Datalevin deployment. Authorization
data lives beside application data and is evaluated against one immutable
database value per request.

Start with the [Datomic quickstart](../README.md#quickstart) or the
[consumer checks](examples/datomic-consumer/).

## Modules

Choose the adapter for your database; it brings the core module transitively:

```clojure
;; Datomic
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-RC-2026-10-02"}}}

;; Datahike
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-RC-2026-10-02"}}}

;; DataScript
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-RC-2026-10-02"}}}

;; Datalevin (implemented; publication pending maintained-fork release)
{:deps {dev.eacl/eacl-datalevin {:mvn/version "8.0.0-SNAPSHOT"}}}
```

The [repository README](https://github.com/theronic/eacl#readme) contains the
complete quickstarts, schema language, query API, relationship maintenance,
consistency descriptors, cursor behavior, and limitations.

## Current operational contract

EACL caches exact immutable-snapshot answers first and automatically attempts
proof-backed reuse across unrelated forward transactions. All
authorization-relevant schema, relationship, permissioned identity/liveness,
repair, and entity-deletion mutations must use EACL APIs or documented EACL
transaction data/functions transacted intact.

After an unsupported raw authorization mutation, quiesce every affected
process, repair the data, expire or recreate every affected client, and only
then resume traffic. Cache expiry does not repair ghost relationships.

Ordinary native entity retraction cannot follow the peer ID embedded inside an
EACL relationship tuple/vector. Use `eacl/delete-object!` before native
retraction, or explicitly install/use the backend's optional
`:eacl.fn/retractEntity` transaction function.

## Guides

- [Permission set algebra](permission-set-algebra.md) — intersection,
  exclusion, precedence, stratification, limits, order, cursors, cache, and
  measured performance
- [Wildcard subjects](../README.md#wildcard-subjects) — `user:*` relations,
  Caveated wildcards, and `*` with its exclusions in subject lookups
- [The stable-discovery engine](stable-discovery-engine.md) — enumeration order, cursors, continuation, limits
- [Cache behavior and recovery](cache.md)
- [Consistency and cache operations](v8-consistency-cache-operations.md)
- [Backend modules and capabilities](v8-backend-modules-and-upgrade.md)
- [Backend adapter contract](v8-backend-adapter-boundary.md)
- [Backend basis sources](v8-snapshot-provider-migration.md)
- [Answer cache and subproblem store](v8-subproblem-cache.md)
- [Formal assurance boundary](formal-verification.md)

## Upgrading an existing database

A new database needs none of these steps; follow the
[quickstart](../README.md#quickstart). To upgrade a database written by an
earlier EACL version, back it up, rehearse on a copy, and keep authorization
readers and writers stopped until every step that applies is complete. Run
the steps in this order:

1. [v6 to v7 relationships](migration-v6-to-v7.md): Datomic databases that
   still store v6 relationship entities.
2. [v7 to v8 permissions](migration-v7-to-v8.md): Datomic or Datahike
   databases with released v7 flat permission rows.
3. [Relationship storage 7 to 8](relationship-storage-v7-to-v8.md): every
   database with storage-7 relationships, on any backend, including one that
   step 1 converted. Complete it before starting v8 clients.

Client construction never migrates data: it rejects storage that still needs
one of these steps. Then check dependencies and configuration with the
[application upgrade checklist](v8-backend-modules-and-upgrade.md#upgrading-an-application),
and follow the [serving rollout](caveats.md#coordinated-rollout-and-rollback)
before writing expiring or conditional relationships.

## Licence

EACL is licensed under the Eclipse Public License v2.0.
