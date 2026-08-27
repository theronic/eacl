(ns eacl.subproblem-cache
  "Bounded client-private storage for immutable authorization subproblems.

  The store contains performance state only. Every admitted value belongs to
  one exact selected immutable generation; replacing that generation makes the
  complete store unreachable."
  (:refer-clojure :exclude [resolve])
  (:require [eacl.execution :as execution]
            #?(:clj
               [eacl.formal.production-kernel]
               :cljs
               [eacl.formal.production-kernel-cljs])))

(def ^:dynamic *store*
  "The exact-generation subproblem store bound around one cached evaluation.

  nil is the cache-free path and performs no lookup, admission, or metric
  mutation."
  nil)

(def ^:dynamic *managed-store*
  "The schema-generation store used for relation-stamped projection reuse.

  This store is bound only for managed current-snapshot evaluation. Historical,
  raw, arbitrary-DB, cache-disabled, and unstamped evaluations leave it nil."
  nil)

(def ^:dynamic *managed-key-fn*
  "Returns a same-snapshot `{:schema-generation n :dependency-stamp n}` descriptor
  for one relation dependency, or nil when managed reuse is unavailable."
  nil)

(def ^:dynamic *managed-scope*
  "Portable backend/source/branch/lifecycle identity for managed projection keys."
  nil)

(def ^:dynamic *publication-attempt-limit*
  "Maximum best-effort CAS publication attempts for the owning request."
  4)

(def ^:dynamic *populate?*
  "False for a read-only cache request. Lookups and request-local memoization
  remain active, but no completed subproblem is published."
  true)

(def ^:dynamic *decision-kernel*
  "Generated-kernel selection inherited from the enclosing public client.

  Pure lookup, admission, and publication decisions run through generated
  code while storage mutation and value computation remain host-runtime
  responsibilities."
  #?(:clj
     eacl.formal.production-kernel/default-selection
     :cljs
     eacl.formal.production-kernel-cljs/default-selection))

(def ^:private known-tiers #{:projection :denotation :answer})
(def ^:private default-projection-max-weight (* 4 1024 1024))
(def ^:private default-denotation-max-weight (* 4 1024 1024))
(def ^:private default-answer-max-weight (* 16 1024 1024))
(def ^:private default-managed-proof-max-atoms 256)
(def ^:private lifecycle-key ::lifecycle)
(def ^:private option-keys
  #{:projection-max-weight :denotation-max-weight :answer-max-weight
    :managed-proof-max-atoms})

(defn- tier-hit-metric
  [tier]
  (case tier
    :projection :projection-hits
    :denotation :denotation-hits
    :answer :answer-hits))

(defn- managed-tier-hit-metric
  [tier]
  (case tier
    :projection :managed-projection-hits
    :denotation :managed-denotation-hits
    :answer :managed-answer-hits))

(defn- exact-cache-tier
  [tier]
  (case tier
    :projection :exact-projection
    :denotation :exact-denotation
    :answer :exact-answer))

(declare positive-weight! publication-weight-ceiling)

(defrecord SubproblemStore
           [state metrics budgets managed-proof-max-atoms content-revision])

(def snapshot-format
  "Version identifier for the process-neutral subproblem snapshot value."
  :eacl.subproblem-cache/snapshot-v1)

(def snapshot-tier-priority
  "Stable tier priority used by bounded cache snapshots."
  [:answer :projection :denotation])

(defn- lifecycle-token
  []
  #?(:clj (Object.)
     :cljs (js-obj)))

(defn with-decision-memo
  "Compatibility wrapper for one top-level authorization computation."
  [compute]
  (compute))

(defn- positive-weight!
  [option value]
  (when-not (and (integer? value) (pos? value))
    (throw (ex-info "Subproblem cache weights must be positive integers."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :option option
                     :value value})))
  value)

