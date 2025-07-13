(ns eacl.datomic.impl-fixed
  "Fixed index-based implementation of lookup-resources with :self generalization"
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl-base :as base]))

;; Phase 1: Data structures for unified permission model

(defrecord UnifiedPermission [resource-type permission-name source-relation target-permission])
(defrecord TraversalPath [steps terminal-relation])
(defrecord TraversalStep [relation source-resource-type target-resource-type])

;; Phase 1: Permission normalization to unified :self model

(defn normalize-permission-to-unified
  "Converts both direct and arrow permissions to unified format using :self"
  [permission-def]
  (cond
    ;; Direct permission: (Permission :server :owner :view)
    ;; Becomes: server.view = self->owner
    (contains? permission-def :eacl.permission/relation-name)
    (->UnifiedPermission
     (:eacl.permission/resource-type permission-def)
     (:eacl.permission/permission-name permission-def)
     :self
     (:eacl.permission/relation-name permission-def))

    ;; Arrow permission: (Permission :server :account :admin :view)
    ;; Becomes: server.view = account->admin
    (contains? permission-def :eacl.arrow-permission/source-relation-name)
    (->UnifiedPermission
     (:eacl.arrow-permission/resource-type permission-def)
     (:eacl.arrow-permission/permission-name permission-def)
     (:eacl.arrow-permission/source-relation-name permission-def)
     (:eacl.arrow-permission/target-permission-name permission-def))

    :else
    (throw (ex-info "Unknown permission format" {:permission permission-def}))))

;; Helper functions for querying permissions

(defn get-direct-permissions
  "Gets all direct permissions for a resource type and permission"
  [db resource-type permission]
  (d/q '[:find [(pull ?perm [*]) ...]
         :in $ ?resource-type ?permission
         :where
         [(tuple ?resource-type ?permission) ?rtype+perm]
         [?perm :eacl.permission/resource-type+permission-name ?rtype+perm]]
       db resource-type permission))

(defn get-arrow-permissions
  "Gets all arrow permissions for a resource type and permission"
  [db resource-type permission]
  (d/q '[:find [(pull ?arrow [*]) ...]
         :in $ ?resource-type ?permission
         :where
         [(tuple ?resource-type ?permission) ?rtype+perm]
         [?arrow :eacl.arrow-permission/resource-type+permission-name ?rtype+perm]]
       db resource-type permission))

(defn get-target-resource-type
  "Given a resource type and relation name, returns the target resource type"
  [db resource-type source-relation]
  (d/q '[:find ?subject-type .
         :in $ ?resource-type ?source-relation
         :where
         [?relation :eacl.relation/resource-type ?resource-type]
         [?relation :eacl.relation/relation-name ?source-relation]
         [?relation :eacl.relation/subject-type ?subject-type]]
       db resource-type source-relation))

;; Phase 1: Unified permission path analysis

;; Forward declarations to avoid circular dependency
(declare get-unified-permission-paths)
(declare traverse-traversal-path)

(defn resolve-permission-recursively
  "Resolves permissions recursively, handling cases where target permissions are arrows"
  [db unified-permission visited-permissions]
  (let [key [(:resource-type unified-permission) (:permission-name unified-permission)]]
    (if (contains? visited-permissions key)
      [] ; Circular reference, return empty
      (let [visited (conj visited-permissions key)]
        (if (= :self (:source-relation unified-permission))
          ;; Direct permission - terminal case
          [(->TraversalPath [] (:target-permission unified-permission))]
          ;; Arrow permission - create single step without recursive expansion
          (let [target-resource-type (get-target-resource-type db (:resource-type unified-permission)
                                                               (:source-relation unified-permission))]
            (if target-resource-type
              [(->TraversalPath
                [(->TraversalStep (:source-relation unified-permission)
                                  (:resource-type unified-permission)
                                  target-resource-type)]
                (:target-permission unified-permission))]
              [])))))))

