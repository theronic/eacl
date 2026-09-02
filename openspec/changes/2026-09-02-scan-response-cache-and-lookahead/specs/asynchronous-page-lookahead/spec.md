# asynchronous-page-lookahead Specification

## Purpose

Optional background publication of a served page's deterministic continuation
so that a caller's next page request becomes an exact rendered-page hit. The
lookahead is tunable per client, runs entirely outside the foreground request,
issues only the reads the continuation itself demands, and can never change
any answer.

## ADDED Requirements

### Requirement: Lookahead is opt-in and tunable per client

A client SHALL accept a `:lookahead` option that is absent or `nil` by
default (no lookahead) or a map with a positive integer `:pages` (how many
successive continuations to publish after a served page) and a positive
integer `:max-inflight` (the bound on concurrently running lookahead
operations for that client). Invalid values MUST be rejected at client
construction with the typed configuration error. On runtimes without
background execution the option SHALL be accepted and have no effect.

#### Scenario: Default is off

- **WHEN** a client is built without `:lookahead`
- **THEN** no background operation runs after any page

#### Scenario: Invalid configuration

- **WHEN** `:lookahead` is not a map, or `:pages` or `:max-inflight` is not a positive integer
- **THEN** client construction fails with the typed configuration error naming the key

#### Scenario: Runtime without background execution

- **WHEN** a ClojureScript client is built with a valid `:lookahead`
- **THEN** construction succeeds and pages behave exactly as without the option

### Requirement: Only the deterministic continuation is published

After a page operation invoked on the client itself (not on a retained
snapshot) returns a page with a next page, was served with caching enabled,
and was published (or found resident) under its exact basis, the client MAY
submit the same public operation with the same normalized query continued
after the served page's end cursor. The lookahead operation
SHALL execute through the ordinary public path on a snapshot it selects
itself, SHALL publish only what that ordinary path would publish, and MUST
NOT read ahead of the continuation, fan out across alternatives, or continue
beyond the configured number of pages. A page that was not cacheable, was
requested with `:cache? false`, or has no next page SHALL trigger no
lookahead.

#### Scenario: Next page becomes a hit

- **WHEN** a lookahead completes for a served page and the caller then requests the continuation on an unchanged basis with caching enabled
- **THEN** the caller receives the exact rendered-page hit with the same public content the uncached path would produce

#### Scenario: Basis moved

- **WHEN** a supported write commits between the served page and the caller's continuation request
- **THEN** the caller's request selects the new basis and is answered by the ordinary path; the lookahead's publication is never served for the new basis

#### Scenario: No next page

- **WHEN** the served page reports no next page
- **THEN** no lookahead is submitted

### Requirement: The foreground request is never affected

Submitting a lookahead MUST happen after the foreground response is complete
and MUST NOT add adapter reads, deadline checks, admission slots, counters, or
allocation on the foreground path beyond one bounded submission. A lookahead
operation SHALL run under its own execution contract and its own counter
ledger, SHALL NOT hold or consume the foreground request's service-admission
slot, SHALL be dropped without error when the client's in-flight bound is
reached, and any failure or typed error inside it SHALL be swallowed and
reported only through the optional observer.

#### Scenario: Saturated executor

- **WHEN** the client already has `:max-inflight` lookahead operations running
- **THEN** the new submission is dropped, the foreground response is unchanged, and no error surfaces

#### Scenario: Lookahead fails

- **WHEN** a lookahead operation throws or exceeds a limit
- **THEN** nothing is published for it, the failure reaches only the observer, and the next foreground request is served by the ordinary path

#### Scenario: Deadline isolation

- **WHEN** a foreground page runs with a short execution timeout
- **THEN** the lookahead operation is not bound by that timeout and the foreground page's deadline behavior is identical to a client without lookahead

### Requirement: Lookahead work is accounted separately

Reads and publications performed by lookahead operations SHALL be reported
under a distinct provenance to the request I/O observer and the cache
statistics so that operators can attribute remote reads spent ahead of
demand. Lookahead operations MUST NOT be counted as foreground requests in
any gate or meter.

#### Scenario: Observer attribution

- **WHEN** an observer is configured and a lookahead operation completes
- **THEN** the observer receives its meters marked as lookahead provenance and the foreground request's meters exclude them

#### Scenario: Dedup of resident pages

- **WHEN** the continuation is already resident under the exact basis or already in flight for that client
- **THEN** no second lookahead operation is submitted for it
