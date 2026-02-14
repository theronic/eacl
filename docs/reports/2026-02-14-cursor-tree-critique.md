# Cursor Tree Implementation Critique

**Date:** 2026-02-14
**Issue:** [#43 — Cursor should be a tree for fast traversal of intermediate paths when paginating](https://github.com/theronic/eacl/issues/43)
**Plan:** [docs/plans/2026-02-14-cursor-tree-plan.md](../plans/2026-02-14-cursor-tree-plan.md)
**Branch:** `feature/cursor-tree`

## Executive Summary

The cursor tree optimization is **correctly implemented**. The core invariant — no valid results are ever skipped during pagination — is maintained through conservative volatile tracking and inclusive intermediate bounds. All 30 tests pass (28 indexed + 2 spice), including 12 cursor-tree-specific tests covering structure, advancement, backward compatibility, pagination correctness, performance, reverse direction, exhaustion preservation, multi-path contribution, self-permission indirection, cursor idempotency, and count integration.

**Minor issues found:** dead code in `count-resources`, `count-resources` doesn't propagate volatile state (acceptable), remaining Step 8 cleanup (unused `Cursor` defrecord).

**No bugs found.** Five new stress tests were written to try to break the implementation; all passed.

## Implementation Review

### Cursor Format

Changed from v1 flat cursor to v2 tree cursor:

```clojure
;; v1 (old):
{:resource {:type :server :id 17592186045503}}

;; v2 (new):
{:v 2
 :e 17592186045503         ;; last result eid
 :p {2 17592186045422      ;; path-index -> intermediate-cursor-eid
     3 17592186045440}}     ;; only arrow paths appear
```

Design choices:
- **Path indices** (integers into `get-permission-paths` vector), not content keys. Compact and stable for a given schema.
- **Sparse `:p` map.** Only arrow paths with contributing intermediates get entries.
- **Carry-forward.** `(into (or (:p cursor) {}) ...)` preserves previous cursor state for exhausted paths.

### `traverse-permission-path-via-subject` (indexed.clj:416-508)

Changed signature to accept `intermediate-cursor-eid`, returns `{:results <lazy-seq>, :!state <volatile!-or-nil>}`.

| Path Type | Intermediate Tracking | Skipping Mechanism |
|-----------|----------------------|-------------------|
| `:relation` | None (`:!state nil`) | N/A — no intermediates |
| `:self-permission` | None (`:!state nil`) | N/A — delegates to `traverse-permission-path` |
| `:arrow` to relation | `volatile!` via `vswap!` side-channel | `d/index-range` starts from `intermediate-cursor-eid` (inclusive) |
| `:arrow` to permission | `volatile!` via `vswap!` side-channel | `drop-while #(< % intermediate-cursor-eid)` on sorted intermediates |

### `lazy-merged-lookup-resources` (indexed.clj:510-538)

Changed to use `map-indexed` for per-path cursor extraction from `:p`, returns `{:results, :path-results}` where `path-results` contains per-path volatile state.

### `lookup-resources` (indexed.clj:540-561)

Forces lazy seq with `doall` (line 550), reads volatile state after realization, builds v2 cursor with carry-forward:

```clojure
new-p (into (or (:p cursor) {})
        (keep (fn [{:keys [idx !state]}]
                (when-let [v (and !state @!state)]
                  [idx v])))
        path-results)
```

### Reverse Direction (indexed.clj:563-715)

`traverse-permission-path-reverse`, `lazy-merged-lookup-subjects`, `lookup-subjects` mirror the forward changes with identical cursor tracking semantics.

### Spiceomic Coercion (core.clj:29-59)

`default-internal-cursor->spice` and `default-spice-cursor->internal` handle v2 cursors: coerce `:e` and all `:p` values between Datomic eids and external IDs. Both handle v1 fallback for backward compatibility.

`spiceomic-lookup-subjects` (core.clj:189-203) now includes cursor coercion (previously had `; todo cursor coercion.`).

## Correctness Analysis

### Arrow-to-Relation Intermediate Skipping — CORRECT

`d/index-range` with start tuple `[... (or intermediate-cursor-eid 0)]` is inclusive on start, giving `>=` semantics. The intermediate at `intermediate-cursor-eid` itself is re-scanned because it may still have unconsumed resources with eid > cursor-eid. Resources are filtered by `(> resource-eid (or cursor-eid 0))` to skip already-returned results.

### Arrow-to-Permission Intermediate Skipping — CORRECT

`traverse-permission-path` is called with `nil` cursor (unchanged), returning ALL intermediates sorted by eid. Then `(drop-while #(< % (or intermediate-cursor-eid 0)))` drops intermediates strictly below the cursor, giving `>=` semantics on the sorted output. This is semantically equivalent to the inclusive index-range start in the arrow-to-relation case.

**Performance note:** The `traverse-permission-path` call with `nil` cursor always fetches ALL intermediates. The optimization only avoids the resource-side work for exhausted intermediates. This is the "primary optimization only" tradeoff — the plan explicitly defers secondary optimization (passing cursor state into recursive calls) as future work.

### Volatile Tracking — SAFE (Conservative)

The `volatile!` tracks `min(intermediate eids that contributed results)` via a `(map (fn [r] (vswap! !min-int ...) r))` side-channel that runs AFTER cursor-eid filtering. Only resources that pass `(> resource-eid cursor-eid)` trigger the volatile.

**Conservative peek-ahead:** The merge sort (`lazy-fold2-merge-dedupe-sorted-by`) may peek at elements beyond the `take limit` boundary. This causes `vswap!` for resources not included in the final output. This is safe: it can only LOWER the min (recording more contributors), never raise it. We skip fewer intermediates than theoretically possible, but never skip one we shouldn't.

**Deduplication interaction:** When multiple intermediates produce the same resource eid, the merge sort deduplicates but all intermediates' `vswap!` calls fire. This correctly records all intermediates as contributors.

### Carry-Forward — CORRECT

| Scenario | Behavior |
|----------|----------|
| Path exhausted (no results) | Filtered by `(filter (comp seq :results))`, not in `path-results`. Previous `:p` entry preserved via `(into (or (:p cursor) {}) ...)`. |
| Path has results but none consumed (other paths filled limit) | Volatile stays nil, `keep` drops it. Previous `:p` entry preserved. |
| Path contributes to output | Volatile has value, overwrites `:p` entry for that path index. |
| `:relation` / `:self-permission` path | `!state` is nil. No `:p` entry ever created. |

### v1 Backward Compatibility — CORRECT

- v1 cursor (no `:v` key): `cursor-eid` falls back to `(get-in cursor [:resource :id])`. All `(get-in cursor [:p idx])` return nil → `intermediate-cursor-eid` is nil → no intermediate skipping. Functionally equivalent to pre-optimization behavior.
- Output is always v2, even with v1 input.

### Path Index Stability — IMPLICIT ASSUMPTION

`get-permission-paths` returns a deterministic vector for a given schema. Path indices in `:p` are stable as long as the schema doesn't change between pages. If the schema changes (e.g., new permission definition transacted), path indices could misalign. This is an acknowledged limitation of cursor-based pagination and is consistent with the existing v1 cursor's assumption that the database doesn't change between pages.

## Issues Found

### 1. Dead Code in `count-resources` (indexed.clj:730)

```clojure
resources       (map #(spice-object resource-type %) counted)
```

The `resources` binding is computed but never referenced. Since `map` is lazy and `resources` is never consumed, the computation never happens. This is harmless but should be removed for clarity.

### 2. `count-resources` Doesn't Propagate Volatile State

`count-resources` destructures only `{:keys [results]}` from `lazy-merged-lookup-resources`, ignoring `path-results`. The output cursor's `:p` is always `(or (:p cursor) {})` — a pass-through of the input cursor's `:p` with no update from the current page's volatile state.

**Impact:** If `count-resources` output cursors are used for subsequent pagination, intermediate cursors never advance. This is acceptable because:
- `count-resources` is typically called once with a cursor from `lookup-resources` (where `:p` is already set)
- The `:e` field still advances correctly, so pagination produces correct results (just without intermediate skipping)
- Chained `count-resources` → `count-resources` pagination is not a documented use case

### 3. Remaining Step 8 Cleanup

- `(defrecord Cursor [path-index resource])` in `src/eacl/datomic/impl/base.clj` (line 4) is unused. Superseded by v2 plain maps.
- `[eacl.datomic.impl.base :as base]` import in `src/eacl/datomic/core.clj` (line 6, comment says "only for Cursor") is now unnecessary.

### 4. `(filter (comp seq :results))` Eagerness

In `lazy-merged-lookup-resources` (line 533), `(filter (comp seq :results))` calls `seq` on each path's lazy result sequence, forcing the first element. This triggers one `vswap!` per path that has any results, even if those results are never consumed by `take limit`. This is another source of conservative volatile updates — safe but slightly reduces the optimization's effectiveness. Not worth changing; the tradeoff is well-understood.

## Testing Gaps Addressed

Five new stress tests were written and all pass:

| Test | Type | What It Tests | Result |
|------|------|---------------|--------|
| `cursor-tree-subjects-pagination-correctness-test` | CRITICAL | Completeness: limit-1 pagination of `lookup-subjects` matches unpaginated. No duplicate subjects. | PASS |
| `cursor-tree-multi-path-test` | CRITICAL | Multiple arrow paths (`account->admin` + `vpc->admin`) and direct relation (`shared_admin`) contributing simultaneously. Pagination correctness with multi-path. | PASS |
| `cursor-tree-self-permission-test` | MODERATE | Self-permission indirection (`server.view = admin + ...`). Pagination correctness through recursive permission resolution. | PASS |
| `cursor-tree-empty-idempotency-test` | LOW | Exhausted cursor produces identical cursor on repeated calls. Idempotent behavior. | PASS |
| `cursor-tree-count-with-v2-cursor-test` | LOW | `count-resources` correctly counts remaining results when given a v2 cursor from `lookup-resources`. | PASS |

**Final test results:** 30 tests (28 indexed + 2 spice), 327 assertions, 0 failures, 0 errors.

## Performance Analysis

### Arrow-to-Relation: O(1) Seek

`d/index-range` starts from `intermediate-cursor-eid` in the tuple index. This is an O(1) seek operation in Datomic's B-tree index. Subsequent pages skip exhausted intermediates entirely at the index level.

### Arrow-to-Permission: O(n) Scan + Drop

`traverse-permission-path` fetches ALL intermediates (O(n) where n = total intermediates), then `drop-while` skips the exhausted ones. The resource-side work (index-range calls per intermediate) is avoided for skipped intermediates. Net savings: O(n-k) resource scans avoided per page (where k = remaining active intermediates). The intermediate scan itself remains O(n). This is the "primary optimization only" tradeoff.

### Conservative Volatile Tracking

The merge sort's peek-ahead and the `(filter (comp seq :results))` check both cause conservative volatile updates. The recorded `intermediate-cursor-eid` may be lower than the true minimum contributor, causing slightly more work on subsequent pages. This is safe — it prevents incorrect skipping at the cost of reduced optimization effectiveness. The practical impact is small: at most 1-2 extra intermediates are scanned per page beyond the theoretical minimum.

## Remaining Plan Items

### Step 7: Opaque Token (Not Yet Implemented)

`cursor->token` / `token->cursor` functions are planned but not implemented. External callers currently see v2 cursor maps directly. The plan specifies:
- Wire format: `"eacl1_<base64(edn)>"`
- TTL-based expiry via `:t` timestamp
- Round-trip: `cursor->token` after spiceomic coercion, `token->cursor` before
- Backward compat: accept either token string or map

### Step 8: Cleanup (Not Yet Done)

- Remove unused `Cursor` defrecord from `base.clj`
- Remove unused `base` import from `core.clj`
- Remove dead `resources` variable from `count-resources`

## SpiceDB References

The opaque cursor token design was inspired by SpiceDB's cursor format:
- [SpiceDB Cursor protobuf](https://buf.build/authzed/api/file/main:authzed/api/v1/core.proto) — `message Cursor { string token = 1; }` — opaque string, max 100KB
- [SpiceDB Go repo](https://github.com/authzed/spicedb) — cursors are opaque tokens encoding internal state; callers never parse them
