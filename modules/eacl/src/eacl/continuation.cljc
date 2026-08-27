(ns eacl.continuation
  "Adapter-neutral, client-private continuation storage.

  Public cursors authenticate query and snapshot lineage. Opaque traversal
  state stays in this bounded in-process store and is addressed by that
  authenticated lineage. Cache loss is always a performance miss: callers can
  deterministically replay the public boundary."
  (:require [eacl.backend.v8 :as backend]
            [eacl.secure-format :as secure]))

(def ^:private context-version 3)
(def ^:private default-max-entries 2048)
(def ^:private default-max-weight (* 128 1024 1024))

(defrecord BoundedContinuationStore
  [state metrics max-entries max-weight max-entry-weight])

(defn store?
  [value]
  (instance? BoundedContinuationStore value))

(defn make-store
  ([]
   (make-store {}))
  ([{:keys [max-entries max-weight max-entry-weight]
     :or {max-entries default-max-entries
          max-weight default-max-weight}}]
   (let [max-entry-weight (or max-entry-weight max-weight)]
     (when-not (and (integer? max-entries) (pos? max-entries))
       (throw
        (ex-info
         "Continuation :max-entries must be a positive integer."
         {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
          :max-entries max-entries})))
     (when-not (and (integer? max-weight) (pos? max-weight))
       (throw
        (ex-info
         "Continuation :max-weight must be a positive integer."
         {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
          :max-weight max-weight})))
     (when-not (and (integer? max-entry-weight)
                    (pos? max-entry-weight)
                    (<= max-entry-weight max-weight))
       (throw
        (ex-info
         "Continuation :max-entry-weight must be a positive integer no larger than :max-weight."
         {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
          :max-entry-weight max-entry-weight
          :max-weight max-weight})))
     (->BoundedContinuationStore
      (atom {:entries {}
             :order []
             :weight 0
             :families {}
             :tombstones {}
             :tombstone-order []})
      (atom {:hits 0
             :misses 0
             :puts 0
             :publications 0
             :replacements 0
             :evictions 0
             :rejections 0
             :errors 0
             :miss-reasons {}
             :by-kind {}})
      max-entries
      max-weight
      max-entry-weight))))

(defn- metric!
  [store kind metric]
  (swap!
   (:metrics store)
   (fn [metrics]
     (-> metrics
         (update metric (fnil inc 0))
         (update-in [:by-kind kind metric] (fnil inc 0))))))

