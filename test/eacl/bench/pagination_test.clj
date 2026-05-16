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
            [eacl.datomic.core :as spiceomic]
            [eacl.datomic.impl :as impl :refer [Relationship]]
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

(def basic-attrs
  [{:db/ident :server/name, :db/cardinality :db.cardinality/one, :db/valueType :db.type/string, :db/index true}
   {:db/ident :team/name, :db/cardinality :db.cardinality/one, :db/valueType :db.type/string, :db/index true}
   {:db/ident :vpc/name, :db/cardinality :db.cardinality/one, :db/valueType :db.type/string, :db/index true}])

(defn- tx-relationships
  [db relationships]
  (mapcat #(impl/tx-relationship db %) relationships))

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
      (doseq [[n account-uuid] (map-indexed vector account-uuids)]
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
                  "Reverse pages should not have overlapping results"))))))))
