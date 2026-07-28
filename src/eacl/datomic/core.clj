(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [com.rpl.specter :as S]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.spicedb.consistency :as consistency]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def ^:private page-token-prefix "eacl3_")
(def ^:private page-token-version 4)
(def ^:private default-page-token-ttl-seconds 300)
(def ^:private secure-random (SecureRandom.))

(defn- now-seconds []
  (quot (System/currentTimeMillis) 1000))

(defn- utf8-bytes [s]
  (.getBytes (str s) StandardCharsets/UTF_8))

(defn- b64url-encode [^bytes bytes]
  (.encodeToString (Base64/getUrlEncoder) bytes))

(defn- b64url-decode [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn- random-bytes [n]
  (let [bytes (byte-array n)]
    (.nextBytes secure-random bytes)
    bytes))

(defn- sha-256 [^bytes bytes]
  (.digest (MessageDigest/getInstance "SHA-256") bytes))

(defn- normalize-token-key [key-material]
  (cond
    (bytes? key-material)
    (if (#{16 24 32} (alength ^bytes key-material))
      key-material
      (sha-256 key-material))

    (string? key-material)
    (sha-256 (utf8-bytes key-material))

    :else
    (throw (ex-info "Page token key must be bytes or string key material."
                    {:key-material-class (some-> key-material class str)}))))

(defn- canonicalize
  [x]
  (cond
    (map? x)
    (into (sorted-map)
          (map (fn [[k v]] [k (canonicalize v)]))
          x)

    (set? x)
    (mapv canonicalize (sort x))

    (sequential? x)
    (mapv canonicalize x)

    :else x))

(defn- stable-hash
  [x]
  (b64url-encode (sha-256 (utf8-bytes (pr-str (canonicalize x))))))

(defn- encrypt-aead
  [^bytes key ^bytes nonce ^bytes aad ^bytes plaintext]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/ENCRYPT_MODE
           (SecretKeySpec. key "AES")
           (GCMParameterSpec. 128 nonce))
    (.updateAAD cipher aad)
    (.doFinal cipher plaintext)))

(defn- decrypt-aead
  [^bytes key ^bytes nonce ^bytes aad ^bytes ciphertext]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/DECRYPT_MODE
           (SecretKeySpec. key "AES")
           (GCMParameterSpec. 128 nonce))
    (.updateAAD cipher aad)
    (.doFinal cipher ciphertext)))

(defn encrypt-page-token
  [opts payload]
  (when payload
    (let [{:keys [page-token-current-kid page-token-keyring]} opts
          kid page-token-current-kid
          key (get page-token-keyring kid)
          nonce (random-bytes 12)
          header {:v page-token-version
                  :kid kid
                  :nonce (b64url-encode nonce)}
          aad (utf8-bytes (pr-str (canonicalize header)))
          plaintext (utf8-bytes (pr-str (canonicalize payload)))
          ciphertext (encrypt-aead key nonce aad plaintext)]
      (str page-token-prefix
           (b64url-encode
            (utf8-bytes
             (pr-str (assoc header :ciphertext (b64url-encode ciphertext)))))))))

(defn decrypt-page-token
  [opts token]
  (when token
    (when-not (and (string? token)
                   (.startsWith ^String token page-token-prefix))
      (throw (ex-info "Invalid page token." {:token token})))
    (try
      (let [{:keys [page-token-keyring]} opts
            envelope (edn/read-string
                      (String. (b64url-decode (subs token (count page-token-prefix)))
                               StandardCharsets/UTF_8))
            {:keys [v kid nonce ciphertext]} envelope
            key (get page-token-keyring kid)]
        (when-not (= page-token-version v)
          (throw (ex-info "Unsupported page token version." {:version v})))
        (when-not key
          (throw (ex-info "Unknown page token key id." {:kid kid})))
        (let [header {:v v :kid kid :nonce nonce}
              aad (utf8-bytes (pr-str (canonicalize header)))
              plaintext (decrypt-aead key
                                      (b64url-decode nonce)
                                      aad
                                      (b64url-decode ciphertext))
              payload (edn/read-string (String. plaintext StandardCharsets/UTF_8))
              now (now-seconds)]
          (when-not (= page-token-version (:v payload))
            (throw (ex-info "Unsupported page token payload version."
                            {:version (:v payload)})))
          (when (and (:exp payload) (<= (:exp payload) now))
            (throw (ex-info "Expired page token." {:exp (:exp payload)
                                                   :now now})))
          payload))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw (ex-info "Invalid page token." {:token token} e))))))

