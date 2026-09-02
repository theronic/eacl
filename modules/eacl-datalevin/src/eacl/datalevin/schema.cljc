(ns eacl.datalevin.schema
  (:require [clojure.string :as str]
            [datalevin.core :as ds]
            [eacl.datalevin.db :as ddb]
            [eacl.datalevin.fork :as fork]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.expression-policy :as expression-policy]
            [eacl.schema.expression-resolver :as expression-resolver]
            [eacl.schema.model :as model]))

(def datalevin-schema
  {:eacl/id {:db/valueType :db.type/string
             :db/unique :db.unique/identity}
   :eacl/schema-string {:db/valueType :db.type/string}
   :eacl.datalevin/schema-generation {:db/valueType :db.type/long}
   :eacl.datalevin/schema-write-fence {:db/valueType :db.type/long}
   :eacl.datalevin/relation-generation {:db/valueType :db.type/long}
   :eacl.datalevin/source-id {:db/valueType :db.type/uuid
                              :db/unique :db.unique/identity}
   :eacl.relation/resource-type {:db/valueType :db.type/keyword
                                 :db/index true}
   :eacl.relation/relation-name {:db/valueType :db.type/keyword
                                 :db/index true}
   :eacl.relation/subject-type {:db/valueType :db.type/keyword
                                :db/index true}
   :eacl.relation/resource-type+relation-name+subject-type
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relation/resource-type
                    :eacl.relation/relation-name
                    :eacl.relation/subject-type]
    :db/unique :db.unique/identity}

   :eacl.permission/resource-type {:db/valueType :db.type/keyword
                                   :db/index true}
   :eacl.permission/permission-name {:db/valueType :db.type/keyword
                                     :db/index true}
   :eacl.permission/source-relation-name
   {:db/valueType :db.type/keyword :db/index true}
   :eacl.permission/target-type {:db/valueType :db.type/keyword
                                 :db/index true}
   :eacl.permission/target-name {:db/valueType :db.type/keyword
                                 :db/index true}
   :eacl.permission/expression-payload {:db/valueType :db.type/string}
   :eacl.permission/resource-type+permission-name
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/permission-name]
    :db/index true}

   relationship-storage/forward-attribute
   {:db/valueType :db.type/tuple
    :db/tupleTypes [:db.type/keyword :db.type/ref
                    :db.type/keyword :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index true}
   relationship-storage/reverse-attribute
   {:db/valueType :db.type/tuple
    :db/tupleTypes [:db.type/keyword :db.type/ref
                    :db.type/keyword :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index true}})

(defn merge-schema
  ([] datalevin-schema)
  ([extra-schema]
   (merge datalevin-schema extra-schema)))

(declare prepare-cache-coherence!)

(defn- eacl-storage-attribute?
  [attribute]
  (let [attribute-namespace (some-> attribute namespace)]
    (and (keyword? attribute)
         (or (= "eacl" attribute-namespace)
             (some-> attribute-namespace (str/starts-with? "eacl.")))
         (not= :eacl/id attribute))))

(defn- definition-attribute?
  [attribute]
  (or (= :eacl/schema-string attribute)
      (contains? #{"eacl.relation" "eacl.permission"}
                 (namespace attribute))))

(defn- expected-write-policy
  [conn]
  (let [db (ds/db conn)
        schema-eid (ds/entid db [:eacl/id "schema-string"])]
    (when-not schema-eid
      (throw
       (ex-info
        "Datalevin write-policy installation requires the schema singleton."
        {:type :eacl.cache/generation-unprepared
         :eacl/error :eacl.cache/generation-unprepared
         :backend :datalevin
         :missing :schema-singleton})))
    (let [guarded (into #{} (filter eacl-storage-attribute?)
                        (keys (ds/schema conn)))
          definition-attributes (filter definition-attribute? guarded)]
      {:guarded-attributes guarded
       :frozen-attributes guarded
       :commit-generation-attributes
       #{:eacl.datalevin/schema-generation
         :eacl.datalevin/schema-write-fence
         :eacl.datalevin/relation-generation}
       :stamp-rules
       (into
        [{:when-attribute relationship-storage/forward-attribute
          :stamp-attribute :eacl.datalevin/relation-generation
          :stamp-entity [:tuple-position 1]}
         {:when-attribute relationship-storage/reverse-attribute
          :stamp-attribute :eacl.datalevin/relation-generation
          :stamp-entity [:tuple-position 1]}]
        (map
         (fn [attribute]
           {:when-attribute attribute
            :stamp-attribute :eacl.datalevin/schema-generation
            :stamp-entity [:constant schema-eid]}))
        definition-attributes)
       :guarded-write-hint
       "Protected EACL data requires the admitted writer; use delete-object! for permissioned-object relationship cleanup."})))

