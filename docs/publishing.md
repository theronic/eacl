# Publishing EACL to Clojars

Publication is an explicit version-tag operation followed by approval of the
`clojars` GitHub environment. Pushing `main`, a feature branch, or a snapshot
branch runs CI without publishing anything.

1. Open a PR into `vMAJOR.MINOR.PATCH-SNAPSHOT` and wait for its push-event
   **Tests** and **Formal verification** workflows to succeed. Fix failed jobs
   first; rerun failed jobs instead of restarting successful formal verification.
2. Merge the PR. Keep the green PR head SHA: the release guard accepts it when
   it is an ancestor of the release branch and its entire Git tree equals the
   branch's current tree. A merge commit with different contents requires its
   own green CI. Do not squash/rebase if you intend to reuse the PR head's CI.
3. Create and push an annotated tag on that green SHA, or create a GitHub Release
   targeting it. Tag names determine the Maven version:

   | Tag | Published version |
   | --- | --- |
   | `v8.0.0-SNAPSHOT-2026-09-12` | `8.0.0-SNAPSHOT` |
   | `v8.0.0-SNAPSHOT-2026-09-12T141530Z` | `8.0.0-SNAPSHOT` |
   | `v8.0.0-RC-2026-09-12` | `8.0.0-RC-2026-09-12` |
   | `v8.0.0` | `8.0.0` |

   All these tags use release branch `v8.0.0-SNAPSHOT`. Use a new dated tag for
   each snapshot. Never move a published tag or reuse an immutable Maven version.
4. Open **Actions → Clojars release**, inspect the source and CI links in the
   provenance summary, and approve the `clojars` deployment. The workflow checks
   provenance and CI again after approval, downloads generated runtimes from the
   verified Tests run, and builds, audits, and cold-smokes all five artifacts
   before uploading any of them.

For example, with `source_sha` set to the green PR head:

```sh
git tag -a v8.0.0-RC-2026-09-12 "$source_sha" -m 'EACL 8.0.0 RC 2026-09-12'
git push origin refs/tags/v8.0.0-RC-2026-09-12
```

Tags do not restart Tests or Formal verification. The latest push run of each
trusted workflow must be green for the exact tagged SHA; PR-event checks,
different commits, skipped required jobs, and truncated listings cannot pass.
Missing/red/in-progress CI fails immediately instead of waiting for 90 minutes.
The tested runtime artifact must still be retained in GitHub Actions; if it has
expired, rerun Tests on the source branch to regenerate it. Formal verification
does not need to be rerun for an unchanged commit.

After correcting an operational failure, use **Re-run failed jobs**. Manual
dispatch also works on an existing tag once this workflow is on the default
branch:

```sh
gh workflow run release.yml --ref v8.0.0-RC-2026-09-12
```

Dispatching on a branch is rejected. If a Clojars upload failed partway through
an immutable release, inspect the remote artifact set before retrying: Clojars
does not support overwriting released versions.

The release set is `dev.eacl/eacl`, `eacl-caveats-jvm`, `eacl-datomic`,
`eacl-datahike`, and `eacl-datascript`, all at the same version. Datalevin remains
excluded until its maintained fork dependency is published.

Repository configuration: allow merge commits and auto-merge, require all ten
Tests/Formal jobs on the snapshot branch, restrict the `clojars` environment to
version tags with maintainer approval, and prevent version-tag updates/deletion.
Keep the release workflow on `main` as well as the snapshot branch so manual
dispatch remains available. GitHub's [tag triggers](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
and [deployment environment rules](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
describe these controls.
