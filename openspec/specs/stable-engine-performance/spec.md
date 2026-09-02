## Purpose

Define the correctness-preserving Core contracts that remove avoidable plan, output-retention, and reducer-bookkeeping amplification from stable authorization execution.

## Requirements

### Requirement: Exact permission aliases do not duplicate acyclic traversal

Before sealing an acyclic least-path traversal frontier, EACL SHALL resolve an arrow target permission only when its complete normalized body is exactly one same-resource self-permission. Resolution MUST stop at a missing definition, composite body, relation-dependent body, or cycle. EACL SHALL normalize that target before forming the existing frontier identity and remove only frontier entries equal in every semantic and work field, including source identity, direction, relation/path, target type and permission, physical order and capability, admission-key granularity, and static limit/cursor ABI or coordinate interpretation. Request demand, concrete limit values, bounds, and cursor position MUST NOT enter this generation-owned frontier identity. Equal entries SHALL retain the earliest deterministic pre-normalization canonical path position, and that retained position SHALL participate in the plan fingerprint. EACL SHALL rebuild the affected acyclic frontier indexes, order certificate, and fingerprint while preserving the pre-optimization public result sequence. This requirement MUST NOT collapse the requested root, remove nodes, or rewrite a recursive/cyclic stable plan.

#### Scenario: Two arrows differ only by a pure alias
- **WHEN** two acyclic arrow paths have the same semantic/work identity and their target permissions resolve through exact pure aliases to the same target
- **THEN** the sealed least-path frontier contains one equivalent traversal at the first path's pre-normalization canonical position
- **AND** lookup order, count, point decisions, stopping behavior, and authorization denotation are unchanged

#### Scenario: Alias chain is composite or cyclic
- **WHEN** an alias candidate has multiple body components, depends on a relation, is missing, or revisits a permission on its resolution path
- **THEN** EACL leaves that frontier candidate uncollapsed and terminates compilation without erasing any semantic branch

#### Scenario: Recursive stable plan contains an alias
- **WHEN** the requested permission compiles to a recursive or cyclic stable plan
- **THEN** this acyclic frontier rule performs no root, node, reachability, or recursive-scheduling rewrite

#### Scenario: Provider returns schema rows in another encounter order
- **WHEN** two plan builds receive the same normalized schema rows in different provider encounter orders
- **THEN** they produce equal canonical acyclic frontier entries, public sequence, and plan fingerprint

### Requirement: Corrected plan-bound identity fails closed

Every cursor, checkpoint, and plan entry whose interpretation depends on a sealed plan MUST bind the plan fingerprint and the narrow format identities required by its representation. A completed value MUST instead bind the complete exact-generation, semantic, compiler/plan, and cache-value compatibility identities; its compiler/plan identity SHALL include the fingerprint algorithm and every order, rank, frontier/rule, and cursor-interpretation contract that can change its producing plan. Any cursor or checkpoint embedded in that completed value still binds the plan fingerprint. Opaque reducer/checkpoint state SHALL additionally bind a private continuation-state ABI; a sink/checkpoint layout change bumps that private ABI, makes old entries miss/replay, and does not by itself invalidate a public cursor. This change SHALL NOT alter the global public-order contract or globally bump the plan format. A plan receives a changed fingerprint exactly when one of its fingerprinted frontier/rule, rank, or cursor-interpreting capability inputs changes; a plan whose fingerprint inputs are unchanged retains its existing fingerprint and cursor compatibility. Work-only adapter capability changes use adapter/cache ABI instead. An incompatible private entry or completed value MUST miss and recompute; an incompatible public cursor MUST fail through the existing typed cursor-incompatibility contract. No incompatible cached edge may be returned or freshly signed.

The compatibility matrix is representation-specific:

| Changed input | Plan fingerprint | Public cursor | Private continuation/checkpoint | Completed cache | Native membership ABI |
| --- | --- | --- | --- | --- | --- |
| Frontier/rule, rank, or cursor interpretation | changes for affected plan | prior affected cursor fails typed | prior affected state misses/replays | compiler identity makes prior value miss | unchanged |
| Work-only adapter capability | unchanged | unchanged | unchanged unless its own layout changes | adapter/cache ABI makes incompatible value miss | unchanged |
| Sink/checkpoint layout only | unchanged | unchanged | private continuation-state ABI changes | embedded private state misses/replays | unchanged |
| Completed-value representation only | unchanged | unchanged | unchanged | cache-value ABI changes; every old value misses | unchanged |
| No compatibility input changes | unchanged | remains valid | remains valid | remains eligible | unchanged |

