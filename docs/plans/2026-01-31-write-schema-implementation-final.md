# write-schema! Implementation Plan - Final

**Date**: 2026-01-31
**Status**: COMPLETED
**Related ADR**: [012-spicedb-schema.md](../adr/012-spicedb-schema.md)
**Review Document**: [2026-01-31-write-schema-review.md](../reports/2026-01-31-write-schema-review.md)
**Earlier Plan**: [2026-01-31-spicedb-schema-plan.md](2026-01-31-spicedb-schema-plan.md)

---

## Summary

This document describes the completed implementation of `eacl/write-schema!` addressing all gaps identified in the review document.

## Completed Tasks

### Phase 0: Full SpiceDB Grammar Support

- [x] **Grammar Rewrite**: Rewrote Instaparse grammar to parse the **complete official SpiceDB syntax**:
  - Type paths (`docs/document`)
  - Wildcards (`user:*`)
  - Subject relations (`group#member`)
  - Caveats (`with caveatname`)
  - All operators (`+`, `-`, `&`)
  - Arrow functions (`.any()`, `.all()`)
  - `nil` and `self` keywords
  - Parentheses for grouping
- [x] **Separation of Concerns**: Parsing now accepts full SpiceDB syntax; **validation** enforces EACL restrictions separately via `validate-eacl-restrictions`

### Phase 1: Parser Validation (Step 2.1-2.2)

- [x] **EACL Restriction Validation**: Added `validate-eacl-restrictions` function that rejects:
  - Exclusion (`-`) and intersection (`&`) operators
  - Multi-level arrows (`a->b->c`)
  - `.all()` arrow function
  - `nil` and `self` keywords
  - Namespaced type paths
  - Wildcards, subject relations, and caveats

### Phase 2: Schema Reference Validation (Step 2.3-2.4)

- [x] **validate-schema-references Function**: Implemented in `schema.clj` with comprehensive validation:
  - Validates direct permissions reference existing relations on same resource type
  - Validates arrow permissions reference valid source relations
  - Validates arrow targets exist on target resource type
  - Validates self-referencing permissions reference existing permissions
- [x] **Integration**: `write-schema!` now calls `validate-schema-references` before transacting

### Phase 3: Grammar Enhancement (Step 1.2)

- [x] **Multi-Type Relations**: Updated grammar to support `relation owner: user | group` syntax
- [x] **Parser Updates**: Updated `extract-relations` and `->eacl-schema` to expand multi-type relations into multiple Relation entities

### Phase 4: Fixtures Schema (Step 1.1)

- [x] **fixtures.schema File**: Created `test/eacl/fixtures.schema` with full SpiceDB DSL conversion of fixtures.clj
  - 24 relations (including multi-type expansion)
  - 57 permissions
  - 11 resource types

### Phase 5: Test Coverage (Steps 4.1-4.3)

- [x] **Operator Tests**: Added test for exclusion operator rejection in `parser_test.clj`
- [x] **Multi-Arrow Tests**: Added test for multi-arrow rejection
- [x] **Validation Tests**: Added comprehensive validation error tests in `schema_test.clj`
- [x] **Round-Trip Test**: Added `fixtures-schema-round-trip-test` that:
  - Writes fixtures.schema to database
  - Verifies 24 relations and 57 permissions
  - Verifies multi-type relation expansion
  - Verifies schema string storage

### Phase 6: ADR Compliance (Step 3.1)

- [x] **read-schema Return Type**: Updated `eacl.datomic.core/read-schema` to return rich map `{:relations [...] :permissions [...]}` instead of raw string

### Phase 7: Cleanup (Step 5.1-5.2)

- [x] **Dead Code Removal**: Removed `:schema-string nil` from `->eacl-schema`
- [x] **TODO Resolution**: Updated `count-relationships-using-relation` docstring to note that validation is now done at write-schema! time

---

## Files Modified

