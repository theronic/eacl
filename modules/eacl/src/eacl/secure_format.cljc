(ns eacl.secure-format
  "Portable bounded canonical serialization and domain-separated HMAC formats.

  This service is synchronous in CLJ and CLJS. It deliberately provides
  authenticity rather than pretending that base64 is encryption; adapters may
  layer authenticated encryption over the resulting payload when their runtime
  supports a compatible API."
  (:require [#?(:clj clojure.edn :cljs cljs.tools.reader.edn) :as edn]
            [clojure.string :as str]
            [eacl.exact-integer :as exact-integer]
            [eacl.uuid :as uuid]
            [eacl.security.protocols :as keyrings]
            #?@(:cljs [[goog.crypt :as gcrypt]
                       [goog.crypt.Hmac]
                       [goog.crypt.Sha256]]))
  #?(:clj
     (:import [java.nio.charset StandardCharsets]
              [java.security MessageDigest SecureRandom]
              [java.util Base64]
              [javax.crypto Mac]
              [javax.crypto.spec SecretKeySpec])))

(def canonical-version 2)
(def default-maximum-size 65536)
(def default-maximum-depth 32)
(def default-maximum-entries 16384)
(def maximum-safe-integer exact-integer/maximum)
(def minimum-safe-integer exact-integer/minimum)
(def ^:private hmac-domain "eacl/secure-format/key/v1")

(defn- format-error!
  [reason data]
  (throw (ex-info "Invalid EACL secure format."
                  (merge {:type :eacl.format/invalid
                          :eacl/error :eacl.format/invalid
                          :reason reason}
                         data))))

(defn- string-code-unit
  [character]
  #?(:clj (int character)
     :cljs (.charCodeAt character 0)))

(defn- string-code-unit-at
  [value index]
  #?(:clj (int (.charAt ^String value index))
     :cljs (.charCodeAt value index)))

(defn- unicode-utf8-size
  "Counts UTF-8 bytes directly from UTF-16, rejecting unpaired surrogates.

  The JVM scan keeps the string, its length and every code unit in primitive
  locals: it runs over every string a token, cursor, digest or cache entry
  carries."
  [value]
  #?(:clj
     (let [^String text value
           length (.length text)]
       (loop [index 0 size 0]
         (if (== index length)
           size
           (let [code (int (.charAt text index))]
             (cond
               (< code 0x80) (recur (unchecked-inc index) (unchecked-inc size))
               (< code 0x800) (recur (unchecked-inc index) (unchecked-add size 2))
               (< code 0xD800) (recur (unchecked-inc index) (unchecked-add size 3))
               (<= code 0xDBFF)
               (when (< (unchecked-inc index) length)
                 (let [next-code (int (.charAt text (unchecked-inc index)))]
                   (when (and (<= 0xDC00 next-code) (<= next-code 0xDFFF))
                     (recur (unchecked-add index 2) (unchecked-add size 4)))))
               (<= code 0xDFFF) nil
               :else (recur (unchecked-inc index) (unchecked-add size 3)))))))
     :cljs
     (let [length (count value)]
       (loop [index 0 size 0]
         (if (= index length)
           size
           (let [code (long (string-code-unit-at value index))]
             (cond
               (< code 0x80) (recur (inc index) (inc size))
               (< code 0x800) (recur (inc index) (+ size 2))
               (< code 0xD800) (recur (inc index) (+ size 3))
               (<= code 0xDBFF)
               (when (< (inc index) length)
                 (let [next-code (long (string-code-unit-at value (inc index)))]
                   (when (and (<= 0xDC00 next-code) (<= next-code 0xDFFF))
                     (recur (+ index 2) (+ size 4)))))
               (<= code 0xDFFF) nil
               :else (recur (inc index) (+ size 3)))))))))

(defn- well-formed-unicode? [value]
  (some? (unicode-utf8-size value)))

(defn utf8-size
  "Returns the exact UTF-8 byte count without allocating a byte collection."
  [value]
  (or (unicode-utf8-size (str value))
      (format-error! :invalid-unicode {})))

