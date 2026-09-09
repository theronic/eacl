# Protect qualified control-plane data from safe retraction

## Why

The optional native safe-retraction operation rejects Relations and Permissions
but admits named Caveats and qualifier entities. Deleting a Caveat may remove an
ordinary ref from a combined Caveat+expiration qualifier, leaving a valid
expiry-only grant. Component cascades can perform the same unsupported mutation.
This bypasses the dedicated schema writer's retained-Caveat guard.

The failure is source-confirmed at `6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56`; a native regression is supplied
but has not been executed by the reviewer. See F1 in the accompanying review.

## What Changes

Reject Caveat/qualifier control entities and component closures containing them
at the native safe-retraction boundary. Align the portable guard with Datomic's
embedded body, including partially populated owned records. Preserve atomic
failure, existing application-object behavior, and supported explicit qualifier
cleanup/preparation APIs. Version and replace installed function bodies.

## Capabilities

### New Capabilities
- `safe-retraction-control-plane`: application object deletion cannot mutate
  qualified authorization control data directly or through a component cascade.

### Modified Capabilities

None of the existing specification files is modified by this proposal; this
change adds an explicit cross-backend contract for the affected operation.

## Impact

Primary implementation: `modules/eacl/src/eacl/relationships/safe_retraction.cljc`
and `modules/eacl-datomic/src/eacl/datomic/safe_retraction.clj`. DataScript and
Datahike wrappers and their installed/direct modes require contract tests.
Datalevin support must be inspected before asserting applicability; it was not
reviewed for this operation. No relationship storage ABI or tuple shape change.

Coordinate the installed-function compatibility update with the self-loop fix;
one release must not leave either old persisted function body active.
