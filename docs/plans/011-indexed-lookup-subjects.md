# Plan: Indexed Implementation of lookup-subjects

## Overview

Implement a direct index-based `lookup-subjects` function in `eacl.datomic.impl.indexed` to replace the Datalog-based implementation that fails with self-permissions. This will mirror the approach used in `lookup-resources` but work in reverse - finding subjects that can access a known resource.

## Background

- Current `lookup-subjects` in `eacl.datomic.impl.datalog` uses Datalog rules that don't support self-permissions
- The indexed implementation should be more performant and complete
- Key difference: `lookup-resources` knows the subject and finds resources; `lookup-subjects` knows the resource and finds subjects
- Must use reverse tuple indices for efficient traversal
- Cannot use `d/q`, `sort`, or `dedupe` operations that materialize the full index

## Current Architecture Analysis

### Forward Index (used by lookup-resources)
- `:eacl.relationship/subject-type+subject+relation-name+resource-type+resource`
- Tuple: `[subject-type subject-eid relation-name resource-type resource-eid]`
- Used when subject is known, finding resources

### Reverse Index (needed for lookup-subjects)  
- `:eacl.relationship/resource-type+resource+relation-name+subject-type+subject`
- Tuple: `[resource-type resource-eid relation-name subject-type subject-eid]`
- Used when resource is known, finding subjects

## Implementation Plan

### [x] 1. Create helper function to extract subject from reverse tuple datoms

```clojure
(defn extract-subject-id-from-reverse-rel-tuple-datom [{:as _datom, v :v}]
  (let [[_resource-type _resource-eid _relation-name _subject-type subject-eid] v]
    subject-eid))
```

### [x] 2. Implement reverse path traversal functions

#### [x] 2.1 Create `traverse-permission-path-reverse` function
- Mirror `traverse-permission-path` but work from resource to subjects
- Handle three path types:
  - `:relation` - Direct relation using reverse index
  - `:self-permission` - Recursive call with target permission 
  - `:arrow` - Complex traversal using reverse then forward indices

#### [x] 2.2 Handle `:relation` paths (direct relations)
```clojure
:relation
;; Direct relation - reverse traversal from resource to subjects
(when (= resource-type (:resource-type path)) ; Validate resource type matches
  (let [reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
        start-tuple [resource-type resource-eid (:name path) subject-type (or cursor-eid 0)]
        end-tuple   [resource-type resource-eid (:name path) subject-type Long/MAX_VALUE]]
    (->> (d/index-range db reverse-tuple-attr start-tuple end-tuple)
         (map extract-subject-id-from-reverse-rel-tuple-datom)
         (filter #(> % (or cursor-eid 0))))))
```

#### [x] 2.3 Handle `:self-permission` paths
```clojure
:self-permission
;; Self-permission: recursively find subjects that have target permission on this resource
(let [target-permission (:target-permission path)]
  (traverse-permission-path-reverse db resource-type resource-eid 
                                   target-permission subject-type cursor-eid))
```

#### [x] 2.4 Handle `:arrow` paths
Two sub-cases based on target type:

**Arrow to relation:**
1. Use reverse index to find intermediates connected to resource via `:via` relation
2. For each intermediate, use forward index to find subjects with `:target-relation` to that intermediate

**Arrow to permission:**
1. Use reverse index to find intermediates connected to resource via `:via` relation  
2. For each intermediate, recursively call to find subjects with `:target-permission` on that intermediate

### [x] 3. Create main `lookup-subjects-indexed` function

#### [x] 3.1 Function signature and validation
```clojure
(defn lookup-subjects-indexed
  [db {:as filters
       :keys [resource permission subject/type limit cursor]}]
  {:pre [(:type resource) (:id resource)]}
  ...)
```

#### [x] 3.2 Extract resource information
```clojure
(let [{resource-type :type
       resource-id   :id} resource
      resource-eid (d/entid db resource-id)
      
      {cursor-subject :subject} cursor
      cursor-eid (:id cursor-subject)]
  ...)
```

