# Implementation Plan: Fix Self-Permission Support

## Status
**Issue**: Self-permissions are not correctly handled in `lookup-resources` traversal functions, causing "No matching clause: :self-permission" errors and incorrect results.

## Root Cause Analysis

### 1. **Missing Case in `traverse-permission-path`**
- The `traverse-permission-path` function (used by `lookup-resources`) has cases for `:relation` and `:arrow` but **missing case for `:self-permission`**
- Error occurs at line ~264: `No matching clause: :self-permission`
- This is the primary cause of the test failures

### 2. **Incorrect Self-Permission Logic in `traverse-permission-path-via-subject`**  
- Current implementation creates `(spice-object resource-type subject-eid)` which is backwards
- Should check if subject has target-permission on resources, not vice versa
- Uses `can?` for individual resource checks, which is inefficient for bulk operations

### 3. **Infinite Recursion Risk**
- Current `can?` implementation recursively calls itself: `(can? db subject target-permission resource)`
- Risk of infinite recursion if target-permission also has self-permissions
- Need cycle detection or guaranteed termination

### 4. **Path Merging Issue**
- `(Permission :server :view {:permission :admin})` creates self-permission path
- Tests expect multiple servers but only getting 1 result from working `:arrow` path (via `:shared_admin`)
- Self-permission path silently dropped → not contributing to merged results
- Analysis shows 2 paths: `{:type :self-permission}` (broken) + `{:type :arrow, :via :nic}` (working)

## Implementation Strategy

### **Core Insight**: Self-Permission Traversal
For self-permission like `{:type :self-permission, :target-permission :admin, :resource-type :server}`:
- **Meaning**: "Subject can `:view` server if subject has `:admin` on that same server"
- **Solution**: Find all servers where subject has `:admin` permission
- **Method**: Recursively traverse with target permission (`:admin`) and return those results

### **Infinite Recursion Prevention**
- Target permission (`:admin`) should have different path types (relations/arrows)
- Recursion terminates when hitting non-self-permission paths
- Add cycle detection if needed

## Implementation Tasks

### [ ] Task 1: Fix `traverse-permission-path` Function
**File**: `src/eacl/datomic/impl/indexed.clj`  
**Function**: `traverse-permission-path` (~line 250-330)

**Action**: Add missing `:self-permission` case to the case statement:

```clojure
:self-permission
;; Self-permission: recursively find resources where subject has target permission
(let [target-permission (:target-permission path)]
  ;; Recursively traverse with target permission to find matching resources
  (traverse-permission-path db subject-type subject-eid 
                           target-permission resource-type cursor-eid limit))
```

**Notes**: 
- This delegates to the same function with target permission
- Leverages existing pagination and cursor logic
- Avoids the need to enumerate all resources individually

### [ ] Task 2: Fix `traverse-permission-path-via-subject` Function  
**File**: `src/eacl/datomic/impl/indexed.clj`  
**Function**: `traverse-permission-path-via-subject` (~line 359-369)

**Action**: Replace incorrect self-permission logic:

**Current (broken)**:
```clojure
(let [target-permission     (:target-permission path)
      resource-spice-object (spice-object resource-type subject-eid)]  ; WRONG!
  (if (can? db (spice-object subject-type subject-eid) target-permission resource-spice-object)
```

**Fixed**:
```clojure
(let [target-permission (:target-permission path)]
  ;; Recursively traverse to find resources where subject has target permission
  (traverse-permission-path-via-subject db subject-type subject-eid 
                                       {:type :permission, :name target-permission} 
                                       resource-type cursor-eid))
```

**Wait** - This approach has a problem. The `traverse-permission-path-via-subject` function expects a single path, not a permission name. 

**Better approach**: Use `lazy-merged-lookup-resources` logic:
```clojure
(let [target-permission (:target-permission path)
      target-paths (get-permission-paths db resource-type target-permission)
      path-results (->> target-paths
                        (map (fn [target-path]
                               (traverse-permission-path-via-subject db subject-type subject-eid 
                                                                     target-path resource-type cursor-eid)))
                        (filter seq))]
  ;; Merge and dedupe results from all target permission paths
  (if (seq path-results)
    (lazy-merge-dedupe-sort path-results)
    []))
```

### [ ] Task 3: Add Cycle Detection to `can?` (Optional Safety)
**File**: `src/eacl/datomic/impl/indexed.clj`  
**Function**: `can?` (~line 208-211)

**Current**:
```clojure
:self-permission
(let [target-permission (:target-permission path)]
  (can? db subject target-permission resource))
```

**Enhanced with cycle detection**:
```clojure
:self-permission
(let [target-permission (:target-permission path)]
  ;; Avoid infinite recursion: don't re-check the same permission
  (when (not= permission target-permission)
    (can? db subject target-permission resource)))
```

**Alternative**: Add a visited set parameter to track checked permissions.

### [ ] Task 4: Test and Validate

**Actions**:
1. Run full test suite: `(clojure.test/run-tests 'eacl.datomic.impl.indexed-test)`
2. Verify specific self-permission tests pass:
   - `can?` with self-permissions should return `true/false` correctly
   - `lookup-resources` should return multiple servers (not just 1)
   - Pagination should work correctly with self-permissions
3. Check for infinite recursion in edge cases
4. Verify performance remains O(log N)

### [ ] Task 5: Debug Specific Test Cases

**Key failing test**: `"lookup-resources: super-user can view all servers"`
- Expected: 3 servers
- Actual: 1 server (from working `:arrow` path via `:shared_admin`)
- Root cause: Self-permission path silently dropped due to missing case clause, not contributing additional servers to merged results

**Debug approach**:
1. Check if `get-permission-paths` returns multiple paths for `:server :view`
2. Verify each path is being traversed correctly
3. Ensure `lazy-merge-dedupe-sort-by` is combining results from all paths

## Risk Assessment

### **Low Risk**
- Target permissions (`:admin`) have relation/arrow paths, not self-permissions
- Recursion should terminate naturally
- Changes are localized to specific case statements

### **Medium Risk**  
- Complex lazy sequence merging could introduce subtle bugs
- Cursor/pagination logic needs careful testing
- Performance impact of recursive traversal

### **Mitigation**
- Add extensive logging during implementation
- Test with small datasets first
- Add cycle detection if infinite recursion detected
- Verify lazy sequence behavior with debugging

## Expected Outcome
- All tests in `eacl.datomic.impl.indexed-test` should pass
- Self-permissions work correctly for both `can?` and `lookup-resources`
- No infinite recursion or performance degradation
- Maintains O(log N) performance characteristics

## Implementation Order
1. **Task 1** (traverse-permission-path) - Fixes the immediate "No matching clause" error
2. **Task 4** (Test) - Verify basic functionality restored  
3. **Task 2** (traverse-permission-path-via-subject) - Fixes logic errors
4. **Task 4** (Test) - Comprehensive validation
5. **Task 3** (Cycle detection) - Only if infinite recursion occurs
6. **Task 5** (Debug) - Address any remaining issues

## Notes
- **Critical**: Test between each task to isolate issues
- **Performance**: Self-permission traversal reuses existing optimized paths  
- **Safety**: Cycle detection provides insurance against infinite recursion
- **Compatibility**: Changes are additive - existing functionality unchanged