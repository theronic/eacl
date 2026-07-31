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
            [eacl.datomic.consistency :as revision]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.spicedb.consistency :as consistency])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [java.util.concurrent.locks Lock ReentrantReadWriteLock]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def ^:private page-token-prefix "eacl3_")
(def ^:private page-token-version 5)
(def ^:private maximum-page-token-length
  "Bounds decode work on an unauthenticated caller-supplied cursor. Real EACL
  cursors are well under this even with per-path frontiers for a wide
  permission graph; mirrors the cap eacl.datomic.consistency puts on Zed
  tokens."
  16384)
(def ^:private default-page-token-ttl-seconds 300)
(def ^:private default-consistency-sync-timeout-ms 30000)
(def ^:private max-coordinator-catchup-attempts
  "Bounds the reader's catch-up loop when a shared coordinator has published a
  revision this connection has not observed. Each attempt is itself bounded by
  :consistency-sync-timeout-ms; without a cap, sustained write load could keep
  a reader retrying indefinitely with no deadline of its own."
  16)
(def ^:private maximum-token-key-id-length 128)
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
    (if (pos? (alength ^bytes key-material))
      (if (#{16 24 32} (alength ^bytes key-material))
        key-material
        (sha-256 key-material))
      (throw (ex-info "Token key material must not be empty."
                      {:type :eacl/invalid-config})))

    (string? key-material)
    (if (not-empty key-material)
      (sha-256 (utf8-bytes key-material))
      (throw (ex-info "Token key material must not be empty."
                      {:type :eacl/invalid-config})))

    :else
    (throw (ex-info "Token key must be bytes or string key material."
                    {:type :eacl/invalid-config
                     :key-material-class (some-> key-material class str)}))))

(defn- valid-token-key-id?
  [kid]
  (and (or (keyword? kid)
           (and (string? kid) (not-empty kid)))
       (<= (count (pr-str kid)) maximum-token-key-id-length)))

(defn- validate-token-key-id!
  [option kid]
  (when-not (valid-token-key-id? kid)
    (throw (ex-info "Token key id must be a bounded keyword or non-empty string."
                    {:type :eacl/invalid-config
                     :key option
                     :value kid}))))

(defn- normalize-token-keyring
  [option keyring]
  (when-not (and (map? keyring) (seq keyring))
    (throw (ex-info "Token keyring must be a non-empty map."
                    {:type :eacl/invalid-config
                     :key option
                     :value keyring})))
  (into {}
        (map (fn [[kid key]]
               (validate-token-key-id! option kid)
               [kid (normalize-token-key key)]))
        keyring))

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

(defn- invalid-page-token!
  "Every page-token rejection carries one type so callers can match cursor
  failures uniformly. The token itself is never echoed back."
  ([reason]
   (invalid-page-token! reason {}))
  ([reason data]
   (throw (ex-info "Invalid page token."
                   (assoc data
                          :type :eacl.pagination/invalid-cursor
                          :eacl/error :eacl.pagination/invalid-cursor
                          :reason reason)))))

(defn decrypt-page-token
  [opts token]
  (when token
    ;; Length is checked before any decoding: the envelope is Base64-decoded
    ;; and EDN-parsed before its AES-GCM tag can be verified, so an unbounded
    ;; token is unauthenticated CPU and allocation amplification.
    (when-not (and (string? token)
                   (<= (count token) maximum-page-token-length)
                   (.startsWith ^String token page-token-prefix))
      (invalid-page-token! :malformed))
    (try
      (let [{:keys [page-token-keyring]} opts
            envelope (edn/read-string
                      (String. (b64url-decode (subs token (count page-token-prefix)))
                               StandardCharsets/UTF_8))
            {:keys [v kid nonce ciphertext]} envelope
            key (get page-token-keyring kid)]
        (when-not (= page-token-version v)
          (invalid-page-token! :unsupported-version {:version v}))
        (when-not key
          (invalid-page-token! :unknown-key-id {:kid kid}))
        (let [header {:v v :kid kid :nonce nonce}
              aad (utf8-bytes (pr-str (canonicalize header)))
              plaintext (decrypt-aead key
                                      (b64url-decode nonce)
                                      aad
                                      (b64url-decode ciphertext))
              payload (edn/read-string (String. plaintext StandardCharsets/UTF_8))
              now (now-seconds)]
          (when-not (= page-token-version (:v payload))
            (invalid-page-token! :unsupported-version {:version (:v payload)}))
          (when (and (:exp payload) (<= (:exp payload) now))
            (invalid-page-token! :expired {:exp (:exp payload) :now now}))
          payload))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      ;; edn/read-string on hostile nesting overflows the stack, and a
      ;; StackOverflowError is an Error: it walked straight out of
      ;; lookup-resources past every `catch Exception` in the request stack.
      (catch StackOverflowError _
        (invalid-page-token! :malformed))
      (catch Exception _
        (invalid-page-token! :malformed)))))

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

(defn- consistency-request
  [opts value]
  (let [{:keys [token] :as descriptor}
        (consistency/descriptor value)]
    (if token
      (assoc descriptor
             :requested-t
             (revision/token-revision opts (:database-id opts) token))
      (assoc descriptor :requested-t nil))))

(def ^:private sync-timeout-marker (Object.))

(declare snapshot-unavailable!)

(defn- freshness-unavailable!
  [message data]
  (throw
   (ex-info message
            (assoc data
                   :type :eacl.consistency/freshness-unavailable
                   :eacl/error :eacl.consistency/freshness-unavailable))))

(defn- observed-connection-revision
  [conn]
  (try
    (d/basis-t (d/db conn))
    (catch Exception _
      nil)))

(defn- await-revision-db
  "Returns a local DB that has observed `requested-t`, waiting only when needed."
  [conn opts requested-t]
  (let [local-db (d/db conn)
        local-t (d/basis-t local-db)]
    (if (or (nil? requested-t)
            (<= requested-t local-t))
      local-db
      (let [timeout-ms (:consistency-sync-timeout-ms opts)]
        (try
          (let [synced-db (deref (d/sync conn requested-t)
                                 timeout-ms
                                 sync-timeout-marker)]
            (when (identical? sync-timeout-marker synced-db)
              (freshness-unavailable!
               "Timed out waiting for the requested EACL revision."
               {:reason :timeout
                :requested-t requested-t
                :observed-t (observed-connection-revision conn)
                :timeout-ms timeout-ms}))
            (let [observed-t (d/basis-t synced-db)]
              (when (< observed-t requested-t)
                (freshness-unavailable!
                 "The local Peer did not reach the requested EACL revision."
                 {:reason :revision-not-observed
                  :requested-t requested-t
                  :observed-t observed-t
                  :timeout-ms timeout-ms}))
              synced-db))
          (catch clojure.lang.ExceptionInfo e
            (if (= :eacl.consistency/freshness-unavailable
                   (:type (ex-data e)))
              (throw e)
              (freshness-unavailable!
               "Failed while waiting for the requested EACL revision."
               {:reason :sync-failed
                :requested-t requested-t
                :observed-t (observed-connection-revision conn)
                :timeout-ms timeout-ms})))
          (catch Exception _
            (freshness-unavailable!
             "Failed while waiting for the requested EACL revision."
             {:reason :sync-failed
              :requested-t requested-t
              :observed-t (observed-connection-revision conn)
              :timeout-ms timeout-ms})))))))

