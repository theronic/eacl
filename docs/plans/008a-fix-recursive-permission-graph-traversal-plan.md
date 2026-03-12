# Implementation Plan for Fixing Bugs in traverse-permission-path (ADR 008 Follow-up)

## Status Summary

**Current Issues**:
- Incorrect tuple values in index-range calls for intermediate lookups in arrow permissions (using :target-type incorrectly instead of proper resource-type).
- Reliance on d/q for reverse lookups in arrow permissions, which materializes relationships and ruins performance (violates O(log N) goal).
- Subject/resource types not pre-resolved in paths, leading to inefficient queries.
- Duplicate handling and sorting are correct via lazy-merge-dedupe-sort, but the input sequences must remain lazy and index-sorted.
- Tests for :relation_view are failing due to these traversal bugs.

**Key Constraints** (MUST FOLLOW):
- NEVER use d/q for Relationship lookups – it materializes the index and is O(N) slow for 10M+ entities.
- NEVER use (sort) or (dedupe) – they materialize the full index. Always preserve index order and use lazy-merge-dedupe-sort or lazy-merge-dedupe-sort-by for deduplication across multiple lazy seqs from d/index-range.
- All IDs are internal Datomic eids – no coercion needed.
- Do not change fixtures.clj or any tests – only fix indexed.clj to make tests pass.
- If a new tuple index is needed (e.g., for efficient reverse traversal), ask for user confirmation before proceeding.
- Results from lookup-resources must be in stable index order (no explicit sorting).
- Permission paths from get-permission-paths may need enhancement to include resolved types for efficiency.

**Proposed Fixes Overview**:
- Enhance get-permission-paths to pre-resolve and include expected subject-types for relations in paths (using find-relation-def).
- Fix tuple values in index-range calls to use correct components.
- Replace all d/q in traversal with d/index-range on appropriate tuples.
- Use existing reverse tuple index (:eacl.relationship/resource-type+resource+relation-name+subject-type+subject) for arrow reverse lookups.
- If reverse index is insufficient (e.g., for certain arrow directions), propose a new index but seek confirmation.
- Ensure all traversals yield sorted lazy seqs of eids for lazy-merge-dedupe-sort.
- No new schema changes anticipated, but confirm if needed for reverse arrows.

**Assumptions**:
- Existing schema tuples are sufficient: forward (:eacl.relationship/subject-type+subject+relation-name+resource-type+resource) and reverse (:eacl.relationship/resource-type+resource+relation-name+subject-type+subject).
- For arrow to relation: Traverse forward to intermediates, then reverse from intermediates to resources (or vice versa based on direction).
- For arrow to permission: Recursive call, then reverse lookup.
- Tests will pass once traversals use correct indices without d/q.

**Risks**:
- If reverse index doesn't cover all arrow cases (e.g., missing subject-type in some lookups), may need a new index like :eacl.relationship/resource+relation-name+subject-type+subject (but ask confirmation).
- Complex chains (e.g., nic->lease->network) require correct type resolution in paths.

## Step-by-Step Plan

Follow steps sequentially. After each step, eval the changed function in clojure-mcp and run relevant tests from indexed_test.clj (e.g., via `(clojure.test/run-tests 'eacl.datomic.impl.indexed-test)`). If a test fails, use think tool to debug without changing tests/fixtures. Only modify indexed.clj.

### Step 1: Analyze and Document Bugs in Code
- [ ] In indexed.clj, add comments above buggy sections in traverse-permission-path and traverse-single-permission-path:
  - For :arrow with :target-relation: Comment "// BUG: intermediate-start/end uses :target-type as resource-type, but should use resolved type from Relation."
  - For d/q calls: Comment "// BUG: d/q materializes index – replace with d/index-range on reverse tuple."
  - For arrow to permission: Similar comment on d/q.
- [ ] Eval the file to ensure no syntax errors.
- [ ] Run all tests – expect failures on relation_view and complex traversals.

### Step 2: Enhance get-permission-paths to Resolve Types
- [ ] In get-permission-paths, when building paths:
  - For direct (:self): After finding rel-def, include :subject-type (:eacl.relation/subject-type rel-def).
  - For arrow to relation: After via-rel-def, set :target-type (:eacl.relation/subject-type via-rel-def). For sub-paths, find target-rel-def and include :subject-type in the relation sub-path.
  - For arrow to permission: Similarly, resolve and propagate :subject-type in sub-paths.
