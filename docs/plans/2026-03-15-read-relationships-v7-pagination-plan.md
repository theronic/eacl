# Plan: Paginated v7 `read-relationships`

## Summary

Rebuild `eacl/read-relationships` so it matches the public return shape of
`lookup-resources` and `lookup-subjects`:

```clojure
{:data [Relationship ...]
 :cursor "eacl1_..." | nil}
```

The implementation should default `:limit` to `1000`, use bounded v7 tuple
index scans instead of full relationship walks, and update `eacl-explorer` to
consume the new result shape after the EACL modules are green.

Current answer to the design question:

- No, the current implementation cannot handle every legal query combination
  efficiently.
- Yes, it can once Datomic uses the existing v7 tuple indexes directly and
  DataScript adds the missing heterogeneous tuple projections needed for
  non-anchored scans.

## Architectural Direction

- Keep `modules/**` as the implementation source of truth.
- Treat `read-relationships` as a direct relationship enumeration API, not a
  seq helper.
- Remove the current unbounded fallback scans from both backends.
- Use tuple-backed scans for all legal query combinations.

## Phases

### Phase 1: Lock the new contract with tests

- Update the shared EACL contract tests so `read-relationships` returns
  `{:data :cursor}` and defaults `:limit` to `1000`.
- Add tests for anchored pagination and opaque cursor round-trips.
- Add explorer tests that expect `(:data ...)` from `read-relationships`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Rebuild DataScript direct reads around tuple indexes

- Add the missing heterogeneous relationship tuple attrs needed for efficient
  non-anchored scans.
- Rewrite `modules/eacl-datascript/src/eacl/datascript/impl.cljc` so direct
  reads choose an indexed scan instead of falling back to `ds/datoms` over the
  full relationship space.
- Change the DataScript core wrapper to decode and encode cursor tokens for the
  new paginated result map.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Rebuild Datomic direct reads around the existing v7 tuples

- Rewrite `modules/eacl-datomic/src/eacl/datomic/impl.clj` so it applies
  `:limit` and `:cursor` directly on v7 tuple scans.
- Use bounded prefix scans plus relation-definition fanout for partial filters
  instead of materializing all matching relationships and filtering afterward.
- Change the Datomic core wrapper to emit the same paginated result map and
  cursor tokens as DataScript.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Update downstream callers

- Update EACL tests that currently treat `read-relationships` as a seq.
- Update `eacl-explorer` to consume `(:data ...)` and token cursors from
  `read-relationships`.
- Bump `eacl-explorer` to the new EACL SHA after the EACL repo passes.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Verify correctness and speed

- Run the EACL module tests for Datomic and DataScript, including the CLJS
  runner.
- Add a benchmark-only regression check for `read-relationships` page latency.
- Rebuild `eacl-explorer`, run its browser tests, and confirm the subject
  directory still works after the dependency bump.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Assumptions

- Default `:limit` for `read-relationships` should match `lookup-resources` and
  `lookup-subjects`, which currently default to `1000`.
- It is acceptable to change the public contract of `read-relationships` from a
  seq to a paginated result map.
- The existing Datomic v7 tuple ordering is sufficient for efficient scans once
  the implementation stops doing filter-after-scan work.
