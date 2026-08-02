# EACL v8 backend modules and upgrade guide

EACL v8 keeps authorization semantics in `modules/eacl` and confines database
access, snapshots, transactions, cursor protection, and cache proofs to an
adapter. Applications depend on one adapter module; backend authors depend on
the core module.

## Choose a module

| Module | Runtime | Consistency and snapshots | Cursors | Cache proof |
| --- | --- | --- | --- | --- |
| `eacl-datomic` | Clojure/JVM | authoritative barrier, local current, causal floor, and exact `d/as-of` | authenticated and encrypted; proof-equivalent current continuation with exact fallback | mutation identity or canonical content over scoped schema and both relationship halves |
| `eacl-datascript` | Clojure and ClojureScript | serialized local head, local current, causal-anchor wait; optional bounded exact registry | authenticated synchronous cursor; proof-equivalent continuation and optional exact fallback | mutation identity or canonical selected-DB content over both endpoint halves |
| `eacl-datahike` | Clojure/JVM | authoritative direct branch head, local current, causal-anchor wait, retained commit/temporal exact | authenticated synchronous cursor; proof-equivalent continuation and exact fallback | mutation identity or canonical store-visible content |
| `eacl` | Clojure and ClojureScript | supplied by an adapter | supplied by an adapter | portable store/entry validation contract |

Capabilities are configuration-specific. DataScript exact reads require
`:exact-snapshot-registry-size`; Datahike exact reads require a retained commit
graph or temporal history; a lagging Datahike source without a branch-head
barrier rejects `:fully-consistent`. All adapters continue a cursor on a newer
proof-equivalent snapshot and use the authenticated original exact snapshot
only after a proof mismatch.

See [the consistency and cache operations guide](v8-consistency-cache-operations.md)
for authority, retention, token, cursor, cache, and failure-diagnostic rules.

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

DataScript's prerelease relationship storage also changes in this candidate.
It no longer creates a relationship entity with five components and five
derived tuples. It stores exactly two indexed ordinary vector values, one on
each endpoint, using Datomic/Datahike component order. There is deliberately no
dual read or automatic migration for an unreleased representation: recreate
DataScript explorer/demo databases or reload their relationships through EACL.
Direct endpoint retraction can leave a ghost peer value, so call
`delete-object!` first and use
`eacl.datascript.integrity/dangling-relationship-report` for offline checks.

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
`delete-relationships!`, and `delete-object!`. Configure
`:coherence-authority :managed` only when every answer-affecting writer uses
the v3 atomic mutation protocol. Otherwise keep authority `:unknown`; the
default `:proof-mode :auto` then uses canonical full-content proof. Disabling
the cache is sufficient for answer reuse, but it does not manufacture the
causal ancestry required to issue or consume at-least/exact tokens.

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
