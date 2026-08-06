(ns eacl.engine.relationships)

(def default-limit 1000)

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
