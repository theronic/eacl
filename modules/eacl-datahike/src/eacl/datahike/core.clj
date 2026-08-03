(ns eacl.datahike.core
  (:require [com.rpl.specter :as S]
            [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.cursor :as cursor]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.mutation :as journal]
            [eacl.datahike.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.mutation :as mutation]
            [eacl.relay :as relay]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.relationships.relay :as relationship-relay]
            [eacl.secure-format :as secure]
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

(defn- snapshot-adapter
  [db opts]
  (datahike-backend/snapshot-adapter db opts))

(defn- selected-context
  [db opts consistency-value]
  (let [selection
        (consistency-v3/select
         (snapshot-adapter db opts)
         consistency-value
         {:format-options (:format-options opts)
          :coherence-authority (:coherence-authority opts)
          :issue-token? true
          :timeout-ms (:consistency-sync-timeout-ms opts)})
        adapter (:adapter selection)]
    {:adapter adapter
     :db (:db (backend/state adapter))
     :selection selection}))

(defn- permission-dependencies
  [adapter resource-type permission]
  (let [relation-ids
        (engine/permission-relationship-eids
         adapter resource-type permission)]
    {:relation-ids relation-ids
     :schema-scope
     {:permission-nodes
      (engine/permission-schema-nodes
       adapter resource-type permission)
      :relation-ids relation-ids}}))