(defn page-token
  [opts {:keys [ttl-seconds] :or {ttl-seconds default-page-token-ttl-seconds} :as payload}]
  (encrypt-page-token opts
                      (-> payload
                          (dissoc :ttl-seconds)
                          (assoc :v page-token-version
                                 :exp (+ (now-seconds) ttl-seconds)))))

(defn token->page-bound
  [opts token]
  (decrypt-page-token opts token))

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

(defn- reject-live-basis!
  [query]
  (when (= :live (:page/basis query))
    (throw (ex-info ":page/basis :live is reserved and not implemented yet."
                    {:page/basis :live}))))

(defn- decoded-page-bound
  [opts page-req]
  (some->> (:bound page-req)
           (token->page-bound opts)))

(defn- pagination-context
  [conn opts query]
  (reject-live-basis! query)
  (let [page-req (impl.indexed/normalize-page-request query)
        decoded (decoded-page-bound opts page-req)
        live-db (d/db conn)
        basis-t (or (:basis-t decoded) (d/basis-t live-db))
        pagination-db (if decoded
                        (d/as-of live-db basis-t)
                        live-db)]
    {:page-req page-req
     :decoded decoded
     :db pagination-db
     :basis-t basis-t}))

(defn- list-query-shape
  [op query]
  (stable-hash {:op op
                :basis :stable
                :query (dissoc query
                               :first :last :after :before
                               :cursor :limit :page/basis)}))

(defn- validate-page-token!
  [op query-shape decoded]
  (when decoded
    (when-not (= op (:op decoded))
      (throw (ex-info "Page token was created for a different operation."
                      {:expected op
                       :actual (:op decoded)})))
    (when-not (= query-shape (:query-shape decoded))
      (throw (ex-info "Page token does not match the current query."
                      {:expected query-shape
                       :actual (:query-shape decoded)})))
    (when-not (= :stable (:basis decoded))
      (throw (ex-info "Unsupported page token basis." {:basis (:basis decoded)})))
    true))

(defn- internal-page-query
  [query page-req decoded]
  (let [edge (:edge decoded)]
    (cond-> (dissoc query :after :before :page/basis)
      (and edge (= :asc (:direction page-req))) (assoc :after edge)
      (and edge (= :desc (:direction page-req))) (assoc :before edge))))

(defn- encode-page-cursor
  [opts op query-shape basis-t edge]
  (when edge
    (page-token opts
                (cond-> {:op op
                         :query-shape query-shape
                         :basis-t basis-t
                         :basis :stable
                         :edge edge}
                  (:page-token-ttl-seconds opts)
                  (assoc :ttl-seconds (:page-token-ttl-seconds opts))))))

