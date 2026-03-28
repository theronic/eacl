# Technical Design: Index-Range Usage for EACL Optimization

Date: 2025-01-03

## Overview

This document provides detailed technical designs for using Datomic's `index-range` API to optimize EACL queries.

## Index-Range API Basics

```clojure
(d/index-range db attr start end)
;; Returns datoms in AVET index order
;; For tuple attrs, comparison is component-wise
```

## Tuple Index Usage Patterns

### 1. Finding Direct Permissions

**Index**: `:eacl.relationship/subject+relation-name`

```clojure
(defn find-direct-resources-via-index
  [db subject-eid resource-type permission]
  ;; Step 1: Find permission definitions
  (let [perm-defs (d/index-range db 
                    :eacl.permission/resource-type+permission-name
                    [resource-type permission]
                    [resource-type permission])
        
        ;; Extract relation names that grant this permission
        relation-names (map #(:eacl.permission/relation-name (d/entity db (:e %))) 
                           perm-defs)]
    
    ;; Step 2: For each relation, find resources
    (for [rel-name relation-names
          :let [datoms (d/index-range db
                         :eacl.relationship/subject+relation-name
                         [subject-eid rel-name]
                         [subject-eid rel-name])]
          datom datoms
          :let [relationship (d/entity db (:e datom))
                resource (:eacl.relationship/resource relationship)]
          :when (= resource-type (:resource/type resource))]
      resource)))
```

### 2. Arrow Permission Traversal

**Indices**: 
- `:eacl.arrow-permission/resource-type+permission-name`
- `:eacl.relationship/relation-name+resource`

```clojure
(defn find-arrow-permission-resources
  [db subject-eid resource-type permission]
  ;; Step 1: Find arrow permission definitions
  (let [arrow-perms (d/index-range db
                      :eacl.arrow-permission/resource-type+permission-name
                      [resource-type permission]
                      [resource-type permission])
        
        arrow-defs (map #(d/entity db (:e %)) arrow-perms)]
    
    ;; Step 2: For each arrow permission
    (for [arrow-def arrow-defs
          :let [via-rel (:eacl.arrow-permission/source-relation-name arrow-def)
                target-perm (:eacl.arrow-permission/target-permission-name arrow-def)]
          
          ;; Find intermediate resources the subject has target-perm on
          intermediate (find-all-resources-with-permission 
                         db subject-eid target-perm)
          
          ;; Find resources connected via the relation
          :let [datoms (d/index-range db
                         :eacl.relationship/resource+relation-name+subject
                         [nil via-rel (:db/id intermediate)]
                         [nil via-rel (:db/id intermediate)])]
          datom datoms
          :let [resource (d/entity db (nth (:v datom) 0))] ; resource is first component
          :when (= resource-type (:resource/type resource))]
      resource)))
```

### 3. Pagination with Index-Range

```clojure
(defn paginated-index-scan
  [db attr start-val end-val offset limit]
  (let [all-datoms (d/index-range db attr start-val end-val)]
    (->> all-datoms
         (drop offset)
         (take limit)
         vec)))

;; For stable pagination across multiple indices
(defn merge-paginated-results
  [& result-seqs]
  (let [;; Deduplicate by entity ID
        seen (atom #{})
        deduped (filter (fn [e]
                         (let [eid (:db/id e)]
                           (when-not (@seen eid)
                             (swap! seen conj eid)
                             true)))
                       (apply concat result-seqs))]
    ;; Sort by entity ID for stable ordering
    (sort-by :db/id deduped)))
```

### 4. Optimized lookup-resources Implementation

