# EACL-FORMAL-080 — partial relationship scans ignored the qualifier component

The eacl-rust port's self-consistency campaign (EACL-RS-004,
<https://github.com/theronic/eacl-rust/blob/main/BUGS.md>) found that a
`read-relationships` walk without `:subject/id` and `:resource/id` over a
Relation holding a caveated or expiring row could skip or repeat rows, and
that `:expiry-active` and `:authorization` walks over such a Relation never
terminated.

A partial scan reads the AVET index. Its values are
`[p0 p1 p2 primary qualifier]`, so one primary endpoint's rows are ordered by
qualifier eid, plain rows first, before owner eid. The relationship cursor and
`beyond-cursor?` compared only the primary and owner eids, and the backends
seeked to the start (or end) of the primary group. With
`org:f child org:c` expiring and `org:f child org:f` plain, the physical order
is `f→f` then `f→c` although `org:c` has the smaller eid:

- a `:first 1` page returned `f→f`; the continuation dropped `f→c` because
  `(f, c)` compares before the cursor `(f, f)`, so the qualified row was never
  returned;
- the `:expiry-active` window ended its first page on the sentinel `f→c`;
  resuming from it re-admitted `f→f`, so every page was `[f→f]`;
- the `:authorization` window (nothing visible to `user:u`) resumed each chunk
  from `f→c`, re-read `f→f`, and so examined the same two rows until the
  10,000-candidate window was exhausted; every page was empty, bounded, and
  claimed a next page.

The comparator now follows the physical order (primary, qualifier with nil
first, owner), a qualified row's cursor edge records its qualifier eid, and
every backend resumes a partial scan at the boundary row's exact position.
Datalevin needed one more change: its AVE seek applies an entity component to
every datom it returns, not only to the first value, so a resumed scan also
dropped plain rows of later primary groups whose owner eid was smaller than
the boundary owner. It now positions inside the boundary value group and
continues from the adjacent value without an entity component.
The engine also checks that each scanned row advances strictly in that order
and fails closed with `:eacl/backend-contract-violation :strict-order`
otherwise, so an order disagreement cannot become a silent skip or a livelock.

Reproduce through the dev nREPL:

```sh
EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay
```

The replayed regression is the shared partial-scan contract on DataScript;
the same contract runs on Datomic, Datahike (both attribute modes), DataScript
CLJS, and Datalevin.