(defn- historical-db
  [conn opts requested-t operation]
  (try
    (d/as-of (await-revision-db conn opts requested-t) requested-t)
    (catch clojure.lang.ExceptionInfo e
      (if (= :eacl.consistency/freshness-unavailable
             (:type (ex-data e)))
        (throw e)
        (snapshot-unavailable!
         {:operation operation
          :revision requested-t
          :reason :historical-database-unavailable})))
    (catch Exception _
      (snapshot-unavailable!
       {:operation operation
        :revision requested-t
        :reason :historical-database-unavailable}))))

(defn- snapshot-unavailable!
  [data]
  (throw (ex-info "The requested EACL cache snapshot is unavailable."
                  (assoc data
                         :type :eacl.consistency/snapshot-unavailable
                         :eacl/error :eacl.consistency/snapshot-unavailable))))

(defn- list-query-identity
  [op query]
  ;; :consistency is excluded from the query binding because cursor-result-
  ;; context validates its freshness/snapshot compatibility separately.
  ;; Including the descriptor here also made page 2 fail when a caller passed
  ;; it on page 1 but omitted the equivalent default on page 2.
  (canonicalize
   {:op op
    :basis :stable
    :query (dissoc query
                   :first :last :after :before
                   :cursor :limit :page/basis :consistency)}))

(defn- list-query-shape
  [op query]
  (stable-hash (list-query-identity op query)))

(defn- client-schema-version
  [opts]
  (some-> opts :schema-state deref :schema-version str))

(defn- selected-schema-version
  "The schema generation this operation is bound to.

  `contains?`, not `or`: a historical read against an unstamped basis selects
  an explicit nil, and falling back to the client's generation there made a
  cursor minted on an unstamped database fail its own page-two validation."
  [opts]
  (if (contains? opts :selected-schema-version)
    (:selected-schema-version opts)
    (client-schema-version opts)))

(defn- invalid-cursor!
  "All cursor-identity rejections share :eacl.pagination/invalid-cursor. Only
  the database mismatch used to carry a type, so callers could not match the
  rest without string-matching messages."
  [message reason data]
  (throw (ex-info message
                  (assoc data
                         :type :eacl.pagination/invalid-cursor
                         :eacl/error :eacl.pagination/invalid-cursor
                         :reason reason))))

(defn- validate-page-token-identity!
  [opts op query-shape decoded]
  (when decoded
    (when-not (= (:database-id opts) (:database-id decoded))
      (invalid-cursor! "Page token was created for a different database."
                       :database-mismatch {}))
    (when-not (= op (:op decoded))
      (invalid-cursor! "Page token was created for a different operation."
                       :operation-mismatch
                       {:expected op :actual (:op decoded)}))
    (when-not (= query-shape (:query-shape decoded))
      (invalid-cursor! "Page token does not match the current query."
                       :query-mismatch
                       {:expected query-shape :actual (:query-shape decoded)}))
    (when-not (= :stable (:basis decoded))
      (invalid-cursor! "Unsupported page token basis."
                       :unsupported-basis {:basis (:basis decoded)}))
    (when-not (and (integer? (:basis-t decoded))
                   (not (neg? (:basis-t decoded))))
      (invalid-cursor! "Page token has an invalid revision."
                       :invalid-revision {:basis-t (:basis-t decoded)}))
    (when-not (or (nil? (:cache-scope decoded))
                  (vector? (:cache-scope decoded)))
      (invalid-cursor! "Page token has an invalid cache proof."
                       :invalid-cache-scope
                       {:cache-scope (:cache-scope decoded)})))
  true)

(defn- validate-page-token-schema!
  [opts decoded]
  (when decoded
    (when-not (= (selected-schema-version opts) (:schema-version decoded))
      (throw (ex-info "Page token was created under a different EACL schema generation."
                      {:type :eacl.pagination/stale-schema
                       :expected (selected-schema-version opts)
                       :actual (:schema-version decoded)}))))
  true)

(defn- internal-page-query
  [query page-req decoded]
  (let [edge (:edge decoded)]
    (cond-> (dissoc query :after :before :page/basis :consistency)
      (and edge (= :asc (:direction page-req))) (assoc :after edge)
      (and edge (= :desc (:direction page-req))) (assoc :before edge))))

(defn- encode-page-cursor
  ([opts op query-shape basis-t edge]
   (encode-page-cursor opts op query-shape basis-t nil edge))
  ([opts op query-shape basis-t cache-scope edge]
   (when edge
     (page-token opts
                 (cond-> {:op op
                          :database-id (:database-id opts)
                          :query-shape query-shape
                          :basis-t basis-t
                          :basis :stable
                          :schema-version (selected-schema-version opts)
                          :cache-scope cache-scope
                          :edge edge}
                   (:page-token-ttl-seconds opts)
                   (assoc :ttl-seconds (:page-token-ttl-seconds opts)))))))

(defn- encode-page-info
  ([opts op query-shape basis-t page-info]
   (encode-page-info opts op query-shape basis-t nil page-info))
  ([opts op query-shape basis-t cache-scope page-info]
   (-> page-info
       (update :start-cursor
               #(encode-page-cursor
                 opts op query-shape basis-t cache-scope %))
       (update :end-cursor
               #(encode-page-cursor
                 opts op query-shape basis-t cache-scope %)))))

(defn- unresolvable-objects!
  "A lookup result names entities that have no external id in the database it
  was evaluated against.

  This is a data-integrity fault, not a cache fault: it fires with {:cache
  false} too, and the usual cause is an entity retracted without first calling
  delete-relationships!, leaving a relationship half that still grants. It is
  reported rather than silently dropped — omitting rows from an authorization
  enumeration is the more dangerous failure. Every offending eid on the page is
  listed so one repair pass fixes them all."
  [op basis-t historical? entity-ids]
  (throw
   (ex-info
    (str "EACL " (name op) " returned " (count entity-ids)
         " object(s) with no external id"
         (if historical?
           (str " at historical basis t=" basis-t ". ")
           ". ")
         "The usual cause is an entity retracted without first calling"
         " delete-relationships!, leaving a relationship half that still grants."
         " Run eacl.datomic.integrity/dangling-relationship-report to find and"
         " repair halves whose peer entity is gone.")
    {:type :eacl/unresolvable-object
     :eacl/error :eacl/unresolvable-object
     :operation op
     :basis-t basis-t
     :historical? (boolean historical?)
     :entity-ids (vec entity-ids)})))

(defn- coerce-lookup-page
  ([db opts op query-shape basis-t page]
   (coerce-lookup-page db opts op query-shape basis-t nil false page))
  ([db opts op query-shape basis-t cache-scope historical? page]
   (let [entid->object-id (:entid->object-id opts)
         resolved (mapv (fn [{:keys [type id]}]
                          [type id (entid->object-id db id)])
                        (:data page))
         unresolvable (into [] (comp (filter (fn [[_ _ external-id]]
                                               (nil? external-id)))
                                     (map second))
                            resolved)]
     (when (seq unresolvable)
       (unresolvable-objects! op basis-t historical? unresolvable))
     (-> page
         (assoc :data (mapv (fn [[type _ external-id]]
                              (spice-object type external-id))
                            resolved))
         (update :page-info
                 #(encode-page-info
                   opts op query-shape basis-t cache-scope %))))))

