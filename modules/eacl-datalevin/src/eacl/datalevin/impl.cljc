(ns eacl.datalevin.impl
  (:require [datalevin.core :as ds]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datalevin.db :as ddb]
            [eacl.engine.relationships :as relationship-engine]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.relationships.mutations :as relationship-mutations]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]))

(def permission-def-pull
  '[:db/id
    :eacl/id
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name
    :eacl.permission/expression-payload])

(defn- bounded-realize!
  [operation values]
  (let [result (into []
                     (take (inc ddb/maximum-unpaged-scan-results))
                     values)]
    (when (> (count result) ddb/maximum-unpaged-scan-results)
      (throw
       (ex-info
        "Datalevin metadata scan exceeded its certified eager bound."
        {:type :eacl/scan-limit-exceeded
         :eacl/error :eacl/scan-limit-exceeded
         :operation operation
         :maximum ddb/maximum-unpaged-scan-results})))
    result))

(defn relation-datoms
  "Returns relation datoms for the exact resource/relation name pair, for ANY
  subject-type keyword. Implemented as a seek + prefix take-while rather than a
  bounded index-range: a keyword-sentinel range like [.. :a]..[.. :z] silently
  misses subject types that collate outside it (uppercase-initial, z-prefixed,
  and namespaced keywords), making those relations invisible to permission
  evaluation (audit 2)."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    ;; Datalevin sorts vectors by LENGTH FIRST, so a short seek-start would
    ;; land at the head of the whole attribute; pad to full tuple arity with
    ;; nil (nil sorts lowest) to position at the exact prefix.
    (bounded-realize!
     :relation-definitions
     (take-while
      (fn [datom]
        (and
         (= :eacl.relation/resource-type+relation-name+subject-type
            (:a datom))
         (let [v (:v datom)]
           (and (= resource-type (nth v 0))
                (= relation-name (nth v 1))))))
      (ds/seek-datoms
       db :ave
       :eacl.relation/resource-type+relation-name+subject-type
       [resource-type relation-name nil]
       nil
       (inc ddb/maximum-unpaged-scan-results))))
    []))

(defn find-permission-defs
  [db resource-type permission-name]
  (let [definition-datoms
        (bounded-realize!
         :permission-definitions
         (ds/datoms
          db :ave :eacl.permission/resource-type+permission-name
          [resource-type permission-name]))]
    ;; Parse the pull pattern once and resolve the bounded entity set as one
    ;; operation. `map` of `pull` reparsed the same pattern for every union arm.
    (ds/pull-many db permission-def-pull (mapv :e definition-datoms))))

(defn all-relation-defs
  [db]
  (mapv (fn [{:keys [e v]}]
          {:relation-id e
           :resource-type (nth v 0)
           :relation-name (nth v 1)
           :subject-type (nth v 2)})
        (bounded-realize!
         :all-relation-definitions
         (ds/datoms
          db :ave
          :eacl.relation/resource-type+relation-name+subject-type))))

(defn- matching-relation-defs
  [db filters]
  (if (and (:resource/type filters) (:resource/relation filters))
    (mapv
     (fn [{:keys [e v]}]
       {:relation-id e
        :resource-type (nth v 0)
        :relation-name (nth v 1)
        :subject-type (nth v 2)})
     (relation-datoms
      db (:resource/type filters) (:resource/relation filters)))
    (all-relation-defs db)))

(defn all-permission-nodes
  [db]
  (into #{}
        (map :v)
        (bounded-realize!
         :all-permission-nodes
         (ds/datoms
          db :ave
          :eacl.permission/resource-type+permission-name))))

(defn- scan-value [datom] (nth (:v datom) 3))

(defn- eager-scan-values
  "Realizes one endpoint scan. The native prefix seek already starts at the
  inclusive bound and yields one EAV datom per distinct value, so the only
  remaining work is dropping an exclusive boundary row and bounding the
  page; nothing is re-filtered or de-duplicated per value."
  [datoms bound-eid inclusive-bound? limit include-qualifier?]
  (let [values (into [] (map (if include-qualifier? edge/from-datom scan-value)) datoms)
        values (if (and (some? bound-eid)
                        (not inclusive-bound?)
                        (pos? (count values))
                        (= bound-eid (edge/endpoint (nth values 0))))
                 (subvec values 1)
                 values)]
    (if limit
      (if (< limit (count values)) (subvec values 0 limit) values)
      (do
        (when (> (count values) ddb/maximum-unpaged-scan-results)
          (throw
           (ex-info
            "Datalevin relationship scan exceeded its certified eager bound."
            {:type :eacl/scan-limit-exceeded
             :eacl/error :eacl/scan-limit-exceeded
             :maximum ddb/maximum-unpaged-scan-results})))
        values))))

