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

(defn- canonical-comparator
  [left right]
  (compare (pr-str left) (pr-str right)))

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
              (boolean? value)
              (string? value)
              (keyword? value))
          1

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
  ([value {:keys [maximum-size] :as limits
           :or {maximum-size default-maximum-size}}]
   (let [encoded (pr-str (canonicalize value limits))]
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
   (try
     (let [value (edn/read-string encoded)
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
       (throw e))
     (catch #?(:clj StackOverflowError :cljs :default) _
       (format-error! :malformed {}))
     #?(:clj
        (catch Exception _
          (format-error! :malformed {}))))))

(defn utf8-bytes
  [value]
  #?(:clj
     (vec (.getBytes ^String (str value) StandardCharsets/UTF_8))
     :cljs
     (vec (gcrypt/stringToUtf8ByteArray (str value)))))

(defn bytes->utf8
  [bytes]
  #?(:clj
     (String. (byte-array (map unchecked-byte bytes))
              StandardCharsets/UTF_8)
     :cljs
     (gcrypt/utf8ByteArrayToString (clj->js bytes))))

(defn random-bytes
  [n]
  (when-not (and (integer? n) (pos? n))
    (format-error! :invalid-random-size {:size n}))
  #?(:clj
     (let [bytes (byte-array n)]
       (.nextBytes (SecureRandom.) bytes)
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
  (let [key (normalize-key key)
        message (if (string? message) (utf8-bytes message) (vec message))]
    #?(:clj
       (let [mac (Mac/getInstance "HmacSHA256")
             key-bytes (byte-array (map unchecked-byte key))
             message-bytes (byte-array (map unchecked-byte message))]
         (.init mac (SecretKeySpec. key-bytes "HmacSHA256"))
         (mapv #(bit-and (int %) 255) (.doFinal mac message-bytes)))
       :cljs
       (vec
        (.getHmac
         (goog.crypt.Hmac. (goog.crypt.Sha256.) (clj->js key) 64)
         (clj->js message))))))

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
      (byte-array (map unchecked-byte bytes)))
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
