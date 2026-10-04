# Tasks

## 1. Delete tests that the ledger does not name

- [x] 1.1 Delete the absence tests (raw entry points, retired schema attributes, retired Dafny markers, exposed policy state) and the six absence assertions inside tests that stay; verify the edited namespaces load and pass
- [x] 1.2 Delete the text tests of dependency files and spec files, with the probe only they used; verify no definition is left unused

## 2. Delete tests that the ledger names

- [x] 2.1 Retarget entries 003, 008, 013, 014, 016, 017, 037 and 059 in `regression-vars` and in each entry's `:closing-evidence`; verify the corpus check passes
- [x] 2.2 Add `tooling-defects` for 028, 031, 034, 048 and 058 and require every entry to be in exactly one of the two; verify the corpus check passes, and fails when an entry is in neither
- [x] 2.3 Delete the audit, facade, launcher, workflow, entrypoint and generated-JavaScript text tests and the in-test cache model, and remove their namespaces from the runner lists; verify strict replay resolves every regression var

## 3. Verify

- [x] 3.1 Run the CI battery, the DataScript ClojureScript suite, strict counterexample replay and the verified-authority suites; verify zero failures
- [x] 3.2 Compare production coverage of the battery before and after; record any line that is no longer executed
