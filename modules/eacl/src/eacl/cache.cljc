(ns eacl.cache
  "Backend-neutral cache store and exact snapshot-proof validation."
  (:require [eacl.backend.v8 :as backend]))

(def cache-entry-version 1)
(def portable-value-version 1)

(defprotocol CacheStore
  (lookup [store key])
  (store! [store key value])
  (evict! [store key])
  (clear! [store])
  (stats [store]))

(defrecord NoCache []
  CacheStore
  (lookup [_ _] nil)
  (store! [_ _ _] false)
  (evict! [_ _] false)
  (clear! [_] nil)
  (stats [_] {:entries 0 :hits 0 :misses 0 :puts 0 :errors 0}))

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
    (assoc @metrics :entries (count @entries))))

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
                 (atom {:hits 0 :misses 0 :puts 0 :errors 0})
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

(defn- cache-entry
  [key kind schema-proof relation-proof value]
  {:eacl.cache/version cache-entry-version
   :eacl.cache/portable-version portable-value-version
   :eacl.cache/key key
   :eacl.cache/kind kind
   :eacl.cache/schema-proof schema-proof
   :eacl.cache/relation-proof relation-proof
   :eacl.cache/value value})

(defn- valid-entry?
  [entry key kind schema-proof relation-proof valid-value?]
  (and (map? entry)
       (= cache-entry-version (:eacl.cache/version entry))
       (= portable-value-version (:eacl.cache/portable-version entry))
       (= key (:eacl.cache/key entry))
       (= kind (:eacl.cache/kind entry))
       (= schema-proof (:eacl.cache/schema-proof entry))
       (= relation-proof (:eacl.cache/relation-proof entry))
       (valid-value? (:eacl.cache/value entry))))

(defn- safe-store-call
  [fallback f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) _
      fallback)))

(defn resolve!
  "Returns {:value value :cached? boolean :cache-basis snapshot-id}.

  Proofs are read from the same immutable adapter used for authorization.
  Store failure, malformed entries, and proof mismatch are misses; no cached
  value is returned before its opaque schema and relation proofs compare
  exactly."
  [adapter store key kind schema-scope relation-ids valid-value? compute]
  (if (no-cache? store)
    {:value (compute)
     :cached? false
     :cache-basis nil}
    (let [schema-proof
          (backend/invoke adapter :schema-proof schema-scope)
          relation-proof
          (backend/invoke adapter :relation-proof relation-ids)
          snapshot-id (backend/invoke adapter :snapshot-id)
          entry (safe-store-call nil #(lookup store key))]
      (if (valid-entry? entry key kind
                        schema-proof relation-proof valid-value?)
        {:value (:eacl.cache/value entry)
         :cached? true
         :cache-basis snapshot-id}
        (let [value (compute)]
          (safe-store-call
           false
           #(store! store key
                    (cache-entry key kind schema-proof
                                 relation-proof value)))
          {:value value
           :cached? false
           :cache-basis snapshot-id})))))
