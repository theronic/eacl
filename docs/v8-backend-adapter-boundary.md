# V8 backend adapter boundary

This inventory is the extraction boundary for the Datomic, DataScript, and
Datahike v8 adapters. It distinguishes reusable authorization behavior from
database mechanics; shared code must not import an adapter namespace.

## Operation inventory

| Concern | Current Datomic implementation | V8 owner |
| --- | --- | --- |
| Public request and error contract | `eacl.datomic.core/spiceomic-*` and `eacl.datomic.impl.indexed/normalize-page-request` | Shared engine, except backend capability rejection |
| Consistency descriptor selection | `eacl.consistency/select` and `eacl.datomic.backend` | Shared capability validation; adapter selects the promised native revision |
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
| Schema generation | physical schema-version/generation assertion | Adapter, consumed by shared current cache |
| Relationship stamps | physical current-transaction per-relation version assertion | Adapter, complete compiled dependency set |
| Completed answers | private exact/managed generations in `eacl.cache`; exact/arbitrary-DB bypass | Shared current-cache contract |
| Schema parsing/model validation | `eacl.spicedb.parser`, `eacl.schema.model` | Shared |
| Schema persistence | `eacl.datomic.schema` | Adapter |
| Relationship reads and transactions | `eacl.datomic.impl` | Adapter |
| Object relationship deletion | `tx-delete-object*` | Adapter transaction; shared public semantics |

## Snapshot adapter

`eacl.backend.v8` validates a capability profile and a map of operations bound
to one immutable backend snapshot. The mandatory operations are:

- snapshot identity;
- stable source/branch identity, source lifecycle, and native revision;
- external/internal object ID conversion;
- relation and permission definition reads;
- ordered forward and reverse adjacency scans;
- direct relationship matching;
- opaque schema generation and complete relation dependency stamps.

Optional operations cover permission-node enumeration, dependency metadata,
relationship reads, and mutation transaction planning. Algorithms call these
operations through `eacl.backend.v8/invoke`; they do not inspect Datomic
`Database`, DataScript DB, Datahike DB, datom, or tuple implementation types.

There is one production backend contract. An adapter declares exactly which
consistency, snapshot, cursor, transaction, proof, and runtime capabilities it
provides.

## Capability policy

Unsupported guarantees fail with `:eacl/unsupported-capability` before
authorization work begins. Datomic declares all four v8 consistency modes,
historical snapshots, authenticated encrypted cursors, schema/relationship
transactions, deletion, and database-visible proofs. DataScript provides a
serialized local head and no exact-history capability. Datahike derives
authoritative-head and exact-history capabilities from the active writer,
commit-graph, and history configuration. Shared code never maps an unsupported
request to a weaker mode.

The required adapter surface has no graph-state or anchor-membership
operation. At-least selection proves a numeric native revision floor inside
one authenticated source lifecycle; exact selection additionally proves the
exact locator. Cache authority is independent: `:unknown` may still issue and
consume supported native tokens while retaining completed answers exact-only.
