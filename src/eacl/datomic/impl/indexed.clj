(ns eacl.datomic.impl.indexed
  (:require [datomic.api :as d]
            [eacl.lazy-merge-sort :refer [lazy-merge-dedupe-sort lazy-merge-dedupe-sort-by]]
            [eacl.core :refer [spice-object]]
            [clojure.tools.logging :as log]))

(defn extract-resource-id-from-rel-tuple-datom [{:as _datom, v :v}]
  (let [[_subject-type _subject-eid _relation-name _resource-type resource-eid] v]
    resource-eid))

(defn extract-subject-id-from-reverse-rel-tuple-datom [{:as _datom, v :v}]
  (let [[_resource-type _resource-eid _relation-name _subject-type subject-eid] v]
    subject-eid))

;(defn find-arrow-permissions
;  "Arrows permission means either,
;  permission thing = relation->relation | permission
;  or
;  permission thing = self->relation | permission.
;
;  Self-relational permissions are still considered arrow permissions. Better name?"
;  ; todo combine these two queries (find-relation-permissions) and group-by target-type. saves a query
;  [db resource-type permission]
;  ; todo avoid cycles by checking seen perm-def.
;  (d/q '[:find [(pull ?perm-def [*]) ...]                   ; todo: can we return values here more directly?
;         :in $ ?resource-type ?permission
;         :where
;         ; todo: tuple optimization.
;         [?perm-def :eacl.permission/resource-type ?resource-type]
;         [?perm-def :eacl.permission/permission-name ?permission]
;         ;[?perm-def :eacl.permission/target-type :permission]
;         [?perm-def :eacl.permission/source-relation-name ?source-relation]] ; via-relation rather?
;       db resource-type permission))

;(defn get-relation-subject-type
;  "Given a resource type and relation name, returns the target subject type, which becomes an inferred resource type."
;  [db resource-type source-relation-name]
;  (d/q '[:find ?subject-type .
;         :in $ ?resource-type ?source-relation
;         :where
;         [?relation :eacl.relation/resource-type ?resource-type]
;         [?relation :eacl.relation/relation-name ?source-relation]
;         [?relation :eacl.relation/subject-type ?subject-type]]
;       db resource-type source-relation-name))

(defn find-relation-def
  "Finds the Relation definition for a given resource-type and relation-name.
  Returns map with :eacl.relation/subject-type as the target type for arrows, or nil if not found."
  [db resource-type relation-name]
  ; this can be optimized, since we already know resource type & relation name.
  ; only need to add subject-type.
  (if (and resource-type relation-name)
    (d/q '[:find (pull ?rel [:eacl.relation/subject-type
                             :eacl.relation/resource-type
                             :eacl.relation/relation-name]) .
           :in $ ?rtype ?rname
           :where
           [?rel :eacl.relation/resource-type ?rtype]
           [?rel :eacl.relation/relation-name ?rname]]
      db resource-type relation-name)
    (do
      (log/warn 'find-relation-def 'called-with resource-type relation-name)
      (throw (Exception. (str 'find-relation-def 'called-with resource-type relation-name))) ; throw temporarily to debug.
      nil)))

