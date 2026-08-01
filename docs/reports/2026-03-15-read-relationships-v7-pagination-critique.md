# Critique: Paginated v7 `read-relationships`

Plan reviewed: [2026-03-15-read-relationships-v7-pagination-plan.md](../plans/2026-03-15-read-relationships-v7-pagination-plan.md)

## Findings

- The current answer to the user-facing design question should be explicit:
  Datomic already has the right tuple order for the legal query matrix, but
  DataScript does not. Without adding mirrored heterogeneous tuple projections
  in DataScript, `read-relationships` still cannot handle every legal query
  combination efficiently.

- The plan identifies the main bottleneck but does not specify how every legal
  filter combination maps to a bounded index scan. Without that matrix, an
  implementer could reintroduce a hidden global fallback.
- The DataScript phase says "add the missing heterogeneous relationship tuple
  attrs" but does not define the tuple order or explain why the current tuple
  order is insufficient for relation-filtered partial scans.
- The cursor work is underspecified. Once `read-relationships` becomes
  paginated, the implementation needs a dedicated cursor model for multi-prefix
  scans, not just "encode and decode cursor tokens".
- The plan does not call out `delete-relationships!`, even though the protocol
  comment explicitly says it can consume the result of `read-relationships`.
- The plan updates explorer too early. The EACL repo should first establish the
  new contract, pass its full suite, and produce a committed SHA before the
  downstream repo changes.
- The verification phase is too coarse. It needs explicit test additions for
  anchored scans, partial scans, mixed filter scans, relation-only scans, and
  cursor resumption across scan-boundary transitions.

## Foundational Redesign Recommendations

- Treat direct relationship enumeration as its own indexed subsystem, parallel
  to the permission traversal engine, instead of adding pagination as a thin
  wrapper around the current impl functions.
- Make scan planning explicit and shared:
  - compute matching relation definitions once
  - translate the query into one or more ordered scan specs
  - execute those specs without any global fallback
- Keep Datomic and DataScript aligned on logical scan order, even if their
  physical tuple storage differs. That keeps cursor behavior and tests portable.
- In DataScript, add both anchored and heterogeneous tuple orders:
  - anchored tuples remain optimal when subject or resource id is present
  - heterogeneous tuples make relation-filtered partial scans efficient
- Make the new `read-relationships` default limit part of the public contract
  and test it centrally.
- Preserve the ergonomic delete flow by teaching `delete-relationships!` to
  consume either a result map or a bare seq.

## Improvements Required in the Upgraded Plan

- Add an explicit query-combination matrix and the tuple/index chosen for each.
- Specify the DataScript tuple attrs to add and how existing relationship rows
  are brought into parity.
- Define the internal cursor shape and where object-id to entity-id translation
  happens in both wrappers.
- Split verification into:
  - shared contract tests
  - DataScript backend tests
  - Datomic backend tests
  - benchmark-only regression checks
  - explorer integration tests after the dependency bump
- Reorder phases so the EACL backend contract is finished before touching
  `eacl-explorer`.
