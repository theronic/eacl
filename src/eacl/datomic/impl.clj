(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
   [datomic.api :as d]
   [eacl.core :as eacl :refer [spice-object]]
   [eacl.datomic.impl.base :as base]
   [eacl.datomic.impl.indexed :as impl.indexed]))

(def Relation base/Relation)
(def Permission base/Permission)

(defn Relationship
  "Constructs relationship data for the public/internal API boundary.
  Persistence is handled by tuple-specific write helpers below."
  [subject relation resource]
  (eacl/->Relationship subject relation resource))

(def can? impl.indexed/can?)
(def lookup-subjects impl.indexed/lookup-subjects)
(def lookup-resources impl.indexed/lookup-resources)
(def count-resources impl.indexed/count-resources)

(def ^:private forward-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def ^:private reverse-relationship-attr
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(defn can!
  "The thrown exception should probably be configurable."
  [db subject permission resource]
  (if (can? db subject permission resource)
    true
    (throw (Exception. "Unauthorized"))))

(defn- object-id->eid-or-tempid
  [db object-id]
  (cond
    (number? object-id) object-id
    (string? object-id) (or (d/entid db [:eacl/id object-id]) object-id)
    :else (or (d/entid db object-id) object-id)))

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
  [db {:keys [subject relation resource]}]
  (let [subject-type (:type subject)
        subject-eid  (object-id->eid-or-tempid db (:id subject))
        resource-type (:type resource)
        resource-eid  (object-id->eid-or-tempid db (:id resource))
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
  [subject-type relation-eid resource-type resource-eid])

(defn- reverse-relationship-tuple
  [{:keys [resource-type relation-eid subject-type subject-eid]}]
  [resource-type relation-eid subject-type subject-eid])

(defn- add-relationship-txes
  [resolved]
  [[:db/add (:subject-eid resolved)
    forward-relationship-attr
    (relationship-tuple resolved)]
   [:db/add (:resource-eid resolved)
    reverse-relationship-attr
    (reverse-relationship-tuple resolved)]])

(defn- retract-relationship-txes
  [resolved]
  [[:db/retract (:subject-eid resolved)
    forward-relationship-attr
    (relationship-tuple resolved)]
   [:db/retract (:resource-eid resolved)
    reverse-relationship-attr
    (reverse-relationship-tuple resolved)]])

(defn- relationship-exists?
  [db {:keys [subject-eid resource-eid] :as resolved}]
  (if (and (number? subject-eid) (number? resource-eid))
    (boolean
     (seq
      (d/datoms db
                :eavt
                subject-eid
                forward-relationship-attr
                (relationship-tuple resolved))))
    false))

(defn find-one-relationship-id
  "Returns the resolved tuple identity for an existing relationship, or nil."
  [db relationship]
  (let [resolved (resolve-relationship db relationship)]
    (when (relationship-exists? db resolved)
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
       :attr-eid (d/entid db forward-relationship-attr)
       :fixed-eid subject-eid
       :tuple-prefix (not-empty (forward-tuple-prefix filters relation-hint))
       :decode decode-forward-datom}

      resource-eid
      {:key :resource-reverse
       :index :eavt
       :attr-eid (d/entid db reverse-relationship-attr)
       :fixed-eid resource-eid
       :tuple-prefix (not-empty (reverse-tuple-prefix filters relation-hint))
       :decode decode-reverse-datom}

      (seq (forward-tuple-prefix filters relation-hint))
      {:key :global-forward
       :index :avet
       :attr-eid (d/entid db forward-relationship-attr)
       :tuple-prefix (forward-tuple-prefix filters relation-hint)
       :decode decode-forward-datom}

      :else
      {:key :global-reverse
       :index :avet
       :attr-eid (d/entid db reverse-relationship-attr)
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
    (let [matching-items (->> (relationship-datoms db plan direction bound)
                              (filter #(contains? relation-eids (relation-eid %)))
                              (map #(relationship-item db relation-by-eid (:key plan) (:decode plan) %))
                              (filter #(relationship-matches-filters? filters (:node %))))
          realized (doall (take (inc size) matching-items))
          items (mapv identity
                      (case direction
                        :asc (take size realized)
                        :desc (reverse (take size realized))))]
      {:data (mapv :node items)
       :page-info {:start-cursor (some-> items first :cursor)
                   :end-cursor (some-> items last :cursor)
                   :has-next-page? (case direction
                                     :asc (> (count realized) size)
                                     :desc (boolean bound))
                   :has-previous-page? (case direction
                                         :asc (boolean bound)
                                         :desc (> (count realized) size))}})))

(defn read-relationships
  [db filters]
  (let [relations    (find-relations db filters)
        subject-id    (:subject/id filters)
        resource-id   (:resource/id filters)
        subject-eid  (when subject-id (d/entid db subject-id))
        resource-eid (when resource-id (d/entid db resource-id))
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

(defn tx-relationship
  "Translate relationship data into v7 tuple writes."
  ([db subject relation resource]
   (tx-relationship db (eacl/->Relationship subject relation resource)))
  ([db relationship]
   (add-relationship-txes (resolve-relationship db relationship))))

(defn tx-update-relationship
  "Relationship writes are implemented against v7 forward/reverse tuple indexes.
  :touch is idempotent."
  [db {:keys [operation relationship]}]
  (let [resolved (resolve-relationship db relationship)
        exists?  (relationship-exists? db resolved)]
    (case operation
      :touch
      (when-not exists?
        (add-relationship-txes resolved))

      :create
      (if exists?
        (throw (Exception. ":create relationship conflicts with existing tuple relationship"))
        (add-relationship-txes resolved))

      :delete
      (when exists?
        (retract-relationship-txes resolved))

      :unspecified
      (throw (Exception. ":unspecified relationship update not supported.")))))
