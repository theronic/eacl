(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [eacl.core :as eacl :refer [IAuthorization spice-object
                                        ->Relationship map->Relationship
                                        ->RelationshipUpdate]]
            [eacl.datomic.impl.base :as base]               ; only for Cursor.
            [eacl.datomic.impl :as impl]
    ;[eacl.datomic.impl-fixed :as impl-fixed]        ; impl-fixed is an experimental implementation. avoid until correct.
            [eacl.spicedb.consistency :as consistency]
            [datomic.api :as d]
            [com.rpl.specter :as S]
            [malli.core :as m]
            [eacl.datomic.schema :as schema]
            [clojure.tools.logging :as log]))

;; How to Configure Spice Object ID Coercion

; SpiceDB uses strings for subject/resource IDs, but internally EACL uses Datomic :db/id Long IDs.
; EACL lets you configure how to coerce internal Datomic IDs to/from an external ID, typically via a lookup-ref, e.g.
(comment
  (eacl.datomic.core/make-client conn {:entity->object-id (fn [ent] (:your/ident ent))
                                       :object-id->ident  (fn [obj-id] [:your/ident obj-id])}))
; Typical usage here is if you already have a UUID attribute you want to use for coercion.
; If you do not intend to expose EACL to an external system, you can use internal Datomic IDs, e.g.
(comment
  (eacl.datomic.core/make-client conn {:entity->object-id (fn [ent] (:db/id ent))
                                       :object-id->ident  (fn [obj-id] obj-id)})) ;; passthrough.

