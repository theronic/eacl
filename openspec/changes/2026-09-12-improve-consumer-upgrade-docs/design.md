## Context

See proposal.md for the motivation and the linked eDrive catalogue for the documentation-only observations. The demo now resolves the dated Clojars release. Source review began only after that catalogue was saved.

## Goals / Non-Goals

**Goals:** Give a reader a short, repeatable path from installation to an allowed and a denied request. Keep custom IDs and expiring shares understandable throughout the examples.

**Non-Goals:** No library implementation changes, removal of legacy ID support, or new guarantees for transaction helpers.

## Decisions

1. Start the README with prerequisites, the exact Maven coordinate, and a complete memory example. Keep a short backend table and links to deeper guides. Retaining the current long introduction would leave the first successful run too hard to find.
2. Use `:app/id` with `:db.type/string`, `:db.cardinality/one`, and `:db.unique/identity`. Install it explicitly before creating entities. Show both ID conversion functions. Explain that IDs must be unique and stable; explain fingerprint and immutable-ID settings only after the basic example. Changing the library default would break existing consumers and is unnecessary.
3. Keep one tested example as the basis for the README snippets. Verify with the published artifacts, and check dependency paths for accidental local overrides. Do not require source preparation or formal tools for this example.
4. Add an upgrade table with the symptom, cause, and fix. Include the storage version error, rejected cache keys, stale internal attribute tests, and reinstallation of the saved Datomic deletion function. Keep retained database migration separate from disposable database recreation.
5. Separate ordinary relationship writes, writes combined with application data for existing endpoints, and same-transaction creation with tempids. The published `tx-relationships` API plans against an EACL snapshot; the existing demo's tempid helper uses `eacl.datomic.impl`. Verify this boundary before publishing a recipe. If the release has no public route for a case, label the limitation and the narrowly supported helper clearly instead of inventing an API.
6. Start the expiration guide with a plain viewer relationship and `:valid-until-ms`. Use `:touch` to renew or clear it. State: “Access from this share ends at the selected time. Other shares may still give access.” Explain local input time, UTC storage, expired rows, inherited access, and refreshes when no transaction occurs. Keep named conditions and evaluator installation in a later section.
7. Check query examples against the published RC, not the moving checkout. The RC recognizes `read-relationships` with `:authorization`; keep and correct that recipe. It rejects lookup `:relationship` and lists `:resource/relationship` among the supported resource lookup keys. Verify the replacement's full shape before publishing it. Show the application's permission check before reading sharing metadata. Fix malformed placeholder snippets, such as the lookup example with `:first page-size N` and an unmatched `:cursor`.

## Suggested wording

- “Give each user and document an ID owned by your application. This example uses `:app/id`. You do not need to put application IDs in EACL's internal schema.”
- “For a new database, install EACL's schema, then your application's schema, then create the client.”
- “Leave the cache settings out to use the defaults. A cache time limit does not set when a share expires.”
- “Leave the expiration blank for access with no end date. At the deadline, this relationship stops granting access, even if nothing is written to the database.”

## Risks / Trade-offs

- Examples can drift from a release → run them against the version printed in the docs and check links and removed options before publishing.
- Readers may think changing ID examples requires a migration → explicitly say existing IDs remain supported; changing an existing application's IDs is a separate migration.
- Expired rows can be mistaken for active access → show saved shares separately from the result of a current permission check.
- Shorter docs can hide important detail → retain links to security, migration, pagination, and cache guides beside the relevant steps.

## Migration Plan

Update the consumer examples and verification first, then rewrite the README and linked guides around those examples. Review each recommendation against the catalogue. No database deployment is required. Documentation changes can be reverted without changing library behavior.
