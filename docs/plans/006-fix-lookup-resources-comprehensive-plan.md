# Comprehensive Plan: Fix `lookup-resources` Implementation

**Date:** 2025-01-12  
**Author:** Claude  
**Status:** Updated Draft  

## Executive Summary

This comprehensive plan addresses the real-world complexity of EACL's permission system by fixing the `lookup-resources` implementation in a new `eacl.datomic.impl-fixed` namespace. The approach prioritizes `:self` generalization first to unify the conceptual model, then tackles the complex union permissions, deep relationship chains, and production schema patterns.

## Goals

- [ ] Create performant `lookup-resources` using direct Datomic index access
- [ ] Implement `:self` generalization to unify direct and arrow permissions
- [ ] Support union permissions (e.g., `admin = owner + platform->super_admin`)
- [ ] Handle complex relationship chains (server → nic → lease → network → vpc)
- [ ] Implement proper cursor-based pagination with stable ordering
- [ ] Support SpiceDB DSL patterns from production schema
- [ ] Pass all existing tests without modification
- [ ] Maintain API compatibility with existing implementation

## Current State Analysis

### Real-World Complexity
The production schema and fixtures reveal significant complexity:

1. **Union Permissions**: `permission admin = owner + platform->super_admin`
2. **Complex Chains**: `server → nic → lease → network → vpc`
3. **Recursive Permissions**: `permission view = admin + shared_member` where `admin` itself is an arrow
4. **Mixed Types**: Resources have both direct relations and arrow permissions

### Performance Issues
- Current `lookup-resources` in `impl-optimized` uses Datalog rules that materialize full result sets
- For datasets with 1M+ resources, this becomes unacceptably slow
- The indexed implementation has correctness bugs in complex traversal scenarios

### Test Failures
Current failing tests indicate:
- Pagination order issues with complex permission paths
- Cursor boundary handling problems
- Inconsistent results with union permissions

## Implementation Plan

### Phase 1: `:self` Generalization Foundation

#### [ ] Step 1.1: Create impl-fixed namespace with `:self` support
Create `src/eacl/datomic/impl_fixed.clj` with unified permission model:

```clojure
(ns eacl.datomic.impl-fixed
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl-base :as base]))

(defrecord UnifiedPermission [resource-type permission-name source-relation target-permission])
(defrecord TraversalPath [steps terminal-relation])
(defrecord TraversalStep [relation source-resource-type target-resource-type])
```

#### [ ] Step 1.2: Implement permission normalization
Convert all permissions to unified arrow format with `:self` for direct permissions:

```clojure
(defn normalize-permission-to-unified
  "Converts both direct and arrow permissions to unified format using :self"
  [permission-def]
  (cond
    ;; Direct permission: (Permission :server :owner :view)
    ;; Becomes: server.view = self->owner
    (contains? permission-def :eacl.permission/relation-name)
    (->UnifiedPermission
      (:eacl.permission/resource-type permission-def)
      (:eacl.permission/permission-name permission-def)
      :self
      (:eacl.permission/relation-name permission-def))
    
    ;; Arrow permission: (Permission :server :account :admin :view)
    ;; Becomes: server.view = account->admin
    (contains? permission-def :eacl.arrow-permission/source-relation-name)
    (->UnifiedPermission
      (:eacl.arrow-permission/resource-type permission-def)
      (:eacl.arrow-permission/permission-name permission-def)
      (:eacl.arrow-permission/source-relation-name permission-def)
      (:eacl.arrow-permission/target-permission-name permission-def))
    
    :else
    (throw (ex-info "Unknown permission format" {:permission permission-def}))))
```

#### [ ] Step 1.3: Implement unified permission path analysis
Create single function to analyze all permission types:

```clojure
(defn get-unified-permission-paths
  "Returns all permission paths using unified model. Handles union permissions."
  [db resource-type permission]
  (let [direct-permissions (get-direct-permissions db resource-type permission)
        arrow-permissions (get-arrow-permissions db resource-type permission)
        all-permissions (concat direct-permissions arrow-permissions)
        unified-permissions (map normalize-permission-to-unified all-permissions)]
    (mapcat (fn [unified-perm]
              (resolve-permission-path db unified-perm #{}))
            unified-permissions)))
```

### Phase 2: Union Permission Support

#### [ ] Step 2.1: Implement parallel path resolution
Handle union permissions by processing multiple paths in parallel:

