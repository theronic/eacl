(ns eacl.datomic.consistency
  "Zed-token key derivation and optional observed revision checkpoints.

  Live Datomic zed tokens are issued and authenticated by the shared
  eacl.causal-token codec; this namespace retains the purpose-specific key
  derivation it feeds plus the bounded checkpoint state behind
  zed-token-at-least-seconds-ago. (The superseded v2 token constructors were
  deleted by trusted-surface-hygiene 11.1.)"
  (:import [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private signing-algorithm "HmacSHA256")
(def ^:private signing-key-domain "eacl/zed-token/signing-key/v2")

(defn- utf8-bytes
  [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

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
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :key :zed-token-key})))
  (hmac-sha-256 root-key (utf8-bytes signing-key-domain)))

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
                       {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
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
                      {:type :eacl/invalid-zed-token :eacl/error :eacl/invalid-zed-token
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
                    {:type :eacl/invalid-consistency :eacl/error :eacl/invalid-consistency
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
