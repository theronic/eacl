# Plan: Support Multiple Subject Types in Relations

## Context
The EACL data model supports polymorphic Relations (e.g., `:owner` can be `:user` or `:group`), but the current implementation of `find-relation-def` assumes a single Relation definition per `(resource-type, relation-name)` pair. This causes only one subject type to be recognized, leading to missing permissions for other subject types.

## Goal
Update `eacl.datomic.impl.indexed` to support multiple Relations for a given `resource-type` and `relation-name`, ensuring that `get-permission-paths` generates paths for all valid subject types.

## Plan

### 1. Modify `find-relation-def`
- **Current**: Returns a single Relation definition map or nil.
- **New**: Rename to `find-relation-defs`. Return a collection of Relation definition maps.
- **Implementation**:
  - Update Datalog query to return `[...]` instead of `.` (scalar).
  - Handle empty results (return empty vector/list).

### 2. Update `resolve-self-relation`
- **Current**: Calls `find-relation-def` and returns a single path map.
- **New**: Calls `find-relation-defs` and returns a collection of path maps.
- **Logic**:
  - Map over the returned relation definitions.
  - For each, construct the path map `{:type :relation, :name ..., :subject-type ...}`.

### 3. Update `get-permission-paths`
- **Current**: Assumes single paths for `:relation` and `:arrow` branches.
- **New**: Flatten/Mapcat results to handle multiple paths.
- **Logic**:
  - **Direct Relation**: When `source-relation-name` is `:self` and target is `:relation`, call `resolve-self-relation` and add all returned paths to the result.
  - **Arrow**:
    - Call `find-relation-defs` for `source-relation-name` (the `via` relation).
    - For *each* `via-rel-def`:
      - Determine `intermediate-type` from the relation definition.
      - Recursively calculate `sub-paths` based on `intermediate-type`.
      - If valid sub-paths exist, construct an `:arrow` path for this specific `via` variation.
  - **Cycle Detection**: Ensure cycle detection logic (checking `visited-perms`) remains correct.

### 4. Verification
- Run `eacl.datomic.impl.indexed-test`.
- The expectation is that `get-permission-paths` will now return multiple paths for the same permission if polymorphic relations exist.
- `can?` and `lookup-resources` should automatically work because they iterate over all returned paths.
- `lookup-subjects` should work, filtering by the requested subject type.

## Checklist
- [ ] Rename/Update `find-relation-def` to `find-relation-defs`.
- [ ] Update `resolve-self-relation`.
- [ ] Update `get-permission-paths`.
- [ ] Run tests and analyze failures.

