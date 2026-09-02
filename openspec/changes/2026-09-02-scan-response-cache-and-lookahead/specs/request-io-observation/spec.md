# request-io-observation Specification

## Purpose

Opt-in, per-request observation of the adapter and storage I/O that an
authorization request performs, delivered to a client-configured observer
with exact mandatory meters and costing nothing when no observer is
configured. Demos and operators use it to attribute remote reads; production
clients without an observer pay one nil check per request.

## ADDED Requirements

### Requirement: An observer receives exact per-request meters

A client SHALL accept an `:io-observer` option: `nil` (default) or a function
of one map. When configured, the observer SHALL be invoked once at the end of
every public read operation and every lookahead operation with the
operation name, provenance (`:request` or `:lookahead`), elapsed nanoseconds,
and the request's exact mandatory meters: adapter commands, fetched values,
identity conversions, probes, candidates examined, and the scan cache's
elided commands, hits, and misses for that request. Values MUST equal the
request-owned counters used for limit decisions; the observer MUST NOT be the
source of any limit or authorization decision, and an observer that throws
MUST NOT change the operation's result or error.

#### Scenario: Observer sees the same counters that govern limits

- **WHEN** an observed request performs eleven adapter commands and hits a fetched-value limit
- **THEN** the observer receives eleven commands and the same fetched-value total that the typed limit error reports

#### Scenario: Observer throws

- **WHEN** the configured observer throws
- **THEN** the caller receives the operation's ordinary result or typed error unchanged

#### Scenario: No observer

- **WHEN** a client is built without `:io-observer`
- **THEN** no meter snapshot, map, or callback allocation occurs for observation on any request path

### Requirement: Observation is disabled at zero cost

The presence check for the observer MUST be a single reference test on the
request path. Meter snapshots and elapsed-time measurement for observation
SHALL be taken only when an observer is configured. Configuring an observer
MUST NOT change any result, error, order, cursor, or cache publication.

#### Scenario: Result equality under observation

- **WHEN** the same request runs with and without an observer on one immutable basis
- **THEN** results, cursors, counts, and typed errors are identical

### Requirement: Storage statistics integrate without a hard dependency

The Datahike module SHALL provide a helper that, when the S3 storage
backend's built-in I/O statistics are present on the classpath, captures the
storage GET, PUT, HEAD, LIST, and DELETE counts and latencies performed while
a body executes and returns them alongside the body's value; when the
statistics are absent the helper SHALL return the body's value with an
explicit `:unavailable` marker. The helper MUST NOT be invoked on any
production request path by EACL itself.

#### Scenario: Statistics available

- **WHEN** the S3 backend with I/O statistics is on the classpath and a demo wraps a request in the helper
- **THEN** the helper returns the request's value and the storage operation counts and latencies observed during it

#### Scenario: Statistics absent

- **WHEN** the S3 backend is not on the classpath
- **THEN** the helper returns the request's value and marks storage statistics unavailable without throwing
