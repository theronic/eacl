# Temporal snapshot, cache, cursor, and continuation model

## Scope

`formal/tla/EaclTemporalDetailed.tla` is a typed transition model of the
history-sensitive authorization boundary. It deliberately models decisions
rather than the internals of Clojure or any database.

`formal/tla/EaclOperatorSafety.tla` is a separate abstract Phase A model for
operator execution. It covers atomic aligned-vector publication and
cancellation, logical progress despite physical overread, exact checkpoint
resume, eviction of entries from expired cache lifecycles, cache-hit lifecycle
matching, and authorization from negative evidence only
after the lower stratum is complete. It is not connected to a production
operator implementation.

`formal/tla/EaclCacheStorage.tla` is the cache-storage authority. It models a
count-bounded partial-map domain of complete composite keys, explicit
exact-first/managed-second lookup, request-owned miss computation, optional
publication gated by completed/page retention eligibility and cache-stage
availability at the publication boundary, arbitrary eviction, independent semantic source-identity and
source-lifecycle key changes, and fresh private-store-instance replacement.
Old entries deliberately remain resident across semantic identity changes in
the abstraction, so source separation is exercised rather than assumed from
an empty store. Format, domain, and engine/value ABI are frozen constants in
this finite transition system; `SubproblemCache.dfy` retains them as explicit
composite-key fields and proves their equality separation. Store references are represented by
strictly increasing ghost IDs, preventing ABA-style reuse after multiple
replacements. Miss ownership is indexed by an
independent computation token, so one request may own multiple simultaneous
subproblem computations. The only live tiers are exact completed denotations
and completed answers; answer keys may be exact or managed, while denotation
keys are exact-only. Exact and managed publication are alternatives of the
same request-owned completed-computation transition; there is no separate
managed seed or loader transition. Physical operator projections are outside this completed
semantic-artifact boundary. It does not
model LRU order: that retention policy is a portable host-library test. The
former weighted, tier-budget, managed-projection, and generation-container TLA
models were deleted.

Each miss token captures both its exact composite key and the managed proof
descriptor that was current when the computation began. A later proof change
cannot relabel the completed value at publication time. The
`managed-publication-proof-drift` executed-model mutant deliberately uses the
later active proof and is required to violate `Safety`.

The storage model receives complete exact keys and already validated managed
key/value candidates; it is deliberately not the authority for basis class or
causal ordering. `CurrentCache.dfy` proves historical bases are exact-only and
`ScalarFrontierCoherence.dfy` proves a managed candidate's immutable computed
revision cannot exceed the selected revision. The detailed temporal model
independently retains the future-entry negative control.

`SubproblemCache.dfy`'s managed resolver accepts an explicit host eligibility
input; `CurrentCache.dfy` proves that this input is false for historical bases.
`EaclCacheStorage.tla` abstracts only an already host-eligible managed answer
lookup and is not itself the basis-class authority.
`SubproblemCache.dfy` makes resident validity inductive. Empty construction
and validated off-side restore establish the invariant; the restore premise
combines local closed-envelope/capacity validation with the public API's
requirement that input is an already authenticated supported EACL export, so
semantic equality is inherited rather than recomputed during restore. Validated live
publication, map-preserving LRU touches, arbitrary eviction, and fresh private
store-instance replacement preserve it. Off-side restore reconstructs the
same configured tier and capacity before installing the fresh store instance.
Deadline expiry or cancellation makes the cache stage unavailable and forces
an otherwise completed publication candidate to drop without changing the
resident map. The same high-level guard precedes exact lookup, managed-proof
descriptor construction, and managed lookup: an unavailable request performs
zero answer/denotation probes, zero recency touches, and zero managed-proof reads before
continuing with independent computation.
Semantic source lifecycle remains a
complete-key field and is not used as the store-detachment token. Exact lookup
therefore performs ordinary
composite-key membership without repeating artifact/ABI validation. Managed
lookup additionally retains the only request-relative condition: its immutable
computed revision must not exceed the selected revision. The same generic
model proves that an enabled miss and a cache-disabled request both return the
requesting operation's independent success-or-error result. This isolation is
scoped to authorization answer and denotation stores; request-independent
derived-schema and cursor LRUs remain active. Arbitrary
eviction cannot change that resolved result.

The finite TLA abstraction permits opaque semantic lifecycle, exact-identity,
and proof values to recur. Such recurrence is safe only when equality truthfully
means the same complete semantic authority, an explicit host/adapter refinement
premise. It is not private-store identity reuse: retired store instances are
tracked separately and may never be reinstalled. `CurrentCache.dfy` also keeps
direct exact publication distinct from validated managed-to-exact promotion.
Direct publication derives cache basis from the exact key's already selected
backend snapshot identity rather than accepting a redundant caller input.
Promotion preserves the old immutable cache basis/revision/locator, so the live
resident may be valid for the current exact result while remaining deliberately
ineligible for portable exact export.

The detailed temporal model's 23 transitions cover:

- managed and unmanaged graph and schema writes;
- clone, reset, restore, branch, and forced-head movement;
- retained and evicted snapshots in a causal history graph;
- selected, computation, and exact snapshots;
- complete dependency scopes, structural proofs, and proof lifting;
- authenticated cache lookup, tampering, abstract local-read/proof failure, future and sibling
  entries, storage, invalidation generations, and telemetry compare-and-set;
- cursor authentication, operation/non-page-query/result scope, positional
  direction changes, expiry, proof-equivalent current selection, exact-snapshot
  fallback, divergence, stale rejection, and freshness conflict;
