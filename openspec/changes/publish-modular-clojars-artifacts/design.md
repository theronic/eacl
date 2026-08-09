## Context

See `proposal.md` for motivation and the two delta specs for the observable contract. The workspace already has independent tools.build entry points for four modules and CI produces `target/formal/java/classes` plus `target/formal/browser/EaclKernel.browser.js`. The core JAR build already checks representative generated entries, but module coordinates remain `cloudafrica/*`, versions are hard-coded independently, generated Java is compiled with the ambient JDK default, and no remote deployment workflow exists.

Recent local changes added `:deps/prep-lib` to the core module and a `prep` function that installs formal tooling, regenerates both runtimes, and stages them inside the module. This solves classpath preparation but violates the new opt-in requirement and must not be retained as an automatic dependency hook.

The formal lock currently identifies these downloaded components:

- Dafny 4.11.0, including its bundled Boogie verifier, Z3 builds, Java/JavaScript translators, and `DafnyRuntime.jar`;
- Apalache 0.58.3 for bounded TLA+ model checking;
- `tla2tools.jar` 1.7.4 for TLA+ parsing and checking;
- repository-managed Node packages used to smoke-test and bundle generated JavaScript; and
- clj-kondo as a separately pinned source-closure checker in CI.

`bin/formal bootstrap` installs and checksum-checks the first three tool distributions under `target/formal-tools`; later commands may also run `npm ci` and create generated, verification, model-checking, and diagnostic outputs under `target/formal`. Translation through Dafny performs proof checking for the translated program, while `bin/formal verify` performs the full explicitly bounded Dafny verification set. These costs belong in maintainer CI or an intentional source workflow, not dependency resolution.

Datahike provides three patterns worth retaining: one release configuration drives artifact identity, tools.build creates the JAR/POM, and deps-deploy performs Clojars upload. Its Java build also demonstrates that the bytecode target should be explicit rather than inherited from the ambient JDK. EACL differs by publishing a dependency-ordered set of four artifacts, targeting the latest GA Java, and requiring narrower release provenance than Datahike's current main-branch deployment.

Clojars group ownership is satisfied through the reverse-domain group `dev.eacl`. The apex `eacl.dev` zone is authoritative in Route 53 and already serves the repository's GitHub Pages deployment. An apex TXT record with value `clojars theronic` was added without changing the Pages A, AAAA, or CNAME records; Clojars self-service verification then created and verified `dev.eacl` with user `theronic` as administrator at all-project scope.

## Goals / Non-Goals

**Goals:**

- Build one internally consistent release set before any remote mutation.
- Make artifact identity, POM dependency edges, licence metadata, generated-runtime content, and source commit machine-verifiable.
- Keep release credentials out of ordinary CI and enforce branch/version policy in both GitHub configuration and repository code.
- Make Maven consumption independent of Dafny/TLA+/Node tooling while retaining a documented explicit source-development path.
- Publish Java bytecode that is portable across supported JVMs rather than tied to the CI JDK.

**Non-Goals:**

- Publishing formal tools, formal source trees, verification logs, or proof reports inside Maven artifacts.
- Guaranteeing that every backend library supports every JVM accepted by the core generated classes; backend dependency requirements still apply.
- Publishing from the historical `release/v8.0` name, `main`, a tag, or a Git SHA dependency.
- Making the initial CI-bypass exception reusable for later snapshots or releases.
- Changing EACL public Clojure namespace names or backend behavior.

## Decisions

### 1. Keep generated authority in the core artifact only

`dev.eacl/eacl` will contain the existing CLJ/CLJS source tree, `deps.cljs`, the browser bundle, all generated kernel classes, and the Dafny-generated/runtime classes needed by the production boundary. Adapter JARs contain only adapter source/resources and depend on core at the same Maven version.

The root release build will validate the complete release set, not only a few representative class names. It will inspect JAR entries and POMs, resolve each artifact through a clean temporary Maven repository, load core and adapter entry points, and invoke a generated-kernel smoke boundary without allowing the EACL checkout or `target/formal` onto the classpath.

Alternative considered: duplicate generated classes into every backend JAR. That makes each backend superficially standalone but creates split-package collisions, larger downloads, and a risk that backends carry different generated kernels.

### 2. Remove automatic preparation and retain an explicit source command

Remove `:deps/prep-lib` from `modules/eacl/deps.edn`. Keep module-local generated paths so IntelliJ and tools.deps do not emit external-path warnings, but populate them only through an explicit maintainer/source-consumer command such as `clojure -T:build prep` from `modules/eacl` (or a root wrapper). That command may invoke `bin/formal build-java` and `bin/formal browser-bundle`, both of which bootstrap missing locked tools; the README warning must appear immediately before the command and explain the installed components and output locations. Full `bin/formal verify` remains a separate explicit command with its own warning.

