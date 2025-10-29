(ns eacl.datomic.impl.indexed
  (:require [datomic.api :as d]
            [eacl.lazy-merge-sort :as lazy-sort :refer [lazy-merge-dedupe-sort lazy-merge-dedupe-sort-by]]
            [eacl.core :refer [spice-object]]
            [clojure.tools.logging :as log]))

(defn extract-resource-id-from-rel-tuple-datom [{:as _datom, v :v}]
  ; could be (last v) but this probably faster.
  (let [[_subject-type _subject-eid _relation-name _resource-type resource-eid] v]
    resource-eid))

(defn extract-subject-id-from-reverse-rel-tuple-datom [{:as _datom, v :v}]
  ; could just be (last v) but probably faster.
  (let [[_resource-type _resource-eid _relation-name _subject-type subject-eid] v]
    subject-eid))

(defn find-relation-def
  "Finds the Relation definition for a given resource-type and relation-name.
  Returns map with :eacl.relation/subject-type as the target type for arrows, or nil if not found."
  ; In v7 we only need the Relation eid.
  [db resource-type relation-name]
  ; this can be optimized, since we already know resource type & relation name.
  ; only need to add subject-type.
  ; uhmmm this looks broken. Should support multiple subject-types. We only return first. WRONG.
  (if (and resource-type relation-name)
    (d/q '[:find (pull ?rel [:db/id
                             :eacl.relation/subject-type
                             :eacl.relation/resource-type
                             :eacl.relation/relation-name]) .
           :in $ ?resource-type ?relation-name
           :where
           [?rel :eacl.relation/resource-type ?resource-type]
           [?rel :eacl.relation/relation-name ?relation-name]]
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
     :relation/id  (:db/id rel-def)
     :name         target-relation-name                     ; TODO: rename to :relation-name since we dispatch on :type. or could this be other things?
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
                     :relation (resolve-self-relation db resource-type target-name)
                     :permission {:type                 :self-permission
                                  :target-permission    target-name
                                  :target-permission/id perm-eid
                                  :resource-type        resource-type})
                   (if-let [via-rel-def (find-relation-def db resource-type source-relation-name)]
                     (let [intermediate-type (:eacl.relation/subject-type via-rel-def)]
                       {:type                 :arrow
                        :relation/id          (:db/id via-rel-def)
                        :via                  source-relation-name ; this can be :self, but we don't handle it
                        :target-type          intermediate-type ;; This is the actual type of the intermediate
                        :target-permission    (when (= target-type :permission) target-name)
                        :target-permission/id (case target-type
                                                :permission target-type
                                                nil)
                        :target-relation      (when (= target-type :relation) target-name)
                        :sub-paths            (case target-type
                                                ;; Recursive permission call with cycle detection
                                                :permission (get-permission-paths db intermediate-type target-name updated-visited)
                                                ;; Direct relation - build the path with proper type resolution
                                                :relation (if-let [target-rel-def (find-relation-def db intermediate-type target-name)]
                                                            ; do we need to repackage here? can't we just assoc :type?
                                                            [{:type         :relation
                                                              :name         target-name
                                                              :relation/id  (:db/id target-rel-def)
                                                              :subject-type (:eacl.relation/subject-type target-rel-def)}]
                                                            (do ; Return empty sub-paths if missing Relation
                                                              (log/warn "Missing Relation definition for arrow target relation"
                                                                {:intermediate-type    intermediate-type
                                                                 :target-relation-name target-name})
                                                              [])))}) ; Skip this permission path
                     (do
                       (log/warn "Missing Relation definition for via relation"
                         {:resource-type     resource-type
                          :via-relation-name source-relation-name}
                         nil)))))))

       (vec)))))

;(let [[_subject-type _subject-eid _relation-name _resource-type resource-eid] v]
;  resource-eid)

