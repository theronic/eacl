## ADDED Requirements

### Requirement: Datalevin certification covers the storage-enforced boundary
Executable certification SHALL establish, against the fork artifact and the Datalevin module: commit-generation materialization equal to the committed `max-tx`; `max-tx` continuity detection of a foreign writer; policy enforcement for unadmitted writes, missing stamps, wrong schema-stamp entities, stale generations, and frozen-schema/admin changes; shared-Store state adoption and bounded same-process stale-wrapper recovery; atomic stamp completeness for every writer operation including object-deletion batches, schema replacement, relation removal, and safe retraction; bounded frame acquisition proportional to the requested closure from one owned reader; and durable lineage across provider restart.

#### Scenario: Controlled unstamped mutation
- **WHEN** a control removes the relation stamp from an otherwise valid writer transaction
- **THEN** the store rejects the transaction and certification records the rejection

#### Scenario: Controlled stale generation
- **WHEN** a control writes a literal generation below the committing `max-tx`
- **THEN** the store rejects the transaction

#### Scenario: Frame probe count
- **WHEN** core requests `N` relation generations
- **THEN** instrumentation observes `N` EAVT probes plus one schema-generation probe on the selected reader and no relationship-content scan

#### Scenario: Restart preserves lineage
- **WHEN** certification closes and reopens the provider between two requests
- **THEN** the second request's lineage equals the first's and an equal frame reuses the first request's managed answer
