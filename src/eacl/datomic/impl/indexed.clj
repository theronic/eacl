(ns eacl.datomic.impl.indexed
  ;; Indexed implementation of EACL
  (:require [datomic.api :as d]
            [eacl.lazy-merge-sort :as lazy-sort]
            [eacl.core :refer [spice-object]]
            [clojure.tools.logging :as log]
            [clojure.core.cache :as cache]))

(defn extract-resource-id-from-rel-tuple-datom [{:as _datom, v :v}]
  (let [[_subject-type _subject-eid _relation-name _resource-type resource-eid] v]
    resource-eid))

(defn extract-subject-id-from-reverse-rel-tuple-datom [{:as _datom, v :v}]
  (let [[_resource-type _resource-eid _relation-name _subject-type subject-eid] v]
    subject-eid))

(defn- tracking-min
  "Wraps coll in a lazy seq that tracks the minimum of `v` into volatile `!min`.
  Elements pass through unchanged. Side-channel for cursor tree."
  [!min v coll]
  (map (fn [x]
         (vswap! !min (fn [cur] (if cur (min cur v) v)))
         x)
    coll))

(defn- extract-cursor-eid
  "Extracts the entity eid from a v1 or v2 cursor.
  v1-key is :resource for forward lookups, :subject for reverse."
  [cursor v1-key]
  (if (= 2 (:v cursor))
    (:e cursor)
    (get-in cursor [v1-key :id])))

(defn- arrow-via-intermediates
  "For each intermediate eid, calls result-fn to get a lazy seq of result eids.
  Wraps each per-intermediate seq with tracking-min to record the minimum
  contributing intermediate into a volatile. Merges all result sequences.
  Returns {:results <lazy-merged-seq>, :!state <volatile>}."
  [intermediate-eids result-fn]
  (let [!min-int    (volatile! nil)
        result-seqs (map (fn [intermediate-eid]
                           (->> (result-fn intermediate-eid)
                             (tracking-min !min-int intermediate-eid)))
                      intermediate-eids)]
    {:results (if (seq result-seqs)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity result-seqs)
               [])
     :!state !min-int}))

(defn- build-v2-cursor
  "Builds a v2 cursor with carry-forward semantics.
  Preserves previous :p entries, overwrites with new volatile state from path-results."
  [cursor last-eid path-results v1-key]
  {:v 2
   :e (or last-eid (:e cursor) (get-in cursor [v1-key :id]))
   :p (into (or (:p cursor) {})
        (keep (fn [{:keys [idx !state]}]
                (when-let [v (and !state @!state)]
                  [idx v])))
        path-results)})

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

(defn relation-datoms
  "Returns a lazy seq of raw datoms for the given resource-type and relation-name.
  Callers unpack :e (eid) and :v (subject-type) as needed; seq is realized on consumption."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (let [start-tuple [resource-type relation-name :a]
          end-tuple   [resource-type relation-name :z]]
      (d/index-range db :eacl.relation/resource-type+relation-name+subject-type start-tuple end-tuple))
    (do
      (log/warn 'relation-datoms 'called-with resource-type relation-name)
      (throw (Exception. (str 'relation-datoms 'called-with resource-type relation-name)))
      [])))

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
  (let [datoms (relation-datoms db resource-type target-relation-name)]
    (if (seq datoms)
      (map (fn [datom]
             {:type         :relation
              :name         target-relation-name
              :subject-type (nth (:v datom) 2)
              :relation-eid (:e datom)})                    ; For v7 compatibility
        datoms)
      (do
        (log/warn "Missing Relation definition"
          {:resource-type resource-type
           :relation-name target-relation-name})
        []))))

(def permission-paths-cache
  (atom (cache/lru-cache-factory {} :threshold 1000)))

(defn evict-permission-paths-cache! []
  (reset! permission-paths-cache (cache/lru-cache-factory {} :threshold 1000)))