(defn- without-key
  [order key]
  (into [] (remove #(= key %)) order))

(defn- touch
  [order key]
  (conj (without-key order key) key))

(defn- checkpoint-family-key
  "Returns the exact checkpoint identity with only its plan fingerprint
  removed. This makes `:plan-mismatch` classification constant-time."
  [key]
  (let [[scope kind checkpoint] key]
    (when (and (vector? checkpoint) (= 7 (count checkpoint)))
      [scope kind
       [(nth checkpoint 0)
        (nth checkpoint 1)
        (nth checkpoint 3)
        (nth checkpoint 4)
        (nth checkpoint 5)
        (nth checkpoint 6)]])))

(defn- add-family
  [current key]
  (if-let [family (checkpoint-family-key key)]
    (update-in current [:families family] (fnil inc 0))
    current))

(defn- remove-family
  [current key]
  (if-let [family (checkpoint-family-key key)]
    (let [remaining (dec (get-in current [:families family] 0))]
      (if (pos? remaining)
        (assoc-in current [:families family] remaining)
        (update current :families dissoc family)))
    current))

(defn- remember-tombstone
  [current key reason limit]
  (let [order (touch (:tombstone-order current) key)
        tombstones (assoc (:tombstones current) key reason)
        excess (max 0 (- (count order) limit))
        expired (take excess order)]
    (assoc current
           :tombstones (apply dissoc tombstones expired)
           :tombstone-order (subvec order excess))))

(defn- evict-oldest
  [{:keys [entries order weight] :as current} tombstone-limit]
  (if-let [oldest (first order)]
    (let [entry (get entries oldest)]
      [(remember-tombstone
        (-> current
            (assoc :entries (dissoc entries oldest)
                   :order (subvec order 1)
                   :weight (- weight (:weight entry)))
            (remove-family oldest))
        oldest :evicted tombstone-limit)
       true])
    [current false]))

(defn- enforce-bounds
  [current max-entries max-weight]
  (loop [state current
         evictions 0]
    (if (or (> (count (:entries state)) max-entries)
            (> (:weight state) max-weight))
      (let [[next-state evicted?]
            (evict-oldest state max-entries)]
        (if evicted?
          (recur next-state (inc evictions))
          [state evictions]))
      [state evictions])))

(defn- plan-mismatch?
  [families key]
  (pos? (get families (checkpoint-family-key key) 0)))

(defn- missing-reason
  [{:keys [families tombstones]} key]
  (or (get tombstones key)
      (when (plan-mismatch? families key) :plan-mismatch)
      :absent))

(defn- miss!
  [store kind reason]
  (swap!
   (:metrics store)
   (fn [metrics]
     (-> metrics
         (update :misses (fnil inc 0))
         (update-in [:miss-reasons reason] (fnil inc 0))
         (update-in [:by-kind kind :misses] (fnil inc 0))
         (update-in [:by-kind kind :miss-reasons reason]
                    (fnil inc 0))))))

(defn- lookup!
  "Context lookup: absence is counted immediately; a present entry becomes a
  hit only after stable-page validates its authenticated boundary."
  [store kind key]
  (try
    (let [state @(:state store)
          entry (get-in state [:entries key])]
      (if (and entry (= kind (:kind entry)))
        (:value entry)
        (do
          (miss! store kind (missing-reason state key))
          nil)))
    (catch #?(:clj Exception :cljs :default) _
      (metric! store kind :errors)
      nil)))

(defn- peek-entry
  [store kind key]
  (let [entry (get-in @(:state store) [:entries key])]
    (when (= kind (:kind entry))
      (:value entry))))

(defn- checkpoint-hit!
  [store kind]
  (metric! store kind :hits))

(defn- checkpoint-miss!
  [store kind reason]
  (miss! store kind reason))

(defn- mark-unavailable-if-absent!
  "Records why a new key was rejected without destroying older valid state.

  A later, overweight checkpoint commonly shares the latest-only key with an
  earlier frontier. Dropping the attempted value must not turn admission
  rejection into eviction of that independently reusable frontier."
  [store key reason]
  (swap! (:state store)
         (fn [current]
           (if (contains? (:entries current) key)
             current
             (remember-tombstone
              current key reason (:max-entries store)))))
  false)

(defn get!
  [store kind key]
  (try
    (let [entry (get-in @(:state store) [:entries key])]
      (if (and entry (= kind (:kind entry)))
        (do
          (metric! store kind :hits)
          (:value entry))
        (do
          (miss! store kind (missing-reason @(:state store) key))
          nil)))
    (catch #?(:clj Exception :cljs :default) _
      (metric! store kind :errors)
      nil)))

(defn put!
  [store kind key value entry-weight]
  (try
    (if-not (and (integer? entry-weight)
                 (not (neg? entry-weight))
                 (<= entry-weight (:max-entry-weight store)))
      (do
        (mark-unavailable-if-absent! store key :overweight)
        (metric! store kind :rejections)
        false)
      (let [eviction-count (atom 0)
            replaced? (atom false)]
        (swap!
         (:state store)
         (fn [{:keys [entries order]
               current-weight :weight
               :as current}]
           (let [prior (get entries key)
                 _ (reset! replaced? (some? prior))
                 current'
                 (cond->
                  (-> current
                      (assoc
                       :entries
                       (assoc
                        entries
                        key
                        {:kind kind
                         :value value
                         :weight entry-weight})
                       ;; Checkpoint hits normally publish greater progress
                       ;; under the same key. Keeping an existing key in its
                       ;; FIFO slot makes that hot path O(1); a genuinely new
                       ;; key is appended and bounds remain exact.
                       :order (if prior order (conj order key))
                       :weight
                       (+ (- entry-weight (or (:weight prior) 0))
                          current-weight))
                      (update :tombstones dissoc key)
                      (update :tombstone-order without-key key))
                   (nil? prior) (add-family key))
                 [bounded evictions]
                 (enforce-bounds
                  current'
                  (:max-entries store)
                  (:max-weight store))]
             (reset! eviction-count evictions)
             bounded)))
        (dotimes [_ @eviction-count]
          (metric! store kind :evictions))
        (metric! store kind :puts)
        (let [published? (contains? (:entries @(:state store)) key)]
          (when published?
            (metric! store kind :publications)
            (when @replaced?
              (metric! store kind :replacements)))
          published?)))
    (catch #?(:clj Exception :cljs :default) _
      (metric! store kind :errors)
      false)))

(defn clear!
  [store]
  (reset! (:state store)
          {:entries {} :order [] :weight 0 :families {}
           :tombstones {} :tombstone-order []})
  nil)

(defn stats
  [store]
  (let [{:keys [entries weight]} @(:state store)]
    (assoc @(:metrics store)
           :entries (count entries)
           :weight weight
           :max-entries (:max-entries store)
           :max-weight (:max-weight store)
           :max-entry-weight (:max-entry-weight store))))

(defn validate-context!
  [context]
  (when-not
   (and
    (map? context)
    (false? (:required? context))
    (true? (:opaque-values? context))
    (every?
     #(fn? (get context %))
     [:peek :get :hit! :miss! :put!]))
    (throw
     (ex-info
      "Continuation context does not satisfy the adapter-neutral contract."
      {:type :eacl/internal-continuation-contract :eacl/error :eacl/internal-continuation-contract
       :context-keys (set (keys context))})))
  context)

(defn private-context
  "Builds engine callbacks scoped to one client, selected snapshot, and query.

  The store itself supplies client isolation. The digest commits to every
  cross-request semantic input, while the final edge/page key identifies the
  resumable frontier within that lineage."
  ([store adapter operation query-identity]
   (private-context store adapter operation query-identity {}))
  ([store adapter operation query-identity
    {:keys [request-lineage request-proof-frame populate-cache?]
     :or {populate-cache? true}}]
   (when store
     (let [basis-identity (:basis-identity request-proof-frame)
           derived-lineage
           (when basis-identity
             {:source-scope
              (select-keys basis-identity [:backend :source-id :branch])
              :source-lifecycle (:source-lifecycle basis-identity)})
           _
           (when (and request-lineage derived-lineage
                      (not= (secure/canonicalize request-lineage)
                            (secure/canonicalize derived-lineage)))
             (throw
              (ex-info
               "Continuation lineage differs from its request proof frame."
               {:type :eacl/internal-continuation-contract
                :eacl/error :eacl/internal-continuation-contract
                :request-lineage request-lineage
                :derived-lineage derived-lineage})))
           lineage (or request-lineage derived-lineage)
           scope
           (when lineage
             (secure/canonical-digest
              "eacl/client-private-continuation/v1"
              {:version context-version
               :backend (backend/backend-id adapter)
               ;; The store is cleared during explicit lifecycle rotation,
               ;; but a late publisher is also isolated by this lineage.
               :lineage lineage
               :adapter-fingerprint (backend/fingerprint adapter)
               :identity-contract (backend/identity-contract adapter)
               :operation operation
               :query query-identity}))
          key-for (fn [kind key] [scope kind key])]
       (when scope
         (validate-context!
          {:required? false
           :opaque-values? true
           :peek
           #(peek-entry store :recursive-continuation
                        (key-for :recursive-continuation %))
           :get
           #(lookup! store :recursive-continuation
                     (key-for :recursive-continuation %))
           :hit!
           (fn []
             (checkpoint-hit! store :recursive-continuation))
           :miss!
           (fn [reason]
             (checkpoint-miss!
              store :recursive-continuation reason))
           :put!
           (fn [edge value weight]
             (and populate-cache?
                  (put!
                   store :recursive-continuation
                   (key-for :recursive-continuation edge)
                   value weight)))}))))))
