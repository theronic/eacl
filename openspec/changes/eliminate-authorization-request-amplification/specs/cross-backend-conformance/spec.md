## ADDED Requirements

### Requirement: Aggregate authorization conformance is backend-neutral

The shared conformance suite SHALL run batch point checks and both authorized pagination routes over equivalent fixtures for every supported backend and public target kind that declares the required capabilities, and SHALL compare results and typed failures with the scalar and filter-then-window oracles on the identical selected snapshot. Backend-specific expected authorization semantics MUST NOT be accepted.

#### Scenario: Four-backend aggregate matrix

- **WHEN** Datomic, Datahike, DataScript, and Datalevin run equivalent aggregate fixtures
- **THEN** ordered batch decisions, page rows, page booleans, `:bounded?`, cursor behavior, cache provenance, and typed errors are equivalent
- **AND** capability differences appear only as documented uniform unsupported-capability outcomes

#### Scenario: Public target matrix

- **WHEN** the suite runs through a writable client, read-only client, composed snapshot view, and direct snapshot where supported
- **THEN** all targets at an equal basis produce equal results
- **AND** retained targets perform no source acquisition during the operation

### Requirement: Conformance measures request amplification

The shared suite SHALL instrument source acquisition and release, public operation entry, request-context construction, plan seals, permission and relation definition reads, schema-generation reads, dependency and cursor proof derivations, backend commands, fetched values, candidates examined, direct-match probes, allocation proxies, and retained snapshot ownership. Deterministic bounds SHALL be expressed by batch size, distinct roots, page demand, window budget, and actual candidate chunks rather than wall-clock time.

#### Scenario: One-root batch grows

- **WHEN** the number of demands for one root increases within the configured maximum
- **THEN** acquisitions remain one, plan seals and definition reads remain bounded by one root, and public scalar entries remain zero

#### Scenario: Second identical request on every backend

- **WHEN** an identical request is repeated against a snapshot with the same certified schema generation
- **THEN** the second request performs zero plan seals and zero definition reads on every backend, including Datalevin

#### Scenario: Ownership balance

- **WHEN** every success and injected failure point in batch and page operations is exercised
- **THEN** acquired owned snapshots equal released owned snapshots
- **AND** no active reader remains after request scope exits

### Requirement: Performance is compared in pairs

The shared benchmark harness SHALL run paired series in one process with interleaved samples: scalar loop versus scan route, scalar loop versus enumerate route, cache hit versus cache bypass, and acquisition before versus after fingerprint removal, on the retained dense, sparse, and all-rejected fixtures. Each series SHALL report p50, p95, p99, current-thread allocation, sample count, warmup, and environment metadata and SHALL retain raw samples. HTTP series SHALL isolate framework overhead with a no-op endpoint and SHALL be reported separately from core series.

#### Scenario: Paired series on an unmatched host

- **WHEN** the harness runs on a host class that does not match the checked-in baseline
- **THEN** paired ratios are still computed and enforced because both arms ran in the same process
- **AND** only the absolute ceilings are reported as not applicable, listing the mismatched fields

### Requirement: Formal models and mutation controls cover aggregation faults

Formal models and named mutation controls SHALL cover snapshot mixing, output reordering or deduplication, cross-demand evidence contamination, deadline renewal, counter reset, treating resource failure as denial, cursor proof omission, skipped or duplicated candidates at window boundaries, per-candidate plan sealing, permission re-evaluation on the enumerate route, and snapshot release imbalance. At least one controlled mutation per fault class MUST be killed by a named proof or executable gate.

#### Scenario: Window-boundary skip mutation

- **WHEN** a controlled mutation anchors the cursor past an unexamined candidate or before the last examined one
- **THEN** the filter-then-window oracle reports an omission or a duplicate

#### Scenario: Per-candidate sealing mutation

- **WHEN** a controlled mutation re-prepares the root for each candidate
- **THEN** the amplification gate fails with the observed seal count
