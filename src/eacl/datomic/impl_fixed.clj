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
      []                                                    ; Circular reference, return empty
      (let [visited (conj visited-permissions key)] ; why are we are not using this?
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
  (let [direct-permissions  (get-direct-permissions db resource-type permission)
        arrow-permissions   (get-arrow-permissions db resource-type permission)
        all-permissions     (concat direct-permissions arrow-permissions)
        unified-permissions (map normalize-permission-to-unified all-permissions)]
    (mapcat (fn [unified-perm]
              (resolve-permission-recursively db unified-perm #{}))
            unified-permissions)))

;; Phase 3: Index traversal functions

(defn datom-matches-forward-tuple?
  "Checks if a datom matches the expected forward tuple pattern"
  [{:as datom, v :v} subject-type subject-eid relation resource-type]
  (log/debug 'take? v)
  (let [[dt-subject-type dt-subject dt-relation dt-resource-type _resource-eid] v]
    (and (= dt-subject-type subject-type)
         (= dt-subject subject-eid)
         (= dt-relation relation)
         (= dt-resource-type resource-type))))

(defn tuple-datom->resource-type+eid
  "Extracts [resource-type resource-eid] from a forward datom"
  [{:as _datom, v :v}]
  (let [[_ _ _ resource-type resource-eid] v]
    [resource-type resource-eid]))

(defn traverse-relationship-forward
  "Traverses relationships forward: subject → resource via relation"
  [db subject-type subject-eid relation target-resource-type cursor]
  (let [;; FIX: Proper cursor handling for index-range
        {cursor-path     :path-index
         cursor-resource :resource} cursor
        cursor-resource-eid (:id cursor-resource)

        ; should we be using cursor resource type here, or the resource-type that was passed in?
        start-tuple         [subject-type subject-eid relation target-resource-type cursor-resource-eid] ; cursor is eid and can be nil.
        datoms              (d/index-range db
                                           :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                           start-tuple nil)]
    (->> datoms
         ;; Verify we're still in the right "section" of index
         ;; ; this is experimental to avoid drop 1 weirdness.
         ; I'm not sure we need this drop-while?
         (drop-while (fn [{:as _datom v :v}]
                       (let [[_subject-type _subject-eid _relation resource-type resource-eid] v]
                         ; we don't check type because it is encoded in tuple index.

                         (log/debug 'drop-while? v)         ; 'compare '< resource-eid cursor-resource-eid)
                         (not= target-resource-type resource-type))))
         ;(if cursor-resource-eid
         ;  (= resource-eid cursor-resource-eid)
         ;  false))))
         (take-while #(datom-matches-forward-tuple? % subject-type subject-eid relation target-resource-type))
         ;; FIX: Skip cursor record itself if we started exactly at cursor
         (drop-while (fn [{:as _datom v :v}]
                       (let [[_subject-type _subject-eid _relation_name _resource-type resource-eid] v]
                         (log/debug 'drop? v)
                         ; todo: should we include the resource type against cursor comparison?
                         (and cursor (= resource-eid cursor-resource-eid))))) ; do we need this?
         (map tuple-datom->resource-type+eid))))

(defn find-resources-with-permission
  "Finds resources where subject has a specific permission (using full permission resolution)"
  [db subject-type subject-eid permission resource-type cursor]
  (let [permission-paths (get-unified-permission-paths db resource-type permission)] ; this is slow.
    (mapcat (fn [path]
              ;; Use traverse-traversal-path for all paths (direct and arrow) - PASS CURSOR!
              (traverse-traversal-path db subject-type subject-eid path resource-type cursor))
            permission-paths)))

;; Phase 4: Single traversal algorithm

(defn traverse-traversal-path
  "Traverses a complete traversal path"
  [db subject-type subject-eid path resource-type cursor]
  (let [steps             (:steps path)
        terminal-relation (:terminal-relation path)]
    (if (empty? steps)
      ;; Direct permission case (:self)
      ;; Find resources where subject has the terminal relation TO the resource
      (traverse-relationship-forward db subject-type subject-eid terminal-relation resource-type cursor)
      ;; Arrow permission case - need to traverse the chain
      ;; For arrow permissions like server.view = account->admin:
      ;; 1. Find servers that have :account relation to some account
      ;; 2. Check if subject has :admin permission on those accounts
      (let [[first-step & remaining-steps] steps
            source-relation      (:relation first-step)
            target-resource-type (:target-resource-type first-step)]

        ;; Find all resources of the target type that the subject has permission for
        (if (empty? remaining-steps)
          ;; Simple one-step arrow: server.view = account->admin
          ;; 1. Find entities where subject has terminal-relation PERMISSION on target-resource-type
          (let [intermediate-entities (find-resources-with-permission db subject-type subject-eid terminal-relation target-resource-type cursor)]
            ;; 2. For each intermediate entity, find resources connected via source-relation (forward traversal)
            (mapcat (fn [[inter-type inter-eid]]
                      (traverse-relationship-forward db inter-type inter-eid source-relation resource-type cursor))
                    intermediate-entities))
          ;; Multi-step arrow: more complex chain
          (let [sub-path              (->TraversalPath remaining-steps terminal-relation)
                intermediate-entities (traverse-traversal-path db subject-type subject-eid sub-path target-resource-type cursor)]
            (->> intermediate-entities
                 (mapcat (fn [[inter-type inter-eid]]
                           (traverse-relationship-forward db inter-type inter-eid source-relation resource-type cursor))))))))))

;; Phase 2: Union permission result combination

(defn dedupe-stable-order [coll]
  (let [seen (volatile! #{})]
    (->> coll (filter (fn [item]
                        (if (contains? @seen item)
                          false
                          (do (vswap! seen conj item)
                              true)))))))

;(defn combine-union-results
;  "Combines results from multiple permission paths."
;  [path-results cursor limit]
;  ; why not mapcat?
;  (let [all-resources      (apply concat path-results)
;        ;last-eids-in-paths (remove nil? (map last path-results)) ; (map :id (map last path-results))
;        ;_ (log/debug 'last-eids-in-paths last-eids-in-paths)
;        ;max-eid-of-last    (apply max last-eids-in-paths)
;        ;; Use volatile for efficient deduplication
;        deduplicated       (dedupe-stable-order all-resources)]
;        ;; Apply cursor filtering if needed (for union permissions)
;
;        ;cursor-filtered    (if cursor
;        ;                     (->> deduplicated
;        ;                          ; not sure if this is correct.
;        ;                          (drop-while (fn [[_ resource-eid]] (not= resource-eid max-eid-of-last)))
;        ;                          (drop 1))                  ; Skip cursor itself. Why are we skipping cursor itself here?
;        ;                     deduplicated)]
;    ;; Take limit from cursor-filtered results
;    (take limit deduplicated))) ; was: cursor-filtered

;; Phase 6: Main implementation

(defn eid->spice-object
  "Converts a Datomic entity ID to a SpiceObject with entity ID as :id"
  [db resource-type resource-eid]
  ;; Return SpiceObject with entity ID as :id (not string ID)
  (spice-object resource-type resource-eid))

(defn lookup-resources
  "Main lookup-resources implementation with unified permission model"
  [db {:as           query
       subject       :subject
       permission    :permission
       resource-type :resource/type
       cursor        :cursor
       limit         :limit
       :or           {cursor nil limit 1000}}]
  {:pre [(:type subject) (:id subject)
         (keyword? permission) (keyword? resource-type)]}

  (log/debug 'impl-fixed/lookup-resources query)

  (let [{subject-type :type subject-eid :id} subject

        ;; Subject ID is always a valid Datomic entid in the internal implementation
        _                  (assert subject-eid (str "Subject not found: " subject-eid))

        ;; Resolve all permission paths using unified model
        paths              (get-unified-permission-paths db resource-type permission)

        _                  (log/debug 'unified-permission-paths paths)
        _                  (log/debug 'lookup 'cursor cursor)

        ;; CRITICAL FIX: Get all path results WITHOUT cursor filtering

        path-results       (->> paths
                                (map (fn [path]
                                       ;; FIX: Only pass cursor for single path, not union permissions
                                       (let [path-cursor cursor] ;(if (= 1 (count paths)) cursor nil)]
                                         (traverse-traversal-path db subject-type subject-eid path resource-type path-cursor)))))

        ;; Use existing correct helper function
        combined-resources (apply concat path-results)      ; note: this is lazy.
        _                  (log/debug 'combined-resources combined-resources)

        deduplicated       (dedupe-stable-order combined-resources)
        _                  (log/debug 'deduplicated deduplicated)

        ; note: search one more than requested and drop it, so we have cursor for next lookup.
        ; can probably be more efficient.
        limited-results    (->> deduplicated (take limit))  ; idea: what if we take one more, and drop it later for next cursor.
        ; this O(limit) cost sort can be avoided if we are smarter about multiple paths and limits:
        ; (todo: pull out my lazy-merge-sort from multiple colls to find)
        sorted-results     (->> limited-results (sort-by (fn [[type id]] id))) ; we need to sort here because we don't have smarter last-tracking, which can be done.
        _                  (log/debug 'sorted-results sorted-results)
        last-result        (last sorted-results)            ; for next cursor.
        ;without-last-result (if (> (count limited-results) limit) ; in case there are more results, we take one more, otherwise return last. not ideal. should be :end or something.
        ;                      (butlast limited-results)
        ;                      limited-results) ;
        ; todo fix feels wrong.

        spice-objects      (->> limited-results (map (fn [[type eid]] (eid->spice-object db type eid))))

        ;; Create next cursor with type+id map format (internal format)
        next-cursor        (if last-result
                             (let [[type eid] last-result]
                               ; why are we not returning the path-index here? this looks like regression.
                               (base/->Cursor 0 (spice-object type eid))))]

    (let [results {:data   spice-objects
                   :cursor next-cursor}]
      (log/debug 'internal/lookup-resources 'returning results 'next next-cursor)
      results)))

(defn count-resources
  "counts resources using the lookup-resources implementation.
  Can be more efficient because it does an unnecessary eid->spice-object in lookup-resources."
  [db query]
  (count (:data (lookup-resources db (assoc query :limit 100000)))))