# Applied change pack

The imported review bundle is a container, not a standalone OpenSpec change.
Its three child changes were copied to the project change root as directed by
its README. These are the canonical implementation task lists:

- [Qualified control protection](../2026-09-09-protect-qualified-control-plane-from-safe-retraction/tasks.md)
- [Datomic self-loop generation stamps](../2026-09-09-stamp-datomic-self-loop-retractions/tasks.md)
- [Qualifier cleanup sweeps](../2026-09-09-avoid-repeated-full-scans-in-qualifier-cleanup/tasks.md)

The original review and proposed regressions remain intact. Maintained native
regressions now live in the backend test roots. The shared safe-retraction
contract covers all owned control fields and supported backend modes; Datomic
native mutation controls retain the false-Caveat/expiry witness and the
self-loop stamp obligation.

The cleanup API uses a synchronous sweep with data-volume budgets and exact
own-commit evidence. Datomic, DataScript, and direct Datahike writers support
sweeps. Datalevin keeps single-batch cleanup until its leased commit evidence is
certified. Native work/race contracts and `QualifierCleanupSweep.dfy` express
the certificate invariant. Timing/allocation acceptance budgets live in
`formal/baselines/qualifier-cleanup-sweep.edn`, consumed by the native benchmark.

See `docs/caveats.md` for budgets and restart semantics, and
`docs/v8-backend-modules-and-upgrade.md` for the required explicit function-v5
installation. Storage remains v8. Generated verification output belongs under
`target/formal/verification/adversarial-review/`; benchmark observations belong
under `target/benchmarks/adversarial-review/`.
