# Updated Plan: Fix `lookup-resources` Implementation

**Date:** 2025-01-12  
**Author:** Claude  
**Status:** Updated Draft  

## Executive Summary

This updated plan addresses the real-world complexity of EACL's permission system, including:
- Complex relationship chains (server → nic → lease → network → vpc)
- Union permissions (e.g., `admin = owner + platform->super_admin`)
- Mixed direct and arrow permissions
- Support for the SpiceDB DSL patterns while handling the complex fixtures

## Goals

- [ ] Create performant `lookup-resources` using direct Datomic index access
- [ ] Support complex relationship chains with proper bidirectional traversal
- [ ] Handle union permissions correctly (direct + arrow combinations)
- [ ] Implement `:self` generalization to unify direct and arrow permissions
- [ ] Fix cursor-based pagination with stable ordering
- [ ] Pass all existing tests without modification
- [ ] Support SpiceDB DSL patterns from production schema

## Current State Analysis

### Real-World Complexity
Looking at the production schema and fixtures, we have:

1. **Complex Relationship Chains**: `server → nic → lease → network → vpc`
2. **Union Permissions**: `permission admin = account->admin + vpc->admin + shared_admin`
3. **Mixed Permission Types**: Both direct relations and arrow permissions on same resource
4. **Recursive Arrows**: `permission view = admin + shared_member` where `admin` itself is an arrow

### Current Implementation Issues
- `impl-indexed` fails on complex relationship chains
- No support for union permissions (multiple permission paths)
- Cursor pagination breaks with complex ordering
- Performance degrades with deep relationship chains

## Implementation Plan

### Phase 1: `:self` Generalization Foundation

#### [ ] Step 1.1: Extend schema to support `:self` conceptually
Create internal representation where all permissions are arrows:

```clojure
(defn normalize-permission-to-arrow
  "Converts direct permissions to arrow permissions using :self"
  [permission-def]
  (if (contains? permission-def :eacl.permission/relation-name)
    ;; Direct permission: (Permission :server :owner :view)
    ;; Becomes: server.view = self->owner (check :owner on current resource)
    {:type :arrow
     :resource-type (:eacl.permission/resource-type permission-def)
     :permission-name (:eacl.permission/permission-name permission-def)
     :source-relation :self
     :target-permission (:eacl.permission/relation-name permission-def)}
    ;; Arrow permission: (Permission :server :account :admin :view)  
    ;; Becomes: server.view = account->admin
    {:type :arrow
     :resource-type (:eacl.arrow-permission/resource-type permission-def)
     :permission-name (:eacl.arrow-permission/permission-name permission-def)
     :source-relation (:eacl.arrow-permission/source-relation-name permission-def)
     :target-permission (:eacl.arrow-permission/target-permission-name permission-def)}))
```

#### [ ] Step 1.2: Create unified permission path analysis
Handle union permissions by finding ALL paths that can grant a permission:

```clojure
(defn get-unified-permission-paths
  "Returns all permission paths for a given resource-type and permission.
   Handles union permissions by returning multiple paths."
  [db resource-type permission]
  (let [direct-permissions (get-direct-permissions db resource-type permission)
        arrow-permissions (get-arrow-permissions db resource-type permission)
        all-permissions (concat direct-permissions arrow-permissions)]
    ;; Convert all to normalized arrow format
    (map normalize-permission-to-arrow all-permissions)))
```

#### [ ] Step 1.3: Implement recursive permission resolution
Handle cases where target permissions are themselves arrows:

```clojure
(defn resolve-permission-recursively
  "Resolves a permission to its base relations, handling recursive arrows."
  [db resource-type permission visited-permissions]
  (if (contains? visited-permissions [resource-type permission])
    []  ; Circular reference, return empty
    (let [visited (conj visited-permissions [resource-type permission])
          direct-paths (get-direct-permission-paths db resource-type permission)
          arrow-paths (get-arrow-permission-paths db resource-type permission visited)]
      (concat direct-paths arrow-paths))))
```

### Phase 2: Complex Relationship Chain Support

#### [ ] Step 2.1: Implement bidirectional index traversal
Create efficient traversal that can go both forward and backward:

```clojure
(defn traverse-relationship-forward
  "Traverses relationships forward: subject → resource via relation"
  [db subject-type subject-eid relation target-resource-type]
  (let [start-tuple [subject-type subject-eid relation target-resource-type nil]
        datoms (d/index-range db 
                 :eacl.relationship/subject-type+subject+relation-name+resource-type+resource 
                 start-tuple nil)]
    (->> datoms
         (take-while #(matches-tuple-prefix % [subject-type subject-eid relation target-resource-type]))
         (map extract-resource-from-datom))))

(defn traverse-relationship-backward
  "Traverses relationships backward: resource → subject via relation"
  [db resource-type resource-eid relation target-subject-type]
  (let [start-tuple [resource-type resource-eid relation target-subject-type nil]
        datoms (d/index-range db 
                 :eacl.relationship/resource-type+resource+relation-name+subject-type+subject 
                 start-tuple nil)]
    (->> datoms
         (take-while #(matches-tuple-prefix % [resource-type resource-eid relation target-subject-type]))
         (map extract-subject-from-datom))))
```