(defn get-unified-permission-paths
  "Returns all permission paths using unified model. Handles union permissions."
  [db resource-type permission]
  (let [direct-permissions (get-direct-permissions db resource-type permission)
        arrow-permissions (get-arrow-permissions db resource-type permission)
        all-permissions (concat direct-permissions arrow-permissions)
        unified-permissions (map normalize-permission-to-unified all-permissions)]
    (mapcat (fn [unified-perm]
              (resolve-permission-recursively db unified-perm #{}))
            unified-permissions)))

;; Phase 3: Index traversal functions

(defn matches-forward-tuple
  "Checks if a datom matches the expected forward tuple pattern"
  [{:as datom, v :v} subject-type subject-eid relation resource-type]
  (let [[dt-subject-type dt-subject dt-relation dt-resource-type _resource-eid] v]
    (and (= dt-subject-type subject-type)
         (= dt-subject subject-eid)
         (= dt-relation relation)
         (= dt-resource-type resource-type))))

(defn extract-resource-from-forward-datom
  "Extracts [resource-type resource-eid] from a forward datom"
  [{:as datom, v :v}]
  (let [[_ _ _ resource-type resource-eid] v]
    [resource-type resource-eid]))

(defn matches-backward-tuple
  "Checks if a datom matches the expected backward tuple pattern"
  [{:as datom, v :v} resource-type resource-eid relation subject-type]
  (let [[dt-resource-type dt-resource dt-relation dt-subject-type _subject-eid] v]
    (and (= dt-resource-type resource-type)
         (= dt-resource resource-eid)
         (= dt-relation relation)
         (= dt-subject-type subject-type))))

(defn extract-subject-from-backward-datom
  "Extracts [subject-type subject-eid] from a backward datom"
  [{:as datom, v :v}]
  (let [[_ _ _ subject-type subject-eid] v]
    [subject-type subject-eid]))

(defn traverse-relationship-forward
  "Traverses relationships forward: subject → resource via relation"
  [db subject-type subject-eid relation target-resource-type cursor limit]
  (let [start-tuple [subject-type subject-eid relation target-resource-type
                     (or cursor 0)]
        datoms (d/index-range db
                              :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                              start-tuple nil)]
    (->> datoms
         (take-while #(matches-forward-tuple % subject-type subject-eid relation target-resource-type))
         (map extract-resource-from-forward-datom)
         (take limit))))

(defn traverse-relationship-backward
  "Traverses relationships backward: resource → subject via relation"
  [db resource-type resource-eid relation target-subject-type cursor limit]
  ;; Since we don't have a reverse index, use Datalog query
  (let [results (d/q '[:find ?subject
                       :in $ ?resource-type ?resource-eid ?relation ?target-subject-type
                       :where
                       [?rel :eacl.relationship/resource-type ?resource-type]
                       [?rel :eacl.relationship/resource ?resource-eid]
                       [?rel :eacl.relationship/relation-name ?relation]
                       [?rel :eacl.relationship/subject-type ?target-subject-type]
                       [?rel :eacl.relationship/subject ?subject]]
                     db resource-type resource-eid relation target-subject-type)]
    ;; Convert to [type eid] format to match forward traversal
    (map (fn [[subject-eid]] [target-subject-type subject-eid]) results)))

(defn find-resources-with-permission
  "Finds resources where subject has a specific permission (using full permission resolution)"
  [db subject-type subject-eid permission resource-type limit]
  (let [permission-paths (get-unified-permission-paths db resource-type permission)]
    (mapcat (fn [path]
              ;; Use traverse-traversal-path for all paths (direct and arrow)
              (traverse-traversal-path db subject-type subject-eid path resource-type limit))
            permission-paths)))

;; Phase 4: Single traversal algorithm

(defn traverse-traversal-path
  "Traverses a complete traversal path"
  [db subject-type subject-eid traversal-path resource-type limit]
  (let [steps (:steps traversal-path)
        terminal-relation (:terminal-relation traversal-path)]
    (if (empty? steps)
      ;; Direct permission case (:self)
      ;; Find resources where subject has the terminal relation TO the resource
      (traverse-relationship-forward db subject-type subject-eid terminal-relation resource-type nil limit)
      ;; Arrow permission case - need to traverse the chain
      ;; For arrow permissions like server.view = account->admin:
      ;; 1. Find servers that have :account relation to some account
      ;; 2. Check if subject has :admin permission on those accounts
      (let [first-step (first steps)
            source-relation (:relation first-step)
            target-resource-type (:target-resource-type first-step)
            remaining-steps (rest steps)]

        ;; Find all resources of the target type that the subject has permission for
        (if (empty? remaining-steps)
          ;; Simple one-step arrow: server.view = account->admin
          ;; 1. Find entities where subject has terminal-relation PERMISSION on target-resource-type
          (let [intermediate-entities (find-resources-with-permission db subject-type subject-eid terminal-relation target-resource-type limit)]
            ;; 2. For each intermediate entity, find resources connected via source-relation (forward traversal)
            (mapcat (fn [[inter-type inter-eid]]
                      (traverse-relationship-forward db inter-type inter-eid source-relation resource-type nil limit))
                    intermediate-entities))
          ;; Multi-step arrow: more complex chain
          (let [sub-path (->TraversalPath remaining-steps terminal-relation)
                intermediate-entities (traverse-traversal-path db subject-type subject-eid sub-path target-resource-type limit)]
            (mapcat (fn [[inter-type inter-eid]]
                      (traverse-relationship-forward db inter-type inter-eid source-relation resource-type nil limit))
                    intermediate-entities)))))))

;; Phase 5: Stable ordering and cursor handling

(defn stable-resource-comparator
  "Defines stable ordering for resources across all permission paths"
  [[type1 eid1] [type2 eid2]]
  (let [type-cmp (compare (str type1) (str type2))]
    (if (zero? type-cmp)
      (compare eid1 eid2)
      type-cmp)))

(defn extract-cursor-eid
  "Extracts cursor EID from cursor object"
  [db cursor]
  (cond
    (nil? cursor) nil
    (string? cursor) (d/entid db [:eacl/id cursor])
    (map? cursor) (:resource-id cursor)
    :else cursor))

(defn apply-cursor-filter
  "Applies cursor filtering to a sequence of resources"
  [resources cursor-eid]
  (if cursor-eid
    (->> resources
         (drop-while (fn [[_ resource-eid]] (not= resource-eid cursor-eid)))
         (drop 1)) ; Skip the cursor itself
    resources))

(defn apply-cursor-and-limit
  "Applies cursor filtering and limit WITHOUT sorting (preserves index order)"
  [resources cursor limit]
  (let [cursor-filtered (apply-cursor-filter resources cursor) ; NO SORTING!
        limited-resources (take limit cursor-filtered)]
    limited-resources))

;; Phase 2: Union permission result combination

(defn combine-union-results
  "Combines results from multiple permission paths with efficient deduplication"
  [path-results cursor limit]
  (let [all-resources (apply concat path-results)
        ;; Use volatile for efficient deduplication
        seen (volatile! #{})
        deduplicated (filter (fn [resource]
                               (if (contains? @seen resource)
                                 false
                                 (do (vswap! seen conj resource)
                                     true)))
                             all-resources)]
    (apply-cursor-and-limit deduplicated cursor limit)))

;; Phase 6: Main implementation

(defn eid->spice-object
  "Converts a Datomic entity ID to a SpiceObject with entity ID as :id"
  [db resource-type resource-eid]
  ;; Return SpiceObject with entity ID as :id (not string ID)
  (spice-object resource-type resource-eid))

(defn lookup-resources
  "Main lookup-resources implementation with unified permission model"
  [db {:as query
       subject :subject
       permission :permission
       resource-type :resource/type
       cursor :cursor
       limit :limit
       :or {cursor nil limit 1000}}]
  {:pre [(:type subject) (:id subject)
         (keyword? permission) (keyword? resource-type)]}

  (let [{subject-type :type subject-id :id} subject

        ;; Convert subject ID to entity ID if needed
        subject-eid (cond
                      (number? subject-id) subject-id
                      (string? subject-id) (d/entid db [:eacl/id subject-id])
                      (keyword? subject-id) (d/entid db subject-id)
                      :else subject-id)

        ;; Subject ID is always a valid Datomic entid in the internal implementation
        _ (assert subject-eid (str "Subject not found: " subject-id))

        ;; Resolve all permission paths using unified model
        permission-paths (get-unified-permission-paths db resource-type permission)

        ;; Handle cursor extraction
        cursor-eid (extract-cursor-eid db cursor)

        ;; CRITICAL FIX: Get all path results WITHOUT cursor filtering
        safe-limit (if (= limit Long/MAX_VALUE) limit (min Long/MAX_VALUE (* limit 2)))
        path-results (map (fn [path]
                            ;; Get path results without cursor filtering
                            (traverse-traversal-path db subject-type subject-eid path
                                                     resource-type safe-limit))
                          permission-paths)

        ;; Use existing correct helper function
        combined-resources (combine-union-results path-results cursor-eid limit)

        ;; Convert to SpiceObjects
        spice-objects (map (fn [[type eid]] (eid->spice-object db type eid)) combined-resources)

        ;; Create next cursor (only if we got full limit, indicating more results)
        next-cursor (when (= (count combined-resources) limit)
                      (when-let [last-resource (last combined-resources)]
                        (base/->Cursor 0 (second last-resource))))]

    {:data spice-objects
     :cursor next-cursor}))

(defn count-resources
  "counts resources using the lookup-resources implementation.
  Can be more efficient because it does an unnecessary eid->spice-object in lookup-resources."
  [db query]
  (count (:data (lookup-resources db (assoc query :limit 100000)))))