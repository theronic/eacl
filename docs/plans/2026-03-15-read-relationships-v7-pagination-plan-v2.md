# Plan v2: Indexed, Paginated `read-relationships` with Explorer Bump

## Summary

This plan treats direct relationship reads as a first-class indexed API rather
than a seq-producing helper. `eacl/read-relationships` will return:

```clojure
{:data [Relationship ...]
 :cursor "eacl1_..." | nil}
```

with a default `:limit` of `1000`. The implementation must remove all
filter-after-full-scan behavior, cover every legal query combination with
bounded tuple scans, pass the full EACL suites, and only then repin
`eacl-explorer` to the new EACL SHA.

Current answer to the design question:

- Not with the current implementation.
- Yes after this redesign, because every legal query combination is planned as
  bounded relation-definition fanout plus tuple-prefix scans.
- Datomic can do that on its existing v7 tuples.
- DataScript needs additional heterogeneous tuple attrs whose logical ordering
  matches Datomic's partial-scan order.

## Query Matrix

- Anchored subject queries:
  - `:subject/type + :subject/id` with optional `:resource/type`,
    `:resource/relation`, `:resource/id`
  - Use anchored forward scans.
- Anchored resource queries:
  - `:resource/type + :resource/id` with optional `:subject/type`,
    `:resource/relation`, `:subject/id`
  - Use anchored reverse scans.
- Partial subject-side queries:
  - `:subject/type` with optional `:resource/type` and/or `:resource/relation`
  - Use heterogeneous forward scans plus relation-def fanout when the filter
    skips over the relation slot.
- Partial resource-side queries:
  - `:resource/type` with optional `:subject/type` and/or `:resource/relation`
  - Use heterogeneous reverse scans plus relation-def fanout when needed.
- Relation-only queries:
  - `:resource/relation` with optional `:subject/type` or `:resource/type`
  - Resolve matching relation defs first, then scan only those prefixes.
- No supported query should fall back to a full relationship-index walk.

## Phases

### Phase 1: Lock the new contract before implementation

- Update `modules/eacl/test/eacl/contract_support.cljc` so
  `read-relationships` is asserted as a paginated API with default `:limit`
  `1000`.
- Add shared contract scenarios for:
  - exact anchored single-match reads
  - anchored pagination with opaque cursors
  - omitted limit defaulting to `1000`
  - `delete-relationships!` consuming a paginated read result
- Add explorer tests that expect `(:data ...)` and token cursors from
  `read-relationships`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Add an explicit direct-read planner

- Introduce a shared planner in `modules/eacl/src` that:
  - resolves matching relation definitions once
  - emits ordered scan specs for the active query
  - never emits a global fallback scan
- Define the internal cursor as planner state:
  - scan-spec index
  - per-scan resume key
  - versioned cursor map suitable for tokenization
- Keep scan ordering deterministic and backend-independent at the logical level
  so cursor behavior matches across DataScript and Datomic.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Rebuild DataScript around anchored and heterogeneous tuples

- Extend `modules/eacl-datascript/src/eacl/datascript/schema.cljc` with
  heterogeneous direct-read tuple attrs that mirror the Datomic logical order:
  - forward heterogeneous tuple
  - reverse heterogeneous tuple
- Keep the current anchored tuple attrs for subject-id and resource-id scans.
- Update relationship writes so all tuple attrs remain in sync.
- Update `modules/eacl-datascript/src/eacl/datascript/impl.cljc` so:
  - anchored queries use anchored tuple scans
  - partial queries use heterogeneous tuple scans
  - relation-only and mixed-filter queries use bounded fanout over matching
    relation defs
  - results return `{:data :cursor}` with no seq fallback
- Update the DataScript core wrapper to translate token cursors and external
  object ids for the new internal cursor shape.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Rebuild Datomic direct reads on v7 tuples

- Rewrite `modules/eacl-datomic/src/eacl/datomic/impl.clj` to execute the same
  planner output against the existing v7 tuple attrs.
- Use bounded prefix scans and relation-def fanout instead of
  `find-relations` plus materialize-and-filter logic.
- Apply `:limit` and `:cursor` in the impl layer and return the same
  `{:data :cursor}` shape.
- Update the Datomic core wrapper to translate the new internal cursor shape to
  opaque public tokens.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Verify backend correctness and performance

- Expand DataScript backend tests to cover:
  - anchored forward and reverse reads
  - subject-type-only scans
  - resource-type-only scans
  - relation-only scans
  - mixed type + relation scans
  - cursor resumption across scan-spec boundaries
- Expand Datomic backend tests to the same filter matrix.
- Keep benchmark-only regression checks for representative `read-relationships`
  pages:
  - `{:subject/type :user :limit 20}`
  - `{:resource/type :server :limit 20}`
  - one relation-filtered page
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 6: Bump explorer only after EACL is green

- Commit the EACL changes in `/Users/petrus/.codex/worktrees/e264/eacl` and use
  that SHA to update [deps.edn](/Users/petrus/Code/eacl-explorer/deps.edn).
- Update explorer call sites to consume `{:data :cursor}`:
  - [src/eacl/explorer/explorer.cljs](/Users/petrus/Code/eacl-explorer/src/eacl/explorer/explorer.cljs)
  - [src/eacl/explorer/state.cljs](/Users/petrus/Code/eacl-explorer/src/eacl/explorer/state.cljs)
  - [test/eacl/explorer/seed_test.cljs](/Users/petrus/Code/eacl-explorer/test/eacl/explorer/seed_test.cljs)
- Run the explorer browser tests and rebuild the Pages artifact after the bump.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Defaults and Assumptions

- `read-relationships` should default to `:limit 1000`, matching the lookup
  APIs.
- Compatibility should be preserved for the common delete workflow by allowing
  `delete-relationships!` to consume a paginated result map.
- The module paths under `modules/**` are the implementation target; top-level
  duplicate namespaces are not part of this change.
