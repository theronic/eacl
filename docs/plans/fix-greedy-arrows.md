# Fix Greedy Arrow Permissions Plan

## Problem Analysis

The current `greedy-arrow-permission-resources` function uses `d/q` queries which materialize ALL results into memory. This is problematic when dealing with millions of resources. The `lazy-arrow-permission-resources` attempts to be lazy but has bugs in its implementation.

### Current Issues:

1. **Greedy Implementation Issues:**
   - Uses `d/q` which returns all results at once
   - Uses `doall` and `mapcat` which force evaluation
   - Cannot handle millions of resources efficiently

2. **Lazy Implementation Issues:**
   - Incorrectly uses `:eacl.relationship/subject+relation-name+resource` index with only 2 values
   - The logic for extracting resource-eid from datoms is incorrect
   - Not properly filtering by resource type

## Solution Approach

### 1. Understanding the Arrow Permission Flow

Arrow permissions like `server.account->admin` work as follows:
1. Find entities where subject has the direct-relation permission (e.g., user has admin on account)
2. Then find all resources that point TO those entities via the arrow relation (e.g., servers that have account relation to those accounts)

### 2. Available Indices

From the schema, we have these useful indices:
- `:eacl.relationship/subject+relation-name` - Find all relationships for a subject with a specific relation
- `:eacl.relationship/resource+relation-name+subject` - Find relationships by resource
- Individual attribute indices on `:eacl.relationship/subject`, `:eacl.relationship/relation-name`, `:eacl.relationship/resource`

### 3. Implementation Strategy

We need to rewrite the lazy implementation to:

1. **For finding relationships where subject has a relation:**
   - Use regular attribute index on `:eacl.relationship/subject` with filtering
   - Or use `d/index-range` if we can construct proper bounds

2. **For the reverse lookup (finding resources that point to entities):**
   - Use the `:eacl.relationship/resource` attribute index
   - Filter by relation-name and check resource types

3. **Keep everything lazy:**
   - Use `d/datoms` or `d/index-range` instead of `d/q`
   - Avoid `doall`, use lazy sequences throughout
   - Use `take-while` for bounded iteration

## Implementation Steps

### Step 1: Fix the relationship lookup

Instead of:
```clojure
(d/datoms db :avet :eacl.relationship/subject+relation-name+resource [eid (:relation step)])
```

We should use:
```clojure
(d/datoms db :avet :eacl.relationship/subject eid)
```
Then filter for the correct relation-name.

### Step 2: Fix the reverse relationship lookup

For finding resources that point TO a given entity with a specific relation, we need to:
```clojure
(d/datoms db :avet :eacl.relationship/resource target-eid)
```
Then filter for the correct relation-name and check that the subject entity has the expected resource type.

### Step 3: Maintain laziness throughout

- Remove all `doall` calls
- Use lazy sequence operations
- Ensure filtering happens lazily

### Step 4: Test with large datasets

After implementation, we should test that:
1. The function returns the same results as the greedy version
2. It doesn't materialize all results at once
3. Pagination works correctly with cursors

## Code Structure

The new function should:
1. Start with the initial entities (those where subject has direct permission)
2. For each step in reverse order, lazily find entities pointing to current entities
3. Filter by resource type at each step
4. Return a lazy sequence that can be paginated

## Expected Outcome

A single `lazy-arrow-permission-resources` function that:
- Is truly lazy and can handle millions of resources
- Returns results in index order (stable but not necessarily sorted by ID)
- Works as a drop-in replacement for the greedy version
- Passes all existing tests

## Implementation Notes from Testing

After attempting the implementation, we discovered several key issues:

1. **Entity Navigation**: When using `d/datoms`, the entity references need to be carefully loaded. The `:eacl.relationship/resource` field may not be immediately available.

2. **Index Usage**: The basic attribute indices work well for the initial lookup, but we need to be careful about how we filter the results.

3. **Greedy Approach Works**: The greedy implementation using `d/q` works correctly but materializes all results. For a truly lazy implementation, we need to carefully replicate its logic using index operations.

4. **Alternative Approach**: Instead of trying to use complex index navigation, we could use `d/index-pull` or a combination of `d/datoms` with careful entity loading to achieve laziness while maintaining correctness. 