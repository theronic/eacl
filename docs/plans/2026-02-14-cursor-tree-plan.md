# Plan: Cursor Tree for Fast Intermediate Path Traversal (Issue #43)

## Problem

When paginating `lookup-resources`/`lookup-subjects`, intermediate arrow traversals restart from eid 0 on every page. For schemas with many intermediates (e.g. 1000 accounts, each owning servers), page N rescans ALL intermediates even though most were fully consumed on previous pages. This is O(pages * intermediates) wasted index-range calls.

**Concrete example**: 100 accounts, 10 servers each. On page 50, the current code scans all 100 accounts starting from eid 0, finding 49 empty before reaching the first with results > cursor-eid.

## Intermediate Tracking: Option B (Minimum Contributing Intermediate)

**Option A (Producer tracking)** is INCORRECT. Counter-example: I1(eid=100)->resources[50,200], I2(eid=150)->resources[60]. Take 2: [50,60]. cursor-eid=60, producer=I2(150). Would skip I1, but I1 still has resource 200>60.

**Option B (Minimum contributing intermediate)** is CORRECT. Track min(intermediate eids that contributed results to this page). All intermediates with eid < min-contributing were scanned in eid order and had no results > cursor-eid. Since new cursor-eid >= old cursor-eid, they still have nothing. Safe to skip.

**Option C (Precise exhaustion)** is equivalent to B with more complexity. No additional benefit.

## Cursor Data Structure

### Internal (indexed.clj) — Datomic eids, tight map

```clojure
;; v2 cursor (new):
{:v 2
 :e 17592186045503         ;; cursor eid (last result/subject eid)
 :p {2 17592186045422      ;; path-index -> intermediate-cursor-eid
     3 17592186045440}}     ;; only arrow paths appear; relations omitted

;; v1 cursor (backward compat, no :v key):
{:resource {:type :server :id 17592186045503}}
```

Design principles:
- **Path indices, not content keys.** `get-permission-paths` returns a cached, deterministic vector for a given db value. Integer indices into this vector are compact and stable. If the schema changes between pages, the cursor is stale — but so are the results. (Pagination against a fixed txId is future work.)
- **Sparse `:p` map.** Only arrow paths that had contributing intermediates get entries. Relations and self-permissions have no intermediates.
- **Carry-forward.** Previous cursor state for exhausted paths is preserved in `:p` (prevents regression to scanning from eid 0).
- **No `:s` sub-path cursors (primary optimization only).** Nested arrow cursoring is a secondary optimization with diminishing returns — intermediate lookups are typically cheap (few permission paths per type). The cursor structure can be extended with `:s` sub-path maps later if needed.

### External (API boundary) — opaque string token

Following SpiceDB's `Cursor { string token = 1; }` pattern, callers receive and pass back an opaque string token. They never parse or construct cursor internals.

```clojure
;; Wire format: "eacl1_<base64(edn)>"
;; Example: "eacl1_ezp2IDIsIDplIDE3NTkyMTg2MDQ1NTAzLCA6cCB7MiAxNzU5MjE4NjA0NTQyMn19"

;; Decoded contents (never seen by caller):
{:v 2
 :e "account1-server1"     ;; coerced to external ID
 :p {2 "account-1"         ;; intermediate eids also coerced
     3 "account-2"}
 :t 1707901800}             ;; expiry unix epoch seconds
```

Token lifecycle:
1. `lookup-resources` (indexed.clj) produces cursor map with Datomic eids
2. `spiceomic-*` (core.clj) coerces eids to external IDs
3. `cursor->token` serializes to opaque string with expiry timestamp
4. Client receives opaque string, passes it back on next request
5. `token->cursor` deserializes, checks expiry, returns cursor map (or nil if expired)
6. `spiceomic-*` coerces external IDs back to eids
7. `lookup-resources` uses cursor map with eids

