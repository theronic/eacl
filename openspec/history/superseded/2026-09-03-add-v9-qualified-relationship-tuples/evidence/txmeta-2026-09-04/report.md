# Datomic temporal relationship filtering: 5% qualified

Measured 2026-09-04 via nREPL on an Apple M4 Max, 36 GiB RAM, Java 26.0.2, Clojure 1.11.4, Datomic Peer/transactor 1.0.7705. Dedicated Peer: 6 GiB max heap. Local :dev transactor: 4 GiB heap. Git HEAD: 031144ceda0925d2cd171c0b72332d609bcc4fcc.

## Fixture and method

One isolated :dev database contained 1,000,000 logical relationships in both four-slot and eight-slot layouts, with forward and reverse datoms for each: 4,000,000 relationship datoms total. Independent index enumeration verified 1,000,000 datoms for each of f4, r4, f8, r8. There was also one allocation marker per resource. No production EACL database or source was modified.

All relationships share a subject and relation. In sorted endpoint eid order, indices congruent to 0 mod 40 are expired; indices congruent to 20 mod 40 are future; all others are permanent. This gives 950,000 permanent, 25,000 expired (until=900), and 25,000 future (from=1100). At valid-at=1000 all 5% are inactive. At valid-at=1200 only the expired 2.5% are inactive. Numeric times are synthetic epoch-millisecond values; the comparison logic is identical for contemporary values. Seed time was 53.219 seconds.

The four-slot layout obtains bounds from the relationship datom's assertion transaction. The eight-slot layout has [identity-fields caveat context from until], with both caveat fields nil. Both layouts carry identical logical bounds.

Resource eids were allocated before relationship writes so temporal grouping by transaction did not cluster temporal relationships in traversal order. Each block of 2,000 relationships used three assertion transactions: 1,900 permanent, 50 expired, 50 future. There are 1,500 assertion transactions total. This strongly favors metadata memoization and must not be mistaken for a one-transaction-per-relationship workload.

These are single-threaded, warm Peer index-read measurements, not complete EACL authorization requests, cold reads, or concurrent service latency. A freshly constructed filtered view and request-local cache allocation are included in each request where applicable. Shared metadata preparation is excluded and reported separately. No authorization answers are cached. Variant order rotates between rounds; requests use deterministic varying index positions.

Each page returns 20 active relationships. Each point check addresses one physical identity. The four-slot point check supplies the full tuple to d/datoms; the eight-slot point check seeks to the identity prefix and checks the returned identity. The point timings therefore compare these access paths, not tuple width in isolation.

Page timing: 3,500 samples per variant after 1,000 warmups. Point timing: 14,000 samples per variant after 2,000 warmups. Full forward scans: seven samples per variant. The unfiltered control returns all one million and intentionally has different semantics. Every filtered full scan returned exactly 950,000 at t=1000, and 975,000 at t=1200. There were 2,016 explicit page/point equality assertions against generated expected results.

## Results

| Read method | Page median µs | Page p95 µs | Point median µs | Point p95 µs | Full scan median ms |
|---|---:|---:|---:|---:|---:|
| Four-slot raw control; no temporal filtering | 8.416 | 12.708 | 4.625 | 6.959 | 95.838 |
| Eight-slot inline validity, manual filter | 8.791 | 14.167 | 7.458 | 10.959 | 102.992 |
| Eight-slot inline validity via d/filter | 10.000 | 15.708 | 8.625 | 12.625 | 129.471 |
| Four-slot + d/filter + entity lookup per candidate | 34.959 | 48.166 | 5.958 | 8.750 | 860.894 |
| Four-slot + d/filter + per-request bounds memo | 11.917 | 18.125 | 5.666 | 8.333 | 136.384 |
| Four-slot + d/filter + shared bounds memo | 9.125 | 14.625 | 4.792 | 7.084 | 133.632 |
| Four-slot + d/filter + sparse qualified-tx bounds map | 9.750 | 14.917 | 4.750 | 7.042 | 130.239 |

