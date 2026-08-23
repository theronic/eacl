(ns eacl.datalevin.schema
  (:require [datalevin.core :as ds]
            [eacl.datalevin.db :as ddb]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

(def datalevin-schema
  {:eacl/id {:db/valueType :db.type/string
             :db/unique :db.unique/identity}
   :eacl/schema-string {:db/valueType :db.type/string}
   :eacl/schema-generation {:db/valueType :db.type/ref}
   :eacl/schema-write-fence {:db/valueType :db.type/ref}
   :eacl/relation-version {:db/valueType :db.type/ref}
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
   :eacl.permission/resource-type+permission-name
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/permission-name]
    :db/index true}
   :eacl.permission/full-key
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name
                    :eacl.permission/permission-name]
    :db/unique :db.unique/identity}

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

(defn create-conn
  ([] (create-conn nil nil nil))
  ([dir] (create-conn dir nil nil))
  ([dir extra-schema] (create-conn dir extra-schema nil))
  ([dir extra-schema store-options]
   (let [conn (ds/get-conn dir (merge-schema extra-schema) store-options)]
     (when-not
      (ds/entid (ds/db conn) [:eacl/id "datalevin-metadata"])
       (ds/transact!
        conn
        [{:eacl/id "datalevin-metadata"
          :eacl.datalevin/source-id (random-uuid)}]))
     conn)))

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
  rejects any incompatible definition. Returns the persisted source UUID."
  [conn]
  (let [db (ds/db conn)
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
          source-id)))))

(def relation-pull
  [:eacl/id :eacl.relation/subject-type
   :eacl.relation/resource-type :eacl.relation/relation-name])

(def permission-pull
  [:eacl/id :eacl.permission/resource-type
   :eacl.permission/permission-name
   :eacl.permission/source-relation-name
   :eacl.permission/target-type :eacl.permission/target-name])

(defn- eager-entity
  [db eid attributes]
  (let [entity (ds/entity db eid)]
    (into (if (some #{:db/id} attributes) {:db/id eid} {})
          (keep (fn [attribute]
                  (when-some [value (get entity attribute)]
                    [attribute value])))
          (remove #{:db/id} attributes))))

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
     {:relations (read-relations db)
      :permissions (read-permissions db)})))

(defn prepare-cache-coherence!
  "Initializes missing physical schema/relation generations and the schema
  write fence additively."
  [conn]
  (let [db (ds/db conn)
        physical-schema (ds/schema conn)
        _ (when-not (and (contains? physical-schema :eacl/schema-generation)
                         (contains? physical-schema :eacl/schema-write-fence)
                         (contains? physical-schema :eacl/relation-version))
            (throw
             (ex-info
              "Datalevin connection schema lacks native EACL generation attributes."
              {:type :eacl.cache/generation-schema-missing
               :eacl/error :eacl.cache/generation-schema-missing
               :backend :datalevin})))
        schema-eid (ds/entid db [:eacl/id "schema-string"])
        relation-eids
        (into [] (map :e)
              (ddb/avet-datoms
               db :eacl.relation/resource-type+relation-name+subject-type))
        missing-schema?
        (and schema-eid
             (empty? (ds/datoms db :eav schema-eid
                                :eacl/schema-generation)))
        missing-schema-fence?
        (and schema-eid
             (empty? (ds/datoms db :eav schema-eid
                                :eacl/schema-write-fence)))
        missing-relations
        (filterv #(empty? (ds/datoms db :eav %
                                    :eacl/relation-version))
                 relation-eids)
        tx-data
        (into (cond-> []
                missing-schema?
                (conj [:db/add schema-eid
                       :eacl/schema-generation :db/current-tx])

                missing-schema-fence?
                (conj [:db/add schema-eid
                       :eacl/schema-write-fence :db/current-tx]))
              (map #(vector :db/add % :eacl/relation-version :db/current-tx))
              missing-relations)
        report (when (seq tx-data) (ds/transact! conn tx-data))
        db-after (if report (:db-after report) db)
        schema-missing-after?
        (and schema-eid
             (empty? (ds/datoms db-after :eav schema-eid
                                :eacl/schema-generation)))
        schema-fence-missing-after?
        (and schema-eid
             (empty? (ds/datoms db-after :eav schema-eid
                                :eacl/schema-write-fence)))
        relation-missing-after
        (filterv #(empty? (ds/datoms db-after :eav %
                                    :eacl/relation-version))
                 relation-eids)]
    {:prepared? true
     :changed? (boolean report)
     :schema-generation-initialized? missing-schema?
     :schema-write-fence-initialized? missing-schema-fence?
     :relation-generations-initialized (count missing-relations)
     :missing-after
     (cond-> relation-missing-after
       schema-missing-after? (conj :eacl/schema-generation)
       schema-fence-missing-after? (conj :eacl/schema-write-fence))
     :db-after db-after}))

