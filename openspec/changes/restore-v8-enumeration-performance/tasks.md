## 1. Lock Regression Evidence and Baselines

- [x] 1.1 Add deterministic 10,000-server Explorer fixtures covering user pagination, owner multipath counts, and super-user counts
- [x] 1.2 Add the 50,000-server super-user acceptance fixture and assert that the acyclic schema never reports a recursive traversal limit
- [x] 1.3 Capture matched v7 latency baselines and v8 logical-work counters for the named page and count scenarios in the benchmark manifest
- [x] 1.4 Add failing DataScript and Datahike tests that expose continuation misses, page-prefix replay, and page-ordinal work growth
- [x] 1.5 Add failing route and count tests that expose acyclic requests entering the recursive engine and duplicating multipath traversal work

## 2. Extend the Formal Authority

- [x] 2.1 Add counterexample-ledger entries and executable witnesses for disconnected continuation reuse and acyclic requests routed through recursive traversal
- [x] 2.2 Extend `RoutingCertificate.dfy` to prove sound and complete reachability classification and bind certificates to normalized schema identity
- [x] 2.3 Extend `AcyclicEngine.dfy` with duplicate-free ordered forward and reverse enumeration over overlapping indexed grant streams
- [x] 2.4 Specify and prove exact acyclic counting equivalent to denotational authorization cardinality
- [x] 2.5 Specify and prove continuation-resume equivalence to authenticated deterministic replay
- [x] 2.6 Prove recursive-budget isolation and deterministic page/count work bounds for certified acyclic roots
- [x] 2.7 Add refinement lemmas and mutation controls that fail when classification, deduplication, continuation, or limit isolation is disconnected from the executable path
- [x] 2.8 Run the complete Dafny verification suite and resolve every new or existing obligation without weakening specifications

## 3. Regenerate Executable Authority

- [x] 3.1 Extend the formal generation boundary to export routing, acyclic page, continuation, exact count, and work-telemetry operations
- [x] 3.2 Regenerate JVM and browser/JavaScript artifacts from the verified Dafny source
- [x] 3.3 Update cross-runtime vectors for route selection, ordered pages, duplicate suppression, continuation resume, exact counts, and bounded failures
- [x] 3.4 Pass browser bundle, JVM build, generated-boundary manifest, and clean-regeneration checks with no hand-edited derived artifacts

## 4. Integrate Certified Traversal Routing

- [x] 4.1 Add the shared fail-closed dispatcher that validates the generated routing certificate for each normalized schema and permission root
- [x] 4.2 Route certified acyclic forward lists, reverse lists, and exact counts through the generated acyclic authority
- [x] 4.3 Retain the generated recursive fixed-point route and configured safety limits for roots that reach recursive SCCs
- [x] 4.4 Separate acyclic scan, merge, duplicate, continuation, and emission telemetry from recursive traversal counters
- [x] 4.5 Add shared-engine tests for stale certificate rejection, route parity, recursive-limit enforcement, and acyclic recursive-counter isolation

## 5. Wire Private Continuations Across Backends

- [x] 5.1 Define the adapter-neutral bounded continuation interface and its complete authenticated cache key
- [x] 5.2 Adapt Datomic continuation handling to the shared interface without changing public cursor or snapshot behavior
- [x] 5.3 Supply the shared continuation context from DataScript list and reverse-list entry points
- [x] 5.4 Supply the shared continuation context from Datahike list and reverse-list entry points
- [x] 5.5 Implement bounded eviction and hit, miss, eviction, and occupancy telemetry for each client-private store
- [x] 5.6 Test cross-client isolation, query-key separation, eviction replay, mutation invalidation, and the absence of private traversal state in public cursors

## 6. Implement Exact Acyclic Enumeration and Count Integration

- [x] 6.1 Connect backend indexed scans to the generated ordered multipath merge contract
- [x] 6.2 Integrate duplicate-free page emission with existing Relay cursors, lookahead, ordering, and constraints
- [x] 6.3 Integrate generated exact acyclic counting while retaining public `count-limit` and bounded-failure semantics
- [x] 6.4 Add denotational differential tests for direct, inherited, overlapping, super-user, forward, and reverse authorization paths
- [x] 6.5 Add CLJ/CLJS and Datomic/DataScript/Datahike parity tests for result sets, ordering, boundaries, cursors, counts, and failures

## 7. Enforce Correctness and Performance Gates

- [x] 7.1 Add deterministic work-envelope gates for 10,000-server pagination, owner counts, and super-user counts
- [x] 7.2 Add the matched-host warmed-median gate requiring named v8 scenarios to remain within 2.0 times their v7 baselines
- [x] 7.3 Verify that continuation-hit page work does not grow with page ordinal on Datomic, DataScript, and Datahike
- [x] 7.4 Run the regular EACL nREPL test suite and all formal smoke, refinement, mutation, and cross-runtime checks
- [x] 7.5 Run the heavy 10,000-resource benchmark suite and resolve every correctness, work, or latency regression
- [x] 7.6 Run the 50,000-resource super-user acceptance suite and confirm exact completion with zero recursive traversal work

## 8. Validate EACL Explorer and Release Evidence

- [x] 8.1 Run the upgraded local EACL Explorer against the corrected EACL build with 10,000 seeded servers
- [x] 8.2 Verify subject selection, view/admin switching, exact counts, and successive server pages for user, owner, and super-user subjects
- [x] 8.3 Repeat the super-user and pagination acceptance checks with 50,000 seeded servers
- [x] 8.4 Record before/after latency and logical-work evidence, formal verification output, generation provenance, and backend/runtime coverage in the release notes
- [x] 8.5 Confirm the public API and cursor formats remain compatible and document any non-breaking telemetry additions

## 9. Keep Empty Recursive Schemas Fast

- [x] 9.1 Add formal route-selection and denotational lemmas for inactive recursive relationship guards
- [x] 9.2 Add exact snapshot-bound relation-prefix population operations and certification coverage for Datomic, DataScript, and Datahike
- [x] 9.3 Route recursive syntax with empty cycle guards through the bounded acyclic evaluator and restore recursive routing when a guard becomes populated
- [x] 9.4 Add CLJ/CLJS regression and mutation coverage for empty and populated recursive Explorer schemas
- [x] 9.5 Add tested Non-recursive and Recursive schema preset tabs to EACL Explorer
- [x] 9.6 Make repeated nested server pagination for `super-user -> account-0001` hit the enabled client-private cache, with stable relationship-query cache-key coverage
- [x] 9.7 Keep nested page and background-total cache provenance separate, and clear pre-mutation cursor stacks after seed or schema writes
