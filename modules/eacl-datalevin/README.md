# EACL Datalevin

`dev.eacl/eacl-datalevin` is the CLJ-only EACL v8 adapter for a qualified
embedded Datalevin database. The certified contract is deliberately narrow:

- one local embedded database and JVM, with storage-serialized local
  connections and synchronous EACL writers;
- platform-thread request execution with acquiring-thread snapshot ownership;
- fresh explicit snapshots for minimize-latency, fully-consistent, and
  at-least-as-fresh reads;
- no exact historical snapshot selection, remote/server, HA, replica,
  multiple-writer, or WAL qualification;
- physical EACL schema frozen after bootstrap;
- a persisted storage write policy enforced after transaction expansion; and
- scalar ordered-generation frames for exact and dependency-equivalent answer
  reuse, plus certified-generation reuse of schema-derived plans.

Every transient request snapshot owns a public Datalevin read handle and closes
it after the complete EACL response is realized. A retained snapshot remains
thread-affine until `eacl/release!`; `:maximum-snapshot-retention-ms` may bound
its EACL lifetime and fail closed on the next access. The module never treats
Datalevin's ordinary live DB handle as an immutable snapshot.

Development uses the sibling maintained-fork checkout containing the public
read-snapshot and write-policy APIs. Publication is blocked until that fork is released as the
explicit `dev.eacl/datalevin-embedded-eacl` coordinate and its packaged native
runtime passes certification.

For a source checkout with the expected sibling layout, use:

```clojure
{:deps
 {dev.eacl/eacl-datalevin
  {:local/root "/absolute/path/to/eacl/core/modules/eacl-datalevin"}}}
```

The reserved release coordinate is
`dev.eacl/eacl-datalevin:8.0.0-SNAPSHOT`, depending on
`dev.eacl/datalevin-embedded-eacl:1.0.2-eacl.2`. Neither is a usable published
dependency until the release and clean remote-consumer gates pass.

Construction requires externally retained lifecycle, signing material, and
revision state. Omitting a signing key/keyring
or supplying a nil lifecycle fails construction; the shared development key is
never used by this module:

```clojure
(require '[eacl.datalevin.core :as datalevin])

(def conn (datalevin/create-conn "/var/lib/my-app/eacl"))
(def watermark (atom (load-watermark-from-durable-storage)))

(def client
  (datalevin/make-client
   conn
   {:security-key signing-key
    :source-lifecycle (load-source-lifecycle)
    :revision-watermark watermark
    :advance-revision-watermark!
    (fn [revision]
      (persist-watermark-durably! revision)
      (swap! watermark max revision))
    :maximum-snapshot-retention-ms 30000}))
```

`:datalevin-topology` was removed. Advisory declarations cannot establish
writer exclusivity. Construction instead checks executable fork capabilities,
the actual embedded environment, WAL/HA state, and LMDB flags. `:nolock`,
`:nosync`, `:nometasync`, `:mapasync`, and `:writemap` are rejected.

`minimize-latency` and `fully-consistent` each acquire a fresh explicit reader
at the qualified local sole-writer head. `at-least-as-fresh` retries fresh
readers until the authenticated revision floor is visible or the original
deadline/cancellation terminates the request. `at-exact-snapshot` always
throws `:eacl.consistency/exact-snapshot-unavailable`. The module advertises
certified ordered generations. `:schema-generation` and each requested
relation generation are scalar `:db.type/long` values equal to the `max-tx` of
the commit that changed them. The adapter reads one exact EAV probe per
requested relation from the owned snapshot. Equal lineage and frame permit
completed answers and managed subproblems to lift across unrelated revisions;
a relevant relation or schema change misses. Exact hits acquire no frame.
Snapshot acquisition reads only revision bounds from the maintained fork; it
does not read full metadata or fingerprint physical schema.

The shared aggregate routes use the same owned reader for their complete
batch or candidate window. Datalevin's certified `:direct-match?` operation is
reused by the enumerate route as exactly one snapshot-bound relationship probe
per candidate. The adapter has no backend-private aggregate loop, makes no
backend-private proof rule, and never moves an owned reader to another thread.

