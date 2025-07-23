(ns eacl.datomic.impl.indexed
  (:require [datomic.api :as d]
            [eacl.lazy-merge-sort :refer [lazy-merge-dedupe-sort lazy-merge-dedupe-sort-by]]
            [eacl.core :refer [spice-object]]
            [clojure.tools.logging :as log]))

(defn extract-resource-id-from-rel-tuple-datom [{:as _datom, v :v}]
  (let [[_subject-type _subject-eid _relation-name _resource-type resource-eid] v]
    resource-eid))

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
  (d/q '[:find (pull ?rel [:eacl.relation/subject-type
                           :eacl.relation/resource-type
                           :eacl.relation/relation-name]) .
         :in $ ?rtype ?rname
         :where
         [?rel :eacl.relation/resource-type ?rtype]
         [?rel :eacl.relation/relation-name ?rname]]
       db resource-type relation-name))

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

(defn get-permission-paths
  "Recursively builds paths granting permission-name on resource-type.
  Returns vector of path maps, where each path is:
  {:type :relation, :name keyword, :subject-type keyword} for direct
  or {:type :arrow, :via keyword, :target-type keyword, :sub-paths [paths]} for arrows."
  [db resource-type permission-name]
  (let [perm-defs (find-permission-defs db resource-type permission-name)]
    (->> perm-defs
         (keep (fn [perm-def]
                 (let [{:keys [eacl.permission/source-relation-name
                               eacl.permission/target-type
                               eacl.permission/target-name]} perm-def]
                   (if (= source-relation-name :self)
                     ;; Direct relation
                     (let [rel-def (find-relation-def db resource-type target-name)]
                       (if-not rel-def
                         (do
                           (log/warn "Missing Relation definition"
                                     {:resource-type resource-type
                                      :relation-name target-name})
                           nil)                             ; Skip this permission path
                         {:type         :relation
                          :name         target-name
                          :subject-type (:eacl.relation/subject-type rel-def)}))
                     ;; Arrow permission
                     (let [;; Find what type the via relation points to
                           via-rel-def (find-relation-def db resource-type source-relation-name)]
                       (if-not via-rel-def
                         (do
                           (log/warn "Missing Relation definition for via relation"
                                     {:resource-type     resource-type
                                      :via-relation-name source-relation-name})
                           nil)                             ; Skip this permission path
                         (let [intermediate-type (:eacl.relation/subject-type via-rel-def)]
                           {:type              :arrow
                            :via               source-relation-name
                            :target-type       intermediate-type ;; This is the actual type of the intermediate
                            :target-permission (when (= target-type :permission) target-name)
                            :target-relation   (when (= target-type :relation) target-name)
                            :sub-paths         (case target-type
                                                 :permission
                                                 ;; Recursive call for permission - types already resolved in recursion
                                                 (get-permission-paths db intermediate-type target-name)
                                                 ;; Direct relation - build the path with proper type resolution
                                                 :relation
                                                 (let [target-rel-def (find-relation-def db intermediate-type target-name)]
                                                   (if-not target-rel-def
                                                     (do
                                                       (log/warn "Missing Relation definition for target relation"
                                                                 {:intermediate-type    intermediate-type
                                                                  :target-relation-name target-name})
                                                       [])  ; Return empty sub-paths
                                                     [{:type         :relation
                                                       :name         target-name
                                                       :subject-type (:eacl.relation/subject-type target-rel-def)}])))})))))))
         vec)))

