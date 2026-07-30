# Porting the DataScript backend to Datahike

The three source files are a near-mechanical port of `modules/eacl-datascript`
(425 + 505 + 224 lines: `core.cljc`, `impl.cljc`, `schema.cljc`). DataScript is
the right template rather than Datomic, because both DataScript and Datahike
diverge from Datomic in the *same* places — see "Tuple seek bounds" below.

The target is `test/eacl/datahike/contract_test.clj`, which runs the shared
`eacl.contract-support` suite. A backend is done when that passes.

## What actually differs from the DataScript source

| | DataScript | Datahike |
|---|---|---|
| `datoms` / `seek-datoms` / `rseek-datoms` | positional: `(ds/seek-datoms db :avet a v)` | option MAP: `(dh/seek-datoms db {:index :avet :components [a v]})` |
| `index-range` | — | `(dh/index-range db {:attrid a :start s :end e})` |
| `transact` | `ds/transact!`, returns report | `dh/transact`, returns report (NOT a future — no `@`) |
| `entid` | `ds/entid` | **absent** — use `(:db/id (dh/entity db ident))` |
| conn creation | `(ds/create-conn schema)` | `(dh/create-database cfg)` then `(dh/connect cfg)` |
| db value | `@conn` | `(dh/db conn)` or `@conn` |

Datahike additionally needs a real config. Use:

```clojure
{:store {:backend :memory :id (random-uuid)}
 :schema-flexibility :write      ; required: tuple attrs must be declared
 :keep-history? false}
```

## Tuple seek bounds — the one non-obvious thing

MEASURED on datahike main (with #921), in BOTH `:attribute-refs?` modes:

```clojure
seek [:room :owner]       → [:kb :reader :party]   ; bound IGNORED
seek [:room :owner nil]   → [:room :owner :party]  ; correct
seek [:room :owner :party]→ [:room :owner :party]  ; correct
```

So a PARTIAL tuple is not a valid seek bound — identical to DataScript, whose
adapter comments "DataScript sorts vectors by LENGTH FIRST". Datomic is the
outlier here, and `modules/eacl-datomic` relies on Datomic's behaviour.

**Therefore: keep the DataScript approach verbatim** — pad to full tuple arity
with `nil` (`nil` sorts lowest) and then `take-while` on the prefix. Do NOT try
to emulate Datomic's partial-tuple bound, and do not work around it by scanning
the whole attribute segment: padding is correct AND O(log n).

## Attribute representation — why this module needs no `:a` handling

Datahike reports a datom's `:a` as the attribute KEYWORD by default, and as a
numeric ref under `:attribute-refs? true` (Datomic's representation). The engine
above the SPI never sees `:a`, so this is purely internal here — but be
consistent within the module: compare attributes with `=`, never `==`, and
resolve through `attr-ident`-style lookups before consulting schema/rschema.

A composite tuple attribute under `:attribute-refs?` requires datahike ≥ the
#921 fix. Before it, tuples were silently never derived and never validated.

## SPI surface to implement

`eacl.backend.spi` is six fns supplied as a map (see
`modules/eacl/src/eacl/backend/spi.cljc`):

`cache-stamp` · `relation-defs` · `permission-defs` · `subject->resources` ·
`resource->subjects` · `direct-match?`

None of them mention datoms, seeks, or attribute ids — those are all private to
this module.

## Wiring

Add `"modules/eacl-datahike/src"` to the root `deps.edn` `:paths`, the test dir
to `:dev`/`:test`/`:nrepl` `:extra-paths`, and `org.replikativ/datahike` to
`:deps`. A `:build-eacl-datahike` alias mirrors the other modules.
