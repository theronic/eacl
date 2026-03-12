# Fix Lookup Resources Plan

## Overview
This plan outlines the steps to fix the bug in the `lookup-resources` function, particularly for cases where the subject is not a user (e.g., VPC listing servers it "views"). The current implementation in `src/eacl/datomic/impl_indexed.clj` (copied to `impl_fixed.clj`) fails because it assumes forward traversal from subject to resources, but the failing test requires traversing relationships in reverse (servers pointing to the VPC).

The corrected implementation will replace the code in `src/eacl/datomic/impl_fixed.clj`, maintaining the same API, and ensure all tests pass except `expand-permission-tree`. Additional tuple indices may be added to `schema.clj` for efficient reverse lookups.

## Bug Analysis
- **Symptom**: In `complex-relation-tests`, `lookup-resources` for a VPC subject returns an empty list instead of the expected servers.
- **Cause**: The permission paths and traversal logic in `lazy-arrow-permission-resources` are designed for forward arrows (subject -> resource via relations). For VPC as subject "viewing" servers, the relationships are from servers to VPCs (reverse direction). The code doesn't handle reverse traversal.
- **Affected Components**:
  - `get-permission-paths`: Needs to identify when reverse traversal is required based on schema arrows.
  - `lazy-arrow-permission-resources`: Needs to support reversing the index lookup (e.g., using resource-to-subject indices).
  - Potential need for new indices like `:eacl.relationship/resource+subject-type+relation-name+resource-type` for efficient reverse queries.

## Proposed Solution
1. **Enhance Schema Analysis**:
   - Modify `get-permission-paths` to detect "reverse arrows" by analyzing the schema for relations where the subject type matches the resource type in arrows.

2. **Support Reverse Traversal**:
   - In `lazy-arrow-permission-resources`, add logic to traverse backwards: start from the subject and find resources that point to it via the relation.
   - Use index-range on a new reverse index (e.g., resource -> subject).

3. **Add Necessary Indices**:
   - Update `schema.clj` to include reverse tuple indices for relationships, e.g.:
     ```
     {:db/ident :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
      :db/valueType :db.type/tuple
      :db/tupleAttrs [:eacl.relationship/resource-type
                      :eacl.relationship/resource
                      :eacl.relationship/relation-name
                      :eacl.relationship/subject-type
                      :eacl.relationship/subject]
      :db/unique :db.unique/identity}
     ```

4. **Handle Deduplication and Pagination**:
   - Ensure the lazy sequences handle duplicates across forward and reverse paths.
   - Maintain stable ordering for cursor-based pagination, possibly by sorting EIDs or using a consistent traversal order.

5. **Implementation in impl_fixed.clj**:
   - Copy and modify the functions from impl_indexed.clj.
   - Integrate the reverse logic conditionally based on path analysis.

6. **Testing**:
   - Run all tests in `test/eacl/datomic/impl_test.clj` after each change.
   - Verify the failing `complex-relation-tests` now passes.
   - Add new tests for reverse lookups if needed.
   - Exclude `expand-permission-tree` as per ADR.

## Risks and Considerations
- **Performance**: Reverse indices must be efficient; test with large datasets.
- **Schema Changes**: Adding indices requires transacting new schema and may need migration handling.
- **API Compatibility**: Ensure the function signature and return format remain unchanged.

## Timeline
- Day 1: Analyze and prototype reverse traversal.
- Day 2: Implement and test fixes.
- Day 3: Write additional tests and finalize.