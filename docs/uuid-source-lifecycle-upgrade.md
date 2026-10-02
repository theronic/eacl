# Native UUID source lifecycles

A lifecycle identifies one continuous history of a source. Ordinary commits,
client restarts, and narrow cache clears retain it. Restoring, resetting, or
replacing history requires a fresh coordinated lifecycle, even if a native
revision number or database name is reused. A lifecycle is neither a revision
nor a secret. Its authority always includes backend, source ID, and branch.

The public value is a native UUID (`java.util.UUID` or `cljs.core/UUID`). Do not
call `str` on `random-uuid`. EACL rejects strings, including UUID-shaped
strings, and keywords, maps, and vectors as lifecycles; it never parses them
into a UUID. Source IDs, branches, and exact locators have their own types, and
a UUID is not valid in those fields.

```clojure
;; Provision ONCE in authoritative host configuration, shared by every worker.
(def source-lifecycle #uuid "854e138f-b8a4-42ee-a8f9-49c01ac19fc1")
(def client (backend/make-client conn {:source-lifecycle source-lifecycle
                                       :security-key     signing-key}))
```

The displayed UUID is illustrative. Generate a fresh value for a real rollout.
Loading EDN configuration preserves its native type. When host configuration
uses JSON, the host must explicitly parse and validate the UUID at startup.
Do not generate a separate value on each worker or on every restart.

Datomic, Datahike, and DataScript use the reserved initial value
`#uuid "00000000-0000-0000-0000-000000000000"` when the option is absent.
Explicit nil and false fail construction. Once a host has rotated a source,
it must retain the new UUID and fail unavailable if authoritative configuration
is lost. EACL cannot detect that an omitted option on a new process conceals a
previous rotation. The source ID must also remain truthful; recreating a
connection-local source normally creates a new source ID.

Datalevin requires a persisted noninitial UUID and its shared key, durable
watermark, and enforced topology safeguards. It has no local expiry
API: history replacement and multiworker coordination belong to the host.
Preserve its watermark and topology during upgrade and rollback.

For backends with local `expire-cache!`, no argument creates a native UUID.
An explicit current UUID performs a full private reset without changing public
lineage. In either case, the private incarnation changes, detaching in-flight
publication. A noninitial-to-initial reset is rejected atomically. Resetting
with the current UUID is appropriate for cache repair, not history replacement.
Narrow clear does not clear proof-health quarantine; full reset does.

CLJS captures, hashes, and freezes a private copy of each incoming UUID. Validated
values that EACL already owns can be shared without another copy. Caller
mutation and a poisoned cached hash cannot alter a captured basis or imported
key. Native JVM UUIDs are already immutable. Raw adapter Relay calls have a
separate private, lazily created UUID per adapter lifetime; their cursors cannot
move to a newly constructed adapter with a colliding snapshot number.

## Artifact validation

The canonical scalar is exactly `#uuid "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"`
with lowercase hexadecimal digits. Uppercase, abbreviated, escaped, and
unknown tagged forms are rejected before reader normalization. UUIDs and their
textual spellings are distinct map keys and set members.

Tokens, cursors, and cache snapshots carry the source lifecycle. EACL
recognizes the formats of builds that predate 8.0.0 and rejects them with a
typed error: causal tokens with `:eacl/zed-token-upgrade-required`, cursors
with `:eacl.pagination/cursor-upgrade-required`, standalone stable-page tokens
with `:eacl.page/cursor-upgrade-required` before adapter work, and an explicit
cache-snapshot restore with `:eacl/cache-snapshot-upgrade-required`. Obtain a
fresh token, restart pagination with a fresh first page, or export a fresh
snapshot. The [cutover record](../maintenance/history/uuid-source-lifecycle-cutover.md)
lists the earlier formats.

Cache restoration checks that every nonempty snapshot entry matches the
destination's complete lineage; empty snapshots carry no authority. Raw
restore trusts the host; authenticated restore additionally checks keyring
trust. Installation is atomic and checks the captured private incarnation
again after reconstruction.

Bad authentication cannot become a first page or grant authority.
Opportunistic authenticated cache failure may be a safe miss. Constructor
failures are `:eacl/invalid-config` with `:key :source-lifecycle` and no
arbitrary value echo.
