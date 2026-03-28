# Fix Plan for Lazy Implementation of lookup-resources

## Summary of Issues

The `lookup-resources` implementation in `impl_grok_lazy.clj` has several critical bugs preventing it from correctly finding resources with arrow permissions.

## Identified Bugs

### 1. ✅ Bug in `find-resources-pointing-to` function (FIXED)

**Current behavior**: The function is using the VAET index incorrectly. It's looking for relationships where `target-eid` is used as a `:eacl.relationship/resource`, but it should be finding resources that point TO the target.

**Fix**: Query relationships where entities of `source-resource-type` are subjects with the given relation pointing to the target.

### 2. ✅ Cursor handling issue (FIXED)

**Current behavior**: The cursor logic appears to be inclusive when it should be exclusive - when resuming from a cursor, it should skip the cursor resource.

**Fix**: Update the logic to make cursor handling exclusive.

### 3. ❌ Cursor not applied correctly across all permission paths

**Current behavior**: The global cursor filtering is too aggressive. When we filter based on cursor-eid, we're dropping all resources after finding the cursor, but resources from different permission paths might be interleaved.

**Example**: If the order is [server-1 (direct), server-2 (arrow), server-3 (direct)], and cursor is "server-2", we should get [server-3], but the current implementation drops everything after server-2.

**Fix**: The cursor should represent a specific resource to skip, not a position in a stream. We need to collect ALL resources from all paths, then apply cursor filtering and pagination.

### 4. ✅ Resource type filtering in arrow permissions (FIXED)

**Current behavior**: Resources returned from arrow permission traversal aren't being properly filtered by resource type.

**Fix**: Ensure that the final resources returned match the requested resource type.

## Available Indices

The schema provides these relevant indices that should be used:
- `:eacl.relationship/subject+relation-name+resource` - For finding relationships by subject
- `:eacl.relationship/resource+relation-name+subject` - For finding relationships by resource

## Proposed Solution

1. ✅ Fix `find-resources-pointing-to` to properly query for entities that point to the target
2. ✅ Make cursor handling exclusive (skip the cursor resource when resuming)
3. Fix global cursor handling - collect all resources first, then apply cursor and pagination
4. ✅ Use efficient index access via `d/index-range` or `d/datoms` with lazy evaluation
5. ✅ Ensure proper resource type filtering at each step

## Implementation Status

- Arrow permissions now work correctly
- Direct permissions work correctly
- Resource type filtering works
- Cursor handling needs final adjustment for proper pagination across multiple permission paths 