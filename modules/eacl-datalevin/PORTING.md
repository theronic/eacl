# Porting and operational contract

`eacl-datalevin` is not a mechanical namespace substitution for DataScript or
Datahike. An ordinary Datalevin DB object is live. Every EACL read therefore
enters the shared snapshot-provider lifecycle, owns one public explicit
Datalevin reader, eagerly externalizes its result, and releases the reader on
the acquiring platform thread.

The certified declaration is
`eacl.datalevin.backend/certified-topology-declaration`. Client construction
requires that exact map and independently checks the native connection profile.
The module rejects remote stores, WAL, HA, unsafe LMDB durability flags,
multiple connections or writers, external writer ownership, virtual request
threads, mutable physical schema, and any undeclared topology variation.

Supported consistency modes are minimize latency, fully consistent, and at
least as fresh. On the sole-writer local topology, a fresh explicit snapshot is
the local head linearization point. At-least retries fresh candidates until the
authenticated revision floor is reached, the original request deadline
expires, or cancellation is observed. Exact historical selection is rejected;
the module advertises no exact, historical, durable-history, or ordered
generation capability.

Physical EACL schema and a random source UUID are installed before readiness.
Every existing physical attribute is compared after Datalevin tuple-type
inference; missing, renamed, retyped, recardinalized, reindexed, or wrong-arity
attributes fail startup. Physical schema is frozen after bootstrap. Logical
SpiceDB schema changes remain ordinary fenced EACL transactions.

Relationship writes maintain forward and reverse tuple halves atomically and
stamp relation versions with `:db/current-tx`. Create conflicts are rechecked
inside the serialized writer. Schema and relationship mutations cross-check
write fences. Object cleanup rescans inside a Datalevin transaction function,
so an intervening relationship commit cannot escape a later-linearized delete.

All revisions, entity IDs, relation/permission IDs, cursor bounds, and returned
scan IDs are restricted to the portable exact-integer domain
`0..9007199254740991`. Exceeding it fails closed without conversion.

Third-party adapters must not call private Datalevin reader functions. Depend
on the explicitly named maintained snapshot-API artifact, retain owned reader
handles only inside EACL's selected-snapshot scope, declare execution
constraints statically, and provide a complete semantic identity containing
backend, source UUID, lifecycle, revision, exact locator (nil here), and the
physical-schema fingerprint.

Signing material, source lifecycle, and the monotonic revision watermark are
external durability inputs. The module rejects the shared default signing key,
nil/implicit lifecycle generation, and process-local `expire-cache!` rotation.
Restore tooling must durably rotate lifecycle and establish its new watermark
before recreating the client; old tokens then fail source-lifecycle
authentication.
