(ns eacl.subproblem-cache
  "Bounded client-private storage for immutable authorization subproblems.

  The store contains performance state only. Every admitted value belongs to
  one exact selected graph generation; replacing that generation makes the
  complete store unreachable."
  (:refer-clojure :exclude [resolve]))

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
  "Portable source/family/branch identity for managed projection keys."
  nil)

(def ^:dynamic ^:private *resolving-keys* #{})

(def ^:private known-tiers #{:projection :denotation})
(def ^:private default-projection-max-weight (* 4 1024 1024))
(def ^:private default-denotation-max-weight (* 4 1024 1024))
(def ^:private default-max-inflight 256)

(defrecord SubproblemStore [state metrics budgets max-inflight])

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

  Projection and denotation budgets are deliberately isolated so one large
  fixed-point result cannot evict every hot relationship chunk."
  ([]
   (store {}))
  ([{:keys [projection-max-weight denotation-max-weight max-inflight]
     :or {projection-max-weight default-projection-max-weight
          denotation-max-weight default-denotation-max-weight
          max-inflight default-max-inflight}}]
   (let [budgets
         {:projection
          (positive-weight! :projection-max-weight projection-max-weight)
          :denotation
          (positive-weight! :denotation-max-weight denotation-max-weight)}
         max-inflight
         (positive-weight! :max-inflight max-inflight)]
     (->SubproblemStore
      (atom
       (into {}
             (map (fn [tier]
                    [tier {:entries {}
                           :weight 0
                           :inflight 0
                           :clock 0}]))
             known-tiers))
      (atom {:hits 0
             :misses 0
             :puts 0
             :evictions 0
             :oversized-rejections 0
             :inflight-rejections 0
             :invalid-results 0
             :failures 0
             :single-flight-waits 0
             :self-bypasses 0
             :lookup-probes 0
             :lookup-misses 0
             :projection-hits 0
             :denotation-hits 0
             :acyclic-denotation-hits 0
             :recursive-component-hits 0
             :managed-projection-hits 0
             :managed-proof-reads 0
             :managed-proof-hits 0
             :managed-proof-failures 0
             :avoided-backend-operations 0
             :fetched-projection-values 0})
      budgets
      max-inflight))))

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
           :inflight
           (reduce + (map (comp :inflight val) state))
           :max-inflight
           (:max-inflight store)
           :tiers
           (into {}
                 (map (fn [[tier {:keys [entries weight inflight]}]]
                        [tier {:entries (count entries)
                               :weight weight
                               :inflight inflight
                               :max-weight (get (:budgets store) tier)}]))
                 state))))

