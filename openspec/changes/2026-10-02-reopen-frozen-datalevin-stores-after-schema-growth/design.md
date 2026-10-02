## Context

Found on 2026-10-02 while deploying `theronic/eacl-demo` PR 115. A service
built on `6982d388` created and seeded a Datalevin store: `create-conn`,
`make-client` (which runs `ensure-physical-schema!` and freezes the EACL
attributes), a schema write, relationship writes. A service built on
`eeb1f844` then opened the same directory and failed in

```
eacl.datalevin.schema/create-conn
  datalevin.conn/get-conn -> datalevin.storage/open -> init-schema
    datalevin.write-policy/assert-schema-admitted!
```

with `"A frozen Datalevin write policy rejected a schema mutation."`,
`{:type :datalevin/frozen-attribute-write :operation :update-schema}`.

Between the two commits `eacl.datalevin.schema/datalevin-schema` grew from 32
to 34 attributes. Wildcard subjects (`13f08cd4`) added
`:eacl.relation/allows-unqualified-wildcard?` and
`:eacl.relation/wildcard-caveats` through `eacl.caveats.schema/datom-schema`.
The policy computation, the bootstrap and the relationship storage version
(8) are the same in both commits.

Two separate checks stand between an earlier store and a later module.

1. **Datalevin, at open.** `create-conn` passes the whole module schema to
   `d/get-conn`. `init-schema` compares it with the stored schema and, when
   any attribute differs, calls `assert-schema-admitted!` with every key of
   the submitted map. The frozen attributes are among those keys, no token can
   be bound before the environment that mints it is open, and the open fails.
   The error names the 31 frozen attributes, none of which changes, and
   neither of the two that are being added.
2. **EACL, at client construction.** `ensure-physical-schema!` adds missing
   attributes and then requires the persisted policy to equal the policy the
   module registers for the physical schema. With two more attributes it no
   longer does, and replacing a persisted policy needs the token, which the
   function has only when a migration passes one.

Observed on the unmodified code, against the fork at `a7e29c25`:

| | Store | Action | Result |
| --- | --- | --- | --- |
| 1 | bootstrapped with the earlier attribute set | `create-conn` | fails as above |
| 2 | same | `d/get-conn dir`, then `d/update-schema` with only the two added attributes and no token | admitted; the attributes exist outside the policy |
| 3 | after 2 | `make-client` | `:eacl.datalevin/write-policy-drift`, caused by "Replacing a persisted Datalevin write policy requires admission." |
| 4 | bootstrapped by the current module with an `extra-schema` | `create-conn` with that `extra-schema` plus one application attribute | fails as in row 1 |
| 5 | bootstrapped by the current module | opened by the earlier attribute set | opens and constructs a client; nothing reports the difference |
| 6 | as row 1, with one connection open | a second `create-conn` through another spelling of the path | fails as in row 1, and Datalevin's shared-store reference count stays incremented, so the environment is not closed with its last connection |

Row 4 is the same defect without any EACL upgrade: an application cannot add
an attribute of its own through `create-conn` once the store is bootstrapped.

