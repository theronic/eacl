# Verification evidence

Verified locally on 2026-08-09 with Temurin 26.0.2+10 (AArch64 macOS).

| Requirement area | Evidence |
| --- | --- |
| Opt-in source preparation | `clojure -Spath` resolves without `:deps/prep-lib`; focused source-preparation/generated-runtime tests pass. Explicit `clojure -T:build prep` produced 737 staged files, and a subsequent core JAR build retained all 737. |
| Configurable Java runtime | Java 26 remains the pinned release-workflow default and the prepared 737-class tree passes the major-70 audit. The complete 737-source generated Java set also compiled with `javac --release 8`, produced major-52 classes, and passed the generated `EaclKernel.RoundTrip` boundary on the installed newer JVM without changing workspace outputs. Focused configuration, audit, source-preparation, and release tests cover Java 8-through-26 selection and exact target propagation. |
| Four-artifact construction | Core, Datomic, Datahike, and DataScript module-local `clojure -T:build jar` commands all succeeded without external-path warnings. Generated POM/JAR audits verified coordinates, exact direct dependencies, EPL-2.0/SCM/developer metadata, licence entries, generated runtime contents, and no checkout path. |
| Clean Maven consumers | The release orchestrator installed all four `8.0.0-SNAPSHOT` artifacts into a fresh temporary Maven repository. Four isolated Maven-only processes loaded core or one backend, asserted major 70, and exercised the packaged generated `EaclKernel.RoundTrip` boundary successfully. |
| Upload failure ordering | Release pipeline tests cover invalid JAR, POM, generated entry, bytecode, coordinate, isolated resolution, and generated-kernel smoke failures and assert that the deploy function receives zero calls. |
| Release provenance | Synthetic guard tests cover exact `vMAJOR.MINOR.PATCH` derivation, main/release/random/tag/PR/override rejection, the exact one-off snapshot branch/version/commit, missing/duplicate/pending/failed/cancelled/timed-out/wrong-SHA checks, and deadline rejection. |
| GitHub environment guard | Environment `clojars` uses selected branch rules `v*` and exact temporary branch `codex/v8-demand-bounded-authorization`, with no `main` or tag rule. Required reviewer `theronic` is configured, administrator bypass is disabled, and self-review remains allowed so the sole maintainer can approve a deployment. Repository workflow inspection confirms only the guarded release deploy job names `clojars` or reads `CLOJARS_USERNAME`/`CLOJARS_DEPLOY_TOKEN`; a regression test checks the exact mapping. |
| Clojars group authorization | Route 53 apex TXT `clojars theronic` is visible from all four authoritative `eacl.dev` nameservers. Clojars self-service verification accepted domain `eacl.dev` for group `dev.eacl`; the group page records user `theronic` with `*` scope and administrator rights, `https://clojars.org/api/users/theronic` includes `dev.eacl`, and the checked-in remote authorization preflight returns true. Existing GitHub Pages A, AAAA, and CNAME records were unchanged. |
| Initial deployment credentials | The protected GitHub environment's `CLOJARS_USERNAME` was explicitly set to `theronic`. The active `Mac Studio Deploy Token` is unscoped (blank scope means `*` in the Clojars implementation), reusable (`Single Use?` is `no`), and non-expiring; the existing `CLOJARS_DEPLOY_TOKEN` environment secret was left untouched, and its value was neither displayed nor read. |
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

Remote artifact publication remains pending, but group authorization no longer
blocks it. The requested coordinates are now `dev.eacl/eacl*`; Clojars has
verified that group through `eacl.dev` DNS and confirms `theronic` has complete
group access. No issue-tracker request was submitted. The `clojars`
environment's stored secret names match the workflow and documentation;
`CLOJARS_USERNAME` was explicitly refreshed to `theronic`. The Clojars token
metadata confirms the initial token is active, reusable, unscoped, and
non-expiring. The token value remained write-only throughout this work.
