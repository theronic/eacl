(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [com.rpl.specter :as S]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.codec :as codec]
            [eacl.datomic.consistency :as revision]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.watermark :as watermark]
            [eacl.migrations.v6-to-v7 :as migrations]
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
            (invalid-page-token! :expired {:exp (:exp payload) :now now}))
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
    (cond-> (dissoc query :after :before :page/basis :consistency :cache?)
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

(defn- exact-result-cache-key
  "Retained answers are keyed by the CACHE EPOCH, not the Datomic basis.

  Keying on basis-t meant any transaction anywhere in the database minted a new
  key, so this cache was effectively a 0% hit rate on a busy system. An epoch is
  the max change stamp over the relations the answer actually reads, so it moves
  only when one of those relations is written — see eacl.datomic.watermark.

  The `:exact` tag distinguished these from the `:live` entries that
  :live-results? used to publish. Those are gone; the tag is kept only so a
  store surviving a rolling deploy cannot serve an old-format entry under a
  key this build would build the same way."
  [prefix epoch]
  (conj prefix [:exact epoch]))

(def ^:private answer-cache-kinds
  "The entry kinds that hold a finished answer, as opposed to the traversal and
  pagination state EACL caches regardless. :remember-answers governs exactly
  these four."
  #{:can? :lookup-page :count :latest-result})

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
       (or (not (contains? expected :epoch))
           (= (:epoch expected) (:epoch answer)))
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
   {:keys [mode basis-t cache-epoch cache-scope schema-version]}]
  (let [store (:lookup-cache-store opts)
        namespace (:cache-namespace opts)
        ttl-ms (:lookup-cache-ttl-ms opts)
        ;; nil epoch means no precise dependency proof was available, so there
        ;; is no key a later read could match. Retaining anyway is pure cost.
        exact-key (when cache-epoch
                    (exact-result-cache-key cache-prefix cache-epoch))
        ;; A cursor/exact page is forced to :at-exact-snapshot, and only that
        ;; mode reads exact-key. Its live entry would be keyed by a query
        ;; identity containing an :after/:before edge, and every request
        ;; carrying such an edge takes the historical branch — so the entry
        ;; could never be read, while still consuming the weight and entry
        ;; budget that live page-one answers compete for.
        historical? (= :at-exact-snapshot mode)
        answer {:basis-t basis-t
                :epoch cache-epoch
                :cache-scope cache-scope
                :result (portable-result kind result)}]
    (when (and store schema-version)
      (when (and (:cache-remember-answers? opts) exact-key)
        (cache/safe-store-entry!
         store namespace exact-key kind answer (+ 128 weight) ttl-ms))
      ;; This is a latency hint, not a correctness proof. An older concurrent
      ;; writer may overwrite it; at-least-as-fresh validates the revision and
      ;; falls back to the selected DB when the hint is too old. A cursor
      ;; prefix's pointer is unreachable for the same reason as its live entry.
      (when (and (:cache-remember-answers? opts) exact-key (not historical?))
        (cache/safe-store-entry!
         store namespace
         (latest-result-cache-key cache-prefix)
         :latest-result
         {:basis-t basis-t
          :epoch cache-epoch
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
                (integer? (:epoch value))
                (vector? (:exact-key value))
                (= kind (:kind value)))))]
    (when (and pointer
               (or (nil? minimum-t)
                   (<= minimum-t (:basis-t pointer)))
               (or (nil? maximum-t)
                   (<= (:basis-t pointer) maximum-t))
               ;; The pointer names its own entry's key, which is derived from
               ;; the epoch. Recomputing it here is what stops a malformed or
               ;; foreign pointer redirecting a read at another entry.
               (= (:exact-key pointer)
                  (exact-result-cache-key cache-prefix (:epoch pointer))))
      (cached-answer opts (:exact-key pointer) kind valid-result?
                     {:basis-t (:basis-t pointer)
                      :epoch (:epoch pointer)}))))

