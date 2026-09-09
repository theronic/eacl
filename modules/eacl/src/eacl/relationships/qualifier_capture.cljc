(ns eacl.relationships.qualifier-capture
  "Resource accounting before retaining offline proof data. Native snapshots
   and backend index buffers are owned by the caller, outside these budgets."
  (:require [eacl.relationships.qualifier :as qualifier]))

(def default-budgets {:max-capture-units 2000000 :max-qualifiers 100000})

(defn bounded-native
  "Bounds captured data cells and string code units, and distinct qualifier ids.
   Fact streams are checked before building entity maps or fact vectors."
  [native options]
  (let [{:keys [max-capture-units max-qualifiers]} (merge default-budgets options)
        _ (when-not (and (pos-int? max-capture-units) (pos-int? max-qualifiers))
            (throw (ex-info "Invalid qualifier capture budgets."
                            {:type :eacl.integrity/invalid-options})))
        units (volatile! 0)
        qids (volatile! #{})
        limit! (fn [budget]
                 (throw (ex-info "Qualifier sweep capture budget exceeded."
                                 {:type :eacl.integrity/capture-budget-exceeded :budget budget})))
        charge! (fn [value]
                  (loop [pending (list value)]
                    (when-let [items (seq pending)]
                      (let [v (first items)
                            cost (if (string? v) (inc (count v)) 1)]
                        (when (> (+ @units cost) max-capture-units) (limit! :max-capture-units))
                        (vswap! units + cost)
                        (recur (if (coll? v) (concat v (rest items)) (rest items)))))))
        track! (fn [qid]
                 (when (and (qualifier/concrete-eid? qid) (not (contains? @qids qid)))
                   (when (>= (count @qids) max-qualifiers) (limit! :max-qualifiers))
                   (vswap! qids conj qid)))
        checked-row (fn [row] (charge! [(:e row) (:a row) (:v row) (:tx row)]) row)
        facts (fn [db eid]
                (into [] (map (fn [row]
                                (checked-row row)
                                [(:a row) (:v row) (:tx row)]))
                      ((:fact-rows native) db eid)))
        entity (fn [db eid]
                 (let [rows (facts db eid)]
                   (when (seq rows)
                     (reduce (fn [result [a v]]
                               (let [attribute ((:attribute-ident native) db a)]
                                 (if (= :eacl.relation/caveats attribute)
                                   (update result attribute (fnil conj #{}) v)
                                   (assoc result attribute v))))
                             {:db/id eid} rows))))]
    (assoc native
           :entity entity :facts facts
           :all-rows (fn [db attribute]
                       (map (fn [row]
                              (checked-row row)
                              (track! (if (contains? qualifier/attributes attribute)
                                        (:e row)
                                        (when (and (vector? (:v row)) (= 5 (count (:v row))))
                                          (nth (:v row) 4))))
                              row)
                            ((:all-rows native) db attribute)))
           :rows (fn [& args] (map checked-row (apply (:rows native) args))))))
