(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [com.rpl.specter :as S]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as shared-cache]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.continuation :as continuation]
            [eacl.core :as eacl :refer [IAuthorization
                                        IDetailedAuthorization
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
            [eacl.datomic.schema :as schema]
            [eacl.engine.physical :as physical]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.formal.production-kernel :as production-kernel]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.proof-frame :as proof-frame]
            [eacl.permission-tree :as permission-tree]
            [eacl.relay :as relay]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.relationships.mutations :as relationship-mutations]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.errors :as schema-errors]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]
            [eacl.spicedb.consistency :as consistency])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream
            DataOutputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Arrays Base64 UUID]
           [java.util.function Supplier]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

;; eacl4_ tokens carry a binary envelope; the eacl3_ EDN format they replace is
;; not read. Cursor expiry is optional policy: without an explicitly configured
;; TTL, authenticated cursors remain age-valid and exact history supplies their
;; snapshot semantics.
(def ^:private page-token-prefix "eacl4_")
(def ^:private page-token-version 7)
(def ^:private maximum-page-token-length
  "Bounds decode work on an unauthenticated caller-supplied cursor. Real EACL
  cursors are well under this even with per-path frontiers for a wide
  permission graph; mirrors the cap eacl.datomic.consistency puts on Zed
  tokens."
  16384)
(def ^:private maximum-page-token-ttl-seconds
  ;; Keep the cursor's second-based expiry representable as a signed long.
  ;; This is intentionally enormous
  ;; (about 292 million years); it is a numeric-safety bound, not policy.
  (quot Long/MAX_VALUE 1000))
(def ^:private default-consistency-sync-timeout-ms 30000)
(def ^:private maximum-token-key-id-length 128)
(def ^:private ^SecureRandom secure-random (SecureRandom.))

(defn- now-seconds []
  (quot (System/currentTimeMillis) 1000))