- [ ] Update return structure: Ensure every :relation path has :subject-type resolved.
- [ ] Add a guard: If rel-def is nil, throw or log "Missing Relation definition".
- [ ] Test: Add a new deftest in indexed_test.clj (but wait – no test changes; instead, eval get-permission-paths manually in REPL via clojure-mcp and prn results for :server :view – ensure types are resolved).
- [ ] Run permission-helper-tests and get-permission-paths-tests – they should pass with enhanced paths.

### Step 3: Fix Tuple Values in Direct Relation Traversals
- [ ] In traverse-single-permission-path for :relation:
  - Ensure start-tuple: [subject-type subject-eid (:name path) resource-type (or cursor-eid 0)]
  - end-tuple: [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]
  - Filter (> resource-eid (or cursor-eid 0)) – but since index-range starts from start-tuple, it should be inclusive; adjust if needed.
- [ ] In traverse-permission-path for :relation: Similar fix – use resource-type, not :target-type.
- [ ] Remove any incorrect :target-type usage in tuples.
- [ ] Eval and run check-permission-tests – expect some passes, but arrow failures remain.

### Step 4: Replace d/q with Index-Range for Arrow to Relation
- [ ] In traverse-single-permission-path for :arrow with :target-relation:
  - For intermediates: Use forward index as is (fix tuple if needed: resource-type should be (:target-type path), which is intermediate-type).
  - For resources from intermediates: Replace d/q with reverse index-range on :eacl.relationship/resource-type+resource+relation-name+subject-type+subject.
    - But wait: This index is [resource-type resource relation subject-type subject].
    - For reverse: We have intermediate-eid (subject), via-relation (relation), resource-type (resource-type), but we want resources (resource) where subject is intermediate-eid.
    - So, to get resources from subject (intermediate): This is forward from subject, not reverse.
    - BUG ANALYSIS: The current d/q is forward from subject (intermediate-eid) to resources via relation (via-relation).
    - FIX: Use the forward tuple :eacl.relationship/subject-type+subject+relation-name+resource-type+resource.
      - start: [intermediate-type intermediate-eid via-relation resource-type (or cursor-eid 0)]
      - end: [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]
      - Extract :v last component as resource-eid.
- [ ] Map over intermediate-eids, create lazy seq per intermediate, then lazy-merge-dedupe-sort the seqs.
- [ ] Ensure intermediate-type is resolved from path (:target-type).
- [ ] If index doesn't support (e.g., type mismatch), stop and ask: "Confirm new tuple index needed: :eacl.relationship/subject+relation-name+resource-type+resource (without subject-type if types vary)."
- [ ] Run complex-relation-tests and lookup-resources-tests – expect arrow-to-relation passes.

### Step 5: Replace d/q with Index-Range for Arrow to Permission
- [ ] In traverse-single-permission-path for :arrow with :target-permission:
  - Recursive call to traverse-permission-path yields intermediate-eids (sorted).
  - For resources from intermediates: Same as above – use forward index-range from intermediate-eid (subject) via via-relation to resources.
    - start/end tuples as in Step 4, with intermediate-type from path.
- [ ] lazy-merge-dedupe-sort over the per-intermediate seqs.
- [ ] If recursion depth > some limit, add cycle detection (seen set of permission-names).
- [ ] Run get-permission-paths-tests and traverse-paths-tests – expect arrow-to-permission passes.

### Step 6: Fix traverse-permission-path Integration
- [ ] In traverse-permission-path: Update to use fixed traverse-single-permission-path.
- [ ] Ensure path-results uses lazy-merge-dedupe-sort-by first (for [eid path] tuples).
- [ ] Remove sorted-deduped (no sort/dedupe).
- [ ] In lazy-merged-lookup-resources: Use fixed traversal, take limit on merged.
- [ ] Run all lookup-resources-optimized-tests and lookup-resources-with-merge-tests.

### Step 7: Verify can? Uses Fixed Paths
- [ ] In can?: Update to use resolved paths from get-permission-paths.
- [ ] For :arrow: Use d/datoms on forward tuple for direct checks, and targeted d/datoms (not q) for reverses.
  - Similar to traversal but short-circuit on any match.
- [ ] Run can-optimized-tests – all should pass.

### Step 8: Final Verification and Cleanup
- [ ] Remove BUG comments added in Step 1.
- [ ] Run entire test suite – all must pass, including failing relation_view.
- [ ] If any test fails, think: "Why? (e.g., type resolution missing?)" and loop back.
- [ ] If new index confirmed needed, add to plan: "Transact new schema in tests, but ask user first."
- [ ] Profile with large data: Generate 10k relationships in REPL, time lookup-resources.

Execute sequentially. If stuck (e.g., index insufficient), ask for clarification. Success: All tests pass without d/q or sort/dedupe.