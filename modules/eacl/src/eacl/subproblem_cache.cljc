(ns eacl.subproblem-cache
  "Bounded client-private storage for immutable authorization subproblems.

  The store contains performance state only. Every admitted value belongs to
  one exact selected graph generation; replacing that generation makes the
  complete store unreachable."
  (:refer-clojure :exclude [resolve])
  (:require [eacl.verified-kernel :as verified]
            #?(:clj
               [eacl.formal.production-kernel]
               :cljs
               [eacl.formal.production-kernel-js]))
  #?(:clj
     (:import [java.util.concurrent Semaphore])))

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

(def ^:dynamic *decision-kernel*
  "Generated-kernel selection inherited from the enclosing public client.

  Pure lookup, admission, and publication decisions run through generated
  code while storage mutation and value computation remain host-runtime
  responsibilities."
  #?(:clj
     eacl.formal.production-kernel/default-selection
     :cljs
     eacl.formal.production-kernel-js/default-selection))

(def ^:dynamic ^:private *decision-memo* nil)

(def ^:dynamic ^:private *resolving-keys* #{})
(def ^:dynamic ^:private *computation-owner* nil)

(def ^:private known-tiers #{:projection :denotation :answer})
(def ^:private default-projection-max-weight (* 4 1024 1024))
(def ^:private default-denotation-max-weight (* 4 1024 1024))
(def ^:private default-answer-max-weight (* 16 1024 1024))
(def ^:private default-max-inflight 256)
(def ^:private default-managed-proof-max-atoms 256)

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

(defrecord ComputationCoordinator [active maximum semaphore flights])

(defrecord SubproblemStore
           [state metrics budgets max-inflight managed-proof-max-atoms
            computation-coordinator lifecycle])

(defn- lifecycle-token
  []
  #?(:clj (Object.)
     :cljs (js-obj)))

(defn computation-coordinator
  "Creates the execution-slot coordinator shared by every subproblem store in
  one client cache lifecycle.

  The coordinator is deliberately independent of evictable entries and exact
  generations. It therefore measures actually executing top-level compute
  callbacks, including flights rejected by cache admission, rather than only
  candidates still represented in one tier map.

  LIVENESS INVARIANT (wedge-free single flight, fix i-b): the semaphore is
  never acquired by a thread holding any flight delay's lock — acquisition
  strictly precedes the owner's deref in resolve!, and every flight body
  runs under *computation-owner*, so all nested resolves compute inline.
  The wait-for graph therefore cannot cycle: flight monitor chains strictly
  descend the tier rank exact-answer -> managed-answer -> exact-denotation ->
  managed-denotation -> exact-projection -> {managed-projection,
  projection-proof} -> leaf (completed-answer flights exist only at the top
  of a public operation and nothing below them ever resolves an answer key;
  denotation computes never resolve another denotation key — compiled
  plans carry the full rule closure and call back only into projection
  scans — and managed proof providers must remain cache-free), while
  semaphore waiters hold no monitors and every monitor holder is
  permit-independent and terminating. Any future code that resolves a
  denotation-tier key from inside a compute breaks the rank argument and
  is caught by the single-flight wedge regression test and the
  randomized nested soak. (A dev-mode empty-*resolving-keys* assertion
  at the acquire site was evaluated and rejected: binding conveyance
  hands *resolving-keys* to child threads that legitimately acquire
  their own slots while holding no locks.)"
  [maximum]
  (let [maximum (positive-weight! :max-inflight maximum)]
    (->ComputationCoordinator
     (atom 0)
     maximum
     #?(:clj (Semaphore. maximum true)
        :cljs nil)
     (atom {}))))

(defn- coordinator?
  [value]
  (instance? ComputationCoordinator value))

