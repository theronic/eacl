# `eacl-datomic`

Datomic adapter for EACL.

Responsibilities:

- Datomic physical schema and schema installation
- Datomic tuple/index storage implementation
- Datomic relationship write planning and transaction execution
- v8 consistency descriptors, Zed tokens, encrypted Relay-style pagination, and historical reads
- v8 authenticated result cache with mutation/content proofs and cache-store contract
- v6-to-v7 migration and v8 object-deletion/integrity helpers
- Datomic compatibility namespaces preserving the existing public surface
- Datomic-only regression and storage-mechanics tests

Depending on this module keeps existing `eacl.core` and `eacl.datomic.*`
require forms unchanged.

## Optional atomic entity retraction

Ordinary Datomic `:db.fn/retractEntity` cannot follow the peer eid embedded in
an EACL heterogeneous relationship tuple. Calling it directly can therefore
leave a peer-side ghost that continues granting access. The optional named
database function removes both endpoint halves and the entity atomically:

```clojure
(require '[datomic.api :as d]
         '[eacl.datomic.safe-retraction :as safe-retraction])

(safe-retraction/support-descriptor) ; => {:mode :named, ...}
(safe-retraction/install! conn)      ; explicit, privileged, idempotent

@(d/transact
  conn
  (safe-retraction/retract-entity-tx-data [:eacl/id "account-1"]))
```

`install!` also prepares the v3 mutation graph. It installs
`:eacl.fn/retractEntity` only when called, upgrades recognized EACL
version/digest markers, and refuses to overwrite an unrelated occupant. The
stored `d/function` body uses only Clojure core, JDK cryptography, and
`datomic.api`; no `DATOMIC_EXT_CLASSPATH` or EACL transactor dependency is
required. Treat installation/removal as a schema deployment and roll back
callers before removing the installed entity.

The function reads the target's two endpoint attributes from transaction-start
state, authenticates the mutation envelope, advances graph/relation proofs,
and preserves Datomic's native inbound-ref/component retraction. It does not
repair ghosts when the target is already missing, and it does not see sibling
relationship additions in the same application transaction. Use one invocation
per transaction. For high-degree targets, prefer batched `eacl/delete-object!`
followed by ordinary entity retraction; use
`eacl.datomic.integrity/dangling-relationship-report` and
`repair-tx-batches` for existing damage.

```clojure
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}
```

Its POM depends on `dev.eacl/eacl` at the exact same version, so consumers do
not declare core separately. EACL targets Java 26 by default; explicit
source/custom builds can target older Java, subject to Datomic's own runtime
requirements. Build this module in isolation with `clojure -T:build jar`; Git and `:local/root`
development must first follow the explicitly opt-in
[core source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers install no formal tools.

For the cross-backend capability matrix, recursive controls, and cache
mutation rules, see the
[v8 backend and upgrade guide](../../docs/v8-backend-modules-and-upgrade.md).
