## REMOVED Requirements

### Requirement: Coordinated module publication
**Reason**: The coordinated release set gained `dev.eacl/eacl-caveats-jvm` with the v9 Caveat foundation; the four-artifact wording no longer describes the pipeline.
**Migration**: Replaced by the five-module requirement "Coordinated release-set publication" below.

## ADDED Requirements

### Requirement: Coordinated release-set publication
The release pipeline SHALL build and publish `dev.eacl/eacl`, `dev.eacl/eacl-caveats-jvm`, `dev.eacl/eacl-datomic`, `dev.eacl/eacl-datahike`, and `dev.eacl/eacl-datascript` at one identical Maven version. Each dependent POM SHALL depend on `dev.eacl/eacl` at that exact version and SHALL declare its own runtime dependencies.

#### Scenario: Coordinated release set
- **WHEN** a release candidate is prepared for publication
- **THEN** all five JARs and POMs are built and validated before the first remote upload
- **AND** core is deployed before the artifacts that depend on it

#### Scenario: Backend-only consumer
- **WHEN** a consumer resolves one backend artifact from Clojars
- **THEN** Maven dependency resolution brings in the matching core artifact and only that adapter's declared runtime dependencies

## MODIFIED Requirements

### Requirement: Ordinary release provenance and gating
Ordinary remote publication SHALL occur only for a branch whose entire name matches `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-SNAPSHOT`, SHALL derive the Maven version from that branch name, and SHALL require the repository's test and formal-verification gates to succeed for the same commit. Release evidence SHALL consist only of the check runs produced by the release branch's own push-event workflow runs at the release commit, correlated through their check suites. Check runs the same commit carries from pull-request events or from other branches SHALL be ignored: they SHALL neither satisfy a required gate nor be reported as ambiguous duplicates. A listing page that omits results GitHub counted SHALL be rejected rather than judged. `main`, pull requests, tags, branch names that merely contain a version, and caller-supplied version overrides SHALL NOT publish.

#### Scenario: Green version branch
- **WHEN** branch `v8.1.0` completes every required gate successfully for a commit
- **THEN** the release pipeline is eligible to publish that commit only as version `8.1.0`

#### Scenario: Same commit verified by other refs
- **WHEN** the release commit also carries Tests and Formal verification results from an open pull request headed by the release branch, or from another branch pushed to the same commit
- **THEN** the gate judges only the release branch's push-event results and publishes once those succeed

#### Scenario: Main or arbitrary branch
- **WHEN** CI runs on `main`, `release/v8.0`, `feature/example`, or another non-matching ref
- **THEN** no job with Clojars credentials can upload an artifact

#### Scenario: Failed or mismatched CI
- **WHEN** any required gate fails, times out, is cancelled, or reports a different source commit
- **THEN** ordinary publication is rejected before Clojars credentials are used