Sources: [schema.cljc — create-conn, ensure-physical-schema!](https://github.com/theronic/eacl/blob/43f150508b5b2740206e9121d39867cae8adabae/modules/eacl-datalevin/src/eacl/datalevin/schema.cljc#L159-L286);
[wildcard-subjects spec — "Wildcard branches are stored additively"](https://github.com/theronic/eacl/blob/43f150508b5b2740206e9121d39867cae8adabae/openspec/changes/2026-09-27-add-wildcard-subjects/specs/wildcard-subjects/spec.md);
[release notes](https://github.com/theronic/eacl/blob/43f150508b5b2740206e9121d39867cae8adabae/docs/release-notes-v8.0.md);
[relationships_v7_to_v8.clj — install!](https://github.com/theronic/eacl/blob/43f150508b5b2740206e9121d39867cae8adabae/modules/eacl-datalevin/src/eacl/datalevin/migrations/relationships_v7_to_v8.clj#L17-L25);
fork [storage.clj — init-schema](https://github.com/theronic/datalevin/blob/a7e29c25a3034b54814e58a2d317e8c6877d1933/src/datalevin/storage.clj#L277-L303),
[write_policy.clj — assert-schema-admitted!](https://github.com/theronic/datalevin/blob/a7e29c25a3034b54814e58a2d317e8c6877d1933/src/datalevin/write_policy.clj#L169-L183),
[conn.clj — acquire-shared-local-store](https://github.com/theronic/datalevin/blob/a7e29c25a3034b54814e58a2d317e8c6877d1933/src/datalevin/conn.clj#L140-L159),
[doc/write-policy.md](https://github.com/theronic/datalevin/blob/a7e29c25a3034b54814e58a2d317e8c6877d1933/doc/write-policy.md).

## What the write policy protects

A policy names guarded attributes (their datoms need the admitted writer),
frozen attributes (schema and administrative changes need the token),
commit-generation attributes, and stamp rules. EACL registers every physical
`eacl`/`eacl.*` attribute except `:eacl/id` as guarded and frozen. That gives
the cache-coherence proofs their writer premise: every change to
authorization data commits through EACL's writer and stamps the generation of
what it changed.

Freezing keeps that premise from being undone beside the data. Retyping an
attribute re-encodes its datoms through raw KV writes that the datom check
never sees. Renaming or deleting one detaches guarded data from its guard.
Changing a generation attribute's cardinality, or a tuple's arity, falsifies
what `install-write-policy!` verified. Each of these changes an attribute
that already holds guarded data.

An attribute that does not exist yet holds no data and has no binding to
protect, so adding it changes nothing the freeze protects. What it does
create is an EACL attribute outside the policy, and
`backend-native-revision-consistency` advertises ordered generations only
with "an installed write policy covering every physical EACL storage
attribute except application identity `:eacl/id`". The obligation that comes
with an added attribute is therefore to extend the policy to it before
anything is stored under it.

## Who should admit the change

Not the fork. It is EACL-agnostic by design and cannot know that an attribute
it has never seen is authorization storage that must be guarded and, for a
definition attribute, stamped. Its rejection at open is not a decision about
growth either. It rejects because it counts every submitted attribute as
touched, and it admits the same two attributes when they are submitted alone
(row 2). A fork that computed the touched attributes exactly would let the
open through and leave the attributes outside the policy, where client
construction refuses them (row 3). The policy has to be extended either way,
and only EACL can decide that.

EACL can, with what the fork already provides and the 7-to-8 migration
already uses. `install-write-policy!` returns the open's token for an
identical policy and replaces a policy only with that token, and
`dependency-validated-authorization-cache` specifies that "frozen schema or
database administration SHALL require the same per-open token through the
synchronous administrative scope".

## Options

### 1. Admit added EACL attributes under the write policy — chosen

It is what the wildcard requirement and the release notes already say
Datalevin does, what Datomic and Datahike do on the first wildcard schema
write, and it converts no data. The decisions below make it conditional.

### 2. An explicit, versioned migration step

The existing migrations are explicit because they rewrite relationships,
need a quiesced store, and can be interrupted halfway through user data.
Adding two empty attributes rewrites nothing and commits no transaction. A
step that only approves the addition would have to be run by every operator
on every release that adds an attribute, on this backend alone, against the
wildcard requirement. It remains the right tool for any change that is not
purely additive, and the refused cases below are where it would be needed.
It would not remove the need to change `create-conn`: a migration cannot run
on a store it cannot open.

### 3. Bump the storage version when the attribute set changes

`eacl.relationships.storage/version` is the relationship tuple layout shared
by every backend, and the wildcard requirement keeps it at 8. Bumping it
would send Datomic, Datahike and DataScript stores that need nothing through
`assert-compatible!`'s migration error. A Datalevin-only attribute-set
version would restate what the module already reads exactly, attribute by
attribute, from the stored schema, would itself be a new frozen attribute,
and would not open the store. Its one real use is the reverse direction (row
5): letting an earlier build notice a later store. That is the rollout rule
every backend already has ("upgrade every serving Peer before writing a
schema that uses wildcards"), not a property of this defect; see the open
questions.

### 4. Declare in-place upgrade unsupported and fail with a clear error

Rejected as the outcome and kept as the failure mode. Nothing in an earlier
store needs converting, and the wildcard requirement promises the attributes
are installed, so refusing would strand real data for no protective gain.
The cases that are refused still fail with typed EACL errors that name the
attributes.

### 5. Change the fork instead

Computing the touched attributes exactly in `init-schema` and `set-schema`
would stop the open-time rejection for callers who pass
`eacl.datalevin.schema/merge-schema` to `d/get-conn` themselves, and is worth
doing. It cannot complete the upgrade (row 3), needs a fork release and a new
pin, and is not required once `create-conn` stops resubmitting frozen
attributes. Listed under follow-ups.

## Decisions

### D1. `create-conn` declares only the application's schema at open

Datalevin applies a schema map given at open as one update of every
attribute in it. EACL's attributes are therefore not repeated there. The
application's `extra-schema` keeps Datalevin's open-time semantics, and a
grown `extra-schema` is admitted because none of its attributes is frozen.

### D2. `create-conn` adds the module's missing attributes only while no policy exists

A fresh store, or one opened before any client was constructed, gets the
missing attributes through `d/update-schema`, which yields the same attribute
definitions as declaring them at open; only Datalevin's internal attribute
ids are assigned in a different order. A protected store is left exactly as
stored: the existing rule that opening a connection must not submit an
unadmitted protected change now also covers its schema. A store that is not
embedded has no readable policy and is treated like one without a policy, so
client construction still rejects it with `:eacl/unsupported-topology`.

### D3. Client construction admits growth, and only growth

On a store with a policy, `ensure-physical-schema!` computes the attributes
the policy does not cover: module attributes that are missing, and physical
EACL attributes that are present but uncovered. If there are none, it behaves
as before. Otherwise it proceeds only when all of these hold.

- **The persisted policy is the module's own.** It asks the fork to install
  the policy the module registers over exactly the covered attributes. The
  fork returns its token only for an identical policy, so the token is both
  the proof and the admission.
- **Every uncovered attribute is the module's.** A physical `eacl.*`
  attribute the module does not declare is not adopted.
- **Every uncovered attribute holds no data.** Every datom of a guarded
  attribute was then committed under the policy, which is the premise the
  generation proofs rely on.

It then adds the missing attributes and installs the policy for the whole
physical schema with the token. Attribute definitions are still compared
first, so a redefined attribute fails with
`:eacl.datalevin/physical-schema-drift` before any of this.

### D4. Admission precedes any change, and an interrupted run completes

The checks and the token come first, together with the two checks
`ensure-physical-schema!` makes of every protected store afterwards: a valid
source identity and complete generation evidence. A store it refuses for any
of these reasons is left as found. Adding the attributes and replacing the
policy are two key-value transactions. A process that stops between them
leaves the attributes present, uncovered and empty, which is the second kind
of uncovered attribute in D3; the next construction finishes the extension.

### D5. Nothing else changes

No Datalog transaction is committed. The revision, the external watermark,
tokens and cursors are unaffected, and the upgraded store's policy is the one
a fresh bootstrap would install. After a completed extension the earlier
module still works with the store until something is stored under the added
attributes, so rolling the deployment back before then needs no restore.

### D6. Errors

The refused cases reuse `:eacl.datalevin/write-policy-drift` and add
`:missing-attributes`, `:uncovered-attributes` and `:populated-attributes`.
No error type is added.

## Risks / Trade-offs

- Constructing a client now changes a protected store's physical schema and
  policy without an operator step. It is limited to D3's conditions, converts
  no data and is reversible by deploying the earlier build. If an explicit
  step is preferred, D1 and D2 stand as they are and D3 moves behind a named
  function, with construction failing on `:missing-attributes` instead.
- The two transactions of the extension are not atomic. If the process stops
  between them, the added attributes are outside the policy until the next
  construction, and in that state the earlier build refuses the store with
  `:eacl.datalevin/write-policy-drift` (row 3). Starting the later build once
  completes the extension. Making it atomic would need a fork operation that
  commits schema and policy together.
- `ensure-physical-schema!` assumes a quiesced connection, as before. A
  writer racing the extension could store data under an added attribute after
  the emptiness check, and two clients constructed concurrently on an earlier
  store can report a spurious drift to the slower one. The certified topology
  constructs clients before it serves.
- An application attribute in an `eacl.*` namespace is frozen like EACL's
  own. Changing `extra-schema` while it contains one is still rejected by
  Datalevin at open.
- A caller who passes `merge-schema` to `d/get-conn` directly still meets the
  open-time rejection once either schema has grown. The module README now
  says to open stores with `create-conn`.
- The module is outside workspace CI, so this is verified by the module suite
  in the Datalevin nREPL and by hand-applied mutants of the change, not by a
  registered mutation control.

## Open questions

1. Is admission at client construction acceptable, or should it be an
   explicit call (see the first risk)?
2. Should an earlier build be able to detect a store that a later build has
   written to (row 5)? Today the rollout rule covers it on every backend.
3. `formal/counterexamples` replays on the CI classpath, which excludes this
   module, so this defect has no ledger entry. Should it have one once the
   module joins CI?

## Follow-ups outside this change

In the maintained fork, neither needed by this change:

- Pass only the attributes whose effective definition changes to
  `assert-schema-admitted!` in `init-schema` and `set-schema`, and write only
  those. Frozen attributes stay protected, and re-declaring them unchanged
  beside a new attribute stops being rejected.
- In `acquire-shared-local-store`, increment the reference count after
  `set-schema` succeeds, so a rejected schema does not leak a reference to
  the open store (row 6).
