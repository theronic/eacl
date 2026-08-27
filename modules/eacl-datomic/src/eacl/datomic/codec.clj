(ns eacl.datomic.codec
  "Compact binary codec for EACL page-token payloads.

  Page tokens were serialized with `canonicalize` + `pr-str` on write and
  `clojure.edn/read-string` on read. Profiling a cached recursive page walk put
  ~50% of wall time in page-token serialization against ~7% in the
  authorization engine itself, and the cryptography was only ~3% of the token
  cost — the rest was Clojure's printer and reader, run three to four times per
  token.

  This codec covers exactly the value types a cursor payload contains: nil,
  booleans, longs, strings, keywords, vectors, maps and sets. Anything else is
  rejected at encode time rather than silently round-tripped as something else,
  so a payload that gains an unsupported value fails loudly in tests instead of
  in production.

  Encoding is NOT canonical and does not need to be: the payload is sealed
  under AES-GCM with a random nonce, so its bytes already differ per token, and
  nothing compares two encodings for equality. The token's AAD is built from
  the header bytes exactly as written, so it is byte-identical by construction
  rather than by reconstruction."
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream
            DataOutputStream]
           [java.nio.charset StandardCharsets]))

(def ^:private tag-nil 0)
(def ^:private tag-false 1)
(def ^:private tag-true 2)
(def ^:private tag-long 3)
(def ^:private tag-string 4)
(def ^:private tag-keyword 5)
(def ^:private tag-vector 6)
(def ^:private tag-map 7)
(def ^:private tag-set 8)

(def ^:private maximum-collection-count
  "Sanity bound on a decoded collection length. The plaintext is only read
  after GCM authentication succeeds, so this cannot be attacker-controlled; it
  bounds the blast radius of a format mismatch to an exception instead of an
  allocation storm."
  (* 1024 1024))

(defn- unencodable!
  [value]
  (throw (ex-info "Value is not encodable in an EACL page token."
                  {:type :eacl.codec/unencodable :eacl/error :eacl.codec/unencodable
                   :value-class (some-> value class str)})))

(defn- write-string!
  [^DataOutputStream out ^String s]
  ;; Length-prefixed UTF-8 rather than writeUTF: writeUTF caps at 64KB and uses
  ;; modified UTF-8, and a wide permission graph's path-frontier keys have no
  ;; such bound.
  (let [bytes (.getBytes s StandardCharsets/UTF_8)]
    (.writeInt out (alength bytes))
    (.write out bytes 0 (alength bytes))))

(defn write-value!
  [^DataOutputStream out value]
  (cond
    (nil? value) (.writeByte out tag-nil)
    (true? value) (.writeByte out tag-true)
    (false? value) (.writeByte out tag-false)

    (integer? value)
    (if (<= Long/MIN_VALUE value Long/MAX_VALUE)
      (do (.writeByte out tag-long)
          (.writeLong out (long value)))
      (unencodable! value))

    (string? value)
    (do (.writeByte out tag-string)
        (write-string! out value))

    (keyword? value)
    (do (.writeByte out tag-keyword)
        ;; (str :a/b) => ":a/b"; drop the colon so `keyword` reverses it.
        (write-string! out (subs (str value) 1)))

    ;; Records are maps. Encoding one would silently decode as a plain map, so
    ;; reject rather than change a value's type across a round trip.
    (record? value) (unencodable! value)

    (map? value)
    (do (.writeByte out tag-map)
        (.writeInt out (count value))
        (reduce-kv (fn [_ k v]
                     (write-value! out k)
                     (write-value! out v)
                     nil)
                   nil
                   value))

    (set? value)
    (do (.writeByte out tag-set)
        (.writeInt out (count value))
        (run! #(write-value! out %) value))

    (or (vector? value) (sequential? value))
    (do (.writeByte out tag-vector)
        (.writeInt out (count value))
        (run! #(write-value! out %) value))

    :else (unencodable! value)))

(defn- read-string*
  [^DataInputStream in]
  (let [n (.readInt in)]
    (when (or (neg? n) (> n maximum-collection-count))
      (throw (ex-info "EACL page token string length is out of range."
                      {:type :eacl.codec/malformed :eacl/error :eacl.codec/malformed :length n})))
    (let [bytes (byte-array n)]
      (.readFully in bytes)
      (String. bytes StandardCharsets/UTF_8))))

(defn- read-count
  [^DataInputStream in]
  (let [n (.readInt in)]
    (when (or (neg? n) (> n maximum-collection-count))
      (throw (ex-info "EACL page token collection length is out of range."
                      {:type :eacl.codec/malformed :eacl/error :eacl.codec/malformed :length n})))
    n))

(defn read-value
  [^DataInputStream in]
  (let [tag (int (.readByte in))]
    ;; Literal `case` rather than `condp =` on the tag vars: case compiles to a
    ;; tableswitch, condp to a linear chain of boxed equality checks, and this
    ;; runs once per value in every cursor. Keep in sync with the tag- defs.
    (case tag
      0 nil                                                 ; tag-nil
      1 false                                               ; tag-false
      2 true                                                ; tag-true
      3 (.readLong in)                                      ; tag-long
      4 (read-string* in)                                   ; tag-string
      5 (keyword (read-string* in))                         ; tag-keyword

      6                                                     ; tag-vector
      (let [n (read-count in)]
        (loop [i 0 acc (transient [])]
          (if (= i n)
            (persistent! acc)
            (recur (inc i) (conj! acc (read-value in))))))

      7                                                     ; tag-map
      (let [n (read-count in)]
        (loop [i 0 acc (transient {})]
          (if (= i n)
            (persistent! acc)
            ;; k then v, in two statements: argument evaluation order inside a
            ;; single form is not something to bet a wire format on.
            (let [k (read-value in)
                  v (read-value in)]
              (recur (inc i) (assoc! acc k v))))))

      8                                                     ; tag-set
      (let [n (read-count in)]
        (loop [i 0 acc (transient #{})]
          (if (= i n)
            (persistent! acc)
            (recur (inc i) (conj! acc (read-value in))))))

      (throw (ex-info "Unknown EACL page token value tag."
                      {:type :eacl.codec/malformed :eacl/error :eacl.codec/malformed :tag tag})))))

(defn encode
  "Serializes one cursor payload value to bytes."
  ^bytes [value]
  (let [out (ByteArrayOutputStream. 256)
        data (DataOutputStream. out)]
    (write-value! data value)
    (.flush data)
    (.toByteArray out)))

(defn decode
  "Reads one cursor payload value from bytes."
  [^bytes bytes]
  (let [in (DataInputStream. (ByteArrayInputStream. bytes))
        value (read-value in)]
    (when (pos? (.available in))
      (throw (ex-info "Trailing bytes in EACL page token value."
                      {:type :eacl.codec/malformed :eacl/error :eacl.codec/malformed
                       :trailing-bytes (.available in)})))
    value))
