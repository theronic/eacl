## Why

EACL v8 already reuses authorization work across bases through a proof-carrying boundary: a basis adapter returns raw relation stamps, core validates them into one request-scoped frame (certified schema generation plus the scalar frontier of the request's complete relation closure), and every reusable artifact — completed answer, managed subproblem, cursor, checkpoint — is admitted by equality of a key that contains that frame. `ScalarFrontierCoherence.dfy` proves the rule sound under explicit premises. The runtime leaves four of those premises unstated or unchecked, and one documented restriction is stronger than the theorem:

1. **Lineage.** Generations are comparable only along one linear history. The identity that separates histories already exists — `source-scope` carries the persisted Datomic database id, the Datahike store id and branch, Datalevin's persisted source id, and a fresh per-connection id for DataScript and in-memory Datahike — but it is absent from the managed completed-answer key, and no adapter obligation names it as the lineage witness. The planned constant default lifecycle (`add-authorization-views`) removes the accidental process isolation the random default provided, so the witness must be explicit.
2. **Numeric domain and ceiling.** The theorem requires every stamp visible at a basis to be no later than that basis. Datomic returns stamps as transaction entity ids while its revision is `basis-t`; nothing fixes one domain, and nothing checks `stamp <= revision`. A defective adapter's frame is accepted as evidence.
3. **Duplicate schema evidence and flat failure classification.** The frame reads the schema generation a second time and cross-checks it against the certified `:schema-generation` operation that reads the same datom; every other defect — malformed, duplicated, out-of-order, future stamps — is classified as "unavailable", an optimization miss that is retried on the next request even though it contradicts a certified invariant.
4. **Direction.** The lifting rule is documented as forward-only (`dependency-validated-authorization-cache`, and `add-authorization-views` design §8 would add a direction check on the grounds that "a symmetric lemma does not exist yet"). The lemma exists: `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` (`ScalarFrontierCoherence.dfy:858`) is proved and cited nowhere. The main theorem is an equality over the two endpoints of one history segment and does not care which endpoint holds the cached value; the implementation performs no direction check today and is correct. A direction check would discard hits for retained snapshots in exactly the reader-Peer pattern `add-authorization-views` exists to serve.

5. **Readability.** Datomic declares `:eacl/relation-version` with `:db/noHistory true` ("only the current stamp is ever read"). That premise fails for any basis selected by exact locator — `d/as-of` — which is exactly how a reader Peer holds a session: superseded stamps are dropped at the next index job, so whether a frame can be read at an as-of basis depends on indexing timing. The Datomic client already carries a special case (`exact-fallback-decision`) to cope, and `add-authorization-views` makes every historical-class basis exact-only partly for this reason. The storage saving is one history datom per affected relation per write, beside the two tuple datoms every relationship write already retains.

Independently, there is no way to read the cache without populating it: `:cache? false` bypasses both lookup and publication.

## What Changes

- **Reuse rule, stated once.** Two bases are comparable iff they share one lineage: equal source scope and source lifecycle. A reusable artifact is keyed by its semantic request, its configuration fingerprint, and the frame `{:schema-generation :dependency-stamp}` at its computation basis; it is reused at any basis in the same lineage whose frame is equal, in either direction. No certificate, kernel, or order comparison is added: the decision is key equality, as it is today.
- **Frame contract hardened.** The adapter `:proof-frame` operation returns relation generations only, in the same numeric domain as `:native-revision`; core takes the schema generation solely from the certified `:schema-generation` operation; core asserts every generation is `<= revision`. Datomic converts transaction ids to `t` and retains stamp history (`:db/noHistory false`), so a frame is readable deterministically at every admissible basis.
- **Frame readability, not basis class, gates lifting.** An admissible basis of any kind may use managed lifting when its frame is readable in its lineage; a basis whose frame is unavailable uses the exact tier only. This relaxes the historical-class exclusion `add-authorization-views` keeps as its conservative baseline.
- **Contract violation is not unavailability.** Absent or transiently unreadable evidence remains an optimization miss. Malformed, duplicated, non-canonical, or above-ceiling evidence is a backend contract violation: the request still evaluates exactly, the runtime disables managed lifting until `expire-cache!`, and the event is counted and reported. No proof epoch, registry, or cluster protocol.
- **Lineage in every key.** The managed completed-answer tier is keyed by lineage and schema generation, then `[semantic-key kind dependency-stamp]`, matching the managed subproblem and continuation keys. The single "installed managed generation" and its order guard are replaced by a bounded map of schema generations, consistent with retained bases.
- **`:populate-cache?`** request option, default `true`: `false` performs every read the request would otherwise perform and publishes nothing across requests.
- **Formal and certification deltas.** The Dafny model names lineage explicitly and the assurance matrix cites the existing direction-agnostic corollary; adapter certification executes the domain and ceiling obligations; the trust manifest lists them.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `dependency-validated-authorization-cache`: lineage-scoped, direction-agnostic lifting; lineage in the managed key; contract-violation classification; `:populate-cache?`.
- `forward-history-cache-coherence`: frame contract (relation generations only, one numeric domain, ceiling, retained stamp history), lineage premise, direction-agnostic rule, readability instead of basis class, violation versus unavailability.
- `authorization-snapshots`: runtime sharing follows frame readability rather than basis class.
- `backend-native-revision-consistency`: source scope is the lineage witness; non-durable sources mint a per-live-source identity; lifecycle rotation remains the replacement boundary.
- `modular-backend-workspace`: the proof-frame operation shape and obligations; one request frame shared by answers, subproblems, cursors, and checkpoints.
- `managed-reuse-certification`: executable domain/ceiling certification; differential coverage of older retained bases.
- `formally-verified-authorization-engine`: lineage premise and direction-agnostic corollary in the model and manifest; the cache-equivalence requirement restated against basis identity and the frame rule.

## Impact

- Modules: `eacl.proof-frame`, `eacl.cache`, `eacl.subproblem-cache`, `eacl.request.context`, `eacl.backend.v8` obligations, bundled adapter `:proof-frame` implementations (Datomic domain conversion), client option validation, `cache-stats`.
- Depends on `add-authorization-views` for basis identity, the three backend roles, and retained bases; supersedes its design §8 direction restriction and the "Candidate is newer than a retained snapshot" scenario in its `dependency-validated-authorization-cache` delta, which should be dropped before that change is implemented.
- Datomic `dependency-stamp` values change domain (`t` instead of tx entity id); v8 is unreleased, so no entry, token, or cursor migration exists.
- Cursor continuation (`enable-proof-equivalent-cursor-streams`) and checkpoint reuse (`enable-proof-equivalent-checkpoints`) apply this rule to their artifacts; Datalevin (`certify-datalevin-ordered-generation-proofs`) supplies a frame under it.

## Related changes

Already applied or archived; this change modifies their outcomes rather than their artifacts:

- `eliminate-authorization-request-amplification` (applied): its task 2.3 kept the frame's `:schema-stamp` and added the agreement check against the certified `:schema-generation`; both are removed here because every bundled adapter reads the same datom for both.
- `archive/2026-08-15-redesign-cross-backend-freshness-cache`: origin of the forward-only wording in `dependency-validated-authorization-cache`, now lineage-scoped.
- `archive/2026-08-15-remove-unknown-cache-coherence` and `archive/2026-08-15-simplify-cache-coherence`: origin of the scalar-frontier requirement and the `:tx`-reading `ordered-generation-frame` implementations whose numeric domain this change fixes.
- `archive/2026-08-15-upgrade-datascript-datahike-to-v8`: origin of the Datahike memory-store `:id` default (`schema.clj:155-159`) that a caller-supplied fixed id overrides; the per-live-source obligation closes it.
- `archive/2026-08-15-eacl-v8-root-fixes` (`trusted-surface-hygiene`): deleted the cross-process authenticated cache path; this change deliberately does not reintroduce one.
