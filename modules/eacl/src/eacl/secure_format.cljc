(ns eacl.secure-format
  "Portable bounded canonical serialization and domain-separated HMAC formats.

  This service is synchronous in CLJ and CLJS. It deliberately provides
  authenticity rather than pretending that base64 is encryption; adapters may
  layer authenticated encryption over the resulting payload when their runtime
  supports a compatible API."
  (:require [#?(:clj clojure.edn :cljs cljs.reader) :as edn]
            [clojure.string :as str]
            #?@(:cljs [[goog.crypt :as gcrypt]
                       [goog.crypt.Hmac]
                       [goog.crypt.Sha256]]))
  #?(:clj
     (:import [java.nio.charset StandardCharsets]
              [java.security MessageDigest SecureRandom]
              [java.util Base64]
              [javax.crypto Mac]
              [javax.crypto.spec SecretKeySpec])))

(def canonical-version 1)
(def default-maximum-size 65536)
(def default-maximum-depth 32)
(def default-maximum-entries 16384)
(def maximum-safe-integer 9007199254740991)
(def minimum-safe-integer (- maximum-safe-integer))
(def ^:private hmac-domain "eacl/secure-format/key/v1")

(defn- format-error!
  [reason data]
  (throw (ex-info "Invalid EACL secure format."
                  (merge {:type :eacl.format/invalid
                          :eacl/error :eacl.format/invalid
                          :reason reason}
                         data))))

(defn- portable-integer?
  [value]
  (and (integer? value)
       (<= minimum-safe-integer value maximum-safe-integer)))

(defn- string-code-unit
  [character]
  #?(:clj (int character)
     :cljs (.charCodeAt character 0)))

(defn- string-code-unit-at
  [value index]
  #?(:clj (int (.charAt ^String value index))
     :cljs (.charCodeAt value index)))

(defn- well-formed-unicode?
  [value]
  (loop [index 0]
    (if (= index (count value))
      true
      (let [code (string-code-unit-at value index)]
        (cond
          (<= 0xD800 code 0xDBFF)
          (and (< (inc index) (count value))
               (<= 0xDC00
                   (string-code-unit-at value (inc index))
                   0xDFFF)
               (recur (+ index 2)))

          (<= 0xDC00 code 0xDFFF)
          false

          :else
          (recur (inc index)))))))

(defn- hidden-reader-input?
  [value]
  (loop [index 0
         in-string? false
         escaped? false]
    (if (= index (count value))
      false
      (let [code (string-code-unit-at value index)]
        (if in-string?
          (cond
            escaped?
            (recur (inc index) true false)

            (= 92 code)
            (recur (inc index) true true)

            (= 34 code)
            (recur (inc index) false false)

            :else
            (recur (inc index) true false))
          (cond
            (= 34 code)
            (recur (inc index) true false)

            (= 59 code)
            true

            (and (= 35 code)
                 (< (inc index) (count value))
                 (= 95 (string-code-unit-at value (inc index))))
            true

            :else
            (recur (inc index) false false)))))))

(defn- four-digit-hex
  [code]
  (let [hex #?(:clj (Integer/toHexString code)
               :cljs (.toString code 16))]
    (str (apply str (repeat (- 4 (count hex)) "0")) hex)))

(defn- string-requires-escaping?
  [value]
  (loop [index 0]
    (if (= index (count value))
      false
      (let [code (string-code-unit-at value index)]
        (if (or (< code 32) (= code 34) (= code 92))
          true
          (recur (inc index)))))))

(defn- render-string
  [value]
  (if-not (string-requires-escaping? value)
    (str "\"" value "\"")
    (str
     "\""
     (apply
      str
      (map
       (fn [character]
         (let [code (string-code-unit character)]
           (case code
             8 "\\b"
             9 "\\t"
             10 "\\n"
             12 "\\f"
             13 "\\r"
             34 "\\\""
             92 "\\\\"
             (if (< code 32)
               (str "\\u" (four-digit-hex code))
               (str character)))))
       value))
     "\"")))

(declare portable-render)

(defn- render-map
  [value]
  (str
   "{"
   (str/join
    ", "
    (map
     (fn [[rendered-key v]]
       (str rendered-key " " (portable-render v)))
     (sort-by first
              (map (fn [[k v]] [(portable-render k) v]) value))))
   "}"))

