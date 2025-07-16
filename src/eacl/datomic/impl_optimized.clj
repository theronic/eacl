(ns eacl.datomic.impl-optimized
  "Optimized EACL implementation with performance improvements"
  (:require
    [datomic.api :as d]
    [eacl.core :as proto :refer [spice-object]]
    [eacl.datomic.impl-base :as base]
    [eacl.datomic.rules.optimized :as rules]))

(defn lookup-subjects
  "Optimized version of lookup-subjects"
  [db
   {:as               filters
    resource          :resource
    permission        :permission
    subject-type      :subject/type
    _subject-relation :subject/relation                     ; not currently supported.
    limit             :limit
    offset            :offset}]
  {:pre [(:type resource) (:id resource)]}
  (let [{resource-type :type
         resource-eid  :id} resource]
    ; Q: can we support dynamic object type resolution?
    (assert resource-eid (str "lookup-subjects (object->entid " (pr-str resource) ") must resolve to a valid Datomic entid."))
    ; todo configurable type resolution.
    ;(assert (= resource-type (:eacl/type resource-ent)) (str "Resource type does not match " resource-type "."))
    (let [subject-types+eids   (->> (d/q '[:find ?subject-type ?subject
                                           :in $ % ?subject-type ?permission ?resource-type ?resource-eid
                                           :where
                                           (has-permission ?subject-type ?subject ?permission ?resource-type ?resource-eid)
                                           [(not= ?subject ?resource-eid)]]
                                         db
                                         rules/rules-lookup-subjects
                                         subject-type
                                         permission
                                         resource-type
                                         resource-eid))
          paginated-types+eids (cond->> subject-types+eids  ; better name.
                                        offset (drop offset)
                                        limit (take limit))
          formatted            (->> paginated-types+eids
                                    (map (fn [[type id]]
                                           (spice-object type id))))]
      ;(prn 'paginated-types+eids paginated-types+eids)
      ;(prn 'formatted formatted)
      ; todo: subjects cursor is WIP. We still support offset & limit.
      {:data   formatted
       :cursor nil})))

(defn can?
  "can? uses recursive Datalog rules. Seems to be fast enough."
  [db subject permission resource]
  {:pre [subject
         resource
         (keyword? permission)
         (:id subject)
         (:type subject)

         (:id resource)
         (:type resource)]}
  (let [; todo hoist.
        {subject-type  :type
         subject-ident :id} subject

        {resource-type  :type
         resource-ident :id} resource

        ; todo assert resource-ident

        ; do we need the d/entid here?
        subject-eid  (d/entid db subject-ident)
        resource-eid (d/entid db resource-ident)]
    (if-not (and subject-eid resource-eid)                  ; duplicated in Spiceomic.
      false
      (->> (d/q '[:find ?subject .
                  :in $ % ?subject-type ?subject ?perm ?resource-type ?resource
                  :where
                  (has-permission ?subject-type ?subject ?perm ?resource-type ?resource)]
                db
                rules/check-permission-rules
                subject-type
                subject-eid
                permission
                resource-type
                resource-eid)
           (boolean)))))

(defn lookup-resources
  ; outdated.
  "Slow version of lookup-resources that reuses check-permission-rules.
  Does not support cursor, only limit & offset."
  [db {:as           _query
       subject       :subject
       permission    :permission
       resource-type :resource/type
       limit         :limit
       cursor        :cursor}]
  {:pre [(:type subject) (:id subject)]}
  (let [{subject-type  :type
         subject-ident :id} subject

        {cursor-path     :path-index
         cursor-resource :resource} cursor

        {cursor-resource-type :type
         cursor-resource-eid  :id} cursor-resource

        subject-eid (d/entid db subject-ident)]

    (assert subject-eid (pr-str "lookup-resources requires a valid subject :id that resolves to an eid via d/entid: " subject-ident "."))
    (let [resource-types+eids  (->> (d/q '[:find ?resource-type ?resource
                                           :in $ % ?subject-type ?subject-eid ?permission ?resource-type
                                           :where
                                           ;(has-permission ?subject-type ?subject-eid ?permission ?resource-type ?resource)
                                           (has-permission ?subject-type ?subject-eid ?permission ?resource-type ?resource)
                                           [(not= ?resource ?subject-eid)]] ; do we still need this?
                                         db
                                         rules/check-permission-rules ; rules-lookup-resources
                                         subject-type
                                         subject-eid
                                         permission
                                         resource-type))
          sorted-by-type+eid   (sort resource-types+eids)
          offsetted-results    (if (and cursor-resource-type cursor-resource-eid)
                                 (->> sorted-by-type+eid
                                      (drop-while (fn [[resource-type resource-eid]]
                                                    ; design decision on <= vs < is to skip the matching value until next cursor is smarter.
                                                    (<= resource-eid cursor-resource-eid))))
                                 sorted-by-type+eid)

          paginated-types+eids (cond->> offsetted-results   ; resource-types+eids
                                        ; offset (drop offset) ; cursor should take care of this.
                                        limit (take limit))
          ;sorted-by-type+eid   (sort paginated-types+eids)
          formatted            (->> paginated-types+eids    ; sorted-by-type+eid
                                    (map (fn [[type eid]] (spice-object type eid))))
          last-result          (last formatted)]
      {:cursor {:resource last-result}                      ; todo: path index.
       :limit  limit
       :data   formatted})))

(defn count-resources
  "Temporary. Just calls lookup-resources.
  Super inefficient due to the sort in lookup-resources, which is not required.
  Any complete count will need to materialize full index."
  [db query]
  (->> (assoc query :limit Long/MAX_VALUE :offset 0)        ; note: we do not currently support cursor here.
       (lookup-resources db)
       (:data)
       (count)))