```clojure
(defn cursor->token
  "Serializes cursor map to opaque string token with TTL."
  [cursor & [{:keys [ttl-seconds] :or {ttl-seconds 300}}]]
  (when cursor
    (let [with-expiry (assoc cursor :t (+ (quot (System/currentTimeMillis) 1000) ttl-seconds))]
      (str "eacl1_" (.encodeToString (java.util.Base64/getEncoder)
                      (.getBytes (pr-str with-expiry) "UTF-8"))))))

(defn token->cursor
  "Deserializes opaque token to cursor map. Returns nil if expired or invalid."
  [token]
  (when (and (string? token) (.startsWith ^String token "eacl1_"))
    (try
      (let [cursor (clojure.edn/read-string
                     (String. (.decode (java.util.Base64/getDecoder)
                                (.getBytes (subs token 6) "UTF-8")) "UTF-8"))
            now (quot (System/currentTimeMillis) 1000)]
        (when (> (:t cursor now) now)
          (dissoc cursor :t)))
      (catch Exception _ nil))))
```

## Key Design Decisions

1. **`volatile!` is correct and sufficient.** All lazy-seq realization happens single-threaded via `doall` in `lookup-resources`/`lookup-subjects`. The volatile is created and consumed within the same synchronous call chain. No concurrent access is possible. If we ever add parallel consumption (`pmap`, `future`), switch to `atom`.

2. **Primary optimization only (this PR).** Track `intermediate-cursor-eid` per top-level arrow path. Skip exhausted intermediates on subsequent pages. Handles the O(pages * intermediates) problem. Secondary optimization (sub-path cursors within `traverse-permission-path` for nested arrow chains) is deferred — intermediate lookups are typically cheap (few permission paths per type), so the benefit is marginal.

3. **Backward compatible.** v1 cursors (no `:v` key) work as today. Opaque string tokens are v2. Old-style map cursors still accepted for backward compat.

4. **Cursor state preserved for exhausted paths.** When a path produces no results on a page, its cursor entry from the previous page carries forward in `:p`. This prevents regression to scanning from eid 0 after a path is temporarily exhausted.

5. **`can?` unchanged.** Single-shot check, no pagination, already optimal with `(some ...)` early termination.

6. **`traverse-permission-path` unchanged.** No changes needed for the primary optimization. Arrow-to-permission intermediates are filtered post-hoc with `drop-while` on the sorted output. The secondary optimization (passing cursor state into recursive calls) is future work.

7. **Volatile peek-ahead is safe.** The merge sort may peek 1 element ahead of the `take limit` boundary, causing a vswap for a result not actually taken. This is conservative — it can only *lower* the min (keeping/adding contributors), never raise it. We skip fewer intermediates than theoretically possible, but never skip one we shouldn't.

## Files to Modify

| File | Changes |
|------|---------|
| `src/eacl/datomic/impl/indexed.clj` | Modify `traverse-permission-path-via-subject` (+`intermediate-cursor-eid` param, return `{:results, :!state}`), modify `lazy-merged-lookup-*`, `lookup-*`, `count-resources` |
| `src/eacl/datomic/core.clj` | v2 cursor coercion in `default-internal-cursor->spice`/`default-spice-cursor->internal`; add `cursor->token`/`token->cursor`; add cursor coercion to `spiceomic-lookup-subjects` (currently missing — `; todo cursor coercion.`) |
| `src/eacl/datomic/impl/base.clj` | Remove unused `Cursor` defrecord |
| `test/eacl/datomic/impl/indexed_test.clj` | New TDD tests for cursor tree |

## Implementation Plan

### Step 1: Write failing TDD tests (indexed_test.clj)

Tests to write BEFORE implementation:

**Test 1a: `cursor-tree-structure-test`**
- Call `lookup-resources` with limit, verify returned cursor has `:v 2`, `:e`, and `:p` keys
- Verify `:p` contains integer keys (path indices) mapping to eid values
- Verify `:p` only has entries for arrow paths (not relations)