;(defn rels-range-subject->resource [db subject-type subject-eid relation-name resource-type cursor-eid]
;  (cond->>
;    (->> (d/index-range db :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
;           [subject-type subject-eid relation-name resource-type cursor-eid] ; cursor can be nil.
;           [subject-type subject-eid relation-name resource-type Long/MAX_VALUE])
;      (map extract-resource-id-from-rel-tuple-datom))       ; this can return nil.
;    ; do we need (filter some?) here. not sure why.
;    ; why do we need the filter if we have the index tuple-start?
;    cursor-eid (filter (fn [resource-eid] (> resource-eid cursor-eid)))
;    (not cursor-eid) (filter some?)))                       ; we filter out nil resource-eids. can happen.

(defn subject->resources [db subject-type subject-eid relation-eid resource-type ?cursor-resource-eid]
  [{:pre [subject-type subject-eid relation-eid resource-type]}] ; todo more checks
  ;(log/debug 'subject->resources [subject-type subject-eid relation-eid resource-type ?cursor-resource-eid])
  (let [attr     :eacl.v7.relationship/subject-type+relation+resource-type+resource
        attr-eid (d/entid db attr)
        cursor   (or ?cursor-resource-eid 0)]               ; can be cached.
    (->> (d/seek-datoms db :eavt
           subject-eid
           attr-eid
           [subject-type relation-eid resource-type ?cursor-resource-eid])
      ; consider magic cursor-eid -1 value as opposed to nil.
      (drop-while (fn [[e A v]]
                    (and
                      (== subject-eid e)
                      (== attr-eid A)
                      (let [[v-subject-type v-relation-eid v-resource-type v-resource-eid] v]
                        (and
                          (= subject-type v-subject-type)
                          (== relation-eid v-relation-eid)
                          (= resource-type v-resource-type)
                          (<= v-resource-eid cursor))))))

      (take-while (fn [[e A v]]
                    (and
                      (== subject-eid e)
                      (== attr-eid A)
                      (let [[v-subject-type v-relation-eid v-resource-type _v-resource-eid] v]
                        (and
                          (= subject-type v-subject-type)
                          (== relation-eid v-relation-eid)
                          (= resource-type v-resource-type))))))
      (map (fn [[_e _a v]] (last v))))))

;(map extract-resource-id-from-rel-tuple-datom))       ; this can return nil.
; do we need (filter some?) here. not sure why.
; why do we need the filter if we have the index tuple-start?

