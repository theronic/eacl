# EACL Bug Report

**Date:** 2026-02-05
**Scope:** Full source review of the EACL ReBAC authorization library
**Reviewer:** Automated code review (Claude)
**Version:** v6 (current `main` branch)

---

## Summary

A line-by-line review of all EACL source files identified **12 bugs**, ranging from a misuse of the Java `Exception` constructor that produces wrong errors at runtime, to a no-op assertion that silently accepts any consistency value, to fragile index-range sentinels that could miss valid entity types. Several findings are in dead code that would crash if loaded.

| Severity   | Count |
|------------|-------|
| Bug        | 7     |
| Dead Code  | 3     |
| Performance| 2     |

---

## BUG-01: Broken `Exception` Constructor in `:create` Duplicate Detection [BUG]

**Location:** `src/eacl/datomic/impl.clj:165`

**Code:**
```clojure
:create
(if-let [rel-id (find-one-relationship-id db relationship)]
  (throw (Exception. ":create relationship conflicts with existing: " rel-id))
  (tx-relationship relationship))
```

**Problem:** `java.lang.Exception(String, Throwable)` is the only two-argument constructor. The second argument `rel-id` is a `Long` (Datomic entity ID), not a `Throwable`. This will throw a `ClassCastException` at the point where EACL tries to report the duplicate, replacing the intended "duplicate relationship" error with an unrelated `ClassCastException`.

The string `:create relationship conflicts with existing: ` and `rel-id` are NOT concatenated — they are passed as separate constructor arguments.

**Expected behavior:** Should produce an error message like `":create relationship conflicts with existing: 12345678"`.

**Actual behavior:** Throws `ClassCastException: java.lang.Long cannot be cast to java.lang.Throwable`.

**Fix:** Use `(throw (ex-info "Relationship already exists" {:existing-id rel-id :relationship relationship}))` or `(throw (Exception. (str ":create relationship conflicts with existing: " rel-id)))`.

---

## BUG-02: Always-True Assertion on Consistency Parameter [BUG]

**Location:** `src/eacl/datomic/core.clj:197`

**Code:**
```clojure
(can? [this {:as demand :keys [subject permission resource consistency]}]
  (assert (= consistency))
  (spiceomic-can? (d/db conn) opts subject permission resource consistency))
```

**Problem:** `(= consistency)` is Clojure's arity-1 `=`, which always returns `true` regardless of the value. This assertion is a no-op. It was likely intended to be:
```clojure
(assert (= consistency/fully-consistent consistency))
```

The downstream `spiceomic-can?` at `core.clj:111` does have the correct assertion:
```clojure
(assert (= consistency/fully-consistent consistency) "EACL only supports consistency/fully-consistent at this time.")
```

So the bug doesn't cause wrong authorization decisions — the inner function catches it. But the outer assertion is misleading dead code that suggests consistency is being validated when it isn't.

**Impact:** Low — caught by inner assertion. But if `consistency` is `nil` (omitted by caller), the outer assertion passes silently, and the inner assertion throws a confusing error without context about the original call.

---

## BUG-03: Fragile `:a`/`:z` Keyword Range Sentinels in `relation-datoms` [BUG]

**Location:** `src/eacl/datomic/impl/indexed.clj:53-54`

**Code:**
```clojure
(defn relation-datoms [db resource-type relation-name]
  (if (and resource-type relation-name)
    (let [start-tuple [resource-type relation-name :a]
          end-tuple   [resource-type relation-name :z]]
      (d/index-range db :eacl.relation/resource-type+relation-name+subject-type start-tuple end-tuple))
    ...))
```

**Problem:** The tuple index `:eacl.relation/resource-type+relation-name+subject-type` has three components. To scan all values of the third component (subject-type keyword), the code uses `:a` and `:z` as lower and upper bounds. Datomic compares keywords lexicographically by name.

This breaks for any subject-type keyword whose name sorts outside the range `["a", "z"]`:

- Keywords starting with digits: `:123_service` — sorts before `:a`
- Keywords starting with uppercase: `:User` — sorts before `:a` (uppercase ASCII < lowercase)
- Keywords starting with underscore: `:_internal` — sorts before `:a`

In practice, EACL uses lowercase alpha types (`:user`, `:server`, `:account`), so this works today. But it's a latent bug that will silently produce wrong results (missing relation definitions, leading to incorrect `can?` results) if a type name falls outside this range.

**Fix:** Use a proper Datomic 2-component prefix scan, or use sentinel values that truly bracket all keywords (e.g., a keyword with the minimum possible string value and one beyond the maximum).

---

## BUG-04: Multi-Type Relation Arrow Resolution Uses Only First Type [BUG]

**Location:** `src/eacl/datomic/spice_parser.clj:527-532`

**Code:**
```clojure
(defn- collect-schema-info [definitions]
  (reduce-kv
    (fn [acc res-type {:keys [relations permissions]}]
      (let [relation-types (into {}
                             (for [[rel-name type-refs] relations
                                   :let [first-ref (first type-refs)]
                                   :when first-ref]
                               [rel-name (:type first-ref)]))]
        ...))
    {} definitions))
```