#### [ ] Step 2.2: Handle deep relationship chains
Support the server → nic → lease → network → vpc chain:

```clojure
(defn traverse-deep-chain
  "Traverses a chain of relationships efficiently"
  [db subject-type subject-eid chain-steps target-resource-type]
  (reduce (fn [current-resources step]
            (let [{:keys [relation target-type]} step]
              (mapcat (fn [[res-type res-eid]]
                        (traverse-relationship-forward db res-type res-eid relation target-type))
                      current-resources)))
          [[subject-type subject-eid]]
          chain-steps))
```

### Phase 3: Union Permission Support

#### [ ] Step 3.1: Implement parallel path traversal
Handle union permissions by traversing multiple paths in parallel:

```clojure
(defn lookup-resources-union-permissions
  "Handles union permissions by combining results from multiple paths"
  [db subject-type subject-eid permission-paths resource-type cursor limit]
  (let [all-results (pmap (fn [path]
                            (lookup-resources-single-path db subject-type subject-eid path resource-type cursor (* limit 2)))
                          permission-paths)]
    ;; Combine and deduplicate results
    (combine-and-deduplicate-results all-results cursor limit)))
```

#### [ ] Step 3.2: Implement efficient result combination
Create efficient deduplication and sorting for union results:

```clojure
(defn combine-and-deduplicate-results
  "Combines results from multiple permission paths, removing duplicates"
  [path-results cursor limit]
  (let [all-resources (apply concat path-results)
        deduplicated (deduplicate-preserving-order all-resources)
        sorted-resources (sort-by-stable-order deduplicated)]
    (apply-cursor-and-limit sorted-resources cursor limit)))
```

### Phase 4: Efficient Single Path Traversal

#### [ ] Step 4.1: Implement optimized single path lookup
Handle individual permission paths efficiently:

```clojure
(defn lookup-resources-single-path
  "Efficiently lookup resources for a single permission path"
  [db subject-type subject-eid path resource-type cursor limit]
  (case (:type path)
    :direct-self (lookup-direct-self-permission db subject-type subject-eid path resource-type cursor limit)
    :arrow-self (lookup-arrow-self-permission db subject-type subject-eid path resource-type cursor limit)
    :arrow-chain (lookup-arrow-chain-permission db subject-type subject-eid path resource-type cursor limit)))
```

#### [ ] Step 4.2: Implement direct self permission lookup
Handle `:self` permissions (former direct permissions):

```clojure
(defn lookup-direct-self-permission
  "Lookup resources where subject has direct relation (via :self)"
  [db subject-type subject-eid path resource-type cursor limit]
  (let [relation (:target-permission path)
        start-tuple [subject-type subject-eid relation resource-type (cursor-start cursor)]
        datoms (d/index-range db 
                 :eacl.relationship/subject-type+subject+relation-name+resource-type+resource 
                 start-tuple nil)]
    (->> datoms
         (take-while #(matches-subject-relation-resource % subject-type subject-eid relation resource-type))
         (map extract-resource-info)
         (apply-cursor-filter cursor)
         (take limit))))
```

#### [ ] Step 4.3: Implement arrow chain permission lookup
Handle complex arrow permission chains:

```clojure
(defn lookup-arrow-chain-permission
  "Lookup resources via arrow permission chain"
  [db subject-type subject-eid path resource-type cursor limit]
  (let [steps (build-traversal-steps db path)
        final-resources (traverse-steps-backward db subject-type subject-eid steps resource-type)]
    (->> final-resources
         (apply-cursor-filter cursor)
         (take limit))))
```

### Phase 5: Advanced Features

#### [ ] Step 5.1: Implement efficient cursor handling
Create cursor system that works with union permissions:

```clojure
(defrecord UnionCursor [path-cursors last-resource-id])

(defn create-union-cursor
  "Creates cursor for union permission results"
  [path-results last-resource]
  (let [path-cursors (map create-path-cursor path-results)
        last-id (extract-resource-id last-resource)]
    (->UnionCursor path-cursors last-id)))

(defn apply-union-cursor
  "Applies cursor to union permission query"
  [db query union-cursor]
  (let [path-queries (map #(apply-path-cursor % query) (:path-cursors union-cursor))]
    (execute-union-query db path-queries)))
```

