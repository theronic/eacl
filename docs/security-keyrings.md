# Security keys and live rotation

EACL cursors are encrypted and authenticated so you can pass them through an
untrusted browser. They contain traversal positions and query details.
Encryption hides those details; authentication prevents a client from changing
a cursor's position or reusing it for a different query. The permission query
still determines which resources the caller may see.

In a load-balanced application, a cursor created by one Peer may be sent to
another Peer on the next request. Configure the same keys on those Peers so
each can read the other's cursors. Shared keys are also needed to verify Zed
tokens and authenticated cache snapshots across processes or restarts.

The default keys are random and process-local. They are useful for a local
example, but do not survive a restart or work across independent Peers.

A keyring holds the keys EACL accepts and identifies the active key used for
new artifacts. A `SecurityKeyring` controller lets you update that set without
recreating clients. Your application must distribute and persist the keys;
EACL does not fetch them from a secret manager.

## Cursor encryption

The `eacl_c6_` cursor format uses AES-256-CTR encryption and HMAC-SHA-256
authentication with separately derived keys and a random 96-bit nonce. EACL
verifies authentication before decrypting or parsing the payload. This
provides confidentiality and tamper detection; encryption alone would not
prevent cursor modification.

Rotate a cursor encryption root before 2^32 encryptions under that root,
counting all Peers that share it. EACL does not count encryptions for you.
See the [cryptographic assumptions](../formal/verification/cryptographic-assumptions.md)
for the implementation and verification details.

## Cursor lifetime

**Cursors have no age expiry by default. Lossless resume therefore requires
indefinite retention of their old keys.** Retirement deliberately invalidates
those cursors. Configure a finite `:cursor-ttl-seconds` before issuing cursors
whose retirement needs a bounded retention window. Adding a TTL later does not
add an expiry to previously issued non-expiring cursors.

## Key material and ownership

Generate at least 32 cryptographically random bytes outside EACL. The library
checks length and representation, but cannot tell whether a secret was
randomly generated. It accepts byte arrays, byte sequences, and UTF-8 strings.
Decode Base64 from your secret manager yourself; EACL treats a string as UTF-8.

Give each key a unique public ID, such as `:cursor-2026-09`. The ID is a label,
not the secret. Never assign different material to an existing ID or recycle
a retired ID, including after a restart.

Your application owns distribution, durable storage, and restart recovery.
Controller updates live in memory. EACL copies mutable byte inputs, but the
JVM and JavaScript runtimes cannot guarantee that retired secret bytes are
erased from every memory copy.

| Limit | Maximum |
| --- | ---: |
| Accepted keys | 64 |
| Retired IDs per controller | 65,536 |
| Encoded key ID | 1,024 bytes |
| Root key material | 4,096 bytes |

`:max-keys` and `:max-retired-kids` can lower the count limits. Plan a new
coordinated configuration before exhausting retired IDs; do not recycle them.

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

`security-keyring-status` reports the generation, active ID, accepted IDs,
and retired IDs without exposing key material. Use that status to confirm
updates on each Peer. Full replacement requires `:expected-generation` and
returns `:eacl.keyring/conflict` if another update won the race.

Invalid operations raise `:eacl.keyring/invalid`, for example when retiring
the active key, reusing an ID, or selecting a missing key. Static configuration
errors use `:eacl/invalid-config`. A full replacement may remove the old active
key only if it selects a valid replacement atomically.

Keep raw secrets and secret-provider input maps out of application logs.

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

An operation already in flight may finish with the keys it captured. Calls
started after retirement observe the new key set. Key rotation does not change
the database or permission rules.

## Errors and optional cache data

| Artifact | Unavailable named key |
|---|---|
| Caller-supplied cursor | `:eacl.pagination/invalid-cursor`, reason `:security-key-unavailable` |
| Caller-supplied Zed token | `:eacl/invalid-zed-token`, reason `:security-key-unavailable` |
| Authenticated cache snapshot | `{:restored? false :cache-miss? true :reason :security-key-unavailable}` |

An expired cursor returns `:eacl.pagination/expired-cursor`. Invalid
authentication and unavailable keys also produce errors for caller-supplied
cursors and Zed tokens. Your application decides whether to begin a new query;
EACL does not silently replace the requested view.

Authenticated cache snapshots are optional. Unknown keys, retired keys,
malformed data, or invalid authentication cause a cache miss and leave the
current cache intact. A successful restore replaces the cache after validation.

The cache envelope authenticates its contents but does not encrypt them. Protect
storage if the contents are confidential. Imported entries remain dependent on
their verifying key and cannot be re-exported as locally trusted answers.
Retiring that key makes them unusable; locally computed answers remain reusable.

See [cache persistence](cache.md#authenticated-cache-snapshots-v8) for export,
restore, and size limits.
