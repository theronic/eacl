; NOTE: this is a plan, not code.
# Fix Cursor-Based Pagination in impl_fixed.clj

## Key Findings from Analysis
- Local sorting of pages causes inconsistent order across pages and incorrect resumption, as the global stream isn't sorted, leading to skips/overlaps.
- Per-path max EIDs for cursors would require API changes (complex cursor), which is forbidden.
- Lazy merging by EID provides stable global order, enables correct dedup (dupes become consecutive), and allows simple max-based cursor without inconsistencies.
- Dedup must happen post-merge for cross-path efficiency.
- Paths must be ordered stably (e.g., by relation) to ensure consistent merge input.

## Overview
This plan fixes pagination bugs in `lookup-resources` by implementing lazy, merged traversal of multiple permission paths while preserving index order, handling cursors per-path, deduplicating globally, and computing next cursor correctly without local sorting. We avoid materializing full indexes by making each path's traversal lazy and cursor-aware. The fix ensures stable, non-overlapping pages aligned with the underlying tuple index order (sorted by resource-type then resource-eid).

Follow steps in order. Check off [ ] items as completed. Do not skip steps. Test after each major section.

## Prerequisites
[ ] Ensure you have the latest codebase and can run tests from impl_test.clj.
[ ] Review impl_indexed.clj's pagination (which works but has traversal bugs) for reference.
[ ] Understand the tuple index :eacl.relationship/subject-type+subject+relation-name+resource-type+resource is sorted by [subject-type subject-eid relation resource-type resource-eid].

## Step 1: Add Helper Functions for Lazy Merging
We need to lazily merge multiple sorted lazy seqs (each path's results sorted by resource-eid) into a single sorted seq by eid, while tracking path-index.

[ ] In impl_fixed.clj, add a function `lazy-merge-by-eid` that takes a vector of lazy seqs (each seq is [[type eid] path-idx]), and returns a lazy seq of [[type eid] path-idx] sorted by eid ascending. Use a priority-queue (java.util.PriorityQueue) for min-heap merge:
    - Each entry in queue: {:eid eid :path-idx path-idx :source-seq (rest source-seq)}
    - Comparator: compare by :eid
    - Lazily pull min, emit, and add next from that seq if available. This yields the lowest available EID on-demand, advancing seqs lazily.

[ ] Test this function independently with sample seqs, e.g., (lazy-merge-by-eid [[[1 0]] [[2 1]]]) => [[1 0] [2 1]].

## Step 2: Modify Path Traversal to Return Lazy Seqs with Path-Idx
[ ] In `lookup-resources`, after getting `paths`, use map-indexed to assign path-idx to each path.

[ ] Update `traverse-traversal-path` to accept path-idx and cursor, but only apply cursor if cursor's path-index matches this path-idx (else start from beginning for that path).

[ ] In `traverse-traversal-path`, make it return a lazy seq of [[type eid] path-idx] (append path-idx to each result).

[ ] Ensure traversals remain lazy (using lazy-seq where needed).

## Step 3: Implement Global Deduplication on Merged Seq
[ ] After getting path-results (vector of lazy seqs from each path), pass to lazy-merge-by-eid to get a single sorted lazy seq.

[ ] Apply lazy deduplication on the merged seq using a volatile! seen set (as before), filtering items not in seen. (Post-merge ensures dupes are consecutive for efficient filtering).

## Step 4: Apply Global Cursor Filtering
[ ] If cursor provided:
    - Use lazy drop-while on deduped seq until finding item where eid == cursor's resource eid (ignore path-idx for matching).
    - Then drop 1 (skip the cursor itself).
[ ] Take (inc limit) from the filtered seq (extra for has-more check).
[ ] If count == (inc limit), set next-cursor to path-idx and eid of the last (the extra one), and drop it from results; else next-cursor nil.

## Step 5: Update Result Construction
[ ] Realize only the paginated slice (doall on take).
[ ] Map to spice-objects as before.
[ ] Return {:data results :cursor next-cursor}.

## Step 6: Handle Parallelism (Optional Enhancement)
[ ] For performance, wrap path traversals in pmap, but since they are lazy, ensure pmap doesn't force realization (use delay or keep lazy).

## Testing
[ ] After Step 1: Unit test lazy-merge-by-eid with duplicates and varying lengths.
[ ] After each step: Run impl_test.clj pagination tests; verify no failures.
[ ] Full test: Ensure count-resources matches lookup-resources count.
[ ] Edge cases: Single path, zero results, cursor at boundary, duplicate eids across paths.

## Potential Pitfalls
- Ensure laziness: Avoid doall except on final page.
- Cursor matching: Use eid only for drop-while, as types may vary.
- If no match for cursor in drop-while, it means cursor invalid—return empty page.
- Preserve index order via eid sorting in merge.
- Stable path ordering: Before map-indexed, sort paths by a fixed key (e.g., (:terminal-relation path)) to prevent schema changes from altering merge order. 