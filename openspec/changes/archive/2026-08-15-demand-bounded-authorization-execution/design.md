## Context

The v8 engine has two individually rational mechanisms that compose into the
wrong public cost model:

1. The cache-free recursive point evaluator starts at the named target and
   stops as soon as it proves the requested Boolean.
2. The cache-enabled evaluator resolves a complete subject-rooted denotation so
   the result can later serve points, pages, and counts.

Likewise, a bounded recursive count stops at its sentinel without a store but
resolves the complete denotation with a store, the projection cache fetches in
its own chunks rather than the evaluator's exact demand, and recursive keyset
pagination sorts a complete denotation before emitting a small page. The cache
therefore changes the evaluator, the backend command trace, the stopping rule,
the limits that can fail, and the amount of retained work.

The Datomic client additionally holds one `ReentrantReadWriteLock` read lock for
an entire authorization operation so a mutable client schema cache cannot be
paired with another schema generation's DB value. Datomic, Datahike, and
DataScript already expose immutable database values; the architectural error is
deriving one request from both an immutable snapshot and mutable client schema
state.

This design treats the selected immutable snapshot as the sole semantic source,
separates cache permission from evaluation completeness, and makes demand a
first-class input to the generated evaluator. It supersedes conflicting
full-denotation, single-flight, sorted-recursive-keyset, and DataScript exact-
registry decisions in earlier unarchived changes.

## Goals / Non-Goals

**Goals:**

- Make a cold cache-enabled request execute the same semantic work as the same
  cache-disabled request.
- Bound foreground work by the public operation's requested Boolean, count
  limit, page size, traversal limits, execution mode, and deadline.
- Retain exact work already demanded without fetching or completing additional
  work for the cache.
- Preserve sound exact and proof-lifted reuse on the snapshot selected by the
  requested consistency mode.
- Remove EACL-level blocking schema locks from authorization reads.
- Make writer races correct through backend-atomic optimistic preconditions.
- Define deterministic incremental recursive pagination without complete-
  denotation sorting.
- Make DataScript current-basis-only across requests without stale cached
  answers or hybrid cursor walks.
- Provide generated/formal and executable evidence for correctness, work
  bounds, ordering, deadlines, and concurrency behavior.
- Make every externally presented formal claim demonstrably conform to the
  shipped EACL implementation and reproducible by an independent certifier.

**Non-Goals:**

- No prewarm API, background warming, adaptive speculative completion, or
  post-response traversal.
- No promise that a response includes commits occurring after snapshot
  selection.
- No hard wall-clock theorem across GC, OS scheduling, or uninterruptible
  backend calls.
- No cache-specific admission queue that delays authorization.
- No guarantee that runtimes or backend connections contain no internal
  synchronization; the guarantee is that EACL authorization does not wait for
  another EACL request's cache or schema work.
- No portable partial fixed-point result that can be mistaken for an
  authorization answer.
- No DataScript reconstruction of an older DB value after the request that
  selected it has ended.

## Decisions

### 1. Normalize an execution contract before cache access

Every public authorization operation will normalize one immutable execution
contract before consistency selection or cache lookup:

```clojure
{:evaluation :demand                    ; default
 :deadline-nanos monotonic-absolute-deadline
 :limits normalized-traversal-limits
 :operation :can?                       ; or lookup/count variant
 :demand {:kind :boolean}}              ; count/page/complete variants
```

The public request controls are:

```clojure
{:evaluation :demand
 :timeout-ms 250}

{:evaluation :complete-denotation
 :timeout-ms 5000}
```

`:evaluation` controls the largest semantic object the caller authorizes EACL
to compute. `:cache?` controls only whether compatible completed artifacts may
be read and whether already-demanded artifacts may be published. The two
controls are orthogonal and appear in semantic/cache keys where they can change
the result shape or permitted work.

The client provides a finite documented default execution timeout; a positive
per-request `:timeout-ms` overrides it. The same normalized contract is passed
to cache-enabled and cache-disabled execution.

