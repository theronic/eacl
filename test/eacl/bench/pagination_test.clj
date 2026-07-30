(ns eacl.bench.pagination-test
  "Multi-path pagination benchmarks for regression detection.

   Run with: clojure -M:bench
   These tests are excluded from normal test runs via ^:benchmark metadata.

   Tests a 4-path permission graph (server.view = account->admin + team->admin + vpc->admin + shared_admin)
   which exercises the cursor-tree merge algorithm with multiple divergent arrow paths."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set :as set]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as spiceomic]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
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
  (mapcat #(impl/tx-relationship db % {:allow-tempids? true}) relationships))

;; --- Seeding ---

(defn seed-multipath!
  "Seeds a multi-path permission graph. Returns the acl client."
  [conn {:keys [num-accounts teams-per-acct vpcs-per-acct servers-per-acct]}]
  (let [acl (spiceomic/make-client conn {:entity->object-id (fn [ent] (:eacl/id ent))
                                         :object-id->ident  (fn [obj-id] [:eacl/id obj-id])})]
    @(d/transact conn basic-attrs)
    @(d/transact conn schema/v7-schema)
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

    acl))

(defn seed-recursive-chain!
  [conn {:keys [chain-length unrelated-count]}]
  (let [acl (spiceomic/make-client conn {:entity->object-id (fn [ent] (:eacl/id ent))
                                         :object-id->ident  (fn [obj-id] [:eacl/id obj-id])})]
    @(d/transact conn schema/v7-schema)
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
    acl))

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

;; --- Regression thresholds ---
;;
;; With 15k servers and 4 arrow paths:
;; - cursor-tree branch: ~2-10ms per page (depending on hardware)
;; - lazy-merge-sort regression: ~30-70ms per page (6x slower)
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
        allowed-late (max (+ first-median page-drift-threshold-ms)
                          (* first-median page-drift-threshold-ratio))]
    (println (format "%s pagination (%d pages x %d runs): early=%.2fms/page, late=%.2fms/page, max-page-median=%.2fms"
                     label pages-per-run pagination-iterations first-median last-median max-page-median))
    (is (< max-page-median per-page-threshold-ms)
        (format "REGRESSION: %s max page median %.2fms exceeds %dms threshold"
                label max-page-median per-page-threshold-ms))
    (is (< last-median allowed-late)
        (format "REGRESSION: %s late-page median %.2fms exceeds allowed %.2fms; pagination may be re-scanning or ignoring cursors"
                label last-median allowed-late))))

