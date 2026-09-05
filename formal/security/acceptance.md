# Live security-keyring acceptance

Phase 4 enables local atomic security-key updates, protected-format integration,
revocable authenticated cache imports, and bounded retired-state cleanup. The
[boundary inventory](inventory.md) maps owners to tests; the
[operator guide](../../docs/security-keyrings.md) documents safe rollout.

## Verification

All local checks below passed with zero failures/errors:

| Check | Result |
|---|---|
| Fresh combined JVM battery, including all four backends and optional JVM Caveats | 1,541 tests / 160,058 assertions |
| Shared public controller and authenticated-import contracts | 4 tests / 160 assertions; Datahike in both attribute modes |
| Final advanced DataScript CLJS suite | 813 tests / 112,677 assertions |
| Focused secure-format, rotation and retention suite after codec option reuse | 45 tests / 76,904 assertions |
| Keyring validation/state/format/public-boundary killed controls | 6 tests / 23 assertions, detecting 13 controls |
| Cache/import replacement contracts | 60 tests / 751 assertions |
| Caveat foundation gate | 82 Dafny obligations; 32 tests / 35,148 assertions |
| Qualified authorization gate | 71 Dafny obligations; 394 tests / 607,016 assertions |
| Public source closure | 132 roots / 3,026 definitions; no forbidden matches; 43 negative controls |
| OpenSpec | Strict validation passes; 31/31 tasks complete |

The combined JVM run preceded the final small cursor option-map reuse change;
the focused codec suite, full advanced CLJS suite, and source closure passed
after that change. CI rechecks the final PR head before landing. Existing
formal obligations and assertion inventories remain unchanged; the qualified
gate records the reviewed source-input hash updates.

Deterministic snapshot callbacks cover encode/decode versus activation/retirement.
Concurrent controller tests cover one-winner replacement and competing installs.
The two-Peer drill covers missing distribution, recovery, observation, activation
skew, overlap, rollback before retirement, and deliberate invalidation afterward.
Cursors with no TTL and finite TTL preserve distinct retirement/age errors.

Equal-root key-ID tampering, wrong-scope acceptance, ring trials, stale generation,
active-key removal, silent page restart, imported-cache fail-open, and secret
canaries all have focused contracts; intentionally unsafe controls fail them.
Skipped cleanup remains safe, another controller's entries are preserved, and
expected-value eviction retains racing replacements. Authenticated snapshots
contain IDs and bounded data, never root keys or fingerprints. Imports cannot
be re-signed or become independent local cache authority.

## Performance and release scope

The [timing/work report](../../docs/benchmarks/results/live-keyring-2026-09-06/README.md)
includes pre-integration and live measurements at 1/2/4/16 keys, retained pilots,
raw samples, hashes, and population sweeps. Instrumented mint/decode each use
one state read and one named lookup regardless of ring size. Rotation does not
scan client caches; cleanup/recomputation is measured separately and preserves
locally computed answers. The source review found no production oracle, Peer
coordination, database/network key lookup, watcher registry, or global answer
flush introduced by this phase.

Default non-expiring cursors require indefinite old-key retention for lossless
resume. Finite TTL affects new cursors only. Controllers and key material remain
externally owned and non-durable. Datalevin's unpublished embedded-artifact
release guard remains unchanged; this phase does not publish that artifact.
