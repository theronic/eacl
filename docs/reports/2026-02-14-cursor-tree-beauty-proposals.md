# Proposals to Make the Cursor Tree Implementation Beautiful

**Date:** 2026-02-14
**Author:** Claude Opus 4.6
**Branch:** `feature/cursor-tree`
**Related:**
- [Cursor Tree Plan](../plans/2026-02-14-cursor-tree-plan.md)
- [Cursor Tree Critique](2026-02-14-cursor-tree-critique.md)
- [Issue #43](https://github.com/theronic/eacl/issues/43)

## Premise

The cursor tree optimization is **correct** (30 tests, 327 assertions, 0 failures). But correctness is the floor, not the ceiling. EACL aspires to be a work of art — the kind of code where every function earns its place and the structure reveals the idea.

Today, `indexed.clj` is 734 lines. The cursor tree added ~150 lines of new logic, but the shape of those lines reveals a deeper structural issue: the same pattern is written four times with minor variations, two parallel traversal hierarchies exist for no good reason, and the forward/reverse symmetry of the algorithm is obscured by copy-paste rather than expressed through abstraction.

This report proposes concrete changes to make the code beautiful. Each proposal stands alone; they can be implemented in any order. Together, they would reduce `indexed.clj` by ~200 lines while making the cursor tree feel like it was always there.

## Proposal 1: Extract `tracking-min` as a Composable Transducer

### The Problem

The volatile side-channel pattern appears **four times** in `indexed.clj` — twice in `traverse-permission-path-via-subject` (lines 469-472, 500-503) and twice in `traverse-permission-path-reverse` (lines 620-623, 652-655). Each is identical:

```clojure
(map (fn [r]
       (vswap! !min-int
         (fn [cur] (if cur (min cur intermediate-eid) intermediate-eid)))
       r))
```

### The Beautiful Version

A transducer that says what it means:

```clojure
(defn- tracking-min
  "Returns a transducer that tracks the minimum value of `v` across all
  elements that pass through it, recording into volatile `!min`.
  Elements pass through unchanged — this is a side-channel only."
  [!min v]
  (map (fn [x]
         (vswap! !min (fn [cur] (if cur (min cur v) v)))
         x)))
```

Usage becomes:

```clojure
(->> (d/index-range db tuple-attr start end)
  (map extract-resource-id-from-rel-tuple-datom)
  (filter some?)
  (filter #(> % (or cursor-eid 0)))
  (tracking-min !min-int intermediate-eid))   ;; <-- one line, self-documenting
```

Four blocks of 4 lines each become four calls of 1 line each. The intent — "track which intermediate contributed" — is named, not encoded.

### Estimated Impact

- **Lines saved:** ~12
- **Concepts named:** 1 (the volatile tracking side-channel)
- **Risk:** Zero. Pure extraction, no behavior change.

## Proposal 2: Extract `arrow-via-intermediates` — The Core Pattern

### The Problem

The arrow traversal in both directions has the same structure, written out in full four times:

1. `traverse-permission-path-via-subject`, arrow-to-relation (lines 447-477)
2. `traverse-permission-path-via-subject`, arrow-to-permission (lines 478-508)
3. `traverse-permission-path-reverse`, arrow-to-relation (lines 598-628)
4. `traverse-permission-path-reverse`, arrow-to-permission (lines 629-660)

All four do the same thing:

```
1. Allocate volatile
2. Get intermediate eids (from index-range or from recursive traversal)
3. For each intermediate: query an index for result eids > cursor-eid
4. Track min contributing intermediate via volatile
5. Merge sorted result sequences with lazy-fold2-merge-dedupe-sorted-by
6. Return {:results merged, :!state volatile}
```

The only things that vary are:
- **How intermediates are obtained** (index-range start position, or `traverse-permission-path` + `drop-while`)
- **Which tuple attribute is queried** (forward vs reverse)
- **Which extraction function is used** (`extract-resource-id-from-rel-tuple-datom` vs `extract-subject-id-from-reverse-rel-tuple-datom`)

### The Beautiful Version

One function that captures the pattern:

```clojure
(defn- arrow-via-intermediates
  "Given a lazy seq of intermediate eids, queries `tuple-attr` for each
  intermediate's result eids > cursor-eid. Tracks the minimum contributing
  intermediate via volatile. Returns {:results <lazy-merged-seq>, :!state <volatile>}."
  [db intermediate-eids
   {:keys [intermediate-type via-relation result-type cursor-eid
           tuple-attr extract-fn]}]
  (let [!min-int     (volatile! nil)
        result-seqs  (map (fn [intermediate-eid]
                            (let [start [intermediate-type intermediate-eid via-relation result-type (or cursor-eid 0)]
                                  end   [intermediate-type intermediate-eid via-relation result-type Long/MAX_VALUE]]
                              (->> (d/index-range db tuple-attr start end)
                                (map extract-fn)
                                (filter some?)
                                (filter #(> % (or cursor-eid 0)))
                                (tracking-min !min-int intermediate-eid))))
                       intermediate-eids)]
    {:results (if (seq result-seqs)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity result-seqs)
               [])
     :!state !min-int}))
```

Now arrow-to-relation in the forward direction (currently 30 lines) becomes:

```clojure
;; Arrow to relation (forward)
(let [intermediate-eids (->> (d/index-range db fwd-tuple-attr
                               [subject-type subject-eid target-relation intermediate-type
                                (or intermediate-cursor-eid 0)]
                               [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE])
                          (map extract-resource-id-from-rel-tuple-datom)
                          (filter some?))]
  (arrow-via-intermediates db intermediate-eids
    {:intermediate-type intermediate-type
     :via-relation      via-relation
     :result-type       resource-type
     :cursor-eid        cursor-eid
     :tuple-attr        fwd-tuple-attr
     :extract-fn        extract-resource-id-from-rel-tuple-datom}))
```

The reverse arrow-to-permission case (currently the most complex at ~30 lines) simplifies similarly, with the recursive traversal pulled out as the intermediate-acquisition step.

### Estimated Impact

- **Lines saved:** ~80 (four 30-line blocks become four 12-line blocks)
- **Concepts named:** 1 (arrow-via-intermediates is the single pattern all four branches share)
- **Risk:** Low. The function boundary makes the invariants explicit: intermediates in, merged results out, volatile tracking is internal.

## Proposal 3: Unify `lookup-resources` and `lookup-subjects`

### The Problem

`lookup-resources` (lines 540-561) and `lookup-subjects` (lines 692-715) are **structurally identical**. Line by line:

| `lookup-resources` | `lookup-subjects` |
|---|---|
| `(lazy-merged-lookup-resources db query)` | `(lazy-merged-lookup-subjects db query)` |
| `(doall (map #(spice-object type %) ...))` | `(doall (map #(spice-object type %) ...))` |
| `(:id (last resources))` | `(:id (last subjects))` |
| `(into (or (:p cursor) {}) ...)` | `(into (or (:p cursor) {}) ...)` |
| `(get-in cursor [:resource :id])` | `(get-in cursor [:subject :id])` |

The same is true for `lazy-merged-lookup-resources` (lines 510-538) and `lazy-merged-lookup-subjects` (lines 662-690). They differ only in:
1. Which entity is the "anchor" (subject vs resource)
2. Which traversal function is called
3. The v1 cursor fallback key (`:resource` vs `:subject`)

### The Beautiful Version

Define a direction:

```clojure
(def ^:private forward-direction
  {:anchor-key      :subject       ;; which query key holds the known entity
   :traverse-fn     traverse-permission-path-via-subject
   :v1-cursor-key   :resource      ;; v1 cursor fallback path
   :entity-fields   [:type :id]})  ;; destructuring for the anchor entity

(def ^:private reverse-direction
  {:anchor-key      :resource
   :traverse-fn     traverse-permission-path-reverse
   :v1-cursor-key   :subject
   :entity-fields   [:type :id]})
```

Then one `lazy-merged-lookup` and one `lookup` that take a direction:

```clojure
(defn- lazy-merged-lookup
  [db direction query]
  (let [{:keys [anchor-key traverse-fn v1-cursor-key]} direction
        {anchor-type :type, anchor-id :id} (get query anchor-key)
        anchor-eid (d/entid db anchor-id)
        cursor     (:cursor query)
        cursor-eid (if (= 2 (:v cursor)) (:e cursor) (get-in cursor [v1-cursor-key :id]))
        perm       (:permission query)
        result-type (or (:resource/type query) (:subject/type query))  ;; whichever is present
        paths      (get-permission-paths db result-type perm)  ;; note: this changes for reverse
        ...]
    ...))
```

Wait — there's a subtlety. For `lookup-resources`, the permission paths are computed for the *result* type (resource-type). For `lookup-subjects`, the permission paths are computed for the *anchor* type (resource-type). In both cases, `get-permission-paths` takes `(db resource-type permission)`. The resource is always what the permission is *about* — it's the "resource" in the ReBAC sense, not the "result" of the query.

So the direction needs to capture which query key provides the type for `get-permission-paths`:

```clojure
(def ^:private forward-direction
  {:anchor-key       :subject
   :perm-type-key    :resource/type   ;; type for get-permission-paths
   :result-type-key  :resource/type   ;; type for spice-object wrapping
   :traverse-fn      traverse-permission-path-via-subject
   :v1-cursor-key    :resource})

(def ^:private reverse-direction
  {:anchor-key       :resource
   :perm-type-key    nil              ;; use (:type resource) directly
   :result-type-key  :subject/type
   :traverse-fn      traverse-permission-path-reverse
   :v1-cursor-key    :subject})
```

Then `lookup-resources` and `lookup-subjects` become:

```clojure
(def lookup-resources (partial lookup forward-direction))
(def lookup-subjects  (partial lookup reverse-direction))
```

Or, if you prefer explicit defns for docstrings:

```clojure
(defn lookup-resources
  "Default :limit 1000. Pass :limit -1 for all results."
  [db query]
  (lookup db forward-direction query))

(defn lookup-subjects
  "Returns subjects that can access the given resource with the specified permission.
  Pass :limit -1 for all results."
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (lookup db reverse-direction query))
```

### Estimated Impact

- **Lines saved:** ~60 (two 30-line functions become one; two 25-line merge functions become one)
- **Concepts named:** 1 (a "direction" is the single axis of variation between forward and reverse lookup)
- **Risk:** Medium. The abstraction must correctly handle the asymmetry between forward and reverse `get-permission-paths` calls. Test thoroughly with the existing 30 tests.

## Proposal 4: Kill the `[eid path]` Tuples in `traverse-permission-path`

### The Problem

`traverse-permission-path` (lines 308-408) returns `[resource-eid path]` tuples. The `path` element is **never used by any caller**. The code itself says:

```clojure
; I'm not sure why we are returning the path with the results, since it does not seem to be used anywhere.
; complicates the consumer, which has to use lazy-merge-dedupe-sort-by to extract the resource eids in first position.
```

This forces every consumer to call `(map first ...)` to extract the eid, and forces the merge sort to use `first` as the comparator key instead of `identity`. It also forces callers to use `lazy-fold2-merge-dedupe-sorted-by first` instead of `lazy-fold2-merge-dedupe-sorted-by identity`.

### The Beautiful Version

Make `traverse-permission-path` return a lazy seq of bare eids, like every other traversal function:

```clojure
(defn traverse-permission-path
  "Returns lazy seq of resource eids where subject has permission."
  [db subject-type subject-eid permission-name resource-type cursor-eid visited-paths]
  ...)
```

Inside the function, remove all `[resource-eid path]` tuple wrapping and switch merge sort from `first` to `identity`. This removes ~20 lines of `(map (fn [resource-eid] [resource-eid path]))` wrappers and `(filter some?)` guards.

Consumers simplify:

```clojure
;; Before (in :self-permission):
(->> (traverse-permission-path db subject-type subject-eid target-permission resource-type cursor-eid #{})
  (map first)                                ;; <-- discard unused path
  (filter (fn [resource-eid] ...)))

;; After:
(->> (traverse-permission-path db subject-type subject-eid target-permission resource-type cursor-eid #{})
  (filter (fn [resource-eid] ...)))
```

```clojure
;; Before (in :arrow to permission):
(let [intermediate-results (traverse-permission-path ...)
      intermediate-eids    (map first intermediate-results)]  ;; <-- discard unused path
  ...)

;; After:
(let [intermediate-eids (traverse-permission-path ...)]
  ...)
```

### Estimated Impact

- **Lines saved:** ~30 (remove tuple wrapping, remove `(map first ...)` calls)
- **Concepts killed:** 1 (the phantom "path taken" metadata that nobody reads)
- **Risk:** Low. No caller uses the path. If `expand-permission-tree` ever needs it (the unimplemented API), it can be a separate function.

## Proposal 5: Extract `cursor-eid` and `build-v2-cursor`

### The Problem

The cursor-eid extraction pattern appears **three times** (lines 521, 673, and the removed v1 pattern in `count-resources`):

```clojure
(if (= 2 (:v cursor)) (:e cursor) (get-in cursor [:resource :id]))   ;; forward
(if (= 2 (:v cursor)) (:e cursor) (get-in cursor [:subject :id]))    ;; reverse
```

The cursor construction pattern appears **three times** (lines 553-561, 707-715, and the simpler version in `count-resources` at 732-734):

```clojure
{:v 2
 :e (or last-eid (:e cursor) (get-in cursor [:resource :id]))
 :p (into (or (:p cursor) {})
      (keep (fn [{:keys [idx !state]}]
              (when-let [v (and !state @!state)]
                [idx v])))
      path-results)}
```

### The Beautiful Version

```clojure
(defn- extract-cursor-eid
  "Extracts the cursor eid from a v1 or v2 cursor.
  v1-key is :resource for forward lookups, :subject for reverse."
  [cursor v1-key]
  (if (= 2 (:v cursor))
    (:e cursor)
    (get-in cursor [v1-key :id])))

(defn- build-v2-cursor
  "Builds a v2 cursor from the current page's results and volatile state.
  Carries forward previous :p entries, overwrites with new volatile state."
  [cursor last-eid path-results v1-key]
  {:v 2
   :e (or last-eid (:e cursor) (get-in cursor [v1-key :id]))
   :p (into (or (:p cursor) {})
        (keep (fn [{:keys [idx !state]}]
                (when-let [v (and !state @!state)]
                  [idx v])))
        path-results)})
```

Now `lookup-resources` (line 559-561) becomes:

```clojure
{:data   resources
 :cursor (build-v2-cursor cursor last-eid path-results :resource)}
```

And `count-resources` can finally propagate volatile state correctly:

```clojure
{:count  (clojure.core/count counted)
 :limit  limit
 :cursor (build-v2-cursor cursor last-eid path-results :resource)}
```

This also fixes the `count-resources` issue identified in the critique — it wasn't propagating volatile state because the carry-forward code wasn't shared. With a shared `build-v2-cursor`, it gets the right behavior for free.

### Estimated Impact

- **Lines saved:** ~20
- **Concepts named:** 2 (cursor extraction, cursor construction)
- **Bugs fixed:** 1 (`count-resources` volatile propagation)
- **Risk:** Zero. Pure extraction.

## Proposal 6: Complete the Opaque Token (Step 7)

### The Problem

Callers currently see raw cursor maps containing Datomic eids. The plan says external callers should receive opaque string tokens. This is the one remaining piece from the original plan.

### The Beautiful Version

The plan already has the code (lines 64-83 of the plan). The implementation is straightforward:

```clojure
;; In core.clj:

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

Wire into spiceomic functions:
- Input: `(S/transform [:cursor] #(or (token->cursor %) %))`
- Output: `(S/transform [:cursor] cursor->token)`

This is the boundary where internals stop leaking. Without it, the v2 cursor format is an implementation detail that's also a public API — the worst of both worlds.

### Estimated Impact

- **Lines added:** ~30
- **Concepts completed:** 1 (the opaque token boundary)
- **Risk:** Low with tests. Token round-trip, expiry, backward compat (accept map or string).

## Proposal 7: Unify `traverse-permission-path` into `traverse-permission-path-via-subject`

### The Problem

There are three traversal functions:

1. `traverse-permission-path` (lines 308-408) — 100 lines. Returns `[eid path]` tuples. Used only by:
   - `traverse-permission-path-via-subject`'s `:self-permission` branch (line 436)
   - `traverse-permission-path-via-subject`'s `:arrow`-to-permission branch (line 482)
2. `traverse-permission-path-via-subject` (lines 416-508) — 92 lines. Returns `{:results :!state}`.
3. `traverse-permission-path-reverse` (lines 563-660) — 97 lines. Returns `{:results :!state}`.

Functions 2 and 3 are the "real" traversal. Function 1 is an older implementation that function 2 delegates to for two specific cases. After Proposal 4 kills the `[eid path]` tuples, function 1 becomes a simpler version of function 2 that doesn't track intermediates.

### The Beautiful Version

Merge function 1 into function 2. The `:self-permission` case already delegates — it can call itself recursively with `intermediate-cursor-eid` set to `nil`. The `:arrow`-to-permission case that calls `traverse-permission-path` to find intermediates can instead call `traverse-permission-path-via-subject` with no intermediate tracking, extracting only the eids.

But wait — `traverse-permission-path` has cycle detection via `visited-paths` that `traverse-permission-path-via-subject` lacks. The clean solution: add `visited-paths` to `traverse-permission-path-via-subject`.

After this merge, `indexed.clj` has exactly two traversal functions: one forward, one reverse. The symmetry is perfect. The cycle detection lives where it's needed. The "primary optimization only" tradeoff (not passing cursor state into recursive calls) is a natural consequence of the recursive call using `nil` for both `cursor-eid` and `intermediate-cursor-eid`.

### Estimated Impact

- **Lines saved:** ~80 (eliminate 100 lines of `traverse-permission-path`, add ~20 for cycle detection in `traverse-permission-path-via-subject`)
- **Functions eliminated:** 1
- **Risk:** Medium. Cycle detection must be verified. The existing `reproduce-infinite-recursion-test` covers this.

## Implementation Order

If all proposals are implemented, I'd suggest this order:

1. **Proposal 4** (kill `[eid path]` tuples) — unblocks Proposal 7, zero risk
2. **Proposal 1** (extract `tracking-min`) — zero risk, immediate clarity
3. **Proposal 5** (extract cursor helpers) — zero risk, fixes `count-resources` bug
4. **Proposal 2** (extract `arrow-via-intermediates`) — low risk, big line savings
5. **Proposal 7** (merge traverse functions) — medium risk, eliminate a whole function
6. **Proposal 3** (unify lookup directions) — medium risk, eliminate duplication
7. **Proposal 6** (opaque token) — new feature, low risk with tests

After all proposals, `indexed.clj` would be approximately:

| Section | Current Lines | After | Change |
|---|---|---|---|
| Helpers & extraction | 16 | 30 | +14 (new `tracking-min`, `arrow-via-intermediates`, cursor helpers) |
| `calc-permission-paths` / caching | 90 | 90 | unchanged |
| `traverse-permission-path` | 100 | 0 | -100 (merged into via-subject) |
| `can?` | 70 | 70 | unchanged |
| `traverse-permission-path-via-subject` | 92 | 70 | -22 (uses `arrow-via-intermediates`, has cycle detection) |
| `traverse-permission-path-reverse` | 97 | 70 | -27 (uses `arrow-via-intermediates`) |
| `lazy-merged-lookup-*` (2 functions) | 55 | 28 | -27 (unified into one) |
| `lookup-*` (2 functions) | 48 | 28 | -20 (unified, uses `build-v2-cursor`) |
| `count-resources` | 18 | 14 | -4 (uses `build-v2-cursor`) |
| **Total** | **~734** | **~530** | **~-200** |

The code would be ~28% shorter while being more correct (`count-resources` volatile propagation), more expressive (named concepts instead of inline patterns), and symmetric (one traversal pattern, two directions).

## What I Would NOT Change

- **The cursor format `{:v 2 :e :p}`** — This is well-designed. Path indices as integers, sparse map, carry-forward. Keep it.
- **The volatile! approach** — Correct and efficient for single-threaded lazy-seq realization. The conservative peek-ahead is an acceptable tradeoff.
- **The `lazy-fold2-merge-dedupe-sorted-by` merge strategy** — Tournament-style lazy merge is the right tool for sorted streams from multiple intermediates.
- **The test structure** — 12 cursor-tree tests + 5 stress tests provide excellent coverage. The TDD approach (failing tests first) was the right methodology.
- **The carry-forward semantics** — `(into (or (:p cursor) {}) ...)` is already elegant. Proposal 5 just names it.
- **`can?`** — Single-shot check with `(some ...)` early termination. Already optimal, no cursor involvement.

## Summary

The cursor tree optimization works. These proposals would make it *sing*. The core idea — seven small extractions that reveal the underlying symmetry — is that `indexed.clj` has always been one algorithm (traverse a permission graph, merge sorted streams, track position for pagination) expressed in two directions (forward, reverse) across three path types (relation, self-permission, arrow). The current code writes this out in full for each combination. The beautiful version names the pattern once and composes it.

> "Perfection is achieved, not when there is nothing more to add, but when there is nothing left to take away."
> — Antoine de Saint-Exupery
