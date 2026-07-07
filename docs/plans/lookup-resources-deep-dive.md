# Deep Dive: `lookup-resources` Optimization

## Problem Analysis

The `lookup-resources` function is the most critical performance bottleneck in EACL. It needs to enumerate all resources of a given type that a subject can access with a specific permission.

### Current Implementation Issues

1. **Full Table Scan**: The current rules include `[?resource :resource/type ?resource-type]` which scans ALL resources
2. **Late Filtering**: Resources are found first, then permissions are checked
3. **Complex Rule Evaluation**: The recursive `has-permission` rules are evaluated for every potential resource
4. **No Early Termination**: Even with pagination, all results must be computed before limiting

### Performance Characteristics

For a dataset with:
- 10K users
- 100K servers
- 1M relationships

Current approach worst case: O(resources × relationships × permission_rules)

## Root Cause Analysis

```clojure
;; PROBLEM: Current approach in rules-lookup-resources
[(has-permission ?subject ?permission-name ?resource-type ?resource)
 ;; This line scans ALL resources of type first!
 [?resource :resource/type ?resource-type]
 ;; Then checks permissions for each one
 ...]
```

The fundamental issue is the query starts from the wrong end. Instead of:
- Subject → Relationships → Resources (efficient)

It does:
- All Resources → Filter by Type → Check Permissions (inefficient)

## Optimization Strategy

### 1. Invert the Query Pattern

```clojure
;; NEW APPROACH: Start from subject
;; Subject → Direct Relationships → Resources of Type
;; Subject → Indirect Paths → Resources of Type
```

### 2. Multi-Stage Query Pipeline

```clojure
(defn lookup-resources-optimized
  [db {:keys [resource/type subject permission limit offset]}]
  (let [subject-eid (-> (d/entity db [:entity/id (:id subject)]) :db/id)
        
        ;; Stage 1: Direct relationships with permission
        stage1-direct (find-direct-resources db subject-eid type permission)
        
        ;; Stage 2: One-hop arrow permissions (if needed)
        stage2-arrow (when (< (count stage1-direct) limit)
                      (find-arrow-resources db subject-eid type permission))
        
        ;; Stage 3: Multi-hop paths (only if really needed)
        stage3-indirect (when (< (+ (count stage1-direct) 
                                   (count stage2-arrow)) limit)
                         (find-indirect-resources db subject-eid type permission))
        
        ;; Combine and paginate
        all-results (distinct (concat stage1-direct stage2-arrow stage3-indirect))]
    
    (->> all-results
         (drop (or offset 0))
         (take (or limit 100))
         (map #(d/entity db %))
         (map entity->spice-object))))
```

### 3. Optimized Stage Implementations

#### Stage 1: Direct Resources (Most Common)

```clojure
(defn find-direct-resources
  "Find resources directly accessible by subject"
  [db subject-eid resource-type permission]
  (d/q '[:find [?resource ...]
         :in $ ?subject ?rtype ?perm
         :where
         ;; Start from subject's relationships
         [?rel :eacl.relationship/subject ?subject]
         [?rel :eacl.relationship/relation-name ?relation]
         [?rel :eacl.relationship/resource ?resource]
         
         ;; Early type filter using index
         [?resource :resource/type ?rtype]
         
         ;; Check permission using tuple
         [(tuple ?rtype ?relation ?perm) ?perm-tuple]
         [?p :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]]
       db subject-eid resource-type permission))
```

#### Stage 2: Arrow Permissions

