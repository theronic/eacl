# recursion-performance-gates Specification

## MODIFIED Requirements

### Requirement: Cache-maintenance op-count invariants

Deterministic fast tests SHALL replay portable LRU traces and pin the cache
boundary's behavior: explicit membership, one library hit transition for a
successful lookup, no application callback inside atomic state updates,
resident entries never above capacity, hot-key survival under cold churn,
continuation-store puts per walk bounded by pages plus one, and cursor-recovery
decisions bounded per resume. They SHALL also verify that EACL contains no
custom touch queue, tombstone scan, repeat-admission window, or compaction
loop on Clojure and ClojureScript.

#### Scenario: Regression to linear maintenance

- **WHEN** EACL reintroduces O(n)-per-touch maintenance, executes request work inside an atom retry, or produces runtime-divergent LRU outcomes
- **THEN** a deterministic trace or operation-count invariant fails without relying on wall-clock benchmarks
