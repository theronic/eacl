# Caveat and qualifier foundation assurance

The Phase 2 model precedes production implementation. Run the bounded gate from
the repository root with a project `:dev` nREPL:

```sh
EACL_NREPL_PORT=7788 bin/formal fast
```

The gate verifies four independent Dafny modules (76 obligations), executes
10,461 assertions through nREPL, and requires all ten registered mutation
controls to be exercised and killed. `gate.lock.json` locks the profile and
mutation inventory hashes, proof count, assertion count, and per-proof resource
limits. Reports are generated under ignored `target/formal/caveats/`. The
regular `bin/formal verify` also discovers these four modules. `bin/formal fast`
names this foundation gate; the existing stable-discovery engine gate remains
`formal/stable-discovery/verify-fast.sh`.

| Obligation | Formal definition/proof | Finite evidence | Remaining production obligation |
| --- | --- | --- | --- |
| Nil or one owner; identity excludes qualifier | `QualifierLifecycle.Healthy`, `SharedIsFault` | Exhaustive four-step, two-owner/two-qid state space; seeded 2,000-step campaign | Native tuple/ref conversion and writer conformance |
| Inert preparation; atomic pair/stamp/application publication | `PreparationIsInert`, `PublicationIsAtomic` | Preparation, partial publication, and stamp mutants | Real transaction APIs on four backends and CLJS |
| Immutable fresh replacement and exact deletion | `ReplacementIsFreshAndAtomic`, `DeleteFollowsIdentity` | Before/after value preservation and immutable mutation control | Native entity allocation, physical deletion, history evidence |
| Non-nil missing target is a fault | `NonNilMissingIsFault` | Dangling target normalization mutant | Native dangling-ref representation |
| Named typed definitions and Relation allowance | `CaveatSchema.Valid`, `Allowed`, replacement lemmas | Duplicate/unresolved/type/CAS/retained-reference fixtures | Parser/schema persistence and retained bound-context revalidation |
| Bound values override request values | `CaveatOutcomes.Merge`, `BoundWins` | Exhaustive absent/true/false contexts and precedence mutant | Canonical byte encoding and input admission |
| True/false/conditional/error; commutative absorbers | `And`, `Or`, `Not`, outcome lemmas | Full four-by-four truth tables and independent SpiceDB/CEL fixtures | Canonical residual refinement and native returned-error detection |
| Selected scalar/container operations | `CaveatProfile.Eval`, typed/index/equality lemmas | Candidate 2,849-assertion native corpus; model type matrix | Portable parser/plan and JVM value adapter refinement |
| Total evaluation and bounded progress | Structural `Plan`, `Nodes`, `Bounded`; saturating cost lemmas | Size/type/time/Unicode/substring hostile fixtures | Bounded lexical parsing, exact UTF-8 sizes, cache/build admission |

The model uses finite maps and structural recursion. Exhaustive state checking
is explicitly bounded; the Dafny lifecycle preservation lemmas quantify over
arbitrary finite maps. Scalar text in Dafny is an abstract sequence of Unicode
scalar values. Native UTF-8/UTF-16 conversion and byte measurement remain
adapter obligations. The scalar model defines behavior only for the admitted
profile, with static typing as the admission boundary. It does not verify the
ANTLR parser, JVM, database engines, or an arbitrary CEL extension.

The logical outcome proof abstracts already-evaluated scalar leaves. It proves
four-valued composition and structural progress, while the separate profile
module defines the scalar operations. The finite oracle connects typed plans,
context merge, residuals, and the corpus. The production refinement bridge must
complete that connection before this phase is marked ready for Phase 3. No
proof module or oracle may be required by production code.

Prepared and deleted IDs remain in the model's allocation history, preventing
an old qid from being reused. A current snapshot detects structural corruption;
proving mutation-in-place requires prior-state/native history evidence. No
single-snapshot test is represented as proof of unavailable history.

Qualifiers and Caveat definitions remain inert for serving in Phase 2. The
release assurance status remains conditional, including the repository's
existing independent-review and host/backend refinement obligations. Passing
this gate is permission to implement the staged foundation, not a claim that
qualified authorization has shipped.

## Pre-implementation checkpoint

The foundation gate passed against the production source tree from merged
v8 PR #173 (`4d496a21fa863a6f02a734d4f4b410defa10d2c2`), with no changes under
`modules/`. The complete Dafny run passed 53 modules / 9,461 proof efforts.
The assurance manifest passed its theorem and source-coverage checks and
retained the pre-existing conditional status (exit 3). Source closure passed
96 public roots / 2,466 definitions with no forbidden dependencies. The fast
gate passed the locked 76 obligations and 8,199 assertions; boundary negative
controls rejected profile drift, a proof escape hatch, and an unregistered
model. Strict OpenSpec validation passed. This checkpoint precedes every
Phase 2 production source edit in the branch history.
