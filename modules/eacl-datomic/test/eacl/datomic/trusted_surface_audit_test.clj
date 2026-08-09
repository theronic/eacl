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
  (testing "the authenticated-envelope completed-cache path is gone"
    (is (var-absent? 'eacl.cache/resolve!))
    (is (var-absent? 'eacl.cache/local-store))
    (is (var-absent? 'eacl.cache/cache-store))
    (is (var-absent? 'eacl.datomic.cache/authenticated-store))
    (is (var-present? 'eacl.cache/no-cache))
    (is (var-present? 'eacl.cache/resolve-current!))
    (is (var-present? 'eacl.datomic.cache/local-continuation-store)))
  (testing "the v2 zed-token constructors are gone; key derivation survives"
    (is (var-absent? 'eacl.datomic.consistency/zed-token))
    (is (var-absent? 'eacl.datomic.consistency/token-data))
    (is (var-absent? 'eacl.datomic.consistency/token-revision))
    (is (var-present? 'eacl.datomic.consistency/derive-signing-key))
    (is (var-present? 'eacl.datomic.consistency/revision-checkpoints))
    (is (var-present? 'eacl.datomic.consistency/observe!))
    (is (var-present?
         'eacl.datomic.consistency/revision-at-least-seconds-ago)))
  (testing "cursor edges carry no path frontiers"
    (let [relay-source (slurp (io/resource "eacl/relay.cljc"))]
      (is (not (re-find #":path-frontiers" relay-source))
          "the relay edge coercion must not resurrect frontier transport")))
  (testing "the vestigial :latest-result answer kind is gone"
    (is (= #{:can? :lookup-page :count}
           @(resolve 'eacl.datomic.core/answer-cache-kinds))))
  (testing "the write-only provider options are gone from client opts"
    (with-mem-conn [conn schema/v7-schema]
      (let [client (core/make-client conn {:page-token-key "audit-test"})]
        (is (not (contains? (:opts client) :shared-cache-store)))
        (is (not (contains? (:opts client) :lookup-cache-store)))))))
