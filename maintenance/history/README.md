# EACL maintenance history

Records for repository maintainers. They describe earlier engines, formats,
and investigations; they are not the current library contract. Consumers
should start at the [documentation index](../../docs/index.md).

- [Formal-verification behavior corrections](formal-verification-corrections.md):
  production behavior changes found by the formal work, most with their
  `EACL-FORMAL-NNN` counterexample.
- [Arrow-to-relation permissions bug fix](bug-fix-arrow-to-relation-v7.md):
  the v7 fix from 2025-10-28.
- [UUID source-lifecycle artifact cutover](uuid-source-lifecycle-cutover.md):
  the token, cursor, and cache format change of 2026-09-08, before 8.0.0.
- [v8 pre-release notes](v8-prerelease-notes.md): vars removed on 2026-09-02
  and resetting unreleased v8 development databases.
- [Audit reports](../../docs/reports/): dated audits, reviews, and design
  records, including the 2026-08-02 v8 cache and cursor design records and
  the 2026-08-15 stable-engine audit.

Earlier decisions and plans are in the [ADRs](../../docs/adr/) and
[implementation plans](../../docs/plans/).
