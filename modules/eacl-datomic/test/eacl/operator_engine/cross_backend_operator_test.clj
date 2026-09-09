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
            [eacl.engine.v8 :as engine]
            [eacl.operator-engine.oracle :as oracle]))

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

(defn- oracle-object [value]
  [(:type value) (:id value)])

(defn- oracle-relationships [values]
  (into #{}
        (map (fn [{:keys [subject relation resource]}]
               {:subject (oracle-object subject)
                :relation relation
                :resource (oracle-object resource)}))
        values))

(defn- acyclic-oracle-snapshot [objects relationship-values]
  {:objects (into #{} (map oracle-object) objects)
   :relationships (oracle-relationships relationship-values)
   :permissions
   {[:document :base]
    [:union [:relation :reader] [:relation :writer]]
    [:document :view]
    [:exclusion
     [:intersection [:permission :base] [:relation :reader]]
     [:relation :banned]]}})

(defn- recursive-oracle-snapshot [objects relationship-values]
  {:objects (into #{} (map oracle-object) objects)
   :relation-target-types {[:folder :parent] #{:folder}}
   :relationships (oracle-relationships relationship-values)
   :permissions
   {[:folder :view]
    [:union
     [:relation :direct]
     [:intersection [:arrow :parent :view] [:relation :eligible]]]
    [:folder :allowed]
    [:exclusion [:permission :view] [:relation :banned]]}})

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

(defn- error-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

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
        oracle-snapshot
        (acyclic-oracle-snapshot
         [alice bob d0 d1 d2 d3] (relationships))
        forward {:subject alice :permission :view
                 :resource/type :document}
        reverse {:resource d1 :permission :view :subject/type :user}]
    (binding [engine/*operator-routing-enabled?* true]
      (is (= (vec
              (for [subject [alice bob]
                    resource [d0 d1 d2 d3]]
                (oracle/check? oracle-snapshot (oracle-object subject) :view
                               (oracle-object resource))))
             (vec
              (for [subject [alice bob]
                    resource [d0 d1 d2 d3]]
                (eacl/can? client {:subject subject :permission :view
                                   :resource resource}))))
          "all point decisions match the independent finite-set oracle")
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
      (let [allowed (eacl/check-permission
                     client {:subject alice :permission :view :resource d0})
            denied (eacl/check-permission
                    client {:subject alice :permission :view :resource d1})]
        (is (true? (:allowed? allowed)))
        (is (false? (:allowed? denied)))
        (is (boolean? (:cached? allowed)))
        (is (= [allowed denied]
               (eacl/check-permissions
                client {:checks [{:subject alice :permission :view
                                  :resource d0}
                                 {:subject alice :permission :view
                                  :resource d1}]}))))
      (is (= ["d0" "d3"]
             (ids (eacl/lookup-resources client (assoc forward :first 20)))))
      (is (= ["d0" "d3"]
             (ids
              (eacl/lookup-resources
               client
               (assoc forward :first 20
                      :resource/relationship
                      {:relation :reader :subject alice})))))
      (is (= ["bob"]
             (ids (eacl/lookup-subjects client (assoc reverse :first 20)))))
      (is (= ["bob"]
             (ids
              (eacl/lookup-subjects
               client
               (assoc reverse :first 20
                      :subject/relationship
                      {:relation :reader :resource d1})))))
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
      (is (= :eacl/unknown-relation-or-permission
             (:type
              (error-data
               #(eacl/check-permission
                 client {:subject alice :permission :missing
                         :resource d0})))))
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
        oracle-snapshot
        (recursive-oracle-snapshot
         [alice bob f0 f1 f2] (recursive-relationships))
        oracle-evaluation (oracle/evaluate-stratified oracle-snapshot)
        forward {:subject alice :permission :allowed :resource/type :folder}
        reverse {:resource f0 :permission :allowed :subject/type :user}]
    (binding [engine/*operator-routing-enabled?* true]
      (is (= (vec
              (for [subject [alice bob] resource [f0 f1 f2]]
                (oracle/evaluated-check?
                 oracle-evaluation (oracle-object subject) :allowed
                 (oracle-object resource))))
             (vec
              (for [subject [alice bob] resource [f0 f1 f2]]
                (eacl/can? client {:subject subject :permission :allowed
                                   :resource resource}))))
          "recursive decisions match the independent stratified oracle")
      (is (= [true true false]
             (mapv #(eacl/can? client {:subject alice :permission :allowed
                                       :resource %})
                   [f0 f1 f2])))
      (is (= [true false false]
             (mapv #(eacl/can? client {:subject bob :permission :allowed
                                       :resource %})
                   [f0 f1 f2])))
      (is (= [true true false]
             (mapv :allowed?
                   (eacl/check-permissions
                    client
                    {:checks
                     (mapv #(hash-map :subject alice
                                      :permission :allowed
                                      :resource %)
                           [f0 f1 f2])}))))
      (is (= ["f0" "f1"]
             (ids (eacl/lookup-resources client (assoc forward :first 20)))))
      (is (= ["f1"]
             (ids
              (eacl/lookup-resources
               client
               (assoc forward :first 20
                      :resource/relationship
                      {:relation :eligible :subject alice})))))
      (is (= ["alice" "bob"]
             (ids (eacl/lookup-subjects client (assoc reverse :first 20)))))
      (is (= ["alice" "bob"]
             (ids
              (eacl/lookup-subjects
               client
               (assoc reverse :first 20
                      :subject/relationship
                      {:relation :direct :resource f0})))))
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
    (with-mem-conn [conn datomic-schema/v8-schema]
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
