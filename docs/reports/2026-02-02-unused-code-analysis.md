# EACL Unused Code Analysis Report

**Date:** 2026-02-02
**Project:** EACL (Enterprise Access Control)

## Executive Summary

This report identifies unused namespaces and functions in the EACL codebase that are candidates for removal to reduce maintenance burden and improve code clarity.

---

## Unused Namespaces (High Priority)

### 1. `eacl.datomic.rules.optimized` - CONFIRMED UNUSED

**Location:** `src/eacl/datomic/rules/optimized.clj`

**Status:** No active usages

**Analysis:**
- Only referenced by `eacl.datomic.impl.datalog`, which itself is unused (commented out everywhere)
- Comment in performance_test.clj shows it was commented out
- Contains Datalog rules (`check-permission-rules`, `rules-lookup-subjects`, `rules-lookup-resources`) that have been superseded by the direct index-based implementation in `eacl.datomic.impl.indexed`

**Recommendation:** Delete this namespace

---

### 2. `eacl.datomic.rules.optimized-old` - CONFIRMED UNUSED

**Location:** `src/eacl/datomic/rules/optimized_old.clj`

**Status:** Zero usages outside its own namespace declaration

**Analysis:**
- Never required or referenced anywhere in the codebase
- Appears to be an older version of `rules.optimized` kept for historical reference

**Recommendation:** Delete this namespace

---

### 3. `eacl.datomic.impl.datalog` - CONFIRMED UNUSED

**Location:** `src/eacl/datomic/impl/datalog.clj`

**Status:** Commented out in all locations

**Analysis:**
- Commented out in `eacl.datomic.impl` (line 8)
- Commented out in `test/eacl/datomic/impl/indexed_test.clj` (line 14)
- This was the Datalog/rules-based implementation that has been superseded by `eacl.datomic.impl.indexed`
- The docstring in the file says "Optimized EACL implementation" but it's actually slower than the index-based approach

**Recommendation:** Delete this namespace

---

### 4. `eacl.datomic.rules` - LIKELY UNUSED (Contains Bug)

**Location:** `src/eacl/datomic/rules.clj`

**Status:** Has a compile-time bug, no active usages

**Analysis:**
- Line 211 calls `build-slow-rules` but this function is commented out (line 102)
- This would cause namespace load failure if anyone tried to require it
- Only referenced in commented-out imports in `performance_test.clj`
- Contains old Datalog rules superseded by the indexed implementation

