## ADDED Requirements

### Requirement: Public cursor validity precedes checkpoint acceleration
EACL SHALL complete cursor authentication, basis selection, continuation acceptance (equal frame in one lineage or exact fallback by identity), and boundary validation before consulting private checkpoint storage, and SHALL build the checkpoint key only from the accepted basis's lineage and frame. Checkpoint availability MUST NOT change the basis, stream, position, freshness, or typed failure selected by the public cursor contract.

#### Scenario: Valid cursor has a matching checkpoint
- **WHEN** the public cursor is accepted and a checkpoint with the same key, ordinal, and boundary identity exists
- **THEN** resumption accelerates the next page without changing its contents or next boundary

#### Scenario: Cursor is rejected
- **WHEN** the public cursor fails authentication, lineage, frame, or boundary validation
- **THEN** EACL returns the cursor outcome and never resumes checkpoint state

### Requirement: Visited pages remain basis-exact
The visited-page cache SHALL be keyed by exact basis identity because its entries hold externalized public identifiers whose rendering is not covered by the frame. Frame equality MUST NOT be used to reuse a visited page across bases.

#### Scenario: Unrelated write between visits
- **WHEN** a previously visited page is requested again after an unrelated write
- **THEN** the visited-page cache misses, the checkpoint may hit, and the page is rendered from the selected basis