- recursive continuation publication, eviction, lookup, and deterministic
  replay. It contains no externalized-page or visited-page state.

The model checks these safety properties:

1. within this current-head temporal submodel, managed cache reuse is only from
   a causal ancestor to the selected snapshot with matching authenticated
   source, query, scope, canonical dependency identity, and proof; an older
   selected snapshot never consumes a future managed entry, while historical
   reuse remains identical-exact-key only;
2. authenticated query-scoped cursors continue on current only when its
   dependency proof equals the cursor proof, otherwise continue on the
   retained exact graph when available and freshness-compatible, or fail
   closed;
3. a page walk never combines results from different computation graphs;
4. cache and continuation publication or eviction races do not change the
   authorization decision.

## Bounds and proof obligations

The compact model remains as a fast regression abstraction and is checked to
length 12. The detailed pull-request configuration has three histories, two
proofs/scopes/queries/directions/operations/result kinds/sources, three times,
and is checked to length 6. The scheduled configuration expands to four
histories, three proofs/queries/operations/result kinds, four times, and is
checked to length 3.

The cache-storage baseline is checked to length 5 and also exhaustively checked
by TLC over a dedicated finite configuration. It contains two source
identities, two request IDs, and two computation IDs: source rotation exercises
complete-key separation while prior entries remain resident, the request pair
exercises cross-request identical misses, and the computation pair permits one
request to own two simultaneous misses. Its six
retained negative configurations separately cover partial-value installation,
returning a staged partial value as a completed hit, publication from a
detached private store instance, reuse of a retired store identity while work
captured under it remains active, managed-proof bypass, and relabeling a
completed managed value with a proof that was not captured by its computation.
The first five registry IDs bind the historical cache-fail-open and
continuation-race names to their narrower semantic controls; the sixth is the
new managed-publication proof-drift control.

The cache inductive invariant additionally requires the staged partial set to
be empty whenever both partial-value negative controls are disabled. This
strengthens arbitrary induction states without constraining a reachable
baseline trace; each partial mutant deliberately enables the corresponding
control before staging or installing its invalid candidate.

The operator baseline is checked to length 8. Six registered mutation
configurations negate vector cancellation, permit partial vector publication,
advance cursors through physical overread, resume from the wrong checkpoint,
reuse a stale cache lifecycle, or publish partial negative authorization. All
six have retained kill evidence. The combined Apalache registry now contains
fourteen temporal mutants; the new cache publication-proof control remains
pending the final aggregate mutation rerun.

The bounded configurations are bug-finding evidence, not a proof for arbitrary
trace length. Therefore `bin/formal apalache-invariant` separately checks:

- initiation: `Init => InductiveInvariant`;
- consecution: `InductiveInvariant /\ Next => InductiveInvariant'`;
- implication: `InductiveInvariant => Safety`.

`formal/dafny/TemporalSafety.dfy` additionally expresses cache, cursor,
continuation, and telemetry states as typed predicates and proves each
transition constructor preserves the corresponding safety predicate without a
finite trace bound. Its cursor machine distinguishes proof-equal current
continuation from retained exact continuation and proves that a changed proof
never selects current.

The temporal model deliberately stops at graph selection. Item-level completed
pagination is specified separately in `formal/dafny/PageWindow.dfy`: an
authenticated logical boundary is the pair of its ordinal and external result
identity in the generated logical order. The completed-denotation path emits
the exact exclusive slice only when both values match; a mismatch is stale.
Production now implements that rule in the stable engine: `eacl.engine.stable-page/edge-page`
validates the `:stable-edge` ordinal and identity by checkpoint hit or governed
replay before slicing (the former `eacl.engine.v8/complete-logical-page` branch
was retired with the merge engine).
The request-local checkpoint store uses the shared standard LRU adapter. Its
host trace peeks without touching, validates the ordinal and external boundary,
and only then performs an identity-checked recency touch; a rejected candidate
does not become more recent. Publication is likewise skipped if deadline
expiry or cancellation has closed the cache stage. Before either a client
continuation callback or the standalone LRU can observe publication, the
stable-page semantic layer also requires the checkpoint's admitted-identity
count to be at most its configured cap (1,000,000 by default). An over-cap new
entry or replacement is dropped with no callback, LRU, or telemetry effect.
The source-shaped progress checkpoint refinement bridge exercises the cap at
that effect boundary, the rejected late-publication trace, and an unavailable
checkpoint hit returning a zero-probe, zero-touch miss.
This ordering is LRU host conformance, not
authorization authority in the semantic model, where eviction remains
arbitrary.
The Dafny function matches the host branch, but the correspondence remains a
cross-checked host-control refinement rather than a mechanized Clojure source
refinement. Demand continuations validate the same ordinal and identity in the
generated indexed continuation authority and never switch graphs.

## Reproduction

Run:

```text
bin/formal tla-typecheck
bin/formal tla-check
bin/formal apalache-check
bin/formal apalache-invariant
bin/formal apalache-mutation-control
bin/formal apalache-scheduled
bin/formal verify
```

The checked-in release evidence records the exact toolchain, bounds, and model
digests for each run. The detailed model exposes 23 transition alternatives to
the inductive checker. No unbounded claim is inferred from the finite checks;
the separate inductive run is required for that transition system.

## Claim boundary

Passing this model establishes the stated properties of the transition system,
not that a backend implements its assumptions. Backend snapshot identity,
immutability, scans, direct matches, proof completeness, and exact selection
remain adapter certification obligations. Release status must remain
`not-verified` until those obligations and the runtime correspondence gates
are complete.