**Test 1b: `cursor-tree-intermediate-advancement-test`**
- Set up: super-user has platform->super_admin, platform->accounts, accounts->servers
- Page 1 (limit 2): get first 2 servers, record cursor
- Page 2 with cursor: verify some path in `:p` has intermediate-cursor-eid > 0
- Verify page 2 results are correct (no duplicates, no missing)

**Test 1c: `cursor-tree-backward-compat-test`**
- Pass an old-style cursor `{:resource {:type :server :id eid}}` (no `:v` key)
- Verify results are correct (same as today — all intermediates scanned from 0)

**Test 1d: `cursor-tree-pagination-correctness-test`**
- Paginate through ALL results with limit 1, collecting pages
- Verify concatenated pages == single query with no limit
- Verify no duplicates across pages

**Test 1e: `cursor-tree-performance-test`**
- Create 50 accounts, each with 2 servers (100 servers total)
- Wrap `d/index-range` with `with-redefs` to count calls
- Paginate with limit 10 using cursor tree
- Verify later pages make fewer index-range calls than page 1

**Test 1f: `cursor-tree-lookup-subjects-test`**
- Similar to 1b but for `lookup-subjects`
- Verify cursor tree works for reverse traversal

**Test 1g: `cursor-tree-exhausted-path-preservation-test`**
- Set up scenario where a path produces results on page 1 but not page 2
- Verify page 3 cursor still has the path's `:p` entry (carried forward, not lost)

### Step 2: Modify `traverse-permission-path-via-subject` (indexed.clj:416-499)

**New signature:**
```clojure
(defn traverse-permission-path-via-subject
  [db subject-type subject-eid path resource-type cursor-eid intermediate-cursor-eid]
  ;; Returns {:results <lazy-seq-of-eids>, :!state <volatile!-or-nil>}
```

**Changes per path type:**

**`:relation`** — No intermediates, no change to logic.
```clojure
{:results <same-as-today> :!state nil}
```

**`:self-permission`** — Delegates to `traverse-permission-path` (unchanged). No intermediates at this level.
```clojure
{:results <same-as-today> :!state nil}
```

**`:arrow` to relation** (lines 445-472) — Primary optimization target.
```clojure
(let [!min-int (volatile! nil)
      ;; START from intermediate-cursor-eid instead of 0
      intermediate-start [subject-type subject-eid target-relation intermediate-type
                          (or intermediate-cursor-eid 0)]
      intermediate-end   [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE]
      ;; index-range is inclusive on start, so >= intermediate-cursor-eid. Correct.
      intermediate-eids  (->> (d/index-range db intermediate-tuple-attr
                                intermediate-start intermediate-end)
                           (map extract-resource-id-from-rel-tuple-datom)
                           (filter some?))
      resource-seqs (map (fn [intermediate-eid]
                           (let [resources (->> ... resources > cursor-eid ...)]
                             ;; Side-channel: track min contributing intermediate
                             (map (fn [r]
                                    (vswap! !min-int
                                      (fn [cur] (if cur (min cur intermediate-eid) intermediate-eid)))
                                    r)
                               resources)))
                     intermediate-eids)
      merged (if (seq resource-seqs)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity resource-seqs)
               [])]
  {:results merged :!state !min-int})
```

Note: no redundant `(filter (fn [ieid] (>= ieid ...)))` — the index-range start already handles this.

**`:arrow` to permission** (lines 474-498) — `traverse-permission-path` call is UNCHANGED. Filter intermediates post-hoc.
```clojure
(let [!min-int (volatile! nil)
      ;; traverse-permission-path unchanged — still passes nil cursor-eid
      intermediate-results (traverse-permission-path db subject-type subject-eid
                             target-permission intermediate-type nil #{})
      ;; Skip exhausted intermediates (drop-while on sorted output)
      intermediate-eids (->> (map first intermediate-results)
                          (drop-while #(< % (or intermediate-cursor-eid 0))))
      resource-seqs (map (fn [intermediate-eid]
                           (let [resources (->> ... resources > cursor-eid ...)]
                             (map (fn [r]
                                    (vswap! !min-int
                                      (fn [cur] (if cur (min cur intermediate-eid) intermediate-eid)))
                                    r)
                               resources)))
                     intermediate-eids)
      merged (if (seq resource-seqs)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity resource-seqs)
               [])]
  {:results merged :!state !min-int})
```

