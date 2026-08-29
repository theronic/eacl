(ns eacl.operator.lookup
  "Demand-bounded lookup and counts over a least-path raw cover plus exact
  aligned operator predicates."
  (:require [eacl.engine.least-path :as least-path]
            [eacl.execution :as execution]
            [eacl.operator.batch-schedule :as batch-schedule]
            [eacl.operator.cover-plan :as cover-plan]
            [eacl.operator.seekable :as seekable]
            [eacl.operator.vector-evaluator :as vector-evaluator]
            [eacl.request.counters :as request-counters]))

(def default-candidate-window 4096)

(def ^:dynamic *lookup-stats*
  "Optional observation-only atom for logical/physical lookup dimensions."
  nil)

(defn- add-stat! [counter amount]
  (when *lookup-stats*
    (swap! *lookup-stats* update counter (fnil + 0) amount))
  nil)

(defn- observe-page! [logical-candidates accepted-results widths overread]
  (add-stat! :logical-candidates logical-candidates)
  (add-stat! :accepted-results accepted-results)
  (add-stat! :batch-count (count widths))
  (add-stat! :physical-overread overread)
  (when *lookup-stats*
    (swap! *lookup-stats* assoc :batch-widths widths))
  nil)

(defn- invalid! [reason message data]
  (throw
   (ex-info message
            (merge {:type :eacl.operator/invalid-lookup
                    :eacl/error :eacl.operator/invalid-lookup
                    :reason reason}
                   data))))

(defn- positive! [field value]
  (when-not (pos-int? value)
    (invalid! :invalid-limit "Operator lookup limits must be positive integers."
              {:field field :value value}))
  value)

(defn- expression-roots [plan]
  (into {} (map (juxt :permission :root)) (:expressions plan)))

(defn- semantic-candidate
  [permission direction subject-type subject-eid resource-eid true-nodes]
  {:direction direction
   :subject-type subject-type
   :subject-eid subject-eid
   :resource-type (first permission)
   :resource-eid resource-eid
   :true-nodes true-nodes})

(defn- local-node-acceptor
  [{:keys [adapter plan cache-lookup vector-limits scope-identity]} cover-plan]
  (let [root-semantic (:operator-root-semantic cover-plan)
        node-map (:operator-synthetic->semantic cover-plan)]
    (fn [{:keys [node direction subject-type subject-eid resource-eid]}]
      (let [[permission node-id :as semantic] (get node-map node)
            predicate (get-in plan
                              [:predicate-programs permission node-id])]
        (when-not semantic
          (invalid! :unknown-cover-node
                    "Least-path requested an unmapped operator cover node."
                    {:node node}))
        ;; The public root is filtered in an aligned demand-sized vector.
        ;; Direct leaves are exact by the certified relation scan itself.
        (if (or (= semantic root-semantic)
                (= :direct-membership (:instruction predicate)))
          true
          (first
           (vector-evaluator/check-cached-many-eids
            (cond->
             {:adapter adapter :plan plan
              :permission permission :node-id node-id
              :candidates
              [(semantic-candidate permission direction
                                   subject-type subject-eid resource-eid #{})]
              :limits vector-limits
              :scope-identity scope-identity}
              cache-lookup (assoc :cache-lookup cache-lookup)))))))))

(defn- raw-options
  [{:keys [adapter cover-plan traversal subject-type anchor-eid width
           order-direction boundary cut-point! traversal-limits
           candidate-accept?]}]
  (merge
   traversal-limits
   {:adapter adapter
    :plan cover-plan
    :subject-type subject-type
    :page-size width
    :raw-candidates? true
    :cut-point! cut-point!
    :candidate-accept? candidate-accept?}
   (if (= :forward traversal)
     {:subject-eid anchor-eid}
     {:resource-eid anchor-eid})
   (if (= :desc order-direction)
     (if boundary {:before-coords boundary} {:last? true})
     (when boundary {:after-coords boundary}))))

(defn- raw-page [options]
  (if (:specialization-node options)
    (seekable/page options)
    ((if (= :forward (:traversal options))
       least-path/forward-page
       least-path/reverse-page)
     (raw-options options))))

(defn- specialization-node [plan permission]
  (let [root-id (get (expression-roots plan) permission)
        source-id (get-in plan [:generators permission root-id :source-node])]
    (some #(when (contains? #{:direct-k-way-intersection
                              :direct-monotone-exclusion}
                            (get-in plan [:specializations permission % :kind]))
             %)
          (distinct [root-id source-id]))))