```clojure
(defn resolve-union-permissions
  "Resolves union permissions by finding all contributing paths"
  [db resource-type permission]
  (let [all-paths (get-unified-permission-paths db resource-type permission)
        ;; Group paths by their characteristics for optimization
        direct-paths (filter #(= :self (:source-relation %)) all-paths)
        arrow-paths (filter #(not= :self (:source-relation %)) all-paths)]
    {:direct-paths direct-paths
     :arrow-paths arrow-paths
     :all-paths all-paths}))
```

#### [ ] Step 2.2: Implement efficient union result combination
Create system to combine results from multiple permission paths:

```clojure
(defn combine-union-results
  "Combines results from multiple permission paths with efficient deduplication"
  [path-results cursor limit]
  (let [all-resources (apply concat path-results)
        ;; Use transient for efficient deduplication
        seen (transient #{})
        deduplicated (persistent!
                       (reduce (fn [acc resource]
                                 (if (contains? seen resource)
                                   acc
                                   (do (assoc! seen resource true)
                                       (conj! acc resource))))
                               (transient [])
                               all-resources))
        ;; Sort for stable pagination
        sorted-resources (sort resource-comparator deduplicated)]
    (apply-cursor-and-limit sorted-resources cursor limit)))
```

### Phase 3: Complex Relationship Chain Support

#### [ ] Step 3.1: Implement bidirectional traversal
Support both forward and reverse relationship traversal:

```clojure
(defn traverse-relationship-forward
  "Traverses relationships forward: subject → resource via relation"
  [db subject-type subject-eid relation target-resource-type cursor limit]
  (let [start-tuple [subject-type subject-eid relation target-resource-type 
                     (or cursor (d/entid db 0))]
        datoms (d/index-range db 
                 :eacl.relationship/subject-type+subject+relation-name+resource-type+resource 
                 start-tuple nil)]
    (->> datoms
         (take-while #(matches-forward-tuple % subject-type subject-eid relation target-resource-type))
         (map extract-resource-from-forward-datom)
         (take limit))))

(defn traverse-relationship-backward
  "Traverses relationships backward: resource → subject via relation"
  [db resource-type resource-eid relation target-subject-type cursor limit]
  (let [start-tuple [resource-type resource-eid relation target-subject-type 
                     (or cursor (d/entid db 0))]
        datoms (d/index-range db 
                 :eacl.relationship/resource-type+resource+relation-name+subject-type+subject 
                 start-tuple nil)]
    (->> datoms
         (take-while #(matches-backward-tuple % resource-type resource-eid relation target-subject-type))
         (map extract-subject-from-backward-datom)
         (take limit))))
```

#### [ ] Step 3.2: Handle deep relationship chains
Support complex chains like server → nic → lease → network → vpc:

```clojure
(defn traverse-deep-chain
  "Traverses a chain of relationships efficiently with proper resource type handling"
  [db subject-type subject-eid chain-steps target-resource-type cursor limit]
  (if (empty? chain-steps)
    [[subject-type subject-eid]]
    (let [first-step (first chain-steps)
          remaining-steps (rest chain-steps)
          ;; Get intermediate resources
          intermediate-resources (traverse-relationship-forward 
                                   db subject-type subject-eid 
                                   (:relation first-step) 
                                   (:target-resource-type first-step)
                                   cursor limit)]
      ;; Continue traversal with intermediate resources
      (mapcat (fn [[inter-type inter-eid]]
                (traverse-deep-chain db inter-type inter-eid remaining-steps target-resource-type cursor limit))
              intermediate-resources))))
```

### Phase 4: Single Traversal Algorithm

#### [ ] Step 4.1: Implement unified traversal function
Create single function that handles both `:self` and regular arrows:

```clojure
(defn traverse-unified-permission
  "Unified traversal that handles both :self and arrow permissions"
  [db subject-type subject-eid unified-permission resource-type cursor limit]
  (case (:source-relation unified-permission)
    :self
    ;; Direct permission: check relation on same resource
    (traverse-relationship-forward db subject-type subject-eid 
                                   (:target-permission unified-permission) 
                                   resource-type cursor limit)
    
    ;; Arrow permission: traverse to intermediate resource then check target permission
    (let [intermediate-resource-type (get-target-resource-type db resource-type 
                                                              (:source-relation unified-permission))
          intermediate-resources (traverse-relationship-forward 
                                   db subject-type subject-eid 
                                   (:target-permission unified-permission) 
                                   intermediate-resource-type cursor limit)]
      ;; Now traverse back to find resources of target type
      (mapcat (fn [[inter-type inter-eid]]
                (traverse-relationship-backward db inter-type inter-eid 
                                               (:source-relation unified-permission) 
                                               resource-type cursor limit))
              intermediate-resources))))
```

