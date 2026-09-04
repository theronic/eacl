## 1. Freeze the v9 storage contract

- [x] 1.1 Record storage ABI 9, the exact forward/reverse attribute names, five component types, `nil` Phase 1 qualifier rule, and first-four logical identity in shared constants; verify codec contract tests assert every field and name.
- [x] 1.2 Bump adapter, ordering, cache, continuation, and endpoint-codec compatibility identities that depend on tuple layout; verify old four-slot artifacts are rejected or missed without a compatibility reader.
- [x] 1.3 Add a source audit that rejects hand-written four/five-slot Relationship literals outside the shared codec; verify the audit fails on an injected fixture and passes on production sources.

## 2. Install and consume one five-slot representation

- [x] 2.1 Add v9 Relationship attributes to Datomic, Datahike, Datalevin, and DataScript schemas; verify physical shape tests match `[keyword ref keyword ref ref]` or the equivalent fixed vector contract.
- [x] 2.2 Extend endpoint construction, decoding, peer derivation, exact retraction, prefix validation, and integrity descriptions to slot five; verify shared CLJ/CLJS codec tests cover malformed arity/type, `nil`, and non-`nil` inputs.
- [x] 2.3 Update forward/reverse scans and full-arity seek/rseek bounds to preserve component-four ordering; verify adjacent-prefix, ascending, descending, and continuation tests on every backend.
- [x] 2.4 Replace exact four-value direct membership with a one-seek first-four identity probe and duplicate guard; verify positive, negative, and adjacent-qualifier cases consume one Relationship attribute stream.
- [x] 2.5 Make Phase 1 authorization and public Relationship reads reject non-`nil` qualifier refs with the agreed typed error; verify no code path treats unsupported qualified data as unconditional.

## 3. Preserve mutations, deletion, and proof publication

- [x] 3.1 Update `:create`, `:touch`, and `:delete` planning to use first-four logical identity while emitting exact five-slot mutations; verify concurrent creates, no-op touch, repair, and identity-only delete tests.
- [x] 3.2 Update commit-time conflict guards and batch normalization for qualifier-independent identity; verify two values differing only in slot five cannot be committed through admitted writers.
- [x] 3.3 Update safe object deletion and integrity repair to copy slot five symmetrically and stamp each affected Relation; verify a dangling half and a mismatched qualifier are reported without broadening access.
- [x] 3.4 Include complete v9 endpoint values in managed/unknown-writer proof inputs and Relation mutation detection; verify a slot-five mutation invalidates affected proof state even before qualifiers are supported.
- [x] 3.5 Update Datalevin write-policy rules and all protected-attribute sets to cover v9 attributes; verify direct protected writes fail and admitted writes advance the correct Relation generation.

## 4. Build the explicit migration framework

- [x] 4.1 Define the portable migration state/report/error model and recognized state transitions; verify unit tests reject skipped, regressing, concurrent, and unknown states.
- [x] 4.2 Implement preflight detection that rejects v6 Relationship entities with the v6-to-v7 prerequisite, then enumerates and validates v7 schema shape, Relation references, endpoint existence, exact peer pairs, and first-four uniqueness and records a durable canonical source count/digest; verify bounded diagnostics identify every seeded legacy/corruption class.
- [x] 4.3 Implement a pure canonical v7-pair-to-v9-pair batch planner with `nil` qualifier and affected-Relation set; verify property tests preserve logical identities and pair symmetry.
- [x] 4.4 Implement idempotent interrupted-state reconciliation for matching target pairs and conflict rejection for non-equivalent target values; verify restart traces converge or fail closed deterministically.
- [x] 4.5 Implement final source-empty, target-parity, uniqueness, target-versus-preflight count/digest, and Relation-version verification; verify no target version stamp is produced when any check is killed or altered.

## 5. Implement backend side-effecting migration entry points

- [x] 5.1 Implement `eacl.datomic.migrations.v7-to-v9/migrate!` with bounded transactions, durable state, CAS/fence checks, progress reports, and final stamp; verify interruption at every phase resumes to the same target graph.
- [x] 5.2 Implement the equivalent Datahike migration using native transaction and writer constraints; verify memory and durable-store restart tests plus concurrent-head rejection.
- [x] 5.3 Implement the equivalent DataScript migration for mutable connections without introducing a runtime dual reader; verify JVM and CLJS fixtures convert and then run only v9 operations.
- [x] 5.4 Implement the equivalent Datalevin migration through its protected writer/write-policy boundary; verify current source data is removed and target generation rules remain installed.
- [x] 5.5 Ensure each migration rerun on a complete store returns an already-complete report and performs no Relationship rewrite; verify transaction/report counters remain unchanged.

## 6. Enforce the startup and serving boundary

- [x] 6.1 Add bounded target-storage compatibility checks before each bundled client becomes usable; verify v6, v7, mixed, interrupted, wrong-stamp, incompatible-schema, and complete-target fixtures produce the specified outcomes and a million-Relationship complete store performs no full graph scan at startup.
- [x] 6.2 Remove or reject automatic migration construction options and any v7 read fallback; verify source search and runtime instrumentation observe zero v7 Relationship reads after successful client construction.
- [x] 6.3 Make cache restore, continuation restore, and cursor decode enforce the new storage/adapter/order ABI; verify old cache entries miss and old cursors fail loudly rather than restart silently.
- [x] 6.4 Add cross-backend conformance fixtures generated from one logical graph before/after migration; verify authorization, lookup, count, deletion, and Relationship pages are identical after qualifier removal from comparison.

## 7. Measure and document the release

- [x] 7.1 Benchmark four-slot versus five-slot-`nil` direct checks, scans, arrows, pages, exhaustive counts, allocation, and cold/warm backend reads; verify recorded numerical budgets and raw results are checked into the implementation report.
- [x] 7.2 Measure tuple/index density, durable bytes, transaction size, migration throughput, peak storage, and restart cost on representative data; verify the report distinguishes logical datom count from physical storage.
- [x] 7.3 Write the v7-to-v9 operator guide with backup, rehearsal, maintenance fencing, invocation, progress, verification, cutover, cache/cursor reset, and restore rollback; verify every backend entry point is executable as documented.
- [x] 7.4 Update README, release notes, backend guides, schema docs, and error reference to distinguish EACL v8, permission storage 8, and Relationship storage 9; verify no contradictory version terminology remains.
- [x] 7.5 Run the CI-equivalent nREPL suites, DataScript CLJS suite, source-closure audit, formal regression battery, and strict OpenSpec validation; verify all gates are green without adding model/shadow checks to ordinary request paths.
