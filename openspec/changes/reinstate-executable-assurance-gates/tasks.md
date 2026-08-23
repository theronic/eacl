## 1. Registry Discipline

- [x] 1.1 Add `:mechanism` to every `registry.edn` entry; add the registry test that rejects a `:executed-*` detector whose source form does not reference the implementation, artifact, or model the entry names, and that restricts `:source-text` to structural facts with a recorded pattern.
- [x] 1.2 Classify all 103 Clojure entries: rewrite literal-only detectors to mutate the named production or generated decision and run its covering differential or conformance test; move Dafny-only bug classes to `:executed-model` lemma variants; delete entries with no target, recording the reason in the registry.
- [x] 1.3 Convert source-text detectors that stand in for decisions (`fuel-cut-wave-rolls-back-original-state`, `cljs-default-restores-generated-kernel`, and peers) to executed controls or re-justify them as structural.
- [x] 1.4 Keep `:required-score 1.0`; confirm `every-registered-mutant-is-killed-test` and `ledger-matches-registry-test` pass on CLJ and, for CLJS-scoped entries, on CLJS.

## 2. Ledger Validation

- [x] 2.1 Make the manifest generator write the mutation row, the closure row, and the stable-discovery leaf/obligation counts from `registry.edn`, `mutation-control.edn`, `public-source-closure.json`, and `verify-fast.sh`; make the validator fail on disagreement with the claim, recorded, and actual values.
- [x] 2.2 Correct `manifest.edn` (96 → current), `assurance-matrix.edn` (58/63/1404 → current), `formal/README.md`, and `docs/formal-verification.md` (42/528 → current) through the generator, and add a negative control that corrupts one count and requires validation to fail.

## 3. Corpus Hygiene

- [x] 3.1 Remove `CacheKernel.dfy`, its `EaclKernel.dfy` include, and `cache-kernel.edn`, or name its consumer; re-pin the manifest and assurance matrix with the reason.
- [x] 3.2 Run `bin/formal verify`, `bin/formal manifest`, `bin/formal mutation-control`, the stable-discovery gate, the CI-equivalent battery, and `openspec validate --strict`.
