(ns eacl.continuation
  "Adapter-neutral, client-private continuation storage.

  Public cursors authenticate query and snapshot lineage. Opaque traversal
  state stays in this bounded in-process store and is addressed by that
  authenticated lineage. Cache loss is always a performance miss: callers can
  deterministically replay the public boundary."
  (:require [eacl.backend.v8 :as backend]
            [eacl.proof-frame :as proof-frame]
            [eacl.secure-format :as secure]))

(def ^:private context-version 2)
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
           :weight 0})
    (atom {:hits 0
           :misses 0
           :puts 0
           :evictions 0
           :rejections 0
           :errors 0
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

(defn- evict-oldest
  [{:keys [entries order weight] :as current}]
  (if-let [oldest (first order)]
    (let [entry (get entries oldest)]
      [(assoc current
              :entries (dissoc entries oldest)
              :order (subvec order 1)
              :weight (- weight (:weight entry)))
       true])
    [current false]))

(defn- enforce-bounds
  [current max-entries max-weight]
  (loop [state current
         evictions 0]
    (if (or (> (count (:entries state)) max-entries)
            (> (:weight state) max-weight))
      (let [[next-state evicted?] (evict-oldest state)]
        (if evicted?
          (recur next-state (inc evictions))
          [state evictions]))
      [state evictions])))

(defn get!
  [store kind key]
  (try
    (let [entry (get-in @(:state store) [:entries key])]
      (if (and entry (= kind (:kind entry)))
        (do
          (swap! (:state store) update :order touch key)
          (metric! store kind :hits)
          (:value entry))
        (do
          (metric! store kind :misses)
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
        (metric! store kind :rejections)
        false)
      (let [eviction-count (atom 0)]
        (swap!
         (:state store)
         (fn [{:keys [entries order]
               current-weight :weight
               :as current}]
           (let [prior (get entries key)
                 current'
                 (assoc
                  current
                  :entries
                  (assoc
                   entries
                   key
                   {:kind kind
                    :value value
                    :weight entry-weight})
                  :order (touch order key)
                  :weight
                  (+ (- entry-weight (or (:weight prior) 0))
                     current-weight))
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
        (contains? (:entries @(:state store)) key)))
    (catch #?(:clj Exception :cljs :default) _
      (metric! store kind :errors)
      false)))

(defn evict!
  [store key]
  (try
    (let [removed? (atom false)]
      (swap!
       (:state store)
       (fn [{:keys [entries order weight] :as current}]
         (if-let [entry (get entries key)]
           (do
             (reset! removed? true)
             (assoc current
                    :entries (dissoc entries key)
                    :order (without-key order key)
                    :weight (- weight (:weight entry))))
           current)))
      @removed?)
    (catch #?(:clj Exception :cljs :default) _
      (swap! (:metrics store) update :errors (fnil inc 0))
      false)))

(defn clear!
  [store]
  (reset! (:state store) {:entries {} :order [] :weight 0})
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
     [:get :evict! :put!
      :get-page :put-page!
      :get-heads :put-heads!]))
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
    {:keys [snapshot-identity request-proof-frame populate-cache?]
     :or {populate-cache? true}}]
   (when store
     (let [basis-identity (:basis-identity request-proof-frame)
           scope
          (secure/canonical-digest
           "eacl/client-private-continuation/v1"
           {:version context-version
            :backend (backend/backend-id adapter)
            :source-scope
            (some-> basis-identity
                    (select-keys
                     [:backend :source-id :branch :source-lifecycle]))
            ;; The store is cleared during explicit lifecycle rotation, but
            ;; an in-flight request may finish after that clear.  Including
            ;; the lifecycle in the address makes any such late publication
            ;; unreachable to requests in the replacement lifecycle.
            :source-lifecycle
            (:source-lifecycle basis-identity)
            :adapter-fingerprint (backend/fingerprint adapter)
            :identity-contract (backend/identity-contract adapter)
            :schema-generation
            (let [frame
                  (if (and request-proof-frame
                           (identical?
                            adapter (:adapter request-proof-frame)))
                    request-proof-frame
                    (proof-frame/request-frame adapter))
                  proof
                  (proof-frame/resolve!
                   frame [])]
              (when (proof-frame/complete? proof)
                (:schema-generation proof)))
            :snapshot-identity
            (or snapshot-identity
                {:kind :exact
                 :snapshot-id (backend/invoke adapter :snapshot-id)})
            :operation operation
            :query query-identity})
          key-for (fn [kind key] [scope kind key])]
       (validate-context!
       {:required? false
        :opaque-values? true
        :get
        #(get! store :recursive-continuation
               (key-for :recursive-continuation %))
        :evict!
        (fn [edge]
          (boolean
           (or
            (evict! store (key-for :recursive-continuation edge))
            (evict! store (key-for :acyclic-continuation edge)))))
        :put!
        (fn [edge value weight]
          (and populate-cache?
               (put!
                store :recursive-continuation
                (key-for :recursive-continuation edge)
                value weight)))
        :get-page
        #(get! store :recursive-page
               (key-for :recursive-page %))
        :put-page!
        (fn [page-key value weight]
          (and populate-cache?
               (put!
                store :recursive-page
                (key-for :recursive-page page-key)
                value weight)))
        :get-heads
        #(get! store :acyclic-continuation
               (key-for :acyclic-continuation %))
        :put-heads!
        (fn [edge value weight]
          (and populate-cache?
               (put!
                store :acyclic-continuation
                (key-for :acyclic-continuation edge)
                value weight)))})))))
