"# Plan to Fix lookup-resources in EACL

## Problem Analysis
The current implementation in `src/eacl/datomic/impl_indexed.clj` has several issues:
1. **Deduplication is not global**: The seen set is reset per call, potentially allowing duplicates across pages.
2. **Cursor handling ignores path-index and type**: Leading to incorrect skipping.
3. **Self-reference exclusion in rules**: May be causing can? to fail for chained permissions, affecting lookup.
4. **Incomplete paths for chains**: If terminal arrow has no sub-paths, the path is skipped, but may be needed.
5. **Order stability**: Relies on path order and eid order per path, but not global sorted.

These cause failures in complex-relation-tests for lookup-resources and possibly can?.

## Proposed Solution
Create a new file `src/eacl/datomic/impl_fixed.clj` with corrected implementations for `lookup-resources` and `count-resources`.

### Key Improvements
1. **Global sorted order**: Collect lazy seqs of eids from each path, merge them in sorted order by eid using a lazy merge function.
2. **Pagination with cursor**: Use cursor as last eid, skip all <= cursor eid.
3. **Deduplication**: In the sorted merged seq, skip consecutive duplicates.
4. **Handle self-reference**: Suggest removing or adjusting not= in rules, but since focus is lookup, assume rules fixed separately.
5. **Complete chains**: Modify get-permission-paths to include paths even if sub-paths empty, treating as reachable to intermediate.
6. **Cursor simplification**: Use only resource-id (eid) in cursor, as order is global by eid.

### Implementation Steps
1. Implement lazy-merge-sorted function to merge multiple sorted lazy seqs by min eid.
2. In lookup-resources:
   - Get paths.
   - For each path, get lazy seq of eids using modified lazy-direct and lazy-arrow that return seq of eids, in eid order.
   - Merge all seqs into one sorted lazy seq.
   - From merged, drop-while #(<= % cursor-eid) if cursor.
   - Then, reduce to dedup: use fold or lazy seq with state to skip if = previous.
   - Take limit.
   - Create new cursor from last eid if any.
   - Map eids to spice-objects.
3. For count-resources: Similar, but count the unique after skip.
4. Add any needed tuple indices if traversal requires.
5. Use semantically meaningful symbols when destructuring datoms from d/index-range, as per the faulty `impl_indexed.clj` implementation, e.g. `resource-type` and `resource-eid`, not just `e`.

### Testing
Swap in `eacl.datomic.impl`:
- Change `(def lookup-resources impl-fixed/lookup-resources)`
- Change `(def count-resources impl-fixed/count-resources)`
- Run tests with `(clojure.test/run-tests 'eacl.datomic.impl-test)` to verify complex-relation-tests pass.
- If can? still fails, adjust rules in optimized.clj by removing (not= ?subject ?intermediate-resource) and test for loops.

This should provide correct, unique, paginated results with stable order." 