(defn- hidden-reader-input?
  "True when `value` uses reader syntax that canonical text never contains
  outside a string: comments, metadata, character literals, and dispatch
  forms other than sets and canonical UUIDs. Comments, metadata, and discards
  hide input from the decoded value. A character literal such as `\\\"` also
  looks like a string delimiter to this scanner, so it must be rejected before
  the scanner and the host reader disagree about where strings are."
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

            ;; `;` comment, `^` metadata, `\` character literal.
            (or (= 59 code) (= 94 code) (= 92 code))
            true

            (= 35 code)
            (cond
              (and (< (inc index) (count value))
                   (= 123 (string-code-unit-at value (inc index))))
              (recur (+ index 2) false false)

              ;; Validate the original tagged spelling before either host
              ;; reader can lowercase text or interpret Unicode escapes.
              (and (<= (+ index 44) (count value))
                   (= "#uuid \"" (subs value index (+ index 7)))
                   (= 34 (string-code-unit-at value (+ index 43)))
                   (uuid/canonical-text? (subs value (+ index 7) (+ index 43))))
              (recur (+ index 44) false false)

              :else true)

            :else
            (recur (inc index) false false)))))))

(defn- four-digit-hex
  [code]
  (let [hex #?(:clj (Integer/toHexString code)
               :cljs (.toString code 16))]
    (str (apply str (repeat (- 4 (count hex)) "0")) hex)))

(defn- string-requires-escaping?
  [value]
  #?(:clj
     (let [^String text value
           length (.length text)]
       (loop [index 0]
         (if (== index length)
           false
           (let [code (int (.charAt text index))]
             (if (or (< code 32) (== code 34) (== code 92))
               true
               (recur (unchecked-inc index)))))))
     :cljs
     (loop [index 0]
       (if (= index (count value))
         false
         (let [code (string-code-unit-at value index)]
           (if (or (< code 32) (= code 34) (= code 92))
             true
             (recur (inc index))))))))

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

(defn- require-distinct-renderings!
  "Rejects equal adjacent renderings in a sorted collection. They come from
  distinct members that project to one portable value, such as a record and
  a map with the same fields. Rendering both would produce text that does not
  decode, and keeping one would silently drop the other."
  [reason rendering sorted]
  (loop [previous nil
         remaining (seq sorted)]
    (when remaining
      (let [current (rendering (first remaining))]
        (when (= previous current)
          (format-error! reason {}))
        (recur current (next remaining))))))

(defn- rendered-key
  [entry]
  (nth entry 0))

(defn- render-map
  [value]
  ;; Each entry is `[rendered-key value]`. Reading the key by index keeps the
  ;; sort and the duplicate check from building a sequence per comparison.
  (let [entries (sort-by rendered-key
                         (mapv (fn [[k v]] [(portable-render k) v]) value))]
    (require-distinct-renderings! :duplicate-key rendered-key entries)
    (str
     "{"
     (str/join
      ", "
      (mapv
       (fn [entry]
         (str (nth entry 0) " " (portable-render (nth entry 1))))
       entries))
     "}")))

(defn- render-set
  [value]
  (let [members (sort (map portable-render value))]
    (require-distinct-renderings! :duplicate-member identity members)
    (str "#{" (str/join " " members) "}")))

(defn- portable-render
  [value]
  (cond
    (nil? value) "nil"
    (true? value) "true"
    (false? value) "false"
    (string? value) (render-string value)
    (uuid/value? value) (str "#uuid \"" (uuid/text (uuid/capture value)) "\"")
    (keyword? value)
    ;; `:namespace/name`, the keyword's own printed form: the JVM caches
    ;; it on the keyword and ClojureScript keeps the qualified name.
    #?(:clj (str value)
       :cljs (str ":" (.-fqn ^Keyword value)))
    (integer? value) (str value)
    (map? value) (render-map value)
    (set? value) (render-set value)
    (sequential? value)
    (str "[" (str/join " " (map portable-render value)) "]")))

(defn- ordinary-keyword-code?
  "One code unit of the conservative EDN subset
  `[A-Za-z_*!?$%&=<>.+-][A-Za-z0-9_*!?$%&=<>.+-]*`: a letter or one of the
  thirteen symbol characters anywhere, a digit anywhere but first."
  [code first?]
  (or (and (<= 65 code) (<= code 90))
      (and (<= 97 code) (<= code 122))
      (and (not first?) (<= 48 code) (<= code 57))
      (case code
        (33 36 37 38 42 43 45 46 60 61 62 63 95) true
        false)))

(defn- ordinary-keyword-component?
  "A deliberately conservative EDN subset. Fast-path only spellings whose
  printed namespace/name split is self-evident; unusual legal keywords keep
  the exact reader round-trip in `unambiguous-keyword?`."
  [text]
  #?(:clj
     ;; The same rule as `ordinary-keyword-code?` on primitive code units:
     ;; every keyword of every validated value passes through here.
     (let [^String text text
           length (.length text)]
       (and (pos? length)
            (loop [index 0]
              (if (== index length)
                true
                (let [code (int (.charAt text index))]
                  (if (or (and (<= 65 code) (<= code 90))
                          (and (<= 97 code) (<= code 122))
                          (and (pos? index) (<= 48 code) (<= code 57))
                          (== code 33) (== code 36) (== code 37) (== code 38)
                          (== code 42) (== code 43) (== code 45) (== code 46)
                          (== code 60) (== code 61) (== code 62) (== code 63)
                          (== code 95))
                    (recur (unchecked-inc index))
                    false))))))
     :cljs
     (let [length (count text)]
       (and (pos? length)
            (loop [index 0]
              (cond
                (= index length) true
                (ordinary-keyword-code? (long (string-code-unit-at text index))
                                        (zero? index))
                (recur (inc index))
                :else false))))))

(defn- ordinary-keyword?
  [value]
  (let [keyword-namespace (namespace value)]
    (and (ordinary-keyword-component? (name value))
         (or (nil? keyword-namespace)
             (ordinary-keyword-component? keyword-namespace)))))

(defn- unambiguous-keyword?
  "An ordinary keyword's components are ASCII, hence well-formed, so it
  needs neither the Unicode scans nor the reader round-trip."
  [value]
  (or
   (ordinary-keyword? value)
   (and
    (well-formed-unicode? (name value))
    (or (nil? (namespace value))
        (well-formed-unicode? (namespace value)))
    (try
      (= value (edn/read-string (portable-render value)))
      (catch #?(:clj Exception :cljs :default) _
        false)))))

(defn- canonical-comparator
  "Orders values by their canonical rendering.

  Two keywords compare by the runtime's keyword string, which is
  byte-identical to their rendering `:namespace/name` and needs no
  allocation (the JVM caches it on the keyword; ClojureScript stores the
  fully qualified name). Every other operand pair renders. Canonical maps
  and sets carry this comparator, so every later lookup or equality check on
  them runs it once per key comparison."
  [left right]
  (if (and (keyword? left) (keyword? right))
    #?(:clj (compare (str left) (str right))
       :cljs (compare (.-fqn ^Keyword left) (.-fqn ^Keyword right)))
    (compare (portable-render left) (portable-render right))))

(defn- validate-value
  "Validates `value` and returns `seen` plus the number of values it holds.

  Every value, scalar or collection, is one entry. The running total is
  checked before each value is examined, so validation visits at most
  `maximum-entries` + 1 values: a huge collection, or an unbounded lazy
  sequence, fails with `:too-many-entries` instead of being walked first."
  [value depth seen {:keys [maximum-depth maximum-entries allow-uuids?]}]
  ;; The limits are read once; the walk below closes over them instead of
  ;; destructuring the same map at every value.
  (let [uuids? (not (false? allow-uuids?))]
    (letfn [(walk [value depth seen]
              (when (> depth maximum-depth)
                (format-error! :too-deep {:maximum-depth maximum-depth}))
              (let [seen (inc seen)]
                (when (> seen maximum-entries)
                  (format-error! :too-many-entries
                                 {:maximum-entries maximum-entries}))
                (cond
                  (or (nil? value)
                      (boolean? value))
                  seen

                  (string? value)
                  (if (well-formed-unicode? value)
                    seen
                    (format-error! :invalid-unicode {}))

                  (and uuids? (uuid/value? value))
                  seen

                  (keyword? value)
                  (if (unambiguous-keyword? value)
                    seen
                    (format-error! :ambiguous-keyword
                                   {:value (portable-render value)}))

                  (integer? value)
                  (if (exact-integer/exact? value)
                    seen
                    (format-error! :integer-out-of-range
                                   {:value value
                                    :minimum minimum-safe-integer
                                    :maximum maximum-safe-integer}))

                  (map? value)
                  (let [depth (inc depth)]
                    (reduce-kv
                     (fn [seen k v]
                       (walk v depth (walk k depth seen)))
                     seen
                     value))

                  (or (set? value) (sequential? value))
                  (let [depth (inc depth)]
                    (reduce
                     (fn [seen item]
                       (walk item depth seen))
                     seen
                     value))

                  :else
                  (format-error! :unsupported-value
                                 {:value-type (str (type value))}))))]
      (walk value depth seen))))

(defn canonicalize
  "Validates and canonicalizes portable EDN without changing collection types."
  ([value]
   (canonicalize value {}))
  ([value {:keys [maximum-depth maximum-entries allow-uuids?]
           :or {maximum-depth default-maximum-depth
                maximum-entries default-maximum-entries}}]
   (let [limits {:allow-uuids? allow-uuids?
                 :maximum-depth maximum-depth
                 :maximum-entries maximum-entries}]
     (validate-value value 0 0 limits)
     (letfn [(canonical [item]
               (cond
                 ;; Records project to plain maps. Distinct members that
                 ;; project to one value would merge in the sorted copy.
                 (map? item)
                 (let [result (into (sorted-map-by canonical-comparator)
                                    (map (fn [[k v]]
                                           [(canonical k) (canonical v)]))
                                    item)]
                   (when-not (= (count result) (count item))
                     (format-error! :duplicate-key {}))
                   result)

                 (set? item)
                 (let [result (into (sorted-set-by canonical-comparator)
                                    (map canonical)
                                    item)]
                   (when-not (= (count result) (count item))
                     (format-error! :duplicate-member {}))
                   result)

                 (sequential? item)
                 (mapv canonical item)

                 (uuid/value? item) (uuid/capture item)

                 :else item))]
       (canonical value)))))

(defn ^:no-doc capture-portable
  "Validates portable data and owns mutable UUID leaves. Already owned plain
  persistent collections can be shared; this does not promise sorted output."
  [value limits]
  (validate-value value 0 0 (merge {:maximum-depth default-maximum-depth
                                    :maximum-entries default-maximum-entries} limits))
  (letfn [(needs-capture? [item]
            (or (some? (meta item)) (record? item) (sorted? item)
                (and (sequential? item) (not (vector? item)))
                (and (uuid/value? item) (not (uuid/owned? item)))
                (cond
                  (map? item)
                  (reduce-kv (fn [_ k v]
                               (if (or (needs-capture? k) (needs-capture? v))
                                 (reduced true) false)) false item)
                  (coll? item) (boolean (some needs-capture? item))
                  :else false)))]
    (if (needs-capture? value) (canonicalize value limits) value)))

(defn encode-canonical
  "Returns the canonical portable EDN representation after enforcing bounds."
  ([value]
   (encode-canonical value {}))
  ([value {:keys [maximum-size maximum-depth maximum-entries allow-uuids?]
           :or {maximum-size default-maximum-size}}]
   ;; `portable-render` already imposes the canonical map/set order and renders
   ;; every sequential value as a vector. Building a second recursively sorted
   ;; copy first repeated every traversal (and repeatedly rendered comparator
   ;; keys) without changing a byte of output. Validate once, then render the
   ;; original value directly.
   (validate-value value 0 0
                   {:allow-uuids? allow-uuids?
                    :maximum-depth (or maximum-depth default-maximum-depth)
                    :maximum-entries
                    (or maximum-entries default-maximum-entries)})
   (let [encoded (portable-render value)]
     (when (> (count encoded) maximum-size)
       (format-error! :too-large {:maximum-size maximum-size}))
     encoded)))

(defn decode-canonical
  "Reads bounded portable EDN and optionally enforces a top-level key allowlist.

  Only the canonical spelling decodes: `encoded` must equal the
  `encode-canonical` text of the value it denotes. Text after the first form,
  extra whitespace or commas, another member order, and any other reader
  spelling of an equal value fail with `:noncanonical`. Each accepted value
  therefore has exactly one accepted string, so bytes that a caller compares,
  caches, or authenticates are the bytes that were decoded."
  ([encoded]
   (decode-canonical encoded {}))
  ([encoded {:keys [maximum-size allowed-keys] :as limits
             :or {maximum-size default-maximum-size}}]
   (when-not (and (string? encoded)
                  (<= (count encoded) maximum-size))
     (format-error! :too-large {:maximum-size maximum-size}))
   (try
     (let [value
           (if (and (= 44 (count encoded))
                    (= "#uuid \"" (subs encoded 0 7))
                    (= 34 (string-code-unit-at encoded 43)))
             ;; The complete scalar has fixed framing and a strict parser.
             ;; Avoid constructing a reader and temporary vector for it;
             ;; the same canonical domain and resource checks still apply.
             (or (uuid/parse-canonical (subs encoded 7 43))
                 (format-error! :malformed {}))
             (do
               (when (hidden-reader-input? encoded)
                 (format-error! :malformed {}))
               (let [forms (edn/read-string
                            {:readers {'uuid (fn [text]
                                               (or (uuid/parse-canonical text)
                                                   (format-error! :malformed {})))}
                             :default (fn [_tag _value] (format-error! :malformed {}))}
                            (str "[" encoded "]"))]
                 (when-not (= 1 (count forms))
                   (format-error! :malformed {}))
                 (first forms))))
           canonical (canonicalize value limits)]
       (when (and allowed-keys
                  (or (not (map? canonical))
                      (not= (set (keys canonical)) (set allowed-keys))))
         (format-error! :unknown-fields
                        {:allowed-keys (set allowed-keys)
                         :actual-keys
                         (when (map? canonical)
                           (set (keys canonical)))}))
       ;; The reader stops after the first complete form and accepts many
       ;; spellings of one value. Comparing against the canonical rendering
       ;; rejects trailing input and every alternative spelling at once.
       (when-not (= encoded (portable-render canonical))
         (format-error! :noncanonical {}))
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

#?(:clj
   (defn- unsigned-vector
     "The bytes of `array` as a vector of unsigned values, the portable byte
     representation of this namespace's public functions."
     [^bytes array]
     (let [length (alength array)]
       (loop [index 0
              result (transient [])]
         (if (== index length)
           (persistent! result)
           (recur (unchecked-inc index)
                  (conj! result (bit-and (long (aget array index)) 255))))))))

#?(:clj
   (defn- host-bytes
     "Byte values (signed or unsigned) as a JVM byte array. A byte array is
     returned as it is, so the JVM paths below convert a value at most once."
     ^bytes [values]
     (if (bytes? values)
       values
       (let [values (if (vector? values) values (vec values))
             length (count values)
             array (byte-array length)]
         (dotimes [index length]
           (aset array index (unchecked-byte (nth values index))))
         array))))

(defn utf8-bytes
  [value]
  (let [value (str value)]
    (when-not (well-formed-unicode? value)
      (format-error! :invalid-unicode {}))
    #?(:clj
       (unsigned-vector (.getBytes ^String value StandardCharsets/UTF_8))
       :cljs
       (vec (gcrypt/stringToUtf8ByteArray value)))))

#?(:clj
   (defn- checked-host-bytes
     "`host-bytes` for untrusted input: every member must be an integer in
     the signed or unsigned byte range."
     ^bytes [values]
     (if (bytes? values)
       values
       (let [values (if (vector? values) values (vec values))
             length (count values)
             array (byte-array length)]
         (dotimes [index length]
           (let [value (nth values index)]
             (when-not (and (integer? value) (<= -128 value 255))
               (format-error! :malformed-utf8 {}))
             (aset array index (unchecked-byte value))))
         array))))

(defn bytes->utf8
  [bytes]
  #?(:clj
     ;; The JVM decoder replaces every malformed sequence, encoded surrogates
     ;; included, with U+FFFD, so the decoded string re-encodes to the same
     ;; bytes exactly when the input was well-formed UTF-8.
     (let [array (checked-host-bytes bytes)
           decoded (String. array StandardCharsets/UTF_8)]
       (when-not (java.util.Arrays/equals
                  array (.getBytes decoded StandardCharsets/UTF_8))
         (format-error! :malformed-utf8 {}))
       decoded)
     :cljs
     (let [bytes (vec bytes)]
       (when-not (every? #(and (integer? %) (<= -128 % 255)) bytes)
         (format-error! :malformed-utf8 {}))
       (let [unsigned-bytes (mapv #(bit-and (int %) 255) bytes)
             decoded (gcrypt/utf8ByteArrayToString (clj->js unsigned-bytes))]
         (when-not (= unsigned-bytes (utf8-bytes decoded))
           (format-error! :malformed-utf8 {}))
         decoded))))

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
       (unsigned-vector bytes))
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
               "EACL: no token key material configured; using a process-local random key. Cursors/tokens will not survive restarts or load balancing. Set :security-key or :security-keyring, and rotate each key before 2^32 cursor encryptions."))
       :cljs (js/console.warn
              "EACL: no token key material configured; using a process-local random key. Cursors/tokens will not survive restarts or load balancing. Set :security-key or :security-keyring, and rotate each key before 2^32 cursor encryptions."))))

#?(:clj
   (defn- unsigned-byte-vector?
     "True for a vector whose every member is already a Long or Integer in
     the unsigned byte range: `normalize-key` would copy it to an equal
     vector."
     [value]
     (and (vector? value)
          (let [length (count value)]
            (loop [index 0]
              (if (== index length)
                true
                (let [member (nth value index)]
                  (if (and (or (instance? Long member) (instance? Integer member))
                           (<= 0 (long member) 255))
                    (recur (unchecked-inc index))
                    false))))))))

(defn normalize-key
  [key]
  (let [bytes
        (cond
          (string? key) (utf8-bytes key)
          #?@(:clj [(unsigned-byte-vector? key) key])
          #?(:clj (bytes? key) :cljs false)
          #?(:clj (unsigned-vector key) :cljs nil)
          (sequential? key) (mapv int key)
          #?(:cljs (instance? js/Uint8Array key) :clj false)
          #?(:cljs (vec key) :clj nil)
          :else (format-error! :invalid-key {}))]
    (when (< (count bytes) 32)
      (format-error! :weak-key {:minimum-bytes 32}))
    (when-not (every? #(<= 0 % 255) bytes)
      (format-error! :invalid-key-byte {}))
    bytes))

(defn- host-hmac-sha-256
  "`hmac-sha-256` with its tag in the host's own byte container (a byte array
  on the JVM, a vector in ClojureScript)."
  [key message]
  (let [key (normalize-key key)]
    #?(:clj
       (let [mac (Mac/getInstance "HmacSHA256")
             message-bytes
             (if (string? message)
               (let [message ^String message]
                 (when-not (well-formed-unicode? message)
                   (format-error! :invalid-unicode {}))
                 (.getBytes message StandardCharsets/UTF_8))
               (host-bytes message))]
         (.init mac (SecretKeySpec. (host-bytes key) "HmacSHA256"))
         (.doFinal mac ^bytes message-bytes))
       :cljs
       (let [message (if (string? message)
                       (utf8-bytes message)
                       (vec message))]
         (vec
          (.getHmac
           (goog.crypt.Hmac. (goog.crypt.Sha256.) (clj->js key) 64)
           (clj->js message)))))))

(defn hmac-sha-256
  [key message]
  #?(:clj (unsigned-vector (host-hmac-sha-256 key message))
     :cljs (host-hmac-sha-256 key message)))

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

#?(:clj
   (def ^:private ^java.util.Base64$Encoder b64url-encoder
     ;; Encoders and decoders are immutable and thread-safe.
     (.withoutPadding (Base64/getUrlEncoder))))

(defn b64url-encode
  [bytes]
  #?(:clj
     (.encodeToString b64url-encoder (host-bytes bytes))
     :cljs
     (let [binary (apply str (map #(js/String.fromCharCode %) bytes))
           encoded (.call (.-btoa js/globalThis) js/globalThis binary)]
       (-> encoded
           (str/replace "+" "-")
           (str/replace "/" "_")
           (str/replace #"=+$" "")))))

(defn- b64url-value
  "The six-bit value of one Base64URL character code, or -1."
  [code]
  (cond
    (and (<= 65 code) (<= code 90)) (- code 65)
    (and (<= 97 code) (<= code 122)) (- code 71)
    (and (<= 48 code) (<= code 57)) (+ code 4)
    (= 45 code) 62
    (= 95 code) 63
    :else -1))

(defn- b64url-alphabet?
  "True when every character of the string `encoded` is in the Base64URL
  alphabet. The JVM scan uses primitive locals: it runs on every token,
  cursor, and cache-entry decode."
  [encoded]
  #?(:clj
     (let [^String text encoded
           length (.length text)]
       (loop [index 0]
         (if (== index length)
           true
           (let [code (int (.charAt text index))]
             (if (or (and (>= code 65) (<= code 90))
                     (and (>= code 97) (<= code 122))
                     (and (>= code 48) (<= code 57))
                     (== code 45)
                     (== code 95))
               (recur (unchecked-inc index))
               false)))))
     :cljs
     (let [length (.-length encoded)]
       (loop [index 0]
         (if (== index length)
           true
           (if (neg? (b64url-value (.charCodeAt encoded index)))
             false
             (recur (inc index))))))))

(defn- canonical-b64url?
  "True only for the unpadded Base64URL spelling that `b64url-encode` emits:
  URL-alphabet characters, a length that ends on a byte boundary, and zero
  unused low bits in the last character (RFC 4648 sections 3.5 and 5)."
  [encoded]
  (and (string? encoded)
       (let [length (count encoded)
             remainder (mod length 4)]
         (and (not= 1 remainder)
              (b64url-alphabet? encoded)
              (or (zero? remainder)
                  (zero? (bit-and
                          (b64url-value
                           (string-code-unit-at encoded (dec length)))
                          (if (= 2 remainder) 0x0F 0x03))))))))

(defn- host-b64url-decode
  "`b64url-decode` into the host's own byte container (a byte array on the
  JVM, a vector in ClojureScript)."
  [encoded]
  (when-not (canonical-b64url? encoded)
    (format-error! :malformed-base64 {}))
  (try
    #?(:clj
       (.decode (Base64/getUrlDecoder) ^String encoded)
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

(defn b64url-decode
  "Decodes the unpadded Base64URL spelling that `b64url-encode` emits.

  Host decoders also accept padding, nonzero unused bits in the last
  character and, in JavaScript, whitespace, so several strings decode to the
  same bytes. Those spellings fail with `:malformed-base64`: each byte string
  has exactly one accepted encoding, and an authenticated string cannot be
  respelled without failing to decode."
  [encoded]
  #?(:clj (unsigned-vector (host-b64url-decode encoded))
     :cljs (host-b64url-decode encoded)))

(defn- host-utf8
  "`utf8-bytes` in the host's own byte container (a byte array on the JVM, a
  JS array in ClojureScript), with the same well-formedness check."
  [value]
  (let [value (str value)]
    (when-not (well-formed-unicode? value)
      (format-error! :invalid-unicode {}))
    #?(:clj (.getBytes ^String value StandardCharsets/UTF_8)
       :cljs (gcrypt/stringToUtf8ByteArray value))))

(defn- host-sha-256
  "`sha-256` with its digest in the host's own byte container."
  [message]
  #?(:clj
     (.digest (MessageDigest/getInstance "SHA-256")
              ^bytes (if (string? message)
                       (host-utf8 message)
                       (host-bytes message)))
     :cljs
     (let [message (if (string? message)
                     (utf8-bytes message)
                     (vec message))
           digest (goog.crypt.Sha256.)]
       (.update digest (clj->js message))
       (vec (.digest digest)))))

(defn sha-256
  "Portable SHA-256 for non-secret, authenticated proof digests."
  [message]
  #?(:clj (unsigned-vector (host-sha-256 message))
     :cljs (host-sha-256 message)))

(defn canonical-digest
  "Domain-separated digest of bounded canonical portable data."
  [domain value]
  (when-not (and (string? domain) (not-empty domain))
    (format-error! :invalid-domain {:domain domain}))
  (b64url-encode
   (host-sha-256
    (str domain "\n" (encode-canonical value)))))

(defn- framed
  "One record's bytes behind their length as four big-endian bytes, in the
  host's byte container."
  [bytes]
  #?(:clj
     (let [^bytes bytes bytes
           length (alength bytes)
           framed (byte-array (+ 4 length))]
       (aset framed 0 (unchecked-byte (bit-shift-right length 24)))
       (aset framed 1 (unchecked-byte (bit-shift-right length 16)))
       (aset framed 2 (unchecked-byte (bit-shift-right length 8)))
       (aset framed 3 (unchecked-byte length))
       (System/arraycopy bytes 0 framed 4 length)
       framed)
     :cljs
     (let [length (.-length bytes)]
       (.concat #js [(bit-and (bit-shift-right length 24) 255)
                     (bit-and (bit-shift-right length 16) 255)
                     (bit-and (bit-shift-right length 8) 255)
                     (bit-and length 255)]
                bytes))))

(defn- absorb!
  [digest framed]
  #?(:clj (.update ^MessageDigest digest ^bytes framed)
     :cljs (.update digest framed)))

(defn- framed-digest
  "A SHA-256 state that has absorbed the length-framed domain."
  [domain]
  (when-not (and (string? domain) (not-empty domain))
    (format-error! :invalid-domain {:domain domain}))
  (let [digest #?(:clj (MessageDigest/getInstance "SHA-256")
                  :cljs (goog.crypt.Sha256.))]
    (absorb! digest (framed (host-utf8 domain)))
    digest))

(defn- finish-digest
  [digest]
  (b64url-encode
   #?(:clj (.digest ^MessageDigest digest)
      :cljs (vec (.digest digest)))))

(defn canonical-records-digest
  "Domain-separated digest of an ordered, potentially large record sequence.

  Every record is independently canonicalized and bounded, then length-framed
  before it enters the incremental SHA-256 state. This avoids both ambiguous
  concatenation and the whole-proof size limit without ever truncating proof
  content. Record order is part of the digest contract; callers must sort
  semantically unordered inputs before calling this function."
  [domain records]
  (let [digest (framed-digest domain)]
    (doseq [record records]
      (absorb! digest (framed (host-utf8 (encode-canonical record)))))
    (finish-digest digest)))

(defn- host-map []
  #?(:clj (java.util.HashMap.) :cljs (js/Map.)))

(defn- host-get [memo key]
  #?(:clj (.get ^java.util.HashMap memo key) :cljs (.get memo key)))

(defn- host-put! [memo key value]
  #?(:clj (.put ^java.util.HashMap memo key value) :cljs (.set memo key value))
  value)

(defn- canonical-array
  "`values` as an array in ascending order of `order-key`."
  [values order-key]
  (let [array (to-array values)]
    (when (< 1 (alength array))
      (let [by-key (fn [left right] (compare (order-key left) (order-key right)))]
        #?(:clj (java.util.Arrays/sort ^objects array ^java.util.Comparator by-key)
           :cljs (.sort array by-key))))
    array))

(defn canonical-tree-digest
  "Digests already admitted compiler data without encoding a whole aggregate.
   Container tags and arities preserve structure; map keys and set members
   retain canonical order. Scalar records still use the bounded wire codec.
   This is not a replacement for admission limits on untrusted input.

   The digest is the records digest of the tree's pre-order record sequence:
   `[:map n]`, `[:set n]` or `[:sequence n]` before a container's children
   (a map's keys and set members in canonical order, each key followed by its
   value) and `[:value v]` for every other value. The records are streamed
   into the digest instead of being built first, and each distinct record is
   encoded and framed once per digest. A container orders its members by
   their canonical encodings; a keyword's encoding is its printed form, so
   keywords order without encoding. Every leaf, keys included, is still
   validated by the codec when its own record is encoded."
  [domain value]
  (let [digest (framed-digest domain)
        leaves (host-map)
        headers (host-map)
        encodings (host-map)
        leaf! (fn [value]
                (absorb!
                 digest
                 (if-some [bytes (host-get leaves value)]
                   bytes
                   (host-put! leaves value
                              (framed (host-utf8 (encode-canonical [:value value])))))))
        ;; `[kind n]` for a container arity renders as exactly this text and
        ;; always satisfies the codec's bounds.
        header! (fn [tag kind n]
                  (let [key (+ (* 4 n) tag)]
                    (absorb!
                     digest
                     (if-some [bytes (host-get headers key)]
                       bytes
                       (host-put! headers key
                                  (framed (host-utf8 (str "[" kind " " n "]"))))))))
        order-key (fn [value]
                    (if (keyword? value)
                      (portable-render value)
                      (if-some [encoded (host-get encodings value)]
                        encoded
                        (host-put! encodings value (encode-canonical value)))))]
    (letfn [(walk! [value]
              (cond
                (map? value)
                (let [^objects ordered (canonical-array (keys value) order-key)]
                  (header! 0 ":map" (count value))
                  (dotimes [index (alength ordered)]
                    (let [key (aget ordered index)]
                      (walk! key)
                      (walk! (get value key)))))

                (set? value)
                (let [^objects ordered (canonical-array value order-key)]
                  (header! 1 ":set" (count value))
                  (dotimes [index (alength ordered)]
                    (walk! (aget ordered index))))

                (sequential? value)
                (do (header! 2 ":sequence" (count value))
                    (reduce (fn [_ item] (walk! item) nil) nil value))

                :else (leaf! value)))]
      (walk! value))
    (finish-digest digest)))

(defn ^:no-doc capture-keyring
  "Captures at most once for a protected operation. Static codec options are
   already immutable; constructed clients supply an opaque controller."
  [options]
  (if-let [controller (:keyring-controller options)]
    (if (:keyring-snapshot options)
      options
      (do
        (when-not (satisfies? keyrings/KeyringSource controller)
          (format-error! :invalid-keyring {}))
        (let [snapshot (keyrings/-snapshot controller)]
          (assoc options :keyring-snapshot snapshot
                 :current-kid (:active-kid snapshot)
                 :keyring (:keys snapshot)))))
    options))

(defn ^:no-doc domain-key
  "Uses only the captured generation; never reads mutable controller state."
  [options kid root-key domain version]
  (if-let [controller (:keyring-controller options)]
    (keyrings/-derive-key controller (:keyring-snapshot options) kid root-key domain version)
    (derive-key root-key domain)))

(defn ^:no-doc key-by-id
  "Looks up one format key by its admitted identifier type. Callers own the
   operation snapshot and error category; this never captures mutable state."
  [keyring kid]
  (when (or (keyword? kid) (and (string? kid) (not-empty kid)))
    (get (or keyring {:default default-root-key}) kid)))

(defn signing-context
  [options domain]
  (let [{:keys [current-kid keyring] :as options} (capture-keyring options)
        kid (or current-kid :default)
        keyring (or keyring {:default default-root-key})
        root-key (key-by-id keyring kid)]
    (when-not root-key
      (format-error! :unknown-key-id {}))
    {:kid kid
     :key (domain-key options kid root-key domain canonical-version)
     :keyring keyring}))

(defn encode-authenticated
  [{:keys [domain prefix] :as options} payload]
  (when-not (and (string? prefix) (not-empty prefix))
    (format-error! :invalid-prefix {}))
  ;; Text, tag and envelope stay in the host's byte container between the
  ;; canonical encoder, the MAC and Base64URL: the portable byte vectors of
  ;; the public helpers hold the same bytes and would be converted back at
  ;; every step.
  (let [{:keys [kid key]} (signing-context options domain)
        encoded-payload (b64url-encode
                         (host-utf8 (encode-canonical payload options)))
        signed {:v canonical-version
                :kid kid
                :payload encoded-payload}
        tag (host-hmac-sha-256
             key
             (str domain "\n" (encode-canonical signed options)))
        envelope (assoc signed :tag (b64url-encode tag))]
    (str prefix
         (b64url-encode
          (host-utf8 (encode-canonical envelope options))))))

(defn ^:no-doc decode-authenticated-envelope
  "Authenticates and decodes a token from `encode-authenticated`.

  The tag covers the canonical `{:v :kid :payload}` text. Both Base64URL
  layers and the envelope decode only in their canonical spelling, so that
  text is a function of the received token and the received token is the
  only string that carries it: a token cannot be respelled and still
  authenticate."
  [{:keys [domain prefix payload-keys maximum-size] :as options} token]
  (when-not (and (string? token)
                 (<= (count token)
                     (or maximum-size default-maximum-size))
                 (str/starts-with? token prefix))
    (format-error! :malformed-token {}))
  (let [envelope
        (decode-canonical
         (bytes->utf8
          (host-b64url-decode (subs token (count prefix))))
         (assoc options :allowed-keys #{:v :kid :payload :tag}))
        {:keys [v kid payload tag]} envelope
        options (capture-keyring options)
        root-key (key-by-id (:keyring options) kid)]
    (when-not (and (= canonical-version v)
                   (string? payload)
                   (string? tag))
      (format-error! :authentication-failed {}))
    (when-not root-key (format-error! :security-key-unavailable {}))
    (let [key (domain-key options kid root-key domain canonical-version)
          expected (hmac-sha-256
                    key
                    (str domain "\n"
                         (encode-canonical
                          {:v v :kid kid :payload payload}
                          options)))
          supplied (b64url-decode tag)]
      (when-not (secure-equal? expected supplied)
        (format-error! :authentication-failed {}))
      {:security-kid kid
       :payload (decode-canonical
                 (bytes->utf8 (host-b64url-decode payload))
                 (cond-> options payload-keys (assoc :allowed-keys payload-keys)))})))

(defn decode-authenticated [options token]
  (:payload (decode-authenticated-envelope options token)))
