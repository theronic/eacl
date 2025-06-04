(ns eacl.benchmark-test
  (:require [criterium.core :as crit]
            [eacl.core :as eacl]
            [eacl.datomic.core :as spiceomic]
            [eacl.datomic.impl :as impl :refer [Relation Relationship Permission]]
            [eacl.datomic.schema :as schema]
            [datomic.api :as d]
            [eacl.datomic.fixtures :as fixtures :refer [->account ->user ->server]]
            [clojure.test :as t :refer [deftest testing is]]
            [clojure.tools.logging :as log]))

;(defn rand-subject [])

(defn tx! [conn tx-data]
  ;(prn tx-data)
  @(d/transact conn tx-data))

(defn ids->tempid-map [uuid-coll]
  (->> uuid-coll
       (reduce (fn [acc uuid]
                 (assoc acc uuid (d/tempid :db.part/user)))
               {})))

(defn make-account-user-txes [account-tempid n]
  (let [user-uuids        (repeatedly n d/squuid)
        user-uuid->tempid (ids->tempid-map user-uuids)]
    (for [user-uuid user-uuids]
      (let [user-tempid (user-uuid->tempid user-uuid)]
        [{:db/id         user-tempid
          :resource/type :user
          :entity/id     (str user-uuid)
          :user/account  account-tempid}                    ; only to police permission checks.
         (impl/Relationship user-tempid :owner account-tempid)]))))

(defn make-account-server-txes [account-tempid n]
  (let [server-uuids        (repeatedly n d/squuid)
        server-uuid->tempid (ids->tempid-map server-uuids)]

    (for [server-uuid server-uuids]
      (let [server-tempid (server-uuid->tempid server-uuid)]
        [{:db/id          server-tempid
          :resource/type  :server
          :server/account account-tempid                    ; only to police permission checks.
          :entity/id      (str server-uuid)
          :server/name    (str "Servers " server-uuid)}
         (impl/Relationship account-tempid :account server-tempid)]))))

(defn make-account-txes [{:keys [num-users num-servers]} account-uuid]
  (let [account-tempid (d/tempid :db.part/user)
        account-tx     {:db/id         account-tempid
                        :resource/type :account
                        :entity/id     (str account-uuid)}
        user-txes      (make-account-user-txes account-tempid num-users)
        server-txes    (make-account-server-txes account-tempid num-servers)]
    (concat [account-tx] (flatten user-txes) (flatten server-txes))))

(defn server->user-ids [db server-id]
  (d/q '[:find [?user-id ...]
         :in $ ?server-id
         :where
         [?server :entity/id ?server-id]
         [?server :server/account ?account]
         [?user :user/account ?account]
         [?user :entity/id ?user-id]]
       db server-id))

(defn setup-benchmark [db]
  {:accounts (d/q '[:find [?account-id ...]
                    :where
                    [?account :resource/type :account]
                    [?account :entity/id ?account-id]]
                  db)
   :users    (d/q '[:find [?user-id ...]
                    :where
                    [?user :resource/type :user]
                    [?user :entity/id ?user-id]]
                  db)
   :servers  (d/q '[:find [?server-id ...]
                    :where
                    [?server :resource/type :server]
                    [?server :entity/id ?server-id]]
                  db)})

(defn run-benchmark [!counter client {:keys [accounts users servers]}]
  ; now we cross-check each user for server and police that the value is correct
  ; do we need to shuffle these for accurate test?
  (doall (for [server-id servers
               user-id   users]
           (do
             (swap! !counter inc)
             [user-id server-id (eacl/can? client (->user user-id) :view (->server server-id))]))))

(defn check-results [server->user-set matrix]
  (for [[user-id server-id actual] matrix
        :let [user-set (server->user-set server-id)
              expected (contains? user-set server-id)]]
    [actual expected]))

