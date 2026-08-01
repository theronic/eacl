# EACL v8 backend modules and upgrade guide

EACL v8 keeps authorization semantics in `modules/eacl` and confines database
access, snapshots, transactions, cursor protection, and cache proofs to an
adapter. Applications depend on one adapter module; backend authors depend on
the core module.

## Choose a module

| Module | Runtime | Consistency and snapshots | Cursors | Cache proof |
| --- | --- | --- | --- | --- |
| `eacl-datomic` | Clojure/JVM | current, minimized-latency, freshness floor, and exact/historical | authenticated and encrypted; reconstructs the pinned historical basis | scoped schema definitions and per-relation transaction stamps |
| `eacl-datascript` | Clojure and ClojureScript | `:fully-consistent` current immutable DB only | synchronous opaque token; query- and snapshot-bound | exact schema and relevant relationship content in the selected DB |
| `eacl-datahike` | Clojure/JVM | `:fully-consistent` current immutable DB only | synchronous opaque token; query- and snapshot-bound | exact schema and relevant relationship content visible across connections |
| `eacl` | Clojure and ClojureScript | supplied by an adapter | supplied by an adapter | portable store/entry validation contract |

DataScript and Datahike reject unsupported consistency or historical promises
with `:eacl/unsupported-capability` before running authorization. Their cursors
cannot reconstruct history: after the database snapshot changes, a cursor
raises `:eacl.pagination/invalid-cursor` with reason `:snapshot-changed`.
Datomic instead evaluates a valid cursor against its authenticated historical
basis.

## DataScript and Datahike v7-to-v8 API migration

The DataScript and Datahike ports no longer expose the v7 `:limit`/`:cursor`
list contract. Migrate every `lookup-resources`, `lookup-subjects`, and
`read-relationships` call:

```clojure
;; v7
(eacl/lookup-resources client
  {:subject user
   :permission :view
   :resource/type :document
   :limit 100
   :cursor cursor})
;; => {:data [...] :cursor "..."}

;; v8, forward
(eacl/lookup-resources client
  {:subject user
   :permission :view
   :resource/type :document
   :first 100
   :after end-cursor})
;; => {:data [...]
;;     :page-info {:start-cursor ...
;;                 :end-cursor ...
;;                 :has-next-page? ...
;;                 :has-previous-page? ...}
;;     :cached? false
;;     :cache-basis ...}
```

Use `:last` with optional `:before` for reverse pagination. Do not combine
forward and reverse controls. Empty pages have nil start/end cursors and both
page flags false, so a caller must follow `:has-next-page?` rather than minting
or reusing a meaningless cursor.

Counts no longer page with `:limit` and `:cursor`. By default they count the
complete authorization set. Use `:count-limit n` to cap work:

```clojure
(eacl/count-resources client
  {:subject user
   :permission :view
   :resource/type :document
   :count-limit 1000})
;; => {:count 1000 :limit 1000 :truncated? true ...}
```

`truncated? true` means at least one additional authorized result exists.
Unknown anchors return an empty page or a zero count rather than an unusable
continuation cursor. `delete-object!` is available on all three v8 adapters and
removes relationships touching the object without retracting the object
entity itself.

## Portable cache and mutation rules

DataScript and Datahike create a bounded local cache when `:cache` is omitted.
Pass a shared `eacl.cache/CacheStore` implementation to share entries, or
disable caching explicitly:

```clojure
(require '[eacl.cache :as cache])

(datascript/make-client conn {:cache cache/no-cache})
(datahike/make-client conn {:cache cache/no-cache})
```

Every portable entry is versioned and contains the exact schema proof and
proof of only the relations its permission reads. A hit is returned only when
both proofs match the same immutable DB used by authorization. Unrelated
relationship writes and schema changes outside the compiled permission graph
therefore retain a valid answer; relevant relationship/schema changes and
object deletion force a miss. A missing, throwing, or corrupt store and any
proof/decoding failure safely recompute from the DB.

Prefer `write-schema!`, `create-relationship!`, `write-relationships!`,
`delete-relationships!`, and `delete-object!`. DataScript/Datahike proofs are
derived from canonical database contents, so a raw transaction is visible
only if it atomically maintains every canonical schema or relationship datom
and tuple the adapter reads. If application code cannot make that guarantee,
use `cache/no-cache` for all clients that may observe those out-of-band
writes.

Datomic uses database-visible relation-version stamps instead. Raw Datomic
relationship transactions must come from EACL transaction helpers or include
the corresponding `eacl.datomic.impl/stamp-relation-versions` data in the same
transaction. A custom raw writer that cannot publish those stamps must use
`eacl.datomic.cache/no-cache`. See the Datomic-specific lifecycle section in
the [v8 release notes](release-notes-v8.0.md#schema-and-mutation-lifecycle).

## Recursive permissions and safety controls

All adapters use the same strongly-connected-component analysis and
deterministic fixed-point engine for self-recursive, mutually recursive, and
acyclic permissions that depend on a recursive component. Forward/reverse
results use semantic de-duplication so multiple paths do not duplicate grants.

Each client accepts positive overrides under
`:recursive-traversal-limits`:

```clojure
(datascript/make-client
 conn
 {:recursive-traversal-limits
  {:max-derived-grants 200000
   :max-advanced-datoms 200000
   :max-queued-work 200000}})
```

Every default is `100000` per page computation. Exceeding a ceiling throws
`:eacl.recursive-traversal/limit-exceeded` and identifies
`:derived-grants`, `:advanced-datoms`, or `:queued-work`. Use `:count-limit`
for bounded counts. Raise traversal limits only after representative heap/load
testing; model very broad permissions acyclically when possible.

## Backend extension boundary

The v8 adapter operation map is validated when constructed. It normalizes
object IDs, schema definitions, adjacency, direct matches, permission nodes,
cursor frontier identity, and cache proofs. Backends declare consistency,
snapshot, cursor, transaction, cache-proof, and runtime capabilities
explicitly.

The legacy six-function SPI remains accepted for supported v7 third-party
adapters; it is a compatibility seam, not the complete v8 contract. New v8
adapters should follow [the adapter boundary inventory](v8-backend-adapter-boundary.md)
and run the shared public API, recursive, cache, and independent-oracle
contracts.
