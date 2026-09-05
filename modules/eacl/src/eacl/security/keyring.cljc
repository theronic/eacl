(ns eacl.security.keyring
  "Externally driven, non-durable security keys with atomic epoch transitions."
  (:require [clojure.set :as set]
            [eacl.cache.standard-lru :as lru]
            [eacl.secure-format :as secure]
            [eacl.security.protocols :as protocols]))

(def maximum-keys 64)
(def maximum-retired-kids 65536)
(def maximum-key-bytes 4096)
(def maximum-kid-bytes 1024)
(def maximum-update-attempts 32)
(def maximum-derived-keys 256)

(defn- failure! [reason]
  (throw (ex-info "Invalid EACL security keyring operation."
                  {:type :eacl.keyring/invalid :eacl/error :eacl.keyring/invalid :reason reason})))

(deftype SecurityKeyring [state]
  protocols/KeyringSource
  (-snapshot [_] @state)
  (-derive-key [_ snapshot kid root-key domain version]
    (let [cache (:derived-cache snapshot)
          key [(:generation snapshot) kid domain version]
          found (lru/lookup! cache key)]
      (if (:found? found)
        (:value found)
        (let [derived (secure/derive-key root-key domain)]
          (lru/put-if-absent! cache key derived)
          derived))))
  Object
  (toString [_] "#<EACL SecurityKeyring>")
  #?@(:cljs [IPrintWithWriter
             (-pr-writer [_ writer _] (-write writer "#<EACL SecurityKeyring>"))]))

#?(:clj (defmethod print-method SecurityKeyring [_ writer]
          (.write ^java.io.Writer writer "#<EACL SecurityKeyring>")))
#?(:clj (defmethod print-dup SecurityKeyring [_ writer]
          (.write ^java.io.Writer writer "#<EACL SecurityKeyring>")))

(defn keyring? [value] (instance? SecurityKeyring value))

(defn- controller! [value]
  (when-not (keyring? value) (failure! :invalid-controller))
  value)

(defn- status-value [{:keys [generation active-kid retired-kids] :as state}]
  {:generation generation :active-kid active-kid
   :accepted-kids (set (keys (:keys state))) :retired-kids retired-kids})

(defn status [controller]
  (status-value (protocols/-snapshot (controller! controller))))

