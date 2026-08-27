# Validation evidence

Validated on 2026-08-23 from branch `agent/add-authorization-views`.

## Behaviour and conformance

- The aggregate JVM suite passed with 697 tests and 24,917 assertions.
- The shared five-target authorization matrix passed on Datomic, Datahike,
  DataScript, and Datalevin with 4 tests and 960 assertions per backend. The
  targets are writable `Acl`, read-only `Acl`, captured `Snapshot`, selected
  `Snapshot`, and direct `Snapshot`.
- The isolated module suites passed:
  - `eacl`: 216 tests, 4,489 assertions.
  - `eacl-datomic`: 484 tests, 16,220 assertions.
  - `eacl-datahike`: 262 tests, 7,002 assertions.
  - `eacl-datascript` on the JVM: 369 tests, 10,619 assertions.
  - `eacl-datalevin`: 240 tests, 5,185 assertions, both from the repository
    alias and the module-isolated dependency graph.
- After correcting malformed test-only `with-redefs` delimiters, the affected
  DataScript lifecycle namespace was rerun on the JVM: 14 tests and 208
  assertions passed.
- DataScript ClojureScript was run last. It passed 267 tests and 8,131
  assertions under Node with zero failures or errors.
- The generated Clojure/JVM formal boundary passed 49 tests and 15,625
  assertions. The generated JavaScript/oracle suite passed 45 tests and 9,971
  assertions.
- Reader-only and writer-only remote-style protocol implementations passed,
  demonstrating that the public API does not require a local database, basis
  adapter, or source when those capabilities are absent.
- Cross-runtime token fixtures passed for all four backends.

All test execution used persistent nREPL sessions and reloaded changed
namespaces. No test suite was launched directly from the shell.

## Acquisition and lifecycle evidence

The instrumented contract suites establish:

- client construction: zero source acquisitions;
- an `Acl` read: one acquisition and one release;
- capture: one acquisition;
- a retained snapshot read: zero acquisitions;
- direct snapshot construction and reads: zero acquisitions;
- snapshot cursor continuation: zero hidden acquisitions;
- write planning: the planning basis is released before submission;
- Datahike exact-by-commit: zero branch-head/current acquisitions;
- Datalevin owned snapshots: thread affinity, at-most-once release, and optional
  `:maximum-snapshot-retention-ms` fail-closed enforcement.

Lifecycle rotation, retained old bases, late publication, exact-basis LRU
eviction, ordinary/as-of separation, source advance during nested evaluation,
and cache-class sharing all have adversarial regression coverage.

## Formal and static gates

- `bin/formal verify`: 31 Dafny modules, 8,811 proof efforts, zero verification
  errors. The largest single proof effort was 24,106,086 for
  `IndexedReverseCompleteness.ReverseResponseWorkCoversSourceFact`.
- `bin/formal format`: passed.
- Public source closure: 70 roots and 1,641 definitions; SHA-256
  `cfe2b6cdfda024adb3adcf14bbc9b381c153bbd7baa0e6459c4e6b95ae02a3df`.
- Source-closure, counterexample-replay, and stable-discovery gates: 60 tests
  and 3,559 assertions passed.
- Production clj-kondo: zero errors; 37 existing warnings.
- `bin/reflection-gate`: passed after adding the two missing Datomic database
  type hints.
- `git diff --check`: passed.
- `openspec validate add-authorization-views --strict`: passed.
- `bin/formal manifest` generated a digest-bound manifest for 303 source files,
  55 reports, 67 counterexamples, and all four generated artifacts. Its release
  status remains intentionally withheld by the existing manifest because five
  separately declared assurance obligations are still open. This change does
  not relabel those obligations as complete; the next stacked change,
  `reinstate-executable-assurance-gates`, owns that work.

## Build and artifact evidence

The coordinated isolated build/install/smoke passed on Java 26 / class-file
major 70 for:

- `dev.eacl/eacl:8.0.0-SNAPSHOT`
- `dev.eacl/eacl-datomic:8.0.0-SNAPSHOT`
- `dev.eacl/eacl-datahike:8.0.0-SNAPSHOT`
- `dev.eacl/eacl-datascript:8.0.0-SNAPSHOT`

`dev.eacl/eacl-datalevin:8.0.0-SNAPSHOT` also built as an isolated JAR and was
audited at Java 26 / class-file major 70. It is deliberately not publication
ready because its required maintained fork artifact,
`dev.eacl/datalevin-embedded-eacl:1.0.2-eacl.1`, is not yet published.

## Datalevin performance sample

The fresh acquisition gate used Apple arm64, Java 26.0.2, 14 processors, 256
documents, and page size 10:

- acquisition allocation p50: 29,992 bytes, below the 56,682-byte gate and the
  226,728-byte pre-change reference;
- acquisition latency p50/p95: 133.375 / 220.166 microseconds;
- cache-hit check p50: 518.125 microseconds;
- cache-bypass check p50: 456.208 microseconds;
- relationship page of 10 p50: 1,014.542 microseconds;
- source acquisition p50: 63.5 microseconds;
- active native readers after both benchmark phases: zero.

The page result is approximately 1.015 ms, so this evidence does not claim that
every page query is below 1 ms. It is also a 256-document synthetic benchmark,
not the 48-server demo workload. Remaining engine overhead is assigned to the
subsequent performance proposals rather than hidden by a loosened assertion.

## Resolved loopholes found during final audit

- Completed relationship pages are partitioned by normalized consistency mode,
  preventing an already externalized cursor from crossing query scopes.
- Public unknown-object errors retain the original endpoint identity without
  leaking that diagnostic field into transactions or cache identities.
- Cursor recovery is bound to the cursor's exact complete basis identity.
- Managed dependency resolution cannot compare or lift across incompatible
  complete schema generation/scalar-frontier facts.
- DataScript source identity is connection metadata, not a process-global weak
  registry.
- Datahike nested/tiered Konserve identity is recursively normalized and exact
  locator acquisition performs no branch-head read.
- Datomic no longer has its private cursor codec, page cache, request pipeline,
  or duplicated cache lifecycle.
