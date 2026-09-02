# range-answer-reuse Specification

## Purpose

Reuse of completed pages for any page window of the same walk under the
same proof scope: an equal complete proof frame over the walk's relations
when proof-managed reuse applies, else the same exact basis. Both public orders are deterministic functions of the plan, the
snapshot, and the boundary, so every completed page is a contiguous segment
of one fixed result sequence: any window that lies inside retained segments
is served from them, a window that extends past a retained segment is the
segment's tail followed by its own continuation, and adjacent segments merge
into longer ones. Reuse never changes any public content and stores only
what demand produced.

## ADDED Requirements

### Requirement: A completed page retains every result's edge

When a page operation on a plain route (the least-derivation-path route of
an acyclic plan, or the stable first-discovery route of a recursive plan,
without a candidate window) completes with caching enabled, the internal
page published under its exact basis SHALL retain, alongside each result,
the internal cursor edge that would end a page at that result, so that any
window of the same walk can be derived from retained results without
traversal. Bounded candidate-window routes SHALL NOT participate: their
page content depends on the window size. Operator-expression routes
(intersection and exclusion covers) SHALL NOT participate in this change.

#### Scenario: Edges retained on an acyclic plan

- **WHEN** a `lookup-resources :first 20` page completes on the least-path route and is published
- **THEN** the published internal page carries twenty result edges, the twentieth equal to the page's end cursor edge

#### Scenario: Edges retained on a recursive plan

- **WHEN** a `lookup-resources :first 20` page completes on the stable first-discovery route of a recursive plan and is published
- **THEN** the published internal page carries twenty result edges, and the page's continuation still resumes from the stored checkpoint

#### Scenario: Bounded route excluded

- **WHEN** a page with a relationship filter reports `:bounded?`
- **THEN** it is published only under its exact key and never used to derive another page

### Requirement: Any window inside retained segments is served without traversal

A page request whose normalized query matches a retained segment of the
same walk (same operation, subject or resource anchor, permission,
direction kind, evaluation demand, and proof scope: an equal complete proof
frame over the walk's relations, or the same exact basis when proof-managed
reuse is unavailable) and whose boundary is
the segment's start boundary or one of the segment's retained edges SHALL
be answered from the segment when the segment holds the whole requested
window, or when it holds the remainder of the walk in the requested
direction: identical result order, boundary cursors equal to the retained
edges at the window's ends, `has-next-page?` true iff results follow the
window inside the segment or the segment itself has a next page, and the
symmetric previous-page flag. A window that begins at the walk's start
(no boundary) matches a segment that has no previous page; a `:last`
window without a boundary matches a segment that has no next page. The
derived page SHALL then be rendered and published exactly as a computed
page would be.

#### Scenario: Shorter page from the same start

- **WHEN** a `:first 20` page is resident and the caller requests `:first 10` from the same start cursor on the same basis
- **THEN** the caller receives the first ten results, a valid end cursor at the tenth result, `has-next-page?` true, and no traversal or adapter command runs

#### Scenario: Continuation inside the resident page

- **WHEN** a `:first 20` page is resident and the caller requests `:first 10` after the derived tenth result's cursor
- **THEN** the caller receives results eleven to twenty with the resident page's next-page flag, and no traversal or adapter command runs

#### Scenario: Window on a recursive plan

- **WHEN** the resident page was served by the stable first-discovery route of a recursive plan and the caller requests a smaller window inside it
- **THEN** the window is served from the resident page and its continuation past the resident page resumes from the stored checkpoint rather than replaying

#### Scenario: Suffix hit for reverse windows

- **WHEN** a `:last 20` page is resident and the caller requests `:last 10` from the same end boundary on the same basis
- **THEN** the caller receives the final ten results with the matching start cursor and flags

#### Scenario: Derived page equals computed page

- **WHEN** the derived page and an uncached computation of the same request are compared
- **THEN** results, cursors, page flags, counts, and typed errors are identical

#### Scenario: Unrelated write keeps segments

- **WHEN** a write outside the walk's relation closure moves the basis and the caller continues from a cursor inside a retained segment
- **THEN** the window is served from the segment under the equal proof frame

#### Scenario: Relevant write drops segments

- **WHEN** a write to a relation in the walk's closure moves the basis
- **THEN** no segment of the previous frame answers a request at the new frame

### Requirement: A window that extends past a retained segment composes the segment with its continuation

A page request whose boundary lies inside a retained segment that has a
next page (or, for `:last`, a previous page) but which holds fewer
results than requested SHALL be answered by the segment's remaining results
followed by an ordinary continuation from the segment's end edge (or, for
`:last`, preceded by the continuation before its start edge) for the
missing remainder; the composed page MUST equal the uncached page for the
full request, and the continuation SHALL be the only traversal performed.
Composition MUST be bypassed on bounded routes and across bases.

#### Scenario: Extension hit

- **WHEN** a `:first 10` page with a next page is resident and the caller requests `:first 30` from the same start on the same basis
- **THEN** the engine traverses only the continuation after the tenth result, and the composed page equals the uncached thirty-result page

#### Scenario: Composition on a recursive plan

- **WHEN** the resident page was served by the stable first-discovery route and the caller requests more results than it holds
- **THEN** the continuation resumes from the checkpoint at the resident page's end regardless of the page size that produced the checkpoint

#### Scenario: Resident page is complete

- **WHEN** the resident shorter page reports no next page
- **THEN** the caller receives it unchanged as the complete answer without traversal

### Requirement: Adjacent segments merge and retention is bounded

A published page whose start boundary is a retained segment's end edge, or
whose end edge is a retained segment's start boundary, SHALL extend that
segment rather than start a new one, so that a client paging through a walk
accumulates one segment. Retention per walk SHALL be bounded by a
configurable number of results and of segments (`:range-reuse
{:max-entries :max-results-per-walk :max-segments-per-walk}`), the whole
tier by a bounded entry count, and eviction MUST NOT affect any answer:
an evicted window is computed again.

#### Scenario: Paging accumulates one segment

- **WHEN** a client pages through a walk with `:first 20` and each request's cursor is the previous page's end cursor
- **THEN** the tier holds one segment covering every result served, and any later window inside it is served without traversal

#### Scenario: Retention cap

- **WHEN** a walk's retained results would exceed the configured maximum
- **THEN** the oldest segment is dropped and later windows inside it are computed and published again

### Requirement: Range reuse is subordinate to every existing cache rule

Range derivation and composition SHALL apply only when the request's exact
key misses, SHALL respect `:cache? false` and a disabled client cache, SHALL
publish derived and composed pages under their exact keys through the same
validated publication path as computed pages, and MUST NOT retain more than
the configured segments plus the exact pages demand produced.

#### Scenario: Cache disabled

- **WHEN** the request passes `:cache? false`
- **THEN** no range derivation or composition occurs

#### Scenario: Speculative or mutable contexts

- **WHEN** the request runs under a speculative write context
- **THEN** no range derivation or composition occurs
