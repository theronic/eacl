(ns eacl.engine.relationships
  (:require [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(def default-limit 1000)
(def maximum-limit 10000)

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

(defn after-cursor?
  [scan-kind cursor {:keys [subject-id resource-id]}]
  (or (nil? cursor)
      (case scan-kind
        :forward-anchored (> resource-id (:resource cursor))
        :reverse-anchored (> subject-id (:subject cursor))
        :forward-partial
        (or (> resource-id (:resource cursor))
            (and (= resource-id (:resource cursor))
                 (> subject-id (:subject cursor))))
        :reverse-partial
        (or (> subject-id (:subject cursor))
            (and (= subject-id (:subject cursor))
                 (> resource-id (:resource cursor))))
        true)))

(defn beyond-cursor?
  "Whether `row` is strictly beyond `cursor` in the requested index direction."
  [scan-kind direction cursor {:keys [subject-id resource-id]}]
  (or
   (nil? cursor)
   (let [ordered-after?
         (fn [a b]
           (case direction
             :asc (> a b)
             :desc (< a b)))]
     (case scan-kind
       :forward-anchored
       (ordered-after? resource-id (:resource-id cursor))

       :reverse-anchored
       (ordered-after? subject-id (:subject-id cursor))

       :forward-partial
       (or (ordered-after? resource-id (:resource-id cursor))
           (and (= resource-id (:resource-id cursor))
                (ordered-after? subject-id (:subject-id cursor))))

       :reverse-partial
       (or (ordered-after? subject-id (:subject-id cursor))
           (and (= subject-id (:subject-id cursor))
                (ordered-after? resource-id (:resource-id cursor))))

       false))))

(defn- relationship-edge
  [{:keys [spec-idx subject-id resource-id]}]
  {:kind :relationship-index
   :v 1
   :scan-index spec-idx
   :subject-id subject-id
   :resource-id resource-id})

(defn- valid-edge?
  [scan-specs edge]
  (and (map? edge)
       (= #{:kind :v :scan-index :subject-id :resource-id}
          (set (keys edge)))
       (= :relationship-index (:kind edge))
       (= 1 (:v edge))
       (nat-int? (:scan-index edge))
       (< (:scan-index edge) (count scan-specs))
       (nat-int? (:subject-id edge))
       (nat-int? (:resource-id edge))))

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
        (let [spec (first pending)
              resume-edge (when (= (:idx spec) start-index) edge)
              remaining (- target (count rows))
              scanned (take remaining
                            (scan-fn spec resume-edge direction))]
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
      {:key unsupported})))
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
       (when any? (relationship-edge (first selected)))
       :end-cursor
       (when any? (relationship-edge (last selected)))
       :has-next-page?
       (boolean (and any? (:has-next? page-decision)))
       :has-previous-page?
       (boolean
        (and any? (:has-previous? page-decision)))}})))

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
        (let [spec          (first pending)
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
