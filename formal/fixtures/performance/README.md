# Performance evidence fixtures

These are authored acceptance inputs and deliberate regression fixtures, not
current performance reports. `eacl.performance.evidence-test` consumes:

- `evidence-acceptance.edn`, `evidence-golden.edn`, and `evidence-golden-report.edn`:
  deterministic evidence normalization, digest and tampering fixtures.
- `release-acceptance.edn`: frozen candidate-independent release criterion;
  its digest is pinned by the test and must not change to accommodate a run.
- `mechanism-ledger.edn` and `mechanism-fixture-coverage.edn`: retained correctness
  mechanisms and required multiscale adversarial coverage.
- `metric-capabilities.edn`: supported/unsupported metric record validation.

The compatibility matrix and adjacent notes preserve the authored requirement
mapping behind those fixtures. They are historical design context, not claims
about the current source or host. New measurements and checker output belong
under ignored `target/benchmarks/` or in CI artifacts; do not refresh these
fixtures from local benchmark output.