(defn- acquire-computation-slot!
  [coordinator]
  #?(:clj
     (do
       ;; A fair semaphore parks saturated JVM callers without polling. It is
       ;; separate from evictable entries, so clearing or replacing a graph
       ;; generation cannot manufacture execution capacity.
       (.acquire ^Semaphore (:semaphore coordinator))
       (swap! (:active coordinator) inc))
     :cljs
     (loop []
       (let [active @(:active coordinator)
             maximum (:maximum coordinator)]
         (if (< active maximum)
           (if (compare-and-set! (:active coordinator) active (inc active))
             nil
             (recur))
           (throw
            (ex-info
             "A synchronous ClojureScript computation attempted to wait for a subproblem slot."
             {:type :eacl/cache-computation-reentrancy
              :maximum maximum})))))))

(defn- release-computation-slot!
  [coordinator]
  (swap! (:active coordinator) dec)
  #?(:clj (.release ^Semaphore (:semaphore coordinator))
     :cljs nil))

(defn- execution-context
  []
  #?(:clj (Thread/currentThread)
     :cljs :javascript-main-thread))

(defn- with-computation-slot
  [store compute]
  (let [context (execution-context)]
    (if (identical? *computation-owner* context)
      (compute)
      (let [coordinator (:computation-coordinator store)]
        (acquire-computation-slot! coordinator)
        (try
          (binding [*computation-owner* context]
            (compute))
          (finally
            (release-computation-slot! coordinator)))))))

(defn- under-store-lock
  [_store f]
  #?(:clj (locking _store (f))
     :cljs (f)))

(defn- install-or-get-flight!
  [coordinator flight-key candidate]
  (loop []
    (let [flights @(:flights coordinator)]
      (if-let [entry (get flights flight-key)]
        {:installed? false
         :entry entry}
        (if (compare-and-set!
             (:flights coordinator)
             flights
             (assoc flights flight-key candidate))
          {:installed? true
           :entry candidate}
          (recur))))))

(defn- remove-flight-if-ticket!
  [coordinator flight-key ticket]
  (swap! (:flights coordinator)
         (fn [flights]
           (let [entry (get flights flight-key)]
             (if (and entry
                      (identical? ticket (:ticket entry)))
               (dissoc flights flight-key)
               flights))))
  nil)

(defn- cache-decision
  [input]
  (if *decision-memo*
    (if-let [cached (find @*decision-memo* input)]
      (val cached)
      (let [decision
            (verified/decide
             *decision-kernel*
             :subproblem-cache-decision
             input)]
        (vswap! *decision-memo* assoc input decision)
        decision))
    (verified/decide
     *decision-kernel*
     :subproblem-cache-decision
     input)))

(defn with-decision-memo
  "Runs one top-level authorization computation with request-local memoization
  of verified pure subproblem transition decisions.

  The generated decision is still invoked and boundary-checked once for every
  distinct complete input. Repeated identical lookup states inside a deep
  shared subgraph reuse that checked result instead of paying the CLJ/CLJS FFI
  conversion cost at every edge. The memo is request-scoped and never stores
  authorization values."
  [compute]
  (if *decision-memo*
    (compute)
    (binding [*decision-memo* (volatile! {})]
      (compute))))

(defn- lookup-action
  [recursive-self? candidate]
  (cache-decision
   {:decision :lookup
    :recursive-self? recursive-self?
    :candidate candidate}))

(defn- admission-action
  [candidate-present? represented-candidates maximum-candidates]
  (cache-decision
   {:decision :admission
    :candidate-present? candidate-present?
    :represented-candidates represented-candidates
    :maximum-candidates maximum-candidates}))