(defn- coerce-relationship-page
  [db opts op query-shape basis-t page]
  (-> page
      (update :data #(mapv (fn [relationship]
                             (relationship->spice db opts relationship))
                           %))
      (update :page-info
              #(encode-page-info opts op query-shape basis-t %))))

(def ^:private result-cache-version 3)

(defn- result-cache-prefix
  [opts op query-identity schema-version]
  [:result
   result-cache-version
   (:cache-namespace opts)
   (:database-id opts)
   schema-version
   op
   ;; Keep the exact canonical request in the key. The compact query hash in
   ;; the page token is authenticated, but cache correctness need not rely on
   ;; collision resistance when ordinary value equality is available.
   (canonicalize query-identity)])

(defn- result-cache-key
  [prefix scope]
  (conj prefix [:live scope]))

(defn- exact-result-cache-key
  [prefix basis-t]
  (conj prefix [:exact basis-t]))

(defn- latest-result-cache-key
  [prefix]
  (conj prefix :latest))

(defn- internal-page-weight
  [page]
  ;; Internal lookup pages contain compact EID/type/cursor maps. This estimate
  ;; deliberately overweights ordinary small pages; admission is a resource
  ;; guard, not a JVM object-size claim.
  (+ 512 (* 128 (count (:data page)))))

(defn- positive-eid?
  [eid]
  (and (integer? eid) (pos? eid)))

(defn- cursor-result
  [cursor]
  (case (:kind cursor)
    :lookup-eid
    (when (positive-eid? (:result-eid cursor))
      {:eid (:result-eid cursor)})

    :recursive-traversal
    (let [{:keys [engine-version direction result-kind ordinal result]} cursor]
      (when (and (integer? engine-version)
                 (pos? engine-version)
                 (#{:forward :reverse} direction)
                 (#{:resource :subject} result-kind)
                 (integer? ordinal)
                 (not (neg? ordinal))
                 (keyword? (:type result))
                 (positive-eid? (:eid result)))
        result))

    nil))

(defn- internal-page?
  [page]
  (and (map? page)
       (vector? (:data page))
       (every? (fn [{:keys [type id]}]
                 (and (keyword? type)
                      (positive-eid? id)))
               (:data page))
       (let [{:keys [start-cursor
                     end-cursor
                     has-next-page?
                     has-previous-page?]} (:page-info page)
             data (:data page)
             start-result (some-> start-cursor cursor-result)
             end-result (some-> end-cursor cursor-result)]
         (and (map? (:page-info page))
              (boolean? has-next-page?)
              (boolean? has-previous-page?)
              (if (empty? data)
                (and (nil? start-cursor)
                     (nil? end-cursor)
                     (false? has-next-page?)
                     (false? has-previous-page?))
                (and start-result
                     end-result
                     (= (:id (first data)) (:eid start-result))
                     (= (:id (peek data)) (:eid end-result))
                     (or (nil? (:type start-result))
                         (= (:type (first data)) (:type start-result)))
                     (or (nil? (:type end-result))
                         (= (:type (peek data)) (:type end-result)))))))))

(defn- count-response?
  [response]
  (let [limit (:limit response)]
    (and (map? response)
         (integer? (:count response))
         (not (neg? (:count response)))
         (integer? limit)
         (<= -1 limit)
         (if (= -1 limit)
           (= #{:count :limit} (set (keys response)))
           (and (= #{:count :limit :truncated?} (set (keys response)))
                (boolean? (:truncated? response))
                (<= (:count response) limit))))))

(defn- boolean-result?
  [value]
  (boolean? value))

(defn- cached-answer?
  [valid-result? expected answer]
  (and (map? answer)
       (integer? (:basis-t answer))
       (not (neg? (:basis-t answer)))
       (or (nil? (:cache-scope answer))
           (vector? (:cache-scope answer)))
       (or (not (contains? expected :basis-t))
           (= (:basis-t expected) (:basis-t answer)))
       (or (not (contains? expected :cache-scope))
           (= (:cache-scope expected) (:cache-scope answer)))
       (contains? answer :result)
       (valid-result? (:result answer))))

(defn- cached-answer
  [opts cache-key kind valid-result? expected]
  (cache/safe-entry-value
   (:lookup-cache-store opts)
   cache-key
   kind
   #(cached-answer? valid-result? expected %)))

(defn- portable-result
  [kind result]
  (case kind
    :lookup-page
    (update result :data
            (fn [objects]
              (mapv (fn [object]
                      {:type (:type object)
                       :id (:id object)})
                    objects)))

    result))

(defn- store-cached-answer!
  [opts cache-prefix kind result weight
   {:keys [mode basis-t cache-scope schema-version]}]
  (let [store (:lookup-cache-store opts)
        namespace (:cache-namespace opts)
        ttl-ms (:lookup-cache-ttl-ms opts)
        exact-key (exact-result-cache-key cache-prefix basis-t)
        ;; A cursor/exact page is forced to :at-exact-snapshot, and only that
        ;; mode reads exact-key. Its live entry would be keyed by a query
        ;; identity containing an :after/:before edge, and every request
        ;; carrying such an edge takes the historical branch — so the entry
        ;; could never be read, while still consuming the weight and entry
        ;; budget that live page-one answers compete for.
        historical? (= :at-exact-snapshot mode)
        answer {:basis-t basis-t
                :cache-scope cache-scope
                :result (portable-result kind result)}]
    (when (and store schema-version)
      (when (:cache-exact-results? opts)
        (cache/safe-store-entry!
         store namespace exact-key kind answer (+ 128 weight) ttl-ms))
      (when (and cache-scope (:cache-live-results? opts) (not historical?))
        (cache/safe-store-entry!
         store namespace
         (result-cache-key cache-prefix cache-scope)
         kind answer (+ 128 weight) ttl-ms))
      ;; This is a latency hint, not a correctness proof. An older concurrent
      ;; writer may overwrite it; at-least-as-fresh validates the revision and
      ;; falls back to the selected DB when the hint is too old. A cursor
      ;; prefix's pointer is unreachable for the same reason as its live entry.
      (when (and (:cache-exact-results? opts) (not historical?))
        (cache/safe-store-entry!
         store namespace
         (latest-result-cache-key cache-prefix)
         :latest-result
         {:basis-t basis-t
          :exact-key exact-key
          :kind kind}
         192
         ttl-ms)))
    answer))

(defn- latest-cached-answer
  [opts cache-prefix kind valid-result? minimum-t maximum-t]
  (let [store (:lookup-cache-store opts)
        pointer-key (latest-result-cache-key cache-prefix)
        pointer
        (cache/safe-entry-value
         store pointer-key :latest-result
         (fn [value]
           (and (map? value)
                (integer? (:basis-t value))
                (not (neg? (:basis-t value)))
                (vector? (:exact-key value))
                (= kind (:kind value)))))]
    (when (and pointer
               (or (nil? minimum-t)
                   (<= minimum-t (:basis-t pointer)))
               (or (nil? maximum-t)
                   (<= (:basis-t pointer) maximum-t))
               (= (:exact-key pointer)
                  (exact-result-cache-key cache-prefix (:basis-t pointer))))
      (cached-answer opts (:exact-key pointer) kind valid-result?
                     {:basis-t (:basis-t pointer)}))))

(defn- cached-authorization-result
  [opts consistency-context op query-identity kind valid-result? weight-fn compute]
  (let [{:keys [mode basis-t requested-t cache-scope]}
        consistency-context
        live? (:cache-live-results? opts)
        exact? (:cache-exact-results? opts)]
    (cond
      ;; portable-result even when nothing is retained, so a caller's result
      ;; shape does not depend on cache configuration. Without it :lookup-page
      ;; data was SpiceObject records with caching off and plain maps with it
      ;; on — a trap for any consumer that comes to rely on record type.
      (and (not live?) (not exact?))
      (assoc consistency-context :result (portable-result kind (compute)))

      :else
      (let [cache-prefix (result-cache-prefix
                          opts op query-identity
                          (:schema-version consistency-context))
            live-key (when (and live? cache-scope)
                       (result-cache-key cache-prefix cache-scope))
            exact-key (when exact?
                        (exact-result-cache-key cache-prefix basis-t))
            current-hit #(when live-key
                           (some-> (cached-answer
                                    opts live-key kind valid-result?
                                    {:cache-scope cache-scope})
                                   ;; A live proof establishes EACL
                                   ;; equivalence at the caller's selected
                                   ;; local revision.
                                   (assoc :basis-t basis-t
                                          :cache-scope cache-scope)))
            exact-hit #(when exact-key
                         (cached-answer opts exact-key kind valid-result?
                                        {:basis-t basis-t}))
            latest-hit #(when exact?
                          (latest-cached-answer
                           opts cache-prefix kind valid-result?
                           % basis-t))
            hit (case mode
                  ;; exact-hit is a sound fallback here, not a staleness
                  ;; window: exact-key pins database-id, schema generation,
                  ;; operation, query identity AND basis-t, and two
                  ;; connection-backed DB values of one database at the same
                  ;; basis-t are the same value. Without it, :exact-results?
                  ;; wrote an entry on every call that the default consistency
                  ;; mode could never read.
                  :fully-consistent (or (current-hit) (exact-hit))
                  :minimize-latency (latest-hit nil)
                  :at-least-as-fresh (latest-hit requested-t)
                  :at-exact-snapshot (exact-hit))]
        (cond
          (some? hit)
          hit

          :else
          (let [result (compute)]
            (store-cached-answer!
             opts cache-prefix kind result (weight-fn result)
             consistency-context)))))))

(defn- recursive-continuation-context
  [opts op query-identity relationship-proof]
  (when-let [store (and (selected-schema-version opts)
                        (:lookup-cache-store opts))]
    (let [prefix [:recursive-continuation
                  result-cache-version
                  (:cache-namespace opts)
                  (:database-id opts)
                  (selected-schema-version opts)
                  op
                  ;; list-query-identity already returns canonical data.
                  query-identity
                  ;; Recursive state depends on EACL relationship content, not
                  ;; unrelated application transactions or their basis t.
                  relationship-proof]
          cache-key #(conj prefix %)
          opaque-token (:opaque-cache-token opts)
          namespace (:cache-namespace opts)
          opaque-values? (contains? (cache/safe-capabilities store)
                                    :opaque-values)]
      {:required? false
       :opaque-values? opaque-values?
       :get (fn [edge]
              (some-> (cache/safe-entry-value
                       store
                       (cache-key edge)
                       :recursive-continuation
                       #(and (map? %)
                             (identical? opaque-token (:opaque-token %))
                             (map? (:continuation %))))
                      :continuation))
       :evict! (fn [edge]
                 (cache/safe-evict! store (cache-key edge)))
       :put! (fn [edge continuation weight]
               (cache/safe-store-entry!
                store
                namespace
                (cache-key edge)
                :recursive-continuation
                {:opaque-token opaque-token
                 :continuation continuation}
                weight
                (:lookup-cache-ttl-ms opts)))
       :get-page (fn [page-key]
                   (cache/safe-entry-value
                    store
                    (cache-key [:page page-key])
                    :recursive-page
                    internal-page?))
       :put-page! (fn [page-key page weight]
                    (cache/safe-store-entry!
                     store
                     namespace
                     (cache-key [:page page-key])
                     :recursive-page
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

(declare capture-result-context)

(defn spiceomic-read-relationships
  [conn
   {:keys [object-id->entid] :as opts}
   filters]
  (reject-live-basis! filters)
  (let [query-shape (list-query-shape :read-relationships filters)
        page-req (impl.indexed/normalize-page-request filters)
        decoded (decoded-page-bound opts page-req)
        _ (validate-page-token-identity!
           opts :read-relationships query-shape decoded)
        {:keys [db basis-t schema-version]}
        (capture-result-context
         conn opts (:consistency filters) false
         (fn [db] {:db db})
         :read-relationships decoded)
        selected-opts (assoc opts :selected-schema-version schema-version)
        ;; Validated before the empty-page short-circuit, so a cursor from
        ;; another schema generation raises :eacl.pagination/stale-schema here
        ;; exactly as it does in the lookups, instead of quietly reading as an
        ;; empty page whenever the filter also names a missing object.
        _ (validate-page-token-schema! selected-opts decoded)
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
            internal-query (internal-page-query filters' page-req decoded)]
        (coerce-relationship-page
         db selected-opts :read-relationships query-shape basis-t
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

(defn- relationship-attr-eids
  [db]
  (into #{} (keep #(d/entid db %)) relationship-attrs))

(defn- relationship-changes
  [db-after tx-data]
  (let [attr-eids (relationship-attr-eids db-after)]
    (into #{}
          (keep (fn [{:keys [a v]}]
                  (when (and (contains? attr-eids a)
                             (vector? v)
                             (<= 2 (count v)))
                    (nth v 1))))
          tx-data)))

(defn- relationship-retraction-count
  [db-after tx-data]
  (let [attr-eids (relationship-attr-eids db-after)]
    (count
     (filter (fn [{:keys [a added]}]
               (and (false? added)
                    (contains? attr-eids a)))
             tx-data))))

(defn- coordinate-relationship-mutation
  [coordinator f]
  (if coordinator
    (cache/with-mutation coordinator f)
    (first (f))))

(defn spiceomic-write-relationships!
  [conn {:keys [relationship-coordinator] :as opts} updates]
  (doseq [{:keys [operation]} updates]
    (impl/validate-relationship-operation! operation))
  (coordinate-relationship-mutation
   relationship-coordinator
   (fn []
     (let [db (d/db conn)
           ;; Endpoint resolution and :create conflict detection run BEFORE
           ;; mutation-attempted!: an :eacl/unknown-object or
           ;; :eacl/relationship-conflict here commits nothing, so it must not
           ;; invalidate the coherence scope's cached results.
           tx-data (->> updates
                        (S/transform [S/ALL :relationship]
                                     #(spice-relationship->internal db opts %))
                        (mapcat #(impl/tx-update-relationship db %))
                        (remove nil?))
           _ (cache/mutation-attempted!)
           {:keys [db-after tx-data]} @(d/transact conn tx-data)
           basis (d/basis-t db-after)
           changes (relationship-changes db-after tx-data)]
       [{:zed/token (revision/zed-token opts (:database-id opts) basis)}
        (when (seq changes)
          {:dependency-keys changes
           :basis-t basis})]))))

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

(defmacro ^:private with-client-schema-write
  [schema-lock & body]
  `(let [^Lock lock# (.writeLock ^ReentrantReadWriteLock ~schema-lock)]
     (.lock lock#)
     (try
       ~@body
       (finally
         (.unlock lock#)))))

(defn- adopt-schema-generation!
  "Promotes a still-unstamped client to the database's schema generation.

  A client constructed before the first write-schema! latches a nil generation
  for life unless write-schema! is called through it. That is not merely
  \"uncached\": a nil generation mints page tokens the client then rejects on
  page two, because its own historical branch derives the real stamp from the
  as-of database. Adopting the stamp the first time one is visible fixes
  pagination and enables caching.

  The :eacl/schema-version read happens ONLY while the client is unstamped; a
  stamped client never performs it, so the one-read-per-generation contract is
  preserved for every normal client."
  [conn schema-state schema-lock]
  (when (nil? (:schema-version @schema-state))
    (when-let [version (impl.indexed/schema-version (d/db conn))]
      (with-client-schema-write schema-lock
        (when (nil? (:schema-version @schema-state))
          (reset! schema-state
                  (impl.indexed/make-schema-cache (d/db conn) version))))))
  nil)

(defmacro ^:private with-client-schema-read
  "Runs one client operation against its latched schema generation. The read
  lock permits concurrent authorization calls while excluding write-schema!'s
  transaction-and-cache-swap window."
  [conn schema-lock schema-state & body]
  `(do
     (adopt-schema-generation! ~conn ~schema-state ~schema-lock)
     (let [^Lock lock# (.readLock ^ReentrantReadWriteLock ~schema-lock)]
       (.lock lock#)
       (try
         (binding [impl.indexed/*schema-cache* (deref ~schema-state)]
           ~@body)
         (finally
           (.unlock lock#))))))

(defn- capture-result-context
  "Captures one DB and its matching relationship proof under the read barrier.

  The barrier covers only the coordinator-snapshot/DB pair. Targeted waits,
  permission-path resolution and the dependency proof happen outside it. A
  cursor or exact request uses a historical DB and request-scoped schema cache."
  [conn opts consistency-value coordinate? prepare operation decoded]
  (let [{:keys [mode requested-t]}
        (consistency-request opts consistency-value)
        cursor-t (:basis-t decoded)
        _ (when (and decoded
                     (= :at-least-as-fresh mode)
                     (> requested-t cursor-t))
            (throw
             (ex-info "The cursor is older than the requested freshness."
                      {:type :eacl.consistency/incompatible-cursor
                       :cursor-t cursor-t
                       :requested-t requested-t})))
        _ (when (and decoded
                     (= :at-exact-snapshot mode)
                     (not= requested-t cursor-t))
            (throw
             (ex-info "The cursor and exact-snapshot token name different revisions."
                      {:type :eacl.consistency/incompatible-cursor
                       :cursor-t cursor-t
                       :requested-t requested-t})))
        historical-t (or cursor-t
                         (when (= :at-exact-snapshot mode)
                           requested-t))]
    (if historical-t
      (let [db (historical-db conn opts historical-t operation)
            schema-cache (impl.indexed/make-schema-cache db)
            prepared
            (binding [impl.indexed/*schema-cache* schema-cache]
              (prepare db))]
        (revision/observe! (:revision-checkpoints opts)
                           (d/basis-t (d/db conn)))
        (assoc prepared
               :mode :at-exact-snapshot
               :requested-t requested-t
               :basis-t historical-t
               :cache-scope (:cache-scope decoded)
               :cursor-scope (:cache-scope decoded)
               :schema-cache schema-cache
               :schema-version (some-> schema-cache :schema-version str)))
      (let [minimum-t (when (= :at-least-as-fresh mode)
                        requested-t)
            _ (await-revision-db conn opts minimum-t)
            coordinator (when coordinate?
                          (:relationship-coordinator opts))
            ;; ONLY the (snapshot, db) pair needs the barrier: a writer must
            ;; not be able to commit and publish between those two reads.
            ;; `prepare` and the dependency proof are pure functions of that
            ;; immutable pair, so they run outside it — holding the barrier
            ;; across permission-path resolution made every relationship write
            ;; queue behind unrelated readers.
            [snapshot db]
            (loop [attempt 0]
              (let [[snapshot db :as pair]
                    (if coordinator
                      (cache/with-read coordinator
                        (fn [snapshot] [snapshot (d/db conn)]))
                      [nil (d/db conn)])
                    coordinator-floor (:observed-t snapshot)]
                (if (and coordinator-floor
                         (< (d/basis-t db) coordinator-floor))
                  (if (< attempt max-coordinator-catchup-attempts)
                    (do
                      (await-revision-db conn opts coordinator-floor)
                      (recur (inc attempt)))
                    (freshness-unavailable!
                     "This connection could not reach the coordinator's published EACL revision."
                     {:reason :coordinator-floor-unreachable
                      :requested-t coordinator-floor
                      :observed-t (d/basis-t db)
                      :attempts attempt}))
                  pair)))
            observed-t (d/basis-t db)
            {:keys [relationship-dependencies] :as prepared} (prepare db)
            cache-scope
            (if snapshot
              [:relationships
               (cache/dependency-generation snapshot relationship-dependencies)]
              [:basis observed-t])]
        (revision/observe! (:revision-checkpoints opts) observed-t)
        (assoc prepared
               :mode mode
               :requested-t requested-t
               :basis-t observed-t
               :cache-scope cache-scope
               :schema-version (client-schema-version opts))))))

(defn- with-result-schema
  [{:keys [schema-cache]} f]
  (if schema-cache
    (binding [impl.indexed/*schema-cache* schema-cache]
      (f))
    (f)))

(def ^:private delete-object-batch-size 1000)

(defn spiceomic-delete-object!
  "Retracts every relationship touching `object`, in both directions, in
  batches. Returns {:zed/token ... :retracted-datoms <n>}.

  The object's own entity is left alone — retract it yourself once this
  returns (or in the same application transaction, using
  eacl.datomic.impl/tx-delete-object directly).

  The coordinator barrier is taken PER BATCH, not once around the whole loop.
  Holding it across every batch blocked all concurrent lookups for the full
  multi-transaction delete (measured 277ms for 20k relationships in-memory,
  and far worse against a real transactor). Per-batch publication is equally
  coherent: the batches are separate Datomic transactions either way, so a
  reader between them already observes a partially deleted object, and each
  batch publishes its own committed t for the relations it actually changed."
  [conn {:keys [object-id->entid relationship-coordinator] :as opts} object]
  (let [object-id (if (map? object) (:id object) object)
        db        (d/db conn)
        eid       (or (try (object-id->entid db object-id)
                           (catch Exception _ nil))
                      ;; A retracted entity no longer resolves through the
                      ;; caller's id coercion, but its raw eid still cleans up.
                      (when (number? object-id) object-id))
        tx-data   (impl/tx-delete-object db eid)]
    (if (empty? tx-data)
      {:zed/token (revision/zed-token opts (:database-id opts) (d/basis-t db))
       :retracted-datoms 0}
      (loop [batches   (partition-all delete-object-batch-size tx-data)
             retracted 0
             basis-t   nil]
        (if-let [batch (first batches)]
          (let [[batch-retracted batch-basis]
                (coordinate-relationship-mutation
                 relationship-coordinator
                 (fn []
                   (cache/mutation-attempted!)
                   (let [{:keys [db-after tx-data]} @(d/transact conn (vec batch))
                         basis (d/basis-t db-after)
                         changes (relationship-changes db-after tx-data)]
                     [[(relationship-retraction-count db-after tx-data) basis]
                      (when (seq changes)
                        {:dependency-keys changes
                         :basis-t basis})])))]
            (recur (next batches)
                   (+ retracted batch-retracted)
                   batch-basis))
          {:zed/token (revision/zed-token opts (:database-id opts) basis-t)
           :retracted-datoms retracted})))))

(defn spiceomic-can?
  [conn {:keys [object->entid] :as opts}
   subject permission resource consistency-value]
  (let [prepare
        (fn [db]
          (let [subject-type (:type subject)
                subject-eid (object->entid db subject)
                resource-type (:type resource)
                resource-eid (object->entid db resource)]
            {:db db
             :internal-subject (spice-object subject-type subject-eid)
             :internal-resource (spice-object resource-type resource-eid)
             :relationship-dependencies
             (when (:cache-live-results? opts)
               (impl.indexed/permission-relationship-eids
                db resource-type permission))}))
        {:keys [db internal-subject internal-resource]
         :as result-context}
        (capture-result-context
         conn opts consistency-value (:cache-live-results? opts)
         prepare :can? nil)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      false
      (let [query-identity
            [:subject (:type internal-subject) (:id internal-subject)
             :permission permission
             :resource (:type internal-resource) (:id internal-resource)]]
        (:result
         (cached-authorization-result
          opts result-context :can? query-identity :can?
          boolean-result? (constantly 64)
          #(with-result-schema
             result-context
             (fn []
               (impl/can? db internal-subject permission
                          internal-resource)))))))))

(defn spiceomic-lookup-resources
  [conn
   {:as opts
    :keys [spice-object->internal]}
  {:as query :keys [subject]}]
  (log/debug 'spiceomic-lookup-resources 'query query)
  (reject-live-basis! query)
  (let [query-shape (list-query-shape :lookup-resources query)
        page-req (impl.indexed/normalize-page-request query)
        decoded (decoded-page-bound opts page-req)
        _ (validate-page-token-identity!
           opts :lookup-resources query-shape decoded)
        prepare
        (fn [db]
          (let [internal-subject (spice-object->internal db subject)
                query' (assoc query :subject internal-subject)]
            {:db db
             :internal-subject internal-subject
             :query' query'
             :query-shape query-shape
             :internal-query (internal-page-query query' page-req decoded)
             :relationship-dependencies
             (when (:relationship-coordinator opts)
               (impl.indexed/permission-relationship-eids
                db (:resource/type query') (:permission query')))}))
        captured
        (capture-result-context
         conn opts (:consistency query) true prepare
         :lookup-resources decoded)
        {:keys [db internal-subject query' query-shape internal-query
                cache-scope cursor-scope schema-version]
         :as result-context}
        captured
        selected-opts (assoc opts :selected-schema-version schema-version)]
    (validate-page-token-schema! selected-opts decoded)
    (if (nil? (:id internal-subject))
      ;; Unknown subjects match nothing and never enter the cache.
      empty-page
      (let [compute
            #(with-result-schema
               result-context
               (fn []
                 (impl/lookup-resources
                  db internal-query
                  {:recursive-continuation-cache-fn
                   (fn []
                     (recursive-continuation-context
                      selected-opts
                      :lookup-resources
                      (list-query-identity :lookup-resources query')
                      (or cursor-scope cache-scope)))})))
            answer
            (cached-authorization-result
             selected-opts result-context :lookup-resources internal-query
             :lookup-page internal-page? internal-page-weight compute)
            selected-basis (:basis-t answer)
            internal-page (:result answer)
            same-basis? (= selected-basis (d/basis-t db))
            selected-db
            (if same-basis?
              db
              (historical-db
               conn selected-opts selected-basis :lookup-resources))
            token-scope (or (:cursor-scope result-context)
                            (:cache-scope answer)
                            cache-scope)]
        (coerce-lookup-page
         selected-db selected-opts :lookup-resources query-shape
         selected-basis token-scope
         ;; Historical when a cursor/exact request pinned an older basis, or
         ;; when a staleness mode selected an older cached answer to coerce
         ;; against. Only then is an unresolvable eid a snapshot-age question
         ;; rather than a live data-integrity fault.
         (or (not same-basis?)
             (= :at-exact-snapshot (:mode result-context)))
         internal-page)))))

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
  [conn
   {:as opts :keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (let [prepare
        (fn [db]
          (let [subject-ent (spice-object->internal db subject)
                query' (-> query
                           (assoc :subject subject-ent)
                           (dissoc :consistency))]
            {:db db
             :subject-ent subject-ent
             :query' query'
             :relationship-dependencies
             (when (:cache-live-results? opts)
               (impl.indexed/permission-relationship-eids
                db (:resource/type query') (:permission query')))}))
        {:keys [db subject-ent query']
         :as result-context}
        (capture-result-context
         conn opts (:consistency query) (:cache-live-results? opts)
         prepare :count-resources nil)]
    (if (nil? (:id subject-ent))
      (empty-count-response query)
      (:result
       (cached-authorization-result
        opts result-context :count-resources query'
        :count count-response? (constantly 256)
        #(with-result-schema
           result-context
           (fn []
             (impl/count-resources db query'))))))))

(defn spiceomic-lookup-subjects
  [conn
   {:as opts
    :keys [spice-object->internal]}
  query]
  (reject-live-basis! query)
  (let [query-shape (list-query-shape :lookup-subjects query)
        page-req (impl.indexed/normalize-page-request query)
        decoded (decoded-page-bound opts page-req)
        _ (validate-page-token-identity!
           opts :lookup-subjects query-shape decoded)
        prepare
        (fn [db]
          (let [internal-resource
                (spice-object->internal db (:resource query))
                query' (assoc query :resource internal-resource)]
            {:db db
             :internal-resource internal-resource
             :query' query'
             :query-shape query-shape
             :internal-query (internal-page-query query' page-req decoded)
             :relationship-dependencies
             (when (:relationship-coordinator opts)
               (impl.indexed/permission-relationship-eids
                db (:type internal-resource) (:permission query')))}))
        captured
        (capture-result-context
         conn opts (:consistency query) true prepare
         :lookup-subjects decoded)
        {:keys [db internal-resource query' query-shape internal-query
                cache-scope cursor-scope schema-version]
         :as result-context}
        captured
        selected-opts (assoc opts :selected-schema-version schema-version)]
    (validate-page-token-schema! selected-opts decoded)
    (if (nil? (:id internal-resource))
      empty-page
      (let [compute
            #(with-result-schema
               result-context
               (fn []
                 (impl/lookup-subjects
                  db internal-query
                  {:recursive-continuation-cache-fn
                   (fn []
                     (recursive-continuation-context
                      selected-opts
                      :lookup-subjects
                      (list-query-identity :lookup-subjects query')
                      (or cursor-scope cache-scope)))})))
            answer
            (cached-authorization-result
             selected-opts result-context :lookup-subjects internal-query
             :lookup-page internal-page? internal-page-weight compute)
            selected-basis (:basis-t answer)
            internal-page (:result answer)
            same-basis? (= selected-basis (d/basis-t db))
            selected-db
            (if same-basis?
              db
              (historical-db
               conn selected-opts selected-basis :lookup-subjects))
            token-scope (or (:cursor-scope result-context)
                            (:cache-scope answer)
                            cache-scope)]
        (coerce-lookup-page
         selected-db selected-opts :lookup-subjects query-shape
         selected-basis token-scope
         ;; Historical when a cursor/exact request pinned an older basis, or
         ;; when a staleness mode selected an older cached answer to coerce
         ;; against. Only then is an unresolvable eid a snapshot-age question
         ;; rather than a live data-integrity fault.
         (or (not same-basis?)
             (= :at-exact-snapshot (:mode result-context)))
         internal-page)))))

(defn spiceomic-count-subjects
  [conn
   {:as opts :keys [spice-object->internal]}
  query]
  (let [prepare
        (fn [db]
          (let [resource-ent
                (spice-object->internal db (:resource query))
                query' (-> query
                           (assoc :resource resource-ent)
                           (dissoc :consistency))]
            {:db db
             :resource-ent resource-ent
             :query' query'
             :relationship-dependencies
             (when (:cache-live-results? opts)
               (impl.indexed/permission-relationship-eids
                db (:type resource-ent) (:permission query')))}))
        {:keys [db resource-ent query']
         :as result-context}
        (capture-result-context
         conn opts (:consistency query) (:cache-live-results? opts)
         prepare :count-subjects nil)]
    (if (nil? (:id resource-ent))
      (empty-count-response query)
      (:result
       (cached-authorization-result
        opts result-context :count-subjects query'
        :count count-response? (constantly 256)
        #(with-result-schema
           result-context
           (fn []
             (impl/count-subjects db query'))))))))

(defrecord Spiceomic [conn opts schema-state schema-lock]
  IAuthorization
  (can? [_ subject permission resource]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-can? conn opts subject permission resource consistency/fully-consistent)))

  (can? [_ subject permission resource consistency]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-can? conn opts subject permission resource consistency)))

  (can? [_ {:keys [subject permission resource consistency]}]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-can? conn opts subject permission resource
                      (or consistency consistency/fully-consistent))))

  (read-schema [_]
    (with-client-schema-read conn schema-lock schema-state
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
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-read-relationships conn opts filters)))

  (write-relationships! [_ updates]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts updates)))

  (create-relationships! [_ relationships]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      (for [rel relationships]
                                        (->RelationshipUpdate :create rel)))))

  (create-relationship! [_ relationship]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :create relationship)])))

  (create-relationship! [_ subject relation resource]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :create (->Relationship subject relation resource))])))

  ;; Audit §13: these were declared on the protocol but unimplemented ->
  ;; AbstractMethodError at runtime.
  (write-relationship! [_ operation subject relation resource]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate operation (->Relationship subject relation resource))])))

  (write-relationship! [_ {:keys [operation subject relation resource]}]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate operation (->Relationship subject relation resource))])))

  (delete-relationship! [_ subject relation resource]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :delete (->Relationship subject relation resource))])))

  (delete-relationship! [_ {:keys [subject relation resource]}]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-write-relationships! conn opts
                                      [(->RelationshipUpdate :delete (->Relationship subject relation resource))])))

  (delete-relationships! [_ relationships]
    (with-client-schema-read conn schema-lock schema-state
      (let [relationships' (if (map? relationships)
                             (:data relationships)
                             relationships)]
        (spiceomic-write-relationships! conn opts
                                        (for [rel relationships']
                                          (->RelationshipUpdate :delete rel))))))

  (delete-object! [_ object]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-delete-object! conn opts object)))

  (lookup-resources [_ query]
    (with-client-schema-read conn schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-lookup-resources conn opts query))))

  (count-resources [_ query]
    (with-client-schema-read conn schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-count-resources conn opts query))))

  (lookup-subjects [_ query]
    (with-client-schema-read conn schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-lookup-subjects conn opts query))))

  (count-subjects [_ query]
    (with-client-schema-read conn schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-count-subjects conn opts query))))

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
    :zed-token-key
    :zed-token-keyring
    :zed-token-kid
    :consistency-sync-timeout-ms
    :recursive-traversal-limits
    :auto-migrate-v6})

(def ^:private known-cache-opt-keys
  #{:store
    :coordinator
    :live-results?
    :exact-results?
    :namespace
    :checkpoints
    :ttl-ms
    :max-weight
    :max-entry-weight
    :max-entries
    :kind-max-weight
    :two-hit-kinds
    :admission-entries})

(defn- normalize-cache-config
  [cache-option page-token-ttl-seconds]
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
    (when (and (contains? config :live-results?)
               (not (boolean? (:live-results? config))))
      (throw (ex-info "EACL Config Error: :cache :live-results? must be boolean."
                      {:type :eacl/invalid-config
                       :key :cache
                       :value (:live-results? config)})))
    (when (and (contains? config :exact-results?)
               (not (boolean? (:exact-results? config))))
      (throw (ex-info "EACL Config Error: :cache :exact-results? must be boolean."
                      {:type :eacl/invalid-config
                       :key :cache
                       :value (:exact-results? config)})))
    (when-not (or (nil? (:namespace config))
                  (keyword? (:namespace config))
                  (and (string? (:namespace config))
                       (not-empty (:namespace config))))
      (throw (ex-info "EACL Config Error: :cache :namespace must be a keyword or non-empty string."
                      {:type :eacl/invalid-config
                       :key :cache
                       :value (:namespace config)})))
    (when-not (or (nil? (:checkpoints config))
                  (false? (:checkpoints config))
                  (true? (:checkpoints config))
                  (map? (:checkpoints config)))
      (throw (ex-info "EACL Config Error: :cache :checkpoints must be false, true, or a configuration map."
                      {:type :eacl/invalid-config
                       :key :cache
                       :value (:checkpoints config)})))
    (let [enabled? (not (false? cache-option))
          live-results? (and enabled? (true? (:live-results? config)))
          exact-results? (and enabled?
                              (or live-results?
                                  (true? (:exact-results? config))))
          token-ttl-ms (* 1000 (or page-token-ttl-seconds
                                   default-page-token-ttl-seconds))
          ttl-ms (or (:ttl-ms config) token-ttl-ms)
          _ (when-not (and (integer? ttl-ms) (pos? ttl-ms))
              (throw (ex-info "EACL Config Error: :cache :ttl-ms must be a positive integer."
                              {:type :eacl/invalid-config
                               :key :cache
                               :value ttl-ms})))
          store (when enabled?
                  (if (false? (:store config))
                    nil
                    (or (:store config)
                        (cache/local-store
                         (select-keys config [:max-weight
                                              :max-entry-weight
                                              :max-entries
                                              :kind-max-weight
                                              :two-hit-kinds
                                              :admission-entries])))))
          configured-coordinator (:coordinator config)
          coordinator (when enabled?
                        (or configured-coordinator
                            (cache/local-coordinator)))]
      (when (and store (not (satisfies? cache/CacheStore store)))
        (throw (ex-info "EACL Config Error: :cache :store must implement CacheStore."
                        {:type :eacl/invalid-config
                         :key :cache
                         :value (:store config)})))
      (when (and coordinator
                 (not (satisfies? cache/RelationshipCoordinator coordinator)))
        (throw (ex-info "EACL Config Error: :cache :coordinator must implement RelationshipCoordinator."
                        {:type :eacl/invalid-config
                         :key :cache
                         :value (:coordinator config)})))
      (when (and live-results? (nil? store))
        (throw (ex-info "EACL Config Error: :cache :live-results? requires a cache store."
                        {:type :eacl/invalid-config
                         :key :cache
                         :value config})))
      (when (and live-results? (nil? configured-coordinator))
        (throw (ex-info "EACL Config Error: :cache :live-results? requires an explicit coordinator."
                        {:type :eacl/invalid-config
                         :key :cache
                         :value config})))
      {:store store
       :coordinator coordinator
       :namespace (or (:namespace config) :eacl)
       :checkpoints
       (when (and enabled? (:checkpoints config))
         (revision/revision-checkpoints
          (if (map? (:checkpoints config))
            (:checkpoints config)
            {})))
       :ttl-ms (min ttl-ms token-ttl-ms)
       :exact-results? exact-results?
       ;; Cross-request live memoization requires every relationship writer in
       ;; the coherence scope to receive this same explicit coordinator.
       ;; The client-local default coordinator gives its own cursors a precise
       ;; relationship proof without enabling cross-client live reuse.
       :live-results? live-results?})))

(defn make-client
  "Builds an IAuthorization client over a Datomic conn.

  Options (unknown keys throw :eacl/invalid-config — a silently ignored key
  means silently wrong ID coercion):
  - :entid->object-id  (fn [db eid] external-id) — canonical ID coercion, as documented in the README.
  - :entity->object-id (fn [entity] external-id) — deprecated alias; do not combine with the above.
  - :object-id->ident  (fn [external-id] ident-resolvable-by-d-entid). Default: [:eacl/id id].
  - :cache — false disables all lookup caching. A map configures the bounded
    ephemeral store with :max-weight, :max-entry-weight, :max-entries and
    :ttl-ms. :exact-results? true retains completed cache-resident snapshots
    without live reuse. :live-results? true implies exact retention, enables
    cross-request can?/lookup/count memoization, and requires an explicit
    :coordinator shared by every EACL relationship reader and writer in that
    coherence scope. Use
    eacl.datomic.cache/local-context to create a local :store/:coordinator
    pair. :store false creates a writer-only client which still advances the
    supplied coordinator. :store and :coordinator accept custom protocol
    implementations. Cache failures are contained as misses or rejected
    publications. Missing exact pages and recursive continuations replay
    against the authenticated historical basis rather than falling forward.
  - :page-token-key / :page-token-keys / :page-token-keyring / :page-token-kid —
    AES-GCM page-token key material. Default: a random per-client key, meaning
    page tokens do not survive restarts and are not portable across clients;
    supply stable key material in production.
  - :page-token-ttl-seconds — overrides the default page-token expiry.
  - :zed-token-key / :zed-token-keyring / :zed-token-kid — HMAC key material
    for authenticated Zed tokens. When omitted, purpose-specific signing keys
    are derived from the page-token keyring. Supply a stable shared keyring for
    frontend round trips across restarts or multiple backend instances.
  - :consistency-sync-timeout-ms — positive maximum wait for a targeted
    Datomic revision. Defaults to 30000.
  - :recursive-traversal-limits — overrides eacl.datomic.impl.indexed/default-recursive-traversal-limits
    for list calls, e.g. {:max-derived-grants 1000000 :max-advanced-datoms 1000000
    :max-queued-work 1000000}. Recursive traversal remains subject to these
    host-JVM memory bounds; tune them only after representative heap/load
    tests.
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
           zed-token-key
           zed-token-keyring
           zed-token-kid
           consistency-sync-timeout-ms
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
  (when (and (contains? config-opts :zed-token-key)
             (contains? config-opts :zed-token-keyring))
    (throw (ex-info "EACL Config Error: supply only one of :zed-token-key or :zed-token-keyring."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:zed-token-key :zed-token-keyring]})))
  (let [timeout-ms (if (contains? config-opts
                                   :consistency-sync-timeout-ms)
                     consistency-sync-timeout-ms
                     default-consistency-sync-timeout-ms)]
    (when-not (and (integer? timeout-ms) (pos? timeout-ms))
      (throw (ex-info "EACL Config Error: :consistency-sync-timeout-ms must be a positive integer."
                      {:type :eacl/invalid-config
                       :key :consistency-sync-timeout-ms
                       :value consistency-sync-timeout-ms}))))
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
                                                   page-token-ttl-seconds)
        timeout-ms         (if (contains? config-opts
                                          :consistency-sync-timeout-ms)
                             consistency-sync-timeout-ms
                             default-consistency-sync-timeout-ms)
        schema-lock        (ReentrantReadWriteLock.)
        entid->object-id   (or entid->object-id
                               (when entity->object-id
                                 (fn [db eid] (entity->object-id (d/entity db eid))))
                               (fn [db eid] (:eacl/id (d/entity db eid))))
        object-id->entid   (fn [db object-id]
                             (d/entid db (object-id->ident object-id)))
        current-kid        (or page-token-kid :current)
        _                  (validate-token-key-id! :page-token-kid current-kid)
        configured-keyring (or page-token-keyring page-token-keys)
        keyring            (if configured-keyring
                             (normalize-token-keyring
                              :page-token-keyring
                              configured-keyring)
                             {current-kid (if page-token-key
                                            (normalize-token-key page-token-key)
                                            (random-bytes 32))})
        _                  (when-not (get keyring current-kid)
                             (throw (ex-info "Page token current key id is not present in keyring."
                                             {:page-token-kid current-kid
                                              :available-kids (set (keys keyring))})))
        zed-current-kid    (if (contains? config-opts :zed-token-kid)
                             zed-token-kid
                             current-kid)
        _                  (validate-token-key-id! :zed-token-kid
                                                   zed-current-kid)
        zed-root-keyring   (cond
                             (contains? config-opts :zed-token-keyring)
                             (normalize-token-keyring
                              :zed-token-keyring
                              zed-token-keyring)

                             (contains? config-opts :zed-token-key)
                             {zed-current-kid
                              (normalize-token-key zed-token-key)}

                             :else
                             keyring)
        _                  (when-not (get zed-root-keyring zed-current-kid)
                             (throw
                              (ex-info "Zed token current key id is not present in keyring."
                                       {:type :eacl/invalid-config
                                        :zed-token-kid zed-current-kid
                                        :available-kids
                                        (set (keys zed-root-keyring))})))
        zed-keyring        (into {}
                                 (map (fn [[kid root-key]]
                                        [kid
                                         (revision/derive-signing-key
                                          root-key)]))
                                 zed-root-keyring)
        opts               {:object-id->ident object-id->ident
                            :schema-state schema-state
                            :database-id (:database-id @schema-state)
                            :lookup-cache-store (:store cache-config)
                            :lookup-cache-ttl-ms (:ttl-ms cache-config)
                            :cache-namespace (:namespace cache-config)
                            :cache-live-results? (:live-results? cache-config)
                            :cache-exact-results? (:exact-results? cache-config)
                            :relationship-coordinator (:coordinator cache-config)
                            :revision-checkpoints (:checkpoints cache-config)
                            :opaque-cache-token (Object.)
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
                            :zed-token-current-kid zed-current-kid
                            :zed-token-keyring zed-keyring
                            :consistency-sync-timeout-ms timeout-ms
                            ;; merged, so a partial override cannot silently
                            ;; disable the limits it does not mention
                            :recursive-traversal-limits (when recursive-traversal-limits
                                                          (merge impl.indexed/default-recursive-traversal-limits
                                                                 recursive-traversal-limits))}]
    (->Spiceomic conn opts schema-state schema-lock)))

(defn current-zed-token
  "Returns a token for the client's currently observed Datomic basis.

  This does not call d/sync."
  [client]
  (when-not (instance? Spiceomic client)
    (throw (ex-info "current-zed-token requires an EACL Datomic client."
                    {:type :eacl/invalid-client})))
  (let [db (d/db (:conn client))
        opts (:opts client)
        basis-t (d/basis-t db)]
    (revision/observe! (:revision-checkpoints opts) basis-t)
    (revision/zed-token opts (:database-id opts) basis-t)))

(defn zed-token-at-least-seconds-ago
  "Returns an at-least-as-fresh token based on bounded observed checkpoints.

  Checkpointing must be enabled in `make-client` with
  `{:cache {:checkpoints true}}` (or a checkpoint config map). The helper
  never calls d/sync and returns an over-fresh current revision when no
  retained observation can establish the requested age."
  [client seconds-ago]
  (when-not (instance? Spiceomic client)
    (throw (ex-info "zed-token-at-least-seconds-ago requires an EACL Datomic client."
                    {:type :eacl/invalid-client})))
  (let [db (d/db (:conn client))
        opts (:opts client)
        checkpoints (:revision-checkpoints opts)
        basis-t (d/basis-t db)]
    (when-not checkpoints
      (throw (ex-info "Revision checkpoints are disabled for this EACL client."
                      {:type :eacl/checkpoints-disabled})))
    (revision/observe! checkpoints basis-t)
    (revision/zed-token
     opts
     (:database-id opts)
     (revision/revision-at-least-seconds-ago
      checkpoints seconds-ago basis-t))))
