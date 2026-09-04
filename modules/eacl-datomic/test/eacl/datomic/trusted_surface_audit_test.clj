(ns eacl.datomic.trusted-surface-audit-test
  "Pins the trusted-surface-hygiene deletions (11.1): the enumerated dead
  paths must not return, and the live survivors they were entangled with
  must still exist. Var probes use requiring-resolve so a reintroduced
  definition fails this suite even if nothing else calls it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(defn- var-absent?
  [sym]
  (nil? (try
          (requiring-resolve sym)
          (catch java.io.FileNotFoundException _ nil))))

(defn- var-present?
  [sym]
  (some? (requiring-resolve sym)))

(deftest deleted-trusted-surfaces-stay-deleted-test
  (testing "the watermark namespace is gone"
    (is (nil? (io/resource "eacl/datomic/watermark.clj"))))
  (testing "the Datomic provider-cache path is gone; the core sentinel survives"
    (is (nil? (io/resource "eacl/datomic/cache.clj")))
    (is (nil? (io/resource "eacl/datomic/cache_store_contract.clj")))
    (is (var-absent? 'eacl.cache/resolve!))
    (is (var-absent? 'eacl.cache/local-store))
    (is (var-absent? 'eacl.cache/cache-store))
    (is (var-absent? 'eacl.datomic.cache/authenticated-store))
    (is (var-absent? 'eacl.datomic.cache/CacheStore))
    (is (var-absent? 'eacl.datomic.cache/LocalStore))
    (is (var-absent? 'eacl.datomic.cache/local-store))
    (is (var-present? 'eacl.cache/no-cache))
    (is (var-present? 'eacl.cache/resolve-basis!)))
  (testing "dead observed-revision checkpoints are gone; key derivation survives"
    (is (var-absent? 'eacl.datomic.consistency/zed-token))
    (is (var-absent? 'eacl.datomic.consistency/token-data))
    (is (var-absent? 'eacl.datomic.consistency/token-revision))
    (is (var-absent? 'eacl.datomic.consistency/derive-signing-key))
    (is (var-absent? 'eacl.datomic.consistency/revision-checkpoints))
    (is (var-absent? 'eacl.datomic.consistency/observe!))
    (is (var-absent?
         'eacl.datomic.consistency/revision-at-least-seconds-ago)))
  (testing "cursor edges carry no path frontiers"
    (let [relay-source (slurp (io/resource "eacl/relay.cljc"))]
      (is (not (re-find #":path-frontiers" relay-source))
          "the relay edge coercion must not resurrect frontier transport")))
  (testing "changed-proof cursor rebase and restart machinery is gone"
    (doseq [resource ["eacl/relay.cljc"
                      "eacl/engine/v8.cljc"
                      "eacl/datomic/core.clj"]
            :let [source (slurp (io/resource resource))]]
      (is (not (re-find #":rebase\?|:cursor-recovery|mark-recursive-restart|restart-unroutable-rebase|keyset-rebase|rebase-acyclic-query"
                        source))
          (str resource " must fail closed instead of rebasing or restarting cursors"))))
  (testing "EACL authorization owns no blocking coordinator"
    (doseq [resource ["eacl/datomic/core.clj"
                      "eacl/cache.cljc"
                      "eacl/subproblem_cache.cljc"]
            :let [source (slurp (io/resource resource))]]
      (is (not (re-find #"\(locking\b|schema-lock|ReentrantReadWriteLock|Semaphore|single[- ]flight"
                        source))
          (str resource " must stay free of EACL-owned blocking coordination"))))
  (testing "the Datomic-only answer-kind registry is gone"
    (is (var-absent? 'eacl.datomic.core/answer-cache-kinds)))
  (testing "the write-only provider options are gone from client opts"
    (with-mem-conn [conn schema/v8-schema]
      (let [client (core/make-client conn {:security-key "audit-test0000000000000000000000"})]
        (is (not (contains? (:runtime client) :shared-cache-store)))
        (is (not (contains? (:runtime client) :lookup-cache-store)))))))