Why `drop-while` instead of passing `intermediate-cursor-eid` as cursor-eid to `traverse-permission-path`: the function uses strict `>` filtering (`(> resource-eid (or cursor-eid 0))`), which would *exclude* intermediate-cursor-eid. But we need inclusive `>=` because the intermediate itself may still have unconsumed resources. `drop-while #(< % X)` gives `>= X` semantics on the sorted output.

### Step 3: Modify `lazy-merged-lookup-resources` (indexed.clj:501-527)

```clojure
(defn lazy-merged-lookup-resources
  [db {:keys [subject permission resource/type limit cursor]
       :or   {limit 1000}}]
  (let [{subject-type :type, subject-id :id} subject
        subject-eid (d/entid db subject-id)
        ;; Extract cursor-eid: v2 uses :e, v1 uses [:resource :id]
        cursor-eid  (if (= 2 (:v cursor)) (:e cursor) (get-in cursor [:resource :id]))
        paths       (get-permission-paths db type permission)
        path-results
        (vec (->> paths
               (map-indexed
                 (fn [idx path]
                   (let [ic-eid (get-in cursor [:p idx])
                         {:keys [results !state]}
                         (traverse-permission-path-via-subject
                           db subject-type subject-eid
                           path type cursor-eid ic-eid)]
                     {:idx idx :results results :!state !state})))
               (filter (comp seq :results))))]
    {:results (if (seq path-results)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by
                 identity (map :results path-results))
               [])
     :path-results path-results}))
```

### Step 4: Modify `lookup-resources` (indexed.clj:529-543)

```clojure
(defn lookup-resources
  [db {:as query :keys [subject permission resource/type limit cursor]
       :or {limit 1000}}]
  (let [{:keys [results path-results]} (lazy-merged-lookup-resources db query)
        limited-results (if (>= limit 0) (take limit results) results)
        resources       (doall (map #(spice-object type %) limited-results))
        last-eid        (:id (last resources))
        ;; Build cursor: carry forward previous :p, overwrite with new state
        new-p           (into (or (:p cursor) {})
                          (keep (fn [{:keys [idx !state]}]
                                  (when-let [v (and !state @!state)]
                                    [idx v])))
                          path-results)]
    {:data   resources
     :cursor {:v 2
              :e (or last-eid (:e cursor) (get-in cursor [:resource :id]))
              :p new-p}}))
```

Key fix from critique: `(into (or (:p cursor) {}) ...)` carries forward previous cursor state. If path 2 contributed on page 1 (`:p {2 45422}`) but has no results on page 2, the `into` preserves `{2 45422}`. Only paths with new volatile state overwrite.

### Step 5: Modify `count-resources` (indexed.clj:681-697)

Adapt to new `{:results, :path-results}` return from `lazy-merged-lookup-resources`. `count-resources` doesn't benefit from cursor tree (counts everything), but must work with the changed return shape.

```clojure
(defn count-resources
  [db {:as query :keys [limit cursor] resource-type :resource/type :or {limit -1}}]
  (let [{:keys [results]} (lazy-merged-lookup-resources db query)
        limited-results (if (>= limit 0) (take limit results) results)
        counted         (doall limited-results)
        last-eid        (last counted)
        resources       (map #(spice-object resource-type %) counted)]
    {:count  (count counted)
     :limit  limit
     :cursor {:v 2
              :e (or last-eid (:e cursor) (get-in cursor [:resource :id]))
              :p (or (:p cursor) {})}}))
```

### Step 6: Modify reverse traversal for `lookup-subjects`

Mirror Steps 2-4 for the reverse direction.

**`traverse-permission-path-reverse` (indexed.clj:545-635):** Add `intermediate-cursor-eid` param.

