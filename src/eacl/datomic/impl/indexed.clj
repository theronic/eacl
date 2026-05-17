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
  {:kind :lookup-eid
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

(defn all-permission-nodes
  [db]
  (->> (d/q '[:find ?resource-type ?permission-name
             :where
             [?p :eacl.permission/resource-type ?resource-type]
             [?p :eacl.permission/permission-name ?permission-name]]
           db)
       (mapv vec)
       set))

(defn traversal-permission?
  "True when a permission root transitively depends on a recursive permission SCC.
  These roots cannot be proven page-bounded in eid order without materialized
  grants, so list APIs evaluate them in deterministic traversal order."
  [db resource-type permission-name]
  (recursive-permission-query? db resource-type permission-name))

(defn traversal-nodes
  [db]
  (->> (all-permission-nodes db)
       (filter (fn [[resource-type permission-name]]
                 (traversal-permission? db resource-type permission-name)))
       set))

(def ^:private recursive-engine-version 1)

(def ^:dynamic *recursive-traversal-limits*
  {:max-derived-grants 100000
   :max-advanced-datoms 100000
   :max-queued-work 100000})

(def ^:dynamic *recursive-traversal-stats* nil)

(defn- recursive-traversal-error!
  [message data]
  (throw (ex-info message data)))

(defn- inc-stat!
  [k]
  (when *recursive-traversal-stats*
    (swap! *recursive-traversal-stats* update k (fnil inc 0))))

(defn- increment-counter
  [state counter-key limit-key]
  (let [n (inc (get-in state [:counters counter-key] 0))
        limit (get *recursive-traversal-limits* limit-key)]
    (when (and limit (> n limit))
      (recursive-traversal-error!
       "Recursive traversal safety limit exceeded."
       {:eacl/error :eacl.recursive-traversal/limit-exceeded
        :limit-kind counter-key
        :limit limit}))
    (assoc-in state [:counters counter-key] n)))

