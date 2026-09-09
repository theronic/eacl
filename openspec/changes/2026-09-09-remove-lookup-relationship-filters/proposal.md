> **Superseded relationship-read requirements (2026-09-09):** The later `2026-09-09-remove-relationship-read-authorization` change replaces every requirement below to retain, construct, validate or migrate to `read-relationships :authorization`. Nested demo branches use plain indexed reads with the exact parent/type/relation, pagination, consistency and cache controls, and perform no endpoint permission checks. Removed authorization fields are rejected on presence, including nil/null. Viewing subject and permission remain inputs to root lookups, the access inspector and permission checks only. Library callers needing endpoint authorization compose direct reads with `can?` or `check-permissions` on one snapshot and own filtered pagination. Historical completed tasks and measurements below describe the earlier release, not the new contract. All unrelated requirements remain in force. Apply the companion delta before archiving; do not restore the superseded clauses.

## Why

The public `:resource/relationship` and `:subject/relationship` lookup filters add an unsupported API commitment and an enumerate-and-probe execution path whose work can scale with unrelated authorized objects. Remove both explicitly, while retaining the shared request preparation, batch checks and authorized relationship reads that address the original per-candidate public-call amplification.

## What Changes

- **BREAKING** Reject either removed key on both public lookup operations before snapshot selection, cache access or backend work, including nil values; never silently ignore it or broaden results. Reject the same obsolete keys on `count-resources` and `count-subjects` so reusing a former lookup query cannot silently produce an unfiltered count; this adds rejection, not count-filter support.
- Delete filter-specific lookup orchestration, relationship-clause validation, scope preparation and dead external predicate plumbing. Preserve internal filtering used by ordinary recursive, operator and qualified lookups.
- Preserve ordinary lookup semantics, `check-permissions`, `read-relationships` with `:authorization`, consistency modes, authorization evidence, cache controls and bounded pagination. No permission schema, relationship storage or fixture changes.
- Verify the dependent demo migration to indexed `read-relationships` with resource-endpoint authorization, retaining query-local latency/cache evidence and correct bounded pages. The local demo has begun this migration in `1693f04`; obsolete wire fields and callers remain. Preserve the unqualified canonical fixture and explicitly exclude automatic conversion of qualified-filter semantics. Do not restore sequential public checks or scans of in-memory fixture collections.
- Replace tests and benchmark arms for removed behavior with fail-closed rejection and retained-operation coverage; reconcile live docs, formal consumers and overlapping OpenSpec changes.
- Treat dependent repository updates as coordinated release prerequisites, not edits authorized by this core-local proposal. No release or deployment occurs during proposal creation.

## Capabilities

### New Capabilities

- `lookup-query-contract`: Formalize the supported lookup boundary after removal, fail-closed handling of obsolete requests/cursors, retained internal semantics, and consumer migration prerequisites. This contract is new to the main spec catalog; it does not introduce a new lookup feature.

### Modified Capabilities

None. The removed enumerate-route requirements currently live in the unarchived `eliminate-authorization-request-amplification` change, not in a main `authorized-relationship-pagination` spec. This change supersedes those requirements without undoing its batching and scan-route requirements.

## Impact

- Core: public admission in `modules/eacl/src/eacl/core.cljc` and `modules/eacl/src/eacl/{authorization/filters,client/orchestration,relay,schema/errors,engine/v8}.cljc`, with a reachability audit of shared engine helpers, branch-dependent cache proofs and backend direct-match primitives before deletion.
- Verification: shared contract support, DataScript batch and qualified lookup tests, Datalevin contract/operator/benchmark tests, Datomic operator tests, formal mutation gates and public-source closure.
- Consumers: sibling `eacl-demo` frontend adapter, DataScript runtime, four JVM service adapters, HTTP validation, fixture verification and vendored Jank code. No use was found in the searched `eacl-edrive` tree; external consumers and first published release remain unknown.
- Documentation: `docs/aggregate-authorization.md`, applicable README text, active core and demo OpenSpec requirements. Historical evidence stays historical.
- Release: coordinate core, packaged backend modules, browser runtime and demo services, including already-open old browser clients. Re-audit current source and artifact identities before implementation: local demo `77bf776` now routes nested branches through authorized reads, but retains obsolete filter constructors/HTTP admission and a Jank exemplar. Routing migration alone is not proof that the removal is complete.