(defn find-permission-defs
  "Finds all Permission definitions that grant permission-name on resource-type.
  Returns vector of pulled Permission maps."
  [db resource-type permission-name]
  ;; Use index lookup instead of query to avoid the error
  (let [tuple-val [resource-type permission-name]]
    (->> (d/datoms db :avet :eacl.permission/resource-type+permission-name tuple-val)
      (map :e)
      (map #(d/pull db '[*] %))
      vec)))

(defn resolve-self-relation [db resource-type target-relation-name]
  (if-let [rel-def (find-relation-def db resource-type target-relation-name)]
    {:type         :relation
     :name         target-relation-name
     :subject-type (:eacl.relation/subject-type rel-def)}
    (do                                                     ; Skip if missing. Do we handle this nil correctly?
      (log/warn "Missing Relation definition"
        {:resource-type resource-type
         :relation-name target-relation-name})
      nil)))

(defn get-permission-paths
  "Recursively builds paths granting permission-name on resource-type.
  Returns vector of path maps, where each path is:
  {:type :relation, :name keyword, :subject-type keyword} for direct path, or
  {:type :arrow, :via keyword, :target-type keyword, :sub-paths [paths]} for arrows."
  ([db resource-type permission-name]
   (get-permission-paths db resource-type permission-name #{}))
  ([db resource-type permission-name visited-perms]
   (let [perm-defs       (find-permission-defs db resource-type permission-name)
         perm-eids       (map :db/id perm-defs)
         updated-visited (reduce conj visited-perms perm-eids)]
     (->> perm-defs
       (keep (fn [{:as                   perm-def
                   perm-eid              :db/id
                   :eacl.permission/keys [source-relation-name
                                          target-type
                                          target-name]}]
               ; how to handle {:relation :self :permission :local-permission} ?
               ;(log/debug 'perm-def perm-def)

               (assert resource-type "resource-type missing")
               (assert source-relation-name "source-relation-name missing")

               ;; Cycle detection: check if we've already visited this permission
               (if (contains? visited-perms perm-eid)
                 (do
                   ; Return empty paths if we detect a cycle. This should be prevented during schema writes.
                   (log/warn "Cycle detected: " perm-def " in " visited-perms)
                   [])
                 (if (= :self source-relation-name)
                   (case target-type
                     :relation (resolve-self-relation db resource-type target-name) ; target-name can be nil here. why?
                     :permission {:type              :self-permission
                                  :target-permission target-name
                                  :resource-type     resource-type})
                   (if-let [via-rel-def (find-relation-def db resource-type source-relation-name)]
                     (let [intermediate-type (:eacl.relation/subject-type via-rel-def)]
                       {:type              :arrow
                        :via               source-relation-name ; this can be :self, but we don't handle it
                        :target-type       intermediate-type ;; This is the actual type of the intermediate
                        :target-permission (when (= target-type :permission) target-name)
                        :target-relation   (when (= target-type :relation) target-name)
                        :sub-paths         (case target-type
                                             ;; Recursive permission call with cycle detection
                                             :permission (get-permission-paths db intermediate-type target-name updated-visited)
                                             ;; Direct relation - build the path with proper type resolution
                                             :relation (if-let [target-rel-def (find-relation-def db intermediate-type target-name)]
                                                         [{:type         :relation
                                                           :name         target-name
                                                           :subject-type (:eacl.relation/subject-type target-rel-def)}]
                                                         (do ; Return empty sub-paths if missing Relation
                                                           (log/warn "Missing Relation definition for arrow target relation"
                                                             {:intermediate-type    intermediate-type
                                                              :target-relation-name target-name})
                                                           [])))}) ; Skip this permission path
                     (do
                       (log/warn "Missing Relation definition for via relation"
                         {:resource-type     resource-type
                          :via-relation-name source-relation-name})
                       nil))))))

       (vec)))))

(defn traverse-single-path
  "Recursively traverses a single path from a subject to find matching resources.
  Returns lazy seq of resource eids."
  [db
   subject-type subject-eid
   {:as                  path
    path-name            :name
    path-type            :type
    path-subject-type    :subject-type
    target-relation-name :target-type
    path-via             :via
    sub-paths            :sub-paths}
   resource-type cursor-eid]
  (case path-type
    :relation
    ;; Direct relation: use index-range on relationship tuple
    ;; Only proceed if the subject-type matches the expected type for this relation
    (if (= subject-type path-subject-type)
      (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
            start-tuple [subject-type subject-eid path-name resource-type cursor-eid] ; cursor-eid can be nil.
            end-tuple   [subject-type subject-eid path-name resource-type Long/MAX_VALUE]]
        (->> (d/index-range db tuple-attr start-tuple end-tuple)
          (map extract-resource-id-from-rel-tuple-datom)
          (filter (fn [resource-eid]
                    (if cursor-eid
                      (> resource-eid cursor-eid)
                      true)))))
      [])                                                   ; Return empty if subject type doesn't match

    :arrow
    ;; Arrow: find intermediate resources, then traverse sub-paths
    (let [;; First find intermediate resources via the :via relation
          intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
          ;; Note that target-type during intermediate paths is the intermediate type.
          intermediate-start      [subject-type subject-eid path-via target-relation-name cursor-eid] ; cursor-eid can be nil
          intermediate-end        [subject-type subject-eid path-via target-relation-name Long/MAX_VALUE]
          intermediate-eids       (->> (d/index-range db intermediate-tuple-attr intermediate-start intermediate-end)
                                    (map extract-resource-id-from-rel-tuple-datom)
                                    (filter (fn [resource-eid]
                                              (if cursor-eid
                                                (> resource-eid cursor-eid)
                                                true))))]

      ;; For each intermediate-eid, recursively traverse its sub-paths
      (->> intermediate-eids
        (map (fn [intermediate-eid]
               (->> sub-paths
                 (map (fn [sub-path]
                        (traverse-single-path db
                          target-relation-name
                          intermediate-eid
                          sub-path
                          resource-type
                          cursor-eid)))
                 (lazy-merge-dedupe-sort))))
        (lazy-merge-dedupe-sort)))))

(defn can?
  "Optimized can? implementation using direct index traversal.
  Returns true as soon as any path grants permission."
  [db subject permission resource]
  (let [{subject-type :type
         subject-id   :id} subject
        {resource-type :type
         resource-id   :id} resource

        ;; Resolve to entity IDs if needed
        subject-eid  (d/entid db subject-id)
        resource-eid (d/entid db resource-id)

        ;; Get permission paths
        paths        (get-permission-paths db resource-type permission)]

    ;; Check each path - return true on first match
    (boolean
      (->> paths
        (some (fn [path]
                (case (:type path)
                  :relation
                  ;; Direct relation check
                  (when (= subject-type (:subject-type path))
                    (let [tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                          tuple-val  [subject-type subject-eid (:name path) resource-type resource-eid]]
                      (seq (d/datoms db :avet tuple-attr tuple-val))))

                  :self-permission
                  ;; Self-permission: recursively check if the resource has the target permission
                  (let [target-permission (:target-permission path)]
                    (can? db subject target-permission resource))

                  :arrow
                  ;; Arrow permission check
                  (let [via-relation      (:via path)
                        intermediate-type (:target-type path)]
                    (if (:target-relation path)
                      ;; Arrow to relation: check if there's any intermediate that connects via the arrow
                      (let [target-relation (:target-relation path)]
                        ;; Find intermediates connected to this resource via via-relation (reverse lookup)
                        ;; Use reverse index to find intermediates
                        (let [reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
                              reverse-start      [resource-type resource-eid via-relation intermediate-type 0] ; cursor = 0 because no pagination.
                              reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
                              intermediates      (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                                                   (map extract-resource-id-from-rel-tuple-datom))] ; Extract subject (intermediate) eid
                          ;; Check if subject has the target relation to any intermediate
                          (some (fn [intermediate-eid]
                                  (let [check-tuple [subject-type subject-eid target-relation
                                                     intermediate-type intermediate-eid]]
                                    (seq (d/datoms db :avet
                                           :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                           check-tuple))))
                            intermediates)))
                      ;; Arrow to permission: recursively check permission
                      (let [target-permission (:target-permission path)]
                        ;; Find intermediates connected to this resource via via-relation (reverse lookup)
                        ;; Use reverse index to find intermediates
                        (let [reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
                              reverse-start      [resource-type resource-eid via-relation intermediate-type 0] ; cursor = 0 because no pagination.
                              reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
                              intermediates      (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                                                   (map extract-resource-id-from-rel-tuple-datom))] ; Extract subject (intermediate) eid
                          ;; Recursively check permission on any intermediate
                          (some (fn [intermediate-eid]
                                  (can? db subject target-permission
                                    (spice-object intermediate-type intermediate-eid)))
                            intermediates))))))))))))

(defn traverse-permission-path
  ; I'm not sure why we are returning the path with the results, since it does not seem to be used anyhere.
  ; complicates the consumer, which has to use lazy-merge-dedupe-sort-by to extract the resource eids in first position.
  "Bidirectional permission path traversal.
  Returns lazy seq of [resource-eid path-taken] tuples.

  For direct relations: Uses forward index from subject
  For arrow permissions: Uses reverse traversal - finds intermediates
  with permission first, then resources connected to them."
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-eid visited-paths]
   ;; Cycle detection for traversal
   (let [path-key [subject-type subject-eid permission-name resource-type]]
     (if (contains? visited-paths path-key)
       []                                                   ; Return empty if we detect a traversal cycle
       (let [updated-visited   (conj visited-paths path-key)
             paths             (get-permission-paths db resource-type permission-name)
             ;; For each path, determine traversal strategy
             lazy-path-results (->> paths
                                 (map (fn [{:as path, path-type :type}]
                                        (case path-type

                                          :relation
                                          ;; Direct relation - forward traversal
                                          (when (= subject-type (:subject-type path))
                                            (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                                  start-tuple [subject-type subject-eid (:name path) resource-type 0] ;(or cursor-eid 0)]
                                                  end-tuple   [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]]
                                              (->> (d/index-range db tuple-attr start-tuple end-tuple)
                                                (map (fn [datom]
                                                       (let [resource-eid (extract-resource-id-from-rel-tuple-datom datom)]
                                                         (when (> resource-eid (or cursor-eid 0))
                                                           [resource-eid path]))))
                                                (filter some?))))

                                          :self-permission
                                          ;; Self-permission: recursively find resources where subject has target permission
                                          (let [target-permission (:target-permission path)]
                                            ;; Recursively traverse with target permission to find matching resources
                                            (traverse-permission-path db subject-type subject-eid
                                              target-permission resource-type cursor-eid updated-visited))

                                          :arrow
                                          ;; Arrow permission - traverse using index-range
                                          (let [via-relation      (:via path)
                                                intermediate-type (:target-type path)]
                                            (if (:target-relation path)
                                              ;; Arrow to relation: find intermediates with that relation to subject
                                              (let [target-relation         (:target-relation path)
                                                    intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                                    intermediate-start      [subject-type subject-eid target-relation intermediate-type 0] ;cursor-eid] ; cursor-eid can be nil
                                                    intermediate-end        [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE]
                                                    intermediate-eids       (->> (d/index-range db intermediate-tuple-attr
                                                                                   intermediate-start intermediate-end)
                                                                              (map extract-resource-id-from-rel-tuple-datom))]
                                                ;; Now find resources connected to these intermediates using index-range
                                                (let [resource-seqs
                                                      (map (fn [intermediate-eid]
                                                             ;; Use forward index from intermediate to resources
                                                             (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                                                   resource-start      [intermediate-type intermediate-eid via-relation resource-type 0] ;cursor-eid] ; cursor-eid can be nil
                                                                   resource-end        [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                                                               (->> (d/index-range db resource-tuple-attr resource-start resource-end)
                                                                 (map extract-resource-id-from-rel-tuple-datom)
                                                                 (filter #(> % (or cursor-eid 0)))
                                                                 (map (fn [resource-eid] [resource-eid path])))))
                                                        intermediate-eids)]
                                                  ;; Use lazy-merge-dedupe-sort-by to combine sorted sequences from all intermediates
                                                  (if (seq resource-seqs)
                                                    (lazy-merge-dedupe-sort-by first resource-seqs)
                                                    [])))
                                              ;; Arrow to permission: recursively find intermediates with permission
                                              (let [target-permission    (:target-permission path)
                                                    ;; First get all intermediates the subject has permission on
                                                    intermediate-results (traverse-permission-path db subject-type subject-eid
                                                                           target-permission
                                                                           intermediate-type nil updated-visited) ; this nil is expensive. how to avoid?
                                                    intermediate-eids    (map first intermediate-results)]
                                                ;; Then find resources connected to these intermediates using index-range
                                                (let [resource-seqs (->> intermediate-eids
                                                                      (map (fn [intermediate-eid]
                                                                             ;; Use forward index from intermediate to resources
                                                                             (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                                                                   resource-start      [intermediate-type intermediate-eid via-relation resource-type 0] ;cursor-eid]
                                                                                   resource-end        [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                                                                               (->> (d/index-range db resource-tuple-attr resource-start resource-end)
                                                                                 (map extract-resource-id-from-rel-tuple-datom)
                                                                                 (filter #(> % (or cursor-eid 0)))
                                                                                 (map (fn [resource-eid] [resource-eid path])))))))]
                                                  ;; Use lazy-merge-dedupe-sort-by to combine sorted sequences from all intermediates
                                                  (if (seq resource-seqs)
                                                    (lazy-merge-dedupe-sort-by first resource-seqs)
                                                    []))))))))
                                 (filter some?)
                                 (lazy-merge-dedupe-sort-by first))] ; Merge results from all paths
         lazy-path-results)))))

