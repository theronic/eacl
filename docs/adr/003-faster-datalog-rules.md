# 003. Record architecture decisions

Date: 2025-06-04

## Status

Accepted

## Context

The tests in `test/eacl/spice_test.clj` and `test/eacl/impl_test.clj` are passing with the exception of `expand-permission-tree`, which is not currently supported.

- `lookup-subjects` and `lookup-resources` perform badly under for real-world sized datasets, especially `lookup-resources`.
- Datomic Tuples have been added to `src/eacl/datomic/schema.clj` to allow for more efficient Datalog rules. As part of this, arrow permissions have their own namespace to avoid nil problems in the 4-tuple for arrow permissions.
- Each of `can?`, `lookup-subjects` and `lookup-resources` now have distinct semi-optimized Datalog rules under `src/eacl/datomic/rules.clj` to speed up permission checks and enumeration.
    - `check-permission` rules can benefit from always knowing `subject` type + ID, as well as `resource` type + ID.
    - `lookup-subjects` rules can benefit from knowing `resource` (type + ID) and `:subject/type`, so you could search from the resource to viable subjects.
    - `lookup-resources` rules can benefits from knowing `subject` (type + ID) and `:resource/type`, so you could search from the subject to viable resources. This is the main feature to optimize.

To optimize checking permissions and enumerating subjects & resources, we need to efficiently traverse the graph from Subjects to Resources via Relationships, while resolving Relations and Permissions (incl. Arrow Permissions)
- schema is typically small, i.e. we have a low number of relations and permissions
- resources (like servers) are typically numerous compared to subjects (like users or accounts)

To make the EACL product faster, you may need to:
- optimize the Datalog rules,
- optimize the schema further (possible, but unlikely),
- use Datomic `index-range`, or
- hand-code the graph traversal and permission resolution using multiple stages, where e.g. we first enumerate all paths between subject & resource, prune these paths, and then search the graph in parallel, streaming in subject or resources as found, but then we need to deduplicate results.

Source Code:
- `src/eacl/core.clj` contains the `IAuthorization` protocol that mirrors the SpiceDB gRPC API the Datomic implementation with Datalog rules
- `resources/sample-spice.schema` contains the Spice schema that we would like to support.
- `src/eacl/datomic/schema.clj` contains the Datomic schema that models SpiceDB data structures.
- `src/eacl/datomic/impl.clj` contains the Datomic implementation with Datalog rules
- `src/eacl/rules.clj` contains the core implementation with Datalog rules
- `src/eacl/fixtures.clj` contains fixtures that partially model the Spice schema in Datomic using Relation, Permission schema helpers, and Relationship helper.
- `test/eacl/impl_test.clj` contain tests for the Datomic implementation.
- `test/eacl/spice_test.clj` contain tests for the reified IAuthorization protocol of the Datomic implementation.

## Decision

Make `can?`, `lookup-subjects` and `lookup-resources` faster. 

Read the schema in `schema.clj`, the rules in `rules.clj`, the fixtures in `fixtures.clj`, the implementation in `impl.clj`, the tests in `test/*` and then write a comprehensive plan to optimize `can?`, `lookup-subjects` and `lookup-resources`.

`lookup-resources` is the highest priority.
