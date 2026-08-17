## ADDED Requirements

### Requirement: Formal claims match shipped implementation semantics
Every formal claim presented as production assurance SHALL model the shipped
EACL operation's actual inputs, control decisions, state transitions, stopping
conditions, errors, limits, and outputs, or SHALL be connected to them by a
mechanized refinement. A proof of a similar, obsolete, or simplified algorithm
MUST NOT be presented as certification of production behavior.

#### Scenario: Production branch changes
- **WHEN** a certification-relevant production branch, input, error, limit, or transition is added, removed, or changed
- **THEN** the conformance gate fails until the matching model/refinement and evidence are updated and reviewed

#### Scenario: Proof-only model
- **WHEN** a useful formal model has no generated production consumer or mechanized implementation refinement
- **THEN** its assurance class is explicitly proof-only
- **AND** it cannot satisfy a production-certification obligation

#### Scenario: Completed recursive denotation order
- **WHEN** explicit complete evaluation materializes a recursive denotation
- **THEN** its formal page model and production artifact preserve the same versioned generated logical order used by demand evaluation
- **AND** neither cache validation nor point membership assumes numeric EID sorting
- **AND** completed cursor slicing rejects an ordinal/result-identity mismatch as stale

#### Scenario: Modeled traversal-limit transport
- **WHEN** the formal execution request contains normalized traversal limits
- **THEN** every production request boundary and backend facade forwards the identical limits to the generated authority
- **AND** the conformance ledger maps normalization, binding, cache identity, generated initialization, limit errors, and strict-limit executable evidence
- **AND** a missing or default-rebinding facade edge fails the production/model conformance gate

#### Scenario: Traversal limits are cursor identity
- **WHEN** a recursive cursor or private continuation is minted under normalized limits `L`
- **THEN** its authenticated query scope and private continuation key bind exactly `L`
- **AND** a cursor presented under different limits fails before traversal

#### Scenario: Fuel-cut scan transition matches every authority
- **WHEN** the verified forward or reverse driver consumes its quantum with a nonempty pending scan sequence
- **THEN** generated Java, generated JavaScript, and the portable CLJS refinement publish the same current-state bounded wave in request-id order
- **AND** none returns the quantum input state or discards pending work
- **AND** the source-closure, generated-artifact, direct low-fuel, and public broad-fanout evidence are bound to the same transition claim

#### Scenario: Differential-only target
- **WHEN** a handwritten runtime is tested differentially against a verified oracle without a mechanized source refinement
- **THEN** EACL reports differential conformance rather than claiming the implementation itself is proved

#### Scenario: Recursive and acyclic authority classes stay distinct
- **WHEN** production classifies a permission root for point, lookup, or count execution
- **THEN** recursive roots are mapped to the generated indexed authority on the JVM and the differentially certified portable recursive authority on CLJS
- **AND** demand-mode certified acyclic roots are mapped to generated route/page/count/work decisions plus the exact digest-locked host source specializations they execute
- **AND** the inclusive exact-EID acyclic point probe is not reported as a generated indexed traversal
- **AND** any claim that all permission roots execute the generated indexed state machine fails the conformance gate

#### Scenario: Explicit completion overrides the acyclic shortcut
- **WHEN** production receives `:evaluation :complete-denotation` for a defined
  root certified as acyclic
- **THEN** the execution-contract model and production route selector both
  choose the fixed-point evaluator for point, lookup, and count operations
- **AND** the model and production renderer preserve the certified acyclic
  public EID order even though the selected evaluator changed
- **AND** both model and implementation bind public order and artifact version
  in the completed-denotation cache contract, require strictly increasing
  positive EIDs at acyclic artifact admission, and read only immutable entries
  marked validated by atomic publication
- **AND** the demand-mode acyclic specialization is not allowed to make the
  explicit completion control decorative
- **AND** cross-operation complete-denotation reuse is executable evidence for
  that route selection, not evidence that the demand shortcut materialized a
  denotation

#### Scenario: Cursor proof strategy matches production
- **WHEN** production normalizes content, no-cache-proof, or managed mutation-stamp proof mode for cursor construction
- **THEN** the formal model selects the same exact-snapshot or managed-dependency strategy as the shipped orchestration branch
- **AND** proves that content and no-cache-proof cursor minting issue zero relationship-proof commands
- **AND** proves that current-only DataScript rejects a changed exact basis because no historical selection is available

#### Scenario: Pure permission alias frontier optimization matches production
- **WHEN** production canonicalizes an arrow target whose permission body is exactly one same-resource self-permission
- **THEN** the formal model follows the same cycle-guarded alias chain
- **AND** proves the target permission denotation is unchanged and canonical frontier deduplication cannot add traversal streams
- **AND** models the production left-to-right seen-set fold, proving canonical streams are unique and the first canonical path remains first
- **AND** composite permission bodies are not treated as aliases

