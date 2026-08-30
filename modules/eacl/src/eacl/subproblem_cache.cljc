(ns eacl.subproblem-cache
  "Count-bounded private storage for completed authorization subproblems.

  Exact denotation and completed-answer retention use two independent standard
  LRU stores. This namespace owns key selection, completed-value validation,
  and publication of request-owned miss results; the LRU adapter owns
  retention."
  (:refer-clojure :exclude [resolve])
  (:require [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as lru]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.secure-format :as secure-format]
            #?(:clj
               [eacl.formal.production-kernel :as production-kernel]
               :cljs
               [eacl.formal.production-kernel-cljs :as production-kernel])))

(def ^:dynamic *store*
  "The request-bound answer/denotation store for one cached evaluation."
  nil)

(def ^:dynamic *exact-denotation-key-fn*
  "Builds a complete exact-denotation composite key from a semantic key.

  A nil or incomplete binding makes bound cache operations bypass storage.
  Production cached evaluation binds this constructor so every operator key
  carries its source lifecycle, exact basis, and ABI identity."
  nil)

(def ^:dynamic *populate?*
  "False for a read-only cache request. Lookups remain active."
  true)

(def default-decision-kernel
  production-kernel/default-selection)

(def ^:dynamic *decision-kernel*
  "Generated-kernel selection inherited from the enclosing public client."
  default-decision-kernel)

(def ^:private known-tiers #{:denotation :answer})
(def snapshot-tier-priority [:answer :denotation])
(def ^:private default-denotation-max-entries 4096)
(def ^:private default-answer-max-entries 1024)
(def ^:private option-keys
  #{:denotation-max-entries
    :answer-max-entries
    :telemetry?})
