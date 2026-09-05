(ns eacl.operator.lookup
  "Demand-bounded lookup and counts over a least-path raw cover plus exact
  aligned operator predicates."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.engine.least-path :as least-path]
            [eacl.execution :as execution]
            [eacl.operator.batch-schedule :as batch-schedule]
            [eacl.operator.cover-plan :as cover-plan]
            [eacl.operator.plan :as operator-plan]
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

(defn- result-policy [options]
  (let [policy (get options :result-policy :definite)]
    (when-not (contains? #{:definite :detailed} policy)
      (invalid! :result-policy "Lookup result policy must be :definite or :detailed." {}))
    policy))

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
  (let [root-id (get (operator-plan/expression-roots plan) permission)
        source-id (get-in plan [:generators permission root-id :source-node])]
    (some #(when (contains? #{:direct-k-way-intersection
                              :direct-monotone-exclusion}
                            (get-in plan [:specializations permission % :kind]))
             %)
          (distinct [root-id source-id]))))

(defn- emission-witness-fn
  "Returns the witness function for one generator node: the plan nodes an
  emission has already proven true. A union root's child is read from its
  root-rule ordinal; that index is built once per batch instead of scanning
  the cover rules for every emission."
  [plan cover-plan permission node-id specialization-node]
  (let [{:keys [source-node source-nodes]}
        (get-in plan [:generators permission node-id])]
    (cond
      (= specialization-node node-id)
      (constantly #{[permission node-id]})

      (some? source-node)
      (constantly #{[permission source-node]})

      (seq source-nodes)
      (let [root (:root cover-plan)
            synthetic->semantic (:operator-synthetic->semantic cover-plan)
            ordinal->child
            (reduce (fn [index rule]
                      (if (and (= root (:node rule))
                               (not (contains? index (:ordinal rule))))
                        (assoc index (:ordinal rule)
                               (get synthetic->semantic (:target-node rule)))
                        index))
                    {}
                    (:rules cover-plan))]
        (fn [emission]
          (if-let [child (get ordinal->child (first (:coords emission)))]
            #{child}
            #{})))

      :else (constantly #{}))))

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
           accept-result? scope-identity qualification result-policy]}
   cover-plan emissions]
  (let [permission (or permission (:root plan))
        node-id (get (operator-plan/expression-roots plan) permission)
        witnesses (mapv (emission-witness-fn plan cover-plan permission
                                             node-id specialization-node)
                        emissions)
        candidates (mapv (fn [witness emission]
                           (let [value (candidate permission traversal subject-type anchor-eid witness emission)]
                             (if qualification
                               (do
                                 (when-not (contains? emission :evidence)
                                   (invalid! :missing-evidence-witness "Qualified traversal must supply exact generator evidence." {}))
                                 (-> value
                                     (assoc :true-nodes #{}
                                            :evidence-witnesses (zipmap witness (repeat (:evidence emission))))))
                               value)))
                         witnesses emissions)
        decisions
        (vector-evaluator/check-cached-many-eids
         (cond-> {:adapter adapter :plan plan :permission permission
                  :node-id node-id :candidates candidates
                  :limits vector-limits :scope-identity scope-identity}
           qualification (assoc :qualification qualification
                                :witness-scope (qualification/exact-reuse-identity qualification))
           cache-lookup (assoc :cache-lookup cache-lookup)))]
    (mapv (fn [emission witness decision]
            (when qualification (evidence/throw-if-fault! decision))
            (cond-> (assoc emission
                           :accepted?
                           (boolean
                            (and (if (= result-policy :detailed)
                                   (not (evidence/no? decision))
                                   (evidence/has? decision))
                                 (or (nil? accept-result?)
                                     (accept-result? (:value emission))))))
              qualification (assoc :evidence decision)
              (not qualification) (assoc :true-nodes witness)))
          emissions witnesses decisions)))

(defn- add-counters [total delta]
  ;; Both raw producers emit exactly the four counter keys.
  (merge-with + total delta))

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
  (let [result-policy (result-policy options)
        permission (or permission (:root plan))
        cover-plan (or (:cover-plan options)
                       (cover-plan/seal-plan adapter plan permission))
        candidate-accept? (local-node-acceptor
                           (assoc options :permission permission)
                           cover-plan)
        options (assoc options :permission permission :result-policy result-policy
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
           widths (when *lookup-stats* [])]
      (execution/check! execution/*contract*
                        :operator-lookup/batch-boundary
                        #(hash-map :candidates (:examined schedule)))
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
                                 (count selected)
                                 (some-> widths (conj width)) overread)
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
                                   (count accepted)
                                   (some-> widths (conj width)) overread)
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
                         (some-> widths (conj width))))))))))))

(defn- count-response [n count-limit truncated? counters categories]
  (cond-> {:count n :limit (or count-limit -1) :truncated? truncated?
           :exhaustive? (nil? count-limit) :counters counters}
    categories (merge categories)))

(defn- count-categories [categories evaluated remaining]
  ;; Count only selected results. A lookahead grant establishes truncation but
  ;; belongs to neither reported category, even when the vector overreads it.
  (loop [categories categories entries (seq evaluated) remaining remaining]
    (if (or (nil? entries) (and (some? remaining) (zero? remaining)))
      categories
      (let [entry (first entries)]
        (if (:accepted? entry)
          (recur (update categories
                         (if (evidence/has? (get entry :evidence true))
                           :definite-count :conditional-count)
                         inc)
                 (next entries) (when remaining (dec remaining)))
          (recur categories (next entries) remaining))))))

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
  (let [result-policy (result-policy options)
        permission (or permission (:root plan))
        cover-plan (or (:cover-plan options)
                       (cover-plan/seal-plan adapter plan permission))
        candidate-accept? (local-node-acceptor
                           (assoc options :permission permission)
                           cover-plan)
        options (assoc options :permission permission :result-policy result-policy
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
           categories (when (= :detailed result-policy)
                        {:definite-count 0 :conditional-count 0})
           width initial-width
           counters {:commands 0 :fetched-values 0
                     :stream-opens 0 :emissions 0}]
      (execution/check! execution/*contract*
                        :operator-count/batch-boundary
                        #(hash-map :count accumulated))
      (let [raw (raw-page
                 (assoc options :cover-plan cover-plan
                        :width width
                        :order-direction :asc :boundary boundary))
            emissions (:emissions raw)
            counters (add-counters counters (:counters raw))]
        (if (empty? emissions)
          (count-response accumulated count-limit false counters categories)
          (let [evaluated (evaluate-emissions options cover-plan emissions)
                grants (reduce (fn [n entry]
                                 (if (:accepted? entry) (inc n) n))
                               0 evaluated)
                next-count (+ accumulated grants)
                categories (when categories
                             (count-categories categories evaluated
                                               (when count-limit
                                                 (- count-limit accumulated))))]
            (if (and target (>= next-count target))
              (count-response count-limit count-limit true counters categories)
              (if (:exhausted? raw)
                (count-response next-count count-limit false counters categories)
                (recur (some-> emissions peek :coords)
                       next-count
                       categories
                       (if target
                         (min batch-schedule/maximum-width
                              (max (- target next-count)
                                   (* 2 width)))
                         batch-schedule/maximum-width)
                       counters)))))))))
