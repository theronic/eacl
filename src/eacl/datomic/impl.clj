(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
    [datomic.api :as d]
    [eacl.core :as proto :refer [spice-object]]
    [eacl.datomic.rules :as rules]))

(defn id->identifier
  "This is to support custom unique entity IDs."
  [entity-id]
  [:entity/id entity-id])

; A central place to configure how IDs and resource types are handled:
; - All SpiceDB objects have a type (string) and a unique ID (string). Spice likes strings.
; - To retain parity with SpiceDB, you can configure EACL to coerce object types & IDs of different
;   types (usually UUIDs) to/from Datomic when returning SpiceObject.
; - By default EACL, uses :entity/id (unique string) and :resource/type (keyword) for objects.
; - Below, you can configure how these are coerced to/from Datomic below.

; this should be passed into the impl.
(def object-id-attr :entity/id)                             ; we default to :entity/id (string).
(def resource-type-attr :resource/type)                     ; we default to :resource/type

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

(defn Relation
  "Defines a relation type. Copied from core2.
  (Relation :product :owner :user) or (Relation :product/owner :user)"
  ([resource-type relation-name subject-type]
   {:pre [(keyword? resource-type) (keyword? relation-name) (keyword? subject-type)]}
   {:eacl.relation/resource-type resource-type
    :eacl.relation/relation-name relation-name
    :eacl.relation/subject-type  subject-type})
  ([resource-type+relation-name subject-type]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? subject-type)]}
   (Relation
     (keyword (namespace resource-type+relation-name))
     (keyword (name resource-type+relation-name))
     subject-type)))

(defn Permission
  "Defines how a permission is granted.
  Arity 1 (direct relation from namespaced keyword): (Permission :document/owner :view)
    => For :document, relation :owner grants :view permission.
  Arity 2 (direct relation): (Permission :document :owner :view)
    => For :document, relation :owner grants :view permission.
  Arity 3 (arrow relation): (Permission :vpc :admin :account :admin)
    => For :vpc, :admin permission is granted if subject has :admin on the resource linked by vpc's :account relation."

  ;; Arity 1: Direct grant, from namespaced keyword resource-type/relation-name
  ([resource-type+relation-name permission-to-grant]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? permission-to-grant)]}
   (let [rt (keyword (namespace resource-type+relation-name))
         rn (keyword (name resource-type+relation-name))]
     ;; Call arity 2
     (Permission rt rn permission-to-grant)))

  ;; Arity 2: Direct grant
  ([resource-type direct-relation-name permission-to-grant]
   {:pre [(keyword? resource-type) (keyword? direct-relation-name) (keyword? permission-to-grant)]}
   {:eacl.permission/resource-type   resource-type
    :eacl.permission/permission-name permission-to-grant
    :eacl.permission/relation-name   direct-relation-name})

  ;; Arity 3: Arrow grant
  ([resource-type
    arrow-source-relation arrow-target-permission
    grant-permission]
   {:pre [(keyword? resource-type) (keyword? grant-permission)
          (keyword? arrow-source-relation) (keyword? arrow-target-permission)]}
   {:eacl.arrow-permission/resource-type          resource-type
    :eacl.arrow-permission/permission-name        grant-permission
    :eacl.arrow-permission/source-relation-name   arrow-source-relation
    :eacl.arrow-permission/target-permission-name arrow-target-permission}))

(defn Relationship
  "A Relationship between a subject and a resource via Relation. Copied from core2."
  [subject relation-name resource]
  {:pre [(some? subject) (keyword? relation-name) (some? resource)]}
  {:eacl.relationship/resource      resource
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/subject       subject})

