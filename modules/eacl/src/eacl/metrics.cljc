(ns eacl.metrics
  "Non-authoritative, client-private optimization observations.

  Entries are scoped by an immutable source/basis high-watermark. They may
  remove or reshape only certified sequence-equivalent physical work; no
  authorization decision is permitted to depend on this namespace."
  (:require [eacl.exact-integer :as exact-integer]))

(def maximum-entries 4096)

(def ^:dynamic *store* nil)
(def ^:dynamic *context* nil)

(defn make-store []
  (atom {:entries {}
         :io-events {}
         :recorded-events 0
         :evictions 0
         :refreshes 0}))

(defn- checked-add [left right]
  (let [result (+ left right)]
    (if (> result exact-integer/maximum)
      exact-integer/maximum
      result)))

(defn- sample-entry
  [prior samples matches io]
  ;; An exact observation at the same immutable high-watermark dominates any
  ;; later bounded sample. In particular, do not accidentally downgrade an
  ;; exact entry or expect sample-only accumulator fields to be present on it.
  (if (= :exact (:completeness prior))
    (assoc prior :last-io io)
    (-> (or prior
            {:completeness :sample
             :sample-count 0
             :match-count 0
             :observed-lower-bound 0})
        (update :sample-count checked-add samples)
        (update :match-count checked-add matches)
        (update :observed-lower-bound max matches)
        (assoc :last-io io))))

