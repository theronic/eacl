(ns eacl.engine.indexed
  (:require [eacl.backend.spi :as spi]
            [eacl.core :refer [spice-object]]
            [eacl.lazy-merge-sort :as lazy-sort]
            #?@(:clj [[clojure.tools.logging :as log]])))

(defn- warn
  [message data]
  #?(:clj
     (log/warn message data)
     :cljs
     (.warn js/console (str message " " (pr-str data)))))

(defn inclusive-cursor->exclusive
  [cursor-id]
  (when cursor-id
    (dec cursor-id)))

(defn permission-paths-cache-key
  [backend resource-type permission-name]
  [(spi/cache-stamp backend) resource-type permission-name])

(defn evict-permission-paths-cache!
  [cache-atom]
  (reset! cache-atom {}))

(defn get-permission-paths
  [cache-atom calc-permission-paths-fn backend resource-type permission-name]
  (let [cache-key (permission-paths-cache-key backend resource-type permission-name)]
    (if-let [cached (get @cache-atom cache-key)]
      cached
      (let [paths (calc-permission-paths-fn backend resource-type permission-name)]
        (swap! cache-atom
               (fn [cache]
                 (let [updated (assoc cache cache-key paths)]
                   (if (> (count updated) 1000)
                     {cache-key paths}
                     updated))))
        paths))))

(defn resolve-self-relation
  [backend resource-type target-relation-name]
  (let [defs (spi/relation-defs backend resource-type target-relation-name)]
    (if (seq defs)
      (mapv (fn [{:keys [relation-id subject-type]}]
              {:type :relation
               :name target-relation-name
               :subject-type subject-type
               :relation-id relation-id})
            defs)
      (do
        (warn "Missing Relation definition"
              {:resource-type resource-type
               :relation-name target-relation-name})
        []))))

(defn calc-permission-paths
  ([backend resource-type permission-name]
   (calc-permission-paths backend resource-type permission-name #{}))
  ([backend resource-type permission-name visited-perms]
   (let [perm-defs       (spi/permission-defs backend resource-type permission-name)
         perm-ids        (map :permission-id perm-defs)
         updated-visited (reduce conj visited-perms perm-ids)]
     (->> perm-defs
          (mapcat
           (fn [{:keys [permission-id
                        source-relation-name
                        target-type
                        target-name]}]
             (cond
               (contains? visited-perms permission-id)
               (do
                 (warn "Cycle detected in permission graph"
                       {:permission-id permission-id
                        :visited visited-perms})
                 [])

               (= :self source-relation-name)
               (case target-type
                 :relation (resolve-self-relation backend resource-type target-name)
                 :permission [{:type :self-permission
                               :target-permission target-name
                               :resource-type resource-type
                               :permission-id permission-id}]
                 [])

               :else
               (let [via-defs (spi/relation-defs backend resource-type source-relation-name)]
                 (if (seq via-defs)
                   (mapcat
                    (fn [{:keys [relation-id subject-type]}]
                      (let [intermediate-type subject-type
                            sub-paths (if (= target-type :permission)
                                        (calc-permission-paths backend intermediate-type target-name updated-visited)
                                        (let [target-defs (spi/relation-defs backend intermediate-type target-name)]
                                          (if (seq target-defs)
                                            (mapv (fn [{:keys [relation-id subject-type]}]
                                                    {:type :relation
                                                     :name target-name
                                                     :subject-type subject-type
                                                     :relation-id relation-id})
                                                  target-defs)
                                            (do
                                              (warn "Missing target relation definition"
                                                    {:intermediate-type intermediate-type
                                                     :target-relation-name target-name})
                                              []))))]
                        (if (seq sub-paths)
                          [{:type :arrow
                            :via source-relation-name
                            :target-type intermediate-type
                            :via-relation-id relation-id
                            :target-permission (when (= target-type :permission) target-name)
                            :target-relation (when (= target-type :relation) target-name)
                            :sub-paths sub-paths}]
                          [])))
                   via-defs)
                   (do
                     (warn "Missing source relation definition"
                           {:resource-type resource-type
                            :via-relation-name source-relation-name})
                     []))))))
          vec))))

(defn extract-cursor-id
  [cursor v1-key]
  (if (= 2 (:v cursor))
    (:e cursor)
    (get-in cursor [v1-key :id])))

(defn arrow-via-intermediates
  [intermediate-ids result-fn]
  (let [pairs (keep (fn [intermediate-id]
                      (let [results (result-fn intermediate-id)]
                        (when (seq results)
                          {:intermediate-id intermediate-id
                           :results results})))
                    intermediate-ids)
        min-intermediate (:intermediate-id (first pairs))
        result-seqs      (map :results pairs)]
    {:results (if (seq result-seqs)
                (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity result-seqs)
                [])
     :!state (when min-intermediate (volatile! min-intermediate))}))

(defn build-v2-cursor
  [cursor last-id path-results v1-key]
  {:v 2
   :e (or last-id (:e cursor) (get-in cursor [v1-key :id]))
   :p (into (or (:p cursor) {})
            (keep (fn [{:keys [idx !state]}]
                    (when-let [v (and !state @!state)]
                      [idx v])))
            path-results)})

(defn matching-relation-sub-paths
  [sub-paths subject-type]
  (filter #(and (= :relation (:type %))
                (= subject-type (:subject-type %)))
          sub-paths))

(declare traverse-permission-path)

(defn traverse-permission-path-via-subject
  [backend get-permission-paths-fn subject-type subject-id path resource-type cursor-id intermediate-cursor-id visited-paths]
  (case (:type path)
    :relation
    {:results (when (= subject-type (:subject-type path))
                (spi/subject->resources backend
                                        subject-type
                                        subject-id
                                        (:relation-id path)
                                        resource-type
                                        cursor-id))
     :!state nil}

    :self-permission
    {:results (traverse-permission-path backend
                                        get-permission-paths-fn
                                        subject-type
                                        subject-id
                                        (:target-permission path)
                                        resource-type
                                        cursor-id
                                        (or visited-paths #{}))
     :!state nil}

    :arrow
    (let [intermediate-type (:target-type path)
          via-relation-id (:via-relation-id path)
          inclusive-intermediate-cursor (inclusive-cursor->exclusive intermediate-cursor-id)]
      (if (:target-relation path)
        (let [intermediate-seqs
              (->> (matching-relation-sub-paths (:sub-paths path) subject-type)
                   (map (fn [sub-path]
                          (spi/subject->resources backend
                                                  subject-type
                                                  subject-id
                                                  (:relation-id sub-path)
                                                  intermediate-type
                                                  inclusive-intermediate-cursor)))
                   (filter seq))
              intermediate-ids (if (seq intermediate-seqs)
                                 (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity intermediate-seqs)
                                 [])]
          (arrow-via-intermediates intermediate-ids
                                   (fn [intermediate-id]
                                     (spi/subject->resources backend
                                                             intermediate-type
                                                             intermediate-id
                                                             via-relation-id
                                                             resource-type
                                                             cursor-id))))
        (let [target-permission (:target-permission path)
              intermediate-ids (traverse-permission-path backend
                                                         get-permission-paths-fn
                                                         subject-type
                                                         subject-id
                                                         target-permission
                                                         intermediate-type
                                                         inclusive-intermediate-cursor
                                                         (or visited-paths #{}))]
          (arrow-via-intermediates intermediate-ids
                                   (fn [intermediate-id]
                                     (spi/subject->resources backend
                                                             intermediate-type
                                                             intermediate-id
                                                             via-relation-id
                                                             resource-type
                                                             cursor-id))))))))

(defn traverse-permission-path
  ([backend get-permission-paths-fn subject-type subject-id permission-name resource-type cursor-id]
   (traverse-permission-path backend get-permission-paths-fn subject-type subject-id permission-name resource-type cursor-id #{}))
  ([backend get-permission-paths-fn subject-type subject-id permission-name resource-type cursor-id visited-paths]
   (let [path-key [subject-type subject-id permission-name resource-type]]
     (if (contains? visited-paths path-key)
       []
       (let [paths (get-permission-paths-fn backend resource-type permission-name)
             next-visited (conj visited-paths path-key)
             path-seqs (->> paths
                            (map (fn [path]
                                   (:results
                                    (traverse-permission-path-via-subject backend
                                                                          get-permission-paths-fn
                                                                          subject-type
                                                                          subject-id
                                                                          path
                                                                          resource-type
                                                                          cursor-id
                                                                          nil
                                                                          next-visited))))
                            (filter seq))]
         (if (seq path-seqs)
           (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
           []))))))

(defn traverse-permission-path-reverse
  [backend get-permission-paths-fn resource-type resource-id path subject-type cursor-id intermediate-cursor-id visited-paths]
  (case (:type path)
    :relation
    {:results (when (= subject-type (:subject-type path))
                (spi/resource->subjects backend
                                        resource-type
                                        resource-id
                                        (:relation-id path)
                                        subject-type
                                        cursor-id))
     :!state nil}

    :self-permission
    {:results (let [target-paths (get-permission-paths-fn backend resource-type (:target-permission path))
                    path-seqs (->> target-paths
                                   (map (fn [target-path]
                                          (:results
                                           (traverse-permission-path-reverse backend
                                                                             get-permission-paths-fn
                                                                             resource-type
                                                                             resource-id
                                                                             target-path
                                                                             subject-type
                                                                             cursor-id
                                                                             nil
                                                                             (or visited-paths #{})))))
                                   (filter seq))]
                (if (seq path-seqs)
                  (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
                  []))
     :!state nil}

    :arrow
    (let [intermediate-type (:target-type path)
          via-relation-id (:via-relation-id path)
          inclusive-intermediate-cursor (inclusive-cursor->exclusive intermediate-cursor-id)
          intermediate-ids (spi/resource->subjects backend
                                                   resource-type
                                                   resource-id
                                                   via-relation-id
                                                   intermediate-type
                                                   inclusive-intermediate-cursor)]
      (if (:target-relation path)
        (let [matching-sub-paths (matching-relation-sub-paths (:sub-paths path) subject-type)]
          (arrow-via-intermediates intermediate-ids
                                   (fn [intermediate-id]
                                     (let [subject-seqs (->> matching-sub-paths
                                                             (map (fn [sub-path]
                                                                    (spi/resource->subjects backend
                                                                                            intermediate-type
                                                                                            intermediate-id
                                                                                            (:relation-id sub-path)
                                                                                            subject-type
                                                                                            cursor-id)))
                                                             (filter seq))]
                                       (if (seq subject-seqs)
                                         (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
                                         [])))))
        (let [target-permission (:target-permission path)]
          (arrow-via-intermediates intermediate-ids
                                   (fn [intermediate-id]
                                     (let [paths (get-permission-paths-fn backend intermediate-type target-permission)
                                           subject-seqs (->> paths
                                                             (map (fn [sub-path]
                                                                    (:results
                                                                     (traverse-permission-path-reverse backend
                                                                                                       get-permission-paths-fn
                                                                                                       intermediate-type
                                                                                                       intermediate-id
                                                                                                       sub-path
                                                                                                       subject-type
                                                                                                       cursor-id
                                                                                                       nil
                                                                                                       (or visited-paths #{})))))
                                                             (filter seq))]
                                       (if (seq subject-seqs)
                                         (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
                                         [])))))))))

(defn can?
  [backend get-permission-paths-fn subject permission resource]
  (let [subject-type  (:type subject)
        subject-id    (:id subject)
        resource-type (:type resource)
        resource-id   (:id resource)
        paths         (get-permission-paths-fn backend resource-type permission)]
    (if (or (nil? subject-id) (nil? resource-id))
      false
      (boolean
       (some
        (fn [path]
          (case (:type path)
            :relation
            (when (= subject-type (:subject-type path))
              (spi/direct-match? backend
                                 subject-type
                                 subject-id
                                 (:relation-id path)
                                 resource-type
                                 resource-id))

            :self-permission
            (can? backend get-permission-paths-fn subject (:target-permission path) resource)

            :arrow
            (let [intermediate-type (:target-type path)
                  via-relation-id (:via-relation-id path)
                  intermediate-ids (spi/resource->subjects backend
                                                           resource-type
                                                           resource-id
                                                           via-relation-id
                                                           intermediate-type
                                                           nil)]
              (if (:target-relation path)
                (let [matching-sub-paths (matching-relation-sub-paths (:sub-paths path) subject-type)]
                  (some (fn [intermediate-id]
                          (some (fn [sub-path]
                                  (spi/direct-match? backend
                                                     subject-type
                                                     subject-id
                                                     (:relation-id sub-path)
                                                     intermediate-type
                                                     intermediate-id))
                                matching-sub-paths))
                        intermediate-ids))
                (some (fn [intermediate-id]
                        (can? backend
                              get-permission-paths-fn
                              subject
                              (:target-permission path)
                              (spice-object intermediate-type intermediate-id)))
                      intermediate-ids)))))
        paths)))))

(def forward-direction
  {:anchor-key :subject
   :perm-type-fn (fn [query] (:resource/type query))
   :result-type-fn (fn [query] (:resource/type query))
   :traverse-fn traverse-permission-path-via-subject
   :v1-cursor-key :resource})

(def reverse-direction
  {:anchor-key :resource
   :perm-type-fn (fn [query] (:type (:resource query)))
   :result-type-fn (fn [query] (:subject/type query))
   :traverse-fn traverse-permission-path-reverse
   :v1-cursor-key :subject})

(defn lazy-merged-lookup
  [backend direction get-permission-paths-fn query]
  (let [{:keys [anchor-key traverse-fn v1-cursor-key perm-type-fn]} direction
        anchor           (get query anchor-key)
        anchor-type      (:type anchor)
        anchor-id        (:id anchor)
        cursor           (:cursor query)
        cursor-id        (extract-cursor-id cursor v1-cursor-key)
        permission       (:permission query)
        perm-type        (perm-type-fn query)
        result-type-key  (if (= anchor-key :subject) :resource/type :subject/type)
        result-type      (get query result-type-key)
        paths            (get-permission-paths-fn backend perm-type permission)
        path-results     (vec
                          (->> paths
                               (map-indexed
                                (fn [idx path]
                                  (let [intermediate-cursor-id (get-in cursor [:p idx])
                                        {:keys [results !state]}
                                        (if anchor-id
                                          (traverse-fn backend
                                                       get-permission-paths-fn
                                                       anchor-type
                                                       anchor-id
                                                       path
                                                       result-type
                                                       cursor-id
                                                       intermediate-cursor-id
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

(defn lookup
  [backend direction get-permission-paths-fn query]
  (let [{:keys [result-type-fn v1-cursor-key]} direction
        {:keys [limit cursor] :or {limit 1000}} query
        {:keys [results path-results]} (lazy-merged-lookup backend direction get-permission-paths-fn query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        result-type (result-type-fn query)
        items       (doall (map #(spice-object result-type %) limited-results))
        last-id     (:id (last items))]
    {:data items
     :cursor (build-v2-cursor cursor last-id path-results v1-cursor-key)}))

(defn count-results
  [backend direction get-permission-paths-fn query default-v1-key]
  (let [{:keys [limit cursor] :or {limit -1}} query
        {:keys [results path-results]} (lazy-merged-lookup backend direction get-permission-paths-fn query)
        limited-results (if (>= limit 0)
                          (take limit results)
                          results)
        counted (doall limited-results)
        last-id (last counted)]
    {:count (count counted)
     :limit limit
     :cursor (build-v2-cursor cursor last-id path-results default-v1-key)}))
