# The Datahike backend

A port of `modules/eacl-datascript`. DataScript was the right template rather
than Datomic, because DataScript and Datahike diverge from Datomic in the *same*
place — see "Tuple seek bounds" below.

Status: `eacl.datahike.contract-test` runs the shared `eacl.contract-support`
suite (the same one the DataScript backend runs, 23 assertions) **twice**, once
per attribute representation, and passes. `eacl.datahike.backend-test` covers
the datahike-specific paths the shared contract does not reach.

The module is **JVM-only** (`.clj`, not `.cljc`): datahike's ClojureScript API is
asynchronous and the backend SPI is synchronous.

## Layout

Four namespaces, where DataScript has three. `eacl.datahike.db` holds the
adapter primitives — `entid`, attribute representation, and the three index
accessors — so that everything above it reads like the DataScript source and
every datahike divergence lives in one file:

| | |
|---|---|
| `db.clj` | adapter primitives (no eacl concepts) |
| `schema.clj` | attribute declarations, `create-conn`, `write-schema!` |
| `impl.clj` | the SPI implementation and relationship scans |
| `core.clj` | `IAuthorization`, `make-client`, cursor coercion |

## What differs from the DataScript source

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

1. A composite tuple attribute under `:attribute-refs?` needs datahike
   **>= 0.8.1759** (replikativ/datahike#921). Before that fix the tuples were
   silently never derived and never validated, and since the tuples ARE the v7
   engine, every permission check denied.

2. `index-range`'s `:attrid` is the one accessor that does **not** accept the
   attribute keyword in both modes: under `:attribute-refs?` it demands the
   numeric ref and raises. `db/avet-range` resolves it via `db/attr-repr`.
   (`datoms` and `seek-datoms` take the keyword in either mode.)

3. Anything comparing a datom's `:a` against a set of keywords matches nothing
   under `:attribute-refs?`. In this module that is `core/schema-transaction?`,
   the tx listener that evicts cached permission paths. Left unresolved it
   fails **OPEN** — `can?` keeps answering from pre-change permission paths and
   grants what the schema has just revoked. `db/attr-ident` resolves it.

MEASURED, both modes: `(:attribute-refs? (:config db))` is the reliable flag.

## Tuple seek bounds — the one non-obvious ordering fact

MEASURED on datahike 0.8.1759, in BOTH modes:

```clojure
seek [:room :owner]        → [:kb :reader :party]   ; bound IGNORED
seek [:room :owner nil]    → [:room :owner :party]  ; correct
seek [:room :owner :party] → [:room :owner :party]  ; correct
```

A PARTIAL tuple is not a valid seek bound — identical to DataScript, whose
adapter comments "DataScript sorts vectors by LENGTH FIRST". Datomic is the
outlier, and `modules/eacl-datomic` relies on Datomic's behaviour.

So `db/seek-tuple-prefix` pads to full tuple arity with `nil` (`nil` sorts
lowest) and then takes the matching prefix. Padding is both correct AND
O(log n) — do not replace it with a scan of the whole attribute segment.

`seek-datoms` also runs off the end of the attribute into the next one, so the
`take-while` guards on `:a` as well as on the value.

## A coverage trap worth knowing about

The shared contract goes through `make-client`, which serves relation
definitions from a prebuilt schema catalog — a full index scan. So **the
contract suite never exercises the tuple seek at all**: removing the nil
padding entirely leaves it green. The seek is reached only by the bare-db path
(`impl/can?` with no options) and by the relation-retraction guard, which is
what `backend-test` tests directly.

Both mechanisms above were verified non-vacuously by breaking them:
removing the padding fails 12 assertions in both modes; comparing `:a` raw
fails exactly 1, only under `:attribute-refs?`.

## SPI surface

`eacl.backend.spi` is six fns supplied as a map (see
`modules/eacl/src/eacl/backend/spi.cljc`):

`cache-stamp` · `relation-defs` · `permission-defs` · `subject->resources` ·
`resource->subjects` · `direct-match?`

None of them mention datoms, seeks, or attribute ids — those are all private to
this module.
