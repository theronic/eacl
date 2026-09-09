(ns eacl.datascript.keyset-recursion-test
  "Current-basis recursive cursor regression suite.

  DataScript never reconstructs history. Default cursors bind the exact current
  basis. Explicit managed mutation-stamp cursors may continue on a
  proof-equivalent current snapshot, while relevant writes, revoked boundaries,
  and route changes reject the cursor rather than creating a hybrid walk."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.bench.recursive-fixture :as rf]
            [eacl.core :as eacl]
            [eacl.client.orchestration :as orchestration]
            [eacl.datascript.core :as dsc]))

(def ^:private config {:shape :star :accounts 60})

(defn- seed!
  ([] (seed! {}))
  ([client-opts]
   (let [conn (dsc/create-conn)
         client (dsc/make-client conn client-opts)]
     (eacl/write-schema! client (rf/schema-for config))
     (ds/transact! conn (vec (rf/object-transactions config)))
     (doseq [batch (rf/relationship-batches config)]
       (eacl/create-relationships! client (vec batch)))
     {:conn conn :client client})))

(defn- page-ids [page]
  (mapv :id (:data page)))

(defn- end-cursor [page]
  (get-in page [:page-info :end-cursor]))

(defn- lookup [client & [overrides]]
  (eacl/lookup-resources
   client (merge (rf/resource-query config rf/user-1 10) overrides)))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- assert-stale-proof! [data]
  (is (= :eacl.pagination/stale-cursor (:type data)))
  (is (= :frame-changed (:reason data))))

(deftest order-perturbing-write-rejects-current-only-cursor-test
  (let [{:keys [conn client]} (seed!)
        page-1 (lookup client)
        _ (ds/transact! conn [{:eacl/id "account-extra"}])
        _ (eacl/create-relationships!
           client
           [(eacl/->Relationship rf/user-1 :owner
                                 (rf/object :account (rf/account-id 45)))
            (eacl/->Relationship (rf/object :account (rf/account-id 0))
                                 :parent
                                 (rf/object :account "account-extra"))])]
    (assert-stale-proof!
     (error-data #(lookup client {:after (end-cursor page-1)})))))

(deftest revoked-boundary-is-stale-test
  (let [{:keys [client]} (seed!)
        page-1 (lookup client)
        boundary-id (last (page-ids page-1))
        _ (eacl/delete-relationships!
           client
           [(eacl/->Relationship (rf/object :account (rf/account-id 0))
                                 :parent
                                 (rf/object :account boundary-id))])]
    (assert-stale-proof!
     (error-data #(lookup client {:after (end-cursor page-1)})))))

(deftest managed-proof-equivalent-write-with-surviving-boundary-continues-test
  (let [{:keys [client]}
        (seed! {})
        page-1 (lookup client)
        _ (eacl/create-relationships!
           client
           [(eacl/->Relationship rf/stranger :reader
                                 (rf/object :account (rf/account-id 3)))])
        request {:after (end-cursor page-1)}]
    (if orchestration/*qualified-authorization-enabled?*
      (is (= :eacl.pagination/stale-cursor (:type (error-data #(lookup client request)))))
      (let [page-2 (lookup client request)]
        (is (nil? (get-in page-2 [:page-info :cursor-recovery])))
        (is (empty? (set/intersection (set (page-ids page-1)) (set (page-ids page-2)))))))))

(deftest bare-last-requires-explicit-completion-and-preserves-logical-order-test
  (let [{:keys [client]} (seed!)
        demand-error
        (error-data
         #(eacl/lookup-resources
           client
           (-> (rf/resource-query config rf/user-1 10)
               (dissoc :first)
               (assoc :last 7))))
        all
        (page-ids
         (lookup client {:first 200
                         :evaluation :complete-denotation}))
        last-page
        (eacl/lookup-resources
         client
         (-> (rf/resource-query config rf/user-1 10)
             (dissoc :first)
             (assoc :last 7
                    :evaluation :complete-denotation)))]
    (is (= :eacl.pagination/complete-evaluation-required
           (:eacl/error demand-error)))
    (is (= (vec (take-last 7 all)) (page-ids last-page)))
    (testing ":last N :before retains only the bounded logical prefix window"
      (let [before-cursor (get-in last-page [:page-info :start-cursor])
            previous
            (eacl/lookup-resources
             client
             (-> (rf/resource-query config rf/user-1 10)
                 (dissoc :first)
                 (assoc :last 5
                        :before before-cursor
                        :evaluation :complete-denotation)))]
        (is (= (vec (take-last 5 (drop-last 7 all)))
               (page-ids previous)))))))

(deftest route-change-rejects-current-only-cursor-test
  (let [{:keys [client]} (seed!)
        page-1 (lookup client)
        cursor (end-cursor page-1)
        parent-edges
        (for [i (range 1 (:accounts config))]
          (eacl/->Relationship
           (rf/object :account (rf/account-id 0))
           :parent
           (rf/object :account (rf/account-id i))))
        _ (eacl/delete-relationships! client (vec parent-edges))]
    (assert-stale-proof!
     (error-data #(lookup client {:after cursor})))))
