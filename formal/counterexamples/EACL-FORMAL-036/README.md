# EACL-FORMAL-036 — verified authority bypassed recursive cache reuse

Generated authority produced the same authorization answers as the legacy
engine, but the cutover happened above the production cache integration. A
completed generated traversal was reduced to its public page and its opaque
Dafny state was discarded. Later pages replayed the prefix, while point and
count operations did not share complete fixed-point denotations. Generated
dimensional counters also never populated compatibility telemetry.

Dafny now owns the continuation transition. It checks the authenticated public
ordinal and EID against the completed page, retains the one-item lookahead, and
preserves the queue, seen sets, consumer registrations, request sequence, and
resource counters. Clojure stores that opaque state only in the client-private
continuation cache. A missing, evicted, malformed, or rejected continuation is
an optimization loss and deterministically replays against the pinned
snapshot.

The host validates the complete counter envelope and retained logical-unit
measure before restoration. Because opaque generated state cannot be
structurally inspected without duplicating the generated runtime's datatype
contract, an incompatible object (for example after a development-time
generated-class reload) is evicted and replayed if the continuation restore
call throws. Failures after restoration succeeds still fail closed.

The first forced-authority run exposed 13 failures and 3 errors. After the fix,
the nonbenchmark suite passed across Datomic, Datahike, and DataScript, and
Datomic exercised the generated continuation boundary 147 times. The
constant-time continuation weight is an admission estimate; direct JVM and
Node resource gates, not Lore or Dafny, remain responsible for bounding actual
heap and latency.
