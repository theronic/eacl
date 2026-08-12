## MODIFIED Requirements

### Requirement: Independently consumable modules
The repository SHALL provide backend-neutral `dev.eacl/eacl`, Datomic `dev.eacl/eacl-datomic`, DataScript `dev.eacl/eacl-datascript`, and Datahike `dev.eacl/eacl-datahike` modules, each with its own source root, dependency declaration, build entry point, documentation, and Clojars artifact.

#### Scenario: Core-only consumer
- **WHEN** a consumer resolves only `dev.eacl/eacl`
- **THEN** the public protocol, records, schema model, parser, consistency descriptors, shared engine, generated kernel runtime, and backend SPI load without Datomic, DataScript, or Datahike on the classpath

#### Scenario: Adapter consumer
- **WHEN** a consumer resolves one `dev.eacl/eacl-*` adapter artifact
- **THEN** that adapter declares the matching backend-neutral artifact and only its adapter-specific runtime dependencies

### Requirement: Upgrade documentation
Documentation SHALL explain Clojars and source-based module selection for Datomic, DataScript, Datahike, core-only, and third-party backend consumers; SHALL document the source preparation and local-development workflow; and SHALL warn before every command that downloads formal tools or performs formal verification.

#### Scenario: Published artifact consumer
- **WHEN** a consumer reads the module installation instructions
- **THEN** it can choose one stable Maven coordinate and run EACL without installing or invoking the formal toolchain

#### Scenario: Local source consumer
- **WHEN** a Git or `:local/root` consumer reads the source instructions
- **THEN** it is told which explicit preparation command is required, which formal tools and generated outputs it affects, and that the download and verification can require substantial disk space and elapsed time

#### Scenario: Local development override
- **WHEN** an application developer needs to test an EACL checkout instead of Clojars
- **THEN** the documentation provides a `:local/root` or alias override and the explicit preparation steps without changing application namespaces

## ADDED Requirements

### Requirement: Formal tooling is opt-in for consumers
Resolving, preparing the classpath for, or starting an application with an EACL Maven, Git, or `:local/root` dependency SHALL NOT automatically download Dafny, Apalache, TLA+ tools, Node packages, or other formal toolchain components and SHALL NOT automatically run formal verification. Formal tool installation, generated-runtime rebuilding, and verification SHALL occur only after a user invokes a clearly documented explicit command.

#### Scenario: Maven dependency resolution
- **WHEN** a clean consumer resolves a published EACL artifact and starts its application
- **THEN** no formal tool is downloaded or executed and the packaged generated runtime is used

#### Scenario: Source dependency resolution
- **WHEN** a clean consumer resolves an EACL Git or `:local/root` dependency without opting into preparation
- **THEN** dependency resolution itself performs no formal work
- **AND** any missing-runtime failure points to the README's explicit source preparation instructions

#### Scenario: Explicit source preparation
- **WHEN** a source consumer intentionally invokes the documented preparation command
- **THEN** EACL may install the checksum-verified formal dependencies and regenerate the required runtime after displaying the documented cost warning

### Requirement: EACL domain module coordinates
All dependency and artifact coordinates within the four modules SHALL use `dev.eacl/eacl` or the applicable `dev.eacl/eacl-*` name. Module build output, POMs, dependency declarations, errors, and examples SHALL contain no `cloudafrica/*` or stale `theronic/eacl*` coordinate.

#### Scenario: Module coordinate audit
- **WHEN** production module files and built metadata are scanned
- **THEN** every EACL coordinate uses the `dev.eacl` group and no `cloudafrica/*` or stale `theronic/eacl*` occurrence is present

#### Scenario: Funding acknowledgement retained
- **WHEN** the repository README is reviewed
- **THEN** the existing former-employer funding acknowledgement may remain as non-coordinate historical attribution

### Requirement: Reference consumer dependency modes
The `eacl-datomic-solidjs` reference application SHALL retain two documented and tested dependency modes: resolving `dev.eacl/eacl-datomic` `8.0.0-SNAPSHOT` from Clojars and resolving the sibling EACL checkout through `:local/root` for local development. The modes SHALL be selectable through separate aliases, scripts, and IntelliJ launch configurations. Neither mode's normal server or nREPL launch SHALL prepare EACL or invoke formal tooling.

#### Scenario: Default IntelliJ application launch
- **WHEN** a developer imports the reference application and runs its Clojars-backed server and client configuration
- **THEN** the server resolves the Clojars snapshot and starts without an EACL checkout or formal-tool preparation command

#### Scenario: Explicit local EACL development
- **WHEN** a developer selects the documented local-source alias, script, or IntelliJ configuration after explicitly preparing the EACL checkout
- **THEN** the reference server resolves the sibling `modules/eacl-datomic` source tree while preserving the same application namespaces and run entry point
