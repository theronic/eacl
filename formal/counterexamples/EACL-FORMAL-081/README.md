# EACL-FORMAL-081 — permission trees failed on qualified relationships

`expand-permission-tree` failed with
`:eacl.permission-tree/adapter-contract-violation {:reason :adapter-operation-failed}`
whenever it scanned a Relation holding a caveated or expiring Relationship.
PR206-F1 recorded the caveated case; the eacl-rust port's differential campaign
(EACL-RS-005, <https://github.com/theronic/eacl-rust/blob/main/BUGS.md>) showed
that an expiring Relationship fails the same way, before and after its
deadline, and that a qualified arrow-source edge breaks every arrow over it.

`scan-relation!` was the one v8 serving scan that did not opt into qualified
results. Without `:include-qualifier? true`, each backend's endpoint scan
rejects a stored value that carries a qualifier reference
(`:eacl/unsupported-qualifier`), and `adapter-call!` redacts that error into an
opaque contract violation. Caveats and expiry both allocate a qualifier, so
the trigger was any qualified row the traversal realized.

The tree now scans compact qualified edges and lists every stored
Relationship. A leaf subject or arrow child node reached through a qualified
Relationship carries that Relationship's `:caveat`, `:caveat-context` (omitted
when empty) and `:valid-until-ms`, decoded by the same inspector as
`read-relationships`. Nothing is evaluated: no Caveat runs and no clock is
read, so an expired Relationship stays listed with its deadline, and the tree
remains a function of the selected basis, as its answer cache key assumes.

Reproduce through the dev nREPL:

```sh
EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay
```

The replayed regression is the exact BUGS.md scenario on DataScript; the
shared permission-tree contract runs on Datomic, Datahike (both attribute
modes), DataScript CLJS, and Datalevin.
