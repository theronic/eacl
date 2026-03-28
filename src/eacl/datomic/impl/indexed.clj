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

(def recursive-query-plan-cache
  (atom (cache/lru-cache-factory {} :threshold 256)))

(defn evict-permission-paths-cache! []
  (reset! permission-paths-cache (cache/lru-cache-factory {} :threshold 1000))
  (reset! recursive-query-plan-cache (cache/lru-cache-factory {} :threshold 256)))

(defn- permission-paths-cache-key
  [db resource-type permission-name]
  [(.id db) resource-type permission-name])

(defn- recursive-query-plan-cache-key
  [db root-node]
  [(.id db) root-node])

(def ^:private default-max-depth 50)

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

(defn- build-recursive-query-plan
  [db root-node]
  (let [ordered    (volatile! [])
        seen       (volatile! #{})
        visiting   (volatile! #{})
        recursive? (volatile! false)]
    (letfn [(visit [node]
              (when-not (contains? @seen node)
                (vswap! seen conj node)
                (vswap! ordered conj node)
                (vswap! visiting conj node)
                (doseq [dep (permission-query-dependencies db node)]
                  (when (contains? @visiting dep)
                    (vreset! recursive? true))
                  (when-not (contains? @seen dep)
                    (visit dep)))
                (vswap! visiting disj node)))]
      (visit root-node)
      (let [nodes        @ordered
            node-paths   (into {}
                               (map (fn [[resource-type permission-name :as node]]
                                      [node (vec (get-permission-paths db resource-type permission-name))]))
                               nodes)
            seed-sources (into {}
                               (map (fn [[resource-type _ :as node]]
                                      [node (->> (get node-paths node)
                                                 (map-indexed
                                                  (fn [path-idx path]
                                                    (case (:type path)
                                                      :relation {:kind :relation
                                                                 :path-idx path-idx
                                                                 :subject-type (:subject-type path)
                                                                 :relation-eid (:relation-eid path)}
                                                      :arrow (when (:target-relation path)
                                                               {:kind :arrow-relation
                                                                :path-idx path-idx
                                                                :target-type (:target-type path)
                                                                :via-relation-eid (:via-relation-eid path)
                                                                :sub-paths (vec (:sub-paths path))})
                                                      nil)))
                                                 (remove nil?)
                                                 vec)]))
                               nodes)
            dependents   (reduce
                          (fn [acc [resource-type _ :as node]]
                            (reduce
                             (fn [acc path]
                               (case (:type path)
                                 :self-permission
                                 (update acc
                                         (permission-query-node resource-type (:target-permission path))
                                         (fnil conj [])
                                         {:kind :copy
                                          :node node})

                                 :arrow
                                 (if-let [target-permission (:target-permission path)]
                                   (update acc
                                           (permission-query-node (:target-type path) target-permission)
                                           (fnil conj [])
                                           {:kind :via
                                            :node node
                                            :intermediate-type (:target-type path)
                                            :via-relation-eid (:via-relation-eid path)})
                                   acc)

                                 acc))
                             acc
                             (get node-paths node)))
                          {}
                          nodes)]
        {:root-node root-node
         :nodes nodes
         :recursive? @recursive?
         :seed-sources seed-sources
         :dependents dependents}))))

(defn- recursive-query-plan
  [db root-node]
  (let [cache-key (recursive-query-plan-cache-key db root-node)
        cache     @recursive-query-plan-cache]
    (if (cache/has? cache cache-key)
      (do
        (swap! recursive-query-plan-cache cache/hit cache-key)
        (cache/lookup cache cache-key))
      (let [plan (build-recursive-query-plan db root-node)]
        (swap! recursive-query-plan-cache cache/miss cache-key plan)
        plan))))

(defn- recursive-permission-query?
  [db resource-type permission-name]
  (:recursive? (recursive-query-plan db (permission-query-node resource-type permission-name))))

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

(defn- query-max-depth
  [{:keys [max-depth]}]
  (or max-depth default-max-depth))

(defn- max-depth-exceeded!
  [state node resource-eid]
  (throw
   (ex-info (str "recursive permission query exceeded max depth " (:max-depth state))
            {:type ::max-depth-exceeded
             :max-depth (:max-depth state)
             :node node
             :resource-eid resource-eid})))

(defn- push-tasks
  [stack tasks]
  (reduce conj stack (reverse (remove nil? tasks))))

(defn- matching-sub-path-descriptors
  [subject-type sub-paths]
  (filter #(= subject-type (:subject-type %)) sub-paths))

(defn- initial-recursive-tasks
  [plan subject-type max-depth]
  (->> (:nodes plan)
       (mapcat
        (fn [node]
          (mapcat
           (fn [seed]
             (case (:kind seed)
               :relation
               (when (= subject-type (:subject-type seed))
                 [{:kind :direct-stream
                   :node node
                   :relation-eid (:relation-eid seed)
                   :cursor nil
                   :depth max-depth}])

               :arrow-relation
               (->> (matching-sub-path-descriptors subject-type (:sub-paths seed))
                    (map (fn [sub-path]
                           {:kind :subject-intermediate-stream
                            :node node
                            :intermediate-type (:target-type seed)
                            :subject-relation-eid (:relation-eid sub-path)
                            :via-relation-eid (:via-relation-eid seed)
                            :cursor nil
                            :depth max-depth}))
                    vec)

               []))
           (get-in plan [:seed-sources node]))))
       vec))

(defn- init-recursive-state
  [plan subject-type max-depth]
  {:v 3
   :mode :recursive-forward
   :max-depth max-depth
   :stack (push-tasks [] (initial-recursive-tasks plan subject-type max-depth))
   :best-depth {}
   :emitted #{}
   :last nil})

(defn- recursive-state-for-query
  [plan query]
  (let [max-depth (query-max-depth query)
        cursor    (:cursor query)
        subject-type (:type (:subject query))]
    (cond
      (nil? cursor)
      (init-recursive-state plan subject-type max-depth)

      (= 3 (:v cursor))
      (do
        (when (and (:max-depth cursor) (not= (:max-depth cursor) max-depth))
          (throw (ex-info "recursive query cursor max-depth does not match query max-depth"
                          {:cursor-max-depth (:max-depth cursor)
                           :query-max-depth max-depth})))
        cursor)

      :else
      (throw (ex-info "unsupported cursor version for recursive lookup"
                      {:cursor-version (:v cursor)})))))

(defn- dependent-recursive-tasks
  [plan node resource-eid depth]
  (let [next-depth (dec depth)]
    (mapv
     (fn [dep]
       (case (:kind dep)
         :copy {:kind :copy-fact
                :node (:node dep)
                :resource-eid resource-eid
                :depth next-depth}
         :via {:kind :via-stream
               :node (:node dep)
               :intermediate-type (:intermediate-type dep)
               :intermediate-eid resource-eid
               :via-relation-eid (:via-relation-eid dep)
               :cursor nil
               :depth next-depth}))
     (get-in plan [:dependents node] []))))

(defn- accept-recursive-fact
  [plan state node resource-eid depth]
  (let [prev-depth (get-in state [:best-depth node resource-eid] Long/MIN_VALUE)]
    (cond
      (<= depth prev-depth)
      {:state state
       :emit nil
       :tasks []}

      (neg? depth)
      (max-depth-exceeded! state node resource-eid)

      :else
      (let [state'       (assoc-in state [:best-depth node resource-eid] depth)
            root-node?   (= node (:root-node plan))
            already-out? (contains? (:emitted state') resource-eid)
            [state'' emit]
            (if (and root-node? (not already-out?))
              [(-> state'
                   (update :emitted (fnil conj #{}) resource-eid)
                   (assoc :last resource-eid))
               resource-eid]
              [state' nil])]
        {:state state''
         :emit emit
         :tasks (dependent-recursive-tasks plan node resource-eid depth)}))))

(defn- recursive-next-result
  [db plan subject-type subject-eid state]
  (loop [state state]
    (if-let [task (peek (:stack state))]
      (let [state' (update state :stack pop)]
        (case (:kind task)
          :copy-fact
          (let [{:keys [state emit tasks]}
                (accept-recursive-fact plan state' (:node task) (:resource-eid task) (:depth task))
                next-state (update state :stack push-tasks tasks)]
            (if emit
              {:state next-state
               :emit emit}
              (recur next-state)))

          :direct-stream
          (let [resource-type (first (:node task))
                next-eid      (first (subject->resources db
                                                         subject-type
                                                         subject-eid
                                                         (:relation-eid task)
                                                         resource-type
                                                         (:cursor task)))]
            (if next-eid
              (let [updated-task          (assoc task :cursor next-eid)
                    {:keys [state emit tasks]}
                    (accept-recursive-fact plan state' (:node task) next-eid (:depth task))
                    next-state (update state :stack push-tasks (concat tasks [updated-task]))]
                (if emit
                  {:state next-state
                   :emit emit}
                  (recur next-state)))
              (recur state')))

          :subject-intermediate-stream
          (let [next-intermediate-eid (first (subject->resources db
                                                                 subject-type
                                                                 subject-eid
                                                                 (:subject-relation-eid task)
                                                                 (:intermediate-type task)
                                                                 (:cursor task)))]
            (if next-intermediate-eid
              (let [updated-task (assoc task :cursor next-intermediate-eid)
                    via-task     {:kind :via-stream
                                  :node (:node task)
                                  :intermediate-type (:intermediate-type task)
                                  :intermediate-eid next-intermediate-eid
                                  :via-relation-eid (:via-relation-eid task)
                                  :cursor nil
                                  :depth (:depth task)}
                    next-state   (update state' :stack push-tasks [via-task updated-task])]
                (recur next-state))
              (recur state')))

          :via-stream
          (let [resource-type (first (:node task))
                next-eid      (first (subject->resources db
                                                         (:intermediate-type task)
                                                         (:intermediate-eid task)
                                                         (:via-relation-eid task)
                                                         resource-type
                                                         (:cursor task)))]
            (if next-eid
              (let [updated-task          (assoc task :cursor next-eid)
                    {:keys [state emit tasks]}
                    (accept-recursive-fact plan state' (:node task) next-eid (:depth task))
                    next-state (update state :stack push-tasks (concat tasks [updated-task]))]
                (if emit
                  {:state next-state
                   :emit emit}
                  (recur next-state)))
              (recur state')))))
      {:state state
       :emit nil
       :done? true})))

(defn- recursive-page
  [db plan subject-type subject-eid state limit]
  (loop [state   state
         results []]
    (if (and (>= limit 0)
             (>= (count results) limit))
      {:state state
       :results results}
      (let [{:keys [state emit done?]} (recursive-next-result db plan subject-type subject-eid state)]
        (cond
          emit (recur state (conj results emit))
          done? {:state state
                 :results results}
          :else (recur state results))))))

(declare traverse-permission-path lookup-subject-eids* can*)

(defn traverse-permission-path-via-subject
  [db subject-type subject-eid path resource-type cursor-eid intermediate-cursor-eid visited-paths _depth-left _max-depth]
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
                                                                          next-visited
                                                                          default-max-depth
                                                                          default-max-depth))))
                            (filter seq))]
         (if (seq path-seqs)
           (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
           []))))))

(defn traverse-permission-path-reverse
  [db resource-type resource-eid path subject-type cursor-eid intermediate-cursor-eid visited-paths depth-left max-depth]
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
    {:results (lookup-subject-eids* db
                                    resource-type
                                    resource-eid
                                    (:target-permission path)
                                    subject-type
                                    cursor-eid
                                    (or visited-paths #{})
                                    (dec depth-left)
                                    max-depth)
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
                                     (lookup-subject-eids* db
                                                           intermediate-type
                                                           intermediate-eid
                                                           target-permission
                                                           subject-type
                                                           cursor-eid
                                                           (or visited-paths #{})
                                                           (dec depth-left)
                                                           max-depth))))))))

(defn- lookup-subject-eids*
  [db resource-type resource-eid permission-name subject-type cursor-eid visited-states depth-left max-depth]
  (let [state [resource-type resource-eid permission-name subject-type]]
    (cond
      (contains? visited-states state)
      []

      (neg? depth-left)
      (max-depth-exceeded! {:max-depth max-depth}
                           (permission-query-node resource-type permission-name)
                           resource-eid)

      :else
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
                                                                     cursor-eid
                                                                     nil
                                                                     next-visited
                                                                     depth-left
                                                                     max-depth))))
                           (filter seq))]
        (if (seq path-seqs)
          (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
          [])))))

(defn- can*
  [db subject-type subject-eid permission resource-type resource-eid visited-states depth-left max-depth]
  (let [state [subject-type subject-eid permission resource-type resource-eid]
        paths (get-permission-paths db resource-type permission)]
    (cond
      (contains? visited-states state)
      false

      (neg? depth-left)
      (max-depth-exceeded! {:max-depth max-depth}
                           (permission-query-node resource-type permission)
                           resource-eid)

      :else
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
                    next-visited
                    (dec depth-left)
                    max-depth)

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
                                next-visited
                                (dec depth-left)
                                max-depth))
                        intermediate-eids)))))
          paths))))))

