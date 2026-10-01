# Tasks

## 1. Gate

- [x] 1.1 Author `eacl.bench.set-algebra-reuse-test` (Datahike, `^:benchmark`) with the spec's budgets before any sampling of the implementation. Each sample uses a fresh client with compiled plans and a clock atom. Verify the gate loads, asserts every sample's answer, and records a baseline under `target/benchmarks/set-algebra-reuse/`.

## 2. Certified cross-time point reuse (design D1)

- [x] 2.1 Add `qualification/certified-denotation-scope`: the exact reuse identity without its time or basis slots, memoized per request. Verify a unit test: two requests that differ only in time or basis share it, and requests that differ in context or evaluator do not.
- [x] 2.2 Key qualified root decisions of the recursive and vector evaluators by the certified scope. Store temporal point answers, accept a hit only where `temporal/reusable?` holds, and replace with `temporal/supersedes?`. Verify cross-time tests on DataScript:
  - a check after a walk at a later time reuses the walk's decision;
  - a grant whose share has expired is decided again and replaces the entry;
  - a conditional decision is reused within its certificate under the same context, and an incomplete certificate only at its own time (unit test of `point-reuse`);
  - a different caveat context misses.

## 3. Operand decisions (design D2)

- [x] 3.1 Consult and publish `:membership-point` decisions in `stable-route/check-eids` and `check-many-eids`. Lookups come after the request-local answers; publication happens after the call succeeds, of complete non-fault resource decisions including exact fallback values. Verify that a `delete_top` walk after a `delete` walk decides no operand resource the first decided, and that `can? read_account` after a `delete` walk is served from the entry, both with answers equal to uncached ones.
- [x] 3.2 Route guarded programs through the same keys and verify that an `inherited` check after an `inherited` walk reuses the member's decisions.

## 4. Snapshots (design D3)

- [x] 4.1 Admit `:membership-point` entries and certified temporal operator entries in `denotation-value-shape?`, for both export and restore, each value checked against its key (`point-reuse/stored-value-valid?`). Verify that a snapshot exported after a qualified walk restores into a fresh client on the same basis, whose check reuses the restored decision within its certificate and returns the uncached answer, and that a value disagreeing with its key fails the restore.

## 5. Store cost (design D5)

- [x] 5.1 Batch denotation lookups and publications per evaluation batch (`subproblem/lookup-denotations!`, `publish-denotations!`, `eacl.authorization.point-reuse`), build keys with a constructor that validates shared fields once and is shared by a lifecycle's requests on one basis, check closed shapes without allocating, and decode scalar evidence directly. Verify that the batch functions count and retain like the single ones, that the key builder equals the constructor, and that the scalar decode agrees with the generic reader on canonical payloads and single-character edits.

## 6. Certification

- [x] 6.1 Prove `formal/dafny/CertifiedPointReuse.dfy`: reuse within a certificate equals the fresh decision, and an incomplete certificate is reused only at its own time. Verify `dafny format` and `bin/formal verify`.
- [x] 6.2 Add the cache-on/off differential campaign. It runs random operator and guarded schemas and qualified relationships through advancing clocks, with random walks (definite and detailed), counts and checks under random caveat contexts, and a mid-run export and restore. Every result must equal `:cache? false` at the same time. Verify on the JVM, in ClojureScript, and with a 200-case sweep.
- [x] 6.3 Register mutation controls: reuse at or after the certificate end, a key without the caveat context, an incomplete certificate reused later, and a reused certificate left unobserved. Pin with a unit test that a deferral marker is never stored. Verify each control is killed on both runtimes.
- [x] 6.4 Add the `:set-algebra-result-reuse` contract entry and update `docs/formal-verification.md` and the cache documentation. Verify `bin/formal source-closure` and `bin/formal manifest`.

## 7. Delivery

- [ ] 7.1 Run the gate. Run the battery, the ClojureScript suites (unoptimized and advanced), the reflection gate and the formal gates, then open a pull request stacked on #202.