### Requirement: Assurance traceability is bidirectional and complete
EACL SHALL assign stable claim identifiers and maintain a machine-validated
bidirectional mapping among changed OpenSpec requirements and scenarios, public
operations, exact production entry points and branches, formal inputs and
preconditions, transition relations and theorems, generated or refinement
artifacts, executable evidence, and release-manifest digests.

#### Scenario: Unmapped formal theorem
- **WHEN** a theorem is cited by a public assurance claim but has no complete production-consumer and precondition mapping
- **THEN** manifest validation rejects the claim

#### Scenario: Unmapped production decision
- **WHEN** source closure finds a certification-relevant production decision with no claim/model/refinement row
- **THEN** the release gate fails closed

#### Scenario: Bidirectional audit
- **WHEN** an external reviewer starts from either a public claim or a production branch
- **THEN** the evidence ledger resolves the complete path to the corresponding theorem, assumptions, artifact, tests, and digests

### Requirement: Production authority or refinement is mechanized
For each certification-relevant runtime, EACL SHALL either execute code generated
from the verified model as the production decision authority or provide a
mechanically checked refinement from the shipped implementation boundary to the
modeled transition relation. Testing MAY supplement this obligation but MUST
NOT substitute for it in an externally certified claim.

#### Scenario: Generated production authority
- **WHEN** production executes a generated verified operation
- **THEN** the manifest binds the theorem, generated source, patched runtime, call boundary, and shipped artifact by digest

#### Scenario: Separately implemented runtime
- **WHEN** CLJ, CLJS, or an adapter uses a separately implemented decision path
- **THEN** external certification remains qualified until its mechanized refinement obligation passes

#### Scenario: Adapter conversion
- **WHEN** host code converts immutable backend data into formal inputs or formal outputs into public values/errors
- **THEN** the conversion contract and all rejected input classes are part of the checked refinement boundary

### Requirement: Drift and negative controls fail closed
The certification gate SHALL compare modeled and production fields, branches,
errors, limits, transition coverage, source closure, theorem preconditions,
trusted adapters, generated artifacts, and public claim text. It SHALL include
negative controls demonstrating that mismatches in each layer are detected.

#### Scenario: Stale generated artifact
- **WHEN** verified source or a target-runtime patch changes without regenerating and reviewing the shipped artifact
- **THEN** digest and conformance validation fail

#### Scenario: Stale theorem count
- **WHEN** a manifest theorem row's recorded obligation count differs from the exact locked Dafny report for its source set
- **THEN** manifest validation fails with the claim, recorded count, actual count, and source set

#### Scenario: Production/model mutation control
- **WHEN** a controlled mutation removes or reverses one production or model decision
- **THEN** at least one required refinement, trace, property, or manifest gate fails for the intended reason

#### Scenario: Claim text overstates evidence
- **WHEN** release documentation labels differential or proof-only evidence as implementation proof
- **THEN** the assurance-claim validator rejects the release manifest

### Requirement: Trusted boundaries and exclusions are explicit
EACL SHALL enumerate every axiom, solver/runtime/compiler assumption, foreign
cryptographic primitive, host adapter, generated-runtime
patch, uninterruptible operation, and unproved performance property on which a
claim depends. Missing or undocumented assumptions MUST close the certification
gate rather than silently widening the claim.

#### Scenario: New trusted adapter
- **WHEN** a production path introduces a new adapter or foreign runtime boundary
- **THEN** it is absent from certified scope until its contract, tests, digest, and review evidence enter the ledger

#### Scenario: Unproved wall-clock property
- **WHEN** a semantic/work theorem is reported
- **THEN** heap, CPU, GC, scheduling, backend latency, and wall-clock behavior remain explicitly outside that theorem unless separately proved

### Requirement: Certification evidence is independently reproducible
EACL SHALL produce a clean-checkout certification bundle that pins all formal,
solver, compiler, JVM, Clojure/CLJS, JavaScript, generated-runtime, and script
versions and records exact commands, source/artifact/evidence hashes, proof
results, negative controls, assumptions, exclusions, and the bundle digest.

#### Scenario: Clean-room rebuild
- **WHEN** an external certifier runs the documented procedure from the bound source commit without developer caches
- **THEN** the same verified sources, generated artifacts, conformance results, and bundle digest are produced

#### Scenario: Toolchain drift
- **WHEN** any pinned tool or runtime differs from the certification bundle
- **THEN** reproduction fails with the exact mismatched component rather than accepting incomparable evidence

#### Scenario: External certification status
- **WHEN** all technical gates pass but independent review evidence is absent
- **THEN** the manifest remains conditionally verified and cannot claim external certification