Alternatives rejected:

- Continue interpreting `:cache? true` as permission to complete a denotation:
  it preserves the defect and forces consumers to understand internal strategy.
- Add `prewarm!`: the caller asked for an optional expensive execution mode,
  not another lifecycle/API surface.
- Infer reuse intent from repeated demand: background/adaptive completion adds
  cancellation and obsolescence races without making the initiating request's
  contract explicit.

### 2. The generated evaluator owns demand and stopping

The generated state machine, not a host cache wrapper, owns the logical
scheduler, demand counter, distinct-result set, stopping decision, and command
sequence.

| Operation | `:demand` stopping condition | `:complete-denotation` |
| --- | --- | --- |
| Positive point check | Stop on a certified proof of the target | Exhaust the compatible forward denotation, then test membership |
| Negative point check | Exhaust only the target-anchored reverse question | Exhaust the compatible forward denotation |
| Count with limit `L` | Stop after `L+1` distinct results or graph exhaustion | Exhaustion is permitted for reuse, but render the same limit/truncation contract; exact output still requires an unbounded count request |
| Count without a limit | Exhaust because exact count is the requested value | Same semantic work |
| Forward page of `N` | Stop after `N+1` results in traversal order | Completion is permitted but page rendering still returns `N` |
| Continued page | Restore/replay to the cursor boundary, then demand `N+1` | Completion is permitted |
| Bare recursive `:last` | Reject as requiring unknown prefix exhaustion | Exhaust explicitly and take the suffix |
| `:before` page | Replay the deterministic prefix with bounded retained state, then return the requested window | Completion is permitted |

The `L+1` and `N+1` values are sentinels used only to prove truncation or the
existence of another page. They are not returned as extra results.

Resource limits apply to actual generated work identically with cache enabled
or disabled. Cache hits may remove commands; a cold miss may not create a
different command or failure envelope.

Traversal limits are transported, not rediscovered. Client construction
normalizes every partial override into one complete positive map. The public
execution contract captures that exact map; completed-answer and denotation
keys bind it; and every backend facade binds the same map into generated
initialization. A compatibility facade that rebinds an engine dynamic to its
own defaults is a correctness defect, because it silently changes both the
formal input and the caller's failure envelope. Strict-limit cross-backend
tests and the implementation-conformance ledger guard this boundary.

### 3. Adopt a cache trace refinement law

For the same selected snapshot, normalized semantic query, execution contract,
limits, and deadline schedule:

```text
cache-enabled semantic command trace is the cache-disabled trace
  with zero or more commands removed
cache-enabled semantic response trace is the matching response subsequence
cold cache miss command/response trace == cache-disabled trace
```

Cache bookkeeping, key construction, and exact-key local lookup are outside the
semantic command trace but receive separate deterministic bounds. Cache
eligibility or proof lifting is not allowed to issue
a semantic/backend command that cache-disabled execution would not issue. If a
complete dependency proof is not already available from snapshot selection,
normal schema/plan work, the demand trace, or the exact local entry, the
candidate is a miss rather than a reason to scan the selected snapshot.

A cache-enabled request MUST NOT:

- reverse traversal direction;
- enlarge a projection window;
- use a different chunk size to fetch more values;
- continue a fixed point after the stopping decision;
- on a cold total miss, change which traversal/resource limit fires;
- charge semantic resource counters for commands removed by a hit; or
- publish work not already validated as part of the request.

Each client normalizes the cache controls the shipped implementation actually
uses: a positive evaluation reserve and a bounded number of local CAS
publication attempts. When the remaining request budget
cannot preserve the reserve, EACL skips cache access. Per-tier and per-entry
weight ceilings belong to construction-time configuration of the client-private
stores, not to a fictitious remote decode envelope. Caller-supplied provider
stores are rejected at construction because no live authorization path consumes
them. There is therefore no provider cancellation, streaming decode, compressed
artifact, or candidate-search behavior in the v8 certification target.

