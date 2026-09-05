## Context

See `proposal.md` for motivation. EACL already has static keyring configuration and key identifiers for protected formats, but the normalized keys are captured during client construction. The current documentation describes staged rotation, yet operators cannot update a running Peer through one supported public control surface.

The primary security keys protect cursor authenticity/confidentiality and portable-cache authenticity. EACL may also use a dedicated Zed-token ring; when absent, the Zed-token domain derives from the primary ring as today. None of these keys are authorization data or suitable for the EACL database. Rotation must therefore be externally orchestrated but locally linearizable, must permit old and new keys to overlap, and must define different failure behavior for user-supplied cursors/tokens versus optional caches.

The existing cursor contract permits no TTL by default. Consequently, no implementation can both retire a key after a finite overlap and promise that every cursor minted under that key remains resumable. This design makes that trade-off explicit rather than inventing an unsafe automatic retirement heuristic.

## Goals / Non-Goals

**Goals:**

- Let a consumer add, activate, and retire security keys on a running Peer without replacing clients or interrupting authorization service.
- Give every encode/decode operation one immutable, internally consistent keyring snapshot.
- Mint only with the active key and verify with any retained key selected by authenticated key identifier.
- Make partial rollout, stale control-plane writes, key-id reuse, active-key retirement, and cache/cursor behavior deterministic and fail closed.
- Let one controller feed multiple clients on one Peer while preserving adapter/module isolation.
- Keep authorization hot paths free of network calls, database reads, distributed consensus, runtime reference-model evaluation, and keyring-wide cryptographic trial loops.
- Supply a concrete zero-downtime deployment runbook and observability that never exposes key material.

**Non-Goals:**

- Fetching keys from a secret manager, exposing an administrative HTTP endpoint, discovering Peers, or coordinating rollout acknowledgements across a cluster.
- Persisting key bytes, encrypted key bytes, provider handles, or a desired keyring in EACL's databases.
- Keeping non-expiring artifacts valid after the operator deliberately retires their key.
- Rotating datastore credentials, object-codec secrets, source-lifecycle identity, or unrelated application keys.
- Changing authorization results, cache dependency proofs, graph revisions, Relationship storage, Caveat semantics, or temporal semantics.
- Proving cryptographic primitive security inside EACL; the existing cryptographic assumptions and vetted runtime primitives remain the trust boundary.

## Decisions

### D1. Add one mutable controller whose values are immutable snapshots

Introduce one backend-neutral controller abstraction, with any number of explicitly scoped instances, conceptually shaped as:

```clojure
{:generation  17
 :active-kid  :eacl-2026-10
 :keys        {:eacl-2026-09 normalized-root-key-1
               :eacl-2026-10 normalized-root-key-2}
 :retired-kids #{:eacl-2026-08}}
```

The controller owns an atomic reference to a validated immutable state. Secret bytes are private implementation values; public status returns only:

```clojure
{:generation 17
 :active-kid :eacl-2026-10
 :accepted-kids #{:eacl-2026-09 :eacl-2026-10}
 :retired-kids #{:eacl-2026-08}}
```

A controller may be passed to multiple clients on one Peer:

```clojure
(def primary-ring
  (eacl/security-keyring
   {:keys {:eacl-2026-09 old-root}
    :active-kid :eacl-2026-09}))

(def acl
  (make-client conn {:security-keyring-controller primary-ring}))
```

For source compatibility, static `:security-key`, `:security-keyring`, and `:security-kid` create a private primary controller. Existing dedicated Zed-token key options create a private dedicated controller; a new `:zed-token-keyring-controller` permits live rotation of that separate ring. When neither dedicated static options nor a dedicated controller is supplied, Zed tokens reuse the primary controller with their existing distinct derivation domain. Supplying both a controller and static material for the same scope is rejected as ambiguous.

The existing cursor error category remains `:eacl.pagination/invalid-cursor`;
unknown/retired ids add reason `:security-key-unavailable`. Zed tokens retain
`:eacl/invalid-zed-token` with the same reason. This preserves current handler
compatibility while distinguishing retirement from authentication and age expiry.
The inventory in `formal/security/inventory.md` records the public API and bounds.

