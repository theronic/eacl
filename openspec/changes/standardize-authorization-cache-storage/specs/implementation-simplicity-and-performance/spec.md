# implementation-simplicity-and-performance Specification

## MODIFIED Requirements

### Requirement: Superseded stateful mechanisms are removed

The v8 implementation MUST delete superseded full-denotation-on-demand-default,
cache-owned projection widening, caller-waiting single-flight, cache computation
semaphores, EACL schema read locks, DataScript historical registries, weighted
LRU stores, repeat-admission windows, access sidecars, generation-recency maps,
stamped queues, tombstones, compaction logic, policy-specific generated cache
decisions, the route/boundary/alias page-navigation state machine, the unconsumed relationship
observation cache, and dead revision-checkpoint/provider stores once their
replacements or deletions pass the product gates. Shared answer, subproblem,
continuation, cursor-codec, derived-schema, and bounded stable-page checkpoint
retention MUST use the private standard cache boundary. A single exact-basis
operation-typed lookup/relationship transport-page representation SHALL use
that same lifecycle to avoid repeating cursor decode, identity/row conversion,
proof/dependency work, and token construction when cursor expiry is disabled.
Its key MUST include the full authenticated consistency descriptor. Point,
count, and permission-tree hits MAY avoid backend ID internalization only via
bounded canonical public keys under the deterministic immutable/injective
identity contract. Plain request-local work
state that does not retain multiple reusable key-to-result mappings MAY remain
outside it. This includes consumable prefetched chunks whose advancing index is
part of one traversal checkpoint rather than reusable result retention;
request-local lifetime alone MUST NOT exempt an actual bounded result map from
the standard retention rule.
Compatibility shims MUST be limited to input validation or typed migration
errors and MUST NOT retain an old evaluator or cache-policy state machine.
Exact-answer, exact-denotation, and derived-schema hits MUST NOT re-run
operation, shape, or ABI validators already established by private validated
ingress; managed hits retain only their request-dependent causal check. The
live publication boundary MUST expose one mandatory-validator form rather than
an accept-all default or compatibility overload.
Ordinary answer resolution MUST derive operation, revision, source identity,
adapter identity, locator, and cache basis from the normalized semantic and
exact-basis keys instead of accepting duplicate options that can disagree.
Public cache configuration MUST expose one flat answer capacity, one flat
denotation capacity, and one telemetry switch; the removed nested configuration
map and answer-only mode MUST fail with typed migration errors.
Shared physical operator chunks, direct Boolean probes, direct-match wrappers,
managed-subproblem descriptors/envelopes, and Relay identity-conversion entries
MUST be absent when measurements show their retention overhead exceeds their
common-workload benefit. The only retained authorization values SHALL be exact
denotations, exact/managed completed semantic answers, and the exact transport
page representation needed to avoid repeated cursor, identity, and proof work.

#### Scenario: A hot stable-page checkpoint survives churn

- **GIVEN** a bounded stable-page checkpoint store contains two identities
- **WHEN** one identity is successfully retrieved by matching its authenticated ordinal and boundary before a third identity is published
- **THEN** the retrieved hot identity remains preferred over cold one-use mappings under representative churn
- **AND** a rejected ordinal or boundary match receives no deliberate library access update

#### Scenario: Source inventory rejects an obsolete path

- **WHEN** the product source inventory detects an obsolete coordinator, evaluator entry point, lock acquisition, registry, cache-owned traversal, custom shared eviction implementation, or route/boundary/alias navigation store
- **THEN** verification fails with the exact production symbol and owning claim
- **AND** the prohibited implementation is removed before the product gate passes

#### Scenario: Simpler storage is workload-qualified

- **WHEN** correctness gates pass but completed-hit, shared-subgraph, continuation, reverse-first-miss, cache-free, or allocation workloads materially regress beyond their existing thresholds
- **THEN** the change is not accepted until the measured cause is fixed or the affected requirement is explicitly revised with evidence
