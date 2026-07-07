# 007. Record architecture decisions

Date: 2025-07-27

## Status

Accepted

## Context

- EACL is a SpiceDB-compatible ReBAC Authorization system built in Clojure and backed by Datomic.
- The goal of EACL is to implement the core SpiceDB gRPC API features in idiomatic Clojure, backed by Datomic, so that the implementation satisfies the `IAuthorization` protocol in `src/eacl/core.clj`.

1. EACL models the SpiceDB permission schema in Datomic via `Relation` and `Permission`, which yield Datomic tx-data. Refer to `src/eacl/datomic/schema.clj` for Datomic attributes & tuple indices (for fast lookup) and how EACL permission schema is defined in `test/eacl/datomic/fixtures.clj`.
2. Relationships are defined via `Relationship`, which yields tx-data. Refer to schema.
3. EACL needs to be able to efficiently answer queries like `CheckPermission` (via `can?`) and `LookupResources` (via `lookup-resources`) using Datomic in O(logN) time, where N is the number of Relationships between subjects & resources.
4. EACL has two internal implementations:
 a. A correct, but slow Datalog implementation in `src/eacl/datomic/impl/datalog.clj` using recursive rules from `src/eacl/datomic/rules/optimized.clj`. However this impl. is not fast enough for `lookup-resources` because the Relationship index can contain 10M+ entities.
 b. A Direct Index implementation in `src/eacl/datomic/impl/indexed.clj`, which recursively traverses the tuple indices directly with cursor-based pagination for much better performance. This is the current implementation, but has some deficiencies.
 5. All permissions are modelled as arrow permissions, where an `:arrow via-relation` points to a target `:relation` or `:permission` on a related resource, except when :arrow is omitted, which means the via-relation is `:self` (the current resource, so no hop is required). This currently works for :self relations, but not for :self permissions.
  a. `(Permission :product :edit {:relation :owner})` means `permission edit = owner`, which works.
  b. `(Permission :product :edit {:arrow :account :relation :owner})` means `permission edit = account->owner`, works, where `owner` is a relation on `account`.
  c. `(Permission :product :edit {:arrow :account :permission :admin})` means `permission edit = account->admin`, works, where `admin` is a permission on account.
  d. `(Permission :product :edit {:arrow :self :permission :admin})` means `permission edit = admin`, which DOES NOT WORK.
6. Multiple paths through the relationship index can yield duplicate resource IDs. This justifies thes existence of `src/eacl/lazy_merge_sort.clj` which, given a collection of _sorted_ lazy sequences (e.g. via `d/index-range`), can emit a sorted, deduplicated collection of resources up to some limit.

7. Traversing multiple permission paths through graph yields lazy sequences of sorted resource-eids from two primary Relationship tuple indices (see schema). Because these relationships are sorted at-rest, we make heavy use of `lazy-merge-dedupe-sort` to lazily return sorted, deduplicated sequences after some `cursor-eid`, up to some `limit`. We can UNDER NO CIRCUMSTANCES, ever materialize the full index (10M+ entities) or call `(sort)` or `(dedupe)` directly on any index of Relationships (too slow).

You have access to a live nREPL via clojure-mcp MCP server. Use only clojure-mcp to run code or make changes, as this MCP server will balance parentheses for you, which saves a lot of time and tokens. If at any time the nREPL is unreachable, stop immediately and notify the user. Remember to use absolute paths when attempting to read files. Use list_files or glob_files tools to enumerate file paths.

To run, tests, use the nREPL via clojure-mcp MCP server by eval'ing:
```
(do (require '[eacl.datomic.impl-test])
  (clojure.test/run-tests 'eacl.datomic.impl-test))
```

Currently, all tests in `test/eacl/datomic/impl_test.clj` are currently passing except for self->permission.

## Decisions


1. Analyze the current codebase and output a detailed plan to `docs/plans/` to implement support for self-permissions for both indexed `can?` and `lookup-resources` that correctly accounts for `:self` arrows.
2. Work in the namespace `eacl.datomic.impl.indexed` in `src/eacl/datomic/impl/indexed.clj`.
3. The tests look correct, so if tests need to be modified, ask for confirmation first.
4. If anything is unclear, ask for clarification. If any files do not exist that you expect to exist, stop and ask for clarification.
5. Use clojure-mcp for all Clojure edits. When reading files, use absolute paths.
6. Run tests between changes to see output. Warn the user if tests are emitting noise unrelated to your work that may be costing tokens to ingest
7. Output your plan to `docs/plans/`. The plan should be fool-proof so that an inferior LLM or intermediate developer can implement it without making any mistakes. Add `[ ]` checkboxes to the plan to track status when executing the plan.
8. When running tests, use the `eacl.datomic.datomic-helpers/with-mem-conn` macro which creates a fresh in-memory Datomic database given some initial tx-data and binds to some let-value. Refer to how tests use this to run test against a fresh in-memory Datomic database while avoiding datom conflicts, e.g.
 ```
 (with-mem-conn [conn schema/v6-schema]
   @(d/transact conn fixtures/base-fixtures)
   (let [client (eacl/make-client conn {})])
      (eacl/can? client (->user :test/user) :view (->server :test/server1)))
 ```
10. If you are unable to read a file you expect to exist using `clojure-mcp`'s `read_file` tool, use the `LS` tool and ensure you have the correct path. If the file is expected to exist, and you can't access it, stop and ask for clarification.
11. The project is small, so do not try to use Grep. Always read the *entire file* (if relevant to context), because all the contents matter. This project can only be understood by reading all files passed to context.
11. Do not mess with `eacl.datomic.core` as it is perfect. Do not mess with the `IAuthorization` protocol – it is perfect. The only problem you have so solve right now, is to correctly account for self permissions and deal with :self.

Godspeed. You are an AGI and will be careful to make no mistakes. You are about to implement a beautiful, perfect solution that does not affect performance negatively by trying to materialize any indices.

Start by running the tests in `eacl.datomic.impl.indexed-test` using the clojure-mcp nREPL to establish a baseline. Make it so, code warrior.