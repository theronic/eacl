(ns eacl.datahike.impl
  (:require [datahike.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.schema :as schema]
            [eacl.engine.relationships :as relationship-engine]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]))

(def Relation model/Relation)
(def Permission model/Permission)

(defn Relationship
  [subject relation resource]
  (eacl/->Relationship subject relation resource))

(def permission-def-pull
  '[:db/id
    :eacl/id
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name
    :eacl.permission/expression-payload])

(defn relation-datoms
  "Returns relation datoms for the exact resource/relation name pair, for ANY
  subject-type keyword. Implemented as a seek + prefix take-while rather than a
  bounded index-range: a keyword-sentinel range like [.. :a]..[.. :z] silently
  misses subject types that collate outside it (uppercase-initial, z-prefixed,
  and namespaced keywords), making those relations invisible to permission
  evaluation (audit 2)."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (ddb/seek-tuple-prefix db schema/relation-key-attr 3 [resource-type relation-name])
    []))

(defn find-permission-defs
  [db resource-type permission-name]
  (let [definitions
        (->> (ddb/avet-datoms
              db schema/permission-key-attr [resource-type permission-name])
             (map :e)
             (mapv #(d/pull db permission-def-pull %)))]
    (if (= schema/permission-storage-version
           (schema/stamped-permission-storage-version db))
      (filterv #(contains? % :eacl.permission/expression-payload)
               definitions)
      definitions)))

(defn all-relation-defs
  [db]
  (mapv (fn [{:keys [e v]}]
          {:relation-id e
           :resource-type (nth v 0)
           :relation-name (nth v 1)
           :subject-type (nth v 2)})
        (ddb/avet-datoms db schema/relation-key-attr)))

(defn- relationship-datoms-on-entity
  ([db entity attr prefix cursor-id]
   (relationship-datoms-on-entity
    db entity attr prefix cursor-id :asc))
  ([db entity attr prefix cursor-id direction]
   (ddb/eavt-tuple-prefix
    db entity attr 4 prefix cursor-id direction)))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (if (map? cursor-or-options)
          (merge {:direction :asc} cursor-or-options)
          {:direction :asc
           :bound-eid cursor-or-options
           :inclusive-bound? false})
        within-bound?
        (case direction
          :asc
          (if bound-eid
            (if inclusive-bound?
              #(<= bound-eid %)
              #(< bound-eid %))
            (constantly true))

          :desc
          (if bound-eid
            (if inclusive-bound?
              #(>= bound-eid %)
              #(> bound-eid %))
            (constantly true)))]
    (->> (relationship-datoms-on-entity
          db subject-id relationship-storage/forward-attribute
          [subject-type relation-id resource-type]
          bound-eid direction)
         (map (comp #(nth % 3) :v))
         (filter within-bound?))))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (if (map? cursor-or-options)
          (merge {:direction :asc} cursor-or-options)
          {:direction :asc
           :bound-eid cursor-or-options
           :inclusive-bound? false})
        within-bound?
        (case direction
          :asc
          (if bound-eid
            (if inclusive-bound?
              #(<= bound-eid %)
              #(< bound-eid %))
            (constantly true))

          :desc
          (if bound-eid
            (if inclusive-bound?
              #(>= bound-eid %)
              #(> bound-eid %))
            (constantly true)))]
    (->> (relationship-datoms-on-entity
          db resource-id relationship-storage/reverse-attribute
          [resource-type relation-id subject-type]
          bound-eid direction)
         (map (comp #(nth % 3) :v))
         (filter within-bound?))))

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
    (ddb/entid db value)))

(defn- existing-internal-id
  "Resolves to an eid and verifies the entity exists (datom presence - entid
  passes unallocated numeric ids through unchanged). Throws :eacl/unknown-object
  otherwise: nil ids reaching tx-data raised raw transact errors, and silent
  no-ops hid typos (audit 11/12)."
  [db {:keys [type id] :as value}]
  (let [eid (internal-id db id)]
    (if (and eid (ddb/entity-exists? db eid))
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
                      (when-let [value (:eacl/id (d/entity db eid))]
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
      [:db.fn/cas eid (ddb/attr-repr db attribute) value value])))

(defn tx-relation-version-stamp
  [relation-id]
  [:db/add relation-id :eacl/relation-version :db/current-tx])

(defn- guard-schema-write-fence
  "Fences client-planned relationship data against concurrent schema writes.

  The dedicated fence is intentionally distinct from the cache generation:
  Datahike reasserts an old==new CAS datom with a new physical assertion tx.
  Letting that happen on :eacl/schema-generation would invalidate all managed
  schema entries after every relationship write."
  [db tx-data]
  (if (seq tx-data)
    (let [schema-eid (ddb/entid db [:eacl/id "schema-string"])
          write-fence
          (when schema-eid
            (some-> (ddb/eavt-datoms
                     db schema-eid :eacl/schema-write-fence)
                    first
                    :v))]
      (when-not write-fence
        (throw
         (ex-info
          "Relationship writes require a prepared EACL schema write fence."
          {:type :eacl.cache/generation-unprepared :eacl/error :eacl.cache/generation-unprepared
           :backend :datahike})))
      (into
       [[:db.fn/cas schema-eid
         (ddb/attr-repr db :eacl/schema-write-fence)
         write-fence write-fence]]
       tx-data))
    []))

(defn- resolve-relationship
  [db {:keys [subject relation resource]}]
  (let [subject-type  (:type subject)
        subject-id    (existing-internal-id db subject)
        resource-type (:type resource)
        resource-id   (existing-internal-id db resource)
        relation-eid  (ddb/entid db (relation-id resource-type relation subject-type))]
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

(defn find-one-relationship-id
  "Returns the resolved tuple identity for an existing relationship, or nil.
  A read: unresolvable endpoints mean no such relationship can exist -> nil."
  [db relationship]
  (let [resolved (try
                   (resolve-relationship db relationship)
                   (catch clojure.lang.ExceptionInfo e
                     (when-not (= :eacl/unknown-object (:type (ex-data e)))
                       (throw e))))
        existing? (and resolved
                       (seq
                        (ddb/eavt-datoms
                         db
                         (:subject-id resolved)
                         relationship-storage/forward-attribute
                         (relationship-tuple resolved)))
                       (seq
                        (ddb/eavt-datoms
                         db
                         (:resource-id resolved)
                         relationship-storage/reverse-attribute
                         (reverse-relationship-tuple resolved))))]
    (when existing?
      resolved)))

(defn relationship-relation-id
  [db relationship]
  (:relation-id (resolve-relationship db relationship)))

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
  [resolved]
  [[:db/retract
    (:subject-id resolved)
    relationship-storage/forward-attribute
    (relationship-tuple resolved)]
   [:db/retract
    (:resource-id resolved)
    relationship-storage/reverse-attribute
    (reverse-relationship-tuple resolved)]
   (tx-relation-version-stamp (:relation-id resolved))])

(defn direct-match?
  [db subject-type subject-id relation-id resource-type resource-id]
  (boolean
   (seq
    (ddb/eavt-datoms
     db subject-id relationship-storage/forward-attribute
     [subject-type relation-id resource-type resource-id]))))

(defn- reverse-match?
  [db resource-type resource-id relation-id subject-type subject-id]
  (boolean
   (seq
    (ddb/eavt-datoms
     db resource-id relationship-storage/reverse-attribute
     [resource-type relation-id subject-type subject-id]))))

(defn- relationship-exists?
  [db {:keys [subject-type subject-id relation-id
              resource-type resource-id]}]
  (and (direct-match? db subject-type subject-id relation-id
                      resource-type resource-id)
       (reverse-match? db resource-type resource-id relation-id
                       subject-type subject-id)))

(def ^:private supported-relationship-operations
  #{:create :touch :delete})

(defn validate-relationship-operation!
  [operation]
  (when-not (contains? supported-relationship-operations operation)
    (throw
     (ex-info
      (str (pr-str operation)
           " relationship update is not supported. Use :create, :touch or :delete.")
      {:type :eacl/unsupported-operation :eacl/error :eacl/unsupported-operation
       :operation operation})))
  true)

(defn- relationship-conflict!
  [relationship]
  (throw
   (ex-info
    ":create conflicts with an existing relationship. Use :touch for idempotent writes."
    {:type :eacl/relationship-conflict
     :eacl/error :eacl/relationship-conflict
     :relationship relationship})))

(defn create-relationship-at-commit
  "Commit-time precondition behind `:create`. It re-checks the relationship
  against the transaction-time database, so two writers that both planned a
  `:create` against the same pre-write value are serialized by the writer: the
  first commits and the second observes the winner and fails with
  `:eacl/relationship-conflict` (Datahike wraps the throw;
  `eacl.datahike.core` unwraps it).

  The planned adds remain adjacent to their update in the outer transaction;
  the shared writer runs every create precondition before those mutations so
  all updates in one batch retain the calculation-snapshot semantics shared
  with Datomic."
  [db resolved relationship]
  (if (relationship-exists? db resolved)
    (relationship-conflict! relationship)
    []))

(defn tx-update-relationship
  [db {:keys [operation relationship]}]
  (validate-relationship-operation! operation)
  (let [resolved (resolve-relationship db relationship)
        exists?  (relationship-exists? db resolved)
        tx-data
        (case operation
          :touch
          (when-not exists?
            (add-relationship-txes resolved))

          :create
          (cond
            exists?
            (relationship-conflict! relationship)

            ;; A remote writer receives serialized tx-data and cannot run a
            ;; transaction function, so it keeps the plan-time check only.
            (ddb/direct-writer? db)
            (into
             [[:db.fn/call create-relationship-at-commit
               resolved relationship]]
             (add-relationship-txes resolved))

            :else
            (add-relationship-txes resolved))

          :delete
          ;; Datahike ignores retraction of an absent datom. Retracting both
          ;; halves unconditionally makes a half-written pair repairable.
          (retract-relationship-txes resolved))]
    (when tx-data
      (guard-schema-write-fence db tx-data))))

(defn read-relationships
  ([db filters]
   (read-relationships db filters nil))
  ([db filters decision-kernel]
   (read-relationships db filters decision-kernel nil))
  ([db filters decision-kernel window-options]
  ;; The unified filter contract shared by every backend
  ;; (backend-unification 9.1); the raw Datahike path previously performed
  ;; no filter validation at all.
   (relationship-filters/validate! filters)
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
                 (drop-while
                  #(not
                    (relationship-engine/beyond-cursor?
                     (:scan-kind spec)
                     direction
                     (normalized-cursor cursor)
                     %))
                  rows))
               (exact-match-row [spec cursor direction]
                 (let [row (when (and (:subject-id spec) (:resource-id spec))
                             (when (direct-match?
                                    db
                                    (:subject-type spec)
                                    (:subject-id spec)
                                    (:relation-id spec)
                                    (:resource-type spec)
                                    (:resource-id spec))
                               (relationship-row spec (:subject-id spec) (:resource-id spec))))]
                   (if row
                     (drop-until-beyond-cursor
                      spec cursor direction [row])
                     [])))
               (scan-forward-anchored [spec cursor direction]
                 (if (:resource-id spec)
                   (exact-match-row spec cursor direction)
                   (->> (relationship-datoms-on-entity
                         db
                         (:subject-id spec)
                         relationship-storage/forward-attribute
                         [(:subject-type spec)
                          (:relation-id spec)
                          (:resource-type spec)]
                         (or (:resource-id cursor)
                             (:resource cursor))
                         direction)
                        (map (fn [{:keys [v]}]
                               (relationship-row
                                spec (:subject-id spec) (nth v 3))))
                        (drop-until-beyond-cursor
                         spec cursor direction))))
               (scan-reverse-anchored [spec cursor direction]
                 (if (:subject-id spec)
                   (exact-match-row spec cursor direction)
                   (->> (relationship-datoms-on-entity
                         db
                         (:resource-id spec)
                         relationship-storage/reverse-attribute
                         [(:resource-type spec)
                          (:relation-id spec)
                          (:subject-type spec)]
                         (or (:subject-id cursor)
                             (:subject cursor))
                         direction)
                        (map (fn [{:keys [v]}]
                               (relationship-row
                                spec (nth v 3) (:resource-id spec))))
                        (drop-until-beyond-cursor
                         spec cursor direction))))
               (scan-forward-partial [spec cursor direction]
                 (->> (ddb/avet-tuple-prefix
                       db
                       relationship-storage/forward-attribute
                       4
                       [(:subject-type spec)
                        (:relation-id spec)
                        (:resource-type spec)]
                       (or (:resource-id cursor)
                           (:resource cursor))
                       direction)
                      (map (fn [{:keys [e v]}]
                             (relationship-row spec e (nth v 3))))
                      (drop-until-beyond-cursor
                       spec cursor direction)))
               (scan-reverse-partial [spec cursor direction]
                 (->> (ddb/avet-tuple-prefix
                       db
                       relationship-storage/reverse-attribute
                       4
                       [(:resource-type spec)
                        (:relation-id spec)
                        (:subject-type spec)]
                       (or (:subject-id cursor)
                           (:subject cursor))
                       direction)
                      (map (fn [{:keys [e v]}]
                             (relationship-row spec (nth v 3) e)))
                      (drop-until-beyond-cursor
                       spec cursor direction)))
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
                (all-relation-defs db) filters')]
           (if window-options
             (relationship-engine/execute-filtered-window
              scan-specs filters' decision-kernel scan-spec window-options)
             (if-not (or (contains? filters' :limit)
                         (contains? filters' :cursor))
               (relationship-engine/execute-page
                scan-specs filters' decision-kernel scan-spec)
               (relationship-engine/execute-plan
                scan-specs filters' scan-spec)))))))))

;; A relationship is split across its endpoints. A bare retractEntity removes
;; only the half stored on that entity because Datahike does not follow refs
;; embedded in heterogeneous tuple values. The supported deletion path removes
;; both halves before the caller retracts the object entity.

(defn- relation-triples
  [db]
  (mapv (fn [{:keys [e v]}]
          [(nth v 0) e (nth v 2)])
        (ddb/avet-datoms db schema/relation-key-attr)))

(defn- relationship-pair-retractions
  [subject-type subject-id relation-id resource-type resource-id]
  [[:db/retract
    subject-id
    relationship-storage/forward-attribute
    [subject-type relation-id resource-type resource-id]]
   [:db/retract
    resource-id
    relationship-storage/reverse-attribute
    [resource-type relation-id subject-type subject-id]]])

(defn- relationship-op-relation-id
  [op]
  (when (and (vector? op)
             (contains? #{:db/add :db/retract} (first op))
             (contains? #{relationship-storage/forward-attribute
                          relationship-storage/reverse-attribute}
                        (nth op 2 nil))
             (vector? (nth op 3 nil)))
    (nth (nth op 3) 1 nil)))

(defn stamp-relation-versions
  "Adds one idempotent native generation stamp per affected relation."
  [tx-data]
  (let [ops (vec tx-data)
        relation-ids (into #{} (keep relationship-op-relation-id) ops)
        stamped (into #{}
                      (keep (fn [op]
                              (when (and (vector? op)
                                         (= :db/add (first op))
                                         (= :eacl/relation-version
                                            (nth op 2 nil)))
                                (nth op 1))))
                      ops)]
    (into ops
          (map tx-relation-version-stamp)
          (sort (remove stamped relation-ids)))))

(defn tx-delete-object
  "Returns transaction data removing both halves of every relationship touching
   `object-id`. The object entity itself is left intact.

   Peer-index probes also find surviving halves after a caller has already
   retracted the object entity and passes its raw numeric eid."
  [db object-id]
  (if-let [object-eid (internal-id db object-id)]
    (let [triples (relation-triples db)]
      (->>
       (concat
        (mapcat
         (fn [{:keys [v]}]
           (let [[subject-type relation-id
                  resource-type resource-id] v]
             (relationship-pair-retractions
              subject-type object-eid relation-id resource-type resource-id)))
         (ddb/eavt-datoms
          db object-eid relationship-storage/forward-attribute))

        (mapcat
         (fn [{:keys [v]}]
           (let [[resource-type relation-id
                  subject-type subject-id] v]
             (relationship-pair-retractions
              subject-type subject-id relation-id resource-type object-eid)))
         (ddb/eavt-datoms
          db object-eid relationship-storage/reverse-attribute))

        (mapcat
         (fn [[resource-type relation-id subject-type]]
           (mapcat
            (fn [{resource-id :e}]
              (relationship-pair-retractions
               subject-type object-eid relation-id resource-type resource-id))
            (ddb/avet-datoms
             db relationship-storage/reverse-attribute
             [resource-type relation-id subject-type object-eid])))
         triples)

        (mapcat
         (fn [[resource-type relation-id subject-type]]
           (mapcat
            (fn [{subject-id :e}]
              (relationship-pair-retractions
               subject-type subject-id relation-id resource-type object-eid))
            (ddb/avet-datoms
             db relationship-storage/forward-attribute
             [subject-type relation-id resource-type object-eid])))
         triples))
       distinct
       vec
       stamp-relation-versions
       (guard-schema-write-fence db)))
    []))

(defn affected-relation-ids
  "Every relation named by relationship tuple retraction ops."
  [tx-data]
  (->> tx-data
       (keep
        (fn [op]
          (when (and (vector? op)
                     (= :db/retract (first op))
                     (contains?
                      #{relationship-storage/forward-attribute
                        relationship-storage/reverse-attribute}
                      (nth op 2 nil)))
            (nth (nth op 3) 1))))
       distinct
       sort
       vec))

(defn orphaned-relationship-halves
  "Lazy offline scan of relationship halves whose matching peer half is absent."
  [db]
  (concat
   (for [{subject-id :e v :v}
         (ddb/avet-datoms db relationship-storage/forward-attribute)
         :let [[subject-type relation-id resource-type resource-id] v]
         :when
         (empty?
          (ddb/eavt-datoms
           db resource-id relationship-storage/reverse-attribute
           [resource-type relation-id subject-type subject-id]))]
     {:half :forward
      :e subject-id
      :attr relationship-storage/forward-attribute
      :v (vec v)
      :subject-eid subject-id
      :resource-eid resource-id
      :relation-eid relation-id})
   (for [{resource-id :e v :v}
         (ddb/avet-datoms db relationship-storage/reverse-attribute)
         :let [[resource-type relation-id subject-type subject-id] v]
         :when
         (empty?
          (ddb/eavt-datoms
           db subject-id relationship-storage/forward-attribute
           [subject-type relation-id resource-type resource-id]))]
     {:half :reverse
      :e resource-id
      :attr relationship-storage/reverse-attribute
      :v (vec v)
      :subject-eid subject-id
      :resource-eid resource-id
      :relation-eid relation-id})))
