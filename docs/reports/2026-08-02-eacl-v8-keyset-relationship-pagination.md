# EACL v8 physical-keyset relationship pagination

Date: 2026-08-02

## Decision

DataScript and Datahike relationship reads no longer materialize every match,
externalize every endpoint, sort by public identity, and then apply a Relay
offset. They page directly over the immutable backend tuple indexes.

The cursor carries an authenticated physical edge:

```clojure
{:kind :relationship-index
 :v 1
 :scan-index relation-spec-index
 :subject-id internal-subject-id
 :resource-id internal-resource-id}
```

The engine seeks strictly beyond that edge, reads at most `page-size + 1`
matching internal rows, externalizes only the selected page, and uses the extra
row only to decide page flags. It retains exact-snapshot continuation semantics
and never hashes the full result set.

EACL does not promise global, lexical, domain, or cross-backend relationship
order. For one fixed adapter, normalized query, and cursor-pinned immutable
snapshot, it promises one deterministic tuple-index sequence. Repeating a page
does not move results, and a complete valid cursor walk contains no omissions
or duplicates.

## Complexity

Let:

- `M` be the number of matching relationships;
- `K` be the requested page size;
- `P` be the number of pages in a complete walk; and
- `R` be the number of matching relation definitions.

The removed wrapper performed `O(M)` materialization and `O(M log M)` public-ID
sorting for every page. A complete walk was therefore
`O(P × M log M)`, plus `O(P × M)` endpoint externalizations.

The new path performs an index seek and consumes at most `K + 1` rows strictly
beyond the cursor per page; an inclusive backend seek may additionally inspect
and discard the boundary row. Across a complete walk it emits each matching
index row once, crosses each relation stream in deterministic schema order, and
externalizes `O(M)` result endpoints. Its graph work is
`O(M + R + P × seek)`. Authenticated token decode/encode remains a fixed
per-page cost independent of `M`.

Historical Datahike wrappers may materialize their exact visible prefix because
Datahike 0.8.1759 cannot reliably position every temporal tuple seek after a
later retraction. Current Datahike DB values use native `seek-datoms` and
`rseek-datoms`; the fallback is outside the ordinary current-snapshot hot path.

## Chromium Explorer measurement

The supplied Explorer harness at
`/tmp/eacl-explorer-upgrade.Jd7WEW/docs/eacl-v8-query-benchmark.md` was rerun
against the working tree after a zero-warning browser rebuild.

Environment and workload match the supplied report:

- 5 accounts, 20 teams, 10 VPCs, 10,000 servers, and 38 users;
- Chromium `performance.now`;
- 20 warm-up calls and 30 measured samples;
- 10 invocations per relationship-page sample;
- all semantic result checks enabled.

Times are p50 microseconds per invocation.

| Workload | Pre-PR #95 | PR #95 `bb69a6b` | Physical keyset | Change from `bb69a6b` |
| --- | ---: | ---: | ---: | ---: |
| Read account-to-server page, 20 of 2,000 | 88,840 | 17,640 | 2,910 | 6.06× faster |
| Read known-user relationship page, 20 | 3,180 | 2,680 | 2,770 | 3.4% slower |

The broad page is 83.5% lower latency than the prior PR revision and 30.5×
faster than pre-PR #95. The small broad-type page is effectively governed by
fixed cursor/security overhead; its 90 µs difference is not evidence of an
algorithmic regression.

With the explicit `cache/no-cache + :proof-mode :none` client, the measured p50
values were:

| Workload | Prior PR #95 no-cache | Physical keyset no-cache |
| --- | ---: | ---: |
| Direct authorization allow | 184 | 194 |
| Recursive authorization allow | 264 | 262 |
| Recursive authorization deny | 632 | 546 |
| Lookup visible server page, 20 | 6,000 | 5,260 |
| Account-to-server relationship page, 20 | 17,540 | 2,750 |
| Known-user relationship page, 20 | 2,600 | 2,580 |

The no-cache broad relationship page is 6.38× faster. The lookup page is 12.3%
faster than the preceding PR measurement. Cache-disabled calls still branch to
evaluation before completed-cache key, dependency-stamp, provider,
canonicalization, or cache-envelope work.

A smaller five-sample check of the intentionally expensive uncached
4,000-result count measured 26.664 ms p50 versus 25.696 ms in the supplied
report. That sample is too small to claim a change, and the keyset work does not
alter the count algorithm.

## Complete-walk and cost isolation

The browser walked all 2,000 account-to-server relationships with `:first 20`:

- 100 pages;
- 2,000 results;
- 2,000 distinct results;
- repeated first-page data exactly equal;
- 495.9 ms total wall time.

The same runtime measured the internal bounded DataScript relationship page at
approximately 138 µs and the complete public call at approximately 3,087 µs.
Isolating the public boundary gave approximately:

- 200 µs to externalize the 20 relationship endpoint pairs;
- 2,399 µs to create the authenticated start and end cursor tokens.

These component measurements are diagnostic, not stable latency promises. They
show that relationship graph work is no longer the bottleneck. Further
optimization must preserve two independently usable authenticated Relay
boundaries; weakening cursor authentication or returning one context-dependent
token for both boundaries would be the wrong trade.

## Correctness and verification evidence

- The shared pure engine exhaustively walks forward and backward over multiple
  relation streams for page sizes 1, 2, 3, the exact result size, and a larger
  size; every walk equals the same sequence and has no duplicates. A physical
  edge minted in either direction is also proved by test to remain an exclusive
  bound when traversal reverses.
- The lookahead test observes exactly `page-size + 1` lazy row realizations.
- Physical edge maps reject missing, extra, negative, and out-of-range fields.
- Datahike repeats the complete forward/backward public walk for keyword and
  numeric attribute representations, including equal tuple tails.
- DataScript verifies that a 20-item public page invokes the internal reader
  once and performs exactly 44 public-ID conversions: 40 result endpoints plus
  two two-endpoint cursor edges.
- Existing DataScript and Datahike tests prove that a continuation after a
  relevant write either uses the retained authenticated original snapshot or
  fails with the typed consistency/retention error.
- `PageWindow.dfy` now proves the keyset page decision's bounded take count,
  reverse-window normalization, and exact next/previous flag laws. The complete
  Dafny suite passes 255 obligations across 12 files.

The release manifest remains `:not-verified` for the complete public
authorization engine. Dafny proves the page decision and generic sequence laws;
adapter certification and executable differential tests cover the physical
scan callbacks. A full composed formal-verification claim still requires a
mechanically linked indexed callback boundary and independent review.
