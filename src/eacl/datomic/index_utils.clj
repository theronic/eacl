(ns eacl.datomic.index-utils
  "Utilities for efficient index-range based queries in EACL"
  (:require [datomic.api :as d]
            [clojure.tools.logging :as log]))

(defn index-range-seq
  "Returns a lazy sequence over index-range with automatic pagination.
   Useful for large result sets to avoid memory issues."
  [db attr start end & {:keys [chunk-size] :or {chunk-size 1000}}]
  (letfn [(fetch-chunk [start-val]
            (lazy-seq
              (let [datoms (vec (take chunk-size 
                                     (d/index-range db attr start-val end)))]
                (when (seq datoms)
                  (concat datoms
                          (when (= (count datoms) chunk-size)
                            (let [last-datom (last datoms)
                                  next-start (update (:v last-datom) 
                                                    (dec (count (:v last-datom))) 
                                                    inc)]
                              (fetch-chunk next-start))))))))]
    (fetch-chunk start)))

(defn tuple-index-lookup
  "Efficiently lookup entities using tuple indices.
   Returns all datoms matching the tuple value."
  [db tuple-attr tuple-value]
  (d/index-range db tuple-attr tuple-value tuple-value))

(defn tuple-prefix-scan
  "Scan a tuple index with a prefix match.
   For example, scanning [:eacl.relationship/resource+relation-name+subject [resource-eid :owner]]
   will find all subjects with :owner relation to the resource."
  [db tuple-attr prefix-components]
  (let [start-val (vec prefix-components)
        end-val (conj (vec (butlast prefix-components)) 
                      (inc (last prefix-components)))]
    (d/index-range db tuple-attr start-val end-val)))

(defn merge-paginated-results
  "Merge multiple result sequences with deduplication and stable ordering.
   Results are deduplicated by :db/id and sorted for pagination stability."
  [& result-seqs]
  (let [seen (atom #{})
        deduped (filter (fn [e]
                         (let [eid (:db/id e)]
                           (when-not (@seen eid)
                             (swap! seen conj eid)
                             true)))
                       (apply concat result-seqs))]
    ;; Sort by entity ID for stable ordering
    (sort-by :db/id deduped)))

(defn paginate-results
  "Apply offset and limit to a sequence of results."
  [results offset limit]
  (->> results
       (drop offset)
       (take limit)
       vec))

(defn entity-ids->entities
  "Convert a sequence of entity IDs to entity maps."
  [db entity-ids]
  (map #(d/entity db %) entity-ids))

(defn extract-tuple-component
  "Extract a specific component from tuple datoms.
   component-idx is 0-based."
  [datoms component-idx]
  (map #(nth (:v %) component-idx) datoms))

(defn find-permission-relations
  "Find all relations that grant a specific permission for a resource type."
  [db resource-type permission]
  ;; Try index-range first
  (let [perm-datoms (d/index-range db
                      :eacl.permission/resource-type+permission-name
                      [resource-type permission]
                      [resource-type permission])
        perm-seq (seq perm-datoms)]
    
    (if perm-seq
      ;; Index-range worked
      (let [perm-entities (map #(d/entity db (:e %)) perm-seq)]
        (distinct (map :eacl.permission/relation-name perm-entities)))
      
      ;; Fallback to regular query (for in-memory databases)
      (let [results (d/q '[:find [?rel-name ...]
                          :in $ ?rt ?p
                          :where
                          [?e :eacl.permission/resource-type ?rt]
                          [?e :eacl.permission/permission-name ?p]
                          [?e :eacl.permission/relation-name ?rel-name]]
                        db resource-type permission)]
        (vec results)))))

(defn find-arrow-permissions
  "Find all arrow permission definitions for a resource type and permission."
  [db resource-type permission]
  (let [arrow-datoms (d/index-range db
                       :eacl.arrow-permission/resource-type+permission-name
                       [resource-type permission]
                       [resource-type permission])
        arrow-seq (seq arrow-datoms)]
    
    (if arrow-seq
      ;; Index-range worked
      (map #(d/entity db (:e %)) arrow-seq)
      
      ;; Fallback to regular query
      (let [arrow-eids (d/q '[:find [?e ...]
                             :in $ ?rt ?p
                             :where
                             [?e :eacl.arrow-permission/resource-type ?rt]
                             [?e :eacl.arrow-permission/permission-name ?p]]
                           db resource-type permission)]
        (map #(d/entity db %) arrow-eids))))) 