(defn- enqueue-work
  [state work]
  (let [queue' (conj (:queue state) work)
        limit (:max-queued-work *recursive-traversal-limits*)]
    (when (and limit (> (count queue') limit))
      (recursive-traversal-error!
       "Recursive traversal queue safety limit exceeded."
       {:eacl/error :eacl.recursive-traversal/limit-exceeded
        :limit-kind :queued-work
        :limit limit}))
    (assoc state :queue queue')))

(defn- enqueue-works
  [state works]
  (reduce enqueue-work state works))

(defn- pop-work
  [state]
  [(peek (:queue state)) (update state :queue pop)])

(defn- sorted-paths
  [paths]
  (sort-by (fn [path]
             (case (:type path)
               :relation [:relation (:subject-type path) (:relation-eid path)]
               :self-permission [:self-permission (:resource-type path) (:target-permission path)]
               :arrow [:arrow (:via path) (:target-type path) (:via-relation-eid path)
                       (:target-relation path) (:target-permission path)
                       (mapv (juxt :subject-type :relation-eid) (:sub-paths path))]
               [(:type path)]))
           paths))

(defn- rule-id
  [node rule path extra]
  (vec (concat [rule node]
               (case rule
                 :relation [(:subject-type path) (:relation-eid path)]
                 :self-permission [(:target-permission path)]
                 :arrow-permission [(:via path) (:target-type path)
                                    (:via-relation-eid path) (:target-permission path)]
                 :arrow-relation [(:via path) (:target-type path)
                                  (:via-relation-eid path)]
                 [])
               extra)))

(defn- compile-recursive-rules
  [db root-node]
  (let [nodes (sort (reachable-permission-query-nodes db root-node))]
    (->> nodes
         (mapcat
          (fn [[resource-type permission :as node]]
            (mapcat
             (fn [path]
               (case (:type path)
                 :relation
                 [{:id (rule-id node :relation path [])
                   :rule :relation
                   :node node
                   :resource-type resource-type
                   :permission permission
                   :relation-eid (:relation-eid path)
                   :subject-type (:subject-type path)}]

                 :self-permission
                 [{:id (rule-id node :self-permission path [])
                   :rule :self-permission
                   :node node
                   :resource-type resource-type
                   :permission permission
                   :target-node [resource-type (:target-permission path)]}]

                 :arrow
                 (if-let [target-permission (:target-permission path)]
                   [{:id (rule-id node :arrow-permission path [])
                     :rule :arrow-permission
                     :node node
                     :resource-type resource-type
                     :permission permission
                     :via-relation-eid (:via-relation-eid path)
                     :intermediate-type (:target-type path)
                     :target-node [(:target-type path) target-permission]}]
                   (mapv (fn [sub-path]
                           {:id (rule-id node :arrow-relation path [(:subject-type sub-path
                                                                                   (:relation-eid sub-path))])
                            :rule :arrow-relation
                            :node node
                            :resource-type resource-type
                            :permission permission
                            :via-relation-eid (:via-relation-eid path)
                            :intermediate-type (:target-type path)
                            :target-relation-eid (:relation-eid sub-path)
                            :target-subject-type (:subject-type sub-path)})
                         (sort-by (juxt :subject-type :relation-eid) (:sub-paths path))))))
             (sorted-paths (get-permission-paths db resource-type permission)))))
         (sort-by :id)
         vec)))

(defn- recursive-edge
  [direction result-kind ordinal result-type result-eid]
  {:kind :recursive-traversal
   :engine-version recursive-engine-version
   :direction direction
   :result-kind result-kind
   :ordinal ordinal
   :result {:type result-type
            :eid result-eid}})

(defn- validate-recursive-bound!
  [bound direction result-kind]
  (when bound
    (when-not (= :recursive-traversal (:kind bound))
      (recursive-traversal-error!
       "Recursive traversal cursor has the wrong kind."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :expected :recursive-traversal
        :actual (:kind bound)}))
    (when-not (= recursive-engine-version (:engine-version bound))
      (recursive-traversal-error!
       "Recursive traversal cursor was created by a different engine version."
       {:eacl/error :eacl.pagination/stale-cursor
        :expected recursive-engine-version
        :actual (:engine-version bound)}))
    (when-not (= direction (:direction bound))
      (recursive-traversal-error!
       "Recursive traversal cursor direction does not match the lookup."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :expected direction
        :actual (:direction bound)}))
    (when-not (= result-kind (:result-kind bound))
      (recursive-traversal-error!
       "Recursive traversal cursor result kind does not match the lookup."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :expected result-kind
        :actual (:result-kind bound)}))))

(defn- same-recursive-bound-result?
  [bound item]
  (and (= (get-in bound [:result :type])
          (get-in item [:cursor :result :type]))
       (= (get-in bound [:result :eid])
          (get-in item [:cursor :result :eid]))))

(defn- validate-lookup-eid-bound!
  [bound]
  (when (and bound (not= :lookup-eid (:kind bound)))
    (page-error! "Lookup page cursor has the wrong kind."
                 {:eacl/error :eacl.pagination/wrong-cursor-kind
                  :expected :lookup-eid
                  :actual (:kind bound)})))

(defn- stream-work
  [eids on-eid]
  (when-let [s (seq eids)]
    {:kind :stream
     :eids s
     :on-eid on-eid}))

(defn- enqueue-stream
  [state eids on-eid]
  (if-let [work (stream-work eids on-eid)]
    (enqueue-work state work)
    state))

(defn- advance-stream
  [state {:keys [eids on-eid]}]
  (let [eid (first eids)
        more (rest eids)
        state' (-> state
                   (increment-counter :advanced-datoms :max-advanced-datoms)
                   (enqueue-works (on-eid eid)))]
    (inc-stat! :advanced-stream-datoms)
    (if (seq more)
      (enqueue-work state' {:kind :stream
                            :eids more
                            :on-eid on-eid})
      state')))

(defn- forward-consumers
  [rules]
  (->> rules
       (keep (fn [rule]
               (case (:rule rule)
                 :self-permission
                 [(:target-node rule) rule]
                 :arrow-permission
                 [(:target-node rule) rule]
                 nil)))
       (group-by first)
       (into {}
             (map (fn [[node pairs]]
                    [node (mapv second (sort-by (comp :id second) pairs))])))))

(defn- forward-seed-state
  [db subject-type subject-eid rules]
  (reduce
   (fn [state rule]
     (case (:rule rule)
       :relation
       (if (= subject-type (:subject-type rule))
         (enqueue-stream state
                         (subject->resources db subject-type subject-eid
                                             (:relation-eid rule)
                                             (:resource-type rule)
                                             nil)
                         (fn [resource-eid]
                           [{:kind :grant
                             :node (:node rule)
                             :resource-eid resource-eid}]))
         state)

       :arrow-relation
       (if (= subject-type (:target-subject-type rule))
         (enqueue-stream state
                         (subject->resources db subject-type subject-eid
                                             (:target-relation-eid rule)
                                             (:intermediate-type rule)
                                             nil)
                         (fn [intermediate-eid]
                           (if-let [stream (stream-work
                                            (subject->resources db
                                                                (:intermediate-type rule)
                                                                intermediate-eid
                                                                (:via-relation-eid rule)
                                                                (:resource-type rule)
                                                                nil)
                                            (fn [resource-eid]
                                              [{:kind :grant
                                                :node (:node rule)
                                                :resource-eid resource-eid}]))]
                             [stream]
                             [])))
         state)

       state))
   {:queue clojure.lang.PersistentQueue/EMPTY
    :seen-grants #{}
    :emitted-root #{}
    :ordinal 0
    :counters {}
    :consumers (forward-consumers rules)}
   rules))

(defn- forward-consumer-work
  [db grant rule]
  (case (:rule rule)
    :self-permission
    [{:kind :grant
      :node (:node rule)
      :resource-eid (:resource-eid grant)}]

    :arrow-permission
    (if-let [stream (stream-work
                     (subject->resources db
                                         (:intermediate-type rule)
                                         (:resource-eid grant)
                                         (:via-relation-eid rule)
                                         (:resource-type rule)
                                         nil)
                     (fn [resource-eid]
                       [{:kind :grant
                         :node (:node rule)
                         :resource-eid resource-eid}]))]
      [stream]
      [])

    []))

(defn- process-forward-grant
  [db root-node result-type state {:keys [node resource-eid] :as grant}]
  (let [grant-key [node resource-eid]]
    (if (contains? (:seen-grants state) grant-key)
      (do
        (inc-stat! :deduped-grants)
        [state nil])
      (let [state' (-> state
                       (increment-counter :derived-grants :max-derived-grants)
                       (update :seen-grants conj grant-key))
            state'' (enqueue-works state'
                                   (mapcat #(forward-consumer-work db grant %)
                                           (get-in state' [:consumers node])))]
        (inc-stat! :derived-grants)
        (if (and (= root-node node)
                 (not (contains? (:emitted-root state'') resource-eid)))
          (let [ordinal (:ordinal state'')
                item {:node (spice-object result-type resource-eid)
                      :cursor (recursive-edge :forward :resource ordinal result-type resource-eid)}]
            (inc-stat! :emitted-results)
            [(-> state''
                 (update :emitted-root conj resource-eid)
                 (update :ordinal inc))
             item])
          [state'' nil])))))

(defn- initial-forward-state
  [db subject-type subject-eid root-node]
  (let [rules (compile-recursive-rules db root-node)]
    (forward-seed-state db subject-type subject-eid rules)))

(defn- next-forward-item
  [db root-node result-type state]
  (loop [state state]
    (if (empty? (:queue state))
      [state nil]
      (let [[work state'] (pop-work state)]
        (case (:kind work)
          :stream
          (recur (advance-stream state' work))

          :grant
          (let [[state'' item] (process-forward-grant db root-node result-type state' work)]
            (if item
              [state'' item]
              (recur state''))))))))

(defn- collect-forward-after
  [db root-node result-type state bound size]
  (loop [state state
         mode (if bound :seek :collect)
         items []]
    (if (>= (count items) (inc size))
      {:items items :complete? false}
      (let [[state' item] (next-forward-item db root-node result-type state)]
        (cond
          (nil? item)
          (if (= mode :seek)
            (recursive-traversal-error!
             "Recursive traversal cursor no longer exists."
             {:eacl/error :eacl.pagination/stale-cursor
              :bound bound})
            {:items items :complete? true})

          (= mode :seek)
          (let [ordinal (get-in item [:cursor :ordinal])]
            (cond
              (< ordinal (:ordinal bound))
              (recur state' :seek items)

              (= ordinal (:ordinal bound))
              (if (same-recursive-bound-result? bound item)
                (recur state' :collect items)
                (recursive-traversal-error!
                 "Recursive traversal cursor points at a different result."
                 {:eacl/error :eacl.pagination/stale-cursor
                  :bound bound
                  :actual (:cursor item)}))

              :else
              (recursive-traversal-error!
               "Recursive traversal cursor was skipped."
               {:eacl/error :eacl.pagination/stale-cursor
                :bound bound
                :actual (:cursor item)})))

          :else
          (recur state' :collect (conj items item)))))))

(defn- collect-forward-before
  [db root-node result-type state bound size]
  (loop [state state
         ring clojure.lang.PersistentQueue/EMPTY
         ring-count 0]
    (let [[state' item] (next-forward-item db root-node result-type state)]
      (cond
        (nil? item)
        (recursive-traversal-error!
         "Recursive traversal cursor no longer exists."
         {:eacl/error :eacl.pagination/stale-cursor
          :bound bound})

        (= (:ordinal bound) (get-in item [:cursor :ordinal]))
        (if (same-recursive-bound-result? bound item)
          (let [items (vec ring)
                has-sentinel? (> (count items) size)
                page-items (if has-sentinel?
                             (subvec items (- (count items) size))
                             items)]
            {:items page-items
             :has-sentinel? has-sentinel?})
          (recursive-traversal-error!
           "Recursive traversal cursor points at a different result."
           {:eacl/error :eacl.pagination/stale-cursor
            :bound bound
            :actual (:cursor item)}))

        (> (get-in item [:cursor :ordinal]) (:ordinal bound))
        (recursive-traversal-error!
         "Recursive traversal cursor was skipped."
         {:eacl/error :eacl.pagination/stale-cursor
          :bound bound
          :actual (:cursor item)})

        :else
        (let [ring' (conj ring item)
              ring-count' (inc ring-count)
              trim? (> ring-count' (inc size))]
          (recur state'
                 (if trim? (pop ring') ring')
                 (if trim? (inc size) ring-count')))))))

(defn- recursive-forward-page
  [db query]
  (let [{:keys [direction size bound]} (normalize-page-request query)
        _ (validate-recursive-bound! bound :forward :resource)
        _ (when (and (= :desc direction) (nil? bound))
            (page-error! "Bare :last is not supported for recursive traversal pagination."
                         {:eacl/error :eacl.pagination/unsupported-recursive-last
                          :reason :requires-full-traversal}))
        {:keys [subject permission]} query
        subject-type (:type subject)
        subject-eid (d/entid db (:id subject))
        result-type (:resource/type query)
        root-node (permission-query-node result-type permission)
        state (when subject-eid
                (initial-forward-state db subject-type subject-eid root-node))]
    (if-not state
      (page-response {:items []
                      :has-next? false
                      :has-previous? (boolean bound)})
      (case direction
        :asc
        (let [{:keys [items complete?]} (collect-forward-after db root-node result-type state bound size)
              page-items (take size items)
              has-sentinel? (> (count items) size)]
          (page-response {:items page-items
                          :has-next? (and has-sentinel? (not complete?))
                          :has-previous? (boolean bound)}))

        :desc
        (let [{:keys [items has-sentinel?]} (collect-forward-before db root-node result-type state bound size)]
          (page-response {:items items
                          :has-next? true
                          :has-previous? has-sentinel?}))))))

(defn- rules-by-node
  [rules]
  (->> rules
       (group-by :node)
       (into {}
             (map (fn [[node node-rules]]
                    [node (vec (sort-by :id node-rules))])))))

(defn- reverse-consumer-key
  [node resource-eid]
  [node resource-eid])

(defn- add-reverse-consumer
  [state key consumer]
  (let [state' (update-in state [:consumers key] (fnil conj []) consumer)
        existing (get-in state' [:grants-by-goal key])]
    (enqueue-works state'
                   (mapcat consumer existing))))

(declare enqueue-reverse-goal)

(defn- reverse-goal-rule-work
  [db state resource-eid rule]
  (case (:rule rule)
    :relation
    (if (= (:subject-type state) (:subject-type rule))
      (enqueue-stream state
                      (resource->subjects db
                                          (:resource-type rule)
                                          resource-eid
                                          (:relation-eid rule)
                                          (:subject-type state)
                                          nil)
                      (fn [subject-eid]
                        [{:kind :grant
                          :node (:node rule)
                          :resource-eid resource-eid
                          :subject-type (:subject-type state)
                          :subject-eid subject-eid}]))
      state)

    :self-permission
    (let [target-key (reverse-consumer-key (:target-node rule) resource-eid)
          consumer (fn [grant]
                     [{:kind :grant
                       :node (:node rule)
                       :resource-eid resource-eid
                       :subject-type (:subject-type grant)
                       :subject-eid (:subject-eid grant)}])]
      (-> state
          (add-reverse-consumer target-key consumer)
          (enqueue-reverse-goal (:target-node rule) resource-eid)))

    :arrow-relation
    (enqueue-stream state
                    (resource->subjects db
                                        (:resource-type rule)
                                        resource-eid
                                        (:via-relation-eid rule)
                                        (:intermediate-type rule)
                                        nil)
                    (fn [intermediate-eid]
                      (if-let [stream (stream-work
                                       (resource->subjects db
                                                           (:intermediate-type rule)
                                                           intermediate-eid
                                                           (:target-relation-eid rule)
                                                           (:subject-type state)
                                                           nil)
                                       (fn [subject-eid]
                                         [{:kind :grant
                                           :node (:node rule)
                                           :resource-eid resource-eid
                                           :subject-type (:subject-type state)
                                           :subject-eid subject-eid}]))]
                        [stream]
                        [])))

    :arrow-permission
    (enqueue-stream state
                    (resource->subjects db
                                        (:resource-type rule)
                                        resource-eid
                                        (:via-relation-eid rule)
                                        (:intermediate-type rule)
                                        nil)
                    (fn [intermediate-eid]
                      (let [target-key (reverse-consumer-key (:target-node rule) intermediate-eid)
                            consumer (fn [grant]
                                       [{:kind :grant
                                         :node (:node rule)
                                         :resource-eid resource-eid
                                         :subject-type (:subject-type grant)
                                         :subject-eid (:subject-eid grant)}])]
                        [{:kind :register-consumer
                          :consumer-key target-key
                          :consumer consumer}
                         {:kind :goal
                          :node (:target-node rule)
                          :resource-eid intermediate-eid}])))))

(defn- enqueue-reverse-goal
  [state node resource-eid]
  (enqueue-work state {:kind :goal
                       :node node
                       :resource-eid resource-eid}))

(defn- process-reverse-goal
  [db state {:keys [node resource-eid]}]
  (let [goal-key (reverse-consumer-key node resource-eid)]
    (if (contains? (:seen-goals state) goal-key)
      state
      (reduce (fn [acc rule]
                (reverse-goal-rule-work db acc resource-eid rule))
              (update state :seen-goals conj goal-key)
              (get-in state [:rules-by-node node])))))

(defn- process-reverse-grant
  [root-node root-resource-eid result-type state {:keys [node resource-eid subject-type subject-eid] :as grant}]
  (let [grant-key [node resource-eid subject-type subject-eid]
        goal-key (reverse-consumer-key node resource-eid)]
    (if (contains? (:seen-grants state) grant-key)
      (do
        (inc-stat! :deduped-grants)
        [state nil])
      (let [state' (-> state
                       (increment-counter :derived-grants :max-derived-grants)
                       (update :seen-grants conj grant-key)
                       (update-in [:grants-by-goal goal-key] (fnil conj []) grant))
            state'' (enqueue-works state'
                                   (mapcat #(% grant)
                                           (get-in state' [:consumers goal-key])))]
        (inc-stat! :derived-grants)
        (if (and (= root-node node)
                 (= root-resource-eid resource-eid)
                 (= result-type subject-type)
                 (not (contains? (:emitted-subjects state'') [subject-type subject-eid])))
          (let [ordinal (:ordinal state'')
                item {:node (spice-object result-type subject-eid)
                      :cursor (recursive-edge :reverse :subject ordinal result-type subject-eid)}]
            (inc-stat! :emitted-results)
            [(-> state''
                 (update :emitted-subjects conj [subject-type subject-eid])
                 (update :ordinal inc))
             item])
          [state'' nil])))))

(defn- initial-reverse-state
  [db subject-type root-node root-resource-eid]
  (let [rules (compile-recursive-rules db root-node)]
    (enqueue-reverse-goal
     {:queue clojure.lang.PersistentQueue/EMPTY
      :seen-goals #{}
      :seen-grants #{}
      :grants-by-goal {}
      :consumers {}
      :emitted-subjects #{}
      :ordinal 0
      :counters {}
      :subject-type subject-type
      :rules-by-node (rules-by-node rules)}
     root-node
     root-resource-eid)))

(defn- next-reverse-item
  [db root-node root-resource-eid result-type state]
  (loop [state state]
    (if (empty? (:queue state))
      [state nil]
      (let [[work state'] (pop-work state)]
        (case (:kind work)
          :stream
          (recur (advance-stream state' work))

          :goal
          (recur (process-reverse-goal db state' work))

          :register-consumer
          (recur (add-reverse-consumer state' (:consumer-key work) (:consumer work)))

          :grant
          (let [[state'' item] (process-reverse-grant root-node root-resource-eid result-type state' work)]
            (if item
              [state'' item]
              (recur state''))))))))

(defn- collect-reverse-after
  [db root-node root-resource-eid result-type state bound size]
  (loop [state state
         mode (if bound :seek :collect)
         items []]
    (if (>= (count items) (inc size))
      {:items items :complete? false}
      (let [[state' item] (next-reverse-item db root-node root-resource-eid result-type state)]
        (cond
          (nil? item)
          (if (= mode :seek)
            (recursive-traversal-error!
             "Recursive traversal cursor no longer exists."
             {:eacl/error :eacl.pagination/stale-cursor
              :bound bound})
            {:items items :complete? true})

          (= mode :seek)
          (let [ordinal (get-in item [:cursor :ordinal])]
            (cond
              (< ordinal (:ordinal bound))
              (recur state' :seek items)

              (= ordinal (:ordinal bound))
              (if (same-recursive-bound-result? bound item)
                (recur state' :collect items)
                (recursive-traversal-error!
                 "Recursive traversal cursor points at a different result."
                 {:eacl/error :eacl.pagination/stale-cursor
                  :bound bound
                  :actual (:cursor item)}))

              :else
              (recursive-traversal-error!
               "Recursive traversal cursor was skipped."
               {:eacl/error :eacl.pagination/stale-cursor
                :bound bound
                :actual (:cursor item)})))

          :else
          (recur state' :collect (conj items item)))))))

(defn- collect-reverse-before
  [db root-node root-resource-eid result-type state bound size]
  (loop [state state
         ring clojure.lang.PersistentQueue/EMPTY
         ring-count 0]
    (let [[state' item] (next-reverse-item db root-node root-resource-eid result-type state)]
      (cond
        (nil? item)
        (recursive-traversal-error!
         "Recursive traversal cursor no longer exists."
         {:eacl/error :eacl.pagination/stale-cursor
          :bound bound})

        (= (:ordinal bound) (get-in item [:cursor :ordinal]))
        (if (same-recursive-bound-result? bound item)
          (let [items (vec ring)
                has-sentinel? (> (count items) size)
                page-items (if has-sentinel?
                             (subvec items (- (count items) size))
                             items)]
            {:items page-items
             :has-sentinel? has-sentinel?})
          (recursive-traversal-error!
           "Recursive traversal cursor points at a different result."
           {:eacl/error :eacl.pagination/stale-cursor
            :bound bound
            :actual (:cursor item)}))

        (> (get-in item [:cursor :ordinal]) (:ordinal bound))
        (recursive-traversal-error!
         "Recursive traversal cursor was skipped."
         {:eacl/error :eacl.pagination/stale-cursor
          :bound bound
          :actual (:cursor item)})

        :else
        (let [ring' (conj ring item)
              ring-count' (inc ring-count)
              trim? (> ring-count' (inc size))]
          (recur state'
                 (if trim? (pop ring') ring')
                 (if trim? (inc size) ring-count')))))))

(defn- recursive-reverse-page
  [db query]
  (when (:subject/relation query)
    (page-error! ":subject/relation is not supported for recursive lookup-subjects."
                 {:eacl/error :eacl.pagination/unsupported-filter
                  :filter :subject/relation}))
  (let [{:keys [direction size bound]} (normalize-page-request query)
        _ (validate-recursive-bound! bound :reverse :subject)
        _ (when (and (= :desc direction) (nil? bound))
            (page-error! "Bare :last is not supported for recursive traversal pagination."
                         {:eacl/error :eacl.pagination/unsupported-recursive-last
                          :reason :requires-full-traversal}))
        {:keys [resource permission]} query
        resource-type (:type resource)
        resource-eid (d/entid db (:id resource))
        subject-type (:subject/type query)
        root-node (permission-query-node resource-type permission)
        state (when resource-eid
                (initial-reverse-state db subject-type root-node resource-eid))]
    (if-not state
      (page-response {:items []
                      :has-next? false
                      :has-previous? (boolean bound)})
      (case direction
        :asc
        (let [{:keys [items complete?]} (collect-reverse-after db root-node resource-eid subject-type state bound size)
              page-items (take size items)
              has-sentinel? (> (count items) size)]
          (page-response {:items page-items
                          :has-next? (and has-sentinel? (not complete?))
                          :has-previous? (boolean bound)}))

        :desc
        (let [{:keys [items has-sentinel?]} (collect-reverse-before db root-node resource-eid subject-type state bound size)]
          (page-response {:items items
                          :has-next? true
                          :has-previous? has-sentinel?}))))))

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

(defn- lookup-eids-page
  [result-type page-req eids-in-scan-order]
  (let [{:keys [direction size bound]} page-req
        realized (doall (take (inc size) eids-in-scan-order))
        has-sentinel? (> (count realized) size)
        page-results-in-scan-order (take size realized)
        page-results (case direction
                       :asc page-results-in-scan-order
                       :desc (reverse page-results-in-scan-order))
        items (lookup-items result-type page-results)]
    (page-response {:items items
                    :has-next? (case direction
                                 :asc has-sentinel?
                                 :desc (boolean bound))
                    :has-previous? (case direction
                                     :asc (boolean bound)
                                     :desc has-sentinel?)})))

(defn- lookup
  [db direction query]
  (let [{:keys [result-type-fn]} direction
        page-req (normalize-page-request query)
        {:keys [size bound]} page-req
        _ (validate-lookup-eid-bound! bound)
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
  (if (traversal-permission? db (:resource/type query) (:permission query))
    (recursive-forward-page db query)
    (lookup db forward-direction query)))

(defn lookup-subjects
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (if (traversal-permission? db (:type (:resource query)) (:permission query))
    (recursive-reverse-page db query)
    (lookup db reverse-direction query)))

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
  (if (traversal-permission? db (:resource/type query) (:permission query))
    (let [page (recursive-forward-page db (assoc query :first max-page-size))]
      {:count (loop [counted (count (:data page))
                     cursor (get-in page [:page-info :end-cursor])
                     has-next? (get-in page [:page-info :has-next-page?])]
                (if-not has-next?
                  counted
                  (let [next-page (recursive-forward-page db (assoc query
                                                                    :first max-page-size
                                                                    :after cursor))]
                    (recur (+ counted (count (:data next-page)))
                           (get-in next-page [:page-info :end-cursor])
                           (get-in next-page [:page-info :has-next-page?])))))
       :limit -1})
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
  (if (traversal-permission? db (:type (:resource query)) (:permission query))
    (let [page (recursive-reverse-page db (assoc query :first max-page-size))]
      {:count (loop [counted (count (:data page))
                     cursor (get-in page [:page-info :end-cursor])
                     has-next? (get-in page [:page-info :has-next-page?])]
                (if-not has-next?
                  counted
                  (let [next-page (recursive-reverse-page db (assoc query
                                                                    :first max-page-size
                                                                    :after cursor))]
                    (recur (+ counted (count (:data next-page)))
                           (get-in next-page [:page-info :end-cursor])
                           (get-in next-page [:page-info :has-next-page?])))))
       :limit -1})
    (let [page-req {:direction :asc :size max-page-size :bound nil}
          {:keys [results]} (lazy-merged-lookup db reverse-direction query page-req)
          counted (doall results)]
      {:count (count counted)
       :limit -1})))