(defn- publication-action
  [ticket-current? complete? valid? weight budget]
  (cache-decision
   {:decision :publication
    :ticket-current? ticket-current?
    :complete? complete?
    :valid? valid?
    :weight weight
    :budget budget}))

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
            max-inflight managed-proof-max-atoms computation-coordinator]
     :or {projection-max-weight default-projection-max-weight
          denotation-max-weight default-denotation-max-weight
          answer-max-weight default-answer-max-weight
          max-inflight default-max-inflight
          managed-proof-max-atoms default-managed-proof-max-atoms}}]
   (let [budgets
         {:projection
          (positive-weight! :projection-max-weight projection-max-weight)
          :denotation
          (positive-weight! :denotation-max-weight denotation-max-weight)
          :answer
          (positive-weight! :answer-max-weight answer-max-weight)}
         max-inflight
         (positive-weight! :max-inflight max-inflight)
         computation-coordinator
         (or computation-coordinator
             (eacl.subproblem-cache/computation-coordinator max-inflight))
         _
         (when-not
          (and (coordinator? computation-coordinator)
               (= max-inflight (:maximum computation-coordinator)))
           (throw
            (ex-info
             "Shared computation coordinator must use the store's max-inflight limit."
             {:type :eacl/invalid-config
              :max-inflight max-inflight
              :coordinator computation-coordinator})))
         managed-proof-max-atoms
         (positive-weight!
          :managed-proof-max-atoms managed-proof-max-atoms)]
     (->SubproblemStore
      (atom
       (into {}
             (map (fn [tier]
                    [tier {:entries {}
                           :lru []
                           :lru-head 0
                           :weight 0
                           :inflight 0
                           :clock 0}]))
             known-tiers))
      (atom {:hits 0
             :misses 0
             :puts 0
             :evictions 0
             :eviction-probes 0
             :oversized-rejections 0
             :inflight-rejections 0
             :invalid-results 0
             :failures 0
             :single-flight-waits 0
             :stolen-computations 0
             :self-bypasses 0
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
      max-inflight
      managed-proof-max-atoms
      computation-coordinator
      (atom (lifecycle-token))))))

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
           :active-computations
           @(:active (:computation-coordinator store))
           :registered-flights
           (count @(:flights (:computation-coordinator store)))
           :max-inflight
           (:max-inflight store)
           :managed-proof-max-atoms
           (:managed-proof-max-atoms store)
           :tiers
           (into {}
                 (map (fn [[tier {:keys [entries weight inflight
                                         lru lru-head]}]]
                        [tier {:entries (count entries)
                               :lru-records (- (count lru) lru-head)
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
  (under-store-lock
   store
   (fn []
     (reset! (:state store)
             (into {}
                   (map (fn [tier]
                          [tier {:entries {}
                                 :lru []
                                 :lru-head 0
                                 :weight 0
                                 :inflight 0
                                 :clock 0}]))
                   known-tiers))
     (reset! (:lifecycle store) (lifecycle-token))))
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
                              (not= protected-key key)
                              (:complete? entry))
                       [index key entry victim-probes]
                       (recur (inc index) victim-probes)))))]
        (recur
         (-> current
             (update :entries dissoc victim)
             (assoc :lru-head (inc victim-index))
             (update :weight - (:weight entry))
             (cond->
               (not (:complete? entry))
               (update :inflight dec)))
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

(defn- finalize-entry!
  [store tier key ticket weight]
  (let [maximum-weight (get (:budgets store) tier)
        entry-ceiling (publication-weight-ceiling store tier)
        evictions (volatile! 0)
        eviction-probes (volatile! 0)
        rejected-oversized? (volatile! false)
        retained? (volatile! false)]
    (swap! (:state store)
           (fn [state]
             (let [entry (get-in state [tier :entries key])
                   ticket-current?
                   (boolean
                    (and entry
                         (identical? ticket (:ticket entry))))
                   action
                   (publication-action
                    ticket-current? true true weight entry-ceiling)]
               (case action
                 :drop-publication
                 (if ticket-current?
                   (do
                     (vreset! rejected-oversized? true)
                     (-> state
                         (update-in [tier :entries] dissoc key)
                         (update-in [tier :weight] - (:weight entry))
                         (cond->
                           (not (:complete? entry))
                           (update-in [tier :inflight] dec))))
                   state)

                 :retain-publication
                 (if (:complete? entry)
                   (do
                     (vreset! retained? true)
                     state)
                   (let [delta (- weight (:weight entry))
                         updated-tier
                         (-> (get state tier)
                             (assoc-in [:entries key :weight] weight)
                             (assoc-in [:entries key :complete?] true)
                             (update :weight + delta)
                             (update :inflight dec))
                         [trimmed n probes]
                         (trim-tier updated-tier maximum-weight key)]
                     (vreset! retained? true)
                     (vreset! evictions n)
                     (vreset! eviction-probes probes)
                     (assoc state tier trimmed)))))))
    (when @rejected-oversized?
      (swap! (:metrics store) update :oversized-rejections inc))
    (when (pos? @evictions)
      (swap! (:metrics store) update :evictions + @evictions))
    (when (pos? @eviction-probes)
      (swap! (:metrics store)
             update :eviction-probes + @eviction-probes))
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
                     (assoc-in [tier :entries key :access] tick)
                     (update-in [tier :lru] conj [tick key])
                     (update tier maybe-compact-lru)))
               state))))
  nil)

