## 1. Continuation Identity

- [x] 1.1 Store `:lineage`, `:frame`, and `:closure-digest` in the cursor dependency context from the request context; delete the second-level `continuation-proof` digest; increment the envelope version and remove version 11 handling and `legacy-cursor-scope`.
- [x] 1.2 Feed `DecideContinuation` the canonical encoding of `[lineage frame closure-digest]` as its proof inputs; keep the kernel signature and re-pin the generated-decision inventory.
- [x] 1.3 Use the request context's memoized frame so a continued page performs one relation-generation read per closure for cursor validation, answer lookup, and checkpoint lookup together.

## 2. Exact Fallback and Cleanup

- [x] 2.1 Implement exact fallback by identity in the shared relay (source scope, lifecycle, revision, locator) after freshness-floor validation, for every source supporting exact selection; delete the Datomic-only `exact-fallback-decision` when the Datomic client converges.
- [x] 2.2 Remove the unreachable `:conflict` continuation branch and `CursorConflict` (or give it a producer), delete the vacuous `:cursor-recovery` assertion, and re-pin the manifest.

## 3. Formal Bridge

- [x] 3.1 Add `ReducerReadScope.dfy` to `formal/stable-discovery`: scan descriptors name closure relations; transitions, emissions, order, and boundaries are functions of plan and closure slices; compose with the scalar-frontier theorem and the existing boundary theorems; pin its obligation count in `verify-fast.sh`.
- [x] 3.2 Add an executable mutation control that compiles a plan referencing a relation outside its closure and requires rejection; cite the bridge in the assurance matrix for proof-equivalent continuation.

## 4. Conformance and Documentation

- [x] 4.1 Add the shared cursor conformance matrix: unrelated write continues (oracle-equal concatenation, forward and reverse), relevant write rejects current and falls back only by capability, non-durable source recreation rejects for scope mismatch under the constant lifecycle and shared keyring, durable sources accept after restart, `:populate-cache? false` changes nothing.
- [x] 4.2 Run the matrix on Datomic, Datahike (durable and memory), DataScript (CLJ and CLJS), and Datalevin.
- [x] 4.3 Update `docs/cache.md` "Cursors and time travel" and `docs/v8-consistency-cache-operations.md` for lineage-scoped continuation, exact fallback by identity, and restart behaviour per source kind.
- [x] 4.4 Regenerate `public-source-closure.json`, run the CI-equivalent battery with the CLJS build last, and `openspec validate --strict`.
