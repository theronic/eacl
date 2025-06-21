(ns eacl.datomic.impl-optimized
  "Optimized EACL implementation with performance improvements"
  (:require
    [datomic.api :as d]
    [eacl.core :as proto :refer [spice-object]]
    [eacl.datomic.impl-base :as base]
    [eacl.datomic.rules.optimized :as rules]))

(defn entity->spice-object [ent]
  (spice-object (get ent base/resource-type-attr) (get ent base/object-id-attr)))

;; Configuration (not used everywhere soz)
(def object-id-attr :eacl/id)
(def resource-type-attr :eacl/type)

(defn lookup-subjects
  "Optimized version of lookup-subjects"
  [db {:as              filters
       resource         :resource
       permission       :permission
       subject-type     :subject/type
       subject-relation :subject/relation
       limit            :limit
       offset           :offset}]
  {:pre [(:type resource) (:id resource)]}
  (let [{resource-type :type
         resource-id   :id} resource

        {:as          resource-ent
         resource-eid :db/id} (d/entity db [object-id-attr resource-id])]
    (assert resource-eid (str "lookup-subjects requires a valid resource with unique attr " (pr-str object-id-attr) "."))
    (assert (= resource-type (:eacl/type resource-ent)) (str "Resource type does not match " resource-type "."))
    (let [subject-eids   (->> (d/q '[:find [?subject ...]
                                     :in $ % ?subject-type ?permission ?resource-eid
                                     :where
                                     (has-permission ?subject-type ?subject ?permission ?resource-eid)
                                     [(not= ?subject ?resource-eid)]]
                                   db
                                   rules/rules-lookup-subjects
                                   subject-type
                                   permission
                                   resource-eid))
          paginated-eids (cond->> subject-eids
                                  offset (drop offset)
                                  limit (take limit))
          objects        (->> paginated-eids
                              (map #(d/entity db %))
                              (map entity->spice-object))]
      ; todo: cursor WIP.
      {:data   objects
       :cursor nil})))

;; Helper functions for staged lookup-resources
(defn find-direct-resources
  "Find resources directly accessible by subject"
  [db subject-eid resource-type permission]
  (d/q '[:find [?resource ...]
         :in $ ?subject ?rtype ?perm
         :where
         ;; Start from subject's relationships
         [?rel :eacl.relationship/subject ?subject]
         [?rel :eacl.relationship/relation-name ?relation]
         [?rel :eacl.relationship/resource ?resource]

         ;; Early type filter
         [?resource :eacl/type ?rtype]

         ;; Check permission using tuple
         [(tuple ?rtype ?relation ?perm) ?perm-tuple]
         [?p :eacl.permission/resource-type+relation-name+permission-name ?perm-tuple]]
       db subject-eid resource-type permission))

(defn find-arrow-resources
  "Find resources via arrow permissions (e.g., account->admin)"
  [db subject-eid resource-type permission]
  ;; First, find what intermediate permissions we need
  (let [arrow-paths (d/q '[:find ?via-rel ?target-perm
                           :in $ ?rtype ?perm
                           :where
                           [?arrow :eacl.arrow-permission/resource-type ?rtype]
                           [?arrow :eacl.arrow-permission/permission-name ?perm]
                           [?arrow :eacl.arrow-permission/source-relation-name ?via-rel]
                           [?arrow :eacl.arrow-permission/target-permission-name ?target-perm]]
                         db resource-type permission)]
    ;; For each arrow path, find accessible resources
    (apply concat
           (for [[via-rel target-perm] arrow-paths]
             ;; Use rules to check if subject has target permission on intermediates
             (d/q '[:find [?resource ...]
                    :in $ % ?subject ?rtype ?via-rel ?target-perm
                    :where
                    ;; Find resources of target type that have the via-relation
                    [?link :eacl.relationship/relation-name ?via-rel]
                    [?link :eacl.relationship/resource ?resource]
                    [?link :eacl.relationship/subject ?intermediate]

                    ;; Filter by resource type
                    [?resource :eacl/type ?rtype]

                    ;; Check if subject has target permission on intermediate
                    (has-permission ?subject ?target-perm ?intermediate)]
                  db
                  rules/check-permission-rules
                  subject-eid
                  resource-type
                  via-rel
                  target-perm)))))

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
  "Optimized version of can? using optimized rules"
  [db subject-ident permission resource-ident]
  {:pre [subject-ident
         (keyword? permission)
         resource-ident]}
  (let [subject-eid  (d/entid db subject-ident)
        resource-eid (d/entid db resource-ident)]
    (if-not (and subject-eid resource-eid)
      false
      (->> (d/q '[:find ?subject .
                  :in $ % ?subject ?perm ?resource
                  :where
                  (has-permission ?subject ?perm ?resource)]
                db
                rules/check-permission-rules
                subject-eid
                permission
                resource-eid)
           (boolean)))))

(def can! (fn [db subject-id permission resource-id]
            (if (can? db subject-id permission resource-id)
              true
              (throw (Exception. "Unauthorized")))))

(defn lookup-resources
  "Optimized version of lookup-resources"
  [db {:as               filters
       subject           :subject
       permission        :permission
       resource-type     :resource/type
       resource-relation :resource/relation
       limit             :limit
       offset            :offset}]
  {:pre [(:type subject) (:id subject)]}
  (let [{subject-type :type
         subject-id   :id} subject

        {:as         subject-ent
         subject-eid :db/id} (d/entity db [object-id-attr subject-id])]
    (assert subject-eid (str "lookup-resources requires a valid resource with unique attr " (pr-str object-id-attr) "."))
    (assert (= subject-type (:resource/type subject-ent)) (str "Specified subject type does not match actual type: " subject-type "."))
    (let [resource-eids  (->> (d/q '[:find [?resource ...]
                                     :in $ % ?subject-type ?subject-eid ?permission ?resource-type
                                     :where
                                     ;(has-permission ?subject-type ?subject-eid ?permission ?resource-type ?resource)
                                     (has-permission ?subject-eid ?permission ?resource-type ?resource)
                                     [(not= ?resource ?subject-eid)]] ; do we still need this?
                                   db
                                   rules/rules-lookup-resources
                                   subject-type
                                   subject-eid
                                   permission
                                   resource-type))
          paginated-eids (cond->> resource-eids
                                  offset (drop offset)
                                  limit (take limit))]
      (->> paginated-eids
           (map #(d/entity db %))
           (map entity->spice-object)))))

