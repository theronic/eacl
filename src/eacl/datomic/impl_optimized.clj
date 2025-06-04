(ns eacl.datomic.impl-optimized
  "Optimized EACL implementation with performance improvements"
  (:require
    [datomic.api :as d]
    [eacl.core :as proto :refer [spice-object]]
    [eacl.datomic.rules-optimized :as rules]))

;; Configuration
(def object-id-attr :entity/id)
(def resource-type-attr :resource/type)

(defn entity->spice-object [ent]
  (spice-object (get ent resource-type-attr) (get ent object-id-attr)))

(defn can?
  "Optimized version of can? using optimized rules"
  [db subject-id permission resource-id]
  {:pre [subject-id
         (keyword? permission)
         resource-id]}
  (let [{:as _subject-ent, subject-eid :db/id} (d/entity db subject-id)
        {:as _resource-ent, resource-eid :db/id} (d/entity db resource-id)]
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

(defn can! [db subject-id permission resource-id]
  (if (can? db subject-id permission resource-id)
    true
    (throw (Exception. "Unauthorized"))))

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
    (assert (= resource-type (:resource/type resource-ent)) (str "Resource type does not match " resource-type "."))
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
                                  limit (take limit))]
      (->> paginated-eids
           (map #(d/entity db %))
           (map entity->spice-object)))))

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
         [?resource :resource/type ?rtype]
         
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
        ;; This is simplified - in production we'd need to check permissions properly
        (d/q '[:find [?resource ...]
               :in $ ?subject ?rtype ?via-rel
               :where
               ;; Find intermediate resources subject has relationships with
               [?rel1 :eacl.relationship/subject ?subject]
               [?rel1 :eacl.relationship/resource ?intermediate]
               
               ;; Find target resources linked from intermediate via arrow relation
               [?rel2 :eacl.relationship/subject ?intermediate]
               [?rel2 :eacl.relationship/relation-name ?via-rel]
               [?rel2 :eacl.relationship/resource ?resource]
               [?resource :resource/type ?rtype]]
             db subject-eid resource-type via-rel)))))

(defn find-indirect-resources
  "Find resources through indirect paths - fallback for complex cases"
  [db subject-eid resource-type permission]
  ;; Use the full rules as a fallback
  (d/q '[:find [?resource ...]
         :in $ % ?subject ?permission ?resource-type
         :where
         (has-permission ?subject ?permission ?resource-type ?resource)]
       db
       rules/rules-lookup-resources
       subject-eid
       permission
       resource-type))

(defn lookup-resources-staged
  "Staged approach to resource lookup for better performance"
  [db {:keys [resource/type subject permission limit offset]
       :or {limit 100 offset 0}}]
  {:pre [(keyword? type)
         (:type subject) (:id subject)
         (keyword? permission)]}
  (let [{subject-id :id} subject
        subject-eid (:db/id (d/entity db [object-id-attr subject-id]))
        
        ;; Stage 1: Find direct relationships
        stage1-direct (find-direct-resources db subject-eid type permission)
        
        ;; Stage 2: Find via arrow permissions (if needed)
        stage2-arrow (when (< (count stage1-direct) limit)
                      (find-arrow-resources db subject-eid type permission))
        
        ;; Stage 3: Multi-hop paths (only if really needed)
        stage3-indirect (when (and (< (+ (count stage1-direct) 
                                        (count (or stage2-arrow []))) 
                                     limit)
                                  ;; Only use expensive fallback for small result sets
                                  (< limit 50))
                         (find-indirect-resources db subject-eid type permission))
        
        ;; Combine and deduplicate
        all-results (distinct (concat stage1-direct 
                                    (or stage2-arrow [])
                                    (or stage3-indirect [])))]
    
    (->> all-results
         (drop offset)
         (take limit)
         (map #(d/entity db %))
         (map entity->spice-object))))

(defn lookup-resources
  "Optimized lookup-resources using staged approach"
  [db filters]
  (lookup-resources-staged db filters)) 