(defn can?
  "Returns true if subject has permission on resource. Copied from core2.
  Note: we are not checking subject & resource types, but we probably should."
  [db subject-id permission resource-id]
  {:pre [subject-id
         (keyword? permission)
         resource-id]}
  (let [{:as _subject-ent, subject-eid :db/id} (d/entity db subject-id)
        {:as _resource-ent, resource-eid :db/id} (d/entity db resource-id)]
    (if-not (and subject-eid resource-eid)
      false
      (->> (d/q '[:find ?subject .                          ; Using . to find a single value, expecting one or none
                  :in $ % ?subject ?perm ?resource
                  :where
                  (has-permission ?subject ?perm ?resource)] ; do we still needs this?
                db
                rules/check-permission-rules
                subject-eid
                permission
                resource-eid)
           (boolean)))))

(defn can! [db subject-id permission resource-id]
  (if (can? db subject-id permission resource-id)
    true
    ; todo nicer error message
    (throw (Exception. "Unauthorized"))))

(defn entity->spice-object [ent]
  ; note: we do not coerce here yet.
  (spice-object (get ent resource-type-attr) (get ent object-id-attr)))

(defn lookup-subjects
  "Enumerates subjects that have a given permission on a specified resource."
  [db {:as              filters
       resource         :resource
       permission       :permission
       subject-type     :subject/type
       subject-relation :subject/relation                   ; optional, but not supportet yet.
       limit            :limit
       offset           :offset}]
  {:pre [(:type resource) (:id resource)]}
  (let [{resource-type :type
         resource-id   :id} resource

        {:as          resource-ent
         resource-eid :db/id} (d/entity db [object-id-attr resource-id])]
    (assert resource-eid (str "lookup-subjects requires a valid resource with unique attr " (pr-str object-id-attr) "."))
    (assert (= resource-type (:resource/type resource-ent)) (str "Resource type does not match " resource-type "."))
    (let [subject-eids   (->> (d/q '[:find [?subject ...]
                                     :in $ % ?subject-type ?permission ?resource-eid
                                     :where
                                     (has-permission ?subject-type ?subject ?permission ?resource-eid)
                                     ;(has-permission ?subject ?permission ?resource-eid)
                                     ;[?subject :resource/type ?subject-type] ; could this be super slow?
                                     [(not= ?subject ?resource-eid)]] ; can we avoid this exclusionary clause?
                                   db
                                   rules/rules-lookup-subjects
                                   subject-type
                                   permission
                                   resource-eid))
          paginated-eids (cond->> subject-eids
                                  offset (drop offset)
                                  limit (take limit))]
      (->> paginated-eids
           (map #(d/entity db %))
           (map entity->spice-object)))))

(defn lookup-resources
  "Enumerate resources of a given type that a subject can access with the given permission."
  [db {:as           _filters
       resource-type :resource/type
       subject       :subject
       permission    :permission
       offset        :offset
       limit         :limit}]
  {:pre [(keyword? resource-type)
         (:type subject) (:id subject)
         (keyword? permission)]}
  (let [{subject-type :type
         subject-id   :id} subject

        {:as         subject-ent
         subject-eid :db/id} (d/entity db [object-id-attr subject-id])]
    (assert subject-eid (str "lookup-resources requires a valid subject with unique ID under attr " (pr-str object-id-attr) "."))
    (assert (= subject-type (:resource/type subject-ent)) (str "Subject Type does not match " subject-type "."))
    (let [resource-eids  (d/q '[:find [?resource ...]
                                :in $ % ?subject-type ?subject-eid ?permission ?resource-type
                                :where
                                (has-permission ?subject-eid ?permission ?resource-type ?resource)
                                [?resource :resource/type ?resource-type]] ; consider moving ?resource-type into has-permission.
                              db
                              rules/rules-lookup-resources ; slow-lookup-rules
                              subject-type ; not used
                              subject-eid
                              permission
                              resource-type)
          paginated-eids (cond->> resource-eids
                                  offset (drop offset)      ; optional.
                                  limit (take limit))]      ; optional.
      (->> paginated-eids
           (map #(d/entity db %))
           (map entity->spice-object)))))
