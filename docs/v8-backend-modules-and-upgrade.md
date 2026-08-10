# EACL v8 backend modules and upgrade guide

EACL v8 keeps authorization semantics and the current-generation cache
protocol in `modules/eacl`. Database access, immutable snapshot selection,
transactions, exact-snapshot recovery, and cursor protection remain adapter
responsibilities. Applications depend on one adapter module; backend authors
depend on the core module.

## Choose a module

| Module | Runtime | Consistency and snapshots | Cursors | Completed answers |
| --- | --- | --- | --- | --- |
| `eacl-datomic` | Clojure/JVM | local Peer DB by default; explicit sync barrier, causal floor, and exact `d/as-of` | encrypted; current recovery or explicit exact continuation | private exact-current plus optional managed relation stamps |
| `eacl-datascript` | Clojure and ClojureScript | one current immutable DB per request; no EACL history registry or exact selection | compact authenticated cursor; proof-equivalent current continuation only | private immutable-DB generation plus optional managed stamps |
| `eacl-datahike` | Clojure/JVM | local connection DB; explicit head barrier and retained exact selection | compact authenticated cursor; current recovery or explicit exact continuation | private immutable-DB generation plus optional managed stamps |
| `eacl` | Clojure and ClojureScript | supplied by an adapter | supplied by an adapter | shared private current-cache implementation |

Capabilities are configuration-specific. DataScript does not support exact
reads and rejects the removed `:exact-snapshot-registry-size` option. Datahike
exact reads require retained commit/temporal history. Ordinary calls use one
selected immutable snapshot and do not perform historical selection.
DataScript cursors may continue only on a proof-equivalent current DB; they do
not silently restart after a relevant change. Only backends advertising
`at-exact-snapshot` may use retained history.

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

## Current-generation cache and mutation rules

All three adapters create a bounded client-private cache when `:cache` is
omitted. Disable caching explicitly:

```clojure
(require '[eacl.cache :as cache])

(datascript/make-client conn {:cache cache/no-cache})
(datahike/make-client conn {:cache cache/no-cache})
```

Exact-current entries are attached to one immutable selected DB generation.
They need no content proof: a changed generation cannot hit the old
generation. Under explicit `:coherence-authority :managed`, a second tier can
survive unrelated forward transactions by keying complete answers with the
physical schema generation and complete canonical vector of physical relation
generations for the compiled dependency closure. Relevant writes replace at
least one vector element; unrelated writes leave the vector unchanged. Empty
complete closures remain eligible under the schema generation alone.

Prefer `write-schema!`, `create-relationship!`, `write-relationships!`,
`delete-relationships!`, and `delete-object!`. Configure
`:coherence-authority :managed` only when every answer-affecting writer uses
the v8 atomic stamp protocol. Otherwise keep authority `:unknown`; EACL then
uses exact-current caching and invalidates on every new immutable DB
generation. Schema replacement drops all managed answers. Reset, restore,
branch force, or unstamped bulk repair requires quiescence followed by the
backend's `expire-cache!`.

An upgraded database must run the backend's `prepare-cache-coherence!` before
managed v4 readers start. Because this protocol is landing before v8 is
released, the supported upgrade is a quiesced cutover, not a rolling
dual-write bridge: stop every writer and managed reader, deploy v4 everywhere,
replace the safe-retraction function, prepare and verify all native
generations, rotate shared lifecycle/token state, attest that only stamped v4
writers can resume, and then restore traffic. Database diagnostics prove
stored preparation but cannot prove that an old process is no longer able to
write. Legacy graph and journal datoms may remain inert; do not destructively
remove them during the cutover. Rolling back to an older binary likewise
requires quiescence, rebaselining that binary's legacy state, and another
lifecycle rotation.

Datahike and DataScript preparation also initializes
`:eacl/schema-write-fence`, a small mutation-linearization token kept separate
from `:eacl/schema-generation`. Their old-equals-old CAS implementations may
reassert a datom, and predicate bookkeeping must not rotate the physical cache
generation or invalidate every schema-derived entry.

The optional `:eacl.fn/retractEntity` is target-only in v4. Multiple and
repeated invocations compose in one transaction, and a known numeric retracted
eid can repair peer-only ghosts. Quiesce callers during the arity cutover from
the legacy envelope-based function; do not mix same-transaction relationship
additions involving a retracted target.

Caller-supplied portable stores are not trusted as an authority for completed
native answers. Exact/historical cursor work and arbitrary low-level `db`
operations bypass the completed-answer cache.

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
cursor frontier identity, and cache stamps. Backends declare consistency,
snapshot, cursor, transaction, cache, and runtime capabilities
explicitly.

EACL v8 accepts only the v8 adapter operation map. Third-party adapters must
follow [the adapter boundary inventory](v8-backend-adapter-boundary.md) and run
the shared public API, recursive, cache, and independent-oracle contracts.
