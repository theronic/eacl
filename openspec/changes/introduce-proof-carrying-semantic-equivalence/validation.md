# Validation

Validated on 2026-08-23 from branch
`agent/introduce-proof-carrying-semantic-equivalence`.

## Runtime correctness and conformance

- The CI-equivalent JVM sweep passed 806 tests and 30,540 assertions
  with zero failures and zero errors.
- The Datalevin-isolated suite passed 248 tests and 5,304 assertions with zero
  failures and zero errors.
- Focused clean-loader suites passed for the shared cache, proof-frame,
  request-context, relay, backend contract, and formal controls (87 tests,
  3,313 assertions); DataScript (71 tests, 3,365 assertions); Datahike (38
  tests, 2,314 assertions); Datomic including the proof-cost benchmark (55
  tests, 4,102 assertions); and Datalevin (30 tests, 904 assertions).
- The randomized DataScript cached-versus-bypass campaign includes retained
  older bases and both delayed publication orders. Its focused run passed
  2,580 assertions.
- Shared public cache-control conformance covers all four adapters, including
  empty and populated batches, permission trees, relationship reads, cursor
  continuation, exact hits, proof-backed hits, misses, and bypasses.
- Adapter certification v4 passed all bundled adapters. Its new executable
  checks exercise native revision domains, generation ceilings, supported
  relationship transitions, non-durable live-source separation, and durable
  source identity across reopen. Datalevin explicitly records its temporal
  ordered-generation claim as not yet supported; the next stacked change owns
  that storage-level proof.

All Clojure test execution ran through nREPL with changed namespaces reloaded.

## ClojureScript and optimizer evidence

- The final ordinary DataScript Node suite passed 279 tests and 8,364
  assertions with zero failures and zero errors.
- A fresh advanced-optimized build with warnings as errors passed 279 tests and
  8,364 assertions with zero failures and zero errors.
- The advanced run exposed four tests that replaced ordinary ClojureScript
  Vars and therefore ceased to observe calls after Closure devirtualization.
  Those tests now use stable execution-stage injection, an explicit client API
  wrapper, codec work counters, and byte-level ciphertext tampering. Their
  affected JVM run passed 63 tests and 535 assertions; the subsequently revised
  batch fault-boundary test passed its 25-test, 188-assertion JVM namespace.

## Proof, mutation, and source-closure evidence

- `bin/formal verify`: 30 Dafny modules, 8,794 proof efforts, zero verification
  errors. `ScalarFrontierCoherence.dfy` retains the proved
  `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` corollary, and the
  assurance matrix now cites it for direction-agnostic retained-basis reuse.
- `bin/formal format`: clean.
- `EACL_NREPL_PORT=52308 bin/formal mutation-control`: 4 tests and 86
  assertions, zero failures and zero errors. All 36 active controls were
  killed: 27 executed-production controls, five structural source controls,
  and four Apalache controls. This includes the new adapter domain, ceiling,
  and live-source identity mutations.
- Public source closure: 78 source files, 70 roots, and 1,653 reachable
  definitions; SHA-256
  `91e41b6d394d181e090f063d84d11a538828d5eb1a24b5e5c290dfafe212a012`.
- `bin/formal manifest` reconciled 303 source files, 55 reports, 67
  counterexamples, all 36 mutation controls, the v4 adapter certification, and
  all four generated artifact digests. It intentionally withholds unqualified
  verified status only for the repository's five already-declared external
  release obligations; it reported no theorem, count, digest, certification,
  or artifact mismatch.

## Builds and static gates

- `bin/reflection-gate`: clean.
- Production clj-kondo with the repository's macro lint mapping: zero errors
  and the existing 37 warnings.
- Generated Java, JavaScript, and browser runtimes built successfully.
- The artifact-size gate passed with its exact pinned Babashka 1.12.213:
  browser 586,813/738,488 bytes; Java classes 1,875,003/2,377,367 bytes; Java
  source 2,115,033/2,670,869 bytes; JavaScript 942,084/1,188,865 bytes.
- The coordinated `8.0.0-SNAPSHOT` build/install/cold-smoke completed through
  nREPL.
- `git diff --check`, public-source-closure checking, and
  `openspec validate introduce-proof-carrying-semantic-equivalence --strict`
  passed.

## Correctness and performance properties pinned by tests

- A proof frame contains only canonical relation generations. Schema
  generation is read independently, and every generation is an exact natural
  number at or below the selected native revision.
- Missing evidence is an ordinary exact-only outcome. Malformed, duplicate,
  non-canonical, non-integer, or future evidence is a typed contract violation
  that disables only managed lifting, reports once per reason per lifecycle,
  and leaves exact caching, authorization, and revision-token issuance intact.
- Managed keys contain the full `{source-scope, source-lifecycle}` lineage and
  schema generation. Equal frames reuse in either revision direction; changed
  frames miss.
- Datomic uses `d/tx->t`, retains relation-version history, and reads historical
  stamps at an as-of basis. The benchmark recorded 54 additional retained
  relation-version history datoms for its named write workload.
- Datahike memory and DataScript sources mint per-live-source identity; durable
  Datalevin reopen preserves its persisted identity.
- `:populate-cache? false` preserves lookups and request-local memoization while
  suppressing completed answers, managed subproblems, checkpoints, and visited
  pages. `:cache? false` still suppresses both lookup and publication.