The qualified Datalevin fork does not currently expose a native in-memory
transaction operation with the transaction report required to certify actual
effects. `eacl/with` and `eacl/with-schema` therefore fail closed with
`:eacl/unsupported-capability`; EACL never guesses effects or admits an
ordinary live Datalevin handle as speculation. `eacl/tx-relationship` remains
available for read-only transaction planning on an EACL snapshot where useful.

The built-in `:eacl/id` codec treats public IDs as immutable for an entity's
lifetime. This is a supported-writer premise because application identity is
deliberately outside the Datalevin write policy. Set `:identity-immutable?
false` if IDs may be reassigned; cursors then remain exact-basis-bound rather
than using an authorization-only frame across revisions. Custom codecs require
an explicit immutable-identity certification for proof-equivalent cursors.

At bootstrap the module installs one normalized persisted write policy. Every
physical `eacl`/`eacl.*` attribute except `:eacl/id` is guarded and frozen; the
three Datalevin generation attributes are commit-generation longs. The fork
checks the fully expanded datom set before commit, so direct
`:db/retractEntity` cannot hide relationship-tuple retractions. Protected
writes without the module-held per-open token, missing stamps, stale stamps,
or frozen-schema changes abort atomically. Use EACL relationship operations,
`eacl/delete-object!`, or
`eacl.datalevin.safe-retraction/transact-retract-entity!`; do not submit the
returned safe-retraction transaction data directly when it touches protected
attributes.

Each commit reads persisted `max-tx` after obtaining LMDB's writer lock. A
foreign process that advanced the store causes
`:datalevin/max-tx-continuity-violation` and makes the environment unusable for
writes until reopen. Distinct local connections are supported: a stale
prepared generation is refreshed and boundedly replanned. Multi-process
writers remain unsupported and are detected, not coordinated.

The advance callback is synchronous and release-critical: EACL does not
acknowledge a bootstrap or authorization commit until the callback returns and
the dereferenced watermark is at least the committed Datalevin revision. The
callback must implement monotonic max semantics when requests commit
concurrently. A process-local atom is suitable only for tests; production must
load and atomically persist the value outside the Datalevin database so a
restored or rolled-back store cannot erase its own rollback detector.

Client construction rejects a persisted Datalevin revision below the external
watermark while the lifecycle is unchanged. An operator-authorized restore
must rotate `:source-lifecycle`, invalidate old tokens/caches, and establish a
new matching watermark before readiness. `expire-cache!` deliberately rejects
process-local lifecycle rotation. Persist the replacement lifecycle and
watermark first, close the old client/connection, and construct a new client.

For operational testing or capacity management, `clear-answer-cache!` evicts
completed answers, exact denotations, and resumable page state while retaining
flat derived-schema cache artifacts, sealed plans, signing configuration, and
source lifecycle. Sticky managed-proof distrust also remains in force. This is
not a recovery operation and must not be used after restore, rollback, or an
unsupported authorization mutation.

See [PORTING.md](PORTING.md) for the adapter boundary and unsupported
configurations. The trusted boundary requires this maintained fork for every
Datalog writer. Raw KV writes to Datalevin's datom/meta DBIs, direct file
mutation, or opening the directory with upstream Datalevin are outside it.

## Removed (2026-09-02)

- `eacl.datalevin.impl/{find-one-relationship-id,orphaned-relationship-halves,Relation,Permission,Relationship}`,
  `eacl.datalevin.db/entity-exists?` and the
  `eacl.datalevin.schema/validate-schema-references` alias — unreferenced
  since the module's integrity namespace was retired.

## Relationship storage 9

This EACL v8 adapter uses five-slot endpoint pairs with a trailing nullable
`qualifier-eid`. This phase writes `nil` and raises `:eacl/unsupported-qualifier`
when serving encounters a qualifier. Upgrades are explicit and restartable;
ordinary client construction requires a completed target store. Follow the
[7-to-9 operator guide](../../docs/migration-v7-to-v9.md) before starting clients.

The adapter's `create-conn` helper explicitly bootstraps fresh stores.
