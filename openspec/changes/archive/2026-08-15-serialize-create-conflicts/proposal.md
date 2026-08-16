## Why

`:create` promises `:eacl/relationship-conflict` when the relationship already
exists, and the Datomic client decides that inside the transaction (a
transactor-side relation stamp CAS with re-planning). On DataScript and
Datahike the existence check ran only at plan time, so two writers that both
planned a `:create` of the same relationship against the same pre-write value
both committed — the second add was a redundant datom — and both callers saw
success. The end state was correct; the duplicate-create semantics under a
race were not shared with Datomic and SpiceDB (audit report 2026-08-15,
§2.19).

## What Changes

- DataScript and Datahike `tx-update-relationship` keep the plan-time conflict
  for an already-present relationship and otherwise emit a transaction
  function (`[:db.fn/call create-relationship-at-commit resolved
  relationship]`) plus the planned relationship adds. The shared writer runs
  every create precondition before batch mutations, so the function re-checks
  both halves at the transaction linearization point and either permits the
  planned adds or throws the typed conflict.
- Datahike runs the function only under its default in-process writer
  (`{:writer {:backend :self}}`); a remote writer cannot transport a function
  value and keeps the plan-time check only. Datahike reports a failing
  transaction function wrapped, so the client's transaction wrapper recovers
  the typed error from the cause chain.
- Repeated same-operation updates inside one batch still collapse through the
  write path's `distinct` and have the same outcome as one occurrence.
  Different operations for one resolved relationship are rejected before
  transaction submission instead of inheriting backend-specific
  statement-order or conflicting-datom behavior; `:touch` and `:delete` are
  otherwise unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `converged-relationship-storage`: the atomic pair mutation requirement gains
  the commit-time decision for racing `:create`s.

## Impact

- `modules/eacl-datascript` and `modules/eacl-datahike` relationship
  transaction planning, the Datahike transaction wrapper and writer-topology
  helper, storage suites, module READMEs and the top-level README.
