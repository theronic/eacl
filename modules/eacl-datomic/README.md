# `eacl-datomic`

Datomic adapter for EACL.

Responsibilities:

- Datomic physical schema and schema installation
- Datomic tuple/index storage implementation
- Datomic relationship write planning and transaction execution
- consistency descriptors, Zed tokens, encrypted Relay-style pagination, and historical reads
- automatic exact-first/proof-backed caching with native ordered generations
- object-deletion and integrity helpers
- Datomic compatibility namespaces preserving the existing public surface
- Datomic-only regression and storage-mechanics tests

Depending on this module keeps existing `eacl.core` and `eacl.datomic.*`
require forms unchanged.

## Cache coherence

The client checks exact immutable-snapshot answers first, then automatically
uses complete ordered-generation proofs to reuse completed answers across
unrelated forward transactions. Missing, malformed, oversized, or exceptional
proof data falls back to exact evaluation.

Serverless hosts may persist completed authorization entries with
`export-cache-snapshot`, `restore-cache-snapshot!`, and
`cache-content-revision`. The host owns authentication and the encoded-byte
bound before decoding; EACL validates the trusted decoded snapshot and its
count bound. Snapshots exclude Datomic database values and process-local
identity. Restore validates before atomically replacing the visible cache.

## Prospective snapshots

The public adapter does not wrap caller-owned Datomic database values. Use
`eacl/tx-relationship` with `eacl/with` for composable relationship/application
transactions and `eacl/with-schema` for prospective permission-schema changes.
These EACL-created snapshots are immutable readers: they may reuse only a
complete committed proof for disjoint dependencies and publish no speculative
cache data. `:orphan-policy :retain-inert` is available only to
`with-schema`; diagnostics report bounded presence without counting tuples.
Calling implementation namespaces to inject raw `d/with` or `d/filter` values
forfeits coherence guarantees.

Every authorization-relevant mutation must use EACL APIs or EACL-produced
transaction data/functions transacted intact. After unsupported raw mutation,
quiesce callers, repair the data, and call
`eacl.datomic.core/expire-cache!` on every affected client in every process.
When processes exchange tokens, pass the same new lifecycle as the optional
second argument. Expiry never repairs ghost tuples.

Custom object-ID codecs are exact-only and client-local unless configured with
a portable `:adapter-fingerprint`, `:adapter-deterministic? true`, and an
application-certified injective round trip. Proof-equivalent cursors further
require `:identity-immutable? true`; without it they remain exact-basis-bound.
The built-in `:eacl/id` codec assumes IDs never change for an entity. Set
`:identity-immutable? false` if the application permits reassignment.

`expand-permission-tree` routes Datomic's selected immutable DB through the
same portable shallow-expansion kernel used by DataScript and Datahike. The
returned `:expanded-at` is issued from that selected adapter without re-reading
the connection; `at-exact-snapshot` can replay it while the required Datomic
history remains available. Native child/subject order is not semantic.
Configure structural ceilings with client-level `:permission-tree-limits`.

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
relation stamps are the cache-coherence evidence; the function
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
not declare core separately. EACL targets Java 25 by default; explicit
source/custom builds can target older Java, subject to Datomic's own runtime
requirements. Build this module in isolation with `clojure -T:build jar`; Git and `:local/root`
development must first follow the explicitly opt-in
[core source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers install no formal tools.

For the cross-backend capability matrix, recursive controls, and cache
mutation rules, see the
[backend guide](../../docs/v8-backend-modules-and-upgrade.md).

## Removed (2026-09-01)

- `eacl.datomic.codec` — page-token payload codec superseded by the portable
  `eacl.secure-format` (used by `eacl.cursor` and `eacl.causal-token`).
- `eacl.datomic.consistency` — the last remnant (`derive-signing-key`);
  live Zed tokens are issued and authenticated by the shared
  `eacl.causal-token` codec.

## Removed (2026-09-02)

- `eacl.datomic.impl.indexed/{subject->resources,resource->subjects,normalize-page-request,permission-relationship-eids,permission-schema-nodes}`
  — unreferenced facade wrappers; call `eacl.datomic.db` and `eacl.engine.v8`
  directly.
- Basis-adapter configuration keys `:object-eid-fn`, `:subject->resources-fn`
  and `:resource->subjects-fn` — an override seam whose only client was an
  identity facade; the adapter reads `eacl.datomic.db` directly.
- `eacl.datomic.schema/{calc-set-deltas,compare-schema}` are now aliases of
  `eacl.schema.model` (same values).

## Relationship storage 9

This EACL v8 adapter uses five-slot endpoint pairs with a trailing nullable
`qualifier-eid`. This phase writes `nil` and raises `:eacl/unsupported-qualifier`
when serving encounters a qualifier. Upgrades are explicit and restartable;
ordinary client construction requires a completed target store. Follow the
[7-to-9 operator guide](../../docs/migration-v7-to-v9.md) before starting clients.

Use `(eacl.datomic.schema/install! conn)` to bootstrap a fresh native database.