This is stronger and more testable than a wall-clock claim. Cache lookup has
nonzero overhead, so literal “never one nanosecond slower” is impossible; EACL
instead guarantees equal semantic work, bounded bookkeeping, and latency gates.

### 4. Cache exact commands and completed demanded artifacts

The projection cache moves below cache-owned lazy sequences to the generated
adapter command/response boundary. The evaluator issues an exact validated scan
command `C`; the cache key includes the complete command and adapter/snapshot
identity; a hit returns the previously validated response `R`; a miss invokes
the backend with exactly `C` and may retain exactly `R`.

Artifact indexes are typed, exact-keyed, and client-private. Their host-weight
estimate is validated before bounded local publication; per-tier and per-entry
ceilings reject oversized values. Lookup retrieves one already resident native
entry and never streams, decodes, decompresses, or searches a remote candidate
set. The engine uses only an artifact type that is sufficient for the operation
or treats lookup as a miss.

Permitted artifacts are explicitly typed:

- completed point Boolean with complete dependency framing;
- exact bounded-count response for its normalized limit and execution mode;
- exact page/top-level response for its normalized query and cursor context;
- validated generated command/response pair;
- private continuation state that is explicitly incomplete and can only resume
  the same execution contract;
- a completed denotation produced by natural demand exhaustion or explicit
  `:complete-denotation` evaluation.

An incomplete traversal, interrupted negative search, partial SCC, fetched
prefix, or timeout is never published as a completed Boolean or denotation.
Continuation state cannot answer another semantic query.

Publication never performs semantic work beyond the result already demanded by
the request. It validates the native value and weight, performs bounded local
eviction bookkeeping, and makes at most the configured number of CAS attempts.
Capacity, lifecycle, validation, or contention rejection skips publication and
returns the completed public result.

### 5. Authorization never waits for cache work

The demand path removes cache-owned blocking single-flight and the fair
computation semaphore. On a miss, each request evaluates its own execution
contract. Compatible concurrent requests may race publication; the first valid
publication wins and losers discard their candidates.

Cache lookup and publication use bounded, failure-isolated coordination:

- completed immutable values are read without an EACL monitor spanning user or
  backend work;
- generation installation is an atomic pointer/CAS operation;
- publication makes at most a configured bounded number of local CAS attempts;
- contention, eviction, capacity rejection, or local cache failure skips
  publication and does not delay or fail authorization;
- lifecycle expiry swaps an atomic generation pointer, so late publication can
  reach only a detached generation;
- no EACL traversal, proof, or publication continues after its owning request
  stops.

If deployments require overload protection, it is a request admission control
applied before cache selection and identically to `:cache? true` and false. It
uses deadline-aware non-waiting admission or a documented external queue; it is
not reported as a cache hit/miss.

Alternative rejected: retain single-flight only for complete mode. It couples a
request's latency and failure to another request and is unnecessary for
correctness. Explicit complete requests may duplicate work; operational
admission controls bound that workload honestly.

### 6. Logical traversal order is a versioned ABI

The generated scheduler defines a total deterministic result order based on
logical rule/component order, canonical endpoint ordering, stable queue
discipline, and a complete tie-breaker. Physical adapter chunk size, wave batch
size, fuel, cache hits, and page size may change how many crossings occur but
MUST NOT change the logical sequence.

Fuel is a scheduling boundary, not a transactional rollback boundary. If a
quantum has collected pending scans when fuel reaches zero, the authority emits
that nonempty request-ordered wave immediately, even when it is smaller than
the maximum wave size. Yielding the quantum's original state would discard
issued request state and can repeat the same prefix forever. Only a fuel cut
with no pending scan yields the current state. Forward and reverse generated
authorities and the portable CLJS refinement implement this same transition.

Cursor envelopes bind:

- source/family/branch and selected graph identity;
- query and operation digest;
- schema, dependency, identity, adapter, engine, and ordering ABI digests;
- direction, ordinal, and authenticated boundary identity;
- execution mode and answer-affecting limits;
- absolute expiry and consistency constraints.

