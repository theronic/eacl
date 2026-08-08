## ADDED Requirements

### Requirement: One monotonic deadline covers the request
EACL SHALL convert the effective execution timeout into one absolute monotonic
deadline at the outer public boundary. Consistency selection, cache/provider
access, schema and plan resolution, proof work, traversal, backend commands,
rendering, externalization, and optional publication MUST consume that same
deadline rather than starting independent relative timeouts.

#### Scenario: Per-request timeout
- **WHEN** a caller supplies a positive `:timeout-ms`
- **THEN** it overrides the documented finite client execution timeout for that request
- **AND** every execution layer receives only the remaining budget

#### Scenario: Positional operation
- **WHEN** a positional public operation has no request map containing `:timeout-ms`
- **THEN** it uses the client's finite execution timeout and demand evaluation

#### Scenario: Invalid timeout
- **WHEN** a timeout is zero, negative, non-integral, or outside the documented supported range
- **THEN** EACL rejects it before snapshot selection or cache access

### Requirement: Deadline checks bound newly started work
After the deadline expires, EACL MUST NOT begin another generated execution
quantum, backend command, consistency refresh, proof calculation, rendering
stage, or cache publication. EACL SHALL check the deadline before and after each
bounded backend command and generated quantum.

#### Scenario: Deadline before backend command
- **WHEN** the deadline is expired immediately before a generated adapter command
- **THEN** EACL does not invoke the adapter command
- **AND** returns the typed deadline error

#### Scenario: Deadline during backend command
- **WHEN** the deadline expires while one bounded backend command is already running
- **THEN** EACL requests cancellation when the adapter supports it
- **AND** begins no subsequent command after the running command returns or aborts

#### Scenario: Deadline after semantic result
- **WHEN** the semantic result is ready but the deadline expires before optional publication
- **THEN** EACL skips publication
- **AND** returns the result only if required validation and externalization completed before expiry

### Requirement: Deadline overrun is stated honestly
EACL SHALL document that the maximum implementation-controlled overrun is one
already-running bounded backend command plus runtime scheduling delay. EACL
MUST NOT claim hard cancellation of GC, OS scheduling, foreign code, network
providers, or backend operations whose adapters do not support interruption.

#### Scenario: Uninterruptible adapter
- **WHEN** an adapter command ignores interruption and returns after the deadline
- **THEN** EACL reports deadline exceeded and starts no new work
- **AND** telemetry identifies the in-flight command stage without claiming it was cancelled

### Requirement: Deadline failure is never an authorization value
Deadline expiry SHALL throw `:eacl.execution/deadline-exceeded`. The error SHALL
contain safe operation, stage, configured timeout, and bounded work diagnostics.
It MUST NOT be converted to false, denial, an exact count, `:truncated?`, an
empty page, a cursor restart, or a successful partial response.

#### Scenario: Point timeout
- **WHEN** point evaluation expires before proof or target-local exhaustion
- **THEN** EACL throws `:eacl.execution/deadline-exceeded`
- **AND** does not return false

#### Scenario: Count timeout
- **WHEN** exact or bounded count evaluation expires before its valid stopping condition
- **THEN** EACL throws the deadline error
- **AND** does not return the accumulated count

#### Scenario: Page timeout
- **WHEN** page evaluation or required cursor replay expires before the page result is certified
- **THEN** EACL throws the deadline error
- **AND** does not mint a cursor for partial state

### Requirement: Timeout state is not cacheable
EACL MUST NOT cache semantic deadline errors, incomplete top-level results, or
continuations that cannot prove their complete scope. An exact command response
that completed and was validated before expiry MAY be retained only when its
publication begins before the deadline and cannot block the request.

#### Scenario: Complete-denotation timeout
- **WHEN** explicit complete-denotation evaluation expires after discovering some grants but before closure
- **THEN** EACL publishes neither a completed denotation nor a Boolean derived from the incomplete forward set

#### Scenario: Valid command before timeout
- **WHEN** an exact generated command response is validated before the deadline but later traversal expires
- **THEN** retaining that exact response is permitted only under its exact command/snapshot key
- **AND** it cannot be interpreted as completion of the timed-out request

### Requirement: Deadline behavior is deterministic at modeled boundaries
The generated evaluator and host adapters SHALL expose deadline checks at
modeled quantum and command boundaries so tests can use a fake monotonic clock
and reproduce the same stop boundary across CLJ and CLJS.

#### Scenario: Cross-runtime deadline vector
- **WHEN** CLJ and CLJS execute the same command trace and fake-clock schedule
- **THEN** they stop before the same next command and return equivalent typed diagnostics
