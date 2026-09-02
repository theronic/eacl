## MODIFIED Requirements

### Requirement: Cache values denote complete immutable subproblems
The cache SHALL publish only values that equal a complete authorization
subproblem denotation on one selected immutable graph, or exact scan-response
prefixes that equal a prefix of one adapter scan sequence for one read
descriptor under a validity scope that pins the scanned relation's generation.
A request's memoization of its own scan replies is execution state on one
immutable basis, not a published value. The cache MUST NOT publish
call-stack-dependent recursion guards, partial worklists, partially rendered
pages, traversal-order-dependent state, or fragments of a scan that do not
start at the scan's first value as reusable values.

#### Scenario: Recursive traversal stops at a page boundary
- **WHEN** a recursive page returns before its anchored reachable worklist is exhausted
- **THEN** the engine may store query-scoped continuation state but does not publish a shared recursive denotation

#### Scenario: Anchored recursive denotation completes
- **WHEN** monotone evaluation exhausts the reachable worklist for a concrete root, direction, anchor, result type, and limit configuration
- **THEN** the complete deterministic denotation may be admitted under its semantic key and proof

#### Scenario: Scan prefix is published
- **WHEN** a request fetches a scan from its first value on an ordinary snapshot with a complete proof for the scanned relation
- **THEN** the returned values may be published as that descriptor's prefix under the relation's generation

#### Scenario: Scan fragment is not published
- **WHEN** a request fetches a chunk after a bound that no retained prefix contains
- **THEN** nothing is published for that descriptor
