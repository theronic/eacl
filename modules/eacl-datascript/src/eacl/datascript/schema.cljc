(ns eacl.datascript.schema
  (:require [datascript.core :as ds]
            [eacl.datascript.db :as ddb]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.expression-policy :as expression-policy]
            [eacl.schema.expression-resolver :as expression-resolver]
            [eacl.schema.model :as model]
            [eacl.schema.replacement-plan :as replacement-plan]))

(def datascript-schema
  {:eacl/id {:db/unique :db.unique/identity}
   :eacl.datascript/source-id {:db/unique :db.unique/identity}
   :eacl/schema-generation {:db/valueType :db.type/ref}
   :eacl/schema-write-fence {:db/valueType :db.type/ref}
   :eacl/relation-version {:db/valueType :db.type/ref}
   :eacl.relation/resource-type {:db/index true}
   :eacl.relation/relation-name {:db/index true}
   :eacl.relation/subject-type {:db/index true}
   :eacl.relation/resource-type+relation-name+subject-type
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relation/resource-type
                    :eacl.relation/relation-name
                    :eacl.relation/subject-type]
    :db/unique :db.unique/identity}

   :eacl.permission/resource-type {:db/index true}
   :eacl.permission/permission-name {:db/index true}
   :eacl.permission/source-relation-name {:db/index true}
   :eacl.permission/target-type {:db/index true}
   :eacl.permission/target-name {:db/index true}
   :eacl.permission/expression-payload {}
   :eacl.permission/resource-type+permission-name
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/permission-name]
    :db/index true}

   relationship-storage/forward-attribute
   {:db/cardinality :db.cardinality/many
    :db/index true}
   relationship-storage/reverse-attribute
   {:db/cardinality :db.cardinality/many
    :db/index true}})

(defn merge-schema
  ([] datascript-schema)
  ([extra-schema]
   (merge datascript-schema extra-schema)))

(defn create-conn
  ([] (create-conn nil))
  ([extra-schema]
   (let [source-id (str (random-uuid))
         conn (ds/create-conn (merge-schema extra-schema))]
     (alter-meta! conn assoc :eacl.datascript/source-id source-id)
     (ds/transact!
      conn
      [{:eacl/id "datascript-metadata"
        :eacl.datascript/source-id source-id}])
     conn)))

(defn read-relations
  [db]
  (ds/q '[:find [(pull ?relation [:eacl/id
                                  :eacl.relation/subject-type
                                  :eacl.relation/resource-type
                                  :eacl.relation/relation-name]) ...]
          :where
          [?relation :eacl.relation/relation-name]]
        db))

(defn read-permissions
  [db]
  (ds/q '[:find [(pull ?perm [:eacl/id
                              :eacl.permission/resource-type
                              :eacl.permission/permission-name
                              :eacl.permission/source-relation-name
                              :eacl.permission/target-type
                              :eacl.permission/target-name
                              :eacl.permission/expression-payload]) ...]
          :where
          [?perm :eacl.permission/permission-name]]
        db))

(defn read-schema
  [db & [_format]]
  (let [permissions (read-permissions db)]
    (expression-persistence/validate-entities permissions)
    {:relations   (read-relations db)
     :permissions permissions}))