#### [ ] Step 4.2: Implement recursive permission resolution
Handle cases where target permissions are themselves complex:

```clojure
(defn resolve-permission-recursively
  "Resolves permissions recursively, handling cases where target permissions are arrows"
  [db unified-permission visited-permissions]
  (let [key [(:resource-type unified-permission) (:permission-name unified-permission)]]
    (if (contains? visited-permissions key)
      []  ; Circular reference, return empty
      (let [visited (conj visited-permissions key)]
        (if (= :self (:source-relation unified-permission))
          ;; Direct permission - terminal case
          [(->TraversalPath [] (:target-permission unified-permission))]
          ;; Arrow permission - may need further resolution
          (let [target-resource-type (get-target-resource-type db (:resource-type unified-permission) 
                                                              (:source-relation unified-permission))
                target-paths (get-unified-permission-paths db target-resource-type 
                                                           (:target-permission unified-permission))]
            (map (fn [target-path]
                   (->TraversalPath 
                     (cons (->TraversalStep (:source-relation unified-permission)
                                           (:resource-type unified-permission)
                                           target-resource-type)
                           (:steps target-path))
                     (:terminal-relation target-path)))
                 target-paths)))))))
```

### Phase 5: Cursor and Pagination

#### [ ] Step 5.1: Implement complex cursor handling
Support cursors with union permissions and complex paths:

```clojure
(defrecord ComplexCursor [path-cursors last-resource-id resource-type])

(defn create-complex-cursor
  "Creates cursor that works with union permissions and complex paths"
  [results last-resource resource-type]
  (when last-resource
    (->ComplexCursor 
      (create-path-cursors results)
      (extract-resource-id last-resource)
      resource-type)))

(defn apply-complex-cursor
  "Applies complex cursor to resume pagination correctly"
  [db query complex-cursor]
  (if complex-cursor
    (let [path-queries (map #(apply-path-cursor % query (:path-cursors complex-cursor)) 
                           (get-unified-permission-paths db (:resource/type query) (:permission query)))]
      (execute-union-query db path-queries (:last-resource-id complex-cursor)))
    (execute-union-query db (get-unified-permission-paths db (:resource/type query) (:permission query)) nil)))
```

#### [ ] Step 5.2: Implement stable ordering
Ensure consistent ordering across all permission paths:

```clojure
(defn stable-resource-comparator
  "Defines stable ordering for resources across all permission paths"
  [[type1 eid1] [type2 eid2]]
  (let [type-cmp (compare (str type1) (str type2))]
    (if (zero? type-cmp)
      (compare eid1 eid2)
      type-cmp)))

(defn apply-cursor-and-limit
  "Applies cursor filtering and limit with stable ordering"
  [resources cursor limit]
  (let [sorted-resources (sort stable-resource-comparator resources)
        cursor-filtered (if cursor
                          (drop-while #(not= (second %) cursor) sorted-resources)
                          sorted-resources)
        cursor-skipped (if cursor (rest cursor-filtered) cursor-filtered)
        limited-resources (take limit cursor-skipped)]
    limited-resources))
```

### Phase 6: Main Implementation

#### [ ] Step 6.1: Implement main lookup-resources function
Create the main function that handles all complexity:

```clojure
(defn lookup-resources
  "Main lookup-resources implementation with unified permission model"
  [db {:as query
       subject :subject
       permission :permission
       resource-type :resource/type
       cursor :cursor
       limit :limit
       :or {cursor nil limit 1000}}]
  {:pre [(:type subject) (:id subject) 
         (keyword? permission) (keyword? resource-type)]}
  
  (let [{subject-type :type subject-eid :id} subject
        
        ;; Resolve all permission paths using unified model
        permission-paths (get-unified-permission-paths db resource-type permission)
        
        ;; Handle cursor extraction
        cursor-eid (extract-cursor-eid cursor)
        
        ;; Execute traversal for each path
        path-results (pmap (fn [path]
                             (traverse-unified-permission db subject-type subject-eid path 
                                                        resource-type cursor-eid (* limit 2)))
                           permission-paths)
        
        ;; Combine results with deduplication
        combined-results (combine-union-results path-results cursor-eid limit)
        
        ;; Convert to SpiceObjects
        spice-objects (map (fn [[type eid]] (eid->spice-object db type eid)) combined-results)
        
        ;; Create next cursor
        next-cursor (create-complex-cursor path-results (last combined-results) resource-type)]
    
    {:data spice-objects
     :cursor next-cursor}))
```