*Alternative considered:* mutate each client independently. Rejected because applications commonly hold several clients/caches on one Peer and could accidentally leave them on inconsistent rings.

### D2. Make complete-state compare-and-set the primitive

The authoritative public mutation is a full desired-state replacement with an expected generation:

```clojure
(eacl/replace-security-keyring!
 keyring
 {:expected-generation 17
  :keys {:eacl-2026-09 old-root
         :eacl-2026-10 new-root}
  :active-kid :eacl-2026-10})
```

A stale expected generation fails with a typed conflict and reveals only the current generation/status. Convenience functions `add-security-key!`, `activate-security-key!`, and `retire-security-key!` are bounded CAS loops over the same validator, not separate state machines.

The validator enforces:

- the ring is non-empty;
- every key id is canonical, bounded, and unique;
- every key meets the existing minimum entropy/length contract;
- `active-kid` exists in `keys`;
- an active key is not removed unless another installed key becomes active in the same replacement;
- accepted-key and retired-id counts stay within explicit hard/configured ceilings;
- a retired key id cannot be reintroduced during the controller lifetime, even with the same material, because doing so would revive old artifacts;
- status, errors, and equality diagnostics never include secret bytes.

*Alternative considered:* independent add/remove/current atoms. Rejected because readers could observe an active id without its key or a removed key still selected for minting.

### D3. Linearize encode and decode on one state snapshot

Every mint or decode captures the controller state once:

```text
mint:
  snapshot := ring.state
  key := snapshot.keys[snapshot.active-kid]
  derive domain key
  emit authenticated envelope naming snapshot.active-kid

decode:
  parse bounded envelope and obtain kid
  snapshot := ring.state
  key := snapshot.keys[kid] or fail
  authenticate before decoding protected payload
```

No request iterates over all keys. The visible key id is authenticated as associated data or inside the authenticated envelope, so changing it cannot redirect validation without detection.

A concurrent operation that captured the old state before retirement may complete and is linearized before the retirement. Once `retire-security-key!` returns, every newly started operation observes a state without that key.

Derived domain keys may be cached per `[generation kid domain format-version]` inside the controller. Replacing the state drops unreachable derived-key entries. Key activation does not clear authorization caches because their denotations are unchanged.

### D4. Use distribute, activate, overlap, retire

The documented cluster rollout is:

1. **Generate externally:** create a new random root key and globally unique key id outside EACL.
2. **Distribute:** add the new key as accepted but inactive on every Peer.
3. **Observe:** verify every Peer reports the expected generation and accepted key id through the application's authenticated control plane.
4. **Activate:** select the new key for minting on every Peer; old keys remain accepted.
5. **Overlap:** retain the old key for at least the maximum age of artifacts that must remain valid, plus rollout/clock margin.
6. **Retire:** remove the old key on every Peer and verify its identifier is absent.

A Peer that has not received the new key cannot decode artifacts minted by a Peer that has activated it. EACL surfaces that error; it cannot repair a bad rollout without receiving the secret. The operational API therefore makes install and activation separate by design.

For non-expiring cursors, the overlap has no finite safe upper bound. Operators must either retain the old key, configure a finite cursor TTL before relying on bounded rotation, or accept deliberate cursor invalidation at retirement.

### D5. Keep key material external and status non-secret

No keyring update writes any EACL database datom, source token, cache snapshot, cursor payload, audit event, metric label, exception data, or log line containing raw/encoded key material. EACL may emit safe events containing operation, key id, generation, success/failure category, and counts.

The controller accepts in-memory key material supplied by the application. Provider-specific handles are outside the portable API unless they can synchronously yield the existing normalized root-key representation without a per-request remote call.

Best-effort cleanup may discard private references to retired arrays and derived keys, but the documentation must not promise guaranteed JVM memory zeroization for immutable strings or garbage-collected objects. Examples use byte material or secret-manager outputs rather than source-code literals.

### D6. Tag every protected artifact and related cursor cache state with its key id

Every cursor and portable authenticated cache entry must select exactly one primary-controller key by authenticated `kid`. Every Zed token selects exactly one key from its configured dedicated controller or, when none exists, the primary controller under the Zed derivation domain. Formats that already carry a key id retain it; any remaining implicit-key path is upgraded rather than attempting every ring key.

