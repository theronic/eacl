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

(def ^:private default-page-size 1000)
(def ^:private max-page-size 10000)

(defn- page-error!
  [message data]
  (throw (ex-info message data)))

(defn normalize-page-request
  [query]
  (let [has-first? (contains? query :first)
        has-last? (contains? query :last)
        has-after? (contains? query :after)
        has-before? (contains? query :before)]
    (cond
      (contains? query :cursor)
      (page-error! ":cursor is not supported; use :first/:after or :last/:before."
                   {:key :cursor})

      (contains? query :limit)
      (page-error! ":limit is not supported for list pagination; use :first or :last."
                   {:key :limit})

      (and has-first? has-last?)
      (page-error! "Use exactly one of :first or :last." {:first (:first query)
                                                          :last (:last query)})

      (and has-before? has-after?)
      (page-error! "Use only one cursor boundary, :after or :before." {:after (:after query)
                                                                       :before (:before query)})

      (and has-after? (not has-first?))
      (page-error! ":after is valid only with :first." {:after (:after query)})

      (and has-before? (not has-last?))
      (page-error! ":before is valid only with :last." {:before (:before query)}))

    (let [direction (if has-last? :desc :asc)
          size (or (when has-first? (:first query))
                   (when has-last? (:last query))
                   default-page-size)
          bound (case direction
                  :asc (:after query)
                  :desc (:before query))]
      (when-not (and (integer? size) (pos? size))
        (page-error! "Page size must be a positive integer." {:size size}))
      (when (> size max-page-size)
        (page-error! "Page size exceeds configured maximum." {:size size
                                                              :max max-page-size}))
      {:direction direction
       :size size
       :bound bound})))

(defn- scan-opts
  [cursor-or-opts]
  (if (and (map? cursor-or-opts)
           (contains? cursor-or-opts :direction))
    {:direction (:direction cursor-or-opts)
     :bound-eid (:bound-eid cursor-or-opts)}
    {:direction :asc
     :bound-eid cursor-or-opts}))

(defn- merge-eid-seqs
  [direction seqs]
  (case direction
    :asc (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs)
    :desc (lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc identity seqs)))

(defn- lookup-edge
  [eid]
  {:kind :lookup
   :result-eid eid})

(defn- page-info
  [{:keys [items has-next? has-previous?]}]
  {:start-cursor (some-> items first :cursor)
   :end-cursor (some-> items last :cursor)
   :has-next-page? (boolean has-next?)
   :has-previous-page? (boolean has-previous?)})

(defn- page-response
  [{:keys [items has-next? has-previous?]}]
  {:data (mapv :node items)
   :page-info (page-info {:items items
                          :has-next? has-next?
                          :has-previous? has-previous?})})

(defn- lookup-items
  [result-type eids]
  (mapv (fn [eid]
          {:node (spice-object result-type eid)
           :cursor (lookup-edge eid)})
        eids))

