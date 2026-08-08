(ns eacl.datascript.keyset-recursion-test
  "Keyset-recursive-pagination regression suite (eacl-v8-root-fixes 5.7).

  Pins the properties the ordinal-cursor design violated (audit finding
  V4): surviving results are returned exactly once across a paginated
  walk under order-perturbing concurrent writes; recovery follows the
  keyset membership contract; direction/`:last` support is uniform; and
  cursors tolerate a schema edit that re-routes the permission between
  engines."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.bench.recursive-fixture :as rf]
            [eacl.core :as eacl]
            [eacl.datascript.core :as dsc]))

(def ^:private config {:shape :star :accounts 60})

(defn- seed! []
  (let [conn (dsc/create-conn)
        client (dsc/make-client conn {})]
    (eacl/write-schema! client (rf/schema-for config))
    (ds/transact! conn (vec (rf/object-transactions config)))
    (doseq [batch (rf/relationship-batches config)]
      (eacl/create-relationships! client (vec batch)))
    {:conn conn :client client}))

(defn- page-ids [page] (mapv :id (:data page)))

(defn- end-cursor [page] (get-in page [:page-info :end-cursor]))

(defn- lookup [client & [overrides]]
  (eacl/lookup-resources
   client (merge (rf/resource-query config rf/user-1 10) overrides)))

(deftest surviving-results-exactly-once-under-order-perturbing-write-test
  ;; The V4 repro: an order-perturbing relevant write between pages made
  ;; the ordinal engine silently skip and duplicate surviving results.
  ;; Keyset order is a function of immutable eids, so every account that
  ;; is authorized for the whole walk appears exactly once.
  (let [{:keys [client]} (seed!)
        page-1 (lookup client)
        survivors-before (set (page-ids page-1))
        ;; Order-perturbing relevant write: grant an additional DIRECT
        ;; ownership path to a late account (changes its derivation
        ;; depth/order under the old worklist engine) plus a brand-new
        ;; account parented to the root (new grant below/above bounds).
        _ (ds/transact! (:conn client) [{:eacl/id "account-extra"}])
        _ (eacl/create-relationships!
           client
           [(eacl/->Relationship rf/user-1 :owner
                                 (rf/object :account (rf/account-id 45)))
            (eacl/->Relationship (rf/object :account (rf/account-id 0))
                                 :parent
                                 (rf/object :account "account-extra"))])
        walk (loop [acc (vec (page-ids page-1))
                    cursor (end-cursor page-1)
                    guard 0]
               (if (or (nil? cursor) (> guard 20))
                 acc
                 (let [page (lookup client {:after cursor})]
                   (if (seq (:data page))
                     (recur (into acc (page-ids page))
                            (when (get-in page [:page-info :has-next-page?])
                              (end-cursor page))
                            (inc guard))
                     acc))))]
    (testing "no surviving result is duplicated across the walk"
      (is (= (count walk) (count (set walk))) (pr-str walk)))
    (testing "every page-1 survivor appears exactly once overall"
      (is (empty? (set/difference survivors-before (set walk)))))
    (testing "every original account survives the walk"
      (is (empty?
           (set/difference
            (set (map #(rf/account-id %) (range (:accounts config))))
            (set walk)))))))

(deftest revoked-boundary-restarts-honestly-test
  (let [{:keys [client]} (seed!)
        page-1 (lookup client)
        boundary-id (last (page-ids page-1))
        ;; Revoke the boundary's grant path: detach it from the root.
        _ (eacl/delete-relationships!
           client
           [(eacl/->Relationship (rf/object :account (rf/account-id 0))
                                 :parent
                                 (rf/object :account boundary-id))])
        page-2 (lookup client {:after (end-cursor page-1)})]
    (testing "the revoked boundary forces an honest restart"
      (is (= :restarted (get-in page-2 [:page-info :cursor-recovery]))
          (pr-str (:page-info page-2)))
      (is (= (vec (remove #{boundary-id} (page-ids page-1)))
             (vec (take (count (remove #{boundary-id} (page-ids page-1)))
                        (page-ids page-2)))
             )))))

(deftest surviving-boundary-resumes-after-write-test
  (let [{:keys [client]} (seed!)
        page-1 (lookup client)
        ;; Unrelated-ish write (still triggers recovery pre-group-6).
        _ (eacl/create-relationships!
           client
           [(eacl/->Relationship rf/stranger :reader
                                 (rf/object :account (rf/account-id 3)))])
        page-2 (lookup client {:after (end-cursor page-1)})]
    (testing "a surviving boundary resumes exclusively with no overlap"
      (is (empty? (set/intersection (set (page-ids page-1))
                                    (set (page-ids page-2))))
          (pr-str [(page-ids page-1) (page-ids page-2)])))))

(deftest bare-last-and-desc-slices-test
  (let [{:keys [client]} (seed!)
        all (page-ids (lookup client {:first 200}))
        last-page (eacl/lookup-resources
                   client (-> (rf/resource-query config rf/user-1 10)
                              (dissoc :first)
                              (assoc :last 7)))]
    (testing "bare :last works on a recursive root (restriction removed)"
      (is (= (vec (take-last 7 all)) (page-ids last-page))))
    (testing ":before slices ascending-presented windows"
      (let [before-cursor (get-in last-page [:page-info :start-cursor])
            prev (eacl/lookup-resources
                  client (-> (rf/resource-query config rf/user-1 10)
                             (dissoc :first)
                             (assoc :last 5 :before before-cursor)))]
        (is (= (vec (take-last 5 (drop-last 7 all)))
               (page-ids prev)))))))

(deftest route-change-tolerant-cursor-test
  ;; A cursor minted while the permission routed recursive must survive
  ;; the route flipping to acyclic (all cycle-enabling data removed).
  (let [{:keys [client]} (seed!)
        page-1 (lookup client)
        cursor (end-cursor page-1)
        parent-edges (for [i (range 1 (:accounts config))]
                       (eacl/->Relationship
                        (rf/object :account (rf/account-id 0))
                        :parent
                        (rf/object :account (rf/account-id i))))
        _ (eacl/delete-relationships! client (vec parent-edges))
        page-2 (lookup client {:after cursor})]
    (testing "resumption does not fail with a cursor-kind mismatch"
      ;; With every parent edge gone only the directly-owned root is
      ;; authorized; the boundary (a child) is revoked -> honest restart
      ;; listing just the root.
      (is (= [(rf/account-id 0)] (page-ids page-2))
          (pr-str (:page-info page-2))))))