(defn prepare-cache-coherence!
  "Initializes missing physical schema/relation generations and the schema
  write fence additively."
  [conn]
  (let [db (ds/db conn)
        _ (when-not (and (contains? (:schema db) :eacl/schema-generation)
                         (contains? (:schema db) :eacl/schema-write-fence)
                         (contains? (:schema db) :eacl/relation-version))
            (throw
             (ex-info
              "DataScript connection schema lacks native EACL generation attributes."
              {:type :eacl.cache/generation-schema-missing
               :eacl/error :eacl.cache/generation-schema-missing
               :backend :datascript})))
        schema-eid (ds/entid db [:eacl/id "schema-string"])
        relation-eids
        (mapv :e
              (ddb/avet-datoms
               db :eacl.relation/resource-type+relation-name+subject-type))
        missing-schema?
        (and schema-eid
             (empty? (ds/datoms db :eavt schema-eid
                                :eacl/schema-generation)))
        missing-schema-fence?
        (and schema-eid
             (empty? (ds/datoms db :eavt schema-eid
                                :eacl/schema-write-fence)))
        missing-relations
        (filterv #(empty? (ds/datoms db :eavt %
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
             (empty? (ds/datoms db-after :eavt schema-eid
                                :eacl/schema-generation)))
        schema-fence-missing-after?
        (and schema-eid
             (empty? (ds/datoms db-after :eavt schema-eid
                                :eacl/schema-write-fence)))
        relation-missing-after
        (filterv #(empty? (ds/datoms db-after :eavt %
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

(defn relationship-present-for-relation?
  "At most one bounded AVET probe per physical direction for speculative
  retain-inert diagnostics. Either half witnesses retained relationship data,
  including a one-sided tuple left by earlier corruption."
  [db {:eacl.relation/keys [resource-type subject-type] :as relation}]
  (when-let [relation-eid (ds/entid db [:eacl/id (:eacl/id relation)])]
    (boolean
     (or
      (first
       (ddb/avet-endpoint-prefix
        db relationship-storage/forward-attribute
        [subject-type relation-eid resource-type]))
      (first
       (ddb/avet-endpoint-prefix
        db relationship-storage/reverse-attribute
        [resource-type relation-eid subject-type]))))))

(defn current-schema-generation
  [db]
  (when-let [schema-eid (ds/entid db [:eacl/id "schema-string"])]
    (some-> (ds/datoms db :eavt schema-eid :eacl/schema-generation)
            first
            :v)))

(defn- current-schema-write-fence
  [db]
  (when-let [schema-eid (ds/entid db [:eacl/id "schema-string"])]
    (some-> (ds/datoms db :eavt schema-eid :eacl/schema-write-fence)
            first
            :v)))

(defn- ensure-schema-coherence!
  "Bootstraps the schema singleton before its first guarded replacement.

  DataScript does not allow a tempid or :db/current-tx as a :db.fn/cas value,
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
           :datascript-error cause-data}
          throwable))
        (throw throwable)))))