**Problem:** When a relation has multiple subject types (e.g., `relation viewer: user | group`), only the first type (`user`) is stored in `relation-types`. When resolving arrows like `viewer->some_permission`, the target is classified by checking only against the first type's schema.

If the arrow target (`some_permission`) exists as a relation on `group` but as a permission on `user`, the classification would be wrong — it would be classified based on `user` (first type) and the wrong Permission spec would be generated.

**Impact:** Only affects schemas that combine multi-type relations with arrow permissions targeting those relations. Uncommon in practice, but produces silently wrong permission definitions when it occurs.

**Fix:** `relation-types` should map to a vector of all types, and arrow resolution should check all of them (or at minimum, ensure consistent classification across all types).

---

## BUG-05: `traverse-permission-path` Doesn't Use Cursor in Index Start Tuple [BUG]

**Location:** `src/eacl/datomic/impl/indexed.clj:335`

**Code:**
```clojure
:relation
(when (= subject-type (:subject-type path))
  (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
        start-tuple [subject-type subject-eid (:name path) resource-type 0]  ;; <-- hardcoded 0
        end-tuple   [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]]
    (->> (d/index-range db tuple-attr start-tuple end-tuple)
      (map (fn [datom]
             (let [resource-eid (extract-resource-id-from-rel-tuple-datom datom)]
               (when (> resource-eid (or cursor-eid 0))  ;; <-- cursor applied in-memory
                 [resource-eid path]))))
      (filter some?))))
```

**Problem:** The start tuple always uses `0` for the resource-eid component, then filters by cursor in memory. Compare with `traverse-permission-path-via-subject` at line 425 which correctly uses `(or cursor-eid 0)` in the start tuple:

```clojure
start-tuple [subject-type subject-eid (:name path) resource-type (or cursor-eid 0)]
```

**Impact:** `traverse-permission-path` scans the full index range from 0 and discards results before the cursor in memory. This is functionally correct but does unnecessary I/O. For large datasets with cursors deep into the result set, this causes O(cursor-position) wasted work on each page.

This function is called by `traverse-permission-path-via-subject` for `:self-permission` paths (line 436) and by arrow-to-permission traversal (line 384, 476), so the inefficiency compounds for complex permission graphs.

---

## BUG-06: `count-resources` Double-Materializes Lazy Sequence [PERFORMANCE]

**Location:** `src/eacl/datomic/impl/indexed.clj:688-696`

**Code:**
```clojure
(defn count-resources [db {:as query ...}]
  (let [merged-results  (lazy-merged-lookup-resources db query)
        limited-results (if (>= limit 0) (take limit merged-results) merged-results)
        resources       (map #(spice-object resource-type %) limited-results)
        last-resource   (last resources)      ;; <-- forces full realization of `resources`
        next-cursor     {:resource (or last-resource (:resource cursor))}]
    {:count  (count limited-results)           ;; <-- traverses `limited-results` again
     :limit  limit
     :cursor next-cursor}))
```

**Problem:** `(last resources)` forces full realization of the `resources` lazy sequence (and transitively, `limited-results`). Then `(count limited-results)` traverses `limited-results` again. While the elements are cached in memory from the first pass, this is an O(n) traversal that allocates `SpiceObject` records (via `spice-object`) only to count them and compute the cursor.

For counting, you don't need to allocate `SpiceObject` wrappers for every result. The count could be computed directly from `merged-results`.

**Fix:** Realize `limited-results` into a vector once, then derive both count and cursor from it:
```clojure
(let [result-vec (vec limited-results)
      cnt        (count result-vec)
      last-eid   (peek result-vec)]
  {:count cnt :limit limit :cursor {:resource (when last-eid (spice-object resource-type last-eid))}})
```

---

## BUG-07: Arrow-to-Permission Intermediate Traversal Passes `nil` Cursor [PERFORMANCE]

**Location:** `src/eacl/datomic/impl/indexed.clj:476-480`

**Code:**
```clojure
;; Arrow to permission
(let [target-permission    (:target-permission path)
      intermediate-results (traverse-permission-path db subject-type subject-eid
                             target-permission
                             intermediate-type nil updated-visited) ; this nil is expensive. how to avoid?
      intermediate-eids    (map first intermediate-results)]
```

**Problem:** The code itself notes this is expensive. When traversing arrow-to-permission paths, the intermediate lookup always starts from the beginning (nil cursor) even if we're paginating. This means on page N, the system re-enumerates all intermediates from the start, then filters resources by cursor.

The same pattern appears at `indexed.clj:384` in `traverse-permission-path`.

**Impact:** For deep permission graphs with many intermediates, pagination becomes O(total-intermediates) per page instead of O(page-size). The comment in the code acknowledges this.

---

## BUG-08: Dead `build-slow-rules` Reference Will Crash on Load [DEAD CODE]

**Location:** `src/eacl/datomic/rules.clj:211`

**Code:**
```clojure
(def slow-lookup-rules (build-slow-rules :eacl/type))
```

