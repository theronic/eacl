# Task 1.9 mechanism dispositions

Source base: `e137dc55512d4eeebcc31cfbe5087d61ab04465b`.

The machine-readable authority is `mechanism-ledger.edn`: 29 reproduced, two
refuted, three correctness-required, and zero pending. “Reproduced” means the
live mechanism was observed through a deterministic counter, structural proxy,
operation trace, or complete finite-domain comparison; it does not by itself
claim a latency improvement.

## Reproduced and retained

| Scope | Decisive observation | Confidence |
| --- | --- | --- |
| Acyclic exact-alias frontier | One correct result crossed nine scans; identical scan groups repeated 3x, 2x, and 2x. | High |
| Least-path adapter limit | The eager adapter realized the entire 64/1,024/16,384 fixture while Core returned one value because `:limit` was dropped before invocation. | High |
| Stable output/completion | Retention and completion uniqueness reconstruction grew with full output width rather than public count/page demand. | High |
| Physical-buffer and routed-vector copies | Per-value suffix views have quadratic aggregate element exposure and already-realized vectors are recopied. | High |
| Retained maxima and sidecar recency | Fixed-capacity churn caused linear whole-capacity visits per event. | High |
| Continuation recency | Fixed capacity 16 caused 31,216, 319,216, and 3,199,216 vector-filter visits for 1k, 10k, and 100k events. | High |
| Zero/one successor scheduling | Both paths allocated two transient collections per transition. | High |
| Public boundary validation | One rejection-heavy public scan repeated relationship-shape validation three times and authorization-shape validation twice. | High |
| Fixed counter lookup | Every bound `:commands` increment repeated key validation and fixed-index lookup; the counter is a mandatory limit input, not telemetry. | High |
| Snapshot identity | A cold request invoked `:snapshot-id` four times and each later exact-cache hit still crossed it twice. | High |
| Eager request state | Every constructed request created one proof frame, four memo atoms, and one publication buffer before backend use. | High |
| Native membership normalization | Candidate validation was exactly `2N`; normalized grouping and 256-wide chunking otherwise remained correct. | High |
| Completed-hit recency and telemetry | Each hot hit mutated shared recency once and three observer atoms; observation has no disable path. | High |
| Plan/memo hit construction | Resident hits still constructed candidate delays and allocated more than direct-force controls. | Moderate (runtime allocation lane plus source structure) |
| Independent completed misses | All 2/8/32 callers computed under their own request and raced one publication; no caller joined another. This behavior is retained. | High |
| Shared derived delays | Parsed schema/catalog waiters inherit owner failure; structural decode and sealed-plan failures additionally poison their slots. | High |
| Finite current-cache decision | All ten stage/availability partitions equal the handwritten host mapping, yet the generated decision is invoked repeatedly. | High |
| Rank-cost duplicate authority | Production contains separate rank mappings that can diverge; the formal comparison must remain independently derived. | High |
| Datomic exact acquisition | Both local-covered and local-behind lanes performed `d/sync`; the existing `await-basis-db` helper already expresses the correct conditional rule. | High |

## Refuted and removed

| Scope | Reason | Confidence |
| --- | --- | --- |
| Terminal/continuation scan evidence | No material benefit was reproduced. A new response capability would add adapter/cache ABI, malformed-progress cases, and compatibility work. Existing conservative width lookahead remains correct. | Moderate |
| Captured adapter invoker | Repeated operation lookup exists, but the only cheaper comparison bypassed mandatory counters, observers, runtime guards, and typed failures. It is not evidence for a safe net win. | High that current evidence is insufficient |

Both optional scopes were removed from the positive proposal, delta
requirements, design decisions, and implementation tasks. The design records
the negative decision only so it is not silently reintroduced.

## Correctness-required and red before production edits

| Scope | Violated durable requirement | Failing test |
| --- | --- | --- |
| Restored operation validation | `nonblocking-cache-coordination`: publication validates artifact type and a projection hit returns a previously validated response; `verified-subproblem-cache`: cached values are complete immutable denotations. `restore-store` instead stamps arbitrary structurally valid decoded values `:validated? true`. | `eacl.subproblem-cache-test/restored-entry-requires-operation-specific-validation-test` (three failing assertions: wrong value returned, validator never called, invalid metric absent). |
| Completed-cache compatibility identity | `verified-subproblem-cache`: semantic keys separate every answer-affecting input; `formally-verified-authorization-engine`: a hit equals fresh evaluation for the same semantic request. Current completed semantic keys omit compiler/plan compatibility and cache-value ABI. | `eacl.performance.correctness-gate-test/completed-cache-semantic-key-binds-compiler-and-value-abi-test` (missing both required fields). |
| Cache-flight contract reconciliation | `nonblocking-cache-coordination`: no request waits for another request's computation. `single-flight-coordination`, `answer-cache-bounding`, and `verified-subproblem-cache` still positively require joining, waiting, and flight metrics. | `eacl.performance.correctness-gate-test/durable-cache-contracts-forbid-request-joining-test` (five positive flight/join requirements detected). |

The red tests are intentional task-1.9 anchors. They must turn green in the
owning implementation/spec tasks; weakening or deleting them is not an
acceptable resolution.
