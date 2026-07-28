# Review Of The Reverse Pagination Plan

Plan under review: [2026-05-15-reverse-pagination-before-after-plan.md](../plans/2026-05-15-reverse-pagination-before-after-plan.md)

## Executive Judgment

`:before` and `:after` are standard cursor pagination arguments, but the de facto standard is more specific than the plan currently says.

The strongest standard is the Relay GraphQL Cursor Connections model:

- forward pagination is `first` plus `after`
- backward pagination is `last` plus `before`
- responses expose `pageInfo.startCursor`, `pageInfo.endCursor`, `pageInfo.hasNextPage`, and `pageInfo.hasPreviousPage`
- result order must stay the same for forward and backward pagination

REST APIs are less unified. JSON:API's cursor pagination profile uses `page[after]` and `page[before]`. Stripe uses `starting_after` and `ending_before`. Slack uses a forward-only `cursor` and `next_cursor`. GitHub REST commonly relies on `Link` headers with `rel="next"` and `rel="prev"`, while GitHub GraphQL follows the Relay model.

The EACL plan is directionally right, but it should align more closely with Relay semantics: use `:before` and `:after` as request arguments, but return page metadata as `:page-info` with `:start-cursor` and `:end-cursor`, not top-level `:before` and `:after` response tokens.

## De Facto Standard

The closest thing to a de facto standard for bidirectional cursor pagination is Relay's connection spec. It is not just "before and after":

```clojure
;; Forward
{:first 50
 :after end-cursor}

;; Backward
{:last 50
 :before start-cursor}
```

Response metadata:

```clojure
{:page-info {:start-cursor first-item-cursor
             :end-cursor last-item-cursor
             :has-next-page? true-or-false
             :has-previous-page? true-or-false}}
```

If EACL wants a Clojure-idiomatic simplification, the clean compromise is:

```clojure
;; Forward, equivalent to Relay first/after.
{:limit 50
 :after end-cursor}

;; Backward, equivalent to Relay last/before.
{:limit 50
 :before start-cursor}
```

But even with `:limit`, the response should still use `:page-info` with start/end cursors. That keeps the public contract familiar to developers who know Relay, GitHub GraphQL, JSON:API cursor pagination, or Stripe-style bidirectional cursor APIs.

## Recommended EACL Contract

Primary recommendation:

```clojure
(def page1
  (eacl/lookup-resources acl
    {:subject subject
     :permission :view
     :resource/type :server
     :first 50}))

(def page2
  (eacl/lookup-resources acl
    {:subject subject
     :permission :view
     :resource/type :server
     :first 50
     :after (get-in page1 [:page-info :end-cursor])}))

(def page1-again
  (eacl/lookup-resources acl
    {:subject subject
     :permission :view
     :resource/type :server
     :last 50
     :before (get-in page2 [:page-info :start-cursor])}))
```

Response:

```clojure
{:data [...]
 :page-info {:start-cursor opaque-token-or-nil
             :end-cursor opaque-token-or-nil
             :has-next-page? true-or-false
             :has-previous-page? true-or-false}}
```

Acceptable pragmatic alternative:

```clojure
{:limit 50 :after cursor}
{:limit 50 :before cursor}
```

Do not make top-level `:before` and `:after` response fields the primary contract. Those names describe cursor filters, not page edges. Use `:start-cursor` and `:end-cursor` for the page's actual bounds.

## Findings

### 1. The response shape should not use top-level `:before` and `:after`

The plan proposes:

```clojure
{:data [...]
 :before opaque-token-or-nil
 :after opaque-token-or-nil
 :has-before? true-or-false
 :has-after? true-or-false}
```

This is serviceable, but not the common shape. In Relay and GitHub GraphQL, `before` and `after` are request arguments, while the response returns first-item and last-item cursors. JSON:API returns `prev` and `next` links that contain `page[before]` or `page[after]`, not bare response fields named `before` and `after`.

**Recommendation**

Replace the response shape with:

```clojure
{:data [...]
 :page-info {:start-cursor token-or-nil
             :end-cursor token-or-nil
             :has-next-page? boolean
             :has-previous-page? boolean}}
```

If UI ergonomics matter, optionally add derived request maps:

```clojure
{:page-info {...}
 :next-page {:after end-cursor}
 :previous-page {:before start-cursor}}
```

Do not make those derived maps authoritative.

### 2. The plan should decide whether EACL follows Relay exactly or Relay-lite

The plan keeps `:limit` and adds `:before`/`:after`. That is simpler for EACL, but it is not the strict de facto model.

**Recommendation**

Choose one model explicitly:

- Strict Relay-style Clojure API: `:first/:after` for forward and `:last/:before` for backward.
- Relay-lite EACL API: `:limit/:after` for forward and `:limit/:before` for backward.

Given the operator asked for intuitive pagination and no migration burden, strict Relay-style is the cleanest foundational API. If `:limit` is kept, document it as an EACL alias for `:first` or `:last` depending on whether `:after` or `:before` is present.