(defn- kid-valid? [kid]
  (and (or (and (keyword? kid) (seq (name kid))
                (<= (+ (count (name kid)) (count (namespace kid))) maximum-kid-bytes))
           (and (string? kid) (seq kid) (<= (count kid) maximum-kid-bytes)))
       (try (secure/encode-canonical kid {:maximum-size maximum-kid-bytes}) true
            (catch #?(:clj Throwable :cljs :default) _ false))))

(defn- normalize-material [material]
  ;; Bound lazy sequences and byte/string inputs before the existing key codec
  ;; can allocate. Never attach a key-bearing input or conversion exception.
  (try
    (let [material
          (cond
            (string? material) (do (when (> (count material) maximum-key-bytes) (failure! :key-size)) material)
            #?(:clj (bytes? material) :cljs (instance? js/Uint8Array material))
            (do (when (> #?(:clj (alength ^bytes material) :cljs (.-length material)) maximum-key-bytes)
                  (failure! :key-size)) material)
            (sequential? material)
            (let [value (vec (take (inc maximum-key-bytes) material))]
              (when (or (> (count value) maximum-key-bytes)
                        (not-every? #(and (integer? %) (<= 0 % 255)) value))
                (failure! :invalid-key))
              value)
            :else (failure! :invalid-key))
          normalized (secure/normalize-key material)]
      (when (> (count normalized) maximum-key-bytes) (failure! :key-size))
      normalized)
    (catch #?(:clj Throwable :cljs :default) _ (failure! :invalid-key))))

(defn- options! [options allowed]
  (when-not (and (map? options) (every? allowed (keys options)))
    (failure! :invalid-options)))

(defn- limit! [value maximum]
  (when-not (and (integer? value) (pos? value) (<= value maximum))
    (failure! :invalid-limit))
  value)

(defn- normalized-keys [key-map maximum]
  (when-not (and (map? key-map) (seq key-map) (<= (count key-map) maximum))
    (failure! :key-count))
  (reduce-kv (fn [result kid material]
               (when-not (kid-valid? kid) (failure! :invalid-key-id))
               (assoc result kid (normalize-material material))) {} key-map))

(defn- fingerprint [key]
  (secure/canonical-digest "eacl/security-keyring/epoch/v1" key))

(defn- validated-state
  "Pure state construction. Retired identifiers keep only a private fingerprint;
   their key material and derived-key cache are not copied into the next state."
  [prior key-map active-kid limits]
  (let [key-map (normalized-keys key-map (:max-keys limits))
        _ (when-not (and (kid-valid? active-kid) (contains? key-map active-kid))
            (failure! :active-key-unavailable))
        fingerprints (reduce-kv #(assoc %1 %2 (fingerprint %3)) {} key-map)
        accepted (set (keys key-map))
        removed (set/difference (set (keys (:keys prior))) accepted)
        retired (into (:retired-kids prior #{}) removed)]
    (when (seq (set/intersection accepted (:retired-kids prior #{})))
      (failure! :retired-key-id))
    (doseq [[kid identity] fingerprints]
      (when-let [previous (get-in prior [:fingerprints kid])]
        (when-not (= previous identity) (failure! :key-id-reuse))))
    (when (> (count retired) (:max-retired-kids limits)) (failure! :retired-id-count))
    {:generation (if prior (inc (:generation prior)) 0)
     :controller-id (:controller-id prior)
     :keys key-map :active-kid active-kid :retired-kids retired
     :fingerprints (merge (:fingerprints prior) fingerprints)
     :limits limits}))

(defn- with-derived-cache [state]
  (assoc state :derived-cache (lru/store maximum-derived-keys)))

(defn keyring
  "Creates an opaque controller from {:keys {kid material} :active-kid kid}.
   Optional :max-keys and :max-retired-kids may lower the hard ceilings."
  [options]
  (options! options #{:keys :active-kid :max-keys :max-retired-kids})
  (let [limits {:max-keys (limit! (get options :max-keys maximum-keys) maximum-keys)
                :max-retired-kids (limit! (get options :max-retired-kids maximum-retired-kids) maximum-retired-kids)}]
    (->SecurityKeyring (atom (with-derived-cache
                               (assoc (validated-state nil (:keys options) (:active-kid options) limits)
                                      :controller-id (str (random-uuid))))))))

(defn- conflict! [current]
  (throw (ex-info "The EACL security keyring generation changed."
                  {:type :eacl.keyring/conflict :eacl/error :eacl.keyring/conflict
                   :status (status-value current)})))

(defn replace!
  "Replaces the full ring only at :expected-generation; returns safe new status.
   Removed ids are permanently retired for this controller's lifetime."
  [controller options]
  (controller! controller)
  (options! options #{:expected-generation :keys :active-kid})
  (let [state (.-state ^SecurityKeyring controller)
        prior @state
        expected (:expected-generation options)]
    (when-not (and (integer? expected) (<= 0 expected secure/maximum-safe-integer))
      (failure! :invalid-generation))
    (when-not (= expected (:generation prior)) (conflict! prior))
    (when (= expected secure/maximum-safe-integer) (failure! :generation-limit))
    (let [next (with-derived-cache (validated-state prior (:keys options) (:active-kid options) (:limits prior)))]
      (if (compare-and-set! state prior next)
        (status-value next)
        (conflict! @state)))))

(defn- update! [controller transform]
  (controller! controller)
  (loop [attempt 0]
    (let [prior (protocols/-snapshot controller)]
      (if-let [desired (transform prior)]
        (let [outcome (try {:value (replace! controller (assoc desired :expected-generation (:generation prior)))}
                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
                             (if (= :eacl.keyring/conflict (:type (ex-data error)))
                               {:conflict error} (throw error))))]
          (if-let [error (:conflict outcome)]
            (if (< (inc attempt) maximum-update-attempts) (recur (inc attempt)) (throw error))
            (:value outcome)))
        (status-value prior)))))

(defn add!
  "Installs an inactive key. Redistributing an identical accepted key is a no-op."
  [controller kid material]
  (controller! controller)
  (when-not (kid-valid? kid) (failure! :invalid-key-id))
  (let [material (normalize-material material)]
    (update! controller
             (fn [prior]
               (when-not (= material (get-in prior [:keys kid]))
                 {:keys (assoc (:keys prior) kid material) :active-kid (:active-kid prior)})))))

(defn activate! [controller kid]
  (update! controller
           (fn [prior]
             (when-not (contains? (:keys prior) kid) (failure! :active-key-unavailable))
             (when-not (= kid (:active-kid prior)) {:keys (:keys prior) :active-kid kid}))))

(defn retire! [controller kid]
  (update! controller
           (fn [prior]
             (when (= kid (:active-kid prior)) (failure! :active-key-retirement))
             (cond
               (contains? (:retired-kids prior) kid) nil
               (not (contains? (:keys prior) kid)) (failure! :unknown-key-id)
               :else {:keys (dissoc (:keys prior) kid) :active-kid (:active-kid prior)}))))
