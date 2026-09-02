## MODIFIED Requirements

### Requirement: Mandatory resource meters are exact and observation is optional

Candidates, probes, adapter commands, fetched values, admissions, transitions, response units, and publication attempts governed by a limit SHALL be charged by mandatory request-owned counters before the corresponding semantic commit. A constant internal counter key MAY use a private preindexed slot only after the key and amount invariants are established at its construction boundary; that path SHALL preserve the checked path's exact value, non-negative amount, and overflow behavior. Dynamic or externally supplied counter input SHALL retain full validation. Optional diagnostic observation MAY sample or aggregate events that do not govern a limit but MUST NOT be the source of any limit decision. Unsupported diagnostic metrics MUST remain unavailable rather than being recorded as zero. A client MAY configure one request I/O observer; when absent, the request path SHALL perform no observation work beyond one reference test, and when present the observer SHALL receive the request's exact mandatory meters after the operation completes without affecting its result.

#### Scenario: Diagnostics are disabled during a limited request
- **WHEN** a request runs with optional observation disabled and reaches a governed limit
- **THEN** it returns the same typed limit outcome and safe mandatory counters as an observed request

#### Scenario: A constant hot-path counter uses a preindexed slot
- **WHEN** profiling retains a private preindexed increment for a compile-time counter key
- **THEN** its accumulated value and overflow failure equal the checked increment path
- **AND** an unknown key or invalid dynamic amount cannot enter that private path

#### Scenario: Optional metric is unavailable
- **WHEN** a runtime cannot measure an optional diagnostic metric correctly
- **THEN** evidence records it as unsupported and no authorization or resource decision depends on it

#### Scenario: Observer receives mandatory meters
- **WHEN** a client configures a request I/O observer and a request completes
- **THEN** the observer receives the same command and fetched-value totals that the request's own counters hold, and the request's result is unchanged