**Problem:** `build-slow-rules` is commented out at line 102. If the `eacl.datomic.rules` namespace is loaded (e.g., via `require`), this `def` will throw `CompilerException: Unable to resolve symbol: build-slow-rules`.

Currently, no active code requires `eacl.datomic.rules` (only `eacl.datomic.rules.optimized` is used), so this is latent.

---

## BUG-09: Dead `direct-match-datoms-in-relationship-index` Uses Wrong Datomic API [DEAD CODE]

**Location:** `src/eacl/datomic/impl/indexed.clj:410-414`

**Code:**
```clojure
(defn direct-match-datoms-in-relationship-index
  [db subject-type subject-eid relation-name resource-type resource-eid]
  (d/datoms db
    :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
    subject-type subject-eid relation-name resource-type resource-eid))
```

**Problem:** `d/datoms` expects the second argument to be an index selector (`:eavt`, `:aevt`, `:avet`, or `:vaet`). Passing an attribute keyword as the index selector is incorrect Datomic API usage. This function would throw an `IllegalArgumentException` if called.

This function is not called from any active code.

---

## BUG-10: Dead `analyze-definition` Filters Map Entries by `:name` Key [DEAD CODE]

**Location:** `src/eacl/datomic/spice_parser.clj:231-232`

**Code:**
```clojure
(defn analyze-definition [schema-str def-name]
  (let [parsed (parse-schema schema-str)]
    (when-not (insta/failure? parsed)
      (let [definitions (extract-definitions (rest parsed))
            target-def  (first (filter #(= def-name (:name %)) definitions))]
        ...))))
```

**Problem:** `extract-definitions` returns a map `{"user" {...} "account" {...}}`. When you `filter` over a map, the elements are `MapEntry` pairs like `["user" {:relations ... :permissions ...}]`. Calling `(:name %)` on a `MapEntry` returns `nil`, so `(= def-name nil)` is always false, and `target-def` is always `nil`.

This function is only used in the `demo` function and `comment` blocks, so it has no production impact.

**Fix:** Should be `(filter #(= def-name (first %)) definitions)` or `(get definitions def-name)`.

---

## BUG-11: Dead `traverse-single-path` Applies Cursor to Intermediates [DEAD CODE]

**Location:** `src/eacl/datomic/impl/indexed.clj:208-220`

**Code:**
```clojure
:arrow
(let [intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
      intermediate-start      [subject-type subject-eid path-via target-relation-name cursor-eid]
      intermediate-end        [subject-type subject-eid path-via target-relation-name Long/MAX_VALUE]
      intermediate-eids       (->> (d/index-range db intermediate-tuple-attr intermediate-start intermediate-end)
                                (map extract-resource-id-from-rel-tuple-datom)
                                (filter (fn [resource-eid]
                                          (if cursor-eid
                                            (> resource-eid cursor-eid)
                                            true))))]
```

**Problem:** `cursor-eid` (a resource entity ID used for pagination) is used to filter intermediate entities. This incorrectly skips intermediates whose entity IDs happen to be less than the cursor value, even though the cursor position is about final resources, not intermediates.

Example: If cursor-eid is 5000 (a server), an account with eid 3000 would be skipped, and all servers reachable through that account would be missed from results.

This function is not called from any active code path — `traverse-permission-path-via-subject` (which correctly uses `0` for intermediate start tuples) is used instead. But if anyone calls `traverse-single-path` directly, they would get incorrect pagination results for arrow permissions.

---

## BUG-12: `extract-resource-id-from-rel-tuple-datom` Used on Reverse Tuples [NAMING]

**Location:** `src/eacl/datomic/impl/indexed.clj:283-284`

**Code:**
```clojure
intermediates (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                (map extract-resource-id-from-rel-tuple-datom)) ; Extract subject (intermediate) eid
```

**Problem:** `extract-resource-id-from-rel-tuple-datom` is named for forward tuples (`subject-type+subject+relation-name+resource-type+resource`) where position 4 is `resource-eid`. When called on reverse tuples (`resource-type+resource+relation-name+subject-type+subject`), position 4 is `subject-eid`.

Both tuples have 5 elements and the desired value is always at position 4, so this is **functionally correct**. But the function name is misleading — the comment acknowledges it extracts a "subject (intermediate) eid" despite the function being named `extract-resource-id-...`.

This pattern appears in multiple places throughout `can?`, `traverse-permission-path-reverse`, and other functions where the same extractor is reused for both tuple orientations.

**Impact:** No functional impact. Naming confusion makes the code harder to audit and maintain.

---

## Cross-Reference with Security Report

Several bugs identified here overlap with findings in the security audit (`2026-02-05-security-audit.md`). The following security findings are NOT repeated in this bug report:

- **EACL-SEC-01:** Missing schema validation on relationship writes
- **EACL-SEC-02:** Permission path cache not invalidated on schema changes
- **EACL-SEC-03:** Unbounded self-permission recursion in `can?`
- **EACL-SEC-04:** No schema cycle prevention at write time
- **EACL-SEC-05:** Subject/resource type claims not verified

These are authorization-correctness issues documented in the security report.