(defn- encode-page-info
  [opts op query-shape basis-t page-info]
  (-> page-info
      (update :start-cursor #(encode-page-cursor opts op query-shape basis-t %))
      (update :end-cursor #(encode-page-cursor opts op query-shape basis-t %))))

(defn- coerce-lookup-page
  [db opts op query-shape basis-t page]
  (-> page
      (update :data
              (fn [data]
                (mapv (fn [{:keys [type id]}]
                        (spice-object type ((:entid->object-id opts) db id)))
                      data)))
      (update :page-info
              #(encode-page-info opts op query-shape basis-t %))))

(defn- coerce-relationship-page
  [db opts op query-shape basis-t page]
  (-> page
      (update :data #(mapv (fn [relationship]
                             (relationship->spice db opts relationship))
                           %))
      (update :page-info
              #(encode-page-info opts op query-shape basis-t %))))

(def ^:private empty-page
  "Unknown objects match nothing (SpiceDB-consistent, audit D9): lookups and
  reads over an object id that does not resolve to an existing entity return
  an empty page instead of asserting or degrading to a broader scan."
  {:data []
   :page-info {:start-cursor nil
               :end-cursor nil
               :has-next-page? false
               :has-previous-page? false}})

(defn spiceomic-read-relationships
  [conn
   {:keys [object-id->entid] :as opts}
   filters]
  (let [{:keys [db page-req decoded basis-t]} (pagination-context conn opts filters)
        subject-id   (:subject/id filters)
        resource-id  (:resource/id filters)
        subject-eid  (when (some? subject-id) (object-id->entid db subject-id))
        resource-eid (when (some? resource-id) (object-id->entid db resource-id))]
    (if (or (and (some? subject-id) (nil? subject-eid))
            (and (some? resource-id) (nil? resource-eid)))
      ;; A filter names an object that does not exist: nothing can match.
      ;; A supplied-but-unresolvable ID must not be conflated with an absent
      ;; filter — that conflation degraded this query to a global scan.
      empty-page
      (let [filters'     (cond-> filters
                           subject-id (assoc :subject/id subject-eid)
                           resource-id (assoc :resource/id resource-eid))
            query-shape  (list-query-shape :read-relationships filters')
            internal-query (internal-page-query filters' page-req decoded)]
        (validate-page-token! :read-relationships query-shape decoded)
        (coerce-relationship-page db opts :read-relationships query-shape basis-t
                                  (impl/read-relationships db internal-query))))))

(defn- resolve-existing-object
  "Resolves an external spice object to its internal eid, verifying the entity
  actually exists. Existence is checked via datom presence because d/entid
  passes unallocated numeric eids through unchanged. Throws :eacl/unknown-object
  when the object cannot be resolved to an existing entity."
  [db object-id->entid {:keys [type id] :as obj}]
  (let [eid (when (some? id) (object-id->entid db id))]
    (if (and eid (seq (d/datoms db :eavt eid)))
      (assoc obj :id eid)
      (throw (ex-info (str "Unknown object: " (pr-str type) " with id " (pr-str id) " does not exist.")
               {:type :eacl/unknown-object
                :object {:type type :id id}})))))

(defn spice-relationship->internal
  "Resolves both relationship endpoints to existing internal eids.
  Throws :eacl/unknown-object for either endpoint rather than letting nils or
  ghost ids reach tx-data (raw :db.error/not-an-entity) or silently no-op."
  [db {:keys [object-id->entid]} {:keys [subject relation resource]}]
  {:subject (resolve-existing-object db object-id->entid subject)
   :relation relation
   :resource (resolve-existing-object db object-id->entid resource)})

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
  (when-not (= consistency/fully-consistent consistency)
    (throw (ex-info "EACL only supports consistency/fully-consistent at this time."
             {:type :eacl/unsupported-consistency
              :consistency consistency})))
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
  [conn
   {:as opts
    :keys [spice-object->internal
           entid->object-id
           object-id->ident]}
   {:as query :keys [subject]}]
  (log/debug 'spiceomic-lookup-resources 'query query)
  (let [{:keys [db page-req decoded basis-t]} (pagination-context conn opts query)
        internal-subject (spice-object->internal db subject)]
    (if (nil? (:id internal-subject))
      ;; Unknown subjects match nothing (SpiceDB-consistent; can? is false).
      empty-page
      (let [query' (assoc query :subject internal-subject)
            query-shape (list-query-shape :lookup-resources query')
            internal-query (internal-page-query query' page-req decoded)]
        (validate-page-token! :lookup-resources query-shape decoded)
        (coerce-lookup-page db opts :lookup-resources query-shape basis-t
                            (impl/lookup-resources db internal-query))))))

(defn spiceomic-count-resources
  [db
   {:as opts
    :keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (let [subject-ent (spice-object->internal db subject)]
    (if (nil? (:id subject-ent))
      ;; Unknown subjects match nothing (SpiceDB-consistent; can? is false).
      {:count 0 :limit -1}
      (->> query
           (S/setval [:subject] subject-ent)
           (impl/count-resources db)))))

(defn spiceomic-lookup-subjects
  [conn
   {:as opts
    :keys [entid->object-id
           spice-object->internal]}
   query]
  (let [{:keys [db page-req decoded basis-t]} (pagination-context conn opts query)
        internal-resource (spice-object->internal db (:resource query))]
    (if (nil? (:id internal-resource))
      ;; Unknown resources match nothing (SpiceDB-consistent).
      empty-page
      (let [query' (assoc query :resource internal-resource)
            query-shape (list-query-shape :lookup-subjects query')
            internal-query (internal-page-query query' page-req decoded)]
        (validate-page-token! :lookup-subjects query-shape decoded)
        (coerce-lookup-page db opts :lookup-subjects query-shape basis-t
                            (impl/lookup-subjects db internal-query))))))

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
    (spiceomic-read-relationships conn opts filters))

  (write-relationships! [_ updates]
    (spiceomic-write-relationships! conn opts updates))

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

  ;; Audit §13: these were declared on the protocol but unimplemented ->
  ;; AbstractMethodError at runtime.
  (write-relationship! [_ operation subject relation resource]
    (spiceomic-write-relationships! conn opts
                                    [(->RelationshipUpdate operation (->Relationship subject relation resource))]))

  (write-relationship! [_ {:keys [operation subject relation resource]}]
    (spiceomic-write-relationships! conn opts
                                    [(->RelationshipUpdate operation (->Relationship subject relation resource))]))

  (delete-relationship! [_ subject relation resource]
    (spiceomic-write-relationships! conn opts
                                    [(->RelationshipUpdate :delete (->Relationship subject relation resource))]))

  (delete-relationship! [_ {:keys [subject relation resource]}]
    (spiceomic-write-relationships! conn opts
                                    [(->RelationshipUpdate :delete (->Relationship subject relation resource))]))

  (delete-relationships! [_ relationships]
    (let [relationships' (if (map? relationships)
                           (:data relationships)
                           relationships)]
      (spiceomic-write-relationships! conn opts
                                      (for [rel relationships']
                                        (->RelationshipUpdate :delete rel)))))

  (lookup-resources [_ query]
    (spiceomic-lookup-resources conn opts query))

  (count-resources [_ query]
    (spiceomic-count-resources (d/db conn) opts query))

  (lookup-subjects [_ query]
    (spiceomic-lookup-subjects conn opts query))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
             {:type :eacl/not-implemented
              :method 'expand-permission-tree}))))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :entity->object-id
    :object-id->ident
    :page-token-key
    :page-token-keys
    :page-token-keyring
    :page-token-kid
    :page-token-ttl-seconds
    :auto-migrate-v6})

(defn make-client
  "Builds an IAuthorization client over a Datomic conn.

  Options (unknown keys throw :eacl/invalid-config — a silently ignored key
  means silently wrong ID coercion):
  - :entid->object-id  (fn [db eid] external-id) — canonical ID coercion, as documented in the README.
  - :entity->object-id (fn [entity] external-id) — deprecated alias; do not combine with the above.
  - :object-id->ident  (fn [external-id] ident-resolvable-by-d-entid). Default: [:eacl/id id].
  - :page-token-key / :page-token-keys / :page-token-keyring / :page-token-kid —
    AES-GCM page-token key material. Default: a random per-process key, meaning
    page tokens do not survive restarts and are not portable across peers;
    supply stable key material in production.
  - :page-token-ttl-seconds — overrides the default page-token expiry.
  - :auto-migrate-v6 — opt-in automatic v6->v7 storage migration at startup.
    Construction fails with {:type :eacl/storage-version} when the database
    holds unmigrated v6 relationship entities (v7 would silently answer false/
    empty against them). Pass true (default options) or an eacl.migrations.v6-to-v7/migrate!
    options map, e.g. {:schema \"definition user {} ...\"} — see docs/migration-v6-to-v7.md."
  [conn
   {:as   config-opts
    :keys [entid->object-id
           entity->object-id
           object-id->ident
           page-token-key
           page-token-keys
           page-token-keyring
           page-token-kid
           page-token-ttl-seconds
           auto-migrate-v6]
    :or   {object-id->ident (fn [obj-id] [:eacl/id obj-id])}}]
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
  (when-not (fn? object-id->ident)
    (throw (ex-info "EACL Config Error: object-id->ident must be a fn that coerces a Spice Object ID to a Datomic ident resolvable by d/entid."
             {:type :eacl/invalid-config
              :key :object-id->ident})))
  ;; Refuse to run v7 code against unmigrated v6 relationship data — it would
  ;; silently answer every check with false/empty. Throws :eacl/storage-version
  ;; unless the DB is v7/fresh/stamped, or :auto-migrate-v6 opts into migration.
  (migrations/assert-storage-compatible! conn {:auto-migrate-v6 auto-migrate-v6})
  (let [entid->object-id   (or entid->object-id
                               (when entity->object-id
                                 (fn [db eid] (entity->object-id (d/entity db eid))))
                               (fn [db eid] (:eacl/id (d/entity db eid))))
        object-id->entid   (fn [db object-id]
                             (d/entid db (object-id->ident object-id)))
        current-kid        (or page-token-kid :current)
        configured-keyring (or page-token-keyring page-token-keys)
        keyring            (if configured-keyring
                             (into {}
                                   (map (fn [[kid key]]
                                          [kid (normalize-token-key key)]))
                                   configured-keyring)
                             {current-kid (if page-token-key
                                            (normalize-token-key page-token-key)
                                            (random-bytes 32))})
        _                  (when-not (get keyring current-kid)
                             (throw (ex-info "Page token current key id is not present in keyring."
                                             {:page-token-kid current-kid
                                              :available-kids (set (keys keyring))})))
        opts               {:object-id->ident object-id->ident
                            :entid->object-id entid->object-id
                            :object-id->entid object-id->entid
                            :object->entid (fn [db {:keys [id]}]
                                             (object-id->entid db id))
                            :internal-object->spice (fn [db {:keys [type id]}]
                                                      (spice-object type (entid->object-id db id)))
                            :spice-object->internal (fn [db obj]
                                                      (update obj :id #(when (some? %) (object-id->entid db %))))
                            :page-token-current-kid current-kid
                            :page-token-keyring keyring
                            :page-token-ttl-seconds page-token-ttl-seconds}]
    (->Spiceomic conn opts)))