Continuation-cache hits resume the same state. On a miss, EACL deterministically
replays on the selected proof-equivalent snapshot to the ordinal/boundary and
then requests the next `N+1`. Replay work is deadline- and traversal-limit-
bounded and observable. A changed ordering ABI rejects the cursor.

Sorting the complete denotation by internal EID is rejected as the default
because no result can be safely emitted until closure proves a smaller EID will
not be discovered. Explicit completion preserves the identical generated
logical order used by demand evaluation; it does not introduce a second sorted
order. Completed cursor slicing validates both logical ordinal and result
identity before returning an exact exclusive page.

### 7. One immutable snapshot replaces schema read locks

Every request follows this order:

```text
select immutable snapshot S
derive source/graph/schema proof G from S
resolve plan keyed by [source, G, engine ABI]
derive dependency/identity proofs from S
evaluate and render entirely from S
publish only into keys/generations for S and G
```

Authorization reads acquire no EACL schema lock. A schema commit after selection
does not invalidate an in-flight read: the read is correct for `S`; subsequent
requests select the new snapshot.

The Datomic `schema-state` latch is replaced by a bounded generation registry
keyed by the selected snapshot's schema proof. A schema write may atomically
detach old cache generations for memory reclamation, but correctness does not
depend on the timing of that cleanup.

### 8. Writers use preconditions captured from their calculation snapshot

Immutability does not make a multi-step write atomic. For every schema or
relationship transaction:

1. Capture `S0`.
2. Resolve endpoints/schema and calculate the complete transaction from `S0`.
3. Include commit-time guards whose expected values were read from `S0`.
4. Commit atomically or return/retry a typed conflict from a newly selected DB.

Datomic continues to use schema-generation CAS and transactor-side relation-
unused guards. DataScript and Datahike must not obtain the expected mutation
head inside a later journal call after the delta was calculated. They pass the
original schema generation/graph head into transaction construction. A
conservative graph-head guard may reject after any intervening EACL mutation;
more selective schema/relation guards are allowed only with equivalent race
coverage.

This protects concurrent schema replacements, relation removal versus tuple
creation, stale endpoint resolution, and late retries across all clients and
processes. A client-local lock is neither necessary nor sufficient.

### 9. Deadlines are absolute, monotonic, and end-to-end

The public boundary converts `:timeout-ms` to one monotonic absolute deadline.
Every layer receives remaining budget rather than starting a new relative
timeout. EACL checks the deadline:

- before and after consistency selection;
- before client-private cache operations;
- before and after schema/plan/proof work;
- before every generated fuel quantum;
- before and after every backend command;
- before rendering/externalization; and
- before optional cache publication.

After expiry, EACL begins no new generated quantum, backend command, proof, or
publication. The documented maximum overrun is one already-running bounded
backend command plus runtime scheduling delay. Adapters use cancellation when
available but do not claim that an uninterruptible call has stopped.

Deadline failure throws `:eacl.execution/deadline-exceeded` with safe operation,
stage, timeout, and consumed-work diagnostics. It never returns false, an exact
count, or a successful page. Semantic timeout failures are not cached. An exact
command response completed and validated before expiry may be retained only if
publication itself is nonblocking and still inside the deadline.

### 10. Cache reuse never weakens consistency

Consistency selection always precedes cache validation. A candidate computed at
`C` can serve selected snapshot `S` only when:

- source/branch identity matches;
- `C` is causally at or before `S`, never a future/sibling history;
- the selected snapshot satisfies the request's consistency floor;
- the full semantic key and ABI match; and
- complete schema, relationship, identity, and ordering dependency proofs
  establish observational equivalence on `S`.

Otherwise EACL evaluates on `S`. Validation cannot start additional backend
commands solely to rescue the candidate; proof lifting uses only proofs already
available inside the cache-attempt envelope and ordinary cache-free request
work. `:cache?` never grants a stale-data exception.
`:at-least-as-fresh T` selects `S` containing `T`; an older candidate may serve
only after proof lifting to `S`. The response's `:cache-basis` remains `C`
(where the answer was computed); request-local validation uses selected `S`
without relabeling the old answer as if it had been computed there.