(defn- deep-page-work-samples
  "Walks a complete forward result set and records traversal calls at selected
  depths. Counting index traversal entry points is deterministic and catches
  frontier regressions without relying on noisy wall-clock thresholds."
  [acl base-query page-count sample-pages]
  (let [calls (atom 0)
        original-subject->resources impl.indexed/subject->resources]
    (with-redefs [impl.indexed/subject->resources
                  (fn [& args]
                    (swap! calls inc)
                    (apply original-subject->resources args))]
      (loop [page-index 0
             boundary nil
             seen-ids #{}
             samples {}]
        (reset! calls 0)
        (let [page (eacl/lookup-resources acl
                                          (cond-> base-query
                                            boundary (assoc :after boundary)))
              next-boundary (get-in page [:page-info :end-cursor])
              seen-ids' (into seen-ids (map :id (:data page)))
              samples' (cond-> samples
                         (contains? sample-pages page-index)
                         (assoc page-index {:boundary boundary
                                            :calls @calls}))]
          (if (= page-count (inc page-index))
            {:seen-ids seen-ids'
             :samples samples'
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
          (let [page-count (quot total-servers page-size)
                sample-pages #{0 (quot page-count 2) (dec page-count)}
                {:keys [seen-ids samples last-page]}
                (deep-page-work-samples acl base-query page-count sample-pages)
                first-page-calls (get-in samples [0 :calls])
                middle-page-calls (get-in samples [(quot page-count 2) :calls])
                last-page-calls (get-in samples [(dec page-count) :calls])
                page-medians (into {}
                                   (map (fn [[page-index {:keys [boundary]}]]
                                          [page-index
                                           (median
                                            (run-timed 20
                                                       #(eacl/lookup-resources
                                                         acl
                                                         (cond-> base-query
                                                           boundary (assoc :after boundary)))))]))
                                   samples)]
            (println (format "Traversal calls by depth: first=%d, middle=%d, last=%d"
                             first-page-calls middle-page-calls last-page-calls))
            (println (format "Deep-page medians: first=%.2fms, middle=%.2fms, last=%.2fms"
                             (get page-medians 0)
                             (get page-medians (quot page-count 2))
                             (get page-medians (dec page-count))))
            (is (= total-servers (count seen-ids))
                "A complete frontier-paginated walk must return every server exactly once")
            (is (= sample-pages (set (keys samples))))
            (is (< middle-page-calls first-page-calls)
                "Intermediate frontiers should reduce traversal work by the middle page")
            (is (< last-page-calls middle-page-calls)
                "Exhausted path markers should reduce traversal work further near exhaustion")
            (is (false? (get-in last-page [:page-info :has-next-page?])))))

        (testing "live lookup/count hits share one dependency-aware cache"
          (let [live-acl
                (spiceomic/make-client
                 conn
                 {:entity->object-id (fn [ent] (:eacl/id ent))
                  :object-id->ident (fn [obj-id] [:eacl/id obj-id])
                  :cache (assoc (cache/local-context)
                                :live-results? true)})
                count-resources-query (dissoc base-query :first)
                first-server
                (first (:data (eacl/lookup-resources live-acl base-query)))
                count-subjects-query {:resource first-server
                                      :permission :view
                                      :subject/type :user}
                disabled-acl
                (spiceomic/make-client conn {:cache false})
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
                (is (= 1 @resource-calls)
                    "resource count computes once, then uses completed results")
                (is (= 1 @subject-calls)
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

(deftest ^:benchmark count-miss-retained-memory-benchmark
  (testing "Broad count misses retain at most one bounded page"
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
            [[:disabled (spiceomic/make-client conn {:cache false})]
             [:admission-rejected
              (spiceomic/make-client
               conn
               {:cache {:store rejecting-store}})]
             [:provider-failure
              (spiceomic/make-client
               conn
               {:cache {:store failing-store}})]]
            query {:subject (->user "super-user")
                   :permission :view
                   :resource/type :server}]
        (doseq [[label client] clients]
          (let [stats (atom {})
                result
                (binding [impl.indexed/*count-stats* stats]
                  (eacl/count-resources client query))]
            (println
             (format
              "Broad count %s: count=%d, pages=%d, max retained page=%d EIDs"
              (name label)
              (:count result)
              (:pages @stats)
              (:max-page-eids @stats)))
            (is (= total-servers (:count result)))
            (is (= 2 (:pages @stats)))
            (is (<= (:max-page-eids @stats) 16384)
                "count misses never retain the full 20,000-result head")))
        (is (zero? (:entries (cache/stats rejecting-store)))
            "a rejected count answer leaves no retained cache entry")
        (is (pos? @provider-calls)
            "provider failures exercise recomputation rather than changing the result")))))

;; --- Permission-check hot path ----------------------------------------------
;;
;; can? is the highest-frequency EACL call. A connection-backed client reads
;; its schema generation once at construction, then new db values caused by
;; unrelated transactions do no schema reads or definition scans.
;;
;; Two numbers matter and both are reported:
;;   warm — repeated checks against the client generation
;;   cold — explicit cache eviction before each call (path-resolution cost)

(def ^:private can-warm-threshold-us 25)
(def ^:private can-cold-threshold-us 250)

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
            live-context
            (cache/local-context
             {:kind-max-weight {:can? (* 2 1024 1024)}
              :two-hit-kinds #{:can?}})
            live-acl
            (spiceomic/make-client
             conn
             {:cache (assoc live-context :live-results? true)})
            live-check #(eacl/can? live-acl subject :view server)]

        (run-timed 2000 check)                              ; warm JIT + caches

        (let [warm-us (* 1000.0 (median (run-timed 5000 check)))]
          (println (format "can? warm: median=%.2fus" warm-us))
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
              ;; Two-hit admission computes twice; the third and later calls
              ;; reuse the same typed store as lookup/count entries.
              (is (true? (live-check)))
              (is (true? (live-check)))
              (run-timed 2000 live-check)
              (let [live-us
                    (* 1000.0 (median (run-timed 5000 live-check)))]
                (println
                 (format "can? completed-cache hit: median=%.2fus" live-us))
                (is (= 2 @calls))
                (is (< live-us can-cold-threshold-us))))))))))
