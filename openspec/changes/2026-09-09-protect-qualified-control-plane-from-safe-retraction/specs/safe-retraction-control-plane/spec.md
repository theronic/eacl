## ADDED Requirements

### Requirement: Object retraction excludes qualified control entities
The safe-retraction operation SHALL reject a target containing any owned Caveat
or Relationship qualifier fact, including partially populated control records.
It SHALL preserve existing Relation, Permission, schema, and function protection.

#### Scenario: A referenced Caveat is the explicit target
- **GIVEN** a Relationship with a false Caveat, no bound context, and future expiration, on a Relation admitting plain and two named alternatives
- **WHEN** safe retraction targets the used Caveat entity
- **THEN** the operation fails with reason `:protected-control-entity`
- **AND** no database mutation commits and a fresh permission check remains denied

#### Scenario: A qualifier entity is the explicit target
- **WHEN** safe retraction targets an attached or unattached qualifier entity
- **THEN** it rejects the object operation without deleting that entity
- **AND** an independently authorized explicit orphan-cleanup operation remains available

### Requirement: Protection covers native component cascades
The operation SHALL inspect the entire native component closure and SHALL abort
the complete transaction if any member is qualified control data.

#### Scenario: An application parent owns a Caveat as a component
- **WHEN** safe retraction targets the parent
- **THEN** neither the parent, the Caveat, nor incoming qualifier references are retracted

### Requirement: Installed bodies implement the same protection
Every supported native safe-retraction mode SHALL enforce the same protected-role
contract, and an explicit install/upgrade SHALL replace a recognized older body.

#### Scenario: A recognized previous Datomic body is installed
- **WHEN** the corrected function is installed
- **THEN** subsequent native invocations enforce control-plane protection
- **AND** repeating installation is idempotent and an unrelated occupant remains a conflict