(defn- portable-render
  [value]
  (cond
    (nil? value) "nil"
    (true? value) "true"
    (false? value) "false"
    (string? value) (render-string value)
    (keyword? value)
    (str ":" (when-let [keyword-namespace (namespace value)]
               (str keyword-namespace "/"))
         (name value))
    (integer? value) (str value)
    (map? value) (render-map value)
    (set? value)
    (str "#{" (str/join " " (sort (map portable-render value))) "}")
    (sequential? value)
    (str "[" (str/join " " (map portable-render value)) "]")))

(def ^:private ordinary-keyword-component
  ;; A deliberately conservative EDN subset. Fast-path only spellings whose
  ;; printed namespace/name split is self-evident; unusual legal keywords keep
  ;; the exact reader round-trip below.
  #"[A-Za-z_*!?$%&=<>.+-][A-Za-z0-9_*!?$%&=<>.+-]*")

(defn- ordinary-keyword?
  [value]
  (let [keyword-namespace (namespace value)]
    (and (re-matches ordinary-keyword-component (name value))
         (or (nil? keyword-namespace)
             (re-matches ordinary-keyword-component keyword-namespace)))))

(defn- unambiguous-keyword?
  [value]
  (and
   (well-formed-unicode? (name value))
   (or (nil? (namespace value))
       (well-formed-unicode? (namespace value)))
   (or
    (ordinary-keyword? value)
    (try
      (= value (edn/read-string (portable-render value)))
      (catch #?(:clj Exception :cljs :default) _
        false)))))

(defn- canonical-comparator
  [left right]
  (compare (portable-render left) (portable-render right)))

(declare validate-value)

(defn- validate-map
  [value depth limits]
  (reduce-kv
   (fn [entries k v]
     (+ entries
        (validate-value k (inc depth) limits)
        (validate-value v (inc depth) limits)))
   1
   value))

(defn- validate-value
  [value depth {:keys [maximum-depth maximum-entries]}]
  (when (> depth maximum-depth)
    (format-error! :too-deep {:maximum-depth maximum-depth}))
  (let [entries
        (cond
          (or (nil? value)
              (boolean? value))
          1

          (string? value)
          (if (well-formed-unicode? value)
            1
            (format-error! :invalid-unicode {}))

          (keyword? value)
          (if (unambiguous-keyword? value)
            1
            (format-error! :ambiguous-keyword
                           {:value (portable-render value)}))

          (integer? value)
          (if (portable-integer? value)
            1
            (format-error! :integer-out-of-range
                           {:value value
                            :minimum minimum-safe-integer
                            :maximum maximum-safe-integer}))

          (map? value)
          (validate-map value depth
                        {:maximum-depth maximum-depth
                         :maximum-entries maximum-entries})

          (set? value)
          (reduce
           (fn [n item]
             (+ n (validate-value item (inc depth)
                                  {:maximum-depth maximum-depth
                                   :maximum-entries maximum-entries})))
           1 value)

          (or (vector? value) (sequential? value))
          (reduce
           (fn [n item]
             (+ n (validate-value item (inc depth)
                                  {:maximum-depth maximum-depth
                                   :maximum-entries maximum-entries})))
           1 value)

          :else
          (format-error! :unsupported-value
                         {:value-type (str (type value))}))]
    (when (> entries maximum-entries)
      (format-error! :too-many-entries
                     {:maximum-entries maximum-entries}))
    entries))

(defn canonicalize
  "Validates and canonicalizes portable EDN without changing collection types."
  ([value]
   (canonicalize value {}))
  ([value {:keys [maximum-depth maximum-entries]
           :or {maximum-depth default-maximum-depth
                maximum-entries default-maximum-entries}}]
   (let [limits {:maximum-depth maximum-depth
                 :maximum-entries maximum-entries}]
     (validate-value value 0 limits)
     (letfn [(canonical [item]
               (cond
                 (map? item)
                 (into (sorted-map-by canonical-comparator)
                       (map (fn [[k v]]
                              [(canonical k) (canonical v)]))
                       item)

                 (set? item)
                 (into (sorted-set-by canonical-comparator)
                       (map canonical)
                       item)

                 (sequential? item)
                 (mapv canonical item)

                 :else item))]
       (canonical value)))))

(defn encode-canonical
  "Returns the canonical portable EDN representation after enforcing bounds."
  ([value]
   (encode-canonical value {}))
  ([value {:keys [maximum-size maximum-depth maximum-entries] :as limits
           :or {maximum-size default-maximum-size}}]
   ;; `portable-render` already imposes the canonical map/set order and renders
   ;; every sequential value as a vector. Building a second recursively sorted
   ;; copy first repeated every traversal (and repeatedly rendered comparator
   ;; keys) without changing a byte of output. Validate once, then render the
   ;; original value directly.
   (validate-value value 0
                   {:maximum-depth (or maximum-depth default-maximum-depth)
                    :maximum-entries
                    (or maximum-entries default-maximum-entries)})
   (let [encoded (portable-render value)]
     (when (> (count encoded) maximum-size)
       (format-error! :too-large {:maximum-size maximum-size}))
     encoded)))

(defn decode-canonical
  "Reads bounded portable EDN and optionally enforces a top-level key allowlist."
  ([encoded]
   (decode-canonical encoded {}))
  ([encoded {:keys [maximum-size allowed-keys] :as limits
             :or {maximum-size default-maximum-size}}]
   (when-not (and (string? encoded)
                  (<= (count encoded) maximum-size))
     (format-error! :too-large {:maximum-size maximum-size}))
   (when (hidden-reader-input? encoded)
     (format-error! :malformed {}))
   (try
     (let [forms (edn/read-string (str "[" encoded "]"))
           _ (when-not (= 1 (count forms))
               (format-error! :malformed {}))
           value (first forms)
           canonical (canonicalize value limits)]
       (when (and allowed-keys
                  (or (not (map? canonical))
                      (not= (set (keys canonical)) (set allowed-keys))))
         (format-error! :unknown-fields
                        {:allowed-keys (set allowed-keys)
                         :actual-keys
                         (when (map? canonical)
                           (set (keys canonical)))}))
       canonical)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
      (if (= :eacl.format/invalid (:type (ex-data e)))
        (throw e)
        (format-error! :malformed {})))
     (catch #?(:clj StackOverflowError :cljs :default) _
       (format-error! :malformed {}))
     #?(:clj
        (catch Exception _
          (format-error! :malformed {}))))))

