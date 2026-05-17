# Reverse Pagination With Relay-Style Page Info

**Date:** 2026-05-15  
**Status:** Implemented on branch `eacl/reverse-pagination`  
**Scope:** API and implementation plan for bidirectional pagination across EACL list-returning APIs.  
**Review incorporated:** [2026-05-15-reverse-pagination-plan-critique.md](../reports/2026-05-15-reverse-pagination-plan-critique.md)

## Context

Issue [theronic/eacl#12](https://github.com/theronic/eacl/issues/12) identified the original limitation: returning a previous page is hard without reverse index access, so callers either keep a stack of seen cursors or re-read/drop from the beginning.

Datomic Pro `1.0.7622` adds `datomic.api/rseek-datoms`, a reverse index iteration API and the complement to `seek-datoms`. The Datomic Pro changelog currently lists `1.0.7622` with release date `2026/04/28`; the operator prompt says `2024-04-28`. Implementation must key off API availability and peer version, not the date.

The current repo already has the pieces that make a clean redesign possible:

- `src/eacl/datomic/schema.clj` stores relationships as forward and reverse v7 tuple attributes.
- `src/eacl/datomic/impl/indexed.clj` has direction-aware lookup plumbing for `lookup-resources` and `lookup-subjects`.
- `src/eacl/datomic/core.clj` already makes pagination tokens opaque at the public API boundary.
- `src/eacl/lazy_merge_sort.clj` already handles lazy sorted dedupe for multiple sorted path streams.

## REPL Validation Notes

Initial validation run on 2026-05-15 through project nREPL:

- Current project deps load Clojure `1.12.0-alpha5`.
- With current `com.datomic/peer "1.0.6733"`, `(resolve 'datomic.api/seek-datoms)` is present and `(resolve 'datomic.api/rseek-datoms)` is absent. The Datomic peer upgrade is therefore a hard prerequisite, not a cleanup task.
- On an in-memory Datomic db, `d/entid` against `(d/as-of live-db old-basis-t)` can resolve an entity whose unique identity attribute was later retracted, while the live db returns nil. This confirms that stable pagination must coerce public ids against the token's `as-of` db.
- In the same check, `(d/basis-t as-of-db)` returned the live db basis, not the historical `old-basis-t` used to construct the `as-of` db. Page-token creation must carry the decoded `page-basis-t` explicitly and must not recompute it from the `as-of` db value.
- `(.id live-db)` and `(.id as-of-db)` were equal. Cache keys based only on database id cannot distinguish historical schema states; the schema fingerprint requirement is necessary.
- `AGENTS.md` should use EACL test namespaces only. EACL validation should run through nREPL with `eacl.datomic.impl.indexed-test`, `eacl.spice-test`, `eacl.datomic.schema-test`, `eacl.datomic.config-test`, `eacl.datomic.parser-test`, and focused benchmark namespace `eacl.bench.pagination-test`.

Post-upgrade validation on branch `eacl/reverse-pagination`:

- `deps.edn` now uses `org.clojure/clojure "1.11.4"` and `com.datomic/peer "1.0.7622"`.
- Fresh nREPL with the upgraded classpath reports Clojure `1.11.4`, `(resolve 'datomic.api/seek-datoms)` true, and `(resolve 'datomic.api/rseek-datoms)` true.
- Raw Datomic smoke test using EACL-shaped v7 tuple attributes confirmed:
  - `d/seek-datoms` over `:eavt` returns relationship tuple resources in ascending eid order.
  - `d/rseek-datoms` over the same `:eavt` tuple attr returns descending eid order.
  - exclusive reverse boundaries work by comparing the full datom/key and dropping the boundary datom, not by eid arithmetic.
- `d/rseek-datoms` works against an `(d/as-of live-db basis-t)` value for the same tuple attr. Historical reverse scans exclude tuples transacted after the token basis, while live reverse scans include them.
- The upgraded peer preserves the previous basis caveat: `(d/basis-t as-of-db)` reports the live basis. Page-token creation must continue to carry explicit `page-basis-t`.
- `(.id live-db)` and `(.id as-of-db)` remain equal after the upgrade, so schema-fingerprint-aware permission-path cache keys remain required.
- Regular EACL nREPL suites pass under the upgraded peer:
  - `eacl.datomic.impl.indexed-test`: 20 tests / 227 assertions.
  - `eacl.spice-test`: 3 tests / 62 assertions.
  - `eacl.datomic.schema-test`: 5 tests / 35 assertions.
  - `eacl.datomic.parser_test`: 2 tests / 18 assertions.
  - `eacl.datomic.config-test`: 1 test / 7 assertions.
- Existing pagination benchmark/load namespace passes under the upgraded peer:
  - `eacl.bench.pagination-test`: 1 benchmark-marked test / 6 assertions.
  - 15,000-server multi-path fixture: first page median 0.90 ms, 20-page pagination median 17.50 ms total / 0.88 ms per page on this machine.

Implementation validation after reverse pagination landed:

- `eacl.datomic.impl.indexed-test`: 21 tests / 223 assertions.
- `eacl.spice-test`: 3 tests / 78 assertions.
- `eacl.datomic.config-test`: 1 test / 7 assertions.
- `eacl.datomic.schema-test`: 5 tests / 35 assertions.
- `eacl.datomic.parser_test`: 2 tests / 18 assertions.
- `eacl.bench.pagination-test`: 1 benchmark-marked test / 6 assertions.
- Latest benchmark run on the same 15,000-server fixture: first page median 1.20 ms; 20-page pagination median 24.03 ms total / 1.20 ms per page.
- nREPL smoke check confirms Clojure `1.11.4` and `(resolve 'datomic.api/rseek-datoms)` true.

Appendix prompt review after implementation:

- The public list API uses `:first/:after` and `:last/:before`; `:cursor` and `:limit` list requests are rejected.
- `lookup-resources`, `lookup-subjects`, and `read-relationships` return `{:data ... :page-info ...}` with start/end cursors and previous/next booleans.
- Reverse traversal in indexed relationship scans uses `d/rseek-datoms`; page results preserve the same ascending result order for forward and backward requests.
- `read-relationships` uses Datomic datom-key cursors over the selected at-rest index scan and does not sort decoded relationships before paging.
- Public page tokens are opaque encrypted/authenticated `eacl3_` tokens, query-specific, basis-stable, and keyring-aware.
- Stable page requests coerce public ids and output ids against the token's `as-of` db, and permission-path caching is schema-fingerprint scoped.
- The eacl-explorer-style workflow no longer needs to retain a long cursor stack: tests page forward with `:after end-cursor` and backward with `:last ... :before start-cursor`.

## De Facto API Decision

`:before` and `:after` are standard cursor pagination arguments, but the de facto bidirectional standard is the Relay cursor connection model:

- forward pagination uses `:first` plus `:after`
- backward pagination uses `:last` plus `:before`
- responses expose page metadata with start/end cursors and previous/next booleans
- result order stays the same for forward and backward requests

EACL should adopt a Clojure-shaped Relay-compatible subset:

```clojure
;; First page.
(eacl/lookup-resources acl
  {:subject subject
   :permission :view
   :resource/type :server
   :first 50})

;; Next page.
(eacl/lookup-resources acl
  {:subject subject
   :permission :view
   :resource/type :server
   :first 50
   :after (get-in page1 [:page-info :end-cursor])})

;; Previous page.
(eacl/lookup-resources acl
  {:subject subject
   :permission :view
   :resource/type :server
   :last 50
   :before (get-in page2 [:page-info :start-cursor])})
```

Response shape:

```clojure
{:data [...]
 :page-info {:start-cursor opaque-token-or-nil
             :end-cursor opaque-token-or-nil
             :has-next-page? true-or-false
             :has-previous-page? true-or-false}}
```

Do not return authoritative top-level `:before` and `:after` fields. Those names are request filters. The page's actual bounds are `:start-cursor` and `:end-cursor`.

## Goals

- Users can paginate forward and backward from the current page response only.
- UIs do not need to retain a long list of seen cursors.
- The API follows common cursor-pagination terminology.
- The same canonical order is returned whether the request is forward or backward.
- Reverse traversal uses `d/rseek-datoms` in indexed hot paths.
- Pagination remains lazy and does not materialize full relationship indexes.
- No backward compatibility or migration path is required.

## Non-Goals

- Do not preserve `:cursor`, v1 cursor maps, or v2 `{:v 2 :e ... :p ...}` public semantics.
- Do not support arbitrary range requests with both `:after` and `:before` in the first implementation.
- Do not let count APIs define the list pagination contract.
- Do not make recursive permission lookup as fast as acyclic indexed traversal in this work. Make it API-correct and document its performance limits.

## Public API Contract

### Request Rules

List-returning APIs accept exactly one page size key:

- `:first n` for forward pagination
- `:last n` for backward pagination

Cursor argument rules:

- `:after` is valid only with `:first`
- `:before` is valid only with `:last`
- neither cursor argument means the beginning for `:first`, or the end for `:last`
- `:cursor` is rejected
- `:limit` is rejected for list pagination, because this is a development-phase breaking redesign
- `:first` and `:last` together are rejected
- `:before` and `:after` together are rejected
- page sizes must be positive integers; zero and negative sizes are rejected
- sizes above the configured maximum are rejected

Relay's formal algorithm can apply both `before` and `after` as range filters, and only discourages combining `first` and `last`. EACL should intentionally reject those ambiguous combinations in the first implementation. This is a Relay-compatible pagination subset, not a full GraphQL connection implementation.

Default list page size:

- if neither `:first` nor `:last` is supplied, treat it as `{:first 1000}`
- this preserves the current "first page by default" behavior without preserving the old `:limit` name

### Response Rules

All paginated list APIs return:

```clojure
{:data [...]
 :page-info {:start-cursor token-or-nil
             :end-cursor token-or-nil
             :has-next-page? boolean
             :has-previous-page? boolean}}
```

Response invariants:

- `:start-cursor` is the opaque cursor for the first returned edge.
- `:end-cursor` is the opaque cursor for the last returned edge.
- both cursors are nil when `:data` is empty.
- `:has-next-page?` and `:has-previous-page?` are mandatory.
- page flags are computed after lazy merge and dedupe.
- result order is canonical ascending order for both forward and backward requests.

### API Surface

Apply the contract to every API that returns a paginated list:

- `eacl/lookup-resources`
- `eacl/lookup-subjects`
- `eacl/read-relationships`

Count helpers are not list APIs. They should be handled after the list contract is correct:

- `eacl/count-resources`
- `eacl.datomic.impl.indexed/count-resources`
- `eacl.datomic.impl.indexed/count-subjects`

For counts, either remove pagination from the public count contract or define it separately as range counting with `:after`/`:before`. Do not return list `:page-info` from count APIs unless a concrete caller workflow requires iterative count progress.

`count-subjects` appears in README/docs and exists in `impl.indexed`, but it is not currently part of `IAuthorization` or re-exported from `eacl.datomic.impl`. Decide during implementation whether to expose it properly or remove stale documentation.

## Database Basis Policy

Use stable pagination by default.

- First page tokens include the database `basis-t` used to produce the page.
- Public client calls that receive a token must decode and validate the token, choose the pagination db, and only then coerce public object ids to Datomic eids.
- Public client calls that receive a stable token use `(d/as-of (d/db conn) basis-t)` before object-id coercion, traversal, page-token construction, and output id coercion.
- Direct internal calls that already receive a stable `db` validate the token's `basis-t` against that db where possible.
- The first implementation rejects live pagination with `ex-info`; live pagination can be designed later as a separate, intentionally shifting mode.

Accepted query option:

```clojure
{:page/basis :stable} ; default
```

Stable mode gives repeatable bidirectional pages across intervening writes. Real-time UIs that want newest data can refresh from the first page when they observe a write. Do not add a live mode until there is a separate design for its shifted-boundary semantics and page flag guarantees.

This requires refactoring the current `spiceomic-*` wrappers. Today `spiceomic-lookup-resources` and `spiceomic-lookup-subjects` coerce public ids before cursor decoding. That is wrong for stable pagination: a subject/resource that existed at the page basis may be deleted or changed in the live db. The new order must be:

1. decode and verify page token, if present
2. choose stable `(d/as-of live-db basis-t)` or live db
3. coerce query object ids using the chosen db
4. execute indexed traversal using the chosen db
5. coerce returned internal ids using the same chosen db
6. encrypt/authenticate page tokens with the same basis metadata

For a first stable request, capture `page-basis-t` from the live db before traversal, using `(d/basis-t live-db)`. For a token request, the decoded token's `:basis-t` is authoritative: construct `(d/as-of live-db basis-t)` and reuse that same `basis-t` in any new page tokens. Do not recompute the token basis from a newer live db while serving page 2, and do not rely on ambiguous `d/basis-t` behavior of an `as-of` db value. The implementation must not use a live db for output coercion when returning a stable page; entities valid at the page basis may no longer exist in the live db.

## Token Policy

Replace the current base64 EDN cursor tokens with authenticated encrypted page tokens.

Token envelope shape before base64url encoding:

```clojure
{:v 3
 :kid key-id
 :nonce random-nonce
 :ciphertext encrypted-authenticated-bytes}
```

The envelope fields are not secret, but they must be authenticated as AES-GCM additional authenticated data or equivalent.

Encrypted plaintext payload shape:

```clojure
{:v 3
 :op :lookup-resources
 :query-shape query-shape-hash
 :order [:eid :asc]
 :basis-t basis-t-or-nil
 :basis :stable-or-live
 :path-fingerprint permission-path-fingerprint-or-nil
 :edge edge-cursor
 :exp epoch-seconds}
```

Cryptographic requirements:

- Use authenticated encryption (AEAD, e.g. AES-GCM) over the serialized payload by default. HMAC-only signing prevents tampering but still exposes internal eids, relation eids, query hashes, and basis values to anyone who can decode base64.
- `make-client` accepts a `:page-token-key` option for production and multi-peer deployments.
- Tokens include a key id, e.g. `:kid`, so deployments can rotate token keys without immediately invalidating every in-flight page.
- Tokens include a fresh random nonce per encryption operation. Never reuse an AES-GCM nonce with the same key.
- If no key is provided, generate a process-local development key and document that tokens become invalid after process restart and across peers.
- Never hardcode an encryption key in source.
- Invalid authentication tags, expired tokens, or mismatched query shapes throw `ex-info` with actionable data.
- Use EDN only inside the encrypted/authenticated envelope, and parse with `clojure.edn/read-string`, not `clojure.core/read-string`.

Token validation must reject:

- wrong `:op`
- wrong `:query-shape`
- wrong `:order`
- wrong `:basis` mode
- stale or mismatched `:path-fingerprint`
- malformed edge cursor
- expired token

If EACL intentionally chooses readable-but-signed tokens for same-process trusted use, the plan must record that as a conscious downgrade and document that tokens are tamper-resistant but not confidential. The recommended production default is authenticated encryption.

Because tokens are encrypted, token payloads should keep internal Datomic eids and relation eids. Do not convert token internals to external object ids. External id conversion remains only for query inputs and `:data` outputs. This keeps tokens compact, avoids stable-basis lookup errors during token construction, and prevents callers from depending on token contents.

Remove the current public cursor conversion extension points (`:internal-cursor->spice` and `:spice-cursor->internal`) from the page-token path. They are part of the old readable cursor design and would either leak internal cursor structure or make stable-basis token construction depend on live-db id coercion. Page-token encoding is owned by EACL; applications customize object id coercion for query inputs and `:data` outputs only.

## Query Shape

Cursor tokens must be reusable with a different page size in the same ordered result set. The query shape hash must therefore exclude pagination controls:

- exclude `:first`, `:last`, `:before`, `:after`, and old rejected keys such as `:cursor` and `:limit`
- exclude `:page/basis` after it has been validated separately
- include operation, subject/resource anchors, permission, result type, relationship filters, consistency/basis mode, and any explicit sort/order option
- compute the hash after public object ids have been normalized to internal Datomic eids using the selected pagination db
- build the hash from a canonical representation, such as sorted-map/vector data, not from arbitrary map iteration order

This prevents a cursor from becoming unusable only because the user changes page size from 50 to 100, while still rejecting reuse across a different subject, permission, resource type, or relationship filter.

## Internal Edge Model

All paginated internals should move from "streams of eids" to "streams of edges."

Generic edge:

```clojure
{:node result-eid-or-relationship
 :cursor edge-cursor
 :frontier path-frontier}
```

For public list responses:

- `:data` is `(map :node page-edges)` after coercion to public objects.
- `:start-cursor` is the encrypted/authenticated token for the first edge cursor.
- `:end-cursor` is the encrypted/authenticated token for the last edge cursor.

Lookup edge cursor:

```clojure
{:kind :lookup
 :result-eid eid
 :path-frontiers {:asc {stable-path-id path-frontier}
                  :desc {stable-path-id path-frontier}}}
```

Path frontier:

```clojure
{:path-id stable-path-id
 :result-eid eid
 :intermediate-eid eid-or-nil
 :subpath-id stable-subpath-id-or-nil}
```

The exact frontier can evolve, but it must be enough to resume a path without rescanning exhausted intermediate work.

The `:path-frontiers` map is bounded by permission-path count, not by page count or relationship count. This is the critical difference between EACL page tokens and the UI workaround this plan replaces: the caller no longer stores every seen cursor, while the server still carries enough per-path traversal state to avoid deep re-scans.

When a page edge is produced by one path but other paths were advanced or exhausted while reaching that edge, the edge cursor must include frontier state for all advanced paths, not only the path that emitted the edge. Otherwise forward and backward pagination would be correct only by falling back to global eid seeks and could regress on deep arrow traversals.

Edge cursors must be direction-neutral. A `:start-cursor` produced by a forward request is normally used as `:before` in a backward request, and an `:end-cursor` produced by a backward request is normally used as `:after` in a forward request. Therefore cursor construction must either:

- include both `:asc` and `:desc` bounded path-frontier maps, or
- compute the missing opposite-direction frontier with a bounded per-path probe before token creation.

It is acceptable for correctness to fall back to the boundary `:result-eid`, but it is not acceptable for the intended performant implementation to require scanning from the beginning/end of deep intermediate paths when moving one page backward from a page that was originally fetched forward.

## Path Identity And Fingerprints

Path IDs must be stable for the same schema and query. Do not rely only on incidental vector order.

Construct path IDs from resolved path content:

- path type
- relation eids and names
- resource type
- subject type
- target permission or target relation
- nested subpath identity

`path-fingerprint` should be a deterministic hash of all path IDs and resolved relation eids for the permission query. It changes when schema or relation identity changes.

Canonicalize the fingerprint input as ordered data. Do not hash raw maps whose printed key order could vary across JVMs, processes, or Clojure versions.

Cached resolved permission paths already include database-local relation eids, so cache keys must be scoped to both database identity and schema identity. The current cache key `[(:id db) resource-type permission-name]` is not sufficient for stable `as-of` pagination: a token may point at an old basis whose schema differs from the live db, while the cache could return paths compiled for the newer schema.

Replace the cache key with:

```clojure
[database-id schema-fingerprint resource-type permission-name]
```

`schema-fingerprint` should be computed from relation and permission schema data in the selected pagination db. It can be cached separately by database id and schema basis/hash. Schema writes must still evict same-database cached entries, but token validation must not rely on eviction alone.

Add a regression test:

- create page1 under schema A
- write schema B that changes permission paths
- request page2 with page1's stable token
- assert traversal uses schema A via token basis and does not reuse schema B cached paths

## Ordering Semantics

Keep one canonical output order:

- `lookup-resources`: ascending internal resource eid
- `lookup-subjects`: ascending internal subject eid
- `read-relationships`: ascending Datomic datom key for the selected scan

Backward requests scan descending internally, but responses are reversed back to canonical ascending order before returning.

Relay order invariant:

- The order of results must be the same for `:first/:after` and `:last/:before`.
- For `:before`, the result closest to the cursor appears last in the returned `:data`.
- For `:after`, the result closest to the cursor appears first in the returned `:data`.

## Scan Semantics

Use `seek-datoms` for ascending scans and `rseek-datoms` for descending scans.

Do not use `d/index-range` for paginated list scans that need reverse traversal. It has no matching reverse primitive in the current code path; bidirectional scans should be expressed through `seek-datoms` and `rseek-datoms` plus explicit range predicates.

Do not implement generic exclusivity by arithmetic `(inc eid)` or `(dec eid)`. That only works for simple integer final tuple components and fails for full datom keys, relationship cursors, path frontiers, and boundary values.

Generic scan primitive:

```clojure
(defn seek-page-datoms
  [db {:keys [index components direction exclusive-key in-range? datom-key]}]
  (let [datoms (case direction
                 :asc  (apply d/seek-datoms db index components)
                 :desc (apply d/rseek-datoms db index components))]
    (->> datoms
         (take-while in-range?)
         (drop-while #(= exclusive-key (datom-key %))))))
```

Allow optimized fast paths only after the generic comparator-based behavior is tested:

- simple eid-only ascending scans may start at `(inc eid)` when safe
- simple eid-only descending scans may start at `(dec eid)` only when safe
- the correctness path must never depend on synthesizing predecessor or successor keys

Both `seek-datoms` and `rseek-datoms` require caller-supplied termination logic. Every scan must provide an `in-range?` predicate.

## Merge And Dedupe Semantics

Multi-path permission graphs can produce duplicate resources or subjects. Reverse pagination must dedupe after merge, not before page slicing.

Invariants:

- per-path scans produce sorted edge streams in requested scan direction.
- lazy merge orders edges by canonical result key in requested scan direction.
- lazy dedupe removes duplicate canonical result keys after merge.
- `page-size + 1` is applied after merge and dedupe.
- the sentinel edge determines `:has-next-page?` or `:has-previous-page?`.
- backward pages are reversed only after final page slice selection.

Add explicit tests where a duplicate appears:

- within a page
- exactly at a page boundary
- as the sentinel candidate

## Relationship Read Cursor Design

`read-relationships` cannot reuse lookup edge cursors. It must store the actual datom key for the selected scan.

Subject-anchored cursor:

```clojure
{:kind :relationship
 :scan :subject
 :index :eavt
 :e subject-eid
 :a forward-relationship-attr-eid
 :v [subject-type relation-eid resource-type resource-eid]}
```

Resource-anchored cursor:

```clojure
{:kind :relationship
 :scan :resource
 :index :eavt
 :e resource-eid
 :a reverse-relationship-attr-eid
 :v [resource-type relation-eid subject-type subject-eid]}
```

Global cursor:

```clojure
{:kind :relationship
 :scan :global
 :index :avet
 :a forward-relationship-attr-eid
 :v [subject-type relation-eid resource-type resource-eid]
 :e subject-eid}
```

`read-relationships` should choose the narrowest available scan:

- subject id: `:eavt` over the forward tuple attr
- resource id: `:eavt` over the reverse tuple attr
- no entity anchor: `:avet` over the forward tuple attr

Relationship filter predicates remain, but scan bounds should be as narrow as the query allows before filtering.

The query shape and tests must account for every documented `read-relationships` filter. In particular, `src/eacl/core.clj` currently mentions `:resource/id-prefix`; either implement it as a real filter and include it in query-shape validation, or remove it from the protocol docs during this redesign. Do not leave a documented filter that is ignored while page tokens claim query-specific correctness.

For unanchored relationship reads with selective filters, a single broad `:avet` attr scan can be correct but too expensive. The implementation should prefer bounded streams over resolved relation descriptors when filters identify resource type, relation name, or subject type:

- build one stream per resolved relation descriptor
- each stream scans the forward tuple attr for that relation's tuple prefix
- merge streams by the canonical global relationship datom key
- dedupe by full relationship identity
- store relationship stream frontiers in the edge cursor when more than one stream is active

This mirrors lookup pagination: correctness comes from the canonical merged order, while performance comes from narrow per-relation streams.

## TDD Implementation Plan

### Phase 0. Baseline And Version Gate

- [x] Start from nREPL, per `AGENTS.md`: `clj-nrepl-eval --discover-ports`.
- [x] If needed, start `clojure -M:dev:nrepl`.
- [x] Run the current indexed tests through nREPL:

```clojure
(require 'eacl.datomic.impl.indexed-test :reload)
(clojure.test/run-tests 'eacl.datomic.impl.indexed-test)
```

- [x] Run the public API tests through nREPL:

```clojure
(require 'eacl.spice-test :reload)
(clojure.test/run-tests 'eacl.spice-test)
```

- [x] Update `deps.edn` from `com.datomic/peer "1.0.6733"` to a version exposing `d/rseek-datoms`, expected `1.0.7622` or newer.
- [x] Update `org.clojure/clojure` to a stable version compatible with the chosen Datomic peer. Datomic Pro `1.0.7364` and later require Clojure `1.11.4` or greater; avoid relying on the current `1.12.0-alpha5`.
- [x] Add a REPL smoke assertion that `(resolve 'datomic.api/rseek-datoms)` is present.
- [x] Add a REPL smoke test that `d/rseek-datoms` works against the v7 tuple attributes on an `as-of` db value.
- [x] Record any transactor, peer, storage-protocol, or dependency compatibility issue before code changes. The current `deps.edn` comment explicitly says the newer transactor was uncertain, and newer Datomic versions have dependency changes that may affect non-memory deployments.

### Phase 1. Red Public Contract Tests

- [ ] Update token tests in `test/eacl/spice_test.clj` from cursor terminology to page-info terminology.
- [ ] Assert list responses include `:page-info`.
- [ ] Assert list responses do not include `:cursor`, top-level `:before`, or top-level `:after`.
- [ ] Assert `:page-info` contains mandatory `:start-cursor`, `:end-cursor`, `:has-next-page?`, and `:has-previous-page?`.
- [ ] Assert `:start-cursor` and `:end-cursor` are opaque strings or nil.
- [ ] Assert passing `:cursor` throws.
- [ ] Assert passing `:limit` to list APIs throws.
- [ ] Assert passing both `:first` and `:last` throws.
- [ ] Assert passing both `:before` and `:after` throws.
- [ ] Assert `:after` with `:last` throws.
- [ ] Assert `:before` with `:first` throws.
- [ ] Assert `:first 0`, `:last 0`, negative sizes, fractional sizes, and oversized requests throw.
- [ ] Assert invalid, expired, tampered, or query-mismatched tokens throw `ex-info`.
- [ ] Assert first forward page has `:has-previous-page? false`.
- [ ] Assert final forward page has `:has-next-page? false`.
- [ ] Assert an empty forward page after the end returns nil cursors, `:has-next-page? false`, and `:has-previous-page? true`.
- [ ] Assert an empty backward page before the beginning returns nil cursors, `:has-previous-page? false`, and `:has-next-page? true`.

### Phase 2. Red `lookup-resources` Forward And Backward Tests

- [ ] Add a direct-relation fixture where four resources are accessible through one path.
- [ ] Page forward with `:first 2`; assert page1 and page2 concatenate to the full ascending result set.
- [ ] From page2, request `:last 2 :before start-cursor`; assert it returns page1 exactly.
- [ ] From page1, request `:first 2 :after end-cursor`; assert it returns page2 exactly.
- [ ] Assert backward page response order is ascending, not descending.
- [ ] Assert no UI-side cursor stack is needed by walking forward and backward using only current `:page-info`.
- [ ] Assert `:start-cursor` from a page fetched with `:first/:after` can be used efficiently with `:last/:before`.
- [ ] Assert `:end-cursor` from a page fetched with `:last/:before` can be used efficiently with `:first/:after`.
- [ ] Add a deep cursor regression based on `test_cursor_pagination.clj` so page2 does not become empty when intermediate eids are higher than resource eids.

### Phase 3. Red Multi-Path And Duplicate Tests

- [ ] Add a multi-path fixture with duplicate resources across relation and arrow paths.
- [ ] Assert forward pagination across the multi-path graph has no duplicates or omissions.
- [ ] Assert backward pagination across the same graph has no duplicates or omissions.
- [ ] Assert duplicate within-page dedupe works.
- [ ] Assert duplicate-at-boundary dedupe works.
- [ ] Assert duplicate-as-sentinel does not produce wrong `:has-next-page?` or `:has-previous-page?`.

### Phase 4. Red `lookup-subjects` Tests

- [ ] Mirror the `lookup-resources` direct-relation forward/backward tests for reverse lookup.
- [ ] Add multi-path subject pagination with duplicate subjects.
- [ ] Assert `lookup-subjects` returns ascending subject order for both forward and backward requests.
- [ ] Assert previous-page navigation uses `:last` and `:before start-cursor`.

### Phase 5. Red `read-relationships` Tests

- [ ] Change `read-relationships` expectations to a page map with `:data` and `:page-info`.
- [ ] Test subject-anchored relationship paging.
- [ ] Test resource-anchored relationship paging.
- [ ] Test global or filter-only relationship paging.
- [ ] Test every retained documented relationship filter, including `:resource/id-prefix` if it remains documented.
- [ ] Include a fixture where two subjects have the same relation to the same resource so the cursor must disambiguate by full datom key.
- [ ] Test forward and backward relationship paging keep canonical datom order.
- [ ] Update callers/tests that pass `read-relationships` into delete helpers to use `(:data page)`.

### Phase 6. Red Basis And Token Security Tests

- [ ] In stable mode, fetch page1, transact a new relationship, then fetch page2 and assert results continue from page1's basis.
- [ ] In stable mode, fetch page1, transact a new relationship, fetch page2, and assert page2's new tokens retain page1's decoded `:basis-t` rather than the newer live basis.
- [ ] In stable mode, fetch page1, retract/delete one of page1's subject/resource entities, then fetch page2 and assert public id coercion still uses the token's `as-of` db.
- [ ] In stable mode, fetch page1, change schema, then fetch page2 and assert permission-path cache lookup uses the old schema fingerprint.
- [ ] Assert `{:page/basis :live}` is rejected with `ex-info` until a separate live-pagination design exists.
- [ ] Assert authenticated encrypted tokens reject payload modification.
- [ ] Assert tokens do not expose internal eids or relation eids by simple base64 decoding.
- [ ] Assert tokens generated with one key are rejected by a client with another key.
- [ ] Assert tokens generated with an old `:kid` are accepted while that key remains configured, and rejected after that key is removed.
- [ ] Assert every generated token uses a fresh nonce.
- [ ] Assert process-local dev keys are documented and invalid across process restart. This can be a doc/test note rather than a full process restart test.
- [ ] Assert encrypted token payloads store internal eids and public response `:data` still uses external object ids.
- [ ] Assert query-shape and path-fingerprint hashes are stable across equivalent maps with different key insertion orders.

### Phase 7. Implement Token And Query Boundary Helpers

- [ ] In `src/eacl/datomic/core.clj`, replace cursor helpers with:
  - `page-token`
  - `token->page-bound`
  - `encrypt-page-token`
  - `decrypt-page-token`
- [ ] Add `:page-token-key` support to `make-client`.
- [ ] Add `:page-token-keys` or `:page-token-keyring` support for key rotation, with a current `:kid`.
- [ ] Generate a process-local development encryption key when no key is provided.
- [ ] Remove raw cursor-map compatibility from token decoding.
- [ ] Remove `:internal-cursor->spice` and `:spice-cursor->internal` from public page-token construction; keep object id customization only for query input and `:data` output coercion.
- [ ] Keep token payloads internal and encrypted; do not convert page token eids to external object ids.
- [ ] Add `normalize-page-request` to enforce the public request rules.
- [ ] Add `query-shape` functions per operation and validate decoded tokens against the current query.
- [ ] Add `pagination-db` or equivalent to choose `(d/as-of live-db basis-t)` for stable token requests.
- [ ] Carry an explicit `page-basis-t` through token creation so later pages reuse the decoded token basis exactly.
- [ ] Refactor `spiceomic-lookup-resources`, `spiceomic-lookup-subjects`, and `spiceomic-read-relationships` so token decoding and pagination db selection happen before public object-id coercion.
- [ ] Compute query shape after public ids are normalized against the pagination db and excluding page size/direction controls.
- [ ] Reject `{:page/basis :live}` until live pagination has a separate testable contract.

### Phase 8. Implement Directional Datom Scan Primitives

- [ ] In `src/eacl/datomic/impl/indexed.clj`, remove `inclusive-cursor->exclusive`, `extract-cursor-eid`, and `build-v2-cursor`.
- [ ] Add `seek-page-datoms` using `d/seek-datoms` for `:asc` and `d/rseek-datoms` for `:desc`.
- [ ] Implement exclusivity by comparing datom page keys and dropping the boundary item.
- [ ] Add `in-range?` predicates for each scan so seek iteration terminates at the query prefix boundary.
- [ ] Replace list-pagination uses of `d/index-range` with direction-capable seek/rseek scans.
- [ ] Write unit tests around direct scan helpers before refactoring graph traversal.
- [ ] Add optimized simple-eid seek starts only after generic behavior is green.

### Phase 9. Generalize Lazy Merge For Reverse Edge Streams

- [ ] Extend `src/eacl/lazy_merge_sort.clj` with a comparator-aware merge/dedupe path, or add explicit ascending and descending edge merge helpers.
- [ ] Preserve the optimized identity/long fast path for ascending pagination.
- [ ] Add a descending identity/long fast path.
- [ ] Add tests for:
  - ascending merge dedupe
  - descending merge dedupe
  - duplicate keys across streams
  - empty streams
  - lazy realization of only `page-size + 1` merged, deduped edges
  - duplicate sentinel correctness

### Phase 10. Refactor Permission Traversal Around Edges

- [ ] Replace bare result eid streams with edge streams.
- [ ] Update `subject->resources` and `resource->subjects` to accept direction and edge bound data instead of cursor eids.
- [ ] Update `traverse-permission-path-via-subject` and `traverse-permission-path-reverse` to thread direction, edge cursors, and path frontiers.
- [ ] Redesign `arrow-via-intermediates` so it returns edges and records the frontier that produced each emitted result.
- [ ] Ensure each emitted lookup edge includes `:path-frontiers` for every path advanced while reaching that edge, not only the emitting path.
- [ ] Make edge cursors direction-neutral by including both `:asc` and `:desc` frontiers, or by deriving the missing opposite-direction frontiers with bounded per-path probes before returning `:page-info`.
- [ ] Keep `:path-frontiers` bounded by resolved permission-path count.
- [ ] Derive stable path IDs from resolved permission path content.
- [ ] Compute `path-fingerprint` from stable path IDs and resolved relation eids.
- [ ] Replace permission-path cache keys with database id plus schema fingerprint plus resource type plus permission name.
- [ ] Ensure stable-token requests after schema writes compile/read paths from the token's `as-of` db, not from the live db cache.
- [ ] Ensure dedupe keeps the first edge in scan order and preserves its cursor/frontier.
- [ ] Remove old v2 `:p` cursor-tree state after tests pass.

### Phase 11. Redesign The Lookup Shell

- [ ] Replace `lazy-merged-lookup` with a shell that accepts:
  - operation id
  - direction config
  - normalized page request
  - query shape
  - path fingerprint
- [ ] Fetch `page-size + 1` edges after merged dedupe.
- [ ] For forward requests:
  - scan ascending
  - return the first `:first` edges
  - set `:has-next-page?` from the sentinel
  - set `:has-previous-page?` true when `:after` is present, or by an efficient prior-edge check if needed
- [ ] For backward requests:
  - scan descending
  - fetch `:last + 1` merged, deduped edges
  - set `:has-previous-page?` from the sentinel
  - reverse the returned page slice into canonical ascending order
  - set `:has-next-page?` true when `:before` is present, or by an efficient next-edge check if needed
- [ ] Build `:start-cursor` from the first returned edge.
- [ ] Build `:end-cursor` from the last returned edge.
- [ ] Return nil cursors for empty pages.

### Phase 12. Apply To Public Lookup APIs

- [ ] Update `lookup-resources` to call the new shell.
- [ ] Update `lookup-subjects` to call the same shell with reverse traversal config.
- [ ] Update recursive permission fallback to use the same public response shape. It may still materialize fixed-point results as it does today, but it must support `:first/:after` and `:last/:before` correctly.
- [ ] Add a doc note if recursive permission pagination remains materially slower than indexed traversal.

### Phase 13. Apply To `read-relationships`

- [ ] Replace the current bare seq return with a page map.
- [ ] Implement subject-anchored, resource-anchored, and global edge cursor variants.
- [ ] For unanchored filtered reads, implement per-relation streams and merge them by canonical relationship datom key instead of defaulting to a broad full-attribute scan.
- [ ] Include relationship stream frontiers in relationship edge cursors when multiple relation streams are active.
- [ ] Use `seek-page-datoms` for forward and backward relationship scans.
- [ ] Keep relationship filter predicates after narrow scan selection.
- [ ] Return relationship `:data` in canonical datom order for both directions.
- [ ] Update `delete-relationships!` docs/tests so callers pass `(:data page)` from a `read-relationships` response.

### Phase 14. Decide Count API Semantics

- [ ] Decide whether count APIs should remain cursor/range-sensitive.
- [ ] If yes, define range count requests with `:after`/`:before` separately from list `:page-info`.
- [ ] If no, remove paginated count docs and return only total counts for the requested query.
- [ ] Expose or remove `count-subjects` consistently.
- [ ] Add tests for the chosen behavior.

### Phase 15. Documentation And Examples

- [ ] Update `src/eacl/core.clj` protocol comments.
- [ ] Update README examples from `:cursor` and `:limit` to `:first/:after`, `:last/:before`, and `:page-info`.
- [ ] Update `docs/index.md` the same way.
- [ ] Document that public tokens are opaque, encrypted/authenticated, expiring, and query-specific.
- [ ] Document stable basis behavior and explain that `:page/basis :live` is reserved/rejected in the first implementation.
- [ ] Document that output order is stable ascending even for backward page requests.
- [ ] Add a real-time UI example showing previous/next buttons store only the current page map.

### Phase 16. Performance Validation

- [ ] Run normal indexed tests through nREPL:

```clojure
(require 'eacl.datomic.impl.indexed-test :reload)
(clojure.test/run-tests 'eacl.datomic.impl.indexed-test)
```

- [ ] Run public API tests through nREPL:

```clojure
(require 'eacl.spice-test :reload)
(clojure.test/run-tests 'eacl.spice-test)
```

- [ ] Run additional EACL namespace-level suites through nREPL after the implementation touches their surface area:

```clojure
(require 'eacl.datomic.schema-test :reload)
(clojure.test/run-tests 'eacl.datomic.schema-test)

(require 'eacl.datomic.parser_test :reload)
(clojure.test/run-tests 'eacl.datomic.parser_test)
```

- [ ] Run benchmark/load tests only when explicitly validating performance:

```clojure
(require 'eacl.bench.pagination-test :reload)
(clojure.test/run-tests 'eacl.bench.pagination-test)
```

- [x] Use EACL nREPL test namespaces for this library; the benchmark harness is `eacl.bench.pagination-test`.
- [ ] Update `test/eacl/bench/pagination_test.clj` to include backward pagination.
- [ ] Add a benchmark that jumps backward from a deep page and verifies it does not re-scan from the beginning.
- [x] Confirm no implementation path calls `sort` or eager `dedupe` on relationship index results.
- [ ] Confirm reverse pages use `d/rseek-datoms` in indexed hot paths.
- [ ] Confirm `page-size + 1` realization happens after merge and dedupe.

### Phase 17. Final Report And Prompt Review

- [ ] Re-read the critique report: [2026-05-15-reverse-pagination-plan-critique.md](../reports/2026-05-15-reverse-pagination-plan-critique.md).
- [ ] Confirm all report findings were addressed:
  - request `:before`/`:after` retained
  - response moved to `:page-info`
  - `:first/:last` chosen over ambiguous `:limit`
  - page booleans mandatory
  - comparator-based boundary exclusion
  - order and path fingerprints in tokens
  - edge cursor abstraction
  - query shape excludes page controls and is computed after stable-db id normalization
  - reverse merge/dedupe sentinel correctness
  - dedicated relationship cursors
  - lookup edge cursors carry bounded per-path frontiers
  - edge cursors are direction-neutral enough for performant next and previous navigation
  - unanchored filtered relationship reads avoid broad scans where resolved relation streams are available
  - counts separated from list contract
  - stable/live basis policy
  - live basis intentionally rejected until a separate shifting-boundary design exists
  - authenticated encrypted token policy
  - removal of public cursor conversion hooks from page-token construction
  - canonical query-shape and path-fingerprint hashing
  - schema-fingerprint-aware permission-path cache
  - Datomic release date note retained
- [ ] Re-read the appendix below.
- [ ] Verify the implementation satisfies every operator requirement:
  - reverse pagination works
  - public API uses `:before` and `:after` instead of `:cursor`
  - all paginated list APIs are covered
  - no backward compatibility or migration path was preserved
  - implementation was driven by failing tests
  - users do not need to hold a long list of seen cursors
  - the API is intuitive
  - forward and backward traversal are performant

## Risks And Decisions

- `read-relationships` now returns a page map. This is a breaking change, but the prompt explicitly allows breaking changes during development.
- `:limit` removal is a breaking change. This is intentional because the de facto bidirectional model is clearer with `:first` and `:last`.
- Authenticated encrypted page tokens require key management. A process-local development key is acceptable for local use, but multi-peer deployments need an explicit shared keyring and key id.
- Stable basis pagination can return a historical page while the live UI has moved on. This is the cost of exact bidirectional pagination. UIs that need the newest view should refresh from the first page after writes; live cursor pagination should remain rejected until its shifting semantics are designed and tested.
- Stable basis pagination requires public id coercion and permission-path cache lookup against the token's `as-of` db. Doing those steps against the live db is a correctness bug.
- Recursive permission lookup already materializes fixed-point results. This plan makes it API-correct for reverse pagination but does not present it as equivalent to indexed traversal performance.

## Sources

- [Relay GraphQL Cursor Connections Specification](https://relay.dev/graphql/connections.htm)
- [GitHub GraphQL pagination guide](https://docs.github.com/en/graphql/guides/using-pagination-in-the-graphql-api)
- [JSON:API Cursor Pagination Profile](https://jsonapi.org/profiles/ethanresnick/cursor-pagination/)
- [Stripe API pagination](https://docs.stripe.com/api/pagination)
- [Slack Web API pagination](https://docs.slack.dev/apis/web-api/pagination/)
- [Datomic Pro changelog for 1.0.7622](https://docs.datomic.com/changes/pro.html#1.0.7622)
- [Datomic `rseek-datoms` API docs](https://docs.datomic.com/clojure/index.html#datomic.api/rseek-datoms)

## Appendix: Original Prompt

Refer to [theronic/eacl](https://github.com/theronic/eacl/issues/12). `datomic.api/rseek-datoms` landed in Datomic Pro on 2024-04-28 (https://docs.datomic.com/changes/pro.html#1.0.7622)

Currently in real-time UIs, (like `/Users/petrus/code/eacl-explorer`), you have to hold on to a list of seen cursors and pop to traverse backwards, or drop N from start.

Write a detailed plan to docs/plans/ (prefix date) to support reverse pagination. Instead of :cursor, we'll need some kind of :before & :after in all API calls that return paginated lists, including this original prompt verbatim as an appendix to retain context. No backwards-compatibility or migration path is required, as we are in development phase. For each proposed change, examine the existing system and redesign it into the most elegant solution that would have emerged if the change had been a foundational assumption from the start. Plan should follow TDD. The last step in the plan should be to review the prompt in the appendix and check if this implementation satisfies the operator's requirements.

The goal is that the user should be able to paginate forwards and backwards easily without holding onto a long list of seen cursors. The API should be intuitive and performant.
