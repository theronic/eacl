# Plan: Fix eacl.core-test Failures

## 1. Problem Summary

The `clj -M:test -n eacl.core-test` command reveals multiple (15 out of 27) test failures. These failures occur in permission checks (`can?`), subject enumeration (`eacl/lookup-subjects`), and resource enumeration (`eacl/lookup-resources`).

The root cause appears to be a mismatch between how entity relationships are defined in `eacl.fixtures.clj` and the expectations of the Datalog rules in `eacl.core.clj`, particularly for "arrow" permissions (where permission on one resource depends on a related resource) and indirect permission inheritance.

## 2. Root Cause Analysis

The Datalog rules in `eacl.core.clj`, specifically the `has-permission` rule (both its indirect and arrow variants), expect relationships that are used for permission traversal to be structured such that the resource *on which permission is being checked or derived* is the `:eacl.relationship/subject` in the linking relationship tuple.

For example, for an arrow permission like `(Permission :vpc :account :admin :admin)` (a `:vpc` gets `:admin` if its related `:account` has `:admin` for the subject), the Datalog rule expects to find a relationship like `[:test/vpc :eacl.relationship/subject ?vpc] [:test/vpc :eacl.relationship/relation-name :account] [:test/vpc :eacl.relationship/resource ?account]`. In simpler terms: `(:test/vpc)-[:account]->(:test/account)`.

However, several key structural relationships in `eacl.fixtures.clj` are defined in the opposite direction. For instance:
-   VPC to Account: `(Relationship "account-1" :account "vpc-1")` implies `(:test/account1)-[:account]->(:test/vpc1)`.
-   Server to Account: `(Relationship "account-1" :account "server-1")` implies `(:test/account1)-[:account]->(:test/server1)`.
-   Account to Platform (for super-user): `(Relationship "platform" :platform "account-1")` implies `(:test/platform)-[:platform]->(:test/account1)`.

This directional mismatch means the Datalog rules cannot find the necessary intermediate resources to correctly evaluate arrow or indirect permissions, leading to the observed test failures. Direct permissions (e.g., `:test/user1` owning `:test/account1`) generally work because their Datalog rule variant aligns with how those specific user-to-resource ownerships are defined.

## 3. Proposed Solution

The primary approach is to correct the relationship definitions in `eacl.fixtures.clj` to align with the Datalog rules' expectations.

### Step 1: Correct Relationship Definitions in Fixtures

Modify the following types of relationships in `test/eacl/fixtures.clj`. The entity string identifiers (e.g., "vpc-1") will need to be used or mapped to their `:db/ident` equivalents (e.g., `:test/vpc`) as appropriate for the `Relationship` helper if it resolves them. Assuming string IDs are resolved to entities:

1.  **VPC linked to Account:**
    *   Current: `(Relationship "account-1" :account "vpc-1")`, `(Relationship "account-2" :account "vpc-2")`
    *   Proposed: `(Relationship "vpc-1" :account "account-1")`, `(Relationship "vpc-2" :account "account-2")`
    *   Rationale: To support `(Permission :vpc :account :admin :admin)`, the VPC must be the subject of the `:account` relationship pointing to the actual account.

2.  **Server linked to Account:**
    *   Current: `(Relationship "account-1" :account "server-1")`, `(Relationship "account-2" :account "server-2")`
    *   Proposed: `(Relationship "server-1" :account "account-1")`, `(Relationship "server-2" :account "account-2")`
    *   Rationale: To support permissions like `(Permission :server :account :admin :view)`, the Server must be the subject of the `:account` relationship.

3.  **Account linked to Platform (for super-user propagation via `:platform_admin`):**
    *   Current: `(Relationship "platform" :platform "account-1")`, `(Relationship "platform" :platform "account-2")`
    *   Proposed: `(Relationship "account-1" :platform "platform")`, `(Relationship "account-2" :platform "platform")`
    *   Rationale: To support `(Permission :account :platform :platform_admin :admin)`, the Account must be the subject of the `:platform` relationship.

4.  **Team linked to Account:**
    *   Current: `(Relationship "account-1" :account "team-1")`, `(Relationship "account-2" :account "team-2")`
    *   Proposed: `(Relationship "team-1" :account "account-1")`, `(Relationship "team-2" :account "account-2")`
    *   Rationale: Assuming team permissions might depend on the account they belong to (e.g., an arrow permission like `(Permission :team :account :some_perm_on_account :some_perm_on_team)`). The `Relation` is defined as `(Relation :team/account :account)`.

### Step 2: Verify `Relation` and `Permission` Definitions

After correcting the `Relationship` data, re-verify that the `(Relation ...)` and `(Permission ...)` definitions in `eacl.fixtures.clj` still accurately describe the intended security model. This step is mostly a sanity check, as these definitions already seem to imply the directionality that the Datalog rules use. For example, `(Permission :vpc :account :admin :admin)` correctly states a VPC's `:account` relation is the source for the arrow.

### Step 3: Review Super User Permissions

With the `Account <-> Platform` relationship corrected, trace the logic for super-user:
-   `user/super-user` has `:super_admin` on `"platform"` via `(Relationship "super-user" :super_admin "platform")`.
-   This grants `user/super-user` the `:platform_admin` permission on `"platform"` via `(Permission :platform :super_admin :platform_admin)`.
-   Then, for an account like `"account-1"`, the corrected `(Relationship "account-1" :platform "platform")` should allow `user/super-user` to gain `:admin` on `"account-1"` via `(Permission :account :platform :platform_admin :admin)`.
-   This should then cascade to other resources linked to the account if further arrow permissions are defined (e.g., from VPC to Account).

### Step 4: Test Incrementally

After applying the fixture changes:
1.  Re-run the tests: `clj -M:test -n eacl.core-test`.
2.  Analyze any remaining failures. It's possible that some test assertions themselves might have been written with the "reversed" relationship logic in mind, or there might be other subtle issues.

## 4. Alternative (If Data Correction is Insufficient/Problematic)

If reversing relationships proves infeasible (e.g., due to strict external data modeling constraints not apparent here, or if it breaks other implicit assumptions), the Datalog rules (specifically `has-permission`) would need to be modified. This could involve:
-   Adding alternative clauses to look for relationships in both directions.
-   Introducing specific rules for "backward traversal" for certain relation types.
This is considered a more complex fallback and should be avoided if data correction is viable.

## 5. Addressing Linter Errors (Secondary)

The linter errors (`Unresolved symbol: conn`) in `test/eacl/core_test.clj` within the `with-fresh-conn` macro body are likely a linter-specific issue with static analysis of macro-expanded code. Clojure's lexical scoping should ensure `conn` is available. The tests currently execute (though fail), suggesting `conn` is resolved at runtime. These errors should be re-evaluated after the primary test failures are resolved. If they persist and indicate a genuine runtime problem (unlikely), the macro usage or test structure might need minor adjustments.

## 6. Expected Outcome

-   All 27 assertions in `eacl.core-test` should pass.
-   The EACL system will correctly evaluate permissions as defined by the schema, relationships, and Datalog rules.
-   A clearer understanding of the expected data directionality for EACL's rules will be established. 