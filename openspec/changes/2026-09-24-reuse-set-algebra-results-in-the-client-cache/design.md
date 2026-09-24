# Design

## Context

See proposal.md for the motivation.

**The client cache.** `make-client` gives each client a basis cache. Its
subproblem store holds two tiers:

- **Answers.** Completed public results, keyed by the exact basis and by a
  managed key whose proof frame survives unrelated writes. For a qualified
  `can?`, lookup or count, the key drops the evaluation time from the
  qualification identity, and the value records the certified interval that
  `temporal/answer-reusable?` checks.
- **Denotations.** Exact-basis point decisions. `exact-denotation-key-fn`
  wraps each semantic key with the source lifecycle, the authorization ABI and
  the exact basis. Cached evaluation binds the store and key function for a
  cache-enabled request (`compute-with-subproblems`); `:cache? false` binds
  neither.

`export-basis-snapshot` exports both tiers as flat entries, and
`restore-basis-snapshot!` imports them under trust. For denotations,
`denotation-value-shape?` admits only `:operator-acyclic-point` and
`:operator-recursive-point` entries with Boolean values, so qualified entries
are never exported.

**Who uses the denotation tier today.** The vector evaluator
(`check-cached-many-eids`) and the recursive evaluator
(`evaluate-cached-many`) publish one root decision per candidate. Lookups,
counts and checks of operator permissions read them. Qualified entries are
keyed by `[:qualified-point format scope (exact-reuse-identity q)]`, whose
identity includes `(:time q)` in milliseconds, and hold encoded evidence.

**Who does not.** The delegated and guarded oracles: `check-many-eids`
decides operands for many resources of a subject, and `check-eids` decides
one point, for operator checks and for public checks of union-only
permissions. Their results live in a request-local membership context.

**Evidence.** A complete decisive value certified until `T` holds at every
time in `[computed, T)` on the same basis and context (`QualifiedTemporal.dfy`,
`WitnessCertificateIsSound`). Plain `false` has no end, because relationships
only expire. A false result that could turn true later carries a deadline in
its evidence. `complete?` describes the certificate, not conditionality. A
caveat residual normally has a complete certificate, so it is reusable within
it under the same caveat context. Only an incomplete certificate is pinned to
its own time.

## Goals / Non-Goals

**Goals:**

- Reuse point decisions across requests on one client, across evaluation
  times within their certificates, and across a snapshot export and restore on
  the same basis.
- Share operand decisions between the operator permissions and checks that
  need them.
- Keep every answer identical, with and without the cache.

**Non-Goals:**

- Publishing the search's intermediate state: per-level memos, holdings, skip
  bounds or intermediates. It is large, and exact only under
  request-specific conditions.
- Managed (cross-revision) reuse for denotations. The denotation tier stays
  exact-basis; the answer tier's managed reuse is unchanged.
- A new cache, tier or retention policy, or a snapshot format change.
- Caching adapter scans. The client's scan tier already does that.

## Decisions

### D1. Qualified point decisions carry their certificate, not their time

**Keys.** A qualified point key ends with its certified scope,
`(qualification/certified-denotation-scope q)`:
`[:certified-point evidence-format qualifier-format context evaluator]`. It is
`exact-reuse-identity` without two slots:

- the evaluation time, which the value's certificate replaces;
- the basis. Every denotation storage key carries the exact basis key as its
  reuse identity, and orchestration derives that key from the same adapter and
  semantic snapshot identity the qualification records as its basis. Keeping
  a second copy made every cross-request hit compare two basis maps
  structurally.

The scope is memoized per request, so its hash is computed once. The marker
keeps these keys disjoint from the old time-keyed ones, which were never
exported and are unreachable now. Because the scope is a qualified key's last
component (`point-reuse/scoped-key`), restore can tell which value a key must
hold (D3).

**Values.** A value is `temporal/point-answer`: the computation time, the
evidence's `valid-until`, completeness, permissionship and the encoded
evidence. Faults are never published, as today.

**Lookup.** A hit requires `(temporal/reusable? value (:time q) true)`. The
third argument is true because the denotation key already fixes the exact
basis. The decoded evidence is then observed on the request's qualification,
as the existing code does.

