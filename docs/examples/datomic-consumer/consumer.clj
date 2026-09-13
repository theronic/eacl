(ns consumer
  "Datomic memory recipes using the published EACL dependency."
  (:require [clojure.test :refer [deftest is run-tests]]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.safe-retraction :as safe]))

(def app-schema
  [{:db/ident :app/id :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :document/title :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def permission-schema
  "definition user {}
   definition folder {
     relation viewer: user
     permission view = viewer
   }
   definition document {
     relation viewer: user
     relation editor: user
     relation owner: user
     relation folder: folder
     permission view = viewer + editor + owner + folder->view
     permission edit = editor + owner
     permission share = owner
   }")

(defn start! ([] (start! {}))
  ([options]
   (let [uri (str "datomic:mem://eacl-consumer-" (random-uuid))
         _ (d/create-database uri)
         conn (d/connect uri)]
     (schema/install! conn)
     @(d/transact conn app-schema)
     (let [acl (datomic/make-client
                conn (merge {:object-id->lookup-ref (fn [id] [:app/id id])
                             :entid->object-id (fn [db eid] (:app/id (d/entity db eid)))}
                            options))]
       (eacl/write-schema! acl permission-schema)
       @(d/transact conn [{:app/id "alice"} {:app/id "bob"}
                          {:app/id "report" :document/title "Report"}
                          {:app/id "shared"}])
       {:uri uri :conn conn :acl acl}))))

(def alice (eacl/spice-object :user "alice"))
(def bob (eacl/spice-object :user "bob"))
(def report (eacl/spice-object :document "report"))
(def folder (eacl/spice-object :folder "shared"))
(def viewer (eacl/->Relationship alice :viewer report))

(defn sharing-page [acl subject]
  (let [snapshot (eacl/snapshot acl)]
    (try
      (when-not (eacl/can? snapshot subject :share report)
        (throw (ex-info "You cannot manage sharing for this document." {:type :app/forbidden})))
      (eacl/read-relationships snapshot
                               {:resource/type :document :resource/id "report" :first 50})
      (finally (eacl/release! snapshot)))))

(deftest consumer-recipes
  (let [now (atom 1000)
        {:keys [uri conn acl]} (start! {:clock #(deref now)})]
    (try
      (eacl/create-relationship! acl viewer)
      (is (true? (eacl/can? acl alice :view report)))
      (is (false? (eacl/can? acl bob :view report)))
      (is (= ["report"] (mapv :id (:data (eacl/lookup-resources acl
                                                                {:subject alice :permission :view
                                                                 :resource/type :document :first 10})))))

      ;; Existing endpoints: commit application data and a relationship together.
      (let [snapshot (eacl/snapshot acl)]
        (try
          @(d/transact conn
                       (eacl/tx-relationships snapshot
                                              {:updates [{:operation :touch :relationship viewer}]
                                               :tx-data [[:db/add [:app/id "report"] :document/title "Shared report"]]}))
          (finally (eacl/release! snapshot))))
      (is (= "Shared report" (:document/title (d/entity (d/db conn) [:app/id "report"]))))

      ;; Public planning resolves endpoints in the snapshot, not in :tx-data.
      (let [snapshot (eacl/snapshot acl)]
        (try
          (is (= :eacl/unknown-object
                 (try
                   (eacl/tx-relationships snapshot
                                          {:updates [{:operation :create
                                                      :relationship (eacl/->Relationship
                                                                     alice :viewer (eacl/spice-object :document "new"))}]
                                           :tx-data [{:db/id "new-tempid" :app/id "new"}]})
                   nil
                   (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
          (finally (eacl/release! snapshot))))

      ;; No evaluator is installed. The clock is controlled only for this test.
      (eacl/write-relationship! acl (assoc viewer :operation :touch :valid-until-ms 2000))
      (reset! now 1999)
      (is (eacl/can? acl alice :view report))
      (reset! now 2000)
      (is (false? (eacl/can? acl alice :view report)))
      (is (= 1 (count (:data (eacl/read-relationships acl
                                                      {:resource/type :document :resource/id "report" :first 50})))))
      (eacl/write-relationship! acl (assoc viewer :operation :touch :valid-until-ms 3000))
      (is (eacl/can? acl alice :view report))
      (eacl/write-relationship! acl (assoc viewer :operation :touch))
      (reset! now 3000)
      (is (eacl/can? acl alice :view report))

      ;; Change the role in one batch.
      (eacl/write-relationships! acl
                                 [{:operation :delete :relationship viewer}
                                  {:operation :touch :relationship (eacl/->Relationship alice :editor report)}])
      (is (eacl/can? acl alice :edit report))
      (eacl/delete-relationship! acl alice :editor report)

      ;; An expired direct grant does not remove inherited access.
      (eacl/create-relationships! acl
                                  [(eacl/->Relationship alice :viewer folder)
                                   (eacl/->Relationship folder :folder report)
                                   (assoc viewer :valid-until-ms 4000)])
      (reset! now 4000)
      (is (eacl/can? acl alice :view report))
      (eacl/create-relationship! acl alice :owner report)
      (is (seq (:data (sharing-page acl alice))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot manage" (sharing-page acl bob)))
      (is (= 1 (count (:data (eacl/read-relationships acl
                                                      {:subject/type :folder :subject/id "shared"
                                                       :resource/type :document :resource/relation :folder
                                                       :authorization {:subject alice :permission :view :on :resource}
                                                       :first 50})))))
      (is (= ["report"] (mapv :id (:data (eacl/lookup-resources acl
                                                                {:subject alice :permission :view :resource/type :document
                                                                 :resource/relationship {:relation :folder :subject folder} :first 50})))))

      (safe/install! conn)
      @(d/transact conn (safe/retract-entity-tx-data [:app/id "report"]))
      (is (nil? (d/entid (d/db conn) [:app/id "report"])))
      (is (false? (eacl/can? acl alice :view report)))
      (is (empty? (:data (eacl/read-relationships acl {:resource/type :document :first 50}))))
      (finally (d/delete-database uri)))))

(defn verify! [] (run-tests 'consumer))
