(ns eacl.operator-engine.cross-backend-operator-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [datascript.core :as ds]
            [datomic.api :as d]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as datahike-db]
            [eacl.datascript.core :as datascript]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.engine.v8 :as engine]))

(def ^:private operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = base & reader - banned
   }")

(def ^:private object-ids
  ["alice" "bob" "d0" "d1" "d2" "d3"])

(def ^:private recursive-schema
  "definition user {}
   definition folder {
     relation direct: user
     relation eligible: user
     relation banned: user
     relation parent: folder
     permission view = direct + (parent->view & eligible)
     permission allowed = view - banned
   }")

(def ^:private recursive-object-ids
  ["alice" "bob" "f0" "f1" "f2"])

(defn- object [type id]
  (eacl/spice-object type id))

(defn- relationships []
  (let [alice (object :user "alice")
        bob (object :user "bob")
        d0 (object :document "d0")
        d1 (object :document "d1")
        d2 (object :document "d2")
        d3 (object :document "d3")]
    [(eacl/->Relationship alice :reader d0)
     (eacl/->Relationship alice :reader d1)
     (eacl/->Relationship alice :banned d1)
     (eacl/->Relationship alice :writer d2)
     (eacl/->Relationship alice :reader d3)
     (eacl/->Relationship alice :writer d3)
     (eacl/->Relationship bob :reader d1)]))

(defn- recursive-relationships []
  (let [alice (object :user "alice")
        bob (object :user "bob")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")]
    [(eacl/->Relationship alice :direct f0)
     (eacl/->Relationship bob :direct f0)
     (eacl/->Relationship f0 :parent f1)
     (eacl/->Relationship alice :eligible f1)
     (eacl/->Relationship f1 :parent f2)
     (eacl/->Relationship alice :eligible f2)
     (eacl/->Relationship alice :banned f2)]))

(defn- ids [page]
  (mapv :id (:data page)))

(defn- write-operator-schema! [client schema-source]
  (binding [orchestration/*operator-expression-writes-enabled?* true]
    (eacl/write-schema! client schema-source)))

(defn- exercise-operator-api! [client]
  (let [alice (object :user "alice")
        bob (object :user "bob")
        d0 (object :document "d0")
        d1 (object :document "d1")
        d2 (object :document "d2")
        d3 (object :document "d3")
        forward {:subject alice :permission :view
                 :resource/type :document}
        reverse {:resource d1 :permission :view :subject/type :user}]
    (binding [engine/*operator-routing-enabled?* true]
      (is (true? (eacl/can? client {:subject alice :permission :view
                                    :resource d0})))
      (is (false? (eacl/can? client {:subject alice :permission :view
                                     :resource d1})))
      (is (false? (eacl/can? client {:subject alice :permission :view
                                     :resource d2})))
      (is (true? (eacl/can? client {:subject alice :permission :view
                                    :resource d3})))
      (is (true? (eacl/can? client {:subject bob :permission :view
                                    :resource d1})))
      (is (= ["d0" "d3"]
             (ids (eacl/lookup-resources client (assoc forward :first 20)))))
      (is (= ["bob"]
             (ids (eacl/lookup-subjects client (assoc reverse :first 20)))))
      (is (= {:count 2 :limit -1}
             (select-keys (eacl/count-resources client forward)
                          [:count :limit :truncated?])))
      (is (= {:count 1 :limit 1 :truncated? true}
             (select-keys
              (eacl/count-resources client (assoc forward :count-limit 1))
              [:count :limit :truncated?])))
      (is (= {:count 1 :limit -1}
             (select-keys (eacl/count-subjects client reverse)
                          [:count :limit :truncated?])))
      (let [page-1 (eacl/lookup-resources client (assoc forward :first 1))
            page-2
            (eacl/lookup-resources
             client (assoc forward :first 1
                           :after (get-in page-1 [:page-info :end-cursor])))]
        (is (= ["d0" "d3"] (into (ids page-1) (ids page-2))))
        (is (true? (get-in page-1 [:page-info :has-next-page?])))
        (is (false? (get-in page-2 [:page-info :has-next-page?]))))
      (eacl/create-relationship!
       client (eacl/->Relationship alice :banned d0))
      (is (false? (eacl/can? client {:subject alice :permission :view
                                     :resource d0})))
      (is (= ["d3"]
             (ids (eacl/lookup-resources client (assoc forward :first 20))))))))

(defn- exercise-recursive-operator-api! [client]
  (let [alice (object :user "alice")
        bob (object :user "bob")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")
        forward {:subject alice :permission :allowed :resource/type :folder}
        reverse {:resource f0 :permission :allowed :subject/type :user}]
    (binding [engine/*operator-routing-enabled?* true]
      (is (= [true true false]
             (mapv #(eacl/can? client {:subject alice :permission :allowed
                                       :resource %})
                   [f0 f1 f2])))
      (is (= [true false false]
             (mapv #(eacl/can? client {:subject bob :permission :allowed
                                       :resource %})
                   [f0 f1 f2])))
      (is (= ["f0" "f1"]
             (ids (eacl/lookup-resources client (assoc forward :first 20)))))
      (is (= ["alice" "bob"]
             (ids (eacl/lookup-subjects client (assoc reverse :first 20)))))
      (is (= {:count 2 :limit -1}
             (select-keys (eacl/count-resources client forward)
                          [:count :limit :truncated?])))
      (is (= {:count 1 :limit 1 :truncated? true}
             (select-keys
              (eacl/count-resources client (assoc forward :count-limit 1))
              [:count :limit :truncated?])))
      (is (= {:count 2 :limit -1}
             (select-keys (eacl/count-subjects client reverse)
                          [:count :limit :truncated?])))
      (let [page-1 (eacl/lookup-resources client (assoc forward :first 1))
            page-2
            (eacl/lookup-resources
             client (assoc forward :first 1
                           :after (get-in page-1 [:page-info :end-cursor])))]
        (is (= ["f0" "f1"] (into (ids page-1) (ids page-2))))))))

(defn- exercise-backends!
  [schema-source ids-to-create relationships-to-create exercise!]
  (testing "DataScript"
    (let [conn (datascript/create-conn)
          client (datascript/make-client conn {})]
      (write-operator-schema! client schema-source)
      (ds/transact! conn (mapv #(hash-map :eacl/id %) ids-to-create))
      (eacl/create-relationships! client relationships-to-create)
      (exercise! client)))

  (doseq [[label options]
          [["Datahike keyword attributes" {}]
           ["Datahike numeric attribute refs" {:attribute-refs? true}]]]
    (testing label
      (let [conn (datahike/create-conn nil options)
            config (datahike-db/db-config (dh/db conn))
            client (datahike/make-client conn {})]
        (try
          (write-operator-schema! client schema-source)
          (dh/transact conn (mapv #(hash-map :eacl/id %) ids-to-create))
          (eacl/create-relationships! client relationships-to-create)
          (exercise! client)
          (finally
            (dh/release conn)
            (dh/delete-database config))))))

  (testing "Datomic"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (with-mem-conn [conn datomic-schema/v7-schema]
      (let [client (datomic/make-client conn {})]
        (write-operator-schema! client schema-source)
        @(d/transact conn (mapv #(hash-map :eacl/id %) ids-to-create))
        (eacl/create-relationships! client relationships-to-create)
        (exercise! client)))))

(deftest public-intersection-and-exclusion-conform-across-built-in-backends-test
  (exercise-backends!
   operator-schema object-ids (relationships) exercise-operator-api!))

(deftest public-recursive-conjunction-and-exclusion-conform-across-built-in-backends-test
  (exercise-backends!
   recursive-schema recursive-object-ids (recursive-relationships)
   exercise-recursive-operator-api!))
