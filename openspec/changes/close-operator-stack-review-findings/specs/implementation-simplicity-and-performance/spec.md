## ADDED Requirements

### Requirement: Request-path telemetry with no consumer is not recorded unconditionally

Non-authoritative optimization observations SHALL NOT be recorded on the request path unless a consumer reads them. Recording SHALL be opt-in through client configuration, or the observation store SHALL be constructed lazily on first consumer use. When recording is disabled, no observation store is allocated and no per-chunk, per-batch, or per-count recording work is performed.

Observation keys that embed a source watermark SHALL NOT cause unbounded key churn on write-active sources when recording is enabled: entries whose key can never be reused SHALL be bounded or partitioned so that recording does not displace reusable entries.

#### Scenario: Recording disabled by default

- **WHEN** a client is constructed without opting into relationship observations and a page, count, or membership batch is evaluated
- **THEN** no observation store is allocated and no recording work occurs on the request path

#### Scenario: Recording enabled on a write-active source

- **WHEN** observations are enabled and the source's watermark advances between requests
- **THEN** entry retention remains bounded and recording does not evict entries that remain reusable at the current watermark