(defn- generation-gaps
  [db]
  (let [schema-eid (ds/entid db [:eacl/id "schema-string"])
        relations
        (mapv :e
              (ddb/avet-datoms
               db :eacl.relation/resource-type+relation-name+subject-type))]
    (cond-> []
      (nil? schema-eid)
      (conj :schema-singleton)

      (and schema-eid
           (empty? (ds/datoms db :eav schema-eid
                              :eacl.datalevin/schema-generation)))
      (conj :eacl.datalevin/schema-generation)

      (and schema-eid
           (empty? (ds/datoms db :eav schema-eid
                              :eacl.datalevin/schema-write-fence)))
      (conj :eacl.datalevin/schema-write-fence)

      true
      (into
       (for [relation-eid relations
             :when (empty? (ds/datoms db :eav relation-eid
                                      :eacl.datalevin/relation-generation))]
         relation-eid)))))

(defn create-conn
  ([] (create-conn nil nil nil))
  ([dir] (create-conn dir nil nil))
  ([dir extra-schema] (create-conn dir extra-schema nil))
  ([dir extra-schema store-options]
   ;; Qualification and bootstrap belong to make-client. Merely opening a
   ;; connection must not submit an unadmitted protected transaction.
   (ds/get-conn dir (merge-schema extra-schema) store-options)))

(def ^:private physical-schema-keys
  #{:db/valueType :db/cardinality :db/unique :db/index
    :db/tupleAttrs :db/tupleTypes :db/tupleType})

(defn- normalized-attribute
  [schema attribute]
  (let [normalized (select-keys attribute physical-schema-keys)]
    (if (and (:db/tupleAttrs normalized)
             (not (:db/tupleTypes normalized)))
      (assoc normalized
             :db/tupleTypes
             (mapv #(get-in schema [% :db/valueType])
                   (:db/tupleAttrs normalized)))
      normalized)))

