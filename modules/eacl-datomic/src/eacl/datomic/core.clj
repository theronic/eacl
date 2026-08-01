(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [com.rpl.specter :as S]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as shared-cache]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.codec :as codec]
            [eacl.datomic.consistency :as revision]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.mutation :as journal]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.mutation :as mutation]
            [eacl.relay :as relay]
            [eacl.secure-format :as secure]
            [eacl.spicedb.consistency :as consistency])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream
            DataOutputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Arrays Base64]
           [java.util.concurrent.locks Lock ReentrantReadWriteLock]
           [java.util.function Supplier]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

;; eacl4_ tokens carry a binary envelope; the eacl3_ EDN format they replace is
;; not read. Cursors are short-lived (300s by default) and every caller already
;; handles :eacl.pagination/invalid-cursor on expiry, so a rolling deploy
;; degrades to "restart your pagination", not to wrong answers.
(def ^:private page-token-prefix "eacl4_")
(def ^:private page-token-version 6)
(def ^:private maximum-page-token-length
  "Bounds decode work on an unauthenticated caller-supplied cursor. Real EACL
  cursors are well under this even with per-path frontiers for a wide
  permission graph; mirrors the cap eacl.datomic.consistency puts on Zed
  tokens."
  16384)
(def ^:private default-page-token-ttl-seconds 300)
(def ^:private maximum-page-token-ttl-seconds
  ;; Keep both the cache's millisecond lifetime and the cursor's second-based
  ;; expiry representable as signed longs. This is intentionally enormous
  ;; (about 292 million years); it is a numeric-safety bound, not policy.
  (quot Long/MAX_VALUE 1000))
(def ^:private default-consistency-sync-timeout-ms 30000)
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

(def ^:private ^ThreadLocal aead-cipher
  "Cipher instances are not thread-safe, but Cipher/getInstance costs ~0.77us
  per call — a JCA provider lookup on the hottest pagination path. `init`
  fully resets state, including after a failed `doFinal`, so one instance per
  thread is safe to reuse."
  (ThreadLocal/withInitial
   (reify Supplier
     (get [_] (Cipher/getInstance "AES/GCM/NoPadding")))))

(defn- aead
  [mode ^bytes key ^bytes nonce ^bytes aad ^bytes input]
  (let [^Cipher cipher (.get aead-cipher)]
    (.init cipher (int mode)
           (SecretKeySpec. key "AES")
           (GCMParameterSpec. 128 nonce))
    (.updateAAD cipher aad)
    (.doFinal cipher input)))

(defn- encrypt-aead
  [^bytes key ^bytes nonce ^bytes aad ^bytes plaintext]
  (aead Cipher/ENCRYPT_MODE key nonce aad plaintext))

(defn- decrypt-aead
  [^bytes key ^bytes nonce ^bytes aad ^bytes ciphertext]
  (aead Cipher/DECRYPT_MODE key nonce aad ciphertext))

;; --- Page token envelope ----------------------------------------------------
;;
;; Layout, all big-endian, everything before :ciphertext is the AAD:
;;
;;   u8    envelope format tag (always envelope-format-tag)
;;   u8    page token version
;;   u8    kid kind (0 = keyword, 1 = string)
;;   u32   kid byte length, then those UTF-8 bytes
;;   u8    nonce length (always 12)
;;   bytes nonce
;;   ---- end of AAD ----
;;   u32   ciphertext length, then those bytes
;;
;; The AAD is the header bytes exactly as written rather than a reconstruction
;; of them, so encrypt and decrypt cannot disagree about canonical form.

(def ^:private envelope-format-tag 1)
(def ^:private nonce-length 12)
(def ^:private maximum-kid-bytes 1024)

(defn- write-kid!
  [^DataOutputStream out kid]
  (let [^String text (if (keyword? kid) (subs (str kid) 1) (str kid))
        bytes (.getBytes text StandardCharsets/UTF_8)]
    (.writeByte out (if (keyword? kid) 0 1))
    (.writeInt out (alength bytes))
    (.write out bytes 0 (alength bytes))))

(defn- read-kid
  [^DataInputStream in]
  (let [kind (int (.readByte in))
        n (.readInt in)]
    (when (or (neg? n) (> n maximum-kid-bytes))
      (invalid-page-token! :malformed))
    (let [bytes (byte-array n)
          _ (.readFully in bytes)
          text (String. bytes StandardCharsets/UTF_8)]
      (case kind
        0 (keyword text)
        1 text
        (invalid-page-token! :malformed)))))

(defn encrypt-page-token
  [opts payload]
  (when payload
    (let [{:keys [page-token-current-kid page-token-keyring]} opts
          kid page-token-current-kid
          key (get page-token-keyring kid)
          nonce (random-bytes nonce-length)
          buffer (ByteArrayOutputStream. 512)
          out (DataOutputStream. buffer)]
      (.writeByte out envelope-format-tag)
      (.writeByte out page-token-version)
      (write-kid! out kid)
      (.writeByte out nonce-length)
      (.write out nonce 0 nonce-length)
      (.flush out)
      (let [aad (.toByteArray buffer)
            ciphertext (encrypt-aead key nonce aad (codec/encode payload))]
        (.writeInt out (alength ^bytes ciphertext))
        (.write out ^bytes ciphertext 0 (alength ^bytes ciphertext))
        (.flush out)
        (let [token (str page-token-prefix
                         (b64url-encode (.toByteArray buffer)))]
          (when (> (count token) maximum-page-token-length)
            (throw
             (ex-info "EACL page token exceeds the maximum encoded length."
                      {:type :eacl.pagination/cursor-too-large
                       :encoded-length (count token)
                       :maximum-length maximum-page-token-length})))
          token)))))

