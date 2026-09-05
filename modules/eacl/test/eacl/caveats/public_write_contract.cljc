(ns eacl.caveats.public-write-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [clojure.set :as set]
            [eacl.caveats.persistence-contract :as persistence]
            [eacl.caveats.publication-contract :as publication]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.relationships.storage :as storage]))

(defn check! [{:keys [client writer entid now allowance-stamps cas-attribute speculative?]
               :or {speculative? true}}]
  (eacl/write-schema! client persistence/first-schema)
  (let [native (:native (writer))
        with-db (:with-snapshot native)
        tx! (:transact! native)
        relation (with-db #(entid % [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]]))
        caveat (with-db #(entid % [:eacl.caveat/name "enabled"]))]
    (tx! (into [{:eacl/id "public/u"} {:eacl/id "public/a"} {:eacl/id "public/b"}
                {:db/id relation :eacl.relation/caveats [caveat] :eacl.relation/allows-unqualified? true}]
               (when allowance-stamps (with-db allowance-stamps))))
    (let [subject (eacl/spice-object :user "public/u")
          resource (eacl/spice-object :doc "public/a")
          other (eacl/spice-object :doc "public/b")
          relationship (assoc (eacl/->Relationship subject :viewer resource)
                              :caveat "enabled" :valid-until-ms 100)
          request {:subject subject :resource resource :permission :view}
          rows #(with-db (fn [db] (mapv :v ((:all-rows native) db storage/forward-attribute))))
          qids #(with-db (fn [db] (set (map :e ((:all-rows native) db :eacl.relationship-qualifier/format-version)))))
          error-data publication/error-data]
      (is (= :eacl/unsupported-capability
             (:type (error-data #(binding [orchestration/*qualified-authorization-enabled?* false]
                                   (eacl/write-relationships! client [{:operation :create :relationship relationship}]))))))
      (is (empty? (rows)))
      (binding [orchestration/*qualified-authorization-enabled?* true]
        (eacl/write-relationships! client [{:operation :create :relationship relationship}
                                           {:operation :create :relationship relationship}])
        (is (= 1 (count (rows)) (count (qids))))
        (is (= :conditional-permission (:permissionship (eacl/check-permission client request))))
        (is (true? (eacl/can? client (assoc request :caveat-context {"flag" true}))))
        (reset! now 100)
        (is (false? (eacl/can? client (assoc request :caveat-context {"flag" true}))))
        (is (= :eacl/relationship-conflict
               (:type (error-data #(eacl/create-relationship! client relationship)))))
        (let [before (qids)]
          (is (= :eacl/invalid-relationship-update-batch
                 (:type (error-data #(eacl/write-relationships!
                                      client [{:operation :touch :relationship relationship}
                                              {:operation :touch :relationship (assoc relationship :valid-until-ms 200)}])))))
          (is (= before (qids)))
          (eacl/write-relationship! client (assoc relationship :operation :touch
                                                  :caveat-context {"flag" true} :valid-until-ms 300))
          (is (true? (eacl/can? client request)))
          (is (= 1 (count (qids))))
          (is (empty? (set/intersection before (qids)))))
        (let [subject-id (with-db #(entid % [:eacl/id "public/u"]))
              flag-attribute (if cas-attribute (with-db #(cas-attribute % :app/flag)) :app/flag)
              before (rows)]
          (is (some? (error-data #(eacl/write-relationships!
                                   client {:updates [{:operation :touch :relationship (assoc relationship :valid-until-ms 200)}
                                                     {:operation :create :relationship (assoc relationship :resource other)}]
                                           :tx-data [[:db.fn/cas subject-id flag-attribute -1 1]]}))))
          (is (= before (rows)))
          (is (true? (eacl/can? client request)))
          (eacl/write-relationships! client {:updates [{:operation :touch :relationship (dissoc relationship :caveat :valid-until-ms)}]
                                             :tx-data [[:db/add subject-id :app/flag 42]]})
          (is (nil? (nth (first (rows)) 4)))
          (is (= 42 (with-db #(:app/flag ((:entity native) % subject-id)))))
          (is (true? (eacl/can? client request))))
        (eacl/write-relationship! client (assoc relationship :operation :delete :caveat "unused_name"))
        (is (empty? (rows)))
        (is (false? (eacl/can? client request)))
        (eacl/write-relationship! client (assoc relationship :operation :delete))
        (is (empty? (rows)))
        (let [qualified (assoc relationship :caveat-context {"flag" true} :valid-until-ms 300)
              before (qids)
              handle (eacl/prepare-relationship! client qualified)
              snapshot (eacl/snapshot client)
              update {:operation :create :relationship qualified :prepared-qualifier handle}
              tx-data
              (try
                (is (some? handle))
                (is (empty? (rows)) "inert preparation cannot publish either endpoint")
                (is (= (inc (count before)) (count (qids))))
                (is (= :prepared-value-mismatch
                       (:reason (error-data #(eacl/tx-relationship snapshot (assoc update :prepared-qualifier nil))))))
                (is (= :prepared-value-mismatch
                       (:reason (error-data #(eacl/tx-relationship snapshot (assoc-in update [:relationship :valid-until-ms] 400))))))
                (let [tx (eacl/tx-relationship snapshot update)]
                  (when speculative?
                    (let [prospective (eacl/with snapshot tx)]
                      (try
                        (is (true? (eacl/can? prospective request)))
                        (is (false? (eacl/can? client request)))
                        (finally (eacl/release! prospective)))))
                  tx)
                (finally (eacl/release! snapshot)))]
          (tx! tx-data)
          (is (true? (eacl/can? client request)))
          (is (= :qualifier-attached
                 (:reason (error-data #(eacl/discard-prepared-relationship! client handle)))))
          (let [orphan (eacl/prepare-relationship! client qualified)
                allocated (qids)]
            (eacl/discard-prepared-relationship! client orphan)
            (is (= (dec (count allocated)) (count (qids)))))
          (let [orphan (eacl/prepare-relationship! client qualified)
                snapshot (eacl/snapshot client)
                pending (try (eacl/tx-relationship snapshot {:operation :touch :relationship qualified
                                                             :prepared-qualifier orphan})
                             (finally (eacl/release! snapshot)))
                subject-id (with-db #(entid % [:eacl/id "public/u"]))
                before (rows)]
            (tx! [[:db/add subject-id :eacl/id "public/reassigned"]])
            (is (some? (error-data #(tx! pending))) "identity CAS rejects reassigned endpoints")
            (is (= before (rows)))
            (tx! [[:db/add subject-id :eacl/id "public/u"]])
            (eacl/discard-prepared-relationship! client orphan))
          (let [resource-id (with-db #(entid % [:eacl/id "public/a"]))
                reverse (with-db #(first (filter (fn [row] (= resource-id (:e row)))
                                                 ((:all-rows native) % storage/reverse-attribute))))
                before (rows)]
            (tx! [[:db/retract resource-id storage/reverse-attribute (:v reverse)]
                  [:db/add relation (:relation-version-attribute native)
                   (if (= :datomic (:backend native)) "datomic.tx" :db/current-tx)]])
            (doseq [operation [:touch :create]]
              (is (= :asymmetric-or-duplicate-relationship
                     (:reason (error-data #(eacl/write-relationships!
                                            client [{:operation operation :relationship qualified}]))))))
            (is (= before (rows)))
            (tx! [[:db/add resource-id storage/reverse-attribute (:v reverse)]
                  [:db/add relation (:relation-version-attribute native)
                   (if (= :datomic (:backend native)) "datomic.tx" :db/current-tx)]]))
          (let [relationships [(assoc qualified :valid-until-ms 400)
                               (assoc qualified :resource other)]
                handles (if (= :prepared (:strategy native))
                          (mapv #(eacl/prepare-relationship! client %) relationships)
                          [nil nil])
                snapshot (eacl/snapshot client)
                pending
                (try
                  (when (= :inline (:strategy native))
                    (let [inline-ids (mapv (fn [relationship]
                                             (->> (eacl/tx-relationship snapshot {:operation :touch :relationship relationship})
                                                  (filter #(and (map? %) (:eacl.relationship-qualifier/format-version %)))
                                                  first :db/id)) relationships)]
                      (is (= 2 (count (set inline-ids))) "separate pure plans allocate distinct native temporary ids")))
                  (eacl/tx-relationships
                   snapshot {:updates (mapv (fn [relationship handle]
                                              (cond-> {:operation :touch :relationship relationship}
                                                handle (assoc :prepared-qualifier handle)))
                                            relationships handles)})
                  (finally (eacl/release! snapshot)))]
            (is (= 1 (count (rows))))
            (tx! pending)
            (is (= 2 (count (rows)) (count (set (map #(nth % 4) (rows))))))
            (is (true? (eacl/can? client request)))
            (is (true? (eacl/can? client (assoc request :resource other))))))))))