(def ^:private publication-option-keys #{:valid?})

(def snapshot-format
  "Version identifier for flat process-neutral subproblem snapshots."
  :eacl.subproblem-cache/snapshot-v2)

(defrecord SubproblemStore
           [tiers capacities content-revision telemetry-enabled? metrics])

(defn- invalid-config!
  [message data]
  (throw
   (ex-info message
            (merge {:type :eacl/invalid-config
                    :eacl/error :eacl/invalid-config}
                   data))))

(defn- positive-capacity!
  [option value]
  (when-not (and (proof-frame/generation? value) (pos? value))
    (invalid-config!
     "Subproblem cache capacities must be positive safe integers."
     {:option option :value value}))
  value)

(defn store
  "Creates independent count-bounded denotation and answer LRU stores."
  ([]
   (store {} nil))
  ([options]
   (store options nil))
  ([{:keys [denotation-max-entries answer-max-entries telemetry?]
     :or {denotation-max-entries default-denotation-max-entries
          answer-max-entries default-answer-max-entries
          telemetry? true}
     :as options}
    content-revision]
   (when-not (map? options)
     (invalid-config! "Subproblem cache options must be a map."
                      {:options options}))
   (let [unknown (seq (sort-by pr-str (remove option-keys (keys options))))]
     (when unknown
       (invalid-config!
        "Unknown subproblem cache option."
        {:unknown-keys (vec unknown)
         :known-keys (vec (sort option-keys))})))
   (when-not (boolean? telemetry?)
     (invalid-config! "Subproblem cache :telemetry? must be boolean."
                      {:telemetry? telemetry?}))
   (let [capacities
         {:denotation
          (positive-capacity! :denotation-max-entries
                              denotation-max-entries)
          :answer
          (positive-capacity! :answer-max-entries answer-max-entries)}]
     (->SubproblemStore
      (into {} (map (fn [[tier capacity]] [tier (lru/store capacity)]))
            capacities)
      capacities
      content-revision
      telemetry?
      (atom {:hits 0
             :puts 0
             :publication-races 0
             :publication-rejections 0
             :retention-ineligible-pages 0
             :store-errors 0
             :invalid-results 0
             :lookup-probes 0
             :lookup-misses 0
             :denotation-hits 0
             :answer-hits 0
             :avoided-backend-operations 0})))))

(defn store?
  [value]
  (instance? SubproblemStore value))

(defn- validate-store!
  [store]
  (when-not (store? store)
    (invalid-config! "Expected an EACL subproblem store." {:store store})))

(defn- validate-tier!
  [store tier]
  (validate-store! store)
  (when-not (contains? known-tiers tier)
    (invalid-config! "Unknown EACL subproblem cache tier."
                     {:tier tier :known-tiers known-tiers})))

(defn- record-metrics!
  [store f & args]
  (when (:telemetry-enabled? store)
    (apply swap! (:metrics store) f args))
  nil)

(defn- record-content-change!
  [store]
  (when-let [content-revision (:content-revision store)]
    (try
      (if (fn? content-revision)
        (content-revision)
        ;; Retain the old atom form for isolated low-level callers. Production
        ;; lifecycles pass a token-guarded callback so detached stores cannot
        ;; dirty the currently installed lifecycle's revision.
        (swap! content-revision inc))
      (catch #?(:clj Throwable :cljs :default) _
        ;; Dirty tracking is optional cache bookkeeping. It cannot turn an
        ;; otherwise valid authorization result into a request failure.
        nil)))
  nil)

(defn- tier-hit-metric
  [tier]
  (case tier
    :denotation :denotation-hits
    :answer :answer-hits))

(defn- exact-cache-tier
  [tier]
  (case tier
    :denotation :exact-denotation
    :answer :exact-answer))

(defn stats
  "Returns behavioral counters and actual count capacities.

  No field is a byte estimate or a mirror of library-private recency state."
  [store]
  (validate-store! store)
  (assoc @(:metrics store)
         :telemetry-enabled? (:telemetry-enabled? store)
         :tiers
         (into {}
               (map (fn [[tier tier-store]]
                      [tier {:entries (lru/entry-count tier-store)
                             :max-entries (get (:capacities store) tier)}]))
               (:tiers store))))

(defn record-avoided-backend-operation!
  ([]
   (record-avoided-backend-operation! *store*))
  ([store]
   (when store
     (record-metrics!
      store update :avoided-backend-operations (fnil inc 0)))
   nil))

(defn- validate-publication-options!
  [options]
  (when-not (map? options)
    (invalid-config! "Subproblem cache publication options must be a map."
                     {:options options}))
  (let [unknown
        (seq (sort-by pr-str
                      (remove publication-option-keys (keys options))))]
    (when unknown
      (invalid-config!
       "Unknown subproblem cache publication option."
       {:unknown-keys (vec unknown)
        :known-keys (vec (sort publication-option-keys))})))
  (when-not (contains? options :valid?)
    (invalid-config!
     "Subproblem cache publication requires an explicit :valid? validator."
     {:required-key :valid?}))
  (when-not (ifn? (:valid? options))
    (invalid-config! "Subproblem cache publication :valid? must be callable."
                     {:valid? (:valid? options)}))
  options)

(defn- valid-value?
  [valid? value]
  (try
    (boolean (valid? value))
    (catch #?(:clj Throwable :cljs :default) _
      false)))

(defn- completed-page
  [value]
  ;; Peel only EACL-owned storage envelopes. Arbitrary scalar/tree maps may
  ;; legitimately contain a :value key and must not inherit the page rule.
  (let [candidate
        (if (and (map? value)
                 (= :eacl.cache/completed-answer-v2 (:format value))
                 (= #{:format :value :cache-basis :computed-revision
                      :computed-exact-locator}
                    (set (keys value))))
          (:value value)
          value)]
    (when (and (map? candidate)
               (vector? (:data candidate))
               (map? (:page-info candidate)))
      candidate)))

(defn retention-eligible?
  "True when a completed value is eligible for shared tier retention.

  This is intentionally about retention only. It does not validate operation
  semantics and never changes the value returned to the request."
  [tier value]
  (or (not= :answer tier)
      (let [page (completed-page value)]
        (or (nil? page) (<= (count (:data page)) 1000)))))

(declare complete-storage-key?)

(defn- validate-storage-key!
  [tier storage-key]
  (when-not (complete-storage-key? tier storage-key)
    (invalid-config! "Cache storage key must be a complete v2 composite key."
                     {:tier tier :key storage-key}))
  storage-key)

(defn- resident!
  [store tier storage-key]
  (let [tier-store (get (:tiers store) tier)
        resident
        (try
          (lru/lookup! tier-store storage-key)
          (catch #?(:clj Throwable :cljs :default) _
            (record-metrics! store update :store-errors inc)
            nil))]
    (if-not (:found? resident)
      (do
        (record-metrics!
         store
         #(-> %
              (update :lookup-probes inc)
              (update :lookup-misses inc)))
        nil)
      resident)))