**`:arrow` to relation** (line 586):
```clojure
;; Change: start from intermediate-cursor-eid instead of 0
reverse-start [resource-type resource-eid via-relation intermediate-type
               (or intermediate-cursor-eid 0)]
```
Track min contributing intermediate via volatile!, same pattern as forward.

**`:arrow` to permission** (line 613):
```clojure
;; Change: start from intermediate-cursor-eid instead of 0
reverse-start [resource-type resource-eid via-relation intermediate-type
               (or intermediate-cursor-eid 0)]
```
Track min contributing intermediate via volatile!, same pattern. Note: unlike the forward case, reverse arrow-to-permission finds intermediates via index-range (not recursive `traverse-permission-path`), so we can start directly from `intermediate-cursor-eid`.

**`lazy-merged-lookup-subjects` (indexed.clj:637-661):** Same changes as Step 3 — use `map-indexed`, extract per-path cursor from `:p`, return `{:results, :path-results}`.

**`lookup-subjects` (indexed.clj:663-679):** Same changes as Step 4 — build v2 cursor with carry-forward. Uses `:e` for subject eid (same key as resources — direction is implicit from the operation).

### Step 7: Cursor coercion + opaque token (core.clj)

**Add `cursor->token` and `token->cursor`** (as shown in Cursor Data Structure section above).

**Update `default-internal-cursor->spice`:**
```clojure
(defn default-internal-cursor->spice
  [db {:keys [entid->object-id]} cursor]
  (when cursor
    (if (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(entid->object-id db %))
        (:p cursor) (update :p (fn [p]
                                 (into {} (map (fn [[k v]] [k (entid->object-id db v)])) p))))
      ;; v1 fallback
      (cond
        (:resource cursor) (S/transform [:resource :id] #(entid->object-id db %) cursor)
        (:subject cursor)  (S/transform [:subject :id] #(entid->object-id db %) cursor)))))
```

**Update `default-spice-cursor->internal`:**
```clojure
(defn default-spice-cursor->internal
  [db {:keys [object-id->entid]} cursor]
  (when cursor
    (if (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(object-id->entid db %))
        (:p cursor) (update :p (fn [p]
                                 (into {} (map (fn [[k v]] [k (object-id->entid db v)])) p))))
      ;; v1 fallback
      (cond
        (:resource cursor) (S/transform [:resource :id] #(object-id->entid db %) cursor)
        (:subject cursor)  (S/transform [:subject :id] #(object-id->entid db %) cursor)))))
```

Note: v2 coercion is flat — no recursive `transform-cursor-tree-eids` needed since there are no `:s` sub-path maps (primary optimization only).

**Add cursor coercion to `spiceomic-lookup-subjects`** (currently has `; todo cursor coercion.`):
```clojure
(defn spiceomic-lookup-subjects
  [db {:as opts :keys [entid->object-id spice-object->internal
                       spice-cursor->internal internal-cursor->spice]} query]
  (->> query
    (S/transform [:resource] #(spice-object->internal db %))
    (S/transform [:cursor] #(spice-cursor->internal db opts %))    ;; NEW
    (impl/lookup-subjects db)
    (S/transform [:data S/ALL] (fn [{:keys [type id]}]
                                 (spice-object type (entid->object-id db id))))
    (S/transform [:cursor] #(internal-cursor->spice db opts %))))  ;; NEW
```

**Integrate opaque token in `spiceomic-lookup-resources` and `spiceomic-lookup-subjects`:**
- On input: `(S/transform [:cursor] #(or (token->cursor %) %))` — accept either token string or map (backward compat)
- On output: `(S/transform [:cursor] cursor->token)` — always return opaque string

### Step 8: Cleanup

- Remove unused `(defrecord Cursor ...)` from `src/eacl/datomic/impl/base.clj`
- Remove `[eacl.datomic.impl.base :as base]` import from core.clj (comment says "only for Cursor")

### Step 9: Run all tests, fix regressions