(defn ensure-physical-schema!
  "Installs missing EACL attributes on a quiesced embedded connection and
  rejects any incompatible definition. Installs the storage write policy and
  returns the persisted source UUID plus the per-open writer token."
  [conn]
  (let [existing-policy (fork/write-policy conn)
        actual (ds/schema conn)
        drift
        (into {}
              (keep
               (fn [[attribute expected]]
                 (when-let [present (get actual attribute)]
                   (when-not (= (normalized-attribute datalevin-schema expected)
                                (normalized-attribute actual present))
                     [attribute
                      {:expected (normalized-attribute datalevin-schema expected)
                       :actual (normalized-attribute actual present)}]))))
              datalevin-schema)]
    (when (seq drift)
      (throw
       (ex-info
        "Datalevin physical EACL schema has drifted."
        {:type :eacl.datalevin/physical-schema-drift
         :eacl/error :eacl.datalevin/physical-schema-drift
         :backend :datalevin
         :drift drift})))
    (let [missing (into {}
                        (remove #(contains? actual (key %)))
                        datalevin-schema)]
      (when (seq missing)
        (ds/update-schema conn missing)))
    (let [db (ds/db conn)
          metadata (ds/entity db [:eacl/id "datalevin-metadata"])
          existing (:eacl.datalevin/source-id metadata)]
      (when (and existing-policy (not (uuid? existing)))
        (throw
         (ex-info
          "A protected Datalevin store has no valid persisted source identity."
          {:type :eacl/invalid-source-identity
           :eacl/error :eacl/invalid-source-identity
           :backend :datalevin
           :value existing})))
      (let [source-id
            (cond
              (uuid? existing) existing

              (some? existing)
              (throw
               (ex-info
                "Datalevin source identity is not a UUID."
                {:type :eacl/invalid-source-identity
                 :eacl/error :eacl/invalid-source-identity
                 :backend :datalevin
                 :value existing}))

              :else
              (let [source-id (random-uuid)]
                (ds/transact!
                 conn
                 [{:eacl/id "datalevin-metadata"
                   :eacl.datalevin/source-id source-id}])
                source-id))]
        ;; The constant schema stamp selector needs a durable eid, but the
        ;; generation values themselves cannot use :db/current-tx until the
        ;; policy declares them as commit-generation attributes.
        (when-not existing-policy
          (when-not (ds/entid (ds/db conn) [:eacl/id "schema-string"])
            (ds/transact! conn [{:eacl/id "schema-string"}])))
        (let [expected-policy (expected-write-policy conn)
              policy-result
              (try
                (fork/install-write-policy! conn expected-policy)
                (catch #?(:clj Throwable :cljs :default) error
                  (throw
                   (ex-info
                    "Datalevin's persisted EACL write policy does not match the module contract."
                    {:type :eacl.datalevin/write-policy-drift
                     :eacl/error :eacl.datalevin/write-policy-drift
                     :backend :datalevin}
                    error))))
              _ (when-not existing-policy
                  (prepare-cache-coherence!
                   conn (:write-token policy-result)))
              gaps (generation-gaps (ds/db conn))]
          (when (seq gaps)
            (throw
             (ex-info
              "A protected Datalevin store has incomplete generation evidence."
              {:type :eacl.cache/generation-unprepared
               :eacl/error :eacl.cache/generation-unprepared
               :backend :datalevin
               :missing gaps})))
          {:source-id source-id
           :schema-eid (ds/entid (ds/db conn) [:eacl/id "schema-string"])
           :write-token (:write-token policy-result)
           :write-policy (:policy policy-result)
           :fork-capabilities (:capabilities policy-result)})))))

(def relation-pull
  [:eacl/id :eacl.relation/subject-type
   :eacl.relation/resource-type :eacl.relation/relation-name])

(def permission-pull
  [:eacl/id :eacl.permission/resource-type
   :eacl.permission/permission-name
   :eacl.permission/source-relation-name
   :eacl.permission/target-type :eacl.permission/target-name
   :eacl.permission/expression-payload])

(defn- eager-entity
  "The named attributes of one entity as a plain map; the pull vectors above
  name attributes only, never `:db/id`."
  [db eid attributes]
  (let [entity (ds/entity db eid)]
    (into {}
          (keep (fn [attribute]
                  (when-some [value (get entity attribute)]
                    [attribute value])))
          attributes)))

(defn read-relations
  [db]
  (mapv #(eager-entity db (:e %) relation-pull)
        (ddb/avet-datoms
         db :eacl.relation/resource-type+relation-name+subject-type)))

(defn read-permissions
  [db]
  (mapv #(eager-entity db (:e %) permission-pull)
        (ddb/avet-datoms
         db :eacl.permission/resource-type+permission-name)))

(defn read-schema
  [snapshot-or-db & [_format]]
  (ddb/with-db
   snapshot-or-db
   (fn [db]
     (let [permissions (read-permissions db)]
       (expression-persistence/validate-entities permissions)
       {:relations (read-relations db)
        :permissions permissions}))))

(defn prepare-cache-coherence!
  "Initializes missing physical schema/relation generations and the schema
  write fence additively."
  ([conn]
   (prepare-cache-coherence! conn nil))
  ([conn write-token]
  (let [db (ds/db conn)
        physical-schema (ds/schema conn)
        _ (when-not (and (contains? physical-schema :eacl.datalevin/schema-generation)
                         (contains? physical-schema :eacl.datalevin/schema-write-fence)
                         (contains? physical-schema :eacl.datalevin/relation-generation))
            (throw
             (ex-info
              "Datalevin connection schema lacks native EACL generation attributes."
              {:type :eacl.cache/generation-schema-missing
               :eacl/error :eacl.cache/generation-schema-missing
               :backend :datalevin})))
        schema-eid (ds/entid db [:eacl/id "schema-string"])
        relation-eids
        (mapv :e
              (ddb/avet-datoms
               db :eacl.relation/resource-type+relation-name+subject-type))
        missing-schema?
        (or (nil? schema-eid)
            (empty? (ds/datoms db :eav schema-eid
                               :eacl.datalevin/schema-generation)))
        missing-schema-fence?
        (or (nil? schema-eid)
            (empty? (ds/datoms db :eav schema-eid
                               :eacl.datalevin/schema-write-fence)))
        missing-relations
        (filterv #(empty? (ds/datoms db :eav %
                                    :eacl.datalevin/relation-generation))
                 relation-eids)
        tx-data
        (into (if (nil? schema-eid)
                [(cond-> {:eacl/id "schema-string"}
                   missing-schema?
                   (assoc :eacl.datalevin/schema-generation :db/current-tx)

                   missing-schema-fence?
                   (assoc :eacl.datalevin/schema-write-fence :db/current-tx))]
                (cond-> []
                  missing-schema?
                  (conj [:db/add schema-eid
                         :eacl.datalevin/schema-generation :db/current-tx])

                  missing-schema-fence?
                  (conj [:db/add schema-eid
                         :eacl.datalevin/schema-write-fence :db/current-tx])))
              (map #(vector :db/add % :eacl.datalevin/relation-generation :db/current-tx))
              missing-relations)
        report
        (when (seq tx-data)
          (ds/transact!
           conn tx-data
           (when write-token {:datalevin/write-token write-token})))
        db-after (if report (:db-after report) db)
        schema-eid-after (ds/entid db-after [:eacl/id "schema-string"])
        schema-missing-after?
        (or (nil? schema-eid-after)
            (empty? (ds/datoms db-after :eav schema-eid-after
                               :eacl.datalevin/schema-generation)))
        schema-fence-missing-after?
        (or (nil? schema-eid-after)
            (empty? (ds/datoms db-after :eav schema-eid-after
                               :eacl.datalevin/schema-write-fence)))
        relation-missing-after
        (filterv #(empty? (ds/datoms db-after :eav %
                                    :eacl.datalevin/relation-generation))
                 relation-eids)]
    {:prepared? true
     :changed? (boolean report)
     :schema-generation-initialized? missing-schema?
     :schema-write-fence-initialized? missing-schema-fence?
     :relation-generations-initialized (count missing-relations)
     :missing-after
     (cond-> relation-missing-after
       schema-missing-after? (conj :eacl.datalevin/schema-generation)
       schema-fence-missing-after? (conj :eacl.datalevin/schema-write-fence))
     :db-after db-after})))

