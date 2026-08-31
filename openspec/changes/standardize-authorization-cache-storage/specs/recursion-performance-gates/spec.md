# recursion-performance-gates Specification

## MODIFIED Requirements

### Requirement: Cache-maintenance op-count invariants

Deterministic fast tests SHALL exercise both runtime adapters and pin the cache
boundary's behavior: explicit membership, one ordinary library access for a
successful lookup, no application callback inside cache mutation,
settled entry count at capacity, hot-key survival under cold churn,
continuation-store puts per walk bounded by pages plus one, and cursor-recovery
decisions bounded per resume. They SHALL also verify that EACL contains no
custom touch queue, tombstone scan, repeat-admission window, or compaction
loop on Clojure and ClojureScript. The tests MUST NOT require Caffeine and CLJS
LRU to select identical cold victims.

#### Scenario: Regression to linear maintenance

- **WHEN** EACL reintroduces O(n)-per-hit EACL maintenance, executes request work inside a cache mutation callback, or violates the common runtime contract
- **THEN** a deterministic trace or operation-count invariant fails without relying on wall-clock benchmarks
