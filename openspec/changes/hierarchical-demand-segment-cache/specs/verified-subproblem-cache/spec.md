# verified-subproblem-cache Specification

## MODIFIED Requirements

### Requirement: Cache values denote complete immutable subproblems
The cache SHALL store only values of three kinds, each equal to a complete
artifact on one selected immutable graph or validity scope: completed answers
(fully prepared public results), latest progress checkpoints (complete
quiescent reducer states for one exact execution identity), and exact
scan-response prefixes (ascending prefixes of one adapter scan reply under a
complete validity scope; see `exact-scan-response-cache`). It MUST NOT publish
call-stack-dependent recursion guards, partial worklists, partially rendered
pages, reducer emissions, plan-node segments, composed multi-hop results, or
traversal-order-dependent state as reusable subproblem answers. Complete
anchored denotations are no longer a cache artifact.

#### Scenario: Recursive traversal stops at a page boundary
- **WHEN** a recursive page returns before its anchored reachable worklist is exhausted
- **THEN** the engine may retain its latest checkpoint and the exact scan replies it fetched, but does not publish any denotation or emitted-sequence artifact

#### Scenario: Scan reply retained without its context
- **WHEN** a request fetches a chunk of one relation scan
- **THEN** the retained prefix carries no admission state, no start-set identity, and no demand, and is served only as the adapter's exact reply

### Requirement: Recursive caching refines least-fixed-point semantics
Request-local and shared recursive evaluation SHALL compute the same least
fixed point as the cache-free positive ReBAC semantics. False results derived
under a non-empty DFS visited set MUST NOT be treated as context-free cached
answers.

#### Scenario: Cycle becomes reachable through another rule
- **WHEN** a recursive state is first encountered through a cyclic path and later receives a grant through a different rule
- **THEN** the fixed-point evaluator includes the grant and no earlier cycle guard can suppress it

#### Scenario: Cached scan reply inside a recursive walk
- **WHEN** a recursive traversal's scan is answered from an exact scan-response prefix
- **THEN** the traversal admits, orders, and terminates exactly as it would reading the same values from the adapter

## REMOVED Requirements

### Requirement: Shared-subgraph cache exceeds the completed-answer baseline
**Reason**: The gate measured the retired denotation tier (`:denotation-hits`,
`:acyclic-denotation-hits`), whose producing engine and benchmark were
removed; the counters are permanently zero. The replacement gate is the
adoption requirement of `exact-scan-response-cache` (elided commands, miss-page
latency, cold overhead, oracle equality).

**Migration**: `formal/verification/performance-gates.edn` entries asserting
denotation hits are retired; the new gate records elided adapter commands and
latency deltas per backend.
