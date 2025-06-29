(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
    [clojure.tools.logging :as log]
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

;; Graph Traversal Strategy to resolve permissions between subjects & resources:
;; - schema is typically small, i.e. we have a low number of relations and permissions
;; - resources (like servers) are typically far more numerous than subjects (like users or accounts)
;;

(def Relation base/Relation)
(def Permission base/Permission)
(def Relationship base/Relationship)

;; Use indexed implementation for better performance with large offsets
(def can? impl-optimized/can?)
(def lookup-subjects impl-optimized/lookup-subjects)
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
  - :resource/id-prefix,
  - :resource/relation
  - :subject/type
  - :subject/id.

Not supported yet:
- subject_relation not supported yet.
- resource_prefix.

subject-type treatment reuses :resource/type. Maybe this should be entity type."
  [{:as               _relationship-filters
    resource-type     :resource/type
    resource-eid      :resource/id
    _resource-prefix  :resource/id-prefix                   ; not supported yet.
    resource-relation :resource/relation
    subject-type      :subject/type
    subject-eid       :subject/id
    _subject-relation :subject/relation}]                   ; not supported yet.
  {:pre [(or resource-type resource-eid _resource-prefix resource-relation subject-type subject-eid)
         (not _resource-prefix)]}                           ; not supported.
  {:find  '[?resource-type ?resource
            ?resource-relation ; bug!
            ?subject-type ?subject]
   ; big todo: string ID support, via UUIDs?

   :keys  [:resource/type :resource/id
           :resource/relation
           :subject/type :subject/id]
   :in    (cond-> ['$]                                      ; this would be a nice macro.
            resource-type (conj '?resource-type)
            resource-eid (conj '?resource)
            ;resource-prefix (conj '?resource-prefix) ; todo.
            resource-relation (conj '?resource-relation) ; ?relation-name
            subject-type (conj '?subject-type)
            subject-eid (conj '?subject))
   ;subject-relation (conj '?subject-relation) ; todo.
   ; Clause ; order is perf. sensitive.
   :where '[[?relationship :eacl.relationship/resource ?resource]
            [?resource :eacl/type ?resource-type]
            [?relationship :eacl.relationship/relation-name ?resource-relation]
            [?relationship :eacl.relationship/subject ?subject]
            [?subject :eacl/type ?subject-type]]})

(defn rel-map->Relationship
  [{:as               _rel-map
    resource-type     :resource/type ; we are doing extra work here to look up the rel.
    resource-eid      :resource/id
    resource-relation :resource/relation
    subject-type      :subject/type
    subject-eid       :subject/id}]
  ; todo make this more efficient
  (eacl/map->Relationship
    {:subject  (spice-object subject-type subject-eid)                   ; todo: 3-arity for type
     :relation resource-relation
     :resource (spice-object resource-type resource-eid)}))

(defn read-relationships
  [db filters]
  ;(log/debug 'read-relationships 'filters filters)
  (let [qry  (build-relationship-query filters)
        args (relationship-filters->args filters)]
    (->> (apply d/q qry db args)
         (map rel-map->Relationship))))

;(defn find-one-relationship-id-slow
;  [db {:as relationship :keys [subject relation resource]}]
;  ;(log/debug 'find-one-relationship relationship)
;  (let [filters {:resource/type     (:type resource)
;                 :resource/id       (:id resource)
;                 :resource/relation relation
;                 :subject/type      (:type subject)
;                 :subject/id        (:id subject)}
;        ;_       (log/debug 'filters filters)
;        qry     (-> (build-relationship-query filters)
;                    (assoc :find '[?relationship .])
;                    (dissoc :keys))
;        args    (relationship-filters->args filters)]
;    (apply d/q qry db args)))

(defn find-one-relationship-id
  [db {:as relationship :keys [subject relation resource]}]
  ; hmm this coercion does not belong here.
  ;(log/debug 'find-one-relationship relationship)
  (let [subject-type (:type subject) ; todo config.
        ; TODO Hoist up the d/entid calls.
        subject-eid  (d/entid db (:id subject))             ;object-id->entid db (:id subject))

        resource-type (:type resource) ; todo config.
        resource-eid (d/entid db (:id resource))]           ;object-id->entid db (:id resource))]
    ;(log/debug 'find-one-relationship-id 'subject-eid subject-eid 'resource-eid resource-eid)
    ;(assert subject-eid (str "No such subject: " subject))
    ;(assert resource-eid (str "No such resource: " resource))
    (if-not (and subject-eid resource-eid)
      nil ; return nil if missing
      (do
        (assert (= (:type subject) subject-type) (str "Subject Type " subject-type " does not match " (:type subject) "."))
        (assert (= (:type resource) resource-type) (str "Resource Type " resource-type " does not match " (:type resource) "."))
        (->> (d/datoms db :avet
                       :eacl.relationship/subject+relation-name+resource-type+resource
                       [subject-eid
                        relation
                        (:type resource)
                        resource-eid])
             (map :e)
             (first))))))

(defn tx-relationship
  "Translate a Relationship to a Datomic entity map.
  Note: `relation` in relationship filters corresponds to `:resource/relation` here.
  We don't validate resource & subject types here."
  [{:as _relationship :keys [subject relation resource]}]
  (base/Relationship
    subject
    relation
    resource))

(defn tx-update-relationship
  "Note that delete costs N queries."
  [db {:as update :keys [operation relationship]}]
  (case operation
    :touch                                                  ; ensure update existing. we don't have uniqueness on this yet.
    (let [rel-id (find-one-relationship-id db relationship)]
      (cond-> (tx-relationship relationship)
        rel-id (assoc :db/id rel-id)))

    :create
    (if-let [rel-id (find-one-relationship-id db relationship)]
      (throw (Exception. ":create relationship conflicts with existing: " rel-id))
      (tx-relationship relationship))

    :delete
    (if-let [rel-id (find-one-relationship-id db relationship)]
      [:db.fn/retractEntity rel-id]
      nil)

    :unspecified
    (throw (Exception. ":unspecified relationship update not supported."))))


(comment
  (build-relationship-query {:resource/type :server}))