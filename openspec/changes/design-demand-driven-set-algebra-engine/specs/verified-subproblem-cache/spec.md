## ADDED Requirements

### Requirement: Operator cache artifacts have explicit completeness classes
Operator execution SHALL distinguish complete point Booleans, aligned complete candidate vectors, exact adapter scan responses, complete lower-stratum denotations, completed top-level answers, and private continuation state. Witness masks, in-progress expression states, recursive join slots, provisional anti-join misses, adaptive batch history, and unfinished strata MUST NOT be accepted as context-free shared answers.

#### Scenario: Repeated candidate in one request
- **WHEN** a compound expression demands the same leaf membership more than once for one candidate
- **THEN** a request-local completed Boolean may satisfy the repeated demand without another backend probe

#### Scenario: Partial recursive conjunction
- **WHEN** only some recursive intersection premises have arrived
- **THEN** the join state remains private and cannot satisfy a later independent request

### Requirement: Witness-aware vector memoization is exact
Within one request, a candidate's completed expression-node Boolean SHALL be keyed by expression identity, typed candidate identity, direction, anchor context, selected snapshot, and every other answer-affecting input. Reuse MUST produce the same decision as cache-free scalar evaluation and MUST NOT reuse a witness outside the derivation for which it is valid.

#### Scenario: Same EID in different types
- **WHEN** equal numeric identifiers occur in different entity types
- **THEN** their vector masks and memoized decisions remain distinct

### Requirement: Exact scan-response reuse serves identical physical demand
An eligible scan-response cache MAY answer an operator leaf demand only when it reproduces the exact bounded adapter response for that descriptor, bound, direction, limit, and valid relation proof. A miss MUST forward the same bounded command rather than fetching a larger operator-specific prefix.

#### Scenario: Cached dense prefix is too short
- **WHEN** a dense batch needs values beyond an eligible cached prefix
- **THEN** the cache forwards the evaluator's current bounded command and does not prefetch the complete relation

### Requirement: Cache bypass remains an operator oracle
With `:cache? false`, every public operator operation SHALL bypass answer, Boolean, vector, scan-response, proof-lifting, single-flight, and publication work while retaining the same sealed plan, semantic demand sequence, result, error, and logical work-limit boundary.

#### Scenario: Hot operator caches bypassed
- **WHEN** a client with hot operator-related entries evaluates the same request with `:cache? false`
- **THEN** the answer is independently recomputed and no cache metric records lookup or publication