(defn subject->resources
  [db subject-type subject-eid relation-eid resource-type cursor-or-opts]
  {:pre [subject-type subject-eid relation-eid resource-type]}
  (let [{:keys [direction bound-eid]} (scan-opts cursor-or-opts)
        attr-eid    (d/entid db forward-relationship-attr)
        prefix      [subject-type relation-eid resource-type]
        start-tuple (conj prefix
                          (case direction
                            :asc (or bound-eid 0)
                            :desc (or bound-eid Long/MAX_VALUE)))
        datoms      (case direction
                      :asc (d/seek-datoms db :eavt subject-eid attr-eid start-tuple)
                      :desc (d/rseek-datoms db :eavt subject-eid attr-eid start-tuple))]
    (->> datoms
         (take-while
          (fn [datom]
            (let [v (:v datom)]
              (and (== subject-eid (:e datom))
                   (== attr-eid (:a datom))
                   (= prefix (subvec (vec v) 0 3))))))
         (drop-while #(and bound-eid (= bound-eid (nth (:v %) 3))))
         (map (fn [datom] (nth (:v datom) 3))))))

(defn resource->subjects
  [db resource-type resource-eid relation-eid subject-type cursor-or-opts]
  {:pre [resource-type resource-eid relation-eid subject-type]}
  (let [{:keys [direction bound-eid]} (scan-opts cursor-or-opts)
        attr-eid    (d/entid db reverse-relationship-attr)
        prefix      [resource-type relation-eid subject-type]
        start-tuple (conj prefix
                          (case direction
                            :asc (or bound-eid 0)
                            :desc (or bound-eid Long/MAX_VALUE)))
        datoms      (case direction
                      :asc (d/seek-datoms db :eavt resource-eid attr-eid start-tuple)
                      :desc (d/rseek-datoms db :eavt resource-eid attr-eid start-tuple))]
    (->> datoms
         (take-while
          (fn [datom]
            (let [v (:v datom)]
              (and (== resource-eid (:e datom))
                   (== attr-eid (:a datom))
                   (= prefix (subvec (vec v) 0 3))))))
         (drop-while #(and bound-eid (= bound-eid (nth (:v %) 3))))
         (map (fn [datom] (nth (:v datom) 3))))))

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

(def ^:private permission-path-schema-attrs
  [:eacl.permission/resource-type+permission-name
   :eacl.permission/source-relation-name
   :eacl.permission/target-type
   :eacl.permission/target-name
   :eacl.relation/resource-type+relation-name+subject-type])

(defn- permission-path-schema-fingerprint
  [db]
  (hash
   (mapv (fn [attr]
           [attr (mapv (fn [datom]
                         [(:e datom) (:v datom)])
                       (d/datoms db :aevt attr))])
         permission-path-schema-attrs)))

(defn- permission-paths-cache-key
  [db resource-type permission-name]
  [(.id db)
   (permission-path-schema-fingerprint db)
   resource-type
   permission-name])

(defn calc-permission-paths
  "Returns path maps with resolved relation eids.
  Permission edges remain symbolic and are evaluated against concrete resources at runtime."
  [db resource-type permission-name]
  (->> (find-permission-defs db resource-type permission-name)
       (mapcat
        (fn [{:eacl.permission/keys [source-relation-name
                                     target-type
                                     target-name]}]
          (assert resource-type "resource-type missing")
          (assert source-relation-name "source-relation-name missing")
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
                         via-relation-eid (:e datom)]
                     (case target-type
                       :permission [{:type :arrow
                                     :via source-relation-name
                                     :target-type intermediate-type
                                     :via-relation-eid via-relation-eid
                                     :target-permission target-name}]
                       :relation (let [target-datoms (relation-datoms db intermediate-type target-name)]
                                   (if (seq target-datoms)
                                     [{:type :arrow
                                       :via source-relation-name
                                       :target-type intermediate-type
                                       :via-relation-eid via-relation-eid
                                       :target-relation target-name
                                       :sub-paths (mapv (fn [target-datom]
                                                          {:type :relation
                                                           :name target-name
                                                           :subject-type (nth (:v target-datom) 2)
                                                           :relation-eid (:e target-datom)})
                                                        target-datoms)}]
                                     (do
                                       (log/warn "Missing target relation definition"
                                                 {:intermediate-type intermediate-type
                                                  :target-relation-name target-name})
                                       []))))))
                 datoms)
                (do
                  (log/warn "Missing source relation definition"
                            {:resource-type resource-type
                             :via-relation-name source-relation-name})
                  []))))))
       vec))

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

(defn- permission-query-node
  [resource-type permission-name]
  [resource-type permission-name])

(defn- permission-query-dependencies
  [db [resource-type permission-name]]
  (->> (get-permission-paths db resource-type permission-name)
       (keep (fn [path]
               (case (:type path)
                 :self-permission (permission-query-node resource-type (:target-permission path))
                 :arrow (when-let [target-permission (:target-permission path)]
                          (permission-query-node (:target-type path) target-permission))
                 nil)))
       distinct
       vec))

