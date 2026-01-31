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

### Missing Test Cases

1. **Invalid reference rejection**: Test that permissions referencing non-existent relations/permissions are rejected

2. **Exclusion operator rejection**: Test that `permission admin = owner - guest` throws an error

3. **Multi-arrow rejection**: Test that `permission view = account->team->member` throws an error

4. **Arrow target validation**: Test that arrow permissions reference valid targets on the target type:
   ```spice
   definition account {
       relation owner: user
   }
   definition server {
       relation account: account
       permission view = account->nonexistent  // Should fail
   }
   ```

5. **Caveats rejection**: Test that caveat syntax is rejected (if grammar can parse it)

---

## Recommendations Summary

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| **P0** | Add schema reference validation | Medium | High - Prevents invalid state |
| **P0** | Reject unsupported operators | Low | High - ADR requirement |
| **P1** | Fix `resolve-component` to validate existence | Low | Medium - Prevents silent failures |
| **P1** | Add multi-arrow rejection | Low | Medium - ADR requirement |
| **P2** | Clarify `read-schema` return type | Low | Low - API consistency |
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

The `write-schema!` implementation provides a solid foundation but requires additional validation work to meet the ADR's requirements for rejecting invalid schemas. The most critical gap is the lack of reference validation, which could allow corrupted schemas to be persisted.

I recommend addressing P0 issues before using this in production, as they represent potential data integrity risks.
