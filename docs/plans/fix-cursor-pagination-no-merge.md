; NOTE: this is a plan, not code.
# Alternative Fix: Cursor-Based Pagination with Per-Path Cursors (No Merge)

## Overview
This alternative plan fixes pagination by extending the Cursor structure to store per-path last EIDs (avoiding global merge/sort). Each path resumes independently from its stored EID (or 0 if unread), we mapcat lazy seqs in stable path order, apply global dedup, take limit, and update per-path max EIDs for the next cursor. This prevents skips/overlaps without sorting costs, but requires internal API changes to Cursor (e.g., serialization).

**Warning**: This changes the Cursor API—ensure it doesn't break clients/serialization. Follow steps in order; check off [ ] items.

## Prerequisites
[ ] Confirm permission to modify internal Cursor API (now a map like {:per-path {path-idx last-eid}, :resource-type T}).
[ ] Ensure paths can be stably ordered (e.g., by terminal-relation) to keep mapcat consistent.
[ ] Review impl_indexed.clj for cursor ideas.

## Step 1: Modify Cursor Structure
[ ] In impl_base.clj, update ->Cursor to accept {:per-path (map<int eid> or vector<eid>), :resource-type (optional for compat)}.
[ ] Adjust cursor creation/reading to handle the map (fallback to old single for migration).

## Step 2: Stable Path Ordering and Indexing
[ ] In lookup-resources, after getting paths, sort them stably by a key (e.g., (sort-by :terminal-relation paths)).
[ ] Use map-indexed to assign path-idx (0 to N-1) to sorted paths.

## Step 3: Per-Path Cursor-Aware Traversal
[ ] Update traverse-traversal-path to accept path-idx and full cursor.
[ ] Extract per-path cursor-eid: (get (:per-path cursor) path-idx 0)  ; 0 means start from beginning.
[ ] In traversal (e.g., index-range), start from tuple with that cursor-eid; drop-while (not= eid cursor-eid), then drop 1 if matched.
[ ] Return lazy seq of [type eid] (no path-idx appended yet).

## Step 4: Combine and Process Results
[ ] Collect path-results: for each [idx path], call traverse-traversal-path with idx and cursor → vector of lazy seqs (in path order).
[ ] Mapcat into single lazy seq.
[ ] Apply global lazy dedup with volatile! seen set on [type eid].
[ ] If cursor, apply global drop-while (not= [type eid] [:from-cursor?])—but since per-path already filtered, this is mostly for safety.
[ ] Take (inc limit) from deduped seq.

## Step 5: Compute Next Cursor
[ ] For each path-idx, find max eid in the page from that path (track during mapcat or post-process).
[ ] If page has (inc limit), create next-cursor with updated :per-path map (set idx to max-eid for read paths), drop extra item from results.
[ ] Else, next-cursor nil.

## Step 6: Update Result Construction
[ ] Realize paginated slice (doall).
[ ] Map to spice-objects.
[ ] Return {:data results :cursor next-cursor}.

## Testing
[ ] Unit test new Cursor handling and per-path max computation.
[ ] Run impl_test.clj pagination tests; verify no skips/overlaps.
[ ] Edge cases: Uneven path lengths, dupes across paths, cursor mid-path.

## Potential Pitfalls
- Stable ordering: If paths reorder (schema change), cursors invalidate—mitigate with sort key.
- Complex cursor: Update all cursor users/serialization.
- If path count changes, handle missing idx in :per-path (default 0).
- Laziness: Ensure no early realization in mapcat. 