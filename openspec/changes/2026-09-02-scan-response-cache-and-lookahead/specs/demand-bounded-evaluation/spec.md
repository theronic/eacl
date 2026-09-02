## MODIFIED Requirements

### Requirement: Cache retains only demanded work
Cache-enabled execution SHALL retain only completed artifacts and exact backend
responses that the evaluator demanded before its stopping decision. Exact
backend responses MAY be retained as scan-response prefixes: for one read
descriptor, a prefix of the adapter's scan sequence from its first value,
extended only by contiguous replies the evaluator itself demanded, in a
request-local memo and in a client-private cross-request tier scoped by
source lineage, schema generation, and the scanned relation's generation.
Cache code MUST NOT increase adapter chunk size, scan bounds, generated fuel,
traversal waves, or result demand, and MUST NOT issue any adapter command that
cache-disabled execution of the same request would not issue.

#### Scenario: Projection shorter than cache chunk
- **WHEN** the evaluator commands a projection scan for five values and the backend can return more
- **THEN** EACL requests, validates, and may retain at most the response authorized by that exact command
- **AND** cache configuration does not fetch a sixth value

#### Scenario: Page sentinel reached
- **WHEN** a page has obtained its `N+1` sentinel
- **THEN** EACL performs no further traversal for cache population

#### Scenario: Count sentinel reached
- **WHEN** a bounded count has obtained `L+1` distinct results
- **THEN** EACL performs no further traversal for cache population

#### Scenario: Scan prefix served instead of a command
- **WHEN** a retained prefix can reproduce the evaluator's exact command reply
- **THEN** the reply is served without an adapter command and the evaluator's demand, order, and limit accounting are unchanged

#### Scenario: Scan prefix cannot reproduce the reply
- **WHEN** a retained prefix is too short and not exhausted for the evaluator's command
- **THEN** the identical command is issued to the adapter and no other command is added