(defn direct-match-datoms-in-relationship-index
  [db subject-type subject-eid relation-name resource-type resource-eid]
  (d/datoms db
    :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
    subject-type subject-eid relation-name resource-type resource-eid))

(defn traverse-permission-path-via-subject
  "Subject must be known. Returns lazy seq of resource eids."
  [db subject-type subject-eid path resource-type cursor-eid]
  ;(prn 'traverse-permission-path-via-subject 'cursor-eid cursor-eid)
  (case (:type path)
    :relation
    ;; Direct relation - forward traversal
    (when (= subject-type (:subject-type path))
      (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
            start-tuple [subject-type subject-eid (:name path) resource-type (or cursor-eid 0)]
            end-tuple   [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]]
        (->> (d/index-range db tuple-attr start-tuple end-tuple)
          (map extract-resource-id-from-rel-tuple-datom)
          (filter (fn [resource-eid]
                    (and resource-eid (> resource-eid (or cursor-eid 0))))))))

    :self-permission
    ;; Self-permission: recursively get resources where subject has target permission
    (let [target-permission (:target-permission path)]
      ;; Use traverse-permission-path to get resources where subject has target permission
      (->> (traverse-permission-path db subject-type subject-eid target-permission resource-type cursor-eid #{})
        (map first)                                         ; Extract resource-eids from [resource-eid path] tuples
        (filter (fn [resource-eid]
                  (and resource-eid (> resource-eid (or cursor-eid 0)))))))

    :arrow
    ;; Arrow permission - reverse traversal
    (let [via-relation      (:via path)
          intermediate-type (:target-type path)]
      (if (:target-relation path)
        ;; Arrow to relation: find intermediates subject has target-relation to, then find resources via via-relation
        (let [target-relation         (:target-relation path)
              ;; Step 1: Find intermediates that subject has target-relation to
              intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
              intermediate-start      [subject-type subject-eid target-relation intermediate-type 0]
              intermediate-end        [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE]
              intermediate-eids       (->> (d/index-range db intermediate-tuple-attr intermediate-start intermediate-end)
                                        (map extract-resource-id-from-rel-tuple-datom)
                                        (filter some?))]
          ;; Step 2: Find resources that those intermediates have via-relation to
          (let [resource-seqs
                (map (fn [intermediate-eid]
                       ;; Use forward index from intermediate to resources via via-relation
                       (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                             resource-start      [intermediate-type intermediate-eid via-relation resource-type (or cursor-eid 0)]
                             resource-end        [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                         (->> (d/index-range db resource-tuple-attr resource-start resource-end)
                           (map extract-resource-id-from-rel-tuple-datom)
                           (filter some?)
                           (filter (fn [resource-eid]
                                     (and resource-eid (> resource-eid (or cursor-eid 0))))))))
                  intermediate-eids)]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq resource-seqs)
              (lazy-merge-dedupe-sort resource-seqs)
              [])))
        ;; Arrow to permission
        (let [target-permission    (:target-permission path)
              ;; Get all intermediates recursively
              intermediate-results (traverse-permission-path db subject-type subject-eid
                                     target-permission
                                     intermediate-type
                                     nil
                                     #{})
              intermediate-eids    (map first intermediate-results)]
          ;; Now find resources connected to these intermediates using index-range
          (let [resource-seqs
                (map (fn [intermediate-eid]
                       ;; Use forward index from intermediate to resources
                       (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                             resource-start      [intermediate-type intermediate-eid via-relation resource-type (or cursor-eid 0)]
                             resource-end        [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                         (->> (d/index-range db resource-tuple-attr resource-start resource-end)
                           (map extract-resource-id-from-rel-tuple-datom)
                           (filter some?)
                           (filter (fn [resource-eid] (> resource-eid (or cursor-eid 0)))))))
                  intermediate-eids)]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq resource-seqs)
              (lazy-merge-dedupe-sort resource-seqs)
              [])))))))

