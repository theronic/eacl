# 004. Record architecture decisions

Date: 2025-06-06

## Status

Accepted

## Context

The tests in `test/eacl/spice_test.clj` and `test/eacl/impl_test.clj` are passing with the exception of `expand-permission-tree`, which is not currently supported.

- Datomic Tuples have been added to `src/eacl/datomic/schema.clj` to allow for more efficient Datalog rules. As part of this, arrow permissions have their own namespace to avoid nil problems in the 4-tuple for arrow permissions.
- The use of Tuples has improved performance.
- However, `lookup-resources` is still performing poorly due to arrow permissions and pagination offset handling.
- We can use Datomic's `index-range` over the tuple indices defined in `src/eacl/datomic/schema.clj` with correct offset support and pagination cursors (`optional_cursor` in SpiceDB gRPC method, `LookupResourcesRequest` and `LookupResourcesResponse`)

Source Code:
- `src/eacl/core.clj` contains the `IAuthorization` protocol that mirrors the SpiceDB gRPC API the Datomic implementation with Datalog rules
- `resources/sample-spice.schema` contains the Spice schema that we would like to support.
- `src/eacl/datomic/schema.clj` contains the Datomic schema that models SpiceDB data structures.
- `src/eacl/datomic/impl.clj` contains the Datomic implementation with Datalog rules
- `src/eacl/datomic/impl_optimized.clj` contains the optimized Datomic rules for check permission, lookup-subjects and lookup-resources.
- `src/eacl/rules_optimized.clj` contains the previously optimized Datalog rules
- `src/eacl/fixtures.clj` contains fixtures that partially model the Spice schema in Datomic using Relation, Permission schema helpers, and Relationship helper.
- `test/eacl/impl_test.clj` contain tests for the Datomic implementation. Note that the order of lookup-resources is not defined in pagination tests. If any sorting is applied, or read from indices, this order may need to be fixed in the tests.
- `test/eacl/spice_test.clj` contain tests for the reified IAuthorization protocol of the Datomic implementation.
- `test/eacl/performance_test.clj` contain tests for datasets of various sizes and pagination limits & offsets.

## Decision

Instead of using recursive Datalog rules, manually implement `lookup-subjects` and `lookup-resources` using optimized `index-range` over tuples with correct offset handling.

Read the schema in `schema.clj`, the rules in `rules.clj`, the fixtures in `fixtures.clj`, the implementation in `impl.clj`, the tests in `test/*` and then write a comprehensive plan to optimize `can?`, `lookup-subjects` and `lookup-resources`.

`lookup-resources` is the highest priority.
