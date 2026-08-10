# `eacl-datomic`

Datomic adapter for EACL.

Responsibilities:

- Datomic physical schema and schema installation
- Datomic tuple/index storage implementation
- Datomic relationship write planning and transaction execution
- v8 consistency descriptors, Zed tokens, encrypted Relay-style pagination, and historical reads
- v8 authenticated result cache with native schema/relation generations
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

`install!` installs `:eacl.fn/retractEntity` only when called, upgrades
recognized EACL version/digest markers, and refuses to overwrite an unrelated
occupant. The stored `[db target]` function uses only Clojure core and
`datomic.api`; no `DATOMIC_EXT_CLASSPATH` or EACL transactor dependency is
required. Treat installation/removal as a schema deployment and roll back
callers before removing the installed entity.

For a live target, the function computes the native component closure, reads
the two EACL endpoint attributes on every closure entity, retracts each exact
peer half, stamps every distinct affected relation with the current
transaction, and finally delegates deletion to `:db.fn/retractEntity`. The
relation stamps are the managed-cache invalidation mechanism; the function
does not modify an in-memory cache and contains no global CAS.

Multiple and repeated invocations compose in one transaction:

```clojure
@(d/transact conn [[:eacl.fn/retractEntity 1]
                   [:eacl.fn/retractEntity 2]
                   [:eacl.fn/retractEntity 1]])
```

A valid lookup ref that does not resolve is a no-op. A numeric eid remains a
repair key after its entity datoms have been retracted: the function enumerates
the relatively small relation schema and performs exact AVET probes in both
tuple directions to remove peer-only ghosts and stamp their relations. This
fallback cannot recover the eid from a missing lookup ref.

Do not combine relationship additions involving a target with its safe
retraction in the same application transaction; transaction-function
visibility/order cannot provide portable semantics for that case. Separate
EACL writers calculated before a winning deletion fail their commit-time
endpoint identity CAS. For high-degree targets, prefer batched
`eacl/delete-object!` followed by ordinary entity retraction; use
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
