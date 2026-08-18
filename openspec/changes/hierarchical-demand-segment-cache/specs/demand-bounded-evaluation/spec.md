# demand-bounded-evaluation Specification

## MODIFIED Requirements

### Requirement: Cache retains only demanded work
Cache-enabled execution SHALL retain only completed artifacts and exact backend
responses that the evaluator demanded before its stopping decision. Cache code
MUST NOT increase adapter chunk size, scan bounds, generated fuel, traversal
waves, or result demand. The exact scan-response cache
(`exact-scan-response-cache`) is the only retention of backend responses: it
stores prefixes of replies the evaluator actually commanded and, on a miss,
forwards the evaluator's command unchanged.

#### Scenario: Projection shorter than cache chunk
- **WHEN** the evaluator commands a projection scan for five values and the backend can return more
- **THEN** EACL requests, validates, and may retain at most the response authorized by that exact command
- **AND** cache configuration does not fetch a sixth value

#### Scenario: Scan-response miss forwards the same command
- **WHEN** the scan-response cache cannot reproduce the evaluator's command from a stored prefix
- **THEN** it forwards that command with the same bound and limit and does not issue any other command

#### Scenario: Page sentinel reached
- **WHEN** a page has obtained its `N+1` sentinel
- **THEN** EACL performs no further traversal for cache population

#### Scenario: Count sentinel reached
- **WHEN** a bounded count has obtained `L+1` distinct results
- **THEN** EACL performs no further traversal for cache population