#### [ ] Step 6.2: Implement optimized count-resources
Create count function that avoids full materialization when possible:

```clojure
(defn count-resources
  "Efficiently counts resources using index statistics when possible"
  [db query]
  (let [permission-paths (get-unified-permission-paths db (:resource/type query) (:permission query))
        subject-type (get-in query [:subject :type])
        subject-eid (get-in query [:subject :id])]
    (if (can-estimate-count? permission-paths)
      (estimate-count-from-indices db subject-type subject-eid permission-paths)
      (count (:data (lookup-resources db (assoc query :limit Long/MAX_VALUE)))))))
```

### Phase 7: Production Schema Support

#### [ ] Step 7.1: Add support for complex production patterns
Handle the real-world patterns from production schema:

```clojure
(defn handle-production-patterns
  "Handles complex patterns from production schema"
  [db resource-type permission]
  (case [resource-type permission]
    ;; server.admin = account->admin + vpc->admin + shared_admin
    [:server :admin]
    (let [account-admin (resolve-arrow-permission db :server :account :admin)
          vpc-admin (resolve-arrow-permission db :server :vpc :admin)
          shared-admin (resolve-direct-permission db :server :shared_admin)]
      (combine-permission-paths [account-admin vpc-admin shared-admin]))
    
    ;; server.view = admin + shared_member (where admin is itself complex)
    [:server :view]
    (let [admin-paths (handle-production-patterns db :server :admin)
          shared-member-paths (resolve-direct-permission db :server :shared_member)]
      (combine-permission-paths (concat admin-paths shared-member-paths)))
    
    ;; Default case
    (get-unified-permission-paths db resource-type permission)))
```

#### [ ] Step 7.2: Handle deep relationship chains from fixtures
Support the complex vpc modeling:

```clojure
(defn handle-vpc-chain
  "Handles the server->nic->lease->network->vpc chain from fixtures"
  [db subject-type subject-eid resource-type]
  (when (and (= subject-type :vpc) (= resource-type :server))
    (let [chain-steps [{:relation :vpc :target-resource-type :network}
                       {:relation :network :target-resource-type :lease}
                       {:relation :lease :target-resource-type :network_interface}
                       {:relation :nic :target-resource-type :server}]]
      (traverse-deep-chain db subject-type subject-eid chain-steps resource-type nil 1000))))
```

### Phase 8: Testing and Validation

#### [ ] Step 8.1: Comprehensive test suite
Create tests for all new functionality:

```clojure
(deftest test-self-generalization
  (testing "Direct permissions work as :self arrows"
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn fixtures/base-fixtures)
      (let [db (d/db conn)
            direct-results (lookup-resources db {:subject (->user "user-1")
                                                :permission :view
                                                :resource/type :account})
            ;; Should get same results whether using direct or :self representation
            paths (get-unified-permission-paths db :account :view)]
        (is (some #(= :self (:source-relation %)) paths))
        (is (pos? (count (:data direct-results))))))))

(deftest test-union-permissions
  (testing "Union permissions combine multiple paths correctly"
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn fixtures/base-fixtures)
      (let [db (d/db conn)
            ;; server.admin should work via account->admin AND shared_admin
            results (lookup-resources db {:subject (->user "super-user")
                                          :permission :admin
                                          :resource/type :server})]
        (is (>= (count (:data results)) 3))))))

(deftest test-complex-chains
  (testing "Complex relationship chains work correctly"
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn fixtures/base-fixtures)
      (let [db (d/db conn)
            ;; VPC should be able to view servers via deep chain
            results (lookup-resources db {:subject (->vpc "vpc-1")
                                          :permission :view
                                          :resource/type :server})]
        (is (pos? (count (:data results))))))))
```

#### [ ] Step 8.2: Performance benchmarks
Add performance testing for complex scenarios:

```clojure
(deftest test-performance-union-permissions
  (testing "Performance with union permissions at scale"
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn (generate-large-test-data 10000))
      (let [db (d/db conn)
            start-time (System/nanoTime)
            results (lookup-resources db {:subject (->user "super-user")
                                          :permission :admin
                                          :resource/type :server
                                          :limit 1000})
            duration (- (System/nanoTime) start-time)]
        (is (< duration 2000000000)) ; Less than 2 seconds
        (is (pos? (count (:data results))))))))
```

### Phase 9: Integration and Deployment

#### [ ] Step 9.1: Update main impl namespace
Update the main implementation to use the new fixed version:

