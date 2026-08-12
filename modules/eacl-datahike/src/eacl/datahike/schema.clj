(ns eacl.datahike.schema
  (:require [datahike.api :as d]
            [eacl.datahike.db :as ddb]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

(def relation-key-attr
  :eacl.relation/resource-type+relation-name+subject-type)

(def permission-key-attr
  :eacl.permission/resource-type+permission-name)

(def max-entid
  "Compatibility sentinel retained for the public Datahike impl surface."
  Long/MAX_VALUE)

(def ^:private component-schema
  "The attributes schema-definition composite tuples are derived FROM.
   Datahike's `:write` flexibility needs a declared `:db/valueType` and
   `:db/cardinality` on every attribute, which DataScript's schema map leaves
   implicit."
  [{:db/ident       :eacl/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :eacl/schema-string
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :eacl/schema-generation
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :eacl/schema-write-fence
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :eacl/relation-version
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :eacl.relation/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relation/relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relation/subject-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/permission-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/source-relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/target-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/target-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ])

(def ^:private tuple-schema
  "Schema-definition composite tuples plus Datomic-compatible heterogeneous
   relationship tuples. A relationship is stored as exactly two datoms: its
   forward tuple on the subject entity and reverse tuple on the resource
   entity."
  [{:db/ident       :eacl.relation/resource-type+relation-name+subject-type
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relation/resource-type
                     :eacl.relation/relation-name
                     :eacl.relation/subject-type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :eacl.permission/resource-type+permission-name
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/full-key
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/source-relation-name
                     :eacl.permission/target-type
                     :eacl.permission/target-name
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       relationship-storage/forward-attribute
    :db/valueType   :db.type/tuple
    :db/tupleTypes  [:db.type/keyword
                     :db.type/ref
                     :db.type/keyword
                     :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       relationship-storage/reverse-attribute
    :db/valueType   :db.type/tuple
    :db/tupleTypes  [:db.type/keyword
                     :db.type/ref
                     :db.type/keyword
                     :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index       true}])

(def datahike-schema
  "EACL's own attributes, as datahike transaction data."
  (into component-schema tuple-schema))

(defn merge-schema
  "EACL's attributes plus the caller's. `extra-schema` is datahike-native
   transaction data (a sequence of attribute maps), NOT DataScript's map of
   attribute to options: datahike's `:write` flexibility needs value types that
   a DataScript schema map does not carry, so the two are not interconvertible."
  ([] datahike-schema)
  ([extra-schema]
   (if (seq extra-schema)
     (into datahike-schema extra-schema)
     datahike-schema)))

(def default-config
  {:store              {:backend :memory}
   :schema-flexibility :write
   :keep-history?      false
   ;; EACL object ids come from the caller, so no cap is imposed here; stating 0
   ;; declares that as intentional rather than leaving datahike to warn about it.
   :max-string-length  0})

(defn create-conn
  "A datahike connection carrying EACL's schema.

  `config` is merged over `default-config`; pass `{:attribute-refs? true}` to get
  Datomic's numeric attribute representation. A memory store gets a fresh id per
  connection unless one is supplied, so two calls do not collide."
  ([] (create-conn nil nil))
  ([extra-schema] (create-conn extra-schema nil))
  ([extra-schema config]
   (let [cfg (-> (merge default-config config)
                 (update :store #(merge {:id (random-uuid)} %)))]
     (d/create-database cfg)
     (let [conn (d/connect cfg)]
       (d/transact conn (merge-schema extra-schema))
       conn))))

(def relation-pull
  '[:eacl/id
    :eacl.relation/subject-type
    :eacl.relation/resource-type
    :eacl.relation/relation-name])

(def permission-pull
  '[:eacl/id
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name])

(defn read-relations
  "Every relation definition. Enumerated from the relation key index rather than
   by query: the index is the engine's own view of the schema, so a relation
   that is invisible here is invisible to permission evaluation too."
  [db]
  (mapv #(d/pull db relation-pull (:e %))
        (ddb/avet-datoms db relation-key-attr)))

(defn read-permissions
  [db]
  (mapv #(d/pull db permission-pull (:e %))
        (ddb/avet-datoms db permission-key-attr)))

(defn read-schema
  [db & [_format]]
  {:relations   (read-relations db)
   :permissions (read-permissions db)})

(defn prepare-cache-coherence!
  "Initializes missing native schema/relation generations and the schema
  write fence additively."
  [conn]
  (let [db (d/db conn)
        _ (when-not (and (ddb/entid db :eacl/schema-generation)
                         (ddb/entid db :eacl/schema-write-fence)
                         (ddb/entid db :eacl/relation-version))
            (throw
             (ex-info
              "Datahike database lacks native EACL generation attributes."
              {:type :eacl.cache/generation-schema-missing
               :eacl/error :eacl.cache/generation-schema-missing
               :backend :datahike})))
        schema-eid (ddb/entid db [:eacl/id "schema-string"])
        relation-eids
        (into [] (map :e)
              (ddb/avet-datoms
               db :eacl.relation/resource-type+relation-name+subject-type))
        missing-schema?
        (and schema-eid
             (empty? (ddb/eavt-datoms db schema-eid
                                      :eacl/schema-generation)))
        missing-schema-fence?
        (and schema-eid
             (empty? (ddb/eavt-datoms db schema-eid
                                      :eacl/schema-write-fence)))
        missing-relations
        (filterv #(empty? (ddb/eavt-datoms db % :eacl/relation-version))
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
        report (when (seq tx-data) (d/transact conn tx-data))
        db-after (if report (:db-after report) db)
        schema-missing-after?
        (and schema-eid
             (empty? (ddb/eavt-datoms
                      db-after schema-eid :eacl/schema-generation)))
        schema-fence-missing-after?
        (and schema-eid
             (empty? (ddb/eavt-datoms
                      db-after schema-eid :eacl/schema-write-fence)))
        relation-missing-after
        (filterv #(empty? (ddb/eavt-datoms
                           db-after % :eacl/relation-version))
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
(def calc-set-deltas model/calc-set-deltas)
(def compare-schema model/compare-schema)

(defn count-relationships-using-relation
  "Counts relationships that reference the given relation.

  The maximum of the two endpoint-index cardinalities is exact for healthy
  pairs and remains positive for either one-sided ghost, so corruption cannot
  make a relation definition appear unused."
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  (let [relation-id  (model/->relation-id resource-type relation-name subject-type)
        relation-eid (ddb/entid db [:eacl/id relation-id])]
    (if-not relation-eid
      0
      (max
       (count
        (ddb/avet-tuple-prefix
         db relationship-storage/forward-attribute 4
         [subject-type relation-eid resource-type]))
       (count
        (ddb/avet-tuple-prefix
         db relationship-storage/reverse-attribute 4
         [resource-type relation-eid subject-type]))))))

(defn- current-schema-generation
  [db]
  (when-let [schema-eid (ddb/entid db [:eacl/id "schema-string"])]
    (some-> (ddb/eavt-datoms db schema-eid :eacl/schema-generation)
            first
            :v)))

(defn- current-schema-write-fence
  [db]
  (when-let [schema-eid (ddb/entid db [:eacl/id "schema-string"])]
    (some-> (ddb/eavt-datoms db schema-eid :eacl/schema-write-fence)
            first
            :v)))

(defn- ensure-schema-coherence!
  "Bootstraps the schema singleton before its first guarded replacement."
  [conn]
  (loop []
    (let [db (d/db conn)]
      (if (and (current-schema-generation db)
               (current-schema-write-fence db))
        db
        (do
          (d/transact
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
          (recur (.getCause ^Throwable cause)))))))

(defn- transact-schema!
  [conn tx-data expected-generation]
  (try
    (d/transact conn tx-data)
    (catch Throwable throwable
      (if-let [cause-data (cas-failure-data throwable)]
        (throw
         (ex-info
          "The EACL schema changed concurrently; retry from the new database value."
          {:type :eacl.schema/concurrent-write
           :expected-generation expected-generation
           :actual-generation (current-schema-generation (d/db conn))
           :datahike-error cause-data}
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
  ([conn schema-string
    {:keys [allow-empty-schema?]}]
   (let [new-schema-map  (parser/->eacl-schema (parser/parse-schema schema-string))
         _               (validate-schema-references new-schema-map)
         initial-db      (d/db conn)
         initial-schema  (read-schema initial-db)
         _               (when (and (empty? (:definitions new-schema-map))
                                    (not allow-empty-schema?)
                                    (or (seq (:relations initial-schema))
                                        (seq (:permissions initial-schema))))
                           (throw (ex-info (str "Refusing to replace a non-empty schema with zero definitions."
                                                " Pass {:allow-empty-schema? true} to write-schema! if this is intentional.")
                                           {:type :eacl.schema/empty-schema-guard
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
                                           {:type :eacl.schema/empty-schema-guard
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
                            :relation rel
                            :count cnt})))))
     (let [relation-additions
           (mapv #(assoc % :eacl/relation-version :db/current-tx)
                 (:additions relations))
           schema-eid (ddb/entid db [:eacl/id "schema-string"])
           schema-generation (current-schema-generation db)
           schema-write-fence (current-schema-write-fence db)
           relation-commit-guards
           (mapv
            (fn [relation]
              (let [relation-eid
                    (ddb/entid db [:eacl/id (:eacl/id relation)])
                    relation-generation
                    (some-> (ddb/eavt-datoms
                             db relation-eid :eacl/relation-version)
                            first
                            :v)]
                (when-not relation-generation
                  (throw
                   (ex-info
                    "Relation removal requires prepared native generations."
                    {:type :eacl.cache/generation-unprepared
                     :backend :datahike
                     :relation-id (:eacl/id relation)})))
                [:db.fn/cas relation-eid
                 (ddb/attr-repr db :eacl/relation-version)
                 relation-generation relation-generation]))
            relation-retractions)
           tx-data
           (vec
            (concat
             [[:db.fn/cas schema-eid
               (ddb/attr-repr db :eacl/schema-write-fence)
               schema-write-fence schema-write-fence]]
             relation-commit-guards
             relation-additions
             (:additions permissions)
             (for [rel relation-retractions
                   :let [eid (ddb/entid db [:eacl/id (:eacl/id rel)])]
                   :when eid]
               [:db/retractEntity eid])
             (for [perm permission-retractions
                   :let [eid (ddb/entid db [:eacl/id (:eacl/id perm)])]
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
           (some-> (d/entity db [:eacl/id "schema-string"])
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