(defn- install-or-get-entry!
  [store lifecycle tier key candidate]
  (loop []
    (let [state @(:state store)
          entry (get-in state [tier :entries key])
          inflight (reduce + (map (comp :inflight val) state))
          action
          (if (identical? lifecycle @(:lifecycle store))
            (admission-action
             (boolean entry) inflight (:max-inflight store))
            :compute-without-admission)]
      (case action
        :join-existing
        {:installed? false
         :entry entry}

        :compute-without-admission
        {:installed? false
         :admission-rejected? true}

        :admit-computation
        (let [maximum-weight (get (:budgets store) tier)
              tick (inc (get-in state [tier :clock]))
              updated-tier
              (-> (get state tier)
                  (assoc-in [:entries key]
                            (assoc candidate :access tick))
                  (update :weight inc)
                  (update :inflight inc)
                  (assoc :clock tick))
              [trimmed evictions eviction-probes]
              (trim-tier updated-tier maximum-weight key)
              updated (assoc state tier trimmed)]
          (if (> (:weight trimmed) maximum-weight)
            {:installed? false
             :admission-rejected? true
             :admission-reason :tier-capacity}
            (if (compare-and-set! (:state store) state updated)
              (do
                (when (pos? evictions)
                  (swap! (:metrics store)
                         update :evictions + evictions))
                (when (pos? eviction-probes)
                  (swap! (:metrics store)
                         update :eviction-probes + eviction-probes))
                {:installed? true
                 :entry candidate})
              (recur))))))))

