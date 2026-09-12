## 1. Reproduce
- [x] Reproduce the `required_checks` rejection of commit `87fc228e` locally from the recorded check-run payload.

## 2. Correlate release evidence with the branch push runs
- [x] Derive admitted check-suite ids from push-event workflow runs of exactly the release branch and commit.
- [x] Judge only check runs inside admitted suites; keep duplicate, wrong-commit, non-success, and deadline rejections within them.
- [x] Reject truncated listing pages.
- [x] Query workflow runs and check runs in `release.yml` and pass both listings to the guard.

## 3. Verify and record
- [x] Cover the failing shape and the new rules in `eacl.release-guard-test`; run it and `eacl.build.release-test` through nREPL.
- [x] Evaluate the recorded payloads of commit `87fc228e` to `ready` with the new guard.
- [x] Update the `clojars-release-pipeline` specification, including the five-module release set.
