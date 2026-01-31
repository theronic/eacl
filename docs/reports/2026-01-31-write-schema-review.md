# Review: `eacl/write-schema!` Implementation

**Date**: 2026-01-31
**Reviewer**: Claude
**Status**: Post-Implementation Review
**Related Plan**: [2026-01-31-spicedb-schema-plan.md](../plans/2026-01-31-spicedb-schema-plan.md)
**ADR**: [012-spicedb-schema.md](../adr/012-spicedb-schema.md)

---

## Executive Summary

The `write-schema!` implementation provides a functional foundation for parsing SpiceDB schema DSL and transacting schema changes to Datomic. However, the implementation has significant gaps in validation that could allow invalid schemas to be committed, violating the ADR's requirement that "Invalid schema should be rejected and no changes made."

**Overall Assessment**: Functional but incomplete - needs validation improvements before production use.

---

## ADR Compliance Checklist

This section systematically reviews each decision from [ADR 012](../adr/012-spicedb-schema.md).

### Decisions Section

| # | Decision | Status | Notes |
|---|----------|--------|-------|
| 1 | "Rewrite the fixtures from fixtures/relations+permissions to a new `test/eacl/fixtures.schema` file using the Spice DSL" | ❌ **NOT DONE** | No `fixtures.schema` file exists. Tests use a minimal 3-definition example instead of the full 17-relation, 50+ permission fixture set. |
| 2 | "Implement parsing, validation & safe updating of the EACL AuthZ schema" | ⚠️ **PARTIAL** | Parsing: ✅ Done. Validation: ❌ Missing. Safe updating: ✅ Done (orphan check). |
| 3 | "`eacl/read-schema` should return a rich map of schema definitions" | ❌ **NOT DONE** | `eacl.datomic.core/read-schema` returns the raw schema string, not a map. The `schema/read-schema` function returns the map, but the protocol implementation doesn't use it. |
| 4 | "Spice schema DSL should be converted to an internal representation that is easy to query & compare" | ✅ **DONE** | `->eacl-schema` converts parse tree to `{:relations [...] :permissions [...]}` |
| 5 | "Compares the new desired schema to existing schema to identify additions & retractions" | ✅ **DONE** | `compare-schema` correctly identifies additions, unchanged, and retractions |
| 6 | "Build up a list of operations before calling d/transact" | ✅ **DONE** | `write-schema!` builds `tx-data` before transacting |
| 7 | "Retracting a Relation should throw if deleting it would orphan any existing relationships" | ✅ **DONE** | `count-relationships-using-relation` is checked before retraction |
| 8 | "Invalid schema should be rejected and no changes made" | ❌ **NOT DONE** | See breakdown below |
| 9 | "Rejections due to orphaned relationships should be detailed in the error response" | ⚠️ **PARTIAL** | Error includes relation name and count, but not specific relationship details |
| 10 | "Schema string should be stored in Datomic under :eacl/id 'schema-string'" | ✅ **DONE** | Stored correctly on successful write |

### Decision 8 Breakdown - Invalid Schema Rejection

The ADR lists specific validation requirements. Status of each:

| Requirement | Status | Evidence |
|-------------|--------|----------|
| "can't define permissions derived from missing relations" | ❌ **NOT VALIDATED** | `resolve-component` assumes permission if not relation, never validates |
| "arrow permissions can't refer to wrong permissions or relations" | ❌ **NOT VALIDATED** | No cross-resource-type validation |
| "can't use unsupported features" | ❌ **NOT VALIDATED** | `-` operator parsed but not rejected; multi-arrow silently truncated |
| "Permissions must refer to valid relations" | ❌ **NOT VALIDATED** | No validation that target relations exist |
| "Resource & subject types must be correct" | ❌ **NOT VALIDATED** | No type consistency checking |

### Context Section Requirements

| Requirement | Status | Notes |
|-------------|--------|-------|
| "EACL only supports Union (+) operators... should reject other operators as invalid" | ❌ **NOT DONE** | Grammar parses `-` but implementation doesn't reject it |
| "EACL does not support Caveats" | ✅ **OK** | Grammar doesn't parse caveat syntax |
| "EACL only supports one level of nested arrow permissions" | ❌ **NOT VALIDATED** | Multi-arrow silently truncated to single arrow at `spice_parser.clj:253` |

### Process Requirements (Next Steps section)