(defn calc-permission-paths
  "Recursively builds paths granting permission-name on resource-type.
  Returns vector of path maps, where each path is:
  {:type :relation, :name keyword, :subject-type keyword, :relation-eid long} for direct path, or
  {:type :arrow, :via keyword, :target-type keyword, :sub-paths [paths], :via-relation-eid long} for arrows.
  Compatible with v6 (uses :subject-type kw) and v7 (uses :relation-eid)."
  ([db resource-type permission-name]
   (calc-permission-paths db resource-type permission-name #{}))
  ([db resource-type permission-name visited-perms]
   (let [perm-defs       (find-permission-defs db resource-type permission-name)
         perm-eids       (map :db/id perm-defs)
         updated-visited (reduce conj visited-perms perm-eids)]
     (->> perm-defs
       (mapcat (fn [{:as                   perm-def
                     perm-eid              :db/id
                     :eacl.permission/keys [source-relation-name
                                            target-type
                                            target-name]}]
                 (assert resource-type "resource-type missing")
                 (assert source-relation-name "source-relation-name missing")

                 ;; Cycle detection: check if we've already visited this permission
                 (if (contains? visited-perms perm-eid)
                   (do
                     (log/warn "Cycle detected: " perm-def " in " visited-perms)
                     [])
                   (if (= :self source-relation-name)
                     (case target-type
                       :relation (resolve-self-relation db resource-type target-name)
                       :permission [{:type              :self-permission
                                     :target-permission target-name
                                     :resource-type     resource-type}])
                     (let [datoms (relation-datoms db resource-type source-relation-name)]
                       (if (seq datoms)
                         (mapcat (fn [datom]
                                   (let [intermediate-type (nth (:v datom) 2)
                                         via-relation-eid  (:e datom)
                                         sub-paths         (case target-type
                                                             ;; Recursive permission call with cycle detection
                                                             :permission (calc-permission-paths db intermediate-type target-name updated-visited)
                                                             ;; Direct relation - build the path with proper type resolution
                                                             :relation (let [target-datoms (relation-datoms db intermediate-type target-name)]
                                                                         (if (seq target-datoms)
                                                                           (map (fn [target-datom]
                                                                                  {:type         :relation
                                                                                   :name         target-name
                                                                                   :subject-type (nth (:v target-datom) 2)
                                                                                   :relation-eid (:e target-datom)}) ; For v7
                                                                             target-datoms)
                                                                           (do
                                                                             (log/warn "Missing Relation definition for arrow target relation"
                                                                               {:intermediate-type    intermediate-type
                                                                                :target-relation-name target-name})
                                                                             []))))]
                                     (if (seq sub-paths)
                                       [{:type              :arrow
                                         :via               source-relation-name
                                         :target-type       intermediate-type
                                         :via-relation-eid  via-relation-eid ; For v7
                                         :target-permission (when (= target-type :permission) target-name)
                                         :target-relation   (when (= target-type :relation) target-name)
                                         :sub-paths         sub-paths}]
                                       [])))
                           datoms)
                         (do
                           (log/warn "Missing Relation definition for via relation"
                             {:resource-type     resource-type
                              :via-relation-name source-relation-name})
                           [])))))))
       (vec)))))

(defn get-permission-paths
  "Recursively builds paths granting permission-name on resource-type.
  Returns vector of path maps.
  Cached."
  [db resource-type permission-name]
  (let [cache @permission-paths-cache
        k     [resource-type permission-name]]
    (if (cache/has? cache k)
      (do
        (swap! permission-paths-cache cache/hit k)
        (cache/lookup cache k))
      (let [res (calc-permission-paths db resource-type permission-name)]
        (swap! permission-paths-cache cache/miss k res)
        res))))

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
                 (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity))))
        ;(lazy-merge-dedupe-sort)
        ; do we need dedupe here?
        (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity)))))

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

(declare traverse-permission-path)

