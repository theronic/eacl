(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
    [datomic.api :as d]
    [eacl.core :as eacl :refer [spice-object]]
    [eacl.datomic.impl-base :as base]
    [eacl.datomic.impl-optimized :as impl-optimized]
    [eacl.datomic.impl-indexed :as impl-indexed]))

; A central place to configure how IDs and resource types are handled:
; - All SpiceDB objects have a type (string) and a unique ID (string). Spice likes strings.
; - To retain parity with SpiceDB, you can configure EACL to coerce object types & IDs of different
;   types (usually UUIDs) to/from Datomic when returning SpiceObject.
; - By default EACL, uses :entity/id (unique string) and :resource/type (keyword) for objects.
; - Below, you can configure how these are coerced to/from Datomic below.

; this should be passed into the impl.
;(def object-id-attr :entity/id)                             ; we default to :entity/id (string).
;(def resource-type-attr :resource/type)                     ; we default to :resource/type

; To support other types of IDs that can be coerced to/from string-formattable entity IDs, than UUIDs

;(defn id->datomic
;  "EACL uses unique string ID under :entity/id"
;  [id] (identity id))
;
;(defn datomic->id [id] (identity id))

;; Graph Traversal Strategy to resolve permissions between subjects & resources:
;; - schema is typically small, i.e. we have a low number of relations and permissions
;; - resources (like servers) are typically far more numerous than subjects (like users or accounts)
;;

(def Relation base/Relation)
(def Permission base/Permission)
(def Relationship base/Relationship)

;; Use indexed implementation for better performance with large offsets
(def can? impl-optimized/can?)
(def can! impl-optimized/can!)
(def entity->spice-object impl-optimized/entity->spice-object)
(def lookup-subjects impl-optimized/lookup-subjects)
;(def lookup-resources impl/lookup-resources)
;(def lookup-resources impl-grok/lookup-resources)

(def lookup-resources impl-indexed/lookup-resources)
(def count-resources impl-indexed/count-resources)

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
  "One of these filters is required:
  - :resource/type,
  - :resource/id,
  - :resource/id-prefix, or
  - :resource/relation
  - :subject/id.

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
  {:pre [(or resource-type resource-id _resource-prefix resource-relation subject-type)
         (not _resource-prefix)]}                           ; not supported.
  {:find  '[?resource-type ?resource-id
            ?resource-relation ; bug!
            ?subject-type ?subject-id]
   ; big todo: string ID support, via UUIDs?

   :keys  [:resource/type :resource/id
           :resource/relation
           :subject/type :subject/id]
   :in    (cond-> ['$]                                      ; this would be a nice macro.
            resource-type (conj '?resource-type)
            resource-id (conj '?resource-id)
            ;resource-prefix (conj '?resource-prefix) ; todo.
            resource-relation (conj '?resource-relation) ; ?relation-name
            subject-type (conj '?subject-type)
            subject-id (conj '?subject-id))
   ;subject-relation (conj '?subject-relation) ; todo.
   ; Clause ; order is perf. sensitive.
   :where '[[?relationship :eacl.relationship/resource ?resource]
            [?resource :eacl/type ?resource-type]
            [?relationship :eacl.relationship/relation-name ?resource-relation]
            [?relationship :eacl.relationship/subject ?subject]
            [?subject :eacl/type ?subject-type]
            [?subject :eacl/id ?subject-id]
            [?resource :eacl/id ?resource-id]]})

(defn rel-map->Relationship
  [{:as               _rel-map
    resource-type     :resource/type
    resource-id       :resource/id
    resource-relation :resource/relation
    subject-type      :subject/type
    subject-id        :subject/id}]
  (eacl/map->Relationship
    {:subject  (spice-object subject-type subject-id)
     :relation resource-relation
     :resource (spice-object resource-type resource-id)}))

(defn read-relationships
  [db filters]
  (let [qry  (build-relationship-query filters)
        args (relationship-filters->args filters)]
    (->> (apply d/q qry db args)
         (map rel-map->Relationship))))

(defn find-one-relationship
  [db {:as relationship :keys [subject relation resource]}]
  ;(log/debug 'find-one-relationship relationship)
  (let [filters {:resource/type     (:type resource)
                 :resource/id       (:id resource)
                 :resource/relation relation
                 :subject/type      (:type subject)
                 :subject/id        (:id subject)}
        ;_       (log/debug 'filters filters)
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
  (base/Relationship
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


(comment
  (build-relationship-query {:resource/type :server}))