(defn- cached-authorization-result
  [opts consistency-context op query-identity kind valid-result? weight-fn compute]
  (let [{:keys [mode basis-t cache-epoch requested-t]}
        consistency-context
        exact? (:cache-remember-answers? opts)]
    (cond
      ;; portable-result even when nothing is retained, so a caller's result
      ;; shape does not depend on cache configuration. Without it :lookup-page
      ;; data was SpiceObject records with caching off and plain maps with it
      ;; on — a trap for any consumer that comes to rely on record type.
      (not exact?)
      (assoc consistency-context
             :result (portable-result kind (compute))
             :cached? false
             :cache-basis basis-t)

      :else
      (let [cache-prefix (result-cache-prefix
                          opts op query-identity
                          (:schema-version consistency-context))
            exact-key (when (and exact? cache-epoch)
                        (exact-result-cache-key cache-prefix cache-epoch))
            tag-hit (fn [answer]
                      ;; :cache-basis is the basis the answer was COMPUTED at,
                      ;; captured before :basis-t is overwritten with the
                      ;; caller's. For a fully-consistent hit the two differ
                      ;; only because nothing the answer depends on changed in
                      ;; between — the answer is provably current, not stale.
                      ;; Under :minimize-latency they differ by real staleness.
                      (assoc answer :cached? true
                             :cache-basis (:basis-t answer)))
            exact-hit #(when exact-key
                         (some-> (cached-answer opts exact-key kind valid-result?
                                                {:epoch cache-epoch})
                                 (tag-hit)
                                 ;; The epoch proves no EACL-relevant datom
                                 ;; changed between the answer's basis and this
                                 ;; one, so the answer IS current: stamp the
                                 ;; caller's basis so external ids coerce
                                 ;; against the database they asked for rather
                                 ;; than sending the caller to d/as-of.
                                 (assoc :basis-t basis-t)))
            latest-hit #(when exact?
                          (some-> (latest-cached-answer
                                   opts cache-prefix kind valid-result?
                                   % basis-t)
                                  (tag-hit)))
            hit (case mode
                  ;; exact-hit is a sound fallback here, not a staleness
                  ;; window: exact-key pins database-id, schema generation,
                  ;; operation, query identity AND basis-t, and two
                  ;; connection-backed DB values of one database at the same
                  ;; basis-t are the same value. Without it, remembered answers
                  ;; wrote an entry on every call that the default consistency
                  ;; mode could never read.
                  :fully-consistent (exact-hit)
                  :minimize-latency (latest-hit nil)
                  :at-least-as-fresh (latest-hit requested-t)
                  :at-exact-snapshot (exact-hit))]
        (cond
          (some? hit)
          hit

          :else
          (let [result (compute)]
            (assoc (store-cached-answer!
                    opts cache-prefix kind result (weight-fn result)
                    consistency-context)
                   :cached? false
                   :cache-basis basis-t)))))))

