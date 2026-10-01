# EACL-FORMAL-085 — Datalevin Relation streams dropped rows after the first batch

`eacl.datalevin.db/qualified-relation-datoms` streams one Relation's endpoint
rows from the AVE index in batches of 1,025. It started each later batch with
`(seek-datoms db :ave attr v e 1025)` at the previous batch's last datom.
Datalevin applies a seek's entity component to every datom it returns
(ascending: `e >= entity`), not only to the first value. Each later batch
therefore dropped every row whose owner eid was smaller than the boundary
row's owner.

A probe with 1,100 rows `[:db/add (- 3000 k) :t/v [:a 1 :b k (+ 5000 k)]]`
streamed 1,025 of them. Seeded random layouts of about 2,750 rows lost 20 to
50 percent.

`relation-allowance/stored-caveats` reads both endpoint streams of a changed
Relation through this function before `write-schema!` may remove a Caveat or
the unqualified alternative from it. With a Caveated Relationship placed after
the first batch in both streams, behind a larger boundary owner,
`relation viewer: user | user with enabled` could be replaced with
`relation viewer: user` while that Relationship still used `enabled`. After the
admitted write:

- `can?` on the Relationship returned false;
- `read-relationships` on its resource failed with `:eacl.qualifier/invalid`
  `:caveat-not-allowed`;
- restoring `enabled` to the Relation failed the same way, so `write-schema!`
  could not repair the schema.

Each later batch now resumes with `resumed-ave-datoms`. It seeks with the
boundary row's value and owner, keeps rows only while the value equals the
boundary value, then seeks from `adjacent-value` (the next possible
five-component value) with no entity component. The stream equals the unpaged
AVE index for the prefix, and the schema replacement fails with
`:eacl.schema/relationship-qualifier-in-use`.

Datomic, DataScript and Datahike read these streams with one unbounded seek
that has no entity component and are unaffected.

CI does not install `eacl-datalevin` until its Datalevin fork is published, so
replay this entry from a Datalevin nREPL:

```sh
clojure -M:dev:datalevin-test:nrepl --port <port>
clj-nrepl-eval -p <port> "(require 'eacl.datalevin.qualified-write-test :reload) (clojure.test/run-tests 'eacl.datalevin.qualified-write-test)"
```
