# EACL Performance Optimization Plan

## Executive Summary

Based on analysis of the ADR 003 and the EACL codebase, this document outlines a comprehensive plan to optimize the performance of `can?`, `lookup-subjects`, and `lookup-resources` functions. The highest priority is `lookup-resources` which performs poorly on real-world datasets.

## Current Performance Issues

### 1. Resource Type Lookups (Critical)
- **Problem**: Clauses like `[?resource :resource/type ?resource-type]` require scanning all entities
- **Impact**: Severe performance degradation as dataset grows
- **Affected**: All lookup functions, especially `lookup-resources`

### 2. Recursive Graph Traversal
- **Problem**: The `reachable` rules use unbounded recursive traversal
- **Impact**: Exponential complexity for deep relationship chains
- **Affected**: All permission checks involving indirect relationships

### 3. Suboptimal Rule Ordering
- **Problem**: Most selective clauses are not always executed first
- **Impact**: Unnecessary intermediate result generation
- **Affected**: All rule sets

### 4. Underutilized Tuple Indices
- **Problem**: Not fully leveraging Datomic's composite tuple indices
- **Impact**: Missing opportunities for efficient range scans
- **Affected**: Relationship and permission lookups

## Optimization Strategy

### Phase 1: Quick Wins (1-2 days)

#### 1.1 Rule Reordering
- Move most selective clauses to the beginning of rules
- Place entity type checks after tuple lookups when possible
- Prioritize indexed attributes

#### 1.2 Tuple Index Optimization
- Add strategic tuple indices for common query patterns:
  - `[:eacl.relationship/subject :eacl.relationship/relation-name]`
  - `[:eacl.relationship/relation-name :eacl.relationship/resource]`
  - `[:eacl.permission/resource-type :eacl.permission/permission-name]`

#### 1.3 Eliminate Redundant Type Checks
- For `can?`: Resource types are already known from entities
- Use type information passed as parameters instead of looking up

### Phase 2: Algorithmic Improvements (3-5 days)

#### 2.1 Staged Query Approach for `lookup-resources`
```clojure
;; Stage 1: Find direct relationships
;; Stage 2: Find arrow permissions
;; Stage 3: Combine and deduplicate results
```

#### 2.2 Bounded Reachability
- Implement depth-limited traversal
- Cache intermediate reachability results
- Consider using Datomic's graph algorithms

#### 2.3 Permission Materialization
- Pre-compute and cache common permission paths
- Store effective permissions as derived attributes
- Update cache on relationship changes

### Phase 3: Advanced Optimizations (5-10 days)

#### 3.1 Custom Index Structures
- Build auxiliary indices for permission lookups
- Implement path compression for relationship chains
- Use Bloom filters for quick negative checks

#### 3.2 Query Planner
- Analyze query patterns and choose optimal execution strategy
- Implement cost-based optimization
- Use statistics to guide query execution

#### 3.3 Parallel Execution
- Split large queries into parallel sub-queries
- Use core.async for concurrent execution
- Implement result streaming

## Specific Optimizations by Function

### `can?` Optimization
```clojure
;; Current approach: Full rule evaluation
;; Optimized approach: Short-circuit evaluation

;; 1. Check direct permissions first (fastest)
;; 2. Check arrow permissions (medium)
;; 3. Check indirect permissions (slowest)
```

### `lookup-subjects` Optimization
```clojure
;; Current: Enumerate all subjects, filter by permission
;; Optimized: 
;; 1. Start from resource, find all relationships
;; 2. Group by relation type
;; 3. Apply permission rules per group
;; 4. Union results
```

### `lookup-resources` Optimization (Highest Priority)
```clojure
;; Current: Scan all resources of type, check permissions
;; Optimized:
;; 1. Start from subject, find direct relationships
;; 2. Expand via arrow permissions
;; 3. Filter by resource type
;; 4. Stream results with pagination
```

## Implementation Plan

### Week 1: Foundation
- [ ] Implement rule reordering
- [ ] Add missing tuple indices
- [ ] Create performance benchmarks
- [ ] Implement basic query statistics

### Week 2: Core Optimizations
- [ ] Implement staged query approach
- [ ] Optimize `lookup-resources`
- [ ] Add bounded reachability
- [ ] Implement result caching

### Week 3: Advanced Features
- [ ] Build custom indices
- [ ] Implement query planner
- [ ] Add parallel execution
- [ ] Performance testing and tuning

## Benchmarking Strategy

### Test Datasets
- Small: 100 users, 1K resources, 10K relationships
- Medium: 1K users, 10K resources, 100K relationships
- Large: 10K users, 100K resources, 1M relationships

### Key Metrics
- Query latency (p50, p95, p99)
- Memory usage
- CPU utilization
- Cache hit rates

### Performance Targets
- `can?`: < 10ms for 99% of queries
- `lookup-subjects`: < 100ms for typical queries
- `lookup-resources`: < 200ms for paginated results (limit 100)

## Alternative Approaches

### 1. Datomic Analytics
- Use Datomic Analytics for complex queries
- Offload heavy computations to Spark
- Suitable for batch operations

### 2. Graph Database Integration
- Use specialized graph database for permission checks
- Maintain synchronized graph representation
- Leverage graph-specific optimizations

### 3. Hand-coded Traversal
- Implement custom graph traversal algorithms
- Use Datomic's raw index access
- Maximum performance but higher complexity

## Risk Mitigation

### Backwards Compatibility
- Maintain existing API contracts
- Provide migration path for schema changes
- Extensive regression testing

### Performance Regression
- Continuous performance monitoring
- A/B testing for optimizations
- Rollback capability for changes

### Complexity Management
- Clear documentation for optimizations
- Modular implementation
- Comprehensive test coverage

## Conclusion

This optimization plan provides a structured approach to improving EACL performance. The phased implementation allows for incremental improvements while maintaining system stability. Priority should be given to `lookup-resources` optimizations as they provide the most significant performance gains for real-world usage.

## Appendix: Detailed Rule Optimizations

### Optimized `lookup-resources` Rules

```clojure
;; Before: Scan all resources of type
;; After: Start from subject, expand outward

[(has-permission-optimized ?subject ?permission ?resource-type ?resource)
 ;; 1. Direct relationships (using tuple index)
 [(tuple ?resource ?relation ?subject) ?rel-tuple]
 [?relationship :eacl.relationship/resource+relation-name+subject ?rel-tuple]
 
 ;; 2. Permission lookup (cached)
 [(tuple ?resource-type ?relation ?permission) ?perm-tuple]
 [?perm :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]
 
 ;; 3. Type check last (only for found resources)
 [?resource :resource/type ?resource-type]]
```

### Index Usage Guidelines

1. **Primary Indices**: Use for initial lookups
2. **Composite Tuples**: Use for multi-attribute queries
3. **Range Scans**: Use `index-range` for type filtering
4. **Covering Indices**: Include all needed attributes

### Query Pattern Analysis

Most common patterns:
1. User → Direct Resources (60%)
2. User → Account → Resources (30%)
3. User → Complex Paths → Resources (10%)

Optimize for common cases while maintaining correctness for edge cases. 