(ns eacl.engine.scan-cache
  "Exact scan-response prefixes at the routed physical read seam.

  One entry per read descriptor (operation, anchor, relation, target type,
  direction) holds a prefix of the adapter's scan sequence from its first
  value and whether that prefix is the complete sequence. A fetch is served
  only when the prefix reproduces the adapter's reply for the requested
  exclusive bound and limit exactly; otherwise the identical command is
  forwarded and its reply may extend the prefix. Nothing above the seam
  changes: served values pass through the same limit accounting as fetched
  values, and a thrown adapter failure deposits nothing.

  Two tiers share the entry model. The request-local memo is part of
  ordinary execution on one immutable basis: it touches no shared store and
  is bounded by a descriptor count. The shared tier is a client-owned
  bounded store consulted only under a validity scope the request supplies
  (nil scope bypasses it) and switched off by `:cache? false` or a disabled
  client cache."
  (:require [eacl.cache.standard-lru :as lru]
            [eacl.request.counters :as request-counters]))

(def default-memo-bound
  "Distinct descriptors one request memoizes before it stops retaining."
  4096)

(def default-max-prefix
  "Longest prefix one entry may hold, in values."
  512)

(def default-max-entries
  "Entry bound of the shared tier."
  2048)

(def ^:dynamic ^:no-doc *memo-disabled?*
  "Internal test seam: when true the request-local memo neither serves nor
  retains, so memo-free execution's command multiset can be observed."
  false)

(def ^:dynamic ^:no-doc *shared-disabled?*
  "Internal test and benchmark seam: when true the shared tier is neither
  consulted nor deposited into, whatever the client configured."
  false)

(defn descriptor-key
  "The reuse identity of one read descriptor, or nil for an unknown
  operation. Bound and limit are not part of it: one prefix serves every
  (bound, limit) it can reproduce."
  [{:keys [operation relation-eid direction] :as descriptor}]
  (case operation
    :subject->resources
    [:subject->resources (:subject-type descriptor) (:subject-eid descriptor)
     relation-eid (:resource-type descriptor) (or direction :asc)]
    :resource->subjects
    [:resource->subjects (:resource-type descriptor) (:resource-eid descriptor)
     relation-eid (:subject-type descriptor) (or direction :asc)]
    nil))

(defn- beyond?
  "True when `value` lies strictly beyond `bound` in the scan direction."
  [direction value bound]
  (if (= :desc direction)
    (neg? (compare value bound))
    (pos? (compare value bound))))

(defn- first-beyond
  "Index of the first prefix value strictly beyond `bound`; the prefix count
  when none is. A nil bound is the scan start."
  [prefix bound direction]
  (if (nil? bound)
    0
    (loop [low 0
           high (count prefix)]
      (if (< low high)
        (let [middle (quot (+ low high) 2)]
          (if (beyond? direction (nth prefix middle) bound)
            (recur low middle)
            (recur (inc middle) high)))
        low))))

(defn serve
  "The adapter's exact reply for (bound, limit) from `entry`, or nil when the
  entry cannot reproduce it: at least `limit` values beyond the bound, or
  every value beyond it when the prefix is the complete scan."
  [{:keys [prefix exhausted?]} bound limit direction]
  (let [start (first-beyond prefix bound direction)
        available (- (count prefix) start)]
    (cond
      (>= available limit) (subvec prefix start (+ start limit))
      exhausted? (subvec prefix start)
      :else nil)))

(defn extend-entry
  "The entry after one adapter reply `values` for (bound, limit).

  Returns a new entry when the reply lengthens the prefix from the scan
  start, the same `entry` when it already covers the reply, and nil when the
  reply is a fragment that does not start at a known position or when the
  extension would exceed `max-prefix` (the existing entry is then retained
  unchanged)."
  [entry bound values limit direction max-prefix]
  (let [values (vec values)
        exhausted? (< (count values) limit)]
    (if (nil? bound)
      (cond
        (and entry (>= (count (:prefix entry)) (count values))) entry
        (<= (count values) max-prefix) {:prefix values :exhausted? exhausted?}
        :else nil)
      (when entry
        (let [prefix (:prefix entry)
              start (first-beyond prefix bound direction)
              contiguous? (or (< start (count prefix))
                              (and (pos? (count prefix))
                                   (zero? (compare bound (peek prefix)))))]
          (when contiguous?
            (if (>= (- (count prefix) start) (count values))
              entry
              (let [extended (into (subvec prefix 0 start) values)]
                (when (<= (count extended) max-prefix)
                  {:prefix extended :exhausted? exhausted?})))))))))

