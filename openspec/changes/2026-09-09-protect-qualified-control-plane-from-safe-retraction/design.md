## Context

The shared guard projects a small list of Relation/Permission/schema fields.
Datomic independently embeds the same incomplete role check in a transaction
function. Named Caveats use `:eacl.caveat/*`; qualifiers use
`:eacl.relationship-qualifier/*`. Neither is currently identified as protected.

Sources: [modules/eacl-datomic/src/eacl/datomic/safe_retraction.clj — function-definition](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl-datomic/src/eacl/datomic/safe_retraction.clj); [modules/eacl/src/eacl/relationships/safe_retraction.cljc — protected-control-entity?, ensure-unprotected!, plan-local-halves](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl/src/eacl/relationships/safe_retraction.cljc); [modules/eacl/src/eacl/caveats/schema.cljc — datom-schema](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl/src/eacl/caveats/schema.cljc); [modules/eacl/src/eacl/caveats/definition.cljc — validate-replacements!](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl/src/eacl/caveats/definition.cljc); [modules/eacl/src/eacl/relationships/qualifier.cljc — decode, normalize, relation-allowance](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl/src/eacl/relationships/qualifier.cljc).

## Goals / Non-Goals

Prevent unsupported object deletion from changing any qualified control record.
Reject the entire mutation before peer retractions or native component deletion.
Keep ordinary object deletion and explicit admitted qualifier cleanup working.

Do not change valid expiry-only semantics, introduce a storage migration, add
read-time repair, or broaden this change into generic protection against arbitrary
raw database transactions. Do not claim the helper authorizes its caller.

## Decisions

### 1. Classify owned roles by present facts

The protected set includes all currently declared Caveat and qualifier facts:

```clojure
#{:eacl.caveat/name
   :eacl.caveat/parameters-payload
   :eacl.caveat/expression-source
   :eacl.caveat/profile-version
   :eacl.relationship-qualifier/format-version
   :eacl.relationship-qualifier/caveat
   :eacl.relationship-qualifier/caveat-context
   :eacl.relationship-qualifier/valid-until-ms}
```

A non-nil value for any owned fact identifies a protected data record. Using
only Caveat name or qualifier format would omit partially populated records.
Native entity wrappers need not implement `contains?`; use the adapter's known
lookup semantics or a normalized owned-role flag. Extend the projection passed
to `protected-control-entity?`, not just that predicate's destructuring.

Preserve the existing Relation, Permission, schema-string, and function checks.
Recognize owned qualification schema idents where the backend represents them as
entities. Keep the role list synchronized with actual storage declarations.

### 2. Check the entire native deletion closure atomically

Resolve the target and its cycle-safe component closure against the transaction's
selected native database. Reject with `:eacl.safe-retraction/invalid` and
`:reason :protected-control-entity` if any member is protected. No partial peer
cleanup, qualifier mutation, generation stamp, or entity deletion may commit.
An ordinary parent whose component points to a control entity must also fail.

### 3. Align native and portable implementations

Prefer a small portable role specification or quoted predicate form that can be
embedded in Datomic without adding an EACL transactor classpath dependency. A
shared Clojure helper alone does not fix the already-embedded Datomic body.
Exercise the actual installed/native function, not just a helper mock.

### 4. Preserve decoder and schema-writer semantics

Expiry-only qualifiers remain valid. The remedy is to prohibit the destructive
transition, not to make nil Caveats always fail. Dedicated schema replacement
continues rejecting removal of referenced definitions. Explicit admitted orphan
cleanup may still retract an unattached qualifier; object safe-retraction is not
that cleanup API.

### 5. Upgrade installed functions explicitly

Change the installed-function compatibility identity using the repository's
existing version/marker mechanism and update its digest representation according
to the project's established contract. Verify that `install!` replaces a
recognized old body and rejects an unrelated occupant. Coordinate one update
with the self-loop change. Do not pin arbitrary repository source hashes as a
substitute for behavioral tests. Keep storage version 8 unchanged.

## Counterexample and acceptance

Use two named Caveat alternatives plus a plain alternative, one expiring
Relationship, no bound context, and a false request-context flag. Both named
alternatives must exist before the test. At time 99 with expiry 100, deleting the
used Caveat is rejected and a fresh uncached check remains `:no-permission`.
With the old guard it can become `:has-permission`; that native outcome remains
to be executed. Also test qualifier targets and component cascades.

The supplied native test file follows the current public writer and test fixture
APIs. Repeat it on supported DataScript CLJ/CLJS and Datahike named/direct modes.
Inspect any additional backend mode before claiming parity.

## Risks / Trade-offs

Classifying only the normal marker is too weak; classifying every arbitrary
application namespace is unnecessarily broad. Derive a narrow owned-role set.
Rejecting a formerly admitted Caveat deletion is an intentional correctness
restriction. Existing installed Datomic code remains unsafe until replaced.

## Verification and formal refinement

Extend the native mutation contract so accepted object deletion preserves all
Caveat definitions and all qualifiers not legitimately removed by the operation's
specified ownership cleanup. Model ordinary incoming-ref retraction explicitly.
Retain the two-Caveat, plain-allowed, future-expiry, empty-bound-context witness.
A mutation control that removes the new role guard must fail a **native** test.
Existing Boolean evidence theorems are not evidence that the writer preserves
those definitions.
