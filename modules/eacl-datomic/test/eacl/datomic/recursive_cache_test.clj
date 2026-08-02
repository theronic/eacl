(ns eacl.datomic.recursive-cache-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]))

(def ^:private recursive-schema
  "definition user {}
   definition account {
     relation parent: account
     relation reader: user
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

(deftest forward-recursive-pagination-resumes-retries-and-replays-misses-test
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
            page1 (eacl/lookup-resources cached-client query)
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
            "an alternate cache replays the authenticated historical snapshot")
        (is (= (:data hit-page2) (:data retry-page2))
            "a cursor retry returns its immutable cached page")
        (is (= (:data page1) (:data previous-page)))
        (is (= 1 (:continuation-hits @hit-stats))
            "the originating client resumes its private proof-bound continuation")
        (is (nil? (:continuation-hits @miss-stats)))
        (is (= 1 (:continuation-misses @alternate-stats)))
        (is (true? (:cached? retry-page2))
            "a retry reuses the originating client's completed current page")
        (is (nil? (:recursive-page-hits @retry-stats))
            "a completed-page hit does not re-enter recursive traversal")
        (is (= 1 (:recursive-page-hits @previous-stats))
            "back navigation may reuse the same private proof-bound page")
        (is (<= (:derived-grants @hit-stats)
                (:derived-grants @miss-stats))
            "private continuation reuse does no more traversal than replay")))))

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
        (is (= 1 (:continuation-hits @stats)))
        (let [all-subjects (collect-reverse client query)]
          (is (= 130 (count all-subjects)))
          (is (= 130 (count (set (map :id all-subjects))))
              "reverse scans resume correctly across 64-EID chunks"))))))

(deftest recursive-cursor-replays-after-relevant-write-test
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
            uncomputed-cursor (page-end-cursor page2)]
        @(d/transact conn [{:eacl/id "new-live-account"}])
        (eacl/create-relationship!
         cached-client
         (->Relationship (spice-object :account (account-id 14))
                         :parent
                         (spice-object :account "new-live-account")))
        (is (= (:data page2)
               (:data
                (eacl/lookup-resources cached-client
                                       (assoc query :after cursor))))
            "an already produced exact page remains cache-resident")
        (is (= (mapv account-id (range 10 15))
               (mapv :id
                     (:data
                      (eacl/lookup-resources
                       cached-client
                       (assoc query :after uncomputed-cursor)))))
            "an uncomputed page is replayed against the historical snapshot")
        (is (= (:data page2)
               (:data
                (eacl/lookup-resources replay-client
                                       (assoc query :after cursor))))
            "cache-disabled replay does not fall forward after a relevant write")
        (is (= "new-live-account"
               (-> (collect-forward cached-client query) :data peek :id))
            "a new enumeration observes the relationship write")))))