(defn resource->subjects [db resource-type resource-eid relation-eid subject-type ?cursor-subject-eid]
  [{:pre [resource-type resource-eid relation-eid subject-type]}] ; todo more checks
  ;(log/debug 'resource->subjects [resource-type resource-eid relation-eid subject-type ?cursor-subject-eid])
  (let [attr     :eacl.v7.relationship/resource-type+relation+subject-type+subject
        attr-eid (d/entid db attr)
        cursor   (or ?cursor-subject-eid 0)]                ; can be cached.
    (->> (d/seek-datoms db :eavt
           resource-eid
           attr-eid
           ; TODO: sort-key value. needs to be combined with eid for merge deduplication.
           [resource-type relation-eid subject-type ?cursor-subject-eid])
      ; consider magic cursor-eid -1 value as opposed to nil.
      ; do we even need these drop-whiles? I think we can drop them now
      (drop-while (fn [[e a v]]
                    (and
                      (== resource-eid e)
                      (== attr-eid a)
                      (let [[v-resource-type v-relation-eid v-subject-type v-subject-eid] v]
                        (and
                          (= resource-type v-resource-type)
                          (== relation-eid v-relation-eid)
                          (= subject-type v-subject-type)
                          (<= v-subject-eid cursor))))))
      (take-while (fn [[e a v]]
                    (and
                      (== resource-eid e)
                      (== attr-eid a)
                      (let [[v-resource-type v-relation-eid v-subject-type _v-subject-eid] v]
                        (and
                          (= resource-type v-resource-type)
                          (== relation-eid v-relation-eid)
                          (= subject-type v-subject-type))))))
      (map (fn [[e a v]] (last v))))))

;(defn relationship-datoms [db subject-type subject-eid relation-eid resource-type resource-eid]
;  (let [sub->res
;        (->> (d/datoms db :eavt
;               :eacl.v7.relationship/subject-type+relation+resource-type+resource
;               [subject-type])
;          (take-while))]))



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

        ;_ (prn 'subject-id subject-id 'resolved subject-eid)
        ;_ (prn 'resource-id resource-id 'resolved resource-eid)

        ;; Get permission paths
        paths        (get-permission-paths db resource-type permission)]

    ; if subject or resource-eid nil, should we throw or return false?

    ; short-circuit on missing subject or resource:
    (if (or (nil? subject-eid) (nil? resource-eid))
      (do
        (when (nil? subject-eid) (log/warn "can? Missing subject-id" subject-id))
        (when (nil? resource-eid) (log/warn "can? Missing resource-id: " resource-id))
        false)

      ;; Check each path - return true on first match
      (boolean
        (->> paths
          (some (fn [path]
                  (case (:type path)
                    :relation
                    ;; Direct relation check
                    (when (= subject-type (:subject-type path))
                      (let [relation-eid (:relation/id path)
                            tuple-attr   :eacl.v7.relationship/subject-type+relation+resource-type+resource
                            ; sort key will have to be known here
                            tuple-val    [subject-type
                                          relation-eid
                                          resource-type resource-eid]]
                        (seq (d/datoms db :eavt subject-eid tuple-attr tuple-val))))

                    :self-permission
                    ;; Self-permission: recursively check if the resource has the target permission
                    (let [target-permission (:target-permission path)]
                      (can? db subject target-permission resource))

                    :arrow
                    ;; Arrow permission check
                    (let [;via-relation      (:via path) ; do we still need this?
                          via-relation-eid  (:relation/id path)
                          intermediate-type (:target-type path)]
                      (if (:target-relation path)
                        ;; Arrow to relation: check if there's any intermediate that connects via the arrow
                        ;; Find intermediates connected to this resource via via-relation (reverse lookup)
                        ;; Use reverse index to find intermediates
                        ;; Check if subject has the target relation to any intermediate
                        ;; FIXED: Extract target-relation-eid from sub-paths
                        (let [target-relation-eid (-> path :sub-paths first :relation/id)]
                          (->> (resource->subjects db resource-type resource-eid via-relation-eid intermediate-type 0)
                            (some (fn [intermediate-eid]
                                    (let [check-tuple [subject-type
                                                       target-relation-eid ; FIXED: was via-relation-eid
                                                       intermediate-type intermediate-eid]]
                                      ; this seems expensive. can we make it cheaper?
                                      ; why do we need another d/datoms here?
                                      (seq (d/datoms db :eavt
                                             subject-eid
                                             :eacl.v7.relationship/subject-type+relation+resource-type+resource
                                             check-tuple)))))))
                        ;; Arrow to permission: recursively check permission
                        (let [target-permission (:target-permission path)]
                          ;; Find intermediates connected to this resource via via-relation (reverse lookup)
                          ;; Use reverse index to find intermediates
                          ;; Recursively check permission on any intermediate
                          (->> (resource->subjects db resource-type resource-eid via-relation-eid intermediate-type 0) ; cursor 0. we would like to pass cursor her.
                            (some (fn [intermediate-eid]
                                    (can? db subject target-permission
                                      (spice-object intermediate-type intermediate-eid))))))))))))))))

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
                                 (map (fn [{:as          path, path-type :type
                                            relation-eid :relation/id}]
                                        (case path-type

                                          :relation
                                          ;; Direct relation - forward traversal
                                          (when (= subject-type (:subject-type path))
                                            (->> (subject->resources db subject-type subject-eid relation-eid resource-type 0) ; we'd like to pass cursor here, but need better cursors.
                                              (map (fn [resource-eid]
                                                     [resource-eid path]))))

                                          :self-permission
                                          ;; Self-permission: recursively find resources where subject has target permission
                                          (let [target-permission (:target-permission path)]
                                            ;; Recursively traverse with target permission to find matching resources
                                            (traverse-permission-path db subject-type subject-eid
                                              target-permission resource-type cursor-eid updated-visited))

                                          :arrow
                                          ;; Arrow permission - traverse using index-range
                                          (let [via-relation      (:via path)
                                                via-relation-eid  (:relation/id path)
                                                intermediate-type (:target-type path)]
                                            (if (:target-relation path)
                                              ;; Arrow to relation: find intermediates with that relation to subject
                                              ;; FIXED: Extract target-relation-eid from sub-paths, use via-relation-eid for second step
                                              (let [target-relation     (:target-relation path)
                                                    target-relation-eid (-> path :sub-paths first :relation/id)
                                                    intermediate-eids   (subject->resources db subject-type subject-eid target-relation-eid intermediate-type 0)] ; we'd like to pass cursor-eid here, but need better cursors.
                                                ;; Now find resources connected to these intermediates using index-range
                                                (let [resource-seqs
                                                      (map (fn [intermediate-eid]
                                                             ;; Use forward index from intermediate to resources
                                                             (->> (subject->resources db intermediate-type intermediate-eid via-relation-eid resource-type cursor-eid)
                                                               (map (fn [resource-eid] [resource-eid path]))))
                                                        intermediate-eids)]
                                                  ;; Use lazy-merge-dedupe-sort-by to combine sorted sequences from all intermediates
                                                  (if (seq resource-seqs)
                                                    (lazy-sort/lazy-fold2-merge-dedupe-sorted-by first resource-seqs)
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
                                                                             (->> (subject->resources db intermediate-type intermediate-eid via-relation-eid resource-type cursor-eid)
                                                                               (map (fn [resource-eid] [resource-eid path]))))))]
                                                  (if (seq resource-seqs)
                                                    (lazy-sort/lazy-fold2-merge-dedupe-sorted-by first resource-seqs)
                                                    []))))))))
                                 (filter some?)             ; not sure if we need this.
                                 (lazy-sort/lazy-fold2-merge-dedupe-sorted-by first))] ; Merge results from all paths
         lazy-path-results)))))