(defn- execute-request
  "Normalizes one Datomic public request before snapshot/cache work and binds
  the same demand/deadline contract used by the shared clients."
  [opts operation request f]
  (let [contract (execution/normalize opts operation request)
        opts (assoc opts
                    :execution-contract contract
                    :cache-lifecycle
                    (shared-cache/capture-current-lifecycle
                     (:current-cache-store opts)))]
    (execution/check! contract :request-start)
    (binding [execution/*contract* contract
              engine/*evaluation-mode* (:evaluation contract)
              engine/*recursive-traversal-limits*
              (:recursive-traversal-limits opts)
              engine/*service-admission* (:service-admission opts)
              impl.indexed/*recursive-traversal-limits*
              (:recursive-traversal-limits opts)]
      (let [result (f opts)]
        (execution/check! contract :request-complete)
        result))))

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

(defn- page-token-expired-error
  "The public expired-token error, byte-compatible with the historical
  decode-time throw. The request path threads the computed `:expired?`
  boolean into the verified continuation decision and throws this only when
  the kernel rejects the token."
  [{:cursor/keys [expired-exp expired-now]}]
  (ex-info
   "Page token has expired."
   {:type :eacl.pagination/expired-cursor
    :eacl/error :eacl.pagination/expired-cursor
    :reason :expired
    :exp expired-exp
    :now expired-now}))

(defn- decrypt-authenticated-page-token
  "Authenticates and decodes a page token without enforcing expiry.

  The payload carries the computed decode facts (`:cursor/authenticated?`,
  `:cursor/expired?`) so the time-to-live check is decided by the verified
  continuation kernel rather than pre-empted here. Authenticity and shape
  failures still throw — an unauthenticated token has no payload to decide
  over."
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
          (assoc payload
                 :cursor/authenticated? true
                 :cursor/expired?
                 (boolean (and (:exp payload) (<= (:exp payload) now)))
                 :cursor/expired-exp (:exp payload)
                 :cursor/expired-now now)))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      ;; A StackOverflowError is an Error, not an Exception: before the codec
      ;; replaced edn/read-string here, hostile nesting walked straight out of
      ;; lookup-resources past every `catch Exception` in the request stack.
      (catch StackOverflowError _
        (invalid-page-token! :malformed))
      (catch Exception _
        (invalid-page-token! :malformed)))))

(defn decrypt-page-token
  [opts token]
  (when-let [payload (decrypt-authenticated-page-token opts token)]
    (when (:cursor/expired? payload)
      (throw (page-token-expired-error payload)))
    (dissoc payload
            :cursor/authenticated?
            :cursor/expired?
            :cursor/expired-exp
            :cursor/expired-now)))

(defn- valid-page-token-ttl?
  [ttl-seconds]
  (and (integer? ttl-seconds)
       (pos? ttl-seconds)
       (<= ttl-seconds maximum-page-token-ttl-seconds)))

(defn- validate-cursor-ttl!
  [ttl-seconds]
  (when-not (valid-page-token-ttl? ttl-seconds)
    (throw
     (ex-info
      "EACL Config Error: :cursor-ttl-seconds must be a positive bounded integer."
      {:type :eacl/invalid-config
       :key :cursor-ttl-seconds
       :value ttl-seconds
       :maximum maximum-page-token-ttl-seconds})))
  ttl-seconds)

(defn page-token
  [opts {:keys [ttl-seconds] :as payload}]
  (let [ttl-seconds (when (some? ttl-seconds)
                      (validate-cursor-ttl! ttl-seconds))]
    (encrypt-page-token
     opts
     (cond-> (-> payload
                 (dissoc :ttl-seconds)
                 (assoc :v page-token-version))
       ttl-seconds
       (assoc :exp (+ (now-seconds) (long ttl-seconds)))))))

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
  "Authenticated decode of one page bound with expiry deferred: the computed
  `:cursor/expired?` boolean flows into the verified continuation decision,
  which is where an expired token is rejected."
  [opts page-req]
  (some->> (:bound page-req)
           (decrypt-authenticated-page-token opts)))

(defn- historical-selection-failure!
  [message phase requested-t timeout-ms cause]
  (throw
   (ex-info
    message
    {:type :eacl.basis/selection-failure
     :eacl/error :eacl.basis/selection-failure
     :classification :retryable
     :phase phase
     :requested-t requested-t
     :requested-order-hint requested-t
     :timeout-ms timeout-ms}
    cause)))

(defn- await-revision-db
  "Returns a local DB that has observed `requested-t`, waiting only when needed."
  [conn opts requested-t]
  (let [timeout-ms (:consistency-sync-timeout-ms opts)
        local-db
        (try
          (d/db conn)
          (catch Exception failure
            (historical-selection-failure!
             "Failed reading the local Datomic database."
             :cursor-exact-local-read requested-t timeout-ms failure)))]
    (if (nil? requested-t)
      local-db
      (datomic-backend/await-basis-db
       conn local-db requested-t timeout-ms :cursor-exact-sync))))

(defn- historical-db
  [conn opts requested-t operation]
  (try
    (d/as-of (await-revision-db conn opts requested-t) requested-t)
    (catch clojure.lang.ExceptionInfo e
      (if (contains? #{:eacl.consistency/freshness-unavailable
                       :eacl.basis/selection-failure}
                     (:type (ex-data e)))
        (throw e)
        (historical-selection-failure!
         "Failed reconstructing the Datomic cursor snapshot."
         :cursor-exact-as-of requested-t
         (:consistency-sync-timeout-ms opts) e)))
    (catch InterruptedException interrupt
      (.interrupt (Thread/currentThread))
      (throw
       (ex-info
        "Datomic cursor reconstruction was interrupted."
        {:type :eacl.basis/selection-failure
         :eacl/error :eacl.basis/selection-failure
         :classification :cancelled
         :phase :cursor-exact-as-of
         :operation operation
         :requested-t requested-t
         :requested-order-hint requested-t}
        interrupt)))
    (catch Exception failure
      (historical-selection-failure!
       "Failed reconstructing the Datomic cursor snapshot."
       :cursor-exact-as-of requested-t
       (:consistency-sync-timeout-ms opts) failure))))

(defn- list-query-identity
  [op query]
  ;; Only transport and cache-selection fields are excluded. The cursor is
  ;; bound to the principal, permission, filters, and normalized consistency
  ;; contract. Relay direction/size stay caller-controlled so the same boundary
  ;; supports forward and backward navigation.
  (canonicalize
   {:op op
    ;; Evaluation is an execution-semantic choice, not a transport option.
    ;; The public Datomic methods remove it before invoking the engine, so bind
    ;; the normalized contract here to prevent a demand cursor from being
    ;; replayed under complete-denotation evaluation (or vice versa).
    :evaluation (:evaluation execution/*contract*)
    :recursive-traversal-limits (:limits execution/*contract*)
    :basis :stable
    :query
    (-> query
        (dissoc :first :last :after :before
                :cursor :limit :page/basis :cache?
                :timeout-ms :cancellation-token)
        (assoc :consistency
               (select-keys
                (consistency/descriptor (:consistency query))
                [:mode]))) }))

(defn- list-query-shape
  [op query]
  (stable-hash (list-query-identity op query)))

(defn- selected-schema-version
  "The schema generation this operation is bound to.

  `contains?`, not `or`: a historical read against an unstamped basis selects
  an explicit nil, and falling back to the client's generation there made a
  cursor minted on an unstamped database fail its own page-two validation."
  [opts]
  (if (contains? opts :selected-schema-version)
    (:selected-schema-version opts)
    nil))

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
  "Throws the typed pre-kernel errors for database/operation/query mismatch
  and malformed continuation metadata, then returns the computed scope
  conjunction. The verified continuation decision receives that computed
  boolean and re-confirms the rejection (belt and braces)."
  [opts op query-shape decoded]
  (if-not decoded
    nil
    (let [database-match?
          (= (:database-id opts) (:database-id decoded))
          operation-match? (= op (:op decoded))
          query-match? (= query-shape (:query-shape decoded))]
      (when-not database-match?
        (invalid-cursor! "Page token was created for a different database."
                         :database-mismatch {}))
      (when-not operation-match?
        (invalid-cursor! "Page token was created for a different operation."
                         :operation-mismatch
                         {:expected op :actual (:op decoded)}))
      (when-not query-match?
        (invalid-cursor! "Page token does not match the current query."
                         :query-mismatch
                         {:expected query-shape
                          :actual (:query-shape decoded)}))
      (when-not (= :stable (:basis decoded))
        (invalid-cursor! "Unsupported page token basis."
                         :unsupported-basis {:basis (:basis decoded)}))
      (when-not (and (integer? (:basis-t decoded))
                     (not (neg? (:basis-t decoded))))
        (invalid-cursor! "Page token has an invalid revision."
                         :invalid-revision {:basis-t (:basis-t decoded)}))
      (when-not (or (nil? (:cache-scope decoded))
                    (vector? (:cache-scope decoded)))
        (invalid-cursor! "Page token has an invalid snapshot scope."
                         :invalid-cache-scope
                         {:cache-scope (:cache-scope decoded)}))
      (doseq [field [:source-scope
                     :native-revision
                     :adapter-fingerprint
                     :identity-contract
                     :dependency-scope-digest
                     :proof-digest]]
        (when-not (contains? decoded field)
          (invalid-cursor!
           "Page token is missing exact-snapshot continuation metadata."
           :missing-proof-context
           {:field field})))
      (and database-match? operation-match? query-match?))))

(defn- authenticate-page-bound
  "Decodes one page bound and annotates it with the computed scope-match
  boolean for the verified continuation decision."
  [opts op query-shape page-req]
  (let [decoded (decoded-page-bound opts page-req)
        scope-matches?
        (validate-page-token-identity! opts op query-shape decoded)]
    (some-> decoded
            (assoc :cursor/scope-matches? (boolean scope-matches?)))))

(defn- validate-page-token-schema!
  "Unconditional schema-generation validation on cursor acceptance.

  Runs on every resumption — recovery mode included. The stamp compared is
  the actual schema mutation identity (see `snapshot-result-context`), not a
  `basis-t` proxy, so ordinary data churn leaves it equal and only a real
  schema generation change rejects. Exact-snapshot reconstruction selects the
  historical snapshot whose stamp minted the token, so pinned walks still
  resume across later schema changes."
  [opts decoded]
  (when decoded
    (when-not (= (selected-schema-version opts)
                 (:schema-version decoded))
      (throw (ex-info "Page token was created under a different EACL schema generation."
                      {:type :eacl.pagination/stale-schema
                       :expected (selected-schema-version opts)
                       :actual (:schema-version decoded)}))))
  true)

(defn- internal-page-query
  [query page-req decoded]
  (let [edge (:edge decoded)]
    (cond-> (dissoc query :after :before :page/basis :consistency :cache?
                    :timeout-ms :cancellation-token)
      (and edge (= :asc (:direction page-req))) (assoc :after edge)
      (and edge (= :desc (:direction page-req))) (assoc :before edge))))

(defn- cursor-anchor-stale!
  [operation]
  (throw
   (ex-info
    "The cursor's query anchor no longer exists on the selected snapshot."
    {:type :eacl.pagination/stale-cursor
     :eacl/error :eacl.pagination/stale-cursor
     :operation operation
     :reason :query-anchor-identity-changed})))

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
  these three. (The vestigial :latest-result kind was deleted by
  trusted-surface-hygiene 11.1 — nothing has minted it since the v8 cursor
  redesign.)"
  #{:can? :lookup-page :relationship-page :count :permission-tree})

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
  "Validates the internal cursor of a cached lookup page. Only the stable
  engine's `:stable-edge` kind is minted; anything else invalidates the
  cached page so it is recomputed."
  [cursor]
  (case (:kind cursor)
    :stable-edge
    (when (and (= engine/stable-cursor-version (:version cursor))
               (= engine/stable-order-abi (:order-abi cursor))
               (contains? #{:forward :reverse} (:traversal cursor))
               (integer? (:ordinal cursor))
               (pos? (:ordinal cursor))
               (positive-eid? (:result-eid cursor)))
      {:eid (:result-eid cursor)
       :ordinal (:ordinal cursor)})

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

(defn- internal-relationship-page?
  [page]
  (and (map? page)
       (vector? (:data page))
       (every?
        (fn [{:keys [subject relation resource]}]
          (and (keyword? relation)
               (keyword? (:type subject))
               (positive-eid? (:id subject))
               (keyword? (:type resource))
               (positive-eid? (:id resource))))
        (:data page))
       (let [{:keys [start-cursor end-cursor
                     has-next-page? has-previous-page?]}
             (:page-info page)]
         (and (map? (:page-info page))
              (or (nil? start-cursor) (map? start-cursor))
              (or (nil? end-cursor) (map? end-cursor))
              (boolean? has-next-page?)
              (boolean? has-previous-page?)))))

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
  [opts consistency-context op query-identity kind valid-result? weight-fn compute]
  (let [contract (:execution-contract opts)
        {:keys [adapter db relationship-dependencies
                permission-dependencies basis-t completed-cache?
                request-proof-frame]}
        consistency-context
        evaluate
        #(do
           (execution/check! contract :semantic-evaluation)
           (let [value
                 (binding [subproblem/*decision-kernel*
                           (:decision-kernel opts)]
                   (portable-result kind (compute)))]
             (execution/check! contract :semantic-evaluation)
             value))
        snapshot-exact? (:snapshot-exact? consistency-context)
        cacheable?
        (and (:current-cache-store opts)
             completed-cache?
             (or (not snapshot-exact?)
                 (backend/deterministic? adapter))
             (or (nil? contract)
                 (execution/cache-stage-available? contract)))]
    (if-not cacheable?
      (do
        (shared-cache/record-current-bypass!
         (:current-cache-store opts))
        (assoc consistency-context
               :result (evaluate)
               :cached? false
               :cache-tier nil
             :cache-basis basis-t))
      (let [relation-ids
            #(or relationship-dependencies
                 (some-> permission-dependencies
                         deref
                         :relationship-dependencies))
            complete-proof
            (delay
              (proof-frame/resolve!
               request-proof-frame (relation-ids)))
            _ (execution/check! contract :cache-lookup)
            semantic-key
            {:operation op
             :query query-identity
             :evaluation (:evaluation contract)
             :demand (:demand contract)
             :engine-version engine/engine-version
             ;; The public order ABI is part of an answer's identity: a page
             ;; cached under one order must never be served under another.
             :order-abi engine/stable-order-abi
             :source-lifecycle
             (proof-frame/source-lifecycle request-proof-frame)
             :adapter-fingerprint (backend/fingerprint adapter)
             :recursive-traversal-limits
             (:recursive-traversal-limits opts)
             :permission-tree-limits
             (:permission-tree-limits opts)}
            answer
            (if snapshot-exact?
              (shared-cache/resolve-exact!
               (:current-cache-store opts)
               {:snapshot-exact-key
                (shared-cache/snapshot-exact-key adapter)
                :cache-lifecycle (:cache-lifecycle opts)
                :cache-basis basis-t
                :decision-kernel (:decision-kernel opts)
                :answer-weight-fn weight-fn
                :remember-answer? (:cache-remember-answers? opts)}
               semantic-key kind valid-result? evaluate)
              (shared-cache/resolve-current!
               (:current-cache-store opts)
               {:snapshot basis-t
                :cache-lifecycle (:cache-lifecycle opts)
                :snapshot-order basis-t
                :same-snapshot? =
                :snapshot-exact-key
                (shared-cache/snapshot-exact-key adapter)
                :cache-basis basis-t
                :decision-kernel (:decision-kernel opts)
                :managed-key-fn
                (when (:managed-cache-enabled? opts)
                  #(proof-frame/descriptor @complete-proof))
                :managed-subproblem-key-fn
                (when (:managed-cache-enabled? opts)
                  (fn [dependency]
                    (proof-frame/subset-descriptor
                     @complete-proof dependency)))
                :managed-subproblem-scope
                (consistency-v3/source-scope adapter)
                :answer-weight-fn weight-fn
                :remember-answer?
                (:cache-remember-answers? opts)}
               semantic-key kind valid-result? evaluate))
            _ (execution/check! contract :cache-result)]
        (assoc consistency-context
               :result (:value answer)
               :cached? (:cached? answer)
               :cache-tier (:cache-tier answer)
               :cache-basis
               (or (:cache-basis answer) basis-t))))))

(defn- continuation-context
  "Client-private cache handles for recursive state, recursive pages, and
  acyclic frontier heads.

  Opaque generated engine state contains runtime objects, so it must never be
  accepted from a caller-supplied
  provider or serialized. The private store is created by make-client and is
  not shared across clients. Its key commits to the exact source, adapter,
  identity, schema, query, and snapshot context authenticated by the cursor.

  A miss, eviction, rejected publication, or malformed value is only an
  optimization loss; the engine deterministically replays the authenticated
  prefix against the selected immutable snapshot."
  [opts op query-identity
   {:keys [adapter cursor-context request-proof-frame]}]
  (when-let [store (and (selected-schema-version opts)
                        adapter
                        cursor-context
                        (:continuation-cache-store opts))]
    (continuation/private-context
     store
     adapter
     op
     {:query (canonicalize query-identity)
      :cursor-proof
      (select-keys
       cursor-context
       [:source-scope
        :adapter-fingerprint
        :identity-contract
        :dependency-scope-digest
        :proof-digest])}
     {:snapshot-identity
      {:kind :proof-equivalent
       :proof
       (select-keys
        cursor-context
        [:source-scope
         :adapter-fingerprint
         :identity-contract
         :dependency-scope-digest
         :proof-digest])}
      :request-proof-frame request-proof-frame})))

(def ^:private empty-page
  "Unknown objects match nothing (SpiceDB-consistent, audit D9): lookups and
  reads over an object id that does not resolve to an existing entity return
  an empty page instead of asserting or degrading to a broader scan."
  {:data []
   :page-info {:start-cursor nil
               :end-cursor nil
               :has-next-page? false
               :has-previous-page? false}})

(declare capture-result-context with-cache-info)

(defn spiceomic-read-relationships
  [conn
   {:keys [object-id->entid] :as opts}
   filters]
  ;; The unified filter contract validates the complete public query before
  ;; page normalization or snapshot work (backend-unification 9.1), so
  ;; misuse classifies identically on every backend.
  (relationship-filters/validate! filters)
  (reject-live-basis! filters)
  (let [query-shape (list-query-shape :read-relationships filters)
        page-req (impl.indexed/normalize-page-request filters)
        decoded (authenticate-page-bound
                 opts :read-relationships query-shape page-req)
        {:keys [db basis-t schema-version cursor-context]
         :as result-context}
        (capture-result-context
         conn opts (:consistency filters)
         (fn [db _decoded]
           (schema-errors/validate-relationship-read!
            (schema/read-schema db)
            filters)
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
      (if decoded
        (cursor-anchor-stale! :read-relationships)
        empty-page)
      (let [filters'     (cond-> filters
                           subject-id (assoc :subject/id subject-eid)
                           resource-id (assoc :resource/id resource-eid))
            internal-query (internal-page-query filters' page-req decoded)
            answer
            (cached-authorization-result
             selected-opts result-context :read-relationships
             (shared-cache/lookup-page-query-identity filters internal-query)
             :relationship-page internal-relationship-page?
             internal-page-weight
             #(impl/read-relationships db internal-query))]
        (with-cache-info
         (coerce-relationship-page
          db selected-opts :read-relationships query-shape basis-t
          (:result answer))
         answer)))))

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
                :eacl/error :eacl/unknown-object
                :object {:type type :id id}})))))

(defn spice-relationship->internal
  "Resolves both relationship endpoints to existing internal eids.
  Throws :eacl/unknown-object for either endpoint rather than letting nils or
  ghost ids reach tx-data (raw :db.error/not-an-entity) or silently no-op."
  [db {:keys [object-id->entid object-id->ident]}
   {:keys [subject relation resource]}]
  (let [internalize
        (fn [object]
          (assoc (resolve-existing-object db object-id->entid object)
                 :eacl.relationship/identity-guard
                 (object-id->ident (:id object))))]
    {:subject (internalize subject)
     :relation relation
     :resource (internalize resource)}))

(defn- relationship-attr-eids
  [db]
  (into
   #{}
   (keep #(d/entid db %))
   relationship-storage/attributes))

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
  (let [adapter ((:backend-adapter-fn opts) db)]
    (causal-token/issue
     (:format-options opts)
     (merge
      (consistency-v3/source-scope adapter)
      (consistency-v3/native-revision adapter)))))

(defn- write-response
  [db opts]
  (if-let [token (response-token db opts)]
    {:zed/token token}
    {}))

(defn spiceomic-write-relationships!
  "Writes relationships and returns a zed token for the committed revision.

  There is no process-local cache bookkeeping here. The committed transaction
  atomically carries endpoint guards and the affected native relation
  generations. Relation-local CAS protects write semantics; cache validity
  depends on the committed generation, not on CAS."
  [conn opts updates]
  (let [updates (vec updates)]
    (doseq [{:keys [operation]} updates]
      (impl/validate-relationship-operation! operation))
    (let [schema (schema/read-schema (d/db conn))]
      (doseq [{:keys [relationship]} updates]
        (schema-errors/validate-relationship-write!
         schema :write-relationships
         {:resource-type (:type (:resource relationship))
          :subject-type (:type (:subject relationship))
          :relation (:relation relationship)})))
    (loop [attempt 1]
      (let [db (d/db conn)
            internal-updates
            (S/transform [S/ALL :relationship]
                         #(spice-relationship->internal db opts %)
                         updates)
            _ (relationship-mutations/validate-batch! internal-updates)
            relationship-tx-data
            (->> internal-updates
                 (mapcat #(impl/tx-update-relationship db %))
                 (remove nil?)
                 distinct
                 vec)
            tx-data
            (when (seq relationship-tx-data)
              (impl/optimistic-relationship-tx-data
               db relationship-tx-data))
            submission
            (if (seq tx-data)
              (try
                {:report @(d/transact conn tx-data)}
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

(defn- permission-cache-dependencies
  [db resource-type permission]
  ;; The same complete closure protects both completed answers and cursors.
  ;; A cacheless client may still paginate, so cursor correctness cannot be
  ;; conditional on whether the client-private result cache is enabled.
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

  Clearing the native store and remember flag selects a direct evaluation
  branch before semantic-key, dependency-stamp, provider, canonicalization, or
  cache-envelope work. Cursors still work independently."
  [opts cache-option]
  (shared-cache/validate-request-cache-option! cache-option)
  (if (false? cache-option)
    (assoc opts
           :continuation-cache-store nil
           :current-cache-store nil
           :cache-remember-answers? false)
    opts))

(defn- selected-schema-cache!
  [opts adapter db request-proof-frame]
  (let [proof (proof-frame/resolve! request-proof-frame [])
        schema-generation
        (when (proof-frame/complete? proof)
          (:schema-stamp proof))
        key [(backend/backend-id adapter)
             (backend/invoke adapter :source-scope)
             (backend/invoke adapter :source-lifecycle)
             schema-generation]
        registry (:derived-schema-caches opts)]
    (or (get @registry key)
        (let [created
              (impl.indexed/make-schema-cache db schema-generation)]
          (get (swap! registry
                      #(if (contains? % key)
                         %
                         (assoc % key created)))
               key)))))

(def ^:private cursor-execution-identity-fields
  [:source-scope :adapter-fingerprint :identity-contract])

(defn- cursor-execution-identity
  [context]
  (secure/canonical-digest
   "eacl/datomic/cursor-execution-identity/v1"
   (select-keys context cursor-execution-identity-fields)))

(defn- cursor-continuation-proof
  [context]
  (secure/canonical-digest
   "eacl/datomic/cursor-continuation-proof/v1"
   [(:dependency-scope-digest context)
    (:proof-digest context)]))

(defn- cursor-revision-code
  "Native-revision code used by the generated continuation decision."
  [current cursor-envelope context]
  (let [revision (:native-revision context)]
    (cond
      (= revision (:native-revision current)) 0
      (= revision (:native-revision cursor-envelope)) 1
      :else 2)))

(defn- cursor-decision
  ;; Routed through the client's configured kernel like every other
  ;; generated decision; the request-time global was the one seam that
  ;; bypassed per-client kernel selection.
  [kernel current cursor-envelope exact]
  (verified/decide
   (or kernel production-kernel/default-selection)
   :cursor-continuation
   {:authenticated?
    (boolean (:cursor/authenticated? cursor-envelope))
    :scope-matches?
    (boolean (:cursor/scope-matches? cursor-envelope))
    :expired? (boolean (:cursor/expired? cursor-envelope))
    :source (cursor-execution-identity current)
    :cursor-source
    (cursor-execution-identity cursor-envelope)
    :current-proof (cursor-continuation-proof current)
    :cursor-proof
    (cursor-continuation-proof cursor-envelope)
    :cursor-graph
    (cursor-revision-code current cursor-envelope cursor-envelope)
    :exact
    (when exact
      {:graph (cursor-revision-code current cursor-envelope exact)
       :source (cursor-execution-identity exact)
       :proof (cursor-continuation-proof exact)})}))

(defn- exact-fallback-decision
  "Decides an exact-fallback continuation.

  The generated `:cursor-continuation` decision accepts `:exact` only when
  the exact snapshot's dependency proof equals the cursor's. On Datomic the
  exact snapshot is `d/as-of` at the cursor's own basis, and its
  relationship data is exact there — but `:eacl/relation-version` is a
  `:db/noHistory` attribute whose superseded values an index job may drop,
  so the historical stamp read can come back empty and the proof frame
  incomplete. That is not a divergence: the exact snapshot's proof is, by
  identity, the proof recorded at minting. When the kernel reports
  divergence purely because the exact snapshot's proof was unreadable
  (`proof-complete?` false) while its native revision and execution
  identity are the cursor's own, the continuation is `:exact`. Readable
  stamps that differ (a rewritten history) still diverge."
  [kernel current-cursor-context decoded exact-cursor-context proof-complete?]
  (let [decision (cursor-decision kernel current-cursor-context decoded
                                  exact-cursor-context)]
    (if (and (= :history-divergence decision)
             (not proof-complete?)
             (= (:native-revision exact-cursor-context)
                (:native-revision decoded))
             (= (cursor-execution-identity exact-cursor-context)
                (cursor-execution-identity decoded)))
      :exact
      decision)))

(defn- dependency-cursor-context
  "Cursor continuation context for one selected snapshot.

  Permission lookups carry the dependency-stamp descriptor the managed cache
  already computes — schema generation plus the scalar dependency frontier —
  so a transaction touching no relation in the query's closure
  leaves the continuation proof equal and the cursor decision at `:current`.
  The generations live in the snapshot itself. Relationship reads and
  unreadable generations keep the exact-snapshot proof; empty closures use
  the initial frontier."
  [snapshot-adapter request-proof-frame relation-ids]
  (let [base (relay/dependency-context snapshot-adapter)
        proof
        (when (some? relation-ids)
          (proof-frame/resolve!
           request-proof-frame relation-ids))
        descriptor (proof-frame/descriptor proof)]
    (if-not descriptor
      base
      (assoc base
             :dependency-scope-digest
             (secure/canonical-digest
              "eacl/datomic/cursor-dependency-scope/v1"
              {:mode :relation-dependencies
               :relation-ids (vec (sort (distinct relation-ids)))})
             :proof-digest
             (secure/canonical-digest
              "eacl/datomic/cursor-dependency-proof/v1"
              descriptor)))))

(defn- new-request-proof-frame
  [opts adapter]
  (proof-frame/request-frame
   adapter
   {:diagnostic-fn
    (fn [diagnostic]
      (shared-cache/record-proof-unavailable!
       (:current-cache-store opts)
       diagnostic))}))

(defn- snapshot-result-context
  [opts snapshot-adapter prepare decoded]
  (let [db (:db (backend/state snapshot-adapter))
        ;; `d/as-of` filters visibility but retains its backing DB's basis-t.
        ;; The adapter exact locator is the selected logical revision.
        basis-t (backend/invoke snapshot-adapter :exact-locator)
        request-proof-frame (new-request-proof-frame opts snapshot-adapter)
        schema-cache
        (delay
          (selected-schema-cache!
           opts snapshot-adapter db request-proof-frame))
        {:keys [permission-dependency-key]
         :as prepared}
        (prepare db decoded)
        permission-dependencies
        (when permission-dependency-key
          (delay
            (binding [impl.indexed/*schema-cache* @schema-cache]
              (apply permission-cache-dependencies
                     db permission-dependency-key))))
        cache-scope [:basis basis-t]
        cursor-context
        (dependency-cursor-context
         snapshot-adapter request-proof-frame
         (some-> permission-dependencies
                 deref
                 :relationship-dependencies))]
    (assoc prepared
           :db db
           :adapter snapshot-adapter
           :request-proof-frame request-proof-frame
           :basis-t basis-t
           :cache-scope cache-scope
           :cursor-scope cache-scope
           :cursor-context cursor-context
           :schema-cache schema-cache
           :permission-dependencies permission-dependencies
           ;; The actual schema mutation identity of the selected snapshot —
           ;; not a basis-t proxy — so the unconditional schema-generation
           ;; check fires only on a real generation change. The token stays
           ;; exact-revision pinned through :basis-t; exact reconstruction
           ;; selects the historical snapshot whose generation minted the
           ;; cursor, so pinned walks resume across later schema changes.
           :schema-version
           (some-> (impl.indexed/schema-version db) str))))

(defn- cursor-snapshot-expired!
  [operation decoded]
  (throw
   (ex-info
    "The cursor's exact Datomic snapshot is no longer retained."
    {:type :eacl.consistency/snapshot-expired
     :eacl/error :eacl.consistency/snapshot-expired
     :operation operation
     :exact-locator (get-in decoded [:native-revision :exact-locator])})))

(defn- select-request-snapshot
  [conn opts consistency-value]
  (let [contract (:execution-contract opts)
        _ (execution/check! contract :consistency-selection)
        descriptor (consistency/descriptor consistency-value)
        source-adapter ((:backend-adapter-fn opts) (d/db conn))
        selection-options
        {:format-options (:format-options opts)
         :decision-kernel (:decision-kernel opts)
         :issue-token? false
         :timeout-ms
         (if contract
           (min (:consistency-sync-timeout-ms opts)
                (execution/remaining-millis contract))
           (:consistency-sync-timeout-ms opts))}
        selection
        (if (= :minimize-latency (:mode descriptor))
          (consistency-v3/captured-current-selection
           source-adapter consistency-value selection-options)
          (consistency-v3/select
           source-adapter
           consistency-value
           selection-options))]
    (execution/check! contract :consistency-selected)
    selection))

(defn- capture-result-context
  "Selects one immutable request snapshot. Exact-snapshot requests reconstruct
  the authenticated historical basis. Other modes continue on current only
  while its dependency proof is equal; after a proof change they reconstruct
  the cursor's authenticated exact basis or fail closed."
  [conn opts consistency-value prepare operation decoded]
  (let [selection
        (select-request-snapshot conn opts consistency-value)
        source-adapter (:adapter selection)
        selected-adapter (:adapter selection)
        selected-context
        (snapshot-result-context opts selected-adapter prepare decoded)
        selected-current-basis (:basis-t selected-context)
        {:keys [mode]} (:descriptor selection)
        request-token (:request-token selection)
        requested-t (:revision request-token)
        _ (when (and decoded
                     (= :at-exact-snapshot mode)
                     (not= (:exact-locator request-token)
                           (get-in decoded
                                   [:native-revision :exact-locator])))
            (consistency-v3/cursor-conflict!
             {:cursor-exact-locator
              (get-in decoded [:native-revision :exact-locator])
              :requested-exact-locator
              (:exact-locator request-token)}))
        selected-context
        (if (nil? decoded)
          selected-context
          (let [current-cursor-context
                (:cursor-context selected-context)
                initial
                (cursor-decision (:decision-kernel opts)
                                 current-cursor-context decoded nil)]
            (case initial
              :current
              selected-context

              :expired
              (throw (page-token-expired-error decoded))

              :snapshot-unavailable
              (let [_
                    (when (and (= :at-least-as-fresh mode)
                               (or (not (integer? requested-t))
                                   (not (integer?
                                         (get-in decoded
                                                 [:native-revision :revision])))
                                   (< (get-in decoded
                                              [:native-revision :revision])
                                      requested-t)))
                      (consistency-v3/cursor-conflict!
                       {:cursor-order-hint
                        (get-in decoded [:native-revision :revision])
                        :requested-order-hint requested-t}))
                    _ (execution/check!
                       (:execution-contract opts)
                       :cursor-exact-fallback)
                    exact-timeout-ms
                    (if-let [contract (:execution-contract opts)]
                      (min (:consistency-sync-timeout-ms opts)
                           (execution/remaining-millis contract))
                      (:consistency-sync-timeout-ms opts))
                    exact
                    (backend/invoke
                     source-adapter
                     :select-exact
                     {:revision
                      (get-in decoded [:native-revision :revision])
                      :exact-locator
                      (get-in decoded [:native-revision :exact-locator])}
                     exact-timeout-ms)
                    _ (when-not exact
                        (cursor-snapshot-expired! operation decoded))
                    exact-context
                    (snapshot-result-context opts exact prepare decoded)
                    exact-relation-ids
                    (some-> (:permission-dependencies exact-context)
                            deref
                            :relationship-dependencies)
                    exact-proof-complete?
                    (or (nil? exact-relation-ids)
                        (proof-frame/complete?
                         (proof-frame/resolve!
                          (:request-proof-frame exact-context)
                          exact-relation-ids)))
                    exact-decision
                    (exact-fallback-decision
                     (:decision-kernel opts)
                     current-cursor-context decoded
                     (:cursor-context exact-context)
                     exact-proof-complete?)]
                (if (= :exact exact-decision)
                  (assoc exact-context :mode :at-exact-snapshot)
                  (throw
                   (ex-info
                    "The Datomic cursor exact locator resolved to another native revision."
                    {:type :eacl.consistency/history-divergence
                     :eacl/error
                     :eacl.consistency/history-divergence
                     :kernel-decision exact-decision}))))

              (throw
               (ex-info
                "The Datomic cursor was rejected by the verification kernel."
                {:type :eacl.consistency/history-divergence
                 :eacl/error :eacl.consistency/history-divergence
                 :kernel-decision initial})))))]
    (when (:revision-checkpoints opts)
      (revision/observe! (:revision-checkpoints opts)
                         (d/basis-t (d/db conn))))
    (let [snapshot-exact?
          (or (= :at-exact-snapshot
                 (or (:mode selected-context) mode))
              (not= selected-current-basis (:basis-t selected-context)))]
      (merge
     selected-context
     {:mode (or (:mode selected-context) mode)
      :snapshot-exact? snapshot-exact?
      ;; A cursor is authenticated before snapshot selection. Historical
      ;; reconstruction may use only the separately keyed snapshot-exact tier;
      ;; current requests retain exact-current plus managed-proof behavior.
      :completed-cache?
      true
      :requested-t requested-t
      :selection selection
      :response-token nil}))))

(defn- capture-basic-result-context
  "Selects one immutable snapshot without constructing schema or relation
  stamps. Non-cursor operations attempt exact-current lookup before paying the
  managed dependency-stamp cost."
  [conn opts consistency-value]
  (let [selection
        (select-request-snapshot conn opts consistency-value)
        adapter (:adapter selection)
        db (:db (backend/state adapter))
        basis-t (backend/invoke adapter :exact-locator)
        mode (get-in selection [:descriptor :mode])
        request-token (:request-token selection)]
    (when (:revision-checkpoints opts)
      (revision/observe! (:revision-checkpoints opts)
                         (d/basis-t (d/db conn))))
    {:adapter adapter
     :db db
     :basis-t basis-t
     :request-proof-frame (new-request-proof-frame opts adapter)
     :mode mode
     :snapshot-exact? (= :at-exact-snapshot mode)
     :requested-t (:revision request-token)
     :selection selection
     :response-token nil}))

(defn- cached-basic-authorization-result
  [opts context op query-identity kind valid-result?
   resource-type permission compute]
  (let [contract (:execution-contract opts)
        {:keys [adapter db basis-t mode request-proof-frame]} context
        schema-cache
        (delay
          (selected-schema-cache!
           opts adapter db request-proof-frame))
        evaluate
        #(do
           (execution/check! contract :schema-plan)
           (let [value
                 (binding [impl.indexed/*schema-cache* @schema-cache
                           subproblem/*decision-kernel*
                           (:decision-kernel opts)]
                   (portable-result kind (compute)))]
             (execution/check! contract :semantic-evaluation)
             value))
        snapshot-exact? (= :at-exact-snapshot mode)
        cacheable?
        (and (:current-cache-store opts)
             (:cache-remember-answers? opts)
             (or (not snapshot-exact?)
                 (backend/deterministic? adapter))
             (or (nil? contract)
                 (execution/cache-stage-available? contract)))]
    (if-not cacheable?
      (do
        (shared-cache/record-current-bypass!
         (:current-cache-store opts))
        (assoc context
               :result (evaluate)
               :cached? false
               :cache-tier nil
               :cache-basis basis-t))
      (let [dependencies
            (delay
              (binding [impl.indexed/*schema-cache* @schema-cache]
                (permission-cache-dependencies
                 db resource-type permission)))
            complete-proof
            (delay
              (proof-frame/resolve!
               request-proof-frame
               (:relationship-dependencies @dependencies)))
            _ (execution/check! contract :cache-lookup)
            semantic-key
            {:operation op
             :query query-identity
             :evaluation (:evaluation contract)
             :demand (:demand contract)
             :engine-version engine/engine-version
             ;; The public order ABI is part of an answer's identity: a page
             ;; cached under one order must never be served under another.
             :order-abi engine/stable-order-abi
             :source-lifecycle
             (proof-frame/source-lifecycle request-proof-frame)
             :adapter-fingerprint (backend/fingerprint adapter)
             :recursive-traversal-limits
             (:recursive-traversal-limits opts)
             :permission-tree-limits
             (:permission-tree-limits opts)}
            answer
            (if snapshot-exact?
              (shared-cache/resolve-exact!
               (:current-cache-store opts)
               {:snapshot-exact-key
                (shared-cache/snapshot-exact-key adapter)
                :cache-lifecycle (:cache-lifecycle opts)
                :cache-basis basis-t
                :decision-kernel (:decision-kernel opts)}
               semantic-key kind valid-result? evaluate)
              (shared-cache/resolve-current!
               (:current-cache-store opts)
               {:snapshot basis-t
                :cache-lifecycle (:cache-lifecycle opts)
                :snapshot-order basis-t
                :same-snapshot? =
                :snapshot-exact-key
                (shared-cache/snapshot-exact-key adapter)
                :cache-basis basis-t
                :decision-kernel (:decision-kernel opts)
                :managed-key-fn
                (when (:managed-cache-enabled? opts)
                  #(proof-frame/descriptor @complete-proof))
                :managed-subproblem-key-fn
                (when (:managed-cache-enabled? opts)
                  (fn [dependency]
                    (proof-frame/subset-descriptor
                     @complete-proof dependency)))
                :managed-subproblem-scope
                (consistency-v3/source-scope adapter)}
               semantic-key kind valid-result? evaluate))
            _ (execution/check! contract :cache-result)]
        (assoc context
               :result (:value answer)
               :cached? (:cached? answer)
               :cache-tier (:cache-tier answer)
               :cache-basis
               (or (:cache-basis answer) basis-t))))))

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
    (binding [impl.indexed/*schema-cache* @schema-cache]
      (f))
    (f)))

(def ^:private delete-object-batch-size 1000)

(defn- transact-delete-object-batch!
  [conn batch]
  (let [batch (vec batch)]
    (loop [attempt 1]
      (let [db (d/db conn)
          stamped (impl/stamp-relation-versions batch)
          guarded (impl/optimistic-relationship-tx-data db stamped)
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
                (transact-delete-object-batch! conn batch)]
            (recur (next batches)
                   (+ retracted
                      (relationship-retraction-count db-after tx-data))
                   db-after))
          (assoc (write-response db-after opts)
                 :retracted-datoms retracted))))))

(defn spiceomic-check-permission
  [conn {:keys [object->entid] :as opts}
   subject permission resource consistency-value]
  (let [{:keys [db] :as result-context}
        (capture-basic-result-context
         conn opts consistency-value)
        _ (schema-errors/validate-permission-request!
           (schema/read-schema db)
           (or (get-in opts [:execution-contract :operation]) :can?)
           {:resource-type (:type resource)
            :subject-type (:type subject)
            :permission permission})
        internal-subject
        (spice-object
         (:type subject)
         (object->entid db subject))
        internal-resource
        (spice-object
         (:type resource)
         (object->entid db resource))]
    (if-not (and (:id internal-subject) (:id internal-resource))
      {:allowed? false
       :cached? false
       :cache-basis nil}
      (let [query-identity
            {:public
             {:subject subject
              :permission permission
              :resource resource}
             :internal
             {:subject internal-subject
              :permission permission
              :resource internal-resource}}
            answer
            (cached-basic-authorization-result
             opts result-context :can? query-identity :can?
             boolean-result?
             (:type resource)
             permission
             #(impl/can? db internal-subject permission
                         internal-resource))]
        {:allowed? (:result answer)
         :cached? (:cached? answer)
         :cache-basis (:cache-basis answer)}))))

(defn spiceomic-can?
  [conn opts subject permission resource consistency-value]
  (:allowed?
   (spiceomic-check-permission
    conn opts subject permission resource consistency-value)))

(defn spiceomic-lookup-resources
  [conn
   {:as opts
    :keys [spice-object->internal]}
  {:as query :keys [subject]}]
  (reject-live-basis! query)
  (let [query-shape (list-query-shape :lookup-resources query)
        page-req (impl.indexed/normalize-page-request query)
        decoded (authenticate-page-bound
                 opts :lookup-resources query-shape page-req)
        prepare
        (fn [db decoded-bound]
          (schema-errors/validate-permission-request!
           (schema/read-schema db)
           :lookup-resources
           {:resource-type (:resource/type query)
            :subject-type (:type subject)
            :permission (:permission query)})
          (let [internal-subject (spice-object->internal db subject)
                query' (assoc query :subject internal-subject)]
            {:db db
             :internal-subject internal-subject
             :query' query'
             :query-shape query-shape
             :internal-query
             (internal-page-query query' page-req decoded-bound)
             :permission-dependency-key
             [(:resource/type query') (:permission query')]}))
        captured
        (capture-result-context
         conn opts (:consistency query) prepare
         :lookup-resources decoded)
        {:keys [db internal-subject query' query-shape internal-query
                cache-scope schema-version]
         :as result-context}
        captured
        selected-opts
        (assoc opts
               :selected-schema-version schema-version
               :page-cursor-context (:cursor-context captured))]
    (validate-page-token-schema! selected-opts decoded)
    (if (nil? (:id internal-subject))
      ;; Unknown subjects match nothing on a first page. A continued page may
      ;; not silently discard its authenticated anchor.
      (if decoded
        (cursor-anchor-stale! :lookup-resources)
        (assoc empty-page :cached? false :cache-basis nil))
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
                      result-context))})))
            answer
            (cached-authorization-result
             selected-opts result-context :lookup-resources
             (shared-cache/lookup-page-query-identity
              query internal-query)
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
  (let [{:keys [db] :as result-context}
        (capture-basic-result-context
         conn opts (:consistency query))
        _ (schema-errors/validate-permission-request!
           (schema/read-schema db)
           :count-resources
           {:resource-type (:resource/type query)
            :subject-type (:type subject)
            :permission (:permission query)})
        subject-ent (spice-object->internal db subject)
        query' (-> query
                   (assoc :subject subject-ent)
                   (dissoc :consistency :cache?))]
    (if (nil? (:id subject-ent))
      (assoc (empty-count-response query) :cached? false :cache-basis nil)
      (let [answer
            (cached-basic-authorization-result
             opts result-context :count-resources
             {:public (dissoc query :consistency :cache?)
              :internal query'}
             :count count-response?
             (:resource/type query')
             (:permission query')
             #(impl/count-resources db query'))]
        (with-cache-info (:result answer) answer)))))

(defn spiceomic-lookup-subjects
  [conn
   {:as opts
    :keys [spice-object->internal]}
  query]
  (reject-live-basis! query)
  (let [query-shape (list-query-shape :lookup-subjects query)
        page-req (impl.indexed/normalize-page-request query)
        decoded (authenticate-page-bound
                 opts :lookup-subjects query-shape page-req)
        prepare
        (fn [db decoded-bound]
          (schema-errors/validate-permission-request!
           (schema/read-schema db)
           :lookup-subjects
           {:resource-type (:type (:resource query))
            :subject-type (:subject/type query)
            :permission (:permission query)})
          (let [internal-resource
                (spice-object->internal db (:resource query))
                query' (assoc query :resource internal-resource)]
            {:db db
             :internal-resource internal-resource
             :query' query'
             :query-shape query-shape
             :internal-query
             (internal-page-query query' page-req decoded-bound)
             :permission-dependency-key
             [(:type internal-resource) (:permission query')]}))
        captured
        (capture-result-context
         conn opts (:consistency query) prepare
         :lookup-subjects decoded)
        {:keys [db internal-resource query' query-shape internal-query
                cache-scope schema-version]
         :as result-context}
        captured
        selected-opts
        (assoc opts
               :selected-schema-version schema-version
               :page-cursor-context (:cursor-context captured))]
    (validate-page-token-schema! selected-opts decoded)
    (if (nil? (:id internal-resource))
      (if decoded
        (cursor-anchor-stale! :lookup-subjects)
        (assoc empty-page :cached? false :cache-basis nil))
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
                      result-context))})))
            answer
            (cached-authorization-result
             selected-opts result-context :lookup-subjects
             (shared-cache/lookup-page-query-identity
              query internal-query)
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
  (let [{:keys [db] :as result-context}
        (capture-basic-result-context
         conn opts (:consistency query))
        _ (schema-errors/validate-permission-request!
           (schema/read-schema db)
           :count-subjects
           {:resource-type (:type (:resource query))
            :subject-type (:subject/type query)
            :permission (:permission query)})
        resource-ent
        (spice-object->internal db (:resource query))
        query' (-> query
                   (assoc :resource resource-ent)
                   (dissoc :consistency :cache?))]
    (if (nil? (:id resource-ent))
      (assoc (empty-count-response query) :cached? false :cache-basis nil)
      (let [answer
            (cached-basic-authorization-result
             opts result-context :count-subjects
             {:public (dissoc query :consistency :cache?)
              :internal query'}
             :count count-response?
             (:type resource-ent)
             (:permission query')
             #(impl/count-subjects db query'))]
        (with-cache-info (:result answer) answer)))))

(defn spiceomic-expand-permission-tree
  [conn opts query]
  (let [{:keys [adapter db] :as context}
        (capture-basic-result-context
         conn opts (:consistency query))
        _ (schema-errors/validate-expansion-request!
           (schema/read-schema db)
           :expand-permission-tree
           (:type (:resource query))
           (:permission query))
        contract (:execution-contract opts)
        answer
        (cached-basic-authorization-result
         opts context :expand-permission-tree
         (dissoc query :consistency :cache? :timeout-ms :cancellation-token)
         :permission-tree map?
         (:type (:resource query)) (:permission query)
         #(permission-tree/expand
           adapter
           {:limits (:permission-tree-limits opts)
            :execution-contract contract}
           (:resource query)
           (:permission query)))
        tree (:result answer)]
    (execution/check! contract :permission-tree-token-issuance)
    (let [token
          (permission-tree/selected-adapter-token adapter opts)]
      (execution/check! contract :permission-tree-token-issued)
      {:expanded-at token
       :tree-root tree})))

(defrecord Spiceomic [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (execute-request
     opts :can? {:subject subject :permission permission :resource resource}
     #(spiceomic-can? conn % subject permission resource
                     consistency/minimize-latency)))

  (can? [_ subject permission resource consistency]
    (execute-request
     opts :can? {:subject subject :permission permission :resource resource}
     #(spiceomic-can? conn % subject permission resource consistency)))

  ;; The map arity is where a per-request :cache? override lands. The
  ;; positional arities keep their signatures and always use the client's own
  ;; configured cache.
  (can? [_ {:keys [subject permission resource consistency]
            cache? :cache? :as request}]
    (execute-request
     opts :can? request
     #(spiceomic-can? conn (request-cache-opts % cache?)
                     subject permission resource
                     consistency)))

  (read-schema [_]
    (schema/read-schema (d/db conn)))

  (write-schema! [_ schema-string]
    (let [base-db     (d/db conn)
          expected-version (impl.indexed/schema-version base-db)
          deltas      (schema/write-schema!
                       conn schema-string
                       (select-keys opts [:token-ttl-seconds])
                       expected-version)
          next-version (:eacl/schema-version (meta deltas))]
      (reset! (:diagnostic-schema-version opts)
              next-version)
      (when-not (:eacl.schema/no-op? (meta deltas))
        (reset! (:derived-schema-caches opts) {})
        (when-let [store (:current-cache-store opts)]
          (shared-cache/expire-current! store)))
      (merge deltas
             (write-response
              (:eacl.schema/db-after (meta deltas))
              opts))))

  (read-relationships [_ filters]
    (execute-request
     opts :read-relationships filters
     #(spiceomic-read-relationships
       conn (request-cache-opts % (:cache? filters))
       (dissoc filters :timeout-ms :cancellation-token))))

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

  (delete-object! [_ object]
    (spiceomic-delete-object! conn opts object))

  (lookup-resources [_ query]
    (execute-request
     opts :lookup-resources query
     #(spiceomic-lookup-resources
       conn (request-cache-opts % (:cache? query))
       (dissoc query :evaluation :timeout-ms :cancellation-token))))

  (count-resources [_ query]
    (execute-request
     opts :count-resources query
     #(spiceomic-count-resources
       conn (request-cache-opts % (:cache? query))
       (dissoc query :evaluation :timeout-ms :cancellation-token))))

  (lookup-subjects [_ query]
    (execute-request
     opts :lookup-subjects query
     #(spiceomic-lookup-subjects
       conn (request-cache-opts % (:cache? query))
       (dissoc query :evaluation :timeout-ms :cancellation-token))))

  (count-subjects [_ query]
    (execute-request
     opts :count-subjects query
     #(spiceomic-count-subjects
       conn (request-cache-opts % (:cache? query))
       (dissoc query :evaluation :timeout-ms :cancellation-token))))

  (expand-permission-tree [_ query]
    (permission-tree/validate-request! query)
    (execute-request
     opts :expand-permission-tree query
     #(spiceomic-expand-permission-tree conn % query)))

  IDetailedAuthorization
  (-check-permission
    [_ {:keys [subject permission resource consistency]
        cache? :cache? :as request}]
    (execute-request
     opts :check-permission request
     #(spiceomic-check-permission
       conn (request-cache-opts % cache?)
       subject permission resource
       (or consistency consistency/minimize-latency)))))

(defn expire-cache!
  "Rotates the complete local cache/token lifecycle for one Datomic client.

  Pass a coordinated lifecycle identity as the optional second argument when
  tokens are exchanged across processes after a restore."
  ([client]
   (expire-cache! client (str (UUID/randomUUID))))
  ([client source-lifecycle]
   (when-not (instance? Spiceomic client)
     (throw (ex-info "expire-cache! requires a Datomic EACL client."
                     {:type :eacl/invalid-client})))
   (causal-token/validate-source-lifecycle! source-lifecycle)
   (let [opts (:opts client)]
     (some-> (:source-lifecycle-state opts)
             (reset! source-lifecycle))
     (when-let [store (:current-cache-store opts)]
       (shared-cache/expire-current! store))
     (some-> (:derived-schema-caches opts) (reset! {}))
     (some-> (:continuation-cache-store opts) continuation/clear!)
     (some-> (:revision-checkpoints opts) :state (reset! [])))
   nil))

(def prepare-cache-coherence!
  "Initializes missing native cache generations on a quiesced connection.

  This does not detect or repair earlier unsupported unstamped mutations and
  is not a cache flush."
  schema/prepare-cache-coherence!)

(defn cache-stats
  "Returns private completed-cache counters for one Datomic EACL client."
  [client]
  (when-not (instance? Spiceomic client)
    (throw (ex-info "cache-stats requires a Datomic EACL client."
                    {:type :eacl/invalid-client})))
  (if-let [store (get-in client [:opts :current-cache-store])]
    (shared-cache/current-cache-stats store)
    {:disabled? true}))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :object-id->lookup-ref
    :object-id->ident
    :cache
    :security-key
    :security-keyring
    :security-kid
    :cursor-ttl-seconds
    :page-token-key
    :page-token-keyring
    :page-token-kid
    :page-token-ttl-seconds
    :zed-token-key
    :zed-token-keyring
    :zed-token-kid
    :token-ttl-seconds
    :source-lifecycle
    :adapter-fingerprint
    :adapter-deterministic?
    :consistency-sync-timeout-ms
    :execution-timeout-ms
    :cache-attempt
    :recursive-traversal-limits
    :permission-tree-limits
    :service-admission
    :auto-migrate-v6})

(def ^:private canonical-security-opt-keys
  [:security-key :security-keyring :security-kid :cursor-ttl-seconds])

(def ^:private page-token-alias-opt-keys
  [:page-token-key :page-token-keyring :page-token-kid
   :page-token-ttl-seconds])

(def ^:private known-cache-opt-keys
  #{:remember-answers
    :namespace
    :checkpoints
    :ttl-ms
    :max-weight
    :max-entry-weight
    :max-entries
    :kind-max-weight
    :two-hit-kinds
    :admission-entries
    :subproblem-cache})

(defn- cache-adapter?
  "Whether `x` is a cache adapter rather than a config map.

  Checked BEFORE map?, and that order is load-bearing: the built-in adapter is
  a defrecord, so it satisfies map? too and would otherwise be read as a
  configuration map whose every key is unknown."
  [x]
  (and (some? x)
       (or (satisfies? cache/CacheStore x)
           (satisfies? shared-cache/CacheStore x))))

(defn- no-cache-option?
  [x]
  (or (cache/no-cache? x)
      (shared-cache/no-cache? x)))

(defn- normalize-cache-config
  "Normalizes the :cache client option.

    absent / nil     a default client-local adapter
    cache/no-cache   no caching
    {...}            advanced tuning and test options; see make-client

  Datomic's completed-answer and continuation stores are client-private.
  Caller-supplied CacheStore adapters used to be accepted but never controlled
  either live store, so they are now rejected instead of being decorative."
  [cache-option]
  (when (boolean? cache-option)
    (throw (ex-info (str "EACL Config Error: :cache takes a configuration map, not"
                         " a boolean. Use eacl.cache/no-cache to"
                         " disable caching, or omit :cache for the default"
                         " stores. To bypass the cache for one call, pass"
                         " :cache? false on the request instead.")
                    {:type :eacl/invalid-config
                     :key :cache
                     :value cache-option})))
  (when (and (cache-adapter? cache-option)
             (not (no-cache-option? cache-option)))
    (throw (ex-info (str "EACL Config Error: Datomic :cache does not accept a"
                         " provider adapter. Omit :cache for the bounded"
                         " client-private stores, pass a tuning map, or use"
                         " eacl.cache/no-cache to disable caching.")
                    {:type :eacl/invalid-config
                     :key :cache
                     :reason :unsupported-provider-store})))
  (when-not (or (nil? cache-option)
                (no-cache-option? cache-option)
                (map? cache-option))
    (throw (ex-info (str "EACL Config Error: :cache must be"
                         " eacl.cache/no-cache or a configuration map.")
                    {:type :eacl/invalid-config
                     :key :cache
                     :value cache-option})))
  (let [config (if (map? cache-option) cache-option {})
        _ (when (contains? config :store)
            (throw
             (ex-info (str "EACL Config Error: Datomic :cache :store never"
                           " controlled the live cache. Omit it for the bounded"
                           " client-private stores, or pass"
                           " eacl.cache/no-cache as :cache.")
                      {:type :eacl/invalid-config
                       :key :cache
                       :reason :unsupported-provider-store
                       :unknown-keys [:store]
                       :known-keys known-cache-opt-keys})))
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
          enabled? (not (no-cache-option? cache-option))
          ;; Consumers choose an adapter (or explicit no-cache); they should
          ;; not have to understand entry kinds to get a good outcome.
          ;; Explicit :on-repeat avoids retaining a completed answer until its
          ;; query has demonstrated reuse. `cache/no-cache` remains a
          ;; meaningful choice:
          ;; on traffic that never repeats, or whose direct evaluation is
          ;; cheaper than authenticated cache validation, lookup/proof cost is
          ;; unrecoverable regardless of admission policy.
          remember (if (contains? config :remember-answers)
                     (when enabled? (:remember-answers config))
                     (when enabled? true))
          remember-answers? (boolean remember)
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
          continuation-store
          (when enabled?
            ;; Opaque engine states remain in one client-private, atomically
            ;; updated bounded store. They are never serialized or admitted to
            ;; a caller-supplied provider.
            (let [max-weight (or (:max-weight store-config)
                                 (* 16 1024 1024))]
              (continuation/make-store
               {:max-weight max-weight
                :max-entry-weight
                (or (:max-entry-weight store-config) max-weight)
                :max-entries (or (:max-entries store-config) 1024)})))]
      {:enabled? enabled?
       :continuation-store continuation-store
       :namespace (or (:namespace config) :eacl)
       :checkpoints
       (when (and enabled? (:checkpoints config))
         (revision/revision-checkpoints
          (if (map? (:checkpoints config))
            (:checkpoints config)
            {})))
       :ttl-ms ttl-ms
       :native-max-entries
       (or (:max-entries config) 1024)
       :native-admit-on-repeat?
       (= :on-repeat remember)
       :native-subproblem-cache
       (or (:subproblem-cache config) {})
       :remember-answers? (and remember-answers? enabled?)})))

(defn make-client
  "Builds an IAuthorization client over a Datomic conn.

  Options (unknown keys throw :eacl/invalid-config — a silently ignored key
  means silently wrong ID coercion):
  - :entid->object-id  (fn [db eid] external-id) — canonical ID coercion, as documented in the README.
  - :object-id->lookup-ref (fn [external-id] ident-resolvable-by-d-entid).
    Default: [:eacl/id id]. :object-id->ident is an accepted Datomic alias.
  - :cache — controls this client's private current-generation cache.

      omitted     a bounded client-private cache (this is the norm)
      nil         the same client-private cache as omission
      cache/no-cache
                  no caching at all
      <map>       native capacity options. Complete native
                  answers are weight-bounded (see :subproblem-cache
                  :answer-max-weight, default 16 MiB) with LRU eviction and
                  oversized rejection; :max-entries bounds the client-private
                  continuation store and :on-repeat sighting window, but no
                  longer counts native answers.

    Pass cache/no-cache when the same permission check is essentially never
    asked twice — a batch job sweeping distinct resources, say — or when direct
    evaluation is cheaper than completed-answer validation. Benchmark
    representative permissions before enabling caching for latency alone.

    Native completed answers never come from a caller-supplied shared
    provider. Exact hits belong to the selected immutable DB generation;
    proof-backed hits additionally require a complete ordered-generation
    request proof.

    To bypass the configured cache for ONE call, pass :cache? false on the
    request — on the map arity of can?, and in the query map for lookups,
    counts and read-relationships. It skips both reading and writing for that
    call only. EACL's private cursor-codec memo remains enabled: it avoids
    decoding tokens minted by this client and is a trust-preserving
    implementation detail, not completed-answer reuse.

    A configuration map may be supplied for tuning. Caller-supplied
    provider adapters are rejected because they never controlled either live
    Datomic store:
      :max-weight, :max-entry-weight, :max-entries, :ttl-ms  capacity bounds
      :namespace         namespaces keys in the client-private stores
      :kind-max-weight, :two-hit-kinds, :admission-entries   per-kind tuning
      :subproblem-cache   shared projection/denotation/answer cache limits,
                          including :enabled?, :projection-max-weight,
                          :denotation-max-weight, :answer-max-weight,
                          and :managed-proof-max-atoms. Cache misses never
                          wait for or throttle other cache computations.
      :checkpoints       bounded revision checkpoints
      :remember-answers  false | true (default) | :on-repeat — whether a
                         finished answer is kept so an identical later check
                         skips evaluation. :on-repeat retains it only after the
                         same check has demonstrated reuse.

    Cursor continuation state is kept in a separate bounded private store.
    A missing optimization deterministically replays the authenticated prefix
    on the already-selected immutable snapshot; it never changes snapshots or
    restarts the public walk.

    There is no cross-process cache coordinator. Supported EACL writers
    atomically update every affected ordered relation generation in the same
    transaction as the authorization change. Bypassing those writers can leave
    proof-backed entries stale and requires quiescence, data repair, and
    expire-cache! on every affected client. Reset, restore, or source-history
    replacement also requires lifecycle expiry.

  - :security-key / :security-keyring / :security-kid —
    AES-GCM page-token key material. Default: a random per-client key, meaning
    page tokens do not survive restarts and are not portable across clients;
    supply stable key material in production. The :page-token-* names are
    accepted as non-mixable aliases.
  - :cursor-ttl-seconds — optional positive page-token expiry. Omitted means
    cursors do not expire by age.
    :page-token-ttl-seconds is a non-mixable alias.
  - :zed-token-key / :zed-token-keyring / :zed-token-kid — HMAC key material
    for authenticated Zed tokens. When omitted, purpose-specific signing keys
    are derived from the security keyring. Supply a stable shared keyring for
    frontend round trips across restarts or multiple backend instances.
  - :consistency-sync-timeout-ms — positive maximum wait for a targeted
    Datomic revision. Defaults to 30000.
  - :execution-timeout-ms — finite end-to-end authorization timeout. Defaults
    to 30000; a positive request :timeout-ms overrides it.
  - :cache-attempt — finite evaluation reserve and local CAS-publication
    attempt bound. Cache work may remove evaluator commands but
    cannot enlarge request demand. Remote provider/decode controls are not
    exposed because caller-supplied cache providers are rejected.
  - :service-admission — {:max-concurrent n :max-replays n :max-replays-per-key n}, the
    service-edge bulkhead for routed enumerations (slots are held for the full
    synchronous call chain) and the replay ledger for cursor replays; omitted
    means no bulkhead. Rejections are :eacl.service/admission-rejected and
    :eacl.service/replay-rejected.
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
           object-id->lookup-ref
           object-id->ident
           cache
           security-key
           security-keyring
           security-kid
           cursor-ttl-seconds
           page-token-key
           page-token-keyring
           page-token-kid
           page-token-ttl-seconds
           zed-token-key
           zed-token-keyring
           zed-token-kid
           token-ttl-seconds
           source-lifecycle
           adapter-fingerprint
           adapter-deterministic?
           consistency-sync-timeout-ms
           execution-timeout-ms
           cache-attempt
           recursive-traversal-limits
           permission-tree-limits
           service-admission
           auto-migrate-v6]}]
  (when-let [unknown-keys (seq (remove known-client-opt-keys (keys config-opts)))]
    (throw (ex-info (str "EACL Config Error: unknown make-client option(s) " (pr-str (vec unknown-keys))
                         ". Known options: " (pr-str (vec (sort known-client-opt-keys))) ".")
             {:type :eacl/invalid-config
              :unknown-keys (vec unknown-keys)
              :known-keys known-client-opt-keys})))
  (let [canonical-keys
        (filterv #(contains? config-opts %) canonical-security-opt-keys)
        alias-keys
        (filterv #(contains? config-opts %) page-token-alias-opt-keys)]
    (when (and (seq canonical-keys) (seq alias-keys))
      (throw
       (ex-info
        "EACL Config Error: do not mix :security-*/:cursor-ttl-seconds options with their :page-token-* aliases."
        {:type :eacl/invalid-config
         :conflicting-keys (into canonical-keys alias-keys)}))))
  (when (and (contains? config-opts :object-id->lookup-ref)
             (contains? config-opts :object-id->ident))
    (throw
     (ex-info
      "EACL Config Error: supply only :object-id->lookup-ref, not its :object-id->ident alias too."
      {:type :eacl/invalid-config
       :conflicting-keys [:object-id->lookup-ref :object-id->ident]})))
  (when (and (contains? config-opts :security-key)
             (contains? config-opts :security-keyring))
    (throw (ex-info "EACL Config Error: supply only one of :security-key or :security-keyring."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:security-key :security-keyring]})))
  (when (and (contains? config-opts :page-token-key)
             (contains? config-opts :page-token-keyring))
    (throw (ex-info "EACL Config Error: supply only one page-token key alias."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:page-token-key
                                        :page-token-keyring]})))
  (when (or (contains? config-opts :cursor-ttl-seconds)
            (contains? config-opts :page-token-ttl-seconds))
    (validate-cursor-ttl!
     (if (contains? config-opts :cursor-ttl-seconds)
       cursor-ttl-seconds
       page-token-ttl-seconds)))
  (let [lookup-ref-fn (cond
                        (contains? config-opts :object-id->lookup-ref)
                        object-id->lookup-ref

                        (contains? config-opts :object-id->ident)
                        object-id->ident

                        :else
                        (fn [obj-id] [:eacl/id obj-id]))]
    (when-not (fn? lookup-ref-fn)
      (throw (ex-info "EACL Config Error: :object-id->lookup-ref must be a fn that coerces a Spice Object ID to a Datomic ident resolvable by d/entid."
                      {:type :eacl/invalid-config
                       :key :object-id->lookup-ref}))))
  (when (and (contains? config-opts :zed-token-key)
             (contains? config-opts :zed-token-keyring))
    (throw (ex-info "EACL Config Error: supply only one of :zed-token-key or :zed-token-keyring."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:zed-token-key :zed-token-keyring]})))
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
  (when source-lifecycle
    (try
      (causal-token/validate-source-lifecycle! source-lifecycle)
      (catch Exception error
        (throw
         (ex-info
          "EACL Config Error: :source-lifecycle must be bounded portable canonical data."
          {:type :eacl/invalid-config
           :key :source-lifecycle
           :value source-lifecycle}
          error)))))
  (when (and (contains? config-opts :execution-timeout-ms)
             (not (and (integer? execution-timeout-ms)
                       (pos? execution-timeout-ms)
                       (<= execution-timeout-ms
                           execution/maximum-execution-timeout-ms))))
    (throw
     (ex-info
      "EACL Config Error: :execution-timeout-ms must be a positive integer within the supported range."
      {:type :eacl/invalid-config
       :key :execution-timeout-ms
       :value execution-timeout-ms
       :maximum-timeout-ms execution/maximum-execution-timeout-ms})))
  (execution/normalize-cache-attempt cache-attempt)
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
  (let [object-id->ident (cond
                           (contains? config-opts :object-id->lookup-ref)
                           object-id->lookup-ref

                           (contains? config-opts :object-id->ident)
                           object-id->ident

                           :else
                           (fn [obj-id] [:eacl/id obj-id]))
        page-token-key (if (contains? config-opts :security-key)
                         security-key
                         page-token-key)
        page-token-keyring (if (contains? config-opts :security-keyring)
                             security-keyring
                             page-token-keyring)
        page-token-kid (if (contains? config-opts :security-kid)
                         security-kid
                         page-token-kid)
        page-token-ttl-seconds
        (if (contains? config-opts :cursor-ttl-seconds)
          cursor-ttl-seconds
          page-token-ttl-seconds)
        source-lifecycle (or source-lifecycle (str (UUID/randomUUID)))
        source-lifecycle-state (atom source-lifecycle)
        codec-instance-id (str (UUID/randomUUID))
        initial-db         (d/db conn)
        database-id       (impl.indexed/database-id initial-db)
        diagnostic-schema-version
        (atom (impl.indexed/schema-version initial-db))
        cache-config       (normalize-cache-config cache)
        timeout-ms         (if (contains? config-opts
                                          :consistency-sync-timeout-ms)
                             consistency-sync-timeout-ms
                             default-consistency-sync-timeout-ms)
        custom-codec?
        (boolean
         (or entid->object-id
             (contains? config-opts :object-id->lookup-ref)
             (contains? config-opts :object-id->ident)))
        entid->object-id   (or entid->object-id
                               (fn [db eid] (:eacl/id (d/entity db eid))))
        object-id->entid   (fn [db object-id]
                             (d/entid db (object-id->ident object-id)))
        adapter-deterministic?
        (if custom-codec?
          (and (some? adapter-fingerprint)
               (true? adapter-deterministic?))
          true)
        current-kid        (or page-token-kid :current)
        _                  (validate-token-key-id! :security-kid current-kid)
        configured-keyring page-token-keyring
        keyring            (if configured-keyring
                             (normalize-token-keyring
                              :security-keyring
                              configured-keyring)
                             {current-kid (if page-token-key
                                            (normalize-token-key page-token-key)
                                            (do
                                              (secure/warn-defaulted-token-key!)
                                              (random-bytes 32)))})
        _                  (when-not (get keyring current-kid)
                             (throw (ex-info "Security key id is not present in the keyring."
                                             {:type :eacl/invalid-config
                                              :key :security-kid
                                              :security-kid current-kid
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
                                causal-token/default-token-ttl-seconds)}
        opts               {:object-id->ident object-id->ident
                            :execution-timeout-ms
                            (or execution-timeout-ms
                                execution/default-execution-timeout-ms)
                            :cache-attempt
                            (execution/normalize-cache-attempt cache-attempt)
                            :diagnostic-schema-version
                            diagnostic-schema-version
                            :derived-schema-caches (atom {})
                            :database-id database-id
                            :source-lifecycle source-lifecycle
                            :source-lifecycle-state source-lifecycle-state
                            :backend-capabilities datomic-backend/capabilities
                            :backend-adapter-fn
                            (fn [db]
                             (datomic-backend/snapshot-adapter
                               db
                               {:entid->object-id entid->object-id
                                ;; The adapter's :object-id->internal must
                                ;; use the client's id codec: the engine
                                ;; hands it internal eids (numbers, passed
                                ;; through) but expand-permission-tree hands
                                ;; it the external root id, which a custom
                                ;; :object-id->lookup-ref/:object-id->ident
                                ;; must resolve exactly like every other
                                ;; operation does.
                                :object-eid-fn
                                (fn [db object-id]
                                  (cond
                                    (nil? object-id) nil
                                    (number? object-id) object-id
                                    :else (object-id->entid db object-id)))
                                :conn conn
                                :database-id database-id
                                :source-lifecycle source-lifecycle
                                :source-lifecycle-state source-lifecycle-state
                                :adapter-fingerprint
                                (or adapter-fingerprint
                                    {:backend :datomic
                                     :adapter-version backend/adapter-version
                                     :recursive-traversal-limits
                                     recursive-traversal-limits
                                     :codec
                                     (if custom-codec?
                                       [:custom-unfingerprinted codec-instance-id]
                                       :eacl-id-immutable-v1)})
                                :adapter-deterministic?
                                adapter-deterministic?}))
                            ;; Continuation state and native completed answers
                            ;; stay in separate client-private stores. (The write-only
                            ;; :shared-cache-store/:lookup-cache-store options
                            ;; were deleted by trusted-surface-hygiene 11.1.)
                            :continuation-cache-store
                            (:continuation-store cache-config)
                            :lookup-cache-ttl-ms (:ttl-ms cache-config)
                            :cache-namespace (:namespace cache-config)
                            :current-cache-store
                            (when (:enabled? cache-config)
                              (shared-cache/current-cache
                               {:max-entries
                                (:native-max-entries cache-config)
                                :admit-on-repeat?
                                (:native-admit-on-repeat? cache-config)
                                :subproblem-cache
                                (:native-subproblem-cache cache-config)}))
                            :cache-remember-answers? (:remember-answers? cache-config)
                            :managed-cache-enabled?
                            (and
                             (:remember-answers? cache-config)
                             adapter-deterministic?)
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
                            :decision-kernel production-kernel/default-selection
                            :token-ttl-seconds
                            (or token-ttl-seconds
                                causal-token/default-token-ttl-seconds)
                            :consistency-sync-timeout-ms timeout-ms
                            ;; merged, so a partial override cannot silently
                            ;; disable the limits it does not mention
                            :recursive-traversal-limits
                            (merge impl.indexed/default-recursive-traversal-limits
                                   recursive-traversal-limits)
                            :permission-tree-limits
                            (permission-tree/normalize-limits
                             permission-tree-limits)
                            ;; The service-edge bulkhead and replay ledger
                            ;; (bounded-physical-execution): nil leaves the
                            ;; routed engine unguarded, a map installs it.
                            :service-admission
                            (some-> (physical/normalize-service-admission
                                     service-admission)
                                    physical/make-service-admission)}
        ;; The stable engine runs only on a qualified topology; the adapter's
        ;; declared execution profile is checked once here.
        _ (physical/require-qualified-topology!
           ((:backend-adapter-fn opts) (d/db conn)))]
    (->Spiceomic conn opts)))

(defn current-zed-token
  "Returns a token for the client's currently observed Datomic basis.

  This does not call d/sync."
  [client]
  (when-not (instance? Spiceomic client)
    (throw (ex-info "current-zed-token requires an EACL Datomic client."
                    {:type :eacl/invalid-client})))
  (let [db (d/db (:conn client))
        opts (:opts client)]
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
    (when-not checkpoints
      (throw (ex-info "Revision checkpoints are disabled for this EACL client."
                      {:type :eacl/checkpoints-disabled})))
    (revision/observe! checkpoints basis-t)
    (let [selected-t
          (revision/revision-at-least-seconds-ago
           checkpoints seconds-ago basis-t)]
      (response-token (d/as-of db selected-t) opts))))