### 11. DataScript is current-basis-only across requests

Each DataScript operation captures one current `ds/db` and remains on it until
the operation ends. DataScript no longer advertises `:at-exact-snapshot`,
creates exact handles, retains DB values in a snapshot registry, or accepts
`:exact-snapshot-registry-size`.

Consistency modes are:

- `:minimize-latency`: current complete local DB without a barrier wait;
- `:fully-consistent`: current head of the serialized local connection;
- `:at-least-as-fresh T`: current DB or a deadline-bounded wait until the DB
  contains the authenticated mutation anchor `T`;
- `:at-exact-snapshot`: typed unsupported capability before cache/evaluation.

For pagination, the default content/unknown-authority path binds the cursor to
the exact selected DataScript basis. It does not hash relationship content to
mint or resume a small page: that graph-linear proof made `:cache? false` pages
hundreds of times slower while providing no historical DataScript basis to
resume. Any later basis is therefore stale in this mode, including after an
unrelated transaction.

Explicit managed mutation-stamp mode may instead bind the compiled dependency
closure and continue on a newer current DB when all bounded schema,
relationship, identity, and ordering stamps remain equal. A changed stamp
returns a typed stale-cursor or newer-floor conflict. DataScript never silently
restarts and never uses cached pages from the old proof. This distinction is
deliberate: exact-basis identity is the safe constant-cost default; scoped
cross-basis continuation is available only under the writer authority that can
maintain bounded complete mutation stamps.

### 12. Observability reports independent decisions

Public detailed responses preserve `:cached?` and `:cache-basis`; detailed point
responses also expose the normalized evaluation mode. Count and page requests
bind that mode into their exact cache identity and cursor rather than adding a
redundant response field. Internal stats implemented by the shipped cache
separately count:

- exact and managed/proof-lifted hits, misses, bypasses, and local failures;
- exact backend commands avoided and projection values fetched;
- publication success, rejection, race, contention, detachment, and oversize;
- continuation/page-store hits, misses, puts, evictions, rejection, and errors;
- generated traversal work when the caller binds the existing traversal stats
  seam; and
- deadline stage and bounded consumed-work fields in typed deadline errors.

A cold miss is never labeled a cache hit merely because another in-flight
request produced its value. With no join behavior, this ambiguity disappears.

### 13. Verification and release gates follow the new claims

The generated/formal model will own demand, stopping, deterministic logical
order, command/response validation, deadlines at quantum boundaries, and typed
artifact completion. Host refinement tests will compare cache-enabled cold
misses with cache-disabled traces and validate that warm traces only remove
commands.

Required executable evidence includes:

- positive shallow/deep and negative points over star, chain, cycle, diamond,
  mutual-recursion, and broad-union graphs;
- bounded/exact counts, first/continued/before/last pages, and cross-operation
  request sequences;
- chunk/fuel/batch/page/cache permutations producing identical order;
- forward and reverse fuel cuts with nonempty scan waves proving bounded
  publication, request-id order, and monotonic progress on broad fan-out;
- 1k, 10k, 100k, and acceptance-gated larger broad-principal fixtures;
- relevant/unrelated schema, relationship, identity, restore, reset, and branch
  mutations under unknown and managed authority;
- deterministic deadline tests with a fake monotonic clock and blocked-command
  adapters;
- concurrent cache misses proving no request waits on cache work;
- concurrent writer schedules proving stale deltas cannot commit;
- DataScript CLJ/CLJS current-only consistency and stale-cursor behavior; and
- all backend, generated-boundary, formal, temporal, mutation, reflection, and
  strict OpenSpec gates.

Formal documentation must continue to distinguish proved semantic/work
properties from unproved JVM heap, CPU, GC, backend latency, and wall-clock
behavior.

