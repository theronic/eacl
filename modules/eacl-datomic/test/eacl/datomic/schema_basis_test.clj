(ns eacl.datomic.schema-basis-test
  "Pins the v8.0 client-lifecycle schema-cache contract.

  A connection-backed client reads :eacl/schema-version once at construction
  and owns exactly one cache generation. New Datomic db values never trigger a
  schema read or definition scan. write-schema! through the client swaps that
  generation; arbitrary-db internals are uncached and cannot mutate it."
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

(deftest client-schema-is-read-once-not-on-every-db-value-test
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
          (is (= 1 @version-reads)
              "new db values and client schema writes reuse the construction-time version")
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

(deftest page-token-replays-with-its-historical-schema-generation-test
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
      (is (= ["a-2"]
             (mapv :id
                   (:data
                    (eacl/lookup-resources
                     acl
                     (assoc query :after cursor)))))
          "cursor replay evaluates permission definitions from its basis")
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

(deftest schema-generation-adoption-uses-one-database-value-test
  ;; The adopted generation label and the cache-generation object must derive
  ;; from one immutable db value. Today construction is cheap metadata; keeping
  ;; the snapshot boundary explicit prevents future eager schema state from
  ;; being mislabeled and removes a redundant d/db call.
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    (let [db-v1 (d/db conn)
          v1 (idx/schema-version db-v1)]
      (schema/write-schema! conn schema-viewer-only)
      (let [db-v2 (d/db conn)
            v2 (idx/schema-version db-v2)
            state (atom {:schema-version nil})
            lock (java.util.concurrent.locks.ReentrantReadWriteLock.)
            reads (atom [db-v1 db-v2])
            built-from (atom nil)
            make-schema-cache idx/make-schema-cache
            adopt (ns-resolve 'eacl.datomic.core
                              'adopt-schema-generation!)]
        (with-redefs [d/db
                      (fn [_]
                        (let [db (first @reads)]
                          (swap! reads rest)
                          db))
                      idx/make-schema-cache
                      (fn [db version]
                        (reset! built-from (idx/schema-version db))
                        (make-schema-cache db version))]
          (adopt conn state lock))
        (is (contains? #{v1 v2} (:schema-version @state)))
        (is (= (:schema-version @state) @built-from)
            "the generation UUID and the db used to build it are one snapshot")
        (is (= 1 (- 2 (count @reads)))
            "adoption needs one immutable Datomic value, not a torn pair")))))

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
            "an unstamped schema remains uncached even when live lookups are requested")

        (eacl/write-schema! acl schema-v1)
        (is (= ["a"] (mapv :id (:data (eacl/lookup-resources acl query)))))
        (is (= ["a"] (mapv :id (:data (eacl/lookup-resources acl query)))))
        (is (= 3 @calls)
            "the first supported schema write enables the client result cache")))))

(deftest out-of-band-schema-write-requires-a-new-client-test
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

      ;; Deliberately bypass the client. The lifecycle contract requires
      ;; recreating other clients/processes after such a schema write.
      (schema/write-schema! conn schema-v2)
      (is (= :outdated (:status (integrity/client-schema-status old-client)))
          "the optional one-datom diagnostic detects the mismatch")
      (let [writer (core/make-client conn {})]
        (eacl/create-relationship! writer viewer :viewer account))

      (is (false? (eacl/can? old-client viewer :admin account)))
      (is (true? (eacl/can? (core/make-client conn {}) viewer :admin account))))))
