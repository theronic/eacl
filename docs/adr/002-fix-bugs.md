# 002. Record architecture decisions

Date: 2025-05-21

## Status

Accepted

## Context

Tests in core3_test.clj are not passing. There appears to be a bug.

Possibly related to the recursive Datalog rules.

Source:
- `resources/sample-spice.schema` contains the Spice schema that we would like to support.
- `src/eacl/core.clj` contains the core implementation with Datalog rules
- `src/eacl/fixtures.clj` contains fixtures that partially model the Spice schema in Datomic using the Relation & Permission helpers.
- `test/eacl/core_test.clj` contain failing tests.

## Decision

Run the tests, analyze the output and figure out the root cause for the failures. Write a plan to fix.
