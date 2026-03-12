# Plan for Implementing Arrow Syntax in EACL (core3)

1.  **Understand Existing `core2` Implementation and Target Schema:**
    *   Thoroughly review `src/eacl/core2.clj` to understand how relations, permissions, and Datalog rules are currently defined and processed.
    *   Analyze `test/eacl/core2_test.clj` to understand the existing test patterns.
    *   Carefully study the arrow syntax examples in `resources/sample-spice.schema` (e.g., `permission admin = owner + platform->super_admin`).
    *   Examine `test/eacl/fixtures.clj` to see how SpiceDB-like definitions are currently translated into Datomic transactions.

2.  **Design Datomic Schema and Helper Extensions for `core3`:**
    *   **Representing Arrow Relations:** Decide how to represent an "arrow relation" (e.g., `account->admin`) in the Datomic schema within `src/eacl/core3.clj`. This might involve:
        *   Extending the existing `:eacl.permission/relation-name` to store a composite structure or a special keyword indicating an arrow.
        *   Alternatively, introducing new attributes to the `Permission` entity or a new entity type specifically for arrow components if deemed cleaner.
    *   **Updating Helper Functions:**
        *   Copy the implementations of `Relation`, `Permission`, and `Relationship` from `src/eacl/core2.clj` to `src/eacl/core3.clj` as a starting point.
        *   Modify the `Permission` helper function in `src/eacl/core3.clj`. It currently has a placeholder `([])`. It will need to parse permission definitions that include arrow syntax (e.g., `account->admin`) and simple relations (e.g., `owner`). It should be able to distinguish between a direct relation and an arrow relation leading to another permission on a related resource type.
        *   The `v3-eacl-schema` in `src/eacl/core3.clj` (currently `[]`) will need to be updated with any new attributes or entities designed above. It should start with a copy of `v2-eacl-schema` from `src/eacl/core2.clj` and be augmented.

3.  **Develop Datalog Rules for Arrow Syntax in `core3`:**
    *   The `rules` definition in `src/eacl/core3.clj` (currently `[]`) needs to be implemented.
    *   Start by copying the rules from `src/eacl/core2.clj`.
    *   Extend these rules to handle the new arrow syntax. This will involve creating Datalog rules that can:
        *   Identify when a permission depends on an arrow relation.
        *   Find the intermediate resource (e.g., the `account` in `vpc`'s `account->admin`).
        *   Recursively check if the subject has the target permission (e.g., `admin`) on that intermediate resource.
        *   Handle the `+` (OR) semantics in permission definitions correctly.

4.  **Implement Core Logic in `src/eacl/core3.clj`:**
    *   Implement the `can?` function using the new Datalog rules.
    *   Implement `lookup-subjects` and `lookup-resources` functions. These currently throw "not impl." exceptions and will need to use the new Datalog rules.

5.  **Update and Fix Tests in `test/eacl/core3_test.clj`:**
    *   The test file `test/eacl/core3_test.clj` is largely a copy of `test/eacl/core2_test.clj` and has several linter errors.
        *   **Fix `conn` Unresolved Symbol Errors:** Ensure `with-fresh-conn` macro is used correctly and `conn` is available in the scope where Datomic transactions and queries are made.
        *   **Fix `Permission` Arity Errors:** The `Permission` function calls in tests need to match the updated signature in `src/eacl/core3.clj` (from step 2.2).
    *   **Adapt Existing Tests:** Ensure existing tests (copied from `core2_test.clj`) pass with the `core3` implementation, once the basic functions are ported.
    *   **Add New Arrow Syntax Tests:**
        *   Create specific test cases that verify the correct functioning of arrow syntax. For example, for `definition vpc { relation account: account; permission admin = account->admin }`, test if a user who is `admin` of an `account` related to a `vpc` becomes `admin` of that `vpc`.
        *   Test scenarios with multiple arrow relations and combinations with direct relations (e.g., `permission view = owner + account->view`).
        *   Use examples from `resources/sample-spice.schema` to guide test case creation.

6.  **Iterative Testing and Refinement:**
    *   Continuously run the tests in `test/eacl/core3_test.clj` throughout the development process.
    *   Debug and refine the schema, helper functions, Datalog rules, and core logic in `src/eacl/core3.clj` until all tests, including the new arrow syntax tests, pass.
    *   Address any failing tests mentioned in the ADR or discovered during development. 
