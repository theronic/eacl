# 011. Record architecture decisions

Date: 2025-12-03

## Status

Accepted

## Context

EACL is a situated ReBAC authorization library based on SpiceDB, built in Clojure and backed by Datomic.
The goal of EACL is to implement the core SpiceDB gRPC API features in idiomatic Clojure, backed by Datomic, with an implementation that satisfies the `IAuthorization` protocol in `src/eacl/core.clj` and offers real-time query performance for up to 10M permissioned entities.

1. EACL models the SpiceDB permission schema in Datomic with `Relation` and `Permission` entities. Refer to `src/eacl/datomic/schema.clj` which contains EACL v6 Datomic attributes & tuple indices (for fast lookup). Refer to how EACL permission schema is defined in `test/eacl/datomic/fixtures.clj`.
2. Relationships are 3-tuples of `[subject relation resource]` created using `Relationship`. API callers use the `->Relationship` helper. Refer to schema.
3. EACL needs to efficiently answer queries like `CheckPermission` (via `can?`), `LookupResources` (via `lookup-resources`) and `LookupSubjects` (via `lookup-subjects`) ~O(logN) time, where N is the number of permissioned resources in terminal Relationship indices + intermediate hops.
4. For any lookup, EACL recursively traverses all potential parallel paths through the permission graph, deduplicating any resources returned from matching tuple indices containing Relationships, while applying cursor-based pagination to support performant queries.
5. Multiple paths through the graph can yield duplicate resource IDs, so parallel paths are lazily deduplicated with the folding algorithms in `src/eacl/lazy_merge_sort.clj`: `lazy-fold2-merge-dedupe-sorted-by` takes a collection of _sorted_ lazy sequences (either resource or subject eids returned from `d/index-range` or `d/seek-datoms`) and emits a lazy, sorted, deduplicated collection of elements, or "merged" result set from parallel paths.
6. EACL queries *MUST* support continuable cursor-based pagination from some `?cursor-eid` and up to some `limit` to avoid materializing full Relationship indices, which can easily span 10M+ entities. This means that UNDER NO CIRCUMSTANCES can we call `(sort)` or `(dedupe)` on any index of Relationships, because it would eagerly materialize large indices.
7. You have access to a live nREPL via clojure-mcp MCP server. Use ONLY clojure-mcp to read, write or run code, i.e. use the `read_file` tool in clojure-mcp – NOT the generic `file_read` tool to avoid getting stuck in "file was modified" loops. Another reason you MUST use clojure-mcp is because the MCP server will balance parentheses for you, which saves time and tokens. If at any time the nREPL is unreachable, STOP IMMEDIATELY and notify the user. Remember to use absolute paths when attempting to read files. Use list_files or glob_files tools to enumerate file paths.
8. The codebase is small, so never read collapsed files. Always call read_file with `collapsed: false` if files changes to avoid re-reads – trust me, you need the full context.

To run, tests, use the nREPL via clojure-mcp MCP server by eval'ing:
```
(do (require '[eacl.datomic.impl.indexed-test])
  (clojure.test/run-tests 'eacl.datomic.impl.indexed-test))
```

Currently, all tests in `test/eacl/datomic/impl/indexed_test.clj` are passing.

## Problem

`eacl.datomic.impl.indexed/get-permission-paths` is a hot path, which costs ~17ms in production on *every* EACL query (against a complex permission graph) to recompute the permission graph, but since permission schema rarely changes, permission paths are eminently cacheable.

We assume permission schema only changes at startup (not during runtime), so we can implement caching of permission paths with cache eviction functions for permission paths, which can be later be used by `eacl/write-schema!` when that is implemented.

The `org.clojure/core.cache` dependency has been added to implement a cache protocol for permission paths.

## Decisions

1. Analyze the current codebase and output a detailed plan to `docs/plans/` to implement caching of permission paths (which are read from `get-permission-paths`) and cache eviction functions of paths for when permission schema changes.
2. Work in the namespace `eacl.datomic.impl.indexed` in `src/eacl/datomic/impl/indexed.clj`.
3. Add tests for permission path caching.
4. If anything is unclear, ask for clarification. If any files do not exist that you expect to exist, stop and ask for clarification.
5. ONLY use clojure-mcp to read or edit Clojure files. When reading files, use absolute paths.
6. Run tests between changes to see output. Warn the user if tests are emitting noise unrelated to your work that may be costing tokens to ingest
7. Output your plan to `docs/plans/`. The plan should be fool-proof so that an inferior LLM or intermediate developer can implement it without making any mistakes. Add `[ ]` checkboxes to the plan to track status when executing the plan.
8. When running tests, use the `eacl.datomic.datomic-helpers/with-mem-conn` macro which creates a fresh in-memory Datomic database given some initial tx-data and binds to some let-value. Refer to how tests use this to run test against a fresh in-memory Datomic database while avoiding datom conflicts, e.g.
 ```
 (with-mem-conn [conn schema/v6-schema]
   @(d/transact conn fixtures/base-fixtures)
   (let [client (eacl/make-client conn {})])
      (eacl/can? client (->user :test/user) :view (->server :test/server1)))
 ```
9. If you are unable to read a file you expect to exist using `clojure-mcp`'s `read_file` tool, use the `LS` tool and ensure you have the correct path. If the file is expected to exist, and you can't access it, stop and ask for clarification.
10. The project is small, so do not try to use Grep. Always read the *entire file* (if relevant to context), because all the contents matter. This project can only be understood by reading all files passed to context.
11. Do not mess with `eacl.datomic.core` as it is perfect. Do not mess with the `IAuthorization` protocol – it is perfect. The only problem you have so solve right now, is to correctly handle multiple Relations.
12. Note that `eacl.datomic.impl` re-exports symbols from `eacl.datomic.impl.indexed` so you may need to re-evaluate this namespace after making changes to `eacl.datomic.impl.indexed`.

Godspeed. You are an AGI and will be careful to make no mistakes. You are about to implement a beautiful, perfect solution that does not affect performance negatively by trying to materialize any indices.

Start by running the tests in `eacl.datomic.impl.indexed-test` using the clojure-mcp nREPL to establish a baseline. Make it so, code warrior.