A missing source-generated runtime will produce a short actionable failure that links to the README section; it will not trigger a process. Maven artifacts use their packaged runtime and never consult module-local generated paths.

Alternative considered: leave Clojure CLI preparation enabled but add a prompt. Dependency preparation is designed to be non-interactive and may occur in IDE import or classpath calculation, so a prompt is unreliable and still surprising.

The sibling `eacl-datomic-solidjs` application will remove its `prep:server` launch step and retain two first-class paths. Its base server basis will use `dev.eacl/eacl-datomic` `8.0.0-SNAPSHOT`; an `:eacl-local` alias will override the same library coordinate with `../../core/modules/eacl-datomic`. Paired command-line scripts and IntelliJ server/compound configurations will make the Clojars and local modes explicit rather than requiring file edits. The local documentation will send the developer to the explicit core preparation command before IntelliJ classpath refresh. Because this reference-consumer edit is outside the `core` OpenSpec project root, implementation must either apply the authorized sibling edit directly or track it in a linked downstream change; the acceptance test remains part of this release outcome.

### 3. Default generated Java to the latest GA target with an older-Java override

For EACL 8, generated Java compilation defaults to `javac --release 26`, the latest GA Java feature release as of August 2026. A source or custom artifact build may explicitly select a whole-number release from Java 8 through Java 26 through the build option or `EACL_JAVA_RELEASE`. The selected numeric release is carried through preparation, artifact construction, class-file audit, and Maven smoke checks; no floating `latest` input may silently change it.

Java `.class` files are platform-neutral bytecode: they are not tied to a JVM patch, OS, or CPU, and newer JVMs load older class-file versions. The complete Dafny-generated source set compiles with `--release 8` (class-file major 52), while the default Java 26 build produces major 70. CI and the ordinary Clojars workflow retain JDK/target 26 unless a reviewed release change selects otherwise. An older-target custom artifact can run on the selected JVM or newer, but each backend's transitive dependencies may impose a higher runtime minimum.

Alternative considered: publish classifiers per JVM. Configurable whole-artifact builds retain one unambiguous bytecode target and avoid classifier complexity; an immutable published coordinate must never be rebuilt with a different target.

### 4. Drive all builds from one validated version

Introduce a root release/build orchestrator under the existing build tooling. It owns an ordered module table and supplies one immutable version to each module build. Local builds default deliberately to `8.0.0-SNAPSHOT`; release builds require the guarded workflow to supply the version. Module build files stop defining independent version literals.

The orchestrator performs these phases:

1. validate version/ref provenance and generated-runtime inputs;
2. clean and build all four JAR/POM pairs into module targets;
3. audit coordinates, POM metadata, dependency edges, licence, class-file level, JAR contents, and absence of `cloudafrica/*` module references;
4. install all four into a clean temporary Maven repository and run isolated consumer smoke projects; and
5. only after every local check succeeds, deploy in order: core, Datomic, Datahike, DataScript.

The POMs will include EPL-2.0, project URL, SCM URL/connection, description, and developer metadata. `LICENCE` will be included in each JAR under `META-INF` as well as declared in its POM. Publication uses only the Clojure CLI: tools.build produces JAR/POM pairs and deps-deploy receives their explicit paths through `clojure -X:deploy`, using Clojars credentials mapped from GitHub environment secrets. Leiningen and Boot are not part of the release path.

Alternative considered: let a job matrix deploy each module independently. Parallel upload is faster but can publish backends before core and can leave a more confusing partial release when one module's POM or JAR is invalid.

### 5. Make normal releases push-triggered on strict version branches

Add a release workflow triggered by pushes to broad GitHub filter `v*` and by manual dispatch for the exceptional snapshot. A repository script then enforces the strict ordinary regex `^v([0-9]+)\.([0-9]+)\.([0-9]+)$`, requires `GITHUB_REF_TYPE=branch`, derives the version from the capture groups, and rejects environment/version overrides. GitHub's broad filter is only an optimization; the checked-in guard is authoritative.

For an ordinary version branch, a credential-free gate waits for and verifies the explicit required check-run names from both Tests and Formal verification at exactly `GITHUB_SHA`. Success, neutral, or skipped states are accepted only where a named check is intentionally non-applicable; failures, cancellations, timeouts, absent checks, duplicate ambiguous results, or SHA mismatches reject release. The deploy job has `needs: gate`, repeats the ref/version assertions, references environment `clojars`, uses minimal `contents: read` and `actions: read` permissions, and is serialized with a non-cancelling release concurrency group.

This push-triggered structure preserves `GITHUB_REF=refs/heads/v...` for GitHub environment branch protection. A `workflow_run` design was rejected because its ref normally points at the default branch, making branch-scoped environment policy misleading, and because two independent upstream workflows need exact-SHA correlation anyway.

