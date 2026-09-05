(ns eacl.relationships.staged
  "Explicitly non-serving qualified Relationship construction.

   Only native backend factories construct writers. No public client dispatch
   reaches this namespace until the qualified serving epoch is enabled."
  (:require [clojure.string :as str]
            [eacl.caveats.definition :as definition]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.storage :as storage]))

(defn error! [reason]
  (throw (ex-info "Invalid staged qualified Relationship operation."
                  {:type :eacl.qualifier/staged-write :eacl/error :eacl.qualifier/staged-write :reason reason})))

(defrecord NativeWriter [native identity source])
(defrecord NativePlanner [native source])
(deftype PreparedQualifier [writer-id source relationship qid value facts generation])

(defn prepared? [value] (instance? PreparedQualifier value))

(defn planner
  "Constructs a pure snapshot planner from a backend's read/transaction-data
   functions. No connection, source selector, or submission function is retained."
  [native db]
  (when (some #(contains? native %) [:snapshot :with-snapshot :transact!])
    (error! :mutable-planner))
  (->NativePlanner native ((:source native) db)))

(defn native-writer
  "Backend implementation boundary. The factory supplies native, certified
   allocation, identity reads, commit-time assertions, and atomic fences."
  [native]
  (when-not (and (contains? #{:datomic :datascript :datahike :datalevin} (:backend native))
                 (= (:strategy native) (get {:datomic :inline :datalevin :inline
                                             :datascript :prepared :datahike :prepared} (:backend native)))
                 (every? #(ifn? (get native %))
                         [:snapshot :source :entity :facts :rows :generation :fence :assert-entity :tempid :transact!]))
    (error! :unsupported-backend))
  (let [source ((:source native) ((:snapshot native)))]
    (when-not source (error! :missing-source-identity))
    (->NativeWriter (assoc native :with-snapshot
                           (or (:with-snapshot native) (fn [f] (f ((:snapshot native))))))
                    (random-uuid) source)))

(defn capability [writer]
  {:version 1 :backend (get-in writer [:native :backend])
   :strategy (get-in writer [:native :strategy]) :serving? false})

(defn- assert-source! [writer db]
  (when-not (= (:source writer) ((get-in writer [:native :source]) db)) (error! :source-mismatch)))

(defn- concrete-eid? [value] (and (integer? value) (pos? value)))

(defn- temporary-id [native]
  (let [id ((:tempid native))]
    (when-not (case (:backend native)
                (:datomic :datascript) (and (string? id) (seq id))
                (:datahike :datalevin) (and (integer? id) (neg? id))
                false)
      (error! :invalid-temporary-id))
    id))

(defn- selected-relation [native db identity]
  (when-not (and (vector? identity) (= 5 (count identity))
                 (keyword? (nth identity 0)) (keyword? (nth identity 3))
                 (every? concrete-eid? (map #(nth identity %) [1 2 4])))
    (error! :relationship-identity))
  (let [[subject-type subject-id relation-id resource-type resource-id] identity
        entity (:entity native)
        relation (entity db relation-id)]
    (when-not (and (seq (dissoc (entity db subject-id) :db/id))
                   (seq (dissoc (entity db resource-id) :db/id)))
      (error! :missing-endpoint))
    (when-not (and (= subject-type (:eacl.relation/subject-type relation))
                   (= resource-type (:eacl.relation/resource-type relation))
                   (keyword? (:eacl.relation/relation-name relation)))
      (error! :missing-relation))
    relation))

(defn- parameters [native db caveat]
  (when (some? caveat)
    (:parameters (definition/decode-entity ((:entity native) db caveat)))))

(defn- admitted-value [native db relation value]
  (let [value (qualifier/normalize value (parameters native db (:caveat value)))
        allowances (qualifier/relation-allowance relation)]
    (when-not (contains? allowances (:caveat value))
      (error! :caveat-not-allowed))
    value))

(defn- values-for [identity qid]
  (let [[subject-type subject-id relation-id resource-type resource-id] identity]
    [(pair/forward-value subject-type relation-id resource-type resource-id qid)
     (pair/reverse-value resource-type relation-id subject-type subject-id qid)]))

(defn- stored-pair [native db identity]
  (let [[_ subject-id _ _ resource-id] identity
        [forward reverse] (values-for identity nil)
        rows (:rows native)
        a (vec (take 2 (rows db subject-id storage/forward-attribute forward)))
        b (vec (take 2 (rows db resource-id storage/reverse-attribute reverse)))]
    (cond
      (and (empty? a) (empty? b)) {:present? false}
      (not= 1 (count a) (count b)) (error! :asymmetric-or-duplicate-relationship)
      :else
      (let [av (:v (first a)) bv (:v (first b)) qid (nth av 4 nil)]
        (when-not (and (pair/endpoint-value? av) (pair/endpoint-value? bv)
                       (= [av bv] (values-for identity qid)))
          (error! :asymmetric-or-duplicate-relationship))
        {:present? true :qid qid}))))

(defn- stored-qualifier [native db qid]
  (when qid
    (let [entity ((:entity native) db qid)]
      (qualifier/decode entity (parameters native db (:eacl.relationship-qualifier/caveat entity))))))

(defn prepare!
  "Creates only an inert qualifier. The opaque handle is bound to this writer,
   one native Relationship identity, immutable facts, and schema generation."
  [writer relationship value]
  (let [native (:native writer)
        prepared ((:with-snapshot native)
                  (fn [db]
                    (assert-source! writer db)
                    (let [relation (selected-relation native db relationship)
                          value (admitted-value native db relation value)
                          parameters (parameters native db (:caveat value))
                          tempid (when value (temporary-id native))]
                      {:value value :parameters parameters :tempid tempid
                       :generation ((:generation native) db)
                       :tx-data (when value
                                  (into ((:fence native) db nil (boolean (:caveat value)))
                                        [(qualifier/entity-data tempid value parameters)]))})))
        {:keys [value parameters tempid generation tx-data]} prepared]
    (if-not value
      (PreparedQualifier. (:identity writer) (:source writer) relationship nil nil [] generation)
      (let [report ((:transact! native) tx-data)
            qid (get (:tempids report) tempid)
            generation (if-let [after-reference (:generation-after-reference native)]
                         (after-reference report) generation)]
        (when-not (concrete-eid? qid) (error! :unresolved-qualifier))
        ((:with-snapshot native)
         (fn [db]
           (assert-source! writer db)
           (when-not (= value (qualifier/decode ((:entity native) db qid) parameters))
             (error! :prepared-qualifier-changed))
           (PreparedQualifier. (:identity writer) (:source writer) relationship qid value
                               ((:facts native) db qid) generation)))))))

(defn- prepared-value [writer db relationship prepared require-generation?]
  (let [native (:native writer)]
    (when-not (and (= (:source writer) (.-source ^PreparedQualifier prepared))
                   (or (instance? NativePlanner writer)
                       (= (:identity writer) (.-writer-id ^PreparedQualifier prepared)))
                   (= relationship (.-relationship ^PreparedQualifier prepared)))
      (error! :prepared-owner-mismatch))
    (when (and require-generation? (not= ((:generation native) db) (.-generation ^PreparedQualifier prepared)))
      (error! :prepared-schema-changed))
    (let [qid (.-qid ^PreparedQualifier prepared)
          value (.-value ^PreparedQualifier prepared)
          facts (.-facts ^PreparedQualifier prepared)]
      (when (and qid (not= facts ((:facts native) db qid)))
        (error! :prepared-qualifier-changed))
      {:qid qid :value value :facts facts})))

(defn- protected-attribute? [attribute]
  (or (not (keyword? attribute))
      (= "eacl" (namespace attribute))
      (some-> (namespace attribute) (str/starts-with? "eacl."))))

(defn application-datoms
  "Admits caller composition without allowing mutations of EACL-owned state."
  [datoms reserved-qids]
  (when-not (vector? datoms) (error! :application-datoms))
  (doseq [op datoms]
    (when-not (or (map? op) (vector? op)) (error! :application-datoms))
    (let [map-op? (map? op)
          eid (if map-op? (:db/id op) (second op))
          attrs (if map-op? (keys (dissoc op :db/id)) [(nth op 2 nil)])]
      (when-not (and (some? eid) (not (contains? reserved-qids eid))
                     (or map-op? (and (vector? op)
                                      (or (and (#{:db/add :db/retract} (first op)) (= 4 (count op)))
                                          (and (= :db.fn/cas (first op)) (= 5 (count op))))))
                     (not-any? protected-attribute? attrs))
        (error! :application-datoms))))
  ;; Native ref-valued attributes may expand nested entity maps into writes.
  ;; Validate those maps too; a harmless-looking application attribute cannot
  ;; smuggle a qualifier edit or a new EACL entity into the publication tx.
  (doseq [node (tree-seq coll? seq datoms)
          :when (map? node)]
    (when (or (contains? reserved-qids (:db/id node))
              (some #(and (keyword? %) (protected-attribute? %)) (keys (dissoc node :db/id))))
      (error! :application-datoms)))
  datoms)

(defn- plan-entry
  [writer db {:keys [operation relationship value expected-value] :as entry} app-datoms include-fences?]
  (when-not (contains? #{:create :replace :touch :delete} operation) (error! :operation))
  (let [native (:native writer)
        _ (assert-source! writer db)
        relation (selected-relation native db relationship)
        prior (stored-pair native db relationship)
        operation (if (= :touch operation) (if (:present? prior) :replace :create) operation)
        old-qid (:qid prior)
        _ (stored-qualifier native db old-qid)
        _ (when (or (and (= :create operation) (:present? prior))
                    (and (= :replace operation) (not (:present? prior))))
            (error! :relationship-conflict))
        prepared? (instance? PreparedQualifier value)
        prepared (when prepared? (prepared-value writer db relationship value true))
        semantic (when-not (= :delete operation)
                   (admitted-value native db relation (if prepared? (:value prepared) value)))
        _ (when (and (contains? entry :expected-value)
                     (not= semantic (admitted-value native db relation expected-value)))
            (error! :prepared-value-mismatch))
        _ (when (and (= :prepared (:strategy native)) semantic (not prepared?))
            (error! :prepared-qualifier-required))
        inline? (and semantic (not prepared?))
        qid (when semantic (if inline? (temporary-id native) (:qid prepared)))
        _ (when (and qid (= qid old-qid)) (error! :qualifier-reuse))
        _ (when (and prepared? semantic (not (concrete-eid? qid))) (error! :unresolved-qualifier))
        app-datoms (application-datoms app-datoms (set (remove nil? [qid old-qid])))
        [subject-type subject-id relation-id resource-type resource-id] relationship
        [forward reverse] (values-for relationship qid)
         ;; Datomic rejects retract/add of an identical tuple in one tx. A
         ;; plain-to-plain replacement still fences and stamps the Relation
         ;; and commits application datoms, while retaining its existing pair.
        retain-pair? (and (= :replace operation) (nil? old-qid) (nil? qid))
        tx (vec
            (concat
             (when include-fences?
               ((:fence native) db relation-id (boolean (and inline? (:caveat semantic)))))
             (when (and prepared? qid) [((:assert-entity native) qid (:facts prepared))])
             (when old-qid [((:assert-entity native) old-qid ((:facts native) db old-qid))])
             (when inline? [(qualifier/entity-data qid semantic (parameters native db (:caveat semantic)))])
             (when (and (:present? prior) (not retain-pair?))
               (pair/retractions subject-type subject-id relation-id resource-type resource-id old-qid))
             (when old-qid [[:db/retractEntity old-qid]])
             (when-not (or (= :delete operation) retain-pair?)
               [[:db/add subject-id storage/forward-attribute forward]
                [:db/add resource-id storage/reverse-attribute reverse]])
             app-datoms))]
    {:tx-data tx :operation operation :relationship relationship :qualifier-eid qid
     :old-qualifier-eid old-qid :adds-caveat-reference? (boolean (and inline? (:caveat semantic)))}))

(defn plan
  "Plans one native create/replace/touch/delete plus application datoms.
   Prepared backends require a handle; inline backends allocate in this tx."
  ([writer db operation relationship value] (plan writer db operation relationship value []))
  ([writer db operation relationship value app-datoms]
   (select-keys (plan-entry writer db {:operation operation :relationship relationship :value value} app-datoms true)
                [:tx-data :operation :relationship :qualifier-eid])))

(defn- unique-batch! [entries]
  (when-not (vector? entries) (error! :batch-shape))
  (when-not (= (count entries) (count (set (map :relationship entries))))
    (error! :duplicate-batch-identity))
  (doseq [{:keys [operation]} entries]
    (when-not (#{:create :replace :touch :delete} operation) (error! :operation)))
  entries)

(defn- batch-fences! [native]
  (when-not (and (ifn? (:schema-fence native)) (ifn? (:relation-fence native)))
    (error! :unsupported-batch-fences)))

(defn- unique-qualifiers! [qids]
  (let [qids (remove nil? qids)]
    (when-not (= (count qids) (count (set qids)))
      (error! :qualifier-reuse))))

(defn prepare-batch!
  "Admits the whole batch before allocating inert qualifiers in one native
   transaction. Inline writers retain semantic values for final publication."
  ([writer entries] (prepare-batch! writer entries false))
  ([writer entries force-prepared?]
   (unique-batch! entries)
   (let [native (:native writer)
         _ (batch-fences! native)
         prepared?
         (or force-prepared? (= :prepared (:strategy native)))
         admitted
         ((:with-snapshot native)
          (fn [db]
            (assert-source! writer db)
            (let [items
                  (mapv (fn [{:keys [operation relationship value schema-generation] :as entry}]
                          (when (and (contains? entry :schema-generation)
                                     (not= schema-generation ((:generation native) db)))
                            (error! :prepared-schema-changed))
                          (let [relation (selected-relation native db relationship)
                                prior (stored-pair native db relationship)
                                _ (stored-qualifier native db (:qid prior))
                                _ (when (or (and (= :create operation) (:present? prior))
                                            (and (= :replace operation) (not (:present? prior))))
                                    (error! :relationship-conflict))
                                semantic (when-not (= :delete operation) (admitted-value native db relation value))
                                tempid (when (and prepared? semantic) (temporary-id native))]
                            {:entry (assoc entry :value semantic) :tempid tempid
                             :parameters (when semantic (parameters native db (:caveat semantic)))}))
                        entries)
                  ids (keep :tempid items)
                  _ (when-not (= (count ids) (count (set ids))) (error! :duplicate-temporary-id))
                  facts (into [] (keep (fn [{:keys [entry tempid parameters]}]
                                         (when tempid (qualifier/entity-data tempid (:value entry) parameters)))) items)]
              {:items items :generation ((:generation native) db)
               :tx-data (when (seq facts)
                          (into ((:schema-fence native) db (boolean (some #(get-in % [:entry :value :caveat]) items)))
                                facts))})))
         {:keys [items generation tx-data]} admitted]
     (if-not tx-data
       (mapv :entry items)
       (let [report ((:transact! native) tx-data)
             _ (unique-qualifiers! (map #(get (:tempids report) (:tempid %)) items))
             generation (if-let [after-reference (:generation-after-reference native)]
                          (after-reference report) generation)]
         ((:with-snapshot native)
          (fn [db]
            (assert-source! writer db)
            (mapv (fn [{:keys [entry tempid parameters]}]
                    (if-not tempid
                      (assoc entry :schema-generation generation)
                      (let [qid (get (:tempids report) tempid)
                            value (:value entry)]
                        (when-not (concrete-eid? qid) (error! :unresolved-qualifier))
                        (when-not (= value (qualifier/decode ((:entity native) db qid) parameters))
                          (error! :prepared-qualifier-changed))
                        (assoc entry :schema-generation generation :value
                               (PreparedQualifier. (:identity writer) (:source writer) (:relationship entry) qid value
                                                   ((:facts native) db qid) generation)))))
                  items))))))))

(defn plan-batch
  "One schema fence and one Relation fence per affected identity partition.
   All endpoint swaps and application datoms share the final atomic commit."
  [writer db entries app-datoms]
  (unique-batch! entries)
  (let [native (:native writer)
        _ (batch-fences! native)
        plans (mapv (fn [{:keys [schema-generation] :as entry}]
                      (when (and (contains? entry :schema-generation)
                                 (not= schema-generation ((:generation native) db)))
                        (error! :prepared-schema-changed))
                      (plan-entry writer db entry [] false)) entries)
        _ (unique-qualifiers! (mapcat (juxt :qualifier-eid :old-qualifier-eid) plans))
        qids (into #{} (remove nil?) (mapcat (juxt :qualifier-eid :old-qualifier-eid) plans))
        app-datoms (application-datoms app-datoms qids)
        relations (sort (set (map #(nth (:relationship %) 2) plans)))]
    {:tx-data (vec (concat (when (seq entries)
                             ((:schema-fence native) db (boolean (some :adds-caveat-reference? plans))))
                           (mapcat #((:relation-fence native) db %) relations)
                           (distinct (mapcat :identity-guards entries))
                           (mapcat :tx-data plans)
                           app-datoms))
     :plans (mapv #(dissoc % :tx-data :old-qualifier-eid :adds-caveat-reference?) plans)}))

(defn plan-batch-current [writer entries app-datoms]
  ((get-in writer [:native :with-snapshot]) #(plan-batch writer % entries app-datoms)))

(defn plan-current
  "Plans against one owned native read snapshot and releases it before the
   caller submits or composes the transaction. Native fences reject stale plans."
  ([writer operation relationship value] (plan-current writer operation relationship value []))
  ([writer operation relationship value app-datoms]
   ((get-in writer [:native :with-snapshot])
    #(plan writer % operation relationship value app-datoms))))

(defn write!
  "Convenience staged writer. Prepared allocations that lose publication are
   harmless orphans; cleanup! can remove their unchanged handles."
  ([writer operation relationship value] (write! writer operation relationship value []))
  ([writer operation relationship value app-datoms]
   (let [native (:native writer)
         prepared (if (and (not= :delete operation) (= :prepared (:strategy native))
                           (not (instance? PreparedQualifier value)))
                    (prepare! writer relationship value) value)]
     ((:transact! native) (:tx-data (plan-current writer operation relationship prepared app-datoms))))))

(defn cleanup!
  "Retracts an unchanged, still-unattached preparation. A consumed handle can
   never remove an attached qualifier or a replacement's entity."
  [writer prepared]
  (when-not (instance? PreparedQualifier prepared) (error! :prepared-qualifier-required))
  (let [native (:native writer)
        tx-data
        ((:with-snapshot native)
         (fn [db]
           (assert-source! writer db)
           (let [relationship (.-relationship ^PreparedQualifier prepared)
                 {:keys [qid facts]} (prepared-value writer db relationship prepared false)
                 relation-id (nth relationship 2)
                 relation-exists? (:eacl.relation/relation-name ((:entity native) db relation-id))]
             (when (and qid (= qid (:qid (stored-pair native db relationship))))
               (error! :qualifier-attached))
             (when qid
               (into ((:fence native) db (when relation-exists? relation-id) false)
                     [((:assert-entity native) qid facts) [:db/retractEntity qid]])))))]
    (when tx-data ((:transact! native) tx-data))))
