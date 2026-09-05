# Compact edge allocation decision

An ordinary edge remains its native integer eid. A qualified edge is
`[opposite-eid qualifier-eid]`. Scans retain the original ordering and bounds
by opposite eid. No qualifier record is allocated for ordinary edges.

Measured on Java 25.0.3, macOS, using the current thread's allocated-byte
counter, 20,000 warmups and eleven batches of 10,000 materialized 100-edge
chunks. The baseline and production `eacl.relationships.edge/from-datom`
were run in the same nREPL. All samples within each row agreed.

| Qualified edges | Eid-only baseline | Production sparse pairs | Always pairs/maps |
| --- | ---: | ---: | ---: |
| 0% | 1,008 bytes/chunk | 1,008 bytes/chunk | 7,408 bytes/chunk |
| 5% | 1,008 bytes/chunk | 1,328 bytes/chunk | 7,408 bytes/chunk |
| 10% | 1,008 bytes/chunk | 1,648 bytes/chunk | 7,408 bytes/chunk |

The sparse representation adds 64 bytes per qualified edge and zero bytes
per ordinary edge on this JVM. This is a representation allocation result,
not an end-to-end latency claim. Native scan wrappers, request capture,
evidence composition, caches, and concentrated expired prefixes remain in
the Phase 3 authorization performance gate.

Reproduce the alternatives with `representation_probe.clj` through nREPL;
pass `eacl.relationships.edge/from-datom` to `measure` for the production
implementation. Native conformance separately checks forward/reverse/direct
results and every ascending/descending inclusive/exclusive bound on all four
backends, including both Datahike attribute modes.