(defn store
  "Creates a weighted exact-generation subproblem store.

  Projection, denotation, and completed-answer budgets are deliberately
  isolated so one large fixed-point result cannot evict every hot
  relationship chunk, and a page-heavy answer workload cannot starve the
  traversal tiers. The `:answer` tier additionally enforces a per-entry
  ceiling of one quarter of its budget; a heavier completed answer is
  rejected and counted under `:oversized-rejections`."
  ([]
   (store {} nil))
  ([options]
   (store options nil))
  ([{:keys [projection-max-weight denotation-max-weight answer-max-weight
            managed-proof-max-atoms]
     :or {projection-max-weight default-projection-max-weight
          denotation-max-weight default-denotation-max-weight
          answer-max-weight default-answer-max-weight
          managed-proof-max-atoms default-managed-proof-max-atoms}
     :as options}
    content-revision]
   (let [unknown-keys (seq (sort (remove option-keys (keys options))))
         _
         (when unknown-keys
           (throw
            (ex-info
             "Unknown subproblem cache option."
             {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
              :unknown-keys (vec unknown-keys)
              :known-keys (vec (sort option-keys))})))
         budgets
         {:projection
          (positive-weight! :projection-max-weight projection-max-weight)
          :denotation
          (positive-weight! :denotation-max-weight denotation-max-weight)
          :answer
          (positive-weight! :answer-max-weight answer-max-weight)}
         managed-proof-max-atoms
         (positive-weight!
          :managed-proof-max-atoms managed-proof-max-atoms)]
     (->SubproblemStore
      (atom
       (assoc
        (into {}
              (map (fn [tier]
                     [tier {:entries {}
                            :lru []
                            :lru-head 0
                            :weight 0
                            :clock 0}]))
              known-tiers)
        lifecycle-key
        (lifecycle-token)))
      (atom {:hits 0
             :misses 0
             :puts 0
             :evictions 0
             :eviction-probes 0
             :oversized-rejections 0
             :invalid-results 0
             :failures 0
             :publication-races 0
             :publication-contention 0
             :publication-rejections 0
             :detached-publications 0
             :lookup-probes 0
             :lookup-misses 0
             :projection-hits 0
             :denotation-hits 0
             :answer-hits 0
             :acyclic-denotation-hits 0
             :recursive-component-hits 0
             :managed-projection-hits 0
             :managed-denotation-hits 0
             :managed-answer-hits 0
             :managed-proof-reads 0
             :managed-proof-hits 0
             :managed-proof-failures 0
             :managed-proof-overflows 0
             :avoided-backend-operations 0
             :fetched-projection-values 0})
      budgets
      managed-proof-max-atoms
      content-revision))))

(defn store?
  [value]
  (instance? SubproblemStore value))

(defn- record-content-change!
  [store]
  (when-let [revision (:content-revision store)]
    (swap! revision inc))
  nil)

(defn- validate-tier!
  [store tier]
  (when-not (store? store)
    (throw (ex-info "Expected an EACL subproblem store."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :store store})))
  (when-not (contains? known-tiers tier)
    (throw (ex-info "Unknown EACL subproblem cache tier."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :tier tier
                     :known-tiers known-tiers}))))

(defn stats
  [store]
  (when-not (store? store)
    (throw (ex-info "Expected an EACL subproblem store."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :store store})))
  (let [state @(:state store)]
    (assoc @(:metrics store)
           :managed-proof-max-atoms
           (:managed-proof-max-atoms store)
           :tiers
           (into {}
                 (map (fn [[tier {:keys [entries weight lru lru-head]}]]
                        [tier {:entries (count entries)
                               :lru-records (- (count lru) lru-head)
                               :weight weight
                               :max-weight (get (:budgets store) tier)}]))
                 (select-keys state known-tiers)))))

(defn clear!
  [store]
  (when-not (store? store)
    (throw (ex-info "Expected an EACL subproblem store."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :store store})))
  (let [changed? (some (comp seq :entries val)
                       (select-keys @(:state store) known-tiers))]
    (reset! (:state store)
            (assoc
             (into {}
                   (map (fn [tier]
                          [tier {:entries {}
                                 :lru []
                                 :lru-head 0
                                 :weight 0
                                 :clock 0}]))
                   known-tiers)
             lifecycle-key
             (lifecycle-token)))
    (when changed?
      (record-content-change! store)))
  nil)

