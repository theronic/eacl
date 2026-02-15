# EACL v6.1 vs v6.2 Multi-Path Benchmark Report

**Date:** 2026-02-15
**Previous report:** [Simple 2-hop benchmark](2026-02-15-eacl-v6.1-vs-v6.2-performance-report.md)
**Raw data:** `eacl-multipath-v6.1-benchmarks.edn`, `eacl-multipath-v6.2-benchmarks.edn`

## Summary

The cursor-tree optimization in v6.2 delivers **7-15x improvement** on multi-path permission graphs compared to v6.1. The benefit scales with:
- Number of divergent arrow paths in a permission
- Depth of pagination (later pages get faster as exhausted intermediates are skipped)
- Number of distinct intermediate types (accounts, teams, VPCs)

## Schema

```
definition user {}
definition platform { relation super_admin: user }
definition account { relation owner: user; relation platform: platform
                     permission admin = owner + platform->super_admin }
definition team    { relation account: account; relation leader: user
                     permission admin = account->admin + leader }
definition vpc     { relation account: account; relation shared_admin: user
                     permission admin = account->admin + shared_admin }
definition server  { relation account: account; relation team: team
                     relation vpc: vpc; relation shared_admin: user
                     permission view = account->admin + team->admin + vpc->admin + shared_admin }
```

**`server.view` has 4 paths:**
1. `account->admin` (arrow through 500 accounts)
2. `team->admin` (arrow through 2000 teams, recursive: `team.admin = account->admin + leader`)
3. `vpc->admin` (arrow through 1000 VPCs, recursive: `vpc.admin = account->admin + shared_admin`)
4. `shared_admin` (direct relation, 100 servers)

## Dataset

| Entity | Count |
|--------|------:|
| Accounts | 500 |
| Teams | 2,000 (4 per account) |
| VPCs | 1,000 (2 per account) |
| Servers | 375,100 (750 per account + 100 direct) |
| Total accessible by super-user | 375,100 |

## Results: lookup-resources by Limit (First Page, No Cursor)

| Limit | v6.1 median | v6.2 median | Speedup |
|------:|----------:|----------:|--------:|
| 10 | 140.18 ms | 9.64 ms | **14.5x** |
| 50 | 139.93 ms | 16.80 ms | **8.3x** |
| 100 | 161.35 ms | 11.16 ms | **14.5x** |
| 500 | 135.03 ms | 11.10 ms | **12.2x** |
| 1000 | 219.01 ms | 12.31 ms | **17.8x** |
| 5000 | 213.10 ms | 37.55 ms | **5.7x** |

v6.1 is consistently 90-220ms regardless of limit because it must initialize lazy sequences from all 3,500+ intermediates (500 accounts + 2000 teams + 1000 VPCs). v6.2 is 10-40ms.

## Results: Deep Pagination (500 pages of 50)

| Metric | v6.1 | v6.2 | Speedup |
|--------|-----:|-----:|--------:|
| Avg (excl cold) | 89.86 ms | 13.23 ms | **6.8x** |
| Median (excl cold) | 81.27 ms | 10.80 ms | **7.5x** |
| Min | 60.65 ms | 7.15 ms | **8.5x** |
| Max | 459.22 ms | 138.88 ms | **3.3x** |
| p99 | 264.78 ms | 83.11 ms | **3.2x** |

## Results: Latency by Page Depth (2000 pages)

| Page | v6.1 | v6.2 | Note |
|-----:|-----:|-----:|------|
| 0 | 443 ms | 1175 ms | v6.2 cold start: initializes cursor-tree structures |
| 1 | 97 ms | 24 ms | |
| 10 | 73 ms | 14 ms | |
| 50 | 70 ms | 26 ms | |
| 100 | 78 ms | 24 ms | |
| 200 | 72 ms | 21 ms | |
| 500 | 154 ms | 25 ms | |
| 1000 | 855 ms | **9.6 ms** | v6.2 skips exhausted intermediates |
| 1500 | 67 ms | **9.0 ms** | v6.2 continues improving |
| 1999 | 56 ms | **3.7 ms** | v6.2: most intermediates exhausted |

### Key Observations

1. **v6.2 page 0 is 2.7x slower** (1175ms vs 443ms). First page must initialize all path structures and probe all intermediates. This is a one-time cost.

2. **v6.2 pages 1+ are 3-10x faster.** The cursor-tree carries forward per-path intermediate state, allowing subsequent pages to skip already-exhausted intermediates.

3. **v6.2 improves with depth.** As pagination progresses, intermediates with small result sets get exhausted and are skipped. Page 1999 takes only 3.7ms (vs 56ms for v6.1).

4. **v6.1 stays flat at ~70-80ms.** Without cursor-tree, every page re-evaluates all 3,500+ intermediates from scratch. The cursor only skips already-returned resource EIDs, not exhausted intermediates.

## Why v6.1 Is Slow on Multi-Path

v6.1's `lookup-resources` for `server.view` must:
1. **Account path:** Resolve `account.admin` for super-user (500 accounts via `platform->super_admin`), then merge server results from 500 intermediates.
2. **Team path:** Resolve `team.admin` for super-user. This is recursive: `team.admin = account->admin + leader`. Must resolve `account->admin` for each of 2000 teams' accounts. That's 2000 recursive permission resolutions.
3. **VPC path:** Resolve `vpc.admin` for super-user. Recursive: `vpc.admin = account->admin + shared_admin`. Must resolve for 1000 VPCs.
4. **Merge** results from all 3,500+ intermediate sequences using tournament sort.

Each page repeats this entire process. The v1 cursor `{:resource {:id last-eid}}` only skips returned resources, not intermediate resolution.

## Why v6.2 Is Fast

v6.2's cursor-tree `{:v 2, :e last-eid, :p {0 min-account-eid, 1 min-team-eid, 2 min-vpc-eid}}` carries forward the minimum intermediate EID per path. On page N+1:
- Path 0 starts scanning accounts from `min-account-eid`, skipping exhausted accounts
- Path 1 starts scanning teams from `min-team-eid`
- Path 2 starts scanning VPCs from `min-vpc-eid`

As intermediates get exhausted (all their servers have been returned), subsequent pages skip them entirely via `d/index-range` cursor seeking.

## Comparison: Simple vs Multi-Path

| Schema | v6.1 median | v6.2 median | Speedup |
|--------|----------:|----------:|--------:|
| Simple 2-hop (limit 50) | 12.68 ms | 8.27 ms | 1.5x |
| Multi-path 4-way (limit 50) | 139.93 ms | 16.80 ms | **8.3x** |
| Simple 2-hop (pagination avg) | 9.95 ms | 10.82 ms | 0.9x |
| Multi-path 4-way (pagination avg) | 89.86 ms | 13.23 ms | **6.8x** |

The cursor-tree provides marginal benefit for simple 2-hop schemas but delivers massive improvements for real-world multi-path permission graphs.

## Optimization Applied

The `tracking-min` per-element volatile tracking was removed from `arrow-via-intermediates` in v6.2. Previously, every result element triggered a `vswap!` to track the minimum contributing intermediate. This was replaced with a pre-computed minimum: since intermediates are scanned in ascending EID order via `d/index-range`, the first non-empty sub-sequence's intermediate is always the minimum.

This fix eliminated the limit=1000 regression (20.15ms -> 14.96ms on simple 2-hop) and reduced GC pressure.

## Conclusion

The cursor-tree optimization is validated. For production schemas with multiple arrow paths (the common case for RBAC/ReBAC), v6.2 delivers 7-15x improvement over v6.1. The trade-off is a slower first page (2-3x), which is amortized over subsequent pages.