(defn- recursive-permission-query?
  [db resource-type permission-name]
  (let [root (permission-query-node resource-type permission-name)]
    (loop [stack [{:node root
                   :deps (seq (permission-query-dependencies db root))}]
           visited #{}]
      (if-let [{:keys [node deps]} (peek stack)]
        (if-let [dep (first deps)]
          (cond
            (= dep node) true
            (some #(= dep (:node %)) stack) true
            (contains? visited dep) (recur (conj (pop stack) {:node node
                                                              :deps (next deps)})
                                           visited)
            :else (recur (conj (conj (pop stack) {:node node
                                                  :deps (next deps)})
                               {:node dep
                                :deps (seq (permission-query-dependencies db dep))})
                         visited))
          (recur (pop stack) (conj visited node)))
        false))))

(defn- reachable-permission-query-nodes
  [db root-node]
  (loop [stack [root-node]
         seen  #{}]
    (if-let [node (peek stack)]
      (if (contains? seen node)
        (recur (pop stack) seen)
        (recur (into (pop stack) (permission-query-dependencies db node))
               (conj seen node)))
      (vec seen))))

(defn- arrow-via-intermediates
  ([intermediate-eids result-fn]
   (arrow-via-intermediates :asc intermediate-eids result-fn))
  ([direction intermediate-eids result-fn]
   (let [pairs (keep (fn [intermediate-eid]
                       (let [results (result-fn intermediate-eid)]
                         (when (seq results)
                           {:int-eid intermediate-eid
                            :results results})))
                     intermediate-eids)
         min-int (:int-eid (first pairs))
         result-seqs (map :results pairs)]
     {:results (if (seq result-seqs)
                 (merge-eid-seqs direction result-seqs)
                 [])
      :!state (when min-int (volatile! min-int))})))

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

(defn- collect-subject-resources
  [db subject-type subject-eid relation-eid resource-type]
  (into #{} (subject->resources db
                                subject-type
                                subject-eid
                                relation-eid
                                resource-type
                                nil)))

(defn- collect-resources-via-intermediates
  [db intermediate-type intermediate-eids via-relation-eid resource-type]
  (reduce (fn [acc intermediate-eid]
            (into acc (subject->resources db
                                          intermediate-type
                                          intermediate-eid
                                          via-relation-eid
                                          resource-type
                                          nil)))
          #{}
          intermediate-eids))

(defn- eval-recursive-permission-node
  [db subject-type subject-eid [resource-type permission-name] current-results]
  (reduce
   (fn [acc path]
     (case (:type path)
       :relation
       (if (= subject-type (:subject-type path))
         (into acc (collect-subject-resources db
                                              subject-type
                                              subject-eid
                                              (:relation-eid path)
                                              resource-type))
         acc)

       :self-permission
       (into acc (get current-results
                      (permission-query-node resource-type (:target-permission path))
                      #{}))

       :arrow
       (let [intermediate-type (:target-type path)
             intermediate-eids (if (:target-relation path)
                                 (reduce (fn [intermediate-acc sub-path]
                                           (into intermediate-acc
                                                 (subject->resources db
                                                                     subject-type
                                                                     subject-eid
                                                                     (:relation-eid sub-path)
                                                                     intermediate-type
                                                                     nil)))
                                         #{}
                                         (matching-relation-sub-paths (:sub-paths path) subject-type))
                                 (get current-results
                                      (permission-query-node intermediate-type (:target-permission path))
                                      #{}))]
         (into acc (collect-resources-via-intermediates db
                                                        intermediate-type
                                                        intermediate-eids
                                                        (:via-relation-eid path)
                                                        resource-type)))

       acc))
   #{}
   (get-permission-paths db resource-type permission-name)))

(defn- solve-recursive-permission-results
  [db subject-type subject-eid root-node]
  (let [nodes   (reachable-permission-query-nodes db root-node)
        initial (zipmap nodes (repeat #{}))]
    (loop [results initial]
      (let [next-results (reduce (fn [acc node]
                                   (assoc acc node
                                          (eval-recursive-permission-node db
                                                                          subject-type
                                                                          subject-eid
                                                                          node
                                                                          results)))
                                 {}
                                 nodes)]
        (if (= results next-results)
          next-results
          (recur next-results))))))

(defn- recursive-resource-eids
  [db subject-type subject-eid permission resource-type]
  (get (solve-recursive-permission-results db
                                           subject-type
                                           subject-eid
                                           (permission-query-node resource-type permission))
       (permission-query-node resource-type permission)
       #{}))

(declare traverse-permission-path lookup-subject-eids* can*)

(defn traverse-permission-path-via-subject
  [db subject-type subject-eid path resource-type page-opts _intermediate-cursor-eid visited-paths]
  (let [{:keys [direction]} (scan-opts page-opts)
        unbounded-opts {:direction direction}]
    (case (:type path)
      :relation
      {:results (when (= subject-type (:subject-type path))
                  (subject->resources db
                                      subject-type
                                      subject-eid
                                      (:relation-eid path)
                                      resource-type
                                      page-opts))
       :!state nil}

      :self-permission
      {:results (traverse-permission-path db
                                          subject-type
                                          subject-eid
                                          (:target-permission path)
                                          resource-type
                                          page-opts
                                          (or visited-paths #{}))
       :!state nil}

      :arrow
      (let [intermediate-type (:target-type path)
            via-relation-eid (:via-relation-eid path)]
        (if (:target-relation path)
          (let [intermediate-seqs
                (->> (matching-relation-sub-paths (:sub-paths path) subject-type)
                     (map (fn [sub-path]
                            (subject->resources db
                                                subject-type
                                                subject-eid
                                                (:relation-eid sub-path)
                                                intermediate-type
                                                unbounded-opts)))
                     (filter seq))
                intermediate-eids (if (seq intermediate-seqs)
                                    (merge-eid-seqs direction intermediate-seqs)
                                    [])]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid]
                                       (subject->resources db
                                                           intermediate-type
                                                           intermediate-eid
                                                           via-relation-eid
                                                           resource-type
                                                           page-opts))))
          (let [target-permission (:target-permission path)
                intermediate-eids (traverse-permission-path db
                                                            subject-type
                                                            subject-eid
                                                            target-permission
                                                            intermediate-type
                                                            unbounded-opts
                                                            (or visited-paths #{}))]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid]
                                       (subject->resources db
                                                           intermediate-type
                                                           intermediate-eid
                                                           via-relation-eid
                                                           resource-type
                                                           page-opts)))))))))

(defn traverse-permission-path
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-or-opts visited-paths]
   (let [{:keys [direction] :as page-opts} (scan-opts cursor-or-opts)
         path-key [subject-type subject-eid permission-name resource-type]]
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
                                                                          page-opts
                                                                          nil
                                                                          next-visited))))
                            (filter seq))]
         (if (seq path-seqs)
           (merge-eid-seqs direction path-seqs)
           []))))))

(defn traverse-permission-path-reverse
  [db resource-type resource-eid path subject-type page-opts _intermediate-cursor-eid visited-paths]
  (let [{:keys [direction]} (scan-opts page-opts)
        unbounded-opts {:direction direction}]
    (case (:type path)
      :relation
      {:results (when (= subject-type (:subject-type path))
                  (resource->subjects db
                                      resource-type
                                      resource-eid
                                      (:relation-eid path)
                                      subject-type
                                      page-opts))
       :!state nil}

      :self-permission
      {:results (lookup-subject-eids* db
                                      resource-type
                                      resource-eid
                                      (:target-permission path)
                                      subject-type
                                      page-opts
                                      (or visited-paths #{}))
       :!state nil}

      :arrow
      (let [intermediate-type (:target-type path)
            via-relation-eid (:via-relation-eid path)
            intermediate-eids (resource->subjects db
                                                  resource-type
                                                  resource-eid
                                                  via-relation-eid
                                                  intermediate-type
                                                  unbounded-opts)]
        (if (:target-relation path)
          (let [matching-sub-paths (matching-relation-sub-paths (:sub-paths path) subject-type)]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid]
                                       (let [subject-seqs (->> matching-sub-paths
                                                               (map (fn [sub-path]
                                                                      (resource->subjects db
                                                                                          intermediate-type
                                                                                          intermediate-eid
                                                                                          (:relation-eid sub-path)
                                                                                          subject-type
                                                                                          page-opts)))
                                                               (filter seq))]
                                         (if (seq subject-seqs)
                                           (merge-eid-seqs direction subject-seqs)
                                           [])))))
          (let [target-permission (:target-permission path)]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid]
                                       (lookup-subject-eids* db
                                                             intermediate-type
                                                             intermediate-eid
                                                             target-permission
                                                             subject-type
                                                             page-opts
                                                             (or visited-paths #{}))))))))))

(defn- lookup-subject-eids*
  [db resource-type resource-eid permission-name subject-type cursor-or-opts visited-states]
  (let [{:keys [direction] :as page-opts} (scan-opts cursor-or-opts)
        state [resource-type resource-eid permission-name subject-type]]
    (if (contains? visited-states state)
      []
      (let [next-visited (conj visited-states state)
            paths (get-permission-paths db resource-type permission-name)
            path-seqs (->> paths
                           (map (fn [path]
                                  (:results
                                   (traverse-permission-path-reverse db
                                                                     resource-type
                                                                     resource-eid
                                                                     path
                                                                     subject-type
                                                                     page-opts
                                                                     nil
                                                                     next-visited))))
                           (filter seq))]
        (if (seq path-seqs)
          (merge-eid-seqs direction path-seqs)
          [])))))

(defn- can*
  [db subject-type subject-eid permission resource-type resource-eid visited-states]
  (let [state [subject-type subject-eid permission resource-type resource-eid]
        paths (get-permission-paths db resource-type permission)]
    (if (contains? visited-states state)
      false
      (let [next-visited (conj visited-states state)]
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
              (can* db
                    subject-type
                    subject-eid
                    (:target-permission path)
                    resource-type
                    resource-eid
                    next-visited)

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
                          (can* db
                                subject-type
                                subject-eid
                                (:target-permission path)
                                intermediate-type
                                intermediate-eid
                                next-visited))
                        intermediate-eids)))))
          paths))))))

