(ns eacl.datomic.recursive-cache-test
  "Recursive pagination over KEYSET cursors on the canonical sorted denotation.

  The first store-bound page resolves and publishes the complete sorted
  denotation (subproblem :denotation tier). Every later page in the same
  validity scope is a binary-search slice of that vector: zero backend work
  (:stream-fills 0, :derived-grants 0 in *recursive-traversal-stats*). Page
  cursors are {:kind :lookup-eid :result-eid <eid>}, the same kind as the
  acyclic route, and enumeration order is ascending internal eid. The old
  per-cursor continuation store, recursive page cache and ordinal rebase are
  gone; their counters (:continuation-hits, :continuation-misses,
  :recursive-page-hits) never fire on this route."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]
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
        (is (<= 30 (stat page1-stats :derived-grants))
            "the first store-bound page resolves the complete denotation")
        (is (zero? (stat hit-stats :stream-fills))
            "a later page is a binary-search slice of the published denotation")
        (is (zero? (stat hit-stats :derived-grants))
            "denotation reuse re-derives nothing")
        (is (pos? (stat miss-stats :derived-grants))
            "a client without the denotation cache pays the closure again")
        (is (pos? (stat alternate-stats :derived-grants))
            "an alternate cache resolves its own denotation instead of trusting foreign state")
        (is (true? (:cached? retry-page2))
            "a retry reuses the originating client's completed answer")
        (is (zero? (stat retry-stats :stream-fills))
            "an answer hit does not re-enter recursive traversal")
        (is (zero? (stat previous-stats :stream-fills))
            "back navigation slices the same denotation with zero backend work")
        (is (zero? (stat previous-stats :derived-grants)))
        (is (pos? (get-in (core/cache-stats cached-client)
                          [:subproblems :denotation-hits]))
            "later pages register as denotation-tier hits")))))

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
        (is (zero? (stat stats :stream-fills))
            "the reverse route also slices its published denotation")
        (is (zero? (stat stats :derived-grants)))
        (let [all-subjects (collect-reverse client query)]
          (is (= 130 (count all-subjects)))
          (is (= 130 (count (set (map :id all-subjects))))
              "reverse scans resume correctly across page boundaries"))))))

(deftest recursive-cursor-rebases-after-relevant-write-test
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
              "a surviving denotation member resumes exclusively after itself")
          (is (= :rebased
                 (get-in recovered [:page-info :cursor-recovery]))))
        (is (= "new-live-account"
               (-> (collect-forward cached-client query) :data peek :id))
            "a new enumeration observes the relationship write")))))

(deftest recursive-cursor-continues-after-unrelated-basis-churn-test
  ;; Re-goldened for cursor-dependency-validity (was
  ;; recursive-cursor-rebases-after-unrelated-basis-churn-test): continuation
  ;; proofs are dependency-scoped — schema stamp plus the closure's relation
  ;; stamps — so transactions touching nothing in the {reader, parent}
  ;; closure leave the proof equal and the kernel decides :current instead of
  ;; :rebase-current.
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
          (is (zero? (stat stats :stream-fills))
              "continuation reuse does zero backend stream work")
          (is (zero? (stat stats :derived-grants))
              "no fixed-point recomputation occurs")
          (is (nil? (:continuation-hits @stats)))
          (let [restart-stats (atom {})
                restarted-page1
                (binding [idx/*recursive-traversal-stats* restart-stats]
                  (eacl/lookup-resources client query))]
            (is (= (:data page1) (:data restarted-page1)))
            (is (true? (:cached? restarted-page1))
                "the completed page is lifted by unchanged relation stamps")
            (is (zero? (get @restart-stats :derived-grants 0)))))))))

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
          (is (zero? (stat second-b-stats :stream-fills))
              "tenant B slices only its own client-private denotation")
          (is (zero? (stat second-b-stats :derived-grants))))))))

(deftest reverse-continuation-side-state-is-not-retained-test
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
            kinds (->> (.values ^java.util.LinkedHashMap (:entries store))
                       (keep #(get-in % [:value :eacl.cache/kind]))
                       set)]
        (is (= 1 (count (:data page1))))
        (is (= 1 (count (:data page2))))
        (is (not (contains? kinds :recursive-continuation))
            "the keyset route stores no per-cursor continuation state at all")))))

(deftest recursive-cursor-recovers-when-its-boundary-object-is-gone-live-test
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
        (testing "a revoked boundary drops the bound and restarts honestly"
          (eacl/delete-object! client (spice-object :account (account-id 4)))
          @(d/transact conn [[:db.fn/retractEntity boundary-eid]])
          (let [recovered (eacl/lookup-resources client (assoc query :after cursor))
                fresh-page1 (eacl/lookup-resources client query)]
            ;; deleting account-4 also breaks the parent chain, so the live
            ;; denotation is exactly accounts 0-3
            (is (= (mapv account-id (range 0 4))
                   (mapv :id (:data recovered)))
                "a non-member boundary restarts with page-1 content")
            (is (= (:data fresh-page1) (:data recovered)))
            (is (= :restarted
                   (get-in recovered [:page-info :cursor-recovery])))))
        (testing "a deleted subject authorizes nothing on the live graph"
          (eacl/delete-object! client subject)
          @(d/transact conn [[:db.fn/retractEntity subject-eid]])
          (let [recovered (eacl/lookup-resources client (assoc query :after cursor))]
            (is (empty? (:data recovered)))
            ;; the cursor still names the retracted account-4 boundary, so
            ;; relay edge internalization drops the bound and reports an
            ;; honest :restarted before the unknown-subject short-circuit
            ;; returns the empty live answer
            (is (= :restarted
                   (get-in recovered [:page-info :cursor-recovery])))))))))

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
      (let [entries (seq (.values ^java.util.LinkedHashMap (:entries store)))
            continuations
            (keep (fn [entry]
                    (when (= :recursive-continuation
                             (get-in entry [:value :eacl.cache/kind]))
                      (get-in entry
                              [:value :eacl.cache/value :continuation])))
                  entries)
            db-class (class (d/db conn))
            retained-values (mapcat #(tree-seq coll? seq %) continuations)]
        (is (empty? continuations))
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