#### [ ] Step 5.2: Implement stable ordering for complex results
Ensure consistent ordering across different permission paths:

```clojure
(defn stable-resource-order
  "Defines stable ordering for resources across all permission paths"
  [resource-a resource-b]
  (let [type-cmp (compare (str (:type resource-a)) (str (:type resource-b)))]
    (if (zero? type-cmp)
      (compare (:id resource-a) (:id resource-b))
      type-cmp)))

(defn sort-resources-stably
  "Sorts resources using stable ordering"
  [resources]
  (sort stable-resource-order resources))
```

### Phase 6: Production Schema Support

#### [ ] Step 6.1: Add support for SpiceDB DSL patterns
Handle the production schema patterns:

```clojure
;; Support for: permission admin = owner + platform->super_admin
(defn parse-union-permission
  "Parse union permission syntax from production schema"
  [permission-def]
  (let [parts (parse-permission-expression (:expression permission-def))]
    {:type :union
     :resource-type (:resource-type permission-def)
     :permission-name (:permission-name permission-def)
     :parts (map parse-permission-part parts)}))

;; Support for: permission view = admin + shared_member  
(defn resolve-indirect-permission
  "Resolve permissions that reference other permissions"
  [db resource-type permission]
  (let [direct-refs (get-direct-permission-refs db resource-type permission)
        indirect-refs (get-indirect-permission-refs db resource-type permission)]
    (concat direct-refs (mapcat #(resolve-indirect-permission db %) indirect-refs))))
```

#### [ ] Step 6.2: Handle complex production scenarios
Support the real-world patterns from the production schema:

```clojure
;; server.admin = account->admin + vpc->admin + shared_admin
(defn handle-triple-union
  "Handle permissions with multiple union parts"
  [db subject-type subject-eid resource-type permission]
  (let [account-admin-path (resolve-arrow-permission db :server :account :admin)
        vpc-admin-path (resolve-arrow-permission db :server :vpc :admin)
        shared-admin-path (resolve-direct-permission db :server :shared_admin)]
    (combine-permission-paths [account-admin-path vpc-admin-path shared-admin-path])))

;; Handle complex chains like server → nic → lease → network → vpc
(defn handle-deep-relationship-chain
  "Handle the server->nic->lease->network->vpc chain from fixtures"
  [db subject-type subject-eid]
  (let [chain-steps [{:relation :nic :target-type :network_interface}
                     {:relation :lease :target-type :lease}
                     {:relation :network :target-type :network}
                     {:relation :vpc :target-type :vpc}]]
    (traverse-deep-chain db subject-type subject-eid chain-steps :vpc)))
```

### Phase 7: Main Implementation

#### [ ] Step 7.1: Implement main lookup-resources function
Create the main function that handles all cases:

```clojure
(defn lookup-resources
  "Main lookup-resources implementation with full feature support"
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
        
        ;; Resolve all permission paths (handles unions, recursion, etc.)
        permission-paths (resolve-permission-recursively db resource-type permission #{})
        
        ;; Handle different cursor types
        normalized-cursor (normalize-cursor cursor)
        
        ;; Execute query based on path complexity
        results (cond
                  (single-path? permission-paths)
                  (lookup-resources-single-path db subject-type subject-eid (first permission-paths) resource-type normalized-cursor limit)
                  
                  (union-paths? permission-paths)
                  (lookup-resources-union-permissions db subject-type subject-eid permission-paths resource-type normalized-cursor limit)
                  
                  :else
                  (lookup-resources-complex-paths db subject-type subject-eid permission-paths resource-type normalized-cursor limit))
        
        ;; Convert to SpiceObjects and create cursor
        spice-objects (map #(eid->spice-object db (:type %) (:id %)) (:resources results))
        next-cursor (create-appropriate-cursor results)]
    
    {:data spice-objects
     :cursor next-cursor}))
```

#### [ ] Step 7.2: Implement count-resources with optimization
Create efficient count that doesn't need full materialization:

```clojure
(defn count-resources
  "Efficiently counts resources using index statistics when possible"
  [db query]
  (let [permission-paths (resolve-permission-recursively db (:resource/type query) (:permission query) #{})
        subject-type (get-in query [:subject :type])
        subject-eid (get-in query [:subject :id])]
    (if (can-use-index-statistics? permission-paths)
      (count-via-index-statistics db subject-type subject-eid permission-paths)
      (count (:data (lookup-resources db (assoc query :limit Long/MAX_VALUE)))))))
```

### Phase 8: Testing and Validation

#### [ ] Step 8.1: Create comprehensive test suite
Add tests for all new functionality:

