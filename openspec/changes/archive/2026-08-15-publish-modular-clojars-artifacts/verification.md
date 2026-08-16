# Verification evidence

Verified locally on 2026-08-09 with Temurin 26.0.2+10 (AArch64 macOS).

| Requirement area | Evidence |
| --- | --- |
| Opt-in source preparation | `clojure -Spath` resolves without `:deps/prep-lib`; focused source-preparation/generated-runtime tests pass. Explicit `clojure -T:build prep` produced 737 staged files, and a subsequent core JAR build retained all 737. |
| Configurable Java runtime | Java 26 remains the pinned release-workflow default and the prepared 737-class tree passes the major-70 audit. The complete 737-source generated Java set also compiled with `javac --release 8`, produced major-52 classes, and passed the generated `EaclKernel.RoundTrip` boundary on the installed newer JVM without changing workspace outputs. Focused configuration, audit, source-preparation, and release tests cover Java 8-through-26 selection and exact target propagation. |
| Four-artifact construction | Core, Datomic, Datahike, and DataScript module-local `clojure -T:build jar` commands all succeeded without external-path warnings. Generated POM/JAR audits verified coordinates, exact direct dependencies, EPL-2.0/SCM/developer metadata, licence entries, generated runtime contents, and no checkout path. |
| Clean Maven consumers | The release orchestrator installed all four `8.0.0-SNAPSHOT` artifacts into a fresh temporary Maven repository. Four isolated Maven-only processes loaded core or one backend, asserted major 70, and exercised the packaged generated `EaclKernel.RoundTrip` boundary successfully. After deployment, a new empty Maven repository resolved `dev.eacl/eacl-datomic` and transitive `dev.eacl/eacl` from Clojars rather than the workspace. |
| Upload failure ordering | Release pipeline tests cover invalid JAR, POM, generated entry, bytecode, coordinate, isolated resolution, and generated-kernel smoke failures and assert that the deploy function receives zero calls. |
| Release provenance | Workflow run `31321497579` accepted exact branch head `381bfe07ec2de097a654ed7ea5c3127dce90b9a1`, required protected-environment approval, and completed successfully. After the integration test, `workflow_dispatch` and the exception guard/constants/tests were removed. Synthetic guard tests now cover only exact `vMAJOR.MINOR.PATCH` derivation, main/release/random/tag/PR/override rejection, missing/duplicate/pending/failed/cancelled/timed-out/wrong-SHA checks, and deadline rejection. |
| GitHub environment guard | Environment `clojars` now permits only selected branch rule `v*`, with no exact exception, `main`, arbitrary branch, or tag rule. Required reviewer `theronic` is configured, administrator bypass is disabled, and self-review remains allowed so the sole maintainer can approve a deployment. Repository workflow inspection confirms only the ordinary guarded release deploy job names `clojars` or reads `CLOJARS_USERNAME`/`CLOJARS_DEPLOY_TOKEN`; a regression test checks the exact mapping. |
| Clojars group authorization | Route 53 apex TXT `clojars theronic` is visible from all four authoritative `eacl.dev` nameservers. Clojars self-service verification accepted domain `eacl.dev` for group `dev.eacl`; the group page records user `theronic` with `*` scope and administrator rights, `https://clojars.org/api/users/theronic` includes `dev.eacl`, and the checked-in remote authorization preflight returns true. Existing GitHub Pages A, AAAA, and CNAME records were unchanged. |
| Initial Clojars publication | Clojars reports `8.0.0-SNAPSHOT` for `dev.eacl/eacl`, `eacl-datomic`, `eacl-datahike`, and `eacl-datascript`, with timestamped uploads `20260809.153803-1`, `.153810-1`, `.153817-1`, and `.153823-1`. Public POMs contain EPL-2.0, SCM tag `381bfe07ec2de097a654ed7ea5c3127dce90b9a1`, and exact adapter-to-core `8.0.0-SNAPSHOT` edges. The remotely downloaded core JAR contains major-70 generated classes, Dafny runtime, CLJ/CLJS production boundaries, `deps.cljs`, `EaclKernel.browser.js`, and `META-INF/LICENCE`. |
| Deployment credentials | The protected GitHub environment's `CLOJARS_USERNAME` is `theronic`. After publication, the reusable unscoped bootstrap token was disabled, a reusable non-expiring `dev.eacl/*` token was created, and `CLOJARS_DEPLOY_TOKEN` was replaced write-only at `2026-08-09T15:48:19Z`. The temporary intermediary scoped token was also disabled; only `GitHub Actions dev.eacl v2` remains active for this group. No token value was logged or committed. |
| Reference consumer | `eacl-datomic-solidjs` base mode resolves the published adapter and transitive core from a clean Maven repository; `:local-eacl` resolves `../../core/modules/eacl-datomic` and its sibling core. Neither launch script invokes preparation. On ephemeral checksum-verified Temurin 26.0.2+10, the Clojars mode started against in-memory Datomic and returned ready health/bootstrap responses. Local mode first failed on deliberately stale staged classes without invoking tools, then `npm run prep:local-eacl` was run explicitly and local mode returned ready health. All shared IntelliJ run-configuration XML validates. |
| Clojure CLI publication | The release alias uses `slipset/deps-deploy`, the workflow invokes `clojure -X:deploy`, and a regression test locks that command. No Leiningen or Boot publication path is present. |
| Combined JVM suite | 529 tests, 31,593 assertions, 0 failures, 0 errors through Java 26 nREPL. |
| Module-isolated JVM suites | Core: 128 tests / 4,231 assertions; Datomic: 359 / 19,008; DataScript: 200 / 9,746; Datahike: 163 / 7,749. All completed with 0 failures and 0 errors through module-local Java 26 nREPLs. |
| DataScript CLJS suite | 167 tests, 9,556 assertions, 0 failures, 0 errors; compiled from nREPL and executed on Node. |
| OpenSpec | `openspec validate publish-modular-clojars-artifacts --strict --json` returned valid with no issues. |

The combined suite initially exposed a pre-existing stale dispatch-call count:
the current source contains 52 literal calls while the ledger recorded 63.
`formal/verification/backend-dispatch.edn` now agrees with both observed CLJ
and CLJS source and the existing performance baseline; the focused closure test
then passed 10 assertions.

Remote artifact publication and both reference-consumer modes are complete.
The requested coordinates are `dev.eacl/eacl*`; Clojars verified that group
through `eacl.dev` DNS and confirms `theronic` has complete group access. No
issue-tracker request was submitted. The one-time release route and environment
branch rule are gone, and the remaining deploy credential is group-scoped. All
token values remained write-only throughout this work.
