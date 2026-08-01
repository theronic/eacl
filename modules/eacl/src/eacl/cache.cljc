(ns eacl.cache
  "Backend-neutral authenticated cache and snapshot-proof validation."
  (:require [eacl.backend.v8 :as backend]
            [eacl.secure-format :as secure]))

(def cache-entry-version 3)
(def portable-value-version 1)
(def cache-entry-prefix "eacl_ce3_")
(def cache-entry-domain "eacl/cache-entry/envelope/v3")
(def cache-entry-keys
  #{:version :portable-version :key :kind :computed-at :validated-at
    :dependency-scope :proof :value})

(def validation-metric-keys
  [:exact-hit
   :causal-proof-lift
   :content-proof
   :mutation-proof
   :proof-mismatch
   :future-history-rejection
   :unauthenticated-entry
   :no-proof-bypass
   :provider-failure])

(defprotocol CacheStore
  (lookup [store key])
  (store! [store key value])
  (evict! [store key])
  (clear! [store])
  (stats [store]))

(defprotocol CacheTelemetry
  (record-validation! [store metric]))

(defprotocol CacheValidationUpdate
  (store-validation!
    [store key expected-entry replacement-entry]
    "Conditionally replaces validation telemetry for an unchanged entry."))

(defrecord NoCache []
  CacheStore
  (lookup [_ _] nil)
  (store! [_ _ _] false)
  (evict! [_ _] false)
  (clear! [_] nil)
  (stats [_] {:entries 0 :hits 0 :misses 0 :puts 0 :errors 0})
  CacheTelemetry
  (record-validation! [_ _] nil)
  CacheValidationUpdate
  (store-validation! [_ _ _ _] false))

(def no-cache (->NoCache))

(defn no-cache?
  [store]
  (instance? NoCache store))

(defrecord LocalStore [entries metrics max-entries]
  CacheStore
  (lookup [_ key]
    (let [value (get @entries key)]
      (swap! metrics update (if (some? value) :hits :misses) inc)
      value))
  (store! [_ key value]
    (if (nil? value)
      false
      (do
        (swap! entries
               (fn [current]
                 (let [updated (assoc current key value)]
                   (if (<= (count updated) max-entries)
                     updated
                     ;; Portable reference implementation: deterministic
                     ;; bounded admission, not a claim of LRU ordering.
                     (dissoc updated (first (keys updated)))))))
        (swap! metrics update :puts inc)
        true)))
  (evict! [_ key]
    (let [present? (contains? @entries key)]
      (swap! entries dissoc key)
      present?))
  (clear! [_]
    (reset! entries {})
    nil)
  (stats [_]
    (assoc @metrics :entries (count @entries)))
  CacheTelemetry
  (record-validation! [_ metric]
    (swap! metrics update metric (fnil inc 0))
    nil)
  CacheValidationUpdate
  (store-validation! [_ key expected-entry replacement-entry]
    (let [updated? (atom false)]
      (swap! entries
             (fn [current]
               (if (= expected-entry (get current key))
                 (do
                   (reset! updated? true)
                   (assoc current key replacement-entry))
                 current)))
      @updated?)))

(defn local-store
  ([]
   (local-store {}))
  ([{:keys [max-entries]
     :or {max-entries 1024}}]
   (when-not (and (integer? max-entries) (pos? max-entries))
     (throw (ex-info "Portable cache :max-entries must be positive."
                     {:type :eacl/invalid-config
                      :max-entries max-entries})))
   (->LocalStore (atom {})
                 (atom
                  (merge {:hits 0 :misses 0 :puts 0 :errors 0}
                         (zipmap validation-metric-keys
                                 (repeat 0))))
                 max-entries)))

(defn cache-store
  "Normalizes a client cache option. nil selects the bounded local reference
  store, a map configures it, and a CacheStore is used as supplied."
  [value]
  (cond
    (nil? value) (local-store)
    (satisfies? CacheStore value) value
    (map? value) (local-store value)
    :else
    (throw (ex-info "Expected a portable EACL CacheStore or cache config map."
                    {:type :eacl/invalid-config
                     :cache value}))))

(defn- selected-point
  [adapter]
  {:source-scope
   {:backend (backend/backend-id adapter)
    :scope (backend/invoke adapter :source-scope)}
   :graph-head (backend/invoke adapter :graph-head)
   :snapshot-id (backend/invoke adapter :snapshot-id)})

(defn- complete-key
  [adapter key kind]
  {:cache-version cache-entry-version
   :adapter-version backend/adapter-version
   :source-scope
   {:backend (backend/backend-id adapter)
    :scope (backend/invoke adapter :source-scope)}
   :kind kind
   :adapter-fingerprint (backend/fingerprint adapter)
   :identity-contract (backend/identity-contract adapter)
   :semantic-key key})

(defn- encode-entry
  [format-options payload]
  (secure/encode-authenticated
   (merge format-options
          {:domain cache-entry-domain
           :prefix cache-entry-prefix})
   payload))

(defn- cache-entry
  [format-options key kind computation-point schema-scope relation-ids
   schema-proof relation-proof value]
  (encode-entry
   format-options
   {:version cache-entry-version
    :portable-version portable-value-version
    :key key
    :kind kind
    :computed-at computation-point
    :validated-at computation-point
    :dependency-scope {:schema schema-scope
                       :relations (vec (sort relation-ids))}
    :proof {:schema schema-proof
            :relations relation-proof}
    :value value}))

