# Index Optimization Implementation Summary

Date: 2025-01-03

## Overview

This summary consolidates the implementation plan (005) and technical design (006) for ADR 004-index-range-optimization.

## Key Implementation Points

### 1. Priority: lookup-resources
The highest priority is optimizing `lookup-resources` due to its poor performance with:
- Arrow permissions requiring recursive traversal
- Inefficient pagination with large offsets
- Full result materialization before applying limit/offset

### 2. Implementation Approach
Replace recursive Datalog rules with direct `index-range` queries:
- Use tuple indices for efficient lookups
- Implement staged retrieval (direct → arrow → complex)
- Apply pagination after merging results

### 3. Test Ordering Considerations

#### Current State
Tests in `test/eacl/datomic/impl_test.clj` make assumptions about result ordering:
```clojure
;; Lines 184-190: Note about undefined order
; Note that return order of Spice resources is not defined, because we do not sort during lookup.
; We assume order will be: [server-1, server-3, server-2].
```

#### Required Changes
1. **Entity ID Ordering**: Index-range returns results in AVET order, which for entity IDs will be:
   - Numeric IDs: Natural numeric order
   - String IDs: Lexicographic order
   - Mixed: String representation comparison

2. **Test Updates Needed**:
   ```clojure
   ;; Before (assumed order)
   [server-1, server-3, server-2]
   
   ;; After (index order - example)
   [server-1, server-2, server-3]  ; If ordered by entity ID
   ```

3. **Pagination Tests**: Must verify:
   - Same total count of results
   - Stable ordering across pages
   - No duplicates or missing items

### 4. Implementation Checklist

#### Phase 1: Infrastructure
- [ ] Create `src/eacl/datomic/index_utils.clj`
- [ ] Implement lazy sequence wrappers for index-range
- [ ] Add deterministic ordering utilities

#### Phase 2: lookup-resources (Days 2-3)
- [ ] Implement `find-direct-resources-via-index`
- [ ] Implement `find-arrow-permission-resources`
- [ ] Replace current staged implementation
- [ ] Ensure stable merge ordering

#### Phase 3: Test Updates (Day 4)
- [ ] Update pagination test expectations:
  - [ ] Lines 168-175: "limit: 10, offset 0"
  - [ ] Lines 177-184: "limit: 10, offset: 1" 
  - [ ] Lines 188-196: "limit 1, offset 0"
  - [ ] Lines 198-206: "limit 1, offset 1"
  - [ ] Lines 208-215: "offset: 2, limit: 10"
- [ ] Add explicit ordering tests
- [ ] Verify counts remain unchanged

#### Phase 4: Other Optimizations (Days 5-6)
- [ ] Optimize lookup-subjects
- [ ] Optimize can? checks
- [ ] Add performance benchmarks

### 5. Validation Strategy

1. **Shadow Mode Testing**:
   ```clojure
   (defn validate-lookup-resources [db params]
     (let [datalog-results (lookup-resources-datalog db params)
           indexed-results (lookup-resources-indexed db params)]
       (assert (= (set datalog-results) (set indexed-results))
               "Results must match (ignoring order)")))
   ```

2. **Performance Metrics**:
   - Measure query time for various dataset sizes
   - Track memory usage during pagination
   - Compare offset=0 vs offset=10000 performance

3. **Ordering Stability**:
   ```clojure
   (defn test-pagination-stability [db params]
     (let [page1 (lookup-resources db (assoc params :offset 0 :limit 10))
           page2 (lookup-resources db (assoc params :offset 10 :limit 10))
           all-20 (lookup-resources db (assoc params :offset 0 :limit 20))]
       (assert (= (concat page1 page2) (take 20 all-20))
               "Pagination must be stable")))
   ```

## Expected Outcomes

1. **Performance**: 10-100x improvement for large datasets with pagination
2. **Correctness**: All tests pass with updated ordering expectations
3. **Stability**: Consistent ordering for iterative pagination
4. **Maintainability**: Clear separation between index and Datalog approaches

## Next Steps

1. Begin with Phase 1 infrastructure (Day 1)
2. Focus on lookup-resources optimization (Days 2-3)
3. Update tests to match new ordering (Day 4)
4. Complete remaining optimizations (Days 5-7)

The implementation should maintain backward compatibility while providing significant performance improvements, especially for paginated queries with large offsets. 