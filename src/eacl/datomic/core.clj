(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [eacl.core :as eacl :refer [IAuthorization spice-object
                                        ->Relationship map->Relationship
                                        ->RelationshipUpdate]]
            [eacl.datomic.impl-base :as base] ; only for Cursor.
            [eacl.datomic.impl :as impl]
            [eacl.spicedb.consistency :as consistency]
            [datomic.api :as d]
            [com.rpl.specter :as S]
            [eacl.datomic.schema :as schema]
            [clojure.tools.logging :as log]))

;; ID Configuration

(defn default-object-id->ident
  "Default implementation interprets :id in object as :eacl/id. Configurable."
  [object-id]
  (cond
    (number? object-id) object-id                             ; support :db/id.
    (keyword? object-id) object-id                            ; :db/ident support.
    (string? object-id) [:eacl/id object-id]))

(defn default-object-id->entid
  "Default implementation interprets :id in object as :eacl/id. Configurable."
  [db object-id]
  (let [ident (default-object-id->ident object-id)]
    ;(log/debug 'default-object->entid (pr-str object) '->ident (pr-str ident))
    (d/entid db ident)))

(defn default-object->entid
  ; this can go away.
  "Default implementation interprets :id in object as :eacl/id. Configurable."
  [db {:as object :keys [type id]}]
  (default-object-id->entid db id))

(defn default-entity->object-id [entity]
  (:eacl/id entity))

; do we need all of these?
(defn default-entid->object-id [db eid]
  (let [ent (d/entity db eid)]
    (default-entity->object-id ent)))

(defn default-entity->object-type [ent]
  (:eacl/type ent))

(defn default-entid->object [db eid]
  (let [ent (d/entity db eid)]
    (spice-object (default-entity->object-type ent) (default-entity->object-id ent))))

(defn default-spice->internal-object [db {:as obj :keys [type id]}]
  {:type type :id (default-object-id->entid db id)})

(defn default-internal-object->spice [db {:as obj :keys [type id]}]
  (spice-object type (default-entid->object-id db id)))

(defn default-internal-cursor->spice
  [db
   {:as opts :keys [entid->object-id]}
   {:as cursor :keys [path-index resource-id]}]
  (base/->Cursor path-index (entid->object-id db resource-id)))

(defn default-spice-cursor->internal
  [db
   {:as opts :keys [object-id->entid]}
   {:as cursor :keys [path-index resource-id]}]
  {:path-index path-index :resource-id (object-id->entid db resource-id)})

; operation: :create, :touch, :delete unspecified

