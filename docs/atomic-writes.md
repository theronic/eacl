# Application data and relationships in one transaction

EACL can commit relationship changes together with application data. For
example, you can update a document's title and grant access in one transaction.

## Existing users and documents

This recipe continues the [Datomic quickstart](../README.md#quickstart).
Both `alice` and `report` must already exist in the database.

```clojure
(eacl/with-snapshot [snapshot (eacl/snapshot acl)]
  @(d/transact
    conn
    (eacl/tx-relationships
     snapshot
     {:updates [{:operation :touch
                 :relationship (eacl/->Relationship alice :viewer report)}]
      :tx-data [[:db/add [:app/id "report"] :document/title "Shared report"]]})))
```

Submit the returned transaction data intact. It includes checks that the
permission schema and endpoint identities still match the snapshot. A
concurrent deletion or identity change can make the transaction fail; start
again with a fresh snapshot instead of stripping those checks.

For an expiring relationship, use `write-relationships!` to handle preparation
and the final transaction for you:

```clojure
(eacl/write-relationships!
 acl
 {:updates [{:operation :touch
             :relationship (assoc (eacl/->Relationship alice :viewer report)
                                  :valid-until-ms deadline-ms)}]
  :tx-data [[:db/add [:app/id "report"] :document/title "Shared report"]]})
```

`deadline-ms` is a UTC timestamp in milliseconds. See
[expiring access](caveats.md#expiring-access) for setting it. If you need to
submit the native transaction yourself, qualified relationships require
`prepare-relationship!` before you acquire the planning snapshot; see
[qualified writes](caveats.md#write-and-check-a-qualified-relationship).

## New entities and tempids

A Datomic tempid names an entity being created in the current transaction.
The published `8.0.0-RC-2026-09-12` public `tx-relationships` planner resolves
endpoints against its snapshot. Adding a new entity to `:tx-data` does not
make that entity visible to the planner: it raises `:eacl/unknown-object`.

The public recipe is therefore:

1. Create the application entities with `d/transact`.
2. Create their relationships with `eacl/create-relationships!`.

These are two transactions. If your application requires atomic creation,
this public recipe does not meet that requirement.

The release contains the Datomic implementation helper
`eacl.datomic.impl/tx-relationship` with `{:allow-tempids? true}`. It is not the
public snapshot planner and does not use your client's ID converters. Existing
integrations using it must resolve custom IDs themselves and preserve
commit-time identity guards. Do not pass unchecked user input as tempids: an
unresolved ID could create an unintended entity. There is no general public
custom-ID/tempid recipe in this release.

The [consumer checks](examples/datomic-consumer/) verify both the existing-entity
transaction and the public planner's new-entity limitation.
