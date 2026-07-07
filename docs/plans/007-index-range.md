# Plan 007: Index-Range Based EACL Implementation

## Overview

This plan outlines the implementation of high-performance EACL functions using Datomic's `index-range` API directly, avoiding the utilities in `index_utils.clj`. The goal is to achieve sub-100ms query performance for results with limit 1,000 and arbitrary offsets.

## Requirements

1. Implement three core functions in `impl_indexed.clj`:
   - `can?` - Check if a subject has permission on a resource
   - `lookup-subjects` - Find all subjects with a permission on a resource
   - `lookup-resources` - Find all resources a subject has permission on

2. Performance target: < 100ms for 1,000 results with arbitrary offset

3. Must produce identical results to the Datalog rules in `optimized.clj`

## Understanding the Datalog Rules

### Key Rules Analysis

1. **Reachability Rule**: Determines if a resource is reachable by a subject
   - Direct: via `resource+subject` tuple
   - Indirect: recursive traversal through intermediate entities

2. **Has-Permission Rule**: Checks if subject has permission on resource
   - Direct: via relation that grants the permission
   - Indirect: via structural relationships (inheritance)
   - Arrow: via arrow permissions (e.g., `account->admin`)

## Index Strategy

### Critical Tuple Indices

1. `:eacl.relationship/resource+subject` - For reachability checks
2. `:eacl.relationship/subject+relation-name` - For subject-based lookups
3. `:eacl.relationship/relation-name+resource` - For relation traversal
4. `:eacl.permission/resource-type+relation-name+permission-name` - For permission checks
5. `:eacl.arrow-permission/resource-type+permission-name` - For arrow permissions

### Index-Range Usage Patterns

For efficient pagination with index-range:
- Start with most selective criteria first
- Use nil for unknown components at the end
- Leverage tuple indices to minimize result set size early

## Implementation Approach

### 1. `can?` Implementation

```clojure
(defn can? [db subject-id permission resource-id])
```

**Algorithm**:
1. Resolve subject and resource entities
2. Check direct permissions:
   - Find relationships where subject -> resource
   - Check if relation grants the permission
3. Check indirect permissions:
   - Find structural relationships where resource is subject
   - Recursively check if subject can reach targets
4. Check arrow permissions:
   - Find arrow permission definitions
   - Traverse intermediate resources

**Index-Range Strategy**:
- Use `:eacl.relationship/resource+subject` for direct checks
- Use `:eacl.permission/resource-type+relation-name+permission-name` for permission validation
- Avoid full traversal by early termination on first match

### 2. `lookup-subjects` Implementation

```clojure
(defn lookup-subjects [db {:keys [resource permission subject/type limit offset]}])
```

**Algorithm**:
1. Get resource entity and type
2. Find all relations that grant the permission
3. For each relation:
   - Find subjects with that relation to resource
   - Filter by subject type
4. Handle arrow permissions
5. Apply pagination

**Index-Range Strategy**:
- Start with resource as known value
- Use `:eacl.relationship/relation-name+resource` index
- Process results in chunks to handle large datasets

### 3. `lookup-resources` Implementation

```clojure
(defn lookup-resources [db {:keys [subject permission resource/type limit offset]}])
```

**Algorithm**:
1. Get subject entity
2. Find all relations that grant the permission for resource type
3. Stage 1: Direct relationships
   - Use subject as starting point
   - Find resources via direct relations
4. Stage 2: Arrow permissions
   - Find arrow permission paths
   - Traverse intermediate resources
5. Stage 3: Indirect relationships (if needed)
6. Deduplicate and paginate

**Index-Range Strategy**:
- Use `:eacl.relationship/subject+relation-name` for efficient subject-based lookup
- Process in stages to minimize work for common cases
- Use lazy sequences to handle large result sets

## Recursive Traversal Implementation

For reachability and permission inheritance, implement efficient recursive traversal:

```clojure
(defn reachable? [db resource-eid subject-eid visited]
  ;; Direct check first
  ;; Then recursive traversal with cycle detection
  )
```

Key optimizations:
- Maintain visited set to prevent cycles
- Use index-range for efficient relationship lookups
- Short-circuit on first positive result

## Pagination Strategy

1. **Offset Handling**:
   - For small offsets (< 100): materialize and drop
   - For large offsets: use index positioning if possible

2. **Result Ordering**:
   - Ensure stable ordering using entity IDs
   - Document ordering guarantees

3. **Deduplication**:
   - Use sets for efficient deduplication
   - Maintain order after deduplication

## Performance Optimizations

1. **Index Warming**: Pre-fetch commonly used indices
2. **Batch Processing**: Process relationships in batches
3. **Early Termination**: Stop traversal once limit is reached
4. **Caching**: Cache permission definitions per query

## Testing Strategy

1. **Correctness Tests**:
   - Compare results with Datalog implementation
   - Test edge cases (cycles, large graphs)

2. **Performance Tests**:
   - Measure query times with various offsets
   - Test with datasets of 10K+ entities

3. **Integration Tests**:
   - Ensure compatibility with existing EACL API
   - Test all permission patterns

## Implementation Steps

1. [ ] Implement basic `can?` with direct permission check
2. [ ] Add indirect permission support to `can?`
3. [ ] Add arrow permission support to `can?`
4. [ ] Implement `lookup-subjects` with pagination
5. [ ] Implement `lookup-resources` with staged approach
6. [ ] Add comprehensive tests
7. [ ] Performance optimization and benchmarking
8. [ ] Documentation and code cleanup

## Risk Mitigation

1. **Memory Usage**: Use lazy sequences and chunked processing
2. **Query Complexity**: Implement query timeout/limits
3. **Index Performance**: Monitor index usage patterns
4. **Correctness**: Extensive testing against Datalog implementation 