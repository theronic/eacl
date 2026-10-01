(ns eacl.engine.relationships
  (:require [eacl.request.counters :as request-counters]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(def default-limit 1000)
(def maximum-limit 10000)
(def relationship-cursor-version 3)

(defn- sort-token
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (nil? value) ""
    :else (str value)))

(defn relation-sort-key
  [{:keys [resource-type relation-name subject-type]}]
  [(sort-token resource-type)
   (sort-token relation-name)
   (sort-token subject-type)])

(defn matching-relation-def?
  [query {:keys [resource-type relation-name subject-type]}]
  (and (or (nil? (:resource/type query))
           (= (:resource/type query) resource-type))
       (or (nil? (:resource/relation query))
           (= (:resource/relation query) relation-name))
       (or (nil? (:subject/type query))
           (= (:subject/type query) subject-type))))

(defn scan-kind
  [query]
  (cond
    (:subject/id query) :forward-anchored
    (:resource/id query) :reverse-anchored
    (:subject/type query) :forward-partial
    :else :reverse-partial))

(defn plan-scans
  [relation-defs query]
  (let [kind (scan-kind query)]
    (->> relation-defs
         (filter #(matching-relation-def? query %))
         (sort-by relation-sort-key)
         (map-indexed
          (fn [idx relation-def]
            (assoc relation-def
                   :idx idx
                   :scan-kind kind
                   :subject-id (:subject/id query)
                   :resource-id (:resource/id query))))
         vec)))

(defn normalize-limit
  [limit]
  (if (nil? limit) default-limit limit))

(defn- compare-eids
  [a b]
  (cond (< a b) -1 (> a b) 1 :else 0))

(defn- compare-qualifiers
  "Index order of the optional fifth tuple component: a plain row (nil)
  precedes every qualified row, and qualified rows follow qualifier eid order."
  [a b]
  (cond
    (nil? a) (if (nil? b) 0 -1)
    (nil? b) 1
    :else (compare-eids a b)))

(defn physical-compare
  "Compares two rows (or a row and an edge) of one scan spec in the ascending
  physical order of that spec's index.

  Anchored scans read one endpoint's EAVT values and are ordered by the
  opposite endpoint, which is unique per anchor. Partial scans read AVET
  values `[p0 p1 p2 primary qualifier]` across owners, so they are ordered by
  the primary endpoint, then the qualifier (nil first), then the owner: the
  resource, qualifier, subject for `:forward-partial` and the subject,
  qualifier, resource for `:reverse-partial`. A continuation must compare in
  this order; comparing only primary and owner skips or repeats rows whenever
  qualifier order disagrees with owner order (EACL-FORMAL-076)."
  [scan-kind a b]
  (case scan-kind
    :forward-anchored
    (compare-eids (:resource-id a) (:resource-id b))

    :reverse-anchored
    (compare-eids (:subject-id a) (:subject-id b))

    :forward-partial
    (let [primary (compare-eids (:resource-id a) (:resource-id b))]
      (if-not (zero? primary)
        primary
        (let [qualifier (compare-qualifiers (:qualifier-id a) (:qualifier-id b))]
          (if-not (zero? qualifier)
            qualifier
            (compare-eids (:subject-id a) (:subject-id b))))))

    :reverse-partial
    (let [primary (compare-eids (:subject-id a) (:subject-id b))]
      (if-not (zero? primary)
        primary
        (let [qualifier (compare-qualifiers (:qualifier-id a) (:qualifier-id b))]
          (if-not (zero? qualifier)
            qualifier
            (compare-eids (:resource-id a) (:resource-id b))))))))

(defn beyond-cursor?
  "Whether `row` is strictly beyond `cursor` in the requested index direction.

  A `:resume-inclusive?` cursor also admits its own row, identified by its
  subject and resource within the scan spec."
  [scan-kind direction cursor {:keys [subject-id resource-id] :as row}]
  (or
   (nil? cursor)
   (and (:resume-inclusive? cursor)
        (= subject-id (:subject-id cursor))
        (= resource-id (:resource-id cursor)))
   (let [order (physical-compare scan-kind row cursor)]
     (case direction
       :asc (pos? order)
       :desc (neg? order)))))

(defn progress-edge
  "Builds the exclusive relationship-keyset anchor for one examined row.

  The row need not be emitted. Authorized window routes use this to advance
  past rejected candidates while ordinary pages use the selected boundary;
  both are the same stable physical stream position. A qualified row also
  records its qualifier eid, the component that orders rows of one primary
  endpoint in a partial scan; a plain row keeps the original edge shape, so
  an edge without `:qualifier-id` names a plain row's position."
  [{:keys [spec-idx subject-id resource-id qualifier-id]}]
  (cond-> {:kind :relationship-index
           :v relationship-cursor-version
           :anchor :progress
           :scan-index spec-idx
           :subject-id subject-id
           :resource-id resource-id}
    (some? qualifier-id) (assoc :qualifier-id qualifier-id)))

(def ^:private edge-base-keys
  #{:kind :v :anchor :scan-index :subject-id :resource-id})

(def ^:private edge-optional-keys
  #{:resume-inclusive? :qualifier-id})

(defn- valid-edge?
  [scan-specs edge]
  (and (map? edge)
       (every? #(contains? edge %) edge-base-keys)
       (every? #(or (contains? edge-base-keys %)
                    (contains? edge-optional-keys %))
               (keys edge))
       (= :relationship-index (:kind edge))
       (= relationship-cursor-version (:v edge))
       (= :progress (:anchor edge))
       (or (not (contains? edge :resume-inclusive?))
           (true? (:resume-inclusive? edge)))
       (or (not (contains? edge :qualifier-id))
           (nat-int? (:qualifier-id edge)))
       (nat-int? (:scan-index edge))
       (< (:scan-index edge) (count scan-specs))
       (nat-int? (:subject-id edge))
       (nat-int? (:resource-id edge))))

(defn- scan-order-violation!
  [scan-kind direction previous row]
  (throw
   (ex-info
    "A relationship scan returned rows outside its physical index order."
    {:type :eacl/backend-contract-violation
     :eacl/error :eacl/backend-contract-violation
     :operation :read-relationships
     :obligation :strict-order
     :scan-kind scan-kind
     :direction direction
     :previous (select-keys previous [:subject-id :resource-id :qualifier-id])
     :row (select-keys row [:subject-id :resource-id :qualifier-id])})))

(defn- check-scan-order!
  "Fails closed unless one spec's scanned rows advance strictly from `cursor`.

  Continuation resumes strictly beyond the last examined row, so a scan whose
  rows disagree with `beyond-cursor?` would make pages skip or repeat rows,
  or make a filtered window re-examine the same rows forever."
  [scan-kind direction cursor rows]
  (reduce
   (fn [previous row]
     (if (beyond-cursor? scan-kind direction previous row)
       row
       (scan-order-violation! scan-kind direction previous row)))
   cursor
   rows)
  rows)

(defn- invalid-edge!
  [edge]
  (throw
   (ex-info
    "Relationship cursor contains an invalid index edge."
    {:type :eacl.pagination/invalid-cursor
     :eacl/error :eacl.pagination/invalid-cursor
     :reason :invalid-relationship-edge
     :edge-fields (when (map? edge) (vec (sort (keys edge))))})))

(defn- pending-specs
  [scan-specs direction start-index]
  (case direction
    :asc (drop start-index scan-specs)
    :desc (reverse (take (inc start-index) scan-specs))))

(defn- realize-page-rows
  [scan-specs direction size edge scan-fn]
  (let [start-index (if edge
                      (:scan-index edge)
                      (case direction
                        :asc 0
                        :desc (dec (count scan-specs))))
        target (inc size)]
    (loop [pending (if (seq scan-specs)
                     (pending-specs scan-specs direction start-index)
                     [])
           rows []]
      (if (or (empty? pending)
              (= target (count rows)))
        rows
        (let [remaining (- target (count rows))
              spec (assoc (first pending) :physical-limit remaining)
              resume-edge (when (= (:idx spec) start-index) edge)
              scanned (check-scan-order!
                       (:scan-kind spec) direction resume-edge
                       (into [] (take remaining)
                             (scan-fn spec resume-edge direction)))]
          (recur (rest pending) (into rows scanned)))))))

(defn- page-presence
  [query field edge?]
  (cond
    (not (contains? query field)) :absent
    (nil? (get query field)) :nil
    edge? 0
    :else (get query field)))

(defn- raw-page-input
  [query]
  {:length 0
   :request
   {:first (page-presence query :first false)
    :last (page-presence query :last false)
    :after (page-presence query :after true)
    :before (page-presence query :before true)}
   :default-size default-limit
   :maximum-size maximum-limit})

(defn- normalized-page
  [decision-kernel query]
  (when-let [unsupported
             (some #(when (contains? query %) %) [:cursor :limit])]
    (throw
     (ex-info
      "EACL v8 pagination accepts only :first/:after or :last/:before."
      {:type :eacl.pagination/invalid-page-request
       :eacl/error :eacl.pagination/invalid-page-request
       :key unsupported})))
  (let [decision
        (verified/decide
         (or decision-kernel subproblem/*decision-kernel*)
         :relationship-page
         (raw-page-input query))]
    (when (= :invalid (:status decision))
      (throw
       (ex-info
        "Generated relationship pagination rejected the request."
        {:type :eacl.pagination/invalid-cursor
         :eacl/error :eacl.pagination/invalid-cursor
         :reason (:reason decision)})))
    decision))

(defn execute-page
  "Executes one Relay page directly over ordered relationship indexes.

  The authenticated cursor carries the last physical index edge, so continuation
  seeks to that edge and reads at most `page-size + 1` matching rows. Public
  object ids are resolved only for the selected page."
  ([scan-specs query scan-fn]
   (execute-page scan-specs query nil scan-fn))
  ([scan-specs query decision-kernel scan-fn]
   (let [decision-kernel
         (or decision-kernel subproblem/*decision-kernel*)
         {:keys [direction size]} (normalized-page decision-kernel query)
         bound (case direction
                 :asc (:after query)
                 :desc (:before query))
         _ (when (and bound (not (valid-edge? scan-specs bound)))
             (invalid-edge! bound))
         realized (realize-page-rows
                   scan-specs direction size bound scan-fn)
         page-decision
         (verified/decide
          decision-kernel
          :relationship-keyset-page
          {:direction direction
           :size size
           :bound? (some? bound)
           :realized-count (count realized)})
         selected-desc (take (:take-count page-decision) realized)
         selected (vec
                   (if (:reverse? page-decision)
                     (reverse selected-desc)
                     selected-desc))
         any? (boolean (seq selected))]
     {:data (mapv :relationship selected)
      :page-info
      {:start-cursor
       (when any? (progress-edge (first selected)))
       :end-cursor
       (when any? (progress-edge (peek selected)))
       :has-next-page?
       (boolean (and any? (:has-next? page-decision)))
       :has-previous-page?
       (boolean
        (and any? (:has-previous? page-decision)))}})))

(defn- window-specs
  [scan-specs direction edge]
  (let [start-index (if edge
                      (:scan-index edge)
                      (case direction
                        :asc 0
                        :desc (dec (count scan-specs))))]
    {:start-index start-index
     :specs (vec
             (if (seq scan-specs)
               (pending-specs scan-specs direction start-index)
               []))}))

(defn- scan-window-chunk
  [scan-fn spec cursor direction limit]
  (check-scan-order!
   (:scan-kind spec) direction cursor
   (into [] (take limit)
         (scan-fn (assoc spec :physical-limit limit)
                  cursor direction))))

(defn- rows-remain?
  [scan-fn specs spec-position cursor direction]
  (loop [position spec-position
         cursor cursor]
    (if (= position (count specs))
      false
      (let [spec (nth specs position)
            row (first (scan-window-chunk
                        scan-fn spec cursor direction 1))]
        (if row
          true
          (recur (inc position) nil))))))

(defn execute-filtered-window
  "Filters the ordered physical relationship stream inside one bounded page.

  `accept?` is called exactly once per examined candidate. Physical chunks
  never exceed the smaller of the remaining candidate window and the
  remaining accepted sentinel demand. A one-row, predicate-free exhaustion
  probe is used only when the candidate budget lands exactly on a physical
  boundary, so `:bounded?` and the continuation booleans remain exact."
  [scan-specs query decision-kernel scan-fn
   {:keys [candidate-window accept?]}]
  (when-not (and (integer? candidate-window) (pos? candidate-window))
    (throw
     (ex-info
      "The authorization candidate window must be a positive integer."
      {:type :eacl.execution/resource-limit-exceeded
       :eacl/error :eacl.execution/resource-limit-exceeded
       :limit-kind :candidate-window
       :value candidate-window})))
  (when-not (fn? accept?)
    (throw
     (ex-info "A filtered relationship window requires an accept predicate."
              {:type :eacl/invalid-config :eacl/error :eacl/invalid-config})))
  (let [{:keys [direction size]} (normalized-page decision-kernel query)
        bound (case direction
                :asc (:after query)
                :desc (:before query))
        _ (when (and bound (not (valid-edge? scan-specs bound)))
            (invalid-edge! bound))
        {:keys [specs]} (window-specs scan-specs direction bound)
        initial-position 0
        initial-cursor bound
        result
        (loop [spec-position initial-position
               cursor initial-cursor
               examined 0
               accepted []
               last-examined nil]
          (cond
            (= (count accepted) (inc size))
            {:accepted accepted
             :last-examined last-examined
             :more? true
             :sentinel? true
             :bounded? false}

            (= examined candidate-window)
            (let [more? (rows-remain?
                         scan-fn specs spec-position cursor direction)]
              {:accepted accepted
               :last-examined last-examined
               :more? more?
               :sentinel? false
               :bounded? more?})

            (= spec-position (count specs))
            {:accepted accepted
             :last-examined last-examined
             :more? false
             :sentinel? false
             :bounded? false}

            :else
            (let [remaining-window (- candidate-window examined)
                  remaining-sentinel (- (inc size) (count accepted))
                  chunk-limit (min remaining-window remaining-sentinel)
                  spec (nth specs spec-position)
                  chunk (scan-window-chunk
                         scan-fn spec cursor direction chunk-limit)]
              (if (empty? chunk)
                (recur (inc spec-position) nil examined accepted
                       last-examined)
                (let [[accepted examined last-examined]
                      (reduce
                       (fn [[accepted examined _] row]
                         (request-counters/add-candidates-examined!)
                         [(cond-> accepted
                            (accept? (:relationship row))
                            (conj row))
                          (inc examined)
                          row])
                       [accepted examined last-examined]
                       chunk)
                      exhausted-spec? (< (count chunk) chunk-limit)
                      next-position (if exhausted-spec?
                                      (inc spec-position)
                                      spec-position)
                      next-cursor (when-not exhausted-spec?
                                    (progress-edge last-examined))]
                  (recur next-position next-cursor examined accepted
                         last-examined))))))
        selected-direction (vec (take size (:accepted result)))
        selected (if (= :desc direction)
                   (vec (rseq selected-direction))
                   selected-direction)
        progress
        (cond-> (some-> (:last-examined result) progress-edge)
          (:sentinel? result) (assoc :resume-inclusive? true))
        first-selected (some-> selected first progress-edge)
        last-selected (some-> selected peek progress-edge)
        start-cursor (case direction
                       :asc (or first-selected progress)
                       :desc progress)
        end-cursor (case direction
                     :asc progress
                     :desc (or last-selected progress))]
    {:data (mapv :relationship selected)
     :page-info
     {:start-cursor start-cursor
      :end-cursor end-cursor
      :has-next-page?
      (case direction
        :asc (boolean (:more? result))
        :desc (boolean bound))
      :has-previous-page?
      (case direction
        :asc (boolean bound)
        :desc (boolean (:more? result)))
      :bounded? (boolean (:bounded? result))}}))

(defn- build-cursor
  [cursor last-row]
  (or (when last-row
        {:v 3
         :i (:spec-idx last-row)
         :subject (:subject-id last-row)
         :resource (:resource-id last-row)})
      cursor))

(defn execute-plan
  [scan-specs {:keys [cursor limit]} scan-fn]
  (let [limit'     (normalize-limit limit)
        remaining0 (when (and (number? limit') (>= limit' 0)) limit')
        start-idx  (or (:i cursor) 0)
        specs      (drop start-idx scan-specs)]
    (loop [remaining remaining0
           pending specs
           acc []
           last-row nil]
      (if (or (empty? pending)
              (and remaining (zero? remaining)))
        {:data (mapv :relationship acc)
         :cursor (build-cursor cursor last-row)}
        (let [spec          (cond-> (first pending)
                              remaining
                              (assoc :physical-limit remaining))
              resume-cursor (when (= (:idx spec) start-idx) cursor)
              rows          (seq (scan-fn spec resume-cursor))]
          (if (empty? rows)
            (recur remaining (rest pending) acc last-row)
            (let [[remaining' acc' last-row']
                  (loop [rows rows
                         remaining remaining
                         acc acc
                         last-row last-row]
                    (if (or (empty? rows)
                            (and remaining (zero? remaining)))
                      [remaining acc last-row]
                      (let [row (first rows)]
                        (recur (rest rows)
                               (when remaining (dec remaining))
                               (conj acc row)
                               row))))]
              (recur remaining' (rest pending) acc' last-row'))))))))