```bash
clj-nrepl-eval -p <port> "(require 'clojure.test) (require 'eacl.datomic.impl.indexed-test :reload) (clojure.test/run-tests 'eacl.datomic.impl.indexed-test)"
clj-nrepl-eval -p <port> "(require 'eacl.spice-test :reload) (clojure.test/run-tests 'eacl.spice-test)"
```

## Verification

1. **All existing tests pass unchanged** — existing tests use old-style cursors or no cursors, both must work
2. **New cursor tree tests pass** — structure, advancement, pagination correctness, performance
3. **Performance test** — page N makes fewer index-range calls than page 1 for schemas with many intermediates
4. **Backward compat** — old-style map cursor produces correct results (degraded to scanning from eid 0)
5. **Opaque token round-trip** — `cursor->token` then `token->cursor` produces equivalent cursor map
6. **Token expiry** — expired tokens return nil, treated as fresh pagination start
7. **End-to-end through Spiceomic** — cursor coercion works for both resources and subjects (spice_test.clj)

## What This Does NOT Change

- `can?` — single-shot check, no pagination, already uses `(some ...)` for early termination
- `traverse-permission-path` — unchanged (secondary optimization deferred)
- Permission path computation (`calc-permission-paths`, `get-permission-paths`) — unchanged
- Lazy merge sort utilities (`eacl.lazy-merge-sort`) — unchanged
- Schema management — unchanged
- Relationship CRUD — unchanged
- The Datalog implementation (`eacl.datomic.impl.datalog`) — unused, unchanged

## Progress

### Baseline (2026-02-14)
- **Existing tests:** 16 tests, 209 assertions, all passing
- **spice_test:** 2 tests, 51 assertions, all passing
- **nREPL port:** 62317 (eacl-cursor-tree)

### Step 1: Failing TDD tests (2026-02-14) - DONE
- 7 new cursor-tree tests written in `test/eacl/datomic/impl/indexed_test.clj`
- All 7 fail as expected (14 assertion failures, 0 errors)
- All 16 original tests still pass (231/245 assertions pass)
- Tests written:
  - `cursor-tree-structure-test` — verifies `:v 2`, `:e`, `:p` keys
  - `cursor-tree-intermediate-advancement-test` — verifies `:p` entries advance
  - `cursor-tree-backward-compat-test` — v1 cursor input still works, output is v2
  - `cursor-tree-pagination-correctness-test` — limit-1 pagination == unpaginated
  - `cursor-tree-performance-test` — page3 calls < page1 calls (36 == 36 currently)
  - `cursor-tree-lookup-subjects-test` — v2 cursor for reverse direction
  - `cursor-tree-exhausted-path-preservation-test` — `:p` entries carry forward

### Next: Step 2 — Modify `traverse-permission-path-via-subject`
- Add `intermediate-cursor-eid` parameter
- Return `{:results, :!state}` instead of bare lazy seq
- Arrow-to-relation: start index-range from `intermediate-cursor-eid`, track min via `volatile!`
- Arrow-to-permission: `drop-while` on intermediate results, track min via `volatile!`
- Relation and self-permission: return `{:results <same>, :!state nil}`

### Then: Steps 3-4 — Modify `lazy-merged-lookup-resources` and `lookup-resources`
- Use `map-indexed` to pass per-path cursor from `:p`
- Build v2 cursor with carry-forward: `(into (or (:p cursor) {}) ...)`

**IMPORTANT: Failing tests must be written BEFORE any implementation code. All TDD tests for Step 1 are complete. Implementation begins at Step 2.**

## Future Work

- **Pagination against fixed txId:** Currently the db value can change between pages, potentially causing missed/duplicate results. Pass the initial db basis-t in the cursor token and use `(d/as-of db t)` on subsequent pages.
- **Secondary optimization (sub-path cursors):** Track cursor state within `traverse-permission-path` for nested arrow chains. Extend cursor `:p` values from bare eids to `{:i eid :s {sub-idx ...}}`. Low priority — intermediate lookups are typically O(1) permission paths.
- **Cursor signing:** HMAC the token to prevent tampering. Low priority — cursor values are internal eids/IDs with no security implications.
