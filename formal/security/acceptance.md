# Live security-keyring acceptance

Phase 4 enables local atomic security-key updates, protected-format integration,
revocable authenticated cache imports, and bounded retired-state cleanup. The
[boundary inventory](inventory.md) maps owners to tests; the
[operator guide](../../docs/security-keyrings.md) documents safe rollout.

## Verification

Run the combined JVM battery (all four backends and optional JVM Caveats),
advanced DataScript CLJS, secure-format/rotation/retention contracts, controller
mutation controls, cache-import replacement contracts, foundation/qualified
formal gates, and public source closure. Run strict OpenSpec validation on the
phase-4 change. Require zero failures/errors and no forbidden source matches.
Keep the current counts and results in ignored `target/` or CI artifacts.

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

The [benchmark guide](../../docs/benchmarks/security-keyring.md) describes
paired pre-integration/live runs and populated-store sweeps. Keep samples,
hashes, and summaries under ignored `target/benchmarks/security-keyring/` or in
CI artifacts. Instrumented mint/decode each use
one state read and one named lookup regardless of ring size. Rotation does not
scan client caches; cleanup/recomputation is measured separately and preserves
locally computed answers. The source review found no production oracle, Peer
coordination, database/network key lookup, watcher registry, or global answer
flush introduced by this phase.

Default non-expiring cursors require indefinite old-key retention for lossless
resume. Finite TTL affects new cursors only. Controllers and key material remain
externally owned and non-durable. Datalevin's unpublished embedded-artifact
release guard remains unchanged; this phase does not publish that artifact.

## Encoded key-ID boundary correction

Final review reproduced a controller accepting a 1,202-byte Unicode key ID
that the cursor decoder correctly rejected. Admission now measures canonical
UTF-8 bytes as well as the existing bounded character representation. Exact
1,024-byte string and keyword IDs round-trip; the next byte is rejected before
controller publication. The state/format/retention/mutation suite includes byte-boundary round-trip
and rejection coverage.
This changes key admission only; protected-operation benchmarks must record their source commit and key-ID inputs.