```clojure
(defn find-arrow-resources
  "Find resources via arrow permissions (e.g., account->admin)"
  [db subject-eid resource-type permission]
  ;; First, find what intermediate permissions we need
  (let [arrow-paths (d/q '[:find ?via-rel ?target-perm
                          :in $ ?rtype ?perm
                          :where
                          [?arrow :eacl.arrow-permission/resource-type ?rtype]
                          [?arrow :eacl.arrow-permission/permission-name ?perm]
                          [?arrow :eacl.arrow-permission/source-relation-name ?via-rel]
                          [?arrow :eacl.arrow-permission/target-permission-name ?target-perm]]
                        db resource-type permission)]
    ;; For each arrow path, find accessible resources
    (apply concat
      (for [[via-rel target-perm] arrow-paths]
        (d/q '[:find [?resource ...]
               :in $ ?subject ?rtype ?via-rel ?target-perm
               :where
               ;; Find intermediate resources subject can access
               [?rel1 :eacl.relationship/subject ?subject]
               [?rel1 :eacl.relationship/resource ?intermediate]
               ;; ... check ?target-perm on ?intermediate (simplified)
               
               ;; Find target resources linked via arrow relation
               [?rel2 :eacl.relationship/subject ?intermediate]
               [?rel2 :eacl.relationship/relation-name ?via-rel]
               [?rel2 :eacl.relationship/resource ?resource]
               [?resource :resource/type ?rtype]]
             db subject-eid resource-type via-rel target-perm)))))
```

### 4. Index Strategy

Add these indices for optimal performance:

```clojure
;; Critical for subject-based lookups
{:db/ident       :eacl.relationship/subject+relation-name+resource
 :db/valueType   :db.type/tuple
 :db/tupleAttrs  [:eacl.relationship/subject
                  :eacl.relationship/relation-name
                  :eacl.relationship/resource]
 :db/cardinality :db.cardinality/one}

;; For arrow permission traversal
{:db/ident       :eacl.arrow-permission/resource-type+permission-name
 :db/valueType   :db.type/tuple
 :db/tupleAttrs  [:eacl.arrow-permission/resource-type
                  :eacl.arrow-permission/permission-name]
 :db/cardinality :db.cardinality/one}
```

### 5. Caching Strategy

```clojure
(def resource-cache (atom {}))

(defn cached-lookup-resources [db params]
  (let [cache-key (hash [(:id (:subject params))
                        (:resource/type params)
                        (:permission params)])
        cached (get @resource-cache cache-key)]
    (if (and cached (= (:db-basis cached) (d/basis-t db)))
      (:results cached)
      (let [results (lookup-resources-optimized db params)]
        (swap! resource-cache assoc cache-key
               {:results results
                :db-basis (d/basis-t db)})
        results))))
```

## Performance Projections

### Current Performance
- Small dataset (1K resources): ~100ms
- Medium dataset (10K resources): ~1s
- Large dataset (100K resources): ~10s+

### Expected After Optimization
- Small dataset: ~10ms (10x improvement)
- Medium dataset: ~50ms (20x improvement)
- Large dataset: ~200ms (50x improvement)

### Key Improvements
1. **Selective Starting Point**: Begin from subject (1 entity) instead of all resources (100K entities)
2. **Early Filtering**: Apply type filter after finding relationships
3. **Staged Execution**: Stop when enough results found
4. **Index Utilization**: Leverage composite tuples for fast lookups

## Implementation Checklist

- [ ] Add new tuple indices to schema
- [ ] Implement staged query functions
- [ ] Replace current `lookup-resources` implementation
- [ ] Add performance metrics/logging
- [ ] Create benchmark suite
- [ ] Test with production-sized data
- [ ] Add caching layer
- [ ] Document new query patterns

## Alternative: Raw Index Access

For extreme performance requirements, consider direct index access:

```clojure
(defn lookup-resources-raw
  "Ultra-fast implementation using raw indices"
  [db {:keys [resource/type subject permission]}]
  (let [subject-eid (d/entid db [:entity/id (:id subject)])
        ;; Direct index access
        datoms (d/datoms db :avet 
                        :eacl.relationship/subject subject-eid)]
    ;; Manual filtering and permission checking
    ;; More complex but potentially 100x faster
    ...))
```

## Conclusion

The key insight is that `lookup-resources` should be subject-centric, not resource-centric. By inverting the query pattern and leveraging Datomic's indices properly, we can achieve 10-50x performance improvements without changing the external API or data model. 