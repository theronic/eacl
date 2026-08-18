# Proposal: Hierarchical Demand-Compatible Segment Cache

## Why

EACL v8’s demand-bounded evaluation and completed-answer cache correctly avoid evaluation widening and keep the relation-version frontier proof simple. However, benchmarks show substantial structural overlap between super-user object visibility and normal-user visibility for the same permission and resource type. Intermediate plan nodes (shared accounts, orgs, or other high-fan-in resources) are repeatedly expanded under the sealed plan even though the ordered results are largely shared.

The earlier multi-hop denotation and aggressive projection-publishing paths were removed because they risked widening work beyond the request’s demand and complicated formal coherence. A new intermediate tier is needed that:

- exploits ordered segment sharing across subjects and page sizes,
- never materializes more results than some prior demand has already walked,
- remains strictly compatible with demand-bounded evaluation, sealed-plan stable order, and the existing schema/dependency frontier proof,
- avoids eager full-index materialization.

Without this, repeated multi-hop walks remain the dominant cost for recursive and high-sharing schemas even when completed-answer hits are rare.

## What Changes

- Introduce a new **hierarchical segment** tier under the existing subproblem cache.
- Segments are keyed by sealed-plan node (or path), start-set fingerprint, demand bound, and validity stamp (schema generation + dependency frontier).
- A segment stores an ordered eid prefix (stable-discovery order), optional continuation, optional intermediate references, and lightweight summaries — never an unordered dense set larger than a prior demand.
- Composition of hops is demand-aware: level-*k* segments are built only up to the active demand.
- Prefix reuse is allowed: a segment populated under demand *D* may satisfy any later demand *D′ ≤ D* with matching frontier and compatible start set.
- Different page sizes (`:first` / `:last`) share segments; only the demand number matters for reuse.
- Completed-answer and public-cursor identities remain unchanged (page size and exact bounds stay in those keys).
- Identity projections, sealed plans, and exact/proof-backed completed answers continue to work as today.
- The denotation tier config surface may be reused or renamed for segment weight budgets; no engine path will publish unrestricted full denotations.

## Capabilities

### New Capabilities

- `cache/hierarchical-segments`: demand-compatible, frontier-stamped, ordered segment storage and prefix/composition reuse for sealed-plan nodes.

### Modified Capabilities

- `cache/subproblem-cache`: gains a segment tier with explicit demand and start-set dimensions; continues to respect weight budgets and managed-proof limits.
- `engine/demand-evaluation`: may consult and deposit hierarchical segments while remaining demand-bounded; must never widen evaluation or return results beyond the request demand.
- `cache/coherence`: segment validity uses the same schema-stamp + dependency-stamp rules already proven for completed answers.

## Non-Goals

- No change to public cursor envelope formats or authentication.
- No support for arbitrary time-travel cache reuse (as-of / since / filter views still degrade to exact-only or `:cache? false`).
- No automatic inverted (resource-centric) global indexes; any resource-side sharing is limited to plan nodes the schema marks as high-sharing and is still demand-bounded.
- No background pre-warming or adaptive completion APIs.
- No requirement that every hop be cached; admission remains best-effort under weight limits.

## Impact

- **Performance**: reduced repeated index seeks and reducer bookkeeping for overlapping subject sets and varying page sizes on the same permission.
- **Correctness**: preserved by prefix-only reuse, frontier stamps, and sealed-plan order.
- **Complexity**: moderate increase in subproblem-cache surface; formal model must be extended for segment soundness.
- **Memory**: bounded by existing (or slightly extended) weight budgets; oversized segments are rejected, not swapped in unbounded fashion.
- **API**: no breaking public API changes; optional cache config keys for segment budgets.

## Risks

- Start-set fingerprint collisions or overly fine keys reduce hit rate.
- Incorrect composition order could violate stable-discovery uniqueness.
- Formal verification surface grows; segment theorems must be stated and tested before enabling by default.

## Success Criteria

- Measurable reduction in mean latency for normal-user pages after a super-user (or larger-demand) warm-up on the same schema and frontier.
- No correctness regressions under existing cache-vs-bypass and coherence test suites.
- Segments never cause a demand-bounded request to return more results than requested or to perform work beyond its demand.
- Different page sizes on the same logical query share underlying segments when demand allows.
