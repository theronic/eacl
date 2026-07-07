# 006. Record architecture decisions

Date: 2025-07-12

## Status

Accepted

## Context

- EACL is a SpiceDB-compatible ReBAC Authorization system built in Clojure and backed by Datomic.
- The goal is to implement the core SpiceDB features in Clojure, backed by Datomic, in a way that satisfies the `IAuthorization` protocol.

### There are three major problems in EACL:

  1. In EACL, `lookup-resources` is inefficient because it reuses the `has-permission` Datalog rule used in `can?` which enumerates full result set. For 1M+ resources, this is way too slow. 
    - A direct index-based implementation was attempted in `src/eacl/datomic/impl_indexed.clj`, but it has bugs. Specifically, it omits some resources because it does not traverse in both directions.
    - Efficient graph traversal requires a recursive implementation that traverses the relationship / schema graph in Datomic while leveraging Datomic tuple indices defined in `src/eacl/datomic/schema.clj` for performance.
    - When calling `lookup-resources`, the subject ID & type are known and the target resource type is known, but not intermediate resource or subject types.
    - Duplicate resources can be encountered along multiple paths through the graph, which can be traversed in parallel.
    - Duplicate resource type+ID need to be deduplicated while maintaining stable sort order.
    - `lookup-resources` return order has to be stable, even if undefined, to enable predictable and performance cursor-based pagination.
  2. In EACL, arrow permissions require permissions on the via-relation and do not support relation references, which introduces unnecessary intermediate permissions that pollute the permission schema (see `test/eacl/datomic/fixtures.clj`).
   - This may require schema changes, or more explicit Permission definitions to support either relation or permission references. The aim here is to achieve feature-parity with SpiceDB, with the option to add a schema DSL later.
  3. When re-transacting schema (Relations + Permissions), Datomic will throw datom conflict exceptions because we do not check for existing schema. We will not attempt to address this yet, but mentioning in case you get datomic conflicts when re-transactaging against a stateful in-memory database. Be careful notre-transact the same schema, or use `with-mem-conn` macro to start with a fresh in-memory Datomic database every time.

### Detailed Context:
- The `eacl.datomic.impl/can?` function answers the question, "can <subject> do <permission> to <resource>?" via recursive Datomic Datalog rules in `eacl.datomic.rules.optimized/check-permission-rules`.
- EACL has some deficiencies which need to be fixed:
  - EACL should have the same fundamental abilities as SpiceDB, including performant resource enumeration via cursor-based pagination, without materializing the full index.
  - The EACL Datomic implementation in `eacl.datomic.impl` models schema Relations, Permissions and Relationships as Datomic schema attributes that use internal Datomic `:db/id` Long values (eids) as the IDs for subjects & resources. The model is good and seems to accurately model SpiceDB.
  - However, there are multiple reified implementations that attempt to satisfy the `IAuthorization` protocol with varying levels of correctness and performance:
    - `eacl.datomic.impl-base` contains functions that construct Datomic tx-data. These are basically models.
    - `eacl.datomic.impl-optimized` works, but it is slow because it relies on recursive Datalog rules that materialize the full index before it can return results, which is not acceptable performance. For `can?` checks, this is fine, but for `lookup-resources`, this is too slow when there are ~1M+ resources. `lookup-resources` needs to leverage the Datomic tuple indices via fine-tuned graph traversal to satisfy the SpiceDB use-cases with good performance.
    - `eacl.datomic.impl-indexed` contains an optimized implementation of `lookup-resources`, but it has bugs, specifically `lookup-resources` does not handle complex relations as used in `complex-relation-tests` in `test/eacl/datomic/impl_test.clj` because `lazy-arrow-permission-resources` are designed for forward arrows only (subject -> resource via relations). The code does not seem to handle reverse traversal (verify this to be sure).
  - There is a layer on top of the internal implementation called `eacl.datomic.core` which reifies the `IAuthorization` protocol using the internal Datomic implementation, with config support for coercing internal Datomic ID to/from a unique ID, e.g. a `:entity/uuid` field, but you can ignore this, since currently we _only_ care about deficiencies in the internal implementation, i.e. in `eacl.datomic.impl`, `eacl.datomic.impl-fixed` and `eacl.datomic.impl-indexed` and possibly in `eacl.datomic.schema` (if new schema is required to satisfy the requirements).
- EACL does not currently support defining arrow permissions that are based on a relation – it demands an intermediate permission. More details below.
- EACL Arrow Permissions can be generalized to subsume Direct Permissions where the via-relation for the arrow is a special keyword like `:self` which means the current resource, instead of traversing to a related resource, but this can be generalised in the codebase.
 - `count-resources` is like `lookup-resources` but only needs to return count of resources, not hydrated resources.

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