### 14. Certification claims are implementation-conformant and fail closed

The formal assurance stack is not permitted to prove a convenient algorithm
that merely resembles production. Every public claim receives one stable claim
identifier and a bidirectional evidence row containing:

```text
OpenSpec requirement/scenario
  <-> public operation and production source/control boundary
  <-> exact formal inputs, assumptions, transition relation, and theorem
  <-> generated artifact or mechanized refinement bridge
  <-> executable conformance, negative-control, and cross-runtime evidence
  <-> source/tool/evidence digests in the release manifest
```

A production target satisfies a theorem only when it executes the verified
artifact or a mechanized refinement establishes that the shipped implementation
implements the modeled transition relation under the theorem's preconditions.
Differential, property, mutation, and exhaustive-small-domain tests are required
drift detectors, but are not mislabeled as a proof of source refinement.
Proof-only models remain useful, but their assurance class and absent production
authority are explicit and cannot satisfy a production certification row.

The conformance gate compares production entry points, modeled fields and
branches, generated/portable target artifacts, source closure, theorem
preconditions, trusted adapters, error mappings, work counters, and public
claim text. An added/removed/renamed branch, input, error, limit, or state
transition fails closed until both sides and their evidence row are reviewed.
No stale digest can bless a changed implementation.

The certification bundle pins Dafny, solver, JVM, Clojure/CLJS, JavaScript
runtime, target compiler, generated-runtime patches, and verification scripts;
records commands, hashes, proof results, mutation controls, residual
assumptions, and exclusions; and rebuilds from a clean checkout. Independent
reviewers can therefore reproduce the exact artifact and see the honest claim
boundary without relying on a developer workstation cache.

### 15. Simplification is a checked architectural property

The replacement architecture has one normalized public request contract, one
immutable-snapshot selection, one generated demand evaluator, and typed cache
substitution at the exact command/artifact boundary. Cache-enabled and
cache-disabled operations do not own separate semantic implementations. This is
also the strongest construction for cache trace refinement: the miss path is
literally the bypass path after a bounded failed lookup.

Deletion is part of the design, not cleanup deferred indefinitely. Complete-
denotation-on-default point/count paths, cache-owned projection widening,
single-flight joins, cache computation semaphores, EACL schema read locks,
DataScript DB-value registries, and temporary differential switches are removed
after their replacements pass. Migration compatibility may parse and reject an
old input; it may not preserve an old semantic engine.

A source-derived structural inventory counts semantic entry paths, answer-
affecting branches, mutable coordinators, locks/semaphores, background work,
artifact kinds, and generated/host semantic forks. These categories are more
meaningful than line count: moving a state machine into fewer dense lines is not
simplification. Every category ratchets down or stays flat unless a reviewed
replacement row ties a necessary addition to deletion of an equal-or-larger
superseded mechanism.

Performance has two independent gates. Deterministic logical-work metrics must
not regress for the checked corpus, even if timing noise makes a candidate look
faster. Reproducible latency, throughput, allocation, and retained-memory
benchmarks then enforce absolute and relative thresholds with environment and
variance disclosure. Neither evidence class impersonates the other, and both
are bound to the exact candidate artifact in the certification ledger.

The certified acyclic frontier also canonicalizes exact pure permission aliases
before identity deduplication. Only a permission body consisting of one
same-resource self-permission qualifies; composite bodies remain untouched and
alias traversal is cycle-guarded. This removes duplicate indexed streams such
as `account->view` and `account->admin` when `view = admin`, preserves the first
canonical path's public position, and cannot widen scans or change denotation.

## Risks / Trade-offs

- **[Point-then-page workloads lose accidental warming]** → Callers that know
  they need the complete reusable set opt into `:complete-denotation`; publish
  sequence benchmarks and migration guidance rather than hiding the cost.
- **[Concurrent cold misses duplicate work]** → Apply uniform request admission,
  deadlines, bounded cache publication, and duplicate-work metrics. Do not turn
  duplicate CPU into unbounded waiter latency.