(defn default-internal-cursor->spice
  [db
   {:as opts :keys [entid->object-id]}
   {:as cursor :keys [_path-index _resource]}]
  (when (and cursor (:resource cursor))                     ; Fix: only transform when cursor has a valid resource
    (->> cursor
         (S/transform [:resource :id] #(entid->object-id db %)))))

(defn default-spice-cursor->internal
  [db
   {:as opts :keys [object-id->entid]}
   {:as cursor :keys [_path-index _resource]}]
  (when cursor
    (->> cursor
         (S/transform [:resource :id] #(object-id->entid db %)))))

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
           entid->object-id]}                               ; used by relationship->spice via object->spice.
   {:as          filters
    subject-oid  :subject/id
    resource-oid :resource/id}]
  (let [subject-eid  (object-id->entid db subject-oid)
        resource-eid (object-id->entid db resource-oid)

        ; we need to check for a valid ID so we don't assoc a nil filter, which does not filter.
        _            (if subject-oid (assert subject-eid "read-relationships is missing a valid :subject/id."))
        _            (if resource-oid (assert resource-eid "read-relationships is missing a valid :resource/id."))

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

(defn spiceomic-can?
  "Subject & Resource types must match in rules, but we don't check them here."
  [db
   {:as opts :keys [object->entid]}
   subject permission resource
   consistency]
  ;(log/debug 'spiceomic-can? 'opts opts)
  (assert (= consistency/fully-consistent consistency) "EACL only supports consistency/fully-consistent at this time.")
  ; impl/can? also runs d/entid. We can probably simply this to ident.
  (let [subject-type  (:type subject)
        subject-eid   (object->entid db subject)
        resource-type (:type resource)
        resource-eid  (object->entid db resource)]
    ; Note: we do not check types here, but we should.
    (if-not (and subject-eid resource-eid)
      false                                                 ; should we throw on missing IDs?
      (impl/can? db
                 (spice-object subject-type subject-eid)
                 permission
                 (spice-object resource-type resource-eid)))))

(defn spiceomic-lookup-resources
  [db
   {:as   opts
    :keys [spice-object->internal
           entid->object
           entid->object-id
           object-id->ident                                 ; why is this nil?
           object-id->entid                                 ; do we need this?

           internal-cursor->spice
           spice-cursor->internal]}
   {:as           query
    :keys         [subject]
    resource-type :resource/type}]
  (log/debug 'spiceomic-lookup-resources 'query query)
  (let [internal-subject (spice-object->internal db subject)]
    (assert (:id internal-subject) (str "subject " (pr-str subject) " passed to lookup-resources does not exist with ident " (object-id->ident (:id subject))))
    ;(assert (= (:type subject-ent) (:type subject)) (str "lookup-resources: subject type passed does not match entity: " (pr-str subject)))
    (let [rx (->> query
                  (S/setval [:subject] internal-subject)    ; do we need to coerce this subject?
                  (S/transform [:cursor] (fn [external-cursor] (spice-cursor->internal db opts external-cursor)))
                  (impl/lookup-resources db)
                  (S/transform [:data S/ALL] (fn [{:as obj :keys [type id]}]
                                               (spice-object type (entid->object-id db id))))
                  (S/transform [:cursor] (fn [internal-cursor]
                                           (prn 'coercing-internal-cursor internal-cursor)
                                           (let [x (internal-cursor->spice db opts internal-cursor)]
                                             (prn 'coerced-to x)
                                             x))))]         ;; TODO FIX!
      ;(log/debug 'rx rx)
      rx)))

(defn spiceomic-count-resources
  [db
   {:as   opts
    :keys [spice-object->internal
           spice-cursor->internal]}
   {:as query :keys [subject]}]
  (let [subject-ent (spice-object->internal db subject)]
    (assert (:id subject-ent) (str "subject passed to count-resources does not exist: " (pr-str subject)))
    (assert (= (:type subject-ent) (:type subject)) (str "count-resources: subject type passed does not match entity: " (pr-str subject)))
    (->> query
         (S/setval [:subject] subject-ent)
         (S/transform [:cursor] #(spice-cursor->internal db opts %))
         (impl/count-resources db))))

(defn spiceomic-lookup-subjects
  [db
   {:as   opts
    :keys [entid->object-id
           spice-object->internal]}
   query]
  (->> query
       (S/transform [:resource] #(spice-object->internal db %))
       ; todo cursor coercion.
       (impl/lookup-subjects db)
       (S/transform [:data S/ALL] (fn [{:as obj :keys [type id]}]
                                    (spice-object type (entid->object-id db id))))))

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
    (spiceomic-count-resources (d/db conn) opts query))

  (lookup-subjects [this query]
    (spiceomic-lookup-subjects (d/db conn) opts query))

  (expand-permission-tree [this {:as opts :keys [consistency permission resource]}]
    (throw (Exception. "not impl."))))

(defn make-client
  "Takes conn and opts. You can configure how EACL converts Spice Objects to/from entities."
  ; a bunch of these options are unnecessary. probably going to switch to d/entity internally.
  [conn
   {:as   opts
    :keys [entity->type

           entity->object-id
           object-id->ident

           ; Cursors:
           internal-cursor->spice
           spice-cursor->internal]
    ; You can configure how to look up the type of subject or resource.
    ; EACL can potentially support dynamic type resolution, but it gets messy.
    :or   {;entity->type           (fn [ent] (:eacl/type ent)) ; no longer relevant. types are now encoded in relationships.
           entity->object-id      (fn [ent] (:eacl/id ent))
           object-id->ident       (fn [obj-id] [:eacl/id obj-id])

             ; Cursor coercion:
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  ;  object-id->ident
  (assert (fn? object-id->ident) "EACL Config Error: object-id->ident fn is required to coerce a Spice Object ID to a Datomic ident that can be resolved by d/entid.")
  ;(assert (fn? entid->object) "entid->object fn is required to coerce Datomic entid to SpiceObject.")
  (let [object-id->entid (fn [db object-id]
                           (let [ident (object-id->ident object-id)]
                             ;(log/debug 'default-object->entid (pr-str object) '->ident (pr-str ident))
                             (d/entid db ident)))

        entid->object-id (fn [db eid]                       ; can be composed.
                           (let [ent (d/entity db eid)]
                             (entity->object-id ent)))

        opts'            {:object-id->ident       object-id->ident ; is this still used?
                          :entid->object-id       entid->object-id

                          :entity->object-id      entity->object-id ; can we compose this better?
                          :object-id->entid       object-id->entid

                                     ; we probably don't need this? just use id to entid at call-site.
                          :object->entid          (fn [db {:as obj :keys [type id]}]
                                                    (object-id->entid db id))

                                     ;(fn [db obj] (default-object->entid db object-id->ident obj))
                                     ; this is outdated: entid->object no longer needed. type comes from Relationships
                                     ;:entid->object          (fn [db entid]
                                     ;                          ; could this use an intermediate entid->entity fn?
                                     ;                          (let [ent (d/entity db entid)]
                                     ;                            (spice-object (entity->type ent) (entity->object-id ent))))

                          :internal-object->spice (fn [db {:as obj :keys [type id]}]
                                                    (spice-object type (entid->object-id db id)))

                          :spice-object->internal (fn [db {:as obj :keys [_type id]}]
                                                               ;(log/debug 'spice-object->internal (pr-str obj) (pr-str (object-id->entid db id)))
                                                    (update obj :id #(object-id->entid db %)))

                          :internal-cursor->spice internal-cursor->spice
                          :spice-cursor->internal spice-cursor->internal}]
    (->Spiceomic conn opts')))                              ; object->eid))

(comment
  (require '[eacl.datomic.datomic-helpers :refer [with-mem-conn]])
  (with-mem-conn [conn []]
                 (let [client (make-client conn {:entity->object-id (fn [ent] (:db/id ent))
                                                 :object-id->ident  (fn [obj-id] obj-id)})])))