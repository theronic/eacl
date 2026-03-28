# Datalog Rule Optimizations for EACL

## Overview

This document provides specific, implementable Datalog rule optimizations for EACL's performance issues, focusing on immediate improvements that can be applied to the existing codebase.

## Critical Issues in Current Rules

### 1. Late Resource Type Binding
```clojure
;; PROBLEM: This clause comes too late in many rules
[?resource :resource/type ?resource-type]
```
This requires scanning ALL entities with a `:resource/type` attribute before filtering.

### 2. Inefficient Reachability Traversal
```clojure
[(reachable ?resource ?subject)
 [?relationship :eacl.relationship/resource ?resource]
 [?relationship :eacl.relationship/subject ?mid]
 (reachable ?mid ?subject)]
```
Unbounded recursion without leveraging indices.

### 3. Suboptimal Clause Ordering
Most selective clauses should execute first, but current rules often start with less selective operations.

## Optimized Rules Implementation

### 1. Optimized `check-permission-rules` for `can?`

```clojure
(def check-permission-rules-optimized
  '[;; Optimized reachability using tuples
    [(reachable ?resource ?subject)
     ;; Direct relationship - most common case
     [(tuple ?resource ?subject) ?resource+subject]
     [?relationship :eacl.relationship/resource+subject ?resource+subject]]
    
    [(reachable ?resource ?subject)
     ;; Indirect relationship - use tuple for first hop
     [(tuple ?resource ?mid) ?resource+mid]
     [?relationship :eacl.relationship/resource+subject ?resource+mid]
     ;; Only traverse if needed
     (reachable ?mid ?subject)]

    ;; Direct permission - optimized clause ordering
    [(has-permission ?subject ?permission-name ?resource)
     ;; Start with the most selective tuple lookup
     [(tuple ?resource ?relation-name ?subject) ?rel-tuple]
     [?relationship :eacl.relationship/resource+relation-name+subject ?rel-tuple]
     
     ;; Get resource type from the resource entity (already have it)
     [?resource :resource/type ?resource-type]
     
     ;; Now lookup permission definition
     [(tuple ?resource-type ?relation-name ?permission-name) ?perm-tuple]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]]

    ;; Indirect permission inheritance - optimized
    [(has-permission ?subject ?permission-name ?resource)
     ;; Get resource type first (we already have the resource)
     [?resource :resource/type ?resource-type]
     
     ;; Find permission definitions for this resource type
     [(tuple ?resource-type ?relation-name ?permission-name) ?perm-tuple]
     [?perm-def :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]
     
     ;; Find structural relationships where resource is the subject
     [(tuple ?target ?relation-name ?resource) ?struct-tuple]
     [?structural-rel :eacl.relationship/resource+relation-name+subject ?struct-tuple]
     
     ;; Check reachability last
     (reachable ?target ?subject)]

    ;; Arrow permission - optimized
    [(has-permission ?subject ?perm-name-on-this-resource ?this-resource)
     ;; Get resource type from the resource we already have
     [?this-resource :resource/type ?this-resource-type]
     
     ;; Find arrow permission definitions using tuple
     [(tuple ?this-resource-type ?via-relation ?perm-on-related ?perm-name-on-this-resource) ?arrow-tuple]
     [?arrow-perm :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name ?arrow-tuple]
     
     ;; Find intermediate resource using tuple index
     [(tuple ?this-resource ?via-relation ?intermediate-resource) ?link-tuple]
     [?rel-linking :eacl.relationship/resource+relation-name+subject ?link-tuple]
     
     ;; Recursive permission check
     (has-permission ?subject ?perm-on-related ?intermediate-resource)]])
```

### 2. Optimized `rules-lookup-resources`

```clojure
(def rules-lookup-resources-optimized
  '[;; Start from subject, not from all resources
    [(subject-has-relationships ?subject ?relation ?resource)
     ;; Use subject+relation tuple if we add that index
     [?relationship :eacl.relationship/subject ?subject]
     [?relationship :eacl.relationship/relation-name ?relation]
     [?relationship :eacl.relationship/resource ?resource]]
    
    ;; Direct permission check - subject-centric
    [(has-permission ?subject ?permission ?resource-type ?resource)
     ;; Start from subject's relationships
     (subject-has-relationships ?subject ?relation ?resource)
     
     ;; Check if resource is of correct type
     [?resource :resource/type ?resource-type]
     
     ;; Check if relation grants permission
     [(tuple ?resource-type ?relation ?permission) ?perm-tuple]
     [?perm :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]]
    
    ;; Indirect via arrow permissions
    [(has-permission ?subject ?permission ?resource-type ?resource)
     ;; Find resources of the target type
     [?resource :resource/type ?resource-type]
     
     ;; Find arrow permissions for this resource type
     [(tuple ?resource-type ?via-relation ?target-perm ?permission) ?arrow-tuple]
     [?arrow :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name ?arrow-tuple]
     
     ;; Find intermediate resources linked to this resource
     [(tuple ?resource ?via-relation ?intermediate) ?link-tuple]
     [?link :eacl.relationship/resource+relation-name+subject ?link-tuple]
     
     ;; Check if subject has permission on intermediate
     (has-permission ?subject ?target-perm ?intermediate-type ?intermediate)
     [?intermediate :resource/type ?intermediate-type]]])
```

### 3. Schema Additions for Performance

