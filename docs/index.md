# 🦅 EACL: Enterprise Access ControL

EACL is a situated relationship-based authorization library for Clojure and
ClojureScript, backed by Datomic Pro, Datahike, DataScript, or a qualified
embedded Datalevin deployment. Authorization
data lives beside application data and is evaluated against one immutable
database value per request.

## Modules

Choose the adapter for your database; it brings the core module transitively:

```clojure
;; Datomic
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Datahike
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-SNAPSHOT"}}}

;; DataScript
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-SNAPSHOT"}}}

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

- [The stable-discovery engine](stable-discovery-engine.md) — enumeration order, cursors, continuation, limits
- [Cache behavior and recovery](cache.md)
- [Consistency and cache operations](v8-consistency-cache-operations.md)
- [Backend modules and capabilities](v8-backend-modules-and-upgrade.md)
- [Backend adapter contract](v8-backend-adapter-boundary.md)
- [Snapshot-provider migration](v8-snapshot-provider-migration.md)
- [Answer cache and subproblem store](v8-subproblem-cache.md)
- [Formal assurance boundary](formal-verification.md)
- [Audit reports](reports/) — dated records; the 2026-08-15 stable-engine audit lists open bugs and optimizations

## Licence

EACL is licensed under the Eclipse Public License v2.0.
