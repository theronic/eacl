# Porting and operational contract

`eacl-datalevin` is not a mechanical namespace substitution for DataScript or
Datahike. An ordinary Datalevin DB object is live. Every EACL read therefore
enters the shared snapshot-provider lifecycle, owns one public explicit
Datalevin reader, eagerly externalizes its result, and releases the reader on
the acquiring platform thread.

There is no topology declaration. Client construction checks executable fork
capabilities and the native connection profile. The module rejects remote
stores, WAL, HA, and LMDB `:nolock`, `:nosync`, `:nometasync`, `:mapasync`, and
`:writemap`. Multiple local connection atoms are serialized by the shared
Store and stale preparations are boundedly replanned. A second writer process
is detected by persisted `max-tx` continuity and poisons writes until reopen.

Supported consistency modes are minimize latency, fully consistent, and at
least as fresh. On the sole-writer local topology, a fresh explicit snapshot is
the local head linearization point. At-least retries fresh candidates until the
authenticated revision floor is reached, the original request deadline
expires, or cancellation is observed. Exact historical selection is rejected;
the module advertises no exact or historical capability. It does advertise
ordered generations over scalar commit-generation longs.

Physical EACL schema, a random source UUID, a schema singleton, native scalar
generations, and a persisted write policy are installed before readiness.
Every existing physical attribute is compared after Datalevin tuple-type
inference; missing, renamed, retyped, recardinalized, reindexed, or wrong-arity
attributes fail startup. Physical EACL schema is frozen after bootstrap.
Logical SpiceDB schema changes remain fenced, token-admitted EACL transactions.

Relationship writes maintain forward and reverse tuple halves atomically and
stamp `:eacl.datalevin/relation-generation` with scalar `:db/current-tx`.
Schema writes similarly stamp `:eacl.datalevin/schema-generation` and the
write fence. The fork materializes those values without entity allocation and
requires equality with committed `max-tx`. Create conflicts are rechecked
inside the serialized writer. Schema and relationship mutations cross-check
write fences. Object cleanup rescans inside a Datalevin transaction function,
so an intervening relationship commit cannot escape a later-linearized delete.

The persisted policy guards every EACL storage attribute except `:eacl/id`,
requires exact relation/schema stamps after transaction-function and
`retractEntity` expansion, and protects schema/admin mutation paths. Direct
retraction of a permissioned object is rejected with guidance to
`delete-object!`. Safe component retraction must be submitted through
`eacl.datalevin.safe-retraction/transact-retract-entity!` so it carries the
admission token and advances the external watermark.

All revisions, entity IDs, relation/permission IDs, cursor bounds, and returned
scan IDs are restricted to the portable exact-integer domain
`0..9007199254740991`. Exceeding it fails closed without conversion.

Third-party adapters must not call private Datalevin reader functions. Depend
on the explicitly named maintained snapshot-API artifact, retain owned reader
handles only inside EACL's selected-snapshot scope, declare execution
constraints statically, and provide a complete semantic identity containing
backend, source UUID, lifecycle, revision, exact locator (nil here), and the
physical-schema fingerprint.

An ordered-generation adapter returns relation-only frames in canonical id
order, one EAV probe per relation, eagerly realized within the owned reader.
Missing generations are proof-unavailable. Non-integers, negative values, or
values above the selected `max-tx` are contract violations that sticky-disable
managed lifting for that client.

Signing material, source lifecycle, and the monotonic revision watermark are
external durability inputs. The module rejects the shared default signing key,
nil/implicit lifecycle generation, and process-local `expire-cache!` rotation.
Restore tooling must durably rotate lifecycle and establish its new watermark
before recreating the client; old tokens then fail source-lifecycle
authentication.

Every Datalog writer must use the maintained fork. Raw KV mutation of the
datom/meta DBIs, direct file edits, and upstream Datalevin binaries bypass the
policy and are outside the certified boundary.
