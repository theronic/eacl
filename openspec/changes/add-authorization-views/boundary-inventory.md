# Authorization boundary inventory

This inventory freezes the pre-change protocol, Datomic orchestration, and
option-map ownership boundaries. Paths are repository-relative unless noted.

## Protocol implementations and probes

| Kind | Location | Current responsibility | Replacement |
| --- | --- | --- | --- |
| Protocol declarations | `modules/eacl/src/eacl/core.cljc` | `IAuthorization` declares all reads, writes, and convenience arities; `IDetailedAuthorization` optionally supplies canonical detailed checks. | `IAuthorizationReader`, `IAuthorizationWriter`, `ISnapshotSource`, and `IAuthorizationSnapshot`. |
| Shared live client | `modules/eacl/src/eacl/client/orchestration.cljc`, `ClientAuthorization` | Implements both legacy protocols plus batch and callback-scoped snapshot composition. | `Acl`, using the reader/source/writer capabilities. |
| Shared callback view | `modules/eacl/src/eacl/client/orchestration.cljc`, `SnapshotAuthorization` | Implements both legacy protocols by delegating to a fixed-provider client and rejects writes at runtime. | Retained `Snapshot`, containing only runtime and basis. |
| Datomic client | `modules/eacl-datomic/src/eacl/datomic/core.clj`, `Spiceomic` | Implements both legacy protocols and a second complete orchestration. | Shared `Acl`; delete `Spiceomic`. |
| Core legacy test double | `modules/eacl/test/eacl/core_test.cljc`, `LegacyAuthorization` | Proves the optional detailed-decision fallback. | Reader/writer/source/snapshot capability test doubles; delete the fallback assertion. |
| Public protocol probes | `modules/eacl/src/eacl/core.cljc` | `satisfies? IDetailedAuthorization`, `IBatchedAuthorization`, and `ISnapshotAuthorization` select fallbacks or optional extensions. | Capability dispatch against the four new protocols; batch remains a reader operation rather than a hidden scalar loop. |
| Shared conformance probes | `modules/eacl/test/eacl/contract_support.cljc` | Three `satisfies? ISnapshotAuthorization` checks gate callback-view assertions. | The five-target matrix uses `acl?`/`snapshot?` and declared source capabilities. |
| Out-of-tree SpiceDB record | `/Users/petrus/code/eacl/eacl-spicedb/src/eacl/spicedb.clj`, `SpiceDBAuthorization` | Implements `eacl/IAuthorization` and `eacl/IDetailedAuthorization`. | Implements reader and writer directly; unsupported EACL extensions fail typed. |
| SpiceDB clean-consumer gate | `/Users/petrus/code/eacl/eacl-spicedb/dev/eacl/spicedb/build/clean_consumer.clj` | Asserts `satisfies? eacl/IAuthorization`. | Assert reader/writer capabilities and typed extension refusal. |

Backend `make-client` wrappers in `modules/eacl-datahike`,
`modules/eacl-datascript`, and `modules/eacl-datalevin` return the shared
`ClientAuthorization`; they do not define another authorization record.

## Datomic behavior that the shared pipeline must absorb

| Behavior | Current implementation | Frozen contract |
| --- | --- | --- |
| Relationship contention loop | `modules/eacl-datomic/src/eacl/datomic/core.clj`, `spiceomic-write-relationships!`; `maximum-relationship-write-attempts` is 8 and `datomic-cas-failure?` walks the cause chain for `:db.error/cas-failed`. | Re-select and re-plan after classified contention; stop after eight submissions and throw `:eacl/relationship-contention`; rethrow non-contention failures unchanged. |
| Object deletion batching | Same file, `delete-object-batch-size` is 1000, `transact-delete-object-batch!`, and `spiceomic-delete-object!`. | Stream relationship halves, submit at most 1000 per transaction, stamp every batch independently, retry each batch under the same contention policy, and report the cumulative retracted-datom count. |
| Schema fence | `modules/eacl-datomic/src/eacl/datomic/impl.clj`, `optimistic-relationship-tx-data`, plus `schema-version-cas`. | The schema-generation CAS precedes relationship transaction functions and mutations, so a plan cannot commit after its schema generation is superseded. |
| Relation stamps | `modules/eacl-datomic/src/eacl/datomic/impl.clj`, `relation-version-cas`, `stamp-relation-versions`, and `optimistic-relationship-tx-data`. | Every affected relation is CAS-advanced in the same transaction as its relationship mutations or retractions. |
| Schema write metadata | `modules/eacl-datomic/src/eacl/datomic/schema.clj`, `write-schema!`; consumed by `Spiceomic.write-schema!`. | Preserve delta data and metadata including `:eacl.schema/db-after`, `:eacl.schema/no-op?`, and `:eacl/schema-version`; invalidate derived/cache state only for a semantic write and issue the response token from `db-after`. |
| Token revision/locator invariant | `modules/eacl-datomic/src/eacl/datomic/backend.clj`, `validate-exact-token!`. | A Datomic token must have scalar `revision == exact-locator`; contradiction fails before selection. |
| Basis-kind admission | Same file, `ordinary-view?`. | Plain and as-of Datomic values are admissible; filtered, since, history, and speculative values remain engine-facade-only. |
| Cursor codec | `modules/eacl-datomic/src/eacl/datomic/core.clj`, `eacl4_` prefix, version 7, encrypt/decrypt/page-token helpers. | Delete it and use the shared secure cursor codec without changing public cursor validation. |
| Page/continuation cache | Same file, `continuation-context`, private `continuation-cache-store`, cursor validation, and page-token helpers. | Use runtime-owned shared continuation and visited-page stores keyed by exact basis and query scope. |
| Result-context capture | Same file, `capture-basic-result-context`, `capture-result-context`, `snapshot-result-context`, and `with-result-schema`. | Replace with one `eacl.request.context/make-context` snapshot context used by every read. |
| Cache lifecycle and stats | Same file, `expire-cache!` and `cache-stats`. | Shared `Acl` owns lifecycle rotation and all registry clearing; shared stats report the same observable cache categories. |

