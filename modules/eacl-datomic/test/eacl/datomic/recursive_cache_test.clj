(ns eacl.datomic.recursive-cache-test
  "Demand-bounded recursive pagination in generated logical order.

  A page computes only the requested window plus lookahead. Authenticated
  cursors carry a logical ordinal and boundary identity; client-private,
  bounded continuation state may avoid replay, but its absence never changes
  results. Complete denotations are computed only under explicit
  :evaluation :complete-denotation and use the identical public order."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]
            [eacl.execution :as execution]
            [eacl.spicedb.consistency :as consistency]
            [eacl.verified-kernel :as verified]))

(def ^:private recursive-schema
  "definition user {}
   definition account {
     relation parent: account
     relation reader: user
     relation auditor: user
     permission read = reader + parent->read
   }")

(defn- account-id [n]
  (str "account-" n))

(defn- user-id [n]
  (str "user-" n))

(defn- seed-recursive!
  [conn client account-count user-count]
  (eacl/write-schema! client recursive-schema)
  @(d/transact conn
               (concat
                (for [n (range account-count)]
                  {:eacl/id (account-id n)})
                (for [n (range user-count)]
                  {:eacl/id (user-id n)})))
  (eacl/create-relationships!
   client
   (concat
    (for [n (range (dec account-count))]
      (->Relationship (spice-object :account (account-id n))
                      :parent
                      (spice-object :account (account-id (inc n)))))
    (for [n (range user-count)]
      (->Relationship (spice-object :user (user-id n))
                      :reader
                      (spice-object :account (account-id 0)))))))

(defn- page-end-cursor [page]
  (get-in page [:page-info :end-cursor]))

(defn- page-start-cursor [page]
  (get-in page [:page-info :start-cursor]))

(defn- stat [stats k]
  (get @stats k 0))

(defn- collect-forward
  [client query]
  (loop [after nil
         data []
         derived 0]
    (let [stats (atom {})
          page (binding [idx/*recursive-traversal-stats* stats]
                 (eacl/lookup-resources
                  client
                  (cond-> query after (assoc :after after))))
          data' (into data (:data page))
          derived' (+ derived (get @stats :derived-grants 0))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (page-end-cursor page) data' derived')
        {:data data' :derived-grants derived'}))))

(defn- collect-reverse
  [client query]
  (loop [after nil
         data []]
    (let [page (eacl/lookup-subjects
                client
                (cond-> query after (assoc :after after)))
          data' (into data (:data page))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (page-end-cursor page) data')
        data'))))

(deftest datomic-validates-execution-contract-before-backend-or-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "execution-order"})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn client 10 1)
      (let [before (core/cache-stats client)
            backend-work (atom {})
            data
            (binding [backend/*backend-op-stats* backend-work]
              (try
                (eacl/lookup-resources
                 client (assoc query :evaluation :everything))
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error))))]
        (is (= :eacl/invalid-request (:type data)))
        (is (= :evaluation (:key data)))
        (is (empty? @backend-work))
        (is (= before (core/cache-stats client)))))))

(deftest datomic-recursive-cursor-binds-normalized-traversal-limits-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "cursor-limit-contract"
          roomy
          (core/make-client
           conn
           {:page-token-key token-key
            :recursive-traversal-limits {:max-derived-grants 1000}})
          tight
          (core/make-client
           conn
           {:page-token-key token-key
            :recursive-traversal-limits {:max-derived-grants 2}})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 1}]
      (seed-recursive! conn roomy 10 1)
      (let [cursor (page-end-cursor (eacl/lookup-resources roomy query))
            data
            (try
              (eacl/lookup-resources tight (assoc query :after cursor))
              nil
              (catch clojure.lang.ExceptionInfo error
                (ex-data error)))]
        (is (= :eacl.pagination/invalid-cursor (:type data)))
        (is (= :query-mismatch (:reason data)))))))

(deftest datomic-demand-and-complete-evaluation-have-identical-page-order-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "execution-parity"
          demand-client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn demand-client 30 1)
      (let [uncached-client
            (core/make-client conn {:page-token-key token-key
                                    :cache cache/no-cache})
            complete-client
            (core/make-client conn {:page-token-key token-key})
            demand-work (atom {})
            uncached-work (atom {})
            complete-work (atom {})
            demand
            (binding [idx/*recursive-traversal-stats* demand-work]
              (eacl/lookup-resources demand-client query))
            uncached
            (binding [idx/*recursive-traversal-stats* uncached-work]
              (eacl/lookup-resources uncached-client query))
            complete
            (binding [idx/*recursive-traversal-stats* complete-work]
              (eacl/lookup-resources
               complete-client
               (assoc query :evaluation :complete-denotation)))]
        (is (= (:data demand) (:data uncached) (:data complete)))
        (is (<= (stat demand-work :derived-grants) 6))
        (is (= (stat demand-work :derived-grants)
               (stat uncached-work :derived-grants))
            "a cold cache does not expand demand")
        (is (<= 30 (stat complete-work :derived-grants))
            "only explicit completion authorizes full-denotation work")))))

(deftest complete-point-membership-uses-generated-logical-not-numeric-order-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "logical-membership"})
          user (spice-object :user "logical-user")
          low (spice-object :account "logical-low")
          high (spice-object :account "logical-high")
          _ (eacl/write-schema! client recursive-schema)
          _ @(d/transact conn [{:eacl/id (:id low)}
                               {:eacl/id "logical-middle"}
                               {:eacl/id (:id high)}
                               {:eacl/id (:id user)}])
          _ (eacl/create-relationships!
             client
             [(->Relationship user :reader high)
              (->Relationship high :parent low)])
          query {:subject user
                 :permission :read
                 :resource/type :account
                 :first 10
                 :evaluation :complete-denotation}
          page (eacl/lookup-resources client query)
          internal-order
          (mapv #(d/entid (d/db conn) [:eacl/id (:id %)]) (:data page))]
      (is (= #{(:id low) (:id high)} (set (map :id (:data page)))))
      (is (> (first internal-order) (second internal-order))
          "the fixture must exercise a generated logical order that is not numeric EID order")
      (is (true? (eacl/can? client {:subject user
                                    :permission :read
                                    :resource low
                                    :evaluation :complete-denotation})))
      (is (true? (eacl/can? client user :read low))
          "demand and explicit completion must return the same Boolean"))))

(deftest datomic-deadline-is-typed-and-never-becomes-denial-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "execution-deadline"})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5
                 :timeout-ms 1}]
      (seed-recursive! conn client 10 1)
      (let [clock (atom 0)
            data
            (binding [execution/*monotonic-nanos* #(deref clock)
                      backend/*invoke-observer*
                      (fn [{:keys [phase]}]
                        (when (= :before phase)
                          (swap! clock + 2000000)))]
              (try
                (eacl/lookup-resources client query)
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error))))]
        (is (= :eacl.execution/deadline-exceeded (:type data)))
        (is (keyword? (:stage data)))
        (is (not (contains? data :allowed?)))))))

(deftest forward-recursive-pagination-resumes-retries-and-recomputes-misses-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-forward-cache"
          cached-client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn cached-client 30 1)
      (let [disabled-client (core/make-client conn {:page-token-key token-key
                                                    :cache cache/no-cache})
            alternate-client (core/make-client conn {:page-token-key token-key})
            page1-stats (atom {})
            page1 (binding [idx/*recursive-traversal-stats* page1-stats]
                    (eacl/lookup-resources cached-client query))
            cursor (page-end-cursor page1)
            replay-page1 (eacl/lookup-resources disabled-client query)
            hit-stats (atom {})
            miss-stats (atom {})
            hit-page2 (binding [idx/*recursive-traversal-stats* hit-stats]
                        (eacl/lookup-resources cached-client (assoc query :after cursor)))
            miss-page2 (binding [idx/*recursive-traversal-stats* miss-stats]
                         (eacl/lookup-resources
                          disabled-client
                          (assoc query :after (page-end-cursor replay-page1))))
            alternate-stats (atom {})
            alternate-page2
            (binding [idx/*recursive-traversal-stats* alternate-stats]
              (eacl/lookup-resources alternate-client
                                     (assoc query :after cursor)))
            retry-stats (atom {})
            retry-page2 (binding [idx/*recursive-traversal-stats* retry-stats]
                          (eacl/lookup-resources cached-client
                                                 (assoc query :after cursor)))
            previous-stats (atom {})
            previous-page (binding [idx/*recursive-traversal-stats* previous-stats]
                            (eacl/lookup-resources
                             cached-client
                             (-> query
                                 (dissoc :first)
                                 (assoc :last 5
                                        :before (page-start-cursor hit-page2)))))]
        (is (= (mapv account-id (range 0 5))
               (mapv :id (:data page1))))
        (is (= (mapv account-id (range 5 10))
               (mapv :id (:data hit-page2))))
        (is (= (:data hit-page2) (:data miss-page2)))
        (is (= (:data hit-page2) (:data alternate-page2))
            "a keyset cursor is portable: an alternate cache serves the same page")
        (is (= (:data hit-page2) (:data retry-page2))
            "a cursor retry returns the same page")
        (is (= (:data page1) (:data previous-page)))
        (is (<= (stat page1-stats :derived-grants) 6)
            "the first page stops after its five rows plus lookahead")
        (is (<= (stat hit-stats :derived-grants) 11)
            "continuation counters remain below full-denotation work")
        (is (pos? (stat miss-stats :derived-grants))
            "a client without the denotation cache pays the closure again")
        (is (pos? (stat alternate-stats :derived-grants))
            "an alternate cache resolves its own denotation instead of trusting foreign state")
        (is (true? (:cached? retry-page2))
            "a retry reuses the originating client's completed answer")
        (is (zero? (stat retry-stats :stream-fills))
            "an answer hit does not re-enter recursive traversal")
        (is (<= (stat previous-stats :derived-grants) 7)
            "bounded prefix replay retains at most the requested window")
        (is (pos? (get-in (core/cache-stats cached-client)
                          [:subproblems :answer-hits]))
            "an exact demanded page is reusable without caching a denotation")))))

(deftest reverse-recursive-pagination-resumes-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "recursive-reverse-cache"})
          query {:resource (spice-object :account (account-id 4))
                 :permission :read
                 :subject/type :user
                 :first 4}]
      (seed-recursive! conn client 5 130)
      (let [page1 (eacl/lookup-subjects client query)
            stats (atom {})
            page2 (binding [idx/*recursive-traversal-stats* stats]
                    (eacl/lookup-subjects
                     client
                     (assoc query :after (page-end-cursor page1))))]
        (is (= 4 (count (:data page1))))
        (is (= 4 (count (:data page2))))
        (is (empty? (set/intersection
                     (set (map :id (:data page1)))
                     (set (map :id (:data page2))))))
        (is (< (stat stats :derived-grants) 130)
            "the reverse route does not complete all 130 subjects for page two")
        (let [all-subjects (collect-reverse client query)]
          (is (= 130 (count all-subjects)))
          (is (= 130 (count (set (map :id all-subjects))))
              "reverse scans resume correctly across page boundaries"))))))

(deftest recursive-cursor-falls-back-to-exact-snapshot-after-relevant-write-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-historical-cache"
          cached-client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn cached-client 15 1)
      (let [replay-client (core/make-client conn {:page-token-key token-key
                                                  :cache cache/no-cache})
            page1 (eacl/lookup-resources cached-client query)
            cursor (page-end-cursor page1)
            page2 (eacl/lookup-resources cached-client
                                         (assoc query :after cursor))
            uncomputed-cursor (page-end-cursor page2)
            page3 (eacl/lookup-resources replay-client
                                         (assoc query :after uncomputed-cursor))]
        @(d/transact conn [{:eacl/id "new-live-account"}])
        (eacl/create-relationship!
         cached-client
         (->Relationship (spice-object :account (account-id 14))
                         :parent
                         (spice-object :account "new-live-account")))
        (doseq [[expected recovered]
                [[(:data page2)
                  (eacl/lookup-resources cached-client
                                         (assoc query :after cursor))]
                 [(:data page3)
                  (eacl/lookup-resources cached-client
                                         (assoc query :after uncomputed-cursor))]
                 [(:data page2)
                  (eacl/lookup-resources replay-client
                                         (assoc query :after cursor))]]]
          (is (= expected (:data recovered))
              "Datomic resumes the immutable cursor snapshot without a hybrid walk")
          (is (nil? (get-in recovered [:page-info :cursor-recovery]))))
        (is (= "new-live-account"
               (-> (collect-forward cached-client query) :data peek :id))
            "a new enumeration observes the relationship write")))))

(deftest exact-cursor-fallback-never-violates-newer-freshness-floor-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:page-token-key "recursive-freshness-floor"
                   :coherence-authority :managed})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn client 10 1)
      @(d/transact conn [{:eacl/id "floor-auditor"}])
      (let [initial-token
            (:zed/token
             (eacl/create-relationship!
              client
              (->Relationship
               (spice-object :user "floor-auditor")
               :auditor
               (spice-object :account (account-id 0)))))
            initial-query
            (assoc query
                   :consistency
                   (consistency/at-least-as-fresh initial-token))
            page1 (eacl/lookup-resources client initial-query)
            cursor (page-end-cursor page1)
            _ @(d/transact conn [{:eacl/id "new-floor-account"}])
            token
            (:zed/token
             (eacl/create-relationship!
              client
              (->Relationship
               (spice-object :account (account-id 9))
               :parent
               (spice-object :account "new-floor-account"))))
            data
            (try
              (eacl/lookup-resources
               client
               (assoc initial-query
                      :after cursor
                      :consistency
                      (consistency/at-least-as-fresh token)))
              nil
              (catch clojure.lang.ExceptionInfo error
                (ex-data error)))]
        (is (= :eacl.consistency/cursor-consistency-conflict
               (:type data)))
        (is (< (:cursor-order-hint data)
               (:requested-order-hint data)))))))

(deftest recursive-cursor-continues-after-unrelated-basis-churn-test
  ;; Continuation proofs are dependency-scoped — schema stamp plus the
  ;; closure's relation stamps — so transactions touching nothing in the
  ;; {reader, parent} closure leave the proof equal and continue on current.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:page-token-key "recursive-unrelated"
                   :coherence-authority :managed})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn client 20 1)
      @(d/transact conn [{:eacl/id "auditor-user"}])
      (let [page1 (eacl/lookup-resources client query)
            cursor (page-end-cursor page1)]
        ;; Twenty unrelated commits: bare entities plus EACL writes to the
        ;; :auditor relation, which lies outside the query's dependency
        ;; closure entirely.
        (doseq [n (range 10)]
          @(d/transact conn [{:eacl/id (str "application-" n)}]))
        (doseq [n (range 10)]
          (eacl/create-relationship!
           client
           (->Relationship (spice-object :user "auditor-user")
                           :auditor
                           (spice-object :account (account-id n)))))
        (let [stats (atom {})
              crossings (atom {})
              page2 (binding [idx/*recursive-traversal-stats* stats
                              verified/*kernel-crossing-stats* crossings]
                      (eacl/lookup-resources client (assoc query :after cursor)))]
          (is (= (mapv #(spice-object :account (account-id %))
                       (range 5 10))
                 (:data page2))
              "the continuation resumes exclusively after the boundary")
          (is (nil? (get-in page2 [:page-info :cursor-recovery]))
              "unrelated churn is a continuation hit, not a recovery")
          (is (pos? (get @crossings :cursor-continuation 0))
              "the reuse is a verified kernel decision, not a bypass")
          (is (<= (stat stats :derived-grants) 11)
              "continuation work stays below full-denotation work")
          (let [fresh-stats (atom {})
                fresh-page1
                (binding [idx/*recursive-traversal-stats* fresh-stats]
                  (eacl/lookup-resources client query))]
            (is (= (:data page1) (:data fresh-page1)))
            (is (false? (:cached? fresh-page1))
                "demand mode does not lift a completed denotation")
            (is (<= (get @fresh-stats :derived-grants 0) 6))))))))

(deftest recursive-denotations-are-client-private-across-namespaces-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-namespace-isolation"
          client-a
          (core/make-client
           conn
           {:security-key token-key
            ;; asserts on engine work, so the answer cache must not
            ;; short-circuit the engine before it can slice the denotation
            :cache {:namespace :tenant-a
                    :remember-answers false}})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 3}]
      (seed-recursive! conn client-a 12 1)
      (let [client-b
            (core/make-client
             conn
             {:security-key token-key
              :cache {:namespace :tenant-b
                      :remember-answers false}})
            a-stats (atom {})
            page-a
            (binding [idx/*recursive-traversal-stats* a-stats]
              (eacl/lookup-resources client-a query))
            first-b-stats (atom {})
            page-b
            (binding [idx/*recursive-traversal-stats* first-b-stats]
              (eacl/lookup-resources client-b query))]
        (is (= (:data page-a) (:data page-b)))
        (is (pos? (stat a-stats :derived-grants))
            "tenant A resolves its own denotation")
        (is (pos? (stat first-b-stats :derived-grants))
            "tenant B cannot reuse tenant A's denotation and pays its own closure")
        (let [second-b-stats (atom {})
              page-b-again
              (binding [idx/*recursive-traversal-stats* second-b-stats]
                (eacl/lookup-resources client-b query))]
          (is (= (:data page-b) (:data page-b-again)))
          (is (zero? (stat second-b-stats :derived-grants))
              "the exact page already demanded by tenant B is reusable")
          (is (= 1 (stat second-b-stats :recursive-page-hits))))))))

(deftest reverse-continuation-side-state-is-bounded-and-private-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client
          (core/make-client
           conn
           {:security-key "reverse-rule-weight"})
          store (get-in client [:opts :continuation-cache-store])
          query {:resource (spice-object :account (account-id 2))
                 :permission :read
                 :subject/type :user
                 :first 1}]
      (seed-recursive! conn client 3 3)
      (let [page1 (eacl/lookup-subjects client query)
            page2 (eacl/lookup-subjects
                   client (assoc query :after (page-end-cursor page1)))
            kinds (->> (vals (:entries @(:state store)))
                       (map :kind)
                       set)]
        (is (= 1 (count (:data page1))))
        (is (= 1 (count (:data page2))))
        (is (contains? kinds :recursive-continuation))
        (is (<= (:weight @(:state store)) (:max-weight store)))))))

(deftest recursive-cursor-remains-on-exact-snapshot-when-live-objects-disappear-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "recursive-deleted-boundary"})
          subject (spice-object :user (user-id 0))
          query {:subject subject
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn client 10 1)
      (let [page1 (eacl/lookup-resources client query)
            cursor (page-end-cursor page1)
            boundary-eid (d/entid (d/db conn) [:eacl/id (account-id 4)])
            subject-eid (d/entid (d/db conn) [:eacl/id (user-id 0)])]
        (is (= (mapv account-id (range 0 5))
               (mapv :id (:data page1))))
        (testing "a deleted live boundary does not alter the historical walk"
          (eacl/delete-object! client (spice-object :account (account-id 4)))
          @(d/transact conn [[:db.fn/retractEntity boundary-eid]])
          (let [recovered (eacl/lookup-resources client (assoc query :after cursor))
                fresh-page1 (eacl/lookup-resources client query)]
            (is (= (mapv account-id (range 5 10))
                   (mapv :id (:data recovered)))
                "the cursor resumes against the retained immutable snapshot")
            (is (not= (:data fresh-page1) (:data recovered)))
            (is (nil? (get-in recovered [:page-info :cursor-recovery])))))
        (testing "a later live subject deletion still cannot rewrite history"
          (eacl/delete-object! client subject)
          @(d/transact conn [[:db.fn/retractEntity subject-eid]])
          (let [recovered (eacl/lookup-resources client (assoc query :after cursor))]
            (is (= (mapv account-id (range 5 10))
                   (mapv :id (:data recovered))))
            (is (nil? (get-in recovered [:page-info :cursor-recovery])))))))))

(deftest alternate-cache-resolves-its-own-denotation-for-a-foreign-cursor-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-shared-proof"
          first-client
          (core/make-client
           conn
           {:security-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn first-client 20 1)
      (let [second-client
            (core/make-client
             conn
             {:security-key token-key})
            page1 (eacl/lookup-resources first-client query)
            stats (atom {})
            page2
            (binding [idx/*recursive-traversal-stats* stats]
              (eacl/lookup-resources
               second-client
               (assoc query :after (page-end-cursor page1))))]
        (is (= (mapv account-id (range 5 10))
               (mapv :id (:data page2))))
        (is (> (stat stats :derived-grants) 5)
            "an alternate cache resolves the complete denotation once instead of trusting foreign state")))))

(deftest recursive-continuation-does-not-retain-opaque-runtime-values-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client
          (core/make-client
           conn
           {:security-key "recursive-bounded-streams"})
          store (get-in client [:opts :continuation-cache-store])
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn client 200 1)
      (eacl/create-relationships!
       client
       (for [n (range 1 200)]
         (->Relationship (spice-object :user (user-id 0))
                         :reader
                         (spice-object :account (account-id n)))))
      (eacl/lookup-resources client query)
      (let [entries (vals (:entries @(:state store)))
            continuations
            (keep (fn [entry]
                    (when (= :recursive-continuation (:kind entry))
                      (get-in entry [:value :continuation])))
                  entries)
            db-class (class (d/db conn))
            retained-values (mapcat #(tree-seq coll? seq %) continuations)]
        (is (seq continuations))
        (is (not-any? #(instance? db-class %) retained-values))
        (is (not-any? #(instance? clojure.lang.LazySeq %) retained-values)))
      (let [walk (collect-forward client query)]
        (is (= 200 (count (:data walk))))
        (is (= 200 (count (set (map :id (:data walk)))))
            "forward scans resume correctly across page boundaries")))))

(deftest uncached-client-safely-serves-a-borrowed-cursor-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-rejected-cache"
          client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 3}]
      (seed-recursive! conn client 12 1)
      (let [fresh-client (core/make-client conn {:page-token-key token-key
                                                 :cache cache/no-cache})
            page1 (eacl/lookup-resources client query)
            stats (atom {})
            page2
            (binding [idx/*recursive-traversal-stats* stats]
              (eacl/lookup-resources
               fresh-client
               (assoc query :after (page-end-cursor page1))))]
        (is (= (mapv account-id (range 3 6))
               (mapv :id (:data page2)))
            "a client with no denotation cache serves the same page from the same cursor")
        (is (> (stat stats :derived-grants) 3)
            "it re-resolves the denotation instead of trusting any cached state")))))

(deftest complete-recursive-enumeration-is-equal-with-or-without-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-linear-walk"
          cached-client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 10}]
      (seed-recursive! conn cached-client 80 1)
      (let [disabled-client (core/make-client conn {:page-token-key token-key
                                                    :cache cache/no-cache})
            cached (collect-forward cached-client query)
            replayed (collect-forward disabled-client query)]
        (is (= (:data replayed) (:data cached)))
        (is (= 80 (count (:data cached))))
        (is (<= (:derived-grants cached)
                (:derived-grants replayed))
            "denotation reuse does no more work than uncached recomputation")))))

(deftest expired-page-token-reaches-the-kernel-decision-test
  ;; cursor-dependency-validity: expiry is a computed input of the verified
  ;; continuation decision, rejected by the kernel rather than pre-empted at
  ;; decode. The public error is unchanged.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:page-token-key "recursive-expired"
                   :page-token-ttl-seconds 1})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn client 10 1)
      (let [page1 (eacl/lookup-resources client query)
            cursor (page-end-cursor page1)
            now-var (ns-resolve 'eacl.datomic.core 'now-seconds)
            now-fn @now-var
            crossings (atom {})
            error
            (with-redefs-fn {now-var #(+ 120 (long (now-fn)))}
              (fn []
                (binding [verified/*kernel-crossing-stats* crossings]
                  (try
                    (eacl/lookup-resources client (assoc query :after cursor))
                    nil
                    (catch clojure.lang.ExceptionInfo thrown
                      thrown)))))]
        (is (some? error) "an expired page token must not resume")
        (is (= :eacl.pagination/expired-cursor (:type (ex-data error))))
        (is (= :eacl.pagination/expired-cursor (:eacl/error (ex-data error))))
        (is (= :expired (:reason (ex-data error))))
        (is (= "Page token has expired." (ex-message error)))
        (is (pos? (get @crossings :cursor-continuation 0))
            "the expired token was rejected by a :cursor-continuation kernel decision")))))
