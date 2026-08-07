(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
   [datomic.api :as d]
   [eacl.core :as eacl :refer [spice-object]]
   [eacl.datomic.backend :as backend]
   [eacl.datomic.impl.base :as base]
   [eacl.datomic.impl.indexed :as impl.indexed]
   [eacl.engine.v8 :as engine]
   [eacl.relationships.endpoint-pair :as endpoint-pair]
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
               impl.indexed/*recursive-traversal-stats*
               engine/*count-stats* impl.indexed/*count-stats*]
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
        {:resource/type resource-type
         :relation/name relation
         :subject/type subject-type})))
    {:subject subject
     :subject-type subject-type
     :subject-eid subject-eid
     :relation relation
     :relation-eid relation-eid
     :resource resource
     :resource-type resource-type
     :resource-eid resource-eid}))

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
  [[:db/add (:subject-eid resolved)
    relationship-storage/forward-attribute
    (relationship-tuple resolved)]
   [:db/add (:resource-eid resolved)
    relationship-storage/reverse-attribute
    (reverse-relationship-tuple resolved)]
   (tx-relation-version-stamp (:relation-eid resolved))])

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

(defn relationship-relation-id
  [db relationship]
  (:relation-eid (resolve-relationship db relationship {})))

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

(defn- decode-forward-datom
  [db relation-by-eid datom]
  (let [subject-eid (:e datom)
        [subject-type relation-eid resource-type resource-eid] (:v datom)
        relation-name (or (get relation-by-eid relation-eid)
                          (:eacl.relation/relation-name (d/entity db relation-eid)))]
    (eacl/->Relationship
     (spice-object subject-type subject-eid)
     relation-name
     (spice-object resource-type resource-eid))))

(defn- decode-reverse-datom
  [db relation-by-eid datom]
  (let [resource-eid (:e datom)
        [resource-type relation-eid subject-type subject-eid] (:v datom)
        relation-name (or (get relation-by-eid relation-eid)
                          (:eacl.relation/relation-name (d/entity db relation-eid)))]
    (eacl/->Relationship
     (spice-object subject-type subject-eid)
     relation-name
     (spice-object resource-type resource-eid))))

(defn- relationship-matches-filters?
  [filters {:keys [subject relation resource]}]
  (and (or (nil? (:subject/type filters))
           (= (:subject/type filters) (:type subject)))
       (or (nil? (:subject/id filters))
           (= (:subject/id filters) (:id subject)))
       (or (nil? (:resource/type filters))
           (= (:resource/type filters) (:type resource)))
       (or (nil? (:resource/id filters))
           (= (:resource/id filters) (:id resource)))
       (or (nil? (:resource/relation filters))
           (= (:resource/relation filters) relation))))

(defn- single-relation-hint
  [relations]
  (when (= 1 (count relations))
    (let [relation (first relations)]
      {:relation-eid (:db/id relation)
       :subject-type (:eacl.relation/subject-type relation)
       :resource-type (:eacl.relation/resource-type relation)})))

(defn- forward-tuple-prefix
  [filters relation-hint]
  (let [subject-type (or (:subject/type filters) (:subject-type relation-hint))
        relation-eid (:relation-eid relation-hint)
        resource-type (or (:resource/type filters) (:resource-type relation-hint))]
    (cond-> []
      subject-type (conj subject-type)
      (and subject-type relation-eid) (conj relation-eid)
      (and subject-type relation-eid resource-type) (conj resource-type))))

(defn- reverse-tuple-prefix
  [filters relation-hint]
  (let [resource-type (or (:resource/type filters) (:resource-type relation-hint))
        relation-eid (:relation-eid relation-hint)
        subject-type (or (:subject/type filters) (:subject-type relation-hint))]
    (cond-> []
      resource-type (conj resource-type)
      (and resource-type relation-eid) (conj relation-eid)
      (and resource-type relation-eid subject-type) (conj subject-type))))

(defn- scan-plan
  [db filters subject-eid resource-eid relations]
  (let [relation-hint (single-relation-hint relations)]
    (cond
      subject-eid
      {:key :subject-forward
       :index :eavt
       :attr-eid (d/entid db relationship-storage/forward-attribute)
       :fixed-eid subject-eid
       :tuple-prefix (not-empty (forward-tuple-prefix filters relation-hint))
       :decode decode-forward-datom}

      resource-eid
      {:key :resource-reverse
       :index :eavt
       :attr-eid (d/entid db relationship-storage/reverse-attribute)
       :fixed-eid resource-eid
       :tuple-prefix (not-empty (reverse-tuple-prefix filters relation-hint))
       :decode decode-reverse-datom}

      (seq (forward-tuple-prefix filters relation-hint))
      {:key :global-forward
       :index :avet
       :attr-eid (d/entid db relationship-storage/forward-attribute)
       :tuple-prefix (forward-tuple-prefix filters relation-hint)
       :decode decode-forward-datom}

      :else
      {:key :global-reverse
       :index :avet
       :attr-eid (d/entid db relationship-storage/reverse-attribute)
       :tuple-prefix (not-empty (reverse-tuple-prefix filters relation-hint))
       :decode decode-reverse-datom})))

(defn- relationship-edge
  [scan-key datom]
  {:kind :relationship
   :scan scan-key
   :e (:e datom)
   :v (vec (:v datom))})

(defn- same-edge-datom?
  [edge datom]
  (and (= (:e edge) (:e datom))
       (= (:v edge) (vec (:v datom)))))

(defn- tuple-prefix?
  [prefix tuple]
  (or (empty? prefix)
      (= prefix (subvec (vec tuple) 0 (count prefix)))))

(defn- reverse-start-tuple
  [prefix]
  (if (seq prefix)
    (conj (vec prefix) Long/MAX_VALUE)
    nil))

(defn- unbounded-scan-components
  [{:keys [index attr-eid fixed-eid tuple-prefix]} direction]
  (case index
    :eavt
    (cond-> [fixed-eid attr-eid]
      (and (= direction :asc) (seq tuple-prefix)) (conj (vec tuple-prefix))
      (and (= direction :desc) (seq tuple-prefix)) (conj (reverse-start-tuple tuple-prefix)))

    :avet
    (cond-> [attr-eid]
      (and (= direction :asc) (seq tuple-prefix)) (conj (vec tuple-prefix))
      (and (= direction :desc) (seq tuple-prefix)) (conj (reverse-start-tuple tuple-prefix)))))

(defn- bound-scan-components
  [{:keys [index attr-eid fixed-eid]} bound]
  (case index
    :eavt [fixed-eid attr-eid (:v bound)]
    :avet [attr-eid (:v bound) (:e bound)]))

(defn- scan-components
  [plan direction bound]
  (if bound
    (bound-scan-components plan bound)
    (unbounded-scan-components plan direction)))

(defn- validate-relationship-bound!
  [{:keys [key fixed-eid]} bound]
  (when bound
    (when-not (= :relationship (:kind bound))
      (throw (ex-info "Relationship page cursor has the wrong kind."
                      {:kind (:kind bound)})))
    (when-not (= key (:scan bound))
      (throw (ex-info "Relationship page cursor does not match the selected scan."
                      {:expected key
                       :actual (:scan bound)})))
    (when (and fixed-eid (not= fixed-eid (:e bound)))
      (throw (ex-info "Relationship page cursor does not match the selected anchor."
                      {:expected fixed-eid
                       :actual (:e bound)})))))

(defn- matching-index-datom?
  [{:keys [index attr-eid fixed-eid tuple-prefix]} datom]
  (and (== attr-eid (:a datom))
       (case index
         :eavt (== fixed-eid (:e datom))
         :avet true)
       (tuple-prefix? tuple-prefix (:v datom))))

(defn- relationship-datoms
  [db plan direction bound]
  (let [components (scan-components plan direction bound)
        datoms (case direction
                 :asc (apply d/seek-datoms db (:index plan) components)
                 :desc (apply d/rseek-datoms db (:index plan) components))]
    (->> datoms
         (take-while #(matching-index-datom? plan %))
         (drop-while #(and bound (same-edge-datom? bound %))))))

(defn- relation-eid
  [datom]
  (nth (:v datom) 1))

(defn- relationship-item
  [db relation-by-eid scan-key decode datom]
  {:node (decode db relation-by-eid datom)
   :cursor (relationship-edge scan-key datom)})

(defn- relationship-page
  [db relations filters subject-eid resource-eid]
  (let [{:keys [direction size bound]} (impl.indexed/normalize-page-request filters)
        plan (scan-plan db filters subject-eid resource-eid relations)
        relation-by-eid (into {}
                              (map (juxt :db/id :eacl.relation/relation-name))
                              relations)
        relation-eids (set (keys relation-by-eid))]
    (validate-relationship-bound! plan bound)
    (if (empty? relation-eids)
      ;; A valid relation-name/type filter that resolves to no schema relation
      ;; is a proved-empty query. Falling into the generic plan here scanned
      ;; the entire global relationship index only to reject every datom.
      {:data []
       :page-info {:start-cursor nil
                   :end-cursor nil
                   :has-next-page? false
                   :has-previous-page? false}}
      (let [matching-items
            (->> (relationship-datoms db plan direction bound)
                 (filter #(contains? relation-eids (relation-eid %)))
                 (map #(relationship-item db relation-by-eid
                                          (:key plan) (:decode plan) %))
                 (filter #(relationship-matches-filters? filters (:node %))))
            realized (doall (take (inc size) matching-items))
            items (mapv identity
                        (case direction
                          :asc (take size realized)
                          :desc (reverse (take size realized))))
            any? (boolean (seq items))]
        ;; An empty page carries no cursors, so it can advertise neither
        ;; direction — see eacl.datomic.impl.indexed/page-response.
        {:data (mapv :node items)
         :page-info {:start-cursor (some-> items first :cursor)
                     :end-cursor (some-> items last :cursor)
                     :has-next-page? (and any?
                                          (case direction
                                            :asc (> (count realized) size)
                                            :desc (boolean bound)))
                     :has-previous-page? (and any?
                                              (case direction
                                                :asc (boolean bound)
                                                :desc (> (count realized)
                                                         size)))}}))))

(def ^:private known-relationship-filter-keys
  "Filter + pagination keys read-relationships accepts. :cursor and :limit are
  included so normalize-page-request can reject them with their specific
  errors; :consistency, :page/basis and :cache? are validated and consumed by
  the client layer but must be listed here, since an unknown key is a hard
  error rather than something to ignore."
  #{:subject/type :subject/id
    :resource/type :resource/id :resource/relation
    :first :last :after :before :cursor :limit
    :page/basis :consistency :cache?})

(def ^:private relationship-anchor-keys
  #{:subject/type :subject/id :resource/type :resource/id :resource/relation})

(defn- validate-relationship-filters!
  "An absent, misspelled, or unsupported filter key must fail loudly: silently
  dropping one degrades the query to a broader scan that returns rows the
  caller did not intend to read (same failure class as audit §4)."
  [filters]
  (doseq [[unsupported-key hint]
          [[:resource/id-prefix "Filter on :resource/id, or filter external ids client-side."]
           [:subject/relation "EACL does not support subject-relation filters."]]]
    (when (contains? filters unsupported-key)
      (throw (ex-info (str (pr-str unsupported-key) " is not supported by read-relationships. " hint)
               {:eacl/error :eacl.pagination/unsupported-filter
                :filter unsupported-key}))))
  (when-let [unknown-keys (seq (remove known-relationship-filter-keys (keys filters)))]
    (throw (ex-info (str "read-relationships was passed unknown filter key(s): " (pr-str (vec unknown-keys))
                         ". Known keys: " (pr-str (vec (sort known-relationship-filter-keys))) ".")
             {:eacl/error :eacl.filters/unknown-filter
              :unknown-keys (vec unknown-keys)})))
  ;; some? not contains?: a present-but-nil anchor (the shape you get from
  ;; {:subject/id (get-in req [:params :user-id])} with the param missing) is
  ;; treated as absent by every consumer below, so accepting it as an anchor
  ;; degraded the read to exactly the global scan this guard exists to prevent.
  (when-not (some #(some? (get filters %)) relationship-anchor-keys)
    (throw (ex-info (str "read-relationships requires at least one non-nil anchor filter of "
                         (pr-str (vec (sort relationship-anchor-keys)))
                         ". An unfiltered read would scan the entire relationship index.")
             {:eacl/error :eacl.filters/missing-anchor
              :nil-anchor-keys (vec (sort (filter #(and (contains? filters %)
                                                        (nil? (get filters %)))
                                                  relationship-anchor-keys)))}))))

(defn read-relationships
  [db filters]
  (validate-relationship-filters! filters)
  (let [relations    (find-relations db filters)
        subject-id    (:subject/id filters)
        resource-id   (:resource/id filters)
        ;; object-eid, not d/entid: a raw-impl caller passing a string id got a
        ;; bare :db.error/not-a-keyword out of Datomic.
        subject-eid  (when (some? subject-id) (impl.indexed/object-eid db subject-id))
        resource-eid (when (some? resource-id) (impl.indexed/object-eid db resource-id))
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
      (relationship-page db relations normalized-filters subject-eid resource-eid))))

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

(defn affected-relation-ids
  "Returns every relation whose relationship tuples are changed by `ops`."
  [ops]
  (into #{}
        (keep
         (fn [op]
           (or (relation-eid-of-retraction op)
               (when (and (vector? op)
                          (= :db/add (first op))
                          (= relation-version-attr (nth op 2 nil)))
                 (nth op 1 nil)))))
        ops))

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