(defn- record-lookup-miss!
  [store]
  (record-metrics!
   store
   #(-> %
        (update :lookup-probes inc)
        (update :lookup-misses inc)))
  nil)

(defn- hit-result!
  [store tier value]
  (record-metrics!
   store
   #(-> %
        (update :lookup-probes inc)
        (update :hits inc)
        (update (tier-hit-metric tier) inc)))
  {:value value
   :cached? true
   :cache-tier (exact-cache-tier tier)})

(defn lookup-eligible!
  "Looks up one mapping and applies only request-dependent managed eligibility."
  [store tier storage-key eligible?]
  (when (execution/cache-stage-available?)
    (let [tier-store (get (:tiers store) tier)]
      (loop []
        (let [resident
              (try
                (lru/peek-entry tier-store storage-key)
                (catch #?(:clj Throwable :cljs :default) _
                  (record-metrics! store update :store-errors inc)
                  ::store-error))]
          (cond
            (= ::store-error resident)
            (record-lookup-miss! store)

            (not (:found? resident))
            (record-lookup-miss! store)

            ;; A future managed value may be ineligible for an older concurrent
            ;; request while remaining valid for later readers. It is not a
            ;; retrieval, so rejection neither deletes nor refreshes the mapping.
            (not (valid-value? eligible? (:value resident)))
            (record-lookup-miss! store)

            :else
            (let [touched?
                  (try
                    (lru/hit-if-value!
                     tier-store storage-key (:value resident))
                    (catch #?(:clj Throwable :cljs :default) _
                      (record-metrics! store update :store-errors inc)
                      ::store-error))]
              (cond
                (= ::store-error touched?)
                (record-lookup-miss! store)

                touched?
                (hit-result! store tier (:value resident))

                ;; Eviction or replacement raced the peek. Re-read and apply
                ;; eligibility to the mapping that can actually be touched.
                :else
                (recur)))))))))

(defn lookup!
  "Looks up and touches one already validated immutable exact mapping.

  Supported publication and restore transitions validate values before they
  enter the private store. Nil and false remain distinguishable from absence.
  Exact lookup is only LRU membership/touch plus metrics. Managed completed
  answers use `lookup-eligible!` for their separate causal-revision obligation.

  This is a private-runtime hot path: the store, tier, and composite key arrive
  from validated configuration and EACL key constructors. Direct application
  calls or mutation of its backing atoms are outside the supported contract."
  [store tier storage-key]
  (when (execution/cache-stage-available?)
    (when-let [resident (resident! store tier storage-key)]
      (hit-result! store tier (:value resident)))))

(defn- request-publication-rejection
  []
  (when-not (execution/cache-stage-available?)
    (if (execution/expired?) :deadline-expired :cancelled)))

(defn- reject-publication!
  [store reason]
  (record-metrics! store update :publication-rejections inc)
  {:published? false :reason reason})

(defn publish!
  "Publishes one already-computed value under an opaque storage key.

  Validation and page eligibility run once before the pure absent-key LRU
  transformation. A concurrent same-key publisher wins, while this request
  still returns its own completed value."
  [store tier storage-key options value]
  (validate-tier! store tier)
  (validate-publication-options! options)
  (let [storage-key (validate-storage-key! tier storage-key)
        valid? (:valid? options)
        initial-rejection (request-publication-rejection)]
    (cond
      initial-rejection
      (reject-publication! store initial-rejection)

      (not (valid-value? valid? value))
      (do
        (record-metrics!
         store #(-> %
                    (update :invalid-results inc)
                    (update :publication-rejections inc)))
        {:published? false :reason :invalid-value})

      (not (retention-eligible? tier value))
      (do
        (record-metrics!
         store #(-> %
                    (update :retention-ineligible-pages inc)
                    (update :publication-rejections inc)))
        {:published? false :reason :page-too-large})

      :else
      (if-let [final-rejection (request-publication-rejection)]
        (reject-publication! store final-rejection)
        (try
          (let [published?
                (lru/put-if-absent! (get (:tiers store) tier)
                                    storage-key value)]
            (if published?
              (do
                (record-metrics! store update :puts inc)
                (record-content-change! store)
                {:published? true :reason :published})
              (do
                (record-metrics! store update :publication-races inc)
                {:published? false :reason :compatible-winner})))
          (catch #?(:clj Throwable :cljs :default) _
            (record-metrics!
             store #(-> %
                        (update :store-errors inc)
                        (update :publication-rejections inc)))
            {:published? false :reason :store-error}))))))