(defn traverse-single-path
  "Traverses a single path from subject to find resources.
  Returns lazy seq of resource eids."
  [db subject-type subject-eid path resource-type cursor-eid]
  (case (:type path)
    :relation
    ;; Direct relation: use index-range on relationship tuple
    ;; Only proceed if the subject-type matches the expected type for this relation
    (if (= subject-type (:subject-type path))
      (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
            start-tuple [subject-type subject-eid (:name path) resource-type cursor-eid] ; cursor-eid can be nil.
            end-tuple   [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]]
        (->> (d/index-range db tuple-attr start-tuple end-tuple)
             (map extract-resource-id-from-rel-tuple-datom)
             (filter #(if cursor-eid (> % cursor-eid) true))))
      [])                                                   ; Return empty if subject type doesn't match

    :arrow
    ;; Arrow: find intermediate resources, then traverse sub-paths
    (let [;; First find intermediate resources via the :via relation
          intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
          ;; BUG: intermediate-start/end uses :target-type as resource-type, but should use resolved type from Relation.
          intermediate-start      [subject-type subject-eid (:via path) (:target-type path) cursor-eid] ; cursor-eid can be nil
          intermediate-end        [subject-type subject-eid (:via path) (:target-type path) Long/MAX_VALUE] ; target-type looks wrong here.
          intermediate-eids       (->> (d/index-range db intermediate-tuple-attr intermediate-start intermediate-end)
                                       (map extract-resource-id-from-rel-tuple-datom))]
      ;; For each intermediate, traverse its sub-paths
      (->> intermediate-eids
           ; todo avoid mapcat (ruins order) and use lazy sort instead to avoid sort & dedupe later.
           (mapcat (fn [intermediate-eid]
                     (->> (:sub-paths path)
                          (mapcat #(traverse-single-path db
                                                         (:target-type path)
                                                         intermediate-eid
                                                         %
                                                         resource-type
                                                         cursor-eid)))))))))

(defn traverse-permission-path
  ; I'm not sure why we are returning the path with the results, since it does not seem to be used anyhere.
  ; complicates the consumer, which has to use lazy-merge-dedupe-sort-by to extract the resource eids in first position.
  "Bidirectional permission path traversal.
  Returns lazy seq of [resource-eid path-taken] tuples.

  For direct relations: Uses forward index from subject
  For arrow permissions: Uses reverse traversal - finds intermediates
  with permission first, then resources connected to them."
  [db subject-type subject-eid permission-name resource-type cursor-eid limit]
  (let [paths           (get-permission-paths db resource-type permission-name)
        ;; For each path, determine traversal strategy
        path-results    (->> paths
                             (map (fn [{:as path, path-type :type}]
                                    (case path-type
                                      :relation
                                      ;; Direct relation - forward traversal
                                      (when (= subject-type (:subject-type path))
                                        (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                              start-tuple [subject-type subject-eid (:name path) resource-type (or cursor-eid 0)]
                                              end-tuple   [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]]
                                          (->> (d/index-range db tuple-attr start-tuple end-tuple)
                                               (map (fn [datom]
                                                      (let [resource-eid (extract-resource-id-from-rel-tuple-datom datom)]
                                                        (when (> resource-eid (or cursor-eid 0))
                                                          [resource-eid path]))))
                                               (filter some?))))

                                      :arrow
                                      ;; Arrow permission - traverse using index-range
                                      (let [via-relation      (:via path)
                                            intermediate-type (:target-type path)]
                                        (if (:target-relation path)
                                          ;; Arrow to relation: find intermediates with that relation to subject
                                          (let [target-relation         (:target-relation path)
                                                intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                                intermediate-start      [subject-type subject-eid target-relation intermediate-type cursor-eid] ; cursor-eid can be nil
                                                intermediate-end        [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE]
                                                intermediate-eids       (->> (d/index-range db intermediate-tuple-attr
                                                                                            intermediate-start intermediate-end)
                                                                             (map extract-resource-id-from-rel-tuple-datom))]
                                            ;; Now find resources connected to these intermediates using index-range
                                            (let [resource-seqs
                                                  (map (fn [intermediate-eid]
                                                         ;; Use forward index from intermediate to resources
                                                         (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                                               resource-start      [intermediate-type intermediate-eid via-relation resource-type cursor-eid] ; cursor-eid can be nil
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
                                                                                               intermediate-type nil Integer/MAX_VALUE)
                                                intermediate-eids    (map first intermediate-results)]
                                            ;; Then find resources connected to these intermediates using index-range
                                            (let [resource-seqs
                                                  (map (fn [intermediate-eid]
                                                         ;; Use forward index from intermediate to resources
                                                         (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                                                               resource-start      [intermediate-type intermediate-eid via-relation resource-type cursor-eid]
                                                               resource-end        [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                                                           (->> (d/index-range db resource-tuple-attr resource-start resource-end)
                                                                (map extract-resource-id-from-rel-tuple-datom)
                                                                (filter #(> % (or cursor-eid 0)))
                                                                (map (fn [resource-eid] [resource-eid path])))))
                                                       intermediate-eids)]
                                              ;; Use lazy-merge-dedupe-sort-by to combine sorted sequences from all intermediates
                                              (if (seq resource-seqs)
                                                (lazy-merge-dedupe-sort-by first resource-seqs)
                                                []))))))))
                             (filter some?)
                             (lazy-merge-dedupe-sort-by first)) ; Merge results from all paths
        ;; Take the requested limit from merged results
        limited-results (take limit path-results)]
    limited-results))

(defn traverse-single-permission-path
  "Traverses a single permission path. Returns lazy seq of resource eids."
  [db subject-type subject-eid path resource-type cursor-eid]
  (case (:type path)
    :relation
    ;; Direct relation - forward traversal
    (when (= subject-type (:subject-type path))
      (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
            start-tuple [subject-type subject-eid (:name path) resource-type cursor-eid] ; cursor-eid can be nil.
            end-tuple   [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]]
        (->> (d/index-range db tuple-attr start-tuple end-tuple)
             (map (fn [datom]
                    (let [resource-eid (extract-resource-id-from-rel-tuple-datom datom)]
                      (when (> resource-eid (or cursor-eid 0))
                        resource-eid))))
             (filter some?))))

    :arrow
    ;; Arrow permission - reverse traversal
    (let [via-relation      (:via path)
          intermediate-type (:target-type path)]
      (if (:target-relation path)
        ;; Arrow to relation: find intermediates subject has target-relation to, then find resources via via-relation
        (let [target-relation         (:target-relation path)
              ;; Step 1: Find intermediates that subject has target-relation to
              intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
              intermediate-start      [subject-type subject-eid target-relation intermediate-type cursor-eid]
              intermediate-end        [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE]
              intermediate-eids       (->> (d/index-range db intermediate-tuple-attr intermediate-start intermediate-end)
                                           (map extract-resource-id-from-rel-tuple-datom)
                                           (filter some?))]
          ;; Step 2: Find resources that those intermediates have via-relation to
          (let [resource-seqs
                (map (fn [intermediate-eid]
                       ;; Use forward index from intermediate to resources via via-relation
                       (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                             resource-start      [intermediate-type intermediate-eid via-relation resource-type cursor-eid]
                             resource-end        [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                         (->> (d/index-range db resource-tuple-attr resource-start resource-end)
                              (map extract-resource-id-from-rel-tuple-datom)
                              (filter some?)
                              (filter #(if cursor-eid (> % cursor-eid) true)))))
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
                                                             intermediate-type nil Integer/MAX_VALUE)
              intermediate-eids    (map first intermediate-results)]
          ;; Now find resources connected to these intermediates using index-range
          (let [resource-seqs
                (map (fn [intermediate-eid]
                       ;; Use forward index from intermediate to resources
                       (let [resource-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                             resource-start      [intermediate-type intermediate-eid via-relation resource-type cursor-eid] ; cursor-eid can be nil.
                             resource-end        [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                         (->> (d/index-range db resource-tuple-attr resource-start resource-end)
                              (map extract-resource-id-from-rel-tuple-datom)
                              (filter some?) ; not sure how this can happen given we're mapping over a seq.
                              (filter (fn [resource-eid] (> resource-eid (or cursor-eid 0)))))))
                     intermediate-eids)]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq resource-seqs)
              (lazy-merge-dedupe-sort resource-seqs)
              [])))))))

(defn lazy-merged-lookup-resources
  "Lookup resources using lazy merge for multiple paths.
  Properly handles duplicate resources from different paths."
  [db {:keys [subject permission resource/type limit cursor]
       :or   {limit 1000}}]
  (let [{subject-type :type
         subject-id   :id} subject

        subject-eid    (d/entid db subject-id)

        {cursor-resource :resource} cursor
        cursor-eid     (:id cursor-resource)                ; can be nil.

        ;; Get all permission paths
        paths          (get-permission-paths db type permission)

        ;; Create sorted lazy sequence for each path
        path-seqs      (keep (fn [path]
                               (let [results (traverse-single-permission-path db subject-type subject-eid
                                                                              path type cursor-eid)]
                                 (when (seq results)
                                   results)))               ; Already sorted by eid
                             paths)

        ;; Use lazy-merge-dedupe-sort to combine sorted sequences
        merged-results (if (seq path-seqs)
                         (lazy-merge-dedupe-sort path-seqs)
                         [])]
    merged-results))

(defn lookup-resources
  [db {:as   query
       :keys [subject permission resource/type limit cursor]
       :or   {limit 1000}}]
  (let [merged-results  (lazy-merged-lookup-resources db query)
        ;; Take the requested limit
        limited-results (take limit merged-results)

        ;; Convert to spice objects
        resources       (map #(spice-object type %) limited-results)

        ;; Create cursor
        last-resource   (last resources)
        new-cursor      (when (and last-resource
                                   (>= (count resources) limit))
                          {:resource last-resource})]
    {:data   resources
     :cursor new-cursor}))

(defn count-resources
  [db query]
  (count (lazy-merged-lookup-resources db query)))

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
                                 reverse-start      [resource-type resource-eid via-relation intermediate-type 0]
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
                                 reverse-start      [resource-type resource-eid via-relation intermediate-type 0]
                                 reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
                                 intermediates      (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                                                         (map extract-resource-id-from-rel-tuple-datom))] ; Extract subject (intermediate) eid
                             ;; Recursively check permission on any intermediate
                             (some (fn [intermediate-eid]
                                     (can? db subject target-permission
                                           (spice-object intermediate-type intermediate-eid)))
                                   intermediates))))))))))))