(defn observation-key
  "Builds the exact cache partition for one relationship observation.

  `:high-watermark` is normally the selected native revision. Adapters may
  supply a narrower certified relation generation without changing this API."
  [context descriptor direction]
  (let [{:keys [backend source-id branch source-lifecycle high-watermark]}
        context]
    (when-not (and (keyword? backend)
                   (some? source-id)
                   (some? source-lifecycle)
                   (exact-integer/natural? high-watermark)
                   (map? descriptor)
                   (contains? #{:forward :reverse :asc :desc} direction))
      (throw
       (ex-info "Relationship metric context is incomplete."
                {:type :eacl.metrics/invalid-context
                 :eacl/error :eacl.metrics/invalid-context
                 :context context
                 :direction direction})))
    [backend source-id branch source-lifecycle high-watermark
     direction descriptor]))

(def ^:private key-watermark-index
  "Both entry and io-event keys carry the immutable high-watermark at the
  same position."
  4)

(defn- trim-entries
  ;; Observations are advisory and carry no recency semantics, so no global
  ;; ordering is maintained. On overflow, entries recorded at superseded
  ;; watermarks are preferred victims: their keys can never be reused, and
  ;; evicting them first keeps entries that remain reusable at the current
  ;; watermark resident on write-active sources. The stale scan runs only at
  ;; capacity, never on an ordinary insert.
  ([entries]
   (trim-entries entries nil))
  ([entries current-key]
   (let [current-watermark (when current-key
                             (nth current-key key-watermark-index nil))
         victim
         (fn [entries]
           (or (when (some? current-watermark)
                 (reduce-kv
                  (fn [_ key _]
                    (when (not= current-watermark
                                (nth key key-watermark-index nil))
                      (reduced key)))
                  nil
                  entries))
               (key (first entries))))]
     (loop [entries entries
            overflow (- (count entries) maximum-entries)
            evicted 0]
       (if (pos? overflow)
         (recur (dissoc entries (victim entries))
                (dec overflow)
                (inc evicted))
         [entries evicted])))))

(defn record-membership!
  "Records an already-completed exact membership batch.

  Candidate probes do not prove total relation cardinality. Consequently the
  entry stores samples, matches, and a conservative observed lower bound, not
  an invented exact count."
  ([descriptor direction candidates decisions]
   (record-membership! *store* *context*
                       descriptor direction candidates decisions nil))
  ([store context descriptor direction candidates decisions io]
   (when (and store context)
     (when-not (and (vector? candidates)
                    (vector? decisions)
                    (= (count candidates) (count decisions))
                    (every? boolean? decisions))
       (throw
        (ex-info "Relationship membership observation is malformed."
                 {:type :eacl.metrics/invalid-observation
                  :eacl/error :eacl.metrics/invalid-observation})))
     (let [key (observation-key context descriptor direction)
           samples (count candidates)
           matches (count (filter true? decisions))]
       (swap! store
              (fn [state]
                (let [prior (get-in state [:entries key])
                      entry (sample-entry prior samples matches io)
                      [entries evicted]
                      (trim-entries (assoc (:entries state) key entry) key)]
                  (-> state
                      (assoc :entries entries)
                      (update :recorded-events checked-add 1)
                      (update :evictions checked-add evicted)))))))
   nil))

(defn record-exhausted!
  "Publishes an exact count only for a stream proven to cover its complete
  descriptor domain at the bound high-watermark."
  ([descriptor direction exact-count]
   (record-exhausted! *store* *context* descriptor direction exact-count nil))
  ([store context descriptor direction exact-count io]
   (when (and store context)
     (when-not (exact-integer/natural? exact-count)
       (throw
        (ex-info "Exact relationship observation count is invalid."
                 {:type :eacl.metrics/invalid-observation
                  :eacl/error :eacl.metrics/invalid-observation
                  :exact-count exact-count})))
     (let [key (observation-key context descriptor direction)]
       (swap! store
              (fn [state]
                (let [[entries evicted]
                      (trim-entries
                       (assoc (:entries state) key
                              {:completeness :exact
                               :exact-count exact-count
                               :observed-lower-bound exact-count
                               :last-io io})
                       key)]
                  (-> state
                      (assoc :entries entries)
                      (update :recorded-events checked-add 1)
                      (update :evictions checked-add evicted)))))))
   nil))

(defn record-count!
  "Records a completed public count at the current immutable high-watermark.

  An untruncated count is exact for its normalized semantic descriptor. A
  truncated count contributes only an observed lower bound. This is advisory
  cache data and is not the completed-answer cache."
  ([descriptor direction result]
   (record-count! *store* *context* descriptor direction result))
  ([store context descriptor direction {:keys [count truncated?] :as result}]
   (when (and store context)
     (when-not (and (map? result)
                    (exact-integer/natural? count)
                    (or (nil? truncated?) (boolean? truncated?)))
       (throw
        (ex-info "Count observation is malformed."
                 {:type :eacl.metrics/invalid-observation
                  :eacl/error :eacl.metrics/invalid-observation})))
     (if (true? truncated?)
       (let [key (observation-key context descriptor direction)]
         (swap! store
                (fn [state]
                  (let [entry (sample-entry
                               (get-in state [:entries key]) count count nil)
                        [entries evicted]
                        (trim-entries (assoc (:entries state) key entry) key)]
                    (-> state
                        (assoc :entries entries)
                        (update :recorded-events checked-add 1)
                        (update :evictions checked-add evicted))))))
       (record-exhausted! store context descriptor direction count nil)))
   nil))

(defn record-scan!
  "Records one already-demanded ordered scan chunk. A first chunk shorter than
  its positive limit (or an unbounded fully realized scan) proves exact
  cardinality for the normalized endpoint descriptor. Other chunks contribute
  only samples/lower bounds."
  ([descriptor values]
   (record-scan! *store* *context* descriptor values))
  ([store context descriptor values]
   (when (and store context (map? descriptor) (vector? values))
     (let [direction (or (:direction descriptor) :asc)
           normalized (dissoc descriptor :limit :bound-eid :direction)
           limit (:limit descriptor)
           first-chunk? (nil? (:bound-eid descriptor))
           exhausted? (and first-chunk?
                           (or (nil? limit) (< (count values) limit)))]
       (if exhausted?
         (record-exhausted! store context normalized direction
                            (count values) nil)
         (let [key (observation-key context normalized direction)
               observed (count values)]
           (swap! store
                  (fn [state]
                    (let [prior (get-in state [:entries key])
                          entry (sample-entry prior observed observed nil)
                          [entries evicted]
                          (trim-entries
                           (assoc (:entries state) key entry) key)]
                      (-> state
                          (assoc :entries entries)
                          (update :recorded-events checked-add 1)
                          (update :evictions checked-add evicted)))))))))
   nil))

(defn record-io!
  "Records optional adapter I/O telemetry for the current immutable basis.
  The payload is intentionally opaque to semantic code; adapters and
  diagnostics may normalize or inspect it without turning it into a count."
  ([operation io]
   (record-io! *store* *context* operation io))
  ([store context operation io]
   (when (and store context (keyword? operation) (map? io))
     (let [key [(:backend context)
                (:source-id context)
                (:branch context)
                (:source-lifecycle context)
                (:high-watermark context)
                operation]]
       (swap! store
              (fn [state]
                (let [[io-events evicted]
                      (trim-entries
                       (assoc (:io-events state) key
                              {:observation io
                               :classification :physical-cost-only})
                       key)]
                  (-> state
                      (assoc :io-events io-events)
                      (update :recorded-events checked-add 1)
                      (update :evictions checked-add evicted)))))))
   nil))

(defn lookup
  ([descriptor direction]
   (lookup *store* *context* descriptor direction))
  ([store context descriptor direction]
   (when (and store context)
     (get-in @store [:entries
                     (observation-key context descriptor direction)]))))

(defn refresh!
  "Evicts relationship observations. A subsequent ordinary read repopulates
  bounded samples organically; this operation itself performs no backend I/O."
  ([store]
   (when store
     (swap! store
            (fn [state]
              (-> state
                  (assoc :entries {})
                  (assoc :io-events {})
                  (update :refreshes checked-add 1)))))
   nil)
  ([] (refresh! *store*)))

(defn stats [store]
  (if store
    (let [state @store]
      (assoc (dissoc state :entries :io-events)
             :entry-count (count (:entries state))
             :io-event-count (count (:io-events state))
             :exact-entry-count
             (count (filter #(= :exact (:completeness %))
                            (vals (:entries state))))))
    {:disabled? true}))
