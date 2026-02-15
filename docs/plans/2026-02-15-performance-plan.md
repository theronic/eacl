# EACL Performance Plan — O(log N) Intermediate Seeks

**Date**: 2026-02-15
**Prior work**: [Cursor Tree Beauty Plan](2026-02-14-cursor-tree-beauty-plan.md), [Cursor Tree Plan](2026-02-14-cursor-tree-plan.md)
**Benchmarks**: [Multi-path Report](../reports/2026-02-15-multipath-benchmark-report.md) (v6.2 already 7-15x faster for multi-path)

## Problem

Forward arrow-to-permission paths passed `nil` cursor to the recursive `traverse-permission-path` call, then used `drop-while` to linearly scan past already-seen intermediates. This was O(k) per page where k = number of intermediates before cursor.

## Fix (Phase 1)

Replaced `nil` cursor + `drop-while` with `(dec intermediate-cursor-eid)` cursor threading. The `(dec X)` converts the inner exclusive `>` filter to inclusive `>=` semantics. The recursive call now uses `d/index-range` B-tree seeks to skip to the cursor position.

**Before** (`indexed.clj:405-410`):
```clojure
(let [intermediate-eids (->> (traverse-permission-path db ... nil ...)
                           (drop-while #(< % (or intermediate-cursor-eid 0))))]
  (arrow-via-intermediates intermediate-eids ...))
```

**After**:
```clojure
(let [inner-cursor-eid  (when intermediate-cursor-eid (dec intermediate-cursor-eid))
      intermediate-eids (traverse-permission-path db ... inner-cursor-eid ...)]
  (arrow-via-intermediates intermediate-eids ...))
```

## Big-O

| Operation | Before | After |
|-----------|--------|-------|
| Generate intermediates per page | O(N * log D) — all from scratch | O(log D) — B-tree seek |
| `drop-while` | O(k) per page | Eliminated |
| Total pagination (P pages) | O(N * P * log D) | O(P * log D) |

## Branch Status

| Branch | Direction | Complexity |
|--------|-----------|------------|
| Arrow-to-relation | Both | O(log N) — optimal |
| Arrow-to-permission | Forward | **O(log N) — fixed** |
| Arrow-to-permission | Reverse | O(log N) — already optimal |
| Relation (direct) | Both | O(log N) — optimal |

## Deferred

- **Nested cursor v3**: Multi-level arrows (e.g., `backup.view = server->admin = account->admin`) still re-discover level-2 intermediates from scratch. Deferred because level-2 fan-out is small (~1-2).
- **`fold2` lazy forcing**: Tournament tree eagerly probes all intermediates. Could replace with min-heap for true laziness. Deferred pending benchmarks.
