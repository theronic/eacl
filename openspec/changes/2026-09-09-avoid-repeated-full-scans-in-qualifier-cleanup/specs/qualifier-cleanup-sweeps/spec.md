## ADDED Requirements

### Requirement: A quiescent cleanup sweep amortizes global proof capture
A supported sweep SHALL capture a global orphan proof once and SHALL NOT rebuild
that proof after every own successful cleanup batch while its certificate remains
valid. Every commit SHALL remain within configured transaction-size bounds.

#### Scenario: Many orphan batches with no foreign writes
- **GIVEN** a healthy source with Q orphans exceeding one deletion batch, within sweep capture budgets
- **WHEN** the sweep drains those orphans without foreign writes
- **THEN** it performs one global proof capture and multiple guarded bounded commits
- **AND** it does not rescan the stable active relationship background per batch

### Requirement: Only proved own commits extend a sweep certificate
A sweep SHALL bind candidate absence to an exact source/lifecycle/revision and
SHALL advance that revision only using an authoritative result of its own
successful cleanup transaction.

#### Scenario: A candidate is attached between batches
- **WHEN** another writer attaches a remaining candidate before the next cleanup commit
- **THEN** the old sweep certificate is rejected or freshly revalidated
- **AND** the attached qualifier is not deleted

#### Scenario: A later current snapshot is not the sweep's own commit result
- **WHEN** the current revision includes an unproved foreign write
- **THEN** the sweep does not treat that revision as a valid continuation certificate

### Requirement: Budgets and partial progress are explicit
A sweep SHALL enforce capture resource budgets, release owned resources, and
report only successfully committed deletions. It SHALL distinguish partial
progress requiring restart from complete collection.

#### Scenario: Interruption after one committed batch
- **WHEN** cancellation or a conflict occurs after an earlier successful batch
- **THEN** the result identifies committed progress and the remaining work requires valid continuation evidence
- **AND** no uncommitted deletion is reported as successful
