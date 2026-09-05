# Benchmark sources and output

Version reproducible benchmark code, authored acceptance limits fixed before
sampling, and deliberate regression fixtures. Keep run output—raw samples,
latency/allocation tables, machine provenance, current pass/fail summaries, and
archives—under ignored `target/benchmarks/` or in CI artifacts. Never commit it.

- [Qualifier-reference storage](qualifier-reference-storage.md)
- [Qualified authorization](qualified-authorization.md) and its [budgets](qualified-authorization-budgets.edn)
- [Live security keyrings](security-keyring.md)
- [Performance evidence fixtures](../../formal/fixtures/performance/README.md)
- [Deliberate comparison baselines](../../formal/baselines/)

An old report is investigation context, not evidence that current code passes.
Rerun the relevant fixture against the changed source. Preserve failed samples
with successful confirmations outside Git, and disclose the host/runtime and
measurement limits when interpreting a result.