A scalar completed answer SHALL bind the compiler/plan compatibility identity
and cache-value ABI but need not carry a ceremonial plan fingerprint when it
contains no plan-bound cursor or checkpoint. Any embedded cursor or checkpoint
still binds its producing plan fingerprint.

#### Scenario: Cursor crosses the corrected-plan rollout
- **WHEN** a cursor minted from an alias-affected old plan is presented after the corrected plan is active
- **THEN** its old fingerprint is rejected with the existing typed invalid-cursor outcome for a fingerprint mismatch
- **AND** EACL does not reinterpret its ordinal or boundary under the corrected plan

#### Scenario: Private plan state crosses the rollout
- **WHEN** an old checkpoint, plan entry, or completed value is restored under an incompatible fingerprint, format, semantic, compiler/plan, or cache-value identity applicable to that representation
- **THEN** it is treated as a miss and recomputed without a durable-data migration

#### Scenario: Sink layout changes without changing plan semantics
- **WHEN** the opaque reducer/checkpoint layout changes but the plan's fingerprint inputs and public cursor coordinates do not
- **THEN** the private continuation-state ABI changes and old checkpoint state misses or replays
- **AND** the public cursor remains compatible

#### Scenario: Unaffected plan is rebuilt
- **WHEN** none of a plan's fingerprinted frontier/rule, rank, or cursor-interpreting capability inputs changes
- **THEN** its fingerprint, compatible cursors, and public result sequence remain valid and identical to the pre-change plan

#### Scenario: Scalar completed answer crosses a compatibility rollout
- **WHEN** a scalar answer predates the current compiler/plan compatibility or
  cache-value ABI
- **THEN** it misses and recomputes without requiring a plan-fingerprint field
  that has no representation-level meaning for that scalar

#### Scenario: Work-only adapter capability changes
- **WHEN** an adapter capability changes physical work without changing public
  order, cursor coordinates, or continuation interpretation
- **THEN** adapter/cache compatibility makes incompatible cache state miss
- **AND** the plan fingerprint and compatible public cursors remain unchanged

### Requirement: Output retention is bounded by operation demand

First-discovery stable execution SHALL separate semantic traversal state from delivered-output retention. Exact count SHALL retain a scalar count rather than every emitted result. A bounded page SHALL retain only the requested window, required lookahead, and authenticated boundary material. Stable last, backward, and replay operations SHALL use bounded output windows. A checkpoint SHALL contain the semantic reducer state plus at most undelivered lookahead or boundary values required to resume; it MUST NOT contain delivered output history or superseded physical buffers. Resuming a checkpoint and concatenating its suffix to already delivered output SHALL equal uninterrupted execution without loss or duplication. Exact admitted/visited identities and pending work MAY remain prefix-dependent where required for correctness, but each growing component MUST remain covered by a documented finite item, work, or capacity limit and MUST be reported honestly rather than counted as output. A new production retained-weight calculation or failure mode SHALL be added only for a specifically reproduced component and MUST be incremental and staged before commit. The separate acyclic least-path evaluator SHALL retain its already bounded route and MUST NOT be rerouted through exhaustive stable enumeration merely to share this sink implementation.

#### Scenario: Exact count discovers a large result set
- **WHEN** an exact count exhausts a permission with many distinct results
- **THEN** output retention remains constant with respect to the result count
- **AND** the returned count and truncation semantics equal the reference traversal

#### Scenario: Forward page requests N results
- **WHEN** a forward page requests `N` results
- **THEN** output retention is bounded by `N`, one required lookahead, and constant-size boundary metadata

#### Scenario: Last or backward page follows a deep prefix
- **WHEN** a last, backward, or replay operation traverses a prefix much larger than its requested page
- **THEN** its output window remains bounded by page demand
- **AND** prefix-dependent admitted or pending state remains under its documented finite work/item limit and is reported separately from output retention

#### Scenario: Point authorization uses its specialized route
- **WHEN** a public point check is eligible for the certified membership-probe route
- **THEN** output-sink selection does not reroute it through exhaustive stable enumeration

#### Scenario: Stable page resumes across its lookahead boundary
- **WHEN** a checkpoint is captured after a page has delivered its window but retains an undelivered boundary value
- **THEN** resume emits exactly that value and the remaining uninterrupted suffix once
- **AND** the checkpoint contains no already delivered output history

#### Scenario: Acyclic page uses least-path evaluation
- **WHEN** a plan is eligible for the bounded acyclic least-path evaluator
- **THEN** sink specialization preserves that evaluator's route and public page semantics

