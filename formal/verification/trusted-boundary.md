# Trusted computing base and proof assumptions

EACL's target theorem is conditional: the generated kernel refines the formal
semantics when its validated input and adapter obligations hold. It is not a
proof of the whole deployed system.

## Verification and compilation tools

The following are trusted to implement their documented behavior:

- Dafny, Boogie, and the bundled Z3 solver;
- Dafny's Java and JavaScript compilers and runtime libraries;
- Java and JavaScript compilers, bundlers, and runtimes used by consumers;
- Clojure, ClojureScript, their host interop, and generated boundary code.

Versions and artifact hashes are pinned in `formal/toolchain.lock.json`.
Reproducibility reduces supply-chain drift; it does not prove these tools.

## Runtime boundary assumptions

Handwritten CLJ/CLJS conversion code must:

- reject unknown variants, fields, and result tags;
- preserve exact object/type/relation identities and the adapter's
  fixed-snapshot cursor-relative sequence positions, without presenting that
  internal sequence as a global, lexical, domain, or cross-adapter order;
- reject integers outside the target's exact representable range;
- bound collection size, nesting, and encoded input size;
- turn every malformed adapter callback or generated result into a typed,
  fail-closed error.

These obligations are tested and runtime-guarded, not proved as Clojure facts.

## Backend adapter obligations

For an operation to inherit a kernel theorem, its adapter must establish:

1. every read in the operation observes one immutable selected snapshot;
2. external/internal object conversion is injective and round-trips for every
   visible object;
3. relation and permission definitions are complete for the requested schema;
4. forward and reverse scans are finite, duplicate-free, complete,
   directionally equivalent, strictly ordered within the adapter's internal
   fixed-snapshot index sequence, and honor inclusive/exclusive bounds; this is
   a pagination obligation, not a public global-order guarantee;
5. direct match agrees exactly with membership in the corresponding scan;
6. `all-permission-nodes` is complete;
7. schema and relationship proofs cover the declared dependency scope;
8. causal-anchor membership denotes ancestry, never numeric transaction order;
9. exact selection returns the compatible immutable graph requested or fails;
10. source scope and adapter fingerprint change whenever an
    assumption-affecting implementation identity changes.

Backend certification provides evidence for these assumptions. It does not
verify DataScript, Datomic, Datahike, their storage engines, or host databases.

### Recursive-routing certificate boundary

`RoutingCertificate.dfy` proves that an accepted certificate classifies every
indexed permission node exactly according to reachability of a recursive
strongly connected component. It first proves that the indexed edge sequence
is exactly derived from every supplied materialized-path descriptor: relation
paths emit no edge and permission paths emit one directed edge. The generated
checker makes exactly one path pass, two node passes, and one edge pass on
acceptance. In
verified-authoritative mode, stamped schema generations consume only that
generated traversal vector; the host classification is not the returned
authority.

The theorem is conditional on the path descriptors. Clojure still obtains
materialized permission paths from the selected adapter, maps their portable
fields to typed descriptors, and assigns stable indices. The generated
boundary, rather than Clojure, decides which descriptors emit dependency
edges; production constructs its graph and certificate from that same edge
vector. Exhaustive typed-graph differentials, path-derivation and certificate
mutations, backend certification, and forced-authority suites test the earlier
adapter/map-to-descriptor extraction, but do not prove Clojure bytecode or
backend truthfulness. Proofless/raw snapshots deliberately retain the
uncached host per-root classifier rather than paying schema-wide certification
on every query or publishing derived state across snapshot boundaries.

### Snapshot-consistency observation boundary

`ConsistencyDecision.dfy` proves the finite decision made *after* production
has observed backend and request facts. It does not prove those observations.
The refinement map in `consistency-decision.edn` binds every Dafny input to its
exact expression in `eacl.consistency`:

- mode comes from the validated public consistency descriptor;
- capability support comes from `backend/supports?`;
- managed writer authority comes from the exact
  `:coherence-authority :managed` option;
- selection presence and adapter validity are separate observations made by
  `some?` and `backend/adapter?`;
- source comparability is adapter identity or equality of both validated
  `source-scope` values;
- at-least freshness is `contains-anchor?` for the authenticated graph anchor;
- exact selection is equality between the authenticated graph anchor and the
  selected adapter's validated `graph-head`.

Consequently, the consistency theorem is conditional on the adapter reporting
capabilities and source scopes truthfully, implementing an authoritative
barrier or failing, treating anchor membership as ancestry, and resolving an
exact locator to the requested immutable graph or failing. Token
authentication, backend selection, host exceptions, and those adapter facts
remain outside the pure decision theorem. Exhaustive generated-runtime tests,
production fact-extraction tests, mutation controls, and adapter certification
are executable refinement evidence; they are not a proof of the Clojure
runtime or storage engines.

