# Unified Permission Schema Implementation Plan

## Overview

This plan outlines the steps to unify the direct and arrow permission schemas in EACL under a single `:eacl.permission/*` namespace, while generalizing arrow permissions to support both relations and permissions as targets. Direct permissions will be treated as a special case without traversal. The implementation will ensure all existing tests pass without modification.

To address potential issues with schema validation during initial definition, the Permission API will be updated to be unambiguous at the call-site, specifying whether the target is a relation or permission. This avoids complex runtime validation.

The plan focuses only on `impl-optimized`, `rules/optimized`, and tests in `impl-test`. Experimental implementations like `impl-fixed` will not be touched.

The plan is designed to be executed step-by-step, with checkpoints for testing and verification. Each step includes checkboxes for tracking progress.

## Prerequisites

- [ ] Ensure all tests in `test/eacl/datomic/impl_test.clj` pass with the current implementation as a baseline. Run `(do (require '[eacl.datomic.impl-test]) (clojure.test/run-tests 'eacl.datomic.impl-test))` using the clojure_eval tool.

## Step 1: Update Schema Definition

Update `src/eacl/datomic/schema.clj` to unify permissions under `:eacl.permission/*`.

- Remove all `:eacl.arrow-permission/*` attributes and indices.
- Add new attributes:
  - `:eacl.permission/source-relation-name` (keyword, optional, indexed) - The source relation for arrow permissions; nil for direct.
  - `:eacl.permission/target-type` (keyword, required, values `:relation` or `:permission`, indexed).
  - `:eacl.permission/target-name` (keyword, required, indexed).
- Update indices:
  - Keep `:eacl.permission/resource-type+permission-name` tuple index.
  - Add new tuple index if needed for queries, e.g., `:eacl.permission/resource-type+target-name`.
- Modify the `v5-schema` to reflect these changes.
- Update `read-permissions` and related functions to handle the new unified structure.

[ ] Implement schema changes in `src/eacl/datomic/schema.clj`.
[ ] Run tests to ensure no immediate breakage (expect some failures due to incomplete implementation).

## Step 2: Update Permission Constructor

Update the `Permission` function in `src/eacl/datomic/impl_base.clj` to handle disambiguation without validation.

- Change the API to use maps for clarity:
  - Direct: `(Permission resource-type {:relation relation-name} permission-name)`
  - Arrow to permission: `(Permission resource-type {:arrow source-relation :permission target-permission} permission-name)`
  - Arrow to relation: `(Permission resource-type {:arrow source-relation :relation target-relation} permission-name)`
- The function will set:
  - For direct: source nil, target-type `:relation`, target-name relation-name.
  - For arrow: source source-relation, target-type `:permission` or `:relation`, target-name target.
- This produces the entity map for transaction.
- Update all callsites in `test/eacl/datomic/fixtures.clj` and elsewhere to the new syntax.

[ ] Update `Permission` function.
[ ] Update fixtures to new syntax.
[ ] Transact schema and fixtures in a test DB, verify entities.

## Step 3: Update Datalog Rules

Update rules in `src/eacl/datomic/rules/optimized.clj`.

- Modify `check-permission-rules` to handle the unified schema.
- Add clauses:
  - If `:eacl.permission/source-relation-name` is nil (direct): Match relationship with relation-name = target-name on resource, if target-type :relation.
  - For arrow: Traverse to intermediate via source-relation.
  - Then if target-type :relation, match relationship with relation-name = target-name on intermediate.
  - If target-type :permission, recursive has-permission with target-name on intermediate.
- Ensure safety checks (not= subject resource).
- Update `rules-lookup-subjects` similarly.

[ ] Update the rules.
[ ] Run tests, debug rule failures.

## Step 4: Update Implementation Functions

Update `src/eacl/datomic/impl.clj` and `impl_optimized.clj`.

- Adjust queries and pulls to use the new unified attributes.
- Update `lookup-resources`, `can?`, `lookup-subjects` in `impl_optimized.clj` to handle the new schema.

[ ] Update impl functions.
[ ] Run tests, fix implementation bugs.

## Step 5: Verify and Test

- [ ] Run all tests and ensure they pass without modifications.
- [ ] Test new functionality: arrow with target relation.
- [ ] Add new tests if necessary (but do not modify existing).

## Step 6: Documentation and Cleanup

- [ ] Update README.md with new Permission syntax.
- [ ] Clean up any deprecated code.

## Potential Breaking Changes

- Monitor for any breaking changes; notify if they don't affect tests.

## Rollback Plan

- If issues arise, ask for clarification.