### Requirement: Duplicate freedom is constructional on the production path

Exact admission SHALL remain the production authority that prevents duplicate work and duplicate results. Successful completion MUST NOT perform another result-width-proportional uniqueness pass. Qualification MAY compare against an independent duplicate oracle, but that oracle SHALL NOT execute on ordinary production requests.

#### Scenario: Several derivations reach one result
- **WHEN** several rule paths derive the same semantic root result
- **THEN** the result is emitted once by exact admission
- **AND** completion does not rebuild a full-result distinct collection

#### Scenario: Admission uniqueness is mutated
- **WHEN** a qualification mutant weakens the admission identity so a duplicate can escape
- **THEN** differential or mutation verification fails before the candidate qualifies

### Requirement: Reducer bookkeeping has bounded per-transition cost

Releasing one value from a realized physical chunk SHALL advance an index or equivalent constant-state cursor without creating a suffix collection per value. Retained-value and retained-buffer maxima SHALL be maintained incrementally. Recency bookkeeping SHALL use bounded indexed or generation-stamped metadata whose size is a documented function of live capacity, not touch count. Continuation and tombstone key lookup SHALL remain direct; touch, removal, and publication-order bookkeeping SHALL avoid filtering or rebuilding the whole bounded order vector per event when that mechanism is retained, using a direct index or equivalent amortized-constant-time update. Live and tombstone metadata SHALL keep independent enforced capacity bounds. Zero- and one-successor fast paths MAY be used only when they preserve staged all-or-error limit decisions, canonical scheduling, admission identity, and counters.

#### Scenario: Long physical chunk releases one value at a time
- **WHEN** a long realized chunk is consumed through single-value semantic transitions
- **THEN** each release advances bounded cursor state without allocating a new suffix view or rescanning all retained buffers

#### Scenario: One sidecar is touched repeatedly
- **WHEN** a retained sidecar is touched many times while live capacity remains fixed
- **THEN** recency metadata remains bounded by its documented capacity-derived ceiling

#### Scenario: Continuations churn at fixed live capacity
- **WHEN** continuations are repeatedly published, resumed, removed, and tombstoned while live capacity remains fixed
- **THEN** key lookup remains direct and touch/removal avoids a whole-capacity vector filter or rebuild per event
- **AND** live and tombstone metadata remain within their independent configured bounds

#### Scenario: Transition has zero or one certified successor
- **WHEN** a transition has zero or one successor and the plan certificate makes duplicate-in-batch staging impossible
- **THEN** the specialized path produces the same stack, admission, limit, and counter outcome as the general staged path

#### Scenario: Successor uniqueness is not certified
- **WHEN** a transition lacks the required zero/one-successor certificate
- **THEN** EACL uses the general staged admission path

### Requirement: Physical response vectors are reused and make verifiable progress

Every validated positive physical descriptor limit SHALL be forwarded in the adapter scan options before invocation; downstream routed truncation MUST NOT be the only mechanism limiting an eager backend. Every successful physical command response SHALL then be bounded and fully realized at the routed physical boundary before any semantic state, cache state, or public output commits; realization failure commits nothing. Validation required by the existing adapter certification/runtime-guard policy SHALL remain at its owning backend boundary: when runtime guards are enabled, output is validated there before use, while a certified adapter with guards disabled MUST NOT acquire a new mandatory per-item validation pass from this optimization. Failure of any applicable guard commits nothing. The routed vector, after satisfying the applicable guard/certification policy, SHALL be consumed directly rather than copied into an equivalent vector by the reducer or route. A full-width response SHALL NOT be treated as proof of exhaustion; adapters retain the existing conservative lookahead contract.

#### Scenario: Adapter returns a full-width bounded response
- **WHEN** an adapter returns exactly the requested number of ordered values
- **THEN** EACL retains conservative lookahead and does not infer exhaustion merely from chunk width

#### Scenario: Routed boundary returns a realized vector
- **WHEN** the physical routing boundary has fully realized the bounded response as a vector and the response has satisfied the adapter's applicable runtime-guard or certification policy
- **THEN** stable, least-path, and point consumers use that vector without rebuilding an equivalent collection or adding another per-item guard

#### Scenario: Least-path requests a narrow eager scan
- **WHEN** least-path issues a descriptor with positive limit `L` to an adapter that eagerly realizes or natively limits its scan
- **THEN** the adapter observes limit `L` before doing the scan and does not fetch an unrequested wider prefix for Core to discard
- **AND** public order, continuation, typed outcomes, and logical fetched-value accounting equal the correctly bounded reference