- **[Generated logical order is not numeric EID order]** → Version the order
  ABI, validate completed artifacts as unique logical sequences rather than
  sorted vectors, use order-independent membership, reject old cursors, and
  prove invariance across all physical execution parameters.
- **[Cursor replay can be expensive after continuation eviction]** → Bound replay
  by the request deadline/limits, retain only demanded continuation state, and
  report replay work. Never complete the denotation merely to avoid replay.
- **[DataScript exact-snapshot callers break]** → Reject the removed capability
  clearly, remove the registry option at construction, and document current-
  proof continuation or application-owned DB-value evaluation as alternatives.
- **[Optimistic writer conflicts become visible]** → Return typed conflict data,
  retry only idempotent operations within the request deadline, and test every
  race. This is safer than silently merging stale deltas.
- **[Deadline cannot interrupt a backend primitive]** → Define the exact one-
  command overrun envelope, bound adapter commands, and use cancellation where
  supported without overstating it.
- **[Proof validation itself can dominate a small request]** → Include proof
  material only when it is also ordinary cache-free work; cache-only validation
  has zero backend-command authority. Preserve evaluation headroom and treat
  unproved local entries as misses.
- **[A future external provider broadens the trusted boundary]** → Keep provider
  stores rejected in v8. A later provider design requires a separate spec,
  streaming bounds, cancellation semantics, threat model, and mechanized
  implementation mapping before it can enter certified scope.
- **[Late publication crosses lifecycle/schema changes]** → Key by immutable
  generation and detach through atomic pointer replacement; late old work remains
  unreachable.
- **[A correct proof targets a stale or simplified implementation]** → Require
  stable bidirectional claim rows, mechanized refinement or generated production
  authority, source/branch drift gates, negative controls, and clean-room
  reproducibility. Qualification replaces any unsupported certification claim.
- **[A rewrite adds abstraction layers while deleting visible code]** → Gate
  semantic paths, branches, mutable coordinators, background tasks, and
  generated/host forks from a source-derived inventory; line count is only
  descriptive.
- **[Noisy benchmarks reject an equal implementation]** → Pin warmup, sample,
  environment, variance, confidence, and rerun rules; keep deterministic work
  counters as the primary non-regression evidence.

## Migration Plan

1. Add characterization tests that fail on the current cache-dependent command
   traces, projection overfetch, chunk-sensitive order, schema-lock blocking,
   DataScript exact registry, and stale-delta schedules.
2. Extend the generated evaluator/model with demand, logical order, deadlines,
   and exact command/response cache boundaries; regenerate CLJ/CLJS authorities.
3. Introduce request controls and provenance while retaining the old evaluator
   behind a temporary test-only differential switch.
4. Move projection caching to exact adapter commands and change point/count/page
   cold misses to the demand evaluator.
5. Replace single-flight/semaphore coordination with bounded non-waiting
   publication and detached immutable generations.
6. Move Datomic schema derivation entirely onto the selected snapshot, add all
   cross-backend transaction guards, then remove the client schema lock.
7. Cut recursive pagination to the new ordering ABI and cursor format; remove
   implicit bare-last completion.
8. Remove DataScript exact capability, handles, registry, and client option;
   enforce proof-equivalent current continuation.
9. Run the complete differential, performance, mutation, concurrency, timeout,
   CLJS advanced, implementation-conformance, and formal gates; produce the
   reproducible certification bundle and update the assurance manifest with the
   exact supported claim boundary.
10. Update release notes and consumer examples, remove the temporary old path,
    and ship as a deliberate v8 breaking contract.

Rollback before release is a branch revert. After releasing the new cursor/order
ABI, rollback must preserve typed rejection of new cursors rather than attempting
to reinterpret them under the old order.

## Open Questions

None. Public option names, DataScript capability removal, demand stopping rules,
nonblocking cache behavior, snapshot linearization, and deadline semantics are
decisions of this change rather than implementation-time policy choices.