(defn plan-schema-replacement
  "Pure schema replacement planner shared by committed and speculative paths."
  [db schema-string
   {:keys [allow-empty-schema? expression-limits orphan-policy]
    :or {orphan-policy :error}}]
  (let [expression-limits
        (expression-policy/normalize-client-limits expression-limits)
        new-schema-map
        (expression-persistence/candidate-schema
         (expression-resolver/validate-schema schema-string expression-limits))
        existing-schema
        (binding [expression-persistence/*expression-limits* expression-limits]
          (read-schema db))
        _
        (when (and (empty? (:definitions new-schema-map))
                   (not allow-empty-schema?)
                   (or (seq (:relations existing-schema))
                       (seq (:permissions existing-schema))))
          (throw
           (ex-info
            (str "Refusing to replace a non-empty schema with zero definitions."
                 " Pass {:allow-empty-schema? true} to write-schema! if this is intentional.")
            {:type :eacl.schema/empty-schema-guard
             :eacl/error :eacl.schema/empty-schema-guard
             :existing {:relations (count (:relations existing-schema))
                        :permissions (count (:permissions existing-schema))}})))
        deltas (compare-schema existing-schema new-schema-map)
        semantic
        (replacement-plan/plan
         {:deltas deltas
          :orphan-policy orphan-policy
          :relationship-count #(count-relationships-using-relation db %)
          :relationship-present?
          #(relationship-present-for-relation? db %)})
        {:keys [relations permissions]} deltas
        relation-retractions (:retractions relations)
        permission-retractions
        (expression-persistence/entity-deletions permissions)
        relation-additions
        (mapv #(assoc % :eacl/relation-version :db/current-tx)
              (:additions relations))
        schema-eid (or (ds/entid db [:eacl/id "schema-string"]) -1)
        relation-commit-guards
        (mapv
         (fn [relation]
           (let [relation-eid
                 (ds/entid db [:eacl/id (:eacl/id relation)])
                 relation-generation
                 (some-> (ds/datoms db :eavt relation-eid
                                    :eacl/relation-version)
                         first :v)]
             (when-not relation-generation
               (throw
                (ex-info
                 "Relation removal requires prepared native generations."
                 {:type :eacl.cache/generation-unprepared
                  :eacl/error :eacl.cache/generation-unprepared
                  :backend :datascript
                  :relation-id (:eacl/id relation)})))
             [:db.fn/cas relation-eid :eacl/relation-version
              relation-generation relation-generation]))
         relation-retractions)
        tx-data
        (vec
         (concat
          relation-additions
          (:additions permissions)
          (for [relation relation-retractions
                :let [eid (ds/entid db [:eacl/id (:eacl/id relation)])]
                :when eid]
            [:db/retractEntity eid])
          (for [permission permission-retractions
                :let [eid (ds/entid db [:eacl/id (:eacl/id permission)])]
                :when eid]
            [:db/retractEntity eid])
          [{:db/id schema-eid
            :eacl/id "schema-string"
            :eacl/schema-string schema-string}
           [:db/add schema-eid :eacl/schema-generation :db/current-tx]
           [:db/add schema-eid :eacl/schema-write-fence :db/current-tx]]))
        stored-string
        (some-> (ds/entity db [:eacl/id "schema-string"])
                :eacl/schema-string)
        no-op?
        (not (or (not= stored-string schema-string)
                 (some seq
                       [(:additions relations)
                        (:retractions relations)
                        (:additions permissions)
                        (:retractions permissions)])))
        tx-data (if no-op? [] tx-data)]
    (assoc semantic
           :tx-data tx-data
           :speculative-tx-data tx-data
           :relation-commit-guards relation-commit-guards
           :schema-entity schema-eid
           :no-op? no-op?
           :schema-string schema-string)))

(defn- reject-committed-retain-inert!
  [options]
  (when (= :retain-inert (:orphan-policy options))
    (throw
     (ex-info
      ":orphan-policy :retain-inert is available only through eacl/with-schema."
      {:type :eacl.schema/invalid-orphan-policy
       :eacl/error :eacl.schema/invalid-orphan-policy
       :orphan-policy :retain-inert
       :operation :write-schema!}))))

(defn write-schema!
  "Plans through `plan-schema-replacement`, adds committed CAS guards, and
  transacts the resulting DataScript schema replacement."
  ([conn schema-string]
   (write-schema! conn schema-string {}))
  ([conn schema-string options]
   (write-schema! conn schema-string options ::read-current-generation))
  ([conn schema-string options known-schema-generation]
   (reject-committed-retain-inert! options)
   ;; Validate before the additive first-write coherence bootstrap.
   (plan-schema-replacement (ds/db conn) schema-string options)
   (let [db (ensure-schema-coherence! conn)
         plan (plan-schema-replacement db schema-string options)
         schema-generation
         (if (= ::read-current-generation known-schema-generation)
           (current-schema-generation db)
           known-schema-generation)
         schema-write-fence (current-schema-write-fence db)
         tx-data
         (vec
          (concat
           [[:db.fn/cas (:schema-entity plan) :eacl/schema-write-fence
             schema-write-fence schema-write-fence]]
           (:relation-commit-guards plan)
           (:tx-data plan)))
         report
         (if (:no-op? plan)
           {:db-before db :db-after db :tx-data [] :no-op? true}
           (transact-schema! conn tx-data schema-generation))]
     (assoc (:deltas plan)
            :eacl.schema/db-after (:db-after report)
            :eacl.schema/no-op? (boolean (:no-op? report))))))