(defn traverse-permission-path-via-subject
  "Subject must be known. Returns lazy seq of resource eids."
  [db subject-type subject-eid path resource-type cursor-eid]
  ;(prn 'traverse-permission-path-via-subject 'cursor-eid cursor-eid)
  (case (:type path)
    :relation
    ;; Direct relation - forward traversal
    (when (= subject-type (:subject-type path))
      (subject->resources db subject-type subject-eid (:relation/id path) resource-type cursor-eid))

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
          via-relation-eid  (:relation/id path)
          intermediate-type (:target-type path)]
      (if (:target-relation path)
        ;; Arrow to relation: find intermediates subject has target-relation to, then find resources via via-relation
        ;; FIXED: Extract target-relation-eid from sub-paths, use via-relation-eid for second step
        (let [target-relation     (:target-relation path)
              target-relation-eid (-> path :sub-paths first :relation/id)]
          ;; Step 1: Find intermediates that subject has target-relation to
          ;; Step 2: Find resources that those intermediates have via-relation to
          (let [resource-seqs
                (->> (subject->resources db subject-type subject-eid target-relation-eid intermediate-type 0)
                  (map (fn [intermediate-eid]
                         ;; Use forward index from intermediate to resources via via-relation
                         (subject->resources db intermediate-type intermediate-eid via-relation-eid resource-type cursor-eid))))]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq resource-seqs)
              (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity resource-seqs)
              [])))
        ;; Arrow to permission
        (let [target-permission    (:target-permission path)

              ;; Get all intermediates recursively
              intermediate-results (traverse-permission-path db subject-type subject-eid
                                     target-permission
                                     intermediate-type
                                     nil
                                     #{})

              resource-seqs        (->> intermediate-results
                                     (map first)
                                     (map (fn [intermediate-eid]
                                            (subject->resources db intermediate-type intermediate-eid via-relation-eid resource-type cursor-eid))))]
          ;; Now find resources connected to these intermediates using index-range

          (->> intermediate-results
            (map first)
            (map (fn [intermediate-eid]
                   (subject->resources db intermediate-type intermediate-eid via-relation-eid resource-type cursor-eid))))

          ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
          (if (seq resource-seqs)
            ; why no dedupe here?
            (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity resource-seqs)
            []))))))

