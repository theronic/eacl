# Modular Backend Workspace

## Purpose

Define the independently consumable EACL modules, shared backend contract, dependency boundaries, and compatibility guarantees for Datomic, DataScript, and third-party backend adapters.
## Requirements
### Requirement: Independently consumable modules
The repository SHALL provide backend-neutral `dev.eacl/eacl`, Datomic `dev.eacl/eacl-datomic`, DataScript `dev.eacl/eacl-datascript`, and Datahike `dev.eacl/eacl-datahike` modules, each with its own source root, dependency declaration, build entry point, documentation, and Clojars artifact.

#### Scenario: Core-only consumer
- **WHEN** a consumer resolves only `dev.eacl/eacl`
- **THEN** the public protocol, records, schema model, parser, consistency descriptors, shared engine, generated kernel runtime, and backend SPI load without Datomic, DataScript, or Datahike on the classpath

#### Scenario: Adapter consumer
- **WHEN** a consumer resolves one `dev.eacl/eacl-*` adapter artifact
- **THEN** that adapter declares the matching backend-neutral artifact and only its adapter-specific runtime dependencies

### Requirement: Stable public namespaces
The modular workspace SHALL preserve the existing public `eacl.*` namespace names and public Datomic client entry points while changing their physical source roots.

#### Scenario: Existing Datomic require forms
- **WHEN** an existing v8 Datomic application changes its dependency selection to `eacl-datomic`
- **THEN** its `eacl.core`, `eacl.datomic.core`, `eacl.datomic.schema`, and related require forms continue to resolve

### Requirement: V8 Datomic behavior parity
The Datomic module SHALL retain the behavior of the `release/v8.0` implementation, including consistency selection, encrypted pagination, forward and reverse lookup, count limits, object deletion, schema and relationship safety, and authorization caching.

#### Scenario: Full v8 regression suite
- **WHEN** the non-benchmark Datomic and migration tests run from module paths
- **THEN** they complete without failures or errors

#### Scenario: No storage migration from modularization
- **WHEN** an existing v8 Datomic database is used with the modular Datomic artifact
- **THEN** the modularization introduces no new persisted attribute, migration step, or cursor format change

### Requirement: Backend-neutral adapter operation contract
The core module SHALL define the backend boundary as one validated adapter value (`eacl.backend.v8/make-adapter`) whose operation map supplies exactly the required snapshot operations (`eacl.backend.v8/required-snapshot-operations`: snapshot identity, source scope and lifecycle, native revision and order hint, current/authoritative/at-least/exact selection, exact locator, object-id conversion in both directions, relation and permission definitions, ordered `subject->resources` and `resource->subjects` scans, `direct-match?`, and `all-permission-nodes`) together with declared capability sets and an optional `:proof-frame` operation. Shared code SHALL reach a backend only through `eacl.backend.v8/invoke`; index tuple layouts and implementation records stay inside the backend module.

#### Scenario: V8 adapter implementation
- **WHEN** a backend module constructs its snapshot adapter
- **THEN** it supplies every required operation and capability declaration, and construction fails closed with `:eacl/invalid-backend-adapter` when an operation is missing or a capability group is unknown

#### Scenario: Optional runtime guards
- **WHEN** an adapter is wrapped with `eacl.backend.v8/with-runtime-guards`
- **THEN** scan results that violate strict order, uniqueness, non-negativity, or the requested bound fail closed with `:eacl/backend-contract-violation` instead of feeding the engine

### Requirement: Shared backend contract
The workspace SHALL provide backend-neutral v7 compatibility and v8 conformance support that adapter tests can invoke against seeded schema and relationship data.

#### Scenario: DataScript and Datahike contract
- **WHEN** the DataScript or Datahike adapter seeds a shared contract fixture
- **THEN** schema round-trip, direct, arrow, recursive permission checks, lookup/count operations, Relay behavior, caching, and relationship writes are verified for its declared capabilities

#### Scenario: Datomic contract and v8-specific tests
- **WHEN** the Datomic adapter is validated
- **THEN** the shared v8 behavioral contract runs alongside Datomic-specific consistency, cursor, cache-proof, and regression tests

### Requirement: Dependency isolation
Each published module SHALL declare all dependencies it directly requires and SHALL NOT rely on the root workspace to mask missing dependencies.

#### Scenario: Isolated module load
- **WHEN** each module's namespaces are loaded using that module's own dependency basis
- **THEN** namespace loading succeeds without undeclared root-only dependencies

#### Scenario: Core dependency graph
- **WHEN** the backend-neutral module dependency graph is inspected
- **THEN** it contains neither Datomic nor DataScript

### Requirement: Consumer-owned logging
Published EACL module dependencies SHALL NOT select Logback or another logging backend for consuming applications.

#### Scenario: Application logging choice
- **WHEN** an application depends on an EACL module
- **THEN** EACL does not add a direct logging implementation or repository-owned logging configuration to the application's runtime

### Requirement: Workspace build and test entry points
The root workspace SHALL expose build and nREPL test paths for all four modules, and CI SHALL exercise their isolated builds plus the combined non-benchmark suite.