(defn direct-match-datoms-in-relationship-index
  [db subject-type subject-eid relation-name resource-type resource-eid]
  (d/datoms db
    :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
    subject-type subject-eid relation-name resource-type resource-eid))

(defn traverse-permission-path-via-subject
  "Subject must be known. Returns {:results <lazy-seq-of-eids>, :!state <volatile!-or-nil>}.
  intermediate-cursor-eid is the minimum intermediate eid to start scanning from (for cursor tree optimization).
  visited-paths is a set of [subject-type subject-eid permission resource-type] for cycle detection."
  [db subject-type subject-eid path resource-type cursor-eid intermediate-cursor-eid visited-paths]
  (case (:type path)
    :relation
    ;; Direct relation - forward traversal. No intermediates.
    {:results (when (= subject-type (:subject-type path))
               (let [tuple-attr  :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                     start-tuple [subject-type subject-eid (:name path) resource-type (or cursor-eid 0)]
                     end-tuple   [subject-type subject-eid (:name path) resource-type Long/MAX_VALUE]]
                 (->> (d/index-range db tuple-attr start-tuple end-tuple)
                   (map extract-resource-id-from-rel-tuple-datom)
                   (filter (fn [resource-eid]
                             (and resource-eid (> resource-eid (or cursor-eid 0))))))))
     :!state nil}

    :self-permission
    ;; Self-permission: recursively get resources where subject has target permission. No intermediates at this level.
    {:results (let [target-permission (:target-permission path)]
               (->> (traverse-permission-path db subject-type subject-eid target-permission resource-type cursor-eid
                      (or visited-paths #{}))
                 (filter (fn [resource-eid]
                           (and resource-eid (> resource-eid (or cursor-eid 0)))))))
     :!state nil}

    :arrow
    ;; Arrow permission - with intermediate tracking via arrow-via-intermediates
    (let [via-relation      (:via path)
          intermediate-type (:target-type path)]
      (if (:target-relation path)
        ;; Arrow to relation
        (let [target-relation         (:target-relation path)
              intermediate-tuple-attr :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
              intermediate-start      [subject-type subject-eid target-relation intermediate-type
                                       (or intermediate-cursor-eid 0)]
              intermediate-end        [subject-type subject-eid target-relation intermediate-type Long/MAX_VALUE]
              intermediate-eids       (->> (d/index-range db intermediate-tuple-attr intermediate-start intermediate-end)
                                        (map extract-resource-id-from-rel-tuple-datom)
                                        (filter some?))]
          (arrow-via-intermediates intermediate-eids
            (fn [intermediate-eid]
              (let [start [intermediate-type intermediate-eid via-relation resource-type (or cursor-eid 0)]
                    end   [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                (->> (d/index-range db :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                       start end)
                  (map extract-resource-id-from-rel-tuple-datom)
                  (filter some?)
                  (filter #(> % (or cursor-eid 0))))))))
        ;; Arrow to permission
        (let [target-permission    (:target-permission path)
              intermediate-eids    (->> (traverse-permission-path db subject-type subject-eid
                                          target-permission intermediate-type nil
                                          (or visited-paths #{}))
                                     (drop-while #(< % (or intermediate-cursor-eid 0))))]
          (arrow-via-intermediates intermediate-eids
            (fn [intermediate-eid]
              (let [start [intermediate-type intermediate-eid via-relation resource-type (or cursor-eid 0)]
                    end   [intermediate-type intermediate-eid via-relation resource-type Long/MAX_VALUE]]
                (->> (d/index-range db :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
                       start end)
                  (map extract-resource-id-from-rel-tuple-datom)
                  (filter some?)
                  (filter #(> % (or cursor-eid 0))))))))))))

(defn traverse-permission-path
  "Returns lazy sorted seq of resource eids where subject has permission.
  Thin wrapper: gets all permission paths, traverses each via
  traverse-permission-path-via-subject, and merges results.
  Includes cycle detection via visited-paths."
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-eid visited-paths]
   (let [path-key [subject-type subject-eid permission-name resource-type]]
     (if (contains? visited-paths path-key)
       []
       (let [updated-visited (conj visited-paths path-key)
             paths           (get-permission-paths db resource-type permission-name)
             path-seqs       (->> paths
                               (map (fn [path]
                                      (:results (traverse-permission-path-via-subject
                                                  db subject-type subject-eid
                                                  path resource-type cursor-eid nil
                                                  updated-visited))))
                               (filter seq))]
         (if (seq path-seqs)
           (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
           []))))))

(defn traverse-permission-path-reverse
  "Resource must be known. Returns {:results <lazy-seq-of-subject-eids>, :!state <volatile!-or-nil>}.
  intermediate-cursor-eid is the minimum intermediate eid to start scanning from (for cursor tree optimization).
  visited-paths is a set of [resource-type resource-eid permission subject-type] for cycle detection."
  [db resource-type resource-eid path subject-type cursor-eid intermediate-cursor-eid visited-paths]
  (case (:type path)
    :relation
    ;; Direct relation - reverse traversal from resource to subjects. No intermediates.
    {:results (when (= subject-type (:subject-type path))
               (let [reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
                     start-tuple        [resource-type resource-eid (:name path) subject-type 0]
                     end-tuple          [resource-type resource-eid (:name path) subject-type Long/MAX_VALUE]]
                 (->> (d/index-range db reverse-tuple-attr start-tuple end-tuple)
                   (map extract-subject-id-from-reverse-rel-tuple-datom)
                   (filter (fn [subject-eid]
                             (and subject-eid (> subject-eid (or cursor-eid 0))))))))
     :!state nil}

    :self-permission
    ;; Self-permission: recursively find subjects that have target permission on this resource. No intermediates.
    {:results (let [target-permission (:target-permission path)
                    target-paths      (get-permission-paths db resource-type target-permission)
                    path-seqs         (->> target-paths
                                        (map (fn [target-path]
                                               (:results (traverse-permission-path-reverse db resource-type resource-eid
                                                           target-path subject-type cursor-eid nil
                                                           (or visited-paths #{})))))
                                        (filter seq))]
               (if (seq path-seqs)
                 (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
                 []))
     :!state nil}

    :arrow
    ;; Arrow permission - with intermediate tracking via arrow-via-intermediates
    (let [via-relation      (:via path)
          intermediate-type (:target-type path)]
      (if (:target-relation path)
        ;; Arrow to relation:
        ;; 1. Use reverse index to find intermediates connected to resource via via-relation
        ;; 2. For each intermediate, find subjects with target-relation to that intermediate
        ;; Arrow to relation (reverse)
        (let [target-relation    (:target-relation path)
              reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
              reverse-start      [resource-type resource-eid via-relation intermediate-type
                                  (or intermediate-cursor-eid 0)]
              reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
              intermediate-eids  (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                                   (map extract-subject-id-from-reverse-rel-tuple-datom))]
          (arrow-via-intermediates intermediate-eids
            (fn [intermediate-eid]
              (let [start [intermediate-type intermediate-eid target-relation subject-type 0]
                    end   [intermediate-type intermediate-eid target-relation subject-type Long/MAX_VALUE]]
                (->> (d/index-range db reverse-tuple-attr start end)
                  (map extract-subject-id-from-reverse-rel-tuple-datom)
                  (filter #(> % (or cursor-eid 0))))))))
        ;; Arrow to permission (reverse)
        (let [target-permission  (:target-permission path)
              reverse-tuple-attr :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
              reverse-start      [resource-type resource-eid via-relation intermediate-type
                                  (or intermediate-cursor-eid 0)]
              reverse-end        [resource-type resource-eid via-relation intermediate-type Long/MAX_VALUE]
              intermediate-eids  (->> (d/index-range db reverse-tuple-attr reverse-start reverse-end)
                                   (map extract-subject-id-from-reverse-rel-tuple-datom))]
          (arrow-via-intermediates intermediate-eids
            (fn [intermediate-eid]
              (let [paths    (get-permission-paths db intermediate-type target-permission)
                    sub-seqs (->> paths
                               (map (fn [sub-path]
                                      (:results (traverse-permission-path-reverse db intermediate-type intermediate-eid
                                                  sub-path subject-type cursor-eid nil
                                                  (or visited-paths #{})))))
                               (filter seq))]
                (if (seq sub-seqs)
                  (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity sub-seqs)
                  [])))))))))

;;; --- Direction-parameterized lookup ---

(def ^:private forward-direction
  {:anchor-key     :subject
   :perm-type-fn   (fn [query] (:resource/type query))
   :result-type-fn (fn [query] (:resource/type query))
   :traverse-fn    traverse-permission-path-via-subject
   :v1-cursor-key  :resource})

(def ^:private reverse-direction
  {:anchor-key     :resource
   :perm-type-fn   (fn [query] (:type (:resource query)))
   :result-type-fn (fn [query] (:subject/type query))
   :traverse-fn    traverse-permission-path-reverse
   :v1-cursor-key  :subject})

(defn- lazy-merged-lookup
  "Lookup entities using lazy merge for multiple paths, parameterized by direction.
  Returns {:results <lazy-seq>, :path-results <vec-of-{:idx :results :!state}>}."
  [db direction query]
  (let [{:keys [anchor-key traverse-fn v1-cursor-key perm-type-fn]} direction
        anchor     (get query anchor-key)
        {anchor-type :type, anchor-id :id} anchor
        anchor-eid (d/entid db anchor-id)
        cursor     (:cursor query)
        cursor-eid (extract-cursor-eid cursor v1-cursor-key)
        permission (:permission query)
        perm-type  (perm-type-fn query)
        paths      (get-permission-paths db perm-type permission)
        result-type-key (if (= anchor-key :subject) :resource/type :subject/type)
        result-type (get query result-type-key)
        path-results (vec (->> paths
                            (map-indexed
                              (fn [idx path]
                                (let [ic-eid (get-in cursor [:p idx])
                                      {:keys [results !state]}
                                      (traverse-fn db anchor-type anchor-eid
                                        path result-type cursor-eid ic-eid #{})]
                                  {:idx idx :results results :!state !state})))
                            (filter (comp seq :results))))]
    {:results (if (seq path-results)
               (lazy-sort/lazy-fold2-merge-dedupe-sorted-by
                 identity (map :results path-results))
               [])
     :path-results path-results}))

(defn- lookup
  "Core lookup implementation, parameterized by direction.
  Returns {:data <vec>, :cursor <v2-cursor>}."
  [db direction query]
  (let [{:keys [v1-cursor-key result-type-fn]} direction
        {:keys [limit cursor] :or {limit 1000}} query
        {:keys [results path-results]} (lazy-merged-lookup db direction query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        result-type (result-type-fn query)
        items       (doall (map #(spice-object result-type %) limited-results))
        last-eid    (:id (last items))]
    {:data   items
     :cursor (build-v2-cursor cursor last-eid path-results v1-cursor-key)}))

(defn lookup-resources
  "Default :limit 1000.
  Pass :limit -1 for all results (or any negative value)."
  [db query]
  (lookup db forward-direction query))

(defn lookup-subjects
  "Returns subjects that can access the given resource with the specified permission.
  Pass :limit -1 for all results (can be slow)."
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (lookup db reverse-direction query))

(defn count-resources
  "Returns {:keys [count cursor limit]}, where limit matches input.
  Pass :limit -1 for all results."
  [db {:as   query
       :keys [limit cursor]
       :or   {limit -1}}]
  (let [{:keys [results path-results]} (lazy-merged-lookup db forward-direction query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        counted         (doall limited-results)
        last-eid        (last counted)]
    {:count  (clojure.core/count counted)
     :limit  limit
     :cursor (build-v2-cursor cursor last-eid path-results :resource)}))