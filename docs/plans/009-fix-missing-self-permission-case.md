# Fix Missing Self-Permission Case in traverse-permission-path

## Issue Analysis

The tests are failing with **"No matching clause: :self-permission"** error because:

1. **Root Cause**: The server `:view` permission includes `{:permission :admin}` which creates a `:self-permission` path 
2. **Missing Implementation**: The `traverse-permission-path` function has the `:self-permission` case commented out as "incomplete"
3. **Impact**: Most `lookup-resources` tests are failing because they call `traverse-permission-path` which throws the error

## Current State

✅ **`can?`** - Has `:self-permission` case working  
✅ **`traverse-permission-path-via-subject`** - Has `:self-permission` case working  
❌ **`traverse-permission-path`** - Missing `:self-permission` case (commented out)

## Solution

**Task**: Add the missing `:self-permission` case to `traverse-permission-path` function.

### Implementation Details

The `:self-permission` case in `traverse-permission-path` should:

1. **Check Permission**: Use `can?` to check if the subject has the target permission on the resource
2. **Return Format**: Return `[resource-eid path]` tuples (same format as other cases)  
3. **Handle Cursor**: Filter results based on cursor like other cases
4. **Handle Limit**: Participate in the lazy evaluation and merging

### Code Changes

**File**: `src/eacl/datomic/impl/indexed.clj`
**Function**: `traverse-permission-path` 
**Location**: Around line 264

Replace the commented out `:self-permission` case with a proper implementation.

## Checklist

- [x] Add `:self-permission` case to `traverse-permission-path`
- [x] Test the fix with basic permission checks
- [ ] Debug why lookup-resources is only returning 1 result instead of multiple
- [ ] Run full test suite to verify all tests pass
- [ ] Confirm no performance regressions

## Status Update

✅ **Fixed the "No matching clause" error** - went from 3 errors to 0 errors  
❌ **Still have 26 test failures** - lookup-resources returning only 1 result instead of multiple

## Current Issue Analysis

The `:self-permission` case is now handled correctly (no more errors), but there's still an issue where `lookup-resources` is only returning 1 result when it should return multiple results.

**Root Cause Investigation Needed:**
1. Check if the `:self-permission` recursive call is causing infinite loops or early termination
2. Verify that all permission paths are being properly merged
3. Ensure the `lazy-merge-dedupe-sort` is working correctly with self-permission results

**Hypothesis:** The recursive call in `:self-permission` might be causing the lazy evaluation to terminate prematurely or create circular dependencies.

## Expected Outcome

After this fix:
- All "No matching clause" errors should be resolved
- `lookup-resources` should work correctly with self-permissions
- All tests should pass
- Self-permission logic should work consistently across all functions