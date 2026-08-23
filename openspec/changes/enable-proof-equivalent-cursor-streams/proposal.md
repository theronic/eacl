## Why

Proof-equivalent cursor continuation already exists and is already complete in what it binds. The portable `eacl_c5_` envelope authenticates and encrypts nine fields: the query scope digest (operation, normalized query, limits, emission-order version), the source scope (persisted or per-connection source id, branch, lifecycle), native revision and exact locator, adapter fingerprint, identity contract, the dependency-scope digest (the canonical relation closure), the proof digest (schema generation plus scalar frontier), and the edge — which for stable and least-path plans carries the composite plan fingerprint (order ABI, rule ordinals, rank certificate, recursiveness), traversal direction, and the boundary's ordinal and identity. The continuation decision is the generated `DecideContinuation`, and the boundary is validated by replay or by keyset seek before any page publishes. Query internalization, object identity, plan and order ABI, direction, limits, and the boundary — the items the earlier draft of this change proposed to add — are all there.

What remains is alignment and evidence:

1. The cursor derives its own digest of the frame descriptor (`canonical-digest` of `{:schema-stamp :dependency-stamp}` inside a second digest) instead of consuming the request's `frame` and `lineage` values; after `introduce-proof-carrying-semantic-equivalence` those are the one reuse identity and the cursor should use them directly.
2. The evidence that equal frames imply an equal ordered stream is split: `ScalarFrontierCoherence.dfy` proves equality for any deterministic evaluator of the closure's slices, and the stable-discovery leaves prove the order is a pure function of plan and snapshot, but no stated lemma connects them by showing the reducer reads only the closure's slices. The dependency-closure completeness guard at plan compilation is that fact; it needs to be the stated bridge.
3. Exact fallback at the cursor's own basis is accepted by identity only in the Datomic client (`exact-fallback-decision`, because `:eacl/relation-version` is `:db/noHistory` and unreadable through `as-of`); the shared relay requires frame equality there. `add-authorization-views` deletes the Datomic client, so the rule must move.
4. Under the constant default lifecycle, restart rejection for DataScript and in-memory Datahike rests on the per-live-source id alone; no conformance case pins it for cursors.
5. Legacy envelope version 11, the unreachable `:conflict` continuation branch, and a vacuous `:cursor-recovery` assertion remain in production and test code.

## What Changes

- Cursor continuation consumes the request context's `lineage` and `frame` directly; the envelope stores the canonical frame and the closure digest rather than a digest of a digest; `DecideContinuation`'s proof inputs are the canonical encoding of `[lineage frame closure-digest]`. No new certificate, envelope domain, or kernel.
- Exact fallback accepts the original basis by identity (source scope, lifecycle, revision, locator) regardless of whether a frame can be read there; frame comparison is for *other* bases only.
- Formal bridge: a stable-discovery leaf states that every fetch descriptor issued by the reducer names a relation in the sealed plan's closure, and that therefore equal frames imply equal transitions, emissions, order, and boundaries; the assurance matrix cites it together with the scalar-frontier theorem for proof-equivalent continuation. Mutation controls for it execute against the engine.
- Conformance: restart rejection under the constant default lifecycle and a shared keyring for every non-durable source; continuation across unrelated writes, rejection on relevant writes, and exact fallback by capability for every backend; `:populate-cache? false` leaves cursor validation unchanged.
- Remove envelope version 11 handling, the unreachable `:conflict` branch, and the vacuous `:cursor-recovery` assertion.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-stable-pagination`: continuation binds lineage and frame; exact fallback by identity; conformance matrix.
- `cursor-dependency-validity`: frame-scoped continuation identity; schema generation from the certified operation.
- `backend-native-revision-consistency`: continuation across unrelated writes is lineage-scoped; non-durable sources continue only within one live source.
- `cross-backend-conformance`: cursor continuation conformance for every backend.
- `formally-verified-authorization-engine`: the reducer read-scope bridge lemma.

## Impact

- Modules: `eacl.relay` (dependency context, continuation decision inputs, exact fallback), `eacl.cursor` (envelope version), `eacl.request.context` (cursor proofs memo uses `frame`), `formal/stable-discovery` (one leaf plus bridge), conformance suites.
- Depends on `introduce-proof-carrying-semantic-equivalence` and `add-authorization-views`. Checkpoints remain subordinate and are `enable-proof-equivalent-checkpoints`.
- Envelope version increments; v8 is unreleased, so no cursor migration exists.

## Related changes

Already applied or archived; this change modifies their outcomes rather than their artifacts:

- `archive/2026-08-15-redesign-cross-backend-freshness-cache`: origin of the dependency-scoped cursor proof (`:dependency-scope-digest`, `:proof-digest`) and the exact-fallback contract; the cursor now carries the frame directly.
- `sync-datomic-exact-snapshots` (applied): Datomic exact selection with bounded targeted sync, on which exact fallback by identity relies.
- `adopt-stable-discovery-enumeration` (in progress) and `acyclic-keyset-pagination` (in progress): the `:stable-edge` and `:least-path-edge` boundaries, the composite plan fingerprint, and the boundary theorems this change composes with the frame rule.
- `eliminate-authorization-request-amplification` (applied): the AEAD `eacl_c5_` envelope whose version increments here.
