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

(defn spiceomic-write-relationships!
  [conn updates]
  (let [db      (d/db conn)                                 ; just to look up matching relationship. could be done in bulk.
        tx-data (->> updates
                     (map #(impl/tx-update-relationship db %))
                     (remove nil?))]                        ; :delete operation can be nil.
    ;(log/debug 'tx-data tx-data)
    (let [{:keys [db-after]} @(d/transact conn tx-data)
          basis (d/basis-t db-after)]
      ; we return the latest DB basis as :zed/token to simulate consistency semantics.
      {:zed/token (str basis)})))

; Steps:
; - handle spice-object type & ID.

(defn object->ident
  "Accepts SpiceObject, or Datomic ident, or :db/id"
  [obj-or-ident]
  (if (:type obj-or-ident)
    [:eacl/id (:id obj-or-ident)]
    obj-or-ident))

(defn spiceomic-can?
  "Subject & Resource types must match in rules, but we don't check them here."
  [db subject permission resource]
  (let [result (impl/can? db (object->ident subject) permission (object->ident resource))]
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
    (impl/read-relationships (d/db conn) filters))

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

  (expand-permission-tree [this {:as opts :keys [consistency permission resource]}]
    (throw (Exception. "not impl."))))

(defn make-client [conn]
  (->Spiceomic conn))

(comment
  (require '[eacl.datomic.datomic-helpers :refer [with-mem-conn]])
  (with-mem-conn [conn []]
                 (make-client conn)))