### 3. Page booleans should be required, not optional

The plan says response booleans are optional. In practice, UI code needs them to enable or disable previous and next controls without guessing from result count.

**Recommendation**

Make both booleans mandatory:

```clojure
:has-next-page?
:has-previous-page?
```

Compute them from `limit + 1` after merge and dedupe, not from the raw per-path streams.

### 4. The plan's `rseek-datoms` exclusivity rule is too arithmetic

The plan says descending scans should start before a bound with `(dec eid)`. That only works when the bound is a simple positive integer final tuple component. It does not generalize to full datom keys, relationship tuples, composite path frontiers, or edge cases around `0` and `Long/MIN_VALUE`.

Datomic's `rseek-datoms` begins at or before the point where the supplied components would reside. That means EACL should implement exclusivity by comparing and dropping the boundary item, not by trying to synthesize a predecessor key.

**Recommendation**

Use a scan primitive like:

```clojure
(defn seek-page [db {:keys [index components direction exclusive-key in-range?]}]
  (let [datoms (case direction
                 :asc  (apply d/seek-datoms db index components)
                 :desc (apply d/rseek-datoms db index components))]
    (->> datoms
         (take-while in-range?)
         (drop-while #(= exclusive-key (datom-page-key %))))))
```

Then add optimized fast paths for simple eid-only bounds where `(inc eid)` is safe. The generic logic must not depend on arithmetic predecessor/successor generation.

### 5. The page-bound token needs a sort and schema fingerprint

The plan includes `:op`, `:shape`, `:basis`, `:key`, and `:paths`. That is a good start, but it does not explicitly include the order definition or permission-path fingerprint.

Cursor tokens are only meaningful for a specific ordered result set. In EACL, that ordered result set depends on:

- operation
- subject/resource anchor
- permission
- result type
- relationship filters
- sort/order semantics
- resolved permission-path graph
- Datomic basis policy

**Recommendation**

Add these fields to the internal token:

```clojure
{:v 3
 :op :lookup-resources
 :query-shape query-shape-hash
 :order [:eid :asc]
 :basis-t basis-t-or-nil
 :path-fingerprint permission-path-fingerprint
 :edge edge-cursor}
```

`path-fingerprint` should change when schema or resolved relation eids change. If the fingerprint does not match, fail with `ex-info` rather than returning a misleading page.

### 6. The plan should preserve one edge-cursor abstraction

The page-bound section talks about `:key`, `:paths`, and `path-frontier`, but it does not name the core abstraction. The clean primitive is an edge cursor: a cursor for a specific edge in the canonical ordered result set.

**Recommendation**

Introduce an internal edge value:

```clojure
{:node result-object-or-eid
 :cursor edge-cursor
 :frontier path-frontier}
```

The public API can still return `:data` only, but internally every emitted result should carry an edge cursor. `:start-cursor` is the cursor of the first edge. `:end-cursor` is the cursor of the last edge.

### 7. The plan under-specifies reverse merge/dedupe semantics

Reverse pagination is not just reversing each stream. Multi-path permission graphs can produce duplicates. The sentinel used to determine `has-previous-page?` must be found after merge and dedupe, otherwise duplicates can make the page flags wrong or produce skipped resources.

**Recommendation**

Make this invariant explicit:

- per-path scans produce sorted streams in the requested scan direction
- lazy merge dedupes by canonical result key in the requested scan direction
- `limit + 1` is applied after merged dedupe
- backward pages are reversed only after the final page slice has been selected

Add tests where a duplicate resource appears as the sentinel candidate.

### 8. `read-relationships` needs its own page cursor design

The plan correctly says `read-relationships` cannot use only a resource or subject eid. It should go further: relationship pagination must use the actual index key for the selected scan.

**Recommendation**

Define relationship edge cursor variants:

```clojure
{:scan :subject
 :index :eavt
 :e subject-eid
 :a forward-relationship-attr-eid
 :v [subject-type relation-eid resource-type resource-eid]}

{:scan :resource
 :index :eavt
 :e resource-eid
 :a reverse-relationship-attr-eid
 :v [resource-type relation-eid subject-type subject-eid]}

{:scan :global
 :index :avet
 :a forward-relationship-attr-eid
 :v [subject-type relation-eid resource-type resource-eid]
 :e subject-eid}
```

Do not attempt to reuse the lookup-resource edge cursor for relationship reads.

### 9. Count APIs should not drive the list pagination contract

The prompt asks for all API calls that return paginated lists. Counts are cursor-sensitive today, but they do not return lists. Folding counts into the first version of the bidirectional list API risks muddying semantics.

**Recommendation**

Split the work:

- First, make `lookup-resources`, `lookup-subjects`, and `read-relationships` follow the new list contract.
- Then decide whether counts should accept `:after`/`:before` as range constraints.
- Do not require count responses to return page cursors unless there is a concrete caller workflow for iterative counting.

