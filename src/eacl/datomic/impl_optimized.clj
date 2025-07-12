(ns eacl.datomic.impl-optimized
  "Optimized EACL implementation with performance improvements"
  (:require
    [datomic.api :as d]
    [eacl.core :as proto :refer [spice-object]]
    [eacl.datomic.impl-base :as base]
    [eacl.datomic.rules.optimized :as rules]))

;; Configuration (not used everywhere soz)
(def object-id-attr :eacl/id)

(defn lookup-subjects
  "Optimized version of lookup-subjects"
  [db
   {:as              filters
    resource         :resource
    permission       :permission
    subject-type     :subject/type
    subject-relation :subject/relation
    limit            :limit
    offset           :offset}]
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

;; Helper functions for staged lookup-resources
;(defn find-direct-resources
;  "Find resources directly accessible by subject"
;  [db subject-eid resource-type permission]
;  (d/q '[:find [?resource ...]
;         :in $ ?subject ?rtype ?perm
;         :where
;         ;; Start from subject's relationships
;         [?rel :eacl.relationship/subject ?subject]
;         [?rel :eacl.relationship/relation-name ?relation]
;         [?rel :eacl.relationship/resource ?resource]
;
;         ;; Early type filter
;         [?resource :eacl/type ?rtype]
;
;         ;; Check permission using tuple
;         [(tuple ?rtype ?relation ?perm) ?perm-tuple]
;         [?p :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]]
;       db subject-eid resource-type permission))

;(defn find-arrow-resources
;  "Find resources via arrow permissions (e.g., account->admin)"
;  [db subject-eid resource-type permission]
;  ;; First, find what intermediate permissions we need
;  (let [arrow-paths (d/q '[:find ?via-rel ?target-perm
;                           :in $ ?rtype ?perm
;                           :where
;                           [?arrow :eacl.arrow-permission/resource-type ?rtype]
;                           [?arrow :eacl.arrow-permission/permission-name ?perm]
;                           [?arrow :eacl.arrow-permission/source-relation-name ?via-rel]
;                           [?arrow :eacl.arrow-permission/target-permission-name ?target-perm]]
;                         db resource-type permission)]
;    ;; For each arrow path, find accessible resources
;    (apply concat
;           (for [[via-rel target-perm] arrow-paths]
;             ;; Use rules to check if subject has target permission on intermediates
;             (d/q '[:find [?resource ...]
;                    :in $ % ?subject ?rtype ?via-rel ?target-perm
;                    :where
;                    ;; Find resources of target type that have the via-relation
;                    [?link :eacl.relationship/relation-name ?via-rel]
;                    [?link :eacl.relationship/resource ?resource]
;                    [?link :eacl.relationship/subject ?intermediate]
;
;                    ;; Filter by resource type
;                    [?resource :eacl/type ?rtype]
;
;                    ;; Check if subject has target permission on intermediate
;                    (has-permission ?subject ?target-perm ?intermediate)]
;                  db
;                  rules/check-permission-rules
;                  subject-eid
;                  resource-type
;                  via-rel
;                  target-perm)))))

;(defn find-indirect-resources
;  "Find resources through indirect paths - fallback for complex cases"
;  [db subject-eid resource-type permission]
;  ;; Use the full rules as a fallback
;  (d/q '[:find [?resource ...]
;         :in $ % ?subject ?permission ?resource-type
;         :where
;         (has-permission ?subject ?permission ?resource-type ?resource)]
;       db
;       rules/rules-lookup-resources
;       subject-eid
;       permission
;       resource-type))

;(defn lookup-resources-staged
;  "Staged approach to resource lookup for better performance"
;  [db {:keys [resource/type subject permission limit offset]
;       :or   {limit 1000 offset 0}}]
;  {:pre [(keyword? type)
;         (:type subject) (:id subject)
;         (keyword? permission)]}
;  (let [{subject-id :id} subject
;        subject-eid     (:db/id (d/entity db [object-id-attr subject-id]))
;
;        ;; We need enough results to satisfy offset + limit
;        target-count    (+ offset limit)
;
;        ;; Stage 1: Find direct relationships
;        stage1-direct   (if subject-eid
;                          (find-direct-resources db subject-eid type permission)
;                          [])
;
;        ;; Stage 2: Find via arrow permissions (if we need more results)
;        stage2-arrow    (when (and subject-eid
;                                   (< (count stage1-direct) target-count))
;                          (find-arrow-resources db subject-eid type permission))
;
;        ;; Stage 3: Multi-hop paths (only if we still need more results)
;        stage3-indirect (when (and subject-eid
;                                   (< (+ (count stage1-direct)
;                                         (count (or stage2-arrow [])))
;                                      target-count))
;                          (find-indirect-resources db subject-eid type permission))
;
;        ;; Combine and deduplicate
;        all-results     (distinct (concat stage1-direct
;                                          (or stage2-arrow [])
;                                          (or stage3-indirect [])))]
;
;    (->> all-results
;         (drop offset)
;         (take limit)
;         (map #(d/entity db %))
;         (map entity->spice-object))))

(defn can?
  ; TODO: this needs another layer of ID resolution.
  "can? using optimized Datalog rules"
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

;(def can! (fn [db subject-id permission resource-id]
;            (if (can? db subject-id permission resource-id)
;              true
;              (throw (Exception. "Unauthorized")))))

(defn lookup-resources
  ; outdated.
  "Optimized version of lookup-resources"
  [db {:as               filters
       subject           :subject
       permission        :permission
       resource-type     :resource/type
       resource-relation :resource/relation
       limit             :limit
       offset            :offset}]
  {:pre [(:type subject) (:id subject)]}
  (let [{subject-type  :type
         subject-ident :id} subject

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
          paginated-types+eids (cond->> resource-types+eids
                                        offset (drop offset)
                                        limit (take limit))
          sorted-by-type+eid   (sort paginated-types+eids)
          formatted            (->> sorted-by-type+eid
                                    (map (fn [[type eid]] (spice-object type eid))))]
      {:cursor 'unsupported
       :limit  limit
       :offset offset
       :data   formatted})))

(defn count-resources
  "Temporary. Super inefficient due to the sort in lookup-resources, which is not required."
  [db query]
  (->> (assoc query :limit Long/MAX_VALUE :offset 0) ; note: we do not currently support cursor here.
       (lookup-resources db)
       (:data)
       (count)))