(defn object->spice
  [db
   {:as   opts
    :keys [entid->object-id]}
   object]
  (update object :id #(entid->object-id db %)))

(defn relationship->spice
  [db opts {:as rel :keys [subject relation resource]}]
  (map->Relationship
    {:subject  (object->spice db opts subject)
     :relation relation
     :resource (object->spice db opts resource)}))

(defn spiceomic-read-relationships
  [db
   {:as   opts
    :keys [object-id->entid
           entid->object-id]} ; used by relationship->spice via object->spice.
   {:as          filters
    subject-oid  :subject/id
    resource-oid :resource/id}]
  (let [subject-eid  (object-id->entid db subject-oid)
        resource-eid (object-id->entid db resource-oid)
        filters'     (cond-> filters
                       subject-oid (assoc :subject/id subject-eid)
                       resource-oid (assoc :resource/id resource-eid))]
    (->> (impl/read-relationships db filters')
         (map #(relationship->spice db opts %)))))

(defn spice-relationship->internal
  [db
   {:as opts :keys [spice-object->internal]}
   {:as rel :keys [subject relation resource]}]
  {:subject  (spice-object->internal db subject)
   :relation relation
   :resource (spice-object->internal db resource)})

(defn spiceomic-write-relationships!
  [conn opts updates]
  (let [db      (d/db conn)                                 ; just to look up matching relationship. could be done in bulk.
        tx-data (->> updates
                     (S/transform [S/ALL :relationship] #(spice-relationship->internal db opts %))
                     (map #(impl/tx-update-relationship db %))
                     (remove nil?))]                        ; :delete operation can be nil.
    ;(log/debug 'tx-data tx-data)
    (let [{:as   _tx-report
           :keys [db-after]} @(d/transact conn tx-data)
          basis (d/basis-t db-after)]
      ; we return the latest DB basis as :zed/token to simulate consistency semantics.
      {:zed/token (str basis)})))

; Steps:
; - handle spice-object type & ID.

(defn spiceomic-can?
  "Subject & Resource types must match in rules, but we don't check them here."
  [db
   {:as opts :keys [object->entid]}
   subject permission resource
   consistency]
  ;(log/debug 'spiceomic-can? 'opts opts)
  (assert (= consistency/fully-consistent consistency) "EACL only supports consistency/fully-consistent at this time.")
  ; impl/can? also runs d/entid. We can probably simply this to ident.
  (let [subject-eid  (object->entid db subject)
        resource-eid (object->entid db resource)]
    ; Note: we do not check types here, but we should.
    (if-not (and subject-eid resource-eid)
      false                                                 ; should we throw on missing IDs?
      (impl/can? db subject-eid permission resource-eid))))

(defn spiceomic-lookup-resources
  [db
   {:as   opts
    :keys [internal-object->spice
           spice-object->internal

           entid->object

           internal-cursor->spice
           spice-cursor->internal]}
   query]
  (->> query
       (S/transform [:subject] #(spice-object->internal db %))
       (S/transform [:cursor] #(spice-cursor->internal db opts %))
       (impl/lookup-resources db)
       (S/transform [:cursor] #(internal-cursor->spice db opts %))
       (S/transform [:data S/ALL] #(entid->object db %))))

(defn spiceomic-lookup-subjects
  [db
   {:as opts
    :keys [entid->object
           spice-object->internal]}
   query]
  (->> query
       (S/transform [:resource] #(spice-object->internal db %))
       ; todo cursor coercion.
       (impl/lookup-subjects db)
       (S/transform [:data S/ALL] #(entid->object db %))))

(defrecord Spiceomic [conn opts]
  ; where object-id is a fn that takes [db object] and returns a Datomic ident or eid.
  IAuthorization
  (can? [this subject permission resource]
    ; how to resolve these?
    (spiceomic-can? (d/db conn) opts subject permission resource consistency/fully-consistent))

  (can? [this subject permission resource consistency]
    (spiceomic-can? (d/db conn) opts subject permission resource consistency))

  (can? [this {:as demand :keys [subject permission resource consistency]}]
    (assert (= consistency))
    (spiceomic-can? (d/db conn) opts subject permission resource consistency))

  (read-schema [this]
    ; this can be read from DB.
    (throw (Exception. "not impl.")))

  (write-schema! [this schema]
    ; todo: potentially parse Spice schema.
    ; we'll need to support
    ; write-schema can take and validaet Relations.
    (throw (Exception. "not impl.")))

  (read-relationships [this filters]
    (spiceomic-read-relationships (d/db conn) opts filters))

  (write-relationships! [this updates]
    (spiceomic-write-relationships! conn opts updates))

  (create-relationships! [this relationships]
    (spiceomic-write-relationships! conn opts
                                    (for [rel relationships]
                                      (->RelationshipUpdate :create rel))))

  (create-relationship! [this relationship]
    (spiceomic-write-relationships! conn opts [(->RelationshipUpdate :create relationship)]))

  (create-relationship! [this subject relation resource]
    (spiceomic-write-relationships! conn opts [(->RelationshipUpdate :create (->Relationship subject relation resource))]))

  (delete-relationships! [this relationships]
    ; note: delete costs N to look up matching rel with ID.
    (spiceomic-write-relationships! conn opts
                                    (for [rel relationships]
                                      (->RelationshipUpdate :delete rel))))

  (lookup-resources [this query]
    (spiceomic-lookup-resources (d/db conn) opts query))

  (count-resources [this query]
    (impl/count-resources (d/db conn) query))

  (lookup-subjects [this query]
    (spiceomic-lookup-subjects (d/db conn) opts query))

  (expand-permission-tree [this {:as opts :keys [consistency permission resource]}]
    (throw (Exception. "not impl."))))

(defn make-client
  "Takes conn and opts. You can configure how EACL converts Spice Objects to/from entities."
  ; a bunch of these options are unnecessary. probably going to switch to d/entity internally.
  [conn
   {:as   opts
    :keys [entid->object-id
           object-id->entid

           entid->object
           object->entid

           spice-object->internal
           internal-object->spice

           internal-cursor->spice
           spice-cursor->internal]
    :or   {entid->object-id       default-entid->object-id
           object-id->entid       default-object-id->entid

           entid->object          default-entid->object
           object->entid          default-object->entid

           spice-object->internal default-spice->internal-object
           internal-object->spice default-internal-object->spice

           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (assert (fn? object->entid) "object->eid fn is required to coerce SpiceObject to Datomic eid.")
  (assert (fn? entid->object) "entid->object fn is required to coerce Datomic entid to SpiceObject.")
  (->Spiceomic conn {:entid->object-id       entid->object-id
                     :object-id->entid       object-id->entid

                     :entid->object          entid->object
                     :object->entid          object->entid

                     :spice-object->internal spice-object->internal
                     :internal-object->spice internal-object->spice

                     :internal-cursor->spice internal-cursor->spice
                     :spice-cursor->internal spice-cursor->internal})) ; object->eid))

(comment
  (require '[eacl.datomic.datomic-helpers :refer [with-mem-conn]])
  (with-mem-conn [conn []]
                 (make-client conn {})))