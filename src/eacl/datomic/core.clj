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
            [eacl.datomic.cache :as cache]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.spicedb.consistency :as consistency]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [java.util.concurrent.locks Lock ReentrantReadWriteLock]
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

;; NOTE: the v2-era default-internal-cursor->spice / default-spice-cursor->internal
;; coercers were removed: cursors never leave the peer decrypted in the token
;; design, so nothing called them and their {:v 2 :e ... :p ...} shape no longer
;; exists anywhere.

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

(defn- validate-consistency!
  "EACL is always fully consistent. can? has thrown on any other requested
  consistency since v6, but the list/read APIs silently ignored the key, so a
  caller asking for e.g. :minimize-latency got no signal. nil means default,
  which is fully-consistent."
  [{:keys [consistency]}]
  (when (and (some? consistency)
             (not= consistency/fully-consistent consistency))
    (throw (ex-info "EACL only supports consistency/fully-consistent at this time."
             {:type :eacl/unsupported-consistency
              :consistency consistency}))))

(defn- list-query-shape
  [op query]
  ;; :consistency is excluded from the shape: it is validated (only
  ;; fully-consistent is accepted), so it cannot change what a token may
  ;; resume, and including it made page 2 fail when a caller passed it on
  ;; page 1 but not page 2.
  (stable-hash {:op op
                :basis :stable
                :query (dissoc query
                               :first :last :after :before
                               :cursor :limit :page/basis :consistency)}))

(defn- client-schema-version
  [opts]
  (some-> opts :schema-state deref :schema-version str))

(defn- cacheable-client-schema?
  [opts]
  (some? (client-schema-version opts)))

