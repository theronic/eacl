# Review: `eacl/write-schema!` Implementation - Follow-up

**Date**: 2026-01-31
**Reviewer**: Claude
**Status**: Post-Implementation Follow-up Review
**Previous Review**: [2026-01-31-write-schema-review.md](2026-01-31-write-schema-review.md)
**Implementation Plan**: [2026-01-31-write-schema-implementation-final.md](../plans/2026-01-31-write-schema-implementation-final.md)
**ADR**: [012-spicedb-schema.md](../adr/012-spicedb-schema.md)

---

## Executive Summary

Significant improvements have been made since the initial review. The implementation now addresses most of the critical gaps identified, including schema reference validation, operator rejection, and the creation of `fixtures.schema`. The grammar was rewritten to parse the full SpiceDB syntax with EACL restrictions enforced via separate validation.

**Overall Assessment**: Production-ready with minor caveats. The P0 issues from the previous review have been addressed.

---

## Previous Issues - Resolution Status

### From Initial Review

| Issue | Priority | Status | Resolution |
|-------|----------|--------|------------|
| Missing schema reference validation | P0 | ✅ **FIXED** | `validate-schema-references` in schema.clj |
| Unsupported operators not rejected | P0 | ✅ **FIXED** | `validate-eacl-restrictions` with detailed error messages |
| Missing `fixtures.schema` file | P0 | ✅ **FIXED** | Created with 11 definitions, 24 relations, 57 permissions |
| `resolve-component` unsafe assumption | P1 | ⚠️ **MITIGATED** | Upstream validation catches most cases |
| Multi-arrow silently truncated | P1 | ✅ **FIXED** | Now rejected with error message |
| `read-schema` return type | P1 | ✅ **FIXED** | Now returns `{:relations [...] :permissions [...]}` |
| Dead `:schema-string nil` code | P2 | ✅ **FIXED** | Removed |
| TDD not followed | Process | N/A | Not fixable retroactively |

---

## ADR 012 Compliance - Updated

| # | Decision | Previous | Current |
|---|----------|----------|---------|
| 1 | Create fixtures.schema file | ❌ | ✅ **DONE** |
| 2 | Implement parsing, validation & safe updating | ⚠️ Partial | ✅ **DONE** |
| 3 | `read-schema` returns rich map | ❌ | ✅ **DONE** |
| 4 | Internal representation easy to query & compare | ✅ | ✅ |
| 5 | Compare new vs existing schema | ✅ | ✅ |
| 6 | Build operations before transacting | ✅ | ✅ |
| 7 | Throw on orphaned relationships | ✅ | ✅ |
| 8 | Reject invalid schema | ❌ | ✅ **DONE** |
| 9 | Detail orphan rejection errors | ⚠️ Partial | ✅ **DONE** |
| 10 | Store schema string | ✅ | ✅ |

**ADR Compliance: 10/10 decisions implemented**

---

## Architecture Improvements

### 1. Two-Stage Validation

The implementation now cleanly separates parsing from validation:

```
Schema String → parse-schema → Full SpiceDB Parse Tree
                                      ↓
                           validate-eacl-restrictions
                                      ↓
                              ->eacl-schema
                                      ↓
                        validate-schema-references
                                      ↓
                              Datomic Transaction
```

This is a significant improvement over the previous approach where validation was tightly coupled to parsing.

### 2. Comprehensive Error Messages

Validation errors now include detailed context:

```clojure
{:type :invalid-arrow-target-permission
 :permission "server/view"
 :arrow-via :account
 :target-type :account
 :target :nonexistent
 :message "Permission server/view arrow via account->nonexistent - permission 'nonexistent' does not exist on account"}
```

### 3. Full SpiceDB Grammar Support

The grammar now parses the complete SpiceDB syntax. EACL restrictions are enforced separately, making it easy to add features incrementally.

---

## New Issues Discovered

### P0 - Critical

#### 1. No Comment Support

**Location**: `src/eacl/spicedb/parser.clj:13-61` (grammar definition)

**Problem**: The grammar does not support SpiceDB comments (`//`, `/* */`, `/** */`). Production schemas commonly include documentation comments.

**Example that fails to parse**:
```spice
// User definition
definition user {}

/* Account with owner relation */
definition account {
    relation owner: user  // The account owner
}
```

**Recommendation**: Add comment support to the grammar:
```
<comment> = line-comment | block-comment
<line-comment> = <'//'> #'[^\n]*' <'\n'>?
<block-comment> = <'/*'> #'.*?' <'*/'>
```

**Impact**: Production schemas are likely to have comments. This is blocking for real-world adoption.

---

### P1 - Important

#### 2. Potential Semantic Difference in Self-Permission Handling

**Location**:
- `test/eacl/fixtures.schema:68` - `permission via_self_admin = admin`
- `test/eacl/datomic/fixtures.clj:141` - `(Permission :server :via_self_admin {:arrow :self :permission :admin})`

**Problem**: The DSL version uses a direct permission reference (`admin`), while the Clojure fixture uses a `:self` arrow (`{:arrow :self :permission :admin}`). These may have different runtime semantics.

