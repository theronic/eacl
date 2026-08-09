(ns eacl.datomic.schema-basis-test
  "Pins the v8.0 immutable-snapshot schema-cache contract.

  Each request derives schema semantics from its selected Datomic value and a
  bounded proof-keyed generation registry. Unrelated DB values reuse the same
  schema generation; schema changes install another immutable generation
  without an EACL lock or mutable current-schema latch."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl :refer [Permission Relation Relationship]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.integrity :as integrity]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]))

(def ^:private schema-v1
  "definition user {}
   definition account { relation owner: user
                        permission admin = owner }")

(def ^:private schema-v2
  "definition user {}
   definition account { relation owner: user
                        relation viewer: user
                        permission admin = owner + viewer }")

(def ^:private schema-viewer-only
  "definition user {}
   definition account { relation owner: user
                        relation viewer: user
                        permission admin = viewer }")

(defn- seed-owner!
  [conn]
  @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
  @(d/transact conn
               (impl/tx-relationship
                (d/db conn)
                (Relationship (spice-object :user [:eacl/id "u"])
                              :owner
                              (spice-object :account [:eacl/id "a"])))))

(deftest proof-keyed-schema-generation-is-reused-across-unrelated-db-values-test
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    (seed-owner! conn)
    (let [version-reads (atom 0)
          path-calcs   (atom 0)
          version-fn   idx/schema-version
          calc-fn      engine/calc-permission-paths]
      (with-redefs [idx/schema-version (fn [db]
                                        (swap! version-reads inc)
                                        (version-fn db))
                    engine/calc-permission-paths
                    (fn [& args]
                      (swap! path-calcs inc)
                      (apply calc-fn args))]
        (let [acl (core/make-client conn {})
              u   (spice-object :user "u")
              a   (spice-object :account "a")]
          (is (= 1 @version-reads) "make-client performs the one automatic stamp read")
          (is (true? (eacl/can? acl u :admin a)))
          (is (= 1 @path-calcs) "first permission use populates the client generation")

          (dotimes [n 50]
            @(d/transact conn [{:eacl/id (str "unrelated-" n)}])
            (is (true? (eacl/can? acl u :admin a))))

          (eacl/write-schema! acl schema-v1)
          (is (= 2 @version-reads)
              "only construction and the write's calculation snapshot read the stamp")
          (is (= 1 @path-calcs)
              "unrelated and identical schema transactions never recompute permission paths"))))))

(deftest write-schema-through-client-replaces-one-generation-test
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    (seed-owner! conn)
    @(d/transact conn [{:eacl/id "viewer"}])
    (let [acl       (core/make-client conn {:page-token-key "schema-generation-test"})
          owner     (spice-object :user "u")
          viewer    (spice-object :user "viewer")
          account   (spice-object :account "a")
          path-calcs (atom 0)
          calc-fn   engine/calc-permission-paths]
      (with-redefs [engine/calc-permission-paths
                    (fn [& args]
                      (swap! path-calcs inc)
                      (apply calc-fn args))]
        (is (true? (eacl/can? acl owner :admin account)))
        (is (= 1 @path-calcs))

        (eacl/write-schema! acl schema-v2)
        (eacl/create-relationship! acl viewer :viewer account)

        (is (true? (eacl/can? acl viewer :admin account)))
        (is (= 2 @path-calcs) "the new generation computes admin once")

        (testing "an identical write neither bumps the stamp nor discards paths"
          (let [before (idx/schema-version (d/db conn))]
            (eacl/write-schema! acl schema-v2)
            (is (= before (idx/schema-version (d/db conn))))
            (is (true? (eacl/can? acl viewer :admin account)))
            (is (= 2 @path-calcs))))))))

(deftest page-token-across-schema-generations-uses-exact-snapshot-test
  ;; Datomic can reconstruct the authenticated immutable old snapshot. It does
  ;; not reinterpret the cursor under the changed current schema.
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a-1"} {:eacl/id "a-2"}])
    (doseq [account ["a-1" "a-2"]]
      @(d/transact conn
                   (impl/tx-relationship
                    (d/db conn)
                    (Relationship (spice-object :user [:eacl/id "u"])
                                  :owner
                                  (spice-object :account [:eacl/id account])))))
    (let [acl   (core/make-client conn {:page-token-key "stale-schema-token"})
          query {:subject (spice-object :user "u")
                 :permission :admin
                 :resource/type :account
                 :first 1}
          page1 (eacl/lookup-resources acl query)
          cursor (get-in page1 [:page-info :end-cursor])]
      (is (some? cursor))
      (eacl/write-schema! acl schema-viewer-only)
      (let [page2 (eacl/lookup-resources acl (assoc query :after cursor))]
        (is (= 1 (count (:data page2))))
        (is (not= (:data page1) (:data page2)))
        (is (nil? (get-in page2 [:page-info :cursor-recovery]))
            "exact continuation has no rebase/restart marker"))
      (is (empty? (:data (eacl/lookup-resources acl query)))
          "a new enumeration uses the new schema generation"))))

(deftest arbitrary-db-evaluation-is-uncached-and-isolated-test
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    (seed-owner! conn)
    (let [acl (core/make-client conn {})
          db  (d/db conn)
          internal-u (spice-object :user (d/entid db [:eacl/id "u"]))
          internal-a (spice-object :account (d/entid db [:eacl/id "a"]))
          public-u   (spice-object :user "u")
          public-a   (spice-object :account "a")]
      (is (true? (eacl/can? acl public-u :admin public-a)))

      (testing "a speculative addition can be evaluated explicitly but cannot publish into the client"
        (let [speculative (:db-after
                           (d/with db [(Permission :account :view {:relation :owner})]))]
          (is (true? (idx/can? speculative internal-u :view internal-a)))
          (is (false? (eacl/can? acl public-u :view public-a)))))

      (testing "a speculative retraction cannot narrow the client generation"
        (let [permission-eid
              (d/q '[:find ?e .
                     :where
                     [?e :eacl.permission/resource-type :account]
                     [?e :eacl.permission/permission-name :admin]]
                   db)
              speculative (:db-after
                           (d/with db [[:db.fn/retractEntity permission-eid]]))]
          (is (false? (idx/can? speculative internal-u :admin internal-a)))
          (is (true? (eacl/can? acl public-u :admin public-a))))))))