```clojure
(defn lookup-resources-indexed
  [db {:keys [resource/type subject permission limit offset]
       :or {limit 1000 offset 0}}]
  (let [subject-eid (:db/id (d/entity db [:entity/id (:id subject)]))
        
        ;; Stage 1: Direct permissions via index
        direct-resources (find-direct-resources-via-index 
                          db subject-eid type permission)
        
        ;; Stage 2: Arrow permissions (if needed)
        arrow-resources (when (< (count direct-resources) (+ offset limit))
                         (find-arrow-permission-resources
                          db subject-eid type permission))
        
        ;; Merge and paginate
        all-resources (merge-paginated-results 
                       direct-resources 
                       arrow-resources)]
    
    (->> all-resources
         (drop offset)
         (take limit)
         (map entity->spice-object))))
```

### 5. Optimized lookup-subjects Implementation

```clojure
(defn lookup-subjects-indexed
  [db {:keys [resource permission subject/type limit offset]
       :or {limit 1000 offset 0}}]
  (let [resource-eid (:db/id (d/entity db [:entity/id (:id resource)]))
        resource-type (:resource/type resource)
        
        ;; Find permission definitions
        perm-datoms (d/index-range db
                      :eacl.permission/resource-type+permission-name
                      [resource-type permission]
                      [resource-type permission])
        
        rel-names (map #(:eacl.permission/relation-name (d/entity db (:e %))) 
                      perm-datoms)
        
        ;; For each relation, find subjects
        subjects (for [rel-name rel-names
                       :let [datoms (d/index-range db
                                     :eacl.relationship/resource+relation-name+subject
                                     [resource-eid rel-name]
                                     [(inc resource-eid) nil])] ; scan all subjects
                       datom datoms
                       :let [subject-eid (nth (:v datom) 2) ; subject is 3rd component
                             subject (d/entity db subject-eid)]
                       :when (= type (:resource/type subject))]
                   subject)]
    
    (->> subjects
         distinct
         (drop offset)
         (take limit)
         (map entity->spice-object))))
```

### 6. Optimized can? Implementation

```clojure
(defn can-indexed?
  [db subject-id permission resource-id]
  (let [subject-eid (:db/id (d/entity db subject-id))
        resource-eid (:db/id (d/entity db resource-id))
        resource-type (:resource/type (d/entity db resource-eid))]
    
    ;; Quick check using index existence
    (or
     ;; Check direct permission via tuple index
     (check-direct-permission-indexed 
      db subject-eid resource-eid resource-type permission)
     
     ;; Check arrow permissions
     (check-arrow-permission-indexed
      db subject-eid resource-eid resource-type permission)
     
     ;; Fall back to limited Datalog for complex cases
     (check-complex-permission-datalog
      db subject-eid resource-eid permission))))

(defn check-direct-permission-indexed
  [db subject-eid resource-eid resource-type permission]
  ;; Use resource+relation+subject index for existence check
  (let [perm-defs (d/index-range db
                    :eacl.permission/resource-type+permission-name
                    [resource-type permission]
                    [resource-type permission])]
    (some (fn [perm-datom]
            (let [rel-name (:eacl.permission/relation-name 
                           (d/entity db (:e perm-datom)))
                  ;; Check if relationship exists
                  rel-datoms (d/index-range db
                               :eacl.relationship/resource+relation-name+subject
                               [resource-eid rel-name subject-eid]
                               [resource-eid rel-name subject-eid])]
              (seq rel-datoms)))
          perm-defs)))
```

## Performance Considerations

### 1. Index Selection Strategy
- Use most selective index first
- Consider cardinality of each tuple component
- Profile actual data distribution

### 2. Memory Management
- Use lazy sequences for large result sets
- Implement streaming pagination
- Consider chunking for very large indices

### 3. Caching Strategy
- Cache permission definitions (rarely change)
- Cache intermediate results in arrow traversal
- Use Datomic's built-in caching

## Migration Path

### Phase 1: Shadow Mode
- Implement index-based versions alongside Datalog
- Compare results for correctness
- Measure performance differences

### Phase 2: Gradual Rollout
- Use index-based for simple queries
- Fall back to Datalog for complex cases
- Monitor performance metrics

### Phase 3: Full Migration
- Replace all Datalog with index-based
- Remove old implementations
- Document any behavior changes 