- There is an efficient, but incorrect direct index-based implementation for `eacl.datomic.impl/lookup-resources` in `src/eacl/datomic/impl_indexed.clj`. However, due to traversal bugs in `lookup-resources`, it has been changed back to use the Datalog rules for `can?`, which is slow because it materialized the full index and then imposes limits. This needs to be rewritten to be fast and correct using direct index access. The code in `eacl.datomic.impl-indexed` can provide a guideline on how to use d/datoms and d/index-range, but should only be a guideleine.
- The tests for `lookup-resources` are correct, but failing because the expected return order using the recursive Datalog rules for `can?` does not match the expected order that optimized tuple indices like `:eacl.relationship/subject-type+subject+relation-name+resource-type+resource` would return.

- You have access to clojure-mcp MCP server, which allows you to interact with a live REPL to debug and write code.
  - Note that when you run tests, only the forms that have been evaluated in the REPL will be in context.
  - So, if you change a function and there is a namespace that depends on it *indirectly* via a require, remember to eval both the function, and the namespace that requires it, e.g. `eacl.datomic.impl` requires symbols from namespaces like `eacl.datomic.impl-fixed` or `eacl.datomic.impl-optimized`, so you need to eval the function (or `load-file`) and `eacl.datomic.impl` after that, i.e. they are order-dependent.

### Writing Code & Running Tests via `clojure-mcp` MCP Server

You can run the relevant tests via the clojure-mcp MCP server by eval'ing:

```
(do (require '[eacl.datomic.impl-test])
  (clojure.test/run-tests 'eacl.datomic.impl-test))
```

Since `eacl.datomic.impl` requires `eacl.datomic.impl-indexed`, if you change anything in `impl-indexed`, remember that you also need to re-evaluate `eacl.datomic.impl` to load the latest values into that namespace, or you need to run `load-file` *if* you touched the file system, e.g.
```clojure
(do
  (load-file "src/eacl/datomic/impl_indexed.clj")
  (load-file "src/eacl/datomic/impl.clj")
  (clojure.test/run-tests 'eacl.datomic.impl-test))
```

However, you are more likely to eval new function definitions, so it's probably easier to re-evaluate both namespaces in entirety.

Note that aside from the result order of `lookup-resources` due to index order differences, all other tests in `test/eacl/datomic/impl_test.clj` are passing with the exception of `expand-permission-tree`, which is not currently implemented.

## ADR Decisions

Write a comprehensive plan to rewrite the EACL implementation for `lookup-resources` to a new namespace `eacl.datomic.impl-fixed` (path: `src/eacl/datomic/impl_fixed.clj`) and output this plan to `docs/plans`. The plan should be fool-proof so that an inferior LLM or intermediate developer can implement it without making any mistakes. Add `[ ]` checkboxes to the plan to track status when executing the plan. The result of the plan should:
 - generalizes Arrow Permissions to subsume Direct Permissions via a special `:self` via-relation keyword,
 - passes all tests without modifying any tests (the tests are correct, so DO NOT mess with them)
 - implement `lookup-resources` uses direct Datomic index access via `d/index-range` or `d/datoms` which support for cursor-based pagination (refer to `eacl.datomic.impl-optimized` for how to deal with datoms returned by d/datoms or d/index-range) 
 - pass all tests
 - When making any changes, run the tests to ensure it works.
 - Notify the user if your changes do not seem to affect the tests.
 - Pay careful attention to ensure the stable order of cursor-based pagination.
 - the `eacl.datomic.datomic-helpers/with-mem-conn` is a macro that creates a fresh in-memory Datomic database with some initial tx-data transacted. You can use this macro to avoid dealing with stateful Datomic problems, e.g.

 ```
 (with-mem-conn [conn schema/v5-schema]
   @(d/transact conn fixtures/base-fixtures)
   ...)
 ```

## Steps:

0. Read the SpiceDB gRPC API, which EACL aims to mirror but in idiomatic Clojure, backed by Datomic.
1. Read the SpiceDB DSL schema in `resources/sample-spice.schema` which we aim to support in EACL.
2. Read the EACL Datomic schema in `src/eacl/datomic/schema.clj`
3. Read the existing Relation & Permission schema fixtures in `test/eacl/datomic/fixtures.clj` which aim to mirror the SpiceDB schema.
4. Read the EACL Datomic implementation in `src/eacl/datomic/impl.clj`
5. Read and understand the tests in `test/eacl/datomic/impl_test.clj`. Note that some `lookup-resources` tests may fail due to return order, but if their results are empty when not expected, or throw an exception, that indicates a bug.
6. Run the tests in `test/eacl/datomic/impl_test.clj` by evaluating `(clojure.test/run-tests 'eacl.datomic.impl-test)` to understand the output as a baseline.
7. Analyze and understand the bugs in `eacl.datomic.impl-indexed/lookup-resources`, paying attention to how `d/datoms` and `d/index-range` are used.
8. Write a comprehensive plan to rewrite `lookup-resources` with an efficient, 100% correct implementation that will live at the `eacl.datomic.impl-fixed` namespace (path: `src/eacl/datomic/impl_fixed.clj`).
9. Output the plan to `docs/plans/`.
10. Ensure the rewrite conforms to the same internal API (which is perfect, so don't mess with it)
11. Ensure the rewrite will all tests pass with the exclusion of `expand-permission-tree` which is not currently implemented.
12. Ask for clarification if schema changes are required to satsify these goals.