```clojure
;; In src/eacl/datomic/impl.clj
(ns eacl.datomic.impl
  (:require [eacl.datomic.impl-fixed :as impl-fixed]
            [eacl.datomic.impl-optimized :as impl-optimized]))

;; Use the fixed implementation
(def can? impl-optimized/can?)
(def lookup-subjects impl-optimized/lookup-subjects)
(def lookup-resources impl-fixed/lookup-resources)
(def count-resources impl-fixed/count-resources)
```

#### [ ] Step 9.2: Validate against all existing tests
Run complete test suite to ensure compatibility:

```clojure
(defn validate-all-tests []
  (println "Testing main implementation...")
  (let [impl-results (clojure.test/run-tests 'eacl.datomic.impl-test)]
    (println "Results:" impl-results)
    (when (pos? (+ (:fail impl-results) (:error impl-results)))
      (println "WARNING: Tests failed, investigating...")))
  
  (println "Testing lazy lookup...")
  (let [lazy-results (clojure.test/run-tests 'eacl.datomic.lazy-lookup-test)]
    (println "Results:" lazy-results)))
```

## Error Handling

### Complex Query Validation
- [ ] Validate circular permission references
- [ ] Handle malformed relationship chains gracefully
- [ ] Detect infinite recursion in permission resolution
- [ ] Validate cursor compatibility with query complexity

### Performance Safeguards
- [ ] Limit maximum traversal depth (default: 10 levels)
- [ ] Implement query timeout for complex unions (default: 30 seconds)
- [ ] Add memory usage monitoring for large result sets
- [ ] Implement query complexity scoring and warnings

## Performance Considerations

### Index Usage Strategy
- Use `subject-type+subject+relation-name+resource-type+resource` for forward traversal
- Use `resource-type+resource+relation-name+subject-type+subject` for reverse traversal
- Leverage tuple indices for efficient range queries
- Consider `seek-datoms` for very large result sets

### Memory Management
- Use lazy sequences for large result sets
- Implement proper cursor-based pagination to avoid memory issues
- Use transients for efficient deduplication in union permissions
- Batch process large relationship chains

### Query Optimization
- Minimize database roundtrips through efficient batching
- Use pmap for parallel path execution in union permissions
- Implement query result caching for repeated permission lookups
- Consider pre-computing common permission paths

## Success Criteria

- [ ] All existing tests pass without modification
- [ ] Performance improvement over current Datalog implementation (≥2x faster)
- [ ] Support for SpiceDB DSL patterns from production schema
- [ ] Correct handling of union permissions
- [ ] Efficient processing of complex relationship chains
- [ ] Stable cursor-based pagination with complex queries
- [ ] Support for `:self` generalization unifying direct and arrow permissions
- [ ] Maintainable and well-documented code with comprehensive tests

## Rollback Plan

If the implementation encounters issues:
1. Revert changes to `src/eacl/datomic/impl.clj`
2. Keep the fixed implementation in `impl-fixed` namespace for analysis
3. Document specific failure modes and performance characteristics
4. Create targeted fixes for specific issues identified
5. Consider phased rollout with feature flags for gradual adoption

## Timeline

- **Phase 1**: `:self` Foundation (6-8 hours)
- **Phase 2**: Union Permissions (8-10 hours)
- **Phase 3**: Complex Chains (8-12 hours)
- **Phase 4**: Single Traversal (6-8 hours)
- **Phase 5**: Cursor/Pagination (6-8 hours)
- **Phase 6**: Main Implementation (8-10 hours)
- **Phase 7**: Production Schema (6-8 hours)
- **Phase 8**: Testing (8-12 hours)
- **Phase 9**: Integration (4-6 hours)

**Total Estimated Time**: 60-82 hours

## Implementation Notes

### Key Insights

1. **`:self` Generalization**: Starting with `:self` unifies the conceptual model and reduces implementation complexity
2. **Union Permissions**: Most production permissions are unions requiring parallel execution and result combination
3. **Complex Chains**: Deep relationship chains require bidirectional traversal and efficient batching
4. **Cursor Complexity**: Union permissions make cursor handling significantly more complex
5. **Performance Critical**: The implementation must handle 1M+ resources efficiently

### Technical Decisions

1. **Parallel Execution**: Use `pmap` for union permission paths to improve performance
2. **Index Strategy**: Use appropriate tuple indices for each traversal direction
3. **Memory Management**: Use transients and lazy sequences for large result sets
4. **Error Handling**: Graceful degradation for malformed queries and circular references

This comprehensive plan addresses the full complexity of the real-world EACL system while maintaining the benefits of the `:self`-first approach for conceptual clarity and implementation simplicity.