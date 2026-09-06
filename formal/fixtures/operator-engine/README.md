# Operator regression fixtures

These inputs are exercised against current code:

- `minimized-counterexamples.edn`: `eacl.operator-engine.counterexamples-test`
  compares the oracle and intentionally faulty algorithms on retained graphs.
- `union-only-baseline.edn`: `eacl.operator-engine.union-only-baseline-test`
  captures current public behavior and compares its canonical denotation,
  order, pagination, and error results with the expected fixture values.
- `union-only-cursor-payloads.edn`: the same suite compares current decoded
  cursor semantics with these expected payloads, accounting for explicit ABI
  changes. Randomized ciphertext is excluded.

These are behavioral inputs, not saved test results or source-file hash pins.
Benchmark measurements and verification reports belong under ignored `target/`
or in CI artifacts. The separate [benchmark budgets](../../../docs/benchmarks/operator-engine-budgets.edn)
retain the existing acceptance limits.
