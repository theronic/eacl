# Native UUID source lifecycles

A lifecycle identifies one continuous history of a source. Ordinary commits,
client restarts, and narrow cache clears retain it. Restoring, resetting, or
replacing history requires a fresh coordinated lifecycle, even if a native
revision number or database name is reused. A lifecycle is neither a revision
nor a secret. Its authority always includes backend, source ID, and branch.

The public value is a native UUID (`java.util.UUID` or `cljs.core/UUID`). Do not
call `str` on `random-uuid`. Strings, including UUID-shaped strings, keywords,
maps, and vectors require an explicit upgrade. EACL never extracts meaning
from their old fields or silently parses them. Source IDs, branches, and exact
locators keep their existing types; this change does not make UUIDs valid in
those fields.

```clojure
;; Provision ONCE in authoritative host configuration, shared by every worker.
(def source-lifecycle #uuid "854e138f-b8a4-42ee-a8f9-49c01ac19fc1")
(def client (backend/make-client conn {:source-lifecycle source-lifecycle
                                     :security-key signing-key}))
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

Datalevin requires a persisted noninitial UUID and the existing shared key,
durable watermark, and enforced topology safeguards. It has no local expiry
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

## Coordinated artifact cutover

| Consumer | Previous | New |
| --- | --- | --- |
| Canonical authenticated framing | 1 | 2 |
| Causal token prefix / envelope domain | `eacl_z4_` / v4 | `eacl_z5_` / v5 |
| Cursor prefix / encryption domain | `eacl_c6_` / v6 | `eacl_c7_` / v7 |
| Relay semantic ABI / envelope | 3 / 13 | 4 / 14 |
| Stable-page token prefix / domain | `eacl_sd1.` / v1 | `eacl_sd2.` / v2 |
| Authorization key / completed answer | key-v2 / completed-answer-v2 | key-v3 / completed-answer-v3 |
| Rendered-page value | v5 | v6 |
| Flat cache snapshot | basis-snapshot-v2 | basis-snapshot-v3 |
| Authenticated cache prefix / domain | `eacl_cache1_` / v1 | `eacl_cache2_` / v2 |
| Selected-basis / continuation context | 2 / 4 | 3 / 5 |

The canonical scalar is exactly `#uuid "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"`
with lowercase hexadecimal digits. Uppercase, abbreviated, escaped, and
unknown tagged forms are rejected before reader normalization. Existing
non-UUID scalar bytes are unchanged. UUIDs and their textual spellings remain
distinct map keys and set members. Version changes preserve keyring retirement,
authentication, exact-history recovery, and dependency checks.

1. Stop or isolate the old compatibility cohort. Provision one fresh UUID for
   each explicitly configured legacy lifecycle. A source deliberately using
   the documented initial default can move to the new sentinel during this
   coordinated format cutover; a replaced history still needs a fresh UUID.
2. Upgrade all artifact writers and readers together. Obtain fresh basis
   tokens and restart pagination with a fresh first page. Discard derived old
   cache snapshots; leave stored objects, relationships, schema, and Datalevin
   safety state intact.
3. Reopen clients using the same source ID, persisted UUID, and accepted keys.
   Verify token exchange, current permission changes, and cache restoration.
   Every nonempty snapshot entry must match the destination's complete lineage;
   empty snapshots carry no authority. Raw restore trusts the host; authenticated
   restore additionally checks keyring trust. Installation is atomic and checks
   the captured private incarnation again after reconstruction.
4. For rollback, isolate the new cohort, provision a fresh old-compatible
   lifecycle, and mint fresh old-format artifacts. Never recycle a retired
   lifecycle or restore an old positive cache. Mixed-version fallback is absent.

Recognized old causal tokens report `:eacl/zed-token-upgrade-required`; old
cursors report `:eacl.pagination/cursor-upgrade-required`; standalone stable-page
tokens report `:eacl.page/cursor-upgrade-required` before adapter work. Explicit legacy
snapshot restore reports `:eacl/cache-snapshot-upgrade-required`. Bad new-format
authentication cannot become a first page or grant authority. Opportunistic
authenticated cache failure may be a safe miss. Constructor failures are
`:eacl/invalid-config` with `:key :source-lifecycle` and no arbitrary value echo.

This is an API simplification, not a universal performance guarantee. A JVM
UUID stores two longs plus object overhead; CLJS uses a string wrapper. Tagged
EDN costs six more bytes than a quoted UUID string and thirty more than the old
short default. Existing size limits remain binding. Qualification uses
`eacl.bench.source-lifecycle`; raw measurements stay under `target/benchmarks/`.

Sibling demos and 0tx must upgrade in their own workspaces: `eacl-demo`,
`eacl-datahike-demo`, `eacl-datalevin-solidjs`, `eacl-datomic-solidjs`, and
`eacl-edrive`. No mixed-version deployment or external rollout is implied by
the implementation in this repository.
