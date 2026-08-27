## Purpose

Provide bounded multi-demand point authorization whose observable result is a sequence of scalar decisions over one immutable snapshot, sharing only work that is invariant across demands and failing as a whole with the failing demand named.

## ADDED Requirements

### Requirement: Batch results refine ordered scalar results

`check-permissions` SHALL accept an ordered vector of point-authorization demands and SHALL return a vector of detailed decisions in the same order and cardinality. Each decision value, evaluation mode, and cache basis MUST equal scalar `check-permission` evaluation of that demand against the one immutable snapshot selected for the batch with the same initial cache state. Work shared inside the batch MAY only remove commands from a demand's cache-disabled trace: a demand that would exhaust its scalar traversal limit MAY complete inside a batch, and a demand that completes in scalar evaluation MUST complete in the batch with the same value. Cache provenance MUST describe the artifact actually used at that position; request-local sharing MUST NOT be reported as a durable cache hit. Duplicate inputs MUST produce duplicate output positions.

#### Scenario: Mixed grants and denials

- **WHEN** a batch contains granted, denied, unknown-subject, and unknown-resource demands
- **THEN** each output equals the corresponding scalar detailed decision on the selected snapshot
- **AND** no decision value depends on another demand's position or result

#### Scenario: Duplicate demands

- **WHEN** the same normalized demand occurs at several input positions
- **THEN** the output contains a decision at every position
- **AND** reusing the first completed computation neither collapses nor reorders outputs nor reports the reuse as a durable cache hit

#### Scenario: Limit refinement

- **WHEN** a demand reaches its scalar traversal limit when evaluated alone but completes inside a batch because an earlier demand certified a shared subproblem
- **THEN** the batch returns the completed decision at that position
- **AND** the converse — a demand that completes alone but fails inside the batch — never occurs

#### Scenario: Empty batch

- **WHEN** the caller submits an empty vector
- **THEN** EACL returns an empty vector without acquiring a snapshot or consulting a cache

### Requirement: One immutable snapshot and request context cover the batch

A non-empty batch SHALL select exactly one immutable snapshot and SHALL use it for validation, plan resolution, proof work, traversal, identity conversion, response metadata, and cache validation and publication. The batch SHALL own one request execution context and SHALL release an owned snapshot exactly once on every exit. A source advance during the batch MUST NOT affect any output position.

#### Scenario: Concurrent source advance

- **WHEN** the source commits a relationship or schema change after batch selection and before the last decision
- **THEN** every decision in the batch describes the originally selected snapshot
- **AND** instrumentation observes one acquisition and one release

#### Scenario: Retained snapshot target

- **WHEN** the batch is evaluated against an already selected snapshot or a composed snapshot view
- **THEN** no source acquisition occurs during the batch

### Requirement: Batch failure is whole and names the demand

Any demand-local typed failure (traversal limit, backend command failure) or request-wide failure (deadline, cancellation, aggregate limit, rendering, publication) SHALL throw the corresponding typed error extended with `:demand-index` and safe aggregate counters. EACL MUST NOT return a partial vector, convert the failing demand into a denial, or publish an aggregate artifact for the failed batch. Independently valid scalar artifacts completed before the failure MAY be published under their ordinary scalar keys only.

#### Scenario: Later demand fails

- **WHEN** a backend command throws or a traversal limit is reached while evaluating the demand at index `k`
- **THEN** the batch throws the scalar typed error with `:demand-index k`
- **AND** the selected snapshot is released exactly once

#### Scenario: Deadline between demands

- **WHEN** the deadline expires after one decision completes and before the next demand begins
- **THEN** EACL starts no work for the next demand
- **AND** throws `:eacl.execution/deadline-exceeded` naming the index of the demand that did not start

### Requirement: Batch validation and demand are bounded

The client SHALL configure a finite positive maximum batch size and finite aggregate semantic-work limits. EACL SHALL reject a non-vector input, malformed demand, unsupported option, per-demand request control, or oversized batch before snapshot acquisition or cache access, using the scalar operation's typed validation errors where they apply and a typed batch-shape error otherwise. Consistency, deadline, cancellation, evaluation mode, cache policy, and aggregate limits SHALL be request-wide. Each demand SHALL retain the scalar traversal limits; aggregate command, transition, fetched-value, and allocation-proxy counters MUST accumulate across demands and MUST NOT reset at a demand boundary.

#### Scenario: Oversized batch

- **WHEN** the input contains more demands than the configured maximum
- **THEN** EACL throws a typed resource-limit error before snapshot acquisition

#### Scenario: Invalid later demand

- **WHEN** one demand after several valid inputs has an invalid permission name shape or an unknown key
- **THEN** EACL rejects the complete batch before authorizing any earlier demand

#### Scenario: Per-demand control

- **WHEN** one demand carries its own consistency, timeout, cancellation, cache, evaluation, or traversal control
- **THEN** EACL rejects the batch before snapshot acquisition

#### Scenario: Aggregate work exhausted

- **WHEN** individually valid demands cumulatively exceed an aggregate command, transition, fetched-value, or allocation-proxy limit
- **THEN** EACL throws the typed aggregate resource-limit error naming the demand index at which the limit was crossed
- **AND** returns and publishes no aggregate result

### Requirement: Sharing preserves demand isolation

Batch execution MAY share immutable validation catalogs, sealed plans, permission and relation definitions, dependency closures, and completed subproblems whose cache contracts permit reuse. It MUST NOT share answer-affecting mutable traversal state, stopping conditions, per-demand resource counters, unvalidated cache candidates, or incomplete negative evidence between semantically distinct demands. Shared plan and definition work SHALL be keyed by normalized permission root and certified schema generation, or memoized for the request when no generation is certified.

#### Scenario: Two roots in one batch

- **WHEN** a batch contains many demands for two distinct permission roots
- **THEN** plan sealing and definition reads occur at most once per distinct root in the request on every backend
- **AND** each root retains its own answer-affecting traversal limits and cache identity

#### Scenario: Same root with different resources

- **WHEN** several demands share a root but target different resources
- **THEN** schema and plan derivations are reused
- **AND** evidence discovered for one resource cannot grant another resource unless the ordinary certified subproblem-cache contract permits that exact reuse

### Requirement: Aggregate cache behavior refines scalar behavior

For an equal selected snapshot, normalized demand vector, execution contract, and initially equivalent cache state, cache-enabled batch execution SHALL be a command-removing refinement of batch execution with `:cache? false`. A per-demand hit MAY remove semantic work for that demand; a miss MUST NOT add a proof command absent from its cache-disabled demand trace. Cache provenance SHALL be reported per output decision.

#### Scenario: Partially warm batch

- **WHEN** only some demands have compatible completed answers
- **THEN** those output positions report their own cache hits
- **AND** cold positions follow the same semantic traces and stopping decisions as cache-disabled scalar evaluation

### Requirement: Batch equivalence is independently verified

The executable oracle SHALL define batch authorization as the ordered application of scalar evaluation over one immutable snapshot. Cross-runtime and cross-backend differential suites MUST compare every batch output, typed error and its `:demand-index`, acquisition count, and cache-disabled command trace with that oracle, including recursive graphs, cycles, duplicate demands, mixed roots, limit refinement, source advance, cancellation, and deterministic failure injection.

#### Scenario: Generated recursive batch

- **WHEN** a seeded generator produces a recursive schema, relationship graph, and bounded vector of demands
- **THEN** the production batch result equals the independent ordered scalar oracle under the refinement rule
- **AND** a reproducible seed and minimized fixture are reported on disagreement
