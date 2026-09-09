# Stamp Datomic self-loop retractions

## Why

Datomic's embedded safe-retraction function filters local-half records whose
peer operation is nil before deriving affected Relation ids. Self-relationships
have no peer operation, but native entity retraction still deletes them. Their
Relation generations therefore fail to advance, violating the writer condition
used by `NativeGenerationCoherence`.

The shared planner already handles this correctly. The defect is an embedded
implementation/refinement gap, not a proposed change to the proof's semantics.

## What Changes

Derive Relation ids from all affected half records. Filter nil only in the
peer-retraction projection. Add installed-native generation assertions for
self-loops, component closures, and mixed affected/unaffected Relations. Replace
previous installed function bodies using the coordinated compatibility update.

## Capabilities

### New Capabilities
- `safe-retraction-generation-coverage`: every changed relationship partition,
  including a self-loop-only partition, advances its generation atomically.

### Modified Capabilities

No existing specification files are edited by this proposal.

## Impact

Change the embedded body in `modules/eacl-datomic/src/eacl/datomic/safe_retraction.clj`.
Retain parity with `plan-local-halves` in the shared safe-retraction namespace.
Update native tests and refinement/mutation evidence. No tuple/storage migration.
Coordinate the function installation identity with the control-plane fix.

A stale default v8 `can?` answer is not claimed by this finding; the concrete
release defect is violation of the supported writer's generation invariant.