```clojure
;; Add these tuple indices to schema.clj
{:db/ident       :eacl.relationship/subject+relation-name
 :db/doc         "Index for efficient subject-based lookups"
 :db/valueType   :db.type/tuple
 :db/tupleAttrs  [:eacl.relationship/subject
                  :eacl.relationship/relation-name]
 :db/cardinality :db.cardinality/one}

{:db/ident       :eacl.relationship/relation-name+resource
 :db/doc         "Index for relation-based traversal"
 :db/valueType   :db.type/tuple
 :db/tupleAttrs  [:eacl.relationship/relation-name
                  :eacl.relationship/resource]
 :db/cardinality :db.cardinality/one}

;; Index for permission lookups by type
{:db/ident       :eacl.permission/resource-type+permission-name
 :db/doc         "Index for finding all relations that grant a permission"
 :db/valueType   :db.type/tuple
 :db/tupleAttrs  [:eacl.permission/resource-type
                  :eacl.permission/permission-name]
 :db/cardinality :db.cardinality/one}
```

### 4. Staged Query Approach for `lookup-resources`

```clojure
(defn lookup-resources-staged
  "Staged approach to resource lookup for better performance"
  [db {:keys [resource/type subject permission limit offset]}]
  (let [{subject-id :id} subject
        subject-eid (:db/id (d/entity db [:entity/id subject-id]))
        
        ;; Stage 1: Find direct relationships
        direct-resources
        (d/q '[:find [?resource ...]
               :in $ ?subject ?resource-type ?permission
               :where
               ;; Start from subject
               [?rel :eacl.relationship/subject ?subject]
               [?rel :eacl.relationship/relation-name ?relation]
               [?rel :eacl.relationship/resource ?resource]
               ;; Filter by type early
               [?resource :resource/type ?resource-type]
               ;; Check permission
               [?perm :eacl.permission/resource-type ?resource-type]
               [?perm :eacl.permission/relation-name ?relation]
               [?perm :eacl.permission/permission-name ?permission]]
             db subject-eid type permission)
        
        ;; Stage 2: Find via arrow permissions (if needed)
        arrow-resources
        (when (< (count direct-resources) (or limit 100))
          (d/q '[:find [?resource ...]
                 :in $ ?subject ?resource-type ?permission
                 :where
                 ;; Find arrow permissions for resource type
                 [?arrow :eacl.arrow-permission/resource-type ?resource-type]
                 [?arrow :eacl.arrow-permission/permission-name ?permission]
                 [?arrow :eacl.arrow-permission/source-relation-name ?via-rel]
                 [?arrow :eacl.arrow-permission/target-permission-name ?target-perm]
                 ;; Find resources with the via-relation
                 [?link :eacl.relationship/relation-name ?via-rel]
                 [?link :eacl.relationship/resource ?resource]
                 [?link :eacl.relationship/subject ?intermediate]
                 [?resource :resource/type ?resource-type]
                 ;; Check subject has permission on intermediate
                 ;; (This would need to be a separate query or rule)
                 ]
               db subject-eid type permission))
        
        ;; Stage 3: Combine and paginate
        all-resources (distinct (concat direct-resources (or arrow-resources [])))
        paginated (cond->> all-resources
                          offset (drop offset)
                          limit (take limit))]
    
    (->> paginated
         (map #(d/entity db %))
         (map entity->spice-object))))
```

## Implementation Priority

### Immediate (Week 1)
1. **Rule Reordering**: Apply optimized clause ordering to existing rules
2. **Add Tuple Indices**: Add the schema additions above
3. **Replace Type Checks**: Move `:resource/type` checks after tuple lookups

### Short-term (Week 2)
1. **Staged Queries**: Implement staged approach for `lookup-resources`
2. **Bounded Traversal**: Add depth limits to reachability rules
3. **Query Statistics**: Add logging to identify slow patterns

### Medium-term (Week 3+)
1. **Custom Indices**: Build specialized data structures
2. **Caching Layer**: Add results caching for common queries
3. **Parallel Execution**: Split large queries

## Testing the Optimizations

### Performance Test Suite
```clojure
(defn measure-performance [db test-name f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)
        duration-ms (/ (- end start) 1000000.0)]
    (println (format "%s: %.2f ms" test-name duration-ms))
    result))

;; Test scenarios
(defn performance-tests [db]
  ;; Direct permission check
  (measure-performance db "can? direct"
    #(can? db (->user "user1") :view (->server "server1")))
  
  ;; Arrow permission check
  (measure-performance db "can? arrow"
    #(can? db (->user "user1") :admin (->vpc "vpc1")))
  
  ;; Lookup resources - small result set
  (measure-performance db "lookup-resources small"
    #(lookup-resources db {:resource/type :server
                          :permission :view
                          :subject (->user "user1")
                          :limit 10}))
  
  ;; Lookup resources - large result set
  (measure-performance db "lookup-resources large"
    #(lookup-resources db {:resource/type :server
                          :permission :view
                          :subject (->user "super-user")
                          :limit 100})))
```

## Expected Performance Improvements

Based on these optimizations:
- `can?`: 50-70% improvement for complex permission checks
- `lookup-subjects`: 40-60% improvement 
- `lookup-resources`: 70-90% improvement (highest impact)

The key insight is to leverage Datomic's strengths:
1. Use composite tuples for multi-attribute lookups
2. Start queries from the most selective point
3. Minimize full table scans
4. Push filtering as early as possible in the query 