(defn- endpoint-scan
  [db endpoint-id attribute prefix cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound? limit include-qualifier?]}
        (relationship-storage/normalize-scan-options cursor-or-options)
        native-limit (inc (or limit ddb/maximum-unpaged-scan-results))]
    (eager-scan-values
     (ddb/eavt-endpoint-prefix
      db endpoint-id attribute prefix bound-eid direction native-limit include-qualifier?)
     bound-eid inclusive-bound? limit include-qualifier?)))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-or-options]
  (endpoint-scan db subject-id relationship-storage/forward-attribute
                 [subject-type relation-id resource-type]
                 cursor-or-options))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-or-options]
  (endpoint-scan db resource-id relationship-storage/reverse-attribute
                 [resource-type relation-id subject-type]
                 cursor-or-options))

(defn- relationship-tuple
  [{:keys [subject-type relation-id resource-type resource-id]}]
  (endpoint-pair/forward-value
   subject-type relation-id resource-type resource-id))

(defn- reverse-relationship-tuple
  [{:keys [resource-type relation-id subject-type subject-id]}]
  (endpoint-pair/reverse-value
   resource-type relation-id subject-type subject-id))

(defn- internal-id
  [db value]
  (when value
    (ds/entid db value)))

(defn- existing-internal-id
  "Resolves to an eid and verifies the entity exists (datom presence - entid
  passes unallocated numeric ids through unchanged). Throws :eacl/unknown-object
  otherwise: nil ids reaching tx-data raised raw transact errors, and silent
  no-ops hid typos (audit 11/12)."
  [db {:keys [type id] :as value}]
  (let [eid (internal-id db id)]
    (if (and eid (seq (ds/datoms db :eav eid)))
      eid
      (let [public-object (or (:eacl.relationship/public-object value)
                              {:type type :id id})]
        (throw (ex-info (str "Unknown object: " (pr-str (:type public-object))
                             " with id " (pr-str (:id public-object)) " does not exist.")
                        {:type :eacl/unknown-object
                         :eacl/error :eacl/unknown-object
                         :object public-object}))))))

(defn- relation-id
  [resource-type relation-name subject-type]
  [:eacl/id (model/->relation-id resource-type relation-name subject-type)])

(defn- endpoint-identity-guard
  [db role eid object]
  (let [candidate (or (:eacl.relationship/identity-guard object)
                      (when (and (vector? (:id object))
                                 (= 2 (count (:id object))))
                        (:id object))
                      (when-let [value (:eacl/id (ds/entity db eid))]
                        [:eacl/id value]))]
    (when-not (and (vector? candidate)
                   (= 2 (count candidate))
                   (keyword? (first candidate))
                   (some? (second candidate)))
      (throw
       (ex-info
        "A relationship endpoint has no commit-time identity guard."
        {:type :eacl/endpoint-identity-unavailable :eacl/error :eacl/endpoint-identity-unavailable
         :role role
         :endpoint-eid eid
         :object object})))
    (let [[attribute value] candidate]
      [:db.fn/cas eid attribute value value])))

(defn tx-relation-version-stamp
  [relation-id]
  [:db/add relation-id :eacl.datalevin/relation-generation :db/current-tx])

(defn- guard-schema-write-fence
  "Fences client-planned relationship data against concurrent schema writes.

  The dedicated fence is intentionally distinct from the cache generation:
  an implementation may reassert an old==new CAS datom with a new physical
  assertion tx. Such predicate bookkeeping must not invalidate managed schema
  entries."
  [db tx-data]
  (if (seq tx-data)
    (let [schema-eid (ds/entid db [:eacl/id "schema-string"])
          write-fence
          (when schema-eid
            (some-> (ds/datoms db :eav schema-eid
                               :eacl.datalevin/schema-write-fence)
                    first
                    :v))]
      (when-not write-fence
        (throw
         (ex-info
          "Relationship writes require a prepared EACL schema write fence."
          {:type :eacl.cache/generation-unprepared :eacl/error :eacl.cache/generation-unprepared
           :backend :datalevin})))
      (into
       [[:db.fn/cas schema-eid :eacl.datalevin/schema-write-fence
         write-fence write-fence]]
       tx-data))
    []))