(deftest unstamped-client-does-not-latch-schema-test
  (with-mem-conn [conn schema/v7-schema]
    @(d/transact conn [(Relation :account :owner :user)])
    (seed-owner! conn)
    (let [acl (core/make-client conn {})
          u   (spice-object :user "u")
          a   (spice-object :account "a")]
      (is (nil? (idx/schema-version (d/db conn))))
      (is (false? (eacl/can? acl u :admin a)))

      @(d/transact conn [(Permission :account :admin {:relation :owner})])
      (is (true? (eacl/can? acl u :admin a))
          "without a stamp, paths are recomputed rather than latched")

      (testing "the first supported write establishes a stamp even for a zero delta"
        (eacl/write-schema! acl schema-v1)
        (is (some? (idx/schema-version (d/db conn))))
        (is (true? (eacl/can? acl u :admin a)))))))

(deftest unstamped-client-does-not-cache-lookup-results-test
  (with-mem-conn [conn schema/v7-schema]
    @(d/transact conn [(Relation :account :owner :user)
                       (Permission :account :admin {:relation :owner})])
    (seed-owner! conn)
    (let [acl (core/make-client
               conn
               {:cache {:remember-answers true}})
          query {:subject (spice-object :user "u")
                 :permission :admin
                 :resource/type :account}
          calls (atom 0)
          lookup-resources impl/lookup-resources]
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query continuation-context]
                      (swap! calls inc)
                      (lookup-resources db
                                        internal-query
                                        continuation-context))]
        (is (= ["a"] (mapv :id (:data (eacl/lookup-resources acl query)))))
        @(d/transact conn [{:eacl/id "unrelated"}])
        (is (= ["a"] (mapv :id (:data (eacl/lookup-resources acl query)))))
        (is (= 2 @calls)
            "unknown writers reuse only the identical current snapshot")

        (eacl/write-schema! acl schema-v1)
        (is (= ["a"] (mapv :id (:data (eacl/lookup-resources acl query)))))
        (is (= ["a"] (mapv :id (:data (eacl/lookup-resources acl query)))))
        (is (= 3 @calls)
            "the schema write installs a new exact generation; its repeat hits")))))

(deftest out-of-band-schema-write-is-observed-by-existing-client-test
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    (seed-owner! conn)
    @(d/transact conn [{:eacl/id "viewer"}])
    (let [old-client (core/make-client conn {})
          viewer     (spice-object :user "viewer")
          account    (spice-object :account "a")]
      ;; Warm and therefore latch the old admin path.
      (is (false? (eacl/can? old-client viewer :admin account)))
      (is (= :current (:status (integrity/client-schema-status old-client))))

      ;; Deliberately bypass the client. v3 rederives the selected snapshot's
      ;; complete schema proof, so an existing client cannot keep using a
      ;; latched permission graph.
      (schema/write-schema! conn schema-v2)
      (is (= :outdated (:status (integrity/client-schema-status old-client)))
          "the optional one-datom diagnostic detects the mismatch")
      (let [writer (core/make-client conn {})]
        (eacl/create-relationship! writer viewer :viewer account))

      (is (true? (eacl/can? old-client viewer :admin account)))
      (is (true? (eacl/can? (core/make-client conn {}) viewer :admin account))))))

(deftest immutable-snapshot-read-does-not-block-schema-commit-test
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    (seed-owner! conn)
    (let [acl (core/make-client conn {})
          user (spice-object :user "u")
          account (spice-object :account "a")
          before-version (idx/schema-version (d/db conn))
          entered (promise)
          release-read (promise)
          original-can? impl/can?]
      (with-redefs [impl/can?
                    (fn [& args]
                      (deliver entered true)
                      @release-read
                      (apply original-can? args))]
        (let [read-work (future (eacl/can? acl user :admin account))
              _ @entered
              write-work (future (eacl/write-schema! acl schema-v2))
              write-result (deref write-work 2000 ::timed-out)]
          (try
            (is (not= ::timed-out write-result)
                "an S1 schema commit must not wait for an EACL S0 read lock")
            (finally
              (deliver release-read true)))
          (is (true? @read-work)
              "the in-flight authorization remains correct for immutable S0")
          (is (not= before-version (idx/schema-version (d/db conn)))))))))
