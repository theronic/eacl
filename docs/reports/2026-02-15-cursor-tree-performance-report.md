# EACL Cursor Tree Performance Report

**Date**: 2026-02-15
**Branches**: `feature/write-schema-dsl` (before) vs `feature/cursor-tree` (after)
**Benchmark**: `test/eacl/bench/pagination_test.clj`

## Benchmark Setup

- **Schema**: 4-path permission graph (`server.view = account->admin + team->admin + vpc->admin + shared_admin`)
- **Dataset**: 30 accounts x 500 servers = 15,000 servers, plus 120 teams (4/acct) and 60 VPCs (2/acct)
- **Workload**: `lookup-resources` for super-user (access to all 15k servers), limit=50
- **Hardware**: Same machine, JIT-warm nREPL sessions, multiple runs averaged

## Results

| Metric | write-schema-dsl | cursor-tree | Speedup |
|--------|-----------------|-------------|---------|
| First page median | 2.70 ms | 0.88 ms | **3.1x** |
| First page min | 1.85 ms | 0.81 ms | **2.3x** |
| Pagination per-page | 2.53 ms/page | 0.99 ms/page | **2.6x** |
| Pagination total (20 pages) | 50.69 ms | 19.71 ms | **2.6x** |

## What Changed

### Cursor Tree (Issue #43)
The cursor-tree optimization tracks per-path state in the pagination cursor: `{:v 2, :e <last-eid>, :p {<path-idx> <min-intermediate-eid>}}`. On subsequent pages, each arrow path skips exhausted intermediates via `d/index-range` B-tree seeks instead of regenerating all intermediates from scratch.

### Specific Optimizations

1. **Cursor tree pagination** (`a964fa1`): Per-path intermediate tracking via cursor `:p` map. Subsequent pages skip exhausted intermediates.

2. **Beauty refactoring** (`06632fc`): Extracted `arrow-via-intermediates`, `build-v2-cursor`, eliminated per-element `vswap!` tracking-min. Volatile set once to first contributing intermediate.

3. **Arrow-to-permission cursor threading** (`b9821ef`): Replaced `nil` cursor + `drop-while` O(k) with `(dec intermediate-cursor-eid)` cursor threading. Inner `d/index-range` B-tree seeks skip to cursor position.

4. **Lazy-merge-sort specialization** (merged `feature/lazy-merge-sort`): Primitive long comparison (`==`/`<`) avoids autoboxing overhead in tournament merge.

5. **Exclusive start tuples** (`8e197e7`): Replaced `(or cursor-eid 0)` + post-filter with `(if cursor-eid (inc cursor-eid) 0)`. Since `d/index-range` is inclusive on start, `(inc X)` makes the range exclusive. Eliminates per-element `(filter #(> % cursor-eid))`.

6. **Reverse cursor seeking** (`8e197e7`): Fixed hardcoded `0` in reverse start tuples to use `cursor-eid`. O(k) linear scan to O(log N) B-tree seek for `lookup-subjects` pagination.

7. **`keep` instead of `map`+`filter`** (`8e197e7`): `(keep extract-*)` replaces `(map extract-*) (filter some?)` chains.

## Big-O Comparison

| Operation | Before (write-schema-dsl) | After (cursor-tree) |
|-----------|--------------------------|---------------------|
| Arrow-to-relation: intermediate seeking | O(log N) — already indexed | O(log N) — unchanged |
| Arrow-to-permission: intermediate seeking | **O(k) — drop-while linear scan** | **O(log N) — B-tree seek** |
| Arrow result-fn: resource scan | O(log N) + O(k) filter | O(log N) — no filter |
| Reverse relation: subject scan | **O(N) — scan from 0** | **O(log N) — B-tree seek** |
| Tournament merge | O(M * log P) with boxing | O(M * log P) primitive longs |

## Conclusion

The cursor-tree branch delivers a consistent **2.5-3x speedup** over the pre-cursor-tree code for multi-path permission graphs. The improvement comes from eliminating O(k) linear scans (replaced with O(log N) B-tree seeks) and reducing per-element overhead in the hot path.
