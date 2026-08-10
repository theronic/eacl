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
  "Returns a same-snapshot `{:schema-stamp n :dependency-stamp n}` descriptor
  for one relation dependency, or nil when managed reuse is unavailable."
  nil)

(def ^:dynamic *managed-scope*
  "Portable backend/source/branch/lifecycle identity for managed projection keys."
  nil)

(def ^:dynamic *publication-attempt-limit*
  "Maximum best-effort CAS publication attempts for the owning request."
  4)

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

(defn- managed-cache-tier
  [tier]
  (case tier
    :projection :managed-projection
    :denotation :managed-denotation
    :answer :managed-answer))

(declare positive-weight!)

(defrecord SubproblemStore
           [state metrics budgets managed-proof-max-atoms])

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
                    {:type :eacl/invalid-config
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
   (store {}))
  ([{:keys [projection-max-weight denotation-max-weight answer-max-weight
            managed-proof-max-atoms]
     :or {projection-max-weight default-projection-max-weight
          denotation-max-weight default-denotation-max-weight
          answer-max-weight default-answer-max-weight
          managed-proof-max-atoms default-managed-proof-max-atoms}
     :as options}]
   (let [unknown-keys (seq (sort (remove option-keys (keys options))))
         _
         (when unknown-keys
           (throw
            (ex-info
             "Unknown subproblem cache option."
             {:type :eacl/invalid-config
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
      managed-proof-max-atoms))))

(defn store?
  [value]
  (instance? SubproblemStore value))

(defn- validate-tier!
  [store tier]
  (when-not (store? store)
    (throw (ex-info "Expected an EACL subproblem store."
                    {:type :eacl/invalid-config
                     :store store})))
  (when-not (contains? known-tiers tier)
    (throw (ex-info "Unknown EACL subproblem cache tier."
                    {:type :eacl/invalid-config
                     :tier tier
                     :known-tiers known-tiers}))))

(defn stats
  [store]
  (when-not (store? store)
    (throw (ex-info "Expected an EACL subproblem store."
                    {:type :eacl/invalid-config
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
                    {:type :eacl/invalid-config
                     :store store})))
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
  nil)

(defn record-avoided-backend-operation!
  ([]
   (record-avoided-backend-operation! *store*))
  ([store]
   (when store
     (swap! (:metrics store)
            update :avoided-backend-operations (fnil inc 0)))
   nil))

(defn add-fetched-projection-values!
  ([n]
   (add-fetched-projection-values! *store* n))
  ([store n]
   (when store
     (swap! (:metrics store)
            update :fetched-projection-values (fnil + 0) n))
   nil))

(defn record-acyclic-denotation-hit!
  ([]
   (record-acyclic-denotation-hit! *store*))
  ([store]
   (when store
     (swap! (:metrics store)
            update :acyclic-denotation-hits (fnil inc 0)))
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
       {:type :eacl/invalid-config :tier tier})))
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
                     {:published? true :reason :published})

                   (< attempt maximum-attempts)
                   (recur (inc attempt))

                   :else
                   (do
                     (swap! (:metrics store)
                            update :publication-contention inc)
                     {:published? false :reason :contention})))))))))))

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
            publication
            (publish!
             store tier key options value *publication-attempt-limit*
             lifecycle)]
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

(defn- proof-component?
  [value]
  (or (and (integer? value) (not (neg? value)))
      (string? value)
      (keyword? value)))

(defn proof-stamp?
  "True for the bounded portable scalar/vector identities admitted into
  managed projection keys."
  [value]
  (or
   (proof-component? value)
   (and (vector? value)
        (<= (count value) 4096)
        (every?
         #(or
           (proof-component? %)
           (and (vector? %)
                (seq %)
                (<= (count %) 4)
                (every? proof-component? %)))
         value))))

(defn- valid-managed-descriptor?
  [descriptor]
  (and (map? descriptor)
       (proof-stamp? (:schema-stamp descriptor))
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
     (if-let [{:keys [schema-stamp dependency-stamp]}
              (managed-descriptor dependency)]
       (let [managed
             (resolve-independent!
              *managed-store*
              tier
              [:managed-subproblem
               2
               *managed-scope*
               schema-stamp
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

(defn lookup-layered-bound!
  "Looks up an exact or bounded managed value without starting value work.

  Proof resolution may run once for the selected exact generation. A missing
  managed value remains a miss; callers that want to compute may subsequently
  use `resolve-layered-bound!` with the same inputs."
  [tier key options dependency]
  (when *store*
    (or
     (lookup! *store* tier key options)
     (when-let [{:keys [schema-stamp dependency-stamp]}
                (managed-descriptor dependency)]
       (when-let [resolved
                  (lookup!
                   *managed-store*
                   tier
                   [:managed-subproblem
                    2
                    *managed-scope*
                    schema-stamp
                    dependency
                    dependency-stamp
                    tier
                    key]
                   options)]
         (swap! (:metrics *store*)
                update (managed-tier-hit-metric tier) inc)
         (record-avoided-backend-operation! *store*)
         (assoc
          resolved
          :cache-tier
          (managed-cache-tier tier)))))))

(defn lookup!
  "Returns a complete existing value without starting a computation.

  Independently computed values are visible only after atomic publication."
  [store tier key {:keys [valid? weight-fn]
                   :or {valid? (constantly true)
                        weight-fn (constantly 1)}}]
  (validate-tier! store tier)
  (when-not (and (fn? valid?) (fn? weight-fn))
    (throw (ex-info "Subproblem lookup callbacks must be functions."
                    {:type :eacl/invalid-config
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

(defn lookup-bound!
  "Looks up a complete value in the dynamically bound store, or returns nil."
  [tier key options]
  (when *store*
    (lookup! *store* tier key options)))

(defn record-recursive-component-hit!
  ([]
   (record-recursive-component-hit! *store*))
  ([store]
   (when store
     (swap! (:metrics store)
            update :recursive-component-hits (fnil inc 0)))
   nil))