(defn lazy-merged-lookup-resources
  "Lookup resources using lazy merge for multiple paths."
  [db {:keys [subject permission resource/type limit cursor]
       :or   {limit 1000}}]
  (let [{subject-type :type
         subject-id   :id} subject

        subject-eid         (d/entid db subject-id)

        {cursor-resource :resource} cursor
        cursor-eid          (:id cursor-resource)           ; can be nil.
        ; we can't validate cursor because may have been deleted since previous call.
        ;_                   (prn 'lazy-merged-lookup-resources 'cursor cursor)

        paths               (get-permission-paths db type permission)
        path-seqs           (->> paths
                              (keep (fn [path]
                                      (let [results (traverse-permission-path-via-subject db subject-type subject-eid
                                                      path type cursor-eid)]
                                        (when (seq results)
                                          results)))))      ; Already sorted by eid

        lazy-merged-results (if (seq path-seqs)
                              (lazy-merge-dedupe-sort path-seqs)
                              [])]
    lazy-merged-results))

(defn lookup-resources
  "Default :limit 1000.
  Pass :limit -1 for all results (or any negative value)."
  [db {:as   query
       :keys [subject permission resource/type limit cursor]
       :or   {limit 1000}}]
  (let [merged-results  (lazy-merged-lookup-resources db query)
        limited-results (if (>= limit 0)
                          (take limit merged-results)
                          merged-results)
        resources       (map #(spice-object type %) limited-results)
        last-resource   (last resources)
        next-cursor     {:resource (or last-resource (:resource cursor))}]
    {:data   resources
     :cursor next-cursor}))

(defn traverse-permission-path-reverse
  "Resource must be known. Returns lazy seq of subject eids that can access the resource."
  [db resource-type resource-eid path subject-type cursor-eid]
  (case (:type path)
    :relation
    ;; Direct relation - reverse traversal from resource to subjects
    ;; Only proceed if the subject-type matches the expected type for this relation
    (when (= subject-type (:subject-type path))
      (let [reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
            start-tuple        [resource-type resource-eid (:name path) subject-type 0] ; (or cursor-eid 0)]
            end-tuple          [resource-type resource-eid (:name path) subject-type Long/MAX_VALUE]]
        (->> (d/index-range db reverse-tuple-attr start-tuple end-tuple)
          (map extract-subject-id-from-reverse-rel-tuple-datom)
          (filter (fn [subject-eid]
                    (and subject-eid (> subject-eid (or cursor-eid 0))))))))

    :self-permission
    ;; Self-permission: recursively find subjects that have target permission on this resource
    (let [target-permission (:target-permission path)
          target-paths      (get-permission-paths db resource-type target-permission)
          path-seqs         (->> target-paths
                              (map (fn [target-path]
                                     (traverse-permission-path-reverse db resource-type resource-eid
                                       target-path subject-type cursor-eid)))
                              (filter seq))]
      (if (seq path-seqs)
        (lazy-merge-dedupe-sort path-seqs)
        []))

    :arrow
    ;; Arrow permission - complex traversal using reverse then forward indices
    (let [via-relation      (:via path)
          intermediate-type (:target-type path)]
      (if (:target-relation path)
        ;; Arrow to relation: 
        ;; 1. Use reverse index to find intermediates connected to resource via via-relation
        ;; 2. For each intermediate, use forward index to find subjects with target-relation to that intermediate
        (let [target-relation    (:target-relation path)
              ;; Step 1: Find intermediates connected to this resource via via-relation (reverse lookup)
              reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
              reverse-start      [resource-type resource-eid via-relation intermediate-type 0] ; why is cursor-eid = 0 here?
              reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
              intermediate-eids  (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                                   (map extract-subject-id-from-reverse-rel-tuple-datom))]
          ; do we need a filter on cursor-eid here?
          ;; Step 2: For each intermediate, find subjects that have target-relation to it
          (let [subject-seqs
                (map (fn [intermediate-eid]
                       ;; Use reverse index again to find subjects with target-relation to this intermediate
                       (let [subject-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
                             subject-start      [intermediate-type intermediate-eid target-relation subject-type 0] ; (or cursor-eid 0)]
                             subject-end        [intermediate-type intermediate-eid target-relation subject-type Long/MAX_VALUE]]
                         (->> (d/index-range db subject-tuple-attr subject-start subject-end)
                           (map extract-subject-id-from-reverse-rel-tuple-datom)
                           (filter #(> % (or cursor-eid 0))))))
                  intermediate-eids)]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq subject-seqs)
              (lazy-merge-dedupe-sort subject-seqs)
              [])))
        ;; Arrow to permission:
        ;; 1. Use reverse index to find intermediates connected to resource via via-relation
        ;; 2. For each intermediate, recursively find subjects with target-permission on that intermediate
        (let [target-permission  (:target-permission path)
              ;; Step 1: Find intermediates connected to this resource via via-relation (reverse lookup)
              reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
              reverse-start      [resource-type resource-eid via-relation intermediate-type 0]
              reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
              intermediate-eids  (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                                   (map extract-subject-id-from-reverse-rel-tuple-datom))]
          ;; Step 2: For each intermediate, recursively find subjects with target-permission
          (let [subject-seqs (map (fn [intermediate-eid]
                                    ;; Recursively call to find subjects with target-permission on this intermediate
                                    (let [paths    (get-permission-paths db intermediate-type target-permission)
                                          sub-seqs (->> paths
                                                     (map (fn [sub-path]
                                                            (traverse-permission-path-reverse db intermediate-type intermediate-eid
                                                              sub-path subject-type cursor-eid)))
                                                     (filter seq))]
                                      (if (seq sub-seqs)
                                        (lazy-merge-dedupe-sort sub-seqs)
                                        [])))
                               intermediate-eids)]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq subject-seqs)
              (lazy-merge-dedupe-sort subject-seqs)
              [])))))))

