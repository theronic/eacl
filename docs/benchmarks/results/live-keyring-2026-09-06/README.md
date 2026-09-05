# Live security-keyring performance certification

Measured on the 14-core Apple M4 Max host, macOS/aarch64, Java 26. All evaluation ran through nREPL. Timed work ran sequentially with no other local test/build gate active.

The cross-version fixture uses the same cursor, roots, key IDs, bounded construction cache, 2,000 warm-up operations, and seven batches of 1,000 operations in both checkouts. The pre-integration checkout is `a5b84b05`; current runtime source is `040d2d05`. Both comparison JVMs use a 2 GiB heap and the same dev/test/Caveat aliases. The recorded comparison follows the retained pilot in each JVM; it is a local diagnostic, not a latency SLA.

| Ring size | Before mint (µs) | Live mint (µs) | Before decode (µs) | Live decode (µs) |
|---|---:|---:|---:|---:|
| 1 | 6.50 | 15.88 | 24.51 | 23.54 |
| 2 | 6.13 | 6.50 | 25.86 | 23.64 |
| 4 | 6.52 | 6.46 | 24.65 | 23.62 |
| 16 | 6.29 | 6.52 | 24.72 | 22.99 |

The single-key live mint run remains sensitive to warm-up/JIT effects; pilots and every batch are retained. Sizes 2–16 show comparable mint cost and no rising decode cost. No general speedup claim or timing threshold is inferred from these small samples. The deterministic gate is stronger for the stated requirement: at each of 1/2/4/16 keys, both mint and decode perform exactly one controller state read and one named map lookup. The instrumented key map supports lookup only, so a ring traversal cannot pass that check.

`performance.edn` also retains same-revision static/live diagnostic samples and five populated-store runs per size from the 3 GiB integration JVM. Updates use two accepted keys at every population.

| Entries per store | Activation (µs) | Retirement (µs) | Next codec use + cleanup (µs) | Imported recomputation (ms) |
|---|---:|---:|---:|---:|
| 0 | 32.58 | 18.54 | 51.13 | 0.00 |
| 64 | 73.67 | 23.00 | 308.25 | 2.07 |
| 512 | 84.67 | 24.21 | 731.88 | 13.34 |

Every run verifies that retirement leaves the populated cursor store physically unchanged until its next use, cleanup removes only retired cursor entries, every retired import misses, and an independently computed local answer stays cached. Updates do not walk client stores; physical cleanup and recomputation costs are paid separately and are bounded by store capacity. Acceptance remains mandatory with cleanup disabled.

Reproduce `eacl.bench.security-keyring/run!` and `eacl.bench.security-keyring-baseline/certify!` through nREPL. For the baseline, load the latter fixture by absolute path in the parent checkout; pass `:live` in the current checkout. `provenance.json` records source/fixture and report hashes. `before-option-reuse.edn` preserves the exploratory run that prompted reusing the captured format-options map within each operation.