(def compare-schema model/compare-schema)

(defn count-relationships-using-relation
  "Counts relationships that reference the given relation.

  The maximum of the two endpoint-index cardinalities is exact for healthy
  pairs and remains positive for either one-sided ghost, so corruption cannot
  make a relation definition appear unused."
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  (let [relation-id  (str "eacl.relation:" resource-type ":" relation-name ":" subject-type)
        relation-eid (ds/entid db [:eacl/id relation-id])]
    (if-not relation-eid
      0
      (max
       (count
        (ddb/avet-endpoint-prefix
         db relationship-storage/forward-attribute
         [subject-type relation-eid resource-type]))
       (count
        (ddb/avet-endpoint-prefix
         db relationship-storage/reverse-attribute
         [resource-type relation-eid subject-type]))))))

(defn current-schema-generation
  [db]
  (when-let [schema-eid (ds/entid db [:eacl/id "schema-string"])]
    (some-> (ds/datoms db :eav schema-eid :eacl.datalevin/schema-generation)
            first
            :v)))

(defn- current-schema-write-fence
  [db]
  (when-let [schema-eid (ds/entid db [:eacl/id "schema-string"])]
    (some-> (ds/datoms db :eav schema-eid :eacl.datalevin/schema-write-fence)
            first
            :v)))

