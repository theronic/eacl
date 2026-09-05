(ns eacl.caveats.deletion-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.backend.writer :as backend-writer]
            [eacl.caveats.cache-trace-contract :as trace]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.relationships.qualifier-integrity :as integrity]
            [eacl.relationships.staged :as staged]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.storage :as storage]))

(defn check! [{:keys [client writer]}]
  (binding [orchestration/*qualified-authorization-enabled?* true]
    (eacl/write-schema! client {:schema (trace/schema "flag")})
    (let [w (writer) native (:native w) with-db (:with-snapshot native)
          subject (eacl/spice-object :user "delete/u")
          resources (mapv #(eacl/spice-object :doc (str "delete/" %)) (range 8))
          tx! (:transact! native)
          capture #(with-db (fn [db] (integrity/proof-input native db)))
          operation backend-writer/operation
          submitted (atom [])]
      (tx! (mapv #(hash-map :eacl/id (:id %)) (into [subject] resources)))
      (eacl/write-relationships! client
                                 (mapv (fn [i resource]
                                         {:operation :create
                                          :relationship (cond-> (eacl/->Relationship subject :member resource)
                                                          (even? i) (assoc :caveat "enabled" :valid-until-ms 100))})
                                       (range) resources))
      (eacl/write-relationships! client [{:operation :create
                                          :relationship (assoc (eacl/->Relationship (first resources) :parent (first resources))
                                                               :valid-until-ms 100)}])
      (eacl/prepare-relationship! client (assoc (eacl/->Relationship subject :member (first resources)) :valid-until-ms 500))
      (let [before (capture)
            orphan-ids (set (remove (set (keys (:references before))) (keys (:qualifiers before))))]
        (is (= 1 (count orphan-ids)))
        (with-redefs [backend-writer/max-transaction-size (constantly 12)
                      backend-writer/operation
                      (fn [writer op]
                        (let [f (operation writer op)]
                          (if (= op :transact!)
                            (fn [conn request]
                              (is (<= (count (:tx-data request)) 12))
                              (let [report (f conn request)
                                    frame (capture)
                                    status (integrity/report frame)]
                                (swap! submitted conj (:tx-data request))
                                (is (= :healthy (:status status)))
                                (is (= 1 (get-in status [:counts :unattached-qualifier])))
                                report))
                            f)))]
          (eacl/delete-object! client subject)
          (eacl/delete-object! client (first resources)))
        (is (> (count @submitted) 1))
        (let [after (capture)]
          (is (empty? (:references after)))
          (is (= orphan-ids (set (keys (:qualifiers after)))))
          (is (= :healthy (:status (integrity/report after)))))
        (is (empty? (:data (eacl/read-relationships client {:resource/type :doc :first 20}))))
        (is (zero? (:retracted-datoms (eacl/delete-object! client subject))))
        (let [relationship (assoc (eacl/->Relationship subject :member (first resources)) :valid-until-ms 300)]
          (eacl/create-relationship! client relationship)
          (let [{:keys [tx-data subject-id]}
                (with-db
                  (fn [db]
                    (let [[qid {:keys [forward]}] (first (:references (integrity/proof-input native db)))
                          [st subject rid rt resource] (first forward)]
                      {:subject-id subject
                       :tx-data (staged/plan-retraction-batch w db
                                                              [[:db/retract subject storage/forward-attribute
                                                                (pair/forward-value st rid rt resource qid)]])})))]
            (tx! [[:db/add subject-id :eacl/id "delete/renamed"]])
            (is (contains? (trace/outcome #(tx! tx-data)) :fault))
            (is (= 1 (count (:references (capture)))))
            (tx! [[:db/add subject-id :eacl/id "delete/u"]])
            (eacl/write-relationships! client [{:operation :touch :relationship (assoc relationship :valid-until-ms 400)}])
            (is (contains? (trace/outcome #(tx! tx-data)) :fault))
            (is (= :healthy (:status (integrity/report (capture)))))
            (is (= 1 (count (:references (capture)))))
            (eacl/delete-object! client subject)
            (is (= orphan-ids (set (keys (:qualifiers (capture))))))))))))
