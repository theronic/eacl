## Why

EACL already accepts static security keyrings with an active key identifier, but changing that configuration normally requires replacing clients or restarting Peers. That turns routine cryptographic key rotation into a coordinated outage risk and makes it too easy to retire an old key before every Peer can verify artifacts minted during the overlap.

This phase adds one external, live, atomic keyring control surface. Library consumers remain responsible for obtaining secrets from their secret manager and distributing updates to every Peer; EACL owns only the in-process state transition, artifact key selection, fail-closed behavior, cache handling, and operational guidance.

## What Changes

- Order this as Phase 4 after `2026-09-04-03-enable-qualified-relationship-evaluation` for the release series, while keeping the keyring implementation independent of Relationship qualifier semantics and database storage.
- Add one public, non-durable `SecurityKeyring` controller abstraction that may be shared by multiple EACL clients on one Peer. The primary controller is initialized from existing `:security-key`, `:security-keyring`, and `:security-kid` configuration; an optional dedicated Zed-token controller is initialized from the existing Zed-token key options and otherwise reuses the primary controller through existing domain separation.
- Add an atomic full-state replacement operation with an expected generation plus convenience operations to add an inactive verification key, activate an installed key for new artifacts, and retire a non-active key.
- Use one immutable keyring snapshot per encode or decode operation. New artifacts use exactly the active key; existing artifacts may be accepted by any currently retained key named by their authenticated key identifier.
- Define the zero-downtime rollout as distribute, observe, activate, overlap, and retire. A new key cannot become active until it is installed, and an active key cannot be retired without atomically selecting another installed key.
- Keep secret material outside Datomic, Datahike, DataScript, Datalevin, cache snapshots, cursor payloads, metrics, logs, errors, and diagnostics. Status APIs expose only key identifiers, the active identifier, generation, and safe counts.
- Require globally unique key identifiers per key epoch and reject any reintroduction of a retired identifier within one controller lifetime; documentation prohibits identifier reuse across restarts because reviving an id can revive old artifacts.
- Require cursor and authenticated portable-cache formats to carry an authenticated primary-controller key identifier, and Zed tokens to carry an authenticated identifier from their selected primary-or-dedicated controller. Cursor continuation/cache entries also retain the minting key identifier and cannot resume after that key has been retired.
- Treat an unavailable cursor/token key as a typed fail-closed request error with no silent restart. Treat an authenticated cache entry whose key is unavailable as a cache miss followed by ordinary selected-snapshot evaluation.
- Invalidate process-local cursor/continuation/rendered-page state and externally authenticated/imported cache records associated with a retired key, while leaving locally computed authorization caches intact. Artifact key-presence validation or an equivalent synchronous trust-epoch detachment remains the correctness boundary if targeted cleanup is delayed.
- Preserve authorization, source-lifecycle, schema, Relationship, and proof-cache semantics across key addition or activation. Cryptographic rotation changes which artifacts can be authenticated; it does not create a new authorization graph revision.
- Document the special case of non-expiring cursors: an old verification key must be retained indefinitely or its retirement intentionally invalidates those cursors. Recommend bounded cursor TTL where operators require a finite retirement deadline.
- Add deterministic state-machine, concurrency, overlap, partial-rollout, retirement, restart, cache, and cross-Peer tests without adding a runtime reference interpreter, distributed coordinator, secret watcher, or per-request control-plane call.

## Capabilities

### New Capabilities

- `live-security-keyring-rotation`: externally driven, atomic in-process key installation, activation, overlap, retirement, status, concurrency, and operational rollout semantics.

### Modified Capabilities

- `cursor-token-handling`: authenticated key identifiers, live-ring decode behavior, retired-key failure, cursor-cache key affinity, and non-expiring-cursor retirement guidance.
- `backend-unification`: one shared runtime keyring controller and equivalent rotation behavior across bundled adapters.
- `modular-backend-workspace`: shared cryptographic format service, dependency isolation, non-durable secret handling, and consumer-facing rotation documentation.

## Impact

The change affects client construction, runtime ownership, secure-format key derivation, cursor/Zed/cache envelopes, continuation and rendered-page stores, cache import/export, telemetry, public API documentation, backend conformance suites, and deployment runbooks. It adds no database schema or migration and must not require restarting EACL clients or Peers.

EACL does not become a secret-distribution system. A consumer may drive the controller from Kubernetes Secrets, Vault, AWS Secrets Manager, an internal control plane, or another authenticated channel, but no provider-specific watcher or network endpoint is part of this change.
