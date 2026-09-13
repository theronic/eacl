# Consumer documentation verification

Verified on 13 September 2026 with Java 26.0.2 and Clojure 1.12.5.
The published core JAR contains Java 25 bytecode (class version 69), matching
the documented minimum for this build.

## Published dependencies

The standalone project at `docs/examples/datomic-consumer` resolves EACL core
and Datomic to `8.0.0-RC-2026-09-12` Maven JARs. Runtime resource URLs and
`clojure -Stree` confirm that no EACL checkout is on the classpath. The final
consumer run used `-Srepro` to exclude user dependency configuration.
Clojars metadata confirms the dated release for Datomic and the JVM evaluator.

## Passing checks

- Standalone consumer suite: 1 test, 19 assertions, no failures or errors.
- README Datomic setup, lookup, safe deletion, snapshot, preview, and Zed-token
  examples evaluated through nREPL.
- Datahike and DataScript quickstarts evaluated through nREPL with the dated
  Maven adapters and custom `:app/id` mappings; both return the documented grant.
- Existing-endpoint atomic writes evaluated directly from the guide, including
  an expiring relationship composed with application data.
- Expiration and saved-sharing blocks evaluated directly from the guide,
  including rejection of a caller without `:share` permission.
- Aggregate batch, resource lookup, and authorized relationship scan functions
  evaluated directly from the guide. The unnecessary batch-limit override was
  removed after the release rejected it; the corrected functions pass.
- Keyring distribution and activation function evaluated against the release.
- DataScript Caveat example passes after changing its application IDs. It covers
  conditional results, grant and ban expiration, pinned time, live-cursor restart,
  renewal, prepared writes, and deletion.
- Local Markdown links and anchors checked for changed consumer documents.
- `git diff --check` and strict OpenSpec validation pass.

The named human-written README sections are unchanged, as is Caching through
“Suppose:”. The EACL API section has only the correction of its malformed
lookup options (`:first page-size N` and an incomplete `:cursor` entry).

## Catalogue coverage

The original catalogue is preserved in
[eDrive's Git history](https://github.com/theronic/eacl-edrive/blob/573e5adab928e161b18c83459f364f2b85e8d305/docs/eacl-v8-upgrade.md).

| Catalogue issues | Documentation |
| --- | --- |
| 1, 2: version and local overrides | Published dependency snippets and upgrade classpath checks. |
| 3: storage setup | Separate fresh setup and retained-data migration instructions. |
| 4: cache options | Default-first configuration and obsolete-option fixes. |
| 5: application IDs | Custom schema and both converters in all three quickstarts; matching deletion examples. |
| 6: atomic creation | Verified existing-endpoint recipe and explicit public tempid limitation. |
| 7: internal storage tests | Public, anchored relationship-read guidance. |
| 8: share updates | Touch, clearing expiration, and atomic role changes. |
| 9, 10: expiration and display | Standalone expiration, saved versus effective access, and refresh without writes. |
| 11: query options | Released aggregate routes retained and obsolete lookup option corrected. |
| 12: setup placement | Datomic quickstart near the start; contributor details linked separately. |

Remaining `:eacl/id` references describe the compatibility default or EACL's
internal schema. Historical migrations, ADRs, reports, and plans retain their
original identifiers. The DataScript Caveat example now uses `:app/id` too.

## Limits of this verification

The public snapshot planner cannot resolve an endpoint created only in pending
`:tx-data`. The test verifies its `:eacl/unknown-object` result. The guide does
not claim a general public atomic custom-ID/tempid recipe for this release.

Retained database migrations were checked against their operator guides, not
run against a production database. The DataScript quickstart was executed on
the JVM; no browser build or performance benchmark is claimed. The optional
ClojureScript Git cache dependency is documented separately.

No library implementation or public source root changed, so this work does
not require regenerating the formal source closure or rerunning the full
library CI battery.
