## Context

`eacl.release-guard/evaluate-checks` read
`GET /repos/{owner}/{repo}/commits/{sha}/check-runs` and grouped the required
check names by name. GitHub Actions creates one check suite per workflow run
and attaches every check run to the run's head commit, so a commit carries one
set of check runs per (workflow, event, ref) that ran on it:

- push runs of the release branch: the evidence the gate wants;
- `pull_request` runs when the release branch heads an open pull request:
  these check out `refs/pull/N/merge`, a synthetic merge with the base branch;
- push runs of any other branch pointing at the same commit, for example
  `main` when a release branch is cut from its head.

## Decisions

### 1. Correlate through the check suites of branch push runs

`GET /repos/{owner}/{repo}/actions/runs?head_sha=SHA&event=push&branch=BRANCH`
returns the workflow runs of the release branch's own push, each with its
`check_suite_id`. A check run whose `check_suite.id` is outside that set is
not release evidence. The guard re-applies the event, branch, and commit
filter itself, so the shell query is not load-bearing.

Alternative considered: skip every job on same-repo `pull_request` events in
`test.yml` and `formal.yml`. That removes one duplicate source, still leaves
`skipped` check runs with required names on the commit, and does nothing for
a second branch at the same commit.

Alternative considered: accept duplicates when every copy succeeded. That
admits pull-request results that verified a different tree and hides a real
disagreement between runs of the same commit.

### 2. Keep the exactness rules inside the admitted suites

Within the admitted suites, duplicates, wrong-commit runs, non-success
conclusions (including `skipped`), and results absent or pending past the
deadline reject exactly as before.

### 3. Refuse truncated listings

Both listing endpoints report `total_count`. A page holding fewer items than
GitHub counted is rejected rather than judged, so a busy commit cannot pass by
omission.

## Verification

- `eacl.release-guard-test` covers the failing shape (pull-request and
  other-branch results on the release commit, including a skipped and a failed
  foreign copy), branch-scoped suite derivation, pending-until-runs-exist,
  truncation, and the workflow's query strings.
- The new guard evaluates the recorded GitHub payloads of commit `87fc228e`
  to `ready`; the previous guard rejects them with the duplicate error.
- The `v8.0.0` release run exercises the fix under the conditions that failed.
