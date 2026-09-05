(ns eacl.operator.seekable
  "History-free ordered direct-leaf generators. Max-head k-way
  intersection and monotone anti-join refine a certified generic cover path
  without materializing any operand."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.execution :as execution]
            [eacl.relationships.edge :as edge]
            [eacl.request.counters :as request-counters]))

(def ^:dynamic *seek-stats*
  "Optional observation-only atom for direct-specialization dimensions."
  nil)

(defn- add-stat! [counter amount]
  (when *seek-stats*
    (swap! *seek-stats* update counter (fnil + 0) amount))
  nil)

(defn- invalid! [reason message data]
  (throw
   (ex-info message
            (merge {:type :eacl.operator/invalid-seekable-plan
                    :eacl/error :eacl.operator/invalid-seekable-plan
                    :reason reason}
                   data))))

(defn- limit! [dimension maximum actual]
  (throw
   (ex-info "Direct operator specialization exceeded a configured limit."
            {:type :eacl.operator/limit-exceeded
             :eacl/error :eacl.operator/limit-exceeded
             :dimension dimension :maximum maximum :actual actual})))

(defn- relation-path
  [cover-plan specialization-node driver-node subject-type relation-id]
  (let [semantic->synthetic
        (:operator-semantic->synthetic cover-plan)
        [permission] (:operator-root-semantic cover-plan)
        target (get semantic->synthetic [permission driver-node])
        rules-by-node (group-by :node (:rules cover-plan))]
    (letfn [(walk [node visited]
              (cond
                (= node target) []
                (contains? visited node) nil
                :else
                (some
                 (fn [rule]
                   (when-let [suffix
                              (and (= :self-permission (:rule rule))
                                   (walk (:target-node rule)
                                         (conj visited node)))]
                     (into [(:ordinal rule)] suffix)))
                 (sort-by :ordinal (rules-by-node node)))))]
      (let [prefix (walk (:root cover-plan) #{})
            relation-rule
            (some #(when (and (= :relation (:rule %))
                              (= relation-id (:relation-eid %))
                              (= subject-type (:subject-type %))) %)
                  (rules-by-node target))]
        (when-not (and prefix relation-rule)
          (invalid! :missing-generic-path
                    "Direct specialization cannot reproduce its generic cover coordinates."
                    {:specialization-node specialization-node
                     :driver-node driver-node
                     :subject-type subject-type
                     :relation-id relation-id}))
        (conj (vec prefix) (:ordinal relation-rule))))))

