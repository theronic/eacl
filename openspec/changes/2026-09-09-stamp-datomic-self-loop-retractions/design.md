## Context

The Datomic implementation intentionally omits peer retractions for a
self-relationship because native retractEntity removes both local halves. It
incorrectly uses that physical optimization to erase logical invalidation data.
Sources: [modules/eacl-datomic/src/eacl/datomic/safe_retraction.clj — function-definition](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl-datomic/src/eacl/datomic/safe_retraction.clj); [modules/eacl/src/eacl/relationships/safe_retraction.cljc — protected-control-entity?, ensure-unprotected!, plan-local-halves](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl/src/eacl/relationships/safe_retraction.cljc); [modules/eacl-datomic/test/eacl/datomic/safe_retraction_test.clj](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl-datomic/test/eacl/datomic/safe_retraction_test.clj); [formal/dafny/NativeGenerationCoherence.dfy — ForwardStep, SafeRetraction](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/formal/dafny/NativeGenerationCoherence.dfy).

## Goals / Non-Goals

Restore exact affected-Relation coverage without adding native scans or global
cache invalidation. Preserve idempotent current-transaction stamps and existing
missing-target/ghost-repair behavior. Do not change the shared planner, which
already derives all affected Relation ids. Do not broaden managed qualified
cache reuse as part of this fix.

## Decisions

### 1. Separate the two projections

Use all half records for logical invalidation and only non-nil operations for
physical peer cleanup:

```clojure
halves (or local-halves repair-halves)
peer-retractions (distinct (keep :op halves))
relation-eids (distinct (map :relation-eid halves))
```

Emit one idempotent current-transaction stamp for each distinct Relation id,
including Relations with only self-relationships in the deletion closure. A
non-self incident edge on the same Relation can mask the old defect, so the
minimal regression must isolate a Relation used only by the self-loop.

### 2. Preserve the asymptotic behavior

For H inspected local half records, this remains linear projection work with
set-based distinctness and O(number of distinct affected Relations) stamps.
There is no reason to scan all Relation definitions, all relationships, or flush
unrelated cache entries to compensate. Non-nil qualifier slots must be preserved
in any actual peer retraction as before.

### 3. Test the installed native body

The regression calls `d/invoke` on the installed function to assert the stamp set,
then transacts it and compares actual generation values. Assert entity and tuple
removal as well. Include a separate unrelated Relation whose generation must not
change. Repeat the essential case for a self-loop-bearing component child.

Do not satisfy the test solely by changing `plan-local-halves`: Datomic executes
its own embedded body. Update the compatibility identity together with the
control-plane fix so persisted old functions are upgraded.

## Formal refinement

The required affected set is exactly:

```text
{ r | exists edge in before.forward union before.reverse:
         edge.relation = r and
         (edge.subject in closure or edge.resource in closure) }
```

Nothing excludes `edge.subject = edge.resource`. Check equality between the
native expansion's stamp set and this independent relation projection. Test
both physical directions, repeated invocations, and component closures.

The supplied Python branch model enumerates 768 small cases and detects 207
violations in the old algorithm versus zero in the correction. It is a finite
independent check, not proof that the actual native implementation ran correctly.
The native regression and production mutation control remain required.

## Risks / Trade-offs

Generation advances may cause previously incorrect proof hits to become misses;
that is necessary invalidation. Extra stamps are not needed for unaffected
Relations. Do not infer that this witness proves a stale default public answer:
the currently inspected qualification path uses exact completed-answer reuse.

## Deployment

No storage ABI change. Upgrade the optional installed function explicitly;
changing the in-process library does not change an existing Datomic database
function. A combined F1/F2 release should use one coordinated compatibility bump.