(defn- select-entry-or-flight!
  [store tier key compute]
  (under-store-lock
   store
   (fn []
     (let [lifecycle @(:lifecycle store)
           flight-key [lifecycle tier key]
           recursive-self?
           (contains? *resolving-keys* flight-key)
           existing-entry
           (when-not recursive-self?
             (get-in @(:state store) [tier :entries key]))
           existing-flight
           (when-not recursive-self?
             (get @(:flights (:computation-coordinator store))
                  flight-key))
           selected-entry
           (or existing-entry existing-flight)
           action
           (lookup-action
            recursive-self?
            (cond
              recursive-self? :missing
              (:complete? existing-entry) :complete
              selected-entry :computing
              :else :missing))]
       (case action
         :bypass-recursive-self
         {:lookup-action action
          :recursive-self? true
          :compound-key flight-key}

         (:use-completed-value :join-computation)
         {:lookup-action action
          :recursive-self? false
          :compound-key flight-key
          :flight-owner? false
          :entry selected-entry}

         :start-computation
         (let [ticket (atom nil)
               admitted? (atom false)
               installing-context (execution-context)
               ;; Wedge-free single flight (i-b): the computation slot is
               ;; acquired by the FLIGHT OWNER strictly before its deref
               ;; (see resolve!), never inside this body — so no thread
               ;; ever blocks on the semaphore while holding this delay's
               ;; lock. Clojure delays run their body on whichever thread
               ;; derefs first: a permit-less joiner may STEAL an
               ;; unrealized body from a slot-queued owner. Binding
               ;; *computation-owner* here (not in with-computation-slot)
               ;; guarantees every body-runner — owner or thief — computes
               ;; nested resolves inline instead of re-entering the
               ;; semaphore. Stolen bodies run unpermitted (bounded
               ;; overshoot, counted via :stolen-computations).
               candidate
               {:ticket ticket
                :result
                (delay
                  (try
                    (let [runner (execution-context)]
                      (when-not (identical? runner installing-context)
                        (swap! (:metrics store)
                               update :stolen-computations inc))
                      (binding [*computation-owner* runner]
                        (compute)))
                    (finally
                      (under-store-lock
                       store
                       #(remove-flight-if-ticket!
                         (:computation-coordinator store)
                         flight-key
                         ticket)))))
                :admitted? admitted?
                :weight 1
                :complete? false}
               {:keys [installed? entry]}
               (install-or-get-flight!
                (:computation-coordinator store)
                flight-key
                candidate)]
           (if-not installed?
             (let [collision-action
                   (lookup-action false :computing)]
               {:lookup-action collision-action
                :recursive-self? false
                :compound-key flight-key
                :flight-owner? false
                :entry entry})
             (let [admission
                   (install-or-get-entry!
                    store lifecycle tier key candidate)]
               (if (:installed? admission)
                 (do
                   (reset! admitted? true)
                   {:lookup-action action
                    :recursive-self? false
                    :compound-key flight-key
                    :flight-owner? true
                    :entry candidate})
                 (if-let [admitted-entry (:entry admission)]
                   (do
                     (remove-flight-if-ticket!
                      (:computation-coordinator store)
                      flight-key
                      ticket)
                     {:lookup-action
                      (if (:complete? admitted-entry)
                        :use-completed-value
                        :join-computation)
                      :recursive-self? false
                      :compound-key flight-key
                      :flight-owner? false
                      :entry admitted-entry})
                   (do
                     (swap! (:metrics store)
                            update :inflight-rejections inc)
                     {:lookup-action action
                      :recursive-self? false
                      :compound-key flight-key
                      :flight-owner? true
                      :entry candidate})))))))))))