(defn- exact-denotation-storage-key
  [semantic-key]
  (when *exact-denotation-key-fn*
    (let [candidate (*exact-denotation-key-fn* semantic-key)]
      (when (complete-storage-key? :denotation candidate)
        candidate))))

(defn lookup-denotation!
  "Looks up a denotation key in the dynamically selected exact store."
  [semantic-key]
  (when *store*
    (when-let [storage-key (exact-denotation-storage-key semantic-key)]
      (lookup! *store* :denotation storage-key))))

(defn publish-denotation!
  "Publishes a denotation key into the dynamically selected exact store."
  [semantic-key options value]
  (if-not *store*
    {:published? false :reason :disabled}
    (if-let [storage-key (exact-denotation-storage-key semantic-key)]
      (publish! *store* :denotation storage-key options value)
      {:published? false :reason :incomplete-key})))

(defn- canonical-entry-sort-key
  [{:keys [tier key]}]
  (secure-format/encode-canonical
   [tier key]
   ;; These are already-admitted in-process keys. Snapshot byte limits belong
   ;; to the host's authenticated encoding boundary, not semantic cache keys.
   {:maximum-size secure-format/maximum-safe-integer
    :maximum-depth 64
    :maximum-entries 131072}))

(defn ^:no-doc resident-tier-entries
  "Returns unsorted resident entry records without serializing their keys.

  Iteration order is deliberately neither an LRU nor snapshot contract."
  [store tier]
  (validate-tier! store tier)
  (mapv (fn [[key value]] {:tier tier :key key :value value})
        (lru/entries (get (:tiers store) tier))))

(defn snapshot-tier-entries
  "Returns canonical flat entry records for one tier.

  Standard-cache recency and priority internals are intentionally omitted."
  [store tier]
  (->> (resident-tier-entries store tier)
       (sort-by canonical-entry-sort-key)
       vec))