(deftest recursive-cursor-survives-unrelated-datomic-transactions-test
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
      (let [page1 (eacl/lookup-resources client query)
            cursor (page-end-cursor page1)]
        (doseq [n (range 20)]
          @(d/transact conn [{:eacl/id (str "application-" n)}]))
        (let [stats (atom {})
              page2 (binding [idx/*recursive-traversal-stats* stats]
                      (eacl/lookup-resources client (assoc query :after cursor)))]
          (is (= (mapv account-id (range 5 10))
                 (mapv :id (:data page2))))
          (is (= 1 (:continuation-hits @stats))
              "proof-equivalent basis churn can resume private continuation state")
          (let [restart-stats (atom {})
                restarted-page1
                (binding [idx/*recursive-traversal-stats* restart-stats]
                  (eacl/lookup-resources client query))]
            (is (= (:data page1) (:data restarted-page1)))
            (is (true? (:cached? restarted-page1))
                "the completed page is lifted by unchanged relation stamps")
            (is (zero? (get @restart-stats :derived-grants 0)))))))))

(deftest recursive-pages-are-isolated-by-cache-namespace-test
  (with-mem-conn [conn schema/v7-schema]
    (let [store (cache/local-store)
          token-key "recursive-namespace-isolation"
          client-a
          (core/make-client
           conn
           {:page-token-key token-key
            ;; asserts on recursive-page-hits, so the answer cache must not
            ;; short-circuit the engine before it records them
            :cache {:store store
                    :namespace :tenant-a
                    :remember-answers false}})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 3}]
      (seed-recursive! conn client-a 12 1)
      (let [client-b
            (core/make-client
             conn
             {:page-token-key token-key
              :cache {:store store
                      :namespace :tenant-b
                      :remember-answers false}})
            page-a (eacl/lookup-resources client-a query)
            first-b-stats (atom {})
            page-b
            (binding [idx/*recursive-traversal-stats* first-b-stats]
              (eacl/lookup-resources client-b query))]
        (is (= (:data page-a) (:data page-b)))
        (is (nil? (:recursive-page-hits @first-b-stats))
            "tenant B cannot read tenant A's completed recursive page")
        (is (zero? (cache/clear-namespace! store :tenant-a))
            "no unauthenticated recursive page was retained")
        (let [second-b-stats (atom {})
              page-b-again
              (binding [idx/*recursive-traversal-stats* second-b-stats]
                (eacl/lookup-resources client-b query))]
          (is (= (:data page-b) (:data page-b-again)))
          (is (= 1 (:recursive-page-hits @second-b-stats))
              "tenant B reuses only its own client-private page"))))))

(deftest reverse-continuation-side-state-is-not-retained-test
  (with-mem-conn [conn schema/v7-schema]
    (let [store (cache/local-store)
          client
          (core/make-client
           conn
           {:page-token-key "reverse-rule-weight"
            :cache {:store store}})
          query {:resource (spice-object :account (account-id 2))
                 :permission :read
                 :subject/type :user
                 :first 1}]
      (seed-recursive! conn client 3 3)
      (eacl/lookup-subjects client query)
      (let [continuation
            (->> (.values ^java.util.LinkedHashMap (:entries store))
                 (keep (fn [entry]
                         (when (= :recursive-continuation
                                  (get-in entry [:value :eacl.cache/kind]))
                           (get-in entry
                                   [:value :eacl.cache/value :continuation]))))
                 first)
            state (:state continuation)
            retained-rule-count
            (reduce + 0 (map count (vals (:rules-by-node state))))
            weight #'idx/continuation-weight]
        (is (nil? continuation))
        (is (zero? retained-rule-count))
        (is (nil? (:rule-count state)))
        (is (= (weight state)
               (weight (assoc state :rule-count 0)))
            "no reverse rule graph reached the unauthenticated provider")))))

(deftest recursive-cursor-replays-when-its-boundary-object-is-gone-live-test
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
            subject-eid (d/entid (d/db conn) [:eacl/id (user-id 0)])]
        (eacl/delete-object! client subject)
        @(d/transact conn [[:db.fn/retractEntity subject-eid]])
        (is (= (mapv account-id (range 5 10))
               (mapv :id
                     (:data
                      (eacl/lookup-resources client
                                             (assoc query :after cursor)))))
            "the cursor resolves its boundary object in the historical snapshot")))))

(deftest alternate-cache-replays-a-cursor-when-the-relationship-proof-matches-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-shared-proof"
          first-client
          (core/make-client
           conn
           {:cache {:store (cache/local-store)}
            :page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn first-client 20 1)
      (let [second-client
            (core/make-client
             conn
             {:cache {:store (cache/local-store)}
              :page-token-key token-key})
            page1 (eacl/lookup-resources first-client query)
            stats (atom {})
            page2
            (binding [idx/*recursive-traversal-stats* stats]
              (eacl/lookup-resources
               second-client
               (assoc query :after (page-end-cursor page1))))]
        (is (= (mapv account-id (range 5 10))
               (mapv :id (:data page2))))
        (is (= 1 (:continuation-misses @stats)))
        (is (> (:derived-grants @stats) 5)
            "an alternate cache recomputes the prefix instead of changing snapshots")))))

(deftest recursive-continuation-does-not-retain-opaque-runtime-values-test
  (with-mem-conn [conn schema/v7-schema]
    (let [store (cache/local-store)
          client
          (core/make-client
           conn
           {:cache {:store store}
            :page-token-key "recursive-bounded-streams"})
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
      (let [entries (seq (.values ^java.util.LinkedHashMap (:entries store)))
            continuations
            (keep (fn [entry]
                    (when (= :recursive-continuation
                             (get-in entry [:value :eacl.cache/kind]))
                      (get-in entry
                              [:value :eacl.cache/value :continuation])))
                  entries)
            db-class (class (d/db conn))
            retained-values (mapcat #(tree-seq coll? seq %) continuations)
            streams (->> continuations
                         (mapcat #(seq (get-in % [:state :queue])))
                         (filter #(= :stream (:kind %))))]
        (is (empty? continuations))
        (is (empty? streams))
        (is (every? vector? (map :eids streams)))
        (is (every? #(<= (count (:eids %)) 64) streams))
        (is (not-any? #(instance? db-class %) retained-values))
        (is (not-any? #(instance? clojure.lang.LazySeq %) retained-values)))
      (let [walk (collect-forward client query)]
        (is (= 200 (count (:data walk))))
        (is (= 200 (count (set (map :id (:data walk)))))
            "forward scans resume correctly across 64-EID chunks")))))

(deftest rejected-continuation-safely-replays-while-proof-matches-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-rejected-cache"
          client (core/make-client
                  conn
                  {:page-token-key token-key
                   :cache {:max-weight 4096
                           :max-entry-weight 1
                           :max-entries 4}})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 3}]
      (seed-recursive! conn client 12 1)
      (let [page1 (eacl/lookup-resources client query)
            stats (atom {})
            page2
            (binding [idx/*recursive-traversal-stats* stats]
              (eacl/lookup-resources
               client
               (assoc query :after (page-end-cursor page1))))]
        (is (= (mapv account-id (range 3 6))
               (mapv :id (:data page2))))
        (is (= 1 (:continuation-misses @stats)))
        (is (> (:derived-grants @stats) 3)
            "the safe fallback replays the proven traversal prefix")))))

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
            "private continuation reuse does no more work than safe replay")))))
