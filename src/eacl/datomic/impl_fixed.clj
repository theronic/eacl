(ns eacl.datomic.impl-fixed
  "Todo: should contain a fixed index-based implementation of lookup-resources."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl-base :as base]))

(defrecord Cursor [path-index resource-id])

(defn Relation
  "Defines a relation type. Copied from core2."
  ([resource-type relation-name subject-type]
   {:pre [(keyword? resource-type) (keyword? relation-name) (keyword? subject-type)]}
   {:eacl.relation/resource-type resource-type
    :eacl.relation/relation-name relation-name
    :eacl.relation/subject-type  subject-type})
  ([resource-type+relation-name subject-type]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? subject-type)]}
   (Relation
     (keyword (namespace resource-type+relation-name))
     (keyword (name resource-type+relation-name))
     subject-type)))

(defn Permission
  "Defines how a permission is granted.
  Arity 1 (direct relation from namespaced keyword): (Permission :document/owner :view)
    => For :document, relation :owner grants :view permission.
  Arity 2 (direct relation): (Permission :document :owner :view)
    => For :document, relation :owner grants :view permission.
  Arity 4 (arrow relation): (Permission :vpc :admin :account :admin :account)
    => For :vpc, :admin permission is granted if subject has :admin on the resource of type :account linked by vpc's :account relation."
  ;; Arity 1: Direct grant, from namespaced keyword resource-type/relation-name
  ([resource-type+relation-name permission-to-grant]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? permission-to-grant)]}
   (let [resource-type (keyword (namespace resource-type+relation-name))
         relation-name (keyword (name resource-type+relation-name))]
     ;; Call arity 2
     (Permission resource-type relation-name permission-to-grant)))
  ;; Arity 2: Direct grant
  ([resource-type direct-relation-name permission-to-grant]
   {:pre [(keyword? resource-type) (keyword? direct-relation-name) (keyword? permission-to-grant)]}
   {:eacl.permission/resource-type   resource-type
    :eacl.permission/permission-name permission-to-grant
    :eacl.permission/relation-name   direct-relation-name})
  ;; Arity 4: Arrow grant
  ([resource-type arrow-source-relation arrow-target-permission grant-permission]
   {:pre [(keyword? resource-type) (keyword? grant-permission)
          (keyword? arrow-source-relation) (keyword? arrow-target-permission)]}
   {:eacl.arrow-permission/resource-type          resource-type
    :eacl.arrow-permission/permission-name        grant-permission
    :eacl.arrow-permission/source-relation-name   arrow-source-relation
    :eacl.arrow-permission/target-permission-name arrow-target-permission}))

(defn Relationship
  "A Relationship between a subject and a resource via Relation. Copied from core2."
  [subject relation-name resource]
  ; :pre can be expensive.
  {:pre [(:id subject)
         (:type subject)
         (keyword? relation-name)
         (:id resource)
         (:type resource)]}
  {:eacl.relationship/resource-type (:type resource)
   :eacl.relationship/resource      (:id resource)
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/subject-type  (:type subject)
   :eacl.relationship/subject       (:id subject)})

(defn get-permission-paths
  [db resource-type permission]
  (let [direct-relations (d/q '[:find [?relation-name ...]
                                :in $ ?resource-type ?permission
                                :where
                                [(tuple ?resource-type ?permission) ?rtype+perm]
                                [?perm :eacl.permission/resource-type+permission-name ?rtype+perm]
                                [?perm :eacl.permission/relation-name ?relation-name]]
                              db resource-type permission)
        arrows           (d/q '[:find ?source-relation ?target-name
                                :in $ ?resource-type ?permission
                                :where
                                [(tuple ?resource-type ?permission) ?rtype+perm]
                                [?arrow :eacl.arrow-permission/resource-type+permission-name ?rtype+perm]
                                [?arrow :eacl.arrow-permission/source-relation-name ?source-relation]
                                [?arrow :eacl.arrow-permission/target-permission-name ?target-name]]
                              db resource-type permission)]
    (concat
      (map (fn [relation] [[{:relation :self :source-resource-type resource-type :target-resource-type resource-type}] relation]) direct-relations)
      (for [[source-relation target-name] arrows
            :let [target-resource-type (d/q '[:find ?subject-type .
                                              :in $ ?resource-type ?source-relation
                                              :where
                                              [?relation :eacl.relation/resource-type ?resource-type]
                                              [?relation :eacl.relation/relation-name ?source-relation]
                                              [?relation :eacl.relation/subject-type ?subject-type]]
                                            db resource-type source-relation)
                  _ (log/debug "Processing arrow:" source-relation "->" target-name "on" target-resource-type)
                  is-permission? (boolean (d/q '[:find ?p .
                                                 :in $ ?trt ?tn
                                                 :where
                                                 [?p :eacl.permission/resource-type ?trt]
                                                 [?p :eacl.permission/permission-name ?tn]]
                                               db target-resource-type target-name))
                  is-relation? (boolean (d/q '[:find ?r .
                                               :in $ ?trt ?tn
                                               :where
                                               [?r :eacl.relation/resource-type ?trt]
                                               [?r :eacl.relation/relation-name ?tn]]
                                             db target-resource-type target-name))
                  _ (log/debug "is-permission?" is-permission? "is-relation?" is-relation?)
                  _ (when (and (not is-permission?) (not is-relation?)) (throw (ex-info "Target is neither permission nor relation" {:target target-name :type target-resource-type})))
                  sub-paths (if is-permission?
                              (get-permission-paths db target-resource-type target-name)
                              [[[] target-name]])] ;; Treat as terminal relation
            [sub-steps sub-direct] sub-paths]
        [(cons {:relation             source-relation
                :source-resource-type resource-type
                :target-resource-type target-resource-type}
               sub-steps)
         sub-direct]))))