```clojure
(deftest test-self-permission-conversion
  (testing "Direct permissions convert to :self arrows"
    (let [direct-perm (Permission :server :owner :view)
          arrow-perm (normalize-permission-to-arrow direct-perm)]
      (is (= :self (:source-relation arrow-perm)))
      (is (= :owner (:target-permission arrow-perm))))))

(deftest test-union-permissions
  (testing "Union permissions combine multiple paths"
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn fixtures/base-fixtures)
      (let [db (d/db conn)
            results (lookup-resources db {:subject (->user "super-user")
                                          :permission :admin
                                          :resource/type :server})]
        ;; Should find servers via account->admin AND vpc->admin AND shared_admin
        (is (>= (count (:data results)) 3))))))

(deftest test-complex-chains
  (testing "Complex relationship chains work correctly"
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn fixtures/base-fixtures)
      (let [db (d/db conn)
            results (lookup-resources db {:subject (->vpc "vpc-1")
                                          :permission :view
                                          :resource/type :server})]
        ;; Should find servers via vpc->network->lease->nic->server chain
        (is (pos? (count (:data results))))))))
```

#### [ ] Step 8.2: Performance testing
Add performance benchmarks for complex scenarios:

```clojure
(deftest test-performance-complex-unions
  (testing "Performance with complex union permissions"
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn (generate-large-test-data 10000))
      (let [db (d/db conn)
            start-time (System/nanoTime)
            results (lookup-resources db {:subject (->user "super-user")
                                          :permission :admin
                                          :resource/type :server
                                          :limit 1000})
            duration (- (System/nanoTime) start-time)]
        (is (< duration 1000000000)) ; Less than 1 second
        (is (pos? (count (:data results))))))))
```

### Phase 9: Integration and Deployment

#### [ ] Step 9.1: Update main impl namespace
Update the main implementation to use the new fixed version:

```clojure
;; In src/eacl/datomic/impl.clj
(def lookup-resources impl-fixed/lookup-resources)
(def count-resources impl-fixed/count-resources)
```

#### [ ] Step 9.2: Validate against all existing tests
Run the complete test suite to ensure compatibility:

```clojure
(defn validate-all-tests []
  (println "Running impl-test...")
  (clojure.test/run-tests 'eacl.datomic.impl-test)
  (println "Running lazy-lookup-test...")
  (clojure.test/run-tests 'eacl.datomic.lazy-lookup-test)
  (println "Running performance-test...")
  (clojure.test/run-tests 'eacl.datomic.performance-test))
```

## Error Handling

### Complex Query Validation
- [ ] Validate circular permission references
- [ ] Handle malformed relationship chains
- [ ] Detect infinite recursion in permission resolution
- [ ] Validate cursor compatibility with query type

### Performance Safeguards
- [ ] Limit maximum traversal depth
- [ ] Implement query timeout for complex unions
- [ ] Add memory usage monitoring
- [ ] Implement query complexity scoring

## Success Criteria

- [ ] All existing tests pass without modification
- [ ] Support for SpiceDB DSL patterns from production schema
- [ ] Correct handling of complex relationship chains (server → nic → lease → network → vpc)
- [ ] Efficient union permission processing
- [ ] Stable cursor-based pagination
- [ ] Performance improvement over current implementation
- [ ] Support for `:self` generalization

## Implementation Notes

### Key Insights from Production Schema Analysis

1. **Union Permissions are Common**: Most permissions in production are unions (e.g., `admin = owner + platform->super_admin`)

2. **Recursive Permission References**: Permissions often reference other permissions (e.g., `view = admin + shared_member`)

3. **Complex Relationship Chains**: The fixtures show deep chains that must be traversed efficiently

4. **Mixed Permission Types**: Resources often have both direct relations and arrow permissions

### Technical Considerations

1. **Index Usage**: The implementation must use appropriate Datomic indices for each traversal direction

2. **Memory Management**: Union permissions can generate large result sets that need efficient handling

3. **Cursor Complexity**: Cursors become more complex with union permissions and need special handling

4. **Query Optimization**: Complex queries need optimization to prevent performance degradation

## Timeline

- **Phase 1**: `:self` Foundation (4-6 hours)
- **Phase 2**: Complex Chains (6-8 hours)
- **Phase 3**: Union Permissions (8-10 hours)
- **Phase 4**: Single Path Optimization (4-6 hours)
- **Phase 5**: Advanced Features (6-8 hours)
- **Phase 6**: Production Schema Support (4-6 hours)
- **Phase 7**: Main Implementation (6-8 hours)
- **Phase 8**: Testing (6-8 hours)
- **Phase 9**: Integration (2-4 hours)

**Total Estimated Time**: 46-64 hours

This updated plan addresses the real-world complexity of the EACL system while maintaining the benefits of the `:self` generalization approach.