## Shared orchestration option ownership

The table classifies every durable `base-opts` key assembled by
`eacl.client.orchestration/make-client`, plus every request-derived key read by
the orchestration. `Basis` means immutable selected-basis data, not client
configuration.

| Owner | Keys |
| --- | --- |
| Runtime | `:adapter-fingerprint`, `:adapter-deterministic?`, `:aggregate-limits`, `:cache-attempt`, `:continuation-cache-store`, `:current-cache-store`, `:cursor-codec-cache`, `:page-navigation-cache`, `:decision-kernel`, `:derived-schema-caches`, `:entid->object-id`, `:object-id->entid`, `:object-id->lookup-ref`, `:object->entid`, `:internal-object->spice`, `:spice-object->internal`, `:internal-cursor->spice`, `:spice-cursor->internal`, `:format-options`, `:cursor-ttl-seconds`, `:token-ttl-seconds`, `:managed-cache-enabled?`, `:recursive-traversal-limits`, `:permission-tree-limits`, `:execution-timeout-ms`, `:consistency-sync-timeout-ms`, `:service-admission`, `:source-lifecycle`, `:source-lifecycle-state`. |
| Source | `:conn`, `:snapshot-provider`, `:native-source-id`; Datalevin extensions `:datalevin-topology`, `:revision-watermark`, `:advance-revision-watermark!`, and its prepared native source-id key. |
| Basis | `:snapshot-semantic-identity`, `:snapshot-exact?`, `:cache-lifecycle`, `:request-proof-frame`, `:request-schema-cache`; private namespaced `::selection` and `::snapshot-exact?`/`::completed-cache?` in the selected context. |
| Writer | No writer value is currently isolated. Submission lives in the backend `api` map as `:transact!`, schema `:write-schema!`, relationship planning functions, retraction counting, and the live `conn`. These move together into the writer role. |
| Request | `:execution-contract`, `:execution-request`, `:request-operation`, `:request-counter-ledger`, `:completed-cache-request?`, `:completed-cache?`, `:continuation-cache-request?`, `:cursor-dependency-relation-ids`, and private `::request-context`. |

## Datomic `opts` ownership

| Owner | Keys |
| --- | --- |
| Runtime | `:adapter-fingerprint`, `:adapter-deterministic?`, `:aggregate-limits`, `:cache-attempt`, `:cache-lifecycle`, `:cache-remember-answers?`, `:continuation-cache-store`, `:current-cache-store`, `:decision-kernel`, `:derived-schema-caches`, `:diagnostic-schema-version`, `:entid->object-id`, `:object-id->entid`, `:object-id->ident`, `:object->entid`, `:internal-object->spice`, `:spice-object->internal`, `:format-options`, `:page-token-current-kid`, `:page-token-keyring`, `:page-token-ttl-seconds`, `:zed-token-current-kid`, `:zed-token-keyring`, `:token-ttl-seconds`, `:managed-cache-enabled?`, `:recursive-traversal-limits`, `:permission-tree-limits`, `:revision-checkpoints`, `:execution-timeout-ms`, `:consistency-sync-timeout-ms`, `:service-admission`, `:source-lifecycle`, `:source-lifecycle-state`. |
| Source | `:database-id`, `:snapshot-provider`; the `conn` is currently captured by the `Spiceomic` record and by `:backend-adapter-fn`, and must leave both runtime and adapter. |
| Basis | `:selected-schema-version`, `:page-cursor-context`, and request-derived `:snapshot-semantic-identity`; the selected `db`, native revision, schema cache, proof frame, and cursor scope currently live in result-context maps rather than a named basis value. |
| Writer | No isolated writer key exists. `Spiceomic` closes over `conn`; schema, relationship, and deletion functions call Datomic directly. |
| Request | `:execution-contract`, per-call `:cache-lifecycle`, `:page-cursor-context`, selected/result context, cache override, consistency descriptor, timeout/cancellation data, and aggregate counters. |

The classification exposes the pre-change defect: both shared and Datomic
adapter constructors receive connection-reachable state, while writer state is
not a separately certifiable capability.

## Boundary instrumentation

`eacl.backend.snapshot-provider/*source-op-stats*` counts source operation
keywords. `eacl.backend.v8/*backend-op-stats*` counts adapter operations.
`eacl.request.counters` separately records `:acquisitions`, `:adapter-reads`,
`:writer-submissions`, and `:releases`; shared tests select these through
`eacl.contract-support/boundary-counts`.
