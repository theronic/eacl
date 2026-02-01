# EACL ClojureScript + DataScript Port Plan

**Date:** 2026-02-01
**Status:** Draft - Pending Approval
**Author:** Claude

## Executive Summary

This plan outlines the translation of EACL (Entity Access Control Library) from Clojure/Datomic to ClojureScript/DataScript. The goal is to enable running EACL in browsers for website demos and edge nodes, with 1-for-1 API compatibility with the Datomic implementation.

**Key Insight:** EACL's current implementation is well-suited for porting because:
1. It uses the `IAuthorization` protocol abstraction (already implementation-agnostic)
2. No Datomic history/temporal features are used
3. Most Datalog queries have already been replaced with direct index traversal
4. The data model is straightforward (Relations, Permissions, Relationships)

---

## Table of Contents

1. [Goals & Non-Goals](#1-goals--non-goals)
2. [Architecture Overview](#2-architecture-overview)
3. [Key Technical Challenges](#3-key-technical-challenges)
4. [DataScript Schema Translation](#4-datascript-schema-translation)
5. [API Compatibility Matrix](#5-api-compatibility-matrix)
6. [Implementation Phases](#6-implementation-phases)
7. [Module Structure](#7-module-structure)
8. [Index Strategy for DataScript](#8-index-strategy-for-datascript)
9. [Lazy Sequence Handling in JS](#9-lazy-sequence-handling-in-js)
10. [Testing Strategy](#10-testing-strategy)
11. [Performance Considerations](#11-performance-considerations)
12. [Future: Sync from Datomic](#12-future-sync-from-datomic)
13. [Open Questions](#13-open-questions)
14. [Appendix: Code Examples](#appendix-code-examples)

---

## 1. Goals & Non-Goals

### Goals

- **1:1 API Compatibility**: Same `IAuthorization` protocol methods with identical behavior
- **Browser-Ready**: Run in any modern browser (demo scenarios, edge nodes)
- **Shared Data Model**: Relations, Permissions, Relationships use identical semantics
- **Portable Core**: Share `eacl.core` (protocol, records) between Clojure and ClojureScript
- **Demo-Friendly**: Support reasonably sized datasets (thousands of relationships) for demos
- **Test Parity**: Port existing test suite to validate correctness

### Non-Goals (For This Phase)

- **Production Scale**: Not optimized for millions of relationships (Datomic handles that)
- **Persistence**: DataScript is in-memory only; persistence is out of scope
- **Sync Protocol**: Syncing from Datomic to DataScript deferred to later phase
- **Schema Migrations**: Assume schema is bootstrapped at startup
- **Consistency Tokens**: ZedTokens are Datomic-specific; DataScript is always consistent

---

## 2. Architecture Overview

### Current Datomic Architecture

```
┌─────────────────────────────────────────────────────┐
│                    eacl.core                        │
│  (IAuthorization protocol, records, helpers)        │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│               eacl.datomic.core                     │
│  (Spiceomic record implementing IAuthorization)     │
│  - ID coercion (external ↔ internal)                │
│  - Cursor transformation                            │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│            eacl.datomic.impl.indexed                │
│  - Permission path calculation (cached)             │
│  - Index traversal (d/index-range, d/datoms)        │
│  - Lazy merge-sort for pagination                   │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│              Datomic Database                       │
│  - Tuple indices for fast composite lookups         │
│  - d/index-range for efficient range scans          │
└─────────────────────────────────────────────────────┘
```

### Proposed ClojureScript Architecture

```
┌─────────────────────────────────────────────────────┐
│           eacl.core (SHARED .cljc)                  │
│  (IAuthorization protocol, records, helpers)        │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│            eacl.datascript.core (NEW)               │
│  (DataScriptomic record implementing IAuthorization)│
│  - ID coercion (simplified for DataScript)          │
│  - Cursor transformation                            │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│         eacl.datascript.impl.indexed (NEW)          │
│  - Permission path calculation (cached)             │
│  - Index traversal (d/index-range works!)           │
│  - Lazy merge-sort (lazy-seq works in CLJS)         │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│             DataScript Database                     │
│  - d/index-range for efficient range scans          │
│  - Manually-computed composite key vectors          │
└─────────────────────────────────────────────────────┘
```

---

## 3. Key Technical Challenges

### 3.1 `d/index-range` IS Supported ✅

**Good News:** DataScript supports `d/index-range` with the same API as Datomic:

```clojure
(d/index-range db attr start end)
;; Returns part of :avet index between [_ attr start] and [_ attr end]
```

This means the core traversal logic in `indexed.clj` can be ported with minimal changes. The `d/index-range` calls will work directly.

**Requirement:** The attribute must be marked as `:db/index true` (same as Datomic).

### 3.2 Tuple Attributes Not Supported

**Problem:** Datomic supports `:db.type/tuple` with `:db/tupleAttrs` for composite indices. DataScript does not.

**Current Schema (schema.clj:180-189)**:
```clojure
{:db/ident :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
 :db/valueType :db.type/tuple
 :db/tupleAttrs [:eacl.relationship/subject-type
                 :eacl.relationship/subject
                 :eacl.relationship/relation-name
                 :eacl.relationship/resource-type
                 :eacl.relationship/resource]}
```

**Solution:** Store composite keys as strings or vectors in a single attribute:

```clojure
;; DataScript approach: composite key as vector
{:db/ident :eacl.relationship/forward-key
 :db/valueType :db.type/ref  ; or use string encoding
 :db/index true}

;; When creating relationships, compute:
:eacl.relationship/forward-key
  [subject-type subject-eid relation-name resource-type resource-eid]
```

**Trade-off:** Vectors are comparable in ClojureScript, enabling sorted iteration. String encoding is simpler but less efficient.

### 3.3 Lazy Sequences in JavaScript

**Problem:** ClojureScript lazy sequences work differently than JVM lazy sequences. JavaScript is single-threaded and doesn't handle truly lazy I/O well.

**Current Usage (lazy_merge_sort.clj)**:
```clojure
(lazy-seq
  (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
    (cons min-val (lazy-fold2-merge-dedupe-sorted-by ...))))
```

**Solution Options:**

1. **Option A: Keep lazy-seq** - ClojureScript supports `lazy-seq`, it just can't do parallel I/O. Since DataScript is in-memory, this is fine.

2. **Option B: Convert to eager with limits** - Replace lazy pagination with eager `take` + `drop`:
   ```clojure
   (defn merge-sorted-with-limit [seqs limit]
     (take limit (sort (mapcat identity seqs))))
   ```

3. **Option C: Use transducers** - More memory-efficient for large datasets:
   ```clojure
   (into [] (comp (mapcat identity) (take limit) (dedupe)) sorted-seqs)
   ```

**Recommendation:** Start with Option A (lazy-seq works in CLJS), optimize to Option C if needed.

### 3.4 Entity ID Resolution

**Problem:** Datomic uses `d/entid` to resolve lookup refs to entity IDs. DataScript has different semantics.

**Current Usage (indexed.clj:248)**:
```clojure
(d/entid db subject-id)  ;; subject-id may be [:eacl/id "alice"]
```

**DataScript Equivalent:**
```clojure
;; DataScript uses different API
(defn resolve-eid [db lookup-ref]
  (if (vector? lookup-ref)
    (let [[attr val] lookup-ref]
      (ffirst (d/q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr val)))
    lookup-ref))
```

**Or use `d/entid` which DataScript does support for simple cases.**

### 3.5 LRU Cache

**Problem:** Permission path caching uses `clojure.core.cache`, which is Clojure-only.

**Current Usage (indexed.clj:87-91)**:
```clojure
(def permission-paths-cache
  (atom (cache/lru-cache-factory {} :threshold 1000)))
```

**Solution:** Use a ClojureScript-compatible cache:

1. **cljs-cache** - Port of core.cache to ClojureScript
2. **Simple atom with size limit**:
   ```clojure
   (defn lru-cache [max-size]
     (atom {:cache {} :order []}))
   ```
3. **js/Map with manual eviction**

**Recommendation:** Implement simple LRU in pure ClojureScript (< 50 lines).

---

## 4. DataScript Schema Translation

### 4.1 Schema Mapping

| Datomic Type | DataScript Type | Notes |
|-------------|-----------------|-------|
| `:db.type/keyword` | `:db.type/keyword` | Direct mapping |
| `:db.type/string` | `:db.type/string` | Direct mapping |
| `:db.type/ref` | `:db.type/ref` | Works the same |
| `:db.type/tuple` | N/A | Use string/vector encoding |
| `:db/unique :db.unique/identity` | `:db/unique :db.unique/identity` | Supported |
| `:db/index true` | `:db/index true` | Supported |

### 4.2 Proposed DataScript Schema

```clojure
(def datascript-schema
  {;; Entity ID mapping (optional, for external IDs)
   :eacl/id {:db/unique :db.unique/identity}

   ;; Relations
   :eacl.relation/resource-type {:db/index true}
   :eacl.relation/relation-name {:db/index true}
   :eacl.relation/subject-type {:db/index true}
   ;; Composite key for uniqueness (as vector)
   :eacl.relation/composite-key {:db/unique :db.unique/identity}

   ;; Permissions
   :eacl.permission/resource-type {:db/index true}
   :eacl.permission/permission-name {:db/index true}
   :eacl.permission/source-relation-name {:db/index true}
   :eacl.permission/target-type {:db/index true}
   :eacl.permission/target-name {:db/index true}
   ;; Composite key for fast lookup
   :eacl.permission/lookup-key {:db/index true}  ;; [resource-type permission-name]
   :eacl.permission/composite-key {:db/unique :db.unique/identity}

   ;; Relationships
   :eacl.relationship/subject-type {:db/index true}
   :eacl.relationship/subject {:db/type :db.type/ref :db/index true}
   :eacl.relationship/relation-name {:db/index true}
   :eacl.relationship/resource-type {:db/index true}
   :eacl.relationship/resource {:db/type :db.type/ref :db/index true}
   ;; Forward composite key for lookup-resources
   :eacl.relationship/forward-key {:db/index true}  ;; [subj-type subj-eid rel-name res-type res-eid]
   ;; Reverse composite key for lookup-subjects
   :eacl.relationship/reverse-key {:db/index true}  ;; [res-type res-eid rel-name subj-type subj-eid]
   :eacl.relationship/identity-key {:db/unique :db.unique/identity}})
```

### 4.3 Composite Key Generation

```clojure
(defn make-forward-key [subject-type subject-eid relation-name resource-type resource-eid]
  ;; Use vector - comparable and indexable
  [subject-type subject-eid relation-name resource-type resource-eid])

(defn make-reverse-key [resource-type resource-eid relation-name subject-type subject-eid]
  [resource-type resource-eid relation-name subject-type subject-eid])

;; When transacting a relationship:
(defn relationship-tx [subject relation resource]
  (let [subj-type (:type subject)
        subj-eid (:id subject)
        res-type (:type resource)
        res-eid (:id resource)]
    {:eacl.relationship/subject-type subj-type
     :eacl.relationship/subject subj-eid
     :eacl.relationship/relation-name relation
     :eacl.relationship/resource-type res-type
     :eacl.relationship/resource res-eid
     :eacl.relationship/forward-key (make-forward-key subj-type subj-eid relation res-type res-eid)
     :eacl.relationship/reverse-key (make-reverse-key res-type res-eid relation subj-type subj-eid)
     :eacl.relationship/identity-key (make-forward-key subj-type subj-eid relation res-type res-eid)}))
```

---

## 5. API Compatibility Matrix

### IAuthorization Methods

| Method | Datomic | DataScript | Notes |
|--------|---------|------------|-------|
| `can?` (3-arity) | ✅ | ✅ | Same semantics |
| `can?` (4-arity) | ✅ | ✅ | Ignore consistency param in DS |
| `can?` (map) | ✅ | ✅ | Same semantics |
| `read-schema` | ⚠️ | ✅ | Query Relations + Permissions |
| `write-schema!` | ❌ | ✅ | Transact schema entities |
| `read-relationships` | ✅ | ✅ | Same query interface |
| `write-relationships!` | ✅ | ✅ | Same batch interface |
| `create-relationships!` | ✅ | ✅ | Convenience wrapper |
| `delete-relationships!` | ✅ | ✅ | Retract entities |
| `lookup-resources` | ✅ | ✅ | Paginated results |
| `count-resources` | ✅ | ✅ | With cursor support |
| `lookup-subjects` | ✅ | ✅ | Reverse traversal |
| `expand-permission-tree` | ❌ | ❌ | Not implemented in either |

### Behavioral Differences

| Behavior | Datomic | DataScript | Resolution |
|----------|---------|------------|------------|
| Consistency | `:fully-consistent` only | Always consistent | Ignore param in DS |
| ZedToken | Returns basis-t | Return nil or synthetic | Return nil |
| ID Resolution | `d/entid` with lookup-refs | Query or direct eid | Support both |
| Transaction Results | Full tx-report | Basic tx-report | Return minimal |

---

## 6. Implementation Phases

### Phase 1: Core Infrastructure (Week 1)

1. **Create project structure**
   - Set up `deps.edn` with DataScript dependency
   - Configure shadow-cljs for browser builds
   - Create `.cljc` files for shared code

2. **Port `eacl.core` to `.cljc`**
   - Move `IAuthorization` protocol
   - Move records: `Relationship`, `RelationshipUpdate`, `SpiceObject`
   - Conditional reader macros for JVM/JS differences (likely none)

3. **Port `eacl.lazy-merge-sort` to `.cljc`**
   - Test lazy-seq behavior in ClojureScript
   - Add fallback eager implementation if needed

4. **Implement simple LRU cache**
   - Pure ClojureScript implementation
   - Same API as core.cache usage

### Phase 2: DataScript Schema & Base Implementation (Week 2)

1. **Create `eacl.datascript.schema`**
   - Define DataScript schema (see Section 4)
   - Implement `Relation`, `Permission`, `Relationship` transaction builders
   - Test schema creation and basic queries

2. **Create `eacl.datascript.impl.base`**
   - Port relation/permission/relationship builders from `eacl.datomic.impl.base`
   - Adapt for composite key generation

3. **Implement composite key helpers**
   - `make-forward-key` / `make-reverse-key` functions
   - Transaction builder that computes keys automatically
   - Test with sample data

### Phase 3: Permission Engine (Week 3)

1. **Port `eacl.datascript.impl.indexed`**
   - `find-permission-defs` using DataScript queries
   - `relation-datoms` adapted for DataScript
   - `calc-permission-paths` (should work with minimal changes)
   - `get-permission-paths` with ClojureScript LRU cache

2. **Implement traversal functions**
   - `traverse-single-path`
   - `traverse-permission-path-via-subject`
   - `traverse-permission-path-reverse`
   - Use `d/index-range` directly (it works in DataScript!)

3. **Test permission calculations**
   - Verify path calculation matches Datomic
   - Test cycle detection
   - Test arrow permissions (relation → relation, relation → permission)

### Phase 4: Client Implementation (Week 4)

1. **Create `eacl.datascript.core`**
   - `DataScriptomic` record implementing `IAuthorization`
   - ID coercion functions (simpler than Datomic version)
   - Cursor transformation

2. **Implement all IAuthorization methods**
   - `can?` - boolean permission check
   - `lookup-resources` - paginated forward traversal
   - `lookup-subjects` - paginated reverse traversal
   - `count-resources` - counting with cursors
   - `read-relationships` / `write-relationships!`

3. **Create `make-client` factory**
   - Options for ID coercion
   - Connection wrapper

### Phase 5: Testing & Validation (Week 5)

1. **Port test fixtures**
   - Convert `eacl.datomic.fixtures` to DataScript format
   - Verify entity creation and relationships

2. **Port test suite**
   - Convert `eacl.datomic.impl.indexed-test` to DataScript
   - Run same test cases, expect same results

3. **Cross-validation**
   - Run same queries against Datomic and DataScript
   - Compare results programmatically
   - Document any differences

### Phase 6: Browser Demo (Week 6)

1. **Create demo application**
   - Simple Reagent/Re-frame UI
   - Load sample schema and relationships
   - Interactive permission queries

2. **Performance profiling**
   - Measure query times with varying dataset sizes
   - Identify bottlenecks
   - Optimize hot paths

3. **Documentation**
   - API reference
   - Usage examples
   - Migration guide from Datomic

---

## 7. Module Structure

```
src/
├── eacl/
│   ├── core.cljc                    # SHARED: Protocol, records
│   ├── lazy_merge_sort.cljc         # SHARED: Merge algorithms
│   │
│   ├── datomic/                     # EXISTING: Datomic implementation
│   │   ├── core.clj
│   │   ├── schema.clj
│   │   ├── impl.clj
│   │   └── impl/
│   │       ├── base.clj
│   │       └── indexed.clj
│   │
│   └── datascript/                  # NEW: DataScript implementation
│       ├── core.cljs                # DataScriptomic client
│       ├── schema.cljs              # DataScript schema
│       ├── impl.cljs                # Public API delegates
│       └── impl/
│           ├── base.cljs            # Transaction builders
│           ├── indexed.cljs         # Permission engine
│           └── cache.cljs           # LRU cache

test/
├── eacl/
│   ├── core_test.cljc               # SHARED: Protocol tests
│   ├── lazy_merge_sort_test.cljc    # SHARED: Merge tests
│   │
│   ├── datomic/                     # EXISTING
│   │   └── impl/indexed_test.clj
│   │
│   └── datascript/                  # NEW
│       ├── fixtures.cljs            # Test data
│       └── impl/indexed_test.cljs   # Permission tests
```

---

## 8. Index Strategy for DataScript

### 8.1 Good News: `d/index-range` Works! ✅

DataScript supports `d/index-range` with the same API as Datomic:

```clojure
(d/index-range db attr start end)
;; Returns part of :avet index between [_ attr start] and [_ attr end]
```

This means the core traversal logic can use `d/index-range` directly on indexed vector attributes.

### 8.2 The Real Challenge: No Tuple Attributes

Datomic auto-computes tuple values from `:db/tupleAttrs`. DataScript doesn't support this, so we must:

1. **Manually compute composite keys** when transacting
2. **Store as indexed vector attributes** (vectors are comparable in CLJS)

```clojure
;; When creating a relationship, compute the composite key:
{:eacl.relationship/subject-type :user
 :eacl.relationship/subject 123
 :eacl.relationship/relation-name :owner
 :eacl.relationship/resource-type :account
 :eacl.relationship/resource 456
 ;; Manually computed composite key (Datomic does this automatically)
 :eacl.relationship/forward-key [:user 123 :owner :account 456]}
```

### 8.3 Using `d/index-range` with Vector Keys

Since vectors are lexicographically comparable, `d/index-range` works naturally:

```clojure
;; Find all resources of type :account that user 123 owns
(d/index-range db
  :eacl.relationship/forward-key
  [:user 123 :owner :account 0]           ;; start (0 = min eid)
  [:user 123 :owner :account Long/MAX_VALUE]) ;; end
```

This is nearly identical to the Datomic code - just using our manually-computed key attribute.

### 8.4 Recommended Approach

1. **Store composite keys as vectors** - computed on transaction
2. **Use `d/index-range` directly** - it works!
3. **Mark composite key attrs as `:db/index true`**
4. **Consider `:db/unique :db.unique/identity`** for deduplication

---

## 9. Lazy Sequence Handling in JS

### 9.1 ClojureScript Lazy Sequences

ClojureScript does support `lazy-seq`, but with caveats:
- No parallel realization (single-threaded)
- Stack overflow risk with deep recursion
- Works fine for DataScript (all in-memory)

### 9.2 Preserving Lazy Behavior

The `eacl.lazy-merge-sort` namespace can remain unchanged:

```clojure
;; This works in ClojureScript!
(lazy-seq
  (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
    (let [heads (map first non-empty-seqs)
          min-val (apply min heads)]
      (cons min-val (lazy-merge-dedupe-sort ...)))))
```

### 9.3 Trampoline for Deep Recursion

If stack overflow occurs, convert to trampoline:

```clojure
(defn lazy-merge-dedupe-sort-trampolined [lowest lazy-colls]
  (trampoline
    (fn step [lowest colls]
      (if-let [non-empty-seqs (seq (filter seq colls))]
        #(step ...)  ;; Return thunk for trampoline
        nil))))
```

### 9.4 Recommendation

Start with existing lazy-seq implementation. Monitor for stack issues. Convert to trampolined or iterative if needed.

---

## 10. Testing Strategy

### 10.1 Test Categories

1. **Unit Tests** - Individual function behavior
2. **Integration Tests** - Full permission flows
3. **Cross-Validation** - Compare Datomic vs DataScript results
4. **Property Tests** - Generative testing for edge cases

### 10.2 Port Priority (High → Low)

1. `indexed_test.clj` - Core permission engine tests
2. `fixtures.clj` - Test data setup
3. `config_test.clj` - ID coercion tests
4. `schema_test.clj` - Schema validation

### 10.3 Cross-Validation Framework

```clojure
(defn cross-validate [datomic-conn datascript-conn query]
  (let [datomic-result (eacl.datomic.core/lookup-resources datomic-conn query)
        datascript-result (eacl.datascript.core/lookup-resources datascript-conn query)]
    (is (= (:data datomic-result) (:data datascript-result))
        (str "Results differ for query: " query))))
```

### 10.4 Browser Testing

Use `karma` or `playwright` for headless browser tests:

```clojure
;; shadow-cljs.edn
{:builds
 {:test {:target :karma
         :output-to "target/karma-test.js"}}}
```

---

## 11. Performance Considerations

### 11.1 Expected Dataset Sizes (Browser)

| Metric | Target | Stretch |
|--------|--------|---------|
| Relationships | 10,000 | 100,000 |
| Entities | 5,000 | 50,000 |
| Permission paths | 50 | 200 |
| Query latency | <10ms | <50ms |

### 11.2 Optimization Opportunities

1. **Permission Path Caching** - Already implemented, port the cache
2. **Index Pre-computation** - Build sorted-map indices on load
3. **Query Result Caching** - Cache `can?` results for repeated checks
4. **Web Workers** - Move heavy computation off main thread

### 11.3 Memory Profiling

Use Chrome DevTools to profile:
- DataScript db size
- Index structures
- Lazy sequence retention

---

## 12. Future: Sync from Datomic

*Deferred to future phase, but worth considering in design.*

### 12.1 Sync Requirements

1. **Initial Load** - Bulk transfer of relationships
2. **Incremental Updates** - Real-time sync of changes
3. **Conflict Resolution** - Handle concurrent modifications

### 12.2 Potential Approaches

1. **Export/Import** - Periodic full dumps
2. **Transaction Log** - Stream Datomic tx-report to DataScript
3. **Event Sourcing** - Publish relationship changes as events

### 12.3 Design Implications

- Keep entity IDs stable (use `:eacl/id` consistently)
- Schema must be identical between systems
- Consider adding `:eacl/sync-version` for conflict detection

---

## 13. Open Questions

### 13.1 For Approval

1. **Vector vs String composite keys?**
   - Vectors are more idiomatic but larger
   - Strings are compact but need parsing

2. **Eager vs Lazy pagination?**
   - Lazy works but may have gotchas
   - Eager with limits is simpler

3. **Shared code percentage target?**
   - 100% for `eacl.core`?
   - 80%+ for merge-sort?

4. **Demo scope?**
   - Just API validation?
   - Full interactive UI?

### 13.2 Technical Decisions Needed

1. **Shadow-cljs vs Figwheel?**
   - Shadow-cljs recommended for npm interop

2. **State management for demo?**
   - Re-frame vs Reagent atoms?

3. **Build targets?**
   - Browser only?
   - Node.js as well?

---

## Appendix: Code Examples

### A.1 DataScript Connection Setup

```clojure
(ns eacl.datascript.core
  (:require [datascript.core :as d]
            [eacl.datascript.schema :as schema]))

(defn create-conn []
  (d/create-conn schema/datascript-schema))

(defn bootstrap-schema! [conn relations permissions]
  (d/transact! conn
    (concat
      (map schema/relation-tx relations)
      (map schema/permission-tx permissions))))
```

### A.2 Permission Check Example

```clojure
(defn can? [db subject permission resource]
  (let [{subject-type :type subject-id :id} subject
        {resource-type :type resource-id :id} resource
        paths (get-permission-paths db resource-type permission)]
    (boolean
      (some (fn [path]
              (check-path db subject-type subject-id path resource-type resource-id))
            paths))))
```

### A.3 Lookup Resources Example

```clojure
(defn lookup-resources [db {:keys [subject permission resource/type limit cursor]}]
  (let [paths (get-permission-paths db type permission)
        all-results (->> paths
                         (mapcat #(traverse-path db subject % type))
                         (distinct)
                         (sort))]
    {:data (take limit (drop-while #(<= % (:id cursor)) all-results))
     :cursor {:resource {:type type :id (last (take limit all-results))}}}))
```

### A.4 Simple LRU Cache

```clojure
(defn make-lru-cache [max-size]
  (atom {:entries {} :order []}))

(defn cache-get [cache key]
  (get-in @cache [:entries key]))

(defn cache-put! [cache key value]
  (swap! cache
    (fn [{:keys [entries order]}]
      (let [new-order (conj (filterv #(not= % key) order) key)
            evict? (> (count new-order) max-size)
            final-order (if evict? (vec (rest new-order)) new-order)
            evicted-key (when evict? (first new-order))
            final-entries (-> entries
                              (assoc key value)
                              (cond-> evicted-key (dissoc evicted-key)))]
        {:entries final-entries :order final-order}))))
```

---

## Summary

This plan provides a clear path to porting EACL to ClojureScript/DataScript while maintaining API compatibility. The main challenges are:

1. **Index traversal** - Solved with composite vector keys + filtering
2. **Lazy sequences** - ClojureScript lazy-seq should work
3. **Caching** - Simple LRU implementation
4. **Testing** - Port existing test suite for validation

The 6-week timeline allows for careful implementation with comprehensive testing. The modular structure enables future optimizations without API changes.

**Next Steps:**
1. Review and approve this plan
2. Set up project structure with shadow-cljs
3. Begin Phase 1: Core Infrastructure

---

*Awaiting approval before implementation begins.*