(defn- resolve-relationship
  [db {:keys [subject relation resource]}]
  (let [subject-type  (:type subject)
        subject-id    (existing-internal-id db subject)
        resource-type (:type resource)
        resource-id   (existing-internal-id db resource)
        relation-eid  (ds/entid db (relation-id resource-type relation subject-type))]
    (when-not relation-eid
      (throw
       (ex-info
        (str "Missing Relation: " relation
             " on resource type " resource-type
             " for subject type " subject-type ".")
        {:type :eacl/unknown-relation-or-permission
         :eacl/error :eacl/unknown-relation-or-permission
         :operation :write-relationships
         :definition resource-type
         :relation relation
         :relation-or-permission relation
         :schema-kind :relation
         :resource/type resource-type
         :relation/name relation
         :subject/type subject-type})))
    {:subject subject
     :subject-type subject-type
     :subject-id subject-id
     :subject-identity-guard
     (endpoint-identity-guard db :subject subject-id subject)
     :relation relation
     :relation-id relation-eid
     :resource resource
     :resource-type resource-type
     :resource-id resource-id
     :resource-identity-guard
     (endpoint-identity-guard db :resource resource-id resource)}))

(defn relationship-publication-input
  "Resolves the same endpoint identities and commit guards as ordinary writes."
  [db relationship]
  (let [resolved (resolve-relationship db relationship)]
    {:relationship (mapv resolved [:subject-type :subject-id :relation-id :resource-type :resource-id])
     :identity-guards [(:subject-identity-guard resolved)
                       (:resource-identity-guard resolved)]}))

(defn relationship-relation-id
  [db relationship]
  (:relation-id (resolve-relationship db relationship)))

(defn- stored-halves
  [db resolved]
  [(vec (ddb/relationship-identity-datoms
         db (:subject-id resolved) relationship-storage/forward-attribute
         (relationship-tuple resolved)))
   (vec (ddb/relationship-identity-datoms
         db (:resource-id resolved) relationship-storage/reverse-attribute
         (reverse-relationship-tuple resolved)))])

(defn- add-relationship-txes
  [resolved]
  [(:subject-identity-guard resolved)
   (:resource-identity-guard resolved)
   [:db/add
    (:subject-id resolved)
    relationship-storage/forward-attribute
    (relationship-tuple resolved)]
   [:db/add
    (:resource-id resolved)
    relationship-storage/reverse-attribute
    (reverse-relationship-tuple resolved)]
   (tx-relation-version-stamp (:relation-id resolved))])

(defn- retract-relationship-txes
  [db resolved]
  (let [forward (ddb/all-relationship-identity-datoms db (:subject-id resolved)
                                                    relationship-storage/forward-attribute (relationship-tuple resolved))
        reverse (ddb/all-relationship-identity-datoms db (:resource-id resolved)
                                                    relationship-storage/reverse-attribute (reverse-relationship-tuple resolved))
        operations
        (into (mapv #(vector :db/retract (:subject-id resolved)
                             relationship-storage/forward-attribute (:v %)) forward)
              (map #(vector :db/retract (:resource-id resolved)
                            relationship-storage/reverse-attribute (:v %))) reverse)]
    ;; Keep absent deletes at the native commit boundary, so they serialize
    ;; with a concurrent create. Present identities always use stored values.
    (conj (if (seq operations)
            operations
            [[:db/retract (:subject-id resolved) relationship-storage/forward-attribute
              (relationship-tuple resolved)]
             [:db/retract (:resource-id resolved) relationship-storage/reverse-attribute
              (reverse-relationship-tuple resolved)]])
          (tx-relation-version-stamp (:relation-id resolved)))))

(defn direct-match?
  [db subject-type subject-id relation-id resource-type resource-id]
  (boolean
   (seq
    (endpoint-pair/checked-datoms
    (ddb/relationship-identity-datoms
     db subject-id relationship-storage/forward-attribute
     (endpoint-pair/forward-value
      subject-type relation-id resource-type resource-id))))))

