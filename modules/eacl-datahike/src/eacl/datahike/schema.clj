(ns eacl.datahike.schema
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [eacl.datahike.db :as ddb]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.expression-policy :as expression-policy]
            [eacl.schema.expression-resolver :as expression-resolver]
            [eacl.schema.model :as model]
            [eacl.schema.replacement-plan :as replacement-plan]))

(def relation-key-attr
  :eacl.relation/resource-type+relation-name+subject-type)

(def permission-key-attr
  :eacl.permission/resource-type+permission-name)

(def permission-storage-version 8)

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
   {:db/ident       :eacl/permission-storage-version
    :db/valueType   :db.type/long
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

   {:db/ident       :eacl.permission/expression-payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
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
   ;; EACL-created stores retain temporal values so exact tokens and cursors
   ;; survive ordinary commit-record cutoff GC. Applications may explicitly
   ;; opt out with {:keep-history? false} to trade this guarantee for lower
   ;; storage and write amplification.
   :keep-history?      true
   ;; EACL object ids come from the caller, so no cap is imposed here; stating 0
   ;; declares that as intentional rather than leaving datahike to warn about it.
   :max-string-length  0})

(def live-source-id-key
  "Connection-local lineage identity carried by non-durable store configs."
  :eacl.datahike/live-source-id)

