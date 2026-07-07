# EACL Optimization Summary

## Overview

This document summarizes the comprehensive optimization plans for EACL's performance issues, particularly focusing on `lookup-resources` which is the highest priority.

## Key Findings

### Root Causes of Poor Performance

1. **Wrong Query Direction**: Current implementation starts from all resources and filters down, instead of starting from the subject and expanding outward
2. **Inefficient Resource Type Checks**: `[?resource :resource/type ?type]` clauses cause full table scans
3. **Suboptimal Index Usage**: Not leveraging Datomic's composite tuple indices effectively
4. **Unbounded Recursion**: Reachability rules can traverse deep relationship chains without limits

### Impact Analysis

- **`can?`**: Moderate impact - already performs reasonably well due to known subject and resource
- **`lookup-subjects`**: High impact - requires enumerating all potential subjects
- **`lookup-resources`**: **CRITICAL** - worst performance, especially problematic as resources greatly outnumber subjects

## Recommended Solution Path

### Phase 1: Immediate Optimizations (1-2 days)

1. **Add Missing Indices**
   ```clojure
   :eacl.relationship/subject+relation-name
   :eacl.relationship/relation-name+resource
   :eacl.permission/resource-type+permission-name
   ```

2. **Reorder Datalog Clauses**
   - Move selective clauses first
   - Place type checks after tuple lookups
   - Start from known entities (subject/resource)

3. **Quick Rule Fixes**
   - Remove redundant type checks in `can?`
   - Optimize `reachable` rules to use tuples

**Expected Impact**: 30-50% performance improvement

### Phase 2: Algorithmic Redesign (3-5 days)

1. **Invert `lookup-resources` Query Pattern**
   - Start from subject, not all resources
   - Subject → Relationships → Resources (not Resources → Filter → Check)

2. **Implement Staged Query Approach**
   - Stage 1: Direct relationships
   - Stage 2: Arrow permissions
   - Stage 3: Indirect paths (only if needed)

3. **Add Bounded Traversal**
   - Limit reachability depth
   - Cache intermediate results

**Expected Impact**: 70-90% performance improvement for `lookup-resources`

### Phase 3: Advanced Optimizations (1-2 weeks)

1. **Custom Index Structures**
   - Build auxiliary permission indices
   - Implement path compression

2. **Query Planning & Caching**
   - Analyze patterns and choose optimal strategies
   - Cache common permission paths

3. **Consider Alternative Approaches**
   - Raw index access for extreme performance
   - Parallel query execution
   - Materialized permission views

**Expected Impact**: 95%+ performance improvement possible

## Implementation Priority

1. **Week 1**: Focus on `lookup-resources` optimization (highest ROI)
2. **Week 2**: Optimize `lookup-subjects` and general rule improvements
3. **Week 3**: Advanced features and performance tuning

## Success Metrics

### Performance Targets
- `can?`: < 10ms (99th percentile)
- `lookup-subjects`: < 100ms (typical queries)
- `lookup-resources`: < 200ms (paginated, limit 100)

### Test Scenarios
- Small: 100 users, 1K resources
- Medium: 1K users, 10K resources
- Large: 10K users, 100K resources

## Risks and Mitigations

1. **Backwards Compatibility**: All optimizations maintain existing API
2. **Correctness**: Extensive test coverage ensures no regressions
3. **Complexity**: Phased approach allows incremental improvements

## Conclusion

The optimization plan addresses EACL's performance bottlenecks through a combination of:
- Better index utilization
- Inverted query patterns (subject-centric vs resource-centric)
- Staged execution with early termination
- Strategic caching

The highest priority is optimizing `lookup-resources` by inverting its query pattern from resource-centric to subject-centric, which alone should provide 10-50x performance improvement.

## Next Steps

1. Review and approve optimization plan
2. Set up performance benchmarking infrastructure
3. Begin Phase 1 implementation
4. Measure and iterate

## Related Documents

- [Comprehensive Optimization Plan](./eacl-optimization-plan.md)
- [Datalog Rule Optimizations](./datalog-rule-optimizations.md)
- [lookup-resources Deep Dive](./lookup-resources-deep-dive.md) 