(def validate-schema-references model/validate-schema-references)
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
    (some-> (ds/datoms db :eav schema-eid :eacl/schema-generation)
            first
            :v)))

(defn- current-schema-write-fence
  [db]
  (when-let [schema-eid (ds/entid db [:eacl/id "schema-string"])]
    (some-> (ds/datoms db :eav schema-eid :eacl/schema-write-fence)
            first
            :v)))

(defn- ensure-schema-coherence!
  "Bootstraps the schema singleton before its first guarded replacement.

  Datalevin does not allow a tempid or :db/current-tx as a :db.fn/cas value,
  so the first physical generation and fence must exist before the replacement
  CAS. The parser, reference checks, and empty-schema guard run first."
  [conn]
  (loop []
    (let [db (ds/db conn)]
      (if (and (current-schema-generation db)
               (current-schema-write-fence db))
        db
        (do
          (ds/transact!
           conn
           [(cond-> {:eacl/id "schema-string"}
              (nil? (current-schema-generation db))
              (assoc :eacl/schema-generation :db/current-tx)

              (nil? (current-schema-write-fence db))
              (assoc :eacl/schema-write-fence :db/current-tx))])
          (recur))))))

(defn- cas-failure-data
  [throwable]
  (loop [cause throwable]
    (when cause
      (let [data (ex-data cause)]
        (if (= :transact/cas (:error data))
          data
          (recur #?(:clj (.getCause ^Throwable cause)
                    :cljs (ex-cause cause))))))))

(defn- transact-schema!
  [conn tx-data expected-generation]
  (try
    (ds/transact! conn tx-data)
    (catch #?(:clj Throwable :cljs :default) throwable
      (if-let [cause-data (cas-failure-data throwable)]
        (throw
         (ex-info
          "The EACL schema changed concurrently; retry from the new database value."
          {:type :eacl.schema/concurrent-write
           :eacl/error :eacl.schema/concurrent-write
           :expected-generation expected-generation
           :actual-generation (current-schema-generation (ds/db conn))
           :backend-error cause-data
           :datalevin-error cause-data}
          throwable))
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
    {:keys [allow-empty-schema?]}
    known-schema-generation]
   (let [new-schema-map  (parser/->eacl-schema (parser/parse-schema schema-string))
         _               (validate-schema-references new-schema-map)
         initial-db      (ds/db conn)
         initial-schema  (read-schema initial-db)
         _               (when (and (empty? (:definitions new-schema-map))
                                    (not allow-empty-schema?)
                                    (or (seq (:relations initial-schema))
                                        (seq (:permissions initial-schema))))
                           (throw (ex-info (str "Refusing to replace a non-empty schema with zero definitions."
                                                " Pass {:allow-empty-schema? true} to write-schema! if this is intentional.")
                                           {:type :eacl.schema/empty-schema-guard :eacl/error :eacl.schema/empty-schema-guard
                                            :existing {:relations (count (:relations initial-schema))
                                                       :permissions (count (:permissions initial-schema))}})))
         db              (ensure-schema-coherence! conn)
         existing-schema (read-schema db)
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
         permission-retractions (:retractions permissions)]
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
           (mapv #(assoc % :eacl/relation-version :db/current-tx)
                 (:additions relations))
           schema-eid (ds/entid db [:eacl/id "schema-string"])
           schema-generation
           (if (= ::read-current-generation known-schema-generation)
             (current-schema-generation db)
             known-schema-generation)
           schema-write-fence (current-schema-write-fence db)
           relation-commit-guards
           (mapv
            (fn [relation]
              (let [relation-eid
                    (ds/entid db [:eacl/id (:eacl/id relation)])
                    relation-generation
                    (some-> (ds/datoms db :eav relation-eid
                                       :eacl/relation-version)
                            first
                            :v)]
                (when-not relation-generation
                  (throw
                   (ex-info
                    "Relation removal requires prepared native generations."
                    {:type :eacl.cache/generation-unprepared :eacl/error :eacl.cache/generation-unprepared
                     :backend :datalevin
                     :relation-id (:eacl/id relation)})))
                [:db.fn/cas relation-eid :eacl/relation-version
                 relation-generation relation-generation]))
            relation-retractions)
           tx-data
           (vec
            (concat
             [[:db.fn/cas schema-eid :eacl/schema-write-fence
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
              [:db/add schema-eid :eacl/schema-generation
               :db/current-tx]
              [:db/add schema-eid :eacl/schema-write-fence
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
             (transact-schema! conn tx-data schema-generation)
             {:db-before db
              :db-after db
              :tx-data []
              :no-op? true})]
       (assoc deltas
              :eacl.schema/db-after (:db-after report)
              :eacl.schema/no-op? (boolean (:no-op? report)))))))