(defn- continuation-context
  "Cache handles for one list operation: recursive traversal continuations and
  pages, plus the acyclic engine's per-intermediate stream heads. All are
  keyed by the cursor edge under one prefix that pins the schema generation,
  the query identity and the relationship proof."
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
                     (:lookup-cache-ttl-ms opts)))
       :get-heads (fn [edge]
                    (cache/safe-entry-value
                     store
                     (cache-key [:heads edge])
                     :lookup-heads
                     map?))
       :put-heads! (fn [edge heads weight]
                     (cache/safe-store-entry!
                      store
                      namespace
                      (cache-key [:heads edge])
                      :lookup-heads
                      heads
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
         conn opts (:consistency filters)
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

(defn spiceomic-write-relationships!
  "Writes relationships and returns a zed token for the committed revision.

  There is no cache bookkeeping here. A write used to run inside a coordinator
  mutation that published which relations it touched, and had to distinguish
  validation failures (which commit nothing) from a possibly-committed throw so
  an ordinary :eacl/relationship-conflict did not flush every cached result.
  The tx-data itself now carries that publication as :eacl/relation-version
  stamps, so a transaction that never commits announces nothing by
  construction."
  [conn opts updates]
  (let [updates (vec updates)]
  (doseq [{:keys [operation]} updates]
    (impl/validate-relationship-operation! operation))
    (loop [attempt 1]
      (let [db (d/db conn)
            tx-data
            (->> updates
                 (S/transform [S/ALL :relationship]
                              #(spice-relationship->internal db opts %))
                 (mapcat #(impl/tx-update-relationship db %))
                 (remove nil?)
                 (impl/optimistic-relationship-tx-data db))
            submission
            (try
              {:report @(d/transact conn tx-data)}
              (catch Throwable throwable
                {:error throwable}))]
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
            {:zed/token (revision/zed-token opts (:database-id opts)
                                            (d/basis-t db-after))}))))))

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

(defn- needs-relationship-dependencies?
  "Whether anything this client caches needs a query's relation dependency set.

  Both consumers derive from it: the exact-result epoch, and the cache scope
  that keys recursive continuations and cursors. Resolving the set is a
  memoised map lookup on a stamped client, so this test is about skipping work
  on a cacheless client, not about hot-path cost."
  [opts]
  (boolean (or (:cache-remember-answers? opts)
               (:lookup-cache-store opts))))

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
  :cache-remember-answers? flag, so clearing those two is the whole mechanism —
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

(defn- capture-result-context
  "Captures one DB and the cache proof that matches it.

  A cursor or exact request uses a historical DB and a request-scoped schema
  cache; everything else reads the current value once. The proof is derived
  from that same db value, so there is no barrier and nothing to synchronise."
  [conn opts consistency-value prepare operation decoded]
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
               ;; A cursor or exact-snapshot read pins a historical basis and
               ;; is its own epoch. EACL targets the current database; keeping
               ;; a cache warm for every point in time is a non-goal.
               :cache-epoch historical-t
               :cache-scope (:cache-scope decoded)
               :cursor-scope (:cache-scope decoded)
               :schema-cache schema-cache
               :schema-version (some-> schema-cache :schema-version str)))
      (let [minimum-t (when (= :at-least-as-fresh mode)
                        requested-t)
            _ (await-revision-db conn opts minimum-t)
            ;; One db value, read once. There used to be a read barrier here
            ;; pairing this value with a relationship-coordinator snapshot, plus
            ;; a bounded catch-up loop for a reader behind the coordinator's
            ;; published floor. Both existed to make an in-process proof cohere
            ;; with Datomic; per-relation stamps live IN the db value, so the
            ;; value is the proof and there is nothing to synchronise.
            db (d/db conn)
            observed-t (d/basis-t db)
            {:keys [relationship-dependencies] :as prepared} (prepare db)
            ;; The max relation stamp over exactly what this answer depends on,
            ;; read from the same db value. An unrelated application write
            ;; leaves it alone, and so does churn on an EACL relation this
            ;; answer does not read. Any write that CAN affect the answer moves
            ;; it, whichever process or connection made it, because the stamp is
            ;; transacted with the relationship datoms.
            cache-epoch (watermark/safe-epoch-for
                         (:cache-epoch-state opts) db
                         relationship-dependencies)]
        (revision/observe! (:revision-checkpoints opts) observed-t)
        (assoc prepared
               :mode mode
               :requested-t requested-t
               :basis-t observed-t
               :cache-epoch cache-epoch
               ;; Continuations and cursors pin the same proof. Falling back to
               ;; the basis when no epoch can be established keeps them correct
               ;; on a database without relation stamps, at that basis's much
               ;; coarser hit rate.
               :cache-scope (if cache-epoch
                              [:relations cache-epoch]
                              [:basis observed-t])
               :schema-version (client-schema-version opts))))))

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
  [conn batch]
  (loop [attempt 1]
    (let [db (d/db conn)
          guarded (impl/optimistic-relationship-tx-data
                   db
                   (impl/stamp-relation-versions batch))
          submission
          (try
            {:report @(d/transact conn guarded)}
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
        (:report submission)))))

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
      {:zed/token (revision/zed-token opts (:database-id opts) (d/basis-t db))
       :retracted-datoms 0}
      (loop [batches   (partition-all delete-object-batch-size tx-data)
             retracted 0
             basis-t   nil]
        (if-let [batch (first batches)]
          ;; Each batch must publish the relations IT changes:
          ;; tx-delete-object deduplicates stamps across the whole result, so
          ;; without this a later batch retracts relationships while announcing
          ;; nothing.
          (let [{:keys [db-after tx-data]}
                (transact-delete-object-batch! conn batch)]
            (recur (next batches)
                   (+ retracted
                      (relationship-retraction-count db-after tx-data))
                   (d/basis-t db-after)))
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
             (when (needs-relationship-dependencies? opts)
               (impl.indexed/permission-relationship-eids
                db resource-type permission))}))
        {:keys [db internal-subject internal-resource]
         :as result-context}
        (capture-result-context
         conn opts consistency-value prepare :can? nil)]
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
             (when (needs-relationship-dependencies? opts)
               (impl.indexed/permission-relationship-eids
                db (:resource/type query') (:permission query')))}))
        captured
        (capture-result-context
         conn opts (:consistency query) prepare
         :lookup-resources decoded)
        {:keys [db internal-subject query' query-shape internal-query
                cache-scope cursor-scope schema-version]
         :as result-context}
        captured
        selected-opts (assoc opts :selected-schema-version schema-version)]
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
                  {:continuation-cache-fn
                   (fn []
                     (continuation-context
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
            {:db db
             :subject-ent subject-ent
             :query' query'
             :relationship-dependencies
             (when (needs-relationship-dependencies? opts)
               (impl.indexed/permission-relationship-eids
                db (:resource/type query') (:permission query')))}))
        {:keys [db subject-ent query']
         :as result-context}
        (capture-result-context
         conn opts (:consistency query) prepare :count-resources nil)]
    (if (nil? (:id subject-ent))
      (assoc (empty-count-response query) :cached? false :cache-basis nil)
      (let [answer (cached-authorization-result
                    opts result-context :count-resources query'
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
            {:db db
             :internal-resource internal-resource
             :query' query'
             :query-shape query-shape
             :internal-query (internal-page-query query' page-req decoded)
             :relationship-dependencies
             (when (needs-relationship-dependencies? opts)
               (impl.indexed/permission-relationship-eids
                db (:type internal-resource) (:permission query')))}))
        captured
        (capture-result-context
         conn opts (:consistency query) prepare
         :lookup-subjects decoded)
        {:keys [db internal-resource query' query-shape internal-query
                cache-scope cursor-scope schema-version]
         :as result-context}
        captured
        selected-opts (assoc opts :selected-schema-version schema-version)]
    (validate-page-token-schema! selected-opts decoded)
    (if (nil? (:id internal-resource))
      (assoc empty-page :cached? false :cache-basis nil)
      (let [compute
            #(with-result-schema
               result-context
               (fn []
                 (impl/lookup-subjects
                  db internal-query
                  {:continuation-cache-fn
                   (fn []
                     (continuation-context
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
            {:db db
             :resource-ent resource-ent
             :query' query'
             :relationship-dependencies
             (when (needs-relationship-dependencies? opts)
               (impl.indexed/permission-relationship-eids
                db (:type resource-ent) (:permission query')))}))
        {:keys [db resource-ent query']
         :as result-context}
        (capture-result-context
         conn opts (:consistency query) prepare :count-subjects nil)]
    (if (nil? (:id resource-ent))
      (assoc (empty-count-response query) :cached? false :cache-basis nil)
      (let [answer (cached-authorization-result
                    opts result-context :count-subjects query'
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

    There is no cross-process cache coordination to configure. Invalidation
    rides on :eacl/relation-version stamps written into the transaction that
    changes a relationship, so every reader of the database observes it.

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
        cache-config       (normalize-cache-config cache page-token-ttl-seconds)
        ;; Per-relation epoch state, built only when results can be retained.
        ;; Holds one memoised attribute eid and no db value. Whether an epoch
        ;; can actually be established is decided per read, not here: a client
        ;; may be constructed before the EACL schema exists — this repo's own
        ;; benchmark seeding does that — and retention starts working by itself
        ;; once write-schema! installs :eacl/relation-version.
        ;; Built whenever anything can be cached, not only for exact results:
        ;; the epoch is also the cache scope that keys recursive continuations
        ;; and cursors. Gating it on :remember-answers left a default client
        ;; falling back to [:basis t] there, so 20 unrelated transactions
        ;; invalidated a recursive page that had not changed.
        cache-epoch-state  (when (:store cache-config)
                             (watermark/make-epoch-state))
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
                            :cache-remember-answers? (:remember-answers? cache-config)
                            :cache-epoch-state cache-epoch-state
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
