## 1. Schema and storage

- [x] 1.1 Admit `T:*` and `T:* with c` branches in the parser and expression resolver; reject duplicate branches and wildcard arrow bases
- [x] 1.2 Store wildcard branches with `:eacl.relation/allows-unqualified-wildcard?` and `:eacl.relation/wildcard-caveats`; canonicalize, retract and validate both branches in relation allowances
- [x] 1.3 Upsert the EACL-owned wildcard subject entity on wildcard schema writes; install the attributes on Datomic, Datahike and Datalevin; fail DataScript writes without them
- [x] 1.4 Reject schema replacements that remove a wildcard branch or Caveat still in use

## 2. Identity and writes

- [x] 2.1 Map the public ID `*` to the wildcard subject entity and back; reject `*` as a resource and as the subject of checks and resource lookups; reject rendering a concrete object whose ID is `*`
- [x] 2.2 Validate the subject form of relationship writes, and select the wildcard branch's Caveat alternatives in the qualified writer
- [x] 2.3 Support wildcard relationships in reads, filters and `delete-object!`, including `delete-object!` of `T:*`

## 3. Evaluation

- [x] 3.1 Report `:wildcard-eid` from every backend's relation definitions
- [x] 3.2 Emit wildcard variants of relation and arrow-relation rules in sealed plans; use them in forward enumeration, least-path, first-discovery and membership-probe checks; disable them in reverse traversal
- [x] 3.3 Probe the wildcard subject beside the subject in the scalar, vector and recursive operator evaluators; skip seekable specializations for wildcard partitions
- [x] 3.4 Route reverse operator lookups and counts that can grant through a wildcard through the touch cover, and attach `:excluded-subjects` to the `*` entry
- [x] 3.5 Exclude every touch-cover subject that is not definitely granted, under both result policies

## 4. Verification

- [x] 4.1 Shared backend contract (`wildcard_contract_support.cljc`) on Datomic, Datahike, DataScript (CLJ and CLJS) and Datalevin, with and without the cache, Caveats, expiry and speculation
- [x] 4.2 SpiceDB v1.56.0 golden fixture `formal/fixtures/wildcards/` and `eacl.datascript.wildcard-spicedb-golden-test`
- [x] 4.3 Dafny model `formal/dafny/WildcardSubjects.dfy`, registered in the assurance contract
- [x] 4.4 Seeded differential against `eacl.wildcard-reference` in CLJ and CLJS
- [x] 4.5 Full nREPL battery, `bin/formal source-closure`, and the DataScript ClojureScript build

## 5. Documentation

- [x] 5.1 README wildcard section, Unknown object IDs and Differences from SpiceDB
- [x] 5.2 Permission set algebra, Caveats, docs index, formal verification and v8 release notes
