(ns eacl.datascript.enumeration-routing-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.continuation :as continuation]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.subproblem-cache :as subproblem]))

(defn- seed-client!
  ([shape]
   (seed-client! shape fixture/schema {}))
  ([shape options]
   (seed-client! shape fixture/schema options))
  ([shape schema options]
   (let [conn (datascript/create-conn)
         client
         (datascript/make-client
          conn
          (merge
           {:cache {:remember-answers false}}
           options))]
     (eacl/write-schema! client schema)
     (ds/transact! conn (vec (fixture/object-transactions shape)))
     (doseq [batch (fixture/relationship-batches shape)]
       (eacl/create-relationships! client batch))
     {:conn conn :client client})))

(def small-shape
  (assoc fixture/default-shape
         :accounts 5
         :servers-per-account 40
         :user-1-account-count 4))

(defn- timed-page
  [client query stats]
  (binding [engine/*acyclic-work-stats* stats
            engine/*recursive-traversal-stats* (atom {})]
    (eacl/lookup-resources client query)))

(deftest certified-acyclic-enumeration-is-exact-and-recursive-limit-isolated-test
  (let [{:keys [client]}
        (seed-client!
         small-shape
         {:recursive-traversal-limits
          {:max-derived-grants 1
           :max-advanced-datoms 1
           :max-queued-work 1}})
        acyclic-stats (atom {})
        recursive-stats (atom {})
        count-result
        (binding [engine/*acyclic-work-stats* acyclic-stats
                  engine/*recursive-traversal-stats* recursive-stats]
          (eacl/count-resources
           client
           (fixture/count-query fixture/super-user :view)))
        page
        (binding [engine/*acyclic-work-stats* acyclic-stats
                  engine/*recursive-traversal-stats* recursive-stats]
          (eacl/lookup-resources
           client
           (fixture/resource-query fixture/super-user :view 17)))]
    (testing "overlapping account/team/VPC paths are emitted exactly once"
      (is (= 200 (:count count-result)))
      (is (= 17 (count (:data page))))
      (is (= (count (:data page))
             (count (distinct (:data page))))))
    (testing "the public count limit remains exact and explicit"
      (is (= {:count 19 :limit 19 :truncated? true}
             (select-keys
              (eacl/count-resources
               client
               (assoc
                (fixture/count-query fixture/super-user :view)
                :count-limit 19))
              [:count :limit :truncated?]))))
    (testing "an acyclic schema does not consume recursive traversal budgets"
      (is (= 2 (:routed-acyclic @acyclic-stats)))
      (is (pos? (:backend-scans @acyclic-stats)))
      (is (empty? @recursive-stats)))))

(deftest generated-route-is-schema-bound-and-fails-closed-test
  (let [{:keys [conn client]} (seed-client! small-shape)
        opts (:opts client)
        adapter
        (datascript-backend/snapshot-adapter (ds/db conn) opts)
        schema-cache (engine/make-schema-cache adapter)
        route
        (binding [engine/*schema-cache* schema-cache
                  subproblem/*decision-kernel*
                  (:decision-kernel opts)]
          (engine/enumeration-route adapter :server :view))
        stale-error
        (try
          (binding [engine/*schema-cache*
                    (assoc schema-cache
                           :source-scope
                           {:backend :datascript
                            :source-id "different-source"})
                    subproblem/*decision-kernel*
                    (:decision-kernel opts)]
            (engine/enumeration-route adapter :server :view))
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) error
            (ex-data error)))]
    (is (= :acyclic route))
    (is (= :eacl.routing/stale-certificate (:type stale-error)))
    (is (= :eacl.routing/stale-certificate
           (:eacl/error stale-error)))))

(deftest adapter-neutral-continuation-hit-miss-and-isolation-test
  (let [{:keys [conn client]} (seed-client! small-shape)
        query (fixture/resource-query fixture/user-1 :view 7)
        first-page (eacl/lookup-resources client query)
        first-cursor (get-in first-page [:page-info :end-cursor])
        page-2-stats (atom {})
        second-page
        (timed-page client (assoc query :after first-cursor) page-2-stats)
        second-cursor (get-in second-page [:page-info :end-cursor])
        page-3-stats (atom {})
        third-page
        (timed-page client (assoc query :after second-cursor) page-3-stats)
        other-client
        (datascript/make-client
         conn
         {:cache {:remember-answers false}})
        replay-stats (atom {})
        replay
        (timed-page other-client
                    (assoc query :after first-cursor)
                    replay-stats)
        decoded (datascript/token->cursor first-cursor)]
    (testing "successive pages resume private state without ordinal growth"
      (is (= (:data second-page) (:data replay)))
      (is (not= (:data first-page) (:data second-page)))
      (is (not= (:data second-page) (:data third-page)))
      (is (= 1 (:continuation-hits @page-2-stats)))
      (is (= 1 (:continuation-hits @page-3-stats)))
      (is (<= (:backend-scans @page-3-stats)
              (+ 2 (:backend-scans @page-2-stats)))))
    (testing "a different client misses private state and deterministically replays"
      (is (= 1 (:continuation-misses @replay-stats)))
      (is (zero?
           (get-in (datascript/cache-stats other-client)
                   [:continuations :hits]
                   0))))
    (testing "public cursors contain no private frontier or continuation state"
      (is (= :lookup-eid (get-in decoded [:edge :kind])))
      (is (= #{:kind :result-eid}
             (set (keys (:edge decoded))))))))

(deftest acyclic-current-cursor-rebase-never-enters-recursive-traversal-test
  (let [{:keys [conn client]}
        (seed-client!
         small-shape
         {:recursive-traversal-limits
          {:max-derived-grants 1
           :max-advanced-datoms 1
           :max-queued-work 1}})
        query (fixture/resource-query fixture/super-user :view 17)
        first-page (eacl/lookup-resources client query)
        cursor (get-in first-page [:page-info :end-cursor])
        added-server (fixture/object :server "server-after-cursor")
        _ (ds/transact! conn [{:eacl/id (:id added-server)}])
        _ (eacl/create-relationship!
           client
           (eacl/->Relationship
            fixture/super-user
            :shared_admin
            added-server))
        acyclic-stats (atom {})
        recursive-stats (atom {})
        second-page
        (binding [engine/*acyclic-work-stats* acyclic-stats
                  engine/*recursive-traversal-stats* recursive-stats]
          (eacl/lookup-resources client (assoc query :after cursor)))]
    (is (= 17 (count (:data second-page))))
    (is (= :rebased
           (get-in second-page [:page-info :cursor-recovery])))
    (is (pos? (:backend-scans @acyclic-stats)))
    (is (empty? @recursive-stats)
        "acyclic cursor membership must use the bounded indexed route")))

(deftest recursive-schema-with-empty-cycle-guards-stays-page-bounded-test
  (let [{baseline-client :client}
        (seed-client! small-shape)
        baseline-stats (atom {})
        baseline-count
        (binding [engine/*acyclic-work-stats* baseline-stats]
          (eacl/count-resources
           baseline-client
           (fixture/count-query fixture/super-user :view)))
        {:keys [conn client]}
        (seed-client!
         small-shape
         fixture/recursive-schema
        {:recursive-traversal-limits
          {:max-derived-grants 1
           :max-advanced-datoms 1
           :max-queued-work 1}})
        query (fixture/resource-query fixture/super-user :view 17)
        count-stats (atom {})
        page-stats (atom {})
        recursive-stats (atom {})
        count-result
        (binding [engine/*acyclic-work-stats* count-stats
                  engine/*recursive-traversal-stats* recursive-stats]
          (eacl/count-resources
           client
           (fixture/count-query fixture/super-user :view)))
        page
        (binding [engine/*acyclic-work-stats* page-stats
                  engine/*recursive-traversal-stats* recursive-stats]
          (eacl/lookup-resources client query))]
    (testing "recursive syntax alone does not activate fixed-point work"
      (is (= 200 (:count count-result)))
      (is (= 17 (count (:data page))))
      (is (= 1 (:routed-acyclic @count-stats)))
      (is (= 1 (:routed-acyclic @page-stats)))
      (is (empty? @recursive-stats))
      (is (= (:count baseline-count) (:count count-result)))
      (is (= (select-keys @baseline-stats
                          [:backend-scans
                           :subject->resources-scans
                           :permission-paths
                           :merge-advances
                           :counted-results])
             (select-keys @count-stats
                          [:backend-scans
                           :subject->resources-scans
                           :permission-paths
                           :merge-advances
                           :counted-results]))
          "empty recursive guards must not add work to the acyclic count"))
    (testing "an actual cycle-enabling relationship restores recursive routing"
      (eacl/create-relationship!
       client
       (eacl/->Relationship
        (fixture/object :server (fixture/server-id 0 0))
        :parent
        (fixture/object :server (fixture/server-id 0 1))))
      (let [adapter
            (datascript-backend/snapshot-adapter
             (ds/db conn)
             (:opts client))
            route
            (binding [engine/*schema-cache* (engine/make-schema-cache adapter)
                      subproblem/*decision-kernel*
                      (get-in client [:opts :decision-kernel])]
              (engine/enumeration-route adapter :server :view))]
        (is (= :recursive route))))))

(deftest cold-exact-count-reuses-one-merge-across-certified-windows-test
  (let [shape
        (assoc
         fixture/default-shape
         :accounts 1
         :servers-per-account 256
         :user-1-account-count 1)
        {:keys [client]}
        (seed-client! shape fixture/recursive-schema {})
        acyclic-stats (atom {})
        recursive-stats (atom {})
        result
        (binding [engine/*count-window-size* 64
                  engine/*acyclic-work-stats* acyclic-stats
                  engine/*recursive-traversal-stats* recursive-stats]
          (eacl/count-resources
           client
           (fixture/count-query fixture/user-1 :view)))]
    (is (= 256 (:count result)))
    (is (= 4 (:count-pages @acyclic-stats)))
    (is (= 5 (:permission-paths @acyclic-stats))
        "internal count windows must reuse one compiled merged traversal")
    (is (<= (:backend-scans @acyclic-stats) 24)
        "one count traversal must not split into tiny cached projections")
    (is (empty? @recursive-stats))))

(deftest continuation-eviction-and-query-key-separation-replay-test
  (let [{:keys [client]}
        (seed-client! small-shape {:cache {:max-entries 1
                                           :remember-answers false}})
        view-query (fixture/resource-query fixture/super-user :view 5)
        admin-query (assoc view-query :permission :admin)
        first-view (eacl/lookup-resources client view-query)
        view-cursor (get-in first-view [:page-info :end-cursor])
        _ (eacl/lookup-resources client admin-query)
        replay-stats (atom {})
        replay
        (timed-page client
                    (assoc view-query :after view-cursor)
                    replay-stats)]
    (is (= 5 (count (:data replay))))
    (is (= 1 (:continuation-misses @replay-stats)))
    (is (pos?
         (get-in (datascript/cache-stats client)
                 [:continuations :evictions]
                 0)))))

(deftest continuation-snapshot-mutation-and-query-lineage-are-isolated-test
  (let [{:keys [conn client]} (seed-client! small-shape)
        opts (:opts client)
        store (:continuation-cache-store opts)
        query (fixture/resource-query fixture/super-user :view 5)
        edge {:result-eid 17}
        before-adapter
        (datascript-backend/snapshot-adapter (ds/db conn) opts)
        before-context
        (continuation/private-context
         store before-adapter :lookup-resources query)
        _ ((:put-heads! before-context) edge {:frontier :before} 1)
        _ (eacl/create-relationship!
           client
           (eacl/->Relationship
            fixture/super-user
            :shared_admin
            (fixture/object :server (fixture/server-id 0 0))))
        after-adapter
        (datascript-backend/snapshot-adapter (ds/db conn) opts)
        after-context
        (continuation/private-context
         store after-adapter :lookup-resources query)
        changed-query-context
        (continuation/private-context
         store
         before-adapter
         :lookup-resources
         (assoc query :permission :admin))]
    (is (= {:frontier :before}
           ((:get-heads before-context) edge)))
    (is (nil? ((:get-heads after-context) edge))
        "a continuation from another snapshot cannot be resumed")
    (is (nil? ((:get-heads changed-query-context) edge))
        "a continuation from another permission cannot be resumed")))

(defn- all-forward
  [client query page-size]
  (loop [cursor nil
         results []
         pages 0]
    (when (>= pages 1000)
      (throw
       (ex-info "Enumeration page walk did not terminate."
                {:pages pages
                 :results (count results)
                 :last-cursor cursor})))
    (let [page
          (eacl/lookup-resources
           client
           (cond-> (assoc query :first page-size)
             cursor (assoc :after cursor)))
          results' (into results (:data page))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) results' (inc pages))
        results'))))

(deftest certified-acyclic-point-check-stays-out-of-recursive-traversal-test
  (let [{:keys [client]} (seed-client! small-shape)
        server (fixture/object :server (fixture/server-id 0 0))
        acyclic-stats (atom {})
        recursive-stats (atom {})
        allowed?
        (binding [engine/*acyclic-work-stats* acyclic-stats
                  engine/*recursive-traversal-stats* recursive-stats]
          (eacl/can? client fixture/super-user :view server))]
    (is allowed?)
    (is (= 1 (:routed-acyclic @acyclic-stats)))
    (is (empty? @recursive-stats))))

(deftest explorer-enumeration-refines-point-authorization-test
  (let [{:keys [client]} (seed-client! small-shape)
        servers
        (filter #(= :server (:type %)) (fixture/objects small-shape))
        users
        (filter #(= :user (:type %)) (fixture/objects small-shape))]
    (doseq [subject
            [fixture/super-user fixture/user-1 fixture/owner-0001]]
      (let [expected
            (filterv
             #(eacl/can? client subject :view %)
             servers)
            actual
            (all-forward
             client
             (fixture/count-query subject :view)
             13)]
        (is (= expected actual)
            (str "forward denotation for " (:id subject)))
        (is (= (count expected)
               (:count
                (eacl/count-resources
                 client
                 (fixture/count-query subject :view)))))))
    (let [server
          (fixture/object :server (fixture/server-id 0 0))
          expected
          (filterv
           #(eacl/can? client % :view server)
           users)
          actual
          (:data
           (eacl/lookup-subjects
            client
            {:resource server
             :permission :view
             :subject/type :user
             :first 100}))]
      (is (= expected actual)
          "reverse enumeration refines point authorization")
      (is (= (count expected)
             (:count
              (eacl/count-subjects
               client
               {:resource server
                :permission :view
                :subject/type :user})))))))