#### [x] 3.3 Get permission paths and traverse
```clojure
(let [paths (get-permission-paths db resource-type permission)
      path-seqs (->> paths
                     (keep (fn [path]
                             (let [results (traverse-permission-path-reverse 
                                           db resource-type resource-eid path subject-type cursor-eid)]
                               (when (seq results) results)))))
      merged-results (if (seq path-seqs)
                       (lazy-merge-dedupe-sort path-seqs)
                       [])]
  ...)
```

#### [x] 3.4 Apply limit and format results
```clojure
(let [limited-results (take limit merged-results)
      subjects (map #(spice-object subject-type %) limited-results)
      last-subject (last subjects)
      next-cursor {:subject (or last-subject (:subject cursor))}]
  {:data subjects
   :cursor next-cursor})
```

### [x] 4. Add to namespace exports

#### [x] 4.1 Update `impl.clj` to use indexed implementation
- Import `lookup-subjects-indexed` 
- Replace datalog implementation with indexed version

#### [x] 4.2 Update test imports
- Change test to import from `impl.indexed` instead of `impl.datalog`

### [x] 5. Test and validate

#### [x] 5.1 Run existing tests
```clojure
(test/run-tests 'eacl.datomic.impl.indexed-test)
```

#### [x] 5.2 Verify specific test cases pass
- ✅ `lookup-subjects-tests` pass - basic functionality works
- ✅ Self-permission cases work individually - the permission path logic is correct
- ✅ Pagination with cursors works correctly
- ✅ Major structural fix applied - added missing subject-type guard in `:relation` case
- ✅ Added `lazy-merged-lookup-subjects` to mirror `lookup-resources` structure exactly
- ✅ **CRITICAL PERFORMANCE FIX:** Replaced `mapcat` and `(apply concat)` with `lazy-merge-dedupe-sort`
- ✅ **NO INDEX MATERIALIZATION:** Maintains lazy evaluation throughout (no `sort` or `dedupe`)
- ✅ **ALL 14/14 TESTS PASS** with optimal performance 🎉

#### [x] 5.3 Performance validation
- Ensure no `d/q` calls that materialize full index
- Verify lazy sequences are used throughout
- Confirm index-range calls use proper bounds

## Key Implementation Notes

### Reverse Index Usage
- Always start with resource as the first tuple element
- Use `:eacl.relationship/resource-type+resource+relation-name+subject-type+subject`
- Extract subject-eid from the last position in tuple value

### Path Type Handling
1. **Direct relations** (`:relation`): Simple reverse index lookup
2. **Self-permissions** (`:self-permission`): Recursive call with different permission
3. **Arrow permissions** (`:arrow`): Complex two-step traversal using both indices

### Lazy Evaluation
- Use `lazy-merge-dedupe-sort` to combine multiple sorted sequences
- Never materialize full index with `sort` or `vec` on large collections
- Apply `take` for limits only at the final step

### Cursor Implementation
- Support cursor-based pagination similar to `lookup-resources`
- Cursor contains the last subject returned
- Use cursor subject ID as lower bound in index-range queries

## Success Criteria

- [x] All existing `lookup-subjects-tests` pass ✅
- [x] Self-permission test cases work correctly ✅
- [x] No use of `d/q` that materializes full index ✅
- [x] Lazy evaluation preserved throughout ✅
- [x] Performance comparable to or better than `lookup-resources` ✅
- [x] Proper cursor-based pagination support ✅
- [x] **COMPLETE SUCCESS: ALL 14/14 tests pass** 🎉  
- [x] **Performance-optimized**: Fixed `mapcat`/`concat` issues while maintaining lazy evaluation

## Files to Modify

1. `src/eacl/datomic/impl/indexed.clj` - Add new functions
2. `src/eacl/datomic/impl.clj` - Update to use indexed implementation  
3. `test/eacl/datomic/impl/indexed_test.clj` - Update imports if needed

## Notes

- This implementation mirrors the `lookup-resources` pattern but uses reverse indices
- The key insight is that permission paths are the same, but traversal direction is reversed
- Self-permissions are the critical missing piece from the Datalog implementation
- Must maintain sorted order through lazy sequences to support pagination