(defn- scan-values
  [{:keys [scan-invoker traversal subject-type anchor-eid order-direction
           resource-type cut-point! physical-chunk-size max-commands
           max-values counters qualification]}
   relation-id bound inclusive?]
  (let [current @counters]
    ;; The routed cut point is the same deadline/cancellation check as the
    ;; direct one; run whichever the caller installed, never both.
    (if cut-point!
      (cut-point! current)
      (execution/check! execution/*contract* :operator-seekable/scan
                        #(hash-map :commands (:commands current))))
    (let [next-command (inc (:commands current))]
      (when (> next-command max-commands)
        (limit! :commands max-commands next-command))
      (let [scan-options
            ;; `:limit` lets natively paging adapters stop at the chunk.
            (cond-> {:direction order-direction :limit physical-chunk-size}
              qualification (assoc :include-qualifier? true)
              (some? bound)
              (assoc :bound-eid bound :inclusive-bound? inclusive?))
            values
            (into [] (take physical-chunk-size)
                  (if (= :forward traversal)
                    (scan-invoker subject-type anchor-eid relation-id
                                  resource-type scan-options)
                    (scan-invoker resource-type anchor-eid relation-id
                                  subject-type scan-options)))
            fetched (count values)
            next-values (+ (:fetched-values current) fetched)]
        (when (> next-values max-values)
          (limit! :fetched-values max-values next-values))
        (vswap! counters #(cond-> (assoc % :commands next-command
                                         :fetched-values next-values)
                            (nil? bound) (update :stream-opens inc)))
        (request-counters/add-commands!)
        (request-counters/add-fetched-values! fetched)
        (add-stat! :adapter-commands 1)
        (add-stat! :fetched-values fetched)
        values))))

(defn- cursor
  [relation-id boundary]
  {:relation-id relation-id :buffer [] :index 0 :bound boundary :done? false})

(defn- refill [options cursor bound inclusive?]
  (let [values (scan-values options (:relation-id cursor) bound inclusive?)
        chunk (:physical-chunk-size options)]
    (assoc cursor :buffer values :index 0
           :bound (edge/endpoint (peek values))
           :done? (< (count values) chunk))))

(defn- head-evidence [cursor]
  (let [compact (nth (:buffer cursor) (:index cursor))]
    (if (vector? compact) (:evidence cursor) true)))

(defn- head [options cursor]
  (loop [cursor cursor]
    (if (< (:index cursor) (count (:buffer cursor)))
      (let [compact (nth (:buffer cursor) (:index cursor))]
        (if-not (and (:qualification options) (vector? compact))
          [(edge/endpoint compact) cursor]
          (let [cached? (= compact (:qualified-edge cursor))
                value (if cached? (:evidence cursor)
                          (qualification/qualify (:qualification options) (:relation-id cursor) compact))
                cursor (if cached? cursor (assoc cursor :qualified-edge compact :evidence value))]
            (when (evidence/fault? value)
              (throw (ex-info "Qualified page evaluation failed."
                              {:type :eacl.authorization/evaluation-failure
                               :eacl/error :eacl.authorization/evaluation-failure
                               :faults (second (evidence/value value))})))
            (when (and (not cached?) (not (boolean? value)))
              (vswap! (:examined-certificate options)
                      #(evidence/combine :intersection %
                                         (evidence/with-certificate true (evidence/valid-until value)
                                           (evidence/complete? value)))))
            (if (evidence/no? value)
              (recur (update cursor :index inc))
              [(edge/endpoint compact) cursor]))))
      (if (:done? cursor)
        [nil cursor]
        (recur (refill options cursor (:bound cursor) false))))))

(defn- advance [options cursor]
  (head options (update cursor :index inc)))

(defn- at-or-beyond?
  [order-direction value target]
  (if (= :asc order-direction)
    (>= value target)
    (<= value target)))

(defn- seek
  [options cursor target]
  (let [[value cursor] (head options cursor)
        order-direction (:order-direction options)]
    (cond
      (nil? value) [nil cursor]
      (at-or-beyond? order-direction value target)
      [value cursor]
      :else
      (loop [index (inc (:index cursor))]
        (if (< index (count (:buffer cursor)))
          (let [value (edge/endpoint (nth (:buffer cursor) index))]
            (if (at-or-beyond? order-direction value target)
              (head options (assoc cursor :index index))
              (recur (inc index))))
          (let [cursor (refill options cursor target true)]
            (add-stat! :inclusive-reseeks 1)
            (head options cursor)))))))

(defn- furthest
  [order-direction values]
  (reduce (if (= :asc order-direction) max min) values))

(defn- intersection-emissions
  [options relation-ids width coords-prefix]
  (let [driver (cursor (first relation-ids) (:boundary-eid options))
        operands (mapv #(cursor % (:boundary-eid options))
                       (subvec relation-ids 1))]
    (loop [driver driver operands operands emissions []]
      (if (= width (count emissions))
        {:emissions emissions :exhausted? false}
        (let [[driver-head driver] (head options driver)]
          (if (nil? driver-head)
            {:emissions emissions :exhausted? true}
            (do
              (add-stat! :anchor-rounds 1)
              (let [[heads operands exhausted?]
                    (loop [remaining operands heads [] positioned []]
                      (if-let [operand (first remaining)]
                        (let [[value operand] (seek options operand driver-head)]
                          (if (nil? value)
                            [heads (into (conj positioned operand)
                                         (subvec remaining 1))
                             true]
                            (recur (subvec remaining 1)
                                   (conj heads value)
                                   (conj positioned operand))))
                        [heads positioned false]))]
                (if exhausted?
                  {:emissions emissions :exhausted? true}
                  (if (every? #(= driver-head %) heads)
                    (let [value (if (:qualification options)
                                  (reduce #(evidence/combine :intersection %1 (head-evidence %2))
                                          (head-evidence driver) operands)
                                  true)
                          emissions
                          (cond-> emissions
                            (not (evidence/no? value))
                            (conj (cond-> {:value driver-head
                                           :coords (conj coords-prefix driver-head)}
                                    (:qualification options) (assoc :evidence value))))]
                      (if (= width (count emissions))
                        {:emissions emissions :exhausted? false}
                        (let [[_ driver] (advance options driver)
                              operands
                              (mapv #(second (advance options %)) operands)]
                          (recur driver operands emissions))))
                    (let [target (furthest (:order-direction options) heads)
                          [next-driver driver] (seek options driver target)]
                      (add-stat! :driver-reseeks 1)
                      (if (nil? next-driver)
                        {:emissions emissions :exhausted? true}
                        (recur driver operands emissions)))))))))))))

(defn- exclusion-emissions
  [options relation-ids width coords-prefix]
  (loop [left (cursor (first relation-ids) (:boundary-eid options))
         right (cursor (second relation-ids) (:boundary-eid options))
         emissions []]
    (if (= width (count emissions))
      {:emissions emissions :exhausted? false}
      (let [[left-head left] (head options left)]
        (if (nil? left-head)
          {:emissions emissions :exhausted? true}
          (let [[right-head right] (seek options right left-head)
                match? (= left-head right-head)
                value (if (:qualification options)
                        (evidence/combine :exclusion (head-evidence left)
                                          (if match? (head-evidence right) false))
                        (not match?))
                emissions (cond-> emissions
                            (not (evidence/no? value))
                            (conj (cond-> {:value left-head :coords (conj coords-prefix left-head)}
                                    (:qualification options) (assoc :evidence value))))]
            (add-stat! :anti-join-rounds 1)
            (if (= width (count emissions))
              {:emissions emissions :exhausted? false}
              (let [[_ left] (advance options left)
                    right (if match? (second (advance options right)) right)]
                (recur left right emissions)))))))))

(defn page
  "Returns a raw exact generator page for one certified direct
   specialization, with the generic cover coordinates and qualified evidence.
   :examined-certificate covers evaluated heads, not the continuation frontier."
  [{:keys [adapter plan cover-plan specialization-node traversal
           subject-type width boundary traversal-limits cut-point! qualification]
    :as request}]
  (when-not (and (integer? width) (not (neg? width)))
    (invalid! :invalid-width "Seekable page width must be a natural integer." {:width width}))
  (if (zero? width)
    {:emissions [] :has-more? nil :exhausted? false
     :counters {:commands 0 :fetched-values 0 :stream-opens 0 :emissions 0}}
    (let [permission (first (:operator-root-semantic cover-plan))
          specialization (get-in plan [:specializations permission specialization-node])
          {:keys [kind typed-partitions driver operands]} specialization
          by-node (into {} (map (juxt :node :relation-eid)) (get typed-partitions subject-type))
          relation-ids (mapv by-node (into [driver] operands))
          _ (when-not (and (contains? #{:direct-k-way-intersection :direct-monotone-exclusion} kind)
                           (= (count relation-ids) (count (distinct relation-ids)))
                           (every? some? relation-ids))
              (invalid! :ineligible "Direct specialization is not eligible."
                        {:permission permission :node specialization-node :subject-type subject-type :kind kind}))
          coords-prefix (relation-path cover-plan specialization-node driver subject-type (first relation-ids))
          boundary-eid (when boundary
                         (when-not (and (= coords-prefix (pop (vec boundary))) (integer? (peek boundary)))
                           (invalid! :invalid-boundary "Seekable boundary is outside the generic cover path."
                                     {:boundary boundary :expected-prefix coords-prefix}))
                         (peek boundary))
          limits (or traversal-limits {})
          counters (volatile! {:commands 0 :fetched-values 0 :stream-opens 0 :emissions 0})
          options (cond-> (assoc request
                                 :resource-type (first permission)
                                 :scan-invoker (backend/scan-invoker adapter (if (= :forward traversal)
                                                                              :subject->resources :resource->subjects))
                                 :boundary-eid boundary-eid
                                 :physical-chunk-size (or (:physical-chunk-size limits) reducer/default-physical-chunk-size)
                                 :max-commands (or (:max-commands limits) reducer/default-max-commands)
                                 :max-values (or (:max-values limits) reducer/default-max-values)
                                 :cut-point! cut-point!
                                 :counters counters)
                    qualification (assoc :examined-certificate (volatile! true)))
          result (case kind
                   :direct-k-way-intersection (intersection-emissions options relation-ids width coords-prefix)
                   :direct-monotone-exclusion (exclusion-emissions options relation-ids width coords-prefix))
          emissions (:emissions result)]
      (vswap! counters assoc :emissions (count emissions))
      (add-stat! :emissions (count emissions))
      (cond-> (assoc result :has-more? nil :counters @counters)
        qualification (assoc :examined-certificate @(:examined-certificate options))))))