(defn can?
  [db subject permission resource]
  (let [subject-type  (:type subject)
        subject-eid   (d/entid db (:id subject))
        resource-type (:type resource)
        resource-eid  (d/entid db (:id resource))]
    (if (or (nil? subject-eid) (nil? resource-eid))
      false
      (can* db subject-type subject-eid permission resource-type resource-eid #{}))))

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
  [db direction query page-req]
  (let [{:keys [anchor-key traverse-fn perm-type-fn]} direction
        anchor      (get query anchor-key)
        anchor-type (:type anchor)
        anchor-eid  (d/entid db (:id anchor))
        permission  (:permission query)
        perm-type   (perm-type-fn query)
        result-type-key (if (= anchor-key :subject) :resource/type :subject/type)
        result-type (get query result-type-key)
        page-opts   {:direction (:direction page-req)
                     :bound-eid (get-in page-req [:bound :result-eid])}
        paths       (get-permission-paths db perm-type permission)
        path-results (vec
                      (->> paths
                           (map-indexed
                            (fn [idx path]
                              (let [{:keys [results !state]}
                                    (if anchor-eid
                                      (traverse-fn db
                                                   anchor-type
                                                   anchor-eid
                                                   path
                                                   result-type
                                                   page-opts
                                                   nil
                                                   #{})
                                      {:results [] :!state nil})]
                                {:idx idx
                                 :results results
                                 :!state !state})))
                           (filter (comp seq :results))))]
    {:results (if (seq path-results)
                (merge-eid-seqs (:direction page-req) (map :results path-results))
                [])
     :path-results path-results}))

(defn- page-eids-from-sorted
  [sorted-eids page-req]
  (let [{:keys [direction size bound]} page-req
        bound-eid (:result-eid bound)]
    (case direction
      :asc
      (let [candidates (if bound-eid
                         (drop-while #(<= % bound-eid) sorted-eids)
                         sorted-eids)
            realized (doall (take (inc size) candidates))
            page (take size realized)]
        {:eids page
         :has-next? (> (count realized) size)
         :has-previous? (boolean bound)})

      :desc
      (let [candidates (if bound-eid
                         (take-while #(< % bound-eid) sorted-eids)
                         sorted-eids)
            page (take-last size candidates)]
        {:eids page
         :has-next? (boolean bound)
         :has-previous? (> (count candidates) size)}))))

(defn- recursive-forward-lookup
  [db query]
  (let [{:keys [subject permission]} query
        page-req (normalize-page-request query)
        subject-type  (:type subject)
        subject-eid   (d/entid db (:id subject))
        resource-type (:resource/type query)]
    (if subject-eid
      (let [sorted-eids   (sort (recursive-resource-eids db
                                                         subject-type
                                                         subject-eid
                                                         permission
                                                         resource-type))
            {:keys [eids has-next? has-previous?]} (page-eids-from-sorted sorted-eids page-req)
            items (lookup-items resource-type eids)]
        (page-response {:items items
                        :has-next? has-next?
                        :has-previous? has-previous?}))
      (page-response {:items []
                      :has-next? false
                      :has-previous? (boolean (:bound page-req))}))))

(defn- recursive-forward-count
  [db {:as query}]
  (when (or (contains? query :cursor)
            (contains? query :limit)
            (contains? query :first)
            (contains? query :last)
            (contains? query :before)
            (contains? query :after))
    (page-error! "count-resources does not use list pagination keys."
                 (select-keys query [:cursor :limit :first :last :before :after])))
  (let [subject       (:subject query)
        subject-type  (:type subject)
        subject-eid   (d/entid db (:id subject))
        resource-type (:resource/type query)
        permission    (:permission query)]
    (if subject-eid
      (let [sorted-eids  (sort (recursive-resource-eids db
                                                        subject-type
                                                        subject-eid
                                                        permission
                                                        resource-type))]
        {:count (count sorted-eids)
         :limit -1})
      {:count 0
       :limit -1})))

(defn- lookup
  [db direction query]
  (let [{:keys [result-type-fn]} direction
        page-req (normalize-page-request query)
        {:keys [size bound]} page-req
        {:keys [results]} (lazy-merged-lookup db direction query page-req)
        realized (doall (take (inc size) results))
        has-sentinel? (> (count realized) size)
        page-results-in-scan-order (take size realized)
        page-results (case (:direction page-req)
                       :asc page-results-in-scan-order
                       :desc (reverse page-results-in-scan-order))
        result-type (result-type-fn query)
        items       (lookup-items result-type page-results)]
    (page-response {:items items
                    :has-next? (case (:direction page-req)
                                 :asc has-sentinel?
                                 :desc (boolean bound))
                    :has-previous? (case (:direction page-req)
                                     :asc (boolean bound)
                                     :desc has-sentinel?)})))

(defn lookup-resources
  [db query]
  (if (recursive-permission-query? db (:resource/type query) (:permission query))
    (recursive-forward-lookup db query)
    (lookup db forward-direction query)))

(defn lookup-subjects
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (lookup db reverse-direction query))

(defn count-resources
  [db {:as query}]
  (when (or (contains? query :cursor)
            (contains? query :limit)
            (contains? query :first)
            (contains? query :last)
            (contains? query :before)
            (contains? query :after))
    (page-error! "count-resources does not use list pagination keys."
                 (select-keys query [:cursor :limit :first :last :before :after])))
  (if (recursive-permission-query? db (:resource/type query) (:permission query))
    (recursive-forward-count db query)
    (let [page-req {:direction :asc :size max-page-size :bound nil}
          {:keys [results]} (lazy-merged-lookup db forward-direction query page-req)
          counted (doall results)]
      {:count (count counted)
       :limit -1})))

(defn count-subjects
  [db {:as query}]
  (when (or (contains? query :cursor)
            (contains? query :limit)
            (contains? query :first)
            (contains? query :last)
            (contains? query :before)
            (contains? query :after))
    (page-error! "count-subjects does not use list pagination keys."
                 (select-keys query [:cursor :limit :first :last :before :after])))
  (let [page-req {:direction :asc :size max-page-size :bound nil}
        {:keys [results]} (lazy-merged-lookup db reverse-direction query page-req)
        counted (doall results)]
    {:count (count counted)
     :limit -1}))