### Requirement: Resource limits are staged, exact, and coherent with public demand

Every governed limit SHALL be checked before the transition it governs commits. Arithmetic used to derive an internal lower bound from public demand MUST be overflow-safe. A public request MUST NOT fail solely because a fixed internal default is below the request's mathematically necessary output/lookahead lower bound; schema-dependent logical work MAY still exhaust its separately documented limits and fail typed. Any tightened public hard maximum MUST be versioned, discoverable, and rejected before traversal rather than silently imposed by one deployment.

#### Scenario: Successor batch would exceed a limit
- **WHEN** admitting a complete successor batch would exceed its item, work, capacity, or specifically configured retained-weight limit
- **THEN** none of that batch, its residual work, counters, or output commits

#### Scenario: Public page demand exceeds a fixed internal default
- **WHEN** a valid public page demand requires `N+1` output slots but a generic internal default is smaller
- **THEN** EACL derives or validates a sufficient operation limit before execution rather than failing after partial traversal

#### Scenario: Schema-dependent work exhausts a limit
- **WHEN** the admitted/visited or physical work required by a schema exceeds a configured governed limit
- **THEN** EACL returns the existing typed resource failure with safe counters and no partial successful result

#### Scenario: Effective public maximum is tightened
- **WHEN** a hard safety cap requires a lower public maximum
- **THEN** the versioned capability contract exposes that maximum and rejects larger requests before semantic work begins

### Requirement: Deadline and cancellation checks retain semantic-quantum boundaries

Execution SHALL preserve checks before and after every generated semantic quantum and every bounded physical command. This change MUST NOT replace those checks with an unproved fixed transition cadence. Once cancellation or deadline expiry is observed, no answer, cursor, checkpoint, or cache entry may be published.

#### Scenario: Cancellation occurs during pure transitions
- **WHEN** cancellation becomes observable during a sequence of generated pure transitions
- **THEN** EACL stops at the next existing semantic-quantum boundary and publishes no later state

#### Scenario: Deadline expires around a physical read
- **WHEN** the deadline expires immediately before a physical command or while that command is running
- **THEN** EACL starts no later command and returns the existing typed deadline outcome after the command returns or aborts

### Requirement: Stable rank costs have one fingerprinted production identity

Planning, execution, diagnostics, and plan fingerprinting SHALL consume one production rank-cost contract. Formal verification SHALL compare that contract with an independently derived specification or oracle rather than importing the production constants as its only authority. A rank-cost change MUST alter the plan fingerprint and execute only after the corresponding formal and behavioral evidence changes.

#### Scenario: Production rank cost changes
- **WHEN** any stable rule cost changes
- **THEN** the plan fingerprint changes and stale plan-bound state cannot be reused

#### Scenario: Production and formal rank identities diverge
- **WHEN** the production rank table differs from the independently derived formal expectation
- **THEN** the conformance gate fails

### Requirement: Stable-engine behavior remains cross-runtime and cross-backend conformant

For every supported Core runtime and certified backend, the optimized and reference paths SHALL agree on authorization denotation, public sequence, count/truncation, cursor composition, duplicate freedom, and non-resource typed failures. Semantic stopping rules remain unchanged. Counters attributable to formally mapped duplicate work MAY decrease; other governed counters MUST remain equal or within an explicitly declared deterministic refinement bound and none may increase without qualification evidence. A resource failure caused only by eliminated duplicate work MAY occur later or disappear, but the optimized path MUST NOT introduce an earlier resource failure. Physical cancellation points SHALL be compared only where optimized and reference execution retain the same modeled command trace; all paths remain governed by the deadline and cancellation requirement above. Runtime-specific performance metrics MAY differ in availability, but semantic parity is mandatory.

#### Scenario: Alias-rich fixture runs on CLJ and CLJS
- **WHEN** CLJ and CLJS execute the same alias-rich normalized schema and immutable relationship fixture
- **THEN** they produce equal transformed plan identity, ordered results, counts, and failures

#### Scenario: Optimized path changes a limit outcome
- **WHEN** the optimized path reaches a resource limit earlier than the reference or exceeds the declared work-refinement bound
- **THEN** qualification fails even if the optimized path is faster

#### Scenario: Proved duplicate work is removed
- **WHEN** an exact-alias fixture previously exhausted a governed physical-work limit solely because it scheduled duplicate equivalent traversals
- **THEN** the optimized path may consume fewer work counters and succeed or fail later
- **AND** it returns the same semantic result whenever both paths complete