The shared memo stored 1,500 decoded transaction-bound pairs, including permanent entries. Populating it with a full relationship scan took 180.397 ms (some entries had already been touched during verification). The sparse variant instead enumerated only the from/until metadata attributes, building 1,000 qualified-tx entries from 1,000 metadata datoms in 2.619 ms in one observed preparation run. Sparse-map absence means permanent only because the map is complete for the selected immutable database basis. Both structures cache bounds, not active/inactive answers.

Separate instrumentation found 21–22 logical candidates through the twentieth result in sampled pages. d/filter actually invoked its predicate 34–35 times, showing read-ahead in these calls. Candidate-to-tx diversity was 2–4 transactions in the instrumented pages. These are measured predicate calls, not disk-read counts. Timing runs did not contain instrumentation counters.

## Rescheduling

Four cases were independently run and asserted on both in-memory and :dev Datomic.

| Attempt | Observed outcome |
|---|---|
| Reassert identical forward/reverse tuples with new tx-meta | New transaction metadata stored; database basis-t 1005→1006; both relationship assertion tx ids stayed 13194139534317; effective expiry remained 100, not 200; relationship tx-data was empty. |
| Retract then reassert identical EAV in one transaction | Rejected with :db.error/datoms-conflict; database unchanged. |
| Assert then retract identical EAV in one transaction | Same conflict; ordering does not rescue it. |
| Retract in one transaction, reassert in the next | New assertion tx and expiry 200 observed, but the intermediate committed database has neither endpoint tuple. |

A further speculative d/with test verified that changing metadata on the original assertion transaction is possible and changes both sampled relationships that share that transaction. The old immutable database retains the old metadata. A separate speculative d/with test successfully replaced both eight-slot tuple values atomically by changing their from component; the old tuple values were absent and both new values present. These two follow-up cases used d/with, not a committed write.

## Cache implications

On one unchanged database basis (1003003) and one unchanged assertion tx (13194140534818), the same forward and reverse identity was inactive at valid-at=1000 and active at valid-at=1200. No transaction is required for time-dependent answers to change. d/filter alone therefore cannot make a cache keyed only by database/relationship versions coherent. Existing EACL relation stamps are changed by writes; see modules/eacl-datomic/src/eacl/datomic/schema.clj:65 and backend.clj:293.

A cache needs valid-time scope: exact valid-at, or a provably stable interval/epoch bounded by relevant transitions. Negative results must stop being reused when a future relationship becomes active. Metadata maps must also be scoped to their source database basis or maintained with correct version tracking. A stale incomplete sparse map would incorrectly treat an unseen qualified transaction as permanent. The measured cache is tied to one frozen basis.

## Interpretation

High confidence: metadata filtering is semantically viable; unchanged-EAV reassertion does not replace the original assertion tx; same-tx retract/reassert is rejected; clock changes need explicit cache scope. Moderate confidence for performance generalization: cached metadata is competitive with inline validity for small warm reads in this distribution, while per-candidate entity lookup is much slower on full scans. The earlier 111 ms first-page result with 999,980 expired predecessors is not representative of this evenly distributed 5% fixture. Local expired-prefix density, not merely global percentage, governs how many candidates a page skips.

No cold-cache, one-transaction-per-relationship, mixed concurrent writes, full authorization graph, or production cache coherence implementation was benchmarked.

## Reproduction and evidence

Scripts: benchmark.clj, rescheduling.clj, followup.clj, report.clj. Raw observations and summaries are adjacent EDN files. Load the benchmark script in a dedicated nREPL using the core classpath, call seed! with 1000000, build-sparse!, verify!, warm-shared!, run-latencies! for page (7 500 1000) and point (7 2000 2000), and run-full-counts! (7 1000) and (1 1200). Run rescheduling/run-all! for mem and dev prefixes; it deletes its own tiny databases. Cleanup the benchmark database using its saved URI.

Official reference: https://docs.datomic.com/reference/filters.html (filtering transaction attributes) and https://docs.datomic.com/transactions/transaction-data-reference.html (redundant assertions).