### 6. Encode the snapshot bypass as exact data, then remove it

The manual path accepts only:

- branch ref `refs/heads/codex/v8-demand-bounded-authorization`;
- ref type `branch`;
- version `8.0.0-SNAPSHOT`; and
- the commit resolved from that branch, not an arbitrary SHA.

It bypasses only the ordinary CI/formal-result gate. It does not bypass compilation, artifact/POM audits, clean Maven installation, consumer smoke tests, environment approval, group authorization, or credential masking. After confirming all four Clojars coordinates and exercising `eacl-datomic-solidjs`, remove the exception from the workflow and the environment's selected-branch rule.

Alternative considered: allow any manual snapshot input. That would become a permanent route around the release policy and makes a typo such as `8.0-SNAPSHOT` externally persistent.

### 7. Treat GitHub environment policy as a second independent guard

Configure `clojars` for selected branches and tags, with branch rule `v*`, no tag rules, and temporarily the exact branch `codex/v8-demand-bounded-authorization`. Remove `main`; its current inclusion contradicts the stated release policy. Add a required reviewer where the GitHub plan permits it, disallow administrator bypass if available, and ensure only the deploy job names this environment.

`CLOJARS_USERNAME` must contain the Clojars username `theronic`, not the login email. `CLOJARS_DEPLOY_TOKEN` must initially be reusable and unscoped because the four distinct projects do not yet exist. The workflow maps these GitHub secret names to the `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` process variables consumed by the release tooling. After the first coordinated deployment, rotate the token to a reusable group-scoped token for `dev.eacl` and update the environment secret.

No additional long-lived GitHub secret is needed. The built-in `GITHUB_TOKEN` with read-only actions/content permission is sufficient for exact-SHA gate inspection. Workflow logs will never echo deployment environment variables.

## Risks / Trade-offs

- [The `dev.eacl` Maven identity depends on continued control of `eacl.dev`] → Keep the domain registration and the `clojars theronic` TXT proof under maintainer control; verification is complete and the Pages routing records are independent of the TXT record.
- [A four-artifact remote release can partially succeed after core is uploaded] → Build, audit, and clean-install the entire set first; deploy dependency-first; use immutable release versions only once and inspect Clojars state before retrying. SNAPSHOT retries remain possible but must use the same guarded commit.
- [Clojars SNAPSHOT coordinates are mutable] → Use `8.0.0-SNAPSHOT` only for the explicit integration test, record the published commit in workflow provenance, and move consumers to immutable `8.0.0` after the normal gate is usable.
- [The Java 26 default excludes older consumers of the default artifact] → Permit an explicit Java 8-through-26 source/custom build target, audit that target exactly, and document that backend dependencies may still require newer Java; never rebuild one immutable coordinate at a different target.
- [Required GitHub check names drift] → Keep the allowlist beside workflow definitions and test the gate against synthetic check-run payloads, including absent, failed, duplicate, and wrong-SHA cases.
- [Explicit source preparation is less convenient than automatic prep] → Provide one copyable command, clear IntelliJ instructions, module-local staging, and a direct missing-runtime diagnostic; prefer Maven artifacts for normal users.
- [Formal tools and generated outputs consume significant disk and time] → Keep all formal commands opt-in for consumers, describe components/output roots before the commands, and preserve checksum verification and caches for maintainers who choose to run them.

## Migration Plan

1. Verify `dev.eacl` through the `clojars theronic` TXT record on `eacl.dev`, confirm user `theronic` has all-project administrator rights, and confirm the initial deploy token is reusable and unscoped.
2. Update module coordinates, central version/build metadata, complete POM metadata, the Java 26 default plus explicit older target, artifact audits, and explicit-only source preparation; validate local and Maven-isolated consumption.
3. Add the release guard and workflow. Configure the `clojars` environment to remove `main`, allow `v*` plus the temporary exact snapshot branch, and optionally require reviewer approval without bypass.
4. Run ordinary Tests and as much formal CI as currently completes, then manually dispatch the exact `8.0.0-SNAPSHOT` exception from `codex/v8-demand-bounded-authorization`.
5. Verify all four Clojars POMs/JARs and exercise a clean `eacl-datomic-solidjs` dependency on `dev.eacl/eacl-datomic` `8.0.0-SNAPSHOT`; separately verify its documented `:local/root` override after explicit source preparation.
6. Remove the exceptional branch/version path and environment rule, rotate the unscoped token to a `dev.eacl` group-scoped token, and retain only strict green `vMAJOR.MINOR.PATCH` publication.

If the snapshot deployment fails before any upload, correct the preflight and retry the same commit. If only part of the snapshot set uploads, inspect the remote POM/JAR state, keep the same version and commit, and resume only the missing artifacts after local revalidation. Non-SNAPSHOT versions are immutable and must never be overwritten; a bad immutable release requires a new patch version.
