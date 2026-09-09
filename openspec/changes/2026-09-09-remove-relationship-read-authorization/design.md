## Context

See proposal.md for the removal decision. `eacl.client.orchestration/authorization-scan-page` constructs an endpoint predicate calling `engine/can?`, then passes it to each backend's relationship reader. `eacl.authorization.filters` validates the clause; schema validation, relay and cursor/cache setup carry its scope. Ordinary checks and lookups do not need this public composition.

The filtered-window executor is also used by `eacl.relationships.inspection/window-options` for expiry-active inspection. Thus removing every `:accept?` path would remove supported behavior unrelated to authorization. Shared request state, batch counters and schema caches also serve `check-permissions` and other operations.

The active `2026-09-09-remove-lookup-relationship-filters` plan explicitly preserves this clause. This owner's later decision supersedes that preservation and its migration advice to use authorized reads. It does not authorize implementing the unrelated lookup-filter removal here. The linked demo proposal owns changes outside this repository.

## Goals / Non-Goals

**Goals:** make the public relationship reader a direct read, delete its unused permission composition, and retain the existing primitives for applications that need composition.

**Non-Goals:** introduce a renamed authorized-read API, change the permission engine or tuple schema, remove exclusion/caveats/expiration, add a demo-specific fast path, optimize general batch authorization, or publish a release in the proposal phase.

## Decisions

1. **Reject the removed key through the existing filter boundary.** Remove `:authorization` from `known-filter-keys` so all values fail as `:eacl.filters/unknown-filter`. Keep validation before snapshot/cursor/cache work. Silent removal is rejected because an old caller explicitly asked for filtered rows; a flag or deprecated wrapper would retain the unwanted feature.

2. **Delete the feature by following consumers.** Remove `authorization-scan-page`, `validate-scan-authorization!`, endpoint authorization schema checks and authorized-read-only root/proof/query handling. Simplify `relay.cljc` scope normalization and read branches that distinguish an authorization clause. Remove associated counters, helpers and tests only when no supported consumer remains. Retain plain-read schema/relation integrity and qualifier handling. Do not globally delete symbols containing the word authorization.

3. **Keep shared filtered inspection.** `execute-filtered-window`, `:accept?` adapter support and physical progress anchors remain wherever expiry-active inspection needs them. Simplify only the endpoint permission predicate and its setup. Likewise preserve aggregate point-check validation, request memoization and execution limits; the batch API remains supported.

4. **Keep old scope from becoming a new answer.** Demonstrate that existing authenticated cursor and cache query identities reject/miss former authorized artifacts after the clause is removed. Do not strip authorization from decoded scope or restored queries. Change an ABI only if existing validation cannot distinguish the removed route, and document the exact invalidation; a global token/cache rewrite is not a prerequisite.

5. **Document caller composition honestly.** Show a direct read plus either `mapv` over existing `eacl/can?` or `check-permissions`, using one explicit snapshot when consistent composition is needed. These APIs have different Boolean/evidence/error contracts; the documentation must preserve them, including `can?`'s existing fail-closed evaluation behavior. Filtering one physical page can produce fewer accepted rows. Cross-page filling, cumulative budgets and cursor bookkeeping are the application's responsibility. Do not build a new reusable authorized paginator as part of this removal.

6. **Prove simplification by work counts.** A controlled single-relation, unqualified anchored scan for 25 rows must perform zero permission evaluations and bounded index work. With background lookahead disabled, record emitted/consumed datoms and ordinary continuation lookahead separately; never equate a 25-row page with exactly 25 physical operations. Use deterministic counters as regression evidence and before/after timings as supporting data, not a universal millisecond SLA.

## Risks / Trade-offs

- Old consumers lose a supported option → mark the removal breaking, reject it explicitly and document application composition.
- Deleting shared filtering breaks expiry inspection or bulk checks → retain live consumers and run their regression suites, including exclusion in a test-only schema.
- An old authorized cache entry or cursor crosses into raw semantics → cover restored-page and old-cursor scope mismatch before removing compatibility code.
- Concurrent lookup-filter removal or UI work changes assumptions → re-read current source at implementation, limit edits to the feature and coordinate through companion artifacts rather than overwriting work.
- Authorized scan gates also covered shared batching → move any still-required assertions to their actual supported operation; do not preserve a dead API just to keep a benchmark green.

## Migration Plan

1. Implement and verify explicit rejection, remove the dedicated scan path and update public docs/release notes. Reconcile the active lookup-removal plan's conflicting preservation/migration text when implementing, without restoring its removed filters.
2. Implement the companion demo's plain calls across browser, JVM and Jank. A new plain caller can be tested against an older service that already accepts plain reads; an old authorized caller against a new service must fail explicitly.
3. Verify exact packaged core versions in each local runtime. Keep all implementation and demonstrations local until publication is separately requested.
4. If a later release needs rollback, restore compatible prior core and demo artifacts together; never recover by silently discarding a received authorization clause. No relationship-data migration is involved.
