(ns eacl.bench.pagination-test
  "Multi-path pagination benchmarks for regression detection.

   Run with: clojure -M:bench
   These tests are excluded from normal test runs via ^:benchmark metadata.

   Tests a 4-path permission graph (server.view = account->admin + team->admin + vpc->admin + shared_admin)
   which exercises the cursor-tree merge algorithm with multiple divergent arrow paths."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set :as set]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.core :as eacl]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as spiceomic]
            [eacl.datomic.db :as ddb]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.secure-format :as secure]
            [eacl.verified-kernel :as verified]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :refer [->user ->account ->server ->team ->vpc ->platform]]))

;; --- Schema ---

(def multipath-schema-dsl
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation owner: user
     relation platform: platform

     permission admin = owner + platform->super_admin
   }

   definition team {
     relation account: account
     relation leader: user

     permission admin = account->admin + leader
   }

   definition vpc {
     relation account: account
     relation shared_admin: user

     permission admin = account->admin + shared_admin
   }

   definition server {
     relation account: account
     relation team: team
     relation vpc: vpc
     relation shared_admin: user

     permission view = account->admin + team->admin + vpc->admin + shared_admin
     permission admin = account->admin + shared_admin
   }")

(def recursive-chain-schema-dsl
  "definition user {}

   definition account {
     relation parent: account
     relation reader: user

     permission read = reader + parent->read
   }")

(def basic-attrs
  [{:db/ident :server/name, :db/cardinality :db.cardinality/one, :db/valueType :db.type/string, :db/index true}
   {:db/ident :team/name, :db/cardinality :db.cardinality/one, :db/valueType :db.type/string, :db/index true}
   {:db/ident :vpc/name, :db/cardinality :db.cardinality/one, :db/valueType :db.type/string, :db/index true}])

(defn- tx-relationships
  [db relationships]
  ; :allow-tempids? because entities are created in the same transaction.
  (impl/optimistic-relationship-tx-data
   db
   (mapcat #(impl/tx-relationship db % {:allow-tempids? true}) relationships)))

(defn- with-generated-traversal-observer
  "Captures the generated result's resource dimensions across the completed
  cache coordinator boundary. Dynamic bindings are thread-local and therefore
  cannot serve as a reliable observer for this heavy regression."
  [stats operation]
  (let [record-var
        (ns-resolve 'eacl.engine.v8 'record-generated-stats!)
        record-original
        (when record-var @record-var)]
    (when-not (and record-var (fn? record-original))
      (throw
       (ex-info
        "Generated traversal resource observer is unavailable."
        {:type :eacl.bench/missing-generated-resource-observer})))
    (with-redefs-fn
      {record-var
       (fn [result baseline]
         (record-original result baseline)
         (reset!
          stats
          {:generated-dimensional-counters (:counters result)
           :generated-retained-logical-units
           (:retained-logical-units result)}))}
      operation)))

;; --- Seeding ---

(defn seed-multipath!
  "Seeds a multi-path permission graph. Returns the acl client."
  [conn {:keys [num-accounts teams-per-acct vpcs-per-acct servers-per-acct]}]
  ;; :remember-answers false so these benchmarks observe the TRAVERSAL layer.
  ;; With answers remembered (the default), a repeated identical page is served
  ;; from the answer cache and the engine is never entered, so traversal-call
  ;; counts read as zero and prove nothing.
  @(d/transact conn (into schema/v7-schema basic-attrs))
  (let [acl (spiceomic/make-client
             conn
             {:cache {:remember-answers false}})]
    (eacl/write-schema! acl multipath-schema-dsl)

    ;; Platform + super-user + test user
    @(d/transact conn
                 (concat
                  [{:db/id "platform", :db/ident :test/platform, :eacl/id "platform"}
                   {:db/id "super-user", :db/ident :user/super-user, :eacl/id "super-user"}
                   {:db/id "user-1", :db/ident :test/user1, :eacl/id "user-1"}]
                  (tx-relationships (d/db conn)
                                    [(Relationship (->user "super-user") :super_admin (->platform "platform"))])))

    ;; Seed accounts with teams, vpcs, servers
    (let [account-uuids (repeatedly num-accounts d/squuid)]
      (doseq [[_n account-uuid] (map-indexed vector account-uuids)]
        (let [account-tempid (d/tempid :db.part/user)
              owner-tempid   (d/tempid :db.part/user)

              team-data (for [t (range teams-per-acct)]
                          (let [team-tempid   (d/tempid :db.part/user)
                                leader-tempid (d/tempid :db.part/user)]
                            {:team-tempid team-tempid
                             :txes (concat
                                    [{:db/id team-tempid, :eacl/id (str (d/squuid)), :team/name (str "Team " t)}
                                     {:db/id leader-tempid, :eacl/id (str (d/squuid))}]
                                    (tx-relationships (d/db conn)
                                                      [(Relationship (->account account-tempid) :account (->team team-tempid))
                                                       (Relationship (->user leader-tempid) :leader (->team team-tempid))]))}))

              vpc-data (for [v (range vpcs-per-acct)]
                         (let [vpc-tempid (d/tempid :db.part/user)
                               sa-tempid  (d/tempid :db.part/user)]
                           {:vpc-tempid vpc-tempid
                            :txes (concat
                                   [{:db/id vpc-tempid, :eacl/id (str (d/squuid)), :vpc/name (str "VPC " v)}
                                    {:db/id sa-tempid, :eacl/id (str (d/squuid))}]
                                   (tx-relationships (d/db conn)
                                                     [(Relationship (->account account-tempid) :account (->vpc vpc-tempid))
                                                      (Relationship (->user sa-tempid) :shared_admin (->vpc vpc-tempid))]))}))

              team-tempids (mapv :team-tempid team-data)
              vpc-tempids  (mapv :vpc-tempid vpc-data)

              server-txes (for [s (range servers-per-acct)]
                            (let [server-tempid (d/tempid :db.part/user)]
                              (concat
                               [{:db/id server-tempid, :server/name (str "Server " (d/squuid)), :eacl/id (str (d/squuid))}]
                               (tx-relationships (d/db conn)
                                                 [(Relationship (->account account-tempid) :account (->server server-tempid))
                                                  (Relationship (->team (nth team-tempids (mod s teams-per-acct))) :team (->server server-tempid))
                                                  (Relationship (->vpc (nth vpc-tempids (mod s vpcs-per-acct))) :vpc (->server server-tempid))]))))

              account-txes (concat
                            [{:db/id account-tempid, :eacl/id (str account-uuid)}
                             {:db/id owner-tempid, :eacl/id (str (d/squuid))}]
                            (tx-relationships (d/db conn)
                                              [(Relationship (->platform :test/platform) :platform (->account account-tempid))
                                               (Relationship (->user owner-tempid) :owner (->account account-tempid))]))

              all-txes (concat account-txes
                               (mapcat :txes team-data)
                               (mapcat :txes vpc-data)
                               (apply concat server-txes))]
          @(d/transact conn all-txes))))

    ;; Give user-1 ownership of first 2 accounts
    (let [db (d/db conn)
          first-accounts (->> (d/q '[:find [?aid ...]
                                     :where [?a :eacl/id ?aid]]
                                   db)
                              sort
                              (take 2))]
      (doseq [aid first-accounts]
        @(d/transact conn
                     (tx-relationships (d/db conn)
                                       [(Relationship (->user :test/user1) :owner (->account [:eacl/id aid]))]))))

    ;; The latency assertions below are traversal benchmarks. Establish the
    ;; managed mutation-proof baseline only after every direct fixture write is
    ;; complete, so proof construction does not dominate the traversal signal.
    ;; The separate cache-proof-strategy benchmark measures unknown/content
    ;; proof cost explicitly.
    (spiceomic/make-client
     conn
     {:coherence-authority :managed
      :proof-mode :mutation
      :cache {:remember-answers false}})))

(defn seed-recursive-chain!
  [conn {:keys [chain-length unrelated-count]}]
  @(d/transact conn schema/v7-schema)
  (let [acl (spiceomic/make-client conn {})]
    (eacl/write-schema! acl recursive-chain-schema-dsl)
    @(d/transact conn
                 (concat
                  [{:db/id "user-1" :eacl/id "user-1"}]
                  (for [n (range chain-length)]
                    {:db/id (str "node-" n)
                     :eacl/id (str "node-" n)})
                  (for [n (range unrelated-count)]
                    {:db/id (str "unrelated-" n)
                     :eacl/id (str "unrelated-" n)})))
    @(d/transact conn
                 (concat
                  (tx-relationships (d/db conn)
                                    [(Relationship (->user "user-1") :reader (->account "node-0"))])
                  (mapcat (fn [n]
                            (tx-relationships (d/db conn)
                                              [(Relationship (->account (str "node-" n))
                                                             :parent
                                                             (->account (str "node-" (inc n))))]))
                          (range (dec chain-length)))))
    ;; As above, start managed authority after the static direct-write fixture
    ;; is complete. Recursive benchmarks then measure worklist/continuation
    ;; behavior rather than whole-graph content-proof hashing.
    (spiceomic/make-client
     conn
     {:coherence-authority :managed
      :proof-mode :mutation
      :cache {:remember-answers false}})))

(deftest ^:benchmark benchmark-seeders-initialize-empty-database-test
  (testing "multi-path seeder initializes an empty database before constructing a client"
    (with-mem-conn [conn []]
      (is (some? (seed-multipath!
                  conn
                  {:num-accounts 1
                   :teams-per-acct 1
                   :vpcs-per-acct 1
                   :servers-per-acct 1})))))
  (testing "recursive seeder initializes an empty database before constructing a client"
    (with-mem-conn [conn []]
      (is (some? (seed-recursive-chain!
                  conn
                  {:chain-length 2
                   :unrelated-count 0}))))))

;; --- Timing utilities ---

(defn- run-timed
  "Runs f n times, returns vector of elapsed times in ms."
  [n f]
  (mapv (fn [_]
          (let [start (System/nanoTime)
                _     (f)
                end   (System/nanoTime)]
            (/ (double (- end start)) 1e6)))
        (range n)))

(defn- timed
  [f]
  (let [start (System/nanoTime)
        value (f)
        end   (System/nanoTime)]
    {:elapsed-ms (/ (double (- end start)) 1e6)
     :value value}))

(defn- recursive-walk
  [client query]
  (let [stats (atom {})
        start (System/nanoTime)
        {:keys [ids pages]}
        (binding [impl.indexed/*recursive-traversal-stats* stats]
          (loop [after nil
                 ids []
                 pages 0]
            (let [page (eacl/lookup-resources
                        client
                        (cond-> query after (assoc :after after)))
                  ids' (into ids (map :id) (:data page))
                  pages' (inc pages)]
              (if (get-in page [:page-info :has-next-page?])
                (recur (get-in page [:page-info :end-cursor])
                       ids'
                       pages')
                {:ids ids'
                 :pages pages'}))))
        end (System/nanoTime)]
    {:elapsed-ms (/ (double (- end start)) 1e6)
     :ids ids
     :pages pages
     :stats @stats}))

(defn- median [coll]
  (let [sorted (sort coll)
        n      (count sorted)
        mid    (quot n 2)]
    (if (odd? n)
      (nth sorted mid)
      (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0))))

(defn- percentile [coll p]
  (let [sorted (sort coll)
        idx    (min (dec (count sorted))
                    (int (Math/ceil (* (/ p 100.0) (count sorted)))))]
    (nth sorted idx)))

(defn- profile-var
  [target-var iterations f]
  (let [original @target-var
        calls (atom 0)
        elapsed-ns (atom 0)
        wrapped
        (fn [& args]
          (let [start (System/nanoTime)]
            (try
              (apply original args)
              (finally
                (swap! calls inc)
                (swap! elapsed-ns + (- (System/nanoTime) start))))))]
    (with-redefs-fn {target-var wrapped}
      #(dotimes [_ iterations] (f)))
    {:calls @calls
     :ns-per-call (/ (double @elapsed-ns) (max 1 @calls))
     :ns-per-operation (/ (double @elapsed-ns) iterations)}))

;; --- Regression thresholds ---
;;
;; With 15k servers and 4 arrow paths:
;; - cursor-tree branch: ~2-10ms per page (depending on hardware)
;; - lazy-merge-sort regression: ~30-70ms per page (6x slower)
;;
;; These are managed mutation-proof traversal gates. Unknown-writer content
;; proofs intentionally hash complete dependencies and are reported separately
;; by cache-proof-strategy-churn-benchmark; mixing that cost into this gate
;; would stop it detecting traversal regressions.
;;
;; Thresholds are generous to account for CI/slow hardware,
;; but will catch a 6x algorithmic regression.

(def ^:private first-page-threshold-ms 75)
(def ^:private per-page-threshold-ms 50)
(def ^:private page-drift-threshold-ms 20)
(def ^:private page-drift-threshold-ratio 4.0)

;; --- Benchmark config ---

(def ^:private bench-config
  {:num-accounts     30
   :teams-per-acct   4
   :vpcs-per-acct    2
   :servers-per-acct 500})

(def ^:private warmup-iterations 20)
(def ^:private bench-iterations 30)
(def ^:private pagination-iterations 8)
(def ^:private pages-per-run 40)
(def ^:private page-size 50)

(defn- page-cursor
  [page cursor-key]
  (get-in page [:page-info cursor-key]))

(defn- page-query
  [base-query direction boundary]
  (case direction
    :forward (cond-> base-query
               boundary (assoc :after boundary))
    :reverse (cond-> (-> base-query
                         (dissoc :first :after)
                         (assoc :last (:first base-query)))
               boundary (assoc :before boundary))))

(defn- next-boundary
  [direction page]
  (case direction
    :forward (page-cursor page :end-cursor)
    :reverse (page-cursor page :start-cursor)))

(defn- assert-page-boundaries
  [direction page-index page]
  (case direction
    :forward
    (do
      (is (true? (get-in page [:page-info :has-next-page?])))
      (is (= (pos? page-index)
             (get-in page [:page-info :has-previous-page?]))))

    :reverse
    (do
      (is (= (pos? page-index)
             (get-in page [:page-info :has-next-page?])))
      (is (true? (get-in page [:page-info :has-previous-page?]))))))

(defn- pagination-run
  [acl base-query direction]
  (loop [boundary nil
         page-index 0
         seen-ids #{}
         samples []]
    (if (= page-index pages-per-run)
      samples
      (let [{:keys [elapsed-ms value]} (timed #(eacl/lookup-resources acl
                                                                      (page-query base-query direction boundary)))
            ids (set (map :id (:data value)))
            boundary' (next-boundary direction value)]
        (is (= page-size (count (:data value)))
            (format "%s page %d should return exactly %d results"
                    (name direction) (inc page-index) page-size))
        (is (empty? (set/intersection seen-ids ids))
            (format "%s page %d should not overlap previous pages"
                    (name direction) (inc page-index)))
        (is (some? boundary')
            (format "%s page %d should expose the cursor needed for the next page"
                    (name direction) (inc page-index)))
        (assert-page-boundaries direction page-index value)
        (recur boundary'
               (inc page-index)
               (into seen-ids ids)
               (conj samples {:direction direction
                              :page page-index
                              :elapsed-ms elapsed-ms}))))))

(defn- pagination-samples
  [acl base-query direction]
  (mapcat (fn [_] (pagination-run acl base-query direction))
          (range pagination-iterations)))

(defn- page-medians
  [samples]
  (->> samples
       (group-by :page)
       (sort-by key)
       (mapv (fn [[page xs]]
               {:page (inc page)
                :median-ms (median (map :elapsed-ms xs))}))))

(defn- assert-stable-pagination-performance
  [label samples]
  (let [medians (page-medians samples)
        first-window (take 5 medians)
        last-window (take-last 5 medians)
        first-median (median (map :median-ms first-window))
        last-median (median (map :median-ms last-window))
        max-page-median (apply max (map :median-ms medians))
        slowest-pages (take 5 (sort-by :median-ms > medians))
        allowed-late (max (+ first-median page-drift-threshold-ms)
                          (* first-median page-drift-threshold-ratio))]
    (println (format "%s pagination (%d pages x %d runs): early=%.2fms/page, late=%.2fms/page, max-page-median=%.2fms"
                     label pages-per-run pagination-iterations first-median last-median max-page-median))
    (println (str label " slowest page medians: " (pr-str slowest-pages)))
    (is (< max-page-median per-page-threshold-ms)
        (format "REGRESSION: %s max page median %.2fms exceeds %dms threshold"
                label max-page-median per-page-threshold-ms))
    (is (< last-median allowed-late)
        (format "REGRESSION: %s late-page median %.2fms exceeds allowed %.2fms; pagination may be re-scanning or ignoring cursors"
                label last-median allowed-late))))

(defn- deep-page-work-samples
  "Walks a complete forward result set and records traversal calls and realized
  relationship-index EIDs at selected depths and over the whole walk.

  A nonterminal continuation should remain page-bounded. The terminal page may
  drain pending duplicate derivations in a multi-path union so it can state
  `has-next-page? false`; that one-page cost need not be constant, but the
  complete walk must remain linear and must not replay earlier prefixes."
  [acl base-query page-count sample-pages]
  (let [calls (atom 0)
        realized-eids (atom 0)
        total-calls (atom 0)
        total-realized-eids (atom 0)
        original-subject->resources ddb/subject->resources]
    (with-redefs [ddb/subject->resources
                  (fn [& args]
                    (swap! calls inc)
                    (swap! total-calls inc)
                    (map (fn [eid]
                           (swap! realized-eids inc)
                           (swap! total-realized-eids inc)
                           eid)
                         (apply original-subject->resources args)))]
      (loop [page-index 0
             boundary nil
             seen-ids #{}
             samples {}]
        (reset! calls 0)
        (reset! realized-eids 0)
        (let [page (eacl/lookup-resources acl
                                          (cond-> base-query
                                            boundary (assoc :after boundary)))
              next-boundary (get-in page [:page-info :end-cursor])
              seen-ids' (into seen-ids (map :id (:data page)))
              samples' (cond-> samples
                         (contains? sample-pages page-index)
                         (assoc page-index {:boundary boundary
                                            :calls @calls
                                            :realized-eids @realized-eids}))]
          (if (= page-count (inc page-index))
            {:seen-ids seen-ids'
             :samples samples'
             :total-calls @total-calls
             :total-realized-eids @total-realized-eids
             :last-page page}
            (recur (inc page-index)
                   next-boundary
                   seen-ids'
                   samples')))))))

;; --- Tests ---

(deftest ^:benchmark multipath-pagination-benchmark
  (testing "Multi-path pagination performance (4 arrow paths)"
    (with-mem-conn [conn []]
      (let [total-servers (* (:num-accounts bench-config) (:servers-per-acct bench-config))
            _   (println (format "\nSeeding %d servers (%d accounts x %d servers, %d teams/acct, %d vpcs/acct)..."
                                 total-servers
                                 (:num-accounts bench-config)
                                 (:servers-per-acct bench-config)
                                 (:teams-per-acct bench-config)
                                 (:vpcs-per-acct bench-config)))
            acl (seed-multipath! conn bench-config)
            _   (println "Seeding complete.")

            subject (->user "super-user")
            base-query {:subject       subject
                        :permission    :view
                        :resource/type :server
                        :first         page-size}]

        (testing "first page lookup"
          ;; Warmup JIT
          (run-timed warmup-iterations #(eacl/lookup-resources acl base-query))

          (let [times  (run-timed bench-iterations #(eacl/lookup-resources acl base-query))
                med    (median times)
                p95    (percentile times 95)]
            (println (format "First page (:first=%d): median=%.2fms, p95=%.2fms, min=%.2fms, max=%.2fms"
                             page-size med p95 (apply min times) (apply max times)))
            (is (< med first-page-threshold-ms)
                (format "REGRESSION: First page median %.2fms exceeds %dms threshold"
                        med first-page-threshold-ms))))

        (testing "forward pagination with :first/:after"
          (assert-stable-pagination-performance
           "Forward"
           (doall (pagination-samples acl base-query :forward))))

        (testing "reverse pagination with :last/:before"
          (assert-stable-pagination-performance
           "Reverse"
           (doall (pagination-samples acl base-query :reverse))))

        (testing "pagination correctness"
          (let [page1 (eacl/lookup-resources acl base-query)
                page2 (eacl/lookup-resources acl
                                             (assoc base-query
                                                    :after (get-in page1 [:page-info :end-cursor])))]
            (is (= page-size (count (:data page1))) "First page should return exactly :first results")
            (is (= page-size (count (:data page2))) "Second page should return exactly :first results")
            (is (some? (get-in page1 [:page-info :end-cursor])) "First page should have an end cursor")
            (let [ids1 (set (map :id (:data page1)))
                  ids2 (set (map :id (:data page2)))]
              (is (empty? (set/intersection ids1 ids2))
                  "Forward pages should not have overlapping results")))
          (let [last-page (eacl/lookup-resources acl
                                                 (-> base-query
                                                     (dissoc :first)
                                                     (assoc :last page-size)))
                previous-page (eacl/lookup-resources acl
                                                     (-> base-query
                                                         (dissoc :first)
                                                         (assoc :last page-size
                                                                :before (get-in last-page [:page-info :start-cursor]))))]
            (is (= page-size (count (:data last-page))) "Last page should return exactly :last results")
            (is (= page-size (count (:data previous-page))) "Previous page should return exactly :last results")
            (is (some? (get-in last-page [:page-info :start-cursor])) "Last page should have a start cursor")
            (let [ids1 (set (map :id (:data last-page)))
                  ids2 (set (map :id (:data previous-page)))]
              (is (empty? (set/intersection ids1 ids2))
                  "Reverse pages should not have overlapping results"))))

        (testing "cursor frontiers reduce deterministic traversal work on deep pages"
          (let [frontier-acl
                (spiceomic/make-client
                 conn
                 {:cache {:remember-answers false}})
                page-count (quot total-servers page-size)
                sample-pages #{0 (quot page-count 2) (dec page-count)}
                {:keys [seen-ids samples total-calls total-realized-eids
                        last-page]}
                (deep-page-work-samples
                 frontier-acl base-query page-count sample-pages)
                continuation-stats
                (cache/stats
                 (get-in frontier-acl [:opts :continuation-cache-store]))
                first-page-calls (get-in samples [0 :calls])
                middle-page-calls (get-in samples [(quot page-count 2) :calls])
                last-page-calls (get-in samples [(dec page-count) :calls])
                first-page-eids (get-in samples [0 :realized-eids])
                middle-page-eids
                (get-in samples [(quot page-count 2) :realized-eids])
                last-page-eids
                (get-in samples [(dec page-count) :realized-eids])
                page-medians (into {}
                                   (map (fn [[page-index {:keys [boundary]}]]
                                          [page-index
                                           (median
                                            (run-timed 20
                                                       #(eacl/lookup-resources
                                                         frontier-acl
                                                         (cond-> base-query
                                                           boundary (assoc :after boundary)))))]))
                                   samples)]
            (println (format "Traversal calls by depth: first=%d, middle=%d, last=%d"
                             first-page-calls middle-page-calls last-page-calls))
            (println (format "Realized relationship EIDs by depth: first=%d, middle=%d, last=%d"
                             first-page-eids middle-page-eids last-page-eids))
            (println (format "Deep-page medians: first=%.2fms, middle=%.2fms, last=%.2fms"
                             (get page-medians 0)
                             (get page-medians (quot page-count 2))
                             (get page-medians (dec page-count))))
            (println
             (format
              "Complete walk work: calls=%d, realized relationship EIDs=%d, continuation rejections=%d"
              total-calls
              total-realized-eids
              (get-in continuation-stats
                      [:by-kind :recursive-continuation :rejections]
                      0)))
            (is (= total-servers (count seen-ids))
                "A complete frontier-paginated walk must return every server exactly once")
            (is (= sample-pages (set (keys samples))))
            (is
             (zero?
              (get-in continuation-stats
                      [:by-kind :recursive-continuation :rejections]
                      0))
             "the default bounded store must admit the one active long-walk continuation")
            (is (<= middle-page-calls first-page-calls)
                "frontier resumption must not add path scans on deeper pages")
            (is (<= middle-page-eids (+ first-page-eids (* 2 page-size)))
                "middle-page index work must remain page-bounded, not prefix-sized")
            (is (<= last-page-eids (* 2 total-servers))
                "terminal duplicate-path drain must remain linear in the result graph")
            (is (<= total-realized-eids (* 8 total-servers))
                "a complete continuation walk must remain linear, not replay every prefix")
            (is (<= total-calls total-servers)
                "backend scan calls over the complete walk must remain linear")
            (is (false? (get-in last-page [:page-info :has-next-page?])))))

        (testing "live lookup/count hits share one dependency-aware cache"
          (let [live-acl
                (spiceomic/make-client
                 conn
                 {:coherence-authority :managed
                  :proof-mode :mutation
                  :cache {:remember-answers true}})
                count-resources-query (dissoc base-query :first)
                first-server
                (first (:data (eacl/lookup-resources live-acl base-query)))
                count-subjects-query {:resource first-server
                                      :permission :view
                                      :subject/type :user}
                disabled-acl
                (spiceomic/make-client conn {:cache cache/no-cache})
                resource-calls (atom 0)
                subject-calls (atom 0)
                original-count-resources impl/count-resources
                original-count-subjects impl/count-subjects]
            (with-redefs [impl/count-resources
                          (fn [db query]
                            (swap! resource-calls inc)
                            (original-count-resources db query))
                          impl/count-subjects
                          (fn [db query]
                            (swap! subject-calls inc)
                            (original-count-subjects db query))]
              (let [resource-cold (timed #(eacl/count-resources
                                           live-acl
                                           count-resources-query))
                    resource-hot (run-timed
                                  30
                                  #(eacl/count-resources
                                    live-acl
                                    count-resources-query))
                    subject-cold (timed #(eacl/count-subjects
                                          live-acl
                                          count-subjects-query))
                    subject-hot (run-timed
                                 30
                                 #(eacl/count-subjects
                                   live-acl
                                   count-subjects-query))
                    resource-live-calls @resource-calls
                    subject-live-calls @subject-calls
                    disabled-resource-times
                    (run-timed
                     10
                     #(eacl/count-resources
                       disabled-acl
                       count-resources-query))]
                (println
                 (format
                  "Count cache: resources cold=%.2fms/hot=%.3fms; subjects cold=%.2fms/hot=%.3fms; disabled median=%.2fms"
                  (:elapsed-ms resource-cold)
                  (median resource-hot)
                  (:elapsed-ms subject-cold)
                  (median subject-hot)
                  (median disabled-resource-times)))
                (is (= total-servers
                       (get-in resource-cold [:value :count])))
                (is (pos? (get-in subject-cold [:value :count])))
                (is (= 1 resource-live-calls)
                    "resource count computes once, then uses completed results")
                (is (= 1 subject-live-calls)
                    "subject count computes once, then uses completed results")))))))))

(deftest ^:benchmark recursive-traversal-prefix-benchmark
  (testing "Recursive continuation pagination advances reachable work without prefix replay"
    (with-mem-conn [conn []]
      (let [acl (seed-recursive-chain! conn {:chain-length 250
                                             :unrelated-count 2000})
            base-query {:subject       (->user "user-1")
                        :permission    :read
                        :resource/type :account
                        :first         25}
            stats1 (atom {})
            page1 (binding [impl.indexed/*recursive-traversal-stats* stats1]
                    (with-redefs [impl.indexed/can? (fn [& _]
                                                      (throw (ex-info "can? should not be called by recursive pagination" {})))]
                      (eacl/lookup-resources acl base-query)))
            stats2 (atom {})
            page2 (binding [impl.indexed/*recursive-traversal-stats* stats2]
                    (eacl/lookup-resources acl (assoc base-query
                                                      :after (get-in page1 [:page-info :end-cursor]))))
            previous (eacl/lookup-resources acl (-> base-query
                                                    (dissoc :first :after)
                                                    (assoc :last 25
                                                           :before (get-in page2 [:page-info :start-cursor]))))]
        (is (= 25 (count (:data page1))))
        (is (= 25 (count (:data page2))))
        (is (= (:data page1) (:data previous)))
        (is (<= (:emitted-results @stats1) 26))
        (is (< (:advanced-stream-datoms @stats1) 100)
            "first page should not scan the unrelated candidate universe")
        (is (= 1 (:continuation-hits @stats2))
            "second page resumes the first page's bounded continuation")
        (is (<= (:advanced-stream-datoms @stats2)
                (:advanced-stream-datoms @stats1))
            "second page advances new work instead of replaying a deeper prefix")
        (is (< (:advanced-stream-datoms @stats2) 200)
            "second page work should still be bounded by the reachable prefix")))))

(deftest ^:benchmark recursive-traversal-scaling-benchmark
  (testing "Recursive continuations scale linearly, replay is quadratic, and completed pages are reusable"
    (let [page-size 25
          trials 3
          measurements
          (mapv
           (fn [chain-length]
             (with-mem-conn [conn []]
               (let [_ (seed-recursive-chain!
                        conn
                        {:chain-length chain-length
                         :unrelated-count 0})
                     client-opts
                     {:page-token-key "recursive-scaling-benchmark"
                      :coherence-authority :managed
                      :proof-mode :mutation
                      ;; measures the continuation/page layer, not answers
                      :cache {:remember-answers false}}
                     query {:subject (->user "user-1")
                            :permission :read
                            :resource/type :account
                            :first page-size}
                     runs
                     (mapv
                      (fn [_]
                        (let [cached-client
                              (spiceomic/make-client conn client-opts)
                              replay-client
                              (spiceomic/make-client
                               conn
                               (assoc client-opts :cache cache/no-cache))
                              cached (recursive-walk cached-client query)
                              completed-hit
                              (recursive-walk cached-client query)
                              replayed (recursive-walk replay-client query)]
                          (is (= (:ids replayed) (:ids cached)))
                          (is (= (:ids cached) (:ids completed-hit)))
                          {:cached cached
                           :completed-hit completed-hit
                           :replayed replayed}))
                      (range trials))
                     cached-work
                     (get-in (first runs) [:cached :stats :derived-grants] 0)
                     replay-work
                     (get-in (first runs) [:replayed :stats :derived-grants] 0)
                     completed-hit-work
                     (get-in (first runs)
                             [:completed-hit :stats :derived-grants] 0)
                     cached-ms
                     (median (map #(get-in % [:cached :elapsed-ms]) runs))
                     completed-hit-ms
                     (median
                      (map #(get-in % [:completed-hit :elapsed-ms]) runs))
                     replayed-ms
                     (median (map #(get-in % [:replayed :elapsed-ms]) runs))
                     measurement
                     {:chain-length chain-length
                      :pages (get-in (first runs) [:cached :pages])
                      :cached-ms cached-ms
                      :completed-hit-ms completed-hit-ms
                      :replayed-ms replayed-ms
                      :cached-work cached-work
                      :completed-hit-work completed-hit-work
                      :replayed-work replay-work
                      :work-ratio (/ (double replay-work)
                                     (max 1.0 cached-work))
                      :time-ratio (/ replayed-ms
                                     (max 0.001 cached-ms))
                      :completed-hit-time-ratio
                      (/ replayed-ms (max 0.001 completed-hit-ms))}]
                 (is (= chain-length
                        (count (get-in (first runs) [:cached :ids]))))
                 (println "Recursive scaling:" measurement)
                 measurement)))
           [250 500 1000 2000 4000])]
      (let [largest (peek measurements)]
        (is (< (:cached-work largest)
               (* 2 (:chain-length largest)))
            "continuation work must remain linear in the result count")
        (is (zero? (:completed-hit-work largest))
            "a repeated exact page walk performs no graph traversal")
        (is (> (:work-ratio largest) 50.0)
            "the large graph must expose the eliminated prefix replay")
        (is (> (:time-ratio largest) 10.0)
            "the large graph should show a material wall-clock gain")))))

(deftest ^:benchmark recursive-page-size-work-benchmark
  (testing "Replay work follows N-squared/page-size while continuation work remains linear"
    (with-mem-conn [conn []]
      (let [chain-length 2000
            _ (seed-recursive-chain!
               conn
               {:chain-length chain-length
                :unrelated-count 0})
            client-opts
            {:page-token-key "recursive-page-size-benchmark"
             :coherence-authority :managed
             :proof-mode :mutation}
            measurements
            (mapv
             (fn [page-size]
               (let [query {:subject (->user "user-1")
                            :permission :read
                            :resource/type :account
                            :first page-size}
                     runs
                     (mapv
                      (fn [_]
                        (let [cached-client
                              (spiceomic/make-client conn client-opts)
                              cached (recursive-walk cached-client query)]
                          {:cached cached
                           :completed-hit
                           (recursive-walk cached-client query)
                           :replayed
                           (recursive-walk
                            (spiceomic/make-client
                             conn
                             (assoc client-opts :cache cache/no-cache))
                            query)}))
                      (range 3))
                     cached (:cached (first runs))
                     completed-hit (:completed-hit (first runs))
                     replayed (:replayed (first runs))
                     measurement
                     {:page-size page-size
                      :pages (:pages cached)
                      :cached-work (get-in cached [:stats :derived-grants] 0)
                      :replayed-work (get-in replayed [:stats :derived-grants] 0)
                      :cached-ms
                      (median (map #(get-in % [:cached :elapsed-ms]) runs))
                      :completed-hit-ms
                      (median
                       (map #(get-in % [:completed-hit :elapsed-ms]) runs))
                      :replayed-ms
                      (median (map #(get-in % [:replayed :elapsed-ms]) runs))}]
                 (is
                  (every?
                   (fn [run]
                     (= (get-in run [:cached :ids])
                        (get-in run [:completed-hit :ids])
                        (get-in run [:replayed :ids])))
                   runs))
                 (is (zero? (get-in completed-hit
                                    [:stats :derived-grants] 0)))
                 (println "Recursive page-size scaling:" measurement)
                 measurement))
             [10 25 100 250 1000])]
        (is (every? #(< (:cached-work %) (* 2 chain-length))
                    measurements))
        (is (apply > (map :replayed-work measurements))
            "larger pages reduce the number of prefixes replayed")))))

(deftest ^:benchmark recursive-cache-cost-breakdown-benchmark
  (testing "Recursive timing separates traversal, cache I/O, tokens, and boundary coercion"
    (with-mem-conn [conn []]
      (let [chain-length 1000
            _ (seed-recursive-chain!
               conn
               {:chain-length chain-length
                :unrelated-count 0})
            client-opts
            {:page-token-key "recursive-cost-breakdown"
             :coherence-authority :managed
             :proof-mode :mutation
             ;; This breaks down the cost of the recursive PAGE path, where a
             ;; completed page is read back and nothing new is published.
             ;; Remembering answers adds its own publications on top and would
             ;; make the "no publications" assertion measure the wrong layer.
             :cache {:remember-answers false}}
            query {:subject (->user "user-1")
                   :permission :read
                   :resource/type :account
                   :first 25}
            targets
            [[:recursive-engine
              #'impl/lookup-resources]
             [:cache-entry-lookup
              #'cache/safe-entry-value]
             [:cache-entry-store
              #'cache/safe-store-entry!]
             [:token-decrypt
              #'spiceomic/decrypt-page-token]
             [:token-encrypt
              #'spiceomic/encrypt-page-token]
             [:boundary-entity
              #'d/entity]
             [:boundary-coercion
              (ns-resolve 'eacl.datomic.core 'coerce-lookup-page)]]
            continuation-breakdown
            (into {}
                  (map
                   (fn [[label target-var]]
                     (let [client (spiceomic/make-client conn client-opts)]
                       [label
                        (profile-var
                         target-var
                         1
                         #(recursive-walk client query))])))
                  targets)
            hot-breakdown
            (into {}
                  (map
                   (fn [[label target-var]]
                     (let [client (spiceomic/make-client conn client-opts)]
                       (recursive-walk client query)
                       [label
                        (profile-var
                         target-var
                         1
                         #(recursive-walk client query))])))
                  targets)]
        (println "Recursive continuation breakdown:" continuation-breakdown)
        (println "Recursive completed-page breakdown:" hot-breakdown)
        (is (every? (comp pos? :calls val) continuation-breakdown))
        (is (zero? (get-in hot-breakdown
                           [:cache-entry-store :calls]))
            "completed recursive pages need lookups but no publications")))))

(deftest ^:benchmark count-miss-retained-memory-benchmark
  (testing "Broad count misses use a bounded legacy page or a compact generated renderer"
    (with-mem-conn [conn []]
      (let [retention-config (assoc bench-config :num-accounts 40)
            total-servers
            (* (:num-accounts retention-config)
               (:servers-per-acct retention-config))
            _ (seed-multipath! conn retention-config)
            rejecting-store
            (cache/local-store {:max-weight 256
                                :max-entry-weight 256
                                :max-entries 8})
            provider-calls (atom 0)
            failing-store
            (reify cache/CacheStore
              (lookup [_ _]
                (swap! provider-calls inc)
                (throw (ex-info "provider unavailable" {})))
              (store! [_ _ _ _ _]
                (swap! provider-calls inc)
                (throw (ex-info "provider unavailable" {})))
              (evict! [_ _] false)
              (clear! [_] nil)
              (stats [_] {}))
            clients
            [[:disabled (spiceomic/make-client conn {:cache cache/no-cache})]
             [:admission-rejected
              (spiceomic/make-client
               conn
               {:cache {:store rejecting-store
                        :remember-answers true}})]
             [:provider-failure
              (spiceomic/make-client
               conn
               {:cache {:store failing-store
                        :remember-answers true}})]]
            query {:subject (->user "super-user")
                   :permission :view
                   :resource/type :server}]
        (doseq [[label client] clients]
          (let [legacy-stats (atom {})
                traversal-stats (atom {})
                result
                ;; Completed-cache misses may compute on a coordinator thread.
                ;; Observe the generated result at its publication boundary,
                ;; which remains exact even when that work is asynchronous.
                (with-generated-traversal-observer
                  traversal-stats
                  (fn []
                    (binding [impl.indexed/*count-stats* legacy-stats]
                      (eacl/count-resources client query))))]
            (is (= total-servers (:count result)))
            (if-let [retained
                     (:generated-retained-logical-units
                      @traversal-stats)]
              (let [counters
                    (:generated-dimensional-counters
                     @traversal-stats)]
                (println
                 (format
                  (str "Broad count %s: count=%d, generated retained "
                       "logical units=%d")
                  (name label)
                  (:count result)
                  retained))
                (is (= total-servers (:emitted-results counters))
                    "the scalar all-count ordinal counts every unique result")
                ;; This fixture has fewer than one intermediate grant/consumer
                ;; per result. Three result-sized units leave room for that
                ;; traversal proof state but fail if the renderer regresses to
                ;; retaining either emitted or delivered result sequences.
                (is (<= retained (+ (* 3 total-servers) 1024))
                    "generated all-count retains traversal proof state, not rendered result copies"))
              (do
                (println
                 (format
                  "Broad count %s: count=%d, pages=%d, max retained page=%d EIDs"
                  (name label)
                  (:count result)
                  (:pages @legacy-stats)
                  (:max-page-eids @legacy-stats)))
                (is (= 2 (:pages @legacy-stats)))
                (is (<= (:max-page-eids @legacy-stats) 16384)
                    "legacy count misses never retain the full 20,000-result head")))))
        (is (zero? (:entries (cache/stats rejecting-store)))
            "a rejected count answer leaves no retained cache entry")
        (is (zero? @provider-calls)
            "private completed answers never consult an untrusted provider")))))

;; --- Permission-check hot path ----------------------------------------------
;;
;; can? is the highest-frequency EACL call. A connection-backed client reads
;; its schema generation once at construction, then new db values caused by
;; unrelated transactions do no schema reads or definition scans.
;;
;; Two numbers matter and both are reported:
;;   warm — repeated checks against the client generation
;;   cold — explicit cache eviction before each call (path-resolution cost)

;; Current-generation hits include snapshot selection and native key lookup,
;; but no proof construction, canonicalization, provider I/O, or crypto.
(def ^:private can-warm-threshold-us 1000)
(def ^:private can-cold-threshold-us 1500)
(def ^:private can-completed-cache-threshold-us 1000)
(def ^:private can-warmup-calls 15000)
(def ^:private can-warm-measurement-batches 3)
(def ^:private can-warm-samples-per-batch 5000)
(def ^:private can-target-local-small-resources 16)
(def ^:private can-target-local-large-resources 1024)

(deftest ^:benchmark permission-check-benchmark
  (testing "can? throughput, warm and cold permission paths"
    (with-mem-conn [conn []]
      (let [acl     (seed-multipath! conn {:num-accounts     5
                                           :teams-per-acct   2
                                           :vpcs-per-acct    1
                                           :servers-per-acct 20})
            subject (->user "super-user")
            server-id
            (d/q '[:find ?id .
                   :where
                   [?server :server/name _]
                   [?server :eacl/id ?id]]
                 (d/db conn))
            server  (->server server-id)
            check   #(eacl/can? acl subject :view server)
            live-acl
            (spiceomic/make-client
             conn
             {:coherence-authority :managed
              :proof-mode :mutation
              :cache {:kind-max-weight {:can? (* 2 1024 1024)}
                      :two-hit-kinds #{:can?}
                      :remember-answers true}})
            live-check #(eacl/can? live-acl subject :view server)]

        ;; HotSpot's highest tier is not guaranteed to compile this generated
        ;; call graph after only 2,000 entries. Measure steady-state service
        ;; time after a C2-sized warmup, then aggregate independent batch
        ;; medians so one compilation/GC transition cannot decide the gate.
        (run-timed can-warmup-calls check)

        (let [batch-medians-ms
              (mapv
               (fn [_]
                 (median
                  (run-timed can-warm-samples-per-batch check)))
               (range can-warm-measurement-batches))
              warm-us (* 1000.0 (median batch-medians-ms))]
          (println
           (format
            "can? warm: median=%.2fus, batch-medians-us=%s"
            warm-us
            (pr-str (mapv #(* 1000.0 %) batch-medians-ms))))
          (is (< warm-us can-warm-threshold-us)
              (format "REGRESSION: warm can? median %.2fus exceeds %dus" warm-us can-warm-threshold-us)))

        (testing "with permission paths forced cold on every call"
          (let [cold-us (* 1000.0
                           (median (run-timed 500
                                              (fn []
                                                (impl.indexed/evict-permission-paths-cache!
                                                 @(:schema-state acl))
                                                (check)))))]
            (println (format "can? cold paths: median=%.2fus" cold-us))
            (is (< cold-us can-cold-threshold-us)
                (format "REGRESSION: cold can? median %.2fus exceeds %dus; path calculation got more expensive"
                        cold-us can-cold-threshold-us))))

        (testing "completed Boolean cache"
          (let [calls (atom 0)
                original impl/can?]
            (with-redefs [impl/can?
                          (fn [db internal-subject permission internal-resource]
                            (swap! calls inc)
                            (original db internal-subject permission internal-resource))]
              ;; The private exact-current generation admits the first complete
              ;; result; every identical call on that immutable DB is a hit.
              (is (true? (live-check)))
              (is (true? (live-check)))
              (run-timed 2000 live-check)
              (let [live-us
                    (* 1000.0 (median (run-timed 5000 live-check)))]
                (println
                 (format "can? completed-cache hit: median=%.2fus" live-us))
                (is (= 1 @calls))
                (is (< live-us can-completed-cache-threshold-us))))))

        (testing "completed Boolean cache cost breakdown"
          ;; Measurements overlap by construction: the basic context contains
          ;; DB selection, and the cached-basic layer contains the native
          ;; current-generation resolution.
          (dotimes [_ 2000]
            (live-check))
          (let [iterations 5000
                targets
                [[:db
                  #'d/db]
                 [:entid
                  #'d/entid]
                 [:capture-basic-context
                  (ns-resolve 'eacl.datomic.core
                              'capture-basic-result-context)]
                 [:current-cache
                  #'shared-cache/resolve-current!]
                 [:cached-basic-authorization
                  (ns-resolve 'eacl.datomic.core
                              'cached-basic-authorization-result)]]
                breakdown
                (into {}
                      (map (fn [[label target-var]]
                             [label
                              (profile-var
                               target-var iterations live-check)]))
                      targets)]
            (println "can? completed-cache breakdown:" breakdown)
            (is (every? (comp pos? :calls val) breakdown)))))))

  (testing "generated point checks stay target-local as reachable resources grow"
    (with-mem-conn [conn schema/v7-schema]
      (let [acl
            (spiceomic/make-client
             conn
             {:coherence-authority :managed
              :proof-mode :mutation
              :cache cache/no-cache})
            subject (eacl/spice-object :user "scale-user")
            documents
            (fn [start amount]
              (mapv
               #(eacl/spice-object :document (str "scale-document-" %))
               (range start (+ start amount))))
            add-documents!
            (fn [resources]
              @(d/transact
                conn
                (mapv (fn [resource] {:eacl/id (:id resource)})
                      resources))
              (eacl/create-relationships!
               acl
               (mapv
                #(Relationship subject :viewer %)
                resources)))
            observe
            (fn [resource]
              (let [stats (atom nil)]
                (is
                 (true?
                  (with-generated-traversal-observer
                    stats
                    #(eacl/can? acl subject :view resource))))
                (:generated-dimensional-counters @stats)))
            small
            (documents 0 can-target-local-small-resources)
            large
            (documents
             can-target-local-small-resources
             can-target-local-large-resources)]
        (is (satisfies?
             verified/DecisionKernel
             (get-in acl [:opts :decision-kernel :kernel]))
            "the release benchmark must exercise the generated authority")
        (eacl/write-schema!
         acl
         "definition user {}
          definition document {
            relation viewer: user
            permission view = viewer
          }")
        @(d/transact conn [{:eacl/id "scale-user"}])
        (add-documents! small)
        (let [small-work (observe (peek small))]
          (add-documents! large)
          (let [large-work (observe (peek large))
                target-local-keys
                [:backend-commands
                 :adapter-fetched-values
                 :engine-consumed-values
                 :unique-grants
                 :rule-applications
                 :render-advances]]
            (println
             "can? target-local scaling:"
             {:small-resources can-target-local-small-resources
              :large-resources
              (+ can-target-local-small-resources
                 can-target-local-large-resources)
              :small-work (select-keys small-work target-local-keys)
              :large-work (select-keys large-work target-local-keys)})
            (is (= (select-keys small-work target-local-keys)
                   (select-keys large-work target-local-keys))
                "point-check logical/backend work must not grow with unrelated resources reachable from the subject")
            (is (= 1 (:backend-commands large-work))
                "a direct point check performs one target-anchored backend scan")))))))

(def ^:private cache-proof-benchmark-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(deftest ^:benchmark cache-proof-strategy-churn-benchmark
  (testing "mutation/content proofs, global invalidation, and no-cache"
    (with-mem-conn [conn schema/v7-schema]
      (let [mutation-store (cache/local-store)
            content-store (cache/local-store)
            global-store (cache/local-store)
            common {:page-token-key "cache-proof-benchmark"
                    :zed-token-key "cache-proof-benchmark-zed"}
            managed
            (fn [store]
              (spiceomic/make-client
               conn
               (assoc common
                      :coherence-authority :managed
                      :proof-mode :mutation
                      :cache (if store
                               {:store store :remember-answers true}
                               cache/no-cache))))
            mutation-client (managed mutation-store)
            writer mutation-client
            content-client
            (spiceomic/make-client
             conn
             (assoc common
                    :coherence-authority :unknown
                    :proof-mode :content
                    :cache {:store content-store
                            :remember-answers true}))
            global-client (managed global-store)
            no-cache-client (managed nil)
            user (->user "benchmark-user")
            account (->account "benchmark-account")
            relationship (Relationship user :owner account)
            _ (eacl/write-schema! writer cache-proof-benchmark-schema)
            _ @(d/transact conn [{:eacl/id "benchmark-user"}
                                 {:eacl/id "benchmark-account"}])
            _ (eacl/create-relationship! writer relationship)
            strategies
            [[:mutation-proof mutation-client nil]
             [:content-proof content-client nil]
             [:global-invalidation global-client global-store]
             [:no-cache no-cache-client nil]]
            iterations 12
            measure
            (fn [client store expected]
              (when store
                (cache/clear! store))
              (let [start (System/nanoTime)
                    value (eacl/can? client user :admin account)
                    elapsed (/ (double (- (System/nanoTime) start)) 1000.0)]
                (is (= expected value))
                elapsed))
            unrelated
            (into
             {}
             (for [[label client clear-store] strategies]
               (do
                 (eacl/can? client user :admin account)
                 [label
                  (median
                   (for [i (range iterations)]
                     (do
                       @(d/transact
                         conn
                         [{:eacl/id
                           (str "benchmark-unrelated-"
                                (name label)
                                "-"
                                i)}])
                       (measure client clear-store true))))])))
            relevant
            (into
             {}
             (for [[label client clear-store] strategies]
               (do
                 (eacl/can? client user :admin account)
                 [label
                  (median
                   (for [i (range iterations)]
                     (let [grant? (odd? i)]
                       (if grant?
                         (eacl/create-relationship! writer relationship)
                         (eacl/delete-relationship! writer relationship))
                       (measure client clear-store grant?))))])))]
        (prn {:benchmark :cache-proof-strategy-churn
              :unit :microseconds-per-read
              :iterations iterations
              :unrelated-churn unrelated
              :relevant-churn relevant})
        (is (= (set (keys unrelated))
               #{:mutation-proof
                 :content-proof
                 :global-invalidation
                 :no-cache}))
        (is (= (set (keys relevant))
               (set (keys unrelated))))))))