(defn- cas-failure-data
  [throwable]
  (loop [cause throwable]
    (when cause
      (let [data (ex-data cause)]
        (if (= :transact/cas (:error data))
          data
          (recur #?(:clj (.getCause ^Throwable cause)
                    :cljs (ex-cause cause))))))))

(defn- datalevin-failure-data
  [throwable failure-type]
  (loop [cause throwable]
    (when cause
      (let [data (ex-data cause)]
        (if (= failure-type (:type data))
          data
          (recur #?(:clj (.getCause ^Throwable cause)
                    :cljs (ex-cause cause))))))))

(defn- transact-schema!
  [conn tx-data expected-generation write-token]
  (try
    (ds/transact!
     conn tx-data
     (when write-token {:datalevin/write-token write-token}))
    (catch #?(:clj Throwable :cljs :default) throwable
      (cond
        (cas-failure-data throwable)
        (let [cause-data (cas-failure-data throwable)]
          (throw
           (ex-info
            "The EACL schema changed concurrently; retry from the new database value."
            {:type :eacl.schema/concurrent-write
             :eacl/error :eacl.schema/concurrent-write
             :expected-generation expected-generation
             :actual-generation (current-schema-generation (ds/db conn))
             :backend-error cause-data
             :datalevin-error cause-data}
            throwable)))

        (datalevin-failure-data throwable :datalevin/stale-generation)
        (let [cause-data
              (datalevin-failure-data throwable :datalevin/stale-generation)]
          (fork/refresh-connection! conn)
          (throw
           (ex-info
            "A shared Datalevin connection prepared the schema from a stale generation."
            {:type :eacl.datalevin/stale-connection-generation
             :eacl/error :eacl.datalevin/stale-connection-generation
             :backend :datalevin
             :expected-generation expected-generation
             :datalevin-error cause-data}
            throwable)))

        :else
        (throw throwable)))))

