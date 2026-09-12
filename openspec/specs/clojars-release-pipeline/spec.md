# clojars-release-pipeline Specification

## Purpose
Define a secure, coordinated Clojars publication contract for EACL's core and backend artifacts, including version provenance, generated-runtime integrity, and exceptional snapshot handling.

## Requirements

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

### Requirement: Tagged release provenance and gating
Publication SHALL require a version tag pushed or manually dispatched on that tag. Tags SHALL match `vMAJOR.MINOR.PATCH`, `vMAJOR.MINOR.PATCH-RC-YYYY-MM-DD`, or `vMAJOR.MINOR.PATCH-SNAPSHOT-YYYY-MM-DD[THHMMSSZ]`. Snapshot tags SHALL publish `MAJOR.MINOR.PATCH-SNAPSHOT`; other tags SHALL publish their version without the leading `v`. The tag SHALL be unchanged, merged into `vMAJOR.MINOR.PATCH-SNAPSHOT`, and have the same complete Git tree as that branch's current head.

The latest push-event runs of `.github/workflows/test.yml` and `.github/workflows/formal.yml` for the exact tagged SHA SHALL both be completed successfully. Their required check jobs SHALL all be successful, correlated through check-suite IDs. PR-event results, unrelated workflows, and different commits SHALL NOT satisfy the gate. Incomplete listings SHALL be rejected. Missing, pending, or failed CI SHALL fail immediately. Publication SHALL reuse generated runtime artifacts from that verified Tests run without rerunning formal verification.

#### Scenario: Green PR head merged without content changes
- **WHEN** the green PR head becomes an ancestor of the snapshot branch and the complete Git trees are identical
- **THEN** a tag on that PR head may reuse its push CI, without waiting for the merge commit's redundant CI

#### Scenario: Versioned snapshot and release candidate
- **WHEN** tags `v8.0.0-SNAPSHOT-2026-09-12` and `v8.0.0-RC-2026-09-12` identify the same admitted green source
- **THEN** they publish `8.0.0-SNAPSHOT` and `8.0.0-RC-2026-09-12` through separately approved, serialized deployments

#### Scenario: Branch push or manual dispatch on a branch
- **WHEN** code is pushed to `main`, a feature branch, or the snapshot branch, or publication is manually dispatched on a branch
- **THEN** no Clojars publication occurs

#### Scenario: CI or source changed while approval was pending
- **WHEN** approval is granted after the tag or branch tree changed, or the latest required source CI is no longer green
- **THEN** the repeated provenance checks reject publication before upload

### Requirement: No verification exception
Every publication, including snapshots, SHALL require green Tests and Formal verification. The historical initial-snapshot exception SHALL remain removed.

### Requirement: Protected deployment credentials
Only a job that references the GitHub `clojars` environment after satisfying repository-side release guards SHALL receive the Clojars username and deploy token. The workflow SHALL map those secrets to the deployment tool without printing them and SHALL serialize deployments to prevent concurrent releases.

#### Scenario: Non-deployment workflow
- **WHEN** tests, pull requests, or builds run without an eligible deployment job
- **THEN** neither Clojars secret is available to those jobs

#### Scenario: Unauthorized Maven group
- **WHEN** the configured Clojars user cannot deploy the verified `dev.eacl` group
- **THEN** a preflight fails before artifact upload and reports that group creation or authorization is required

### Requirement: Coordinated release-set publication
The release pipeline SHALL build and publish `dev.eacl/eacl`, `dev.eacl/eacl-caveats-jvm`, `dev.eacl/eacl-datomic`, `dev.eacl/eacl-datahike`, and `dev.eacl/eacl-datascript` at one identical Maven version. Each dependent POM SHALL depend on `dev.eacl/eacl` at that exact version and SHALL declare its own runtime dependencies.

#### Scenario: Coordinated release set
- **WHEN** a release candidate is prepared for publication
- **THEN** all five JARs and POMs are built and validated before the first remote upload
- **AND** core is deployed before the artifacts that depend on it

#### Scenario: Backend-only consumer
- **WHEN** a consumer resolves one backend artifact from Clojars
- **THEN** Maven dependency resolution brings in the matching core artifact and only that adapter's declared runtime dependencies
