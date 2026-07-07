# Plan: Fix Permission Tuple Uniqueness Conflicts

## Problem Analysis

After implementing ADR 007 (unified permission schema), tests are failing with Datomic tuple conflicts:

```
:db.error/datoms-conflict Two datoms in the same transaction conflict
[:server :permission :admin :admin]
```

### Root Cause

The current schema has **two unique constraints** on permissions:

1. **4-element tuple (currently failing):**
   ```clojure
   [:eacl.permission/resource-type
    :eacl.permission/target-type  
    :eacl.permission/target-name
    :eacl.permission/permission-name]
   ```

2. **5-element tuple (already exists but unused):**
   ```clojure
   [:eacl.permission/resource-type
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name
    :eacl.permission/permission-name]
   ```

### Conflicting Permissions

From fixtures, these two permissions create the same 4-element tuple `[:server :permission :admin :admin]`:

1. `(Permission :server :admin {:arrow :account :permission :admin})`
   - source-relation-name: `:account`
   - target-type: `:permission`
   - target-name: `:admin`

2. `(Permission :server :admin {:arrow :vpc :permission :admin})`
   - source-relation-name: `:vpc`  
   - target-type: `:permission`
   - target-name: `:admin`

The permissions are different (different `:source-relation-name`) but the 4-element tuple is identical.

## Solution Strategy

The user specified requirements:
1. **Include `:source-relation-name` in tuple** for disambiguation
2. **Use `:self` when source-relation-name is nil** (for direct permissions) to avoid null values
3. **Support efficient index-based enumeration** of arrow permissions
4. **Consider separate indices** for `:permission` vs `:relation` types
5. **Update Datalog rules** to handle `:self` special value
6. **Eventually rename `:source-relation-name` to `:via-relation`** (later)

## Implementation Plan

### Phase 1: Update Schema (Required for Immediate Fix)

1. **Remove the failing 4-element tuple:**
   ```clojure
   ;; REMOVE this tuple that's causing conflicts:
   {:db/ident :eacl.permission/resource-type+target-type+target-name+permission-name
    :db/unique :db.unique/identity}
   ```

2. **Ensure 5-element tuple handles all cases:**
   ```clojure
   ;; Keep this tuple (already exists) for all permissions:
   {:db/ident :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name
    :db/unique :db.unique/identity}
   ```

3. **Add separate indices for efficient enumeration:**
   ```clojure
   ;; For enumerating permission-type arrows
   {:db/ident :eacl.permission/resource-type+source-relation-name+target-type+permission-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index true}
   
   ;; For enumerating relation-type arrows  
   {:db/ident :eacl.permission/resource-type+source-relation-name+target-type+target-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name]
    :db/cardinality :db.cardinality/one
    :db/index true}
   ```

### Phase 2: Update Permission Function

1. **Modify Permission function** to always set `:source-relation-name`:
   ```clojure
   ;; For direct permissions: {:relation relation-name}
   {:eacl.permission/source-relation-name :self  ; <-- Add this
    ...}
   
   ;; Arrow permissions already set source-relation-name correctly
   ```

### Phase 3: Update Datalog Rules

1. **Update rules in `optimized.clj`** to handle `:self` value:
   ```clojure
   ;; Change from:
   [(missing? $ ?perm-def :eacl.permission/source-relation-name)]
   
   ;; To:
   [?perm-def :eacl.permission/source-relation-name :self]
   ```

2. **Ensure all rule clauses** check for `:self` vs actual relation names appropriately.

### Phase 4: Verification

1. **Run all tests** to ensure no conflicts
2. **Verify index performance** for arrow permission enumeration
3. **Test d/index-range access** works with `:self` values

## Technical Details

### Files to Modify

1. **`src/eacl/datomic/schema.clj`**
   - Remove conflicting 4-element tuple
   - Add enumeration indices  

2. **`src/eacl/datomic/impl_base.clj`**
   - Update `Permission` function to set `:source-relation-name :self` for direct permissions

3. **`src/eacl/datomic/rules/optimized.clj`**
   - Update all rules to handle `:self` instead of `missing?` checks

### Key Constraints

- **No `nil` values** in tuples (breaks `d/index-range`)
- **Preserve existing functionality** for all permission types
- **Maintain performance** for both direct and arrow permissions  
- **Support efficient enumeration** of arrow permissions by type

### Risk Mitigation

- **Incremental approach**: Fix schema first, then update code
- **Comprehensive testing**: Verify all test suites pass
- **Performance validation**: Ensure index access patterns work as expected

## Expected Outcome

After implementation:
1. ✅ All tuple conflicts resolved
2. ✅ Tests pass completely
3. ✅ Efficient enumeration of arrow permissions  
4. ✅ Support for both `:permission` and `:relation` arrow types
5. ✅ No null values in tuple indices
6. ✅ Datalog rules work with `:self` for direct permissions

This plan provides a robust solution that addresses the immediate conflict while setting up the foundation for efficient arrow permission enumeration as requested.