# 012. Record architecture decisions

Date: 2026-01-29

## Status

Accepted

## ADR Goal

`(eacl/write-schema! acl-client schema-string)` should behave exactly like the SpiceDB gRPC WriteSchema operation, with some limitations due to unsupported features in EACL.

## Context

EACL is a situated ReBAC authorization library based on SpiceDB, built in Clojure and backed by Datomic.

The goal of EACL is to implement the core SpiceDB gRPC API features in idiomatic Clojure, backed by Datomic, with an implementation that satisfies the `IAuthorization` protocol in `src/eacl/core.clj` and offers real-time query performance for up to 10M permissioned entities, with cursor-based pagination. Refer to the README.md for documentation and API details.

This requires creating & updating [schema](https://authzed.com/docs/spicedb/concepts/schema) over time.

The SpiceDB Golang implementation is included at ~/spicedb of this repo for reference with schema DSL parser implementation at ~/spicedb/pkg/schemadsl with parser_test.go. Use this to inform your implementation. Note that they wrote their own schema parser & schema compiler, but we can probably use Instaparse.

Some initial work was done in `src/eacl/spicedb/parser.clj` & `test/eacl/datomic/parser_test.clj` to parse the SpiceDB schema DSL.

- EACL constructs a unique :eacl/id for each Relation & Permission to avoid duplicate transactions that is derived from the the Relation or Permission spec.
- EACL only supports Union (+) operators for permissions at this time, so should reject other operators as invalid. Operator rejection should not be too tightly coupled because support may be added in future.
- EACL does not support Caveats.
- EACL only supports one level of nested arrow permissions at this time, but multi-arrow schema could be supported in future with hidden internal arrow jumps or a more fleshed out schema spec, because I suspect the permission path traversal can probably handle it.

To run, tests, use the nREPL via clojure-mcp MCP server by eval'ing:
```
(do (require '[eacl.datomic.impl.indexed-test])
  (clojure.test/run-tests 'eacl.datomic.impl.indexed-test))
```

All tests in `test/eacl/datomic/impl/indexed_test.clj` are passing.

You can manage your own nREPL connections using the clojure-mcp tools.

## Problem

- SpiceDB uses a schema DSL that validates schema during the WriteSchema operation.
- EACL does not support this DSL yet. It expects the user to transact Relation & Permission spec entities directly to Datomic, which means they are not validated and schema removals are not handled, e.g. checking that an arrow permission is referencing a valid permission or relation, and checking if removing a permission spec will orphan relationships.

## Decisions

- Once you understand the SpiceDB schema DSL, rewrite the fixtures from fixtures/relations+permissions to a new `test/eacl/fixtures.schema` file using the Spice DSL which fill be used to test the implementation against
- Implement parsing, validation & safe updating of the EACL AuthZ schema by accepting SpiceDB schema DSL strings (refer to SpiceDB docs & instaparse definitions)
- `eacl/read-schema` should return a rich map of schema definitions. Currently it returns {:keys [relations permissions]}, but you can enrich this to be easy to compare, validate & update new schema.
- Spice schema DSL should be converted to an internal representation that is easy to query & compare
- Compares the new desired schema to existing schema to identify additions & retractions.
- Build up a list of operations before calling d/transact so you can test the "event-based" operations are correct when moving from old to new schema.
- Retracting a Relation should throw if deleting it would orphan any existing relationships involving that Relation, just like SpiceDB.
- Invalid schema should be rejected and no changes made, e.g. can't define permissions derived from missing relations, arrow permissions can't refer to wrong permissions or relations, can't use unsupported features. Permissions must refer to valid relations. Resource & subject types must be correct.
- Rejections due to orphaned relationships should be detailed in the error response so that the user can choose to drop those relationships first.
- On a successful schema update, the schema string that was passed to write-schema! should be stored in Datomic under a special entity with :eacl/id "schema-string" :eacl/schema-string for future analysis.
- Write failing unit tests BEFORE writing any code first.
- Use only clojure-mcp tools to read, edit or write Clojure code.
- When you write or edit a new function, evaluate it in the REPL after defining it to test your assumptions.
- Use Malli for validating the structure of maps, ala "poor man's types."

## Validating Assumptions against SpiceDB

You can start an in-memory SpiceDB instance to test your assumptions and compare behaviour between SpiceDB & EACL:
```
docker run --rm -p 50051:50051 -p 8443:8443 authzed/spicedb serve --http-enabled true --grpc-preshared-key "somerandomkeyhere"
```

Remember to stop it when you're done if it's running in background. You can add gRPC deps to a :spicedb alias if you must, but probably lighter to use their HTTP API.

## Next Steps

0. Ensure you can connect to (or start) an nREPL and run a simple clojure-eval to ensure it's working.
1. Read the current EACL implementation (it's small and will fit in context) and gather all information you need.
2. Write a detailed report to `docs/reports/` (prefix with date) about the current state of EACL to inform a future plan for getting to where we need. Note any implementation issues or bugs you spot in the code in your report, which may require attention first.
3. Based on your report, write a detailed plan to `docs/plans/` (prefix with date) that even a retarded junior engineer could follow without making any mistakes. Track progress in the plan with `[ ]` checkboxes. Link from report to plan file and back to report from plan file.
4. Submit your plan for approval.
5. If anything is unclear, stop & ask for clarification. If any files do not exist that you expect to exist, stop and ask for clarification.
6. Run tests between logical changes and ensure tests that should be passing, are still passing. If tests are emitting noise unrelated to your work that may be filling up your context, stop & inform the operator to avoid unnecessary costs.
8. When running database tests, use the `eacl.datomic.datomic-helpers/with-mem-conn` macro, which creates a fresh in-memory Datomic database given some initial tx-data and binds to some let-value. Refer to how tests use this to run test against a fresh in-memory Datomic database while avoiding datom conflicts, e.g.
 ```
 (with-mem-conn [conn schema/v6-schema]
   @(d/transact conn fixtures/base-fixtures)
   (let [client (eacl/make-client conn {})])
      (eacl/can? client (->user :test/user) :view (->server :test/server1)))
 ```
9. If you are unable to read a file you expect to exist using `clojure-mcp`'s `read_file` tool, use the `LS` tool and ensure you have the correct path. If the file is expected to exist, and you can't access it, stop and ask for clarification.
10. The project is small, so do not try to truncate or use Grep. Always read the *entire file* (if relevant to context), because all the contents matter. This project can only be understood by reading all files passed to context.
11. Do not mess with `eacl.datomic.core` as it is perfect. Do not mess with the `IAuthorization` protocol – it is perfect. The only area of focus is to correctly implement `eacl/write-schema!`.
12. Note that `eacl.datomic.impl` re-exports symbols from `eacl.datomic.impl.indexed` so you may need to re-evaluate this namespace after making changes to `eacl.datomic.impl.indexed` if you are using that namespace. I would suggest working directly against indexed.clj.

You are a careful AGI who takes careful to make no mistakes, documents your progress to avoid forgetting important things during complex execution. You will implement a beautiful, elegant and perfect solution that does not affect performance negatively by trying to fully materialize any relationship indices. Godspeed. 

Start by running the tests in `eacl.datomic.impl.indexed-test` using the clojure-mcp nREPL to establish a baseline. Make it so, code warrior.