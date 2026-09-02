## MODIFIED Requirements

### Requirement: Core qualification uses multiple shapes and scales

The evidence record SHALL map each retained mechanism to its current-source reproducer, at least one adversarial fixture, measured operations, and scale points. Across the changed paths, applicable fixtures SHALL cover flat grants, exact pure-alias chains, dense overlap, sparse sharing, deep arrows, recursive/cyclic plans, rejection-heavy membership, continuation replay, cache hit/miss/bypass, and physical nonprogress. Cache-identity qualification SHALL additionally cover repeated requests with fixed and varying invocation controls, exact page and count hits, adjacent forward/reverse page reuse, semantic non-aliases, cold publication, replacement, and capacity-bound eviction. Every growth claim SHALL use at least three predeclared independently measured cardinalities or cache capacities and name the fitted or ratio model and acceptance tolerance; it is evidence over those sizes, not a proof of asymptotic complexity. Portable cache bookkeeping SHALL have deterministic structural-work assertions in both CLJ and CLJS plus runtime-appropriate wall-time lanes. The pinned Datomic fixture SHALL run 30,000-, 100,000-, and verified 1,000,000-result lanes where the public operation semantically supports those cardinalities. A combination is unsupported only when the public contract makes it inapplicable; timeout, out-of-memory, resource exhaustion, or slowness on a baseline-supported lane is a failure, not an omission. Final qualification of an affected cache change MUST run the complete declared unit, cross-runtime, formal/conformance, cache-structure, and multi-size performance battery; one cardinality or one public operation cannot substitute for another affected lane.

#### Scenario: One-point allocation result looks linear
- **WHEN** a candidate measures only one result cardinality or one page-cache capacity
- **THEN** it cannot claim a growth rate, removal of result-width amplification, or capacity-independent cache bookkeeping

#### Scenario: Million-result label is unverified
- **WHEN** a database or fixture is described as containing one million relevant results without an independently recorded basis and cardinality check
- **THEN** the lane is invalid until the fixture identity and count are verified

#### Scenario: Shape improves while another changed shape regresses
- **WHEN** an optimization improves a flat fixture but regresses an alias-rich, recursive, rejection-heavy, reverse-navigation, or cache-eviction fixture governed by the same changed path beyond its declared tolerance
- **THEN** the workstream does not qualify

#### Scenario: Single benchmark is substituted for the affected battery
- **WHEN** the candidate runs one count-resources cardinality but omits an affected page identity, reverse-alias, eviction-capacity, runtime, correctness, or formal lane
- **THEN** the cache optimization has not qualified

#### Scenario: Portable structural work diverges by runtime
- **WHEN** CLJ satisfies the bounded cache-bookkeeping assertion but CLJS retains capacity-proportional ordinary publication or eviction work
- **THEN** the portable optimization has not qualified
