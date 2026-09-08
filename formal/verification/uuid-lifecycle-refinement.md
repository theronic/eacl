# UUID lifecycle refinement and trust boundary

`formal/dafny/UuidLifecycle.dfy` proves exact 128-bit lowercase hexadecimal
round trips and injectivity, distinguishes UUIDs from strings, and connects
complete lineage equality to the abstract lifecycle fields in
`NativeGenerationCoherence.dfy` and `ScalarFrontierCoherence.dfy`. Their `nat`
identifiers denote complete backend/source/branch/UUID lineage. They do not
mean the UUID alone, and their coherence algorithms are unchanged.

| Model obligation | Production boundary | Executable witness |
| --- | --- | --- |
| Both halves preserved, canonical encoding injective | `eacl.uuid/capture`, `parse-canonical`, `eacl.secure-format/encode-canonical`, `decode-canonical` | `eacl.uuid-lifecycle-test`; independent `fixtures/uuid_vectors.py`; UUID representation mutation controls |
| Host identity cannot change after capture | `eacl.uuid/capture`; `secure-format/capture-portable`; constructor; source acquisition; cache restore | CLJS hostile wrapper, initialized hash, exposed basis, and imported-key tests |
| Complete scope is required | `backend.source/semantic-identity`, causal `token-data`, Relay scope comparison, cache restore lineage comparison | Same UUID/different source and same source/different UUID tests |
| Public lineage and private incarnation are independent | `client.orchestration/expire-cache!`, `install-restored-runtime-cache-lifecycle!`, coherent runtime capture | Snapshot lifecycle race suite; `EaclUuidLifecycle.tla` publication/restore controls |
| UUID sharing is deliberate | Omitted constructor sentinel, Datalevin explicit config, raw adapter lifetime | Constructor, backend restart, and raw adapter collision tests |
| Legacy artifacts cannot recover new authority | Causal, cursor, and snapshot ingress version checks | Upgrade error tests before acquisition/installation; key retirement suites |

`EaclUuidLifecycle.tla` explores capture, ordinary commit, clear/same-UUID reset,
fresh rotation, source/branch/backend changes, local publication, two-stage
restore, and imported artifact lineage eligibility. The model keeps source incarnation and cache-store generation separate. Narrow
clear changes only the store generation; same-UUID full reset changes both.
Publication into captured stores checks both. Restore installation checks
source incarnation and may rebase across narrow clear; successful installation
then retires the store generation. The existing snapshot race tests exercise
that deliberate distinction. A private source incarnation is
checked for local publication and installation; imported artifacts have no
meaningful process-local incarnation. Their lineage eligibility still needs
the separate authentication, revision, proof, and format obligations of the
existing models. It is not an authorization theorem by itself. Retained
captures remain unchanged while current state changes. The positive bounded
campaign is registered in `bin/formal apalache-check`; three registered negative
configurations omit scope, lifecycle, or private-incarnation guards and must
produce counterexamples, never merely time out. The detached-incarnation
control preserves the store-token guard, forcing the witness through a
restore/full-reset race. Detached store publication is independently exercised
by the existing `EaclCacheOrphanPublication.cfg` control.

Representation controls mutate named production definitions and observe their
consumers. They drop each half, coerce UUIDs to strings, collide the canonical
comparator, round through IEEE-754 numbers, or admit noncanonical aliases. Each
requires an unmutated baseline, evidence that the mutation ran, and a changed
observation. Additional controls join the scored registry without rewriting
historical audit counts. Current results and source closure belong under
`target/formal/`; source contains no observed proof counts or source hashes.

Trusted boundaries are explicit: JVM/CLJS runtime and prototype integrity,
the cryptographic implementation, the EDN reader after bounded/tagged admission,
native backend truthfulness, the random UUID generator, and the host's durable
coordination/persistence. Tests refine handwritten code; it is not claimed to
be extracted from Dafny. RNG uniqueness is not proved. The host must never
reuse a retired UUID for another history of the same source. The model's
`RecycledUuidCanReauthorize` witness records why UUID type alone cannot enforce
that condition. A fresh process cannot discover lost lifecycle configuration.

The review also covers two indirect consumers found during implementation:
`subproblem-cache/completed-page` must recognize the upgraded answer envelope
to retain its size guard. `engine.stable-page/execution-binding` also binds full
source scope and validates the UUID, closing its bare-lifecycle/revision collision.
Raw Relay adapters require their own lifetime identity rather than nil or an unscoped snapshot number. Public source paths
continue to use their coordinated durable lineage.

Proof and bounded testing establish the stated properties under these
assumptions. They do not prove all host programs safe or establish literal
100% confidence in deployed systems. Performance and deployment qualification
are separate release obligations, described in the change and upgrade guide.
