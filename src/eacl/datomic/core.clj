(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [eacl.core :as eacl :refer [IAuthorization spice-object
                                        ->Relationship map->Relationship
                                        ->RelationshipUpdate]]
            [eacl.datomic.impl :as impl]
            [datomic.api :as d]
            [eacl.datomic.schema :as schema]
            [clojure.tools.logging :as log]))

;(ns-unalias *ns* 'impl)

; operation: :create, :touch, :delete unspecified

(defn relationship-filters->args
  "Order matters. Maps to :in value."
  [filters]
  (->> (map filters
            [:resource/type
             :resource/id
             :resource/relation
             :subject/type
             :subject/id
             :subject/relation])
       (remove nil?)
       (vec)))

(comment
  (relationship-filters->args
    {:resource/type :server
     :subject/id    123}))

(defn build-relationship-query
  "One of these filters are required:
  - :resource/type,
  - :resource/id,
  - :resource/id-prefix, or
  - :resource/relation.

Not supported yet:
- subject_relation not supported yet.
- resource_prefix.

subject-type treatment reuses :resource/type. Maybe this should be entity type."
  [{:as               _relationship-filters
    resource-type     :resource/type
    resource-id       :resource/id
    _resource-prefix  :resource/id-prefix                   ; not supported yet.
    resource-relation :resource/relation
    subject-type      :subject/type
    subject-id        :subject/id
    _subject-relation :subject/relation}]                   ; not supported yet.
  {:pre [(or resource-type resource-id _resource-prefix resource-relation)
         (not _resource-prefix)]}                           ; not supported.
  {:find  '[?resource-type ?resource-id
            ?relation-name
            ?subject-type ?subject-id]
   ; big todo: string ID support, via UUIDs?
   :keys  [:resource/type :resource/id
           :resource/relation
           :subject/type :subject/id]
   :in    (cond-> ['$]                                      ; this would be a nice macro.
            resource-type (conj '?resource-type)
            resource-id (conj '?resource-id)
            ;resource-prefix (conj '?resource-prefix) ; todo.
            resource-relation (conj '?resource-relation)
            subject-type (conj '?subject-type)
            subject-id (conj '?subject-id))
   ;subject-relation (conj '?subject-relation) ; todo.
   ; Clause ; order is perf. sensitive.
   :where '[[?relationship :eacl.relationship/resource ?resource]
            [?resource :eacl/type ?resource-type]
            [?relationship :eacl.relationship/relation-name ?relation-name]
            [?relationship :eacl.relationship/subject ?subject]
            [?subject :eacl/type ?subject-type]
            [?subject :eacl/id ?subject-id]
            [?resource :eacl/id ?resource-id]]})

(comment
  (build-relationship-query {:resource/type :server}))

(defn rel-map->Relationship
  [{:as               _rel-map
    resource-type     :resource/type
    resource-id       :resource/id
    resource-relation :resource/relation
    subject-type      :subject/type
    subject-id        :subject/id}]
  (map->Relationship
    {:subject  (spice-object subject-type subject-id)
     :relation resource-relation
     :resource (spice-object resource-type resource-id)}))

(defn spiceomic-read-relationships
  [db filters]
  (let [qry  (build-relationship-query filters)
        args (relationship-filters->args filters)]
    (->> (apply d/q qry db args)
         (map rel-map->Relationship))))

(defn find-one-relationship
  [db {:as relationship :keys [subject relation resource]}]
  (log/debug 'find-one-relationship relationship)
  (let [filters {:resource/type     (:type resource)
                 :resource/id       (:id resource)
                 :resource/relation relation
                 :subject/type      (:type subject)
                 :subject/id        (:id subject)}
        _       (log/debug 'filters filters)
        qry     (-> (build-relationship-query filters)
                    (assoc :find '[?relationship .])
                    (dissoc :keys))
        args    (relationship-filters->args filters)]
    (apply d/q qry db args)))

(defn tx-relationship
  "Translate a Relationship to a Datomic entity map.
  Note: `relation` in relationship filters corresponds to `:resource/relation` here.
  We don't validate resource & subject types here."
  [{:as _relationship :keys [subject relation resource]}]
  ; this is kind of grosos
  (impl/Relationship
    {:type (:type subject), :id [:eacl/id (:id subject)]}
    relation
    {:type (:type resource), :id [:eacl/id (:id resource)]}))

(defn tx-update-relationship
  "Note that delete costs N queries."
  [db {:as update :keys [operation relationship]}]
  (case operation
    :touch                                                  ; ensure update existing. we don't have uniqueness on this yet.
    (let [rel-id (find-one-relationship db relationship)]
      (cond-> (tx-relationship relationship)
        rel-id (assoc :db/id rel-id)))

    :create
    (if-let [rel-id (find-one-relationship db relationship)]
      (throw (Exception. ":create relationship conflicts with existing: " rel-id))
      (tx-relationship relationship))

    :delete
    (if-let [rel-id (find-one-relationship db relationship)]
      [:db.fn/retractEntity rel-id]
      nil)

    :unspecified
    (throw (Exception. ":unspecified relationship update not supported."))))

(defn spiceomic-write-relationships!
  [conn updates]
  (let [db      (d/db conn)                                 ; just to look up matching relationship. could be done in bulk.
        tx-data (->> updates
                     (map #(tx-update-relationship db %))
                     (remove nil?))]                        ; :delete operation can be nil.
    (log/debug 'tx-data tx-data)
    (let [{:keys [db-after]} @(d/transact conn tx-data)
          basis (d/basis-t db-after)]
      ; we return the latest DB basis as :zed/token to simulate consistency semantics.
      {:zed/token (str basis)})))

; Steps:
; - handle spice-object type & ID.

(defn spiceomic-can?
  "Subject & Resource types must match in rules, but we don't check them here."
  [db subject permission resource]
  (let [result (impl/can? db [:eacl/id (:id subject)] permission [:eacl/id (:id resource)])]
    ;; Ensure we return a boolean
    (boolean result)))

;(defn
;  ->RelationshipFilter
;  "All fields are optional, but at least one field is required.
;  Performance is sensitive to indexing."
;  [{:as            filters
;    :resource/keys [type id id-prefix relation]             ; destructured for call signature.
;    :subject/keys  [type id relation]}]                     ; where relation means subject_relation.
;  (assert (or (:resource/type filters)
;              (:resource/id filters)
;              (:resource/id-prefix filters)
;              (:resource/relation filters))
;          "One of the filters :resource/type, :resource/id, :resource/id-prefix or :resource/relation are required.")
;  (let [subject-filter (let [{:subject/keys [type id relation]} filters]
;                         (if (or type id relation)          ; only construct SubjectFilter if any subject filters are present.
;                           (->SubjectFilter filters)))
;        {:resource/keys [type id id-prefix relation]} filters]
;    ;; pass through for :subject/* filters.
;    (->> (cond-> (PermissionService$RelationshipFilter/newBuilder)
;           type (.setResourceType (name type))              ;; optional. not named optional for legacy SpiceDB reasons.
;           id (.setOptionalResourceId (str id))
;           id-prefix (.setOptionalResourceIdPrefix id-prefix)
;           relation (.setOptionalRelation (name relation))
;           subject-filter (.setOptionalSubjectFilter subject-filter))
;         (.build))))


(comment
  (build-relationship-query))

(defn spiceomic-lookup-resources [db filters]
  ; todo coercion
  (impl/lookup-resources db filters))

(defn spiceomic-lookup-subjects [db filters]
  ; todo coercion
  (impl/lookup-subjects db filters))

(defrecord Spiceomic [conn]
  IAuthorization
  (can? [this subject permission resource]
    ; how to resolve these?
    (spiceomic-can? (d/db conn) subject permission resource))

  (can? [this subject permission resource _consistency]
    ; todo: throw if consistency not fully-consistent.
    (spiceomic-can? (d/db conn) subject permission resource))

  (can? [this {:as demand :keys [subject permission resource consistency]}]
    (spiceomic-can? (d/db conn) subject permission resource))

  (read-schema [this]
    ; this can be read from DB.
    (throw (Exception. "not impl.")))

  (write-schema! [this schema]
    ; todo: potentially parse Spice schema.
    ; we'll need to support
    ; write-schema can take and validaet Relations.
    (throw (Exception. "not impl.")))

  (read-relationships [this filters]
    (spiceomic-read-relationships (d/db conn) filters))

  (write-relationships! [this updates]
    (spiceomic-write-relationships! conn updates))

  (create-relationships! [this relationships]
    (spiceomic-write-relationships! conn
                                    (for [rel relationships]
                                      (->RelationshipUpdate :create rel))))

  (create-relationship! [this relationship]
    (spiceomic-write-relationships! conn [(->RelationshipUpdate :create relationship)]))

  (create-relationship! [this subject relation resource]
    (spiceomic-write-relationships! conn [(->RelationshipUpdate :create (->Relationship subject relation resource))]))

  (delete-relationships! [this relationships]
    ; note: delete costs N to look up matching rel with ID.
    (spiceomic-write-relationships! conn
                                    (for [rel relationships]
                                      (->RelationshipUpdate :delete rel))))

  (lookup-resources [this filters]
    (spiceomic-lookup-resources (d/db conn) filters))

  (count-resources [this filters]
    (impl/count-resources (d/db conn) filters))

  (lookup-subjects [this filters]
    (spiceomic-lookup-subjects (d/db conn) filters))

  (expand-permission-tree [this {:as opts :keys [consistency]} permission resource]
    (throw (Exception. "not impl."))))

(defn make-client [conn]
  (->Spiceomic conn))

(comment
  (require '[eacl.datomic.datomic-helpers :refer [with-mem-conn]])
  (with-mem-conn [conn []]
                 (make-client conn)))