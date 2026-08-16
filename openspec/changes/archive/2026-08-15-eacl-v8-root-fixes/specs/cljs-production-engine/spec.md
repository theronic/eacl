# cljs-production-engine

## ADDED Requirements

### Requirement: Advanced compilation works and is enforced
A ClojureScript `:advanced` (or shadow-cljs `:release`) build of the DataScript backend and core engine SHALL compile and pass the CLJS test suite in CI. Property accesses into any remaining foreign/generated JavaScript SHALL be covered by externs or rename-safe access patterns.

#### Scenario: Advanced build in CI
- **WHEN** the CI pipeline runs
- **THEN** an `:advanced`-optimized CLJS build job compiles without warnings-as-errors and executes the backend/engine test suite successfully — a job that does not exist today and would currently fail with silent `undefined` reads

### Requirement: No BigNumber foreign-lib on the browser hot path
The browser authorization hot path (point checks, page enumeration, counts) SHALL NOT execute through the BigNumber-based generated foreign library. The chosen mechanism (per design.md: a differentially certified CLJC engine for CLJS, or a native-number regenerated ESM kernel) SHALL remove `bignumber.js` from hot-path execution and eliminate the current ~591 KB unshakeable payload.

#### Scenario: Bundle audit
- **WHEN** the production browser bundle is analyzed
- **THEN** the authorization hot path contains no BigNumber arithmetic and the shipped engine payload is within the recorded size budget (an order of magnitude below the current 591 KB raw / 96 KB gzip foreign-lib)

#### Scenario: Equivalent compiler output varies by platform
- **WHEN** supported JVM/OS pairs emit semantically equivalent advanced bundles whose Closure symbol allocation or gzip representation differs
- **THEN** reference observations record their measurement environment but are not treated as byte-identical release invariants
- **AND** CI enforces reviewed absolute runtime/kernel ceilings, independent incremental-engine ceilings, and forbidden-runtime markers on every build
- **AND** an output that is smaller than every applicable ceiling does not fail solely because it differs from one reference observation

### Requirement: Absolute CLJS performance ceiling
CLJS traversal performance SHALL be gated by an absolute recorded ceiling (ns per result) in addition to linearity ratios. The ceiling SHALL be recorded in the verification EDN and enforced in CI.

#### Scenario: Ceiling gate
- **WHEN** the CLJS traversal benchmark runs in CI
- **THEN** p50 ns/result at the reference size is at or below the recorded ceiling; a result that is linear but slow (today's ~31 µs/result would pass the existing ratio-only gate) fails

### Requirement: Cross-target semantic parity
The CLJS production engine SHALL be certified against the same authority as the JVM engine: cross-runtime vectors, counterexample-corpus replay, mutation controls, and the randomized differential oracle SHALL all run against the CLJS engine in CI, and any divergence SHALL fail the build.

#### Scenario: Divergence detection
- **WHEN** a change causes the CLJS engine to produce a different answer, page, or count than the JVM engine or the Dafny oracle for any corpus or generated case
- **THEN** CI fails with the diverging case identified
