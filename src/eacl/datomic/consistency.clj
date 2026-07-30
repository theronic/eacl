(ns eacl.datomic.consistency
  "Authenticated Datomic revision tokens and optional observed checkpoints.

  A token names one logical database and monotonic Datomic basis t. Its HMAC
  prevents claim forgery, but does not make it a credential, prevent replay,
  or authorize a caller to select exact historical evaluation."
  (:require [clojure.edn :as edn])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def token-version 2)
(def ^:private token-prefix "eacl_z2_")
(def ^:private maximum-token-length 4096)
(def ^:private maximum-key-id-length 128)
(def ^:private signing-algorithm "HmacSHA256")
(def ^:private signing-domain "eacl/zed-token/envelope/v2")
(def ^:private signing-key-domain "eacl/zed-token/signing-key/v2")

(defn- utf8-bytes
  [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

(defn- b64url-encode
  [^bytes value]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value))

(defn- b64url-decode
  [^String value]
  (.decode (Base64/getUrlDecoder) value))

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

(defn- canonical-bytes
  [value]
  (utf8-bytes (pr-str (canonicalize value))))

(defn- hmac-sha-256
  [^bytes key ^bytes value]
  (let [mac (Mac/getInstance signing-algorithm)]
    (.init mac (SecretKeySpec. key signing-algorithm))
    (.doFinal mac value)))

(defn derive-signing-key
  "Derives a purpose-specific Zed-token HMAC key from normalized root key bytes."
  [root-key]
  (when-not (and (bytes? root-key)
                 (pos? (alength ^bytes root-key)))
    (throw (ex-info "Zed token root key must be non-empty bytes."
                    {:type :eacl/invalid-config
                     :key :zed-token-key})))
  (hmac-sha-256 root-key (utf8-bytes signing-key-domain)))

(defn- key-id?
  [kid]
  (and (or (keyword? kid)
           (and (string? kid) (not-empty kid)))
       (<= (count (pr-str kid)) maximum-key-id-length)))

(defn- signing-context
  [{:keys [zed-token-current-kid zed-token-keyring]}]
  (let [key (get zed-token-keyring zed-token-current-kid)]
    (when-not (and (key-id? zed-token-current-kid)
                   (map? zed-token-keyring)
                   (bytes? key)
                   (pos? (alength ^bytes key)))
      (throw (ex-info "Invalid EACL Zed token signing context."
                      {:type :eacl/invalid-config
                       :key :zed-token-keyring})))
    {:kid zed-token-current-kid
     :key key}))

(defn- signing-input
  [envelope]
  (utf8-bytes
   (str signing-domain "\n" (pr-str (canonicalize envelope)))))

(defn- invalid-token!
  ([]
   (invalid-token! :invalid))
  ([reason]
   (throw (ex-info "Invalid EACL Zed token."
                   {:type :eacl/invalid-zed-token
                    :reason reason}))))

(defn zed-token
  "Creates an authenticated token bound to `database-id` at Datomic basis `t`.

  `opts` must contain the normalized :zed-token-current-kid and
  :zed-token-keyring installed by eacl.datomic.core/make-client."
  [opts database-id t]
  (when-not (and (string? database-id)
                 (not-empty database-id)
                 (integer? t)
                 (not (neg? t))
                 (<= t Long/MAX_VALUE))
    (throw (ex-info "Invalid EACL Zed token components."
                    {:type :eacl/invalid-zed-token
                     :database-id database-id
                     :revision t})))
  (let [{:keys [kid key]} (signing-context opts)
        payload (b64url-encode
                 (canonical-bytes
                  {:v token-version
                   :db database-id
                   :t (long t)}))
        signed-envelope {:v token-version
                         :kid kid
                         :payload payload}
        envelope (assoc signed-envelope
                        :tag
                        (b64url-encode
                         (hmac-sha-256
                          key
                          (signing-input signed-envelope))))
        token (str token-prefix
                   (b64url-encode (canonical-bytes envelope)))]
    (when (> (count token) maximum-token-length)
      (throw (ex-info "EACL Zed token exceeds the maximum encoded length."
                      {:type :eacl/invalid-zed-token
                       :reason :too-long})))
    token))

(defn token-data
  "Authenticates and decodes `token` for `expected-database-id`.

  Returns {:database-id string :revision Long}."
  [opts expected-database-id token]
  (try
    (when-not (and (string? token)
                   (<= (count token) maximum-token-length)
                   (.startsWith ^String token token-prefix))
      (invalid-token! :malformed))
    (let [envelope (edn/read-string
                    (String.
                     (b64url-decode (subs token (count token-prefix)))
                     StandardCharsets/UTF_8))
          envelope-version (:v envelope)
          kid (:kid envelope)
          encoded-payload (:payload envelope)
          tag (:tag envelope)
          _ (when-not (and (= #{:v :kid :payload :tag}
                              (set (keys envelope)))
                           (= token-version envelope-version)
                           (key-id? kid)
                           (string? encoded-payload)
                           (string? tag))
              (invalid-token! :malformed))
          key (get (:zed-token-keyring opts) kid)
          _ (when-not (bytes? key)
              (invalid-token! :authentication-failed))
          supplied-tag (b64url-decode tag)
          expected-tag
          (hmac-sha-256
           key
           (signing-input {:v envelope-version
                           :kid kid
                           :payload encoded-payload}))
          _ (when-not (and (= (alength ^bytes expected-tag)
                              (alength ^bytes supplied-tag))
                           (MessageDigest/isEqual expected-tag supplied-tag))
              (invalid-token! :authentication-failed))
          parsed-payload (edn/read-string
                          (String. (b64url-decode encoded-payload)
                                   StandardCharsets/UTF_8))
          payload-version (:v parsed-payload)
          db (:db parsed-payload)
          t (:t parsed-payload)]
      (when-not (= #{:v :db :t} (set (keys parsed-payload)))
        (invalid-token! :malformed))
      (when-not (= token-version payload-version)
        (invalid-token! :malformed))
      (when-not (and (string? db)
                     (not-empty db)
                     (integer? t)
                     (not (neg? t))
                     (<= t Long/MAX_VALUE))
        (invalid-token! :malformed))
      (when-not (= expected-database-id db)
        (throw (ex-info "EACL Zed token belongs to another database."
                        {:type :eacl/invalid-zed-token
                         :reason :database-mismatch
                         :expected-database-id expected-database-id
                         :actual-database-id db})))
      {:database-id db
       :revision (long t)})
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch StackOverflowError _
      (invalid-token! :malformed))
    (catch Exception _
      (invalid-token! :malformed))))

(defn token-revision
  [opts expected-database-id token]
  (:revision (token-data opts expected-database-id token)))

(def ^:private default-checkpoint-config
  {:max-entries 64
   :max-age-ms (* 60 60 1000)
   :interval-ms 1000
   :clock #(quot (System/nanoTime) 1000000)})

(defrecord RevisionCheckpoints [state config])

(defn revision-checkpoints
  "Creates explicit bounded checkpoint state.

  The injected clock must be monotonic milliseconds. No timer or Datomic DB
  value is retained."
  ([]
   (revision-checkpoints {}))
  ([config]
   (let [{:keys [max-entries max-age-ms interval-ms clock] :as config'}
         (merge default-checkpoint-config config)]
     (when-not (and (integer? max-entries)
                    (pos? max-entries)
                    (integer? max-age-ms)
                    (pos? max-age-ms)
                    (integer? interval-ms)
                    (not (neg? interval-ms))
                    (fn? clock))
       (throw (ex-info "Invalid EACL revision checkpoint configuration."
                       {:type :eacl/invalid-config
                        :checkpoints config'})))
     (->RevisionCheckpoints (atom []) config'))))

(defn checkpoint-values
  [checkpoints]
  (if checkpoints
    @(:state checkpoints)
    []))

(defn- retain-checkpoints
  [entries now {:keys [max-entries max-age-ms]}]
  (let [cutoff (- now max-age-ms)
        recent (into [] (drop-while #(< (:captured-at %) cutoff)) entries)
        excess (- (count recent) max-entries)]
    (if (pos? excess)
      (subvec recent excess)
      recent)))

(defn observe!
  "Records an actually observed basis revision at a bounded sampling rate."
  [checkpoints basis-t]
  (when checkpoints
    (when-not (and (integer? basis-t) (not (neg? basis-t)))
      (throw (ex-info "Invalid observed Datomic revision."
                      {:type :eacl/invalid-zed-token
                       :revision basis-t})))
    (let [{:keys [clock interval-ms] :as config} (:config checkpoints)
          now (clock)]
      (swap! (:state checkpoints)
             (fn [entries]
               (let [entries' (retain-checkpoints entries now config)
                     last-entry (peek entries')]
                 (if (and last-entry
                          (< (- now (:captured-at last-entry)) interval-ms))
                   entries'
                   (retain-checkpoints
                    (conj entries'
                          {:captured-at now
                           :basis-t (long basis-t)})
                    now
                    config)))))
      basis-t)))

(defn revision-at-least-seconds-ago
  "Returns an observed revision at or after `seconds-ago`.

  `current-t` is returned when retained observations cannot establish an older
  lower bound. That answer is over-fresh, never under-fresh."
  [checkpoints seconds-ago current-t]
  (when-not (and (number? seconds-ago)
                 (not (neg? seconds-ago))
                 (integer? current-t)
                 (not (neg? current-t)))
    (throw (ex-info "Invalid age-based freshness request."
                    {:type :eacl/invalid-consistency
                     :seconds-ago seconds-ago
                     :current-t current-t})))
  (if-not checkpoints
    (long current-t)
    (let [now ((get-in checkpoints [:config :clock]))
          cutoff (- now (long (* 1000 seconds-ago)))]
      (or (some (fn [{:keys [captured-at basis-t]}]
                  (when (>= captured-at cutoff)
                    basis-t))
                (checkpoint-values checkpoints))
          (long current-t)))))