(defn direct-edge
  "Stored compact edge or nil, prior to request qualification."
  [db subject-type subject-id relation-id resource-type resource-id]
  (some-> (first (endpoint-pair/checked-datoms
                 (ddb/relationship-identity-datoms
                  db subject-id relationship-storage/forward-attribute
                  (endpoint-pair/forward-value subject-type relation-id resource-type resource-id))
                 true))
          edge/from-datom))

(defn- reverse-match?
  [db resource-type resource-id relation-id subject-type subject-id]
  (boolean
   (seq
    (endpoint-pair/checked-datoms
    (ddb/relationship-identity-datoms
     db resource-id relationship-storage/reverse-attribute
     (endpoint-pair/reverse-value
      resource-type relation-id subject-type subject-id))))))

(defn validate-relationship-operation!
  [operation]
  (relationship-mutations/validate-operation! operation))

(defn create-relationship-at-commit
  "Commit-time precondition behind `:create`. It re-checks the relationship
  against the transaction-time database, so two writers that both planned a
  `:create` against the same pre-write value are serialized by the connection:
  the first commits and the second observes the winner and fails with
  `:eacl/relationship-conflict`.

  The planned adds remain adjacent to their update in the outer transaction;
  the shared writer runs every create precondition before those mutations so
  all updates in one batch retain the calculation-snapshot semantics shared
  with Datomic."
  [db resolved relationship]
  (let [halves (stored-halves db resolved)]
    (when (every? seq halves)
      (relationship-mutations/conflict! relationship))
    ;; A partial pair introduced after planning must not acquire a second
    ;; qualifier through the unconditional writer's repair operation.
    (doseq [half halves]
      (dorun (endpoint-pair/checked-datoms half)))
    []))

(defn tx-update-relationship
  [db {:keys [operation relationship]}]
  (validate-relationship-operation! operation)
  (let [resolved (resolve-relationship db relationship)
        halves (stored-halves db resolved)
        exists? (every? seq halves)
        _ (when (or (= :touch operation) (and (= :create operation) (not exists?)))
            (doseq [half halves]
              (dorun (endpoint-pair/checked-datoms half))))
        tx-data
        (case operation
          :touch
          (when-not exists?
            (add-relationship-txes resolved))

          :create
          (if exists?
            (relationship-mutations/conflict! relationship)
            (into
             [[:db.fn/call create-relationship-at-commit
               resolved relationship]]
             (add-relationship-txes resolved)))

          :delete
          ;; Retraction of an absent Datalevin datom is harmless. Always
          ;; retract both halves so an out-of-band half pair is repairable.
          (retract-relationship-txes db resolved))]
    (when tx-data
      (guard-schema-write-fence db tx-data))))

