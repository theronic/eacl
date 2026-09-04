(ns eacl.relationships.migration-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.core :as eacl]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade-test :refer [error-data]]))

(def schema "definition user {}
definition document {
 relation viewer: user
 permission view = viewer
}")

(defn seed!
  [{:keys [snapshot transact! entid write-schema!] :as fixture}]
  (when write-schema! (write-schema! schema))
  (transact! [{:eacl/id "schema-string" :eacl/storage-version 7}
              {:eacl/id "document"} {:eacl/id "alice"} {:eacl/id "bob"}])
  (let [db (snapshot)
        r (entid db [:eacl.relation/resource-type+relation-name+subject-type [:document :viewer :user]])
        [doc a b] (mapv #(entid db [:eacl/id %]) ["document" "alice" "bob"])]
    (transact! [[:db/add a legacy/forward-attribute [:user r :document doc]]
                [:db/add b legacy/forward-attribute [:user r :document doc]]
                [:db/add doc legacy/reverse-attribute [:document r :user a]]
                [:db/add doc legacy/reverse-attribute [:document r :user b]]])
    (assoc fixture :relation r :document doc :alice a :bob b)))

(defn exercise-public! [open-source]
  (let [{:keys [client! migrate! close!]} (seed! (open-source))]
    (try
      (migrate! {:quiesced? true :batch-size 1})
      (let [client (client!)
            alice (eacl/spice-object :user "alice")
            bob (eacl/spice-object :user "bob")
            doc (eacl/spice-object :document "document")
            subjects {:resource doc :permission :view :subject/type :user}
            relationships (fn [] (:data (eacl/read-relationships client {:resource/type :document :first 10})))]
        (is (true? (eacl/can? client alice :view doc)))
        (is (true? (eacl/can? client bob :view doc)))
        (is (= #{"alice" "bob"} (set (map :id (:data (eacl/lookup-subjects client (assoc subjects :first 10)))))))
        (is (= 2 (:count (eacl/count-subjects client subjects))))
        (is (= #{(eacl/->Relationship alice :viewer doc) (eacl/->Relationship bob :viewer doc)}
               (set (relationships))))
        (is (= ["document"] (mapv :id (:data (eacl/lookup-resources client {:subject alice :permission :view :resource/type :document :first 10})))))
        (eacl/delete-relationship! client (eacl/->Relationship alice :viewer doc))
        (is (false? (eacl/can? client alice :view doc)))
        (is (true? (eacl/can? client bob :view doc)))
        (is (= 1 (:count (eacl/count-subjects client subjects))))
        (is (= [(eacl/->Relationship bob :viewer doc)] (relationships))))
      (finally (close!)))))

(defn exercise-admission! [open-source]
  (let [{:keys [client! migrate! transact! close! alice relation document]} (seed! (open-source))]
    (try
      (is (= :eacl/storage-version (:type (error-data client!))))
      (migrate! {:quiesced? true})
      (is (some? (client!)))
      (transact! [[:db/add [:eacl/id "schema-string"] :eacl/storage-version 7]])
      (is (= :incomplete-storage (:reason (error-data client!))))
      (transact! [[:db/add [:eacl/id "schema-string"] :eacl/storage-version 9]])
      (is (some? (client!)))
      (transact! [[:db/add alice legacy/forward-attribute
                   (legacy/endpoint-value :user relation :document document)]])
      (is (= :legacy-data (:reason (error-data client!))))
      (finally (close!)))))

(defn exercise-resume!
  "Runs the same interruption/recovery checks against native adapters."
  [open-source]
  (doseq [stop [:preflight :converting :verifying :cleaning :complete]]
    (let [{:keys [snapshot migrate! evidence close! rows revision reopen! prepare-source! client!]} (seed! (open-source))]
      (try
        (when prepare-source! (prepare-source!))
        (is (= 7 (:version (evidence (snapshot)))))
        (is (= :eacl/storage-version (:type (error-data client!))))
        (is (= :interrupted
               (:reason (error-data
                         #(migrate! {:quiesced? true :batch-size 1
                                     :on-progress (fn [report]
                                                    (when (and (= stop (:state report))
                                                               (or (not= :converting stop) (= 1 (:converted report))))
                                                      (throw (ex-info "Test interruption" {:reason :interrupted}))))})))))
        (when-not (= :complete stop)
          (is (= 7 (:version (evidence (snapshot)))))
          (is (= :eacl/storage-version (:type (error-data client!)))))
        (when reopen! (reopen!))
        (let [report (migrate! {:quiesced? true :batch-size 1})
              after (snapshot)
              rev (revision after)]
          (is (= :complete (:state report)))
          (is (= 2 (:source-count report)))
          (is (= 9 (:version (evidence after))))
          (is (empty? (rows after legacy/forward-attribute)))
          (is (empty? (rows after legacy/reverse-attribute)))
          (is (= 2 (count (seq (rows after storage/forward-attribute)))))
          (is (= 2 (count (seq (rows after storage/reverse-attribute)))))
          (is (every? #(and (= 5 (count (:v %))) (nil? (peek (:v %))))
                      (rows after storage/forward-attribute)))
          (is (:already-complete? (migrate! {:quiesced? true})))
          (is (= rev (revision (snapshot)))))
        (finally (close!))))))

(defn exercise-concurrent-head! [open-source]
  (let [{:keys [snapshot transact! migrate! evidence close! prepare-source!]} (seed! (open-source))]
    (try
      (when prepare-source! (prepare-source!))
      (is (= :concurrent-write
             (:reason (error-data
                       #(migrate! {:quiesced? true
                                   :on-progress (fn [_] (transact! [{:eacl/id "foreign-writer"}]))})))))
      (is (= 7 (:version (evidence (snapshot)))))
      (finally (close!)))))
