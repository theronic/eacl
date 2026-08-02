(ns eacl.datascript.impl-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.schema :as schema]
            [eacl.engine.v8 :as engine]))

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
  (->> (ds/datoms db :eavt subject-id schema/forward-relationship-attr)
       (keep (fn [{:keys [v]}]
               (when (= [subject-type relation-id resource-type]
                        (subvec v 0 3))
                 (nth v 3))))
       (filter #(or (nil? cursor-resource-id)
                    (< cursor-resource-id %)))
       vec))

(defn- reverse-reference
  [db resource-type resource-id relation-id subject-type cursor-subject-id]
  (->> (ds/datoms db :eavt resource-id schema/reverse-relationship-attr)
       (keep (fn [{:keys [v]}]
               (when (= [resource-type relation-id subject-type]
                        (subvec v 0 3))
                 (nth v 3))))
       (filter #(or (nil? cursor-subject-id)
                    (< cursor-subject-id %)))
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

    (testing "forward scans use native reverse seeks for descending windows"
      (is (= [server-2-id server-1-id]
             (vec
              (impl/subject->resources
               db :account account-id account-relation :server
               {:direction :desc}))))
      (is (= [server-1-id]
             (vec
              (impl/subject->resources
               db :account account-id account-relation :server
               {:direction :desc
                :bound-eid server-2-id
                :inclusive-bound? false}))))
      (is (= [server-2-id server-1-id]
             (vec
              (impl/subject->resources
               db :account account-id account-relation :server
               {:direction :desc
                :bound-eid server-2-id
                :inclusive-bound? true})))))

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

    (testing "reverse scans use native reverse seeks"
      (is (= [account-id]
             (vec
              (impl/resource->subjects
               db :server server-1-id account-relation :account
               {:direction :desc})))))

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

(deftest permission-path-cache-lifecycle-test
  (let [{:keys [conn client]} (seed-permission-db)
        calc-calls            (atom 0)
        orig-calc             engine/calc-permission-paths
        subject               (spice-object :user "user-1")
        resource              (spice-object :server "server-1")]
    (with-redefs [engine/calc-permission-paths
                  (fn [& args]
                    (swap! calc-calls inc)
                    (apply orig-calc args))]
      (testing "permission paths and schema catalog stay warm across relationship writes"
        (is (false? (eacl/can? client subject :view resource)))
        (is (= 1 @calc-calls))

        (reset! calc-calls 0)
        (eacl/create-relationship! client
                                   (spice-object :group "group-1")
                                   :group
                                   resource)
        (is (false? (eacl/can? client subject :view resource)))
        (is (zero? @calc-calls)))

      (testing "schema writes invalidate permission paths and compiled schema catalog"
        (reset! calc-calls 0)
        (eacl/write-schema! client permission-schema-v2)
        (is (false? (eacl/can? client subject :view resource)))
        (is (= 1 @calc-calls))))))

(deftest permission-path-cache-is-connection-local-test
  (let [{client-1 :client} (seed-permission-db)
        {client-2 :client} (seed-permission-db)
        calc-calls (atom 0)
        orig-calc engine/calc-permission-paths]
    (with-redefs [engine/calc-permission-paths
                  (fn [& args]
                    (swap! calc-calls inc)
                    (apply orig-calc args))]
      (let [subject (spice-object :user "user-1")
            resource (spice-object :server "server-1")]
        (is (not= (:derived-schema-caches (:opts client-1))
                  (:derived-schema-caches (:opts client-2))))
        (eacl/can? client-1 subject :view resource)
        (eacl/can? client-2 subject :view resource)
        (is (= 2 @calc-calls))
        (reset! calc-calls 0)
        (eacl/can? client-1 subject :view resource)
        (eacl/can? client-2 subject :view resource)
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
        {page-1 :data page-info :page-info}
        (eacl/read-relationships client {:subject/type :user})
        cursor (:end-cursor page-info)
        {page-2 :data}
        (eacl/read-relationships client {:subject/type :user
                                         :first        1000
                                         :after        cursor})]
    (is (= 1000 (count page-1)))
    (is (= 5 (count page-2)))
    (is (string? cursor))
    (is (= "bulk-user-0" (get-in (first page-1) [:subject :id])))
    (is (= "bulk-user-995" (get-in (first page-2) [:subject :id]))
        "portable relationship order is lexicographic by public object id")))
