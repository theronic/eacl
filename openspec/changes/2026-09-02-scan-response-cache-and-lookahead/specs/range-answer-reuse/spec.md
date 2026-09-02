# range-answer-reuse Specification

## Purpose

Reuse of a completed page for a shorter or longer page of the same query
from the same start boundary on the same exact basis: a shorter page is a
prefix of the longer completed page, and a longer page is the completed page
followed by its own continuation, because both public orders are
deterministic functions of the plan, the snapshot, and the start boundary.
Reuse never changes any public content and stores only what demand produced.

## ADDED Requirements

### Requirement: A completed page retains every result's edge

When a page operation on the least-derivation-path route (an acyclic plan
without a candidate window) completes with caching enabled, the internal
page published under its exact basis SHALL retain, alongside each result,
the internal cursor edge that would end a page at that result, so that a
page of any smaller size from the same start boundary can be derived
without traversal. Bounded candidate-window routes SHALL NOT participate:
their page content depends on the window size. Stable first-discovery
routes (recursive plans) SHALL NOT participate either: their continuation
resumes from a stored checkpoint at the served boundary, and a derived
page would hand out a boundary with no checkpoint behind it.

#### Scenario: Edges retained

- **WHEN** a `lookup-resources :first 20` page completes on an unbounded route and is published
- **THEN** the published internal page carries twenty result edges, the twentieth equal to the page's end cursor edge

#### Scenario: Bounded route excluded

- **WHEN** a page with a relationship filter reports `:bounded?`
- **THEN** it is published only under its exact key and never used to derive another page size

#### Scenario: Recursive plan excluded

- **WHEN** a page is served by the stable first-discovery route of a recursive plan
- **THEN** it is published only under its exact key, and its continuation still resumes from the stored checkpoint

### Requirement: A shorter page is served as a prefix of a longer completed page

A page request whose normalized query differs from a completed, retained
page only by a smaller page size, with the same direction and the same start
boundary on the same exact basis, SHALL be answered by the first (or, for
`:last`, the final) `M` results of that page: identical result order, an end
cursor equal to the `M`-th retained edge, `has-next-page?` true iff the
longer page held more than `M` results or itself had a next page, and the
same previous-page flag. The derived page SHALL then be rendered and
published exactly as a computed page would be.

#### Scenario: Prefix hit

- **WHEN** a `:first 20` page is resident and the caller requests `:first 10` from the same start cursor on the same basis
- **THEN** the caller receives the first ten results, a valid end cursor at the tenth result, `has-next-page?` true, and no traversal or adapter command runs

#### Scenario: Suffix hit for reverse windows

- **WHEN** a `:last 20` page is resident and the caller requests `:last 10` from the same end boundary on the same basis
- **THEN** the caller receives the final ten results with the matching start cursor and flags

#### Scenario: Derived page equals computed page

- **WHEN** the derived page and an uncached computation of the same request are compared
- **THEN** results, cursors, page flags, counts, and typed errors are identical

### Requirement: A longer page may compose a completed page with its continuation

A page request that exceeds a resident completed page from the same start
boundary on the same exact basis MAY be answered by that page followed by an
ordinary continuation from its end edge for the missing remainder; the
composed page MUST equal the uncached page for the full size, and the
continuation SHALL be the only traversal performed. Composition MUST be
bypassed when either page is on a bounded route, when the basis differs, or
when the resident page reports no next page (the shorter result is then the
complete answer).

#### Scenario: Extension hit

- **WHEN** a `:first 10` page with a next page is resident and the caller requests `:first 30` from the same start on the same basis
- **THEN** the engine traverses only the continuation after the tenth result, and the composed page equals the uncached thirty-result page

#### Scenario: Resident page is complete

- **WHEN** the resident shorter page reports no next page
- **THEN** the caller receives it unchanged as the complete answer without traversal

### Requirement: Range reuse is subordinate to every existing cache rule

Range derivation and composition SHALL apply only when the request's exact
key misses, SHALL respect `:cache? false` and a disabled client cache, SHALL
publish derived pages under their exact keys through the same validated
publication path as computed pages, and MUST NOT retain more than the
longest completed page per range key plus the exact pages demand produced.

#### Scenario: Cache disabled

- **WHEN** the request passes `:cache? false`
- **THEN** no range derivation or composition occurs

#### Scenario: Longest page retained

- **WHEN** a `:first 20` page is published after a `:first 10` page for the same range key
- **THEN** the range key retains the twenty-result page and later shorter requests derive from it
