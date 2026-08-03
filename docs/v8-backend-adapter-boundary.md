# V8 backend adapter boundary

This inventory is the extraction boundary for the Datomic, DataScript, and
Datahike v8 adapters. It distinguishes reusable authorization behavior from
database mechanics; shared code must not import an adapter namespace.

## Operation inventory

| Concern | Current Datomic implementation | V8 owner |
| --- | --- | --- |
| Public request and error contract | `eacl.datomic.core/spiceomic-*` and `eacl.datomic.impl.indexed/normalize-page-request` | Shared engine, except backend capability rejection |
| Consistency descriptor selection | `eacl.datomic.core/capture-result-context` and `eacl.datomic.consistency` | Shared capability validation; adapter selects the promised snapshot |
| Historical/current snapshot | `d/db`, `d/as-of`, `d/sync` in `eacl.datomic.core` | Adapter |
| Object ID resolution | `object-id->entid`, `entid->object-id`, `object-eid` | Adapter |
| Relation and permission definitions | `relation-datoms`, `find-permission-defs` | Adapter returns normalized definitions |
| Permission dependency graph and SCC detection | `calc-permission-paths`, `permission-query-dependencies`, `recursive-permission-query?` | Shared engine |
| Forward/reverse adjacency scans | `subject->resources`, `resource->subjects`, Datomic `:eavt` seeks | Adapter |
| Direct relationship match | `direct-match-datoms-in-relationship-index` | Adapter |
| Acyclic path traversal and merge/de-duplication | `traverse-permission-path*`, `lazy-merged-lookup` | Shared engine |
| Recursive forward/reverse work queues | `compile-recursive-rules`, `next-forward-item`, `next-reverse-item` | Shared engine |
| Relay windowing and count limits | `normalize-page-request`, `page-response`, `count-*` | Shared engine |
| Cursor traversal state | path frontiers and recursive continuation maps | Shared engine, versioned |
| Cursor protection and runtime encoding | AES-GCM page tokens in `eacl.datomic.core` | Adapter/runtime |
| Schema proof | `:eacl/schema-version` | Adapter, opaque to shared code |
| Relationship proof | `eacl.datomic.watermark/safe-epoch-for` | Adapter, opaque to shared code |
| Cache store, entries, and validation | `eacl.datomic.cache` and result-cache helpers in `core` | Shared cache contract |
| Schema parsing/model validation | `eacl.spicedb.parser`, `eacl.schema.model` | Shared |
| Schema persistence | `eacl.datomic.schema` | Adapter |
| Relationship reads and transactions | `eacl.datomic.impl` | Adapter |
| Object relationship deletion | `tx-delete-object*` | Adapter transaction; shared public semantics |

## Snapshot adapter

`eacl.backend.v8` validates a capability profile and a map of operations bound
to one immutable backend snapshot. The mandatory operations are:

- snapshot identity;
- external/internal object ID conversion;
- relation and permission definition reads;
- ordered forward and reverse adjacency scans;
- direct relationship matching;
- opaque schema and relation proofs.

Optional operations cover permission-node enumeration, dependency proofs,
relationship reads, and mutation transaction planning. Algorithms call these
operations through `eacl.backend.v8/invoke`; they do not inspect Datomic
`Database`, DataScript DB, Datahike DB, datom, or tuple implementation types.

The legacy six-function SPI in `eacl.backend.spi` is unchanged. It remains the
compatibility surface for v7-style third-party adapters. A v8 adapter opts into
the richer contract and declares exactly which consistency, snapshot, cursor,
transaction, proof, and runtime capabilities it provides.

## Capability policy

Unsupported guarantees fail with `:eacl/unsupported-capability` before
authorization work begins. Datomic declares all four v8 consistency modes,
historical snapshots, authenticated encrypted cursors, schema/relationship
transactions, deletion, and database-visible proofs. DataScript and Datahike
will declare only the guarantees implemented by their immutable snapshot and
runtime APIs; shared code must not silently map an unsupported request to a
weaker mode.