(defn lazy-merged-lookup-subjects
  "Lookup subjects using lazy merge for multiple paths."
  [db {:keys [resource permission subject/type limit cursor]
       :or   {limit 1000}}]
  (let [{resource-type :type
         resource-id   :id} resource

        resource-eid   (d/entid db resource-id)

        {cursor-subject :subject} cursor
        cursor-eid     (:id cursor-subject)                 ; can be nil

        paths          (get-permission-paths db resource-type permission)
        path-seqs      (->> paths
                         (keep (fn [path]
                                 (let [results (traverse-permission-path-reverse db resource-type resource-eid
                                                 path type cursor-eid)]
                                   (when (seq results)
                                     results)))))

        merged-results (if (seq path-seqs)
                         (lazy-merge-dedupe-sort path-seqs)
                         [])]
    merged-results))

(defn lookup-subjects
  "Indexed implementation of lookup-subjects using direct index access.
  Returns subjects that can access the given resource with the specified permission.
  Pass :limit -1 for all results (can be slow)."
  [db {:as   query
       :keys [resource permission subject/type limit cursor]
       :or   {limit 1000}}]
  {:pre [(:type resource) (:id resource)]}
  (let [merged-results  (lazy-merged-lookup-subjects db query)
        limited-results (if (>= limit 0)
                          (take limit merged-results)
                          merged-results)
        subjects        (map #(spice-object type %) limited-results)
        last-subject    (last subjects)
        next-cursor     {:subject (or last-subject (:subject cursor))}]
    {:data   subjects
     :cursor next-cursor}))

(defn count-resources
  "Returns {:keys [count cursor limit]}, where limit matches input.
  Pass :limit -1 for all results."
  [db {:as   query
       :keys [limit cursor]
       :or   {limit -1}}]
  (let [merged-results  (lazy-merged-lookup-resources db query)
        limited-results (if (>= limit 0)
                          (take limit merged-results)
                          merged-results)
        resources       (map #(spice-object type %) limited-results)
        last-resource   (last resources)
        next-cursor     {:resource (or last-resource (:resource cursor))}]
    {:count (count limited-results)
     :limit limit
     :cursor next-cursor}))