Cursor-related process-local entries carry the minting token's key id:

```clojure
{:cursor-identity ...
 :security-kid :eacl-2026-10
 :continuation ...}
```

Resume first authenticates the supplied cursor against the current ring, then requires any found continuation/rendered-page entry to name the same retained key id. Retirement makes entries ineligible synchronously; the next use of a bounded private store performs targeted eviction by key id. Missed or racing eviction cannot authorize reuse because the current ring/key-id check is mandatory at the external cursor boundary.

Portable completed-cache entries and private entries whose trust originated only from such an imported artifact retain the verifying key id/trust epoch. If that key is unavailable they are untrusted optional data and become misses. Locally computed authorization entries derive correctness from the selected snapshot/proofs rather than a transport key and remain reusable. A cursor or causal token supplied by the caller is part of the requested consistency/pagination contract and therefore fails loudly rather than falling back to another snapshot or first page.

### D7. Key rotation does not rotate authorization identity

Adding or activating a key in either controller scope does not change:

- selected database basis;
- source lifecycle;
- schema generation;
- Relation generations;
- qualifier generation;
- authorization semantic key;
- result ordering.

It may change the byte representation and key id of newly minted artifacts. Existing entries remain usable while their key remains accepted. Retirement removes cryptographic acceptability only; it does not invalidate or recompute authorization data that can be safely recomputed from the selected snapshot.

This separation avoids turning routine key rollout into a whole-client authorization-cache flush. Retirement synchronously detaches or makes ineligible cursor state and externally authenticated trust under the retired id; targeted physical cleanup is operational hygiene and memory reclamation. Locally computed answers are unaffected.

### D8. Verify the state machine without a production shadow interpreter

Add a small pure transition oracle used only by tests. Generate sequential and concurrent traces of add, activate, replace, retire, mint, decode, cache lookup, and cursor resume; assert linearizable outcomes against the oracle and deterministic killed controls.

The production request path contains only:

- one atomic state read;
- one direct key-id map lookup;
- normal domain-key derivation/cache lookup;
- existing cryptographic verification.

No runtime model comparison, ring-wide fallback loop, Peer coordination check, database check, or network call is introduced to satisfy verification.

## Risks / Trade-offs

- **[Partial rollout causes temporary decode failures]** → Separate install from activation, provide generation/status APIs, document cluster acknowledgements, and keep old/new overlap.
- **[Non-expiring cursors prevent finite lossless retirement]** → State this explicitly; require finite TTL or intentional invalidation when operators need a bounded retirement date.
- **[A retired key id is reused]** → Reject every in-lifetime reintroduction and document globally unique epoch identifiers across restarts so retired artifacts cannot be revived.
- **[A stale control-plane write removes a newer key]** → Require expected-generation CAS for full replacement and return a typed conflict.
- **[Concurrent retirement races with decode]** → Linearize each operation on one immutable state snapshot; after retirement returns, later operations cannot obtain the key.
- **[Targeted cache cleanup is incomplete]** → Make current key presence or an equivalent synchronously advanced trust epoch authoritative; externally authenticated optional data misses and cursors error, while local computed answers remain independent.
- **[Secrets leak through observability]** → Closed status/error/event schemas contain identifiers and counts only; add redaction tests over logs and ex-data.
- **[Shared controller broadens blast radius]** → Sharing is explicit and scoped to clients that deliberately receive the same controller; static options still create private controllers.

## Migration Plan

1. Add the controller and pure state validator while preserving static construction through a private controller.
2. Upgrade/confirm every protected format carries an authenticated key id and add per-key cache metadata.
3. Route mint/decode operations through atomic controller snapshots.
4. Add public replacement/convenience/status APIs and safe events.
5. Add per-key deferred cleanup and all cross-backend/concurrency tests.
6. Publish the deployment runbook and examples for external secret watchers/control planes.
7. Exercise a two-Peer old/new overlap, activation skew, and retirement drill before release.

There is no database migration or rollback. A deployment may revert to static configuration only after preserving every key still needed to decode outstanding artifacts. Removing the controller while dropping an old key intentionally invalidates those artifacts.
