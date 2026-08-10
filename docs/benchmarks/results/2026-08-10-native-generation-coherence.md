# Native-generation coherence benchmark — 2026-08-10

This report records structural write amplification and warmed relation-proof
cost for `simplify-cache-coherence`. It is evidence for the selected protocol,
not an absolute latency gate.

## Environment and method

- EACL checkout: `6d68215` plus the OpenSpec implementation worktree
- Java: OpenJDK 26.0.2, 64-Bit Server VM
- Host: Apple Mac Studio, M4 Max, 14 cores, 36 GB RAM
- OS: macOS 26.4.1, arm64
- Backends: Datomic Peer 1.0.7622, Datahike 0.8.1759, DataScript 1.7.8
- Test: `eacl.bench.managed-proof-cost-test/cross-backend-write-amplification-and-proof-cost-test`
- Invocation: through the persistent repository nREPL with the namespace
  required using `:reload`

For each backend, the test records committed datom events for one relationship
create and delete, then times 31 warmed samples of 500 reads of one relation's
managed proof. It repeats the proof measurement after adding 1,024 unrelated
relationships. The ratio is the large-database p50 divided by the small p50.

## Results

| Backend | Create datom events | Delete datom events | Small proof p50 (ns/call) | +1,024 unrelated p50 (ns/call) | Ratio |
| --- | ---: | ---: | ---: | ---: | ---: |
| Datomic | 6 | 4 | 1,018.584 | 1,053.250 | 1.034 |
| Datahike | 6 | 4 | 1,333.250 | 1,129.666 | 0.847 |
| DataScript | 6 | 4 | 407.668 | 392.834 | 0.964 |

The committed transaction shapes contain the relationship payload, deduplicated
endpoint identity guards, one old-equals-old schema-write-fence predicate, and
one native generation per affected relation. The predicate prevents a stale
planned relationship transaction from committing after relation removal. On
Datahike/DataScript it targets a dedicated fence because predicate reassertion
must not rotate the physical cache generation. The shapes contain no mutation
record, anchor, retention datom, or database-global graph-head CAS.

The unrelated-data ratios remained within 1.04 in this run. That does not prove
latency improves on every host; it supports the structural claim that proof
work is indexed by the dependency relation rather than proportional to the
number of unrelated permissioned entities. CI gates the operation shape and a
generous ratio bound, not these nanosecond values.

The companion start-barrier test committed two simultaneous writes to distinct
relations. Datomic, Datahike, and DataScript each made exactly two transaction
attempts for two logical writes and emitted zero graph/journal operations. The
measured elapsed times were 2.222 ms, 5.931 ms, and 1.966 ms respectively.
This isolates the absence of database-global head retries. A second simultaneous
pair targeting the same relation recorded 3 Datomic transaction attempts, 2
Datahike attempts, and 2 DataScript attempts for 2 logical writes: the Datomic
relation-local CAS can retry under actual same-relation contention, while
unrelated relations do not share that point. Their elapsed samples were 2.008
ms, 1.394 ms, and 0.588 ms. These are same-process samples, not production
throughput claims.

The read-side break-even remains workload-dependent:

```text
p(managed hit) × authorization evaluation cost
  > dependency-proof cost + cache lookup/promotion overhead
```

Exact-current hits run before proof acquisition and pay no generation reads.
The first exact miss at a new basis may acquire one `O(|dependencies|)` native
proof; a managed hit is promoted into that exact generation, so subsequent
identical reads avoid the proof.