(defn record-avoided-backend-operation!
  ([]
   (record-avoided-backend-operation! *store*))
  ([store]
   (when store
     (swap! (:metrics store)
            update :avoided-backend-operations (fnil inc 0)))
   nil))

(defn- remove-entry-if-token!
  [store tier key token]
  (let [removed? (volatile! false)]
    (swap! (:state store)
           (fn [state]
             (let [entry (get-in state [tier :entries key])]
               (if (and entry (identical? token (:token entry)))
                 (do
                   (vreset! removed? true)
                   (-> state
                       (update-in [tier :entries] dissoc key)
                       (update-in [tier :weight] - (:weight entry))))
                 state))))
    (when @removed?
      (record-content-change! store))
    @removed?))

(defn- current-lru-record?
  [entries [access key]]
  (= access (:access (get entries key))))

(defn- compact-lru
  [tier-state]
  (let [entries (:entries tier-state)
        active
        (into []
              (filter #(current-lru-record? entries %))
              (subvec (:lru tier-state)
                      (:lru-head tier-state)))]
    (assoc tier-state :lru active :lru-head 0)))

(defn- maybe-compact-lru
  [tier-state]
  (let [record-count (count (:lru tier-state))
        entry-count (count (:entries tier-state))
        maximum-records (max 1024 (* 2 (max 1 entry-count)))]
    (if (> record-count maximum-records)
      (compact-lru tier-state)
      tier-state)))

(defn- trim-tier
  [tier-state maximum-weight protected-key]
  (loop [current tier-state
         evictions 0
         probes 0]
    (if (<= (:weight current) maximum-weight)
      [(maybe-compact-lru current) evictions probes]
      (if-let [[victim-index victim entry victim-probes]
               (loop [index (:lru-head current)
                      victim-probes 0]
                 (when (< index (count (:lru current)))
                   (let [[access key] (nth (:lru current) index)
                         entry (get (:entries current) key)
                         victim-probes (inc victim-probes)]
                     (if (and (= access (:access entry))
                              (not= protected-key key))
                       [index key entry victim-probes]
                       (recur (inc index) victim-probes)))))]
        (recur
         (-> current
             (update :entries dissoc victim)
             (assoc :lru-head (inc victim-index))
             (update :weight - (:weight entry)))
         (inc evictions)
         (+ probes victim-probes))
        [(maybe-compact-lru
          (assoc current :lru-head (count (:lru current))))
         evictions
         probes]))))

(defn- publication-weight-ceiling
  "The maximum weight one entry may occupy in `tier`.

  Projection and denotation entries may fill their complete tier budget.
  Completed answers are additionally capped at one quarter of the answer
  budget so a single oversized page cannot displace every retained answer;
  a heavier answer is dropped at publication and counted under
  `:oversized-rejections`."
  [store tier]
  (let [budget (get (:budgets store) tier)]
    (if (= :answer tier)
      (max 1 (quot budget 4))
      budget)))

(defn- touch-entry!
  [store tier key token]
  (swap! (:state store)
         (fn [state]
           (let [entry (get-in state [tier :entries key])]
             (if (and entry (identical? token (:token entry)))
               (let [tick (inc (get-in state [tier :clock]))]
                 (-> state
                     (assoc-in [tier :clock] tick)
                     (assoc-in [tier :entries key :access] tick)
                     (update-in [tier :lru] conj [tick key])
                     (update tier maybe-compact-lru)))
               state))))
  nil)

(declare lookup!)

(defn publish!
  "Best-effort nonblocking publication of one already-computed value.

  Publication never owns semantic computation and never waits for another
  request. A compatible existing entry wins; CAS contention is retried only up
  to `maximum-attempts`; lifecycle detachment and capacity rejection are normal
  non-failing outcomes."
  ([store tier key options value]
   (publish! store tier key options value 4))
  ([store tier key {:keys [valid? weight-fn]
                    :or {valid? (constantly true)
                         weight-fn (constantly 1)}
                    :as options} value maximum-attempts]
   (publish! store tier key options value maximum-attempts
             (get @(:state store) lifecycle-key)))
  ([store tier key {:keys [valid? weight-fn]
                    :or {valid? (constantly true)
                         weight-fn (constantly 1)}} value maximum-attempts
    lifecycle]
   (validate-tier! store tier)
   ;; Publication is optional performance work. Once the request deadline has
   ;; expired, return the already-computed value without mutating cache state.
   ;; This also prevents a late request from publishing into a lifecycle that
   ;; remained otherwise valid.
   (when-not (and (fn? valid?)
                  (fn? weight-fn)
                  (integer? maximum-attempts)
                  (pos? maximum-attempts))
     (throw
      (ex-info
       "Subproblem publication options are invalid."
       {:type :eacl/invalid-config :eacl/error :eacl/invalid-config :tier tier})))
   (let [deadline-expired? (execution/expired?)
         valid-value?
         (try
           (boolean (valid? value))
           (catch #?(:clj Throwable :cljs :default) _ false))
         weight
         (when valid-value?
           (try
             (positive-weight! :entry-weight (weight-fn value))
             (catch #?(:clj Throwable :cljs :default) _ nil)))
         ceiling (publication-weight-ceiling store tier)]
     (cond
       deadline-expired?
       (do
         (swap! (:metrics store) update :publication-rejections inc)
         {:published? false :reason :deadline-expired})

       (not valid-value?)
       (do
         (swap! (:metrics store) update :invalid-results inc)
         (swap! (:metrics store) update :publication-rejections inc)
         {:published? false :reason :invalid-value})

       (nil? weight)
       (do
         (swap! (:metrics store) update :invalid-results inc)
         (swap! (:metrics store) update :publication-rejections inc)
         {:published? false :reason :invalid-weight})

       (> (or weight 0) ceiling)
       (do
         (swap! (:metrics store) update :oversized-rejections inc)
         (swap! (:metrics store) update :publication-rejections inc)
         {:published? false :reason :oversized})

       :else
       (let [token (lifecycle-token)]
         (loop [attempt 1]
           (let [state @(:state store)]
             (cond
               (not (identical? lifecycle (get state lifecycle-key)))
               (do
                 (swap! (:metrics store) update :detached-publications inc)
                 {:published? false :reason :detached})

               (get-in state [tier :entries key])
               (do
                 (swap! (:metrics store) update :publication-races inc)
                 {:published? false :reason :compatible-winner})

               :else
               (let [maximum-weight (get (:budgets store) tier)
                     tick (inc (get-in state [tier :clock]))
                     candidate {:token token
                                :value value
                                :weight weight
                                :validated? true
                                :access tick}
                     updated-tier
                     (-> (get state tier)
                         (assoc-in [:entries key] candidate)
                         (update :weight + weight)
                         (assoc :clock tick)
                         (update :lru conj [tick key]))
                     [trimmed evictions probes]
                     (trim-tier updated-tier maximum-weight key)]
                 (cond
                   (> (:weight trimmed) maximum-weight)
                   (do
                     (swap! (:metrics store) update :publication-rejections inc)
                     {:published? false :reason :capacity})

                   (compare-and-set!
                    (:state store) state (assoc state tier trimmed))
                   (do
                     (swap! (:metrics store) update :puts inc)
                     (when (pos? evictions)
                       (swap! (:metrics store) update :evictions + evictions))
                     (when (pos? probes)
                       (swap! (:metrics store) update :eviction-probes + probes))
                     (record-content-change! store)
                     {:published? true :reason :published})

                   (< attempt maximum-attempts)
                   (recur (inc attempt))

                   :else
                   (do
                     (swap! (:metrics store)
                            update :publication-contention inc)
                     {:published? false :reason :contention})))))))))))

(defn snapshot-tier-entries
  "Returns one tier's process-neutral entries in most-recently-used order.

  This is the immutable selection surface used by the basis-cache exporter.
  Runtime tokens, validation flags, and access ticks are deliberately omitted."
  [store tier]
  (validate-tier! store tier)
  (let [entries (get-in @(:state store) [tier :entries])]
    (->> entries
         (sort-by (fn [[key entry]]
                    [(- (:access entry)) (pr-str key)]))
         (mapv (fn [[key {:keys [value weight]}]]
                 {:key key :value value :weight weight})))))

(defn snapshot-value
  "Builds one versioned subproblem snapshot from already bounded tier entries."
  [store tier->entries]
  (when-not (store? store)
    (throw (ex-info "Expected an EACL subproblem store."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :store store})))
  (let [tiers (into {}
                    (map (fn [tier]
                           [tier (vec (get tier->entries tier []))]))
                    snapshot-tier-priority)
        entries (mapcat val tiers)]
    {:format snapshot-format
     :budgets (:budgets store)
     :managed-proof-max-atoms (:managed-proof-max-atoms store)
     :tiers tiers
     :entry-count (count entries)
     :retained-weight (reduce + 0 (map :weight entries))}))

(defn- positive-snapshot-bound!
  [option value]
  (when-not (and (integer? value) (pos? value))
    (throw
     (ex-info "Cache snapshot bounds must be positive integers."
              {:type :eacl/invalid-bound :eacl/error :eacl/invalid-bound
               :option option :value value})))
  value)

(defn export-snapshot
  "Exports one store under caller-supplied represented-weight and entry bounds.

  Entries are considered in answer, projection, and denotation priority and in
  most-recently-used order within each tier. An entry that does not fit is
  skipped so later smaller entries can still use the remaining capacity."
  [store {:keys [max-weight max-entries]}]
  (positive-snapshot-bound! :max-weight max-weight)
  (positive-snapshot-bound! :max-entries max-entries)
  (let [{:keys [selected]}
        (reduce
         (fn [{:keys [weight count] :as acc} tier]
           (reduce
            (fn [inner entry]
              (if (and (< (:count inner) max-entries)
                       (<= (+ (:weight inner) (:weight entry)) max-weight))
                (-> inner
                    (update-in [:selected tier] (fnil conj []) entry)
                    (update :weight + (:weight entry))
                    (update :count inc))
                inner))
            acc
            (snapshot-tier-entries store tier)))
         {:selected {} :weight 0 :count 0}
         snapshot-tier-priority)]
    (snapshot-value store selected)))

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

(defn- validate-snapshot-entry!
  [store tier entry]
  (when-not (closed-map? entry #{:key :value :weight})
    (incompatible-snapshot! "Malformed cache snapshot entry."
                            {:tier tier :entry entry}))
  (when-not (and (integer? (:weight entry))
                 (pos? (:weight entry))
                 (<= (:weight entry)
                     (publication-weight-ceiling store tier)))
    (incompatible-snapshot! "Cache snapshot entry exceeds its tier contract."
                            {:tier tier :weight (:weight entry)}))
  entry)

(defn restore-store
  "Constructs a fresh store from one already authenticated snapshot value.

  The caller MUST authenticate and encoded-size-bound external bytes before
  deserializing them. This function validates the decoded closed data model,
  then creates fresh lifecycle and entry identity tokens."
  ([snapshot options]
   (restore-store snapshot options nil))
  ([snapshot options content-revision]
   (let [destination (store options content-revision)
         top-keys #{:format :budgets :managed-proof-max-atoms :tiers
                    :entry-count :retained-weight}]
     (when-not (closed-map? snapshot top-keys)
       (incompatible-snapshot! "Malformed subproblem cache snapshot."
                               {:snapshot-keys (some-> snapshot keys set)}))
     (when-not (= snapshot-format (:format snapshot))
       (incompatible-snapshot! "Unsupported subproblem cache snapshot format."
                               {:format (:format snapshot)}))
     (when-not (closed-map? (:tiers snapshot) known-tiers)
       (incompatible-snapshot! "Malformed subproblem cache snapshot tiers."
                               {:tiers (some-> snapshot :tiers keys set)}))
     (when-not (= (:managed-proof-max-atoms destination)
                  (:managed-proof-max-atoms snapshot))
       (incompatible-snapshot! "Incompatible managed proof cache contract."
                               {:snapshot (:managed-proof-max-atoms snapshot)
                                :destination
                                (:managed-proof-max-atoms destination)}))
     (when-not (and (closed-map? (:budgets snapshot) known-tiers)
                    (every? (fn [tier]
                              (let [source (get-in snapshot [:budgets tier])
                                    destination-budget
                                    (get (:budgets destination) tier)]
                                (and (integer? source) (pos? source)
                                     (<= source destination-budget))))
                            known-tiers))
       (incompatible-snapshot! "Destination cache budgets are incompatible."
                               {:snapshot-budgets (:budgets snapshot)
                                :destination-budgets (:budgets destination)}))
     (let [validated
           (into {}
                 (map
                  (fn [tier]
                    (let [entries (get-in snapshot [:tiers tier])]
                      (when-not (vector? entries)
                        (incompatible-snapshot!
                         "Cache snapshot tier entries must be vectors."
                         {:tier tier}))
                      (doseq [entry entries]
                        (validate-snapshot-entry! destination tier entry))
                      (when-not (= (count entries)
                                   (count (set (map :key entries))))
                        (incompatible-snapshot!
                         "Cache snapshot contains duplicate entry keys."
                         {:tier tier}))
                      (let [weight (reduce + 0 (map :weight entries))]
                        (when (> weight (get (:budgets destination) tier))
                          (incompatible-snapshot!
                           "Cache snapshot tier exceeds destination capacity."
                           {:tier tier :weight weight
                            :max-weight (get (:budgets destination) tier)})))
                      [tier entries])))
                 snapshot-tier-priority)
           all-entries (mapcat val validated)
           entry-count (count all-entries)
           retained-weight (reduce + 0 (map :weight all-entries))]
       (when-not (and (= entry-count (:entry-count snapshot))
                      (= retained-weight (:retained-weight snapshot)))
         (incompatible-snapshot! "Cache snapshot totals do not match entries."
                                 {:declared-entry-count (:entry-count snapshot)
                                  :actual-entry-count entry-count
                                  :declared-retained-weight
                                  (:retained-weight snapshot)
                                  :actual-retained-weight retained-weight}))
       (reset!
        (:state destination)
        (assoc
         (into {}
               (map
                (fn [tier]
                  (let [mru-entries (get validated tier)
                        chronological (vec (reverse mru-entries))
                        entries
                        (into {}
                              (map-indexed
                               (fn [index {:keys [key value weight]}]
                                 [key {:token (lifecycle-token)
                                       :value value
                                       :weight weight
                                       :validated? true
                                       :access (inc index)}]))
                              chronological)]
                    [tier {:entries entries
                           :lru (mapv (fn [index {:keys [key]}]
                                        [(inc index) key])
                                      (range)
                                      chronological)
                           :lru-head 0
                           :weight (reduce + 0 (map :weight chronological))
                           :clock (count chronological)}])))
               snapshot-tier-priority)
         lifecycle-key (lifecycle-token)))
       destination))))

(defn resolve-independent!
  "Looks up a completed value or computes independently and races publication.

  A miss never joins an in-flight request and never acquires a computation
  slot. A request that loses publication still returns its own value and
  remains a miss in telemetry."
  [store tier key options compute]
  (let [lifecycle (get @(:state store) lifecycle-key)]
    (if-let [hit (lookup! store tier key options)]
      hit
      (let [value (compute)
            populate? (get options :populate? *populate?*)
            publication
            (if populate?
              (publish!
               store tier key options value *publication-attempt-limit*
               lifecycle)
              {:published? false :reason :suppressed})]
        (swap! (:metrics store) update :misses inc)
        {:value value
         :cached? false
         :cache-tier nil
         :publication publication}))))

(defn resolve!
  "Compatibility alias for independent miss computation and publication."
  [store tier key options compute]
  (resolve-independent! store tier key options compute))

(defn resolve-bound!
  "Uses the dynamically bound exact-generation store, or computes without any
  cache interaction when no store is bound."
  [tier key options compute]
  (if *store*
    (resolve-independent! *store* tier key options compute)
    {:value (compute)
     :cached? false
     :cache-tier nil}))

(defn proof-stamp?
  "True for a portable non-negative scalar transaction generation."
  [value]
  (and
   #?(:clj (integer? value)
      :cljs (and (number? value) (js/Number.isSafeInteger value)))
   (not (neg? value))))

(defn- valid-managed-descriptor?
  [descriptor]
  (and (map? descriptor)
       (proof-stamp? (:schema-generation descriptor))
       (proof-stamp? (:dependency-stamp descriptor))))

(defn- dependency-atom-count
  [dependency]
  (if (vector? dependency)
    (count dependency)
    1))

(defn- managed-descriptor
  [dependency]
  (when (and *store* *managed-store* *managed-key-fn* *managed-scope*
             (some? dependency))
    (if (> (dependency-atom-count dependency)
           (:managed-proof-max-atoms *store*))
      (do
        (swap! (:metrics *store*) update :managed-proof-overflows inc)
        nil)
      (try
        (let [resolved
              (resolve-independent!
               *store*
               :projection
               [:managed-projection-proof 1 *managed-scope* dependency]
               {:valid? valid-managed-descriptor?
                :weight-fn
                (constantly
                 (+ 128 (* 24 (dependency-atom-count dependency))))}
               #(do
                  (swap! (:metrics *store*)
                         update :managed-proof-reads inc)
                  (*managed-key-fn* dependency)))]
          (when (:cached? resolved)
            (swap! (:metrics *store*) update :managed-proof-hits inc))
          (when (valid-managed-descriptor? (:value resolved))
            (:value resolved)))
        (catch #?(:clj Throwable :cljs :default) _
          (swap! (:metrics *store*) update :managed-proof-failures inc)
          nil)))))

(declare lookup!)

(defn resolve-layered-bound!
  "Resolves an exact-generation value and, on its miss, optionally consults a
  dependency-stamped store shared by forward exact generations.

  The bounded managed descriptor is read from the same immutable snapshot and
  cached once per dependency set in the exact store. Missing, malformed,
  over-bound, or failing proof providers fall back to exact recomputation;
  compute failures still propagate. Managed entries are keyed by source,
  schema stamp, complete dependency identity/stamp, tier, and the complete
  semantic key."
  [tier key options dependency compute]
  (resolve-bound!
   tier
   key
   options
   (fn []
     (if-let [{:keys [schema-generation dependency-stamp]}
              (managed-descriptor dependency)]
       (let [managed
             (resolve-independent!
              *managed-store*
              tier
              [:managed-subproblem
               2
               *managed-scope*
               schema-generation
               dependency
               dependency-stamp
               tier
               key]
              options
              compute)]
         (when (:cached? managed)
           (swap! (:metrics *store*)
                  update (managed-tier-hit-metric tier) inc)
           (record-avoided-backend-operation! *store*))
         (:value managed))
       (compute)))))

(defn lookup!
  "Returns a complete existing value without starting a computation.

  Independently computed values are visible only after atomic publication."
  [store tier key {:keys [valid? weight-fn]
                   :or {valid? (constantly true)
                        weight-fn (constantly 1)}}]
  (validate-tier! store tier)
  (when-not (and (fn? valid?) (fn? weight-fn))
    (throw (ex-info "Subproblem lookup callbacks must be functions."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :tier tier})))
  (swap! (:metrics store) update :lookup-probes inc)
  (let [entry (get-in @(:state store) [tier :entries key])]
    (if-not (true? (:validated? entry))
      (do
        (when entry
          (remove-entry-if-token! store tier key (:token entry))
          (swap! (:metrics store) update :invalid-results inc))
        (swap! (:metrics store) update :lookup-misses inc)
        nil)
      (do
        (touch-entry! store tier key (:token entry))
        (swap! (:metrics store) update :hits inc)
        (swap! (:metrics store) update (tier-hit-metric tier) inc)
        {:value (:value entry)
         :cached? true
         :cache-tier (exact-cache-tier tier)}))))
