(ns eacl.request.counters
  "Mandatory work meters for one public request execution.

  Aggregate resource limits consume these exact counters. They are therefore
  semantic request state, not optional telemetry. A caller binds one ledger at
  the public boundary; internal operations may record work without accepting
  another instrumentation parameter.")

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
   :publications
   :scan-memo-hits
   :scan-shared-hits
   :scan-misses])

(def ^:private known-counter-keys (set counter-keys))
(def ^:private counter-index (zipmap counter-keys (range)))
(def ^:private adapter-reads-index (get counter-index :adapter-reads))
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

#?(:clj
   (def ^:private ^ThreadLocal active-ledger-cache
     ;; A cache entry is [binding-frame ledger]. Comparing the current frame
     ;; preserves nested direct `binding` semantics while avoiding
     ;; PersistentHashMap.entryAt's MapEntry allocation on the normal
     ;; call-with-ledger path. A conveyed binding on another thread has no
     ;; ThreadLocal entry and therefore falls back to the dynamic Var.
     (ThreadLocal.)))

(defn- current-ledger
  []
  #?(:clj
     (let [entry (.get active-ledger-cache)
           frame (clojure.lang.Var/getThreadBindingFrame)]
       (if (and entry (identical? (aget ^objects entry 0) frame))
         (aget ^objects entry 1)
         ;; A nested `binding` (the execution contract, engine observers)
         ;; pushed a new frame. Resolve the Var once and re-key the cache to
         ;; this frame so per-tuple increments under it stay allocation-free;
         ;; every later frame change falls back to the Var again.
         (let [ledger *ledger*]
           (when entry
             (.set active-ledger-cache (object-array [frame ledger])))
           ledger)))
     :cljs
     *ledger*))

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
  (when-let [ledger (current-ledger)]
    (let [values (:values ledger)]
      #?(:clj
         (let [index (int index)
               values ^longs values]
           (aset values index (long (+ (aget values index) amount))))
         :cljs
         (aset values index (+ (aget values index) amount)))))
  nil)

(defn- increment-at!
  "Primitive default increment for fixed counters. Keeping the literal `+1`
  out of `add-at!` avoids boxing the generic amount on every hot-path read."
  [^long index]
  (when-let [ledger (current-ledger)]
    (let [values (:values ledger)]
      #?(:clj
         (let [index (int index)
               values ^longs values]
           (aset values index (Math/addExact (aget values index) (long 1))))
         :cljs
         (aset values index (inc (aget values index))))))
  nil)

(defn ^:no-doc add-commands!
  ([] (increment-at! commands-index))
  ([amount] (add-at! commands-index :commands amount)))

(defn ^:no-doc add-adapter-reads!
  ([] (increment-at! adapter-reads-index))
  ([amount] (add-at! adapter-reads-index :adapter-reads amount)))

(defn ^:no-doc add-fetched-values!
  ([] (increment-at! fetched-values-index))
  ([amount] (add-at! fetched-values-index :fetched-values amount)))

(defn ^:no-doc add-candidates-examined!
  ([] (increment-at! candidates-examined-index))
  ([amount] (add-at! candidates-examined-index :candidates-examined amount)))

(defn ^:no-doc add-probes!
  ([] (increment-at! probes-index))
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
    #?(:clj
       (let [previous (.get active-ledger-cache)
             entry (object-array
                    [(clojure.lang.Var/getThreadBindingFrame) ledger])]
         (.set active-ledger-cache entry)
         (try
           (f)
           (finally
             (if previous
               (.set active-ledger-cache previous)
               (.remove active-ledger-cache)))))
       :cljs
       (f))))