**Publication.**
- Publication passes `:replace?` with `temporal/supersedes?`, so a later
  computation replaces an entry it could not reuse, and an older one never
  evicts a newer one.
- Unqualified keys and Boolean values are unchanged.
- This applies to the recursive and vector evaluators' root decisions.

*Alternatives:*

- **Keep the time in the key.** No cross-request hits with a real clock.
- **Round the time to buckets.** Arbitrary, and still misses at every bucket
  edge.
- **Store the value at a later point in time.** Needs a new certificate
  model; the answer tier's interval rule already exists and is proved.

### D2. Operand decisions are point decisions of their own permission

`check-eids` and `check-many-eids` consult and publish the key
`[:membership-point version plan-fingerprint subject-type subject-eid resource-eid]`,
followed by the certified scope when the request is qualified.

**Key.**
- `plan-fingerprint` is the operand's sealed union plan or guarded program.
  A guarded program's fingerprint names the operator plan and the member.
- The scope is absent unqualified, or the D1 certified scope.
- The key has no direction: a membership decision is the same fact from
  either side.

**Values** are Booleans unqualified, and D1's temporal point answers when
qualified.

**`check-many-eids`.**
- A resource is looked up after the request-local answers and before
  `decide-leveled`. A hit is recorded in the request-local answers too.
- After the whole call has succeeded, every value it computed is published,
  including exact fallback values from `check-eids` or the tabled evaluator.
  Faults and the internal deferral marker are never published.

**`check-eids`** looks up at entry, before the membership-probe search, and
publishes its non-fault result.

**Sharing.** The oracle already routes operand decisions through these two
functions, so sibling operator permissions, guard evaluation (which calls
the oracle) and public checks of union-only permissions all share one key per
fact.

*Why resource-level only.* Resources are what walks, counts and checks ask
for, so they bound the entry count at one per demanded fact. Intermediate
states would multiply it by the search's depth and churn the bounded tier.

*Certificates.* Batched decisions certify the widest witness and point checks
their first witness. Both are sound. Whichever publishes first wins, until a
computation that cannot reuse it replaces it.

*Alternatives:*

- **Per-subject membership snapshots in one entry.** Larger values, merge
  races, and growth under replacement.
- **Publish only from operator evaluation.** Misses union checks, which can
  reuse the same facts at no extra cost.

### D3. Snapshots carry the new entries

`denotation-value-shape?` admits `:membership-point` entries beside the
operator point entries, and delegates the value to
`point-reuse/stored-value-valid?`: a key that ends with a certified scope
holds a temporal point answer satisfying `temporal/point-answer-valid?`,
whose decoded evidence agrees with its permissionship, certificate end and
completeness; any other key holds a Boolean. Export and restore use the same
predicate, so a restored client reuses the entries within their
certificates, and a value that disagrees with its key fails the restore.

Across clients, keys match only when the clients' exact basis keys do. A
client with custom ID converters gets a per-client codec fingerprint unless
it declares a stable `:adapter-fingerprint` and `:adapter-deterministic?
true`, as a host that restores snapshots must.

Existing trust rules still apply: a computation that consumed an imported
entry does not publish its own result (`imports/derived?`).

### D4. Capacity is the client's existing bound

Operand entries count against `:denotation-max-entries`, 4,096 by default.
A walk of N candidates now publishes up to N entries per operand besides its
N roots: about 6,200 for a two-operand walk of the 2,077-account chart, which
the default does not hold. The evicted decisions are recomputed. A client
that persists its cache should size this bound; the Datahike demo persists up
to 8,192 entries, and the gate uses 16,384.

### D5. Store operations cost less than the work they save

An operand decision costs 1 to 5 µs to compute, so an entry must cost less
than that to read and write. The first implementation spent 2 to 8 µs per
entry in validation, key construction and bookkeeping, and a walk that reused
every operand was slower than one that computed them. So:

- `subproblem/lookup-denotations!` and `publish-denotations!` take a batch.
  They check the request stage, validate the options, record the metrics and
  bump the content revision once per batch. They key, look up, validate,
  insert and count each entry exactly as the single-entry functions do.
