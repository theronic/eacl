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
            [eacl.cursor :as cursor]
            [eacl.datomic.core :as spiceomic]
            [eacl.datomic.db :as ddb]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
            [eacl.engine.stable-page :as stable-page]
            [eacl.engine.v8 :as engine]
            [eacl.relay :as relay]
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
(defn seed-multipath!
  "Seeds a multi-path permission graph. Returns the acl client."
  [conn {:keys [num-accounts teams-per-acct vpcs-per-acct servers-per-acct]}]
  ;; Disable completed answers so these benchmarks observe the traversal layer.
  ;; With the default cache, a repeated identical page is served
  ;; from the answer cache and the engine is never entered, so traversal-call
  ;; counts read as zero and prove nothing.
  @(d/transact conn (into schema/v7-schema basic-attrs))
  (let [acl (spiceomic/make-client
             conn
             {:cache shared-cache/no-cache})]
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
     {:cache shared-cache/no-cache})))

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
     {:cache shared-cache/no-cache})))

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
(defn- profile-var
  [target-label target-var iterations f]
  (when-not (var? target-var)
    (throw
     (ex-info
      "Benchmark target does not resolve to a Var."
      {:target target-label
       :value target-var})))
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
(deftest ^:benchmark recursive-cache-cost-breakdown-benchmark
  (testing "Recursive timing separates traversal, cache I/O, tokens, and boundary rendering"
    (with-mem-conn [conn []]
      (let [chain-length 1000
            _ (seed-recursive-chain!
               conn
               {:chain-length chain-length
                :unrelated-count 0})
            client-opts
            {:security-key "recursive-cost-breakdown00000000"
             ;; Managed caching is required to exercise both the cold
             ;; continuation-store path and the completed-answer read path.
             :cache {}}
            query {:subject (->user "user-1")
                   :permission :read
                   :resource/type :account
                   :first 25}
            targets
            [[:recursive-engine
              #'engine/lookup-resources]
             [:checkpoint-lookup
              (ns-resolve 'eacl.engine.stable-page 'checkpoint-hit)]
             [:checkpoint-store
              #'stable-page/checkpoint-put!]
             [:token-decode
              #'cursor/token->authenticated-cursor]
             [:token-encrypt
              #'cursor/cursor->token]
             [:boundary-entity
              #'d/entity]
             [:page-render
              #'relay/externalize-page]]
            continuation-breakdown
            (into {}
                  (map
                   (fn [[label target-var]]
                     (let [client (spiceomic/make-client conn client-opts)]
                       [label
                        (profile-var
                         label
                         target-var
                         1
                         #(recursive-walk client query))])))
                  targets)
            completed-answer-breakdown
            (into {}
                  (map
                   (fn [[label target-var]]
                     (let [client (spiceomic/make-client conn client-opts)]
                       (recursive-walk client query)
                       [label
                        (profile-var
                         label
                         target-var
                         1
                         #(recursive-walk client query))])))
                  targets)]
        (println "Recursive continuation breakdown:" continuation-breakdown)
        (println "Recursive completed-answer breakdown:"
                 completed-answer-breakdown)
        (is (every? (comp pos? :calls val) continuation-breakdown))
        (is (every?
             #(zero? (get-in completed-answer-breakdown [% :calls]))
             [:recursive-engine
              :checkpoint-lookup
              :checkpoint-store
              :token-decode
              :token-encrypt
              :boundary-entity
              :page-render])
            "exact transport hits bypass traversal, cursor, identity, and rendering work")))))
(def ^:private cache-proof-benchmark-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(deftest ^:benchmark cache-proof-strategy-churn-benchmark
  (testing "managed proof reuse, global invalidation, and no-cache"
    (with-mem-conn [conn schema/v7-schema]
      (let [common {:security-key "cache-proof-benchmark00000000000"}
            managed #(spiceomic/make-client conn (assoc common :cache %))
            proof-client (managed {})
            writer proof-client
            global-client (managed {})
            no-cache-client (managed shared-cache/no-cache)
            user (->user "benchmark-user")
            account (->account "benchmark-account")
            relationship (Relationship user :owner account)
            _ (eacl/write-schema! writer cache-proof-benchmark-schema)
            _ @(d/transact conn [{:eacl/id "benchmark-user"}
                                 {:eacl/id "benchmark-account"}])
            _ (eacl/create-relationship! writer relationship)
            strategies
            [[:proof-reuse proof-client nil]
             [:global-invalidation global-client
              #(spiceomic/expire-cache! global-client)]
             [:no-cache no-cache-client nil]]
            iterations 12
            measure
            (fn [client clear-cache! expected]
              (when clear-cache!
                (clear-cache!))
              (let [start (System/nanoTime)
                    value (eacl/can? client user :admin account)
                    elapsed (/ (double (- (System/nanoTime) start)) 1000.0)]
                (is (= expected value))
                elapsed))
            unrelated
            (into
             {}
             (for [[label client clear-cache!] strategies]
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
                       (measure client clear-cache! true))))])))
            relevant
            (into
             {}
             (for [[label client clear-cache!] strategies]
               (do
                 (eacl/can? client user :admin account)
                 [label
                  (median
                   (for [i (range iterations)]
                     (let [grant? (odd? i)]
                       (if grant?
                         (eacl/create-relationship! writer relationship)
                         (eacl/delete-relationship! writer relationship))
                       (measure client clear-cache! grant?))))])))]
        (prn {:benchmark :cache-proof-strategy-churn
              :unit :microseconds-per-read
              :iterations iterations
              :unrelated-churn unrelated
              :relevant-churn relevant})
        (is (= (set (keys unrelated))
               #{:proof-reuse
                 :global-invalidation
                 :no-cache}))
        (is (= (set (keys relevant))
               (set (keys unrelated))))))))
