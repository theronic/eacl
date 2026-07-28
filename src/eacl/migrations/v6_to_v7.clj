(ns eacl.migrations.v6-to-v7
  "One-time migration from EACL v6 relationship storage to the v7 tuple model.

  v6 stored one Datomic entity per relationship (7 datoms across
  :eacl.relationship/* attributes). v7 stores each relationship as two
  cardinality-many tuple datoms asserted directly on your domain entities:

    [:db/add <subject-eid>  :eacl.v7.relationship/subject-type+relation+resource-type+resource
     [<subject-type> <relation-eid> <resource-type> <resource-eid>]]
    [:db/add <resource-eid> :eacl.v7.relationship/resource-type+relation+subject-type+subject
     [<resource-type> <relation-eid> <subject-type> <subject-eid>]]

  where <relation-eid> refers to the Relation schema entity, not the relation
  name keyword.

  Entry points:
  - `migrate!` runs the whole migration end-to-end (see its docstring for the
    exact steps). It is additive and idempotent: v6 relationship entities are
    left in place unless you pass {:retract-v6-entities? true}, so you can
    re-run it safely and roll back by redeploying v6 code any time before you
    retract.
  - `assert-storage-compatible!` is called by eacl.datomic.core/make-client on
    startup. It throws {:type :eacl/storage-version} when the database still
    contains unmigrated v6 relationship data, unless the client opts into
    automatic migration with {:auto-migrate-v6 <opts>}.

  Schema (Relations & Permissions) is deliberately NOT interpreted or
  converted from stored v6 data. Pass your SpiceDB schema string as {:schema
  ...} and `migrate!` re-asserts it via eacl.datomic.schema/write-schema!,
  which validates it and retracts any stored schema entities that are no
  longer part of it. `normalize-schema-entity-ids!` first gives legacy schema
  entities the canonical :eacl/id handles write-schema! needs to manage them.

  Full walkthrough, rollback strategy and edge cases:
  docs/migration-v6-to-v7.md."
  (:require [datomic.api :as d]
            [eacl.datomic.impl.base :as base]
            [eacl.datomic.schema :as schema]))

(def storage-version
  "The relationship storage model version this version of EACL reads & writes."
  7)

(def forward-attr :eacl.v7.relationship/subject-type+relation+resource-type+resource)
(def reverse-attr :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(def v6-relation-name-attr
  "Every v6 relationship entity carries this attribute exactly once, so its
  datoms enumerate v6 relationships."
  :eacl.relationship/relation-name)

(def v6-relationship-schema
  "The v6 relationship-entity attribute definitions, kept for reference and for
  test fixtures that need to construct a v6-model database. Nothing in v7
  installs or reads these. Datomic cannot uninstall attributes, so migrated
  databases retain these definitions (inert) forever."
  [{:db/ident       :eacl.relationship/subject-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relationship/subject
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relationship/relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relationship/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relationship/resource
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject-type
                     :eacl.relationship/subject
                     :eacl.relationship/relation-name
                     :eacl.relationship/resource-type
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource-type
                     :eacl.relationship/resource
                     :eacl.relationship/relation-name
                     :eacl.relationship/subject-type
                     :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity}])

;; Detection ------------------------------------------------------------------

(defn- count-datoms [db attr]
  ;; d/datoms on a not-yet-installed attribute returns an empty seq, so these
  ;; are safe against any database. reduce avoids holding the seq head.
  (reduce (fn [n _] (inc n)) 0 (d/datoms db :aevt attr)))

(defn stamped-storage-version
  "The :eacl/storage-version long stamped by a completed migrate! (or
  stamp-storage-version!), or nil if never stamped. Fresh v7 installs are not
  stamped and don't need to be: the stamp exists to distinguish 'migrated, v6
  leftovers are inert' from 'unmigrated v6 data'."
  [db]
  (:v (first (d/datoms db :aevt :eacl/storage-version))))

(defn v6-relationship-eids
  "Lazy seq of all v6 relationship entity ids."
  [db]
  (map :e (d/datoms db :aevt v6-relation-name-attr)))

(defn detect-storage-version
  "Classifies the relationship data at rest:
  :v7    — only v7 tuples (or a stamped migration)
  :v6    — only v6 relationship entities
  :mixed — both, i.e. mid-migration or migrated but not yet cleaned up
  :none  — no relationship data at all (fresh database)"
  [db]
  (let [v6? (boolean (seq (d/datoms db :aevt v6-relation-name-attr)))
        v7? (boolean (seq (d/datoms db :aevt forward-attr)))]
    (cond
      (and v6? v7?) :mixed
      v7?           :v7
      v6?           :v6
      :else         :none)))

;; Step helpers ---------------------------------------------------------------

(defn ensure-v7-attributes!
  "Installs the v7 schema attributes. Additive and idempotent against a v6
  database: shared attribute definitions are unchanged and only the new
  attributes (:eacl/schema-version, :eacl/storage-version and the two
  :eacl.v7.relationship/* tuples) are added. Running v6 code is unaffected."
  [conn]
  @(d/transact conn schema/v7-schema)
  :installed)

(defn normalize-schema-entity-ids!
  "Asserts the canonical :eacl/id onto any Relation/Permission schema entity
  missing one (installs that predate the :eacl/id convention). write-schema!
  addresses schema entities by [:eacl/id ...] when computing retractions, so
  entities without one cannot be managed — or cleaned up — until normalized.

  For pre-unified-permission entities the derived id may contain empty
  segments; that is fine — it only needs to be a unique handle that lets
  write-schema! retract the outdated entity. Returns the number of entities
  normalized (0 on databases written by any recent v6)."
  [conn]
  (let [db        (d/db conn)
        rel-txes  (for [datom (d/datoms db :aevt :eacl.relation/relation-name)
                        :let [e (d/entity db (:e datom))]
                        :when (nil? (:eacl/id e))]
                    [:db/add (:db/id e) :eacl/id
                     (base/->relation-id (:eacl.relation/resource-type e)
                                         (:eacl.relation/relation-name e)
                                         (:eacl.relation/subject-type e))])
        perm-txes (for [datom (d/datoms db :aevt :eacl.permission/permission-name)
                        :let [e (d/entity db (:e datom))]
                        :when (nil? (:eacl/id e))]
                    [:db/add (:db/id e) :eacl/id
                     (base/->permission-id (:eacl.permission/resource-type e)
                                           (:eacl.permission/permission-name e)
                                           (:eacl.permission/source-relation-name e)
                                           (:eacl.permission/target-type e)
                                           (:eacl.permission/target-name e))])
        tx-data   (vec (concat rel-txes perm-txes))]
    (when (seq tx-data)
      @(d/transact conn tx-data))
    (count tx-data)))

(defn relation-eid-index
  "Maps [resource-type relation-name subject-type] -> Relation schema entity
  eid. v7 tuples reference Relations by eid, so every migrated relationship
  must resolve its triple through this index."
  [db]
  (into {}
    (map (fn [[resource-type relation-name subject-type relation-eid]]
           [[resource-type relation-name subject-type] relation-eid]))
    (d/q '[:find ?resource-type ?relation-name ?subject-type ?relation
           :where
           [?relation :eacl.relation/resource-type ?resource-type]
           [?relation :eacl.relation/relation-name ?relation-name]
           [?relation :eacl.relation/subject-type ?subject-type]]
      db)))

(defn v6-relationship->v7-txes
  "The two v7 tuple assertions for one v6 relationship entity. Throws
  {:type :eacl.migration/missing-relation} if the relationship references a
  [resource-type relation-name subject-type] triple with no Relation schema
  entity — such rows never granted anything in v6 either; add the Relation to
  your schema or retract the dead row, then re-run."
  [db relation-index v6-rel-eid]
  (let [{:eacl.relationship/keys [subject-type subject relation-name resource-type resource]}
        (d/entity db v6-rel-eid)
        subject-eid  (:db/id subject)
        resource-eid (:db/id resource)
        relation-eid (get relation-index [resource-type relation-name subject-type])]
    (when-not relation-eid
      (throw (ex-info "v6 relationship references a missing Relation."
               {:type                :eacl.migration/missing-relation
                :v6-relationship-eid v6-rel-eid
                :resource-type       resource-type
                :relation-name       relation-name
                :subject-type        subject-type})))
    [[:db/add subject-eid forward-attr [subject-type relation-eid resource-type resource-eid]]
     [:db/add resource-eid reverse-attr [resource-type relation-eid subject-type subject-eid]]]))

(defn backfill-relationship-tuples!
  "Asserts the v7 tuple pair for every v6 relationship entity, in batches.
  Additive and idempotent: v6 entities are untouched and re-asserting an
  existing tuple datom is a no-op. Pause relationship writes while this runs —
  a v6-side delete racing the backfill would leave a resurrecting tuple.
  Returns the number of v6 relationships processed."
  [conn {:keys [batch-size] :or {batch-size 500}}]
  (let [db             (d/db conn)
        relation-index (relation-eid-index db)]
    (reduce
      (fn [n batch]
        @(d/transact conn (vec (mapcat #(v6-relationship->v7-txes db relation-index %) batch)))
        (+ n (count batch)))
      0
      (partition-all batch-size (v6-relationship-eids db)))))

;; Verification ---------------------------------------------------------------

(defn missing-tuples
  "Lazy seq of v6 relationship rows whose v7 forward or reverse tuple is
  absent (or whose Relation cannot be resolved). Empty after a complete
  backfill. Streams via per-row index lookups, so it is safe on large
  databases."
  [db]
  (let [relation-index (relation-eid-index db)]
    (for [v6-rel-eid (v6-relationship-eids db)
          :let [{:eacl.relationship/keys [subject-type subject relation-name resource-type resource]}
                (d/entity db v6-rel-eid)
                subject-eid  (:db/id subject)
                resource-eid (:db/id resource)
                relation-eid (get relation-index [resource-type relation-name subject-type])
                forward?     (boolean
                               (and relation-eid
                                    (seq (d/datoms db :eavt subject-eid forward-attr
                                           [subject-type relation-eid resource-type resource-eid]))))
                reverse?     (boolean
                               (and relation-eid
                                    (seq (d/datoms db :eavt resource-eid reverse-attr
                                           [resource-type relation-eid subject-type subject-eid]))))]
          :when (not (and forward? reverse?))]
      {:v6-relationship-eid v6-rel-eid
       :subject-type        subject-type
       :subject-eid         subject-eid
       :relation-name       relation-name
       :resource-type       resource-type
       :resource-eid        resource-eid
       :relation-resolved?  (boolean relation-eid)
       :forward-tuple?      forward?
       :reverse-tuple?      reverse?})))

(defn verify-backfill
  "Reports on backfill completeness. :complete? is true when every v6
  relationship has both v7 tuples. v7 counts exceeding the v6 count is not a
  failure — relationships written through v7 code after deploy have no v6
  counterpart."
  [db]
  (let [missing-sample (into [] (take 10) (missing-tuples db))]
    {:v6-relationship-entities (count-datoms db v6-relation-name-attr)
     :v7-forward-tuples        (count-datoms db forward-attr)
     :v7-reverse-tuples        (count-datoms db reverse-attr)
     :missing-sample           missing-sample
     :complete?                (empty? missing-sample)}))

;; Cleanup & stamping ---------------------------------------------------------

(defn retract-v6-relationship-entities!
  "Retracts all v6 relationship entities in batches. Idempotent. Run only
  after v7 code is deployed and verified — this forfeits the redeploy-v6
  rollback. Returns the number of entities retracted."
  [conn {:keys [batch-size] :or {batch-size 500}}]
  (reduce
    (fn [n batch]
      @(d/transact conn (mapv (fn [eid] [:db.fn/retractEntity eid]) batch))
      (+ n (count batch)))
    0
    (partition-all batch-size (v6-relationship-eids (d/db conn)))))

(defn stamp-storage-version!
  "Records {:eacl/storage-version 7} on the EACL singleton so
  assert-storage-compatible! knows any remaining v6 relationship entities are
  migrated leftovers, not live data. migrate! stamps automatically; call this
  yourself only if you migrated manually (e.g. by following the guide's
  snippets) and make-client now refuses to start."
  [conn]
  @(d/transact conn [{:eacl/id              "schema-string"
                      :eacl/storage-version storage-version}])
  storage-version)

;; End-to-end -----------------------------------------------------------------

(def ^:private known-migrate-opt-keys
  #{:schema :batch-size :retract-v6-entities?})

(defn migrate!
  "End-to-end v6 -> v7 migration. Steps, in order:

  1. ensure-v7-attributes!            — install v7 schema attributes (additive)
  2. normalize-schema-entity-ids!     — give legacy schema entities :eacl/id handles
  3. write-schema! with :schema       — re-assert your schema (skipped if absent)
  4. backfill-relationship-tuples!    — v7 tuples for every v6 relationship
  5. verify-backfill                  — throws unless every v6 row has its tuples
  6. retract-v6-relationship-entities! when {:retract-v6-entities? true}
  7. stamp-storage-version!           — unblocks make-client's startup check

  Options:
  - :schema (string, recommended) — your SpiceDB schema DSL string, re-asserted
    via eacl.datomic.schema/write-schema!. This is the supported way to migrate
    schema: stored v6 Relation/Permission entities are not interpreted; on
    standard v6 databases re-assertion is a zero-delta no-op that keeps
    relation eids stable, and outdated schema entities are retracted by
    write-schema!'s delta logic. Without :schema, stored schema entities carry
    over untouched (the Relation/Permission model is identical in v6 and v7).
    Note: if the schema string drops a relation that stored v6 relationships
    still use, step 4 throws :eacl.migration/missing-relation and the
    migration aborts additively — nothing is lost, fix the schema and re-run.
  - :retract-v6-entities? (default false) — also run step 6. Leave false for a
    soak period: v6 entities are inert under v7 code, and keeping them
    preserves rollback by redeploying v6.
  - :batch-size (default 500) — relationships per transaction (2 datoms each).

  Idempotent: safe to re-run after interruption or as a catch-up pass. Pause
  relationship writes while it runs. Returns a report map; throws
  {:type :eacl.migration/incomplete} if verification fails."
  ([conn] (migrate! conn {}))
  ([conn {:keys [schema batch-size retract-v6-entities?]
          :or   {batch-size 500, retract-v6-entities? false}
          :as   opts}]
   (when-let [unknown-keys (seq (remove known-migrate-opt-keys (keys opts)))]
     (throw (ex-info (str "EACL Migration Error: unknown migrate! option(s) " (pr-str (vec unknown-keys))
                          ". Known options: " (pr-str (vec (sort known-migrate-opt-keys))) ".")
              {:type         :eacl/invalid-config
               :unknown-keys (vec unknown-keys)
               :known-keys   known-migrate-opt-keys})))
   (ensure-v7-attributes! conn)
   (let [normalized    (normalize-schema-entity-ids! conn)
         schema-deltas (when schema
                         (schema/write-schema! conn schema))
         backfilled    (backfill-relationship-tuples! conn {:batch-size batch-size})
         verify-report (verify-backfill (d/db conn))]
     (when-not (:complete? verify-report)
       (throw (ex-info "EACL v6->v7 backfill incomplete: some v6 relationships are missing v7 tuples. Nothing was retracted; fix the sample rows and re-run migrate!."
                {:type   :eacl.migration/incomplete
                 :report verify-report})))
     (let [retracted (if retract-v6-entities?
                       (retract-v6-relationship-entities! conn {:batch-size batch-size})
                       0)]
       (stamp-storage-version! conn)
       {:storage-version              (stamped-storage-version (d/db conn))
        :detected                     (detect-storage-version (d/db conn))
        :normalized-schema-entity-ids normalized
        :schema-deltas                schema-deltas
        :relationships-backfilled     backfilled
        :v6-entities-retracted        retracted
        :verify                       (dissoc verify-report :missing-sample)}))))

;; Startup guard --------------------------------------------------------------

(defn assert-storage-compatible!
  "Startup check called by eacl.datomic.core/make-client. v7 code reads only
  v7 tuples, so starting it against unmigrated v6 relationship data would
  silently answer every permission check with false/empty — this fails loudly
  instead.

  Passes when the database has a :eacl/storage-version stamp >= 7, or contains
  no v6 relationship entities (fresh install, or migrated & cleaned up).
  Otherwise: with {:auto-migrate-v6 opts} it runs (migrate! conn opts) —
  pass {:auto-migrate-v6 true} for default options — and with no opt-in it
  throws {:type :eacl/storage-version}."
  [conn {:keys [auto-migrate-v6]}]
  (let [db    (d/db conn)
        stamp (stamped-storage-version db)]
    (cond
      (and stamp (>= stamp storage-version))
      :ok

      (empty? (take 1 (v6-relationship-eids db)))
      :ok

      auto-migrate-v6
      (do (migrate! conn (if (map? auto-migrate-v6) auto-migrate-v6 {}))
          :migrated)

      :else
      (throw
        (ex-info
          (str "EACL storage-version mismatch: this database contains v6 relationship entities "
               "(:eacl.relationship/*), which EACL v7 does not read — starting up would answer "
               "every permission check with false/empty results. "
               "Run (eacl.migrations.v6-to-v7/migrate! conn {:schema <your schema string>}), or opt in "
               "at startup with (make-client conn {:auto-migrate-v6 {:schema <your schema string>}}). "
               "If you already migrated manually, record it with "
               "(eacl.migrations.v6-to-v7/stamp-storage-version! conn). "
               "See docs/migration-v6-to-v7.md.")
          {:type             :eacl/storage-version
           :detected         (detect-storage-version db)
           :stamped-version  stamp
           :required-version storage-version
           :migration-ns     'eacl.migrations.v6-to-v7
           :guide            "docs/migration-v6-to-v7.md"})))))
