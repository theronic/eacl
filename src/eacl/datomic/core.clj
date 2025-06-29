(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [eacl.core :as eacl :refer [IAuthorization spice-object
                                        ->Relationship map->Relationship
                                        ->RelationshipUpdate]]
            [eacl.datomic.impl :as impl]
            [eacl.spicedb.consistency :as consistency]
            [datomic.api :as d]
            [com.rpl.specter :as S]
            [eacl.datomic.schema :as schema]
            [clojure.tools.logging :as log]))

; operation: :create, :touch, :delete unspecified

(defn object->spice
  [db
   {:as opts
    :keys [entid->object-id]}
   object]
  (update object :id #(entid->object-id db %)))

(defn relationship->spice
  [db opts {:as rel :keys [subject relation resource]}]
  (map->Relationship
    {:subject (object->spice db opts subject)
     :relation relation
     :resource (object->spice db opts resource)}))

(defn spiceomic-read-relationships
  [db
   {:as   opts
    :keys [object-id->entid
           entid->object-id]}                               ; tempted to hoist coercion up to call-site.
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
   {:as opts :keys [spice->internal-object]}
   {:as rel :keys [subject relation resource]}]
  {:subject (spice->internal-object db subject)
   :relation relation
   :resource (spice->internal-object db resource)})

(defn spiceomic-write-relationships!
  [conn opts updates]
  (let [db      (d/db conn)                                 ; just to look up matching relationship. could be done in bulk.
        tx-data (->> updates
                     (S/transform [S/ALL :relationship] #(spice-relationship->internal db opts %))
                     (map #(impl/tx-update-relationship db %))
                     (remove nil?))]                        ; :delete operation can be nil.
    ;(log/debug 'tx-data tx-data)
    (let [{:as _tx-report
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
  (let [subject-eid  (object->entid db subject)
        resource-eid (object->entid db resource)]
    (if-not (and subject-eid resource-eid)
      false ; should we throw on missing IDs?
      (impl/can? db subject-eid permission resource-eid))))

(defn spiceomic-lookup-resources [db opts filters]
  (impl/lookup-resources db opts filters))

(defn spiceomic-lookup-subjects [db opts filters]
  (impl/lookup-subjects db opts filters))

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

  (lookup-resources [this filters]
    (spiceomic-lookup-resources (d/db conn) opts filters))

  (count-resources [this filters]
    (impl/count-resources (d/db conn) filters))

  (lookup-subjects [this filters]
    (spiceomic-lookup-subjects (d/db conn) opts filters))

  (expand-permission-tree [this {:as opts :keys [consistency permission resource]}]
    (throw (Exception. "not impl."))))

(defn make-client
  "Takes conn and opts. You can configure how EACL converts Spice Objects to/from entities."
  ; a bunch of these options are unnecessary. probably going to switch to d/entity internally.
  [conn
   {:as   opts
    :keys [spice->internal-object
           internal->spice-object
           entid->object-id
           object-id->entid
           object->entid
           entid->object]
    :or   {spice->internal-object impl/default-spice->internal-object
           internal->spice-object impl/default-internal->spice-object
           entid->object-id impl/default-entid->object-id
           object-id->entid impl/default-object-id->entid
           entid->object impl/default-entid->object
           object->entid impl/default-object->entid}}]
  (assert (fn? object->entid) "object->eid fn is required to coerce SpiceObject to Datomic eid.")
  (assert (fn? entid->object) "entid->object fn is required to coerce Datomic entid to SpiceObject.")
  (->Spiceomic conn {:spice->internal-object spice->internal-object
                     :internal->spice-object internal->spice-object
                     :entid->object-id entid->object-id
                     :object-id->entid object-id->entid
                     :entid->object entid->object
                     :object->entid object->entid})) ; object->eid))

(comment
  (require '[eacl.datomic.datomic-helpers :refer [with-mem-conn]])
  (with-mem-conn [conn []]
                 (make-client conn {})))