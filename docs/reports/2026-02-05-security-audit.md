# EACL Security Audit Report

**Date:** 2026-02-05
**Scope:** Full source audit of the EACL ReBAC authorization library
**Auditor:** Automated security review (Claude)
**Version:** v6 (current `main` branch)
**Status:** EACL is in production at CloudAfrica and under active development

---

## Executive Summary

EACL is a Relationship-Based Access Control (ReBAC) library implementing a SpiceDB-compatible permission model on top of Datomic. The audit identified **14 findings** across four severity tiers:

| Severity | Count |
|----------|-------|
| High     | 5     |
| Medium   | 5     |
| Low      | 4     |

The most impactful findings relate to **missing relationship-schema enforcement on write** (allowing arbitrary relationship creation that bypasses the permission model), a **global permission-path cache that is not invalidated on schema changes** (leading to stale authorization decisions), and **unbounded recursion in `can?` for cyclic self-permissions** (potential denial of service).

---

## Findings

### EACL-SEC-01: Relationships Can Be Created Without Schema Validation [HIGH]

**Location:** `src/eacl/datomic/impl.clj:144-152` (`tx-relationship`), `src/eacl/datomic/impl.clj:156-174` (`tx-update-relationship`)

**Description:** When a relationship is written via `write-relationships!`, `create-relationships!`, or `create-relationship!`, the system does not validate that the `(subject-type, relation-name, resource-type)` triple corresponds to a `Relation` defined in the schema. Any caller with access to the EACL client can create relationships with arbitrary types and relation names.

The test suite itself acknowledges this at `test/eacl/datomic/impl/indexed_test.clj:371`:
```clojure
(Relationship (->user :test/user1) :shared_admin (->server :test/server2))
;; comment: "this shouldn't be working. no schema for it."
```

**Impact:** An attacker (or buggy code) with write access to the EACL client can inject relationships that grant unintended permissions. For example, creating a `(:user, :super_admin, :platform)` relationship for an arbitrary user would grant them platform-wide super-admin access. The entire permission model is undermined if write access is not independently protected.

**Recommendation:** Validate every relationship write against the schema. Before transacting, confirm that a matching `Relation` entity exists for the given `(resource-type, relation-name, subject-type)` triple. Reject writes that do not match a defined relation.

---

### EACL-SEC-02: Permission Path Cache Not Invalidated on Schema Changes [HIGH]

**Location:** `src/eacl/datomic/impl/indexed.clj:87-91` (`permission-paths-cache`), `src/eacl/datomic/schema.clj:412-451` (`write-schema!`)

**Description:** `permission-paths-cache` is a global `atom` holding an LRU cache keyed by `[resource-type permission-name]`. The cache does not include the database basis-t (point-in-time) in its key. When `write-schema!` transacts schema changes, it never calls `evict-permission-paths-cache!`. This means:

1. After adding a new permission path, `can?` may return `false` because it uses the cached (stale) paths that don't include the new definition.
2. After removing a permission path, `can?` may return `true` because the removed path is still in cache.

**Impact:** Authorization decisions may be based on an outdated permission schema. In multi-tenant systems or during schema migrations, this creates a window where permissions are incorrectly evaluated. The cache has a 1000-entry LRU threshold, so stale entries persist until evicted by pressure from new entries.

**Recommendation:** Call `evict-permission-paths-cache!` at the end of `write-schema!`. Alternatively, incorporate the database basis-t into the cache key so that each DB snapshot resolves its own permission paths.

---

### EACL-SEC-03: Unbounded Recursion for Self-Permissions in `can?` [HIGH]

**Location:** `src/eacl/datomic/impl/indexed.clj:266-269`

**Description:** The `can?` function handles `:self-permission` paths by recursively calling itself:

```clojure
:self-permission
(let [target-permission (:target-permission path)]
  (can? db subject target-permission resource))
```

Unlike `calc-permission-paths` (which tracks `visited-perms` to detect cycles), `can?` has no cycle detection for self-permission recursion. If the schema contains:

```
permission a = b
permission b = a
```

Then `can?` will recurse infinitely: checking `a` leads to `b`, which leads back to `a`, etc. While `calc-permission-paths` would detect this cycle when building paths, the `can?` function takes a different code path for `:self-permission` that bypasses that protection.

**Impact:** A cyclic self-permission schema causes `StackOverflowError` on any `can?` check that traverses the cycle. This is a denial-of-service vulnerability exploitable by anyone who can write schema.

**Recommendation:** Add a `visited-permissions` set parameter to the `can?` function (defaulting to `#{}`). Before recursing on a self-permission, check if the target permission has already been visited, and return `false` if so. Alternatively, prevent cyclic self-permissions at schema write time.

