# Avoid repeated full scans in qualifier cleanup

## Why

Each `cleanup-orphans!` batch rebuilds a whole-database `proof-input` before
limiting deletion candidates. Draining Q orphans in batches of B revisits
`Q + (Q-B) + ...` qualifier records, even with no active data or concurrent writes.
With stable active data, every batch scans that background again. Bounded
transaction size therefore does not imply bounded work or a scalable sweep.

This is a source-derived maintenance complexity finding, not an observed
latency regression or a hot-path authorization defect.

## What Changes

Add an explicit sweep operation/session that captures one global proof, then
commits bounded candidate batches while advancing its certificate only through
its own verified cleanup commits. Foreign writes and source changes invalidate
the certificate. Preserve existing single-batch behavior and all native guards.
Instrument scan counts and distinguish scan/materialization cost from commit size.

## Capabilities

### New Capabilities
- `qualifier-cleanup-sweeps`: source-scoped, exact-revision-certified orphan
  collection that does not rebuild a full proof after each own deletion batch.

### Modified Capabilities

No existing specification file is edited by this proposal.

## Impact

Primary implementation: `modules/eacl/src/eacl/relationships/qualifier_integrity.cljc`
and the native staged-writer result/proof boundary needed to identify the exact
revision of a successful cleanup commit. Add operation-count and interleaving
contracts. No required relationship storage ABI change or ownership sidecar.

This change is independent of the safe-retraction safety fixes. A sweep must
not weaken head guards merely to avoid work.