(defn read-relationships
  ([db filters]
   (read-relationships db filters nil))
  ([db filters decision-kernel]
   (read-relationships db filters decision-kernel nil))
  ([db filters decision-kernel window-options]
  ;; The unified filter contract shared by every backend
  ;; (backend-unification 9.1).
   (when-not relationship-filters/*validated-request?*
     (relationship-filters/validate! filters))
   (let [subject-id'  (when (contains? filters :subject/id)
                        (internal-id db (:subject/id filters)))
         resource-id' (when (contains? filters :resource/id)
                        (internal-id db (:resource/id filters)))
         filters'     (cond-> filters
                        (contains? filters :subject/id) (assoc :subject/id subject-id')
                        (contains? filters :resource/id) (assoc :resource/id resource-id'))]
     (if (or (and (contains? filters :subject/id) (nil? subject-id'))
             (and (contains? filters :resource/id) (nil? resource-id')))
       {:data [] :cursor nil}
       (letfn [(relationship-row [spec subject-id resource-id]
                 {:spec-idx    (:idx spec)
                  :subject-id  subject-id
                  :resource-id resource-id
                  :relationship
                  (eacl/->Relationship
                   (spice-object (:subject-type spec) subject-id)
                   (:relation-name spec)
                   (spice-object (:resource-type spec) resource-id))})
               (normalized-cursor [cursor]
                 (when cursor
                   (cond->
                    {:subject-id (or (:subject-id cursor)
                                     (:subject cursor))
                     :resource-id (or (:resource-id cursor)
                                      (:resource cursor))}
                     (:resume-inclusive? cursor)
                     (assoc :resume-inclusive? true))))
               (drop-until-beyond-cursor [spec cursor direction rows]
                 (let [cursor (normalized-cursor cursor)
                       scan-kind (:scan-kind spec)]
                   (drop-while
                    #(not (relationship-engine/beyond-cursor?
                           scan-kind direction cursor %))
                    rows)))
               (native-page-limit [spec cursor]
                 (when-let [remaining (:physical-limit spec)]
                  ;; Native seeks are inclusive. A continued page therefore
                  ;; needs one extra row for the authenticated boundary that
                  ;; `drop-until-beyond-cursor` removes.
                   (+ remaining
                      (if (and cursor (not (:resume-inclusive? cursor)))
                        1
                        0))))
               (exact-match-row [spec cursor direction]
                 (let [row
                       (when (and (:subject-id spec) (:resource-id spec))
                         (when
                          (direct-match?
                           db
                           (:subject-type spec)
                           (:subject-id spec)
                           (:relation-id spec)
                           (:resource-type spec)
                           (:resource-id spec))
                           (relationship-row
                            spec (:subject-id spec) (:resource-id spec))))]
                   (if row
                     (drop-until-beyond-cursor
                      spec cursor direction [row])
                     [])))
               (scan-forward-anchored [spec cursor direction]
                 (if (:resource-id spec)
                   (exact-match-row spec cursor direction)
                   (let [args
                         [db
                          (:subject-id spec)
                          relationship-storage/forward-attribute
                          [(:subject-type spec)
                           (:relation-id spec)
                           (:resource-type spec)]
                          (or (:resource-id cursor)
                              (:resource cursor))
                          direction]
                         rows
                         (if-let [limit (native-page-limit spec cursor)]
                           (apply ddb/eavt-endpoint-prefix
                                  (conj args limit))
                           (apply ddb/eavt-endpoint-prefix args))]
                     (->> rows
                          (map
                           (fn [{:keys [v]}]
                             (relationship-row
                              spec (:subject-id spec) (nth v 3))))
                          (drop-until-beyond-cursor
                           spec cursor direction)))))
               (scan-reverse-anchored [spec cursor direction]
                 (if (:subject-id spec)
                   (exact-match-row spec cursor direction)
                   (let [args
                         [db
                          (:resource-id spec)
                          relationship-storage/reverse-attribute
                          [(:resource-type spec)
                           (:relation-id spec)
                           (:subject-type spec)]
                          (or (:subject-id cursor)
                              (:subject cursor))
                          direction]
                         rows
                         (if-let [limit (native-page-limit spec cursor)]
                           (apply ddb/eavt-endpoint-prefix
                                  (conj args limit))
                           (apply ddb/eavt-endpoint-prefix args))]
                     (->> rows
                          (map
                           (fn [{:keys [v]}]
                             (relationship-row
                              spec (nth v 3) (:resource-id spec))))
                          (drop-until-beyond-cursor
                           spec cursor direction)))))
               (scan-forward-partial [spec cursor direction]
                 (let [args
                       [db
                        relationship-storage/forward-attribute
                        [(:subject-type spec)
                         (:relation-id spec)
                         (:resource-type spec)]
                        (or (:resource-id cursor)
                            (:resource cursor))
                        (or (:subject-id cursor)
                            (:subject cursor))
                        direction]
                       rows
                       (if-let [limit (native-page-limit spec cursor)]
                         (apply ddb/avet-endpoint-prefix (conj args limit))
                         (ddb/avet-endpoint-prefix
                          db
                          relationship-storage/forward-attribute
                          [(:subject-type spec)
                           (:relation-id spec)
                           (:resource-type spec)]
                          (or (:resource-id cursor)
                              (:resource cursor))
                          direction))]
                   (->> rows
                        (map
                         (fn [{:keys [e v]}]
                           (relationship-row spec e (nth v 3))))
                        (drop-until-beyond-cursor
                         spec cursor direction))))
               (scan-reverse-partial [spec cursor direction]
                 (let [args
                       [db
                        relationship-storage/reverse-attribute
                        [(:resource-type spec)
                         (:relation-id spec)
                         (:subject-type spec)]
                        (or (:subject-id cursor)
                            (:subject cursor))
                        (or (:resource-id cursor)
                            (:resource cursor))
                        direction]
                       rows
                       (if-let [limit (native-page-limit spec cursor)]
                         (apply ddb/avet-endpoint-prefix (conj args limit))
                         (ddb/avet-endpoint-prefix
                          db
                          relationship-storage/reverse-attribute
                          [(:resource-type spec)
                           (:relation-id spec)
                           (:subject-type spec)]
                          (or (:subject-id cursor)
                              (:subject cursor))
                          direction))]
                   (->> rows
                        (map
                         (fn [{:keys [e v]}]
                           (relationship-row spec (nth v 3) e)))
                        (drop-until-beyond-cursor
                         spec cursor direction))))
               (scan-spec
                 ([spec cursor]
                  (scan-spec spec cursor :asc))
                 ([spec cursor direction]
                  (case (:scan-kind spec)
                    :forward-anchored
                    (scan-forward-anchored spec cursor direction)

                    :reverse-anchored
                    (scan-reverse-anchored spec cursor direction)

                    :forward-partial
                    (scan-forward-partial spec cursor direction)

                    (scan-reverse-partial
                     spec cursor direction))))]
         (let [scan-specs
               (relationship-engine/plan-scans
                (matching-relation-defs db filters') filters')]
           (if window-options
             (relationship-engine/execute-filtered-window
              scan-specs filters' decision-kernel scan-spec window-options)
             (if-not (or (contains? filters' :limit)
                         (contains? filters' :cursor))
               (relationship-engine/execute-page
                scan-specs filters' decision-kernel scan-spec)
               (relationship-engine/execute-plan
                scan-specs filters' scan-spec)))))))))

(defn stamp-relation-versions
  "Adds one idempotent native generation stamp per affected relation."
  [tx-data]
  (relationship-mutations/stamp-relation-generations
   tx-data
   :eacl.datalevin/relation-generation
   tx-relation-version-stamp))

(defn- object-relationship-retractions
  "Exact paired cleanup, including surviving peers of an already deleted object."
  [db object-eid]
  (if object-eid
    (let [triples (relationship-storage/relation-triples
                   (ddb/avet-datoms db :eacl.relation/resource-type+relation-name+subject-type))
          local (mapcat (fn [[direction attr]]
                          (mapcat #(endpoint-pair/half-retractions direction object-eid (:v %))
                                  (ddb/eavt-datoms db object-eid attr)))
                        [[:forward relationship-storage/forward-attribute]
                         [:reverse relationship-storage/reverse-attribute]])
          incoming (mapcat
                    (fn [[resource-type relation-id subject-type]]
                      (concat
                       (mapcat #(endpoint-pair/half-retractions :reverse (:e %) (:v %))
                               (ddb/global-relationship-identity-datoms
                                db relationship-storage/reverse-attribute
                                (endpoint-pair/reverse-value resource-type relation-id subject-type object-eid)))
                       (mapcat #(endpoint-pair/half-retractions :forward (:e %) (:v %))
                               (ddb/global-relationship-identity-datoms
                                db relationship-storage/forward-attribute
                                (endpoint-pair/forward-value subject-type relation-id resource-type object-eid)))))
                    triples)]
      (->> (concat local incoming) distinct vec stamp-relation-versions))
    []))

(defn delete-object-at-commit
  "Datalevin transaction function that rescans the writer's serialized DB.
  A relationship committed after planning but before this transaction is
  therefore removed; a relationship committed afterward linearizes later."
  [db object-eid]
  (object-relationship-retractions db object-eid))

(defn tx-delete-object
  "Returns a commit-time transaction function removing both physical halves
  of every relationship touching `object-id`. The object entity itself is
  retained. Cross-entity AVET probes repair one-sided out-of-band ghosts."
  [db object-id]
  (if-let [object-eid (internal-id db object-id)]
    (guard-schema-write-fence
     db [[:db.fn/call delete-object-at-commit object-eid]])
    []))

(defn affected-relation-ids
  "Every relation named by endpoint-pair retraction operations."
  [tx-data]
  (relationship-mutations/affected-relation-ids tx-data #{:db/retract}))
