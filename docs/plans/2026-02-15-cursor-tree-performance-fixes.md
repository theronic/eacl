# Cursor-Tree Performance Fixes

**Date:** 2026-02-15
**Status:** Proposed
**Benchmark report:** [docs/reports/2026-02-15-eacl-v6.1-vs-v6.2-performance-report.md](../reports/2026-02-15-eacl-v6.1-vs-v6.2-performance-report.md)

## Problem

EACL v6.2 (cursor-tree) shows regressions vs v6.1 in several benchmarks despite adding cursor-tree optimization:

| Operation | v6.1 | v6.2 | Delta |
|-----------|-----:|-----:|------:|
| `lookup-resources` limit 1000 | 10.18 ms | 20.15 ms | **+98%** |
| Small result set (user-1) | 0.023 ms | 0.041 ms | +78% |
| `lookup-subjects` | 0.026 ms | 0.041 ms | +58% |
| Cursor page avg (500 pages) | 9.95 ms | 10.84 ms | +9% |

The cursor-tree was expected to improve pagination latency by 20-100%, but shows no improvement and adds overhead to non-paginated queries.

## Root Cause Analysis

### 1. Per-element `tracking-min` overhead (`indexed.clj:17-24`)

Every element flowing through an arrow path triggers a `vswap!` call:

```clojure
(defn- tracking-min [!min v coll]
  (map (fn [x]
         (vswap! !min (fn [cur] (if cur (min cur v) v)))
         x)
    coll))
```

This adds an extra `map` layer + volatile mutation per result element per intermediate sequence. For limit=1000 with a 500-way merge, ~1000+ elements pass through this wrapper, each triggering a closure call + volatile swap.

The GC pressure from closures and volatile boxes increases variance: v6.2 limit=1000 has min=9.18ms (matching v6.1) but median=20.15ms and max=69.33ms, suggesting GC pauses.

### 2. Eager `vec` on path-results (`indexed.clj:559`)

```clojure
path-results (vec (->> paths (map-indexed ...) (filter (comp seq :results))))
```

v6.2 forces all paths eagerly into a vector (needed for later volatile state reading). v6.1 uses lazy `keep`. This adds O(paths) eager evaluation overhead.

### 3. Cursor coercion overhead (`core.clj:57-67`)

v2 cursor coercion requires N+1 `entid->object-id` calls (1 for `:e` + N for each `:p` entry), vs v1's single call.

### 4. `can?` map arity bug (`core.clj:250-252`)

```clojure
(can? [this {:as demand :keys [subject permission resource consistency]}]
  (assert (= consistency/fully-consistent consistency))  ; fails when consistency is nil
```

The map arity doesn't default `consistency`, causing `AssertionError` for callers that omit it.

## Proposed Fixes

### Fix 1: Replace `tracking-min` with tagged tuples

Instead of wrapping each sub-sequence with per-element volatile tracking, tag results with their intermediate EID and extract the minimum post-hoc.

**Before** (`arrow-via-intermediates`):
```clojure
(let [!min-int    (volatile! nil)
      result-seqs (map (fn [intermediate-eid]
                         (->> (result-fn intermediate-eid)
                           (tracking-min !min-int intermediate-eid)))
                    intermediate-eids)]
  {:results (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity result-seqs)
   :!state !min-int})
```

**After:**
```clojure
(let [result-seqs (map (fn [intermediate-eid]
                         (map (fn [eid] [eid intermediate-eid])
                           (result-fn intermediate-eid)))
                    intermediate-eids)]
  {:results (if (seq result-seqs)
              (lazy-sort/lazy-fold2-merge-dedupe-sorted-by first result-seqs)
              [])
   :!state nil})  ; state extracted post-hoc from consumed tuples
```

**Impact:** Eliminates all volatile overhead. Zero per-element cost. Cursor state computed O(1) at cursor build time from consumed tuples.

**Trade-off:** Merge sort now compares `first` of tuples instead of bare eids. Path results from non-arrow paths (`:relation`, `:self-permission`) need to be wrapped as `[eid nil]` tuples for uniform handling, OR the `lookup` function needs to handle mixed types.

### Fix 2: Delete `tracking-min` function

Remove lines 17-24 entirely. No longer needed after Fix 1.

### Fix 3: Update `build-v2-cursor` for tagged tuples

Extract per-path minimum intermediate from consumed tagged tuples rather than reading volatiles:

```clojure
(defn- build-v2-cursor [cursor last-eid path-min-intermediates v1-key]
  {:v 2
   :e (or last-eid (:e cursor) (get-in cursor [v1-key :id]))
   :p (into (or (:p cursor) {})
        (keep (fn [[idx min-int]]
                (when min-int [idx min-int])))
        path-min-intermediates)})
```

### Fix 4: Fix `can?` map arity

```clojure
(can? [this {:as demand :keys [subject permission resource consistency]}]
  (spiceomic-can? (d/db conn) opts subject permission resource
    (or consistency consistency/fully-consistent)))
```

## Expected Impact

| Fix | Affected operations | Expected improvement |
|-----|-------------------|---------------------|
| Fix 1 (tagged tuples) | All `lookup-resources` calls | Eliminate ~15-20 us per-element overhead |
| Fix 2 (delete tracking-min) | Code cleanliness | N/A |
| Fix 3 (cursor build) | Pagination cursor construction | Minor (reduce volatile reads) |
| Fix 4 (can? bug) | `can?` map arity | Fixes crash, no perf impact |

After fixes, v6.2 should:
- Match v6.1 on single-page queries (no tracking overhead)
- Outperform v6.1 on deep pagination with complex permission graphs (cursor-tree skips exhausted intermediates)

## Validation

1. Run existing test suite: `(kaocha.repl/run :core)`
2. Re-run benchmarks with both simple-path and multi-path schemas
3. Verify cursor correctness: no duplicates, no missed results across pages
