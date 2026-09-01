(ns eacl.operator.seekable
  "History-free ordered direct-leaf generators. Max-head k-way
  intersection and monotone anti-join refine a certified generic cover path
  without materializing any operand."
  (:require [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.execution :as execution]
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
           max-values counters]}
   relation-id bound inclusive?]
  (execution/check! execution/*contract*
                    :operator-seekable/scan
                    {:commands (:commands @counters)})
  (when cut-point! (cut-point! @counters))
  (let [next-command (inc (:commands @counters))]
    (when (> next-command max-commands)
      (limit! :commands max-commands next-command))
    (let [scan-options
          (cond-> {:direction order-direction}
            (some? bound)
            (assoc :bound-eid bound :inclusive-bound? inclusive?))
          values
          (into [] (take physical-chunk-size)
                (if (= :forward traversal)
                  (scan-invoker subject-type anchor-eid relation-id
                                resource-type scan-options)
                  (scan-invoker resource-type anchor-eid relation-id
                                subject-type scan-options)))
          next-values (+ (:fetched-values @counters) (count values))]
      (when (> next-values max-values)
        (limit! :fetched-values max-values next-values))
      (vswap! counters assoc :commands next-command
              :fetched-values next-values)
      (when (nil? bound)
        (vswap! counters update :stream-opens inc))
      (request-counters/add-commands!)
      (request-counters/add-fetched-values! (count values))
      (add-stat! :adapter-commands 1)
      (add-stat! :fetched-values (count values))
      values)))

(defn- cursor
  [relation-id boundary]
  {:relation-id relation-id :buffer [] :index 0 :bound boundary
   :inclusive? false :done? false :opened? false})

(defn- refill [options cursor bound inclusive?]
  (let [values (scan-values options (:relation-id cursor) bound inclusive?)
        chunk (:physical-chunk-size options)]
    (assoc cursor :buffer values :index 0
           :bound (peek values)
           :done? (< (count values) chunk)
           :opened? true)))

(defn- head [options cursor]
  (cond
    (< (:index cursor) (count (:buffer cursor)))
    [(nth (:buffer cursor) (:index cursor)) cursor]

    (:done? cursor) [nil cursor]

    :else
    (let [cursor (refill options cursor (:bound cursor) false)]
      (if (seq (:buffer cursor))
        [(first (:buffer cursor)) cursor]
        [nil cursor]))))

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
          (let [value (nth (:buffer cursor) index)]
            (if (at-or-beyond? order-direction value target)
              [value (assoc cursor :index index)]
              (recur (inc index))))
          (let [cursor (refill options cursor target true)]
            (add-stat! :inclusive-reseeks 1)
            (if (seq (:buffer cursor))
              [(first (:buffer cursor)) cursor]
              [nil cursor])))))))

(defn- furthest
  [order-direction values]
  (apply (if (= :asc order-direction) max min) values))

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
                    (let [emissions
                          (conj emissions
                                {:value driver-head
                                 :coords (conj coords-prefix driver-head)})]
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
          (let [[right-head right] (seek options right left-head)]
            (add-stat! :anti-join-rounds 1)
            (if (= left-head right-head)
              (let [[_ left] (advance options left)
                    [_ right] (advance options right)]
                (recur left right emissions))
              (let [emissions
                    (conj emissions
                          {:value left-head
                           :coords (conj coords-prefix left-head)})]
                (if (= width (count emissions))
                  {:emissions emissions :exhausted? false}
                  (let [[_ left] (advance options left)]
                    (recur left right emissions)))))))))))

(defn page
  "Returns a raw exact generator page for one certified direct
  specialization. Its coordinates are exactly the generic cover path."
  [{:keys [adapter plan cover-plan specialization-node traversal
           subject-type width boundary traversal-limits cut-point!]
    :as request}]
  (when-not (and (integer? width) (not (neg? width)))
    (invalid! :invalid-width "Seekable page width must be a natural integer."
              {:width width}))
  (if (zero? width)
    {:emissions [] :has-more? nil :exhausted? false
     :counters {:commands 0 :fetched-values 0 :stream-opens 0 :emissions 0}}
    (let [permission (first (:operator-root-semantic cover-plan))
          specialization
          (get-in plan [:specializations permission specialization-node])
          {:keys [kind typed-partitions driver operands]} specialization
          partitions (get typed-partitions subject-type)
          by-node (into {} (map (juxt :node :relation-eid)) partitions)
          relation-ids (mapv by-node (into [driver] operands))]
      (when-not (and (contains? #{:direct-k-way-intersection
                                  :direct-monotone-exclusion} kind)
                     (= (count relation-ids)
                        (count (distinct relation-ids)))
                     (every? some? relation-ids))
        (invalid! :ineligible "Direct specialization is not eligible."
                  {:permission permission :node specialization-node
                   :subject-type subject-type :kind kind}))
      (let [coords-prefix
          (relation-path cover-plan specialization-node driver subject-type
                         (first relation-ids))
          boundary-eid
          (when boundary
            (when-not (and (= coords-prefix (pop (vec boundary)))
                           (integer? (peek boundary)))
              (invalid! :invalid-boundary
                        "Seekable boundary is outside the generic cover path."
                        {:boundary boundary
                         :expected-prefix coords-prefix}))
            (peek boundary))
          limits (or traversal-limits {})
          counters (volatile!
                    {:commands 0 :fetched-values 0 :stream-opens 0
                     :emissions 0})
          options (assoc request
                         :resource-type (first permission)
                         :scan-invoker
                         (backend/scan-invoker
                          adapter
                          (if (= :forward traversal)
                            :subject->resources
                            :resource->subjects))
                         :boundary-eid boundary-eid
                         :physical-chunk-size
                         (or (:physical-chunk-size limits)
                             reducer/default-physical-chunk-size)
                         :max-commands (or (:max-commands limits)
                                           reducer/default-max-commands)
                         :max-values (or (:max-values limits)
                                         reducer/default-max-values)
                         :cut-point! cut-point!
                         :counters counters)
          result
          (case kind
            :direct-k-way-intersection
            (intersection-emissions options relation-ids width coords-prefix)
            :direct-monotone-exclusion
            (exclusion-emissions options relation-ids width coords-prefix))
          emissions (:emissions result)]
      (vswap! counters assoc :emissions (count emissions))
      (add-stat! :emissions (count emissions))
        (assoc result
               :has-more? nil
               :counters @counters)))))
