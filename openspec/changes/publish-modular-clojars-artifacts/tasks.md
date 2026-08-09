## 1. Confirm External Publication Access

- [x] 1.1 Add apex TXT record `clojars theronic` to `eacl.dev`, self-service verify Clojars group `dev.eacl`, confirm user `theronic` has all-project administrator/deploy rights, and record the successful public-API authorization preflight.
- [x] 1.2 Confirm `CLOJARS_USERNAME` contains Clojars username `theronic` rather than an email address and the initial `CLOJARS_DEPLOY_TOKEN` is reusable and unscoped so it can create four new artifacts.
- [x] 1.3 Configure the GitHub `clojars` environment for selected branch rules `v*` and temporary exact branch `codex/v8-demand-bounded-authorization`, remove `main` and all tag rules, add reviewer/admin-bypass protections where available, and verify ordinary CI jobs cannot read either secret.

## 2. Make Source Preparation Explicit

- [x] 2.1 Remove the core module's `:deps/prep-lib` hook and verify tools.deps resolution, IntelliJ classpath import, and application startup never launch formal installation or verification processes.
- [x] 2.2 Retain one explicit source-preparation build entry point that stages the JVM classes and browser bundle under `modules/eacl/target/generated`, and make a missing generated runtime fail with a concise pointer to the README rather than running a command.
- [x] 2.3 Add tests that simulate clean Maven and clean unprepared source dependencies and assert that formal tools are neither downloaded nor executed automatically.

## 3. Normalize Coordinates and Release Metadata

- [x] 3.1 Replace all `cloudafrica/eacl*` library and dependency coordinates in `modules/eacl*` with `dev.eacl/eacl*`, including build errors and module examples, while preserving the root README former-employer funding acknowledgement.
- [x] 3.2 Replace independent module version literals with one root-controlled version value that defaults to `8.0.0-SNAPSHOT` only for local builds and cannot override a guarded release-derived version.
- [x] 3.3 Add complete shared POM metadata for each artifact: description, project URL, SCM coordinates, developer information, EPL-2.0 licence, and exact dependency coordinates; include `LICENCE` in every JAR under `META-INF`.
- [x] 3.4 Add a coordinate audit over module sources, generated POMs, and JAR metadata that fails on any `cloudafrica/*` occurrence or any backend dependency on a non-matching core version.

## 4. Package a Configurable Generated Runtime Defaulting to Java 26

- [x] 4.1 Upgrade generated-runtime build and test CI to JDK 26, default Dafny-generated Java and runtime sources to `javac --release 26`, permit an explicit Java 8-through-26 target, and fail the release audit unless packaged classes match the selected class-file major version.
- [x] 4.2 Expand the core JAR audit to verify all required generated kernel/runtime classes, CLJ/CLJS production boundary sources, `deps.cljs`, and `EaclKernel.browser.js`, with no dependency on checkout-local `target/formal` paths.
- [x] 4.3 Run the generated-kernel smoke boundary from a clean classpath, assert the selected class version, and compile the complete generated source set at both the Java 26 default and an explicit older target.
- [x] 4.4 Document that one platform-neutral core JAR supports its selected Java target and newer JVMs without per-JVM builds, that Java 26 remains the default, and that backend dependencies may require a newer runtime.

## 5. Build and Audit the Four-Artifact Set

- [x] 5.1 Add a root release orchestrator, following Datahike's centralized build/deploy pattern, that validates one version and builds core, Datomic, Datahike, and DataScript JAR/POM pairs before any upload.
- [x] 5.2 Audit all four artifacts for coordinates, licence/POM metadata, expected contents, exact core dependency edges, direct backend dependencies, and absence of workspace-only paths.
- [x] 5.3 Install the complete set into a fresh temporary Maven repository and run clean core-only plus Datomic, Datahike, and DataScript consumer smoke programs using only Maven coordinates.
- [x] 5.4 Add deps-deploy as release-only tooling and implement serialized dependency-order upload of core, Datomic, Datahike, and DataScript with credentials supplied only through environment variables and never logged.
- [x] 5.5 Add failure tests proving no upload begins when any JAR, POM, generated entry, bytecode target, coordinate, isolated resolution, or smoke check is invalid.