| File | Changes |
|------|--------|
| `src/eacl/datomic/spice_parser.clj` | Added `validate-operators`, multi-arrow validation, multi-type grammar support, removed dead code |
| `src/eacl/datomic/schema.clj` | Added `validate-schema-references`, integrated validation into `write-schema!`, updated docstrings |
| `src/eacl/datomic/core.clj` | Changed `read-schema` to return rich map |
| `test/eacl/datomic/parser_test.clj` | Added `unsupported-features-tests`, updated expected parse tree structure |
| `test/eacl/datomic/schema_test.clj` | Added `schema-validation-tests`, `fixtures-schema-round-trip-test` |
| `test/eacl/fixtures.schema` | Created new file with full SpiceDB DSL schema |

---

## Test Results

```
Indexed Tests:  16 tests, 209 assertions - PASSED
Schema Tests:    5 tests,  34 assertions - PASSED
Parser Tests:    2 tests,  20 assertions - PASSED

Total: 23 tests, 263 assertions - ALL PASSED
```

---

## SpiceDB Grammar Support

The EACL parser now supports the **full official SpiceDB grammar**. Parsing and validation are separate concerns:

| SpiceDB Feature | Parsed | EACL Supported |
|-----------------|--------|----------------|
| Basic definitions | Yes | Yes |
| Simple relations | Yes | Yes |
| Multi-type relations (`user \| group`) | Yes | Yes |
| Union operator (`+`) | Yes | Yes |
| Arrow permissions (`->`) | Yes | Yes |
| Parentheses for grouping | Yes | Yes |
| `.any()` arrow function | Yes | Yes (equiv to `->`) |
| Exclusion operator (`-`) | Yes | **No** |
| Intersection operator (`&`) | Yes | **No** |
| Multi-level arrows (`a->b->c`) | Yes | **No** |
| Wildcards (`user:*`) | Yes | **No** |
| Subject relations (`group#member`) | Yes | **No** |
| Caveats (`with caveatname`) | Yes | **No** |
| `.all()` arrow function | Yes | **No** |
| `nil` keyword | Yes | **No** |
| `self` keyword | Yes | **No** |
| Namespaced types (`docs/document`) | Yes | **No** |

Unsupported features are rejected during validation with clear error messages.

---

## ADR 012 Compliance Summary

| # | ADR Requirement | Status |
|---|-----------------|--------|
| 1 | Create fixtures.schema file | DONE |
| 2 | Implement parsing, validation & safe updating | DONE |
| 3 | read-schema returns rich map | DONE |
| 4 | Internal representation easy to query & compare | DONE |
| 5 | Compare new vs existing schema | DONE (existing) |
| 6 | Build operations before transacting | DONE (existing) |
| 7 | Throw on orphaned relationships | DONE (existing) |
| 8 | Reject invalid schema | DONE |
| 9 | Detail orphan rejection errors | DONE (existing) |
| 10 | Store schema string | DONE (existing) |

---

## Usage Example

```clojure
(require '[eacl.core :as eacl])
(require '[eacl.datomic.core :as datomic])

;; Create client
(def client (datomic/make-client conn {:entity->object-id :eacl/id
                                        :object-id->ident (fn [id] [:eacl/id id])}))

;; Write schema using SpiceDB DSL
(eacl/write-schema! client
  "definition user {}
   definition account {
     relation owner: user | group
     permission admin = owner
   }")

;; Read schema back (returns rich map)
(eacl/read-schema client)
;; => {:relations [{:eacl.relation/resource-type :account ...} ...]
;;     :permissions [{:eacl.permission/resource-type :account ...} ...]}
```

---

## Validation Errors

The implementation rejects invalid schemas with detailed error messages:

```clojure
;; Unsupported operator
(eacl/write-schema! client "definition a { permission p = x - y }")
;; => throws: "Unsupported operator in schema" {:operators #{"-"}}

;; Invalid reference
(eacl/write-schema! client "definition a { permission p = nonexistent }")
;; => throws: "Invalid schema: reference validation failed" 
;;    {:errors [{:type :invalid-self-permission, :message "..."}]}

;; Invalid arrow target
(eacl/write-schema! client 
  "definition a { relation b: b }
   definition b {}
   definition c {
     relation a: a
     permission p = a->nonexistent
   }")
;; => throws: "Invalid schema: reference validation failed"
;;    {:errors [{:type :invalid-arrow-target-permission, :message "..."}]}
```
