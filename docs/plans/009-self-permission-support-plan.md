# Implementation Plan: Self-Permission Support (ADR 009)

## Status Summary

**Current Issues**:
- `get-permission-paths` throws exception for `{:arrow :self :permission :admin}` style permissions  
- `traverse-permission-path-via-subject` doesn't handle self->permission paths (missing case clause)
- Tests failing for `:via_self_admin` permission which uses `{:arrow :self :permission :admin}`
- Self->permission means "this resource has the target permission on itself"

**Key Constraints** (MUST FOLLOW):
- NEVER use d/q for Relationship lookups – preserve O(log N) performance
- NEVER use (sort) or (dedupe) – always use lazy-merge-dedupe-sort  
- All IDs are internal Datomic eids – no coercion needed
- Do not change fixtures.clj or any tests – only fix indexed.clj to make tests pass
- Results must remain in stable index order
- Self->permission is a recursive permission check on the same resource

**Problem Analysis**:
From fixtures: `(Permission :server :via_self_admin {:arrow :self :permission :admin})`
This means: "server has via_self_admin permission if the server itself has admin permission"

**Expected Behavior**:
```clojure
;; For permission path like {:arrow :self :permission :admin}
;; Subject: user-1, Resource: server-1, Permission: :via_self_admin
;; Should resolve to: "Does server-1 have :admin permission for user-1?"
;; This is a recursive permission check where resource becomes the subject
```

**Proposed Solution**:
1. In `get-permission-paths`: Handle `:self` + `:permission` case by creating a special path type
2. In traversal functions: Add case for self->permission that performs recursive permission check
3. For self->permission: The resource becomes the subject in a recursive `can?` check

## Step-by-Step Implementation Plan

Execute steps sequentially. After each step, eval changed functions and run tests.

### Step 1: Understand Current Permission Schema
- [ ] Read the Permission helper function to understand how `:arrow :self` permissions are created
- [ ] Eval `(Permission :server :via_self_admin {:arrow :self :permission :admin})` to see the exact tx-data structure
- [ ] Verify the Datomic schema attributes that will be used

### Step 2: Fix get-permission-paths for Self->Permission  
- [ ] In `get-permission-paths`, replace the exception `(throw (Exception. "we don't handle this"))` 
- [ ] For `source-relation-name :self` + `target-type :permission`:
  - Create a path with `:type :self-permission`
  - Include `:target-permission target-name`
  - Include `:resource-type resource-type` (for recursive check)
- [ ] Test by calling `(get-permission-paths db :server :via_self_admin)` and ensure it returns a path
- [ ] Run `get-permission-paths-tests` to ensure no regression

### Step 3: Handle Missing :arrow Key in Permission Helper
- [ ] Check if `(Permission :server :share {:permission :admin})` needs explicit `:arrow :self`
- [ ] If so, update the Permission helper function to default missing `:arrow` to `:self` when only `:permission` is specified
- [ ] Eval Permission helper and test the transformation
- [ ] Run `permission-helper-tests` to ensure correctness

### Step 4: Add Self-Permission Case to traverse-permission-path-via-subject
- [ ] Add `:self-permission` case to the `case` statement in `traverse-permission-path-via-subject`
- [ ] For self-permission path: 
  - Extract `:target-permission` from path
  - Perform recursive `can?` check: `(can? db subject target-permission (spice-object resource-type subject-eid))`
  - If can? returns true, return `[subject-eid]` (the resource itself)
  - If can? returns false, return `[]`
- [ ] Handle cursor: if `cursor-eid` is provided and `subject-eid <= cursor-eid`, return `[]`
- [ ] Test with a simple case in REPL

### Step 5: Add Self-Permission Case to traverse-permission-path
- [ ] Add `:self-permission` case to `traverse-permission-path` function
- [ ] Similar logic as Step 4 but return `[[subject-eid path]]` tuples for consistency
- [ ] Handle cursor and limit appropriately  
- [ ] Ensure integration with `lazy-merge-dedupe-sort-by first`

### Step 6: Add Self-Permission Case to can? Function
- [ ] In `can?`, add handling for `:self-permission` paths  
- [ ] For self-permission: Perform recursive check and return boolean directly
- [ ] Ensure early termination (return true on first match)
- [ ] Test with failing test case: `(can? db (->vpc :test/user1) :via_self_admin (->server :test/server1))`

### Step 7: Test and Debug
- [ ] Run specific failing tests:
  - `complex-relation-tests` 
  - `lookup-resources-tests` with `:via_self_admin`
- [ ] Debug any remaining issues with cursor handling or edge cases
- [ ] Ensure self->permission doesn't create infinite recursion (add cycle detection if needed)

### Step 8: Full Test Suite Validation
- [ ] Run entire test suite: `(clojure.test/run-tests 'eacl.datomic.impl.indexed-test)`
- [ ] All tests must pass including the previously failing ones
- [ ] Test performance with self->permission (should not materialize indices)
- [ ] Remove any debug prints or temporary code

### Step 9: Edge Case Validation
- [ ] Test self->permission with cursor pagination
- [ ] Test nested self->permission chains (if any exist in fixtures)
- [ ] Test that self->permission doesn't break normal permission paths
- [ ] Verify that count-resources works correctly with self->permission

## Implementation Notes

**Self-Permission Semantics**:
- `{:arrow :self :permission :admin}` means "resource has this permission if it has admin permission"
- This requires recursive permission check where resource becomes subject
- No relationship traversal needed - just permission check on same resource

**Performance Considerations**:
- Self->permission should be O(1) or O(log N) depending on recursive permission complexity
- No index materialization - recursive `can?` calls should use same efficient paths
- Cycle detection may be needed if permissions can recursively reference themselves

**Error Handling**:
- If recursive permission doesn't exist, should return empty results (not error)
- If resource type doesn't match expected type, should handle gracefully
- Log warnings for missing permissions but don't fail

Execute this plan sequentially and test after each step. The goal is to support self->permission arrows without breaking existing functionality or performance.