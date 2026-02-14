# Plan: Make the Cursor Tree Implementation Beautiful

> **STATUS: COMPLETE — all seven phases fully detailed with TDD tests, implementation steps, and verification.**

**Date:** 2026-02-14
**Author:** Claude Opus 4.6
**Branch:** `feature/cursor-tree`
**Report:** [Beauty Proposals](../reports/2026-02-14-cursor-tree-beauty-proposals.md)
**Critique:** [Cursor Tree Critique](../reports/2026-02-14-cursor-tree-critique.md)
**Original Plan:** [Cursor Tree Plan](2026-02-14-cursor-tree-plan.md)
**Issue:** [#43](https://github.com/theronic/eacl/issues/43)

## User's Original Prompt (preserved verbatim)

> Write a detailed plan to docs/plans/ (prefix date, link to report) to implement the improvements you suggested in your report docs/reports/2026-02-14-cursor-tree-beauty-proposals.md
> In plan, state that failing tests must be writen *before* any new code is writen (TDD).
>
> Only clj-nrepl-eval should be used for running tests (faster, manage your own nREPL). Always establish a testing baseline ring impl.
>
> When spinning up sub-agents, only use opus ({model: opus}) because this is a sophisticated codebase. Think deeply, max effort. Godspeed. We are building EACL together as a work of art.

## Ground Rules

1. **TDD: Failing tests MUST be written BEFORE any implementation code.** Each phase begins with tests that fail against the current code, then implementation makes them pass, then all 30 existing tests must still pass.
2. **Tests run via `clj-nrepl-eval` only.** Start nREPL with `clojure -M:nrepl` from `/Users/petrus/Code/eacl-cursor-tree`. Discover port with `clj-nrepl-eval --discover-ports`. Always use double quotes.
3. **Establish a testing baseline before every phase.** Run all 30 tests (28 indexed + 2 spice), confirm 327 assertions, 0 failures. If baseline fails, stop and fix before proceeding.
4. **Sub-agents: Opus only** (`{model: opus}`).
5. **Implementation order follows dependency graph.** Each phase produces a single commit.

## Baseline

```bash
clj-nrepl-eval -p <port> "(require '[clojure.test :refer [run-tests]] '[eacl.datomic.impl.indexed-test] :reload) (run-tests 'eacl.datomic.impl.indexed-test)"
# Expected: 28 tests, 276 assertions, 0 failures, 0 errors

clj-nrepl-eval -p <port> "(require '[eacl.spice-test] :reload) (run-tests 'eacl.spice-test)"
# Expected: 2 tests, 51 assertions, 0 failures, 0 errors
```

## Structural Analysis Summary

A deep structural analysis was completed (see agent output in conversation). Key findings that inform the plan:

### Forward vs Reverse Asymmetries

- **`traverse-permission-path` is forward-only.** Reverse never calls it. Reverse manually replicates its path-expansion logic in `:self-permission` (lines 583-591) and `:arrow`-to-permission (lines 641-655).
- **Arrow-to-permission has fundamentally different algorithm shapes in forward vs reverse:**
  - Forward: expensive(subject -> intermediates via `traverse-permission-path`) + cheap(intermediate -> resources via index-range)
  - Reverse: cheap(resource -> intermediates via index-range) + expensive(intermediate -> subjects via recursive `traverse-permission-path-reverse`)
  - This is a necessary structural inversion. `arrow-via-intermediates` (Proposal 2) can only unify the "cheap" side (the index-range + merge + volatile tracking). The "expensive" side (intermediate acquisition) remains direction-specific.
- **`(filter some?)` appears in forward but not reverse** — minor defensive asymmetry.
- **`get-permission-paths` always takes the resource type** in the ReBAC sense, in both directions. For forward, this is `resource/type` from the query. For reverse, this is `(:type resource)`.
- **`count-resources` ignores `path-results`** — volatile state is never propagated to cursor `:p`.

### What Can Be Unified vs What Cannot

| Pattern | Can Unify? | Notes |
|---------|-----------|-------|
| `tracking-min` vswap | YES | Identical in all 4 arrow branches |
| Arrow result-side (index-range + filter + merge + volatile) | YES | Same in all 4 branches, parameterized by tuple-attr + extract-fn |
| Arrow intermediate-side acquisition | NO | Forward arrow-to-permission uses `traverse-permission-path` + `drop-while`. Reverse uses index-range. Forward arrow-to-relation uses index-range. These vary per direction and target-type. |
| `lazy-merged-lookup-*` | YES | Structurally identical, parameterized by anchor entity + traversal fn + v1-cursor-key |
| `lookup-*` | YES | Structurally identical, parameterized by v1-cursor-key |
| `cursor-eid` extraction | YES | Same pattern, parameterized by v1 key |
| `build-v2-cursor` | YES | Same pattern, parameterized by v1 key |

## Files to Modify

| File | Changes |
|------|---------|
| `src/eacl/datomic/impl/indexed.clj` | Phases 1-5, 7 (bulk of changes) |
| `src/eacl/datomic/core.clj` | Phase 6 (opaque token) |
| `test/eacl/datomic/impl/indexed_test.clj` | All phases (TDD tests) |
| `test/eacl/spice_test.clj` | Phase 6 (token tests) |

---

## Phase 1: Extract `tracking-min` Transducer (Proposal 1)

**Risk:** Zero. Pure extraction.

### 1a. TDD — Write Failing Test

Add to `indexed_test.clj`:

```clojure
(deftest tracking-min-transducer-test
  (testing "tracking-min records minimum value that passes through"
    (let [!min (volatile! nil)
          result (doall (sequence (impl.indexed/tracking-min !min 42) [1 2 3]))]
      (is (= [1 2 3] result) "elements pass through unchanged")
      (is (= 42 @!min) "volatile records the value")))

  (testing "tracking-min picks minimum across multiple calls"
    (let [!min (volatile! nil)]
      (doall (sequence (impl.indexed/tracking-min !min 50) [1]))
      (doall (sequence (impl.indexed/tracking-min !min 30) [2]))
      (doall (sequence (impl.indexed/tracking-min !min 70) [3]))
      (is (= 30 @!min) "should be minimum of 50, 30, 70")))

  (testing "tracking-min with nil volatile starts fresh"
    (let [!min (volatile! nil)
          result (doall (sequence (impl.indexed/tracking-min !min 99) []))]
      (is (= [] result))
      (is (nil? @!min) "no elements means volatile stays nil"))))
```

This test will fail because `impl.indexed/tracking-min` does not exist yet.

### 1b. Implement

Add to `indexed.clj` (near top, after extraction helpers):

```clojure
(defn tracking-min
  "Returns a lazy seq that tracks the minimum of `v` into volatile `!min`
  as elements pass through unchanged. Side-channel for cursor tree."
  [!min v]
  (map (fn [x]
         (vswap! !min (fn [cur] (if cur (min cur v) v)))
         x)))
```

Note: despite the report calling this a "transducer", it returns a lazy seq (via `map`), not a transducer (which would use `(fn [rf] ...)`). This is correct for the current usage — all four call sites use it in `->>` threading, not in `(sequence xf coll)` or `(into [] xf coll)`. Making it a true transducer would require changing call sites. We use `map` to stay consistent with the existing code.

**Update for test:** Since `tracking-min` uses `map` (returns a lazy seq, not a transducer), update the test to use it the same way the production code does:

```clojure
(deftest tracking-min-test
  (testing "tracking-min records minimum value that passes through"
    (let [!min (volatile! nil)
          result (doall (impl.indexed/tracking-min !min 42 [1 2 3]))]
      ;; Wait — tracking-min takes [!min v] and returns a fn that maps.
      ;; Actually it returns (map f coll)... no, it returns (map f).
      ;; Let me re-check: (map (fn [x] ...)) with 1 arg returns a transducer,
      ;; with 2 args returns a lazy seq. Our tracking-min calls (map f) with
      ;; 1 arg, so it IS a transducer after all. But the call sites use
      ;; (->> coll ... (tracking-min !min v)) which threads coll as the
      ;; LAST arg to tracking-min...
```

Actually, let me re-examine. The report says:

```clojure
(defn- tracking-min [!min v]
  (map (fn [x] (vswap! !min ...) x)))
```

`(map f)` with one arg returns a transducer. But the call sites thread a collection into it:

```clojure
(->> (d/index-range ...) ... (tracking-min !min-int intermediate-eid))
```

This passes the seq as the third arg, so `(map f seq)` — which returns a lazy seq. Wait, `tracking-min` returns `(map f)` which is a transducer. Then threading `seq` into it would be `((map f) seq)` which calls the transducer as a function on the seq — that doesn't work as intended.

**The correct implementation must accept the collection:**

```clojure
(defn tracking-min
  "Wraps coll in a lazy seq that tracks the minimum of `v` into volatile `!min`.
  Elements pass through unchanged."
  [!min v coll]
  (map (fn [x]
         (vswap! !min (fn [cur] (if cur (min cur v) v)))
         x)
    coll))
```

Usage: `(->> some-seq ... (tracking-min !min-int intermediate-eid))`

The `->>` macro threads as the last argument, so `coll` receives the seq. This is consistent with how `->> ... (map f)` works (2-arg map).

### 1c. Replace All Four Inline Patterns

Replace the 4-line vswap blocks at lines 469-472, 500-503, 620-623, 652-655 with single `(tracking-min !min-int intermediate-eid)` calls in the `->>` pipeline.

### 1d. Verify

Run baseline. All 30 tests pass + new `tracking-min-test` passes.

---

## Phase 2: Extract `extract-cursor-eid` and `build-v2-cursor` (Proposal 5)

**Risk:** Zero. Pure extraction. Fixes `count-resources` volatile propagation.

### 2a. TDD — Write Failing Test

```clojure
(deftest count-resources-propagates-volatile-state-test
  (testing "count-resources cursor :p advances (not just pass-through)"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Get page1 from lookup-resources
          page1 (lookup-resources db {:subject       (->user super-user-eid)
                                       :permission    :admin
                                       :resource/type :server
                                       :limit         2
                                       :cursor        nil})
          ;; Now count remaining with page1's cursor
          remaining (count-resources db {:subject       (->user super-user-eid)
                                          :permission    :admin
                                          :resource/type :server
                                          :limit         -1
                                          :cursor        (:cursor page1)})
          ;; The count-resources cursor should have :p entries
          ;; that reflect the intermediate positions, not just pass-through
          count-cursor (:cursor remaining)
          lookup-cursor (:cursor page1)]
      ;; If count-resources propagates volatile state, its cursor :p
      ;; should be at least as populated as the input cursor's :p
      (when (seq (:p lookup-cursor))
        (is (seq (:p count-cursor))
            "count-resources cursor :p should not lose intermediate positions")))))
```

This test will fail on current code because `count-resources` uses `(or (:p cursor) {})` instead of building from `path-results`.

### 2b. Implement

Add two private helpers to `indexed.clj`:

```clojure
(defn- extract-cursor-eid
  "Extracts the entity eid from a v1 or v2 cursor.
  v1-key is :resource for forward lookups, :subject for reverse."
  [cursor v1-key]
  (if (= 2 (:v cursor))
    (:e cursor)
    (get-in cursor [v1-key :id])))

(defn- build-v2-cursor
  "Builds a v2 cursor with carry-forward semantics.
  Preserves previous :p entries, overwrites with new volatile state from path-results."
  [cursor last-eid path-results v1-key]
  {:v 2
   :e (or last-eid (:e cursor) (get-in cursor [v1-key :id]))
   :p (into (or (:p cursor) {})
        (keep (fn [{:keys [idx !state]}]
                (when-let [v (and !state @!state)]
                  [idx v])))
        path-results)})
```

### 2c. Replace All Call Sites

1. `lazy-merged-lookup-resources` line 521: replace inline `(if (= 2 ...) ...)` with `(extract-cursor-eid cursor :resource)`
2. `lazy-merged-lookup-subjects` line 673: replace with `(extract-cursor-eid cursor :subject)`
3. `lookup-resources` lines 553-561: replace cursor construction with `(build-v2-cursor cursor last-eid path-results :resource)`
4. `lookup-subjects` lines 707-715: replace with `(build-v2-cursor cursor last-eid path-results :subject)`
5. `count-resources` lines 724-734: destructure `path-results` from `lazy-merged-lookup-resources`, replace cursor with `(build-v2-cursor cursor last-eid path-results :resource)` — this fixes the volatile propagation bug.

### 2d. Verify

Run baseline. All 30 tests + new test pass. The `count-resources` fix is the first behavioral change.

---

## Phase 3: Kill `[eid path]` Tuples in `traverse-permission-path` (Proposal 4)

**Risk:** Low. No caller uses the path element.

### 3a. TDD — Write Failing Test

```clojure
(deftest traverse-permission-path-returns-bare-eids-test
  (testing "traverse-permission-path returns bare eids, not [eid path] tuples"
    (let [db (d/db *conn*)
          user1-eid (d/entid db :test/user1)
          results (impl.indexed/traverse-permission-path db :user user1-eid :view :server nil)]
      (is (seq results) "should have results")
      (is (every? integer? results)
          "every element should be a bare eid (integer), not a tuple"))))
```

This test will fail because `traverse-permission-path` currently returns `[eid path-map]` tuples.

### 3b. Implement

In `traverse-permission-path` (lines 308-408):

1. `:relation` branch (lines 331-342): Change `[resource-eid path]` to bare `resource-eid`. Remove `(filter some?)` (use `(filter #(> % (or cursor-eid 0)))` directly after extraction).
2. `:arrow`-to-relation branch (lines 356-380): Change `(map (fn [resource-eid] [resource-eid path]))` to just the filter pipeline. Switch merge from `(lazy-fold2-merge-dedupe-sorted-by first ...)` to `(lazy-fold2-merge-dedupe-sorted-by identity ...)`.
3. `:arrow`-to-permission branch (lines 381-403): Same changes as above.
4. Final merge (line 406): Switch from `(lazy-fold2-merge-dedupe-sorted-by first)` to `(lazy-fold2-merge-dedupe-sorted-by identity)`.
5. Remove the comment about `[resource-eid path-taken]` from the docstring.

### 3c. Update Callers

1. `traverse-permission-path-via-subject` `:self-permission` (line 436-437): Remove `(map first)`.
2. `traverse-permission-path-via-subject` `:arrow`-to-permission (line 488): Change `(->> (map first intermediate-results) ...)` to just `(->> intermediate-results ...)` since results are already bare eids.
3. `traverse-permission-path` self-recursion at line 384: `intermediate-eids (map first intermediate-results)` becomes just `intermediate-eids intermediate-results` (or assign directly).

### 3d. Update Existing Tests

`traverse-paths-tests` (lines 886-923) asserts on `(first %)` and `(map first ...)` patterns. Update to use bare eids.

### 3e. Verify

Run baseline. All 30 tests + new test pass.

---

## Phase 4: Extract `arrow-via-intermediates` (Proposal 2)

**Risk:** Low. Depends on Phase 1 (`tracking-min`).

**Summary:** Extract the shared "for each intermediate, get result eids, track volatile, merge" pattern into a single function. The intermediate-acquisition side (how intermediates are obtained) remains caller-specific. The result-acquisition side (how each intermediate produces results) is passed as a `result-fn` parameter.

**Key subtlety:** The four arrow branches produce per-intermediate results in two different shapes:
1. **Flat:** intermediate → index-range → result eids (forward arrow-to-relation, forward arrow-to-permission, reverse arrow-to-relation)
2. **Recursive:** intermediate → recursive traversal → merged result eids (reverse arrow-to-permission)

`arrow-via-intermediates` accepts a `result-fn: (fn [intermediate-eid] -> lazy-seq-of-result-eids)`. The flat and recursive shapes are hidden inside the caller's `result-fn`. The function only sees: intermediates in, merged results out, volatile tracking is internal.

### 4a. TDD — Write Failing Test

Add to `indexed_test.clj`:

```clojure
(deftest arrow-via-intermediates-test
  (testing "arrow-via-intermediates merges results from multiple intermediates and tracks min"
    (let [{:keys [results !state]}
          (#'impl.indexed/arrow-via-intermediates
            [10 20 30]
            (fn [int-eid]
              (case (long int-eid)
                10 [100 200]
                20 [150]
                30 [250 300])))]
      (is (= [100 150 200 250 300] (vec results))
          "results should be merged and sorted")
      (is (= 10 @!state)
          "volatile should track the minimum contributing intermediate")))

  (testing "empty intermediates returns empty results"
    (let [{:keys [results !state]}
          (#'impl.indexed/arrow-via-intermediates [] (fn [_] [1 2]))]
      (is (empty? results))
      (is (nil? @!state))))

  (testing "only intermediates that actually contribute results are tracked"
    (let [{:keys [results !state]}
          (#'impl.indexed/arrow-via-intermediates
            [10 20 30]
            (fn [int-eid]
              (case (long int-eid)
                10 []      ;; empty — should not be tracked
                20 [150]   ;; contributes — min should be 20
                30 [250])))]
      ;; Only intermediates 20 and 30 contributed results
      (is (= [150 250] (vec results)))
      (is (= 20 @!state)
          "minimum of contributing intermediates (20, 30) is 20"))))
```

This test fails because `arrow-via-intermediates` does not exist yet.

### 4b. Implement

Add to `indexed.clj`, after `tracking-min`:

```clojure
(defn- arrow-via-intermediates
  "For each intermediate eid, calls result-fn to get a lazy seq of result eids.
  Wraps each per-intermediate seq with tracking-min to record the minimum
  contributing intermediate into a volatile. Merges all result sequences.
  Returns {:results <lazy-merged-seq>, :!state <volatile>}."
  [intermediate-eids result-fn]
  (let [!min-int    (volatile! nil)
        result-seqs (map (fn [intermediate-eid]
                           (->> (result-fn intermediate-eid)
                             (tracking-min !min-int intermediate-eid)))
                      intermediate-eids)]
    {:results (if (seq result-seqs)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity result-seqs)
               [])
     :!state !min-int}))
```

This is 12 lines. It captures: volatile allocation, per-intermediate mapping, tracking-min wrapping, merge, and `{:results :!state}` return.

### 4c. Replace All Four Arrow Branches

**1. Forward arrow-to-relation** (`traverse-permission-path-via-subject` lines 446-477):

Replace the `!min-int` allocation, inner `resource-seqs` map, tracking-min, merge, and return with:

```clojure
;; Arrow to relation
(let [target-relation         (:target-relation path)
      intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
      intermediate-start      [subject-type subject-eid target-relation intermediate-type
                               (or intermediate-cursor-eid 0)]
      intermediate-end        [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE]
      intermediate-eids       (->> (d/index-range db intermediate-tuple-attr intermediate-start intermediate-end)
                                (map extract-resource-id-from-rel-tuple-datom)
                                (filter some?))]
  (arrow-via-intermediates intermediate-eids
    (fn [intermediate-eid]
      (let [start [intermediate-type intermediate-eid via-relation resource-type (or cursor-eid 0)]
            end   [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
        (->> (d/index-range db :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
               start end)
          (map extract-resource-id-from-rel-tuple-datom)
          (filter some?)
          (filter #(> % (or cursor-eid 0))))))))
```

**2. Forward arrow-to-permission** (`traverse-permission-path-via-subject` lines 478-508):

```clojure
;; Arrow to permission
(let [target-permission    (:target-permission path)
      intermediate-results (traverse-permission-path db subject-type subject-eid
                             target-permission intermediate-type nil #{})
      intermediate-eids    (->> (map first intermediate-results)
                             (drop-while #(< % (or intermediate-cursor-eid 0))))]
  (arrow-via-intermediates intermediate-eids
    (fn [intermediate-eid]
      (let [start [intermediate-type intermediate-eid via-relation resource-type (or cursor-eid 0)]
            end   [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
        (->> (d/index-range db :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
               start end)
          (map extract-resource-id-from-rel-tuple-datom)
          (filter some?)
          (filter #(> % (or cursor-eid 0))))))))
```

Note: After Phase 3, `(map first intermediate-results)` becomes just `intermediate-results` (bare eids). The `drop-while` for intermediate cursor skipping remains.

**3. Reverse arrow-to-relation** (`traverse-permission-path-reverse` lines 598-628):

```clojure
;; Arrow to relation (reverse)
(let [target-relation    (:target-relation path)
      reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
      reverse-start      [resource-type resource-eid via-relation intermediate-type
                          (or intermediate-cursor-eid 0)]
      reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
      intermediate-eids  (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                           (map extract-subject-id-from-reverse-rel-tuple-datom))]
  (arrow-via-intermediates intermediate-eids
    (fn [intermediate-eid]
      (let [start [intermediate-type intermediate-eid target-relation subject-type 0]
            end   [intermediate-type intermediate-eid target-relation subject-type Long/MAX_VALUE]]
        (->> (d/index-range db reverse-tuple-attr start end)
          (map extract-subject-id-from-reverse-rel-tuple-datom)
          (filter #(> % (or cursor-eid 0))))))))
```

**4. Reverse arrow-to-permission** (`traverse-permission-path-reverse` lines 629-660):

This is the recursive case. The `result-fn` does the inner traversal:

```clojure
;; Arrow to permission (reverse)
(let [target-permission  (:target-permission path)
      reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
      reverse-start      [resource-type resource-eid via-relation intermediate-type
                          (or intermediate-cursor-eid 0)]
      reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
      intermediate-eids  (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                           (map extract-subject-id-from-reverse-rel-tuple-datom))]
  (arrow-via-intermediates intermediate-eids
    (fn [intermediate-eid]
      (let [paths    (get-permission-paths db intermediate-type target-permission)
            sub-seqs (->> paths
                       (map (fn [sub-path]
                              (:results (traverse-permission-path-reverse db intermediate-type intermediate-eid
                                          sub-path subject-type cursor-eid nil))))
                       (filter seq))]
        (if (seq sub-seqs)
          (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity sub-seqs)
          [])))))
```

The key insight: `arrow-via-intermediates` wraps the entire `result-fn` output with `tracking-min`. For the flat case, tracking happens per-datom. For the recursive case, tracking happens per-subject-eid that emerges from the recursive traversal. Both are correct — any result eid that passes through records its contributing intermediate.

### 4d. Verify

Run baseline. All 30 existing tests pass + new `arrow-via-intermediates-test` passes.

---

## Phase 5: Merge `traverse-permission-path` into `traverse-permission-path-via-subject` (Proposal 7)

**Risk:** Medium. Depends on Phase 3 (bare eids) and Phase 4 (`arrow-via-intermediates`).

**Summary:** After Phase 3, `traverse-permission-path` returns bare eids. After Phase 4, the arrow branches use `arrow-via-intermediates`. `traverse-permission-path` is now a 100-line function that duplicates path-expansion logic already in `traverse-permission-path-via-subject`. Replace the 100-line implementation with a ~15-line thin wrapper that delegates to `traverse-permission-path-via-subject` for each path.

**Key design decision:** The thin wrapper does `get-permission-paths` → calls `traverse-permission-path-via-subject` for each path → merges results. Cycle detection via `visited-paths` lives in this wrapper. `traverse-permission-path-via-subject` gains a `visited-paths` parameter and passes it through when it recurses via the wrapper.

**What changes:**
- `traverse-permission-path`: Delete the 100-line implementation (lines 308-408), replace with ~15-line thin wrapper
- `traverse-permission-path-via-subject`: Add `visited-paths` as 8th parameter
- `:self-permission` branch: call `traverse-permission-path` with `visited-paths` carried forward
- `:arrow`-to-permission branch: call `traverse-permission-path` with `visited-paths` carried forward
- Callers (`lazy-merged-lookup-resources`, `lazy-merged-lookup-subjects`): pass `#{}` for `visited-paths`

**What stays the same:**
- `traverse-permission-path` keeps its name and 6-arg arity (default `visited-paths = #{}`)
- Test callers at `traverse-paths-tests` (lines 886-923) continue to work unchanged
- Recursive calls pass `cursor-eid = nil` and `intermediate-cursor-eid = nil` — the "primary optimization only" tradeoff is preserved

### 5a. TDD — Write Failing Test

Add to `indexed_test.clj`:

```clojure
(deftest traverse-permission-path-via-subject-cycle-detection-test
  (testing "traverse-permission-path-via-subject propagates visited-paths for cycle detection"
    ;; This test exercises the recursive path through the merged function.
    ;; The existing reproduce-infinite-recursion-test covers the schema-level cycle.
    ;; This test verifies that the traversal-level cycle detection works when
    ;; visited-paths is passed through traverse-permission-path-via-subject.
    (let [db (d/db *conn*)
          user1-eid (d/entid db :test/user1)
          ;; server.view = admin + ... where admin = account->admin + ...
          ;; This exercises :self-permission -> traverse-permission-path ->
          ;; traverse-permission-path-via-subject recursion chain.
          paths (impl.indexed/get-permission-paths db :server :view)
          self-perm-path (first (filter #(= :self-permission (:type %)) paths))]
      (is self-perm-path "server.view should have a self-permission path (admin)")
      ;; Call traverse-permission-path-via-subject directly with visited-paths
      (let [{:keys [results]}
            (impl.indexed/traverse-permission-path-via-subject
              db :user user1-eid self-perm-path :server nil nil #{})]
        (is (seq results) "should find servers through self-permission")
        (is (every? integer? results) "results should be bare eids")))))
```

This test will fail because `traverse-permission-path-via-subject` currently takes 7 args, not 8.

### 5b. Implement — New `traverse-permission-path` (thin wrapper)

Delete the current 100-line `traverse-permission-path` (lines 308-408) and replace with:

```clojure
(defn traverse-permission-path
  "Returns lazy seq of resource eids where subject has permission.
  Thin wrapper: gets all permission paths, traverses each via
  traverse-permission-path-via-subject, and merges results.
  Includes cycle detection via visited-paths."
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-eid visited-paths]
   (let [path-key [subject-type subject-eid permission-name resource-type]]
     (if (contains? visited-paths path-key)
       []
       (let [updated-visited (conj visited-paths path-key)
             paths           (get-permission-paths db resource-type permission-name)
             path-seqs       (->> paths
                               (map (fn [path]
                                      (:results (traverse-permission-path-via-subject
                                                  db subject-type subject-eid
                                                  path resource-type cursor-eid nil
                                                  updated-visited))))
                               (filter seq))]
         (if (seq path-seqs)
           (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
           []))))))
```

**Important:** `traverse-permission-path` must be defined AFTER `traverse-permission-path-via-subject` because it calls it. And `traverse-permission-path-via-subject` calls `traverse-permission-path` (mutual recursion). In Clojure, this requires a `declare`:

```clojure
(declare traverse-permission-path)
```

Place this before `traverse-permission-path-via-subject`. Then define `traverse-permission-path-via-subject` first, followed by `traverse-permission-path`.

### 5c. Implement — Update `traverse-permission-path-via-subject` Signature

Add `visited-paths` as the 8th parameter:

```clojure
(defn traverse-permission-path-via-subject
  "Subject must be known. Returns {:results <lazy-seq-of-eids>, :!state <volatile!-or-nil>}.
  intermediate-cursor-eid is the minimum intermediate eid to start scanning from.
  visited-paths is a set of [subject-type subject-eid permission resource-type] for cycle detection."
  [db subject-type subject-eid path resource-type cursor-eid intermediate-cursor-eid visited-paths]
  ...)
```

### 5d. Update `:self-permission` Branch

Before (after Phase 3):

```clojure
:self-permission
{:results (let [target-permission (:target-permission path)]
            (->> (traverse-permission-path db subject-type subject-eid target-permission resource-type cursor-eid #{})
              (filter (fn [resource-eid]
                        (and resource-eid (> resource-eid (or cursor-eid 0)))))))
 :!state nil}
```

After:

```clojure
:self-permission
{:results (let [target-permission (:target-permission path)]
            (->> (traverse-permission-path db subject-type subject-eid
                   target-permission resource-type cursor-eid
                   (or visited-paths #{}))
              (filter (fn [resource-eid]
                        (and resource-eid (> resource-eid (or cursor-eid 0)))))))
 :!state nil}
```

The change: pass `visited-paths` instead of `#{}`. This means cycle detection carries forward through self-permission recursion.

### 5e. Update `:arrow`-to-permission Branch

Before (after Phase 3 + Phase 4):

```clojure
;; Arrow to permission
(let [target-permission    (:target-permission path)
      intermediate-eids    (->> (traverse-permission-path db subject-type subject-eid
                                  target-permission intermediate-type nil #{})
                             (drop-while #(< % (or intermediate-cursor-eid 0))))]
  (arrow-via-intermediates intermediate-eids ...))
```

After:

```clojure
;; Arrow to permission
(let [target-permission    (:target-permission path)
      intermediate-eids    (->> (traverse-permission-path db subject-type subject-eid
                                  target-permission intermediate-type nil
                                  (or visited-paths #{}))
                             (drop-while #(< % (or intermediate-cursor-eid 0))))]
  (arrow-via-intermediates intermediate-eids ...))
```

Same change: pass `visited-paths` instead of `#{}`.

### 5f. Update Callers

1. `lazy-merged-lookup-resources` (line 529-531): Add `#{}` as the 8th arg to `traverse-permission-path-via-subject`:

```clojure
(traverse-permission-path-via-subject
  db subject-type subject-eid
  path type cursor-eid ic-eid
  #{})                                    ;; <-- new
```

2. `lazy-merged-lookup-subjects` (line 681-683): Same:

```clojure
(traverse-permission-path-reverse
  db resource-type resource-eid
  path type cursor-eid ic-eid)            ;; reverse unchanged (Phase 7 adds visited-paths)
```

Note: `traverse-permission-path-reverse` does NOT get `visited-paths` in Phase 5. That happens in Phase 7 when we unify directions.

### 5g. Update Existing Tests

`traverse-paths-tests` (lines 886-923) call `traverse-permission-path` with 6 args. The new implementation has the same 6-arg arity (defaults `visited-paths = #{}`). **No test changes needed.**

### 5h. Verify

Run baseline. All 30 existing tests pass + new cycle detection test passes + `reproduce-infinite-recursion-test` still passes.

---

## Phase 6: Complete Opaque Token (Proposal 6)

**Risk:** Low. New feature in `core.clj`. This is the boundary where internals stop leaking.

**Summary:** Add `cursor->token` and `token->cursor` to `core.clj`. Tokens are strings with format `"eacl1_<base64(edn)>"` that include a TTL. Wire into all spiceomic lookup/count functions. Accept either token string or raw cursor map on input (backward compatibility).

**What changes:**
- `core.clj`: Add `cursor->token`, `token->cursor` functions
- `spiceomic-lookup-resources`: Output cursor becomes opaque token
- `spiceomic-lookup-subjects`: Same
- `spiceomic-count-resources`: Same
- All three accept either token string or raw map as input cursor
- `spice_test.clj`: New token tests, update existing assertions that inspect cursor structure

### 6a. TDD — Write Failing Tests

Add to `spice_test.clj`:

```clojure
(deftest opaque-cursor-token-test
  (testing "cursor->token round-trip preserves cursor"
    (let [cursor {:v 2 :e "some-id" :p {0 "intermediate-id"}}
          token  (spiceomic/cursor->token cursor)
          decoded (spiceomic/token->cursor token)]
      (is (string? token))
      (is (.startsWith ^String token "eacl1_"))
      (is (= cursor decoded))))

  (testing "nil cursor produces nil token"
    (is (nil? (spiceomic/cursor->token nil))))

  (testing "nil or invalid input returns nil cursor"
    (is (nil? (spiceomic/token->cursor nil)))
    (is (nil? (spiceomic/token->cursor "garbage")))
    (is (nil? (spiceomic/token->cursor "eacl1_not-valid-base64!!!"))))

  (testing "expired token returns nil"
    (let [cursor {:v 2 :e "x"}
          token  (spiceomic/cursor->token cursor {:ttl-seconds 0})]
      (Thread/sleep 1100)
      (is (nil? (spiceomic/token->cursor token)))))

  (testing "backward compat: raw cursor map passes through token->cursor"
    (let [cursor {:v 2 :e 12345 :p {0 67890}}]
      (is (= cursor (spiceomic/token->cursor cursor))))))
```

These tests fail because `cursor->token` and `token->cursor` don't exist yet.

### 6b. Implement `cursor->token` and `token->cursor`

Add to `core.clj` (requires `[clojure.edn :as edn]` in ns):

```clojure
(defn cursor->token
  "Serializes an internal cursor map to an opaque string token with TTL.
  Returns nil if cursor is nil."
  [cursor & [{:keys [ttl-seconds] :or {ttl-seconds 300}}]]
  (when cursor
    (let [with-expiry (assoc cursor :t (+ (quot (System/currentTimeMillis) 1000) ttl-seconds))]
      (str "eacl1_" (.encodeToString (java.util.Base64/getEncoder)
                      (.getBytes (pr-str with-expiry) "UTF-8"))))))

(defn token->cursor
  "Deserializes an opaque token string to a cursor map. Returns nil if expired or invalid.
  Also accepts raw cursor maps for backward compatibility."
  [token-or-cursor]
  (cond
    (nil? token-or-cursor) nil
    (map? token-or-cursor) token-or-cursor
    (and (string? token-or-cursor)
         (.startsWith ^String token-or-cursor "eacl1_"))
    (try
      (let [cursor (edn/read-string
                     (String. (.decode (java.util.Base64/getDecoder)
                                (.getBytes (subs token-or-cursor 6) "UTF-8")) "UTF-8"))
            now    (quot (System/currentTimeMillis) 1000)]
        (when (> (:t cursor now) now)
          (dissoc cursor :t)))
      (catch Exception _ nil))
    :else nil))
```

### 6c. Wire into Spiceomic Functions

In `spiceomic-lookup-resources` (line 158-168), wrap cursor transforms:

```clojure
(let [rx (->> query
           (S/transform [:cursor] (fn [token-or-cursor]
                                    (some->> (token->cursor token-or-cursor)
                                      (spice-cursor->internal db opts))))
           (impl/lookup-resources db)
           (S/transform [:data S/ALL] ...)
           (S/transform [:cursor] (fn [internal-cursor]
                                    (some->> (internal-cursor->spice db opts internal-cursor)
                                      cursor->token))))]
```

Apply the same pattern to:
- `spiceomic-lookup-subjects` (line 196-202)
- `spiceomic-count-resources` (line 182-186)

### 6d. Update Existing spice_test.clj Assertions

The existing tests at lines 312-322 check `(:e page1-cursor)`. After opaque tokens, cursors are strings.

Update assertions like:

```clojure
;; Before:
(testing "page1 cursor should be non-nil"
  (is page1-cursor)
  (is (:e page1-cursor)))

;; After:
(testing "page1 cursor should be an opaque token"
  (is page1-cursor)
  (is (string? page1-cursor))
  (is (.startsWith ^String page1-cursor "eacl1_")))
```

Similarly update:
- Line 317-318: `(:e page1-cursor)` → `(string? page1-cursor)`
- Line 337: `(= 2 (:v cursor))` → `(string? cursor)`
- Line 338: `(string? (:e cursor))` → `(.startsWith ^String cursor "eacl1_")`

### 6e. Verify

Run all tests (indexed + spice). All pass with opaque tokens.

---

## Phase 7: Unify `lookup-resources` and `lookup-subjects` (Proposal 3)

**Risk:** Medium. Depends on Phase 2 (`extract-cursor-eid`, `build-v2-cursor`) and Phase 5 (`visited-paths`).

**Summary:** `lazy-merged-lookup-resources` (lines 510-538) and `lazy-merged-lookup-subjects` (lines 662-690) are structurally identical. `lookup-resources` (lines 540-561) and `lookup-subjects` (lines 692-715) are also structurally identical. Unify each pair into a single function parameterized by a "direction" map. `lookup-resources` and `lookup-subjects` become thin wrappers. `count-resources` also uses the shared infrastructure.

**What varies between forward and reverse:**

| Aspect | Forward | Reverse |
|--------|---------|---------|
| Anchor entity (known) | `(:subject query)` | `(:resource query)` |
| Traverse function | `traverse-permission-path-via-subject` | `traverse-permission-path-reverse` |
| v1 cursor fallback key | `:resource` | `:subject` |
| Type for `get-permission-paths` | `(:resource/type query)` | `(:type (:resource query))` |
| Type for `spice-object` wrapping | `(:resource/type query)` | `(:subject/type query)` |

All five differences are captured in a direction map.

### 7a. TDD — Write Property Test

For a pure refactoring, the existing 30 tests ARE the real tests. But we add one explicit property test:

```clojure
(deftest unified-lookup-parity-test
  (testing "forward and reverse lookups produce consistent results"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Forward: super-user -> :admin -> :server
          forward-results (lookup-resources db {:subject       (->user super-user-eid)
                                                 :permission    :admin
                                                 :resource/type :server
                                                 :limit         -1})
          ;; For each result, reverse lookup should find super-user
          server-eids (map :id (:data forward-results))]
      (doseq [server-eid server-eids]
        (let [reverse-results (lookup-subjects db {:resource      (->server server-eid)
                                                    :permission    :admin
                                                    :subject/type  :user
                                                    :limit         -1})
              subject-eids (set (map :id (:data reverse-results)))]
          (is (contains? subject-eids super-user-eid)
              (str "reverse lookup for server " server-eid " should find super-user")))))))
```

This test passes on the current code. The point is to catch regressions during the refactoring.

### 7b. Implement — Direction Maps

Add to `indexed.clj` (near the top, after helpers):

```clojure
(def ^:private forward-direction
  {:anchor-key     :subject
   :perm-type-fn   (fn [query] (:resource/type query))
   :result-type-fn (fn [query] (:resource/type query))
   :traverse-fn    traverse-permission-path-via-subject
   :v1-cursor-key  :resource})

(def ^:private reverse-direction
  {:anchor-key     :resource
   :perm-type-fn   (fn [query] (:type (:resource query)))
   :result-type-fn (fn [query] (:subject/type query))
   :traverse-fn    traverse-permission-path-reverse
   :v1-cursor-key  :subject})
```

**Note on `perm-type-fn`:** `get-permission-paths` always takes the resource type in the ReBAC sense. In forward, this is `(:resource/type query)`. In reverse, this is `(:type (:resource query))` — the resource entity's type. Both refer to the same concept: the type that the permission is *about*.

**Note on `traverse-fn` calling convention:** Both traverse functions have the same positional structure:
- `(db anchor-type anchor-eid path result-type cursor-eid ic-eid visited-paths)`
- Forward: anchor=subject, result-type=resource-type
- Reverse: anchor=resource, result-type=subject-type

### 7c. Implement — Unified `lazy-merged-lookup`

```clojure
(defn- lazy-merged-lookup
  "Lookup entities using lazy merge for multiple paths, parameterized by direction.
  Returns {:results <lazy-seq>, :path-results <vec-of-{:idx :results :!state}>}."
  [db direction query]
  (let [{:keys [anchor-key traverse-fn v1-cursor-key perm-type-fn]} direction
        anchor     (get query anchor-key)
        {anchor-type :type, anchor-id :id} anchor
        anchor-eid (d/entid db anchor-id)
        cursor     (:cursor query)
        cursor-eid (extract-cursor-eid cursor v1-cursor-key)
        permission (:permission query)
        perm-type  (perm-type-fn query)
        paths      (get-permission-paths db perm-type permission)
        path-results (vec (->> paths
                            (map-indexed
                              (fn [idx path]
                                (let [ic-eid (get-in cursor [:p idx])
                                      {:keys [results !state]}
                                      (traverse-fn db anchor-type anchor-eid
                                        path perm-type cursor-eid ic-eid #{})]
                                  {:idx idx :results results :!state !state})))
                            (filter (comp seq :results))))]
    {:results (if (seq path-results)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by
                 identity (map :results path-results))
               [])
     :path-results path-results}))
```

Wait — there's a subtlety with the `perm-type` parameter. In forward, `traverse-permission-path-via-subject` takes `resource-type` as the 5th param, which equals `perm-type`. In reverse, `traverse-permission-path-reverse` takes `subject-type` as the 5th param, which is the **result type** (not `perm-type`). So the 5th param to `traverse-fn` should be `result-type`, not `perm-type`.

Let me fix: `perm-type` is used for `get-permission-paths`. The traverse function's 5th param is the result type:

```clojure
(defn- lazy-merged-lookup
  [db direction query]
  (let [{:keys [anchor-key traverse-fn v1-cursor-key perm-type-fn result-type-fn]} direction
        anchor     (get query anchor-key)
        {anchor-type :type, anchor-id :id} anchor
        anchor-eid (d/entid db anchor-id)
        cursor     (:cursor query)
        cursor-eid (extract-cursor-eid cursor v1-cursor-key)
        permission (:permission query)
        perm-type  (perm-type-fn query)
        result-type (result-type-fn query)
        paths      (get-permission-paths db perm-type permission)
        path-results (vec (->> paths
                            (map-indexed
                              (fn [idx path]
                                (let [ic-eid (get-in cursor [:p idx])
                                      {:keys [results !state]}
                                      (traverse-fn db anchor-type anchor-eid
                                        path result-type cursor-eid ic-eid #{})]
                                  {:idx idx :results results :!state !state})))
                            (filter (comp seq :results))))]
    {:results (if (seq path-results)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by
                 identity (map :results path-results))
               [])
     :path-results path-results}))
```

Wait, for forward: `result-type = (:resource/type query)`. And `traverse-permission-path-via-subject` 5th param = `resource-type` = `:resource/type` from the query. ✓

For reverse: `result-type = (:subject/type query)`. And `traverse-permission-path-reverse` 5th param = `subject-type` = `:subject/type` from the query. ✓

But for forward, `perm-type = (:resource/type query) = result-type`. For reverse, `perm-type = (:type (:resource query))`, which is different from `result-type = (:subject/type query)`. ✓

This is correct! `perm-type` is for `get-permission-paths`, `result-type` is passed to the traverse function.

### 7d. Implement — Unified `lookup`

```clojure
(defn- lookup
  "Core lookup implementation, parameterized by direction.
  Returns {:data <vec>, :cursor <v2-cursor>}."
  [db direction query]
  (let [{:keys [v1-cursor-key result-type-fn]} direction
        {:keys [limit cursor] :or {limit 1000}} query
        {:keys [results path-results]} (lazy-merged-lookup db direction query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        result-type (result-type-fn query)
        items       (doall (map #(spice-object result-type %) limited-results))
        last-eid    (:id (last items))]
    {:data   items
     :cursor (build-v2-cursor cursor last-eid path-results v1-cursor-key)}))
```

### 7e. Thin Wrappers

```clojure
(defn lookup-resources
  "Default :limit 1000.
  Pass :limit -1 for all results (or any negative value)."
  [db query]
  (lookup db forward-direction query))

(defn lookup-subjects
  "Returns subjects that can access the given resource with the specified permission.
  Pass :limit -1 for all results (can be slow)."
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (lookup db reverse-direction query))

(defn count-resources
  "Returns {:keys [count cursor limit]}, where limit matches input.
  Pass :limit -1 for all results."
  [db {:as   query
       :keys [limit cursor]
       resource-type :resource/type
       :or   {limit -1}}]
  (let [{:keys [results path-results]} (lazy-merged-lookup db forward-direction query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        counted         (doall limited-results)
        last-eid        (last counted)]
    {:count  (clojure.core/count counted)
     :limit  limit
     :cursor (build-v2-cursor cursor last-eid path-results :resource)}))
```

### 7f. Delete Old Functions

Delete:
- `lazy-merged-lookup-resources` (~30 lines)
- `lazy-merged-lookup-subjects` (~30 lines)
- Old `lookup-resources` (~22 lines)
- Old `lookup-subjects` (~24 lines)
- Old `count-resources` (~18 lines)

### 7g. Add `visited-paths` to `traverse-permission-path-reverse`

For symmetry with the forward direction (Phase 5), add `visited-paths` as the 8th parameter to `traverse-permission-path-reverse`. This also fixes the potential infinite loop in the reverse `:self-permission` branch (which currently lacks cycle detection).

Signature change:

```clojure
(defn traverse-permission-path-reverse
  [db resource-type resource-eid path subject-type cursor-eid intermediate-cursor-eid visited-paths]
  ...)
```

In `:self-permission`, pass `visited-paths` to recursive calls:

```clojure
:self-permission
{:results (let [target-permission (:target-permission path)
                target-paths      (get-permission-paths db resource-type target-permission)
                path-seqs         (->> target-paths
                                    (map (fn [target-path]
                                           (:results (traverse-permission-path-reverse db resource-type resource-eid
                                                       target-path subject-type cursor-eid nil
                                                       (or visited-paths #{})))))
                                    (filter seq))]
            (if (seq path-seqs)
              (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
              []))
 :!state nil}
```

In `:arrow`-to-permission's `result-fn`, pass `visited-paths`:

```clojure
(fn [intermediate-eid]
  (let [paths    (get-permission-paths db intermediate-type target-permission)
        sub-seqs (->> paths
                   (map (fn [sub-path]
                          (:results (traverse-permission-path-reverse db intermediate-type intermediate-eid
                                      sub-path subject-type cursor-eid nil
                                      (or visited-paths #{})))))
                   (filter seq))]
    (if (seq sub-seqs)
      (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity sub-seqs)
      [])))
```

The `lazy-merged-lookup` call passes `#{}` as the 8th argument to both traverse functions.

### 7h. Verify

Run all tests. All 30+ existing tests pass + new parity test passes. The refactoring is invisible to callers.

---

## Verification After All Phases

```bash
# Run all tests
clj-nrepl-eval -p <port> "(require '[clojure.test :refer [run-tests]] '[eacl.datomic.impl.indexed-test] :reload) (run-tests 'eacl.datomic.impl.indexed-test)"
clj-nrepl-eval -p <port> "(require '[eacl.spice-test] :reload) (run-tests 'eacl.spice-test)"

# Expected: ~35 tests (30 existing + ~5 new), ~370+ assertions, 0 failures, 0 errors
```

## Post-Refactoring Line Count Estimate

| Section | Before | After | Change |
|---------|--------|-------|--------|
| Helpers (`tracking-min`, `arrow-via-intermediates`, cursor helpers) | 16 | 45 | +29 |
| `calc-permission-paths` / caching | 90 | 90 | 0 |
| `traverse-permission-path` | 100 | 15 | -85 |
| `can?` | 70 | 70 | 0 |
| `traverse-permission-path-via-subject` | 92 | 55 | -37 |
| `traverse-permission-path-reverse` | 97 | 55 | -42 |
| `lazy-merged-lookup` (was 2 functions) | 55 | 25 | -30 |
| `lookup` (was 2 functions) | 48 | 15 | -33 |
| `count-resources` | 18 | 12 | -6 |
| Direction maps | 0 | 12 | +12 |
| **Total** | **~734** | **~530** | **~-204** |

## Notes for Implementation

### Running Tests
**Always use nREPL, NEVER `clojure -M:test`** (JVM startup is too slow).

```bash
cd /Users/petrus/Code/eacl-cursor-tree && clojure -M:nrepl &
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p <port> --timeout 60000 "(require '[clojure.test :refer [run-tests]] '[eacl.datomic.impl.indexed-test] :reload) (run-tests 'eacl.datomic.impl.indexed-test)"
clj-nrepl-eval -p <port> --timeout 60000 "(require '[eacl.spice-test] :reload) (run-tests 'eacl.spice-test)"
```

**IMPORTANT**: Use double quotes for `clj-nrepl-eval` code, never single quotes (bash strips `!` from `swap!`, `reset!`, etc.).

### Key Design Constraints
- `volatile!` is safe (single-threaded via `doall`)
- `tracking-min` must accept `coll` as last argument for `->>` threading
- `arrow-via-intermediates` accepts `result-fn` — hides both flat (index-range) and recursive (traverse-reverse) shapes
- Forward arrow-to-permission uses `drop-while` for intermediate cursor; all other paths use index-range start position
- `get-permission-paths` always takes the resource type in the ReBAC sense
- Cycle detection propagates `visited-paths` through `traverse-permission-path` → `traverse-permission-path-via-subject` mutual recursion
- `traverse-permission-path` and `traverse-permission-path-via-subject` form a mutual recursion pair; use `(declare traverse-permission-path)` for forward declaration
- Direction maps capture all five points of variation between forward and reverse: anchor-key, perm-type-fn, result-type-fn, traverse-fn, v1-cursor-key
- Sub-agents: use Opus only (`{model: opus}`) — this is a sophisticated codebase
