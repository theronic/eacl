(ns eacl.datascript.impl-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.schema :as schema]))

(def scan-schema
  "definition user {}

   definition account {}

   definition server {
     relation account: account
     relation owner: user
   }

   definition network {
     relation account: account
   }")

(def permission-schema
  "definition user {}

   definition group {
     relation member: user
   }

   definition server {
     relation group: group
     permission view = group->member
   }")

(def permission-schema-v2
  "definition user {}

   definition group {
     relation member: user
   }

   definition server {
     relation group: group
     permission view = group->member
     permission admin = group->member
   }")

(defn- spice-object
  [type id]
  (eacl/spice-object type id))

(defn- read-relationships-data
  [db query]
  (:data (impl/read-relationships db query)))

(defn- seed-objects!
  [conn object-ids]
  (ds/transact! conn
    (map-indexed (fn [idx object-id]
                   {:db/id (- (inc idx))
                    :eacl/id object-id})
                 object-ids)))

(defn- seed-db
  []
  (let [conn       (datascript/create-conn)
        client     (datascript/make-client conn {})
        object-ids ["user-1" "account-1" "server-1" "server-2" "network-1"]]
    (eacl/write-schema! client scan-schema)
    (seed-objects! conn object-ids)
    (eacl/create-relationships! client
                                [(eacl/->Relationship (spice-object :account "account-1") :account (spice-object :server "server-1"))
                                 (eacl/->Relationship (spice-object :account "account-1") :account (spice-object :server "server-2"))
                                 (eacl/->Relationship (spice-object :user "user-1") :owner (spice-object :server "server-1"))
                                 (eacl/->Relationship (spice-object :account "account-1") :account (spice-object :network "network-1"))])
    {:conn conn
     :db   (ds/db conn)}))

(defn- relation-id
  [db resource-type relation-name]
  (:e (first (impl/relation-datoms db resource-type relation-name))))

(defn- object-id->entid
  [db object-id]
  (ds/entid db [:eacl/id object-id]))

(defn- forward-reference
  [db subject-type subject-id relation-id resource-type cursor-resource-id]
  (->> (ds/index-range db
                       schema/forward-relationship-attr
                       [subject-type subject-id relation-id resource-type (if cursor-resource-id (inc cursor-resource-id) 0)]
                       [subject-type subject-id relation-id resource-type schema/max-entid])
       (map (fn [datom] (nth (:v datom) 4)))
       vec))

(defn- reverse-reference
  [db resource-type resource-id relation-id subject-type cursor-subject-id]
  (->> (ds/index-range db
                       schema/reverse-relationship-attr
                       [resource-type resource-id relation-id subject-type (if cursor-subject-id (inc cursor-subject-id) 0)]
                       [resource-type resource-id relation-id subject-type schema/max-entid])
       (map (fn [datom] (nth (:v datom) 4)))
       vec))

(deftest datascript-bounded-scan-parity-test
  (let [{:keys [db]}      (seed-db)
        account-id        (object-id->entid db "account-1")
        server-1-id       (object-id->entid db "server-1")
        server-2-id       (object-id->entid db "server-2")
        account-relation  (relation-id db :server :account)]
    (testing "forward scans match the bounded AVET range semantics"
      (is (= [server-1-id server-2-id]
             (vec (impl/subject->resources db :account account-id account-relation :server nil))))
      (is (= (forward-reference db :account account-id account-relation :server nil)
             (vec (impl/subject->resources db :account account-id account-relation :server nil)))))

    (testing "forward scans keep exclusive cursor semantics"
      (is (= [server-2-id]
             (vec (impl/subject->resources db :account account-id account-relation :server server-1-id))))
      (is (= (forward-reference db :account account-id account-relation :server server-1-id)
             (vec (impl/subject->resources db :account account-id account-relation :server server-1-id)))))

    (testing "forward scans stop at tuple-prefix boundaries"
      (is (= [server-1-id server-2-id]
             (vec (impl/subject->resources db :account account-id account-relation :server nil))))
      (is (not-any? #{(object-id->entid db "network-1")}
                    (impl/subject->resources db :account account-id account-relation :server nil))))

    (testing "reverse scans match the bounded AVET range semantics"
      (is (= [account-id]
             (vec (impl/resource->subjects db :server server-1-id account-relation :account nil))))
      (is (= (reverse-reference db :server server-1-id account-relation :account nil)
             (vec (impl/resource->subjects db :server server-1-id account-relation :account nil)))))

    (testing "reverse scans keep exclusive cursor semantics"
      (is (= []
             (vec (impl/resource->subjects db :server server-1-id account-relation :account account-id))))
      (is (= (reverse-reference db :server server-1-id account-relation :account account-id)
             (vec (impl/resource->subjects db :server server-1-id account-relation :account account-id)))))

    (testing "reverse scans stop at tuple-prefix boundaries"
      (is (= [account-id]
             (vec (impl/resource->subjects db :server server-1-id account-relation :account nil))))
      (is (not-any? #{(object-id->entid db "user-1")}
                    (impl/resource->subjects db :server server-1-id account-relation :account nil))))))

(defn- seed-permission-db
  []
  (let [conn       (datascript/create-conn)
        client     (datascript/make-client conn {})
        object-ids ["user-1" "group-1" "server-1"]]
    (eacl/write-schema! client permission-schema)
    (seed-objects! conn object-ids)
    {:conn conn
     :client client}))

(defn- seed-bulk-read-db
  [n]
  (let [conn       (datascript/create-conn)
        client     (datascript/make-client conn {})
        user-ids   (mapv #(str "bulk-user-" %) (range n))
        server-ids (mapv #(str "bulk-server-" %) (range n))
        object-ids (into user-ids server-ids)]
    (eacl/write-schema! client scan-schema)
    (seed-objects! conn object-ids)
    (eacl/create-relationships! client
                                (mapv (fn [idx]
                                        (eacl/->Relationship (spice-object :user (nth user-ids idx))
                                                             :owner
                                                             (spice-object :server (nth server-ids idx))))
                                      (range n)))
    {:conn conn
     :client client}))

(defn- backend-for-client
  [client db]
  (impl/indexed-backend db (:opts client)))

(deftest permission-path-cache-lifecycle-test
  (let [{:keys [conn client]} (seed-permission-db)
        calc-calls            (atom 0)
        schema-builds         (atom 0)
        orig-calc             impl/calc-permission-paths
        orig-build            impl/build-schema-catalog
        cache-stamp           (:cache-stamp (:opts client))]
    (with-redefs [impl/calc-permission-paths (fn [& args]
                                               (swap! calc-calls inc)
                                               (apply orig-calc args))
                  impl/build-schema-catalog (fn [db]
                                              (swap! schema-builds inc)
                                              (orig-build db))]
      (testing "permission paths and schema catalog stay warm across relationship writes"
        (let [db-before      (ds/db conn)
              backend-before (backend-for-client client db-before)
              stamp-before   (cache-stamp)]
          (is (seq (impl/get-permission-paths backend-before :server :view)))
          (is (= 1 @calc-calls))
          (is (= 1 @schema-builds))

          (reset! calc-calls 0)
          (eacl/create-relationship! client
                                     (spice-object :group "group-1")
                                     :group
                                     (spice-object :server "server-1"))
          (let [db-after      (ds/db conn)
                backend-after (backend-for-client client db-after)]
            (is (= stamp-before (cache-stamp)))
            (is (seq (impl/get-permission-paths backend-after :server :view)))
            (is (zero? @calc-calls))
            (is (= 1 @schema-builds)))))

      (testing "schema writes invalidate permission paths and compiled schema catalog"
        (let [stamp-before (cache-stamp)]
          (reset! calc-calls 0)
          (eacl/write-schema! client permission-schema-v2)
          (let [db-after      (ds/db conn)
                backend-after (backend-for-client client db-after)]
            (is (not= stamp-before (cache-stamp)))
            (is (seq (impl/get-permission-paths backend-after :server :view)))
            (is (= 1 @calc-calls))
            (is (= 2 @schema-builds))))))))

(deftest permission-path-cache-is-connection-local-test
  (let [{conn-1 :conn client-1 :client} (seed-permission-db)
        {conn-2 :conn client-2 :client} (seed-permission-db)
        calc-calls                      (atom 0)
        orig-calc                       impl/calc-permission-paths]
    (with-redefs [impl/calc-permission-paths (fn [& args]
                                               (swap! calc-calls inc)
                                               (apply orig-calc args))]
      (let [backend-1a (backend-for-client client-1 (ds/db conn-1))
            backend-2a (backend-for-client client-2 (ds/db conn-2))]
        (is (not= (:permission-paths-cache (:opts client-1))
                  (:permission-paths-cache (:opts client-2))))
        (impl/get-permission-paths backend-1a :server :view)
        (impl/get-permission-paths backend-2a :server :view)
        (is (= 2 @calc-calls))
        (reset! calc-calls 0)
        (impl/get-permission-paths (backend-for-client client-1 (ds/db conn-1)) :server :view)
        (impl/get-permission-paths (backend-for-client client-2 (ds/db conn-2)) :server :view)
        (is (zero? @calc-calls))))))

(deftest read-relationships-query-matrix-test
  (let [{:keys [db]}     (seed-db)
        account-id       (object-id->entid db "account-1")
        server-1-id      (object-id->entid db "server-1")
        owner-relations  (read-relationships-data db {:resource/relation :owner})
        server-relations (read-relationships-data db {:resource/type :server})]
    (testing "anchored subject scans return exact direct relationships"
      (is (= [(eacl/->Relationship (spice-object :account account-id)
                                   :account
                                   (spice-object :server server-1-id))]
             (read-relationships-data db {:subject/type      :account
                                          :subject/id        account-id
                                          :resource/type     :server
                                          :resource/id       server-1-id
                                          :resource/relation :account}))))

    (testing "relation-only scans stay bounded to matching relation definitions"
      (is (= [(eacl/->Relationship (spice-object :user (object-id->entid db "user-1"))
                                   :owner
                                   (spice-object :server server-1-id))]
             owner-relations)))

    (testing "resource-type-only scans return all direct relationships for the type"
      (is (= #{:account :owner}
             (set (map :relation server-relations))))
      (is (= #{server-1-id (object-id->entid db "server-2")}
             (set (map (comp :id :resource) server-relations)))))))

(deftest read-relationships-default-limit-test
  (let [{:keys [client]} (seed-bulk-read-db 1005)
        {page-1 :data cursor :cursor}
        (eacl/read-relationships client {:subject/type :user})
        {page-2 :data}
        (eacl/read-relationships client {:subject/type :user
                                         :cursor       cursor})]
    (is (= 1000 (count page-1)))
    (is (= 5 (count page-2)))
    (is (string? cursor))
    (is (= "bulk-user-0" (get-in (first page-1) [:subject :id])))
    (is (= "bulk-user-1000" (get-in (first page-2) [:subject :id])))))