(defn lazy-merged-lookup-resources
  "Lookup resources using lazy merge for multiple paths."
  [db {:keys [subject permission resource/type limit cursor]
       :or   {limit 1000}}]
  (let [{subject-type :type
         subject-id   :id} subject

        subject-eid         (d/entid db subject-id)

        {cursor-resource :resource} cursor
        ?cursor-eid         (:id cursor-resource)           ; can be nil.
        ; we can't validate cursor because may have been deleted since previous call.
        ;_                   (prn 'lazy-merged-lookup-resources 'cursor cursor)

        paths               (get-permission-paths db type permission)
        path-seqs           (->> paths
                              (keep (fn [path]
                                      (let [results (traverse-permission-path-via-subject db
                                                      subject-type subject-eid
                                                      path type ?cursor-eid)]
                                        (when (seq results)
                                          results)))))      ; Already sorted by eid

        lazy-merged-results (if (seq path-seqs)
                              (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
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
      (resource->subjects db resource-type resource-eid (:relation/id path) subject-type 0)) ; could we pass cursor-eid here?

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
        (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
        []))

    :arrow
    ;; Arrow permission - complex traversal using reverse then forward indices
    (let [via-relation      (:via path)
          via-relation-eid  (:relation/id path)
          intermediate-type (:target-type path)]
      (if (:target-relation path)                           ; todo if-let
        ;; Arrow to relation: 
        ;; 1. Use reverse index to find intermediates connected to resource via via-relation
        ;; 2. For each intermediate, use reverse index to find subjects with target-relation to that intermediate
        ;; FIXED: Extract target-relation-eid from sub-paths
        (let [target-relation     (:target-relation path)
              target-relation-eid (-> path :sub-paths first :relation/id)
              ;; Step 1: Find intermediates connected to this resource via via-relation (reverse lookup)
              intermediate-eids   (resource->subjects db resource-type resource-eid via-relation-eid intermediate-type 0)] ; could we pass cursor-eid here?
          ; do we need a filter on cursor-eid here?
          ;; Step 2: For each intermediate, find subjects that have target-relation to it
          (let [subject-seqs
                (map (fn [intermediate-eid]
                       ;; Use reverse index again to find subjects with target-relation to this intermediate
                       (resource->subjects db intermediate-type intermediate-eid target-relation-eid subject-type cursor-eid)) ; we would like to pass cursor-eid, but was reverted.
                  ;(d/index-range db subject-tuple-attr subject-start subject-end)
                  ;(filter #(> % (or cursor-eid 0)))))
                  intermediate-eids)]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq subject-seqs)
              (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
              [])))
        ;; Arrow to permission:
        ;; 1. Use reverse index to find intermediates connected to resource via via-relation
        ;; 2. For each intermediate, recursively find subjects with target-permission on that intermediate
        (let [target-permission (:target-permission path)
              ;; Step 1: Find intermediates connected to this resource via via-relation (reverse lookup)
              intermediate-eids (resource->subjects db resource-type resource-eid via-relation-eid intermediate-type 0)] ; no cursor here for a reason. need better cursors.
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
                                        (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity sub-seqs)
                                        [])))
                               intermediate-eids)]
            ;; Use lazy-merge-dedupe-sort to combine sorted sequences from all intermediates
            (if (seq subject-seqs)
              (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
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
                         (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
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
    {:count  (count limited-results)
     :limit  limit
     :cursor next-cursor}))

(defn count-subjects
  "Returns {:keys [count cursor limit]}, where limit matches input.
  Pass :limit -1 for all results."
  [db {:as   query
       :keys [limit cursor]
       :or   {limit -1}}]
  (prn 'count-subjects 'query query)
  (let [merged-results  (lazy-merged-lookup-subjects db query)
        limited-results (if (>= limit 0)
                          (take limit merged-results)
                          merged-results)
        subjects        (map #(spice-object type %) limited-results)
        last-subject   (last subjects)
        next-cursor     {:subject (or last-subject (:subject cursor))}]
    {:count  (count limited-results)
     :limit  limit
     :cursor next-cursor}))