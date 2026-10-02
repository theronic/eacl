## ADDED Requirements

### Requirement: An earlier frozen store opens with a later module
`eacl.datalevin.core/create-conn` SHALL open a Datalevin store whose write policy an earlier module version installed, whether or not the module or the application has added attributes since. It SHALL declare only the application's schema to Datalevin at open. It SHALL add the module's missing attributes itself only while the store has no write policy, and MUST NOT change the EACL attributes or the write policy of a store that has one.

#### Scenario: The module added attributes
- **GIVEN** a store that was bootstrapped, frozen and populated before the module declared the wildcard Relation attributes
- **WHEN** the current module opens it with `create-conn`
- **THEN** the connection opens, and the store's EACL attributes and write policy are those it was closed with

#### Scenario: The application added an attribute
- **GIVEN** a bootstrapped store that was opened with an `extra-schema`
- **WHEN** `create-conn` opens it with that `extra-schema` plus one new application attribute outside the `eacl` namespaces
- **THEN** the connection opens and the new attribute can be transacted

#### Scenario: A store without a write policy
- **GIVEN** a store opened by an earlier module but never used to construct a client
- **WHEN** the current module opens it with `create-conn`
- **THEN** the module's missing attributes are present and no write policy is installed

#### Scenario: A store that is not embedded
- **GIVEN** a store whose write policy cannot be read because it is not embedded
- **WHEN** `create-conn` opens it
- **THEN** the connection is returned with the module's attributes, and `make-client` rejects it with `:eacl/unsupported-topology`

### Requirement: Client construction admits added module attributes under the write policy
When a store's write policy does not cover an attribute the module declares, constructing a client SHALL add the attribute if the store lacks it and SHALL extend the persisted policy so that it covers every physical EACL storage attribute except `:eacl/id` before the client is returned. The extension SHALL be admitted with the store's per-open token, SHALL commit no Datalog transaction, and SHALL be completed by a later construction when an earlier one stopped after adding the attributes.

#### Scenario: An earlier store gains the wildcard attributes
- **GIVEN** the opened store of the first scenario above
- **WHEN** `make-client` is called
- **THEN** the stored relationships grant as before, the wildcard attributes are guarded and frozen, the store's revision is unchanged, and a schema that declares `user:*` can be written and used

#### Scenario: A write to an added attribute
- **WHEN** a transaction without the admission token asserts an added attribute after the extension
- **THEN** Datalevin rejects it with `:datalevin/guarded-attribute-write`

#### Scenario: The policy covers other attributes
- **GIVEN** an earlier store whose policy also covers an `eacl.*` attribute that the module does not declare and that existed at bootstrap
- **WHEN** `make-client` is called
- **THEN** the policy is extended and covers that attribute and the added ones

#### Scenario: An interrupted extension
- **GIVEN** a store whose added attributes exist, hold no data and are outside its policy
- **WHEN** `make-client` is called
- **THEN** the policy is extended to them and the client is returned

#### Scenario: The earlier module after the extension
- **GIVEN** a completely extended store in which nothing has been stored under the added attributes
- **WHEN** the earlier module version opens it and constructs a client
- **THEN** construction succeeds and the stored relationships grant as before

### Requirement: Only growth from the module's own policy is admitted
The extension SHALL be made only when the fork confirms that the persisted policy is identical to the policy the module registers for the attributes it covers, every attribute to be newly covered is declared by the module, and none of them holds a datom. Otherwise construction SHALL fail with `:eacl.datalevin/write-policy-drift`, naming the `:missing-attributes`, `:uncovered-attributes` and `:populated-attributes` that apply, and SHALL leave the physical schema and the policy as they were. An attribute whose stored definition differs from the module's SHALL continue to fail with `:eacl.datalevin/physical-schema-drift`.

#### Scenario: Data was written outside the policy
- **GIVEN** an added attribute that exists outside the policy and holds a datom
- **WHEN** `make-client` is called
- **THEN** it fails with `:eacl.datalevin/write-policy-drift` listing the attribute under `:populated-attributes`, and the policy still does not cover it

#### Scenario: The persisted policy is not the module's
- **GIVEN** an earlier store whose persisted policy was replaced with a different one
- **WHEN** `make-client` is called
- **THEN** it fails with `:eacl.datalevin/write-policy-drift` listing the `:missing-attributes`, and neither the attributes nor the policy have changed

#### Scenario: An attribute the module does not declare
- **GIVEN** a bootstrapped store to which an `eacl.*` attribute outside the module schema was added
- **WHEN** `make-client` is called
- **THEN** it fails with `:eacl.datalevin/write-policy-drift` listing the attribute under `:uncovered-attributes`, and the policy does not cover it

### Requirement: A store refused for another reason is not extended
Every check that client construction makes of a protected store SHALL be made before the extension changes it. An earlier store without a valid source identity or with incomplete generation evidence SHALL fail with the same error as before and SHALL keep its physical schema and policy.

#### Scenario: No source identity
- **GIVEN** an earlier store whose source-identity entity no longer resolves
- **WHEN** `make-client` is called
- **THEN** it fails with `:eacl/invalid-source-identity` and the added attributes are absent

#### Scenario: Incomplete generation evidence
- **GIVEN** an earlier store in which a Relation has no generation
- **WHEN** `make-client` is called
- **THEN** it fails with `:eacl.cache/generation-unprepared`, the added attributes are absent, and the policy is unchanged
