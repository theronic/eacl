# clojars-release-pipeline Specification

## Purpose
Define a secure, coordinated Clojars publication contract for EACL's core and backend artifacts, including version provenance, generated-runtime integrity, and exceptional snapshot handling.
## Requirements
### Requirement: Coordinated module publication
The release pipeline SHALL build and publish `dev.eacl/eacl`, `dev.eacl/eacl-datomic`, `dev.eacl/eacl-datahike`, and `dev.eacl/eacl-datascript` at one identical Maven version. Each backend POM SHALL depend on `dev.eacl/eacl` at that exact version and SHALL declare its own runtime dependencies.

#### Scenario: Four-artifact release set
- **WHEN** a release candidate is prepared for publication
- **THEN** all four JARs and POMs are built and validated before the first remote upload
- **AND** core is deployed before the backend artifacts that depend on it

#### Scenario: Backend-only consumer
- **WHEN** a consumer resolves one backend artifact from Clojars
- **THEN** Maven dependency resolution brings in the matching core artifact and only that adapter's declared runtime dependencies

### Requirement: Publish complete verifiable artifacts
The core release artifact SHALL contain the Clojure and ClojureScript sources, `deps.cljs`, the generated JVM kernel classes and their Dafny runtime classes, and the generated browser runtime. Every release POM SHALL contain valid coordinates, project metadata, source-control information, and the EPL-2.0 licence declaration required by Clojars.

#### Scenario: Clean Maven consumption
- **WHEN** a clean consumer resolves a published backend and loads its public entry point
- **THEN** the application runs without an EACL checkout, generated files, or formal tools on the consumer machine

#### Scenario: Pre-deploy artifact audit
- **WHEN** the release set is validated before upload
- **THEN** required core entries, POM coordinates, licence metadata, exact core dependency versions, and the absence of undeclared workspace paths are verified

### Requirement: Portable generated JVM bytecode
The EACL 8 generated kernel build SHALL default to Java 25 and SHALL permit an explicit whole-number bytecode target from Java 8 through Java 26 for source and custom artifact builds. The artifact audit SHALL derive the expected class-file major version from the selected target. A generated artifact SHALL NOT vary by operating system, processor architecture, or installed JVM patch version, and SHALL run on its selected Java release and newer JVMs without requiring JVM-specific EACL classes.

#### Scenario: Default target execution
- **WHEN** no bytecode override is supplied and the packaged core artifact is built and exercised
- **THEN** its generated classes use Java 25 class-file major version 69 and produce the expected smoke result

#### Scenario: Newer-JVM execution
- **WHEN** an artifact compiled for a selected Java target is exercised on the same or a later supported JVM
- **THEN** the generated kernel produces the same boundary result without recompilation

#### Scenario: Explicit older target
- **WHEN** a source or custom artifact build explicitly selects Java 8 through Java 26
- **THEN** the generated sources are compiled and audited at that target and the artifact can run on that Java release or newer, subject to backend dependency requirements

### Requirement: Ordinary release provenance and gating
Ordinary remote publication SHALL occur only for a branch whose entire name matches `vMAJOR.MINOR.PATCH`, SHALL derive Maven version `MAJOR.MINOR.PATCH` from that branch, and SHALL require the repository's test and formal-verification gates to succeed for the same commit. `main`, pull requests, tags, branch names that merely contain a version, and caller-supplied version overrides SHALL NOT publish.

#### Scenario: Green version branch
- **WHEN** branch `v8.1.0` completes every required gate successfully for a commit
- **THEN** the release pipeline is eligible to publish that commit only as version `8.1.0`

#### Scenario: Main or arbitrary branch
- **WHEN** CI runs on `main`, `release/v8.0`, `feature/example`, or another non-matching ref
- **THEN** no job with Clojars credentials can upload an artifact

#### Scenario: Failed or mismatched CI
- **WHEN** any required gate fails, times out, is cancelled, or reports a different source commit
- **THEN** ordinary publication is rejected before Clojars credentials are used

### Requirement: Narrow initial snapshot exception
The release pipeline SHALL provide one explicit exception that accepts only branch `codex/v8-demand-bounded-authorization`, source commit checked out from that branch, and Maven version `8.0.0-SNAPSHOT`. This exception SHALL permit the initial snapshot despite the existing formal-workflow result, require an intentional manual dispatch and protected `clojars` environment access, and be removed or disabled after the integration test succeeds.

#### Scenario: Authorized initial snapshot
- **WHEN** an authorized maintainer manually dispatches the exception from `codex/v8-demand-bounded-authorization` with exact version `8.0.0-SNAPSHOT`
- **THEN** the four validated artifacts may be deployed without the ordinary formal-success condition

#### Scenario: Exception input changed
- **WHEN** the exception receives any other branch, ref type, commit provenance, or version, including `8.0-SNAPSHOT`
- **THEN** it fails before credentials are exposed or an upload begins

### Requirement: Protected deployment credentials
Only a job that references the GitHub `clojars` environment after satisfying repository-side release guards SHALL receive the Clojars username and deploy token. The workflow SHALL map those secrets to the deployment tool without printing them and SHALL serialize deployments to prevent concurrent releases.

#### Scenario: Non-deployment workflow
- **WHEN** tests, pull requests, or builds run without an eligible deployment job
- **THEN** neither Clojars secret is available to those jobs

#### Scenario: Unauthorized Maven group
- **WHEN** the configured Clojars user cannot deploy the verified `dev.eacl` group
- **THEN** a preflight fails before artifact upload and reports that group creation or authorization is required