(defn decrypt-page-token
  [opts token]
  (when token
    ;; Length is checked before any decoding: the envelope is decoded before
    ;; its AES-GCM tag can be verified, so an unbounded token would be
    ;; unauthenticated CPU and allocation amplification.
    (when-not (and (string? token)
                   (<= (count token) maximum-page-token-length)
                   (.startsWith ^String token page-token-prefix))
      (invalid-page-token! :malformed))
    (try
      (let [{:keys [page-token-keyring]} opts
            envelope (b64url-decode (subs token (count page-token-prefix)))
            in (DataInputStream. (ByteArrayInputStream. envelope))]
        (when-not (= envelope-format-tag (int (.readByte in)))
          (invalid-page-token! :malformed))
        (let [version (int (.readByte in))
              _ (when-not (= page-token-version version)
                  (invalid-page-token! :unsupported-version {:version version}))
              kid (read-kid in)
              key (get page-token-keyring kid)
              _ (when-not key
                  (invalid-page-token! :unknown-key-id {:kid kid}))
              declared-nonce-length (int (.readByte in))
              _ (when-not (= nonce-length declared-nonce-length)
                  (invalid-page-token! :malformed))
              nonce (byte-array nonce-length)
              _ (.readFully in nonce)
              ;; Everything consumed so far — up to and including the nonce —
              ;; is the AAD, byte-for-byte as encrypt wrote it.
              aad-length (- (alength ^bytes envelope) (.available in))
              aad (Arrays/copyOfRange ^bytes envelope 0 (int aad-length))
              ciphertext-length (.readInt in)
              _ (when-not (and (not (neg? ciphertext-length))
                               (<= ciphertext-length (.available in)))
                  (invalid-page-token! :malformed))
              ciphertext (byte-array ciphertext-length)
              _ (.readFully in ciphertext)
              _ (when (pos? (.available in))
                  ;; The GCM tag authenticates `ciphertext-length` bytes, not an
                  ;; arbitrary suffix. Accepting a suffix made the public token
                  ;; encoding non-canonical and left attacker-controlled bytes
                  ;; outside authentication.
                  (invalid-page-token! :malformed))
              payload (codec/decode (decrypt-aead key nonce aad ciphertext))
              now (now-seconds)]
          (when-not (map? payload)
            (invalid-page-token! :malformed))
          (when-not (= page-token-version (:v payload))
            (invalid-page-token! :unsupported-version {:version (:v payload)}))
          (when (and (:exp payload) (<= (:exp payload) now))
            (throw
             (ex-info
              "Page token has expired."
              {:type :eacl.pagination/expired-cursor
               :eacl/error :eacl.pagination/expired-cursor
               :reason :expired
               :exp (:exp payload)
               :now now})))
          payload))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      ;; A StackOverflowError is an Error, not an Exception: before the codec
      ;; replaced edn/read-string here, hostile nesting walked straight out of
      ;; lookup-resources past every `catch Exception` in the request stack.
      (catch StackOverflowError _
        (invalid-page-token! :malformed))
      (catch Exception _
        (invalid-page-token! :malformed)))))

(defn- valid-page-token-ttl?
  [ttl-seconds]
  (and (integer? ttl-seconds)
       (pos? ttl-seconds)
       (<= ttl-seconds maximum-page-token-ttl-seconds)))

(defn- validate-page-token-ttl!
  [ttl-seconds]
  (when-not (valid-page-token-ttl? ttl-seconds)
    (throw
     (ex-info
      "EACL Config Error: :page-token-ttl-seconds must be a positive bounded integer."
      {:type :eacl/invalid-config
       :key :page-token-ttl-seconds
       :value ttl-seconds
       :maximum maximum-page-token-ttl-seconds})))
  ttl-seconds)

(defn page-token
  [opts {:keys [ttl-seconds]
         :or {ttl-seconds default-page-token-ttl-seconds}
         :as payload}]
  (let [ttl-seconds (validate-page-token-ttl! ttl-seconds)]
    (encrypt-page-token opts
                        (-> payload
                            (dissoc :ttl-seconds)
                            (assoc :v page-token-version
                                   :exp (+ (now-seconds)
                                           (long ttl-seconds)))))))

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
    ;; :cache? is excluded for the same reason as :consistency: it selects HOW
    ;; the answer is obtained, not WHICH answer. Leaving it in would make a
    ;; page-2 request that omits it fail validation against a page-1 token
    ;; minted with it — which is the exact failure :consistency already caused.
    :query (dissoc query
                   :first :last :after :before
                   :cursor :limit :page/basis :consistency :cache?)}))

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
                       {:cache-scope (:cache-scope decoded)}))
    (doseq [field [:source-scope
                   :graph-head
                   :adapter-fingerprint
                   :identity-contract
                   :dependency-scope-digest
                   :proof-digest]]
      (when-not (contains? decoded field)
        (invalid-cursor!
         "Page token is missing proof-equivalent continuation metadata."
         :missing-proof-context
         {:field field}))))
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
    (cond-> (dissoc query :after :before :page/basis :consistency :cache?)
      (and edge (= :asc (:direction page-req))) (assoc :after edge)
      (and edge (= :desc (:direction page-req))) (assoc :before edge))))

