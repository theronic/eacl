(ns eacl.datomic.impl.indexed
  (:require [clojure.core.cache :as cache]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.lazy-merge-sort :as lazy-sort]))

(def ^:private forward-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def ^:private reverse-relationship-attr
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(defn- inclusive-cursor->exclusive
  [cursor-eid]
  (when cursor-eid
    (dec cursor-eid)))

(defn subject->resources
  [db subject-type subject-eid relation-eid resource-type cursor-resource-eid]
  {:pre [subject-type subject-eid relation-eid resource-type]}
  (let [attr-eid    (d/entid db forward-relationship-attr)
        start-tuple [subject-type
                     relation-eid
                     resource-type
                     (if cursor-resource-eid (inc cursor-resource-eid) 0)]]
    (->> (d/seek-datoms db :eavt subject-eid attr-eid start-tuple)
         (take-while
          (fn [[e a v]]
            (and (== subject-eid e)
                 (== attr-eid a)
                 (= [subject-type relation-eid resource-type]
                    (subvec (vec v) 0 3)))))
         (map (fn [[_ _ v]] (nth v 3))))))

(defn resource->subjects
  [db resource-type resource-eid relation-eid subject-type cursor-subject-eid]
  {:pre [resource-type resource-eid relation-eid subject-type]}
  (let [attr-eid    (d/entid db reverse-relationship-attr)
        start-tuple [resource-type
                     relation-eid
                     subject-type
                     (if cursor-subject-eid (inc cursor-subject-eid) 0)]]
    (->> (d/seek-datoms db :eavt resource-eid attr-eid start-tuple)
         (take-while
          (fn [[e a v]]
            (and (== resource-eid e)
                 (== attr-eid a)
                 (= [resource-type relation-eid subject-type]
                    (subvec (vec v) 0 3)))))
         (map (fn [[_ _ v]] (nth v 3))))))

(defn relation-datoms
  "Returns relation datoms for the exact resource/relation name pair."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (let [start-tuple [resource-type relation-name :a]
          end-tuple   [resource-type relation-name :z]]
      (d/index-range db
                     :eacl.relation/resource-type+relation-name+subject-type
                     start-tuple
                     end-tuple))
    []))

(defn find-relation-def
  "Compatibility helper retained for tests.
  Returns the first matching relation definition, if any."
  [db resource-type relation-name]
  (when-let [datom (first (relation-datoms db resource-type relation-name))]
    (d/pull db
            '[:db/id
              :eacl.relation/subject-type
              :eacl.relation/resource-type
              :eacl.relation/relation-name]
            (:e datom))))

