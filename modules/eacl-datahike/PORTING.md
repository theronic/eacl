# The Datahike backend

The first Datahike port was derived from `modules/eacl-datascript`. The v8
relationship layer now deliberately follows `modules/eacl-datomic`: Datahike
supports heterogeneous tuples, so it does not need DataScript's relationship
entity plus derived composite indexes. DataScript keeps that backend-specific
layout until it gains heterogeneous tuples.

Status: `eacl.datahike.contract-test` runs the shared v8 public API,
recursive, cache, and independent-oracle contracts **twice**, once per
attribute representation, and passes. `eacl.datahike.backend-test` covers the
Datahike-specific paths the shared contract does not reach.

The module is **JVM-only** (`.clj`, not `.cljc`): datahike's ClojureScript API is
asynchronous and the backend SPI is synchronous.

## Layout

Six namespaces. `eacl.datahike.db` holds entity-id, attribute-representation,
and index-access primitives so Datahike API spelling and temporal-seek behavior
stay below the EACL relationship model:

| | |
|---|---|
| `db.clj` | adapter primitives (no EACL concepts) |
| `schema.clj` | attribute declarations, `create-conn`, `write-schema!` |
| `impl.clj` | Datomic-layout relationship transactions, scans, and cleanup |
| `integrity.clj` | explicit offline dangling-half diagnostics |
| `backend.clj` | the validated v8 snapshot adapter and cache proofs |
| `core.clj` | `IAuthorization`, v8 Relay/cache integration, and `make-client` |

## Physical relationship layout

One relationship is exactly two heterogeneous tuple datoms, identical to the
Datomic Pro adapter:

```clojure
[subject-eid forward-attr
 [subject-type relation-eid resource-type resource-eid]]

[resource-eid reverse-attr
 [resource-type relation-eid subject-type subject-eid]]
```

Authorization traversal reads the endpoint-local EAVT segment. Global
relationship listing and relation-in-use checks read AVET tuple ranges. Writes
add or retract both halves in one Datahike transaction. `:touch` treats a
half-pair as absent and reasserts both datoms; `:delete` retracts both
unconditionally.

The layout costs two relationship datoms rather than a relationship entity,
five component datoms, and five derived tuple datoms. It also has Datomic's
integrity tradeoff: `:db/retractEntity` does not follow refs inside tuple
values. Consumers must call EACL relationship deletion first. The explicit
`eacl.datahike.integrity` report detects surviving peer halves offline.

The old Datahike entity/composite layout existed only on the unreleased v8
branch. Databases created from that branch must be recreated; the adapter does
not dual-read or migrate the obsolete physical model.

## What differs from DataScript

| | DataScript | Datahike |
|---|---|---|
| `datoms` / `seek-datoms` | positional: `(ds/seek-datoms db :avet a v)` | option MAP: `(d/seek-datoms db {:index :avet :components [a v]})` |
| `index-range` | positional | `(d/index-range db {:attrid a :start s :end e})` |
| `transact` | `ds/transact!` | `d/transact` — a report, not a future, so no `@` |
| `entid` | `ds/entid` | **absent** — `db/entid`, via `d/entity` |
| `listen` | `ds/listen!` | `d/listen` |
| conn creation | `(ds/create-conn schema)` | `create-database` + `connect`, with a config |
| schema | map of attribute to options | transaction data; `:write` flexibility needs `:db/valueType` and `:db/cardinality` on every attribute |
| `extra-schema` | DataScript schema map | datahike transaction data — the two are NOT interconvertible, since a DataScript schema map carries no value types |

`read-relations` / `read-permissions` read the index rather than running the
DataScript version's `q` with a pull expression: the index is the engine's own
view of the schema, so a relation invisible there is invisible to permission
evaluation too, and it sidesteps datahike's query planner entirely.

## Attribute representation — two modes, and they are not interchangeable

Datahike reports a datom's `:a` as the attribute KEYWORD by default and as a
numeric ref under `:attribute-refs? true` (Datomic's representation). This is a
creation-time, one-way choice per database. Both modes are supported and both
are tested; three consequences are load-bearing.

1. A composite tuple attribute under `:attribute-refs?` needs Datahike
   **>= 0.8.1759** (replikativ/datahike#921). EACL still uses derived composite
   tuples for relation and permission definitions. Relationship tuples are
   explicit heterogeneous values and are not derived.

2. `index-range`'s `:attrid` is the one accessor that does **not** accept the
   attribute keyword in both modes: under `:attribute-refs?` it demands the
   numeric ref and raises. `db/avet-range` resolves it via `db/attr-repr`.
   (`datoms` and `seek-datoms` take the keyword in either mode.)

3. Anything comparing a datom's `:a` directly against a keyword matches
   nothing under `:attribute-refs?`. The adapter avoids raw comparisons and
   resolves the attribute representation where Datahike's `index-range`
   requires it.

MEASURED, both modes: `(:attribute-refs? (dbi/-config db))` is the reliable
flag. Concrete DB records also expose `:config` directly; temporal/filter
wrappers do not, and instead delegate `IDB/-config` to their origin database.

## Composite schema-tuple seek bounds

MEASURED on datahike 0.8.1759, in BOTH modes:

```clojure
seek [:room :owner]        → [:kb :reader :party]   ; bound IGNORED
seek [:room :owner nil]    → [:room :owner :party]  ; correct
seek [:room :owner :party] → [:room :owner :party]  ; correct
```

A partial composite tuple is therefore not a reliable seek bound. Datahike
0.8.1759 does carry an `AsOfDB` wrapper's temporal context into
`seek-datoms`, but its full-tuple temporal seek can position after a
historically visible tuple that was retracted later. This was reproduced with
an exact `(entity, attribute)` `datoms` read returning the old relationship
while the equivalent lower-bound seek started in a later index segment.

Concrete and retained-commit DB values consequently use padded prefix seeks.
Temporal/filter wrappers use exact attribute- or endpoint-bounded `datoms`
followed by prefix filtering. Those wrapper reads remain bounded by schema size
for definitions and by one endpoint for relationships. Attribute guards on the
seek path resolve the configured representation through Datahike's database
protocol, which also works when attributes are numeric refs.

The hot relationship paths use explicit heterogeneous values through
endpoint-local EAVT seeks or full-length AVET range bounds. Every prefix seek
checks the appropriate entity/attribute index boundary because Datahike seeks
continue into the next segment when no value matches the requested prefix.

## A coverage trap worth knowing about

The shared contract goes through `make-client`, whose v8 adapter serves
relation definitions from a prebuilt schema catalog. So **the contract suite
does not exercise every schema tuple-prefix path**. The bare-db compatibility
path does, and `backend-test` covers it directly.

The schema-prefix and relationship-storage regressions run in both attribute
representations so a keyword-only implementation cannot pass silently.

## Adapter surfaces

`eacl.backend.spi` remains a six-function compatibility map:

`cache-stamp` · `relation-defs` · `permission-defs` · `subject->resources` ·
`resource->subjects` · `direct-match?`

None of them mention datoms, seeks, or attribute ids — those are all private to
this module.

The public v8 client uses `eacl.datahike.backend/snapshot-adapter`. Its
validated operation map adds snapshot identity, object ID conversion,
permission-node discovery, cursor frontier identity, and scoped schema/relation
proofs. Direct-writer stores support authoritative current selection; retained
commit graphs or temporal history support exact reconstruction; managed stores
support causal at-least selection. Authorization graph compilation,
SCC/fixed-point traversal, Relay behavior, and cache validation stay in
`modules/eacl`.
