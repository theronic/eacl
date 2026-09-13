# EACL for Datomic

Use EACL to check permissions against data in your Datomic database. Add the
adapter to `deps.edn`; it includes core and Datomic Peer:

```clojure
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-RC-2026-09-12"}}}
```

The published build requires Java 25 or newer. This is a release candidate.
Start with the [Datomic quickstart](../../README.md#quickstart), which creates
a memory database with application-owned `:app/id` values. No separate
Datomic server, account, source checkout, or formal tools are needed for it.
The [consumer checks](../../docs/examples/datomic-consumer/) run against these
Maven dependencies.

## Database setup

For a fresh database, call `eacl.datomic.schema/install!`, install your
application schema, then create the client. Configure both ID converters if
your application uses an attribute other than the default `:eacl/id`.

For retained data, follow the [upgrade guide](../../docs/v8-backend-modules-and-upgrade.md#upgrading-an-application).
Startup refuses an incompatible store; it does not migrate data for you.

## Writes and snapshots

Use `eacl/create-relationships!` for existing endpoints. To commit application
data and relationship changes together, use the public snapshot planner;
see [atomic writes](../../docs/atomic-writes.md). The guide also explains the
published API's limitation for new entities and tempids.

Use `eacl/snapshot` when several reads must see the same database version.
Release it with `eacl/with-snapshot` or `eacl/release!`. Use `eacl/with` and
`eacl/with-schema` to preview changes. The public adapter does not wrap raw
`d/with` or `d/filter` database values.

## Cache coherence

Use EACL APIs or submit EACL-produced transaction data intact for authorization
changes. These writes preserve the checks and relation stamps needed for
correct caching. Ordinary application data can use normal Datomic writes.

Custom ID mappings stay local to a client unless every participating Peer uses
the same deterministic mapping and stable adapter fingerprint. Keep public IDs
unique and stable. See [ID configuration](../../README.md#eacl-id-configuration)
and the [cache guide](../../docs/cache.md).

After unsupported raw authorization changes, stop affected traffic, repair the
data, and expire every affected client. A database restore or history
replacement also requires coordinating the source lifecycle across Peers.

## Optional atomic entity retraction

Ordinary Datomic entity retraction does not remove the relationship stored on
the other endpoint. That leftover relationship can still grant access.

Use `eacl/delete-object!` before retracting the application entity, or install
the optional function that removes both together:

```clojure
(require '[datomic.api :as d]
         '[eacl.datomic.safe-retraction :as safe-retraction])

;; Run during database setup, and again after upgrading EACL.
(safe-retraction/install! conn)

@(d/transact conn
  (safe-retraction/retract-entity-tx-data [:app/id "report"]))
```

This continues the quickstart. The target may be a numeric entity ID or a
lookup ref for an installed unique attribute.

- Installation is idempotent and updates recognized older EACL function bodies.
  It refuses to overwrite an unrelated function.
- Datomic stores the function body. Updating the library dependency alone does
  not update that body.
- The saved function needs only Clojure core and `datomic.api` on the
  transactor; it needs no EACL transactor dependency.
- Repeated deletions in one transaction are allowed. A missing lookup ref is
  a no-op. A known numeric ID can repair leftovers after an earlier native deletion.
- Do not add relationships to an entity in the same transaction that deletes it.
- For very high-degree entities, prefer batched `delete-object!` followed by
  native deletion.

For existing damage, use `eacl.datomic.integrity/dangling-relationship-report`
and `repair-tx-batches`. Cache expiry alone does not repair relationships.

## Security keys

Load-balanced Peers need shared keys to accept each other's cursors and Zed
tokens. Default keys are process-local and do not survive restarts. See
[security keys and rotation](../../docs/security-keyrings.md).

## Further reading

- [Expiration and conditional access](../../docs/caveats.md).
- [Backend consistency and limits](../../docs/v8-backend-modules-and-upgrade.md).
- [Developing from source](../../README.md#development-from-source).
