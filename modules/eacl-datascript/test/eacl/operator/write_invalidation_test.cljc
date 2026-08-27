(ns eacl.operator.write-invalidation-test
  "Operator-schema write invalidation, promoted from the 2026-08-26 review's
  live probes: exclusion, intersection, and recursive-chain answers flip on
  both the add and the delete of a dependency-closure relationship; a
  captured snapshot keeps its old basis while the live client advances; and
  a resume cursor replayed after a write inside its dependency closure fails
  closed with the typed stale-cursor outcome."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]))

(def ^:private operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation approved: user
     relation banned: user
     permission view = reader - banned
     permission both = reader & approved
   }
   definition folder {
     relation member: user
     relation banned: user
     relation parent: folder
     permission access = member + parent->access
     permission allowed = access - banned
   }")

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo) error
      (ex-data error))))

(defn- fixture []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        alice (eacl/spice-object :user "alice")
        docs (mapv #(eacl/spice-object :document (str "doc-" %)) (range 4))
        folders (mapv #(eacl/spice-object :folder (str "folder-" %))
                      (range 3))]
    (binding [orchestration/*operator-expression-writes-enabled?* true]
      (eacl/write-schema! client operator-schema))
    (ds/transact! conn (mapv #(hash-map :eacl/id (:id %))
                             (concat [alice] docs folders)))
    (eacl/create-relationships!
     client
     (into
      (mapv #(eacl/->Relationship alice :reader %) docs)
      ;; folder-0 is folder-1's parent, folder-1 is folder-2's parent;
      ;; alice is a member at the root, so access reaches the leaf through
      ;; two parent hops.
      [(eacl/->Relationship alice :member (nth folders 0))
       (eacl/->Relationship (nth folders 0) :parent (nth folders 1))
       (eacl/->Relationship (nth folders 1) :parent (nth folders 2))]))
    {:conn conn :client client :alice alice :docs docs :folders folders}))

(defn- can? [client subject permission resource]
  (binding [engine/*operator-routing-enabled?* true]
    (eacl/can? client {:subject subject
                       :permission permission
                       :resource resource})))

(defn- page-ids [client subject permission first-n]
  (binding [engine/*operator-routing-enabled?* true]
    (mapv :id (:data (eacl/lookup-resources
                      client {:subject subject
                              :resource/type :document
                              :permission permission
                              :first first-n})))))

(defn- resource-count [client subject permission]
  (binding [engine/*operator-routing-enabled?* true]
    (:count (eacl/count-resources
             client {:subject subject
                     :resource/type :document
                     :permission permission}))))

(deftest exclusion-write-invalidation-and-snapshot-basis-test
  (let [{:keys [client alice docs]} (fixture)
        doc-1 (nth docs 1)]
    (is (true? (can? client alice :view doc-1)))
    (is (= ["doc-0" "doc-1" "doc-2" "doc-3"]
           (page-ids client alice :view 10)))
    (is (= 4 (resource-count client alice :view)))
    (let [before-ban (binding [engine/*operator-routing-enabled?* true]
                       (eacl/snapshot client))]
      (try
        (eacl/create-relationship!
         client (eacl/->Relationship alice :banned doc-1))
        (testing "the ban flips the check, the page, and the count"
          (is (false? (can? client alice :view doc-1)))
          (is (= ["doc-0" "doc-2" "doc-3"]
                 (page-ids client alice :view 10)))
          (is (= 3 (resource-count client alice :view))))
        (testing "a captured snapshot keeps its pre-write basis"
          (is (true? (can? before-ban alice :view doc-1))))
        (finally
          (eacl/release! before-ban))))
    (eacl/delete-relationship!
     client (eacl/->Relationship alice :banned doc-1))
    (testing "deleting the ban restores every answer"
      (is (true? (can? client alice :view doc-1)))
      (is (= ["doc-0" "doc-1" "doc-2" "doc-3"]
             (page-ids client alice :view 10)))
      (is (= 4 (resource-count client alice :view))))))

(deftest intersection-write-invalidation-test
  (let [{:keys [client alice docs]} (fixture)
        doc-0 (nth docs 0)]
    (is (false? (can? client alice :both doc-0))
        "reader without approval is outside the intersection")
    (eacl/create-relationship!
     client (eacl/->Relationship alice :approved doc-0))
    (is (true? (can? client alice :both doc-0)))
    (is (= ["doc-0"] (page-ids client alice :both 10)))
    (eacl/delete-relationship!
     client (eacl/->Relationship alice :approved doc-0))
    (is (false? (can? client alice :both doc-0)))
    (is (= [] (page-ids client alice :both 10)))))

(deftest recursive-chain-write-invalidation-test
  (let [{:keys [client alice folders]} (fixture)
        leaf (nth folders 2)]
    (is (true? (can? client alice :allowed leaf))
        "membership reaches the leaf through two parent hops")
    (testing "a ban at the leaf flips the recursive exclusion"
      (eacl/create-relationship!
       client (eacl/->Relationship alice :banned leaf))
      (is (false? (can? client alice :allowed leaf)))
      (is (true? (can? client alice :access leaf))
          "the positive recursive chain itself is unaffected")
      (eacl/delete-relationship!
       client (eacl/->Relationship alice :banned leaf))
      (is (true? (can? client alice :allowed leaf))))
    (testing "cutting and restoring the chain flips the leaf"
      (eacl/delete-relationship!
       client (eacl/->Relationship (nth folders 0) :parent (nth folders 1)))
      (is (false? (can? client alice :allowed leaf)))
      (eacl/create-relationship!
       client (eacl/->Relationship (nth folders 0) :parent (nth folders 1)))
      (is (true? (can? client alice :allowed leaf))))))

(deftest operator-cursor-replay-after-closure-write-fails-closed-test
  (let [{:keys [client alice]} (fixture)
        query {:subject alice
               :resource/type :document
               :permission :view
               :first 2}
        page-1 (binding [engine/*operator-routing-enabled?* true]
                 (eacl/lookup-resources client query))
        cursor (get-in page-1 [:page-info :end-cursor])]
    (is (= ["doc-0" "doc-1"] (mapv :id (:data page-1))))
    (is (some? cursor))
    (testing "the control resume without a write continues the page"
      (is (= ["doc-2" "doc-3"]
             (binding [engine/*operator-routing-enabled?* true]
               (mapv :id (:data (eacl/lookup-resources
                                 client
                                 (assoc query :after cursor))))))))
    (eacl/create-relationship!
     client (eacl/->Relationship alice :banned (eacl/spice-object
                                                :document "doc-3")))
    (testing "a write inside the dependency closure stales the cursor"
      (let [data (error-data
                  #(binding [engine/*operator-routing-enabled?* true]
                     (eacl/lookup-resources
                      client (assoc query :after cursor))))]
        (is (= :eacl.pagination/stale-cursor (:type data)))
        (is (= (:type data) (:eacl/error data)))
        (is (= :frame-changed (:reason data)))))))
