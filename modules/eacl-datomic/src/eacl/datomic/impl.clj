(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
   [datomic.api :as d]
   [eacl.core :as eacl :refer [spice-object]]
   [eacl.datomic.backend :as backend]
   [eacl.datomic.impl.base :as base]
   [eacl.datomic.impl.indexed :as impl.indexed]
   [eacl.engine.relationships :as relationship-engine]
   [eacl.engine.v8 :as engine]
   [eacl.relationships.endpoint-pair :as endpoint-pair]
   [eacl.relationships.filters :as relationship-filters]
   [eacl.relationships.storage :as relationship-storage]))

(def Relation base/Relation)
(def Permission base/Permission)

(defn Relationship
  "Constructs relationship data for the public/internal API boundary.
  Persistence is handled by tuple-specific write helpers below."
  [subject relation resource]
  (eacl/->Relationship subject relation resource))

(defmacro ^:private with-request-engine
  "Builds ONE snapshot adapter for the call and binds the shared engine
  context: a caller-supplied impl.indexed schema cache wins; otherwise a
  request-local derived context scoped to this adapter's immutable
  snapshot (eliminating duplicate proof reads, path walks, and plan
  compiles inside one raw request without cross-request publication)."
  [[adapter-sym db] & body]
  `(let [~adapter-sym (backend/snapshot-adapter ~db)]
     (binding [engine/*schema-cache*
               (or impl.indexed/*schema-cache*
                   (engine/request-schema-cache ~adapter-sym))
               engine/*recursive-traversal-limits*
               impl.indexed/*recursive-traversal-limits*
               engine/*recursive-traversal-stats*
               impl.indexed/*recursive-traversal-stats*]
       ~@body)))

(defn can?
  ([db subject permission resource]
   (with-request-engine [adapter db]
     (engine/can? adapter subject permission resource)))
  ([db {:keys [subject permission resource]}]
   (can? db subject permission resource)))

(defn lookup-subjects
  ([db query]
   (lookup-subjects db query nil))
  ([db query lookup-opts]
   (with-request-engine [adapter db]
     (engine/lookup-subjects adapter query lookup-opts))))

(defn lookup-resources
  ([db query]
   (lookup-resources db query nil))
  ([db query lookup-opts]
   (with-request-engine [adapter db]
     (engine/lookup-resources adapter query lookup-opts))))

(defn count-resources
  [db query]
  (with-request-engine [adapter db]
    (engine/count-resources adapter query)))

(defn count-subjects
  [db query]
  (with-request-engine [adapter db]
    (engine/count-subjects adapter query)))

(def ^:private relation-version-attr :eacl/relation-version)
(def ^:private schema-version-attr :eacl/schema-version)

(defn tx-schema-version-guard
  "Commit-time assertion that relationship tx-data is applied under the same
  schema generation it was resolved against.

  This prevents a delayed relationship transaction from resurrecting a
  relation entity after a concurrent write-schema! removed it."
  [db]
  (when-let [version (impl.indexed/schema-version db)]
    [:db.fn/cas [:eacl/id "schema-string"]
     schema-version-attr version version]))

(defn guard-schema-version
  "Appends one schema-generation CAS guard to `ops`, when the db is stamped."
  [db ops]
  (let [ops (vec ops)]
    (if-let [guard (tx-schema-version-guard db)]
      (if (some #(= guard %) ops)
        ops
        (conj ops guard))
      ops)))

(defn tx-relation-version-stamp
  "Stamps the relation with the transaction that is changing it.

  This is how a writer publishes WHAT changed rather than merely THAT
  something changed: the stamp lands atomically with the relationship datoms,
  so no db value can show one without the other. Readers take the max stamp
  over the relations a permission depends on, so churn on an unrelated relation
  leaves cached answers alone.

  The value is the transaction entity rather than a fresh id, which makes the
  assertion idempotent — the same [e a v] however many times it is emitted in
  one transaction. That is what lets every relationship-producing helper append
  its own stamp unconditionally, and lets callers concat several helpers'
  output into a single transaction without provoking :db.error/datoms-conflict."
  [relation-eid]
  [:db/add relation-eid relation-version-attr "datomic.tx"])

(defn can!
  "Like can?, but throws :eacl/unauthorized instead of returning false."
  [db subject permission resource]
  (if (can? db subject permission resource)
    true
    (throw (ex-info "Unauthorized"
             {:type :eacl/unauthorized
              :subject subject
              :permission permission
              :resource resource}))))

(defn- unknown-object!
  [object-id]
  (throw (ex-info (str "Unknown object: " (pr-str object-id) " does not resolve to an existing entity."
                       " Pass {:allow-tempids? true} to tx-relationship for same-transaction tempids.")
           {:type :eacl/unknown-object
            :object-id object-id})))

(defn- object-id->eid-or-tempid
  "Resolves an object id to an existing eid. Unresolvable ids throw
  :eacl/unknown-object unless :allow-tempids? is set, in which case strings,
  negative longs and datomic.db.DbId values pass through as tempids for
  same-transaction entity+relationship creation. Silent tempid pass-through
  minted ghost entities on typo'd ids (audit §12). Positive numeric eids are
  verified via datom presence — the transactor rejects unallocated eids anyway,
  but with a raw :db.error/invalid-entity-id."
  [db object-id {:keys [allow-tempids?]}]
  (cond
    (number? object-id)
    (cond
      (seq (d/datoms db :eavt object-id)) object-id
      (and allow-tempids? (neg? object-id)) object-id
      :else (unknown-object! object-id))

    (string? object-id)
    (or (d/entid db [:eacl/id object-id])
        (if allow-tempids?
          object-id
          (unknown-object! object-id)))

    (instance? datomic.db.DbId object-id)
    (if allow-tempids?
      object-id
      (unknown-object! object-id))

    :else
    (or (d/entid db object-id)
        (unknown-object! object-id))))

(defn- find-relation-eid
  [db resource-type relation-name subject-type]
  (d/q '[:find ?relation .
         :in $ ?resource-type ?relation-name ?subject-type
         :where
         [?relation :eacl.relation/resource-type ?resource-type]
         [?relation :eacl.relation/relation-name ?relation-name]
         [?relation :eacl.relation/subject-type ?subject-type]]
       db resource-type relation-name subject-type))

(defn- endpoint-identity-guard
  [db role eid object]
  (when (and (number? eid) (not (neg? eid)))
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
          {:type :eacl/endpoint-identity-unavailable
           :role role
           :endpoint-eid eid
           :object object})))
      (let [[attribute value] candidate]
        [:db.fn/cas eid attribute value value]))))

(defn- resolve-relationship
  [db {:keys [subject relation resource]} opts]
  (let [subject-type (:type subject)
        subject-eid  (object-id->eid-or-tempid db (:id subject) opts)
        resource-type (:type resource)
        resource-eid  (object-id->eid-or-tempid db (:id resource) opts)
        relation-eid  (find-relation-eid db resource-type relation subject-type)]
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
     :subject-eid subject-eid
     :subject-identity-guard
     (endpoint-identity-guard db :subject subject-eid subject)
     :relation relation
     :relation-eid relation-eid
     :resource resource
     :resource-type resource-type
     :resource-eid resource-eid
     :resource-identity-guard
     (endpoint-identity-guard db :resource resource-eid resource)}))

(defn- relationship-tuple
  [{:keys [subject-type relation-eid resource-type resource-eid]}]
  (endpoint-pair/forward-value
   subject-type relation-eid resource-type resource-eid))

(defn- reverse-relationship-tuple
  [{:keys [resource-type relation-eid subject-type subject-eid]}]
  (endpoint-pair/reverse-value
   resource-type relation-eid subject-type subject-eid))

(defn- add-relationship-txes
  [resolved]
  (into []
        (remove nil?)
        [(:subject-identity-guard resolved)
         (:resource-identity-guard resolved)
         [:db/add (:subject-eid resolved)
          relationship-storage/forward-attribute
          (relationship-tuple resolved)]
         [:db/add (:resource-eid resolved)
          relationship-storage/reverse-attribute
          (reverse-relationship-tuple resolved)]
         (tx-relation-version-stamp (:relation-eid resolved))]))

(defn- retract-relationship-txes
  [resolved]
  [[:db/retract (:subject-eid resolved)
    relationship-storage/forward-attribute
    (relationship-tuple resolved)]
   [:db/retract (:resource-eid resolved)
    relationship-storage/reverse-attribute
    (reverse-relationship-tuple resolved)]
   (tx-relation-version-stamp (:relation-eid resolved))])

(defn- forward-tuple-exists?
  [db {:keys [subject-eid] :as resolved}]
  (boolean (seq (d/datoms db :eavt subject-eid relationship-storage/forward-attribute
                          (relationship-tuple resolved)))))

(defn- reverse-tuple-exists?
  [db {:keys [resource-eid] :as resolved}]
  (boolean (seq (d/datoms db :eavt resource-eid relationship-storage/reverse-attribute
                          (reverse-relationship-tuple resolved)))))

(defn- relationship-exists?
  "True only when BOTH halves of the relationship are present.

  Checking the forward index alone made a half-written pair unrepairable:
  :touch saw 'already there' and :delete saw 'nothing to do', so the surviving
  half kept answering lookups forever. A half-pair now reads as absent, which
  lets :touch re-assert it and :delete retract it."
  [db {:keys [subject-eid resource-eid] :as resolved}]
  (and (number? subject-eid)
       (number? resource-eid)
       (forward-tuple-exists? db resolved)
       (reverse-tuple-exists? db resolved)))

(defn find-one-relationship-id
  "Returns the resolved tuple identity for an existing relationship, or nil.
  A read: unresolvable endpoints mean no such relationship can exist -> nil."
  [db relationship]
  (let [resolved (try
                   (resolve-relationship db relationship {})
                   (catch clojure.lang.ExceptionInfo e
                     (when-not (= :eacl/unknown-object (:type (ex-data e)))
                       (throw e))))]
    (when (and resolved (relationship-exists? db resolved))
      resolved)))

(defn- find-relations
  [db filters]
  (let [resource-type     (:resource/type filters)
        resource-relation (:resource/relation filters)
        subject-type      (:subject/type filters)]
    (->> (d/q '[:find [(pull ?relation [:db/id
                                        :eacl.relation/resource-type
                                        :eacl.relation/relation-name
                                        :eacl.relation/subject-type]) ...]
                :where
                [?relation :eacl.relation/relation-name ?relation-name]]
              db)
         (filter (fn [relation]
                   (and (or (nil? resource-type)
                            (= resource-type (:eacl.relation/resource-type relation)))
                        (or (nil? resource-relation)
                            (= resource-relation (:eacl.relation/relation-name relation)))
                        (or (nil? subject-type)
                            (= subject-type (:eacl.relation/subject-type relation)))))))))

(defn relationship-relation-ids
  "The complete schema relation-id scope that can match a relationship read."
  [db filters]
  (->> (find-relations db filters)
       (map :db/id)
       sort
       vec))

(defn- relation-def
  [relation]
  {:relation-id (:db/id relation)
   :resource-type (:eacl.relation/resource-type relation)
   :relation-name (:eacl.relation/relation-name relation)
   :subject-type (:eacl.relation/subject-type relation)})

(defn- endpoint-datoms
  [db endpoint attr prefix cursor-eid direction]
  (let [attr-eid (d/entid db attr)
        bound (conj prefix
                    (or cursor-eid
                        (case direction
                          :asc 0
                          :desc Long/MAX_VALUE)))
        datoms ((case direction
                  :asc d/seek-datoms
                  :desc d/rseek-datoms)
                db :eavt endpoint attr-eid bound)]
    (take-while
     (fn [{:keys [e a v]}]
       (and (== endpoint e)
            (== attr-eid a)
            (endpoint-pair/value-prefix? v prefix)))
     datoms)))

(defn- global-endpoint-datoms
  [db attr prefix cursor-eid cursor-endpoint direction]
  (let [attr-eid (d/entid db attr)
        bound (conj prefix
                    (or cursor-eid
                        (case direction
                          :asc 0
                          :desc Long/MAX_VALUE)))
        components (cond-> [attr-eid bound]
                     cursor-endpoint (conj cursor-endpoint))
        datoms (apply (case direction
                        :asc d/seek-datoms
                        :desc d/rseek-datoms)
                      db :avet components)]
    (take-while
     (fn [{:keys [a v]}]
       (and (== attr-eid a)
            (endpoint-pair/value-prefix? v prefix)))
     datoms)))

(defn read-relationships
  ([db filters]
   (read-relationships db filters nil))
  ([db filters decision-kernel]
   ;; The unified filter contract shared by every backend
   ;; (backend-unification 9.1). Value-presence anchor semantics: a
   ;; present-but-nil anchor throws instead of widening the read.
   (relationship-filters/validate! filters)
   (let [relations (find-relations db filters)
         subject-id (:subject/id filters)
         resource-id (:resource/id filters)
         ;; object-eid, not d/entid: a raw-impl caller passing a string id got a
         ;; bare :db.error/not-a-keyword out of Datomic.
         subject-eid (when (some? subject-id)
                       (impl.indexed/object-eid db subject-id))
         resource-eid (when (some? resource-id)
                        (impl.indexed/object-eid db resource-id))
         normalized-filters (cond-> filters
                              subject-eid (assoc :subject/id subject-eid)
                              resource-eid (assoc :resource/id resource-eid))]
     (cond
       (and subject-id (nil? subject-eid))
       (throw (ex-info "read-relationships is missing a valid :subject/id."
                       {:subject/id subject-id}))

       (and resource-id (nil? resource-eid))
       (throw (ex-info "read-relationships is missing a valid :resource/id."
                       {:resource/id resource-id}))

       :else
       (letfn [(relationship-row [spec subject-id resource-id]
                 {:spec-idx (:idx spec)
                  :subject-id subject-id
                  :resource-id resource-id
                  :relationship
                  (eacl/->Relationship
                   (spice-object (:subject-type spec) subject-id)
                   (:relation-name spec)
                   (spice-object (:resource-type spec) resource-id))})
               (drop-until-beyond-cursor [spec cursor direction rows]
                 (drop-while
                  #(not
                    (relationship-engine/beyond-cursor?
                     (:scan-kind spec) direction cursor %))
                  rows))
               (exact-match-row [spec cursor direction]
                 (let [row
                       (when (and (:subject-id spec) (:resource-id spec))
                         (when
                          (seq
                           (d/datoms
                            db :eavt
                            (:subject-id spec)
                            relationship-storage/forward-attribute
                            (endpoint-pair/forward-value
                             (:subject-type spec)
                             (:relation-id spec)
                             (:resource-type spec)
                             (:resource-id spec))))
                           (relationship-row
                            spec (:subject-id spec) (:resource-id spec))))]
                   (if row
                     (drop-until-beyond-cursor
                      spec cursor direction [row])
                     [])))
               (scan-forward-anchored [spec cursor direction]
                 (if (:resource-id spec)
                   (exact-match-row spec cursor direction)
                   (->> (endpoint-datoms
                         db
                         (:subject-id spec)
                         relationship-storage/forward-attribute
                         [(:subject-type spec)
                          (:relation-id spec)
                          (:resource-type spec)]
                         (:resource-id cursor)
                         direction)
                        (map
                         (fn [{:keys [v]}]
                           (relationship-row
                            spec (:subject-id spec) (nth v 3))))
                        (drop-until-beyond-cursor
                         spec cursor direction))))
               (scan-reverse-anchored [spec cursor direction]
                 (if (:subject-id spec)
                   (exact-match-row spec cursor direction)
                   (->> (endpoint-datoms
                         db
                         (:resource-id spec)
                         relationship-storage/reverse-attribute
                         [(:resource-type spec)
                          (:relation-id spec)
                          (:subject-type spec)]
                         (:subject-id cursor)
                         direction)
                        (map
                         (fn [{:keys [v]}]
                           (relationship-row
                            spec (nth v 3) (:resource-id spec))))
                        (drop-until-beyond-cursor
                         spec cursor direction))))
               (scan-forward-partial [spec cursor direction]
                 (->> (global-endpoint-datoms
                       db
                       relationship-storage/forward-attribute
                       [(:subject-type spec)
                        (:relation-id spec)
                        (:resource-type spec)]
                       (:resource-id cursor)
                       (:subject-id cursor)
                       direction)
                      (map
                       (fn [{:keys [e v]}]
                         (relationship-row spec e (nth v 3))))
                      (drop-until-beyond-cursor
                       spec cursor direction)))
               (scan-reverse-partial [spec cursor direction]
                 (->> (global-endpoint-datoms
                       db
                       relationship-storage/reverse-attribute
                       [(:resource-type spec)
                        (:relation-id spec)
                        (:subject-type spec)]
                       (:subject-id cursor)
                       (:resource-id cursor)
                       direction)
                      (map
                       (fn [{:keys [e v]}]
                         (relationship-row spec (nth v 3) e)))
                      (drop-until-beyond-cursor
                       spec cursor direction)))
               (scan-spec [spec cursor direction]
                 (case (:scan-kind spec)
                   :forward-anchored
                   (scan-forward-anchored spec cursor direction)

                   :reverse-anchored
                   (scan-reverse-anchored spec cursor direction)

                   :forward-partial
                   (scan-forward-partial spec cursor direction)

                   (scan-reverse-partial spec cursor direction)))]
         (relationship-engine/execute-page
          (relationship-engine/plan-scans
           (mapv relation-def relations)
           normalized-filters)
          normalized-filters
          decision-kernel
          scan-spec))))))

;; --- Object deletion --------------------------------------------------------
;;
;; A v7 relationship is two datoms living on two DIFFERENT entities, each
;; naming its peer inside a tuple VALUE:
;;
;;   [subject-eid  <forward-attr> [subject-type relation-eid resource-type resource-eid]]
;;   [resource-eid <reverse-attr> [resource-type relation-eid subject-type subject-eid]]
;;
;; :db.fn/retractEntity follows :db.type/ref ATTRIBUTES. It does not follow
;; ref-typed components of a heterogeneous tuple, and a heterogeneous tuple
;; cannot be :db/isComponent. So retracting a permissioned entity the ordinary
;; Datomic way removes only the half stored ON that entity and leaves the peer's
;; half behind, where it keeps answering queries:
;;
;;   - delete a RESOURCE  -> the subject keeps its forward tuple, so can? still
;;                           answers true and lookup-resources still lists it;
;;   - delete a SUBJECT   -> the resource keeps its reverse tuple, so
;;                           lookup-subjects still lists the deleted subject
;;                           while can? answers false — the two APIs disagree.
;;
;; Worse, the survivor is unreachable through write-relationships!, because
;; resolving either endpoint of the relationship now throws :eacl/unknown-object.
;;
;; EACL consumers are expected to delete relationships before retracting an
;; entity. tx-delete-object and the client's delete-object! are convenience
;; helpers for that workflow. A bare retractEntity remains valid Datomic, but
;; callers should run the explicit integrity audit if it might have left a
;; surviving peer half.

(defn- relation-triples
  "[resource-type relation-eid subject-type] for every Relation in the schema.
  Bounded by schema size, never by relationship count."
  [db]
  (mapv (fn [datom]
          (let [[resource-type _relation-name subject-type] (:v datom)]
            [resource-type (:e datom) subject-type]))
        (d/datoms db :aevt :eacl.relation/resource-type+relation-name+subject-type)))

(defn- relationship-pair-retractions
  "Both halves of one relationship, as retraction ops."
  [subject-type subject-eid relation-eid resource-type resource-eid]
  [[:db/retract subject-eid relationship-storage/forward-attribute
    [subject-type relation-eid resource-type resource-eid]]
   [:db/retract resource-eid relationship-storage/reverse-attribute
    [resource-type relation-eid subject-type subject-eid]]])

(defn- op-attr
  "The attribute of a list-form tx op, or nil for a map form or anything else.
  Map forms cannot express a relationship tuple retraction, so skipping them is
  correct rather than merely defensive."
  [op]
  (when (and (vector? op) (<= 3 (count op)))
    (nth op 2)))

(defn- relation-eid-of-retraction
  "The relation eid named by a relationship retraction op, or nil for any other
  op. Both tuple attributes carry the relation eid at position 1."
  [op]
  (let [attr (op-attr op)]
    (when (or (identical? attr relationship-storage/forward-attribute)
              (identical? attr relationship-storage/reverse-attribute))
      (let [v (nth op 3 nil)]
        (when (and (vector? v) (<= 2 (count v)))
          (nth v 1))))))

(defn stamp-relation-versions
  "Ensures `ops` carries a version stamp for every relation it retracts.

  tx-delete-object deduplicates its output, which keeps only the first stamp
  per relation. That is correct for a single transaction and WRONG for a
  batched one: a batch holding the second half of a relation's retractions
  would change relationship data while publishing nothing, and a reader would
  keep serving a cached answer that the retraction had already invalidated.

  Idempotent — stamping an already-stamped batch adds nothing, because the
  stamp is the same [e a v] triple either way."
  [ops]
  (let [ops (vec ops)
        stamped (into #{}
                      (keep (fn [op]
                              (when (identical? relation-version-attr
                                                (op-attr op))
                                (nth op 1))))
                      ops)
        missing (into #{}
                      (comp (keep relation-eid-of-retraction)
                            (remove stamped))
                      ops)]
    (if (seq missing)
      (into ops (map tx-relation-version-stamp) missing)
      ops)))

(defn- schema-version-guard?
  [op]
  (and (vector? op)
       (= :db.fn/cas (first op))
       (= schema-version-attr (nth op 2 nil))))

(defn- relation-version-stamp?
  [op]
  (and (vector? op)
       (= :db/add (first op))
       (= relation-version-attr (nth op 2 nil))))

(defn optimistic-relationship-tx-data
  "Turns ordinary idempotent relation stamps into commit-time CAS stamps.

  Public relationship writes use this to serialize competing mutations of the
  same relation. A CAS loser rebuilds from a fresh db: duplicate :create then
  observes the winner and throws :eacl/relationship-conflict, while unrelated
  writes simply retry. The schema guard is deduplicated at the same boundary."
  [db ops]
  (let [ops (vec ops)
        relation-eids (into #{} (comp (filter relation-version-stamp?)
                                      (map second))
                            ops)
        ordinary-ops (into []
                           (remove #(or (relation-version-stamp? %)
                                        (schema-version-guard? %)))
                           ops)
        relation-cases
        (mapv
         (fn [relation-eid]
           (when-not (:eacl.relation/relation-name
                      (d/entity db relation-eid))
             (throw
              (ex-info
               "A relationship transaction names a relation removed by a concurrent schema write."
               {:type :eacl/schema-changed
                :relation-eid relation-eid})))
           (let [current (some-> ^datomic.Datom
                                 (first (d/datoms db :eavt relation-eid
                                                  relation-version-attr))
                                 (.v))]
             [:db.fn/cas relation-eid relation-version-attr
              current "datomic.tx"]))
         (sort relation-eids))]
    (into (guard-schema-version db ordinary-ops) relation-cases)))

(defn tx-delete-object-stream
  "Lazy retraction ops removing every EACL relationship touching `object-id`.

  `object-id` is resolved the same way reads resolve object ids (string ->
  [:eacl/id ...], anything else -> d/entid), so it also accepts the raw eid of
  an entity already retracted the bare Datomic way. Returns an empty sequence
  for an id that does not resolve.

  Healthy relationships are emitted from the peer halves that NAME this
  object. The object's own halves are emitted only when their peer is absent,
  preserving cleanup of corrupt/orphan data without emitting every healthy
  relationship twice. A self-relationship is emitted once from its forward
  half. The resulting stream therefore needs no whole-result `distinct` set.

  This low-level stream intentionally contains only tuple retractions. Every
  transaction-sized slice MUST pass through `stamp-relation-versions`; the
  public delete-object! does this automatically. Keeping stamps batch-local is
  what makes discovery and heap use bounded by the batch size."
  [db object-id]
  (if-let [eid (impl.indexed/object-eid db object-id)]
    (let [triples (relation-triples db)]
      (concat
       ;; Orphaned forward halves, plus the canonical copy of a self-edge.
       (mapcat
        (fn [datom]
          (let [[subject-type relation-eid resource-type resource-eid] (:v datom)
                reverse-value [resource-type relation-eid subject-type eid]]
            (when (or (= eid resource-eid)
                      (empty? (d/datoms db :eavt resource-eid
                                        relationship-storage/reverse-attribute
                                        reverse-value)))
              (relationship-pair-retractions subject-type eid relation-eid
                                             resource-type resource-eid))))
        (d/datoms db :eavt eid relationship-storage/forward-attribute))

       ;; Orphaned reverse halves. Healthy self-edges were emitted above.
       (mapcat
        (fn [datom]
          (let [[resource-type relation-eid subject-type subject-eid] (:v datom)
                forward-value [subject-type relation-eid resource-type eid]]
            (when (empty? (d/datoms db :eavt subject-eid
                                    relationship-storage/forward-attribute
                                    forward-value))
              (relationship-pair-retractions subject-type subject-eid
                                             relation-eid resource-type eid))))
        (d/datoms db :eavt eid relationship-storage/reverse-attribute))

       ;; Peer halves naming this object as the SUBJECT.
       (mapcat
        (fn [[resource-type relation-eid subject-type]]
          (mapcat
           (fn [datom]
             ;; Self-edges are canonicalized to the own-forward scan above.
             (when (not= eid (:e datom))
               (relationship-pair-retractions subject-type eid relation-eid
                                              resource-type (:e datom))))
           (d/datoms db :avet relationship-storage/reverse-attribute
                     [resource-type relation-eid subject-type eid])))
        triples)

       ;; Peer halves naming this object as the RESOURCE.
       (mapcat
        (fn [[resource-type relation-eid subject-type]]
          (mapcat
           (fn [datom]
             (when (not= eid (:e datom))
               (relationship-pair-retractions subject-type (:e datom)
                                              relation-eid resource-type eid)))
           (d/datoms db :avet relationship-storage/forward-attribute
                     [subject-type relation-eid resource-type eid])))
        triples)))
    ()))

(defn tx-delete-object
  "Materialized transaction data removing every EACL relationship touching
  `object-id`, in both directions, without retracting the object itself.

  This compatibility helper returns one vector suitable for ONE transaction.
  `delete-object!` uses `tx-delete-object-stream` instead, partitions it before
  realization, and stamps every batch, so a high-degree object does not require
  retaining its complete retraction vector in heap.

  Large results are transacted in batches by delete-object!, so use
  `stamp-relation-versions` on any slice of this output before transacting it
  separately — the deduplication below keeps only the FIRST stamp for each
  relation, which would otherwise leave later batches retracting relationships
  without publishing that they changed."
  [db object-id]
  (->> (tx-delete-object-stream db object-id)
       distinct
       vec
       stamp-relation-versions
       (guard-schema-version db)))

(defn orphaned-relationship-halves
  "Lazy seq of relationship halves whose peer half is absent — the residue of
  entities retracted without tx-delete-object.

  Scans both relationship indexes and probes for each peer, so this is an
  offline maintenance operation, O(number of relationships). Pass a plain db
  value (not history/filter)."
  [db]
  (concat
   (for [datom (d/datoms db :aevt relationship-storage/forward-attribute)
         :let  [subject-eid (:e datom)
                [subject-type relation-eid resource-type resource-eid] (:v datom)]
         :when (empty? (d/datoms db :eavt resource-eid relationship-storage/reverse-attribute
                                 [resource-type relation-eid subject-type subject-eid]))]
     {:half          :forward
      :e             subject-eid
      :attr          relationship-storage/forward-attribute
      :v             (vec (:v datom))
      :subject-eid   subject-eid
      :resource-eid  resource-eid
      :relation-eid  relation-eid})
   (for [datom (d/datoms db :aevt relationship-storage/reverse-attribute)
         :let  [resource-eid (:e datom)
                [resource-type relation-eid subject-type subject-eid] (:v datom)]
         :when (empty? (d/datoms db :eavt subject-eid relationship-storage/forward-attribute
                                 [subject-type relation-eid resource-type resource-eid]))]
     {:half          :reverse
      :e             resource-eid
      :attr          relationship-storage/reverse-attribute
      :v             (vec (:v datom))
      :subject-eid   subject-eid
      :resource-eid  resource-eid
      :relation-eid  relation-eid})))

(defn tx-retract-orphaned-relationships
  "Retraction tx-data for orphaned-relationship-halves. Fails closed: an
  orphan means one endpoint is gone, so the survivor should stop granting.
  Returns a lazy sequence; transact in batches on large databases.

  Stays lazy: the relation stamps are emitted inline rather than deduplicated
  up front, which is safe because they are idempotent within a transaction."
  [db]
  (mapcat (fn [{:keys [e attr v relation-eid]}]
            [[:db/retract e attr v]
             (tx-relation-version-stamp relation-eid)])
          (orphaned-relationship-halves db)))

(defn tx-relationship
  "Translate relationship data into v7 tuple writes.

  Strict by default: endpoints must resolve to existing entities or this
  throws :eacl/unknown-object. Pass {:allow-tempids? true} to let unresolvable
  string ids / tempids pass through for same-transaction entity+relationship
  creation (fixtures-style)."
  ([db subject relation resource]
   (tx-relationship db (eacl/->Relationship subject relation resource) {}))
  ([db relationship]
   (tx-relationship db relationship {}))
  ([db relationship opts]
   (guard-schema-version
    db
    (add-relationship-txes (resolve-relationship db relationship opts)))))

(def ^:private supported-relationship-operations
  #{:create :touch :delete})

(defn- unsupported-relationship-operation!
  [operation]
  (throw
   (ex-info
    (str (pr-str operation)
         " relationship update is not supported. Use :create, :touch or :delete.")
    {:type :eacl/unsupported-operation
     :operation operation})))

(defn validate-relationship-operation!
  "Validates an update operation before any relationship endpoint work."
  [operation]
  (when-not (contains? supported-relationship-operations operation)
    (unsupported-relationship-operation! operation))
  true)

(defn tx-update-relationship
  "Relationship writes are implemented against v7 forward/reverse tuple indexes.
  :touch is idempotent. Endpoints must resolve to existing entities."
  [db {:keys [operation relationship]}]
  (validate-relationship-operation! operation)
  (let [resolved (resolve-relationship db relationship {})
        exists?  (relationship-exists? db resolved)
        ops
        (case operation
          :touch
          (when-not exists?
            (add-relationship-txes resolved))

          :create
          (if exists?
            (throw (ex-info ":create conflicts with an existing relationship. Use :touch for idempotent writes."
                            {:type :eacl/relationship-conflict
                             :relationship relationship}))
            (add-relationship-txes resolved))

          ;; Unconditional: Datomic ignores retraction of an absent datom, and
          ;; skipping on a not-exists? check left a surviving half-pair in place.
          :delete
          (retract-relationship-txes resolved))]
    (when ops
      (guard-schema-version db ops))))
