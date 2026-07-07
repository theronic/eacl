"(ns docs.plans.006-fix-lookup-resources-plan
  \"Comprehensive plan to fix lookup-resources in EACL\")

# Fix lookup-resources Plan

This plan provides a step-by-step guide to rewrite the `lookup-resources` and related functions in the new `eacl.datomic.impl-fixed` namespace at `src/eacl/datomic/impl_fixed.clj`. The goal is to create an efficient, correct implementation using direct Datomic index access, generalize arrow permissions to support relations and subsume direct permissions using a special `:self` keyword, ensure stable cursor-based pagination, and pass all existing tests without modifying them.

The plan is designed to be fool-proof, with detailed steps, explanations, and checkboxes for tracking progress. Follow each step in order, verifying with tests after key changes.

## Prerequisites

[ ] Read and understand the SpiceDB gRPC API documentation, focusing on LookupResources semantics (use web_search if needed).
[ ] Read the SpiceDB DSL schema in `resources/sample-spice.schema`.
[ ] Read the EACL Datomic schema in `src/eacl/datomic/schema.clj`.
[ ] Read the existing Relation & Permission schema fixtures in `test/eacl/datomic/fixtures.clj`.
[ ] Read the EACL Datomic implementation in `src/eacl/datomic/impl.clj`.
[ ] Read and understand the tests in `test/eacl/datomic/impl_test.clj`. Note the complex-relation-tests that fail in indexed impl.
[ ] Run the tests by evaluating `(do (require '[eacl.datomic.impl-test]) (clojure.test/run-tests 'eacl.datomic.impl-test))` to establish baseline.
[ ] Analyze bugs in `eacl.datomic.impl-indexed/lookup-resources`, noting usage of d/index-range and d/datoms, and identify why complex chains fail (reverse traversal handling).

## Step 1: Setup the New Namespace

[ ] Create the file `src/eacl/datomic/impl_fixed.clj` if not exists.
[ ] Add the namespace declaration with requires:
   ```clojure
   (ns eacl.datomic.impl-fixed
     (:require [clojure.tools.logging :as log]
               [datomic.api :as d]
               [eacl.core :refer [spice-object]]
               [eacl.datomic.schema :as schema]
               [eacl.datomic.impl-base :as base]))
   ```
[ ] Copy necessary records from impl_base.clj: Cursor, Relation, Permission, Relationship.

## Step 2: Generalize Arrow Permissions (No Schema Changes)

We will generalize without schema changes by modifying logic to handle target as either permission or relation, and treat direct as special :self arrow in code.

[ ] Update get-permission-paths (copy from impl_indexed and modify):
   - For arrows, after getting target-permission, check if there is a permission for target-permission on target-resource-type using d/q.
   - If yes, proceed as before.
   - If not, check if there is a Relation for target-resource-type with relation-name = target-permission and some subject-type.
   - If yes, treat it as relation: set sub-paths to [[], target-permission] (terminal with "direct-relation" = target-name).
   - If neither or both, throw error.
[ ] For direct permissions, convert to special arrow path: [[{:relation :self, :source-resource-type resource-type, :target-resource-type resource-type}], direct-relation]
[ ] Ensure paths are returned as before: [steps direct-relation]

## Step 3: Implement Helper Functions

[ ] Implement lazy-direct-relation-resources (similar to lazy-direct-permission-resources but without permission check):
   - Use d/index-range on :eacl.relationship/subject-type+subject+relation-name+resource-type+resource with tuple [subject-type subject-eid relation resource-type cursor-eid-or-nil].
   - If cursor, start from tuple with cursor-eid +1, or use drop-while.
   - Map to [resource-type resource-eid].
[ ] Update lazy-arrow-permission-resources to handle :self:
   - If first reverse-step has (:relation step) = :self, return current-eids (the initial).
   - For normal steps, proceed as before.
   - To handle self in test, add base case: if initial-resource-type = subject-type and some initial-eid = subject-eid, include it (to allow self permission).

## Step 4: Implement lookup-resources with Stable Pagination

Use merged lazy sorted by eid for stable order.

[ ] Copy get-permission-paths as updated.
[ ] In lookup-resources:
   - Get all paths.
   - For each path, get the lazy seq of [type eid]: if empty steps, lazy-direct-relation (for relation) or lazy-direct-permission (if permission).
   - For arrow, use lazy-arrow, handling :self.
   - To handle cursor, if cursor-eid, for each path's lazy, start index-range with > cursor-eid (set the last component to (inc cursor-eid)).
   - Create a min-heap (use java.util.PriorityQueue) with comparator on eid.
   - For each path idx, take the lazy seq, if (seq lazy), take first [type eid], push [eid type idx (rest lazy)] to heap.
   - Then, to produce the output lazy seq: while heap not empty, pop min [eid type idx remainder], if remainder, push next from remainder if not empty.
   - For dedup, use a seen set, skip if in seen.
   - Take limit from this lazy merged seq.
   - For next cursor, the last eid in the page.
[ ] Ensure dedup and skip cursor correctly.

## Step 5: Implement count-resources

[ ] Similar to lookup-resources, but use a set for unique eids, traverse all paths fully, count the unique.

## Step 6: Update impl.clj to Use New Impl

[ ] In src/eacl/datomic/impl.clj, change to use impl-fixed for lookup-resources and count-resources.

## Step 7: Testing and Verification

[ ] After each major step, run the tests and verify no regressions.
[ ] Specifically test complex-relation-tests, ensure lookup-resources returns expected for vpc view servers.
[ ] If changes don't affect tests, notify user.
[ ] Run performance tests with large datasets to verify efficiency.

## Step 8: Documentation and Cleanup

[ ] Add comments to the code explaining the logic.
[ ] Update README if needed.
[ ] Commit changes with detailed messages.

This plan ensures all requirements are met without modifying tests or schema." 