(defn- valid-entry?
  [adapter format-options entry key kind schema-scope relation-ids
   schema-proof relation-proof valid-value? selected-point]
  (try
    (let [decoded
          (secure/decode-authenticated
           (merge format-options
                  {:domain cache-entry-domain
                   :prefix cache-entry-prefix
                   :payload-keys cache-entry-keys})
           entry)]
      (cond
        (not (and (= cache-entry-version (:version decoded))
                  (= portable-value-version (:portable-version decoded))
                  (= (secure/canonicalize key)
                     (secure/canonicalize (:key decoded)))
                  (= kind (:kind decoded))
                  (= (secure/canonicalize
                      {:schema schema-scope
                       :relations (vec (sort relation-ids))})
                     (:dependency-scope decoded))
                  (valid-value? (:value decoded))))
        {:status :unauthenticated-entry}

        (not=
         (secure/canonicalize
          (:source-scope selected-point))
         (secure/canonicalize
          (get-in decoded [:computed-at :source-scope])))
        {:status :unauthenticated-entry}

        (not
         (backend/invoke
          adapter
          :contains-anchor?
          (get-in decoded [:computed-at :graph-head :graph-anchor])))
        {:status :future-history-rejection}

        (not=
         (secure/canonicalize
          {:schema schema-proof
           :relations relation-proof})
         (:proof decoded))
        {:status :proof-mismatch}

        :else
        {:status
         (if (= (get-in decoded [:computed-at :graph-head :graph-anchor])
                (get-in selected-point [:graph-head :graph-anchor]))
           :exact-hit
           :causal-proof-lift)
         :entry decoded}))
    (catch #?(:clj Exception :cljs :default) _
      {:status :unauthenticated-entry})))

(defn- safe-store-call
  [fallback f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) _
      fallback)))

(defn- note!
  [store metric]
  (when (satisfies? CacheTelemetry store)
    (record-validation! store metric)))

(defn- proof-metric
  [schema-proof relation-proof]
  (if (and (string? schema-proof)
           (or (empty? relation-proof)
               (every? (fn [[_ value]]
                         (string? value))
                       relation-proof)))
    :mutation-proof
    :content-proof))

(defn resolve!
  "Returns {:value value :cached? boolean :cache-basis snapshot-id}.

  Proofs are read from the same immutable adapter used for authorization.
  Store failure, malformed entries, and proof mismatch are misses; no cached
  value is returned before its opaque schema and relation proofs compare
  exactly."
  ([adapter store key kind schema-scope relation-ids valid-value? compute]
   (resolve! adapter store key kind schema-scope relation-ids
             valid-value? compute {}))
  ([adapter store key kind schema-scope relation-ids valid-value? compute
    format-options]
   (if (no-cache? store)
     {:value (compute)
      :cached? false
      :cache-basis nil}
     (let [schema-proof
           (backend/invoke adapter :schema-proof schema-scope)
           relation-proof
           (backend/invoke adapter :relation-proof relation-ids)
           point (selected-point adapter)
           full-key (complete-key adapter key kind)]
       (if (or (not (backend/deterministic? adapter))
               (nil? schema-proof)
               (nil? relation-proof)
               (empty? relation-ids))
         (do
           (note! store :no-proof-bypass)
           {:value (compute)
            :cached? false
            :cache-basis (:snapshot-id point)})
         (let [provider-error #?(:clj (Object.) :cljs (js-obj))
               stored-entry
               (safe-store-call provider-error #(lookup store full-key))
               _ (when (identical? provider-error stored-entry)
                   (note! store :provider-failure))
               {:keys [status entry]}
               (if (or (identical? provider-error stored-entry)
                       (nil? stored-entry))
                 {:status nil}
                 (valid-entry?
                  adapter format-options stored-entry full-key kind
                  schema-scope relation-ids
                  schema-proof relation-proof valid-value? point))]
           (when status
             (note! store status))
           (if entry
             (do
               (note! store (proof-metric schema-proof relation-proof))
               ;; `validated-at` is authenticated telemetry only. Every hit
               ;; above still re-read the selected snapshot's proof and causal
               ;; anchor; this update never acts as a lease.
               (let [replacement
                     (encode-entry
                      format-options
                      (assoc entry :validated-at point))]
                 (safe-store-call
                  false
                  #(if (satisfies? CacheValidationUpdate store)
                     (store-validation!
                      store full-key
                      ;; The authenticated provider value read above is the
                      ;; CAS expectation. A later validator cannot be
                      ;; overwritten by this request after it wins the race.
                      stored-entry
                      replacement)
                     (store! store full-key replacement))))
               {:value (:value entry)
                :cached? true
                :cache-basis
                (get-in entry [:computed-at :snapshot-id])
                :cache-computed-at (:computed-at entry)
                :cache-validated-at point})
             (let [value (compute)
                   stored?
                   (safe-store-call
                    provider-error
                    #(store! store full-key
                             (cache-entry
                              format-options full-key kind point
                              schema-scope relation-ids
                              schema-proof relation-proof value)))]
               (when (identical? provider-error stored?)
                 (note! store :provider-failure))
               {:value value
                :cached? false
                :cache-basis (:snapshot-id point)
                :cache-computed-at point
                :cache-validated-at point}))))))))