(defn- emission-witness
  [plan cover-plan permission node-id specialization-node emission]
  (let [{:keys [source-node source-nodes]}
        (get-in plan [:generators permission node-id])]
    (cond
      (= specialization-node node-id)
      #{[permission node-id]}

      (some? source-node)
      #{[permission source-node]}

      (seq source-nodes)
      (let [ordinal (first (:coords emission))
            root (:root cover-plan)
            rule (some #(when (and (= root (:node %))
                                   (= ordinal (:ordinal %))) %)
                       (:rules cover-plan))
            child (get (:operator-synthetic->semantic cover-plan)
                       (:target-node rule))]
        (if child #{child} #{}))

      :else #{})))

(defn- candidate
  [permission traversal subject-type anchor-eid witness {:keys [value]}]
  (if (= :forward traversal)
    (semantic-candidate permission :forward subject-type anchor-eid value
                        witness)
    (semantic-candidate permission :reverse subject-type value anchor-eid
                        witness)))

(defn- evaluate-emissions
  [{:keys [adapter plan traversal subject-type anchor-eid
           cache-lookup vector-limits permission specialization-node
           accept-result? scope-identity]}
   cover-plan emissions]
  (let [permission (or permission (:root plan))
        node-id (get (expression-roots plan) permission)
        witnesses (mapv #(emission-witness plan cover-plan permission
                                           node-id specialization-node %)
                        emissions)
        candidates (mapv #(candidate permission traversal subject-type
                                     anchor-eid %1 %2)
                         witnesses emissions)
        decisions
        (vector-evaluator/check-cached-many-eids
         (cond-> {:adapter adapter :plan plan :permission permission
                  :node-id node-id :candidates candidates
                  :limits vector-limits :scope-identity scope-identity}
           cache-lookup (assoc :cache-lookup cache-lookup)))]
    (mapv (fn [emission witness decision]
            (assoc emission
                   :true-nodes witness
                   :accepted?
                   (boolean
                    (and decision
                         (or (nil? accept-result?)
                             (accept-result? (:value emission)))))))
          emissions witnesses decisions)))

(defn- add-counters [total delta]
  (merge-with + total
              (select-keys delta
                           [:commands :fetched-values :stream-opens
                            :emissions])))

(defn resume-coordinate
  "Returns the logical continuation coordinate. A physically evaluated
  sentinel or overread suffix never advances the public cursor."
  [sentinel? selected last-examined]
  (if sentinel?
    (some-> selected peek :coords)
    last-examined))

(defn lookup-page
  "Returns one filtered operator page in generator direction.

  :traversal is :forward (resources for a subject) or :reverse (subjects for
  a resource). :order-direction is :asc or :desc. :boundary is a strict raw
  least-path coordinate boundary. The returned :resume-coords never advances
  through physical vector overread."
  [{:keys [adapter plan traversal subject-type anchor-eid page-size
           candidate-window order-direction boundary permission]
    :or {candidate-window default-candidate-window
         order-direction :asc}
    :as options}]
  (positive! :page-size page-size)
  (positive! :candidate-window candidate-window)
  (when-not (contains? #{:forward :reverse} traversal)
    (invalid! :invalid-traversal "Operator traversal direction is invalid."
              {:traversal traversal}))
  (when-not (contains? #{:asc :desc} order-direction)
    (invalid! :invalid-order "Operator page order direction is invalid."
              {:order-direction order-direction}))
  (when-not (and (keyword? subject-type) (some? anchor-eid))
    (invalid! :invalid-anchor "Operator lookup anchor is malformed."
              {:subject-type subject-type :anchor-eid anchor-eid}))
  (when-not (or (nil? (:accept-result? options))
                (fn? (:accept-result? options)))
    (invalid! :invalid-result-filter
              "Operator result filter must be callable."
              {:value-type (some-> (:accept-result? options) type str)}))
  (let [permission (or permission (:root plan))
        cover-plan (or (:cover-plan options)
                       (cover-plan/seal-plan adapter plan permission))
        candidate-accept? (local-node-acceptor
                           (assoc options :permission permission)
                           cover-plan)
        options (assoc options :permission permission
                       :order-direction order-direction
                       :candidate-accept? candidate-accept?
                       :specialization-node
                       (when-not (false? (:direct-specializations? options))
                         (specialization-node plan permission)))
        result-demand (inc page-size)]
    (loop [schedule (batch-schedule/initial result-demand candidate-window)
           boundary boundary
           accepted []
           last-examined nil
           counters {:commands 0 :fetched-values 0
                     :stream-opens 0 :emissions 0}
           widths []]
      (execution/check! execution/*contract*
                        :operator-lookup/batch-boundary
                        {:candidates (:examined schedule)})
      (if (batch-schedule/done? schedule)
        (let [sentinel? (> (count accepted) page-size)
              selected (vec (take page-size accepted))
              resume (resume-coordinate sentinel? selected last-examined)
              bounded? (and (not sentinel?)
                            (zero? (:remaining-window schedule)))]
          (observe-page! (:examined schedule) (count selected) widths 0)
          {:emissions selected
           :has-more? (boolean (or sentinel? bounded?))
           :bounded? (boolean bounded?)
           :exhausted? false
           :resume-coords resume
           :last-examined-coords last-examined
           :physical-overread 0
           :counters counters})
        (let [width (:next-width schedule)
              raw (raw-page
                   (assoc options :cover-plan cover-plan :width width
                          :boundary boundary))
              emissions (:emissions raw)
              counters (add-counters counters (:counters raw))]
          (if (empty? emissions)
            (do
              (observe-page! (:examined schedule)
                             (min page-size (count accepted)) widths 0)
              {:emissions (vec (take page-size accepted))
               :has-more? false :bounded? false :exhausted? true
               :resume-coords last-examined
               :last-examined-coords last-examined
               :physical-overread 0
               :counters counters})
            (let [evaluated (evaluate-emissions options cover-plan emissions)
                  remaining-demand (- result-demand (count accepted))
                  consumed
                  (loop [remaining evaluated consumed [] grants 0]
                    (if-let [value (first remaining)]
                      (let [grants (+ grants (if (:accepted? value) 1 0))
                            consumed (conj consumed value)]
                        (if (= grants remaining-demand)
                          consumed
                          (recur (rest remaining) consumed grants)))
                      consumed))
                  grants (filterv :accepted? consumed)
                  accepted (into accepted grants)
                  consumed-count (count consumed)
                  overread (- (count evaluated) consumed-count)
                  last-consumed (some-> consumed peek :coords)
                  physical-boundary (some-> emissions peek :coords)
                  sentinel? (= (count accepted) result-demand)
                  exhausted? (and (:exhausted? raw)
                                  (= consumed-count (count emissions)))]
              (request-counters/add-candidates-examined! consumed-count)
              (if sentinel?
                (let [selected (vec (take page-size accepted))]
                  (observe-page! (+ (:examined schedule) consumed-count)
                                 (count selected) (conj widths width)
                                 overread)
                  {:emissions selected
                   :has-more? true :bounded? false :exhausted? false
                   ;; Resume after the last PUBLIC result, so a sentinel and
                   ;; every physically overread candidate remain replayable.
                   :resume-coords
                   (resume-coordinate true selected last-consumed)
                   :last-examined-coords last-consumed
                   :physical-overread overread
                   :counters counters})
                (if exhausted?
                  (do
                    (observe-page! (+ (:examined schedule) consumed-count)
                                   (count accepted) (conj widths width)
                                   overread)
                    {:emissions (vec accepted)
                     :has-more? false :bounded? false :exhausted? true
                     :resume-coords last-consumed
                     :last-examined-coords last-consumed
                     :physical-overread overread
                     :counters counters})
                  (recur (batch-schedule/advance
                          schedule width (count grants))
                         physical-boundary
                         accepted
                         last-consumed
                         counters
                         (conj widths width)))))))))))

(defn count-results
  "Exact count when :count-limit is absent; otherwise stops after the
  lookahead result needed to report truncation. Exact and bounded work remain
  separately observable through :exhaustive?."
  [{:keys [adapter plan count-limit permission]
    :as options}]
  (when-not (or (nil? count-limit)
                (and (integer? count-limit) (not (neg? count-limit))))
    (invalid! :invalid-count-limit "Count limit must be a natural integer."
              {:count-limit count-limit}))
  (let [permission (or permission (:root plan))
        cover-plan (or (:cover-plan options)
                       (cover-plan/seal-plan adapter plan permission))
        candidate-accept? (local-node-acceptor
                           (assoc options :permission permission)
                           cover-plan)
        options (assoc options :permission permission
                       :candidate-accept? candidate-accept?
                       :specialization-node
                       (when-not (false? (:direct-specializations? options))
                         (specialization-node plan permission)))
        target (when (some? count-limit) (inc count-limit))
        initial-width (if target
                        (min batch-schedule/maximum-width target)
                        batch-schedule/maximum-width)]
    (loop [boundary nil
           accumulated 0
           width initial-width
           counters {:commands 0 :fetched-values 0
                     :stream-opens 0 :emissions 0}]
      (execution/check! execution/*contract*
                        :operator-count/batch-boundary
                        {:count accumulated})
      (let [raw (raw-page
                 (assoc options :cover-plan cover-plan
                        :width width
                        :order-direction :asc :boundary boundary))
            emissions (:emissions raw)
            counters (add-counters counters (:counters raw))]
        (if (empty? emissions)
          {:count accumulated :limit (or count-limit -1) :truncated? false
           :exhaustive? (nil? count-limit) :counters counters}
          (let [evaluated (evaluate-emissions options cover-plan emissions)
                grants (count (filter :accepted? evaluated))
                next-count (+ accumulated grants)]
            (if (and target (>= next-count target))
              {:count count-limit :limit count-limit :truncated? true
               :exhaustive? false :counters counters}
              (if (:exhausted? raw)
                {:count next-count :limit (or count-limit -1)
                 :truncated? false :exhaustive? (nil? count-limit)
                 :counters counters}
                (recur (some-> emissions peek :coords)
                       next-count
                       (if target
                         (min batch-schedule/maximum-width
                              (max (- target next-count)
                                   (* 2 width)))
                         batch-schedule/maximum-width)
                       counters)))))))))
