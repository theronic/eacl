(ns eacl.datahike.schema
  (:require [datahike.api :as d]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.mutation :as journal]
            [eacl.mutation :as mutation]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

(def relation-key-attr
  :eacl.relation/resource-type+relation-name+subject-type)

(def permission-key-attr
  :eacl.permission/resource-type+permission-name)

(def max-entid Long/MAX_VALUE)

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

   {:db/ident       :eacl.mutation/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/index       true}
   {:db/ident       :eacl.mutation/fingerprint
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.mutation/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.mutation/issued-at
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.mutation/expires-at
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.graph/family-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.graph/head-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.graph/head-order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :eacl.schema/mutation-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relation/mutation-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.dependency/mutation-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}])

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

(def validate-schema-references model/validate-schema-references)
(def calc-set-deltas model/calc-set-deltas)
(def compare-schema model/compare-schema)

(defn count-relationships-using-relation
  "Counts forward relationship tuples that reference the given relation."
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  (let [relation-id  (model/->relation-id resource-type relation-name subject-type)
        relation-eid (ddb/entid db [:eacl/id relation-id])]
    (if-not relation-eid
      0
      (count
       (ddb/avet-range
        db
        relationship-storage/forward-attribute
        [subject-type relation-eid resource-type 0]
        [subject-type relation-eid resource-type max-entid])))))

(defn write-schema!
  "Parses, validates, diffs and transacts a SpiceDB schema string.
  Throws :eacl.schema/parse-error on unparseable input (a failed parse must
  never retract the stored schema) and :eacl.schema/empty-schema-guard when the
  new schema has zero definitions while a non-empty schema is stored; pass
  {:allow-empty-schema? true} to wipe intentionally."
  ([conn schema-string]
   (write-schema! conn schema-string {}))
  ([conn schema-string
    {:keys [allow-empty-schema? token-ttl-seconds retention-grace-seconds]}]
   (let [new-schema-map  (parser/->eacl-schema (parser/parse-schema schema-string))
         _               (validate-schema-references new-schema-map)
         db              (d/db conn)
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
                           {:relation rel :count cnt})))))
     (let [tx-data
           (vec
            (concat
             (:additions relations)
             (:additions permissions)
             (for [rel relation-retractions
                   :let [eid (ddb/entid db [:eacl/id (:eacl/id rel)])]
                   :when eid]
               [:db/retractEntity eid])
             (for [perm permission-retractions
                   :let [eid (ddb/entid db [:eacl/id (:eacl/id perm)])]
                   :when eid]
               [:db/retractEntity eid])
             [{:eacl/id "schema-string"
               :eacl/schema-string schema-string}]))
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
           mutation-id (mutation/new-id)
           report
           (if changed?
             (journal/transact!
              conn
              {:mutation-id mutation-id
               :calculation-db db
               :kind :schema
               :canonical-data
               {:operation :write-schema
                :schema-string schema-string
                :deltas deltas}
               :schema-change? true
               :token-ttl-seconds token-ttl-seconds
               :retention-grace-seconds retention-grace-seconds
               :relation-ids
               (mapv (fn [relation]
                       [:eacl/id (:eacl/id relation)])
                     (:additions relations))
               :tx-data tx-data})
             {:db-before db
              :db-after db
              :tx-data []
              :mutation-id (:head-id (journal/ensure-migrated! conn))
              :no-op? true})]
       (assoc deltas
              :eacl.mutation/id (:mutation-id report)
              :eacl.mutation/db-after (:db-after report)
              :eacl.mutation/no-op? (boolean (:no-op? report)))))))