(defn clear!
  [store]
  (when-not (store? store)
    (throw (ex-info "Expected an EACL subproblem store."
                    {:type :eacl/invalid-config
                     :store store})))
  (reset! (:state store)
          (into {}
                (map (fn [tier]
                       [tier {:entries {}
                              :weight 0
                              :inflight 0
                              :clock 0}]))
                known-tiers))
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

(defn- remove-entry-if-ticket!
  [store tier key ticket]
  (let [removed? (volatile! false)]
    (swap! (:state store)
           (fn [state]
             (let [entry (get-in state [tier :entries key])]
               (if (and entry (identical? ticket (:ticket entry)))
                 (do
                   (vreset! removed? true)
                   (-> state
                       (update-in [tier :entries] dissoc key)
                       (update-in [tier :weight] - (:weight entry))
                       (cond->
                        (not (:complete? entry))
                         (update-in [tier :inflight] dec))))
                 state))))
    @removed?))

(defn- trim-tier
  [tier-state maximum-weight protected-key]
  (loop [current tier-state
         evictions 0]
    (if (<= (:weight current) maximum-weight)
      [current evictions]
      (if-let [[victim entry]
               (reduce
                (fn [selected [key entry :as candidate]]
                  (if (or (= protected-key key)
                          (and selected
                               (<= (:access (val selected))
                                   (:access entry))))
                    selected
                    candidate))
                nil
                (:entries current))]
        (recur
         (-> current
             (update :entries dissoc victim)
             (update :weight - (:weight entry))
             (cond->
              (not (:complete? entry))
               (update :inflight dec)))
         (inc evictions))
        [current evictions]))))

(defn- finalize-entry!
  [store tier key ticket weight]
  (let [maximum-weight (get (:budgets store) tier)
        evictions (volatile! 0)
        rejected-oversized? (volatile! false)
        retained? (volatile! false)]
    (swap! (:state store)
           (fn [state]
             (let [entry (get-in state [tier :entries key])]
               (if-not (and entry
                            (identical? ticket (:ticket entry)))
                 state
                 (cond
                   (:complete? entry)
                   (do
                     (vreset! retained? true)
                     state)

                   (> weight maximum-weight)
                   (do
                     (vreset! rejected-oversized? true)
                     (-> state
                         (update-in [tier :entries] dissoc key)
                         (update-in [tier :weight] - (:weight entry))
                         (update-in [tier :inflight] dec)))

                   :else
                   (let [delta (- weight (:weight entry))
                         updated-tier
                         (-> (get state tier)
                             (assoc-in [:entries key :weight] weight)
                             (assoc-in [:entries key :complete?] true)
                             (update :weight + delta)
                             (update :inflight dec))
                         [trimmed n]
                         (trim-tier updated-tier maximum-weight key)]
                     (vreset! retained? true)
                     (vreset! evictions n)
                     (assoc state tier trimmed)))))))
    (when @rejected-oversized?
      (swap! (:metrics store) update :oversized-rejections inc))
    (when (pos? @evictions)
      (swap! (:metrics store) update :evictions + @evictions))
    @retained?))

(defn- touch-entry!
  [store tier key ticket]
  (swap! (:state store)
         (fn [state]
           (let [entry (get-in state [tier :entries key])]
             (if (and entry (identical? ticket (:ticket entry)))
               (let [tick (inc (get-in state [tier :clock]))]
                 (-> state
                     (assoc-in [tier :clock] tick)
                     (assoc-in [tier :entries key :access] tick)))
               state))))
  nil)

(defn- install-or-get-entry!
  [store tier key candidate]
  (loop []
    (let [state @(:state store)]
      (if-let [entry (get-in state [tier :entries key])]
        {:installed? false
         :entry entry}
        (let [inflight
              (reduce + (map (comp :inflight val) state))]
          (if (>= inflight (:max-inflight store))
            {:installed? false
             :admission-rejected? true}
            (let [maximum-weight (get (:budgets store) tier)
                  tick (inc (get-in state [tier :clock]))
                  updated-tier
                  (-> (get state tier)
                      (assoc-in [:entries key]
                                (assoc candidate :access tick))
                      (update :weight inc)
                      (update :inflight inc)
                      (assoc :clock tick))
                  [trimmed evictions]
                  (trim-tier updated-tier maximum-weight key)
                  updated (assoc state tier trimmed)]
              (if (compare-and-set! (:state store) state updated)
                (do
                  (when (pos? evictions)
                    (swap! (:metrics store)
                           update :evictions + evictions))
                  {:installed? true
                   :entry candidate})
                (recur)))))))))

(defn resolve!
  "Resolves one exact-generation subproblem.

  Returns `{:value value :cached? boolean :cache-tier tier}`. `valid?` and
  `weight-fn` default to accepting every value with weight one. A recursive
  call resolving its own key bypasses shared state instead of waiting on its
  in-flight delay."
  [store tier key {:keys [valid? weight-fn]
                   :or {valid? (constantly true)
                        weight-fn (constantly 1)}} compute]
  (validate-tier! store tier)
  (when-not (and (fn? valid?) (fn? weight-fn) (fn? compute))
    (throw (ex-info "Subproblem resolver callbacks must be functions."
                    {:type :eacl/invalid-config
                     :tier tier})))
  (let [compound-key [tier key]]
    (if (contains? *resolving-keys* compound-key)
      (do
        (swap! (:metrics store) update :self-bypasses inc)
        {:value (compute)
         :cached? false
         :cache-tier nil})
      (let [ticket (atom nil)
            candidate
            {:ticket ticket
             :result (delay (compute))
             :weight 1
             :complete? false}
            {:keys [installed? admission-rejected? entry]}
            (install-or-get-entry! store tier key candidate)]
        (if admission-rejected?
          (do
            (swap! (:metrics store) update :misses inc)
            (swap! (:metrics store) update :inflight-rejections inc)
            {:value
             (binding [*resolving-keys*
                       (conj *resolving-keys* compound-key)]
               (compute))
             :cached? false
             :cache-tier nil})
          (let [result-delay (:result entry)
                was-realized? (realized? result-delay)]
            (swap! (:metrics store)
                   update (if installed? :misses :hits) inc)
            (when-not installed?
              (swap! (:metrics store)
                     update (if (= tier :projection)
                              :projection-hits
                              :denotation-hits)
                     inc)
              (when-not was-realized?
                (swap! (:metrics store) update :single-flight-waits inc)))
            (try
              (let [value
                    (binding [*resolving-keys*
                              (conj *resolving-keys* compound-key)]
                      @result-delay)]
                (if (:complete? entry)
                  (do
                    (touch-entry! store tier key (:ticket entry))
                    {:value value
                     :cached? true
                     :cache-tier
                     (if (= tier :projection)
                       :exact-projection
                       :exact-denotation)})
                  (let [valid-value?
                        (try
                          (boolean (valid? value))
                          (catch #?(:clj Throwable :cljs :default) _
                            false))]
                    (if-not valid-value?
                      (do
                        (remove-entry-if-ticket!
                         store tier key (:ticket entry))
                        (swap! (:metrics store) update :invalid-results inc)
                        {:value value
                         :cached? false
                         :cache-tier nil})
                      (let [weight
                            (try
                              (positive-weight!
                               :entry-weight
                               (weight-fn value))
                              (catch #?(:clj Throwable :cljs :default) _
                                nil))]
                        (if-not weight
                          (do
                            (remove-entry-if-ticket!
                             store tier key (:ticket entry))
                            (swap! (:metrics store)
                                   update :invalid-results inc)
                            {:value value
                             :cached? false
                             :cache-tier nil})
                          (let [retained?
                                (finalize-entry!
                                 store tier key (:ticket entry) weight)]
                            (touch-entry! store tier key (:ticket entry))
                            (when (and installed? retained?)
                              (swap! (:metrics store) update :puts inc))
                            {:value value
                             :cached? (not installed?)
                             :cache-tier
                             (when-not installed?
                               (if (= tier :projection)
                                 :exact-projection
                                 :exact-denotation))})))))))
              (catch #?(:clj Throwable :cljs :default) error
                (remove-entry-if-ticket!
                 store tier key (:ticket entry))
                (swap! (:metrics store) update :failures inc)
                (throw error)))))))))

(defn resolve-bound!
  "Uses the dynamically bound exact-generation store, or computes without any
  cache interaction when no store is bound."
  [tier key options compute]
  (if *store*
    (resolve! *store* tier key options compute)
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
        (seq value)
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

(defn- managed-descriptor
  [dependency]
  (when (and *store* *managed-store* *managed-key-fn* *managed-scope*
             (some? dependency))
    (try
      (let [resolved
            (resolve!
             *store*
             :projection
             [:managed-projection-proof 1 *managed-scope* dependency]
             {:valid? valid-managed-descriptor?
              :weight-fn (constantly 128)}
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
        nil))))

(defn resolve-layered-bound!
  "Resolves an exact-generation value and, on its miss, optionally consults a
  relation-stamped store shared by forward exact generations.

  The managed descriptor is read from the same immutable snapshot and cached
  once per relation in the exact store. Missing, malformed, or failing proof
  providers fail open to exact recomputation; compute failures still propagate.
  Managed entries are keyed by schema stamp, relation dependency, mutation
  stamp, tier, and the complete exact semantic key."
  [tier key options dependency compute]
  (resolve-bound!
   tier
   key
   options
   (fn []
     (if-let [{:keys [schema-stamp dependency-stamp]}
              (managed-descriptor dependency)]
       (let [managed
             (resolve!
              *managed-store*
              tier
              [:managed-projection
               1
               *managed-scope*
               schema-stamp
               dependency
               dependency-stamp
               key]
              options
              compute)]
         (when (:cached? managed)
           (swap! (:metrics *store*) update :managed-projection-hits inc)
           (record-avoided-backend-operation! *store*))
         (:value managed))
       (compute)))))

(defn lookup!
  "Returns a complete existing value without starting a computation.

  In-flight identical work may be joined, but missing, recursive-self,
  failed, and invalid candidates are misses. This is used where computing a
  full denotation would be more expensive than the caller's cache-free
  short-circuit."
  [store tier key {:keys [valid? weight-fn]
                   :or {valid? (constantly true)
                        weight-fn (constantly 1)}}]
  (validate-tier! store tier)
  (when-not (and (fn? valid?) (fn? weight-fn))
    (throw (ex-info "Subproblem lookup callbacks must be functions."
                    {:type :eacl/invalid-config
                     :tier tier})))
  (swap! (:metrics store) update :lookup-probes inc)
  (let [compound-key [tier key]]
    (if (contains? *resolving-keys* compound-key)
      (do
        (swap! (:metrics store) update :self-bypasses inc)
        (swap! (:metrics store) update :lookup-misses inc)
        nil)
      (if-let [entry (get-in @(:state store) [tier :entries key])]
        (let [result-delay (:result entry)
              was-realized? (realized? result-delay)]
          (swap! (:metrics store) update :hits inc)
          (swap! (:metrics store)
                 update (if (= tier :projection)
                          :projection-hits
                          :denotation-hits)
                 inc)
          (when-not was-realized?
            (swap! (:metrics store) update :single-flight-waits inc))
          (try
            (let [value
                  (binding [*resolving-keys*
                            (conj *resolving-keys* compound-key)]
                    @result-delay)
                  valid-value?
                  (or
                   (:complete? entry)
                   (try
                     (boolean (valid? value))
                     (catch #?(:clj Throwable :cljs :default) _
                       false)))]
              (if valid-value?
                (let [retained?
                      (or
                       (:complete? entry)
                       (when-let [weight
                                  (try
                                    (positive-weight!
                                     :entry-weight
                                     (weight-fn value))
                                    (catch
                                     #?(:clj Throwable :cljs :default) _
                                      nil))]
                         (finalize-entry!
                          store tier key (:ticket entry) weight)))]
                  (if retained?
                    (do
                      (touch-entry! store tier key (:ticket entry))
                      {:value value
                       :cached? true
                       :cache-tier
                       (if (= tier :projection)
                         :exact-projection
                         :exact-denotation)})
                    (do
                      (remove-entry-if-ticket!
                       store tier key (:ticket entry))
                      (swap! (:metrics store) update :invalid-results inc)
                      (swap! (:metrics store) update :lookup-misses inc)
                      nil)))
                (do
                  (remove-entry-if-ticket!
                   store tier key (:ticket entry))
                  (swap! (:metrics store) update :invalid-results inc)
                  (swap! (:metrics store) update :lookup-misses inc)
                  nil)))
            (catch #?(:clj Throwable :cljs :default) _
              (remove-entry-if-ticket!
               store tier key (:ticket entry))
              (swap! (:metrics store) update :failures inc)
              (swap! (:metrics store) update :lookup-misses inc)
              nil)))
        (do
          (swap! (:metrics store) update :lookup-misses inc)
          nil)))))

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