#### Scenario: Root development workflow
- **WHEN** a developer starts the configured nREPL from the root workspace
- **THEN** all shared, Datomic, DataScript, and Datahike source and test namespaces are available

#### Scenario: Isolated module workflow
- **WHEN** a module's build or tests run from that module's own dependency basis
- **THEN** all direct source, test-support, and build dependencies resolve without relying on the root basis

#### Scenario: CI validation
- **WHEN** the repository test workflow runs
- **THEN** it exercises the shared contract and module suites and excludes only tests explicitly marked as benchmarks

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

#### Scenario: Existing monolithic consumer
- **WHEN** a current EACL v8 Datomic consumer reads the upgrade instructions
- **THEN** it can identify the Datomic module dependency without changing its existing public namespace usage

#### Scenario: DataScript or Datahike consumer
- **WHEN** a v7 DataScript or Datahike consumer reads the upgrade instructions
- **THEN** it can migrate legacy pagination, count, cache, recursive schema, and consistency usage to the adapter's declared v8 contract

#### Scenario: Third-party adapter author
- **WHEN** a backend author reads the extension guidance
- **THEN** the legacy SPI, v8 adapter operations, capability declarations, cache proof contract, and conformance-test entry points are explicit

### Requirement: Shared cryptographic format service
The core module SHALL own versioned, bounded, canonical cryptographic formats for Zed tokens, cursor envelopes, and authorization-affecting cache entries. It SHALL use distinct signing/encryption domains and derived keys, key ids, rotation keyrings, strict field allowlists, constant-time authentication checks, and exact portable numeric representations. Cursor authentication is mandatory; cursor confidentiality SHALL be an independently advertised capability.

#### Scenario: Portable cursor is created
- **WHEN** Datahike or DataScript emits a cursor
- **THEN** shared authentication protects its scope, stable position, and dependency proof
- **AND** the cursor contains no unserializable backend object

#### Scenario: Datomic encrypted cursor is created
- **WHEN** the Datomic adapter advertises confidential cursors
- **THEN** it uses authenticated encryption in addition to mandatory authenticity

#### Scenario: Synchronous browser client has no synchronous AEAD
- **WHEN** DataScript runs behind a synchronous ClojureScript API without compatible synchronous encryption
- **THEN** it may emit an authenticated non-confidential cursor using stable external identities/digests
- **AND** MUST NOT advertise cursor confidentiality

#### Scenario: Cache entry is read from a shared provider
- **WHEN** a provider returns a completed authorization value
- **THEN** shared code authenticates the embedded complete key, causal metadata, proof, and value before considering it

#### Scenario: Decoder receives hostile input
- **WHEN** a token exceeds size/depth limits, contains unknown fields, has an unsupported numeric representation, or fails authentication
- **THEN** decoding fails with a bounded typed error

### Requirement: Shared conformance and reference-model suite
The core module SHALL provide a backend contract suite and deterministic full-content reference model covering causal tokens, source scope, authoritative selection, dependency completeness, proof lifting, cursor continuation, exact expiry, and cache integrity. Every bundled adapter MUST run applicable scenarios with real backend transaction and snapshot APIs.

#### Scenario: Bundled backend validation
- **WHEN** the non-benchmark module suite runs
- **THEN** Datomic, Datahike, and DataScript pass all shared guarantees they advertise
- **AND** unsupported configuration variants fail before authorization

#### Scenario: Generated divergence trace
- **WHEN** a generated trace clones, restores, resets, branches, force-moves, or reuses transaction numbers for different content
- **THEN** no bundled adapter accepts numeric equality as causal or dependency equality

#### Scenario: Differential cache oracle
- **WHEN** any generated cached request returns a result
- **THEN** it equals uncached deterministic evaluation on the graph identified by the response token

#### Scenario: Differential cursor oracle
- **WHEN** generated mutations occur between pages
- **THEN** concatenated pages equal enumeration of the original exact graph or a graph with an equal complete dependency proof

### Requirement: Portable cache and query scopes include configuration identity
The core contract SHALL require deterministic fingerprints for adapter implementation, object-id codecs, recursion/traversal limits, caveat configuration, and every option capable of changing authorization or ordering. Mutable identity, caveat, and adapter data SHALL additionally provide snapshot dependency proofs; a function/configuration fingerprint alone is insufficient. A backend unable to provide complete stable fingerprints and proofs MUST disable completed-answer caching and graph-equivalent cursors.

#### Scenario: Adapter configuration changes
- **WHEN** two clients share a cache but use different answer-affecting configurations
- **THEN** their semantic keys and cursor scopes cannot validate against each other

#### Scenario: Adapter reads undeclared mutable state
- **WHEN** a primitive or codec depends on external mutable state absent from the fingerprint/proof
- **THEN** adapter validation rejects the cache/cursor capability claim

### Requirement: Old portable formats fail closed
The core module SHALL reject pre-change listener-counter tokens, unauthenticated base64 cursors, and cache entries lacking causal, proof, complete-key, or authentication fields.

#### Scenario: Decimal listener token is supplied
- **WHEN** a caller supplies an old DataScript or Datahike listener counter
- **THEN** EACL returns a typed unsupported-token-version error