(defn find-permission-defs
  [db resource-type permission-name]
  (let [tuple-val [resource-type permission-name]]
    (->> (d/datoms db :avet :eacl.permission/resource-type+permission-name tuple-val)
         (map :e)
         (map #(d/pull db '[*] %))
         vec)))

(defn resolve-self-relation
  [db resource-type target-relation-name]
  (let [datoms (relation-datoms db resource-type target-relation-name)]
    (if (seq datoms)
      (map (fn [datom]
             {:type :relation
              :name target-relation-name
              :subject-type (nth (:v datom) 2)
              :relation-eid (:e datom)})
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

(defn- permission-paths-cache-key
  [db resource-type permission-name]
  [(.id db) resource-type permission-name])

(defn calc-permission-paths
  "Returns path maps with resolved relation eids, expanding multi-subject
  relation definitions into distinct path variants."
  ([db resource-type permission-name]
   (calc-permission-paths db resource-type permission-name #{}))
  ([db resource-type permission-name visited-perms]
   (let [perm-defs       (find-permission-defs db resource-type permission-name)
         perm-eids       (map :db/id perm-defs)
         updated-visited (reduce conj visited-perms perm-eids)]
     (->> perm-defs
          (mapcat
           (fn [{:as                   perm-def
                 perm-eid              :db/id
                 :eacl.permission/keys [source-relation-name
                                        target-type
                                        target-name]}]
             (assert resource-type "resource-type missing")
             (assert source-relation-name "source-relation-name missing")
             (if (contains? visited-perms perm-eid)
               (do
                 (log/warn "Cycle detected in permission graph"
                           {:permission perm-def
                            :visited visited-perms})
                 [])
               (if (= :self source-relation-name)
                 (case target-type
                   :relation (resolve-self-relation db resource-type target-name)
                   :permission [{:type :self-permission
                                 :target-permission target-name
                                 :resource-type resource-type}])
                 (let [datoms (relation-datoms db resource-type source-relation-name)]
                   (if (seq datoms)
                     (mapcat
                      (fn [datom]
                        (let [intermediate-type (nth (:v datom) 2)
                              via-relation-eid (:e datom)
                              sub-paths (case target-type
                                          :permission (calc-permission-paths
                                                       db intermediate-type target-name updated-visited)
                                          :relation (let [target-datoms
                                                          (relation-datoms db intermediate-type target-name)]
                                                      (if (seq target-datoms)
                                                        (map (fn [target-datom]
                                                               {:type :relation
                                                                :name target-name
                                                                :subject-type (nth (:v target-datom) 2)
                                                                :relation-eid (:e target-datom)})
                                                             target-datoms)
                                                        (do
                                                          (log/warn "Missing target relation definition"
                                                                    {:intermediate-type intermediate-type
                                                                     :target-relation-name target-name})
                                                          []))))]
                          (if (seq sub-paths)
                            [{:type :arrow
                              :via source-relation-name
                              :target-type intermediate-type
                              :via-relation-eid via-relation-eid
                              :target-permission (when (= target-type :permission) target-name)
                              :target-relation (when (= target-type :relation) target-name)
                              :sub-paths sub-paths}]
                            [])))
                      datoms)
                     (do
                       (log/warn "Missing source relation definition"
                                 {:resource-type resource-type
                                  :via-relation-name source-relation-name})
                       [])))))))
          vec))))

(defn get-permission-paths
  [db resource-type permission-name]
  (let [cache @permission-paths-cache
        cache-key (permission-paths-cache-key db resource-type permission-name)]
    (if (cache/has? cache cache-key)
      (do
        (swap! permission-paths-cache cache/hit cache-key)
        (cache/lookup cache cache-key))
      (let [paths (calc-permission-paths db resource-type permission-name)]
        (swap! permission-paths-cache cache/miss cache-key paths)
        paths))))

(defn- extract-cursor-eid
  [cursor v1-key]
  (if (= 2 (:v cursor))
    (:e cursor)
    (get-in cursor [v1-key :id])))

(defn- arrow-via-intermediates
  [intermediate-eids result-fn]
  (let [pairs (keep (fn [intermediate-eid]
                      (let [results (result-fn intermediate-eid)]
                        (when (seq results)
                          {:int-eid intermediate-eid
                           :results results})))
                    intermediate-eids)
        min-int (:int-eid (first pairs))
        result-seqs (map :results pairs)]
    {:results (if (seq result-seqs)
                (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity result-seqs)
                [])
     :!state (when min-int (volatile! min-int))}))

(defn- build-v2-cursor
  [cursor last-eid path-results v1-key]
  {:v 2
   :e (or last-eid (:e cursor) (get-in cursor [v1-key :id]))
   :p (into (or (:p cursor) {})
            (keep (fn [{:keys [idx !state]}]
                    (when-let [v (and !state @!state)]
                      [idx v])))
            path-results)})

(defn direct-match-datoms-in-relationship-index
  [db subject-type subject-eid relation-eid resource-type resource-eid]
  (d/datoms db
            :eavt
            subject-eid
            forward-relationship-attr
            [subject-type relation-eid resource-type resource-eid]))

(defn- matching-relation-sub-paths
  [sub-paths subject-type]
  (filter #(and (= :relation (:type %))
                (= subject-type (:subject-type %)))
          sub-paths))

(declare traverse-permission-path)

(defn traverse-permission-path-via-subject
  [db subject-type subject-eid path resource-type cursor-eid intermediate-cursor-eid visited-paths]
  (case (:type path)
    :relation
    {:results (when (= subject-type (:subject-type path))
                (subject->resources db
                                    subject-type
                                    subject-eid
                                    (:relation-eid path)
                                    resource-type
                                    cursor-eid))
     :!state nil}

    :self-permission
    {:results (traverse-permission-path db
                                        subject-type
                                        subject-eid
                                        (:target-permission path)
                                        resource-type
                                        cursor-eid
                                        (or visited-paths #{}))
     :!state nil}

    :arrow
    (let [intermediate-type (:target-type path)
          via-relation-eid (:via-relation-eid path)
          inclusive-intermediate-cursor (inclusive-cursor->exclusive intermediate-cursor-eid)]
      (if (:target-relation path)
        (let [intermediate-seqs
              (->> (matching-relation-sub-paths (:sub-paths path) subject-type)
                   (map (fn [sub-path]
                          (subject->resources db
                                              subject-type
                                              subject-eid
                                              (:relation-eid sub-path)
                                              intermediate-type
                                              inclusive-intermediate-cursor)))
                   (filter seq))
              intermediate-eids (if (seq intermediate-seqs)
                                  (lazy-sort/lazy-fold2-merge-dedupe-sorted-by
                                   identity
                                   intermediate-seqs)
                                  [])]
          (arrow-via-intermediates intermediate-eids
                                   (fn [intermediate-eid]
                                     (subject->resources db
                                                         intermediate-type
                                                         intermediate-eid
                                                         via-relation-eid
                                                         resource-type
                                                         cursor-eid))))
        (let [target-permission (:target-permission path)
              intermediate-eids (traverse-permission-path db
                                                          subject-type
                                                          subject-eid
                                                          target-permission
                                                          intermediate-type
                                                          inclusive-intermediate-cursor
                                                          (or visited-paths #{}))]
          (arrow-via-intermediates intermediate-eids
                                   (fn [intermediate-eid]
                                     (subject->resources db
                                                         intermediate-type
                                                         intermediate-eid
                                                         via-relation-eid
                                                         resource-type
                                                         cursor-eid))))))))

(defn traverse-permission-path
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-eid visited-paths]
   (let [path-key [subject-type subject-eid permission-name resource-type]]
     (if (contains? visited-paths path-key)
       []
       (let [paths (get-permission-paths db resource-type permission-name)
             next-visited (conj visited-paths path-key)
             path-seqs (->> paths
                            (map (fn [path]
                                   (:results
                                    (traverse-permission-path-via-subject db
                                                                          subject-type
                                                                          subject-eid
                                                                          path
                                                                          resource-type
                                                                          cursor-eid
                                                                          nil
                                                                          next-visited))))
                            (filter seq))]
         (if (seq path-seqs)
           (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
           []))))))

(defn traverse-permission-path-reverse
  [db resource-type resource-eid path subject-type cursor-eid intermediate-cursor-eid visited-paths]
  (case (:type path)
    :relation
    {:results (when (= subject-type (:subject-type path))
                (resource->subjects db
                                    resource-type
                                    resource-eid
                                    (:relation-eid path)
                                    subject-type
                                    cursor-eid))
     :!state nil}

    :self-permission
    {:results (let [target-paths (get-permission-paths db resource-type (:target-permission path))
                    path-seqs (->> target-paths
                                   (map (fn [target-path]
                                          (:results
                                           (traverse-permission-path-reverse db
                                                                             resource-type
                                                                             resource-eid
                                                                             target-path
                                                                             subject-type
                                                                             cursor-eid
                                                                             nil
                                                                             (or visited-paths #{})))))
                                   (filter seq))]
                (if (seq path-seqs)
                  (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
                  []))
     :!state nil}

    :arrow
    (let [intermediate-type (:target-type path)
          via-relation-eid (:via-relation-eid path)
          inclusive-intermediate-cursor (inclusive-cursor->exclusive intermediate-cursor-eid)
          intermediate-eids (resource->subjects db
                                                resource-type
                                                resource-eid
                                                via-relation-eid
                                                intermediate-type
                                                inclusive-intermediate-cursor)]
      (if (:target-relation path)
        (let [matching-sub-paths (matching-relation-sub-paths (:sub-paths path) subject-type)]
          (arrow-via-intermediates intermediate-eids
                                   (fn [intermediate-eid]
                                     (let [subject-seqs (->> matching-sub-paths
                                                             (map (fn [sub-path]
                                                                    (resource->subjects db
                                                                                        intermediate-type
                                                                                        intermediate-eid
                                                                                        (:relation-eid sub-path)
                                                                                        subject-type
                                                                                        cursor-eid)))
                                                             (filter seq))]
                                       (if (seq subject-seqs)
                                         (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
                                         [])))))
        (let [target-permission (:target-permission path)]
          (arrow-via-intermediates intermediate-eids
                                   (fn [intermediate-eid]
                                     (let [paths (get-permission-paths db intermediate-type target-permission)
                                           subject-seqs (->> paths
                                                             (map (fn [sub-path]
                                                                    (:results
                                                                     (traverse-permission-path-reverse db
                                                                                                       intermediate-type
                                                                                                       intermediate-eid
                                                                                                       sub-path
                                                                                                       subject-type
                                                                                                       cursor-eid
                                                                                                       nil
                                                                                                       (or visited-paths #{})))))
                                                             (filter seq))]
                                       (if (seq subject-seqs)
                                         (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
                                         [])))))))))

(defn can?
  [db subject permission resource]
  (let [subject-type (:type subject)
        subject-eid  (d/entid db (:id subject))
        resource-type (:type resource)
        resource-eid  (d/entid db (:id resource))
        paths         (get-permission-paths db resource-type permission)]
    (if (or (nil? subject-eid) (nil? resource-eid))
      false
      (boolean
       (some
        (fn [path]
          (case (:type path)
            :relation
            (when (= subject-type (:subject-type path))
              (seq
               (direct-match-datoms-in-relationship-index db
                                                          subject-type
                                                          subject-eid
                                                          (:relation-eid path)
                                                          resource-type
                                                          resource-eid)))

            :self-permission
            (can? db subject (:target-permission path) resource)

            :arrow
            (let [intermediate-type (:target-type path)
                  via-relation-eid (:via-relation-eid path)
                  intermediate-eids (resource->subjects db
                                                        resource-type
                                                        resource-eid
                                                        via-relation-eid
                                                        intermediate-type
                                                        nil)]
              (if (:target-relation path)
                (let [matching-sub-paths
                      (matching-relation-sub-paths (:sub-paths path) subject-type)]
                  (some (fn [intermediate-eid]
                          (some (fn [sub-path]
                                  (seq
                                   (direct-match-datoms-in-relationship-index db
                                                                              subject-type
                                                                              subject-eid
                                                                              (:relation-eid sub-path)
                                                                              intermediate-type
                                                                              intermediate-eid)))
                                matching-sub-paths))
                        intermediate-eids))
                (some (fn [intermediate-eid]
                        (can? db subject (:target-permission path)
                              (spice-object intermediate-type intermediate-eid)))
                      intermediate-eids)))))
        paths)))))

(def ^:private forward-direction
  {:anchor-key :subject
   :perm-type-fn (fn [query] (:resource/type query))
   :result-type-fn (fn [query] (:resource/type query))
   :traverse-fn traverse-permission-path-via-subject
   :v1-cursor-key :resource})

(def ^:private reverse-direction
  {:anchor-key :resource
   :perm-type-fn (fn [query] (:type (:resource query)))
   :result-type-fn (fn [query] (:subject/type query))
   :traverse-fn traverse-permission-path-reverse
   :v1-cursor-key :subject})

(defn- lazy-merged-lookup
  [db direction query]
  (let [{:keys [anchor-key traverse-fn v1-cursor-key perm-type-fn]} direction
        anchor      (get query anchor-key)
        anchor-type (:type anchor)
        anchor-eid  (d/entid db (:id anchor))
        cursor      (:cursor query)
        cursor-eid  (extract-cursor-eid cursor v1-cursor-key)
        permission  (:permission query)
        perm-type   (perm-type-fn query)
        result-type-key (if (= anchor-key :subject) :resource/type :subject/type)
        result-type (get query result-type-key)
        paths       (get-permission-paths db perm-type permission)
        path-results (vec
                      (->> paths
                           (map-indexed
                            (fn [idx path]
                              (let [intermediate-cursor-eid (get-in cursor [:p idx])
                                    {:keys [results !state]}
                                    (if anchor-eid
                                      (traverse-fn db
                                                   anchor-type
                                                   anchor-eid
                                                   path
                                                   result-type
                                                   cursor-eid
                                                   intermediate-cursor-eid
                                                   #{})
                                      {:results [] :!state nil})]
                                {:idx idx
                                 :results results
                                 :!state !state})))
                           (filter (comp seq :results))))]
    {:results (if (seq path-results)
                (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity (map :results path-results))
                [])
     :path-results path-results}))

(defn- lookup
  [db direction query]
  (let [{:keys [result-type-fn v1-cursor-key]} direction
        {:keys [limit cursor] :or {limit 1000}} query
        {:keys [results path-results]} (lazy-merged-lookup db direction query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        result-type (result-type-fn query)
        items       (doall (map #(spice-object result-type %) limited-results))
        last-eid    (:id (last items))]
    {:data items
     :cursor (build-v2-cursor cursor last-eid path-results v1-cursor-key)}))

(defn lookup-resources
  [db query]
  (lookup db forward-direction query))

(defn lookup-subjects
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (lookup db reverse-direction query))

(defn count-resources
  [db {:as query :keys [limit cursor] :or {limit -1}}]
  (let [{:keys [results path-results]} (lazy-merged-lookup db forward-direction query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        counted (doall limited-results)
        last-eid (last counted)]
    {:count (count counted)
     :limit limit
     :cursor (build-v2-cursor cursor last-eid path-results :resource)}))

(defn count-subjects
  [db {:as query :keys [limit cursor] :or {limit -1}}]
  (let [{:keys [results path-results]} (lazy-merged-lookup db reverse-direction query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        counted (doall limited-results)
        last-eid (last counted)]
    {:count (count counted)
     :limit limit
     :cursor (build-v2-cursor cursor last-eid path-results :subject)}))