(defn snapshot-value
  "Builds the flat v2 value from already selected tier entries."
  [store tier->entries]
  (validate-store! store)
  (let [entries
        (->> snapshot-tier-priority
             (mapcat #(get tier->entries % []))
             (sort-by canonical-entry-sort-key)
             vec)]
    {:format snapshot-format
     :entries entries
     :entry-count (count entries)}))

(defn- positive-snapshot-bound!
  [option value]
  (when-not (and (proof-frame/generation? value) (pos? value))
    (throw
     (ex-info "Cache snapshot bounds must be positive integers."
              {:type :eacl/invalid-bound
               :eacl/error :eacl/invalid-bound
               :option option :value value})))
  value)

(defn export-snapshot
  "Exports a deterministic flat mapping sequence under an entry bound."
  ([store]
   (export-snapshot store
                    {:max-entries (reduce + 0 (vals (:capacities store)))}))
  ([store {:keys [max-entries] :as options}]
   (validate-store! store)
   (when-not (= #{:max-entries} (set (keys options)))
     (invalid-config! "Snapshot export accepts only :max-entries."
                      {:options options}))
   (positive-snapshot-bound! :max-entries max-entries)
   (let [selected
         (loop [tiers snapshot-tier-priority
                remaining max-entries
                result {}]
           (if (or (zero? remaining) (empty? tiers))
             result
             (let [tier (first tiers)
                   entries (snapshot-tier-entries store tier)
                   retained (vec (take remaining entries))]
               (recur (rest tiers)
                      (- remaining (count retained))
                      (assoc result tier retained)))))]
     (snapshot-value store selected))))

(defn- incompatible-snapshot!
  [message data]
  (throw
   (ex-info message
            (merge {:type :eacl/cache-snapshot-incompatible
                    :eacl/error :eacl/cache-snapshot-incompatible}
                   data))))

(defn- closed-map?
  [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- complete-storage-key?
  [tier key]
  (and (vector? key)
       (= 3 (count key))
       (= cache-key/key-format (first key))
       (= (if (= :answer tier)
            :authorization-answer
            :authorization-subproblem)
          (second key))
       (let [identity (nth key 2)]
         (and (vector? identity)
              (= 6 (count identity))
              (= tier (nth identity 0))
              (contains? (if (= :answer tier)
                           #{:exact :managed}
                           #{:exact})
                         (nth identity 1))
              (every? some? (subvec identity 2 6))))))

(defn restore-store
  "Constructs fresh standard LRUs from an already trusted decoded v2 value.

  `:entry-valid?` is the outer cache's operation-specific closed key/value
  validator. It is invoked exactly once per entry after structural and
  capacity validation and before any LRU insertion."
  ([snapshot options]
   (invalid-config!
    "Cache snapshot restore requires an :entry-valid? callback."
    {:option :entry-valid?}))
  ([snapshot options content-revision]
   (invalid-config!
    "Cache snapshot restore requires an :entry-valid? callback."
    {:option :entry-valid?}))
  ([snapshot options content-revision
    {:keys [entry-valid?] :as restore-options}]
   (when-not (and (= #{:entry-valid?} (set (keys restore-options)))
                  (fn? entry-valid?))
     (invalid-config!
      "Cache snapshot restore requires exactly one :entry-valid? callback."
      {:restore-options restore-options}))
   (when-not (closed-map? snapshot #{:format :entries :entry-count})
     (incompatible-snapshot!
      "Malformed subproblem cache snapshot."
      {:snapshot-keys (some-> snapshot keys set)}))
   (when-not (= snapshot-format (:format snapshot))
     (incompatible-snapshot!
      "Unsupported subproblem cache snapshot format."
      {:format (:format snapshot)}))
   (when-not (vector? (:entries snapshot))
     (incompatible-snapshot! "Cache snapshot entries must be a vector." {}))
   (when-not (and (proof-frame/generation? (:entry-count snapshot))
                  (= (:entry-count snapshot) (count (:entries snapshot))))
     (incompatible-snapshot!
      "Cache snapshot entry count does not match entries."
      {:entry-count (:entry-count snapshot)
       :actual (count (:entries snapshot))}))
   (doseq [entry (:entries snapshot)]
     (when-not (closed-map? entry #{:tier :key :value})
       (incompatible-snapshot! "Malformed cache snapshot entry."
                               {:entry entry}))
     (when-not (contains? known-tiers (:tier entry))
       (incompatible-snapshot! "Unknown cache snapshot tier."
                               {:tier (:tier entry)}))
     (when-not (complete-storage-key? (:tier entry) (:key entry))
       (incompatible-snapshot! "Cache snapshot key is incomplete."
                               {:tier (:tier entry) :key (:key entry)})))
   (let [identities (mapv (juxt :tier :key) (:entries snapshot))]
     (when-not (= (count identities) (count (set identities)))
       (incompatible-snapshot!
        "Cache snapshot contains duplicate tier/key mappings." {})))
   (let [ordered-entries
         (try
           (vec (sort-by canonical-entry-sort-key (:entries snapshot)))
           (catch #?(:clj Throwable :cljs :default) _
             (incompatible-snapshot!
              "Cache snapshot key is not canonical portable data." {})))
         destination (store options content-revision)
         counts (frequencies (map :tier (:entries snapshot)))]
     (doseq [tier known-tiers]
       (when (> (get counts tier 0) (get (:capacities destination) tier))
         (incompatible-snapshot!
          "Cache snapshot tier exceeds destination capacity."
          {:tier tier
           :entries (get counts tier 0)
           :max-entries (get (:capacities destination) tier)})))
     (doseq [{:keys [tier] :as entry} ordered-entries]
       (let [valid?
             (try
               (boolean (entry-valid? entry))
               (catch #?(:clj Throwable :cljs :default) _ false))]
         (when-not (and valid?
                        (retention-eligible? tier (:value entry)))
           (incompatible-snapshot!
            "Cache snapshot entry violates its key/value contract."
            {:tier tier :key (:key entry)}))))
     ;; Canonical input order is not trusted; normalize before reconstructing
     ;; deterministic initial recency in fresh empty LRUs.
     (doseq [{:keys [tier key value]} ordered-entries]
       (when-not (lru/put-if-absent! (get (:tiers destination) tier) key value)
         (incompatible-snapshot!
          "Cache snapshot entry could not be restored."
          {:tier tier :key key})))
     destination)))
