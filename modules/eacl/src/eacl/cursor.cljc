(ns eacl.cursor
  "Authenticated-encryption portable cursor envelopes for CLJ and CLJS."
  (:require [clojure.string :as str]
            [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as lru]
            [eacl.secure-format :as secure]
            #?@(:cljs [[goog.crypt.Aes]]))
  #?(:clj
     (:import [java.nio.charset StandardCharsets]
              [java.util.concurrent ArrayBlockingQueue]
              [javax.crypto Cipher]
              [javax.crypto Mac]
              [javax.crypto.spec IvParameterSpec SecretKeySpec])))

(def cursor-version 5)
(def cursor-prefix "eacl_c5_")
(def cursor-domain "eacl/cursor/envelope/v5")
(def payload-keys #{:version :cursor :issued-at :expires-at})
(def ^:private nonce-size 12)
(def ^:private aes-block-size 16)
(def ^:private maximum-counter 4294967295)
(def ^:private encryption-key-domain "eacl/cursor/aead/encryption/v1")
(def ^:private authentication-key-domain
  "eacl/cursor/aead/authentication/v1")
#?(:clj (def ^:private maximum-idle-authenticators 8))

(def ^:dynamic *codec-work*
  "Optional atom populated with deterministic cursor-codec work counters.

  These counters are a non-timing regression boundary: tests can reject an
  additional whole-payload canonicalization, authentication pass, or Base64
  pass without depending on host load or JIT state."
  nil)

(defn- record-work!
  [field amount]
  (when *codec-work*
    (swap! *codec-work* update field (fnil + 0) amount)))

(defrecord CursorCodecCache
    [token-store reverse-token-store context-store key-context-store
     max-entries])

(defn- require-codec-cache!
  [cache]
  (when-not (and (instance? CursorCodecCache cache)
                 (every?
                  lru/store?
                  ((juxt :token-store
                         :reverse-token-store
                         :context-store
                         :key-context-store)
                   cache)))
    (throw (ex-info "Expected an EACL cursor codec cache."
                    {:type :eacl/invalid-config
                     :eacl/error :eacl/invalid-config})))
  cache)

(defn- storage-key
  [domain identity]
  (cache-key/domain-key domain [cursor-version identity]))

(defn- safe-lookup
  [store key]
  (try
    (lru/lookup! store key)
    (catch #?(:clj Throwable :cljs :default) _
      {:found? false :value nil})))

(defn- safe-put-if-absent!
  [store key value]
  (try
    (lru/put-if-absent! store key value)
    (catch #?(:clj Throwable :cljs :default) _
      false)))

(defn- safe-evict!
  [store key]
  (try
    (lru/evict! store key)
    (catch #?(:clj Throwable :cljs :default) _
      false)))

(defn codec-cache
  "Creates a bounded, client-private cache for cursor codecs.

  Tokens found here were authenticated when this exact client minted them.
  Expiring entries retain their authenticated expiry and are reused only while
  unexpired. Unknown tokens still pass through the authenticated decoder."
  ([]
   (codec-cache {}))
  ([{:keys [max-entries]
     :or {max-entries 1024}}]
   (->CursorCodecCache
    (lru/store max-entries)
    (lru/store max-entries)
    (lru/store max-entries)
    (lru/store max-entries)
    max-entries)))

(defn- memoized-value!
  [store domain key build hit-counter build-counter]
  (let [key' (storage-key domain key)
        hit (safe-lookup store key')]
    (if (:found? hit)
      (do
        (record-work! hit-counter 1)
        (:value hit))
      (let [_ (record-work! build-counter 1)
            ;; Builders run exactly once per caller and outside every atom
            ;; retry. Concurrent callers may compute the same value; an
            ;; already-published winner is preferred when it remains resident.
            candidate (build)]
        (if (safe-put-if-absent! store key' candidate)
          candidate
          (let [winner (safe-lookup store key')]
            (if (:found? winner)
              (:value winner)
              candidate)))))))

(defn memoized-context!
  "Returns one bounded client-private cursor construction artifact.

  `key` must contain every semantic input to `build`. The cache shares the
  codec cache's entry bound and lifecycle but remains separate from token
  lookup, so a context value can never authenticate an unknown token."
  [cache key build]
  (when-not (fn? build)
    (throw (ex-info "Cursor context builder must be a function."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config})))
  (if-not cache
    (build)
    (do
      (require-codec-cache! cache)
      (memoized-value!
       (:context-store cache)
       :cursor-construction-context
       key
       build
       :context-cache-hits
       :context-builds))))

(defn- memoized-key-context!
  [cache key build]
  (if-not cache
    (build)
    (do
      (require-codec-cache! cache)
      (memoized-value!
       (:key-context-store cache)
       :cursor-key-context
       key
       build
       :key-context-cache-hits
       :key-context-builds))))

(defn- now-seconds
  [options]
  (or (:now-seconds options)
      (quot (#?(:clj System/currentTimeMillis
                :cljs js/Date.now))
            1000)))

(defn- cursor-error!
  [reason data]
  (throw (ex-info "Invalid EACL cursor."
                  (merge {:type :eacl.pagination/invalid-cursor
                          :eacl/error :eacl.pagination/invalid-cursor
                          :reason reason}
                         data))))

(def ^:private format-option-keys
  "The only top-level cursor options consumed by the secure-format codec.
  Request execution, schema, proof, cache, and adapter state must not be
  copied into this hot-path options map."
  [:current-kid
   :keyring
   :maximum-size
   :maximum-depth
   :maximum-entries])

(defn- format-options
  [options]
  (merge (select-keys options format-option-keys)
         (:format-options options)))

(defn- aead-error!
  [reason data]
  (throw (ex-info "Invalid encrypted cursor."
                  (merge {:type :eacl.format/invalid
                          :eacl/error :eacl.format/invalid
                          :reason reason}
                         data))))

(defn- uint32-bytes
  [value]
  [(bit-and (bit-shift-right value 24) 255)
   (bit-and (bit-shift-right value 16) 255)
   (bit-and (bit-shift-right value 8) 255)
   (bit-and value 255)])

(defn- aes-block-encrypter
  [key]
  #?(:clj
     (let [cipher (Cipher/getInstance "AES/ECB/NoPadding")
           key-bytes (byte-array (map unchecked-byte key))]
       (.init cipher
              Cipher/ENCRYPT_MODE
              (SecretKeySpec. key-bytes "AES"))
       (fn [block]
         (mapv #(bit-and (int %) 255)
               (.doFinal
                cipher
                (byte-array (map unchecked-byte block))))))
     :cljs
     (let [cipher (goog.crypt.Aes. (clj->js key))]
       (fn [block]
         (vec (.encrypt cipher (clj->js block)))))))

(defn- ctr-transform
  [key nonce input]
  (when-not (= nonce-size (count nonce))
    (aead-error! :invalid-nonce {:nonce-size (count nonce)}))
  #?(:clj
     ;; Keep the JVM path in byte arrays from canonical UTF-8 through JCA and
     ;; Base64. The portable format still operates on the same unsigned bytes;
     ;; this only removes three whole-payload vector/array copies per cursor.
     (let [input-length (if (bytes? input) (alength ^bytes input) (count input))
           block-count
           (quot (+ input-length (dec aes-block-size)) aes-block-size)]
       (when (> block-count maximum-counter)
         (aead-error! :too-large {:maximum-blocks maximum-counter}))
       ;; The portable format defines AES-CTR as nonce || uint32(counter), with
       ;; the first counter equal to one. JCA implements that exact big-endian
       ;; counter progression in one native bulk operation.
       (let [cipher (Cipher/getInstance "AES/CTR/NoPadding")
             key-bytes (byte-array (map unchecked-byte key))
             iv-bytes
             (byte-array
              (map unchecked-byte (into nonce (uint32-bytes 1))))
             input-bytes
             (if (bytes? input)
               input
               (byte-array (map unchecked-byte input)))]
         (.init cipher
                Cipher/ENCRYPT_MODE
                (SecretKeySpec. key-bytes "AES")
                (IvParameterSpec. iv-bytes))
         (.doFinal cipher ^bytes input-bytes)))
     :cljs
     (let [input (vec input)
           block-count
           (quot (+ (count input) (dec aes-block-size)) aes-block-size)]
       (when (> block-count maximum-counter)
         (aead-error! :too-large {:maximum-blocks maximum-counter}))
       (let [encrypt-block (aes-block-encrypter key)]
         (loop [offset 0
                counter 1
                output (transient [])]
           (if (= offset (count input))
             (persistent! output)
             (let [stream
                   (encrypt-block (into nonce (uint32-bytes counter)))
                   remaining (- (count input) offset)
                   length (min aes-block-size remaining)
                   output'
                   (loop [index 0
                          result output]
                     (if (= index length)
                       result
                       (recur
                        (inc index)
                        (conj!
                         result
                         (bit-xor
                          (nth input (+ offset index))
                          (nth stream index))))))]
               (recur (+ offset length) (inc counter) output'))))))))

(defn- aead-keys
  [domain-key]
  {:encryption-key
   (secure/derive-key domain-key encryption-key-domain)
   :authentication-key
   (secure/derive-key domain-key authentication-key-domain)})

(defn- pooled-authenticator
  "Builds a thread-safe HMAC function for one already-derived cursor key.

  JCA `Mac` resets to its initialized state after `doFinal`. A fixed-capacity,
  nonblocking idle pool therefore avoids provider lookup, key conversion, and
  initialization on ordinary cursor traffic without retaining a peak
  concurrency burst. Excess instances are transient and discarded. The pool
  is an object pool owned by one bounded key-context LRU value, not a second
  cache policy."
  [authentication-key]
  #?(:clj
     (let [pool (ArrayBlockingQueue. maximum-idle-authenticators)
           key-spec
           (SecretKeySpec.
            (byte-array (map unchecked-byte authentication-key))
            "HmacSHA256")]
       (fn [^String message]
         (let [^Mac mac
               (or (.poll pool)
                   (doto (Mac/getInstance "HmacSHA256")
                     (.init key-spec)))
               result
               (.doFinal mac (.getBytes message StandardCharsets/UTF_8))]
           ;; Pool retention is best effort and must not invalidate a tag that
           ;; was already computed successfully. `offer` never blocks; a full
           ;; pool discards this burst-only instance.
           (try
             (.reset mac)
             (.offer pool mac)
             (catch Throwable _ nil))
           (mapv #(bit-and (int %) 255) result))))
     :cljs
     (fn [message]
       (secure/hmac-sha-256 authentication-key message))))

(defn- authentication-input
  [kid-segment nonce-segment ciphertext-segment]
  (str cursor-domain "\n"
       kid-segment "." nonce-segment "." ciphertext-segment))

(defn- encode-context
  "Resolves the stable key material and visible kid segment once per client.

  These values depend only on the configured key identity. They contain no
  nonce or payload state and remain inside the bounded client-private codec
  cache, whose lifecycle is rotated with signing configuration changes."
  [options format-options]
  (let [kid (or (:current-kid format-options) :default)
        keyring (or (:keyring format-options)
                    {:default secure/default-root-key})
        identity [kid (get keyring kid)]
        build
        (fn []
          (let [{domain-key :key :keys [kid]}
                (secure/signing-context format-options cursor-domain)
                {:keys [encryption-key authentication-key]}
                (aead-keys domain-key)
                authenticate (pooled-authenticator authentication-key)
                kid-segment
                (secure/b64url-encode
                 (secure/utf8-bytes
                  (secure/encode-canonical
                   kid
                   (assoc format-options :maximum-size 1024))))]
            {:encryption-key encryption-key
             :authenticate authenticate
             :kid-segment kid-segment}))]
    (if-let [cache (or (:cursor-codec-cache options)
                       (:cursor-construction-cache options))]
      (memoized-key-context!
       cache [:cursor-aead-encode-context 1 identity] build)
      (build))))

(defn- encode-aead
  [options payload]
  (let [format-options (format-options options)
        maximum-size
        (or (:maximum-size format-options)
            secure/default-maximum-size)
        {:keys [encryption-key authenticate kid-segment]}
        (encode-context options format-options)
        encoded-payload (secure/encode-canonical payload format-options)
        payload-bytes
        #?(:clj (.getBytes ^String encoded-payload StandardCharsets/UTF_8)
           :cljs (secure/utf8-bytes encoded-payload))
        nonce (secure/random-bytes nonce-size)
        ciphertext (ctr-transform encryption-key nonce payload-bytes)
        nonce-segment (secure/b64url-encode nonce)
        ciphertext-segment (secure/b64url-encode ciphertext)
        associated-input
        (authentication-input
         kid-segment nonce-segment ciphertext-segment)
        tag (authenticate associated-input)
        token
        (str cursor-prefix
             kid-segment "."
             nonce-segment "."
             ciphertext-segment "."
             (secure/b64url-encode tag))]
    (when (> (count token) maximum-size)
      (aead-error! :too-large {:maximum-size maximum-size}))
    (record-work! :encode-calls 1)
    (record-work! :payload-canonical-passes 1)
    (record-work! :payload-input-bytes (count payload-bytes))
    (record-work! :encryption-passes 1)
    (record-work! :authentication-passes 1)
    (record-work! :authentication-input-bytes
                  ;; Every component is Base64URL or a fixed ASCII delimiter.
                  (count associated-input))
    (record-work! :base64-encode-passes 4)
    token))

(defn- decode-aead
  [options token]
  (let [format-options (format-options options)
        maximum-size
        (or (:maximum-size format-options)
            secure/default-maximum-size)]
    (when-not (and (string? token)
                   (<= (count token) maximum-size)
                   (str/starts-with? token cursor-prefix))
      (throw (ex-info "Invalid encrypted cursor."
                      {:type :eacl.format/invalid :eacl/error :eacl.format/invalid
                       :reason :malformed-token})))
    (let [segments
          (str/split
           (subs token (count cursor-prefix))
           #"\."
           -1)]
      (when-not (and (= 4 (count segments))
                     (every? not-empty segments))
        (throw (ex-info "Invalid encrypted cursor."
                        {:type :eacl.format/invalid :eacl/error :eacl.format/invalid
                         :reason :malformed-token})))
      (let [[kid-segment nonce-segment ciphertext-segment tag-segment]
            segments
            _ (when (> (count kid-segment) 1368)
                (throw (ex-info "Invalid encrypted cursor key id."
                                {:type :eacl.format/invalid :eacl/error :eacl.format/invalid
                                 :reason :malformed-token})))
            kid
            (secure/decode-canonical
             (secure/bytes->utf8
              (secure/b64url-decode kid-segment))
             {:maximum-size 1024})
            root-key
            (get
             (or (:keyring format-options)
                 {:default secure/default-root-key})
             kid)]
        (when-not root-key
          (throw (ex-info "Encrypted cursor authentication failed."
                          {:type :eacl.format/invalid :eacl/error :eacl.format/invalid
                           :reason :authentication-failed})))
        (let [domain-key (secure/derive-key root-key cursor-domain)
              {:keys [encryption-key authentication-key]}
              (aead-keys domain-key)
              associated-input
              (authentication-input
               kid-segment nonce-segment ciphertext-segment)
              expected
              (secure/hmac-sha-256
               authentication-key associated-input)
              supplied
              (secure/b64url-decode tag-segment)]
          (record-work! :decode-calls 1)
          (record-work! :authentication-passes 1)
          (record-work! :authentication-input-bytes
                        ;; Every component is Base64URL or a fixed ASCII delimiter.
                        (count associated-input))
          (when-not (secure/secure-equal? expected supplied)
            (throw
             (ex-info "Encrypted cursor authentication failed."
                      {:type :eacl.format/invalid :eacl/error :eacl.format/invalid
                       :reason :authentication-failed})))
          (let [nonce (secure/b64url-decode nonce-segment)
                ciphertext (secure/b64url-decode ciphertext-segment)
                payload-bytes
                (ctr-transform encryption-key nonce ciphertext)
                payload
                (secure/decode-canonical
                 (secure/bytes->utf8 payload-bytes)
                 (cond-> format-options
                   payload-keys
                   (assoc :allowed-keys payload-keys)))]
            (record-work! :base64-decode-passes 4)
            (record-work! :decryption-passes 1)
            (record-work! :payload-canonical-passes 1)
            (record-work! :payload-input-bytes (count payload-bytes))
            payload))))))

(defn- codec-identity
  [options]
  (let [{:keys [current-kid keyring]} (format-options options)
        kid (or current-kid :default)
        keyring (or keyring {:default secure/default-root-key})]
    ;; TTL is part of the issuance policy. Keeping it in the private identity
    ;; prevents a token minted under one policy from satisfying another
    ;; policy's encode lookup, while authenticated decode remains compatible
    ;; with retained keys and reads expiry from the protected payload.
    [kid (get keyring kid) (:cursor-ttl-seconds options)]))

(defn- memoizable-cache
  [{:keys [cursor-codec-cache]}]
  (when cursor-codec-cache
    (require-codec-cache! cursor-codec-cache)))

(defn- token-storage-key
  [identity token]
  (storage-key :cursor-token [identity token]))

(defn- reverse-token-storage-key
  [identity cursor]
  (storage-key :cursor-reverse-token [identity cursor]))

(defn- evict-token-entry!
  [cache identity token entry]
  (safe-evict!
   (:token-store cache)
   (token-storage-key identity token))
  (when (map? (:cursor entry))
    (let [reverse-key
          (reverse-token-storage-key identity (:cursor entry))
          reverse-hit
          (safe-lookup (:reverse-token-store cache) reverse-key)]
      (when (and (:found? reverse-hit)
                 (= token (:value reverse-hit)))
        ;; A reverse entry is only an optimization. If a concurrent remint
        ;; races this conditional eviction, losing that newer mapping can
        ;; cause one extra mint but can never authorize or expose a cursor.
        (safe-evict! (:reverse-token-store cache) reverse-key))))
  nil)

(defn- live-token-entry?
  [entry identity cursor now]
  (and (map? entry)
       (= identity (:identity entry))
       (map? cursor)
       (= cursor (:cursor entry))
       (integer? (:issued-at entry))
       (or (nil? (:expires-at entry))
           (and (integer? (:expires-at entry))
                (number? now)
                (< now (:expires-at entry))))))

(defn- cached-token
  [cache identity cursor now]
  (let [reverse-key (reverse-token-storage-key identity cursor)
        reverse-hit (safe-lookup (:reverse-token-store cache) reverse-key)]
    (when (:found? reverse-hit)
      (let [token (:value reverse-hit)
            token-key (token-storage-key identity token)
            token-hit (safe-lookup (:token-store cache) token-key)]
        (if (and (:found? token-hit)
                 (live-token-entry?
                  (:value token-hit) identity cursor now))
          token
          (do
            ;; Independent LRU stores can evict in different orders. A
            ;; dangling reverse entry is merely a miss, never authority to
            ;; reuse a token. Expired entries are removed after their one
            ;; validation and must be authenticated again if decoded.
            (safe-evict! (:reverse-token-store cache) reverse-key)
            (when (:found? token-hit)
              (safe-evict! (:token-store cache) token-key))
            nil))))))

(defn- cached-cursor
  [cache identity token now]
  (let [token-key (token-storage-key identity token)
        token-hit (safe-lookup (:token-store cache) token-key)]
    (when (:found? token-hit)
      (let [entry (:value token-hit)]
        (if (live-token-entry? entry identity (:cursor entry) now)
          entry
          (do
            (evict-token-entry! cache identity token entry)
            nil))))))

(defn- remember-token!
  [cache identity cursor token issued-at expires-at]
  (safe-put-if-absent!
   (:token-store cache)
   (token-storage-key identity token)
   {:identity identity
    :cursor cursor
    :issued-at issued-at
    :expires-at expires-at})
  (safe-put-if-absent!
   (:reverse-token-store cache)
   (reverse-token-storage-key identity cursor)
   token)
  token)

(defn cursor->token
  "Encrypts and authenticates an internal cursor map as an opaque token."
  ([cursor]
   (cursor->token cursor nil))
  ([cursor {:keys [cursor-ttl-seconds] :as options}]
   (when cursor
     (when-not (map? cursor)
       (cursor-error! :malformed {}))
     (let [cache (memoizable-cache options)
           identity (when cache (codec-identity options))
           ;; A TTL-bearing hit needs one clock read to prove the cached token
           ;; remains inside the expiry protected by its payload. Preserve the
           ;; clock-free non-expiring hit path.
           current-time (when cursor-ttl-seconds (now-seconds options))]
       (or (when cache
             (cached-token cache identity cursor current-time))
           (let [issued-at (or current-time (now-seconds options))
                 expires-at (when cursor-ttl-seconds
                              (+ issued-at cursor-ttl-seconds))
                 token
                 (encode-aead
                  options
                  {:version cursor-version
                   :cursor cursor
                   :issued-at issued-at
                   :expires-at expires-at})]
             (if cache
               (remember-token!
                cache identity cursor token issued-at expires-at)
               token)))))))

(defn expired-cursor-error
  "Builds the public expired-cursor error for one deferred expiry decision.

  The relay pipeline threads the computed `:expired?` boolean into the
  verified continuation decision and throws this exact error only when the
  kernel rejects the token, keeping the public error identical to the
  historical decode-time throw."
  [expired-at]
  (ex-info "Invalid EACL cursor."
           {:type :eacl.pagination/expired-cursor
            :eacl/error :eacl.pagination/expired-cursor
            :reason :expired
            :expired-at expired-at}))

(defn token->authenticated-cursor
  "Authenticates and decodes a cursor without enforcing expiry.

  Returns `{:cursor m :authenticated? true :expired? bool :expired-at n}`.
  Authenticity and payload-shape failures still throw; the time-to-live
  check result is returned as data so the caller can thread the computed
  boolean into a verified continuation decision instead of deciding here."
  ([token]
   (token->authenticated-cursor token nil))
  ([token options]
   (if (nil? token)
     nil
     (let [cache (memoizable-cache options)
           identity (when cache (codec-identity options))]
       (or (when cache
             ;; The codec cache holds only tokens this client minted itself.
             ;; Expiry is protected by the token payload and retained in the
             ;; entry, so a hit skips crypto without skipping the TTL decision.
             (when-let [{:keys [cursor expires-at]}
                        (cached-cursor
                         cache
                         identity
                         token
                         (when (:cursor-ttl-seconds options)
                           (now-seconds options)))]
               {:cursor cursor
                :authenticated? true
                :expired? (boolean
                           (and expires-at
                                (>= (now-seconds options) expires-at)))
                :expired-at expires-at}))
           (let [payload
                 (try
                   (decode-aead options token)
                   (catch #?(:clj Exception :cljs :default) error
                     (cursor-error!
                      (or (:reason (ex-data error)) :undecodable)
                      {})))
                 {:keys [version cursor expires-at]} payload]
             (when-not (and (= cursor-version version)
                            (map? cursor)
                            (integer? (:issued-at payload))
                            (or (nil? expires-at)
                                (integer? expires-at)))
               (cursor-error! :undecodable {}))
             {:cursor cursor
              :authenticated? true
              :expired? (boolean
                         (and expires-at
                              (>= (now-seconds options) expires-at)))
              :expired-at expires-at}))))))

(defn token->cursor
  "Authenticates and decodes a cursor. Legacy maps and `eacl1_` tokens fail."
  ([token]
   (token->cursor token nil))
  ([token options]
   (when-let [decoded (token->authenticated-cursor token options)]
     (when (:expired? decoded)
       (cursor-error!
        :expired
        {:expired-at (:expired-at decoded)
         :type :eacl.pagination/expired-cursor
         :eacl/error
         :eacl.pagination/expired-cursor}))
     (:cursor decoded))))