---

### EACL-SEC-04: No Schema Cycle Prevention at Write Time [HIGH]

**Location:** `src/eacl/datomic/impl/base.clj:48`, `src/eacl/datomic/schema.clj:271-374`

**Description:** The `Permission` function's docstring explicitly states: *"Note that EACL does not detect or prevent cycles in schema (big todo!)."* The `validate-schema-references` function in `schema.clj` validates that referenced relations and permissions exist, but does not perform cycle detection.

Cyclic schemas can be constructed:
- Direct cycle: `permission a = b`, `permission b = a`
- Indirect cycle via arrows: `server/view -> account/admin -> server/view`

The test at `indexed_test.clj:1054-1080` (`reproduce-infinite-recursion-test`) demonstrates this. While `calc-permission-paths` has cycle detection at runtime, this is a defensive measure rather than a proper fix.

**Impact:** An attacker who can write schema can introduce cycles that cause degraded performance (extra traversal before cycle detection triggers), denial of service via `can?` self-permission recursion (see EACL-SEC-03), and potentially confusing authorization behavior.

**Recommendation:** Add cycle detection to `validate-schema-references` or `write-schema!`. Build a directed graph of permission dependencies and check for cycles before transacting. Reject schemas with cycles.

---

### EACL-SEC-05: Subject/Resource Type Claims Not Verified Against Database [HIGH]

**Location:** `src/eacl/datomic/core.clj:104-123` (`spiceomic-can?`)

**Description:** When `can?` is called, the subject and resource types are taken directly from the caller-supplied maps (`:type` field). The code at `core.clj:117` has a comment:

```clojure
; Note: we do not check types here, but we should.
```

The system trusts the caller's claim about what type an entity is. EACL does not verify that the entity at the given ID actually has relationships consistent with the claimed type.

