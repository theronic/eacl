# Security keys and live rotation

EACL v9 accepts externally supplied keys through an opaque, in-memory
`SecurityKeyring`. Clients sharing a controller observe its updates without
being recreated. Cursors, Zed tokens, and authenticated cache snapshots each
name one authenticated key ID. Their cryptographic domains remain separate.

**Cursors have no age expiry by default. Lossless resume therefore requires
indefinite retention of their old keys.** Retirement deliberately invalidates
those cursors. Configure a finite `:cursor-ttl-seconds` before issuing cursors
whose retirement needs a bounded retention window. Adding a TTL later does not
add an expiry to previously issued non-expiring cursors.

## Key material and ownership

Generate at least 32 bytes of cryptographically random key material outside
EACL. The library validates representation and length; it cannot measure the
entropy of a supplied secret. Byte arrays, byte sequences, and UTF-8 strings
are supported. A string is used as UTF-8 bytes, not implicitly decoded from
Base64. Decode your secret manager's representation in application code.

Use a fresh, globally unique public ID for each new key. An accepted ID cannot
change material, and a retired ID cannot return during a controller's lifetime.
Maintain that uniqueness across application restarts as well. Secret IDs should
be labels, never the secret itself.

The controller copies supported mutable byte inputs before publication. Key
updates are not durable: the application owns secret distribution, durable
configuration, and restart recovery. EACL neither polls a secret manager nor
performs a network fetch during authorization. Without explicit primary keys,
a private controller uses the process-local random default key.

Retired root bytes and prior derived-key caches leave the current controller
state. In-flight operations may still hold an older snapshot. Garbage-collected
JVM and JavaScript runtimes cannot guarantee zeroization; immutable strings,
application references, heap dumps, and runtime copies remain application
security concerns.

Accepted-key count is capped at 64, retired IDs at 65,536, encoded IDs at 1,024
bytes, and root material at 4,096 bytes. `:max-keys` and `:max-retired-kids` can
lower the count ceilings. Derived-key caches are bounded to 256 entries per
generation. Plan a fresh externally coordinated configuration before exhausting
the retired-ID ceiling; do not recycle an old ID.

## Configuration scopes

| Scope | Live option | Static options |
|---|---|---|
| Cursors and authenticated cache snapshots | `:security-keyring-controller` | `:security-key` or `:security-keyring`, plus optional `:security-kid` |
| Dedicated Zed tokens | `:zed-token-keyring-controller` | `:zed-token-key` or `:zed-token-keyring`, plus optional `:zed-token-kid` |

Static options construct private controllers. With no dedicated Zed options,
Zed tokens share the primary controller under their own derivation domain.
Dedicated options create an independent scope and require dedicated material.
A controller cannot be combined with any static option in the same scope,
including just an explicit key ID. A static key and static ring also conflict.
The default static ID is `:default`; an explicitly selected ID must be present.

This example receives secret bytes as arguments. Its one-day TTL applies only
to newly issued cursors; retain keys for any older non-expiring cursors
indefinitely if they must remain resumable.

```clojure
(require '[eacl.core :as eacl]
         '[eacl.datascript.core :as datascript])

(defn configured-client [conn initial-id initial-root]
  (let [ring (eacl/security-keyring
              {:keys {initial-id initial-root}
               :active-kid initial-id})]
    {:ring ring
     :client (datascript/make-client
              conn {:security-keyring-controller ring
                    :cursor-ttl-seconds 86400})}))
```

Use the corresponding backend's `make-client` for Datomic, Datahike, or
Datalevin. Datalevin also requires its documented durable source lifecycle and
revision-watermark options. Sharing a controller does not share databases,
authorization caches, or source identities.

## Controller operations

All operations below are in `eacl.core`.

| Operation | Contract |
|---|---|
| `security-keyring` | Construct from `{:keys {kid bytes} :active-kid kid}`. |
| `security-keyring?` | Identify a public controller. |
| `security-keyring-status` | Return generation, active ID, accepted IDs, and retired IDs. |
| `add-security-key!` | Install an inactive key; identical accepted material is idempotent. |
| `activate-security-key!` | Select an already accepted key; selecting the active ID is idempotent. |
| `retire-security-key!` | Retire an inactive key; an already retired ID is idempotent. |
| `replace-security-keyring!` | Atomically replace the complete desired state at `:expected-generation`. |

Successful state changes advance the generation once. Full replacement also
advances it when the desired state equals the current state. Convenience
operations retry competing updates at most 32 times; stale complete-state
replacement returns `:eacl.keyring/conflict` with safe current status. Validation
returns `:eacl.keyring/invalid` with a closed reason, including
`:active-key-retirement`, `:active-key-unavailable`, `:retired-key-id`, and
`:key-id-reuse`. Configuration errors remain `:eacl/invalid-config`.

A full replacement can remove the old active key while selecting another
accepted key in the same atomic update. Removing the active key without a valid
replacement is rejected. Consumers never observe a partially installed ring.