(defn- encode-page-cursor
  ([opts op query-shape basis-t edge]
   (encode-page-cursor opts op query-shape basis-t nil edge))
  ([opts op query-shape basis-t cache-scope edge]
   (when edge
     (page-token opts
                 (cond-> (merge
                          (:page-cursor-context opts)
                          {:op op
                           :database-id (:database-id opts)
                           :query-shape query-shape
                           :basis-t basis-t
                           :basis :stable
                           :schema-version (selected-schema-version opts)
                           :cache-scope cache-scope
                           :edge edge})
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

(def ^:private answer-cache-kinds
  "The entry kinds that hold a finished answer, as opposed to the traversal and
  pagination state EACL caches regardless. :remember-answers governs exactly
  these four."
  #{:can? :lookup-page :count :latest-result})

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

(defn- cached-authorization-result
  [opts consistency-context op query-identity kind valid-result? _weight-fn compute]
  (let [{:keys [adapter schema-dependencies relationship-dependencies basis-t]}
        consistency-context
        answer
        (shared-cache/resolve!
         adapter
         (if (:cache-remember-answers? opts)
           (:shared-cache-store opts)
           shared-cache/no-cache)
         {:operation op
          :query query-identity
          :engine-version engine/engine-version
          :proof-mode (:proof-mode opts)
          :recursive-traversal-limits
          (:recursive-traversal-limits opts)}
         kind
         schema-dependencies
         relationship-dependencies
         valid-result?
         #(portable-result kind (compute))
         (:format-options opts))
        computation-snapshot (:cache-basis answer)]
    (assoc consistency-context
           :result (:value answer)
           :cached? (:cached? answer)
           :cache-basis
           (or (:basis-t computation-snapshot)
               basis-t))))

(defn- continuation-context
  "Recursive/frontier state is an optional optimization. Until a provider
  entry uses the same authenticated causal/proof envelope as completed
  answers, do not read it at all: the shared engine deterministically replays
  from the authenticated cursor and selected immutable snapshot."
  [_opts _op _query-identity _relationship-proof]
  nil)

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
        {:keys [db basis-t schema-version cursor-context]}
        (capture-result-context
         conn opts (:consistency filters)
         (fn [db]
           (let [relation-ids
                 (impl/relationship-relation-ids db filters)]
             {:db db
              :relationship-dependencies relation-ids
              :schema-dependencies
              {:permission-nodes []
               :relation-ids relation-ids}}))
         :read-relationships decoded)
        selected-opts
        (assoc opts
               :selected-schema-version schema-version
               :page-cursor-context cursor-context)
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

(defn- relationship-retraction-count
  [db-after tx-data]
  (let [attr-eids (relationship-attr-eids db-after)]
    (count
     (filter (fn [{:keys [a added]}]
               (and (false? added)
                    (contains? attr-eids a)))
             tx-data))))

(def ^:private maximum-relationship-write-attempts 8)

(defn- datomic-cas-failure?
  [throwable]
  (loop [cause throwable]
    (when cause
      (if (= :db.error/cas-failed (:db/error (ex-data cause)))
        true
        (recur (.getCause ^Throwable cause))))))

(defn- response-token
  [db opts]
  (when (= :managed (:coherence-authority opts))
    (let [{:keys [family-id head-id]} (journal/graph-state db)]
      (causal-token/issue
       (:format-options opts)
       {:backend :datomic
        :source-id {:database-id (:database-id opts)
                    :family-id family-id}
        :branch nil
        :graph-anchor head-id
        :order-hint (d/basis-t db)
        :exact-locator (d/basis-t db)}))))

(defn- write-response
  [db opts]
  (if-let [token (response-token db opts)]
    {:zed/token token}
    {}))

(defn spiceomic-write-relationships!
  "Writes relationships and returns a zed token for the committed revision.

  There is no process-local cache bookkeeping here. The committed transaction
  atomically carries the v3 mutation record, graph head, and affected relation
  mutation identities. Legacy :eacl/relation-version CAS datoms remain only as
  a Datomic write-serialization mechanism for the existing relationship
  storage schema; cache validity never depends on them."
  [conn opts updates]
  (let [updates (vec updates)
        mutation-id (mutation/new-id)]
    (doseq [{:keys [operation]} updates]
      (impl/validate-relationship-operation! operation))
    (loop [attempt 1]
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
            relationship-tx-data
            (->> internal-updates
                 (mapcat #(impl/tx-update-relationship db %))
                 (remove nil?)
                 vec)
            tx-data
            (when (seq relationship-tx-data)
              (impl/optimistic-relationship-tx-data
               db relationship-tx-data))
            submission
            (if (seq tx-data)
              (try
                {:report
                 (journal/transact!
                  conn
                  {:mutation-id mutation-id
                   :kind :relationships
                   :canonical-data
                   {:operation :write-relationships
                    :updates internal-updates}
                   :relation-ids relation-ids
                   :token-ttl-seconds (:token-ttl-seconds opts)
                   :retention-grace-seconds
                   (:retention-grace-seconds opts)
                   :tx-data tx-data})}
                (catch Throwable throwable
                  {:error throwable}))
              {:report {:db-before db
                        :db-after db
                        :tx-data []
                        :no-op? true}})]
        (if-let [throwable (:error submission)]
          (if (and (datomic-cas-failure? throwable)
                   (< attempt maximum-relationship-write-attempts))
            ;; Re-resolve the endpoints and relationship existence from the
            ;; winner's db. A duplicate :create becomes the documented
            ;; relationship-conflict here; unrelated same-relation writes
            ;; simply rebuild their CAS expectation and retry.
            (recur (inc attempt))
            (if (datomic-cas-failure? throwable)
              (throw
               (ex-info
                "EACL relationship write could not obtain a stable schema/relation generation."
                {:type :eacl/relationship-contention
                 :attempts attempt}
                throwable))
              (throw throwable)))
          (let [db-after (get-in submission [:report :db-after])]
            (write-response db-after opts)))))))

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
    (with-client-schema-write schema-lock
      (when (nil? (:schema-version @schema-state))
        ;; The stamp and the cache generation must come from the SAME immutable
        ;; DB value. Reading the version before acquiring this lock and the
        ;; definitions afterwards could label V2's paths with V1's UUID.
        (let [db (d/db conn)]
          (when-let [version (impl.indexed/schema-version db)]
            (reset! schema-state
                    (impl.indexed/make-schema-cache db version)))))))
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

(defn- permission-cache-dependencies
  [opts db resource-type permission]
  ;; The same complete closure protects both completed answers and cursors.
  ;; A cacheless client may still paginate, so cursor correctness cannot be
  ;; conditional on whether a result-cache provider was configured.
  (let [relation-ids
        (impl.indexed/permission-relationship-eids
         db resource-type permission)]
    {:relationship-dependencies relation-ids
     :schema-dependencies
     {:permission-nodes
      (impl.indexed/permission-schema-nodes
       db resource-type permission)
      :relation-ids relation-ids}}))

(defn- request-cache-opts
  "Validates and applies a per-request `:cache?` override to the client's opts.

  `false` bypasses the cache for this one call: nothing is read from it and
  nothing is written to it, exactly as if the client had been built with
  `{:cache cache/no-cache}`. Absent or true uses the client's configured
  cache.

  Note the key is `:cache?`, not `:cache`: the client option names WHICH cache
  (an adapter), the request option says WHETHER to use it (a boolean). Two
  different kinds of thing, so two different names.

  Everything downstream is already guarded on the store and the
  :cache-remember-answers? flag, so clearing both is the whole mechanism —
  there is no separate bypass path to keep correct. Cursors still work: a page
  token is minted and validated from the request, not from the cache."
  [opts cache-option]
  (when-not (or (nil? cache-option) (boolean? cache-option))
    (throw (ex-info "EACL Error: per-request :cache? must be true or false."
                    {:type :eacl/invalid-request
                     :key :cache?
                     :value cache-option})))
  (if (false? cache-option)
    (assoc opts
           :lookup-cache-store nil
           :cache-remember-answers? false)
    opts))

(defn- selected-schema-cache!
  [opts adapter db]
  (let [schema-proof (backend/invoke adapter :schema-proof)
        key [(backend/backend-id adapter)
             (backend/invoke adapter :source-scope)
             schema-proof]
        registry (:derived-schema-caches opts)]
    (or (get @registry key)
        (let [created
              (impl.indexed/make-schema-cache db schema-proof)]
          (get (swap! registry
                      #(if (contains? % key)
                         %
                         (assoc % key created)))
               key)))))

(def ^:private cursor-equivalence-fields
  [:source-scope
   :adapter-fingerprint
   :identity-contract
   :dependency-scope-digest
   :proof-digest])

(defn- cursor-context-equivalent?
  [current cursor-envelope]
  (and current
       (every?
        (fn [field]
          (= (secure/canonicalize (get current field))
             (secure/canonicalize (get cursor-envelope field))))
        cursor-equivalence-fields)))

(defn- snapshot-result-context
  [opts snapshot-adapter prepare]
  (let [db (:db (backend/state snapshot-adapter))
        ;; `d/as-of` filters visibility but retains its backing DB's basis-t.
        ;; The adapter exact locator is the selected logical revision.
        basis-t (backend/invoke snapshot-adapter :exact-locator)
        schema-cache
        (selected-schema-cache! opts snapshot-adapter db)
        {:keys [relationship-dependencies schema-dependencies]
         :as prepared}
        (binding [impl.indexed/*schema-cache* schema-cache]
          (prepare db))
        relationship-proof
        (when (some? relationship-dependencies)
          (backend/invoke
           snapshot-adapter
           :relation-proof
           relationship-dependencies))
        cache-scope
        (if (some? relationship-proof)
          [:relations relationship-proof]
          [:basis basis-t])
        cursor-cache-scope
        [:proof
         (secure/canonical-digest
          "eacl/datomic/cursor-cache-scope/v3"
          cache-scope)]
        schema-proof
        (backend/invoke snapshot-adapter :schema-proof)
        schema-version
        (secure/canonical-digest
         "eacl/datomic/cursor-schema-proof/v3"
         schema-proof)
        cursor-context
        (when (and schema-dependencies
                   (some? relationship-dependencies))
          (relay/dependency-context
           snapshot-adapter
           {:schema-scope schema-dependencies
            :relation-ids relationship-dependencies}))]
    (assoc prepared
           :db db
           :adapter snapshot-adapter
           :basis-t basis-t
           :cache-scope cache-scope
           ;; Cursors need an authenticated proof identity, not the proof
           ;; material itself. Content-mode proofs grow with the graph and
           ;; schema proofs grow with the schema; embedding either makes token
           ;; size attacker/workload dependent and duplicates `proof-digest`.
           :cursor-scope cursor-cache-scope
           :cursor-context cursor-context
           :schema-cache schema-cache
           :schema-version schema-version
           :cache-schema-proof
           (when schema-dependencies
             (backend/invoke
              snapshot-adapter
              :schema-proof
              schema-dependencies)))))

(defn- cursor-snapshot-expired!
  [operation decoded]
  (throw
   (ex-info
    "The cursor's exact Datomic snapshot is no longer retained."
    {:type :eacl.consistency/snapshot-expired
     :eacl/error :eacl.consistency/snapshot-expired
     :operation operation
     :exact-locator (get-in decoded [:graph-head :exact-locator])})))

(defn- capture-result-context
  "Selects one immutable request snapshot, then continues a cursor on that
  snapshot when its complete proof is equal. Only a proof mismatch may fall
  back to the cursor's authenticated original basis."
  [conn opts consistency-value prepare operation decoded]
  (let [source-adapter ((:backend-adapter-fn opts) (d/db conn))
        selection
        (consistency-v3/select
         source-adapter
         consistency-value
         {:format-options (:format-options opts)
          :coherence-authority (:coherence-authority opts)
          :issue-token? true
          :timeout-ms (:consistency-sync-timeout-ms opts)})
        selected-adapter (:adapter selection)
        selected-context
        (snapshot-result-context opts selected-adapter prepare)
        {:keys [mode]} (:descriptor selection)
        request-token (:request-token selection)
        requested-t (:order-hint request-token)
        _ (when (and decoded
                     (= :at-exact-snapshot mode)
                     (not= (:exact-locator request-token)
                           (get-in decoded
                                   [:graph-head :exact-locator])))
            (consistency-v3/cursor-conflict!
             {:cursor-exact-locator
              (get-in decoded [:graph-head :exact-locator])
              :requested-exact-locator
              (:exact-locator request-token)}))
        selected-context
        (cond
          (nil? decoded)
          selected-context

          (cursor-context-equivalent?
           (:cursor-context selected-context)
           decoded)
          selected-context

          (= :at-least-as-fresh mode)
          (consistency-v3/cursor-conflict!
           {:cursor-graph-anchor
            (get-in decoded [:graph-head :graph-anchor])
            :selected-graph-anchor
            (:graph-anchor
             (backend/invoke selected-adapter :graph-head))})

          :else
          (let [exact
                (backend/invoke
                 source-adapter
                 :select-exact
                 {:graph-anchor
                  (get-in decoded [:graph-head :graph-anchor])
                  :order-hint
                  (get-in decoded [:graph-head :order-hint])
                  :exact-locator
                  (get-in decoded [:graph-head :exact-locator])}
                 (:consistency-sync-timeout-ms opts))
                _ (when-not exact
                    (cursor-snapshot-expired! operation decoded))
                exact-context
                (snapshot-result-context opts exact prepare)]
            (when-not
             (and
              (= (secure/canonicalize
                  (backend/invoke source-adapter :source-scope))
                 (secure/canonicalize
                  (backend/invoke exact :source-scope)))
              (= (get-in decoded [:graph-head :graph-anchor])
                 (:graph-anchor (backend/invoke exact :graph-head)))
              (cursor-context-equivalent?
               (:cursor-context exact-context)
               decoded))
              (throw
               (ex-info
                "The Datomic cursor exact locator resolved to another graph."
                {:type :eacl.consistency/history-divergence
                 :eacl/error :eacl.consistency/history-divergence})))
            (assoc exact-context :mode :at-exact-snapshot)))]
    (revision/observe! (:revision-checkpoints opts)
                       (d/basis-t (d/db conn)))
    (merge
     selected-context
     {:mode (or (:mode selected-context) mode)
      :requested-t requested-t
      :selection selection
      :response-token
      (if (= :at-exact-snapshot (:mode selected-context))
        (response-token (:db selected-context) opts)
        (:response-token selection))})))

(defn basis-instant
  "Wall-clock time of a Datomic basis `t`, for interpreting the `:cache-basis`
  on a lookup or count response.

    (let [{:keys [cached? cache-basis]} (eacl/lookup-resources acl query)]
      (when cached?
        (- (System/currentTimeMillis)
           (.getTime (eacl.datomic.core/basis-instant acl cache-basis)))))

  Returns nil for a nil basis. This is one entity read against the transaction
  entity, so it is not on any hot path — call it only when you want the age."
  [client t]
  (when t
    (let [conn (:conn client)]
      (:db/txInstant (d/entity (d/db conn) (d/t->tx t))))))

(defn- with-cache-info
  "Adds cache provenance to a map-shaped response.

  :cached?     whether this exact response came from the cache.
  :cache-basis the Datomic `t` the response was COMPUTED at. Resolve it to a
               wall-clock time with `basis-instant` to answer \"how old is
               this?\". On a hit in the default consistency mode it is older
               than the read's own basis and the answer is still provably
               current — nothing it depends on changed in between. Only the
               staleness-tolerant modes can return one that is genuinely old."
  [response answer]
  (assoc response
         :cached? (boolean (:cached? answer))
         :cache-basis (:cache-basis answer)))

(defn- with-result-schema
  [{:keys [schema-cache]} f]
  (if schema-cache
    (binding [impl.indexed/*schema-cache* schema-cache]
      (f))
    (f)))

(def ^:private delete-object-batch-size 1000)

(defn- transact-delete-object-batch!
  [conn opts batch]
  (let [batch (vec batch)
        mutation-id (mutation/new-id)]
    (loop [attempt 1]
      (let [db (d/db conn)
          stamped (impl/stamp-relation-versions batch)
          relation-ids (vec (sort (impl/affected-relation-ids stamped)))
          guarded (impl/optimistic-relationship-tx-data db stamped)
          submission
          (try
            {:report
             (journal/transact!
              conn
              {:mutation-id mutation-id
               :kind :object-deletion
               :canonical-data
               {:operation :delete-object-batch
                :tx-data batch}
               :relation-ids relation-ids
               :token-ttl-seconds (:token-ttl-seconds opts)
               :retention-grace-seconds
               (:retention-grace-seconds opts)
               :tx-data guarded})}
            (catch Throwable throwable
              {:error throwable}))]
        (if-let [throwable (:error submission)]
          (if (and (datomic-cas-failure? throwable)
                   (< attempt maximum-relationship-write-attempts))
            (recur (inc attempt))
            (if (datomic-cas-failure? throwable)
              (throw
               (ex-info
                "EACL object deletion could not obtain a stable schema/relation generation."
                {:type :eacl/relationship-contention
                 :attempts attempt}
                throwable))
              (throw throwable)))
          (:report submission))))))

(defn spiceomic-delete-object!
  "Retracts every relationship touching `object`, in both directions, in
  batches. Returns {:zed/token ... :retracted-datoms <n>}.

  The object's own entity is left alone — retract it yourself once this
  returns (or in the same application transaction, using
  eacl.datomic.impl/tx-delete-object directly).

  Batches are separate Datomic transactions, so a reader between them already
  observes a partially deleted object; each batch stamps the relations it
  actually retracted. This used to hold a coordinator barrier per batch — and
  before that, one across the whole loop, which blocked every concurrent lookup
  for the full multi-transaction delete (277ms for 20k relationships in-memory,
  far worse against a real transactor). No barrier is taken now."
  [conn {:keys [object-id->entid] :as opts} object]
  (let [object-id (if (map? object) (:id object) object)
        db        (d/db conn)
        eid       (or (try (object-id->entid db object-id)
                           (catch Exception _ nil))
                      ;; A retracted entity no longer resolves through the
                      ;; caller's id coercion, but its raw eid still cleans up.
                      (when (number? object-id) object-id))
        tx-data   (impl/tx-delete-object-stream db eid)]
    (if (empty? tx-data)
      (assoc (write-response db opts)
             :retracted-datoms 0)
      (loop [batches   (partition-all delete-object-batch-size tx-data)
             retracted 0
             db-after  nil]
        (if-let [batch (first batches)]
          ;; Each batch must publish the relations IT changes:
          ;; tx-delete-object deduplicates stamps across the whole result, so
          ;; without this a later batch retracts relationships while announcing
          ;; nothing.
          (let [{:keys [db-after tx-data]}
                (transact-delete-object-batch! conn opts batch)]
            (recur (next batches)
                   (+ retracted
                      (relationship-retraction-count db-after tx-data))
                   db-after))
          (assoc (write-response db-after opts)
                 :retracted-datoms retracted))))))

(defn spiceomic-can?
  [conn {:keys [object->entid] :as opts}
   subject permission resource consistency-value]
  (let [prepare
        (fn [db]
          (let [subject-type (:type subject)
                subject-eid (object->entid db subject)
                resource-type (:type resource)
                resource-eid (object->entid db resource)]
            (merge
             {:db db
              :internal-subject (spice-object subject-type subject-eid)
              :internal-resource (spice-object resource-type resource-eid)}
             (permission-cache-dependencies
              opts db resource-type permission))))
        {:keys [db internal-subject internal-resource]
         :as result-context}
        (capture-result-context
         conn opts consistency-value prepare :can? nil)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      false
      (let [query-identity
            {:public
             {:subject subject
              :permission permission
              :resource resource}
             :internal
             {:subject internal-subject
              :permission permission
              :resource internal-resource}}]
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
            (merge
             {:db db
              :internal-subject internal-subject
              :query' query'
              :query-shape query-shape
              :internal-query
              (internal-page-query query' page-req decoded)}
             (permission-cache-dependencies
              opts db (:resource/type query') (:permission query')))))
        captured
        (capture-result-context
         conn opts (:consistency query) prepare
         :lookup-resources decoded)
        {:keys [db internal-subject query' query-shape internal-query
                cache-scope cursor-scope schema-version]
         :as result-context}
        captured
        selected-opts
        (assoc opts
               :selected-schema-version schema-version
               :page-cursor-context (:cursor-context captured))]
    (validate-page-token-schema! selected-opts decoded)
    (if (nil? (:id internal-subject))
      ;; Unknown subjects match nothing and never enter the cache.
      (assoc empty-page :cached? false :cache-basis nil)
      (let [compute
            #(with-result-schema
               result-context
               (fn []
                 (impl/lookup-resources
                  db internal-query
                  {})))
            answer
            (cached-authorization-result
             selected-opts result-context :lookup-resources
             {:public (dissoc query :consistency :cache?)
              :internal internal-query}
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
        (with-cache-info
         (coerce-lookup-page
          selected-db selected-opts :lookup-resources query-shape
          selected-basis token-scope
          ;; Historical when a cursor/exact request pinned an older basis, or
          ;; when a staleness mode selected an older cached answer to coerce
          ;; against. Only then is an unresolvable eid a snapshot-age question
          ;; rather than a live data-integrity fault.
          (or (not same-basis?)
              (= :at-exact-snapshot (:mode result-context)))
          internal-page)
         answer)))))

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
                           (dissoc :consistency :cache?))]
            (merge
             {:db db
              :subject-ent subject-ent
              :query' query'}
             (permission-cache-dependencies
              opts db (:resource/type query') (:permission query')))))
        {:keys [db subject-ent query']
         :as result-context}
        (capture-result-context
         conn opts (:consistency query) prepare :count-resources nil)]
    (if (nil? (:id subject-ent))
      (assoc (empty-count-response query) :cached? false :cache-basis nil)
      (let [answer (cached-authorization-result
                    opts result-context :count-resources
                    {:public (dissoc query :consistency :cache?)
                     :internal query'}
                    :count count-response? (constantly 256)
                    #(with-result-schema
                       result-context
                       (fn []
                         (impl/count-resources db query'))))]
        (with-cache-info (:result answer) answer)))))

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
            (merge
             {:db db
              :internal-resource internal-resource
              :query' query'
              :query-shape query-shape
              :internal-query
              (internal-page-query query' page-req decoded)}
             (permission-cache-dependencies
              opts db (:type internal-resource) (:permission query')))))
        captured
        (capture-result-context
         conn opts (:consistency query) prepare
         :lookup-subjects decoded)
        {:keys [db internal-resource query' query-shape internal-query
                cache-scope cursor-scope schema-version]
         :as result-context}
        captured
        selected-opts
        (assoc opts
               :selected-schema-version schema-version
               :page-cursor-context (:cursor-context captured))]
    (validate-page-token-schema! selected-opts decoded)
    (if (nil? (:id internal-resource))
      (assoc empty-page :cached? false :cache-basis nil)
      (let [compute
            #(with-result-schema
               result-context
               (fn []
                 (impl/lookup-subjects
                  db internal-query
                  {})))
            answer
            (cached-authorization-result
             selected-opts result-context :lookup-subjects
             {:public (dissoc query :consistency :cache?)
              :internal internal-query}
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
        (with-cache-info
         (coerce-lookup-page
          selected-db selected-opts :lookup-subjects query-shape
          selected-basis token-scope
          ;; Historical when a cursor/exact request pinned an older basis, or
          ;; when a staleness mode selected an older cached answer to coerce
          ;; against. Only then is an unresolvable eid a snapshot-age question
          ;; rather than a live data-integrity fault.
          (or (not same-basis?)
              (= :at-exact-snapshot (:mode result-context)))
          internal-page)
         answer)))))

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
                           (dissoc :consistency :cache?))]
            (merge
             {:db db
              :resource-ent resource-ent
              :query' query'}
             (permission-cache-dependencies
              opts db (:type resource-ent) (:permission query')))))
        {:keys [db resource-ent query']
         :as result-context}
        (capture-result-context
         conn opts (:consistency query) prepare :count-subjects nil)]
    (if (nil? (:id resource-ent))
      (assoc (empty-count-response query) :cached? false :cache-basis nil)
      (let [answer (cached-authorization-result
                    opts result-context :count-subjects
                    {:public (dissoc query :consistency :cache?)
                     :internal query'}
                    :count count-response? (constantly 256)
                    #(with-result-schema
                       result-context
                       (fn []
                         (impl/count-subjects db query'))))]
        (with-cache-info (:result answer) answer)))))

(defrecord Spiceomic [conn opts schema-state schema-lock]
  IAuthorization
  (can? [_ subject permission resource]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-can? conn opts subject permission resource consistency/fully-consistent)))

  (can? [_ subject permission resource consistency]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-can? conn opts subject permission resource consistency)))

  ;; The map arity is where a per-request :cache? override lands. The
  ;; positional arities keep their signatures and always use the client's own
  ;; configured cache.
  (can? [_ {:keys [subject permission resource consistency] cache? :cache?}]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-can? conn (request-cache-opts opts cache?)
                      subject permission resource
                      (or consistency consistency/fully-consistent))))

  (read-schema [_]
    (with-client-schema-read conn schema-lock schema-state
      (schema/read-schema (d/db conn))))

  (write-schema! [_ schema-string]
    (with-client-schema-write schema-lock
      (let [deltas      (schema/write-schema!
                         conn schema-string
                         (select-keys
                          opts
                          [:token-ttl-seconds
                           :retention-grace-seconds])
                         (:schema-version @schema-state))
            next-version (:eacl/schema-version (meta deltas))
            next-cache  (impl.indexed/make-schema-cache (d/db conn) next-version)]
        (when-not (= (:schema-version @schema-state)
                     (:schema-version next-cache))
          (reset! schema-state next-cache))
        (merge deltas
               (write-response
                (:eacl.mutation/db-after (meta deltas))
                opts)))))

  (read-relationships [_ filters]
    (with-client-schema-read conn schema-lock schema-state
      (spiceomic-read-relationships
       conn (request-cache-opts opts (:cache? filters)) filters)))

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
        (spiceomic-lookup-resources conn (request-cache-opts opts (:cache? query))
                           query))))

  (count-resources [_ query]
    (with-client-schema-read conn schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-count-resources conn (request-cache-opts opts (:cache? query))
                           query))))

  (lookup-subjects [_ query]
    (with-client-schema-read conn schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-lookup-subjects conn (request-cache-opts opts (:cache? query))
                           query))))

  (count-subjects [_ query]
    (with-client-schema-read conn schema-lock schema-state
      (with-recursive-limits opts
        (spiceomic-count-subjects conn (request-cache-opts opts (:cache? query))
                           query))))

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
    :token-ttl-seconds
    :retention-grace-seconds
    :coherence-authority
    :proof-mode
    :adapter-fingerprint
    :adapter-deterministic?
    :consistency-sync-timeout-ms
    :recursive-traversal-limits
    :auto-migrate-v6})

(def ^:private known-cache-opt-keys
  #{:store
    :remember-answers
    :namespace
    :checkpoints
    :ttl-ms
    :max-weight
    :max-entry-weight
    :max-entries
    :kind-max-weight
    :two-hit-kinds
    :admission-entries})

(defn- cache-adapter?
  "Whether `x` is a cache adapter rather than a config map.

  Checked BEFORE map?, and that order is load-bearing: the built-in adapter is
  a defrecord, so it satisfies map? too and would otherwise be read as a
  configuration map whose every key is unknown."
  [x]
  (and (some? x) (satisfies? cache/CacheStore x)))

(defn- normalize-cache-config
  "Normalizes the :cache client option.

    absent / nil     a default client-local adapter
    cache/no-cache   no caching
    <adapter>        any other CacheStore implementation
    {...}            advanced tuning and test options; see make-client

  Whatever is passed must BE a cache: a real adapter, or the explicit
  `no-cache` one. There is no boolean form. `false` used to mean \"off\" and
  `nil` used to mean it too, which left `nil` ambiguous between \"none\" and
  \"the default\" and made the option read as a flag rather than as the cache."
  [cache-option page-token-ttl-seconds]
  (when (boolean? cache-option)
    (throw (ex-info (str "EACL Config Error: :cache takes a cache adapter, not"
                         " a boolean. Use eacl.datomic.cache/no-cache to"
                         " disable caching, or omit :cache for the default"
                         " adapter. To bypass the cache for one call, pass"
                         " :cache? false on the request instead.")
                    {:type :eacl/invalid-config
                     :key :cache
                     :value cache-option})))
  (when-not (or (nil? cache-option)
                (cache-adapter? cache-option)
                (map? cache-option))
    (throw (ex-info (str "EACL Config Error: :cache must be a cache adapter,"
                         " eacl.datomic.cache/no-cache, or a configuration"
                         " map.")
                    {:type :eacl/invalid-config
                     :key :cache
                     :value cache-option})))
  (let [config (cond
                 (cache/no-cache? cache-option) {:store cache/no-cache}
                 (cache-adapter? cache-option) {:store cache-option}
                 (map? cache-option) cache-option
                 :else {})
        unknown-keys (seq (remove known-cache-opt-keys (keys config)))]
    (when unknown-keys
      (throw (ex-info "EACL Config Error: unknown :cache option(s)."
                      {:type :eacl/invalid-config
                       :key :cache
                       :unknown-keys (vec unknown-keys)
                       :known-keys known-cache-opt-keys})))
    (when-not (contains? #{nil false true :on-repeat}
                         (:remember-answers config))
      (throw (ex-info (str "EACL Config Error: :cache :remember-answers must be"
                           " false, true or :on-repeat.")
                      {:type :eacl/invalid-config
                       :key :cache
                       :value (:remember-answers config)})))
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
    (let [;; nil and absent both mean "the default adapter" now, so there is
          ;; nothing to distinguish and no sentinel to thread through.
          enabled? (not (cache/no-cache? cache-option))
          ;; Consumers choose an adapter (or explicit no-cache); they should
          ;; not have to understand entry kinds to get a good outcome.
          ;; :on-repeat is the
          ;; default because it DOMINATES always-remember in every workload
          ;; measured — never slower, sometimes faster — so there is nothing
          ;; to trade off:
          ;;   repeating, direct   none 4.9-6.7us  always 4.0-4.2  on-repeat 3.9-4.1
          ;;   repeating, arrow    none 8.0-9.5us  always 4.3      on-repeat 4.3
          ;;   never repeating     none 7.9-8.6us  always 13.3+    on-repeat 11.8+
          ;; The last row is why `cache/no-cache` remains a meaningful choice:
          ;; on traffic that never asks the same check twice, the key build and
          ;; lookup cost on every read is unrecoverable no matter the admission
          ;; policy. That is a decision about traffic, which consumers can make.
          remember (if (contains? config :remember-answers)
                     (when enabled? (:remember-answers config))
                     (when enabled? :on-repeat))
          remember-answers? (boolean remember)
          token-ttl-ms (* 1000 (or page-token-ttl-seconds
                                   default-page-token-ttl-seconds))
          ;; No expiry by default. A ttl was originally a staleness bound;
          ;; relation stamps are that bound now, and they are exact rather than
          ;; approximate. What remains is capacity, which :max-weight and
          ;; :max-entries already handle by evicting the least recently used
          ;; entry — a better rule than age, since an entry that is still being
          ;; read is exactly the one a timer would throw away.
          ttl-ms (:ttl-ms config)
          _ (when-not (or (nil? ttl-ms)
                          (and (integer? ttl-ms) (pos? ttl-ms)))
              (throw (ex-info (str "EACL Config Error: :cache :ttl-ms must be a"
                                   " positive integer, or absent for no expiry.")
                              {:type :eacl/invalid-config
                               :key :cache
                               :value ttl-ms})))
          ;; :on-repeat is implemented as second-sighting admission on the
          ;; four answer kinds, so a check asked once is never stored.
          store-config
          (cond-> (select-keys config [:max-weight
                                       :max-entry-weight
                                       :max-entries
                                       :kind-max-weight
                                       :two-hit-kinds
                                       :admission-entries])
            (= :on-repeat remember)
            (update :two-hit-kinds (fnil into #{}) answer-cache-kinds))
          ;; Same rule inside the advanced map: :store is an adapter or
          ;; no-cache, never a boolean.
          _ (when (boolean? (:store config))
              (throw (ex-info (str "EACL Config Error: :cache :store takes a"
                                   " cache adapter, not a boolean. Use"
                                   " eacl.datomic.cache/no-cache to disable"
                                   " caching.")
                              {:type :eacl/invalid-config
                               :key :cache
                               :value (:store config)})))
          store (when (and enabled? (not (cache/no-cache? (:store config))))
                  (or (:store config)
                      (cache/local-store store-config)))]
      (when (and store (not (satisfies? cache/CacheStore store)))
        (throw (ex-info "EACL Config Error: :cache :store must implement CacheStore."
                        {:type :eacl/invalid-config
                         :key :cache
                         :value (:store config)})))
      {:store store
       :namespace (or (:namespace config) :eacl)
       :checkpoints
       (when (and enabled? (:checkpoints config))
         (revision/revision-checkpoints
          (if (map? (:checkpoints config))
            (:checkpoints config)
            {})))
       :ttl-ms (when ttl-ms (min ttl-ms token-ttl-ms))
       :remember-answers? (and remember-answers? (some? store))})))

(defn make-client
  "Builds an IAuthorization client over a Datomic conn.

  Options (unknown keys throw :eacl/invalid-config — a silently ignored key
  means silently wrong ID coercion):
  - :entid->object-id  (fn [db eid] external-id) — canonical ID coercion, as documented in the README.
  - :entity->object-id (fn [entity] external-id) — deprecated alias; do not combine with the above.
  - :object-id->ident  (fn [external-id] ident-resolvable-by-d-entid). Default: [:eacl/id id].
  - :cache — the cache adapter this client uses.

      omitted     a default client-local in-memory adapter (this is the norm)
      nil         the same default client-local adapter as omission
      cache/no-cache
                  no caching at all
      <adapter>   any eacl.datomic.cache/CacheStore implementation, e.g.
                  (eacl.datomic.cache/local-store {:max-weight ...})

    Pass cache/no-cache when the same permission check is essentially never
    asked twice — a batch job sweeping distinct resources, say. A read then
    pays for a cache lookup it can never benefit from: measured 7.9us with the
    cache off against 11.8us on for entirely distinct checks. When checks recur
    it is the other way round, 8.0us against 4.3us for an arrow permission.

    To bypass the configured cache for ONE call, pass :cache? false on the
    request — on the map arity of can?, and in the query map for lookups,
    counts and read-relationships. It skips both reading and writing for that
    call only. Cursors are unaffected: a page token is minted and validated
    from the request, not from the cache, so a cursor minted with the bypass is
    usable without it and vice versa.

    A configuration map may be supplied in place of an adapter for tuning and
    tests. It is deliberately not part of the API consumers need:
      :store             an adapter, or cache/no-cache
      :max-weight, :max-entry-weight, :max-entries, :ttl-ms  capacity bounds
      :namespace         isolates entries between clients sharing an adapter
      :kind-max-weight, :two-hit-kinds, :admission-entries   per-kind tuning
      :checkpoints       bounded revision checkpoints
      :remember-answers  false | true | :on-repeat (default) — whether a
                         finished answer is kept so an identical later check
                         skips evaluation. :on-repeat keeps it only once the
                         same check has been seen twice, and measured no slower
                         than true in every workload tested, which is why it is
                         a default rather than a question for consumers.

    Cache failures are contained as misses or rejected publications. Missing
    exact pages and recursive continuations replay against the authenticated
    historical basis rather than falling forward.

    There is no cross-process cache coordination to configure. Managed writers
    atomically publish v3 graph and dependency mutation identities in the same
    transaction as the authorization change. Unknown or mixed writers use
    complete content proofs instead of trusting those identities.

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
           token-ttl-seconds
           retention-grace-seconds
           coherence-authority
           proof-mode
           adapter-fingerprint
           adapter-deterministic?
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
  (when (contains? config-opts :page-token-ttl-seconds)
    (validate-page-token-ttl! page-token-ttl-seconds))
  (when-not (fn? object-id->ident)
    (throw (ex-info "EACL Config Error: object-id->ident must be a fn that coerces a Spice Object ID to a Datomic ident resolvable by d/entid."
             {:type :eacl/invalid-config
              :key :object-id->ident})))
  (when (and (contains? config-opts :zed-token-key)
             (contains? config-opts :zed-token-keyring))
    (throw (ex-info "EACL Config Error: supply only one of :zed-token-key or :zed-token-keyring."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:zed-token-key :zed-token-keyring]})))
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
  (when (and retention-grace-seconds
             (not (and (integer? retention-grace-seconds)
                       (not (neg? retention-grace-seconds)))))
    (throw (ex-info "EACL Config Error: :retention-grace-seconds must be non-negative."
                    {:type :eacl/invalid-config
                     :key :retention-grace-seconds
                     :value retention-grace-seconds})))
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
  (journal/ensure-migrated! conn)
  (let [coherence-authority (or coherence-authority :unknown)
        proof-mode (case (or proof-mode :auto)
                     :auto (if (= :managed coherence-authority)
                             :mutation
                             :content)
                     proof-mode)
        initial-db         (d/db conn)
        schema-state       (atom (impl.indexed/make-schema-cache initial-db))
        cache-config       (normalize-cache-config cache page-token-ttl-seconds)
        timeout-ms         (if (contains? config-opts
                                          :consistency-sync-timeout-ms)
                             consistency-sync-timeout-ms
                             default-consistency-sync-timeout-ms)
        schema-lock        (ReentrantReadWriteLock.)
        custom-codec?
        (boolean
         (or entid->object-id
             entity->object-id
             (contains? config-opts :object-id->ident)))
        entid->object-id   (or entid->object-id
                               (when entity->object-id
                                 (fn [db eid] (entity->object-id (d/entity db eid))))
                               (fn [db eid] (:eacl/id (d/entity db eid))))
        object-id->entid   (fn [db object-id]
                             (d/entid db (object-id->ident object-id)))
        adapter-deterministic?
        (if custom-codec?
          (and (some? adapter-fingerprint)
               (true? adapter-deterministic?))
          true)
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
        format-options     {:current-kid zed-current-kid
                            :keyring
                            (into {}
                                  (map (fn [[kid root-key]]
                                         [kid
                                          (secure/normalize-key root-key)]))
                                  zed-root-keyring)
                            :token-ttl-seconds
                            (or token-ttl-seconds
                                mutation/default-token-ttl-seconds)}
        opts               {:object-id->ident object-id->ident
                            :schema-state schema-state
                            :derived-schema-caches (atom {})
                            :database-id (:database-id @schema-state)
                            :backend-capabilities datomic-backend/capabilities
                            :backend-adapter-fn
                            (fn [db]
                             (datomic-backend/snapshot-adapter
                               db
                               {:entid->object-id entid->object-id
                                :conn conn
                                :database-id (:database-id @schema-state)
                                :coherence-authority coherence-authority
                                :proof-mode proof-mode
                                :adapter-fingerprint
                                (or adapter-fingerprint
                                    {:backend :datomic
                                     :adapter-version backend/adapter-version
                                     :proof-mode proof-mode
                                     :recursive-traversal-limits
                                     recursive-traversal-limits
                                     :codec
                                     (if custom-codec?
                                       :custom-unfingerprinted
                                       :eacl-id-immutable-v1)})
                                :adapter-deterministic?
                                adapter-deterministic?}))
                            ;; Compatibility/introspection aliases retained
                            ;; for v7 callers and cache telemetry. Completed
                            ;; answers execute only through
                            ;; :shared-cache-store below.
                            :lookup-cache-store (:store cache-config)
                            :lookup-cache-ttl-ms (:ttl-ms cache-config)
                            :cache-namespace (:namespace cache-config)
                            :shared-cache-store
                            (cache/authenticated-store
                             (:store cache-config)
                             (:namespace cache-config)
                             (:ttl-ms cache-config))
                            :cache-remember-answers? (:remember-answers? cache-config)
                            :revision-checkpoints (:checkpoints cache-config)
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
                            :format-options format-options
                            :coherence-authority coherence-authority
                            :proof-mode proof-mode
                            :token-ttl-seconds
                            (or token-ttl-seconds
                                mutation/default-token-ttl-seconds)
                            :retention-grace-seconds
                            (or retention-grace-seconds
                                mutation/default-retention-grace-seconds)
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
        opts (:opts client)]
    (when-not (= :managed (:coherence-authority opts))
      (throw (ex-info
              "Causal token issuance requires complete writer authority."
              {:type :eacl/causal-authority-incomplete})))
    (revision/observe! (:revision-checkpoints opts) (d/basis-t db))
    (response-token db opts)))

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
    (when-not (= :managed (:coherence-authority opts))
      (throw (ex-info
              "Causal token issuance requires complete writer authority."
              {:type :eacl/causal-authority-incomplete})))
    (when-not checkpoints
      (throw (ex-info "Revision checkpoints are disabled for this EACL client."
                      {:type :eacl/checkpoints-disabled})))
    (revision/observe! checkpoints basis-t)
    (let [selected-t
          (revision/revision-at-least-seconds-ago
           checkpoints seconds-ago basis-t)]
      (response-token (d/as-of db selected-t) opts))))
