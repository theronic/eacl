# 1. Record architecture decisions

Date: 2025-05-13

## Status

Accepted

## Context

This project, "Spiceomic" or "EACL" is a partial implementation of SpiceDB in Datomic using recursive Datalog rules. However, it does not support SpiceDB's arrow syntax, e.g. `permission admin = account->admin + shared_admin` under `definition vpc { ... }`, where the permission `admin` is conferred to subjects via the `shared_admin` relation, or who have admin permission on the related account, which in turn, may be the result of arrow syntax.

Source:
- `resources/sample-spice.schema` contains the Spice schema that we would like to support
- `src/eacl/core2.clj` contains the core implementation with Datalog rules
- `src/eacl/fixtures.clj` contains fixtures that partially model the Spice schema in Datomic using the Relation & Permission helpers.
- `test/eacl/core2_test.clj` contain tests, one of which is failing due to missing arrow syntax support.
- `src/eacl/core3.clj` is the target file for new implementation, which will have arrow syntax support.
- `test/eacl/core3_test.clj` contain failing tests for unimplemented v3.

## Decision

Based on the implementation in core2.clj, the fixtures in fixtures.clj and the tests in core2_test.clj, write a newer v3 implementation in `src/eacl/core3.clj` with recursive Datalog rules supports the arrow syntax. You will probably need to amend or extend `Relation` & `Permission` to support this. May need an `ArrowRelation`, but that is up to you.

Make sure to run the tests before and after to check your work.