Status and controller printing omit roots and private fingerprints. Use returned
status for application-controlled audit events and authenticated operational
endpoints. EACL does not emit secret-bearing rotation events or metric labels.
Do not include application input maps or raw exceptions from secret providers
in those logs.

## Two-Peer rollout

Perform each step for every independently hosted controller, including a
separate dedicated Zed ring when configured. **Non-expiring cursors require
indefinite old-key retention for lossless resume.** For finite-TTL cursors,
retain the old key beyond the last old-key issuance plus the longest relevant
artifact TTL and rollout/clock margin. Authenticated cache snapshots have no
age-expiry promise; their retirement safely loses cache reuse.

1. Generate a fresh ID and root outside EACL and securely distribute them.
2. Install the new key as inactive on every Peer.
3. Observe safe status through the application's authenticated control plane.
   Confirm every Peer accepts the new ID before activating any Peer.
4. Activate the new ID on each Peer. Activation skew is safe while both keys
   remain accepted everywhere.
5. Observe that every Peer has switched, record the last possible old-key
   issuance, and maintain the required overlap.
6. Retire the old inactive ID on every Peer. Confirm it is absent from accepted
   IDs and present in retired IDs.

This executable in-process drill models two independently configured Peers.
Production distribution and acknowledgements belong to the application. It
stops before retirement: non-expiring cursors require indefinite retention, and
finite-TTL deployments must establish their overlap deadline externally.

```clojure
(defn distribute-and-activate! [peer-a peer-b new-id new-root]
  (doseq [peer [peer-a peer-b]]
    (eacl/add-security-key! peer new-id new-root))
  (doseq [peer [peer-a peer-b]]
    (assert (contains? (:accepted-kids (eacl/security-keyring-status peer))
                       new-id)))
  (eacl/activate-security-key! peer-a new-id)
  ;; Both Peers accept both IDs throughout activation skew.
  (eacl/activate-security-key! peer-b new-id)
  (mapv eacl/security-keyring-status [peer-a peer-b]))
```

When your externally established overlap deadline has passed, retire with
`(eacl/retire-security-key! peer old-id)` on each Peer. For non-expiring cursors,
this action intentionally invalidates old cursors; retaining their key is the
only lossless alternative.

If a Peer missed distribution, artifacts from an activated Peer fail there with
`:security-key-unavailable`. Distribute the missing key under its original ID
before retrying the request. If activation must be rolled back, reactivate the
old ID while it remains accepted and continue accepting the new ID for artifacts
already issued under it. A retired ID cannot be restored to that controller;
after retirement, recover by retaining the new configuration and restarting
invalidated pagination explicitly at the application boundary.

Each protected operation captures one immutable controller generation. An
operation that captured a key before retirement may finish under that snapshot.
Every operation started after retirement returns observes its removal. Adding
or activating keys changes no database basis, source lifecycle, Relationship
generation, qualifier generation, or authorization proof.

## Errors and optional cache data

| Artifact | Unavailable named key |
|---|---|
| Caller-supplied cursor | `:eacl.pagination/invalid-cursor`, reason `:security-key-unavailable` |
| Caller-supplied Zed token | `:eacl/invalid-zed-token`, reason `:security-key-unavailable` |
| Authenticated cache snapshot | `{:restored? false :cache-miss? true :reason :security-key-unavailable}` |

Cursor age expiry remains `:eacl.pagination/expired-cursor` with reason
`:expired`. Signature failures remain distinct from unavailable keys. Cursor
and consistency errors preserve the caller's requested contract: application
code decides whether to start a new traversal. Optional cache data can miss and
recompute against the selected immutable snapshot.

Every backend provides `export-authenticated-cache-snapshot` and
`restore-authenticated-cache-snapshot!`. Supply `{:max-entries n}` and optionally
`:maximum-size` to lower the 16 MiB encoded-byte ceiling. The envelope
**authenticates** cache contents; the application controls storage confidentiality.
Only locally computed entries are exported. Imported entries keep their verifying
controller and key ID privately, and are omitted from re-export so a new signature
cannot extend their original trust. Values computed using imported subproblems
are not published into local answer, range, continuation, or rendered-page caches.

Unknown, retired, malformed, or invalid authenticated cache artifacts are misses.
A failed restore leaves the existing client caches intact. Successful restore
atomically installs the reconstructed snapshot. Subsequent retirement invalidates
only imported trust; answers computed locally after restore remain reusable.

Retirement itself does not scan client caches. On the next protected-page or
codec use, bounded private stores remove retired cursor state and unreachable
key contexts. Imported entries detach on lookup. Cleanup is best effort: late
publishers may leave unreachable bounded entries until eviction. Mandatory
key acceptance and cursor-cache policy identity enforce retirement even if
physical cleanup is skipped or races an in-flight request.

The older decoded `export-cache-snapshot` / `restore-cache-snapshot!` boundary
remains host-owned trust and supports existing applications.

Cryptographic limits still apply across activations and controller instances:
rotate a cursor encryption root before 2^32 encryptions under that root. EACL does
not count those invocations. See [cryptographic assumptions](../formal/verification/cryptographic-assumptions.md)
and [cache operations](cache.md) for the associated contracts.