## 6. Guard Ordinary and Exceptional Releases

- [x] 6.1 Implement and test a credential-free release guard that accepts ordinary publication only for a branch ref whose entire name matches `vMAJOR.MINOR.PATCH`, derives the identical Maven version, and rejects `main`, tags, pull requests, `release/v8.0`, partial matches, and supplied overrides.
- [x] 6.2 Make the ordinary gate wait for an explicit allowlist of Tests and Formal verification checks at exactly the release commit and reject absent, duplicate, pending past deadline, failed, cancelled, timed-out, skipped-when-required, or wrong-SHA results.
- [x] 6.3 Add a push-triggered `v*` release workflow whose deploy job repeats provenance assertions, needs the green exact-SHA gate, uses minimal read permissions, references `clojars`, and serializes deployments without cancelling an in-flight release.
- [x] 6.4 Implement and test the manual exception as exact constants for branch `codex/v8-demand-bounded-authorization` and version `8.0.0-SNAPSHOT`; bypass only the current CI/formal-success condition and reject `8.0-SNAPSHOT` plus every other ref/version/commit combination before environment access.
- [x] 6.5 Exercise workflow guard tests with synthetic event and check-run payloads so changes to trigger/ref semantics cannot make `main`, random branches, or tags deployable.

## 7. Document Consumer and Maintainer Workflows

- [x] 7.1 Update `core/README.md` module examples to use the four Clojars coordinates, show Maven consumption as the default, and retain documented Git/`:local/root` development alternatives without changing public namespaces.
- [x] 7.2 Place a prominent warning immediately before every source preparation or formal verification command explaining that it installs checksum-verified Dafny/Boogie/Z3, Apalache, and TLA+ tools, may install Node dependencies, writes under `target/formal-tools` and `target/formal`, and can consume substantial disk space and time.
- [x] 7.3 Distinguish explicit runtime generation from full formal verification, document the checksum lock and caches, and explain that published consumers perform neither operation.
- [x] 7.4 Update each module README with its `dev.eacl/eacl*` coordinate, exact transitive core behavior, supported host runtime, isolated build command, and local-source preparation link.
- [x] 7.5 Document release naming and recovery: `v8.1.0` maps to immutable `8.1.0`, `release/v8.0` and `main` never release, the initial snapshot is exactly `8.0.0-SNAPSHOT`, and partial/failed release retry rules preserve commit provenance.

## 8. Publish and Verify the Initial Snapshot

- [ ] 8.1 From the exact head of `codex/v8-demand-bounded-authorization`, manually dispatch the guarded exception and approve the protected environment to publish all four `8.0.0-SNAPSHOT` artifacts even if the current formal workflow is not green.
- [ ] 8.2 Inspect Clojars after deployment and verify each POM/JAR, transitive core dependency, EPL-2.0 metadata, generated runtime entries, bytecode target, and recorded source commit.
- [ ] 8.3 Update the sibling `eacl-datomic-solidjs` server to retain two explicit modes: base `dev.eacl/eacl-datomic` `8.0.0-SNAPSHOT` resolution and an `:eacl-local` override to the sibling `:local/root`; remove automatic `clojure -X:deps prep` from both modes.
- [ ] 8.4 Add paired Clojars/local command-line scripts and IntelliJ server/compound configurations, then run the application from a clean Maven cache in Clojars mode and from the prepared sibling checkout in local mode.

## 9. Remove Bootstrap Access and Complete Verification

- [ ] 9.1 Remove or permanently disable the snapshot exception and delete the exact temporary branch rule from the `clojars` GitHub environment immediately after the integration test succeeds.
- [ ] 9.2 Rotate the initial unscoped deploy token to a reusable token scoped to the verified `dev.eacl` group and replace `CLOJARS_DEPLOY_TOKEN` in the protected environment.
- [x] 9.3 Run module-isolated tests/builds, combined non-benchmark tests through nREPL, generated-runtime boundaries, clean Maven consumer tests, release-guard tests, and OpenSpec strict validation; record requirement-to-evidence results.
- [ ] 9.4 Review the final diffs in core and the reference consumer for unrelated changes, credential leakage, automatic formal execution, stale coordinates, mutable version inputs, generated build output, and accidental deployment routes.