;; ---------------------------------------------------------------------------
;; Request-local memo
;; ---------------------------------------------------------------------------

(defn memo
  "A fresh request-local memo: a volatile map bounded by `bound` descriptors."
  ([] (memo default-memo-bound))
  ([bound] (volatile! {::bound bound})))

(defn- memo-entry
  [memo key]
  (get @memo key))

(defn- memo-remember!
  "Retains `entry` under `key` unless the memo is full and the key is new."
  [memo key entry]
  (let [state @memo]
    (when (or (contains? state key)
              (< (dec (count state)) (::bound state)))
      (vswap! memo assoc key entry)))
  nil)

;; ---------------------------------------------------------------------------
;; Shared tier
;; ---------------------------------------------------------------------------

(defn tier
  "A client-owned shared tier over the standard bounded store."
  [{:keys [max-entries max-prefix]
    :or {max-entries default-max-entries
         max-prefix default-max-prefix}}]
  {:store (lru/store max-entries)
   :max-entries max-entries
   :max-prefix max-prefix
   :metrics (atom {:hits 0 :misses 0 :deposits 0 :extensions 0
                   :scope-unavailable 0})})

(defn tier?
  [value]
  (and (map? value) (lru/store? (:store value))))

(defn- meter!
  [tier metric]
  (when tier
    (swap! (:metrics tier) update metric inc))
  nil)

(defn stats
  "The shared tier's meters plus its resident entry count."
  [tier]
  (assoc @(:metrics tier)
         :entry-count (lru/entry-count (:store tier))
         :max-entries (:max-entries tier)
         :max-prefix (:max-prefix tier)))

(defn shared-key
  "The shared tier's storage key: the validity scope with the descriptor key.
  Two requests share an entry only under equal scopes."
  [scope key]
  [scope key])

(defn forward-command
  "Forwards the evaluator's command to the routed fetch function unchanged:
  same descriptor, bound, limit, and direction."
  [inner descriptor]
  (inner descriptor))

(defn- shared-deposit!
  [tier key resident candidate]
  (let [store (:store tier)]
    (if (nil? resident)
      (lru/put-if-absent! store key candidate)
      (lru/replace-if! store key resident candidate))))

;; ---------------------------------------------------------------------------
;; The caching fetch function
;; ---------------------------------------------------------------------------

(defn caching-fetch-fn
  "Wraps the routed fetch function `inner` with the request memo and, when
  `tier` and `scope-fn` are supplied, the shared tier. `scope-fn` maps a
  relation id to the request's validity scope for that relation or nil."
  [inner {:keys [memo tier scope-fn]}]
  (let [max-prefix (if tier (:max-prefix tier) default-max-prefix)]
    (fn [descriptor]
      (let [key (descriptor-key descriptor)
            limit (:limit descriptor)]
        (if (or (nil? key) (not (pos-int? limit)))
          (inner descriptor)
          (let [bound (:bound-eid descriptor)
                direction (or (:direction descriptor) :asc)
                memo? (and memo (not *memo-disabled?*))
                resident-memo (when memo? (memo-entry memo key))]
            (if-let [reply (and resident-memo
                                (serve resident-memo bound limit direction))]
              (do (request-counters/add! :scan-memo-hits)
                  reply)
              (let [scope (when (and tier scope-fn (not *shared-disabled?*))
                            (scope-fn (:relation-eid descriptor)))
                    shared-key (when scope (shared-key scope key))
                    _ (when (and tier (nil? scope))
                        (meter! tier :scope-unavailable))
                    shared-hit (when shared-key
                                 (lru/lookup! (:store tier) shared-key))
                    resident-shared (when (:found? shared-hit)
                                      (:value shared-hit))]
                (if-let [reply (and resident-shared
                                    (serve resident-shared bound limit
                                           direction))]
                  (do (request-counters/add! :scan-shared-hits)
                      (meter! tier :hits)
                      (when memo?
                        (memo-remember! memo key resident-shared))
                      reply)
                  (let [values (forward-command inner descriptor)]
                    (request-counters/add! :scan-misses)
                    (when shared-key (meter! tier :misses))
                    (let [base (or resident-memo resident-shared)
                          candidate (extend-entry base bound values limit
                                                  direction max-prefix)]
                      (when (and candidate (not (identical? candidate base)))
                        (when memo? (memo-remember! memo key candidate))
                        (when shared-key
                          (when (shared-deposit! tier shared-key
                                                 resident-shared candidate)
                            (meter! tier (if resident-shared
                                           :extensions
                                           :deposits))))))
                    values))))))))))
