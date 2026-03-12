(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [clojure.tools.logging :as log]
            [com.rpl.specter :as S]
            [datomic.api :as d]
            [eacl.cursor :as cursor]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]
            [malli.core :as m]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(defn default-internal-cursor->spice
  [db {:keys [entid->object-id]} cursor]
  (when cursor
    (if (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(entid->object-id db %))
        (:p cursor) (update :p
                       (fn [p]
                         (into {}
                           (map (fn [[k v]] [k (entid->object-id db v)]))
                           p))))
      (cond
        (:resource cursor) (S/transform [:resource :id] #(entid->object-id db %) cursor)
        (:subject cursor) (S/transform [:subject :id] #(entid->object-id db %) cursor)))))

(defn default-spice-cursor->internal
  [db {:keys [object-id->entid]} cursor]
  (when cursor
    (if (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(object-id->entid db %))
        (:p cursor) (update :p
                       (fn [p]
                         (into {}
                           (map (fn [[k v]] [k (object-id->entid db v)]))
                           p))))
      (cond
        (:resource cursor) (S/transform [:resource :id] #(object-id->entid db %) cursor)
        (:subject cursor) (S/transform [:subject :id] #(object-id->entid db %) cursor)))))

(defn object->spice
  [db {:keys [entid->object-id]} object]
  (update object :id #(entid->object-id db %)))

(defn relationship->spice
  [db opts {:keys [subject relation resource]}]
  (map->Relationship
   {:subject (object->spice db opts subject)
    :relation relation
    :resource (object->spice db opts resource)}))

(defn spiceomic-read-relationships
  [db
   {:keys [object-id->entid] :as opts}
   filters]
  (let [subject-id   (:subject/id filters)
        resource-id  (:resource/id filters)
        subject-eid  (when subject-id (object-id->entid db subject-id))
        resource-eid (when resource-id (object-id->entid db resource-id))
        filters'     (cond-> filters
                       subject-id (assoc :subject/id subject-eid)
                       resource-id (assoc :resource/id resource-eid))]
    (->> (impl/read-relationships db filters')
      (map #(relationship->spice db opts %)))))

(defn spice-relationship->internal
  [db {:keys [spice-object->internal]} {:keys [subject relation resource]}]
  {:subject (spice-object->internal db subject)
   :relation relation
   :resource (spice-object->internal db resource)})

(defn spiceomic-write-relationships!
  [conn opts updates]
  (let [db (d/db conn)
        tx-data (->> updates
                  (S/transform [S/ALL :relationship]
                    #(spice-relationship->internal db opts %))
                  (mapcat #(impl/tx-update-relationship db %))
                  (remove nil?))
        {:keys [db-after]} @(d/transact conn tx-data)
        basis (d/basis-t db-after)]
    {:zed/token (str basis)}))

(defn spiceomic-can?
  [db {:keys [object->entid]} subject permission resource consistency]
  (assert (= consistency/fully-consistent consistency)
    "EACL only supports consistency/fully-consistent at this time.")
  (let [subject-type (:type subject)
        subject-eid  (object->entid db subject)
        resource-type (:type resource)
        resource-eid  (object->entid db resource)]
    (if-not (and subject-eid resource-eid)
      false
      (impl/can? db
        (spice-object subject-type subject-eid)
        permission
        (spice-object resource-type resource-eid)))))

(defn spiceomic-lookup-resources
  [db
   {:as opts
    :keys [spice-object->internal
           entid->object-id
           object-id->ident
           internal-cursor->spice
           spice-cursor->internal]}
   {:as query :keys [subject]}]
  (log/debug 'spiceomic-lookup-resources 'query query)
  (let [internal-subject (spice-object->internal db subject)]
    (assert (:id internal-subject)
      (str "subject " (pr-str subject)
           " passed to lookup-resources does not exist with ident "
           (object-id->ident (:id subject))))
    (->> query
      (S/setval [:subject] internal-subject)
      (S/transform [:cursor]
        (fn [token-or-cursor]
          (some->> (token->cursor token-or-cursor)
            (spice-cursor->internal db opts))))
      (impl/lookup-resources db)
      (S/transform [:data S/ALL]
        (fn [{:keys [type id]}]
          (spice-object type (entid->object-id db id))))
      (S/transform [:cursor]
        (fn [internal-cursor]
          (some->> (internal-cursor->spice db opts internal-cursor)
            cursor->token))))))

(defn spiceomic-count-resources
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   {:as query :keys [subject]}]
  (let [subject-ent (spice-object->internal db subject)]
    (assert (:id subject-ent)
      (str "subject passed to count-resources does not exist: " (pr-str subject)))
    (assert (= (:type subject-ent) (:type subject))
      (str "count-resources: subject type passed does not match entity: "
           (pr-str subject)))
    (->> query
      (S/setval [:subject] subject-ent)
      (S/transform [:cursor]
        (fn [token-or-cursor]
          (some->> (token->cursor token-or-cursor)
            (spice-cursor->internal db opts))))
      (impl/count-resources db)
      (S/transform [:cursor]
        (fn [internal-cursor]
          (some->> (internal-cursor->spice db opts internal-cursor)
            cursor->token))))))

(defn spiceomic-lookup-subjects
  [db
   {:as opts
    :keys [entid->object-id
           spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (->> query
    (S/transform [:resource] #(spice-object->internal db %))
    (S/transform [:cursor]
      (fn [token-or-cursor]
        (some->> (token->cursor token-or-cursor)
          (spice-cursor->internal db opts))))
    (impl/lookup-subjects db)
    (S/transform [:data S/ALL]
      (fn [{:keys [type id]}]
        (spice-object type (entid->object-id db id))))
    (S/transform [:cursor]
      (fn [internal-cursor]
        (some->> (internal-cursor->spice db opts internal-cursor)
          cursor->token)))))

(defn spiceomic-count-subjects
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (->> query
    (S/transform [:resource] #(spice-object->internal db %))
    (S/transform [:cursor]
      (fn [token-or-cursor]
        (some->> (token->cursor token-or-cursor)
          (spice-cursor->internal db opts))))
    (impl/count-subjects db)
    (S/transform [:cursor]
      (fn [internal-cursor]
        (some->> (internal-cursor->spice db opts internal-cursor)
          cursor->token)))))

(defrecord Spiceomic [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (spiceomic-can? (d/db conn) opts subject permission resource consistency/fully-consistent))

  (can? [_ subject permission resource consistency]
    (spiceomic-can? (d/db conn) opts subject permission resource consistency))

  (can? [_ {:keys [subject permission resource consistency]}]
    (spiceomic-can? (d/db conn) opts subject permission resource
      (or consistency consistency/fully-consistent)))

  (read-schema [_]
    (schema/read-schema (d/db conn)))

  (write-schema! [_ schema-string]
    (schema/write-schema! conn schema-string))

  (read-relationships [_ filters]
    (spiceomic-read-relationships (d/db conn) opts filters))

  (write-relationships! [_ updates]
    (spiceomic-write-relationships! conn opts updates))

  (write-relationship! [_ operation subject relation resource]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate operation
         (->Relationship subject relation resource))]))

  (write-relationship! [_ {:as demand :keys [operation subject relation resource]}]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate operation
         (->Relationship subject relation resource))]))

  (create-relationships! [_ relationships]
    (spiceomic-write-relationships! conn opts
      (for [rel relationships]
        (->RelationshipUpdate :create rel))))

  (create-relationship! [_ relationship]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :create relationship)]))

  (create-relationship! [_ subject relation resource]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :create (->Relationship subject relation resource))]))

  (delete-relationships! [_ relationships]
    (spiceomic-write-relationships! conn opts
      (for [rel relationships]
        (->RelationshipUpdate :delete rel))))

  (delete-relationship! [_ {:as relationship :keys [subject relation resource]}]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :delete
         (->Relationship subject relation resource))]))

  (delete-relationship! [_ subject relation resource]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :delete
         (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (spiceomic-lookup-resources (d/db conn) opts query))

  (count-resources [_ query]
    (spiceomic-count-resources (d/db conn) opts query))

  (lookup-subjects [_ query]
    (spiceomic-lookup-subjects (d/db conn) opts query))

  (count-subjects [_ query]
    (spiceomic-count-subjects (d/db conn) opts query))

  (expand-permission-tree [_ _]
    (throw (Exception. "not impl."))))

(defn make-client
  [conn
   {:keys [entity->object-id
           object-id->ident
           internal-cursor->spice
           spice-cursor->internal]
    :or   {entity->object-id      (fn [ent] (:eacl/id ent))
           object-id->ident       (fn [obj-id] [:eacl/id obj-id])
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (assert (fn? object-id->ident)
    "EACL Config Error: object-id->ident fn is required to coerce a Spice Object ID to a Datomic ident that can be resolved by d/entid.")
  (let [object-id->entid (fn [db object-id]
                           (d/entid db (object-id->ident object-id)))
        entid->object-id (fn [db eid]
                           (entity->object-id (d/entity db eid)))
        opts             {:object-id->ident object-id->ident
                          :entid->object-id entid->object-id
                          :entity->object-id entity->object-id
                          :object-id->entid object-id->entid
                          :object->entid (fn [db {:keys [id]}]
                                           (object-id->entid db id))
                          :internal-object->spice (fn [db {:keys [type id]}]
                                                    (spice-object type (entid->object-id db id)))
                          :spice-object->internal (fn [db obj]
                                                    (update obj :id #(object-id->entid db %)))
                          :internal-cursor->spice internal-cursor->spice
                          :spice-cursor->internal spice-cursor->internal}]
    (->Spiceomic conn opts)))