#### Scenario: Old cursor is supplied
- **WHEN** a caller supplies an unauthenticated portable cursor
- **THEN** EACL rejects it as unsupported/invalid and requires pagination restart

#### Scenario: Old cache entry is returned
- **WHEN** a provider returns a prior entry format
- **THEN** EACL treats it as a miss and never interprets missing security fields

### Requirement: Graph-independent coherence adapter contract
The backend-neutral adapter contract SHALL expose native immutable snapshot identity, source lifecycle, revision selection capabilities, one complete ordered-generation proof context, and explicit lifecycle expiry without requiring mutation-graph head, anchor-membership, journal-retention, cache-authority, alternate proof-mode, or duplicate managed-descriptor operations.

#### Scenario: Cache-capable adapter
- **WHEN** an adapter supplies stable current-snapshot identity and a complete certified ordered-generation proof while its mutations obey the supported-writer contract
- **THEN** the shared engine can provide exact-current and managed-current caching without graph metadata or configuration authority

#### Scenario: Managed proof unavailable
- **WHEN** an adapter cannot provide complete proof evidence for one selected request
- **THEN** the shared engine evaluates that request exactly without treating the adapter as a separate coherence mode

#### Scenario: Native consistency capability
- **WHEN** an adapter advertises at-least, fully-consistent, or exact selection
- **THEN** it supplies the corresponding native selection operation and source-lifecycle validation independently of completed-answer proof availability

#### Scenario: History replacement
- **WHEN** an adapter or operator replaces source history
- **THEN** it rotates the source lifecycle before the client resumes cached authorization requests

### Requirement: Backend dependency isolation after graph removal
Removing the portable mutation graph SHALL preserve module dependency isolation and SHALL keep all backend-native revision and transaction-function code in the corresponding adapter module.

#### Scenario: Core-only consumer
- **WHEN** a consumer loads only the backend-neutral module
- **THEN** graph-independent cache orchestration and adapter contracts load without Datomic, Datahike, or DataScript dependencies

#### Scenario: Backend-specific token selection
- **WHEN** an adapter implements its native revision token fields and selection operations
- **THEN** those runtime dependencies remain confined to that adapter artifact

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

### Requirement: Uniform automatic cache configuration
Every bundled backend SHALL expose one automatic proof-backed completed-cache behavior and SHALL reject `:coherence-authority` and `:proof-mode` as unknown client configuration. Third-party adapters SHALL NOT expose authority selection; an adapter without a complete certified proof capability SHALL fail closed to exact evaluation or no caching for that request.

#### Scenario: Removed coherence option
- **WHEN** a consumer constructs a bundled backend client with either former `:coherence-authority` value
- **THEN** construction fails with the stable invalid-configuration error identifying `:coherence-authority` as an unknown key

#### Scenario: Removed proof option
- **WHEN** a consumer constructs a bundled backend client with any former `:proof-mode` value
- **THEN** construction fails with the stable invalid-configuration error identifying `:proof-mode` as an unknown key

#### Scenario: Third-party adapter lacks proof support
- **WHEN** a third-party adapter cannot supply a complete certified proof for an otherwise cache-enabled request
- **THEN** shared orchestration evaluates the selected exact snapshot without reusing an unproved managed answer

### Requirement: One immutable request proof frame
The backend-neutral adapter contract SHALL expose one immutable proof context for the exact selected adapter, lifecycle, and database value. Completed answers, managed subproblems, schema planning, and cursor validation SHALL share that request context rather than acquiring semantically duplicate proof evidence independently.

#### Scenario: Exact cache hits first
- **WHEN** exact-snapshot lookup succeeds before a proof context is needed
- **THEN** the adapter performs no managed relation-generation reads for that completed answer

#### Scenario: Several request consumers need proof
- **WHEN** schema planning, a completed-answer lookup, managed subproblems, and cursor validation require the same schema and relation evidence
- **THEN** they share the lazily acquired immutable context scoped to that request

#### Scenario: Subproblem adds an unproved relation
- **WHEN** a managed subproblem declares a dependency outside the complete relation set established by its request proof context
- **THEN** that subproblem is not admitted as a managed hit or publication from partial evidence

#### Scenario: Proof provider fails
- **WHEN** the adapter proof operation throws or returns malformed or incomplete evidence
- **THEN** the request remains exact-only and no partial proof context is retained

### Requirement: Certified ordered-generation adapter capability
A cache-capable adapter SHALL certify immutable snapshot identity, source lifecycle, complete canonical dependency generation reads, schema generation, and native transaction generations that are globally ordered across supported commits. Adapters without the ordered-generation capability SHALL remain valid exact-current adapters.

#### Scenario: Bundled backend certification
- **WHEN** Datomic, Datahike, or DataScript executes a supported relationship mutation
- **THEN** adapter certification observes that every affected relation stamp equals the committed transaction generation and exceeds every prior relation stamp

#### Scenario: Exact-current-only adapter
- **WHEN** an adapter supplies stable immutable snapshot identity but not certified ordered generations
- **THEN** the shared engine may use exact-current caching without cross-snapshot managed reuse