(defn utf8-bytes
  [value]
  (let [value (str value)]
    (when-not (well-formed-unicode? value)
      (format-error! :invalid-unicode {}))
    #?(:clj
       (mapv #(bit-and (int %) 255)
             (.getBytes ^String value StandardCharsets/UTF_8))
       :cljs
       (vec (gcrypt/stringToUtf8ByteArray value)))))

(defn bytes->utf8
  [bytes]
  (let [bytes (vec bytes)]
    (when-not (every? #(and (integer? %) (<= -128 % 255)) bytes)
      (format-error! :malformed-utf8 {}))
    (let [unsigned-bytes (mapv #(bit-and (int %) 255) bytes)
          decoded
          #?(:clj
             (String. (byte-array (map unchecked-byte unsigned-bytes))
                      StandardCharsets/UTF_8)
             :cljs
             (gcrypt/utf8ByteArrayToString (clj->js unsigned-bytes)))]
      (when-not (= unsigned-bytes (utf8-bytes decoded))
        (format-error! :malformed-utf8 {}))
      decoded)))

#?(:clj
   (defonce ^:private ^SecureRandom secure-random
     ;; SecureRandom is thread-safe. Reusing the initialized provider avoids a
     ;; provider lookup and reseed check for each boundary cursor while every
     ;; call still draws fresh cryptographic randomness.
     (SecureRandom.)))

(defn random-bytes
  [n]
  (when-not (and (integer? n) (pos? n))
    (format-error! :invalid-random-size {:size n}))
  #?(:clj
     (let [bytes (byte-array n)]
       (.nextBytes secure-random bytes)
       (mapv #(bit-and (int %) 255) bytes))
     :cljs
     (let [crypto (or (.-crypto js/globalThis)
                      (.-webcrypto
                       (when (exists? js/require)
                         (js/require "crypto"))))
           bytes (js/Uint8Array. n)]
       (when-not crypto
         (format-error! :secure-random-unavailable {}))
       (.getRandomValues crypto bytes)
       (vec bytes))))

(defonce default-root-key
  (random-bytes 32))

(defonce ^:private warned-defaulted-token-key? (atom false))

(defn warn-defaulted-token-key!
  "One-time startup warning when a client is constructed without explicit
  token key material: defaulted keys are process-local and random, so
  cursors and tokens do not survive restarts and are not portable across
  peers or load-balanced nodes (page 2 on another node fails with a
  typed invalid-cursor error). Supply :security-key/:security-keyring
  (portable clients) or :page-token-key/:page-token-keyring (Datomic),
  and rotate an authenticated-encryption key before 2^32 cursor
  encryptions."
  []
  (when (compare-and-set! warned-defaulted-token-key? false true)
    #?(:clj (binding [*out* *err*]
              (println
               "EACL: no token key material configured; using a process-local random key. Cursors/tokens will not survive restarts or load balancing. Set :security-key(ring) or :page-token-key(ring), and rotate each key before 2^32 cursor encryptions."))
       :cljs (js/console.warn
              "EACL: no token key material configured; using a process-local random key. Cursors/tokens will not survive restarts or load balancing. Set :security-key(ring) or :page-token-key(ring), and rotate each key before 2^32 cursor encryptions."))))