Since EACL encodes types into relationship tuple indices, the tuple lookups will naturally constrain results to the claimed type. This means type confusion is more likely to cause false negatives (failing closed) than false positives. However, if an entity participates in relationships under multiple types (which is possible since there's no uniqueness constraint on entity-to-type mapping), a caller could choose whichever type yields the most favorable permission outcome.

**Impact:** In multi-type scenarios, callers could potentially influence authorization outcomes by claiming a different type for a subject or resource. The practical exploitability depends on whether entities actually have relationships under multiple types in the target deployment.

**Recommendation:** Validate that the claimed type matches the entity's actual type. One approach: store and check `:eacl/type` on entities. Alternatively, document that callers are responsible for type accuracy and that multi-type entities are unsupported.

---

### EACL-SEC-06: No Authorization Controls on Schema and Relationship Writes [MEDIUM]

**Location:** `src/eacl/datomic/core.clj:186-240` (`Spiceomic` record)

**Description:** The `IAuthorization` protocol exposes `write-schema!`, `write-relationships!`, `create-relationships!`, and `delete-relationships!` without any authorization checks. Any code holding a reference to the EACL client can:

- Modify the permission schema (add/remove relations and permissions)
- Create relationships granting arbitrary access
- Delete relationships revoking access

**Impact:** If the EACL client reference is accessible to untrusted code (e.g., exposed via an API without middleware authorization), attackers can directly modify the authorization graph.

**Recommendation:** Either implement authorization checks within the write methods (e.g., require a "schema admin" permission), or clearly document that the EACL client must be kept in a trusted boundary and never exposed directly to untrusted callers.

---

### EACL-SEC-07: Broken Exception in `:create` Duplicate Detection [MEDIUM]

**Location:** `src/eacl/datomic/impl.clj:164-166`

**Description:** The `:create` operation in `tx-update-relationship` throws an `Exception` on duplicate relationships:

```clojure
:create
(if-let [rel-id (find-one-relationship-id db relationship)]
  (throw (Exception. ":create relationship conflicts with existing: " rel-id))
  (tx-relationship relationship))
```

The `Exception.` constructor in Clojure/Java does not concatenate multiple arguments into a message string. The second argument (`rel-id`) is interpreted as a "cause" `Throwable`, not part of the message. Since `rel-id` is a `Long` (not a `Throwable`), this will throw a different `ClassCastException` or produce an unclear error, masking the actual duplicate.

**Impact:** Duplicate relationship detection fails to produce a useful error message. Callers may not correctly identify or handle duplicate creation attempts, potentially leading to silent failures in relationship management workflows.

**Recommendation:** Use `(str ...)` or `ex-info` to construct the error message:
```clojure
(throw (ex-info "Relationship already exists" {:existing-id rel-id :relationship relationship}))
```

---

### EACL-SEC-08: Missing Input Validation Asymmetry [MEDIUM]

**Location:** `src/eacl/datomic/impl/indexed.clj:529-543` (`lookup-resources`), `src/eacl/datomic/impl/indexed.clj:663-679` (`lookup-subjects`)

**Description:** `lookup-subjects` has a `:pre` assertion validating its resource input:
```clojure
{:pre [(:type resource) (:id resource)]}
```

However, `lookup-resources` has no equivalent `:pre` assertion on its subject input. Similarly, `spiceomic-can?` returns `false` for nil entity IDs instead of throwing, which silently treats "entity not found" as "permission denied."

**Impact:** Missing subject entities silently return empty results from `lookup-resources` instead of alerting the caller to a configuration or data issue. In `can?`, a typo in entity ID silently returns `false` rather than signaling an error, which could mask access issues during debugging.

**Recommendation:** Add `:pre` assertions to `lookup-resources` for subject validation. In `spiceomic-can?`, consider throwing when subject or resource entity IDs cannot be resolved (or at minimum, log a warning).

---

### EACL-SEC-09: TOCTOU Between Read and Write Operations [MEDIUM]

**Location:** `src/eacl/datomic/core.clj:90-102` (`spiceomic-write-relationships!`)

**Description:** `spiceomic-write-relationships!` calls `(d/db conn)` to get a point-in-time DB snapshot, uses it to look up existing relationships (for `:create` duplicate checking and `:delete` ID resolution), then transacts against the connection. Between the read and the transact, another transaction could:

1. Create the same relationship (making `:create`'s duplicate check pass incorrectly)
2. Delete the target relationship (making `:delete` retract a non-existent entity)

Datomic's unique identity constraint on the relationship tuple would catch duplicate creations at the transaction level, but the error would be a Datomic constraint violation rather than a clean EACL error. For deletes, a stale `rel-id` would produce a no-op retraction.

**Impact:** Low practical impact due to Datomic's MVCC and constraint enforcement, but the error messages produced in race conditions would be confusing and potentially leak internal details.

**Recommendation:** Use Datomic transaction functions or `:db/cas` for atomic check-and-write operations. At minimum, handle Datomic unique constraint violations gracefully in the `:create` path.

---

### EACL-SEC-10: Information Leakage in Error Messages [MEDIUM]

**Location:** Multiple locations

**Description:** Error messages and assertions include internal system details:

- `core.clj:141`: `"subject " (pr-str subject) " passed to lookup-resources does not exist with ident " (object-id->ident (:id subject))`
- `core.clj:165-166`: Entity details in assertion messages
- `schema.clj:271-374`: Full schema structure in validation errors
- `indexed.clj:116-117`: Permission definition details in cycle detection warnings

**Impact:** If these errors propagate to API responses or logs accessible to unauthorized parties, they reveal the internal permission structure, entity ID schemes, and authorization topology. This information aids attackers in crafting targeted privilege-escalation attacks.

**Recommendation:** Use internal error codes instead of detailed messages in exceptions that may reach external callers. Log full details at debug level only. Provide generic error messages (e.g., "Authorization check failed") at the API boundary.

---

### EACL-SEC-11: Unbounded Resource Enumeration [LOW]

**Location:** `src/eacl/datomic/impl/indexed.clj:529-543` (`lookup-resources`), `src/eacl/datomic/impl/indexed.clj:681-696` (`count-resources`)

**Description:** `lookup-resources` accepts `:limit -1` to return all results without pagination. `count-resources` also defaults to `:limit -1`. On a system with millions of permissioned entities (the documented target scale), this forces full materialization of lazy sequences.

**Impact:** A caller passing `:limit -1` on a large dataset could cause excessive memory consumption or prolonged query times, leading to denial of service.

**Recommendation:** Enforce a maximum limit (e.g., 10,000). Reject or clamp negative limits. Provide a streaming API for bulk enumeration if needed.

---

### EACL-SEC-12: Debug Logging of Authorization Details [LOW]

**Location:** Multiple log statements across `indexed.clj`, `core.clj`, `impl.clj`

**Description:** `log/debug` and `log/warn` calls include permission paths, entity IDs, relationship structures, and schema definitions. While debug-level logging is typically disabled in production, misconfigured log levels could expose this data.

**Impact:** Authorization topology leakage via log aggregation systems.

**Recommendation:** Audit all log statements to ensure no sensitive authorization details are logged at WARN level or above. For debug logging, use opaque identifiers rather than full entity details.

---

### EACL-SEC-13: Unused Code Increases Attack Surface [LOW]

**Location:** `src/eacl/datomic/impl/datalog.clj`, `src/eacl/datomic/rules.clj`, `src/eacl/datomic/rules/optimized.clj`

**Description:** The `datalog.clj` namespace contains an alternative `can?` and `lookup-subjects` implementation that is not used by the active system. The `rules.clj` and `rules/optimized.clj` namespaces contain both active and commented-out Datalog rules with known issues. These namespaces are compiled and accessible.

The `rules.clj:211` line `(def slow-lookup-rules (build-slow-rules :eacl/type))` calls a function `build-slow-rules` that is commented out, which would cause a compilation error if this namespace is loaded. This indicates the code is stale.

**Impact:** Accidental use of alternative implementations could introduce different (possibly less secure) authorization behavior. Dead code complicates security reviews.

**Recommendation:** Remove or archive unused namespaces. If kept for reference, add `:deprecated` metadata and remove from the classpath.

---

### EACL-SEC-14: Union-Only Permissions Cannot Express Deny Rules [LOW - Design]

**Description:** EACL intentionally restricts permission expressions to the union (`+`) operator only. The exclusion (`-`) and intersection (`&`) operators are parsed but rejected during validation. This is a documented design decision.

**Impact:** There is no way to express "deny" rules. Permissions are purely additive. To revoke access, the underlying relationship must be deleted. This means:

- Emergency access revocation requires finding and deleting the granting relationship(s)
- There is no way to grant broad access with specific exceptions (e.g., "all servers except production")
- Compliance scenarios requiring explicit deny policies cannot be implemented

**Recommendation:** This is a known limitation documented in the README. When exclusion support is added, ensure it is evaluated correctly (exclusion must be applied after union, following SpiceDB semantics) and that deny rules take precedence to prevent privilege escalation via union paths.

---

## Positive Security Observations

The audit also identified several security-positive design decisions:

1. **Immutable database snapshots:** Datomic's MVCC model ensures `can?` checks are evaluated against a consistent point-in-time snapshot, eliminating most TOCTOU issues during reads.

2. **Tuple index uniqueness constraints:** The Datomic schema enforces uniqueness on relationship tuples, preventing accidental duplicate relationships at the storage level.

3. **Atomic schema updates:** `write-schema!` computes deltas and transacts them atomically, with orphaned-relationship checks preventing accidental data loss.

4. **Schema validation on write:** `validate-schema-references` catches many invalid schema configurations (dangling references, missing relations) before they are transacted.

5. **Cycle detection in path computation:** `calc-permission-paths` tracks `visited-perms` to break cycles in the permission graph during path building.

6. **Fully consistent reads:** EACL enforces `fully-consistent` mode (no eventual consistency), eliminating a class of stale-read vulnerabilities common in distributed authorization systems.

---

## Recommended Priority

| Priority | Finding | Effort |
|----------|---------|--------|
| 1 | EACL-SEC-01: Schema validation on relationship writes | Medium |
| 2 | EACL-SEC-02: Cache invalidation on schema changes | Low |
| 3 | EACL-SEC-03: Cycle guard in `can?` self-permission | Low |
| 4 | EACL-SEC-04: Schema cycle detection at write time | Medium |
| 5 | EACL-SEC-07: Fix broken exception constructor | Low |
| 6 | EACL-SEC-05: Type verification for subjects/resources | Medium |
| 7 | EACL-SEC-08: Input validation parity | Low |
| 8 | EACL-SEC-10: Sanitize error messages | Low |
| 9 | EACL-SEC-06: Document write-access trust boundary | Low |
| 10 | EACL-SEC-11: Enforce max limit on enumeration | Low |

---

## Methodology

This audit was performed via static analysis of all source files in the EACL repository. The following files were reviewed in full:

- `src/eacl/core.clj` (95 lines)
- `src/eacl/datomic/core.clj` (308 lines)
- `src/eacl/datomic/impl.clj` (177 lines)
- `src/eacl/datomic/impl/base.clj` (133 lines)
- `src/eacl/datomic/impl/indexed.clj` (696 lines)
- `src/eacl/datomic/impl/datalog.clj` (~80 lines)
- `src/eacl/datomic/schema.clj` (451 lines)
- `src/eacl/datomic/spice_parser.clj` (642 lines)
- `src/eacl/datomic/rules.clj` (552 lines)
- `src/eacl/datomic/rules/optimized.clj` (426 lines)
- `src/eacl/lazy_merge_sort.clj` (342 lines)
- `src/eacl/spicedb/consistency.clj` (8 lines)
- All test files (~2,500 lines total)
- Schema definition file `test/eacl/fixtures.schema` (94 lines)