(defn can?
  ([db subject permission resource]
   (can? db {:subject subject
             :permission permission
             :resource resource}))
  ([db {:keys [subject permission resource max-depth]}]
   (let [subject-type  (:type subject)
         subject-eid   (d/entid db (:id subject))
         resource-type (:type resource)
         resource-eid  (d/entid db (:id resource))
         max-depth     (or max-depth default-max-depth)]
     (if (or (nil? subject-eid) (nil? resource-eid))
       false
       (can* db subject-type subject-eid permission resource-type resource-eid #{} max-depth max-depth)))))

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
        max-depth   (query-max-depth query)
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
                                                   #{}
                                                   max-depth
                                                   max-depth)
                                      {:results [] :!state nil})]
                                {:idx idx
                                 :results results
                                 :!state !state})))
                           (filter (comp seq :results))))]
    {:results (if (seq path-results)
                (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity (map :results path-results))
                [])
     :path-results path-results}))

(defn- recursive-forward-lookup
  [db {:as query :keys [subject permission limit] :or {limit 1000}}]
  (let [subject-type  (:type subject)
        subject-eid   (d/entid db (:id subject))
        resource-type (:resource/type query)
        root-node     (permission-query-node resource-type permission)
        plan          (recursive-query-plan db root-node)
        state         (recursive-state-for-query plan query)]
    (if subject-eid
      (let [{:keys [state results]}
            (recursive-page db plan subject-type subject-eid state limit)]
        {:data (mapv #(spice-object resource-type %) results)
         :cursor state})
      {:data []
       :cursor state})))

(defn- recursive-forward-count
  [db {:as query :keys [limit subject permission] :or {limit -1}}]
  (let [subject-type  (:type subject)
        subject-eid   (d/entid db (:id subject))
        resource-type (:resource/type query)
        root-node     (permission-query-node resource-type permission)
        plan          (recursive-query-plan db root-node)
        state         (recursive-state-for-query plan query)]
    (if subject-eid
      (let [{:keys [state results]}
            (recursive-page db plan subject-type subject-eid state limit)]
        {:count (count results)
         :limit limit
         :cursor state})
      {:count 0
       :limit limit
       :cursor state})))

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
  (if (recursive-permission-query? db (:resource/type query) (:permission query))
    (recursive-forward-lookup db query)
    (lookup db forward-direction query)))

(defn lookup-subjects
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (lookup db reverse-direction query))

(defn count-resources
  [db {:as query :keys [limit cursor] :or {limit -1}}]
  (if (recursive-permission-query? db (:resource/type query) (:permission query))
    (recursive-forward-count db query)
    (let [{:keys [results path-results]} (lazy-merged-lookup db forward-direction query)
          limited-results (if (>= limit 0)
                            (take limit results)
                            results)
          counted (doall limited-results)
          last-eid (last counted)]
      {:count (count counted)
       :limit limit
       :cursor (build-v2-cursor cursor last-eid path-results :resource)})))

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
