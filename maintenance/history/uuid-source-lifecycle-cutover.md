# UUID source-lifecycle artifact cutover (2026-09-08)

Historical record. The native UUID source-lifecycle change (commit `0ee425a7`,
OpenSpec change `2026-09-08-use-uuid-source-lifecycles`) changed the token,
cursor, page-token and cache formats listed below. EACL 8.0.0, the first
published v8 release, and every later release use the formats in the "New"
column, so this cutover applies only to builds that predate the change. The
current contract is in [Native UUID source lifecycles](../../docs/uuid-source-lifecycle-upgrade.md).

The section below is the cutover procedure as that guide published it.

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