If `count-resources` remains iterative, name the return metadata as count progress, not list page info.

### 10. The plan should choose a database basis policy

The README already warns that public client calls use `(d/db conn)` per request, so paginating while the database changes can be inconsistent. Reverse pagination will make this more visible in real-time UIs.

**Recommendation**

Add a basis policy to the plan:

- Stable mode: page token includes `basis-t`, and subsequent pages query `(d/as-of db basis-t)` or equivalent stable db value.
- Live mode: page token omits or ignores `basis-t`, and docs state that inserts/deletes between pages can shift boundaries.

For EACL Explorer-style real-time UIs, live mode may be acceptable, but it must be explicit. For exact bidirectional pagination, stable mode is the principled default.

### 11. Token opacity should be stronger than base64 EDN if exposed outside trusted code

The current token format is base64 EDN with TTL. That is opaque enough for casual callers, but not tamper-resistant. A caller can decode and modify it.

**Recommendation**

Add one of:

- signed EDN payloads with HMAC
- encrypted payloads
- server-side token storage keyed by random id

If EACL remains same-process trusted Clojure only, unsigned opaque-ish tokens may be fine. The plan should state that decision clearly.

### 12. The report should correct the Datomic release date note

The current plan already noticed the date mismatch. Keep that note. The Datomic changelog currently lists `1.0.7622` as `2026/04/28`, with `rseek-datoms` described as reverse index iteration.

**Recommendation**

Do not anchor implementation to the date. Anchor it to `(resolve 'datomic.api/rseek-datoms)` and the Datomic peer version.

## Recommended Plan Amendments

1. Rename the public response metadata from top-level `:before`/`:after` to `:page-info`.
2. Prefer strict Relay-style request keys: `:first/:after` and `:last/:before`.
3. If EACL keeps `:limit`, document it as Relay-lite and make it mutually exclusive with `:first` and `:last`.
4. Make `:has-next-page?` and `:has-previous-page?` mandatory.
5. Introduce an internal edge cursor abstraction and derive page start/end cursors from first/last edges.
6. Replace arithmetic cursor exclusivity with comparator-based boundary exclusion.
7. Apply `limit + 1` after lazy merge and dedupe, not before.
8. Give `read-relationships` a dedicated datom-key cursor design.
9. De-scope count APIs from the initial list pagination contract, or document their semantics separately.
10. Add a stable-vs-live basis policy.
11. Add token signing/encryption decision points.
12. Add tests that assert forward and backward pagination return the same canonical order.

## Revised TDD Skeleton

Use this as a replacement for the early test phases in the plan:

1. Public contract tests:
   - responses include `:page-info`
   - responses do not include `:cursor`
   - request rejects `:cursor`
   - request rejects ambiguous combinations
   - `:start-cursor` and `:end-cursor` are opaque strings or nil
2. Forward lookup tests:
   - first page uses `:first`
   - next page uses `:after end-cursor`
   - pages concatenate to the full canonical result set
3. Backward lookup tests:
   - previous page uses `:last` and `:before start-cursor`
   - backward result order is the same canonical ascending order
   - no client-side cursor stack is required
4. Multi-path duplicate tests:
   - duplicate appears within page
   - duplicate appears at page boundary
   - duplicate appears as sentinel
5. Relationship read tests:
   - subject-anchored scan
   - resource-anchored scan
   - global scan
   - duplicate tuple values disambiguated by full datom key
6. Basis tests:
   - stable mode gives repeatable pages across intervening writes
   - live mode documents and tests expected shifting behavior, if supported

## Conclusion

The existing plan is a good implementation starting point, especially around `rseek-datoms`, direction-aware traversal, and the need to redesign rather than patch `:cursor`.

The main correction is API shape. `:before` and `:after` are standard as request arguments. The de facto response standard is page info with `startCursor` and `endCursor`, plus previous/next booleans. EACL should adopt that model directly, or at least a Clojure-shaped variant of it.

## Sources

- [Relay GraphQL Cursor Connections Specification](https://relay.dev/graphql/connections.htm)
- [GitHub GraphQL pagination guide](https://docs.github.com/en/graphql/guides/using-pagination-in-the-graphql-api)
- [JSON:API Cursor Pagination Profile](https://jsonapi.org/profiles/ethanresnick/cursor-pagination/)
- [Stripe API pagination](https://docs.stripe.com/api/pagination)
- [Slack Web API pagination](https://docs.slack.dev/apis/web-api/pagination/)
- [GitHub REST pagination guide](https://docs.github.com/rest/using-the-rest-api/using-pagination-in-the-rest-api/)
- [Datomic Pro changelog for 1.0.7622](https://docs.datomic.com/changes/pro.html#1.0.7622)
- [Datomic `rseek-datoms` API docs](https://docs.datomic.com/clojure/index.html#datomic.api/rseek-datoms)
