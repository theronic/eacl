(ns eacl.authorization.qualifier-cache
  "Bounded decoded-data reuse under exact basis or complete native content.
   Publication capability alone never certifies an arbitrary source's writers."
  (:require [eacl.cache.standard-lru :as lru]
            [eacl.relationships.qualifier :as qualifier]))

(def default-max-entries 256)
(defrecord DecodeCache [entries])

(defn cache? [value] (instance? DecodeCache value))

(defn normalize-option [option]
  (cond
    (false? option) false
    (nil? option) {:max-entries default-max-entries}
    (and (map? option) (= #{:max-entries} (set (keys option)))
         (integer? (:max-entries option)) (<= 1 (:max-entries option) 100000)) option
    :else (throw (ex-info "Qualifier cache must be false or a bounded :max-entries map."
                          {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                           :key :qualifier-cache :value option}))))

(defn cache [options]
  (when-let [options (normalize-option options)]
    (->DecodeCache (lru/store (:max-entries options)))))

(defn exact-key [basis relation-id qid]
  [:exact basis relation-id qid qualifier/format-version])

(defn content-key
  "Every native field remains in the collision-checked identity. In particular,
   a marker assertion version alone does not prove in-place immutability.
   Speculative identity and source lifecycle remain after removing basis facts."
  [basis relation-id qid version entity named-entity relation]
  [:content (dissoc basis :revision :exact-locator :backend-snapshot-id)
   relation-id qid version qualifier/format-version entity named-entity relation])

(defn lookup! [cache key]
  (lru/lookup! (:entries cache) key))

(defn publish! [cache exact content decoded]
  ;; Two indices share one capacity and immutable value. Only successful data
  ;; decoding reaches this point; no contextual evidence enters either index.
  (lru/put-if-absent! (:entries cache) content decoded)
  (lru/put-if-absent! (:entries cache) exact decoded)
  decoded)
