## MODIFIED Requirements

### Requirement: Coordinated release-set publication
The release pipeline SHALL build and publish `dev.eacl/eacl`, `dev.eacl/eacl-caveats-jvm`, `dev.eacl/eacl-caveats-portable`, `dev.eacl/eacl-datomic`, `dev.eacl/eacl-datahike`, and `dev.eacl/eacl-datascript` at one identical Maven version. Each dependent POM SHALL depend on `dev.eacl/eacl` at that exact version and SHALL declare its own runtime dependencies.

#### Scenario: Coordinated release set
- **WHEN** a release candidate is prepared for publication
- **THEN** all six JARs and POMs are built and validated before the first remote upload
- **AND** core is deployed before the artifacts that depend on it

#### Scenario: Backend-only consumer
- **WHEN** a consumer resolves one backend artifact from Clojars
- **THEN** Maven dependency resolution brings in the matching core artifact and only that adapter's declared runtime dependencies

#### Scenario: Evaluator-only consumer
- **WHEN** a consumer resolves `dev.eacl/eacl-caveats-portable` from Clojars
- **THEN** Maven dependency resolution brings in only the matching core artifact, with no CEL, ANTLR or backend dependency