(defn lazy-direct-relation-resources
  "Lazily fetches resource EIDs where subject has direct relation."
  [db subject-type subject-eid relation resource-type cursor-eid]
  (let [tuple-val [subject-type subject-eid relation resource-type (or cursor-eid nil)]
        datoms    (d/index-range db :eacl.relationship/subject-type+subject+relation-name+resource-type+resource tuple-val nil)]
    (->> datoms
         (take-while (fn [{:keys [v]}]
                       (let [[s-type s-eid rel r-type _] v]
                         (and (= s-type subject-type)
                              (= s-eid subject-eid)
                              (= rel relation)
                              (= r-type resource-type)))))
         (map (fn [{:keys [v]}]
                (let [[_ _ _ r-type r-eid] v]
                  [r-type r-eid])))
         (drop-while (fn [[_ r-eid]] (and cursor-eid (<= r-eid cursor-eid))))
         (drop (if cursor-eid 1 0)))))

(defn lazy-arrow-permission-resources
  "Lazily fetches resource EIDs via arrow permissions, handling :self."
  [db subject-type subject-eid steps direct-relation resource-type cursor-eid]
  (if (and (= (count steps) 1) (= (:relation (first steps)) :self))
    (lazy-direct-relation-resources db subject-type subject-eid direct-relation resource-type cursor-eid)
    (let [reverse-steps (reverse steps)
          initial-step  (first reverse-steps)
          initial-type  (:target-resource-type initial-step)
          initial-eids  (lazy-direct-relation-resources db subject-type subject-eid direct-relation initial-type cursor-eid)]
      (reduce (fn [current-eids step]
                (mapcat (fn [[cur-type cur-eid]]
                          (let [rel         (:relation step)
                                src-type    (:source-resource-type step)
                                start-tuple [cur-type cur-eid rel src-type nil]
                                datoms      (d/index-range db :eacl.relationship/subject-type+subject+relation-name+resource-type+resource start-tuple nil)]
                            (->> datoms
                                 (take-while (fn [{v :v}] (let [[s-t s-e r s-rt _] v] (and (= s-t cur-type) (= s-e cur-eid) (= r rel) (= s-rt src-type)))))
                                 (map (fn [{v :v}] (let [[_ _ _ rt re] v] [rt re])))
                                 (drop-while (fn [[_ re]] (and cursor-eid (<= re cursor-eid))))
                                 (drop (if cursor-eid 1 0)))))
                        current-eids))
              initial-eids
              (rest reverse-steps)))))

(defn lookup-resources
  [db {:keys [subject permission resource/type cursor limit] :or {limit 1000}}]
  (let [{subject-type :type subject-eid :id} subject
        paths      (get-permission-paths db type permission)
        cursor-eid (:resource-id cursor)
        lazy-seqs  (map-indexed (fn [idx [steps direct]]
                                  (let [lazy-seq (if (empty? steps)
                                                   (lazy-direct-relation-resources db subject-type subject-eid direct type cursor-eid)
                                                   (lazy-arrow-permission-resources db subject-type subject-eid steps direct type cursor-eid))]
                                    [idx lazy-seq]))
                                paths)
        heap       (java.util.PriorityQueue. (comparator (fn [[e1] [e2]] (< e1 e2)))) ; compare by eid
        _          (doseq [[idx l-seq] lazy-seqs]
                     (when-let [[rtype eid :as first-item] (first l-seq)]
                       (.add heap [eid rtype idx (rest l-seq)])))
        seen       (volatile! #{})
        results    (loop [res []]
                     (if (>= (count res) limit)
                       res
                       (if-let [[eid rtype idx remainder] (.poll heap)]
                         (if (@seen eid)
                           (recur res)
                           (do (vswap! seen conj eid)
                               (when-let [next-item (first remainder)]
                                 (.add heap [(:eid next-item) rtype idx (rest remainder)]))
                               (recur (conj res [rtype eid]))))
                         res)))
        new-cursor (when-let [last-eid (second (last results))]
                     (base/->Cursor (count paths) last-eid)) ; simple cursor with last eid
        data       (map (fn [[rtype eid]] (spice-object rtype eid)) results)]
    {:cursor new-cursor :data data}))

(defn count-resources
  [db {:keys [subject permission resource/type cursor]}]
  (let [{subject-type :type subject-eid :id} subject
        paths        (get-permission-paths db type permission)
        cursor-eid   (:resource-id cursor)
        all-eids     (set (mapcat (fn [[steps direct]]
                                    (map second
                                         (if (empty? steps)
                                           (lazy-direct-relation-resources db subject-type subject-eid direct type cursor-eid)
                                           (lazy-arrow-permission-resources db subject-type subject-eid steps direct type cursor-eid))))
                                  paths))
        after-cursor (if cursor-eid
                       (disj all-eids cursor-eid)           ; approximate, assumes no dups before cursor
                       all-eids)]
    (count after-cursor)))

;; Rest of the file will be added in later steps.