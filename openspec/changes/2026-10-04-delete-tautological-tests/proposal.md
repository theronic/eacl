# Proposal

## Why

Some tests cannot detect a defect in EACL. They assert that a removed name is
still absent, restate the text of a script or workflow, or exercise a model
defined in the test file. Two requirements ask for such tests, and the
counterexample ledger closes thirteen entries with them. Assurance should come
from tests of behavior through the public API.

## What Changes

- Delete eighteen tests of those three kinds, and six assertions of the same
  kind inside five tests that stay.
- Point each ledger entry that those tests closed at a test that executes the
  behavior that replaced the defective path: rejection of caller-supplied cache
  providers, independent concurrent publication, read-only lookup, and the
  single production authority.
- Record five ledger entries whose defect was in CI or test tooling as closed
  by the gate that failed, without a product regression.
- Stop requiring negative source assertions for deleted paths.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `implementation-simplicity-and-performance`: "Simplicity and performance
  evidence is certification-linked" no longer requires negative source
  assertions for deleted paths, and rules them out.
- `formally-verified-authorization-engine`: "Counterexample-driven bug
  discovery" says what counts as a retained regression, and how an entry is
  closed when the defective path was deleted or the defect was in tooling.

## Impact

- Tests under `modules/*/test` and `src-build`.
- The `regression-vars` map and the corpus check in
  `eacl.formal.counterexample-replay-test`.
- `formal/counterexamples/*/entry.edn` for the affected entries.
- Namespace lists in the DataScript ClojureScript runner, the two
  verified-authority suites, and `.github/workflows/formal.yml`.
- No production code.
