## Why

EACL source dependencies currently require consumers to obtain the formal toolchain and generate the production kernel before their applications can start. Publishing independently consumable module artifacts with the generated runtimes already packaged makes normal use predictable, while preserving an explicit source-development path and keeping costly formal installation and verification opt-in.

## What Changes

- Publish `dev.eacl/eacl`, `dev.eacl/eacl-datomic`, `dev.eacl/eacl-datahike`, and `dev.eacl/eacl-datascript` as separately consumable Clojars artifacts with one coordinated version.
- Package the generated JVM kernel classes and browser runtime in `dev.eacl/eacl`; backend artifacts depend transitively on that exact core version and do not duplicate the generated runtime.
- Remove automatic dependency preparation that downloads formal tools or runs Dafny for Git and `:local/root` consumers. Document the explicit source preparation and formal verification commands, with prominent disk-space and elapsed-time warnings.
- Replace `cloudafrica/*` Maven coordinates in all modules with `dev.eacl/eacl*`; preserve only the root README acknowledgement of former-employer funding.
- Add release metadata, artifact-content and isolated-consumer checks, an explicit generated-Java bytecode baseline, and a coordinated build/deploy entry point based on Datahike's multi-output release approach.
- Add defense-in-depth Clojars release controls: ordinary publication is possible only from a strictly versioned `vMAJOR.MINOR.PATCH` branch, derives the Maven version from that branch, and follows successful CI including the formal gate. `main`, pull requests, tags, and arbitrary branches cannot publish.
- Add one removable exception that permits only `codex/v8-demand-bounded-authorization` to publish exactly `8.0.0-SNAPSHOT` for the initial Clojars integration test, even though the existing formal workflow is not green. The inconsistent `8.0-SNAPSHOT` spelling is not used.

## Capabilities

### New Capabilities

- `clojars-release-pipeline`: Coordinated artifact construction, validation, guarded version derivation, credential use, and Clojars deployment for the four EACL modules.

### Modified Capabilities

- `modular-backend-workspace`: Make all four modules Maven-consumable under `dev.eacl/eacl*`, include prebuilt generated runtimes in core, and keep formal preparation explicit for source consumers.

## Impact

- Affects all module `deps.edn` and `build.clj` files, the root build aliases, generated-runtime compilation, POM metadata, CI/release workflows, README/module documentation, and consumer examples.
- Affects the sibling `eacl-datomic-solidjs` reference consumer: it will retain two first-class dependency modes—`dev.eacl/eacl-datomic` `8.0.0-SNAPSHOT` from Clojars and an explicit `:local/root` override—and stop preparing EACL on every server launch.
- Adds Clojars as a release target and requires the GitHub `clojars` environment to expose `CLOJARS_USERNAME` and `CLOJARS_DEPLOY_TOKEN` only to approved deployment refs.
- Uses the reverse-domain Maven group `dev.eacl`, verified for Clojars user `theronic` through the apex `eacl.dev` TXT record `clojars theronic`; the account has administrator and all-project deploy access.
- Makes Java compatibility explicit: EACL 8 defaults to the latest GA Java release (Java 26), permits an explicit Java 8-through-26 bytecode target for source/custom builds, and compiles the Dafny-generated Java sources once for the selected target rather than once per JVM.