**Recommendation:** Delete this namespace (or fix the bug if there's a reason to keep it)

---

### 5. `eacl.impl.spicedb` - STUB (Not Implemented)

**Location:** `src/eacl/impl/spicedb.clj`

**Status:** Stub implementation with TODO

**Analysis:**
- Contains only a namespace declaration and a TODO comment
- Never required or used anywhere
- Intended for SpiceDB gRPC implementation that was never completed

**Recommendation:** Either implement or delete. If keeping as placeholder, add to documentation.

---

## Unused Functions (Medium Priority)

### In `eacl.lazy-merge-sort`

**Location:** `src/eacl/lazy_merge_sort.clj`

The following functions are defined but never called outside the file:

| Function | Lines | Notes |
|----------|-------|-------|
| `lazy-parallel-merge-dedupe-sort` | 26-44 | pmap variant, never benchmarked |
| `lazy-parallel-merge-dedupe-sort-by` | 72-93 | pmap variant, never benchmarked |
| `lazy-merge2` | 113-126 | Internal helper, only used by other unused fns |
| `lazy-merge-all` | 128-139 | Only used by `lazy-fold2-merge-sorted` |
| `fold2` | 141-148 | Used internally, but not by external code |
| `lazy-fold2-merge-sorted` | 150-156 | Never called externally |
| `lazy-fold2-merge-sorted-by` | 158-166 | Never called externally |
| `dedupe-by` | 168-185 | Never called externally |
| `lazy-merge2-dedupe-by` | 220-268 | Internal helper |
| `lazy-merge-all-dedupe-by` | 270-283 | Internal helper |

**Functions actively used:**
- `lazy-merge-dedupe-sort` - used in tests
- `lazy-merge-dedupe-sort-by` - used by indexed impl
- `lazy-fold2-merge-dedupe-sorted-by` - primary merge function used throughout

**Recommendation:**
- Keep `lazy-fold2-merge-dedupe-sorted-by` and its required helpers (`fold2`, `lazy-merge2-dedupe-by`, `lazy-merge-all-dedupe-by`)
- Keep `lazy-merge-dedupe-sort` and `lazy-merge-dedupe-sort-by` (used in tests)
- Consider removing the parallel (pmap) variants and the non-deduping fold2 variants

---

### In `eacl.datomic.impl`

**Location:** `src/eacl/datomic/impl.clj`

| Function | Line | Notes |
|----------|------|-------|
| `can!` | 23-28 | Never called - throwing variant of `can?` |

**Recommendation:** Remove if not needed, or add tests if keeping

---

### In `eacl.datomic.impl.indexed`

**Location:** `src/eacl/datomic/impl/indexed.clj`

| Function | Lines | Notes |
|----------|-------|-------|
| `traverse-single-path` | 179-236 | Only calls itself recursively, never called externally |
| `direct-match-datoms-in-relationship-index` | 410-414 | Never called anywhere |

**Recommendation:** Remove these unused functions

---

## Backup Files to Remove

### `indexed.clj.orig`

**Location:** `src/eacl/datomic/impl/indexed.clj.orig`

**Status:** Backup file, should not be in source control

**Recommendation:** Delete and add `*.orig` to `.gitignore`

---

## Work-in-Progress Code (Low Priority - Keep for Now)

### `eacl.datomic.spice-parser`

**Location:** `src/eacl/datomic/spice_parser.clj`

**Status:** WIP, has tests, partially functional

**Analysis:**
- Has working tests in `parser_test.clj`
- Contains `->eacl-schema` stub (line 215) that is not implemented
- Parser works, but transformation to EACL schema is incomplete

**Recommendation:** Keep but mark clearly as WIP. Tests indicate active development.

---

## Summary of Cleanup Actions

### High Priority (Delete)
1. `src/eacl/datomic/rules/optimized.clj`
2. `src/eacl/datomic/rules/optimized_old.clj`
3. `src/eacl/datomic/impl/datalog.clj`
4. `src/eacl/datomic/rules.clj`
5. `src/eacl/datomic/impl/indexed.clj.orig`

### Medium Priority (Review and Clean)
1. Remove unused functions from `eacl.lazy-merge-sort`
2. Remove `can!` from `eacl.datomic.impl` if not needed
3. Remove `traverse-single-path` and `direct-match-datoms-in-relationship-index` from `indexed.clj`

### Low Priority (Keep/Document)
1. `eacl.impl.spicedb` - decide: implement or document as placeholder
2. `eacl.datomic.spice-parser` - keep as WIP

---

## Estimated Impact

Removing the high-priority items would:
- Delete ~1,200+ lines of unused Datalog rules code
- Simplify the codebase architecture (one clear implementation path via `indexed.clj`)
- Remove potential confusion about which implementation to use
- Eliminate dead code that could rot or cause maintenance burden

---

## Architecture Note

The EACL codebase has evolved from a Datalog rules-based implementation to a direct index-based implementation:

```
OLD (Unused):
eacl.datomic.core -> eacl.datomic.impl -> eacl.datomic.impl.datalog -> eacl.datomic.rules.optimized

NEW (Active):
eacl.datomic.core -> eacl.datomic.impl -> eacl.datomic.impl.indexed -> eacl.lazy-merge-sort
```

The index-based implementation provides better performance and more predictable behavior for large datasets.
