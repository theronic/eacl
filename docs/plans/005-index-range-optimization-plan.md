# Plan: Index-Range Optimization Implementation

Date: 2025-01-03

## Overview

This plan details the implementation of ADR 004-index-range-optimization to improve EACL performance by replacing recursive Datalog rules with direct `index-range` queries over tuple indices.

## Current State Analysis

### Performance Bottlenecks
1. **lookup-resources** - Most critical, slow due to:
   - Recursive Datalog rules for arrow permissions
   - Inefficient pagination with offset handling
   - Full result materialization before pagination

2. **lookup-subjects** - Less critical but can benefit from:
   - Direct index traversal
   - Better pagination support

3. **can?** - Already optimized but can be further improved

### Available Tuple Indices (from schema.clj)
- `:eacl.relationship/resource+subject` - For direct relationship checks
- `:eacl.relationship/resource+relation-name+subject` - For specific relation lookups
- `:eacl.relationship/subject+relation-name` - For subject-based traversal
- `:eacl.relationship/relation-name+resource` - For relation-based traversal
- `:eacl.permission/resource-type+permission-name` - For permission definitions
- `:eacl.arrow-permission/resource-type+permission-name` - For arrow permissions

## Implementation Strategy

### Phase 1: Core Index-Range Infrastructure

#### 1.1 Create Index Utilities Module
**File**: `src/eacl/datomic/index_utils.clj`

```clojure
(ns eacl.datomic.index-utils
  (:require [datomic.api :as d]))

(defn index-range-seq
  "Lazy sequence over index-range with automatic pagination"
  [db attr start end & {:keys [limit] :or {limit 1000}}]
  ;; Implementation details)

(defn tuple-index-lookup
  "Efficiently lookup entities using tuple indices"
  [db tuple-attr tuple-value]
  ;; Implementation details)
```

#### 1.2 Pagination Support
- Implement cursor-based pagination for stable ordering
- Support both offset and cursor approaches
- Ensure deterministic ordering within index ranges

### Phase 2: Optimize lookup-resources (Highest Priority)

#### 2.1 Replace Staged Implementation
**File**: `src/eacl/datomic/impl_optimized.clj`

Current staged approach uses Datalog queries. Replace with:

1. **Direct Resources via Index**
   ```clojure
   ;; Use :eacl.relationship/subject+relation-name index
   ;; Start from subject, find all relationships
   ;; Filter by permission definitions
   ```

2. **Arrow Permission Resources**
   ```clojure
   ;; Use :eacl.arrow-permission/resource-type+permission-name index
   ;; Find arrow permission definitions
   ;; Traverse using :eacl.relationship/relation-name+resource index
   ```

3. **Pagination Handling**
   - Merge results from multiple indices deterministically
   - Apply offset/limit after merging
   - Maintain stable ordering for iterative pagination

#### 2.2 Algorithm Outline
```
1. Find all permission definitions for resource-type + permission
2. For each permission type:
   a. Direct: Use subject+relation index
   b. Arrow: Use relation+resource index for traversal
   c. Indirect: May still need limited Datalog
3. Merge results with deterministic ordering
4. Apply pagination (offset + limit)
```

### Phase 3: Optimize lookup-subjects

#### 3.1 Index-Based Implementation
**File**: `src/eacl/datomic/impl_optimized.clj`

1. Start from resource entity
2. Use `:eacl.relationship/resource+relation-name+subject` index
3. Filter subjects by permission definitions
4. Handle arrow permissions via index traversal

### Phase 4: Further Optimize can?

#### 4.1 Direct Index Checks
- Use tuple indices for existence checks
- Avoid full rule evaluation when possible
- Short-circuit on first match

### Phase 5: Test Adjustments

#### 5.1 Order Stability
**File**: `test/eacl/datomic/impl_test.clj`

1. Review pagination tests that depend on specific ordering
2. Adjust expected results to match index ordering
3. Ensure count assertions remain unchanged
4. Add tests for pagination cursor stability

#### 5.2 Performance Benchmarks
**File**: `test/eacl/datomic/performance_test.clj`

1. Add benchmarks comparing Datalog vs index-range
2. Test with various dataset sizes
3. Measure pagination performance improvements

## Implementation Steps

### Step 1: Create Index Utilities (Day 1)
- [ ] Create `index_utils.clj` with core functions
- [ ] Add lazy sequence support for large result sets
- [ ] Implement deterministic ordering helpers

### Step 2: Implement lookup-resources Optimization (Days 2-3)
- [ ] Replace `find-direct-resources` with index-based version
- [ ] Replace `find-arrow-resources` with index traversal
- [ ] Implement efficient result merging
- [ ] Add proper pagination with offset support

### Step 3: Update Tests (Day 4)
- [ ] Identify tests dependent on result ordering
- [ ] Update expected results while maintaining counts
- [ ] Add new tests for cursor-based pagination
- [ ] Verify all existing tests pass

### Step 4: Implement lookup-subjects Optimization (Day 5)
- [ ] Replace Datalog-based implementation
- [ ] Use resource+relation+subject index
- [ ] Handle arrow permissions efficiently

### Step 5: Optimize can? (Day 6)
- [ ] Add index-based existence checks
- [ ] Implement short-circuit evaluation
- [ ] Maintain compatibility with existing API

### Step 6: Performance Testing (Day 7)
- [ ] Run comprehensive benchmarks
- [ ] Compare before/after performance
- [ ] Document performance improvements
- [ ] Identify any remaining bottlenecks

## Success Criteria

1. **Performance**: 
   - lookup-resources: >10x improvement for large datasets
   - Pagination: O(log n) instead of O(n) for offset handling

2. **Correctness**:
   - All existing tests pass (with order adjustments)
   - Result counts remain identical
   - Pagination is stable and deterministic

3. **Maintainability**:
   - Clear separation between index and Datalog approaches
   - Well-documented index usage patterns
   - Fallback to Datalog for complex cases

## Risks and Mitigations

1. **Risk**: Index ordering differs from current implementation
   - **Mitigation**: Document ordering changes, update tests accordingly

2. **Risk**: Complex permission chains may not be indexable
   - **Mitigation**: Hybrid approach - use indices for common cases, Datalog for complex

3. **Risk**: Memory usage with large result sets
   - **Mitigation**: Implement lazy sequences and streaming pagination

## Notes

- Priority is lookup-resources due to performance impact
- Order stability is required only for pagination, not specific ordering
- Consider adding index hints to schema for future optimizations
- May need to add additional tuple indices based on access patterns 