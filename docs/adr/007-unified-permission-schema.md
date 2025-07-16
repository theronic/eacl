# 007. Record architecture decisions

Date: 2025-07-16

## Status

Accepted

## Context

- EACL is a SpiceDB-compatible ReBAC Authorization system built in Clojure and backed by Datomic.
- The goal of EACL is to implement the core SpiceDB gRPC API features in idiomatic Clojure, backed by Datomic, in such a way the implementation satisfies the `IAuthorization` protocol in `eacl.core`.

There are two problems with EACL:
1. Direct permissions are really just arrow permissions where the `via-relation` is `:self`, but these are currently modelled separately, complicating the schema and requiring 4-arity Permission calls. We would still like to support the 3-arity, but it could default to `:self` for `via-relation`.
2. The arrow permission implementation only supports a "via-permission" using `:eacl.arrow-permission/permission-name`, but this could be a relation or a permission, as per SpiceDB's schema. Possibly this will require a separate attribute and needs to be determined at schema write time for `(Permission ...)`.

## Detailed Context

EACL currently models direct permissions and arrow permissions using the Datomic schema attrs `:eacl.permission/*` and `eacl.arrow-permission/*`, respectively. However, we realize that direct permissions are like arrow permissions where the via-relation is simply `:self` and we can treat this as a special keyword during traversal, so we can reduce the size of our schema and reduce our indices.

Supporting this with the same schema will cause ambiguity because relations & permissions can have the same keyword values, so to disambiguage we will probably need a separate schema attr to distinguish via-relations from via-permissions, or we need separate Permission builder that recognises this, or a record to indicate whether it's a ViaRelation or ViaPermission.

### The Arrow Permission Problem:

- Unlike Spice, EACL Arrow Permissions currently require intermediate permissions, e.g.
```
definition user {}

definition account {
    relation owner: user
    permission is_owner = owner # supported in EACL as a "direct permission"
}

definition server {
  relation account: account
  permission admin = owner # supported in EACL as a "direct permission"
  permission view = account->owner # not supported in EACL because owner is a relation, not a permission.
  permission view1 = account->is_owner # this is supported in EACL, but not ideal.
}
```
To match SpiceDB We need to generalize EACL arrow permissions to support either relations or permissions.

### Reading code, making changes and running tests via `clojure-mcp` MCP tools:

You have access to a live nREPL via clojure-mcp MCP server. Use only clojure-mcp to run code or make changes, as this MCP server will balance parentheses for you, which saves a lot of time and tokens. If at any time the nREPL is unreachable, stop immediately and notify the user.

You can run the relevant tests via the clojure-mcp MCP server by eval'ing:

```
(do (require '[eacl.datomic.impl-test])
  (clojure.test/run-tests 'eacl.datomic.impl-test))
```

Note that all tests in `test/eacl/datomic/impl_test.clj` are currently passing except for `expand-permission-tree`, which is not currently implemented.

## Decisions

Write a comprehensive plan to unify the permission schema under one `:eacl.permission/*` schema that can handle the ambiguity of for via-relation vs via-permission when traversing paths through the permission graph (determined by `Relation` and `Permission`).

If anything is unclear, ask for clarification.

Update the recursive EACL datalog rules under `eacl.rules.optimized` to support this unification. You may need a Datalog or-clause to handle the direct vs arrow case, but modelled in a similar way. Hopefully it can be unified in a clean way.

Output this plan to `docs/plans/`. The plan should be fool-proof so that an inferior LLM or intermediate developer can implement it without making any mistakes. Add `[ ]` checkboxes to the plan to track status when executing the plan. The result of the plan should:
 - generalize the schema for direct vs arrow permissions under `:eacl.permission/*` schema attributes, where the special word `:self` means the via-relation is the current resource.
 - all tests should pass without modification. The tests are correct, so DO NOT attempt to change them. 
 - Before and after making any changes, run the tests in `eacl.datomic.impl-test` namespace to ensure it works. Analyze any unexpected failures and resolve any bugs.
 - Notify me if breaking changes do not seem to affect the tests.
 - The return order of `lookup-resources` matters in the tests.
 - In the internal Datomic implementation, e.g. `eacl.datomic.impl*`, IDs are *always* interal Datomic IDs, so no need to coerce them. That's the job of `eacl.datomic.core`, which deals with coercion to/from internal/external IDs. Do not attempt to be clever about coercing idents to d/entid in the internal implementation. The internal Datomic implementation ONLY deals in Datomic IDs, which would be safe to pass to `datomic.api/entid`, or intearct with `d/index-range` if the index stores eids.
 - For testing, the `eacl.datomic.datomic-helpers/with-mem-conn` is a macro that creates a fresh in-memory Datomic database with some initial tx-data transacted. Refer to how tests use this to run test against a fresh in-memory Datomic database while avoiding datom conflicts, e.g.
 ```
 (with-mem-conn [conn schema/v5-schema]
   @(d/transact conn fixtures/base-fixtures)
   (let [client (eacl/make-client conn {})])
      (eacl/can? client (->user :test/user) :view (->server :test/server1)))
 ```
 - If you are unable to read a file you expect to exist using `clojure-mcp`'s `read_file` tool, use the `LS` tool and ensure you have the correct path. If the file is expected to exist, and you can't access it, stop and ask for clarification.

## Steps:

0. Read and understand the SpiceDB gRPC API, which EACL aims to mirror but in idiomatic Clojure, backed by Datomic.
1. Read the SpiceDB DSL schema in `resources/cloudafrica-spice.production.schema` which we aim to support in EACL.
2. Read the recursive Datomic Datalog rules in `src/eacl/datomic/rules/optimized.clj`
3. Read the EACL Datomic schema in `src/eacl/datomic/schema.clj`
4. Read the existing Relation & Permission schema fixtures in `test/eacl/datomic/fixtures.clj` which aim to mirror the SpiceDB schema using `Relation` & `Permission` internals.
5. Read the EACL Datomic implementation in `src/eacl/datomic/impl.clj` and `src/eacl/datomic/impl_optimized.clj`
6. Read and understand the tests in `test/eacl/datomic/impl_test.clj`. Note that `lookup-resources` tests related to pagination may fail due to return order, but since we materialize the entire index using Datalog rules and sort, these should be stable.
7. Run the tests in `test/eacl/datomic/impl_test.clj` by evaluating `(clojure.test/run-tests 'eacl.datomic.impl-test)` to understand the output as a baseline.
8. Analyze and the current impelementation in `eacl.datomic.impl-optimized/lookup-resources`.
9. Write a comprehensive plan to update the schem and modify all internals, including `can?`, `lookup-subject`, `lookup-resources` and any other relevant API calls with a 100% correct implementation that will live at the `eacl.datomic.impl-optimized` namespace (path: `src/eacl/datomic/impl_optimized.clj`). Feel free to delete the whole namespace as a secure checkpoint has been made, so we can rollback if needed.
10. Output the plan to `docs/plans/`. Ensure the rewrite conforms to the external API. The external API is perfect, so don't mess with it. There may be some discrepancies in cursor handling between `lookup-subjects` and `lookup-resources`, but should not affect this work (`lookup-resources` cursor usage is newer and correct.)
12. Reflect deelyp on any design changes and ensure the rewrite will all tests pass with the exclusion of `expand-permission-tree` which is not currently implemented.
13. Ask for confirmation before making schema changes.
14. Good luck. You are an AGI, so I know you will produce a perfect, elegant solution.