(defn- validate-page-token!
  [opts op query-shape decoded]
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
    (when-not (= (client-schema-version opts) (:schema-version decoded))
      (throw (ex-info "Page token was created under a different EACL schema generation."
                      {:type :eacl.pagination/stale-schema
                       :expected (client-schema-version opts)
                       :actual (:schema-version decoded)})))
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
                         :schema-version (client-schema-version opts)
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

(def ^:dynamic *relationship-coordinator*
  "The coordinator bound while an opt-in live lookup holds its read barrier."
  nil)

(defn- lookup-page-cache-key
  [opts op query-shape basis-t decoded page-req relationship-dependencies]
  (let [scope (if decoded
                [:basis basis-t]
                [:relationships
                 (cache/generation *relationship-coordinator*
                                   relationship-dependencies)])]
    [:lookup-page
     (:database-id opts)
     (client-schema-version opts)
     scope
     op
     query-shape
     (:direction page-req)
     (:size page-req)
     (:edge decoded)]))

(defn- internal-page-weight
  [page]
  ;; Internal lookup pages contain compact EID/type/cursor maps. This estimate
  ;; deliberately overweights ordinary small pages; admission is a resource
  ;; guard, not a JVM object-size claim.
  (+ 512 (* 128 (count (:data page)))))

(defn- cacheable-live-page?
  [opts decoded]
  (and (cacheable-client-schema? opts)
       (or decoded (:cache-live-lookups? opts))))

(defn- cached-lookup-page
  [opts cache-key compute]
  (let [store (:lookup-cache-store opts)]
    (if-let [cached (cache/safe-lookup store cache-key)]
      cached
      (let [page (compute)]
        (cache/safe-store! store
                           cache-key
                           page
                           (internal-page-weight page)
                           (:lookup-cache-ttl-ms opts))
        page))))

(defn- recursive-continuation-context
  [opts op query-shape basis-t]
  (when-let [store (and (cacheable-client-schema? opts)
                        (:lookup-cache-store opts))]
    (let [prefix [:recursive-continuation
                  (:database-id opts)
                  (client-schema-version opts)
                  op
                  query-shape
                  basis-t]
          cache-key #(conj prefix %)]
      {:get (fn [edge]
              (cache/safe-lookup store (cache-key edge)))
       :evict! (fn [edge]
                 (try
                   (cache/evict! store (cache-key edge))
                   (catch Throwable _
                     false)))
       :put! (fn [edge continuation weight]
               (cache/safe-store! store
                                  (cache-key edge)
                                  continuation
                                  weight
                                  (:lookup-cache-ttl-ms opts)))
       :get-page (fn [page-key]
                   (cache/safe-lookup
                    store
                    (cache-key [:page page-key])))
       :put-page! (fn [page-key page weight]
                    (cache/safe-store!
                     store
                     (cache-key [:page page-key])
                     page
                     weight
                     (:lookup-cache-ttl-ms opts)))})))

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
  (validate-consistency! filters)
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
        (validate-page-token! opts :read-relationships query-shape decoded)
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

(def ^:private relationship-attrs
  #{:eacl.v7.relationship/subject-type+relation+resource-type+resource
    :eacl.v7.relationship/resource-type+relation+subject-type+subject})

(defn- relationship-changes
  [db-after tx-data]
  (let [relationship-attr-eids (into #{} (keep #(d/entid db-after %)) relationship-attrs)]
    (into #{}
          (keep (fn [{:keys [a v]}]
                  (when (and (contains? relationship-attr-eids a)
                             (vector? v)
                             (<= 2 (count v)))
                    (nth v 1))))
          tx-data)))

(defn spiceomic-write-relationships!
  [conn {:keys [relationship-coordinator] :as opts} updates]
  (cache/with-mutation
   relationship-coordinator
   (fn []
     (let [db (d/db conn)
           tx-data (->> updates
                        (S/transform [S/ALL :relationship]
                                     #(spice-relationship->internal db opts %))
                        (mapcat #(impl/tx-update-relationship db %))
                        (remove nil?))
           {:keys [db-after tx-data]} @(d/transact conn tx-data)
           basis (d/basis-t db-after)]
       [{:zed/token (str basis)}
        (relationship-changes db-after tx-data)]))))

(defmacro ^:private with-recursive-limits
  "Applies the client's :recursive-traversal-limits, if configured, for the
  duration of one list call. Recursive permissions with large grant sets need
  headroom above the defaults on deep pages."
  [opts & body]
  `(let [limits# (:recursive-traversal-limits ~opts)]
     (if limits#
       (binding [impl.indexed/*recursive-traversal-limits* limits#]
         ~@body)
       (do ~@body))))

(defmacro ^:private with-client-schema-read
  "Runs one client operation against its latched schema generation. The read
  lock permits concurrent authorization calls while excluding write-schema!'s
  transaction-and-cache-swap window."
  [schema-lock schema-state & body]
  `(let [^Lock lock# (.readLock ^ReentrantReadWriteLock ~schema-lock)]
     (.lock lock#)
     (try
       (binding [impl.indexed/*schema-cache* (deref ~schema-state)]
         ~@body)
       (finally
         (.unlock lock#)))))

(defmacro ^:private with-client-schema-write
  [schema-lock & body]
  `(let [^Lock lock# (.writeLock ^ReentrantReadWriteLock ~schema-lock)]
     (.lock lock#)
     (try
       ~@body
       (finally
         (.unlock lock#)))))

(defmacro ^:private with-relationship-read
  "Runs a live-cache lookup against one coherent relationship generation.
  Exact-basis cursor and recursive caches need no mutation barrier."
  [opts & body]
  `(if (:cache-live-lookups? ~opts)
     (cache/with-read (:relationship-coordinator ~opts)
                      (fn [_relationship-snapshot#]
                        (binding [*relationship-coordinator*
                                  (:relationship-coordinator ~opts)]
                          ~@body)))
     (binding [*relationship-coordinator* nil]
       ~@body)))

(def ^:private delete-object-batch-size 1000)

(defn spiceomic-delete-object!
  "Retracts every relationship touching `object`, in both directions, in
  batches. Returns {:zed/token ... :retracted-datoms <n>}.

  The object's own entity is left alone — retract it yourself once this
  returns (or in the same application transaction, using
  eacl.datomic.impl/tx-delete-object directly)."
  [conn {:keys [object-id->entid relationship-coordinator] :as _opts} object]
  (cache/with-mutation
   relationship-coordinator
   (fn []
     (let [object-id (if (map? object) (:id object) object)
           db        (d/db conn)
           eid       (or (try (object-id->entid db object-id)
                              (catch Exception _ nil))
                         ;; A retracted entity no longer resolves through the
                         ;; caller's id coercion, but its raw eid still cleans up.
                         (when (number? object-id) object-id))
           tx-data   (impl/tx-delete-object db eid)]
       (if (empty? tx-data)
         [{:zed/token (str (d/basis-t db)) :retracted-datoms 0} #{}]
         (loop [batches   (partition-all delete-object-batch-size tx-data)
                retracted 0
                token     nil
                changes   #{}]
           (if-let [batch (first batches)]
             (let [{:keys [db-after tx-data]} @(d/transact conn (vec batch))]
               (recur (next batches)
                      (+ retracted (count batch))
                      (str (d/basis-t db-after))
                      (into changes (relationship-changes db-after tx-data))))
             [{:zed/token token :retracted-datoms retracted} changes])))))))

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
  (validate-consistency! query)
  (let [{:keys [db page-req decoded basis-t]} (pagination-context conn opts query)
        internal-subject (spice-object->internal db subject)]
    (if (nil? (:id internal-subject))
      ;; Unknown subjects match nothing (SpiceDB-consistent; can? is false).
      empty-page
      (let [query' (assoc query :subject internal-subject)
            query-shape (list-query-shape :lookup-resources query')
            internal-query (internal-page-query query' page-req decoded)
            recursive? (impl.indexed/traversal-permission?
                        db (:resource/type query') (:permission query'))
            relationship-dependencies
            (when (and (not recursive?)
                       (nil? decoded)
                       (:cache-live-lookups? opts))
              (impl.indexed/permission-relationship-eids
               db (:resource/type query') (:permission query')))
            cache-key (when (and (not recursive?)
                                 (cacheable-live-page? opts decoded))
                        (lookup-page-cache-key opts
                                               :lookup-resources
                                               query-shape
                                               basis-t
                                               decoded
                                               page-req
                                               relationship-dependencies))]
        (validate-page-token! opts :lookup-resources query-shape decoded)
        (coerce-lookup-page db opts :lookup-resources query-shape basis-t
                            (binding [impl.indexed/*recursive-continuation-cache*
                                      (when recursive?
                                        (recursive-continuation-context
                                         opts :lookup-resources query-shape basis-t))]
                              (if cache-key
                                (cached-lookup-page
                                 opts cache-key
                                 #(impl/lookup-resources db internal-query))
                                (impl/lookup-resources db internal-query))))))))

(defn- empty-count-response
  [query]
  (if (contains? query :count-limit)
    (let [limit (:count-limit query)]
      (when-not (and (integer? limit) (not (neg? limit)))
        (throw (ex-info ":count-limit must be a non-negative integer."
                        {:eacl/error :eacl.count/invalid-limit
                         :count-limit limit})))
      {:count 0 :limit limit :truncated? false})
    {:count 0 :limit -1}))

(defn spiceomic-count-resources
  [db
   {:keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (validate-consistency! query)
  (let [subject-ent (spice-object->internal db subject)]
    (if (nil? (:id subject-ent))
      ;; Unknown subjects match nothing (SpiceDB-consistent; can? is false).
      (empty-count-response query)
      (->> query
           (S/setval [:subject] subject-ent)
           (impl/count-resources db)))))

(defn spiceomic-lookup-subjects
  [conn
   {:as opts
    :keys [entid->object-id
           spice-object->internal]}
   query]
  (validate-consistency! query)
  (let [{:keys [db page-req decoded basis-t]} (pagination-context conn opts query)
        internal-resource (spice-object->internal db (:resource query))]
    (if (nil? (:id internal-resource))
      ;; Unknown resources match nothing (SpiceDB-consistent).
      empty-page
      (let [query' (assoc query :resource internal-resource)
            query-shape (list-query-shape :lookup-subjects query')
            internal-query (internal-page-query query' page-req decoded)
            recursive? (impl.indexed/traversal-permission?
                        db (:type internal-resource) (:permission query'))
            relationship-dependencies
            (when (and (not recursive?)
                       (nil? decoded)
                       (:cache-live-lookups? opts))
              (impl.indexed/permission-relationship-eids
               db (:type internal-resource) (:permission query')))
            cache-key (when (and (not recursive?)
                                 (cacheable-live-page? opts decoded))
                        (lookup-page-cache-key opts
                                               :lookup-subjects
                                               query-shape
                                               basis-t
                                               decoded
                                               page-req
                                               relationship-dependencies))]
        (validate-page-token! opts :lookup-subjects query-shape decoded)
        (coerce-lookup-page db opts :lookup-subjects query-shape basis-t
                            (binding [impl.indexed/*recursive-continuation-cache*
                                      (when recursive?
                                        (recursive-continuation-context
                                         opts :lookup-subjects query-shape basis-t))]
                              (if cache-key
                                (cached-lookup-page
                                 opts cache-key
                                 #(impl/lookup-subjects db internal-query))
                                (impl/lookup-subjects db internal-query))))))))

(defn spiceomic-count-subjects
  [db
   {:keys [spice-object->internal]}
   query]
  (validate-consistency! query)
  (let [resource-ent (spice-object->internal db (:resource query))]
    (if (nil? (:id resource-ent))
      (empty-count-response query)
      (->> query
           (S/setval [:resource] resource-ent)
           (impl/count-subjects db)))))

(defrecord Spiceomic [conn opts schema-state schema-lock]
  IAuthorization
  (can? [_ subject permission resource]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-can? (d/db conn) opts subject permission resource consistency/fully-consistent)))

  (can? [_ subject permission resource consistency]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-can? (d/db conn) opts subject permission resource consistency)))

  (can? [_ {:keys [subject permission resource consistency]}]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-can? (d/db conn) opts subject permission resource
                      (or consistency consistency/fully-consistent))))

  (read-schema [_]
    (with-client-schema-read schema-lock schema-state
      (schema/read-schema (d/db conn))))

  (write-schema! [_ schema-string]
    (with-client-schema-write schema-lock
      (let [deltas      (schema/write-schema!
                         conn schema-string
                         {}
                         (:schema-version @schema-state))
            next-version (:eacl/schema-version (meta deltas))
            next-cache  (impl.indexed/make-schema-cache (d/db conn) next-version)]
        (when-not (= (:schema-version @schema-state)
                     (:schema-version next-cache))
          (reset! schema-state next-cache))
        deltas)))

  (read-relationships [_ filters]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-read-relationships conn opts filters)))

  (write-relationships! [_ updates]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts updates)))

  (create-relationships! [_ relationships]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      (for [rel relationships]
                                        (->RelationshipUpdate :create rel)))))

  (create-relationship! [_ relationship]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :create relationship)])))

  (create-relationship! [_ subject relation resource]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :create (->Relationship subject relation resource))])))

  ;; Audit §13: these were declared on the protocol but unimplemented ->
  ;; AbstractMethodError at runtime.
  (write-relationship! [_ operation subject relation resource]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate operation (->Relationship subject relation resource))])))

  (write-relationship! [_ {:keys [operation subject relation resource]}]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate operation (->Relationship subject relation resource))])))

  (delete-relationship! [_ subject relation resource]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :delete (->Relationship subject relation resource))])))

  (delete-relationship! [_ {:keys [subject relation resource]}]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :delete (->Relationship subject relation resource))])))

  (delete-relationships! [_ relationships]
    (with-client-schema-read schema-lock schema-state
      (let [relationships' (if (map? relationships)
                             (:data relationships)
                             relationships)]
        (spiceomic-write-relationships! conn opts
                                        (for [rel relationships']
                                          (->RelationshipUpdate :delete rel))))))

  (delete-object! [_ object]
    (with-client-schema-read schema-lock schema-state
      (spiceomic-delete-object! conn opts object)))

  (lookup-resources [_ query]
    (with-client-schema-read schema-lock schema-state
      (with-relationship-read opts
        (with-recursive-limits opts
          (spiceomic-lookup-resources conn opts query)))))

  (count-resources [_ query]
    (with-client-schema-read schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-count-resources (d/db conn) opts query))))

  (lookup-subjects [_ query]
    (with-client-schema-read schema-lock schema-state
      (with-relationship-read opts
        (with-recursive-limits opts
          (spiceomic-lookup-subjects conn opts query)))))

  (count-subjects [_ query]
    (with-client-schema-read schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-count-subjects (d/db conn) opts query))))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
             {:type :eacl/not-implemented
              :method 'expand-permission-tree}))))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :entity->object-id
    :object-id->ident
    :cache
    :page-token-key
    :page-token-keys
    :page-token-keyring
    :page-token-kid
    :page-token-ttl-seconds
    :recursive-traversal-limits
    :auto-migrate-v6})

(def ^:private known-cache-opt-keys
  #{:store
    :coordinator
    :live-lookups?
    :ttl-ms
    :max-weight
    :max-entry-weight
    :max-entries})

(defn- normalize-cache-config
  [cache-option page-token-ttl-seconds database-id]
  (when-not (or (nil? cache-option)
                (false? cache-option)
                (map? cache-option))
    (throw (ex-info "EACL Config Error: :cache must be false or a configuration map."
                    {:type :eacl/invalid-config
                     :key :cache
                     :value cache-option})))
  (let [config (if (map? cache-option) cache-option {})
        unknown-keys (seq (remove known-cache-opt-keys (keys config)))]
    (when unknown-keys
      (throw (ex-info "EACL Config Error: unknown :cache option(s)."
                      {:type :eacl/invalid-config
                       :key :cache
                       :unknown-keys (vec unknown-keys)
                       :known-keys known-cache-opt-keys})))
    (let [enabled? (not (false? cache-option))
          token-ttl-ms (* 1000 (or page-token-ttl-seconds
                                   default-page-token-ttl-seconds))
          ttl-ms (or (:ttl-ms config) token-ttl-ms)
          _ (when-not (and (integer? ttl-ms) (pos? ttl-ms))
              (throw (ex-info "EACL Config Error: :cache :ttl-ms must be a positive integer."
                              {:type :eacl/invalid-config
                               :key :cache
                               :value ttl-ms})))
          store (when enabled?
                  (or (:store config)
                      (cache/local-store
                       (select-keys config [:max-weight
                                            :max-entry-weight
                                            :max-entries]))))
          coordinator (or (:coordinator config)
                          (cache/process-coordinator database-id))]
      (when (and store (not (satisfies? cache/CacheStore store)))
        (throw (ex-info "EACL Config Error: :cache :store must implement CacheStore."
                        {:type :eacl/invalid-config
                         :key :cache
                         :value (:store config)})))
      (when-not (satisfies? cache/RelationshipCoordinator coordinator)
        (throw (ex-info "EACL Config Error: :cache :coordinator must implement RelationshipCoordinator."
                        {:type :eacl/invalid-config
                         :key :cache
                         :value (:coordinator config)})))
      {:store store
       :coordinator coordinator
       :ttl-ms (min ttl-ms token-ttl-ms)
       ;; Cross-request live memoization requires every relationship writer to
       ;; share this coordinator. Cursor continuations do not.
       :live-lookups? (and enabled? (true? (:live-lookups? config)))})))

(defn make-client
  "Builds an IAuthorization client over a Datomic conn.

  Options (unknown keys throw :eacl/invalid-config — a silently ignored key
  means silently wrong ID coercion):
  - :entid->object-id  (fn [db eid] external-id) — canonical ID coercion, as documented in the README.
  - :entity->object-id (fn [entity] external-id) — deprecated alias; do not combine with the above.
  - :object-id->ident  (fn [external-id] ident-resolvable-by-d-entid). Default: [:eacl/id id].
  - :cache — false disables all lookup caching. A map configures the bounded
    ephemeral store with :max-weight, :max-entry-weight, :max-entries and
    :ttl-ms. :live-lookups? true enables non-recursive cross-request page
    memoization only when every relationship writer shares the configured
    coordinator. :store and :coordinator accept custom protocol
    implementations. Recursive cursor continuations remain basis-pinned and
    safe without a global coordinator.
  - :page-token-key / :page-token-keys / :page-token-keyring / :page-token-kid —
    AES-GCM page-token key material. Default: a random per-process key, meaning
    page tokens do not survive restarts and are not portable across peers;
    supply stable key material in production.
  - :page-token-ttl-seconds — overrides the default page-token expiry.
  - :recursive-traversal-limits — overrides eacl.datomic.impl.indexed/default-recursive-traversal-limits
    for list calls, e.g. {:max-derived-grants 1000000 :max-advanced-datoms 1000000
    :max-queued-work 1000000}. Recursive continuation misses replay the
    traversal prefix and remain subject to these host-JVM memory bounds; tune
    them only after representative heap/load tests.
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
           cache
           page-token-key
           page-token-keys
           page-token-keyring
           page-token-kid
           page-token-ttl-seconds
           recursive-traversal-limits
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
  (when recursive-traversal-limits
    (let [known (set (keys impl.indexed/default-recursive-traversal-limits))]
      (when-not (and (map? recursive-traversal-limits)
                     (every? known (keys recursive-traversal-limits))
                     (every? (fn [v] (and (integer? v) (pos? v)))
                             (vals recursive-traversal-limits)))
        (throw (ex-info (str "EACL Config Error: :recursive-traversal-limits must be a map of "
                             (pr-str (vec (sort known)))
                             " to positive integers.")
                 {:type :eacl/invalid-config
                  :key :recursive-traversal-limits
                  :known-keys known
                  :value recursive-traversal-limits})))))
  ;; Refuse to run v7 code against unmigrated v6 relationship data — it would
  ;; silently answer every check with false/empty. Throws :eacl/storage-version
  ;; unless the DB is v7/fresh/stamped, or :auto-migrate-v6 opts into migration.
  (migrations/assert-storage-compatible! conn {:auto-migrate-v6 auto-migrate-v6})
  (let [initial-db         (d/db conn)
        schema-state       (atom (impl.indexed/make-schema-cache initial-db))
        cache-config       (normalize-cache-config cache
                                                   page-token-ttl-seconds
                                                   (:database-id @schema-state))
        schema-lock        (ReentrantReadWriteLock.)
        entid->object-id   (or entid->object-id
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
                            :schema-state schema-state
                            :database-id (:database-id @schema-state)
                            :lookup-cache-store (:store cache-config)
                            :lookup-cache-ttl-ms (:ttl-ms cache-config)
                            :cache-live-lookups? (:live-lookups? cache-config)
                            :relationship-coordinator (:coordinator cache-config)
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
                            :page-token-ttl-seconds page-token-ttl-seconds
                            ;; merged, so a partial override cannot silently
                            ;; disable the limits it does not mention
                            :recursive-traversal-limits (when recursive-traversal-limits
                                                          (merge impl.indexed/default-recursive-traversal-limits
                                                                 recursive-traversal-limits))}]
    (->Spiceomic conn opts schema-state schema-lock)))
