# Plan: Make the Cursor Tree Implementation Beautiful

> **STATUS: INCOMPLETE — context window exhausted during writing. Next agent must continue from Phase 4 onward. The structural analysis and Phase 1-3 steps are complete and precise.**

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

> **STATUS: NOT YET DETAILED.** Phases 4-7 need the same level of detail as Phases 1-3 above. The next agent should continue from here.

**Risk:** Low. Depends on Phase 1 (`tracking-min`).

**Summary:** Extract the shared "iterate intermediates, query index per intermediate, filter by cursor-eid, track volatile, merge" pattern into a single function. This function handles the result-side of all four arrow branches. The intermediate-acquisition side remains caller-specific.

**Key subtlety from structural analysis:** The reverse arrow-to-permission case (lines 629-660) places `tracking-min` OUTSIDE the recursive sub-path calls (wrapping the merged sub-seq), not inside a flat index-range pipeline. This means `arrow-via-intermediates` must handle two shapes:
1. **Flat:** intermediate -> index-range -> results (forward arrow-to-relation, forward arrow-to-permission, reverse arrow-to-relation)
2. **Recursive:** intermediate -> recursive traversal -> merged results (reverse arrow-to-permission)

The function should accept a `result-fn` parameter: given `(db, intermediate-eid)`, returns a lazy seq of result eids. For the flat case, this does the index-range lookup. For the recursive case, this does the `get-permission-paths` + recursive `traverse-permission-path-reverse` + merge.

### TDD Tests Needed
- Test `arrow-via-intermediates` directly with mock data
- All 30 existing tests still pass

---

## Phase 5: Merge `traverse-permission-path` into `traverse-permission-path-via-subject` (Proposal 7)

> **STATUS: NOT YET DETAILED.**

**Risk:** Medium. Depends on Phase 3.

**Summary:** After Phase 3 kills `[eid path]` tuples, `traverse-permission-path` becomes a simpler version of `traverse-permission-path-via-subject` (no volatile tracking, has cycle detection). Merge it by adding `visited-paths` parameter to `traverse-permission-path-via-subject`.

**Key subtlety:** `traverse-permission-path-via-subject`'s `:arrow`-to-permission branch calls `traverse-permission-path` with `visited-paths = #{}` (line 482), NOT carrying forward from the caller. After the merge, it should call itself recursively with `intermediate-cursor-eid = nil` and carry `visited-paths` forward for cycle detection.

### TDD Tests Needed
- `reproduce-infinite-recursion-test` (existing) must still pass
- New test: cycle detection through `traverse-permission-path-via-subject` directly

---

## Phase 6: Complete Opaque Token (Proposal 6)

> **STATUS: NOT YET DETAILED.**

**Risk:** Low. New feature in `core.clj`.

**Summary:** Add `cursor->token` / `token->cursor` to `core.clj`. Wire into `spiceomic-lookup-resources`, `spiceomic-lookup-subjects`, `spiceomic-count-resources`. Accept either token string or map on input (backward compat).

### TDD Tests Needed (in `spice_test.clj`)
- Token round-trip: `cursor->token` then `token->cursor` equals original
- Token expiry: expired token returns nil
- Token backward compat: raw map still accepted as cursor input
- End-to-end: paginate through spiceomic layer with opaque tokens

---

## Phase 7: Unify `lookup-resources` and `lookup-subjects` (Proposal 3)

> **STATUS: NOT YET DETAILED.**

**Risk:** Medium. Depends on Phase 2 (`extract-cursor-eid`, `build-v2-cursor`).

**Summary:** Define `forward-direction` and `reverse-direction` maps. Write single `lazy-merged-lookup` and `lookup` functions parameterized by direction. `lookup-resources` and `lookup-subjects` become thin wrappers. `count-resources` also uses the shared infrastructure.

**Key subtlety from structural analysis:** For `get-permission-paths`, both directions pass the resource type (in the ReBAC sense). For forward, this comes from `resource/type` in the query. For reverse, this comes from `(:type resource)`. The direction map must capture this asymmetry.

### TDD Tests Needed
- All 30 existing tests must pass (these are the real tests — the refactoring must be invisible to callers)

---

## Verification After All Phases

```bash
# Run all tests
clj-nrepl-eval -p <port> "(require '[clojure.test :refer [run-tests]] '[eacl.datomic.impl.indexed-test] :reload) (run-tests 'eacl.datomic.impl.indexed-test)"
clj-nrepl-eval -p <port> "(require '[eacl.spice-test] :reload) (run-tests 'eacl.spice-test)"

# Expected: ~35 tests (30 existing + ~5 new), ~350+ assertions, 0 failures, 0 errors
```

## Notes for Next Agent

### Files to Read (in this order)
1. **This plan** — `docs/plans/2026-02-14-cursor-tree-beauty-plan.md`
2. **Beauty proposals report** — `docs/reports/2026-02-14-cursor-tree-beauty-proposals.md`
3. **`src/eacl/datomic/impl/indexed.clj`** (~734 lines) — the primary implementation file
4. **`src/eacl/datomic/core.clj`** — spiceomic coercion layer (Phase 6)
5. **`test/eacl/datomic/impl/indexed_test.clj`** — 28 tests including 12 cursor-tree tests
6. **`test/eacl/spice_test.clj`** — 2 spiceomic-layer tests

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
- `arrow-via-intermediates` must handle both flat (index-range) and recursive (traverse-reverse) result acquisition
- Forward arrow-to-permission uses `drop-while` for intermediate cursor; all other paths use index-range start position
- `get-permission-paths` always takes the resource type in the ReBAC sense
- Cycle detection must propagate `visited-paths` after Proposal 7 merge
