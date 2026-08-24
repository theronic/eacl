# Validation

Validated on 2026-08-23 with the repository's locked formal toolchain and Clojure evaluation through nREPL.

## Executable assurance controls

- `EACL_NREPL_PORT=52308 bin/formal mutation-control`: 4 tests, 75 assertions, zero failures and zero errors. All 23 active Clojure controls were killed: 18 executed production controls and 5 narrowly structural source-text controls. The registry also proves the complete historical split: 103 Clojure controls equal 23 active plus 80 explicitly retired controls, and 4 model controls remain active.
- `bin/formal apalache-mutation-control`: all four active temporal model mutants were rejected. Combined with the Clojure controls, all 27 active controls were killed. The remaining 80 historical literal-only controls are explicitly retired with a reason; active plus retired remains the 107-entry historical corpus.
- The manifest validator's corrupt-count subprocess control passed with the dedicated invalid-evidence exit status `2`, proving a registry/ledger/manifest count disagreement cannot masquerade as expected assurance withholding.
- DataScript CLJS: 268 tests, 8,149 assertions, zero failures and zero errors. This includes the 18 portable executed-production controls in `eacl.formal.executed-mutation-controls`.

## Formal corpus and generated boundary

- `bin/formal format`: clean.
- `bin/formal verify`: 30 Dafny modules, 8,792 proof efforts, zero verification errors. Removing the unused `CacheKernel.dfy` reduced the total by exactly its 17 proof efforts; the retained `CurrentCache.dfy` proof remains at 32 obligations.
- `sh formal/stable-discovery/verify-fast.sh`: 46 leaves and 631 verified obligations; all TLC and Dafny mutation groups passed in 7 seconds, below the 12-second bound.
- `bin/formal build-java`, `bin/formal build-js`, and `bin/formal browser-bundle`: passed.
- The artifact-size gate passed with locked Babashka 1.12.213: browser bundle 586,813/738,488 bytes, Java classes 1,875,003/2,377,367 bytes, Java source 2,115,033/2,670,869 bytes, and JavaScript 942,084/1,188,865 bytes. The host `bb` is 1.12.218 and was correctly rejected by the pinned-toolchain guard.
- Fresh JVM formal smoke: 47 tests, 15,624 assertions, zero failures and zero errors.
- `node bin/public-source-closure.mjs check`: 70 public roots and 1,641 reachable definitions across 79 source files; clean after isolating the dependency-free exact-integer boundary.

## CI-equivalent runtime battery

- Full JVM matrix: 782 tests, 30,105 assertions, zero failures and zero errors.
- `bin/reflection-gate target/reflection-gate.log`: clean.
- `eacl.build.release/build-install-smoke` through nREPL for `8.0.0-SNAPSHOT`: built, audited, installed, and cold-smoked the EACL, Datomic, Datahike, and DataScript release set.
- `openspec validate reinstate-executable-assurance-gates --strict`: clean.
- `git diff --check`: clean.

## Honest release-gate result

`bin/formal manifest` regenerated and validated the 304-source digest set, 55 reports, 67 counterexamples, generated runtimes, adapter certification, mutation counts, source-closure counts, and stable-discovery counts. It exits with the dedicated expected-withholding status `3`; the workflow accepts only that status. Invalid evidence exits `2` and fails CI. `:verified` remains withheld because these five declared release obligations remain open:

1. mechanized host-control source refinement;
2. mechanized CLJ cache-transition source refinement;
3. mechanized CLJS production-authority refinement;
4. mechanized backend-adapter conversion refinement; and
5. independent security/formal review.

No count, theorem, adapter-certification, source-digest, report-digest, generated-artifact, or counterexample mismatch was reported.
