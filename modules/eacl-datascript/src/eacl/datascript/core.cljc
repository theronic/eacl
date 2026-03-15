(ns eacl.datascript.core
  (:require [com.rpl.specter :as S]
            [datascript.core :as ds]
            [eacl.cursor :as cursor]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.schema :as schema]
            [eacl.spicedb.consistency :as consistency]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(defn default-internal-cursor->spice
  [db {:keys [entid->object-id]} cursor]
  (when cursor
    (cond
      (= 3 (:v cursor))
      (cond-> cursor
        (:subject cursor) (update :subject #(entid->object-id db %))
        (:resource cursor) (update :resource #(entid->object-id db %)))

      (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(entid->object-id db %))
        (:p cursor) (update :p
                       (fn [p]
                         (into {}
                           (map (fn [[k v]] [k (entid->object-id db v)]))
                           p))))
      :else
      (cond
        (:resource cursor) (S/transform [:resource :id] #(entid->object-id db %) cursor)
        (:subject cursor) (S/transform [:subject :id] #(entid->object-id db %) cursor)))))

(defn default-spice-cursor->internal
  [db {:keys [object-id->entid]} cursor]
  (when cursor
    (cond
      (= 3 (:v cursor))
      (cond-> cursor
        (:subject cursor) (update :subject #(object-id->entid db %))
        (:resource cursor) (update :resource #(object-id->entid db %)))

      (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(object-id->entid db %))
        (:p cursor) (update :p
                       (fn [p]
                         (into {}
                           (map (fn [[k v]] [k (object-id->entid db v)]))
                           p))))
      :else
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

(defn datascript-read-relationships
  [db
   {:as opts
    :keys [object-id->entid
           internal-cursor->spice
           spice-cursor->internal]}
   filters]
  (let [subject-id   (:subject/id filters)
        resource-id  (:resource/id filters)
        subject-eid  (when subject-id (object-id->entid db subject-id))
        resource-eid (when resource-id (object-id->entid db resource-id))
        missing-id?  (or (and subject-id (nil? subject-eid))
                         (and resource-id (nil? resource-eid)))]
    (if missing-id?
      {:data [] :cursor nil}
      (let [filters' (cond-> filters
                       subject-id (assoc :subject/id subject-eid)
                       resource-id (assoc :resource/id resource-eid))
            filters'' (S/transform [:cursor]
                        (fn [token-or-cursor]
                          (some->> (token->cursor token-or-cursor)
                                   (spice-cursor->internal db opts)))
                        filters')
            result    (impl/read-relationships db filters'')]
        (-> result
            ((fn [page]
               (S/transform [:data S/ALL]
                 #(relationship->spice db opts %)
                 page)))
            ((fn [page]
               (S/transform [:cursor]
                 (fn [internal-cursor]
                   (some->> (internal-cursor->spice db opts internal-cursor)
                            cursor->token))
                 page))))))))

(defn spice-relationship->internal
  [db {:keys [spice-object->internal]} {:keys [subject relation resource]}]
  {:subject (spice-object->internal db subject)
   :relation relation
   :resource (spice-object->internal db resource)})

(defn datascript-write-relationships!
  [conn opts updates]
  (let [db      (ds/db conn)
        tx-stamp (:tx-stamp opts)
        tx-data (->> updates
                  (S/transform [S/ALL :relationship]
                    #(spice-relationship->internal db opts %))
                  (mapcat #(impl/tx-update-relationship db %))
                  (remove nil?))]
    (when (seq tx-data)
      (ds/transact! conn tx-data))
    {:zed/token (str @tx-stamp)}))

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn datascript-can?
  [db {:keys [object->entid] :as opts} subject permission resource consistency]
  (assert (= consistency/fully-consistent consistency)
    "EACL only supports consistency/fully-consistent at this time.")
  (let [subject-type (:type subject)
        subject-id   (object->entid db subject)
        resource-type (:type resource)
        resource-id   (object->entid db resource)]
    (if-not (and subject-id resource-id)
      false
      (impl/can? db
        opts
        (spice-object subject-type subject-id)
        permission
        (spice-object resource-type resource-id)))))

(defn datascript-lookup-resources
  [db
   {:as opts
    :keys [spice-object->internal
           entid->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal]}
   {:as query :keys [subject]}]
  (let [internal-subject (spice-object->internal db subject)]
    (assert (:id internal-subject)
      (str "subject " (pr-str subject)
           " passed to lookup-resources does not exist with lookup ref "
           (object-id->lookup-ref (:id subject))))
    (->> query
      (S/setval [:subject] internal-subject)
      (S/transform [:cursor]
        (fn [token-or-cursor]
          (some->> (token->cursor token-or-cursor)
            (spice-cursor->internal db opts))))
      (impl/lookup-resources db opts)
      (S/transform [:data S/ALL]
        (fn [{:keys [type id]}]
          (spice-object type (entid->object-id db id))))
      (S/transform [:cursor]
        (fn [internal-cursor]
          (some->> (internal-cursor->spice db opts internal-cursor)
            cursor->token))))))

(defn datascript-count-resources
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   {:as query :keys [subject]}]
  (let [subject-ent (spice-object->internal db subject)]
    (->> query
      (S/setval [:subject] subject-ent)
      (S/transform [:cursor]
        (fn [token-or-cursor]
          (some->> (token->cursor token-or-cursor)
            (spice-cursor->internal db opts))))
      (impl/count-resources db opts)
      (S/transform [:cursor]
        (fn [internal-cursor]
          (some->> (internal-cursor->spice db opts internal-cursor)
            cursor->token))))))

(defn datascript-lookup-subjects
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
    (impl/lookup-subjects db opts)
    (S/transform [:data S/ALL]
      (fn [{:keys [type id]}]
        (spice-object type (entid->object-id db id))))
    (S/transform [:cursor]
      (fn [internal-cursor]
        (some->> (internal-cursor->spice db opts internal-cursor)
          cursor->token)))))

(defn datascript-count-subjects
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
    (impl/count-subjects db opts)
    (S/transform [:cursor]
      (fn [internal-cursor]
        (some->> (internal-cursor->spice db opts internal-cursor)
          cursor->token)))))

(defrecord DataScriptAuthorization [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (datascript-can? (ds/db conn) opts subject permission resource consistency/fully-consistent))
  (can? [_ subject permission resource consistency]
    (datascript-can? (ds/db conn) opts subject permission resource consistency))
  (can? [_ {:keys [subject permission resource consistency]}]
    (datascript-can? (ds/db conn) opts subject permission resource
      (or consistency consistency/fully-consistent)))

  (read-schema [_]
    (schema/read-schema (ds/db conn)))
  (write-schema! [_ schema-string]
    (schema/write-schema! conn schema-string))

  (read-relationships [_ filters]
    (datascript-read-relationships (ds/db conn) opts filters))
  (write-relationships! [_ updates]
    (datascript-write-relationships! conn opts updates))
  (write-relationship! [_ operation subject relation resource]
    (datascript-write-relationships! conn opts
      [(->RelationshipUpdate operation
         (->Relationship subject relation resource))]))
  (write-relationship! [_ {:as demand :keys [operation subject relation resource]}]
    (datascript-write-relationships! conn opts
      [(->RelationshipUpdate operation
         (->Relationship subject relation resource))]))
  (create-relationships! [_ relationships]
    (datascript-write-relationships! conn opts
      (for [rel relationships]
        (->RelationshipUpdate :create rel))))
  (create-relationship! [_ relationship]
    (datascript-write-relationships! conn opts
      [(->RelationshipUpdate :create relationship)]))
  (create-relationship! [_ subject relation resource]
    (datascript-write-relationships! conn opts
      [(->RelationshipUpdate :create (->Relationship subject relation resource))]))
  (delete-relationships! [_ relationships]
    (datascript-write-relationships! conn opts
      (for [rel (relationship-seq relationships)]
        (->RelationshipUpdate :delete rel))))
  (delete-relationship! [_ {:as relationship :keys [subject relation resource]}]
    (datascript-write-relationships! conn opts
      [(->RelationshipUpdate :delete
         (->Relationship subject relation resource))]))
  (delete-relationship! [_ subject relation resource]
    (datascript-write-relationships! conn opts
      [(->RelationshipUpdate :delete
         (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (datascript-lookup-resources (ds/db conn) opts query))
  (count-resources [_ query]
    (datascript-count-resources (ds/db conn) opts query))
  (lookup-subjects [_ query]
    (datascript-lookup-subjects (ds/db conn) opts query))
  (count-subjects [_ query]
    (datascript-count-subjects (ds/db conn) opts query))

  (expand-permission-tree [_ _]
    (throw (ex-info "not impl." {}))))

(defonce runtime-state-registry (atom {}))

(defn- schema-transaction?
  [tx-report]
  (boolean
   (some (fn [{:keys [a]}]
           (contains? schema/schema-change-attrs a))
         (:tx-data tx-report))))

(defn- reset-schema-derived-state!
  [state]
  (swap! (:schema-stamp state) inc)
  (reset! (:permission-paths-cache state) {})
  (reset! (:schema-catalog state) nil))

(defn ensure-runtime-state!
  [conn]
  (if-let [state (get @runtime-state-registry conn)]
    state
    (let [state {:conn-id (random-uuid)
                 :tx-stamp (atom 0)
                 :schema-stamp (atom 0)
                 :permission-paths-cache (atom {})
                 :schema-catalog (atom nil)
                 :listener-key (keyword (str "eacl-stamp-" (random-uuid)))}]
      (ds/listen! conn
        (:listener-key state)
        (fn [tx-report]
          (swap! (:tx-stamp state) inc)
          (when (schema-transaction? tx-report)
            (reset-schema-derived-state! state))))
      (get (swap! runtime-state-registry
             #(if (contains? % conn) % (assoc % conn state)))
        conn))))

(defn- ensure-schema-catalog!
  [state db]
  (let [schema-stamp @(:schema-stamp state)
        cached       @(:schema-catalog state)]
    (if (= schema-stamp (:schema-stamp cached))
      (:catalog cached)
      (let [catalog (impl/build-schema-catalog db)]
        (-> (swap! (:schema-catalog state)
                   (fn [entry]
                     (if (= schema-stamp (:schema-stamp entry))
                       entry
                       {:schema-stamp schema-stamp
                        :catalog      catalog})))
            :catalog)))))

(defn make-client
  [conn
   {:keys [entity->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal]
    :or   {entity->object-id      (fn [ent] (:eacl/id ent))
           object-id->lookup-ref  (fn [obj-id] [:eacl/id obj-id])
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (let [runtime-state   (ensure-runtime-state! conn)
        object-id->entid (fn [db object-id]
                           (ds/entid db (object-id->lookup-ref object-id)))
        entid->object-id (fn [db eid]
                           (entity->object-id (ds/entity db eid)))
        opts             {:object-id->lookup-ref object-id->lookup-ref
                          :cache-stamp (fn []
                                         [(:conn-id runtime-state)
                                          @(:schema-stamp runtime-state)])
                          :tx-stamp (:tx-stamp runtime-state)
                          :permission-paths-cache (:permission-paths-cache runtime-state)
                          :schema-catalog (fn [db]
                                            (ensure-schema-catalog! runtime-state db))
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
    (->DataScriptAuthorization conn opts)))

(defn create-conn
  ([] (schema/create-conn))
  ([extra-schema]
   (schema/create-conn extra-schema)))