- `cache-key/exact-denotation-key-builder` validates the fields a request
  shares once, and builds each key with one allocation-free check of its
  semantic part. The storage-key check indexes vectors instead of walking
  seqs.
- A cache lifecycle keeps the constructor of the last basis it saw. The
  requests of one client on an unchanged basis share it, and with it the
  basis and source identity values in every key. A later request's lookup
  then matches a resident key by identity.
- `temporal/point-answer-valid?` and `interval-valid?` check their closed
  shapes without allocating key sets. `evidence/decode` reads the scalar
  envelope (a Boolean with a deadline) directly, and keeps it only when it
  re-encodes to the payload, the check the generic read must pass anyway.

### D6. Certification

- **Dafny.** `formal/dafny/CertifiedPointReuse.dfy` proves that an entry
  computed at `s` with evidence certified until `T`, reused at `t` with
  `s ≤ t < T`, has the value and permissionship a fresh decision has at `t`,
  for any evidence tree over qualified edges. It composes
  `QualifiedTemporal.dfy`'s qualifier certificate soundness with its
  evidence-tree certificate lemma. It also proves that an incomplete
  certificate is reused only at `s`, that keys ignore only the time, that a
  replaced entry had no later reuse, that observing a reused certificate
  bounds the request's, and that an admitted restored entry decides its
  kind.
- **Differential.** `eacl.cache.set-algebra-reuse-differential-test` runs on
  DataScript with a clock that advances by random steps. It uses random
  operator and guarded schemas and random qualified relationships (plain,
  expiring, caveated). Random requests run on one caching client: walks
  (definite and detailed), counts, checks of operator and operand
  permissions, and caveat contexts. Each result must equal the same request
  with `:cache? false` at the same time. The campaign also exports and
  restores the cache mid-run.
- **Mutation controls.**
  - reuse at or after the certificate end;
  - a key without the caveat context;
  - reuse of an incomplete certificate at a later time;
  - reuse without observing the reused certificate on the request.

  The deferral marker never reaches the store: a qualified publication of
  anything but evidence fails in `temporal/point-answer`, and an unqualified
  one fails `boolean?`. A unit test pins both.
- **Contract.** A new operation entry, `:set-algebra-result-reuse`.

### D7. Gate

`eacl.bench.set-algebra-reuse-test` (Datahike, `^:benchmark`) uses the
operator-shapes charts and a client clock atom.

**Samples.**
- Each sample builds a fresh client, compiles its plans with a `:cache? false`
  walk, runs the setup untimed at `t0`, advances the clock to `t0 + 60 s`, and
  times the measured request.
- The reference arm skips the setup.
- Arms are interleaved. Counts come from the membership and subproblem
  statistics.
- The chart maps ids with custom converters, so its clients declare a stable
  `:adapter-fingerprint` (D3), and hold 16,384 denotations (D4).
- The restored arm restores before compiling plans: a restore installs a
  fresh derived-schema store.

The budgets are the spec's.

## Risks / Trade-offs

- [More entries evict useful ones sooner] → The bound is unchanged and
  configurable, and resource-level publication keeps the count to one entry
  per demanded fact.
- [Per-candidate lookups add overhead to a cold walk] → The gate bounds the
  first cached walk at 1.25× cache-free. Before this change, publishing root
  decisions alone cost 1.38 to 1.40× on the 636-account chart.
- [A deployment that never reuses pays for publication] → A host that builds
  a client per request and never restores a snapshot gains nothing from these
  entries. `:cache? false` or `eacl.cache/no-cache` skips them.
- [A restored entry's decision is shorter-lived than a fresh one] → Each
  entry is reused only within its own certificate. Replacement lets a later
  computation extend it.
- [Clock skew between processes] → An entry is reused only at or after the
  time it was computed, never earlier.

## Migration Plan

No stored data changes. Time-keyed qualified entries become unreachable and
age out of the LRU. Snapshots from earlier versions restore as before; they
never contained qualified entries. Rolling back leaves the new
`:membership-point` and certified entries unreachable under the old code's
keys, and the old code's restore validation rejects them.