(defn- select-lookup-entry!
  [store tier key]
  (under-store-lock
   store
   (fn []
     (let [lifecycle @(:lifecycle store)
           compound-key [lifecycle tier key]
           recursive-self?
           (contains? *resolving-keys* compound-key)
           entry
           (when-not recursive-self?
             (get-in @(:state store) [tier :entries key]))
           action
           (lookup-action
            recursive-self?
            (cond
              recursive-self? :missing
              (nil? entry) :missing
              (:complete? entry) :complete
              :else :computing))]
       {:recursive-self? recursive-self?
        :compound-key compound-key
        :lookup-action action
        :entry entry}))))

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
  (let [{:keys [compound-key flight-owner? entry lookup-action]}
        (select-entry-or-flight! store tier key compute)
        initial-action lookup-action]
    (if (= :bypass-recursive-self initial-action)
      (do
        (swap! (:metrics store) update :self-bypasses inc)
        {:value (with-computation-slot store compute)
         :cached? false
         :cache-tier nil})
      (let [result-delay (:result entry)
            was-realized? (realized? result-delay)
            completed-hit?
            (= :use-completed-value initial-action)
            coordinator (:computation-coordinator store)
            nested-owner?
            (identical? *computation-owner* (execution-context))
            ;; Owner-acquires-first: the slot is taken here, holding no
            ;; delay lock, never inside the flight body. A nested owner
            ;; already holds a slot (re-entrancy skip); a realized delay
            ;; needs none (a joiner stole and completed the body).
            needs-slot?
            (and flight-owner? (not nested-owner?) (not was-realized?))]
        ;; Honest metrics: a single-flight join is not a cache hit.
        ;; Hits count only lookups served from realized cached state.
        (if flight-owner?
          (swap! (:metrics store) update :misses inc)
          (if was-realized?
            (do
              (swap! (:metrics store) update :hits inc)
              (swap! (:metrics store)
                     update (tier-hit-metric tier) inc))
            (swap! (:metrics store) update :single-flight-waits inc)))
        (when needs-slot?
          ;; Structural invariant: same-thread nesting never reaches this
          ;; acquire (nested-owner? short-circuits needs-slot?). A CHILD
          ;; THREAD spawned inside a compute inherits *computation-owner*
          ;; and *resolving-keys* by binding conveyance but holds no delay
          ;; lock, so it correctly takes its own slot here — conveyed
          ;; bindings must not be mistaken for lock ownership (pinned by
          ;; inherited-future-bindings-do-not-inherit-a-computation-slot-test).
          (try
            (acquire-computation-slot! coordinator)
            (catch #?(:clj Throwable :cljs :default) acquire-error
              ;; A queued owner that dies before realization must not
              ;; strand its unrealized flight: joiners CAN steal it, but
              ;; none is guaranteed to exist. Ticket-guarded, idempotent.
              (under-store-lock
               store
               #(remove-flight-if-ticket!
                 coordinator compound-key (:ticket entry)))
              (throw acquire-error))))
        (try
          (let [value
                (try
                  (binding [*resolving-keys*
                            (conj *resolving-keys* compound-key)]
                    @result-delay)
                  (finally
                    (when needs-slot?
                      (release-computation-slot! coordinator))))]
            (if completed-hit?
              (do
                (touch-entry! store tier key (:ticket entry))
                {:value value
                 :cached? true
                 :cache-tier (exact-cache-tier tier)})
              (let [admitted? @(:admitted? entry)
                    valid-value?
                    (try
                      (boolean (valid? value))
                      (catch #?(:clj Throwable :cljs :default) _
                        false))]
                (if-not (and admitted? valid-value?)
                  (do
                    (when admitted?
                      (remove-entry-if-ticket!
                       store tier key (:ticket entry)))
                    (when-not valid-value?
                      (swap! (:metrics store)
                             update :invalid-results inc))
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
                        (when (and flight-owner? retained?)
                          (swap! (:metrics store) update :puts inc))
                        {:value value
                         :cached? (not flight-owner?)
                         :cache-tier
                         (when-not flight-owner?
                           (exact-cache-tier tier))})))))))
          (catch #?(:clj Throwable :cljs :default) error
            (remove-entry-if-ticket!
             store tier key (:ticket entry))
            (swap! (:metrics store) update :failures inc)
            (throw error)))))))

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
              (resolve!
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
             (resolve!
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
  (let [{:keys [compound-key entry lookup-action]}
        (select-lookup-entry! store tier key)
        initial-action lookup-action]
    (if (= :bypass-recursive-self initial-action)
      (do
        (swap! (:metrics store) update :self-bypasses inc)
        (swap! (:metrics store) update :lookup-misses inc)
        nil)
      (if entry
        (let [result-delay (:result entry)
              was-realized? (realized? result-delay)
              completed-hit?
              (= :use-completed-value initial-action)]
          ;; Honest metrics: a join-wait on an unrealized flight is not a
          ;; cache hit (mirrors resolve!).
          (if was-realized?
            (do
              (swap! (:metrics store) update :hits inc)
              (swap! (:metrics store)
                     update (tier-hit-metric tier) inc))
            (swap! (:metrics store) update :single-flight-waits inc))
          (try
            (let [value
                  (binding [*resolving-keys*
                            (conj *resolving-keys* compound-key)]
                    @result-delay)
                  valid-value?
                  (or
                   completed-hit?
                   (try
                     (boolean (valid? value))
                     (catch #?(:clj Throwable :cljs :default) _
                       false)))]
              (if valid-value?
                (let [retained?
                      (or
                       completed-hit?
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
                       :cache-tier (exact-cache-tier tier)})
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
