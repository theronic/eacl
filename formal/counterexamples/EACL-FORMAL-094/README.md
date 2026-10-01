# EACL-FORMAL-094 — removing a Relation with qualified Relationships crashed the schema write

A schema replacement that removes a Relation identity `(resource type,
relation, subject type)` counts the Relationships still stored under it and
fails with `:eacl.schema/relation-in-use {:count n}`. On DataScript, and on
Datalevin, that count scanned the Relation's endpoint rows with the
endpoint-prefix scan that admits only unqualified rows, so the first expiring
or Caveated row raised `:eacl/unsupported-qualifier {:qualifier-eid n}`
instead — expired rows included. `with-schema … {:orphan-policy :retain-inert}`
probes only the first indexed row on DataScript, so whether a speculative
removal succeeded depended on whether that row was qualified.

Both scans now admit qualified rows. Datalevin counts the complete batched
qualified Relation stream, which also removes its 100,000-row truncation of
the reported count. Datomic and Datahike already scanned the full tuple range;
the same shared contract
(`modules/eacl/test/eacl/caveats/relation_removal_contract.cljc`) now runs on
all four backends. It removes the Relation after one, two, three and four
retained Relationships (expiring, expired, Caveated, plain, with the first
indexed row qualified), checks the count and the unchanged stored schema,
checks `:retain-inert` diagnostics where speculative schemas exist, and finally
removes the Relation once no Relationship remains. Found by the eacl-rust port
(BUGS.md EACL-RS-110).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`
(DataScript), and run the Datalevin regression in an nREPL started with
`clojure -M:dev:datalevin-test:nrepl` and the `../datalevin` checkout.