(deftest eacl-benchmarks
  ; todo switch to with-mem-conn.
  (def datomic-uri "datomic:dev://localhost:4597/eacl-benchmark")
  (d/delete-database datomic-uri)
  (d/create-database datomic-uri)
  (def conn (d/connect datomic-uri))

  (comment

    (d/q '[:find (count ?account) .
           :where
           [?account :resource/type :account]]
         (d/db conn))

    (d/q '[:find (count ?server) .
           :where
           [?server :resource/type :server]]
         (d/db conn))

    (d/q '[:find (count ?user) .
           :where
           [?user :resource/type :user]]
         (d/db conn))

    (def test-account (d/q '[:find (rand ?account-uuid) .
                             :where
                             [?account :resource/type :account]
                             [?account :entity/id ?account-uuid]]
                           (d/db conn)))

    (def test-user (d/q '[:find (rand ?user-uuid) .
                          :in $ ?account-uuid
                          :where
                          [?user :user/account ?account]
                          [?user :entity/id ?user-uuid]]
                        (d/db conn) test-account))

    (def client (spiceomic/make-client conn))

    (defn rand-user [db]
      (d/q '[:find (rand ?user-uuid) .
             :in $
             :where
             [?user :user/account ?account]
             [?user :entity/id ?user-uuid]]
           db))

    (let [test-user (rand-user (d/db conn))]
      (prn 'test-user test-user)
      (time (eacl/lookup-resources client {:resource/type :server
                                           :permission    :view
                                           :subject       (->user test-user)})))

    (defn rand-server [db]
      (d/q '[:find (rand ?server-uuid) .
             :in $
             :where
             [?server :server/account ?account]
             [?server :entity/id ?server-uuid]]
           db))

    (let [test-server (rand-server (d/db conn))]
      (prn 'test-server test-server)
      (time (eacl/lookup-subjects client {:resource     (->server test-server)
                                          :permission   :view
                                          :subject/type :user})))

    (eacl/read-relationships client {:resource/type :account})
    (eacl/read-relationships client {:resource/type :server})
    ())

  (testing "Transact EACL Datomic Schema"
    (tx! conn (concat schema/v4-schema)))

  (testing "Transact a realistic EACL Permission Schema"
    (tx! conn fixtures/base-fixtures))

  (testing "some schema to police our data"
    (tx! conn [{:db/ident       :server/name
                :db/doc         "Just to add some real data into the mix."
                :db/cardinality :db.cardinality/one
                :db/valueType   :db.type/string
                :db/index       true}

               {:db/ident       :user/account
                :db/cardinality :db.cardinality/many
                :db/valueType   :db.type/ref
                :db/index       true}

               {:db/ident       :server/account
                :db/cardinality :db.cardinality/one
                :db/valueType   :db.type/ref
                :db/index       true}]))

  (let [num-accounts  100
        num-users     10
        num-servers   50
        account-uuids (repeatedly num-accounts d/squuid)
        account-txes  (time (->> account-uuids
                                 (mapv (partial make-account-txes {:num-users   num-users
                                                                   :num-servers num-servers}))
                                 (flatten)))
        tx-count      (count account-txes)]
    ;(log/warn "Skipping txe.")
    (log/debug "Transacting " tx-count " things.")
    (tx! conn account-txes)

    (let [!counter         (atom 0)
          !mistakes (atom 0)
          client           (spiceomic/make-client conn)
          db               (d/db conn)
          {:as setup :keys [accounts users servers]} (time (setup-benchmark db))
          _                (do (log/debug "N accounts" (count accounts))
                               (log/debug "N users" (count users))
                               (log/debug "N servers" (count servers)))
          server->user-set (time (into {}
                                       (for [server-id servers]
                                         [server-id (set (server->user-ids db server-id))])))
          server->users    (time (into {}
                                       (for [server-id servers]
                                         [server-id (vec (server->user-ids db server-id))])))]
      ;(prn 'server->users server->users)
      (when false ; do
        (log/debug "Starting benchmark...")
        (crit/quick-bench
          (let [random-server      (rand-nth servers)
                expected-userset   (server->user-set random-server)
                expected-user-list (server->users random-server)
                ;_                  (prn 'expected-user-list expected-user-list)
                random-user        (if (and (>= (rand) 0.5) (seq expected-user-list)) ; some empty
                                     (rand-nth expected-user-list)
                                     (rand-nth users))
                expected           (contains? expected-userset random-user)
                _ (swap! !counter inc)
                actual (eacl/can? client (->user random-user) :view (->server random-server))]
            ;(log/debug expected random-user random-server)
            (when (not= expected actual)
              (swap! !mistakes inc))
            [expected actual]))
        (prn 'counter @!counter 'mistakes @!mistakes)))))

;(let [result-matrix (time (run-benchmark !counter client setup))
;      checks        (check-results server->user-set result-matrix)
;      matches       (map (fn [[actual expected]]
;                           (= actual expected)) checks)
;      disparities   (frequencies matches)]
;  (log/debug 'disparities disparities)
;  (log/debug 'counter @!counter)
;  disparities))))

(comment
  ; ok so we have 100 accounts, 10 users per account, and 1000 servers per account
  ; th ameans we ahve 100 * 1000 = 100,000 servers.
  ; total users = 10 * 100 = 1,000 users
  ; users * servers = 100,000 * 1,000 = 100,000,000 M permission checks.

  (let [db        (d/db conn)
        ; pull some relations
        users-cos (d/q '[:find ?user ?company
                         :where
                         [?user :user/username]
                         [?rel :eacl/subject ?user]
                         [?rel :eacl/relation :company/owner]
                         [?rel :eacl/resource ?company]]
                       db)]
    (prn (count users-cos) 'users-cos)
    (time
      (doall (frequencies (->> users-cos (pmap (fn [[user company]]
                                                 (eacl/can? db user :company/view company))))))))

  (let [subjects (d/q '[:find [?subject ...]
                        :where
                        [?subject :eacl/subject]]
                      (d/db conn))
        subject  (rand-nth subjects)]))
;(prn 'can? (eacl/can? (d/db conn) (:db/id subject) :company/view [:entity/id (first cids)]))))