(defn write-schema!
  "Parses, validates, diffs and transacts a SpiceDB schema string.
  Throws :eacl.schema/parse-error on unparseable input (a failed parse must
  never retract the stored schema) and :eacl.schema/empty-schema-guard when the
  new schema has zero definitions while a non-empty schema is stored; pass
  {:allow-empty-schema? true} to wipe intentionally."
  ([conn schema-string]
   (write-schema! conn schema-string {}))
  ([conn schema-string options]
   (write-schema! conn schema-string options ::read-current-generation))
  ([conn schema-string
    {:keys [allow-empty-schema? expression-limits]}
    known-schema-generation]
   (write-schema! conn schema-string
                  {:allow-empty-schema? allow-empty-schema?
                   :expression-limits expression-limits}
                  known-schema-generation nil))
  ([conn schema-string
    {:keys [allow-empty-schema? expression-limits]}
    known-schema-generation
    write-token]
   (let [expression-limits
         (expression-policy/normalize-client-limits expression-limits)
         new-schema-map  (expression-persistence/candidate-schema
                           (expression-resolver/validate-schema
                            schema-string expression-limits))
         db              (ds/db conn)
         current-generation (current-schema-generation db)
         schema-write-fence (current-schema-write-fence db)
         _               (when-not (and current-generation
                                        schema-write-fence)
                           (throw
                            (ex-info
                             "Datalevin schema writes require prepared generation evidence."
                             {:type :eacl.cache/generation-unprepared
                              :eacl/error :eacl.cache/generation-unprepared
                              :backend :datalevin
                              :policy-installed? (boolean (fork/write-policy conn))
                              :missing
                              (cond-> []
                                (nil? current-generation)
                                (conj :eacl.datalevin/schema-generation)

                                (nil? schema-write-fence)
                                (conj :eacl.datalevin/schema-write-fence))})))
         existing-schema (binding [expression-persistence/*expression-limits*
                                   expression-limits]
                           (read-schema db))
         _               (when (and (empty? (:definitions new-schema-map))
                                    (not allow-empty-schema?)
                                    (or (seq (:relations existing-schema))
                                        (seq (:permissions existing-schema))))
                           (throw (ex-info (str "Refusing to replace a non-empty schema with zero definitions."
                                                " Pass {:allow-empty-schema? true} to write-schema! if this is intentional.")
                                           {:type :eacl.schema/empty-schema-guard :eacl/error :eacl.schema/empty-schema-guard
                                            :existing {:relations (count (:relations existing-schema))
                                                       :permissions (count (:permissions existing-schema))}})))
         deltas          (compare-schema existing-schema new-schema-map)
         {:keys [relations permissions]} deltas
         relation-retractions   (:retractions relations)
         permission-retractions
         (expression-persistence/entity-deletions permissions)]
     (doseq [rel relation-retractions]
       (let [cnt (count-relationships-using-relation db rel)]
         (when (pos? cnt)
           (throw (ex-info (str "Cannot delete relation " (:eacl.relation/relation-name rel)
                                " because it is used by " cnt " relationships.")
                           {:type :eacl.schema/relation-in-use
                            :eacl/error :eacl.schema/relation-in-use
                            :relation rel
                            :count cnt})))))
     (let [relation-additions
           (mapv #(assoc % :eacl.datalevin/relation-generation :db/current-tx)
                 (:additions relations))
           schema-eid (ds/entid db [:eacl/id "schema-string"])
           schema-generation
           (if (= ::read-current-generation known-schema-generation)
             current-generation
             known-schema-generation)
           relation-commit-guards
           (mapv
            (fn [relation]
              (let [relation-eid
                    (ds/entid db [:eacl/id (:eacl/id relation)])
                    relation-generation
                    (some-> (ds/datoms db :eav relation-eid
                                       :eacl.datalevin/relation-generation)
                            first
                            :v)]
                (when-not relation-generation
                  (throw
                   (ex-info
                    "Relation removal requires prepared native generations."
                    {:type :eacl.cache/generation-unprepared :eacl/error :eacl.cache/generation-unprepared
                     :backend :datalevin
                     :relation-id (:eacl/id relation)})))
                [:db.fn/cas relation-eid :eacl.datalevin/relation-generation
                 relation-generation relation-generation]))
            relation-retractions)
           tx-data
           (vec
            (concat
             [[:db.fn/cas schema-eid :eacl.datalevin/schema-write-fence
               schema-write-fence schema-write-fence]]
             relation-commit-guards
             relation-additions
             (:additions permissions)
             (for [rel relation-retractions
                   :let [eid (ds/entid db [:eacl/id (:eacl/id rel)])]
                   :when eid]
               [:db/retractEntity eid])
             (for [perm permission-retractions
                   :let [eid (ds/entid db [:eacl/id (:eacl/id perm)])]
                   :when eid]
               [:db/retractEntity eid])
             [{:db/id schema-eid
               :eacl/id "schema-string"
               :eacl/schema-string schema-string}
              [:db/add schema-eid :eacl.datalevin/schema-generation
               :db/current-tx]
              [:db/add schema-eid :eacl.datalevin/schema-write-fence
               :db/current-tx]]))
           stored-string
           (some-> (ds/entity db [:eacl/id "schema-string"])
                   :eacl/schema-string)
           changed?
           (or (not= stored-string schema-string)
               (some seq
                     [(:additions relations)
                      (:retractions relations)
                      (:additions permissions)
                      (:retractions permissions)]))
           report
           (if changed?
             (transact-schema! conn tx-data schema-generation write-token)
             {:db-before db
              :db-after db
              :tx-data []
              :no-op? true})]
       (assoc deltas
              :eacl.schema/db-after (:db-after report)
              :eacl.schema/no-op? (boolean (:no-op? report)))))))
