# Plan: Cache Permission Paths

Ref: `docs/adr/011-optimize-get-permission-paths.md`

## Goal
Optimize `get-permission-paths` by caching the permission graph traversal results. This function is a hot path called on every query (~17ms overhead) and the schema is assumed to be static during runtime.

## Architecture

1.  **Namespace**: `eacl.datomic.impl.indexed`
2.  **Cache Library**: `org.clojure/core.cache`
3.  **Strategy**:
    -   Introduce `calc-permission-paths` to hold the original recursive logic.
    -   Introduce a `permission-paths-cache` atom holding a `core.cache` instance (e.g., LRU or TTL, likely LRU with a reasonable size since schema is small).
    -   Redefine `get-permission-paths` to:
        -   Check cache for key `[resource-type permission-name]`.
        -   If miss, call `calc-permission-paths` and update cache.
        -   (Note: `db` argument is effectively ignored for the cache key under the assumption of static schema, but must be passed to the calculation function).
    -   Implement `evict-permission-paths-cache!` to clear the cache when schema updates (to be used by future `write-schema!`).

## Implementation Steps

- [ ] **Step 1: Verify & Setup**
    - [ ] Confirm `org.clojure/core.cache` availability (Done).
    - [ ] Add require `[clojure.core.cache :as cache]` to `eacl.datomic.impl.indexed`.

- [ ] **Step 2: Define Cache**
    - [ ] Define `permission-paths-cache` atom initialized with `cache/lru-cache-factory` (threshold 1000 - ample for permissions).
    - [ ] Implement helper `flush-permission-paths-cache!` to reset the atom.

- [ ] **Step 3: Refactor `get-permission-paths`**
    - [ ] Rename existing `get-permission-paths` to `calc-permission-paths`.
    - [ ] Ensure recursive calls in `calc-permission-paths` call `calc-permission-paths` to avoid caching intermediate recursive steps (or call `get-permission-paths` if we want to cache intermediates too - likely unnecessary complexity for now, and `calc-permission-paths` builds the whole tree).
        -   *Analysis*: `get-permission-paths` returns a full tree of paths. Caching the top-level request `(type, perm)` is sufficient. Internal recursive calls can remain direct or call the cached version. Calling cached version internally is safer for consistency but might fill cache with intermediates. Given the graph size, caching intermediates is probably fine and potentially beneficial. Let's Stick to `calc-permission-paths` calling `calc-permission-paths` (passing `visited`) to avoid "visited" state pollution in cache keys.
    - [ ] Create new `get-permission-paths` (arity 3) that checks/updates `permission-paths-cache`.
    - [ ] Preserve arity 4 (with `visited`) in `calc-permission-paths` for recursion.

- [ ] **Step 4: Testing**
    - [ ] Add `test/eacl/datomic/impl/indexed_test.clj` test case:
        -   Measure call count to `calc-permission-paths` using `with-redefs`.
        -   Call `can?` or `get-permission-paths` multiple times.
        -   Assert `calc-permission-paths` is called only once per unique key.
    - [ ] Verify cache eviction works (call flush, check called again).

- [ ] **Step 5: Verification**
    - [ ] Run all tests to ensure no regression.