| # | Requirement | Status |
|---|-------------|--------|
| 1 | "Read the current EACL implementation" | ✅ Done |
| 2 | "Write a detailed report to docs/reports/" | ✅ Done (`2026-01-31-eacl-status.md`) |
| 3 | "Write a detailed plan to docs/plans/" | ✅ Done (`2026-01-31-spicedb-schema-plan.md`) |
| 4 | "Submit your plan for approval" | ❓ Unknown |
| 5 | "If anything is unclear, stop & ask for clarification" | ❓ Unknown |
| 6 | "Run tests between logical changes" | ❓ Unknown |
| 7 | "Write failing unit tests BEFORE writing any code first" | ❌ **NOT DONE** | Tests were written after implementation, not TDD style |

---

## What Works Well

1. **Parser Foundation**: The Instaparse grammar correctly parses the core SpiceDB schema constructs (definitions, relations, permissions with unions and arrows).

2. **Schema Diffing**: `compare-schema` and `calc-set-deltas` correctly identify additions, unchanged items, and retractions.

3. **Orphan Protection**: The implementation correctly prevents deleting relations that are in use by existing relationships.

4. **Stable IDs**: The `->relation-id` and `->permission-id` functions generate deterministic `:eacl/id` values, enabling idempotent transactions.

5. **Tests**: Basic lifecycle tests exist for write/read schema operations.

---

## Critical Issues

### 1. Missing Schema Reference Validation

**Location**: `src/eacl/datomic/schema.clj:305-339` (`write-schema!`)

**Problem**: The ADR explicitly requires: "Invalid schema should be rejected and no changes made, e.g. can't define permissions derived from missing relations, arrow permissions can't refer to wrong permissions or relations."

The current implementation **does not validate**:
- Arrow permissions reference valid source relations on the same resource type
- Arrow targets (permissions or relations) exist on the referenced resource type
- Direct permission relations exist on the same resource type
- Self-referencing permissions (`permission view = admin`) reference existing permissions

**Example of Invalid Schema That Would Be Accepted**:
```spice
definition account {
    relation owner: user
    permission admin = nonexistent_relation  // Should be rejected
    permission view = admin->invalid_target  // Should be rejected
}
```

**Recommendation**: Add a `validate-schema` function that:
1. Builds a lookup of all relations per resource type
2. Builds a lookup of all permissions per resource type
3. Validates each permission's references exist
4. Returns a list of validation errors or throws with detailed message

### 2. Unsupported Operators Not Rejected

**Location**: `src/eacl/datomic/spice_parser.clj:26-27`

**Problem**: The grammar parses both `+` and `-` operators:
```clojure
permission-operator = '+' | '-'
```

But the ADR states: "EACL only supports Union (+) operators for permissions at this time, so should reject other operators as invalid."

The `-` (exclusion/intersection) operator is parsed but silently treated as union.

**Recommendation**: Either:
- Modify the grammar to only accept `+`, OR
- Add post-parse validation that throws on `-` operators

**Test Gap**: `test/eacl/datomic/parser_test.clj:95` has an empty placeholder test for this:
```clojure
(testing "ensure we warn against unsupported Spice schema like exclusion permissions"))
```

### 3. Incomplete Permission Resolution Logic

**Location**: `src/eacl/datomic/spice_parser.clj:240-262` (`resolve-component`)

**Problem**: The function makes a dangerous assumption:
```clojure
(cond
  (contains? (:relations info) name) {:relation (keyword name)}
  :else {:permission (keyword name)}) ; Assume permission if not relation
```

If a permission name doesn't match a relation, it assumes it's a permission **without verifying the permission exists**. This allows invalid references like `permission admin = totally_fake_thing`.

**Recommendation**: Check both relations and permissions explicitly, throw if neither matches.

### 4. Inconsistent `read-schema` Return Type

**Location**: `src/eacl/datomic/core.clj:200-203`

**Problem**: The protocol method returns the raw schema string:
```clojure
(read-schema [this]
  (let [db (d/db conn)
        ent (d/entity db [:eacl/id "schema-string"])]
    (:eacl/schema-string ent)))
```

But the ADR states: "`eacl/read-schema` should return a rich map of schema definitions."

This is inconsistent with the `schema/read-schema` function which returns `{:relations [...] :permissions [...]}`.

**Recommendation**: Decide on the canonical return type. Consider:
- `(read-schema client)` returns the parsed map representation
- `(read-schema-string client)` returns the raw DSL string (if needed)

---

## Design Issues

### 5. Parser Returns Nil Schema-String

**Location**: `src/eacl/datomic/spice_parser.clj:283`

```clojure
{:relations [...] :permissions [...] :schema-string nil}
```

The `:schema-string` is always nil. This is dead code that should be removed or the schema string should be passed through from the caller.