(defn normalize-key
  [key]
  (let [bytes
        (cond
          (string? key) (utf8-bytes key)
          #?(:clj (bytes? key) :cljs false)
          #?(:clj (mapv #(bit-and (int %) 255) key) :cljs nil)
          (sequential? key) (mapv int key)
          #?(:cljs (instance? js/Uint8Array key) :clj false)
          #?(:cljs (vec key) :clj nil)
          :else (format-error! :invalid-key {}))]
    (when (< (count bytes) 32)
      (format-error! :weak-key {:minimum-bytes 32}))
    (when-not (every? #(<= 0 % 255) bytes)
      (format-error! :invalid-key-byte {}))
    bytes))

(defn hmac-sha-256
  [key message]
  (let [key (normalize-key key)]
    #?(:clj
       (let [mac (Mac/getInstance "HmacSHA256")
             key-bytes (byte-array (map unchecked-byte key))
             message-bytes
             (cond
               (string? message)
               (let [message ^String message]
                 (when-not (well-formed-unicode? message)
                   (format-error! :invalid-unicode {}))
                 (.getBytes message StandardCharsets/UTF_8))

               (bytes? message)
               message

               :else
               (byte-array (map unchecked-byte message)))]
         (.init mac (SecretKeySpec. key-bytes "HmacSHA256"))
         (mapv #(bit-and (int %) 255) (.doFinal mac message-bytes)))
       :cljs
       (let [message (if (string? message)
                       (utf8-bytes message)
                       (vec message))]
         (vec
          (.getHmac
           (goog.crypt.Hmac. (goog.crypt.Sha256.) (clj->js key) 64)
           (clj->js message)))))))

(defn derive-key
  "Derives a distinct 256-bit key for one authenticated format domain."
  [root-key domain]
  (when-not (and (string? domain) (not-empty domain))
    (format-error! :invalid-domain {:domain domain}))
  (hmac-sha-256 root-key (str hmac-domain "\n" domain)))

(defn secure-equal?
  "Length-aware constant-work comparison for authentication tags."
  [left right]
  (let [left (vec left)
        right (vec right)
        n (max (count left) (count right))
        difference
        (loop [index 0
               difference (bit-xor (count left) (count right))]
          (if (= index n)
            difference
            (recur
             (inc index)
             (bit-or difference
                     (bit-xor
                      (get left index 0)
                      (get right index 0))))))]
    (zero? difference)))

(defn b64url-encode
  [bytes]
  #?(:clj
     (.encodeToString
      (.withoutPadding (Base64/getUrlEncoder))
      (if (bytes? bytes)
        bytes
        (byte-array (map unchecked-byte bytes))))
     :cljs
     (let [binary (apply str (map #(js/String.fromCharCode %) bytes))
           encoded (.call (.-btoa js/globalThis) js/globalThis binary)]
       (-> encoded
           (str/replace "+" "-")
           (str/replace "/" "_")
           (str/replace #"=+$" "")))))

(defn b64url-decode
  [encoded]
  (try
    #?(:clj
       (mapv #(bit-and (int %) 255)
             (.decode (Base64/getUrlDecoder) ^String encoded))
       :cljs
       (let [padding (subs "====" 0 (mod (- 4 (mod (count encoded) 4)) 4))
             standard (-> encoded
                          (str/replace "-" "+")
                          (str/replace "_" "/")
                          (str padding))
             binary (.call (.-atob js/globalThis) js/globalThis standard)]
         (mapv #(.charCodeAt binary %) (range (count binary)))))
    (catch #?(:clj Exception :cljs :default) _
      (format-error! :malformed-base64 {}))))

(defn sha-256
  "Portable SHA-256 for non-secret, authenticated proof digests."
  [message]
  (let [message (if (string? message)
                  (utf8-bytes message)
                  (vec message))]
    #?(:clj
       (let [digest (MessageDigest/getInstance "SHA-256")]
         (mapv #(bit-and (int %) 255)
               (.digest digest
                        (byte-array
                         (map unchecked-byte message)))))
       :cljs
       (let [digest (goog.crypt.Sha256.)]
         (.update digest (clj->js message))
         (vec (.digest digest))))))

(defn canonical-digest
  "Domain-separated digest of bounded canonical portable data."
  [domain value]
  (when-not (and (string? domain) (not-empty domain))
    (format-error! :invalid-domain {:domain domain}))
  (b64url-encode
   (sha-256
    (str domain "\n" (encode-canonical value)))))

(defn canonical-records-digest
  "Domain-separated digest of an ordered, potentially large record sequence.

  Every record is independently canonicalized and bounded, then length-framed
  before it enters the incremental SHA-256 state. This avoids both ambiguous
  concatenation and the whole-proof size limit without ever truncating proof
  content. Record order is part of the digest contract; callers must sort
  semantically unordered inputs before calling this function."
  [domain records]
  (when-not (and (string? domain) (not-empty domain))
    (format-error! :invalid-domain {:domain domain}))
  (let [digest #?(:clj (MessageDigest/getInstance "SHA-256")
                  :cljs (goog.crypt.Sha256.))
        update-bytes!
        (fn [bytes]
          (let [length (count bytes)
                prefix [(bit-and (bit-shift-right length 24) 255)
                        (bit-and (bit-shift-right length 16) 255)
                        (bit-and (bit-shift-right length 8) 255)
                        (bit-and length 255)]]
            #?(:clj
               (do
                 (.update ^MessageDigest digest
                          (byte-array (map unchecked-byte prefix)))
                 (.update ^MessageDigest digest
                          (byte-array (map unchecked-byte bytes))))
               :cljs
               (do
                 (.update digest (clj->js prefix))
                 (.update digest (clj->js bytes))))))]
    (update-bytes! (utf8-bytes domain))
    (doseq [record records]
      (update-bytes! (utf8-bytes (encode-canonical record))))
    (b64url-encode
     #?(:clj
        (mapv #(bit-and (int %) 255)
              (.digest ^MessageDigest digest))
        :cljs
        (vec (.digest digest))))))

(defn signing-context
  [{:keys [current-kid keyring]} domain]
  (let [kid (or current-kid :default)
        keyring (or keyring {:default default-root-key})
        root-key (get keyring kid)]
    (when-not (and (or (keyword? kid)
                       (and (string? kid) (not-empty kid)))
                   root-key)
      (format-error! :unknown-key-id {:kid kid}))
    {:kid kid
     :key (derive-key root-key domain)
     :keyring keyring}))

(defn encode-authenticated
  [{:keys [domain prefix] :as options} payload]
  (when-not (and (string? prefix) (not-empty prefix))
    (format-error! :invalid-prefix {}))
  (let [{:keys [kid key]} (signing-context options domain)
        encoded-payload (b64url-encode
                         (utf8-bytes (encode-canonical payload options)))
        signed {:v canonical-version
                :kid kid
                :payload encoded-payload}
        tag (hmac-sha-256
             key
             (str domain "\n" (encode-canonical signed options)))
        envelope (assoc signed :tag (b64url-encode tag))]
    (str prefix
         (b64url-encode
          (utf8-bytes (encode-canonical envelope options))))))

(defn decode-authenticated
  [{:keys [domain prefix payload-keys maximum-size] :as options} token]
  (when-not (and (string? token)
                 (<= (count token)
                     (or maximum-size default-maximum-size))
                 (str/starts-with? token prefix))
    (format-error! :malformed-token {}))
  (let [envelope
        (decode-canonical
         (bytes->utf8
          (b64url-decode (subs token (count prefix))))
         (assoc options :allowed-keys #{:v :kid :payload :tag}))
        {:keys [v kid payload tag]} envelope
        root-key (get (or (:keyring options)
                          {:default default-root-key})
                      kid)]
    (when-not (and (= canonical-version v)
                   root-key
                   (string? payload)
                   (string? tag))
      (format-error! :authentication-failed {}))
    (let [key (derive-key root-key domain)
          expected (hmac-sha-256
                    key
                    (str domain "\n"
                         (encode-canonical
                          {:v v :kid kid :payload payload}
                          options)))
          supplied (b64url-decode tag)]
      (when-not (secure-equal? expected supplied)
        (format-error! :authentication-failed {}))
      (decode-canonical
       (bytes->utf8 (b64url-decode payload))
       (cond-> options
         payload-keys (assoc :allowed-keys payload-keys))))))
