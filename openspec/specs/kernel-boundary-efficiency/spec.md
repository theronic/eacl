# kernel-boundary-efficiency Specification

## Purpose
TBD - created by archiving change eacl-v8-root-fixes. Update Purpose after archive.
## Requirements
### Requirement: Amortized per-crossing marshalling
The host↔generated-kernel boundary SHALL NOT re-marshal traversal-constant values on every crossing. Traversal limits and fuel SHALL be marshalled once per traversal; scan-command type identifiers SHALL be resolved without per-command string decoding; empty scan responses SHALL reuse an interned representation.

#### Scenario: Limits marshalled once
- **WHEN** a generated traversal performs S scans (2S+O(1) crossings under the sequential protocol)
- **THEN** the traversal-limits structure is constructed exactly once for the traversal, not once per drive and once per resume

### Requirement: Single validation authority per response
Scan-response validation SHALL run exactly once per response on the certified path. Host-side per-value walks that duplicate the certified kernel validator SHALL be removed, and the JVM and CLJS boundaries SHALL apply the identical validation contract.

#### Scenario: Empty-response fast path
- **WHEN** a scan response contains zero values
- **THEN** the host performs O(1) work before handing the response to the certified validator, on both JVM and CLJS

### Requirement: Crossing law enforced by gates
The relationship between kernel crossings and backend scans SHALL be pinned by logical-work gates: resumes equal scans, and drives are bounded by scans plus one plus an explicit fuel-yield allowance. Any protocol change (including batching) SHALL update the recorded law rather than silently altering observed counts.

#### Scenario: Star-fixture crossing audit
- **WHEN** the populated-star count gate runs with kernel-crossing counters bound
- **THEN** the crossing counts satisfy the recorded law for the active protocol version, and a violation fails the gate

### Requirement: Batched scan protocol under measured need
If, after the host-side amortizations and the other capabilities in this change land, the populated-recursion latency gate still exceeds its bound, the generated protocol SHALL be extended so one drive can return a bounded batch of independent scan commands and one resume can fold the ordered response batch — reducing crossings from 2 per stream to 2 per batch — with the coverage and refinement proofs re-established and the engine emission-order version stamped into cursor digests.

#### Scenario: Batching trigger condition
- **WHEN** the populated-recursion matched-v7 latency gate fails its recorded bound with all host-side efficiency work landed
- **THEN** the batched-protocol work item activates; otherwise it remains explicitly deferred with the decision recorded

#### Scenario: Batched crossings (once active)
- **WHEN** the batched protocol executes a traversal with S streams and batch capacity B
- **THEN** kernel crossings are bounded by 2×⌈S/B⌉ plus a recorded constant, and all differential and counterexample-replay suites pass against the regenerated kernel