### 6. Permission Order Dependency

**Problem**: Consider this schema:
```spice
definition account {
    relation owner: user
    permission admin = view  // References permission defined below
    permission view = owner
}
```

The current implementation processes permissions in the order they appear in the parsed tree. There's no topological sorting to ensure dependencies are resolved correctly.

While Datomic transactions are atomic and order within a transaction doesn't matter for entity creation, validation logic that checks if referenced permissions exist must account for order or use a two-pass approach.

**Recommendation**: Implement two-pass validation:
1. First pass: collect all relation and permission names
2. Second pass: validate all references against collected names

### 7. No Multi-Arrow Validation

**Location**: `src/eacl/datomic/spice_parser.clj:253`

The ADR mentions: "EACL only supports one level of nested arrow permissions at this time."

The parser only extracts the first path element:
```clojure
path (first (:path component)) ; Only support 1 level for now
```

But there's no **validation** that multi-arrow expressions like `account->team->owner` are rejected. They would silently become `account->team`.

**Recommendation**: Throw an error if `:path` contains more than one element.

### 8. Unimplemented TODO

**Location**: `src/eacl/datomic/schema.clj:228`

```clojure
; TODO: throw for invalid Relation not present in schema.
```

The function `count-relationships-using-relation` should validate that the relation exists in the schema before attempting to count relationships.

---

## Test Gaps

### Missing Fixture File

**ADR Requirement**: "Rewrite the fixtures from fixtures/relations+permissions to a new `test/eacl/fixtures.schema` file using the Spice DSL which will be used to test the implementation against"

**Current State**: No `fixtures.schema` file exists. The tests use a minimal inline schema:
```spice
definition user {}
definition platform { relation super_admin: user }
definition account {
    relation platform: platform
    relation owner: user
    permission admin = owner + platform->super_admin
    permission view = owner + admin
    permission update = admin
}
```

**Required**: The full fixture set from `test/eacl/datomic/fixtures.clj` should be converted to SpiceDB DSL format. This includes:
- 17+ relations across platform, server, network_interface, lease, network, vpc, account, team, backup_schedule, backup
- 50+ permissions with complex arrow expressions
- Multiple subject types per relation (e.g., `relation owner: user | group`)

This is critical because:
1. The implementation has never been tested against the real-world schema complexity
2. Edge cases in the fixtures (like multi-type relations) may expose parser/validation bugs
3. The round-trip test (parse fixtures.schema → compare to fixtures.clj output) would validate correctness

### Missing Test Cases

1. **Fixtures round-trip test**: Parse `fixtures.schema` and compare output to `fixtures/relations+permissions`

2. **Invalid reference rejection**: Test that permissions referencing non-existent relations/permissions are rejected

3. **Exclusion operator rejection**: Test that `permission admin = owner - guest` throws an error

4. **Multi-arrow rejection**: Test that `permission view = account->team->member` throws an error

5. **Arrow target validation**: Test that arrow permissions reference valid targets on the target type:
   ```spice
   definition account {
       relation owner: user
   }
   definition server {
       relation account: account
       permission view = account->nonexistent  // Should fail
   }
   ```

6. **Caveats rejection**: Test that caveat syntax is rejected (if grammar can parse it)

7. **Multi-type relation support**: Test that `relation owner: user | group` parses correctly (current grammar may not support this)

---

## Recommendations Summary

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| **P0** | Add schema reference validation | Medium | High - Prevents invalid state |
| **P0** | Reject unsupported operators | Low | High - ADR requirement |
| **P0** | Create `test/eacl/fixtures.schema` from fixtures.clj | Medium | High - ADR requirement, enables comprehensive testing |
| **P1** | Fix `resolve-component` to validate existence | Low | Medium - Prevents silent failures |
| **P1** | Add multi-arrow rejection | Low | Medium - ADR requirement |
| **P1** | Fix `read-schema` to return rich map per ADR | Low | Medium - ADR requirement |
| **P2** | Remove dead `:schema-string nil` code | Low | Low - Code hygiene |
| **P3** | Implement TODO in `count-relationships-using-relation` | Low | Low - Edge case |

---

## Suggested Implementation Order

1. Add `validate-schema` function with comprehensive reference checking
2. Add operator validation in parser or post-parse
3. Add multi-arrow depth check
4. Update `resolve-component` to explicitly check permission existence
5. Add comprehensive tests for all validation paths
6. Document the canonical `read-schema` return type

---

## Code Samples for Fixes

### Validate Schema Function