## Host source specializations

`OrderedMerge.dfy` now mirrors the production identity-EID merge's explicit
last-value state, exhausted-tail behavior, empty-stream filtering, and pairwise
fold schedule and proves that control model equivalent to the canonical merge.
Generated Java and JavaScript execute the model against the actual CLJ/CLJS
source. `AcyclicEngine.dfy` similarly models the leapfrog probe/reseek control
flow, its logical counters, and the exact ordered trace of reseek stream side
and target. Generated Java and JavaScript compare that trace with callbacks
from the actual CLJ/CLJS source. This rules out preserving only the aggregate
reseek count while changing which stream is sought or the requested boundary.
The same module models the empty/singleton/wide arrow selection in
`can-uncached*`, including direct-intersection and full-candidate-check counts.
Generated Java and JavaScript compare eight Boolean/work traces with actual
CLJ/CLJS execution. The first comparison found and removed the empty-arrow
wide-path work recorded as EACL-FORMAL-042.
The source digests and public call closure make any host-source edit invalidate
the reviewed evidence.

This still trusts the documented correspondence between Clojure operations
(`lazy-seq`, `seq`, `first`, `rest`, `next`, numeric comparison, and
`drop-while`) and their Dafny sequence/integer model. No formal semantics or
verified compiler for Clojure or ClojureScript is part of this repository.
Inclusive backend reseek remains a separately certified adapter obligation.
The adapter fixture certification exercises no bound and every materialized
EID as a bound; it is finite executable evidence, not a proof of each storage
engine's implementation.
For arrow selection, the facts that direct matches are a subset of full
far-side authorization and that `:exhaustive?` means equality are now derived
by the source-shaped materialization model from typed path results. Generated
Java and JavaScript compare the exact ranked path maps and direct summary with
CLJ/CLJS on 99 fixtures each; adapter certification v2 composes that source
calculation with actual Datomic, Datahike, and DataScript relation IDs. The
remaining trust is in the CLJ/CLJS language correspondence, adapter behavior
outside certified fixtures, and the truth of nested non-direct callbacks.
The enclosing acyclic union fold has an additional exact source-control model:
407 fixtures per runtime compare authorization, realized path count, per-kind
callback counts, and ordered callback kind/path index. Complete host callback
arguments and the nested results themselves remain trusted inputs to that
model.

## Cryptographic and canonicalization axioms

The formal model assumes:

- authenticated decoding returns only the value encoded with the same key and
  domain;
- canonicalization is deterministic and injective over accepted values;
- equal complete dependency proofs imply equal answer-affecting inputs for
  that declared scope;
- production hashes and authentication tags provide their intended
  collision/forgery resistance;
- secret keys and entropy are generated, stored, and selected correctly;
- expiry time supplied to the kernel is trustworthy.

Production HMAC/hash implementations, constant-time comparison, canonical
encoding, clocks, and entropy remain in the TCB. Secure-format and structural
proof tests are evidence, not mathematical proofs of cryptography.

## Operational limits

Configured maximum input sizes, recursion work, queued work, derived grants,
cursor age, retained snapshots, and continuation/cache capacity are trusted
configuration inputs after range validation. The kernel proves that crossing a
modeled traversal limit fails the entire operation. `CursorCost.dfy` proves the
compact framing model has one payload canonicalization and authentication pass;
production exposes matching deterministic counters and tests that refinement
boundary. Neither proof establishes wall-clock latency or the cost hidden
inside a trusted canonicalization or cryptographic primitive.

Lore's historical resource analyser is not in the TCB and contributes no
correctness or resource theorem. EACL adopts only its useful accounting
discipline: admission weight, represented candidates, registered flights,
actually running computations, waiting callers, backend operations, logical
work, retained heap, and elapsed time are different dimensions and cannot
substitute for one another. Dafny proves bounds only for explicitly modeled
logical counters. Source instrumentation checks the corresponding Clojure
calls for named paths. JVM/JavaScript wall time and allocation are measured by
host-specific regression gates; retained live heap, CPU time, scheduler peaks,
and worst-case latency remain unproved unless separately named.

## Excluded claims

The verification does not establish that a customer's policy expresses their
intent, that an adapter meets its assumptions without certification, that
toolchain/runtime defects are impossible, or that wall-clock performance
targets hold.
The release manifest must list these exclusions and must never label an
unmapped operation “formally verified.”