**Analysis**:
- DSL `admin` → resolves to `{:permission :admin}` → `impl/Permission` with `:source-relation-name :self`
- Clojure `{:arrow :self :permission :admin}` → explicit self-arrow

These should be semantically equivalent (both check the `admin` permission on the current resource), but the equivalence should be verified with a test.

**Recommendation**: Add a test that verifies the `via_self_admin` permission works identically between DSL-written and Clojure-written schemas.

---

### P2 - Minor

#### 3. Unreachable Code in `validate-schema-references`

**Location**: `src/eacl/datomic/schema.clj:312-329`

**Problem**: The `validate-schema-references` function has special handling for `source-rel = :self`:

```clojure
(if (= source-rel :self)
  (if (= target-type :relation)
    ;; Self -> relation validation
    ...
```

However, the `self` keyword is rejected by `validate-eacl-restrictions` before this code is reached. This dead code adds confusion.

**Recommendation**: Either:
1. Remove the `:self` handling from `validate-schema-references`, OR
2. Consider supporting `self` keyword in future (move from "unsupported" to "supported")

#### 4. `resolve-component` Still Has Unsafe Fallback

**Location**: `src/eacl/spicedb/parser.clj:549-555`

```clojure
(if (contains? (:relations info) name)
  {:relation (keyword name)}
  {:permission (keyword name)}) ; Still assumes permission if not relation
```

**Mitigation**: The downstream `validate-schema-references` catches invalid permission references, so this is no longer a data integrity risk. However, the error message comes from a different location, which could be confusing.

**Recommendation**: Add explicit validation here and throw immediately with a clear message rather than deferring to downstream validation.

#### 5. Inconsistent Error Sources

**Problem**: Invalid schemas can be rejected at two different points with different error messages:
1. `resolve-component` throws "Unknown relation for arrow base"
2. `validate-schema-references` throws "Invalid schema: reference validation failed"

**Example from tests**:
- `schema_test.clj:151` expects "Unknown relation" (from parser)
- `schema_test.clj:120` expects "Invalid schema" (from validation)

This works correctly but could be cleaner with a single validation point.

---

### P3 - Cosmetic / Future Work

#### 6. Outstanding Items from Implementation Plan

The implementation plan correctly identifies these future work items:
- Comment support (should be P0)
- Expiration traits
- Caveat definitions
- `self` keyword support
- `.all()` function support
- Subject relations (`group#member`)
- Wildcards (`user:*`)

---

## Test Coverage Analysis

### Added Tests

| Test File | Test | Coverage |
|-----------|------|----------|
| `parser_test.clj` | `unsupported-features-tests` | Exclusion, intersection, multi-arrow, wildcards, subject relations, caveats, nil, self, .all() |
| `schema_test.clj` | `schema-validation-tests` | Invalid references, invalid arrow targets, missing relations |
| `schema_test.clj` | `fixtures-schema-round-trip-test` | Full fixtures.schema write/read |

### Test Gaps Remaining

1. **Comment handling** - No test for schemas with comments (will fail)
2. **Self-permission equivalence** - No test verifying DSL `permission x = y` equals Clojure `{:arrow :self :permission :y}`
3. **Complex union expressions** - Limited testing of deeply nested `(a + b) + (c + d)` expressions

---

## Recommendations Summary

| Priority | Issue | Effort | Action |
|----------|-------|--------|--------|
| **P0** | No comment support | Medium | Add comment handling to grammar before production use |
| **P1** | Self-permission equivalence | Low | Add equivalence test |
| **P2** | Dead code in validate-schema-references | Low | Remove or document `:self` handling |
| **P2** | Unsafe fallback in resolve-component | Low | Add explicit validation with clear error |
| **P3** | Inconsistent error sources | Low | Consider consolidating validation |

---

## Conclusion

The implementation has substantially improved since the initial review. All P0 issues from the ADR compliance checklist have been addressed:

1. ✅ `fixtures.schema` created
2. ✅ Schema validation implemented
3. ✅ Unsupported operators rejected
4. ✅ `read-schema` returns rich map

**The only blocking issue for production use is comment support.** Real-world SpiceDB schemas typically include documentation comments, and the current parser will fail on them.

**Recommendation**: Add comment support to the grammar, then the implementation is ready for production use.

---

## Appendix: fixtures.schema Completeness

Comparing `fixtures.schema` to `fixtures.clj`:

| Metric | fixtures.clj | fixtures.schema | Match |
|--------|--------------|-----------------|-------|
| Resource types | 12 | 11 | ⚠️ `host` missing in DSL |
| Relations (expanded) | ~24 | 24 | ✅ |
| Permissions | 53 | 57 | ✅ (some combined differently) |
| Multi-type relations | 2 | 1 | ⚠️ Only `account/owner: user | group` |

Note: `host` type exists in fixtures.clj but is not used, so omitting it is acceptable. The multi-type relation count difference is because fixtures.clj has `(Relation :account :owner :user)` and `(Relation :account :owner :group)` as separate entities, while the DSL combines them as `relation owner: user | group`.