(defn- cursor-options
  [adapter opts selection resource-type permission]
  (binding [engine/*schema-cache*
            (engine/schema-cache-for!
             (:derived-schema-caches opts)
             adapter)]
    (assoc opts
           :cursor-dependencies
           (permission-dependencies
            adapter resource-type permission)
           :cursor-consistency-mode
           (get-in selection [:descriptor :mode])
           :timeout-ms (:consistency-sync-timeout-ms opts))))

(defn- page-context
  [adapter opts selection operation query resource-type permission]
  (let [current-opts
        (cursor-options
         adapter opts selection resource-type permission)
        page-adapter
        (relay/select-continuation-adapter
         adapter current-opts operation query)
        page-opts
        (cursor-options
         page-adapter opts selection resource-type permission)]
    {:adapter page-adapter
     :db (:db (backend/state page-adapter))
     :opts page-opts}))

(defn- pagination-snapshot-context
  [adapter]
  {:source-scope
   {:backend (backend/backend-id adapter)
    :scope (backend/invoke adapter :source-scope)}
   :graph-head (backend/invoke adapter :graph-head)
   :adapter-fingerprint (backend/fingerprint adapter)
   :identity-contract (backend/identity-contract adapter)})

(defn- cached-engine-result
  [adapter opts operation query resource-type permission
   valid-value? compute]
  (binding [engine/*schema-cache*
            (engine/schema-cache-for!
             (:derived-schema-caches opts)
             adapter)
            engine/*recursive-traversal-limits*
            (:recursive-traversal-limits opts)]
    (let [{:keys [schema-scope relation-ids]}
          (permission-dependencies adapter resource-type permission)]
      (cache/resolve!
       adapter
       (:cache-store opts)
       {:operation operation
        :query query
        :engine-version engine/engine-version
        :adapter-fingerprint (:adapter-fingerprint opts)
        :proof-mode (:proof-mode opts)
        :recursive-traversal-limits
        (:recursive-traversal-limits opts)}
       operation
       schema-scope
       relation-ids
       valid-value?
       compute
       (:format-options opts)))))

(defn- with-cache-info
  [value {:keys [cached? cache-basis]}]
  (if (map? value)
    (assoc value :cached? cached? :cache-basis cache-basis)
    value))

(defn datahike-read-relationships
  [db
   {:as opts
   :keys [object-id->entid
           internal-cursor->spice
           spice-cursor->internal]}
   filters]
  (let [{selected-db :db adapter :adapter selection :selection}
        (selected-context db opts (:consistency filters))
        base-filters (apply dissoc filters
                            [:first :last :after :before :consistency])
        _ (relationship-filters/validate! base-filters)
        items-for-adapter
        (fn [page-adapter]
          (let [page-db (:db (backend/state page-adapter))
                subject-id (:subject/id base-filters)
                resource-id (:resource/id base-filters)
                subject-eid (when subject-id
                              (object-id->entid page-db subject-id))
                resource-eid (when resource-id
                               (object-id->entid page-db resource-id))]
            (if (or (and subject-id (nil? subject-eid))
                    (and resource-id (nil? resource-eid)))
              []
              (let [filters'
                    (cond-> base-filters
                      subject-id (assoc :subject/id subject-eid)
                      resource-id (assoc :resource/id resource-eid))
                    internal-relationships
                    (loop [cursor nil
                           result []]
                      (let [page
                            (impl/read-relationships
                             page-db
                             (cond-> (assoc filters' :limit 10000)
                               cursor (assoc :cursor cursor)))
                            result' (into result (:data page))
                            next-cursor (:cursor page)]
                        (if (and next-cursor
                                 (not= cursor next-cursor)
                                 (seq (:data page)))
                          (recur next-cursor result')
                          result')))]
                (->> internal-relationships
                     (map #(relationship->spice page-db opts %))
                     (sort-by
                      (fn [{:keys [subject relation resource]}]
                        [(:type subject) (:id subject)
                         relation
                         (:type resource) (:id resource)]))
                     vec)))))
        cursor-opts
        (assoc opts
               :cursor-consistency-mode
               (get-in selection [:descriptor :mode])
               :relationship-adapter adapter
               :relationship-items-for-adapter items-for-adapter)]
    (relationship-relay/paginate
     cursor-opts
     :read-relationships
     filters
     (pagination-snapshot-context adapter)
     (items-for-adapter adapter))))

(defn spice-relationship->internal
  [db {:keys [spice-object->internal]} {:keys [subject relation resource]}]
  {:subject (spice-object->internal db subject)
   :relation relation
   :resource (spice-object->internal db resource)})

(defn- source-id
  [db family-id]
  (let [{:keys [backend id]} (get-in db [:config :store])]
    {:store-backend backend
     :store-id (str id)
     :family-id family-id}))

(defn- response-token
  [db opts]
  (when (= :managed (:coherence-authority opts))
    (let [adapter (snapshot-adapter db opts)]
      (causal-token/issue
       (:format-options opts)
       (merge
        {:backend :datahike}
        (backend/invoke adapter :source-scope)
        (backend/invoke adapter :graph-head))))))

(defn- write-response
  [db opts]
  (if-let [token (response-token db opts)]
    {:zed/token token}
    {}))

(defn datahike-write-relationships!
  [conn opts updates]
  (let [db (d/db conn)
        internal-updates
        (S/transform [S/ALL :relationship]
                     #(spice-relationship->internal db opts %)
                     updates)
        relation-ids
        (->> internal-updates
             (map (comp #(impl/relationship-relation-id db %)
                        :relationship))
             distinct
             vec)
        tx-data
        (->> internal-updates
             (mapcat #(impl/tx-update-relationship db %))
             (remove nil?)
             vec)]
    (if (seq tx-data)
      (let [report
            (journal/transact!
             conn
             {:mutation-id (mutation/new-id)
              :kind :relationships
              :canonical-data
              {:operation :write-relationships
               :updates internal-updates}
              :relation-ids relation-ids
              :token-ttl-seconds (:token-ttl-seconds opts)
              :retention-grace-seconds
              (:retention-grace-seconds opts)
              :tx-data tx-data})]
        (write-response (:db-after report) opts))
      (do
        (journal/ensure-migrated! conn)
        (write-response (d/db conn) opts)))))

(defn datahike-delete-object!
  "Removes every relationship that references `object`, without retracting the
  object entity itself."
  [conn {:keys [object->entid] :as opts} object]
  (let [db (d/db conn)]
    (when-let [object-eid (object->entid db object)]
      (let [relationship-eids
            (d/q '[:find [?relationship ...]
                   :in $ ?object
                   :where
                   (or [?relationship :eacl.relationship/subject ?object]
                       [?relationship :eacl.relationship/resource ?object])]
                 db object-eid)
            relation-ids
            (d/q '[:find [?relation ...]
                   :in $ [?relationship ...]
                   :where
                   [?relationship :eacl.relationship/relation ?relation]]
                 db relationship-eids)]
        (when (seq relationship-eids)
          (journal/transact!
           conn
           {:mutation-id (mutation/new-id)
            :kind :object-deletion
            :canonical-data
            {:operation :delete-object
             :object object
             :relationship-eids (vec (sort relationship-eids))}
            :relation-ids relation-ids
            :token-ttl-seconds (:token-ttl-seconds opts)
            :retention-grace-seconds
            (:retention-grace-seconds opts)
            :tx-data
            (mapv (fn [relationship-eid]
                    [:db/retractEntity relationship-eid])
                  relationship-eids)})))))
  (journal/ensure-migrated! conn)
  (write-response (d/db conn) opts))

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn datahike-can?
  [db {:keys [spice-object->internal] :as opts}
   subject permission resource consistency]
  (let [{selected-db :db adapter :adapter}
        (selected-context db opts consistency)
        internal-subject (spice-object->internal selected-db subject)
        internal-resource (spice-object->internal selected-db resource)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      false
      (:value
       (cached-engine-result
        adapter opts :can?
        {:public [subject permission resource]
         :internal
         [internal-subject permission internal-resource]}
        (:type internal-resource)
        permission
        boolean?
        #(engine/can?
          adapter internal-subject permission internal-resource))))))

(defn datahike-lookup-resources
  [db
   {:as opts
    :keys [spice-object->internal
           entid->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal]}
   {:as query :keys [subject]}]
  (let [{source-adapter :adapter selection :selection}
        (selected-context db opts (:consistency query))
        {adapter :adapter selected-db :db cursor-opts :opts}
        (page-context
         source-adapter opts selection :lookup-resources query
         (:resource/type query) (:permission query))
        internal-subject (spice-object->internal selected-db subject)]
    (if (nil? (:id internal-subject))
      (assoc relay/empty-page :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (dissoc :consistency)
                (#(relay/internalize-page-query
                   adapter cursor-opts :lookup-resources %))
                (assoc :subject internal-subject))
            answer
            (cached-engine-result
             adapter cursor-opts :lookup-resources
             {:public (dissoc query :consistency)
              :internal internal-query}
             (:resource/type internal-query)
             (:permission internal-query)
             #(and (map? %) (vector? (:data %))
                   (map? (:page-info %)))
             #(engine/lookup-resources adapter internal-query))]
        (with-cache-info
           (relay/externalize-page
           adapter cursor-opts :lookup-resources query (:value answer))
          answer)))))

(defn datahike-count-resources
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   {:as query :keys [subject]}]
  (let [{selected-db :db adapter :adapter selection :selection}
        (selected-context db opts (:consistency query))
        internal-subject (spice-object->internal selected-db subject)]
    (if-not (:id internal-subject)
      (assoc
       (cond-> {:count 0 :limit (or (:count-limit query) -1)}
         (contains? query :count-limit) (assoc :truncated? false))
       :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (assoc :subject internal-subject)
                (dissoc :consistency))
            answer
            (cached-engine-result
             adapter opts :count-resources
             {:public (dissoc query :consistency)
              :internal internal-query}
             (:resource/type internal-query)
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-resources adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn datahike-lookup-subjects
  [db
   {:as opts
    :keys [entid->object-id
           spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (let [{source-adapter :adapter selection :selection}
        (selected-context db opts (:consistency query))
        {adapter :adapter selected-db :db cursor-opts :opts}
        (page-context
         source-adapter opts selection :lookup-subjects query
         (:type (:resource query)) (:permission query))
        internal-resource
        (spice-object->internal selected-db (:resource query))]
    (when (contains? query :subject/relation)
      (throw (ex-info ":subject/relation is not supported by lookup-subjects."
                      {:eacl/error :eacl.pagination/unsupported-filter
                       :filter :subject/relation})))
    (if-not (:id internal-resource)
      (assoc relay/empty-page :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (dissoc :consistency)
                (#(relay/internalize-page-query
                   adapter cursor-opts :lookup-subjects %))
                (assoc :resource internal-resource))
            answer
            (cached-engine-result
             adapter cursor-opts :lookup-subjects
             {:public (dissoc query :consistency)
              :internal internal-query}
             (:type (:resource internal-query))
             (:permission internal-query)
             #(and (map? %) (vector? (:data %))
                   (map? (:page-info %)))
             #(engine/lookup-subjects adapter internal-query))]
        (with-cache-info
           (relay/externalize-page
           adapter cursor-opts :lookup-subjects query (:value answer))
          answer)))))

(defn datahike-count-subjects
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (let [{selected-db :db adapter :adapter}
        (selected-context db opts (:consistency query))
        internal-resource
        (spice-object->internal selected-db (:resource query))]
    (if-not (:id internal-resource)
      (assoc
       (cond-> {:count 0 :limit (or (:count-limit query) -1)}
         (contains? query :count-limit) (assoc :truncated? false))
       :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (assoc :resource internal-resource)
                (dissoc :consistency))
            answer
            (cached-engine-result
             adapter opts :count-subjects
             {:public (dissoc query :consistency)
              :internal internal-query}
             (:type (:resource internal-query))
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-subjects adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defrecord DatahikeAuthorization [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (datahike-can? (d/db conn) opts subject permission resource consistency/fully-consistent))
  (can? [_ subject permission resource consistency]
    (datahike-can? (d/db conn) opts subject permission resource consistency))
  (can? [_ {:keys [subject permission resource consistency]}]
    (datahike-can? (d/db conn) opts subject permission resource
                   (or consistency consistency/fully-consistent)))

  (read-schema [_]
    (schema/read-schema (d/db conn)))
  (write-schema! [_ schema-string]
    (let [result
          (schema/write-schema!
           conn schema-string
           (select-keys opts
                        [:token-ttl-seconds
                         :retention-grace-seconds]))]
      (merge result
             (write-response (:eacl.mutation/db-after result) opts))))

  (read-relationships [_ filters]
    (datahike-read-relationships (d/db conn) opts filters))
  (write-relationships! [_ updates]
    (datahike-write-relationships! conn opts updates))
  (write-relationship! [_ operation subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate operation
                                                          (->Relationship subject relation resource))]))
  (write-relationship! [_ {:as demand :keys [operation subject relation resource]}]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate operation
                                                          (->Relationship subject relation resource))]))
  (create-relationships! [_ relationships]
    (datahike-write-relationships! conn opts
                                   (for [rel relationships]
                                     (->RelationshipUpdate :create rel))))
  (create-relationship! [_ relationship]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :create relationship)]))
  (create-relationship! [_ subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :create (->Relationship subject relation resource))]))
  (delete-relationships! [_ relationships]
    (datahike-write-relationships! conn opts
                                   (for [rel (relationship-seq relationships)]
                                     (->RelationshipUpdate :delete rel))))
  (delete-object! [_ object]
    (datahike-delete-object! conn opts object))
  (delete-relationship! [_ {:as relationship :keys [subject relation resource]}]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :delete
                                                          (->Relationship subject relation resource))]))
  (delete-relationship! [_ subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :delete
                                                          (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (datahike-lookup-resources (d/db conn) opts query))
  (count-resources [_ query]
    (datahike-count-resources (d/db conn) opts query))
  (lookup-subjects [_ query]
    (datahike-lookup-subjects (d/db conn) opts query))
  (count-subjects [_ query]
    (datahike-count-subjects (d/db conn) opts query))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
                    {:type :eacl/not-implemented
                     :method (quote expand-permission-tree)}))))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :entity->object-id
    :object-id->lookup-ref
    :internal-cursor->spice
    :spice-cursor->internal
    :cursor-ttl-seconds
    :cache
    :recursive-traversal-limits
    :security-key
    :security-keyring
    :security-kid
    :token-ttl-seconds
    :retention-grace-seconds
    :coherence-authority
    :proof-mode
    :adapter-fingerprint
    :adapter-deterministic?
    :consistency-sync-timeout-ms})

(defn make-client
  "Builds an IAuthorization client over a datahike conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
  - :entity->object-id (fn [entity] external-id) - deprecated alias; do not combine.
  - :object-id->lookup-ref (fn [external-id] lookup-ref). Default: [:eacl/id id].
  - :cursor-ttl-seconds - optional cursor token expiry; default nil (tokens never expire).
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides."
  [conn
   {:as   config-opts
    :keys [entid->object-id
           entity->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal
           cursor-ttl-seconds
           cache
           recursive-traversal-limits
           security-key
           security-keyring
           security-kid
           token-ttl-seconds
           retention-grace-seconds
           coherence-authority
           proof-mode
           adapter-fingerprint
           adapter-deterministic?
           consistency-sync-timeout-ms]
    :or   {object-id->lookup-ref  (fn [obj-id] [:eacl/id obj-id])
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (when-let [unknown-keys (seq (remove known-client-opt-keys (keys config-opts)))]
    (throw (ex-info (str "EACL Config Error: unknown make-client option(s) " (pr-str (vec unknown-keys))
                         ". Known options: " (pr-str (vec (sort known-client-opt-keys))) ".")
                    {:type :eacl/invalid-config
                     :unknown-keys (vec unknown-keys)
                     :known-keys known-client-opt-keys})))
  (when (and entid->object-id entity->object-id)
    (throw (ex-info "EACL Config Error: supply only one of :entid->object-id (canonical) or :entity->object-id (deprecated alias)."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:entid->object-id :entity->object-id]})))
  (when (and security-key security-keyring)
    (throw (ex-info "EACL Config Error: supply only one of :security-key or :security-keyring."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:security-key :security-keyring]})))
  (when-not (contains? #{nil :unknown :managed} coherence-authority)
    (throw (ex-info "EACL Config Error: :coherence-authority must be :managed or :unknown."
                    {:type :eacl/invalid-config
                     :key :coherence-authority
                     :value coherence-authority})))
  (when-not (contains? #{nil :auto :mutation :content :none} proof-mode)
    (throw (ex-info "EACL Config Error: unsupported :proof-mode."
                    {:type :eacl/invalid-config
                     :key :proof-mode
                     :value proof-mode})))
  (when (and (= :mutation proof-mode)
             (not= :managed coherence-authority))
    (throw (ex-info "EACL Config Error: mutation proof requires managed writer authority."
                    {:type :eacl/invalid-config
                     :key :proof-mode
                     :value proof-mode})))
  (when (and (contains? config-opts :adapter-deterministic?)
             (not (boolean? adapter-deterministic?)))
    (throw (ex-info "EACL Config Error: :adapter-deterministic? must be boolean."
                    {:type :eacl/invalid-config
                     :key :adapter-deterministic?
                     :value adapter-deterministic?})))
  (when adapter-fingerprint
    (try
      (secure/encode-canonical adapter-fingerprint)
      (catch Exception error
        (throw (ex-info "EACL Config Error: :adapter-fingerprint must be portable canonical data."
                        {:type :eacl/invalid-config
                         :key :adapter-fingerprint}
                        error)))))
  (when (and token-ttl-seconds
             (not (and (integer? token-ttl-seconds)
                       (pos? token-ttl-seconds))))
    (throw (ex-info "EACL Config Error: :token-ttl-seconds must be positive."
                    {:type :eacl/invalid-config
                     :key :token-ttl-seconds
                     :value token-ttl-seconds})))
  (when (and consistency-sync-timeout-ms
             (not (and (integer? consistency-sync-timeout-ms)
                       (pos? consistency-sync-timeout-ms))))
    (throw (ex-info "EACL Config Error: :consistency-sync-timeout-ms must be positive."
                    {:type :eacl/invalid-config
                     :key :consistency-sync-timeout-ms
                     :value consistency-sync-timeout-ms})))
  (let [_ (journal/ensure-migrated! conn)
        coherence-authority (or coherence-authority :unknown)
        proof-mode (case (or proof-mode :auto)
                     :auto (if (= :managed coherence-authority)
                             :mutation
                             :content)
                     proof-mode)
        current-kid (or security-kid :default)
        root-keyring
        (cond
          security-keyring
          (into {} (map (fn [[kid key]]
                          [kid (secure/normalize-key key)]))
                security-keyring)

          security-key
          {current-kid (secure/normalize-key security-key)}

          :else
          {:default secure/default-root-key})
        _ (when-not (get root-keyring current-kid)
            (throw (ex-info "EACL Config Error: :security-kid is absent from :security-keyring."
                            {:type :eacl/invalid-config
                             :key :security-kid
                             :value current-kid})))
        format-options {:current-kid current-kid
                        :keyring root-keyring
                        :token-ttl-seconds
                        (or token-ttl-seconds
                            mutation/default-token-ttl-seconds)}
        object-id->entid (fn [db object-id]
                           (ddb/entid db (object-id->lookup-ref object-id)))
        custom-codec?
        (boolean
         (or entid->object-id
             entity->object-id
             (contains? config-opts :object-id->lookup-ref)))
        cache-eligible? (or (not custom-codec?)
                            (and (some? adapter-fingerprint)
                                 (true? adapter-deterministic?)))
        entid->object-id (or entid->object-id
                             (when entity->object-id
                               (fn [db eid] (entity->object-id (d/entity db eid))))
                             (fn [db eid] (:eacl/id (d/entity db eid))))
        opts             {:object-id->lookup-ref object-id->lookup-ref
                          :conn conn
                          :derived-schema-caches (atom {})
                          :adapter-fingerprint
                          (or adapter-fingerprint
                              {:backend :datahike
                               :adapter-version backend/adapter-version
                               :proof-mode proof-mode
                               :recursive-traversal-limits
                               recursive-traversal-limits
                               :codec
                               (if custom-codec?
                                 :custom-unfingerprinted
                                 :eacl-id-immutable-v1)})
                          :adapter-deterministic?
                          (if custom-codec?
                            (true? adapter-deterministic?)
                            true)
                          :entid->object-id entid->object-id
                          :object-id->entid object-id->entid
                          :cursor-ttl-seconds cursor-ttl-seconds
                          :format-options format-options
                          :coherence-authority coherence-authority
                          :proof-mode proof-mode
                          :consistency-sync-timeout-ms
                          (or consistency-sync-timeout-ms 30000)
                          :token-ttl-seconds
                          (or token-ttl-seconds
                              mutation/default-token-ttl-seconds)
                          :retention-grace-seconds
                          (or retention-grace-seconds
                              mutation/default-retention-grace-seconds)
                          :cache-store
                          (if cache-eligible?
                            (eacl.cache/cache-store cache)
                            eacl.cache/no-cache)
                          :recursive-traversal-limits
                          (engine/normalize-recursive-traversal-limits
                           recursive-traversal-limits)
                          :object->entid (fn [db {:keys [id]}]
                                           (object-id->entid db id))
                          :internal-object->spice (fn [db {:keys [type id]}]
                                                    (spice-object type (entid->object-id db id)))
                          :spice-object->internal (fn [db obj]
                                                    (update obj :id #(object-id->entid db %)))
                          :internal-cursor->spice internal-cursor->spice
                          :spice-cursor->internal spice-cursor->internal}]
    (->DatahikeAuthorization conn opts)))

(defn create-conn
  "A datahike connection carrying EACL's schema. See
  `eacl.datahike.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema))
  ([extra-schema config] (schema/create-conn extra-schema config)))