```clojure
(defn validate-schema
  "Validates that all permission references are valid.
   Returns nil if valid, throws with detailed errors if invalid."
  [{:keys [relations permissions]}]
  (let [;; Build lookups by resource type
        relations-by-type (group-by :eacl.relation/resource-type relations)
        permissions-by-type (group-by :eacl.permission/resource-type permissions)

        ;; Get relation names per resource type
        relation-names-by-type
        (into {} (for [[rt rels] relations-by-type]
                   [rt (set (map :eacl.relation/relation-name rels))]))

        ;; Get permission names per resource type
        permission-names-by-type
        (into {} (for [[rt perms] permissions-by-type]
                   [rt (set (map :eacl.permission/permission-name perms))]))

        errors (atom [])]

    (doseq [perm permissions]
      (let [res-type (:eacl.permission/resource-type perm)
            perm-name (:eacl.permission/permission-name perm)
            source-rel (:eacl.permission/source-relation-name perm)
            target-type (:eacl.permission/target-type perm)
            target-name (:eacl.permission/target-name perm)]

        ;; Validate source relation exists (unless :self)
        (when (and (not= source-rel :self)
                   (not (contains? (get relation-names-by-type res-type) source-rel)))
          (swap! errors conj
            (str "Permission " res-type "/" perm-name
                 " references non-existent relation: " source-rel)))

        ;; For arrow permissions, validate target exists on target resource type
        (when (not= source-rel :self)
          (let [;; Find the target resource type from the relation
                source-rels (get relations-by-type res-type)
                source-rel-def (first (filter #(= source-rel (:eacl.relation/relation-name %)) source-rels))
                target-res-type (:eacl.relation/subject-type source-rel-def)]
            (when target-res-type
              (if (= target-type :relation)
                (when-not (contains? (get relation-names-by-type target-res-type) target-name)
                  (swap! errors conj
                    (str "Permission " res-type "/" perm-name
                         " arrow target relation " target-name
                         " does not exist on " target-res-type)))
                (when-not (contains? (get permission-names-by-type target-res-type) target-name)
                  (swap! errors conj
                    (str "Permission " res-type "/" perm-name
                         " arrow target permission " target-name
                         " does not exist on " target-res-type)))))))

        ;; For self permissions, validate target exists on same resource
        (when (= source-rel :self)
          (if (= target-type :relation)
            (when-not (contains? (get relation-names-by-type res-type) target-name)
              (swap! errors conj
                (str "Permission " res-type "/" perm-name
                     " references non-existent relation: " target-name)))
            (when-not (contains? (get permission-names-by-type res-type) target-name)
              (swap! errors conj
                (str "Permission " res-type "/" perm-name
                     " references non-existent permission: " target-name)))))))

    (when (seq @errors)
      (throw (ex-info "Invalid schema" {:errors @errors})))

    nil))
```

### Operator Validation

```clojure
;; In spice_parser.clj, add after parsing:

(defn validate-operators
  "Ensures only union (+) operators are used."
  [parse-tree]
  (let [operators (atom [])]
    ;; Walk tree and collect operators
    (clojure.walk/postwalk
      (fn [node]
        (when (and (vector? node)
                   (= :permission-operator (first node)))
          (swap! operators conj (second node)))
        node)
      parse-tree)
    (let [unsupported (filter #(not= "+" %) @operators)]
      (when (seq unsupported)
        (throw (ex-info "Unsupported operators in schema"
                       {:operators (set unsupported)
                        :message "EACL only supports union (+) operators. Exclusion (-) and intersection (&) are not supported."}))))))
```

---

## Conclusion

The `write-schema!` implementation provides a functional foundation but has significant gaps against the ADR requirements:

**Summary of ADR Compliance:**
- ✅ 5 of 10 decisions fully implemented
- ⚠️ 2 partially implemented
- ❌ 3 not implemented (including critical validation requirements)

**Critical Gaps:**
1. **No `fixtures.schema` file** - The implementation was never tested against the real fixture complexity
2. **No schema validation** - Invalid schemas with broken references can be committed
3. **Unsupported operators not rejected** - `-` operator silently treated as `+`
4. **`read-schema` returns string not map** - Violates ADR specification

**Risk Assessment:**
The lack of validation represents a data integrity risk. Invalid schemas could be persisted, potentially causing runtime failures in `can?` checks when arrow permissions reference non-existent relations or permissions.

**Recommendation:**
Address all P0 issues before production use:
1. Create `fixtures.schema` and add round-trip test
2. Implement `validate-schema` function
3. Add operator validation
4. Fix `read-schema` return type
