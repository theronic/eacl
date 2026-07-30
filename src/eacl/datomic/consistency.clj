(ns eacl.datomic.consistency
  "Datomic revision tokens and optional observed-revision checkpoints.

  A token's semantic value is one monotonic Datomic basis t. It is a freshness
  lower bound, not a wall-clock timestamp and not a retained database value."
  (:require [clojure.edn :as edn])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def token-version 1)
(def ^:private token-prefix "eacl_z1_")

(defn- utf8-bytes
  [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

(defn- b64url-encode
  [^bytes value]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value))

(defn- b64url-decode
  [^String value]
  (.decode (Base64/getUrlDecoder) value))

(defn zed-token
  "Creates a versioned token bound to `database-id` at Datomic basis `t`."
  [database-id t]
  (when-not (and (string? database-id)
                 (not-empty database-id)
                 (integer? t)
                 (not (neg? t))
                 (<= t Long/MAX_VALUE))
    (throw (ex-info "Invalid EACL Zed token components."
                    {:type :eacl/invalid-zed-token
                     :database-id database-id
                     :revision t})))
  (str token-prefix
       (b64url-encode
        (utf8-bytes
         (pr-str {:v token-version
                  :db database-id
                  :t (long t)})))))

(defn token-data
  "Validates and decodes `token` for `expected-database-id`.

  Returns {:database-id string :revision Long}."
  [expected-database-id token]
  (try
    (when-not (and (string? token)
                   (.startsWith ^String token token-prefix))
      (throw (ex-info "Invalid EACL Zed token."
                      {:type :eacl/invalid-zed-token})))
    (let [payload (edn/read-string
                   (String.
                    (b64url-decode (subs token (count token-prefix)))
                    StandardCharsets/UTF_8))
          {:keys [v db t]} payload]
      (when-not (= #{:v :db :t} (set (keys payload)))
        (throw (ex-info "Malformed EACL Zed token payload."
                        {:type :eacl/invalid-zed-token})))
      (when-not (= token-version v)
        (throw (ex-info "Unsupported EACL Zed token version."
                        {:type :eacl/invalid-zed-token
                         :version v})))
      (when-not (and (string? db)
                     (integer? t)
                     (not (neg? t))
                     (<= t Long/MAX_VALUE))
        (throw (ex-info "Malformed EACL Zed token payload."
                        {:type :eacl/invalid-zed-token})))
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
    (catch Exception e
      (throw (ex-info "Invalid EACL Zed token."
                      {:type :eacl/invalid-zed-token}
                      e)))))

(defn token-revision
  [expected-database-id token]
  (:revision (token-data expected-database-id token)))

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
