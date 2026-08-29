(ns eacl.request.counters
  "Mandatory work meters for one public request execution.

  Aggregate resource limits consume these exact counters. They are therefore
  semantic request state, not optional telemetry. A caller binds one ledger at
  the public boundary; internal operations may record work without accepting
  another instrumentation parameter."
  (:refer-clojure :exclude [reset!]))

(def counter-keys
  [:acquisitions
   :releases
   :adapter-reads
   :writer-submissions
   :public-entries
   :context-constructions
   :seals
   :definition-reads
   :generation-reads
   :proof-derivations
   :contract-normalizations
   :identity-conversions
   :cache-key-builds
   :renderings
   :cursor-builds
   :commands
   :fetched-values
   :candidates-examined
   :probes
   :publications])

(def ^:private known-counter-keys (set counter-keys))
(def ^:private counter-index (zipmap counter-keys (range)))
(def ^:private commands-index (get counter-index :commands))
(def ^:private fetched-values-index (get counter-index :fetched-values))
(def ^:private candidates-examined-index
  (get counter-index :candidates-examined))
(def ^:private probes-index (get counter-index :probes))

(defrecord CounterLedger [values])

(def ^:dynamic *ledger*
  "The mutable ledger for the current public request, or nil when observation is off.

  Ledgers use a fixed primitive array so observing one bounded increment does
  not allocate another persistent counter map on the request path."
  nil)

(defn empty-counts
  []
  (zipmap counter-keys (repeat 0)))

(defn make-ledger
  []
  (->CounterLedger
   #?(:clj (long-array (count counter-keys))
      :cljs (into-array (repeat (count counter-keys) 0)))))

(defn- require-counter!
  [counter]
  (when-not (contains? known-counter-keys counter)
    (throw
     (ex-info
      "Unknown request counter."
      {:type :eacl.request/unknown-counter
       :eacl/error :eacl.request/unknown-counter
       :counter counter
       :known-counters counter-keys}))))

(defn- add-at!
  [index counter amount]
  (when-not (and (integer? amount) (not (neg? amount)))
    (throw
     (ex-info
      "Request counter increments must be non-negative integers."
      {:type :eacl.request/invalid-counter-increment
       :eacl/error :eacl.request/invalid-counter-increment
       :counter counter
       :amount amount})))
  (when *ledger*
    (let [values (:values *ledger*)]
      #?(:clj
         (let [index (int index)
               values ^longs values]
           (aset-long values index (+ (aget values index) amount)))
         :cljs
         (aset values index (+ (aget values index) amount)))))
  nil)

(defn ^:no-doc add-commands!
  ([] (add-at! commands-index :commands 1))
  ([amount] (add-at! commands-index :commands amount)))

(defn ^:no-doc add-fetched-values!
  ([] (add-at! fetched-values-index :fetched-values 1))
  ([amount] (add-at! fetched-values-index :fetched-values amount)))

(defn ^:no-doc add-candidates-examined!
  ([] (add-at! candidates-examined-index :candidates-examined 1))
  ([amount] (add-at! candidates-examined-index :candidates-examined amount)))

(defn ^:no-doc add-probes!
  ([] (add-at! probes-index :probes 1))
  ([amount] (add-at! probes-index :probes amount)))

(defn add!
  "Adds a non-negative integer amount to one counter on the bound ledger."
  ([counter]
   (add! counter 1))
  ([counter amount]
   (require-counter! counter)
   (add-at! (get counter-index counter) counter amount)))

(defn snapshot
  "Returns a complete immutable counter map for `ledger`."
  [ledger]
  (let [values (:values ledger)]
    (into {}
          (map-indexed
           (fn [index counter]
             [counter
              #?(:clj (aget ^longs values (int index))
                 :cljs (aget values index))]))
          counter-keys)))

(defn delta
  "Returns the non-negative per-counter difference between two snapshots."
  [before after]
  (reduce
   (fn [result counter]
     (let [difference (- (get after counter 0) (get before counter 0))]
       (when (neg? difference)
         (throw
          (ex-info
           "Request counters cannot move backwards."
           {:type :eacl.request/counter-regression
            :eacl/error :eacl.request/counter-regression
            :counter counter
            :before (get before counter 0)
            :after (get after counter 0)})))
       (assoc result counter difference)))
   {}
   counter-keys))

(defn call-with-ledger
  "Runs synchronous `f` with `ledger` as the current request ledger."
  [ledger f]
  (binding [*ledger* ledger]
    (f)))
