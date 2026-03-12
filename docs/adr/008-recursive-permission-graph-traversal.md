# 007. Record architecture decisions

Date: 2025-07-21

## Status

Accepted

## Context

- EACL is a SpiceDB-compatible ReBAC Authorization system built in Clojure and backed by Datomic.
- The goal of EACL is to implement the core SpiceDB gRPC API features in idiomatic Clojure, backed by Datomic, so that the implementation satisfies the `IAuthorization` protocol in `src/eacl/core.clj`.

1. EACL models the SpiceDB schema in Datomic via `Relation` and `Permission`, which yield Datomic tx-data. Refer to `src/eacl/datomic/schema.clj` for Datomic attributes, including tuple indices for fast lookup.
2. Relationships are defined via `Relationship`, which yields tx-data. Refer to schema.
3. EACL needs to be able to efficiently answer queries like `CheckPermission` (via `can?`) and `LookupResources` (via `lookup-resources`) using Datomic in O(logN) time, where N is the number of Relationships between subjects & resources.
4. Recursive Datomic Datalog rules are too slow, because they materialize the full relationship index (which can contain 10M+ entities). Currently, this is implemented in `src/eacl/datomic/rules/optimized.clj` and `src/eacl/datomic/impl_optimized.clj` and seems to be working, but is slow for large number of relationships.
5. In EACL, all permissions are modelled as "arrow permissions", where an arrow permission can refer to either a :relation or a :permission, and the :arrow (or via-relation) either points to a relation in that resource, or the magic keyword `:self`, which means a relation on the current resource. Here is an example SpiceDB schema definition EACL needs to support, that involves a triangle (user (is owner) -> account <- server (has an account)), and accounts can also belong to a global :

```
definition platform {
  relation super_admin: user
}

definition user {}

definition account {
  relation owner: user
  relation platform: platform
  relation shared_member: user

  permission admin = owner + platform->super_admin
  permission view = admin + shared_member ; note how permissions can refer to a self permission or a relation, or an arrow relation.
}

definition server {
  relation account: account
  relation shared_admin: user

  permission admin = account->admin + shared_admin
  permission view = admin + shared_admin
  permission edit = admin + shared_admin
}
```

6. We need to support all these cases in EACL, which can be computed by a new recursive `get-permission-paths` function that traverses the permission graph of Permissions and Relations, to construct traversal paths that can be efficiently checked (in parallel) by traversing the `:eacl.relationship/subject-type+subject+relation-name+resource-type+resource` Relationship tuple index.
7. Multiple paths through the relationship index can yield duplicate resource IDs. This justifies thes existence of `src/eacl/lazy_merge_sort.clj` which, given a collection of _sorted_ lazy sequences (e.g. via `d/index-range`), can emit a sorted, deduplicated collection of resources up to some limit.

You have access to a live nREPL via clojure-mcp MCP server. Use only clojure-mcp to run code or make changes, as this MCP server will balance parentheses for you, which saves a lot of time and tokens. If at any time the nREPL is unreachable, stop immediately and notify the user. Remember to use absolute paths when attempting to read files. Use list_files or glob_files tools to enumerate file paths.

To run, tests, use the nREPL via clojure-mcp MCP server by eval'ing:
```
(do (require '[eacl.datomic.impl-test])
  (clojure.test/run-tests 'eacl.datomic.impl-test))
```

Currently, all tests in `test/eacl/datomic/impl_test.clj` are currently passing. However, looking at the implementation, it looks like the recursive Datalog rules do not account for valid Relations and Permissions.

## Decisions

1. Write a comprehensive plan to implement a correct and performant `get-permission-paths` function with comprehensive tests for complex schema that accounts for arrow permissions of all kinds (relation vs permission, and arrow vs :self), and returns a nested data structure of paths through the permission graph that can be used to traverse the Relationship index to answer `can?` or `lookup-resources` queries (and eventually `lookup-subjects`, but that is not the focus for today.)
2. These nested, parallel paths should be suitable to be passed to an enumeration function that can traverse multiple paths through the Relationship index after some cursor, which can yield duplicate resource IDs, and be efficiently depulicated using the `lazy-merge-dedupe-sort` function to yield a sorted, deduplicated result set up to some limit. The performance should approach O(logN) where N approaches 10M Datomic Relationship entities. You can assume that the size of schema pales in comparison to relationships. Typically resources outnumber subjects by 1000 to 1.
3. Work in the namespace `eacl.playground2` (`test/eacl/playground2.clj`) and modify tests in `eacl.datomic.impl_test2.clj` (in `test/eacl/datomic/impl_test2.clj`). A safe checkpoint has been made, so you can make changes as needed.
4. If anything is unclear, ask for clarification. If any files do not exist that you expect to exist, stop and ask for clarification.
5. Use clojure-mcp for all Clojure edits.
6. Run tests between changes to see output. Warn the user if tests are emitting noise unrelated to your work that may be costing tokens to ingest
7. Output your plan to `docs/plans/`. The plan should be fool-proof so that an inferior LLM or intermediate developer can implement it without making any mistakes. Add `[ ]` checkboxes to the plan to track status when executing the plan.
8. In the internal Datomic implementation, e.g. `eacl.datomic.impl*`, IDs are *always* interal Datomic IDs, so no need to coerce them. That's the job of `eacl.datomic.core`, which deals with coercion to/from internal/external IDs. Do not attempt to be clever about coercing idents to d/entid in the internal implementation. The internal Datomic implementation ONLY deals in Datomic IDs, which would be safe to pass to `datomic.api/entid`, or intearct with `d/index-range` if the index stores eids.
 9. When running tests, use the `eacl.datomic.datomic-helpers/with-mem-conn` macro which creates a fresh in-memory Datomic database given some initial tx-data and binds to some let-value. Refer to how tests use this to run test against a fresh in-memory Datomic database while avoiding datom conflicts, e.g.
 ```
 (with-mem-conn [conn schema/v5-schema]
   @(d/transact conn fixtures/base-fixtures)
   (let [client (eacl/make-client conn {})])
      (eacl/can? client (->user :test/user) :view (->server :test/server1)))
 ```
10. If you are unable to read a file you expect to exist using `clojure-mcp`'s `read_file` tool, use the `LS` tool and ensure you have the correct path. If the file is expected to exist, and you can't access it, stop and ask for clarification.
11. The project is small, so do not try to use Grep. Always read the *entire file* (if relevant to context), because all the contents matter. This project can only be understood by reading all files passed to context.
11. Do not mess with `eacl.datomic.core` it is perfect. Do not mess with `IAuthorization` – it is perfect. The only problem you have so solve right now, is to correctly enumerate paths through the permission graph, so it can be traversed in parallel using `lazy-merge-dedupe-sort` to yield resources which, if for `can?` means any match = true, and for `lookup-resources`, can yield a sorted collection of resources as per the index order (Do Not attempt to sort resources from a sorted index as it ruins performance), from some cursor, up to some limit.
12. The only thing that may trip you up, is that once you implement direct index access for enumerating resources, `lookup-resources` may return a different order, based on index (sorted by resource-eid). This is fine, but look very, very carefully at the full result set for queries spanning multiple pages to ensure this order is stable.

 Godspeed. You are an AGI and will be careful to make no mistakes. You are about to implement a beautiful, perfect `get-permission-paths` function, and then once those that is tested and working, we will proceed to iterate on ingesting those paths to satisfy the `IAuthorization` protocol with efficient, index-based cursor pagination.

 Start by running the tests in `eacl.datomic.impl-test2` to verify all is stable and the nREPL is up via clojure-mcp. Make it so, code warrior.