(defn create-conn
  "A datahike connection carrying EACL's schema.

  `config` is merged over `default-config`; temporal history is enabled unless
  the caller explicitly passes `{:keep-history? false}`. Pass
  `{:attribute-refs? true}` to get Datomic's numeric attribute representation.
  A memory store gets a fresh lineage id per created live source even when the
  caller supplies a fixed store id."
  ([] (create-conn nil nil))
  ([extra-schema] (create-conn extra-schema nil))
  ([extra-schema config]
   (let [cfg (-> (merge default-config config)
                 (update :store #(merge {:id (random-uuid)} %)))
         cfg (cond-> cfg
               (= :memory (get-in cfg [:store :backend]))
               (assoc live-source-id-key (random-uuid)))]
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
    :eacl.permission/target-name
    :eacl.permission/expression-payload])

(def ^:private legacy-permission-pull
  (vec (remove #{:eacl.permission/expression-payload} permission-pull)))

(defn stamped-permission-storage-version
  [db]
  (when (ddb/entid db :eacl/permission-storage-version)
    (:eacl/permission-storage-version
     (d/entity db [:eacl/id "schema-string"]))))

(defn- physical-permission-key-rows
  "Migration-aware permission rows, including their physical entity ids.

  A released-v7 database does not have the expression attribute at all. Avoid
  asking Datahike to pull an unknown attribute on every row: apart from noisy
  warnings, that work would make the bounded startup compatibility gate look
  more expensive than it is."
  [db]
  (let [pull-pattern
        (into [:db/id]
              (if (ddb/entid db :eacl.permission/expression-payload)
                permission-pull
                legacy-permission-pull))]
    (mapv #(d/pull db pull-pattern (:e %))
          (ddb/avet-datoms db permission-key-attr))))

(defn- physical-permissions
  "Active permission rows. A stamped v8 database retains released-v7 flat
  entities as inert history and selects only rows carrying the authoritative
  expression payload."
  [db]
  (let [rows (physical-permission-key-rows db)]
    (if (= permission-storage-version
           (stamped-permission-storage-version db))
      (filterv #(contains? % :eacl.permission/expression-payload) rows)
      rows)))

(defn read-relations
  "Every relation definition. Enumerated from the relation key index rather than
   by query: the index is the engine's own view of the schema, so a relation
   that is invisible here is invisible to permission evaluation too."
  [db]
  (mapv #(d/pull db relation-pull (:e %))
        (ddb/avet-datoms db relation-key-attr)))

(defn read-permissions
  [db]
  (mapv #(dissoc % :db/id) (physical-permissions db)))

(defn permission-storage-shape
  "Classifies only bounded permission-definition rows.

  `:flat` is the released-v7 representation. Ordinary v8 reads accept only
  `:expression` or `:none`; `:mixed` is evidence of an interrupted or external
  rewrite and is never guessed through. Relationship tuples are not read."
  [db]
  (let [version (stamped-permission-storage-version db)
        rows (physical-permission-key-rows db)]
    (cond
      (and (some? version) (not= permission-storage-version version))
      (throw
       (ex-info
        "Datahike permission storage has an unsupported version stamp."
        {:type :eacl/permission-storage-version
         :eacl/error :eacl/permission-storage-version
         :detected version
         :required-version permission-storage-version}))

      (= permission-storage-version version)
      (let [expressions
            (filterv #(contains? % :eacl.permission/expression-payload) rows)]
        (cond
          (seq expressions) :expression
          (seq rows)
          (throw
           (ex-info
            "Stamped Datahike v8 storage contains no expression permissions."
            {:type :eacl/permission-storage-version
             :eacl/error :eacl/permission-storage-version
             :detected :stamped-flat-only
             :required-version permission-storage-version}))
          :else :none))

      :else
      (let [flat? (some #(not (contains?
                               % :eacl.permission/expression-payload))
                        rows)
            expression? (some #(contains?
                                % :eacl.permission/expression-payload)
                              rows)]
        (cond
          (and flat? expression?) :mixed
          expression? :expression
          flat? :flat
          :else :none)))))

(defn read-schema
  [db & [_format]]
  (let [permissions (read-permissions db)]
    (expression-persistence/validate-entities permissions)
    {:relations   (read-relations db)
     :permissions permissions}))

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
        (mapv :e
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

(defn relationship-present-for-relation?
  "At most one bounded AVET probe per physical direction for speculative
  retain-inert diagnostics. Either half witnesses retained relationship data,
  including a one-sided tuple left by earlier corruption."
  [db {:eacl.relation/keys [resource-type subject-type] :as relation}]
  (when-let [relation-eid (ddb/entid db [:eacl/id (:eacl/id relation)])]
    (boolean
     (or
      (first
       (ddb/avet-tuple-prefix
        db relationship-storage/forward-attribute 4
        [subject-type relation-eid resource-type]))
      (first
       (ddb/avet-tuple-prefix
        db relationship-storage/reverse-attribute 4
        [resource-type relation-eid subject-type]))))))

(defn current-schema-generation
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
           :eacl/error :eacl.schema/concurrent-write
           :expected-generation expected-generation
           :actual-generation (current-schema-generation (d/db conn))
           :backend-error cause-data
           :datahike-error cause-data}
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
        schema-eid (or (ddb/entid db [:eacl/id "schema-string"]) -1)
        relation-commit-guards
        (mapv
         (fn [relation]
           (let [relation-eid
                 (ddb/entid db [:eacl/id (:eacl/id relation)])
                 relation-generation
                 (some-> (ddb/eavt-datoms
                          db relation-eid :eacl/relation-version)
                         first :v)]
             (when-not relation-generation
               (throw
                (ex-info
                 "Relation removal requires prepared native generations."
                 {:type :eacl.cache/generation-unprepared
                  :eacl/error :eacl.cache/generation-unprepared
                  :backend :datahike
                  :relation-id (:eacl/id relation)})))
             [:db.fn/cas relation-eid
              (ddb/attr-repr db :eacl/relation-version)
              relation-generation relation-generation]))
         relation-retractions)
        tx-data
        (vec
         (concat
          relation-additions
          (:additions permissions)
          (for [relation relation-retractions
                :let [eid (ddb/entid db [:eacl/id (:eacl/id relation)])]
                :when eid]
            [:db/retractEntity eid])
          (for [permission permission-retractions
                :let [eid (ddb/entid db [:eacl/id (:eacl/id permission)])]
                :when eid]
            [:db/retractEntity eid])
          [{:db/id schema-eid
            :eacl/id "schema-string"
            :eacl/schema-string schema-string
            :eacl/permission-storage-version permission-storage-version}
           [:db/add schema-eid :eacl/schema-generation :db/current-tx]
           [:db/add schema-eid :eacl/schema-write-fence :db/current-tx]]))
        stored-string
        (some-> (d/entity db [:eacl/id "schema-string"])
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
  transacts the resulting Datahike schema replacement."
  ([conn schema-string]
   (write-schema! conn schema-string {}))
  ([conn schema-string options]
   (write-schema! conn schema-string options ::read-current-generation))
  ([conn schema-string options known-schema-generation]
   (reject-committed-retain-inert! options)
   ;; Validate before the additive first-write coherence bootstrap.
   (plan-schema-replacement (d/db conn) schema-string options)
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
           [[:db.fn/cas (:schema-entity plan)
             (ddb/attr-repr db :eacl/schema-write-fence)
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

(defn- flat-permission-coordinate
  [permission]
  [(:eacl.permission/resource-type permission)
   (:eacl.permission/permission-name permission)
   (:eacl.permission/source-relation-name permission)
   (:eacl.permission/target-type permission)
   (:eacl.permission/target-name permission)])

(defn- candidate-flat-denotation
  [permissions]
  (into
   #{}
   (mapcat
    (fn [permission]
      (let [resolved (expression-persistence/decode-entity permission)]
        (map
         (juxt :resource-type :permission-name :source-relation-name
               :target-type :target-name)
         (expression-persistence/union-compatible-definitions
          (:eacl/id permission) resolved)))))
   permissions))

(defn- assert-migration-equivalent!
  [relations legacy-permissions candidate]
  (model/validate-schema-references
   {:relations relations :permissions legacy-permissions})
  (let [stored-relations (set relations)
        candidate-relations (set (:relations candidate))
        relation-additions
        (vec (sort-by pr-str
                      (set/difference candidate-relations stored-relations)))
        relation-retractions
        (vec (sort-by pr-str
                      (set/difference stored-relations candidate-relations)))]
    (when (or (seq relation-additions) (seq relation-retractions))
      (throw
       (ex-info
        "The Datahike v7->v8 permission upgrade cannot change relation identities."
        {:type :eacl.migration/relation-schema-change
         :eacl/error :eacl.migration/relation-schema-change
         :relation-additions relation-additions
         :relation-retractions relation-retractions}))))
  (let [stored-arms (mapv flat-permission-coordinate legacy-permissions)
        stored-denotation (set stored-arms)
        candidate-denotation
        (candidate-flat-denotation (:permissions candidate))]
    (when-not (= (count stored-arms) (count stored-denotation))
      (throw
       (ex-info
        "Released-v7 Datahike permission storage contains duplicate arms."
        {:type :eacl.schema/corrupt-expression-storage
         :eacl/error :eacl.schema/corrupt-expression-storage
         :reason :duplicate-flat-permission})))
    (when-not (= stored-denotation candidate-denotation)
      (throw
       (ex-info
        "The Datahike v7->v8 permission upgrade must preserve permission semantics exactly."
        {:type :eacl.migration/permission-semantic-change
         :eacl/error :eacl.migration/permission-semantic-change
         :missing-from-candidate
         (vec (sort-by pr-str
                       (set/difference stored-denotation
                                       candidate-denotation)))
         :added-by-candidate
         (vec (sort-by pr-str
                       (set/difference candidate-denotation
                                       stored-denotation)))})))))

(def ^:private v8-permission-attribute-definitions
  (filterv #(contains? #{:eacl.permission/expression-payload
                         :eacl/permission-storage-version}
                       (:db/ident %))
           component-schema))

(defn- normalize-schema-ref
  [db value]
  (if (number? value)
    (or (:db/ident (d/entity db value)) value)
    value))

(defn- ensure-v8-permission-attributes!
  [conn]
  (let [db (d/db conn)
        authoritative-keys [:db/ident :db/valueType :db/cardinality]
        missing
        (reduce
         (fn [result definition]
           (let [attribute (:db/ident definition)
                 expected (select-keys definition authoritative-keys)]
             (if-let [eid (ddb/entid db attribute)]
               (let [actual (select-keys (d/entity db eid)
                                         authoritative-keys)
                     actual (-> actual
                                (update :db/valueType
                                        #(normalize-schema-ref db %))
                                (update :db/cardinality
                                        #(normalize-schema-ref db %)))]
                 (when-not (= expected actual)
                   (throw
                    (ex-info
                     "An existing Datahike v8 permission attribute is incompatible."
                     {:type :eacl.migration/attribute-conflict
                      :eacl/error :eacl.migration/attribute-conflict
                      :attribute attribute
                      :expected expected
                      :actual actual})))
                 result)
               (conj result definition))))
         []
         v8-permission-attribute-definitions)]
    (when (seq missing)
      (d/transact conn missing))
    {:installed (count missing)}))

(defn migrate-v7-permissions!
  "Atomically activates v8 expressions over released-v7 flat permissions.

  This is a schema-row migration only. It proves exact relation and permission
  denotation before installing additive v8 attributes, then writes canonical
  expressions and the version-8 authority stamp behind the existing
  schema-write fence. Flat entities remain inert history; avoiding persistent
  index deletions is material for remote S3 stores. Relationship tuples are
  neither enumerated nor rewritten. `schema-string` may be nil when the
  database's schema singleton contains the authoritative source text."
  ([conn schema-string]
   (migrate-v7-permissions! conn schema-string nil))
  ([conn schema-string expression-limit-overrides]
   (let [expression-limits
         (expression-policy/normalize-client-limits
          expression-limit-overrides)
         db (d/db conn)
         shape (permission-storage-shape db)]
     (case shape
       :mixed
       (throw
        (ex-info
         "EACL permission storage contains mixed flat and expression rows."
         {:type :eacl/permission-storage-version
          :eacl/error :eacl/permission-storage-version
          :detected :mixed
          :required-version 8}))

       (:expression :none)
       {:status :already-v8
        :permission-storage-shape shape
        :relationships-touched 0}

       :flat
       (let [schema-eid (ddb/entid db [:eacl/id "schema-string"])
             schema-generation (current-schema-generation db)
             schema-write-fence (current-schema-write-fence db)
             stored-schema-string
             (some-> (d/entity db [:eacl/id "schema-string"])
                     :eacl/schema-string)
             schema-text (or schema-string stored-schema-string)
             legacy-permissions (physical-permissions db)
             relations (read-relations db)]
         (when-not (and schema-eid schema-generation schema-write-fence)
           (throw
            (ex-info
             "The Datahike v7->v8 migration requires prepared schema generations."
             {:type :eacl.cache/generation-unprepared
              :eacl/error :eacl.cache/generation-unprepared
              :backend :datahike})))
         (when-not (string? schema-text)
           (throw
            (ex-info
             "The Datahike v7->v8 migration requires stored or supplied schema text."
             {:type :eacl.migration/schema-required
              :eacl/error :eacl.migration/schema-required})))
         (binding [expression-persistence/*expression-limits*
                   expression-limits]
           (let [candidate
                 (expression-persistence/candidate-schema
                  (expression-resolver/validate-schema
                   schema-text expression-limits))]
             ;; Everything capable of failing on input must run before the
             ;; additive attribute transaction.
             (assert-migration-equivalent!
              relations legacy-permissions candidate)
             (ensure-v8-permission-attributes! conn)
             (let [prepared-db (d/db conn)
                   current-permissions (physical-permissions prepared-db)
                   current-relations (read-relations prepared-db)]
               (when-not (and (= schema-generation
                                 (current-schema-generation prepared-db))
                              (= schema-write-fence
                                 (current-schema-write-fence prepared-db))
                              (= legacy-permissions current-permissions)
                              (= relations current-relations))
                 (throw
                  (ex-info
                   "The EACL schema changed while preparing the Datahike v7->v8 migration."
                   {:type :eacl.schema/concurrent-write
                    :eacl/error :eacl.schema/concurrent-write
                    :expected-generation schema-generation
                    :actual-generation
                    (current-schema-generation prepared-db)})))
               (let [tx-data
                     (vec
                      (concat
                       [[:db.fn/cas schema-eid
                         (ddb/attr-repr prepared-db
                                        :eacl/schema-write-fence)
                         schema-write-fence schema-write-fence]]
                       (:permissions candidate)
                       [{:db/id schema-eid
                         :eacl/id "schema-string"
                         :eacl/schema-string schema-text
                         :eacl/permission-storage-version
                         permission-storage-version}
                        [:db/add schema-eid :eacl/schema-generation
                         :db/current-tx]
                        [:db/add schema-eid :eacl/schema-write-fence
                         :db/current-tx]]))
                     report
                     (transact-schema!
                      conn tx-data schema-generation)
                     db-after (:db-after report)]
                 (when-not (= :expression
                              (permission-storage-shape db-after))
                   (throw
                    (ex-info
                     "The Datahike v7->v8 permission swap did not produce expression storage."
                     {:type :eacl.migration/postcondition-failed
                      :eacl/error :eacl.migration/postcondition-failed})))
                 {:status :migrated
                  :permission-storage-shape :expression
                  :relationships-touched 0
                  :permission-additions (count (:permissions candidate))
                  :legacy-permissions-retained (count legacy-permissions)
                  :schema-generation
                  (current-schema-generation db-after)})))))))))
