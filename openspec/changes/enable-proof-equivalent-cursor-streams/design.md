## Context

See `proposal.md`. The current shared continuation path (`eacl.relay/prepare-page-query`): authenticate and decrypt both tokens; compute `scope-matches?` and `expired?`; select the snapshot for the request's consistency; run the generated `DecideContinuation` over `(authenticated?, scope-matches?, expired?, source identity, proof digests, graph code, exact selection)`; on `:current` continue on the selected snapshot; on `:snapshot-unavailable` check the freshness floor, then — only for history-capable adapters — acquire the cursor's exact basis through the source and decide again; otherwise the typed stale outcome. The boundary is then internalized and validated by the engine (`validate-stable-bound!`, `validate-least-path-bound!`, relationship-index comparison) and by replay or checkpoint before publication.

The ordered stream for one plan and one basis is already proved deterministic: least-path order is a pure function of plan and snapshot (`LeastPathOrder.dfy`, `LeastPathEnumeration.dfy`, `LeastPathResume.dfy`); stable first-discovery order is deterministic for one basis, plan, and adapter scan contract, with resume equal to the continuation (`ReducerCheckpoint.dfy`, `RuntimeCheckpointComposition.dfy`). The scalar-frontier theorem says any deterministic function of the closure's slices agrees at equal-frame bases. The missing statement is that the reducer *is* such a function — that it reads nothing outside the closure. Plan compilation asserts exactly that (`managed-reuse-certification`: every relation id referenced by compiled rules is in the closure), and identity conversions are re-resolved per request, so it is true; it is not yet stated where the assurance matrix can cite it.

## Goals / Non-Goals

**Goals:**

- One reuse identity for cursors: the request context's `lineage` and `frame`, shared with answers and checkpoints.
- Exact fallback at the original basis by identity, on every backend that can select it.
- The stream theorem stated as one bridge over existing leaves, with an executable mutation control.
- Conformance pins for restart rejection, unrelated/relevant writes, and capability-dependent exact fallback.

**Non-Goals:**

- New cryptography, envelope domains, certificates, or kernels. The envelope's authenticity and confidentiality assumptions stay in `cryptographic-assumptions.md`.
- Session-scoped lineage. Non-durable sources are already isolated by their per-live-source id; durable sources continue across restart.
- Legacy or compatibility cursor paths. v8 is unreleased.
- Checkpoint acceptance. A valid cursor makes replay correct; private state is `enable-proof-equivalent-checkpoints`.

## Decisions

### 1. The cursor carries the frame

`build-dependency-context` stores `:lineage`, `:frame` (`{:schema-generation :dependency-stamp}`), and `:closure-digest` (the canonical relation-id vector digest) from the request context; the second-level `continuation-proof` digest is deleted. `DecideContinuation` keeps its signature; `currentProof`/`cursorProof` are the canonical encoding of `[lineage frame closure-digest]`, and `sourceIdentity` stays the execution identity fields. The closure digest is redundant given schema-generation equality — the closure is a deterministic function of schema semantics and query — and is retained as a cheap guard against a dependency-extraction regression, not as evidence.

The `:scope` digest continues to exclude the schema generation so that a schema change reaches frame comparison and exact fallback rather than failing as a scope mismatch.

The proof covers the internal ordered stream, not mutable external identity.
Proof-equivalent continuation therefore also requires an immutable external
identity contract. The built-in `:eacl/id` codec declares that supported-writer
premise; a custom codec must opt in with `:identity-immutable? true` in addition
to its portable deterministic fingerprint. Without it, the cursor carries the
exact-basis frame and cannot cross a native revision. This prevents a public ID
delivered on an earlier page from being reassigned to a future internal entity
and appearing twice in one walk.

### 2. Exact fallback by identity

When the selected basis's frame differs (or cannot be read), the request's freshness floor permits the original basis, and the source supports exact selection, the relay acquires the cursor's original basis and continues **by identity**: equal source scope, lifecycle, revision, and locator. No frame is read at the original basis. This generalizes the Datomic-only `exact-fallback-decision` (needed because `:db/noHistory` stamps are unreadable through `as-of`) and removes a second `DecideContinuation` call whose only honest answer at the original basis is identity.

### 3. The read-scope bridge

A stable-discovery leaf, `ReducerReadScope.dfy`, states: every scan descriptor the reducer issues for a sealed plan names a relation in that plan's closure; hence the reducer's transition function, emissions, order, and boundary positions are a function of the plan and the closure's slices. Combined with `EqualScalarProofPreservesEveryDeterministicDenotation`, equal frames at two bases of one lineage imply equal complete ordered streams, and the existing boundary theorems then give the exact suffix (forward) or prefix (reverse). The executable side is the compile-time closure-completeness guard plus a mutation control that compiles a plan referencing a relation outside its closure and must be rejected. Least-path plans have the same property by construction (their coordinates are scans over plan relations).

### 4. Conformance

For each backend, with the constant default lifecycle and a shared keyring: a cursor minted before an unrelated write continues with no duplicate or omission against the cache-free oracle; a cursor minted before a relevant write is rejected on the current basis and continues on the original basis only where exact selection is supported; a DataScript, in-memory Datahike, or Datomic `mem` source recreated with identical content rejects the old cursor for scope mismatch before any frame read; Datalevin and durable Datahike/Datomic accept it after restart. `:populate-cache? false` does not change any of these outcomes.

### 5. Cleanup

Envelope version 11 decoding, `legacy-cursor-scope`, the `:conflict` decision branch that `DecideContinuation` never produces, and the `:cursor-recovery` assertion that nothing writes are removed; `CursorConflict` is removed from `PageWindow.dfy` or given a producer, and the manifest is re-pinned.

## Rejected alternatives

- **A purpose-specific cursor-stream certificate with its own domain tag and kernel.** Every field it would bind is already authenticated in the envelope; the theorem it would carry is the frame theorem plus the read-scope bridge; a second kernel would generate a comparison of the same canonical data.
- **Session-scoped cursor lineage.** Isolation of non-durable sources is already provided by their per-live-source id; applying it to durable sources would invalidate every cursor on every restart for no soundness gain.
- **Digest-only acceptance with rederivation.** The envelope has room for the canonical frame; digests remain only for the relation-id vector, which can be large.
- **Legacy exact-only path for prior envelope versions.** Nothing is released.

## Risks / Trade-offs

- **[Closure digest becomes vestigial]** → kept as a regression guard with a one-line justification; it costs one digest per cursor mint.
- **[The physical schemas do not enforce `:eacl/id` immutability]** → immutability is an explicit supported-writer premise and a configurable cursor eligibility gate; deployments that permit identity mutation set `:identity-immutable? false` and retain exact-basis cursor semantics.
- **[Exact fallback by identity skips frame reads at the original basis]** → the original basis is the cursor's own immutable value; identity is the strongest possible evidence there.
- **[Dafny leaf adds verification time]** → the bridge is small and joins the ten-second stable-discovery gate; its obligation count is pinned.
