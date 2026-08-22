# EACL Datalevin

`dev.eacl/eacl-datalevin` is the CLJ-only EACL v8 adapter for a qualified
embedded Datalevin database. The initial contract is deliberately narrow:

- one local embedded database, JVM, connection, and synchronous writer;
- platform-thread request execution with acquiring-thread snapshot ownership;
- fresh explicit snapshots for minimize-latency, fully-consistent, and
  at-least-as-fresh reads;
- no exact historical snapshot selection, remote/server, HA, replica,
  multiple-writer, or WAL qualification;
- physical schema frozen after bootstrap; and
- revision-bound cache and cursor reuse with no ordered-generation proofs.

Every request snapshot owns a public Datalevin read handle and closes it after
the complete EACL response is realized. The module never treats Datalevin's
ordinary live DB handle as an immutable snapshot.

Development uses the sibling maintained-fork checkout containing the public
read-snapshot API. Publication is blocked until that fork is released as the
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
`dev.eacl/datalevin-embedded-eacl:1.0.2-eacl.1`. Neither is a usable published
dependency until the release and clean remote-consumer gates pass.

Construction requires an exact topology declaration plus externally retained
lifecycle, signing material, and revision state. Omitting a signing key/keyring
or supplying a nil lifecycle fails construction; the shared development key is
never used by this module:

```clojure
(require '[eacl.datalevin.backend :as datalevin-backend]
         '[eacl.datalevin.core :as datalevin])

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
    :datalevin-topology
    datalevin-backend/certified-topology-declaration}))
```

`minimize-latency` and `fully-consistent` each acquire a fresh explicit reader
at the qualified local sole-writer head. `at-least-as-fresh` retries fresh
readers until the authenticated revision floor is visible or the original
deadline/cancellation terminates the request. `at-exact-snapshot` always
throws `:eacl.consistency/exact-snapshot-unavailable`. The module never
advertises ordered generations and never supplies a `:proof-frame`, so cached
answers cannot lift across revisions.

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

See [PORTING.md](PORTING.md) for the adapter boundary and unsupported
configurations.
