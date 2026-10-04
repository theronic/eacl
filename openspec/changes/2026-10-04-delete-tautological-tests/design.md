# Design

## Context

See proposal.md for motivation. The counterexample replay test requires a
regression var for every ledger entry, so a test that is some entry's
regression var has to be replaced before it can go.

## Goals / Non-Goals

**Goals:**

- Every regression var is a test that can fail when behavior changes.
- No ledger entry loses its record: fixture, expected result and README stay.

**Non-Goals:**

- Self-tests of oracles, generators and gates that other tests rely on.
- Lints that enforce a rule over current sources: the Dafny induction policy,
  the relationship-literal audit, the public source closure.
- Pins of the persisted storage format.
- The `formal/` tree, apart from namespace lists that name a deleted file.

## Decisions

**What counts as tautological.** A test is deleted when every assertion in it
is one of these:

- a removed var, namespace, resource, attribute or key is absent;
- a script, workflow, source file or dependency file contains or lacks literal
  text;
- a model or "mutant" defined in the test file behaves as written.

Alternative considered: also delete self-tests of test-tree code. Rejected,
because those tests can fail and differential tests depend on the code they
check.

**Removed-option tests stay.** They call the public constructor and assert the
rejection, which is behavior.

**Replacing a regression var.** Where the defective path was deleted, the entry
points at a test of what replaced it.

| Entries | Replacement regression |
|---|---|
| 003, 008 | a caller-supplied cache provider is rejected at construction |
| 013, 014, 017 | concurrent publishers compute independently and one publication wins |
| 016 | lookup is read-only; validation runs once, at publication |
| 037 | the Datomic client's decision kernel is the generated authority |
| 059 | the JVM smoke test that consumes the shared cross-runtime page vector |

**Tooling defects.** Entries 028, 031, 034, 048 and 058 record defects in CI
scripts or test harnesses. Their regressions were text checks of those scripts.
The replay test gains a `tooling-defects` set, and the corpus check requires
every entry to be in that set or to have a regression var, never both.

Alternative considered: point those entries at an unrelated passing test so
that one map covers the ledger. Rejected, because that is a regression in name
only.

## Risks / Trade-offs

- [A deleted name comes back unnoticed] → Reintroduced dead code is a review
  matter and shows in the public source closure; a test that lists names does
  not test what the code does.
- [A replacement regression is narrower than the original defect] → Each
  replacement is taken from the entry's own `:fix` text, and the ledger keeps
  the original witness.
- [A CI script regresses] → The gate it runs fails on the next push.
