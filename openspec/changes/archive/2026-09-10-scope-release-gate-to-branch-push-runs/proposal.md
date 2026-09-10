# Scope the Clojars release gate to the release branch's push runs

## Why

The Clojars release run for `v8.0.0-SNAPSHOT` commit `87fc228e` failed in
`required_checks` with "Required checks have duplicate ambiguous results"
although every Tests and Formal verification job for that commit had
succeeded. The release guard judges every check run GitHub attaches to the
release commit. GitHub attaches check runs to a commit regardless of the ref
or event that produced them, so the commit carried a second set of Tests and
Formal check runs from the `pull_request` event of PR #189, which was open from
the release branch at the time. Those runs verify a synthetic merge commit,
not the pushed tree, and the guard could not tell the two sets apart.

The same defect blocks the intended `8.0.0` release: a `v8.0.0` branch cut
from the head of `main` shares its commit with `main`, so `main`'s push runs
also attach to the release commit and the gate reports duplicates again.

## What Changes

- Correlate required check runs with the push-event workflow runs whose head
  branch and head commit are exactly the release branch and release commit,
  through each workflow run's check-suite id. Check runs the commit carries
  from pull-request events or from other branches are ignored: they never
  count as evidence and are never reported as ambiguous duplicates.
- Reject a truncated GitHub listing page instead of judging a partial view.
- Query both listings in the release workflow; the credential-free guard
  remains the only judge.
- Record the current five-module release set in the pipeline specification
  (`dev.eacl/eacl-caveats-jvm` joined the coordinated set with the v9 Caveat
  foundation).

## Capabilities

### Modified Capabilities
- `clojars-release-pipeline`: ordinary gating judges only the release
  branch's own push-event runs at the release commit; the coordinated
  release set lists all five published modules.

## Impact

`src-build/eacl/release_guard.clj`, its tests, and
`.github/workflows/release.yml`. No published artifact source changes.
