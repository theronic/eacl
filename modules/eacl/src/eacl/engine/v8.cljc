(ns eacl.engine.v8
  (:require [eacl.backend.v8 :as backend]
            [eacl.core :refer [spice-object]]
            [eacl.lazy-merge-sort :as lazy-sort]
            [eacl.subproblem-cache :as subproblem]))

(def engine-version 8)

(def ^:private default-page-size 1000)
(def ^:private max-page-size 10000)
(def ^:private count-page-size 16384)
(def ^:private lookup-frontier-version 1)
(def ^:private lookup-continuation-version 1)
(def ^:private projection-key-version 1)
(def ^:private denotation-key-version 1)

(def ^:dynamic *projection-chunk-size*
  "Maximum relationship eids retained for one exact projection prefix.

  The cached prefix is deliberately smaller than a potentially unbounded
  adjacency list. A caller that consumes beyond it resumes with an exclusive
  backend seek and does not retain the tail."
  32)

(def ^:dynamic *backend-work-stats*
  "Optional atom populated by tests, benchmarks, and diagnostic callers.

  Counts backend operations actually invoked by the engine. Cache layers
  record avoided work separately; keeping executed and avoided counters
  distinct prevents a cache hit from being mistaken for database work."
  nil)

(defn- record-backend-work!
  [operation]
  (when *backend-work-stats*
    (swap! *backend-work-stats*
           (fn [stats]
             (-> stats
                 (update :executed-backend-operations (fnil inc 0))
                 (update operation (fnil inc 0))))))
  nil)

(defn- warn
  [message data]
  #?(:clj
     (binding [*out* *err*]
       (println message (pr-str data)))
     :cljs
     (.warn js/console message (pr-str data))))

(def ^:private empty-queue
  #?(:clj clojure.lang.PersistentQueue/EMPTY
     :cljs (.-EMPTY cljs.core/PersistentQueue)))

(defn object-eid
  "Resolves an external object id through the snapshot adapter."
  [snapshot id]
  (when (some? id)
    (backend/invoke snapshot :object-id->internal id)))

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
      (page-error! ":before is valid only with :last." {:before (:before query)})

      ;; A present-but-nil boundary used to mean "start over", so a client
      ;; looping on a page-info that carried a nil cursor silently restarted at
      ;; page 1 forever. Absent means first page; nil means the caller lost
      ;; their cursor and must be told.
      (and has-after? (nil? (:after query)))
      (page-error! ":after was passed as nil. Omit it for the first page."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :key :after})

      (and has-before? (nil? (:before query)))
      (page-error! ":before was passed as nil. Omit it for the last page."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :key :before}))

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
     :bound-eid (:bound-eid cursor-or-opts)
     :inclusive-bound? (boolean (:inclusive-bound? cursor-or-opts))}
    {:direction :asc
     :bound-eid cursor-or-opts
     :inclusive-bound? false}))

(defn- merge-eid-seqs
  [direction seqs]
  (case direction
    :asc (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs)
    :desc (lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc identity seqs)))

(defn- path-frontier-identity
  "Stable, semantic identity for a permission path.

  Cursor state must not depend on the incidental ordering of keys in a path
  map. Relation ids make the identity specific to the schema visible at the
  token's pinned backend snapshot."
  [path]
  (case (:type path)
    :relation
    [:relation
     (:subject-type path)
     (:relation-eid path)]

    :self-permission
    [:self-permission
     (:resource-type path)
     (:target-permission path)]

    :arrow
    [:arrow
     (:via-relation-eid path)
     (:target-type path)
     (:target-relation path)
     (:target-permission path)
     (mapv path-frontier-identity (:sub-paths path))]

    [:unknown (pr-str path)]))

(defn- path-frontier-key
  [snapshot path]
  (backend/invoke
   snapshot :frontier-key (path-frontier-identity path)))

(defn- lookup-edge
  ([eid]
   (lookup-edge eid nil nil))
  ([eid frontier-direction path-frontiers]
   (cond-> {:kind :lookup-eid
            :result-eid eid}
     (seq path-frontiers)
     (assoc :frontier-version lookup-frontier-version
            :frontier-direction frontier-direction
            :path-frontiers path-frontiers))))

(defn- page-info
  [{:keys [items has-next? has-previous?]}]
  {:start-cursor (some-> items first :cursor)
   :end-cursor (some-> items last :cursor)
   :has-next-page? (boolean has-next?)
   :has-previous-page? (boolean has-previous?)})

(defn- page-response
  "An empty page carries no cursors, so it can advertise neither direction:
  `has-next-page? true` with a nil `end-cursor` gave clients a loop they could
  not exit. Both flags are therefore clamped to false when there are no items."
  [{:keys [items has-next? has-previous?]}]
  (let [any? (boolean (seq items))]
    {:data (mapv :node items)
     :page-info (page-info {:items items
                            :has-next? (and any? has-next?)
                            :has-previous? (and any? has-previous?)})}))

(defn- lookup-items
  [result-type eids frontier-direction path-frontiers]
  (mapv (fn [eid]
          {:node (spice-object result-type eid)
           :cursor (lookup-edge eid frontier-direction path-frontiers)})
        eids))

(defn- raw-subject->resources
  [snapshot subject-type subject-eid relation-eid resource-type opts]
  (record-backend-work! :subject->resources-scans)
  (backend/invoke snapshot
                  :subject->resources
                  subject-type
                  subject-eid
                  relation-eid
                  resource-type
                  opts))

(defn- raw-resource->subjects
  [snapshot resource-type resource-eid relation-eid subject-type opts]
  (record-backend-work! :resource->subjects-scans)
  (backend/invoke snapshot
                  :resource->subjects
                  resource-type
                  resource-eid
                  relation-eid
                  subject-type
                  opts))

(defn- strictly-ordered?
  [direction values]
  (or (<= (count values) 1)
      (let [ordered?
            (case direction
              :asc <
              :desc >)]
        (every? true?
                (map ordered? values (next values))))))

(defn- valid-projection-chunk?
  [direction maximum-size value]
  (and (map? value)
       (vector? (:values value))
       (boolean? (:terminal? value))
       (every? integer? (:values value))
       (strictly-ordered? direction (:values value))
       (<= (count (:values value)) maximum-size)
       (= (:terminal? value)
          (< (count (:values value)) maximum-size))))

(defn- projection-weight
  [{:keys [values]}]
  (+ 128 (* 16 (count values))))

(defn- cached-projection
  [operation dependency key-args opts raw-scan]
  (if-not subproblem/*store*
    (raw-scan opts)
    (let [chunk-size *projection-chunk-size*
        key [projection-key-version
             operation
             key-args
             (:direction opts)
             (:bound-eid opts)
             (:inclusive-bound? opts)
             chunk-size]
        resolved
        (subproblem/resolve-layered-bound!
         :projection
         key
         {:valid? #(valid-projection-chunk?
                    (:direction opts) chunk-size %)
          :weight-fn projection-weight}
         dependency
         (fn []
           (let [values (vec (take chunk-size (raw-scan opts)))
                 chunk {:values values
                        :terminal? (< (count values) chunk-size)}]
             (subproblem/add-fetched-projection-values! (count values))
             chunk)))
        {:keys [values terminal?]} (:value resolved)]
    (when (:cached? resolved)
      (subproblem/record-avoided-backend-operation!))
      (if (or terminal? (empty? values))
        values
        (concat
         values
         (lazy-seq
          (cached-projection
           operation
           dependency
           key-args
           (assoc opts
                  :bound-eid (peek values)
                  :inclusive-bound? false)
           raw-scan)))))))

(defn- cached-acyclic-denotation
  [operation key-args opts raw-evaluate]
  (if-not subproblem/*store*
    (raw-evaluate opts)
    (let [chunk-size *projection-chunk-size*
          key [denotation-key-version
               operation
               key-args
               (:direction opts)
               (:bound-eid opts)
               (:inclusive-bound? opts)
               chunk-size]
          resolved
          (subproblem/resolve-bound!
           :denotation
           key
           {:valid? #(valid-projection-chunk?
                      (:direction opts) chunk-size %)
            :weight-fn projection-weight}
           (fn []
             (let [values
                   (vec (take chunk-size (raw-evaluate opts)))]
               {:values values
                :terminal? (< (count values) chunk-size)})))
          {:keys [values terminal?]} (:value resolved)]
      (when (:cached? resolved)
        (subproblem/record-acyclic-denotation-hit!))
      (if (or terminal? (empty? values))
        values
        (concat
         values
         (lazy-seq
          (cached-acyclic-denotation
           operation
           key-args
           (assoc opts
                  :bound-eid (peek values)
                  :inclusive-bound? false)
           raw-evaluate)))))))

(defn subject->resources
  [snapshot subject-type subject-eid relation-eid resource-type cursor-or-opts]
  (let [opts (scan-opts cursor-or-opts)]
    (cached-projection
     :subject->resources
     relation-eid
     [subject-type subject-eid relation-eid resource-type]
     opts
     #(raw-subject->resources
       snapshot subject-type subject-eid relation-eid resource-type %))))

(defn resource->subjects
  [snapshot resource-type resource-eid relation-eid subject-type cursor-or-opts]
  (let [opts (scan-opts cursor-or-opts)]
    (cached-projection
     :resource->subjects
     relation-eid
     [resource-type resource-eid relation-eid subject-type]
     opts
     #(raw-resource->subjects
       snapshot resource-type resource-eid relation-eid subject-type %))))

(defn relation-datoms
  "Compatibility shape used by the extracted algorithm while storage adapters
  return normalized relation-definition maps."
  [snapshot resource-type relation-name]
  (mapv (fn [{:keys [relation-id subject-type]}]
          {:e relation-id
           :v [resource-type relation-name subject-type]})
        (backend/invoke
         snapshot :relation-defs resource-type relation-name)))

(defn find-relation-def
  "Compatibility helper retained for tests.
  Returns the first matching relation definition, if any."
  [snapshot resource-type relation-name]
  (when-let [{:keys [e v]}
             (first
              (relation-datoms snapshot resource-type relation-name))]
    {:db/id e
     :eacl.relation/resource-type (nth v 0)
     :eacl.relation/relation-name (nth v 1)
     :eacl.relation/subject-type (nth v 2)}))

(defn find-permission-defs
  [snapshot resource-type permission-name]
  (mapv
   (fn [{:keys [permission-id
                resource-type
                permission-name
                source-relation-name
                target-type
                target-name]}]
     {:db/id permission-id
      :eacl.permission/resource-type resource-type
      :eacl.permission/permission-name permission-name
      :eacl.permission/source-relation-name source-relation-name
      :eacl.permission/target-type target-type
      :eacl.permission/target-name target-name})
   (backend/invoke
    snapshot :permission-defs resource-type permission-name)))

(defn resolve-self-relation
  [snapshot resource-type target-relation-name]
  (let [datoms (relation-datoms
                snapshot resource-type target-relation-name)]
    (if (seq datoms)
      (map (fn [datom]
             {:type :relation
              :name target-relation-name
              :subject-type (nth (:v datom) 2)
              :relation-eid (:e datom)})
           datoms)
      (do
        (warn "Missing Relation definition"
              {:resource-type resource-type
               :relation-name target-relation-name})
        []))))

;; --- Selected-snapshot permission-path cache (issue #74) ---------------------
;;
;; A client owns derived-schema generations keyed by backend, source scope, and
;; the schema proof visible in the selected immutable snapshot. Managed writer
;; authority makes that proof one atomically published mutation identity.
;; Unknown writer authority deliberately uses a complete content proof instead:
;; detecting arbitrary out-of-band schema writes without trusting a listener or
;; writer-maintained stamp requires reading the database-visible definitions.
;;
;; Permission paths, dependency closures, and recursive-routing decisions are
;; memoized inside one proof generation. Their cold compilation cost is paid
;; once per queried permission root and schema proof, then reused. Low-level
;; functions remain uncached unless a client binds *schema-cache*, so arbitrary
;; d/with/filter/as-of values cannot publish derived state into another
;; snapshot's generation.

(def ^:dynamic *schema-cache*
  "The client-owned schema cache bound by eacl.datomic.core.

  nil means raw/arbitrary-db evaluation and is deliberately uncached."
  nil)

(defn schema-version
  "The schema proof visible in this immutable backend snapshot, or nil before
  the first supported schema write."
  [snapshot]
  (backend/invoke snapshot :schema-proof))

(defn schema-version-stamp
  "String form of the schema proof visible in a snapshot."
  [snapshot]
  (some-> (schema-version snapshot) str))

(defn make-schema-cache
  "Creates a derived-schema generation for one selected schema proof.

  A nil proof deliberately disables derived-state latching."
  ([snapshot]
   (make-schema-cache snapshot (schema-version snapshot)))
  ([snapshot known-schema-version]
   {:database-id (:database-id (backend/invoke snapshot :snapshot-id))
    :schema-version known-schema-version
    :permission-paths (atom {})
    :traversal-permissions (atom {})
    :traversal-analysis (atom nil)
    :relationship-dependencies (atom {})
    :recursive-plans (atom {})
    :direct-grant-relations (atom {})}))

(defn schema-cache-key
  "Identity of schema-derived state for one selected immutable snapshot.

  The key deliberately contains no listener/client counter. A missed callback
  cannot make a cache entry cross a source or schema proof boundary."
  ([snapshot]
   (schema-cache-key
    snapshot
    (backend/invoke snapshot :schema-proof)))
  ([snapshot schema-proof]
   [(backend/backend-id snapshot)
    (backend/invoke snapshot :source-scope)
    schema-proof]))

(defn schema-cache-for!
  "Returns a derived-schema generation keyed by selected source and proof."
  [registry snapshot]
  (let [schema-proof (backend/invoke snapshot :schema-proof)
        key (schema-cache-key snapshot schema-proof)
        existing (get @registry key)]
    (if existing
      existing
      (let [created
            (make-schema-cache
             snapshot
             schema-proof)]
        (get (swap! registry
                    #(if (contains? % key)
                       %
                       (assoc % key created)))
             key)))))

(defn- permission-paths-cache-key
  [resource-type permission-name]
  [resource-type permission-name])

(defn evict-permission-paths-cache!
  "Clears one client generation's derived entries without rereading schema.
  With no bound/provided client cache this is intentionally a no-op."
  ([]
   (when *schema-cache*
     (evict-permission-paths-cache! *schema-cache*)))
  ([schema-cache]
   (reset! (:permission-paths schema-cache) {})
   (reset! (:traversal-permissions schema-cache) {})
   (some-> (:traversal-analysis schema-cache) (reset! nil))
   (reset! (:relationship-dependencies schema-cache) {})
   (some-> (:recursive-plans schema-cache) (reset! {}))
   (some-> (:direct-grant-relations schema-cache) (reset! {}))
   nil))

(defn- path-check-rank
  "Static cost order for short-circuit evaluation of a union permission.

  A plain relation is one datom lookup; a self-permission recurses on the same
  resource without adding fan-out; an arrow enumerates or intersects the
  resource's intermediates, and an arrow to a permission may recurse per
  intermediate.

  Paths previously came out in the order `find-permission-defs` happened to
  return them, which traces back to a clojure.set/difference in write-schema!
  — so whether `owner + team->access` checked `owner` or the arrow first was
  hash order, and unobservable to the schema author. On identical data that was
  measured at 6.8ms versus 3.4us. Sorting here rather than at each call site
  means the order is computed once per schema generation and every consumer
  sees a deterministic one."
  [path]
  (case (:type path)
    :relation 0
    :self-permission 1
    :arrow (if (:target-relation path) 2 3)
    4))

(defn calc-permission-paths
  "Returns path maps with resolved relation eids, cheapest-to-check first.
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
                                       (warn "Missing target relation definition"
                                             {:intermediate-type intermediate-type
                                              :target-relation-name target-name})
                                       []))))))
                 datoms)
                (do
                  (warn "Missing source relation definition"
                        {:resource-type resource-type
                         :via-relation-name source-relation-name})
                  []))))))
       (sort-by path-check-rank)
       vec))

(defn get-permission-paths
  [db resource-type permission-name]
  (if-not (some? (:schema-version *schema-cache*))
    (calc-permission-paths db resource-type permission-name)
    (let [cache-key (permission-paths-cache-key resource-type permission-name)
          cache-atom (:permission-paths *schema-cache*)
          snapshot @cache-atom]
      (if (contains? snapshot cache-key)
        (get snapshot cache-key)
        (let [paths (calc-permission-paths db resource-type permission-name)]
          (get (swap! cache-atom
                      #(if (contains? % cache-key)
                         %
                         (assoc % cache-key paths)))
               cache-key))))))

(defn- frontier-permission-paths
  "Expands same-resource permission aliases into independently resumable paths.

  A self-permission has no intermediate frontier of its own. Keeping it as a
  top-level lookup path would discard the frontiers of any arrow paths below
  it, forcing every page to replay those intermediates."
  [db resource-type permission-name]
  (letfn [(expand [permission-name visited-nodes]
            (let [node [resource-type permission-name]]
              (if (contains? visited-nodes node)
                []
                (let [visited-nodes' (conj visited-nodes node)]
                  (mapcat (fn [path]
                            (if (= :self-permission (:type path))
                              (expand (:target-permission path) visited-nodes')
                              [path]))
                          (get-permission-paths db resource-type permission-name))))))]
    ;; `permission view = owner + admin` where `permission admin = owner`
    ;; expands to the same relation path twice: scanned twice, and both
    ;; collapsing onto one path-frontier-key anyway.
    (->> (expand permission-name #{})
         (reduce (fn [{:keys [seen paths] :as acc} path]
                   (let [k (path-frontier-identity path)]
                     (if (contains? seen k)
                       acc
                       {:seen (conj seen k)
                        :paths (conj paths path)})))
                 {:seen #{} :paths []})
         :paths)))

(defn- permission-query-node
  [resource-type permission-name]
  [resource-type permission-name])

(defn- calc-permission-relationship-eids
  [db resource-type permission-name]
  (loop [stack [(permission-query-node resource-type permission-name)]
         seen #{}
         relationship-eids #{}]
    (if-let [[node-resource-type node-permission :as node] (peek stack)]
      (if (contains? seen node)
        (recur (pop stack) seen relationship-eids)
        (let [paths (get-permission-paths db node-resource-type node-permission)
              next-nodes
              (keep (fn [path]
                      (case (:type path)
                        :self-permission
                        (permission-query-node node-resource-type
                                               (:target-permission path))

                        :arrow
                        (when-let [target-permission (:target-permission path)]
                          (permission-query-node (:target-type path)
                                                 target-permission))

                        nil))
                    paths)
              relationship-eids'
              (reduce
               (fn [result path]
                 (cond-> result
                   (:relation-eid path)
                   (conj (:relation-eid path))

                   (:via-relation-eid path)
                   (conj (:via-relation-eid path))

                   (seq (:sub-paths path))
                   (into (keep :relation-eid (:sub-paths path)))))
               relationship-eids
               paths)]
          (recur (into (pop stack) next-nodes)
                 (conj seen node)
                 relationship-eids')))
      (vec (sort relationship-eids)))))

(defn permission-relationship-eids
  "Returns the sorted relation-definition eids whose relationship tuples can
  affect one permission lookup. A stamped client memoises the vector for its
  schema generation, so live-result reads do not sort dependencies."
  [db resource-type permission-name]
  (if-not (some? (:schema-version *schema-cache*))
    (calc-permission-relationship-eids db resource-type permission-name)
    (let [cache-key (permission-paths-cache-key resource-type permission-name)
          cache-atom (:relationship-dependencies *schema-cache*)
          snapshot @cache-atom]
      (if (contains? snapshot cache-key)
        (get snapshot cache-key)
        (let [dependencies
              (calc-permission-relationship-eids
               db resource-type permission-name)]
          (get (swap! cache-atom
                      #(if (contains? % cache-key)
                         %
                         (assoc % cache-key dependencies)))
               cache-key))))))

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

(defn permission-schema-nodes
  "Returns the permission-definition nodes that can affect one permission
  root. Cache adapters use this as an opaque proof scope so unrelated schema
  definitions do not invalidate an otherwise exact authorization answer."
  [db resource-type permission-name]
  (set
   (reachable-permission-query-nodes
    db
    (permission-query-node resource-type permission-name))))

(defn- permission-graph
  [db nodes]
  (let [node-set (set nodes)]
    (into {}
          (map (fn [node]
                 [node
                  (vec
                   (filter node-set
                           (permission-query-dependencies db node)))])
               nodes))))

(defn- transpose-graph
  [nodes graph]
  (reduce-kv
   (fn [result node dependencies]
     (reduce (fn [result dependency]
               (update result dependency conj node))
             result
             dependencies))
   (zipmap nodes (repeat []))
   graph))

(defn- postorder-from
  [graph root initial-seen initial-order]
  (loop [stack [[root false]]
         seen initial-seen
         order initial-order]
    (if-let [[node expanded?] (peek stack)]
      (cond
        expanded?
        (recur (pop stack) seen (conj order node))

        (contains? seen node)
        (recur (pop stack) seen order)

        :else
        (let [dependencies (get graph node)]
          (recur (into (conj (pop stack) [node true])
                       (map #(vector % false)
                            (reverse dependencies)))
                 (conj seen node)
                 order)))
      [seen order])))

(defn- graph-postorder
  [nodes graph]
  (second
   (reduce (fn [[seen order] node]
             (if (contains? seen node)
               [seen order]
               (postorder-from graph node seen order)))
           [#{} []]
           nodes)))

(defn- collect-component
  [graph root initial-seen]
  (loop [stack [root]
         seen initial-seen
         component []]
    (if-let [node (peek stack)]
      (if (contains? seen node)
        (recur (pop stack) seen component)
        (recur (into (pop stack)
                     (reverse (get graph node)))
               (conj seen node)
               (conj component node)))
      [seen component])))

(defn- graph-components
  "Returns deterministic strongly connected components in O(V+E) time and
  memory using iterative Kosaraju passes."
  [nodes graph]
  (let [transposed (transpose-graph nodes graph)
        roots (reverse (graph-postorder nodes graph))]
    (second
     (reduce (fn [[seen components] root]
               (if (contains? seen root)
                 [seen components]
                 (let [[seen component]
                       (collect-component transposed root seen)]
                   [seen (conj components component)])))
             [#{} []]
             roots))))

(defn- reachable-from-many
  [graph roots]
  (loop [stack (vec roots)
         seen #{}]
    (if-let [node (peek stack)]
      (if (contains? seen node)
        (recur (pop stack) seen)
        (recur (into (pop stack) (get graph node))
               (conj seen node)))
      seen)))

(defn permission-schema-components
  "Returns deterministic strongly connected permission components reachable
  from one permission root. The implementation is deliberately iterative:
  deeply nested schemas do not consume the host stack."
  [db resource-type permission-name]
  (let [root (permission-query-node resource-type permission-name)
        nodes (sort (reachable-permission-query-nodes db root))
        graph (permission-graph db nodes)]
    (mapv (comp vec sort)
          (graph-components nodes graph))))

(defn- recursive-permission-query?
  [db resource-type permission-name]
  (let [components
        (permission-schema-components db resource-type permission-name)]
    (boolean
     (some (fn [component]
             (or (> (count component) 1)
                 (let [node (first component)]
                   (some #{node}
                         (permission-query-dependencies db node)))))
           components))))

(defn- calc-traversal-analysis
  "Classifies every permission node into one shared schema-generation result.

  Once permission paths are materialized, one iterative SCC pass plus reverse
  reachability is O(V+E) in the permission graph. Sharing the resulting map
  prevents a large schema from recompiling recursive routing independently for
  every first-read root."
  [db]
  (let [nodes (vec (set (backend/invoke db :all-permission-nodes)))
        graph (permission-graph db nodes)
        transposed (transpose-graph nodes graph)
        recursive-nodes
        (->> (graph-components nodes graph)
             (filter (fn [component]
                       (or (> (count component) 1)
                           (let [node (first component)]
                             (some #{node} (get graph node))))))
             (mapcat identity)
             set)
        traversal-nodes
        (reachable-from-many transposed recursive-nodes)]
    (into {}
          (map (fn [node]
                 [node (contains? traversal-nodes node)])
               nodes))))

(defn- resume-scan-opts
  "Scan options that resume an intermediate's result stream strictly after
  `resume-eid`, or the page's own options when there is nothing to resume."
  [direction page-opts resume-eid]
  (if resume-eid
    {:direction direction :bound-eid resume-eid :inclusive-bound? false}
    page-opts))

(defn- arrow-via-intermediates
  "Merges one result stream per intermediate into a single ordered stream.

  `head-state`, when present, makes this resumable across pages:

    :cached   {intermediate-eid -> head-eid} proved beyond this page's bound
              while producing the previous page.
    :observed an atom collecting each opened stream's head, from which the
              next page's :cached map is derived.

  Without it every page must open an index scan for every intermediate just to
  learn where each stream starts, which is O(intermediates) per page and
  O(intermediates * results / page-size) over a full walk. A cached head is
  returned without touching the index, and the rest of that stream is only
  opened if this page actually consumes past it — so a page costs one scan per
  intermediate it draws from, not one per intermediate that exists."
  ([intermediate-eids result-fn]
   (arrow-via-intermediates :asc intermediate-eids result-fn nil))
  ([direction intermediate-eids result-fn]
   (arrow-via-intermediates direction intermediate-eids result-fn nil))
  ([direction intermediate-eids result-fn head-state]
   (let [cached (:cached head-state)
         observed (:observed head-state)
         note-head! (fn [intermediate-eid head]
                      (when observed
                        (swap! observed assoc intermediate-eid head)))
         pairs (keep (fn [intermediate-eid]
                       (if-let [head (get cached intermediate-eid)]
                         (do
                           (note-head! intermediate-eid head)
                           {:int-eid intermediate-eid
                            :results (cons head
                                           (lazy-seq
                                            (result-fn intermediate-eid head)))})
                         (let [results (seq (result-fn intermediate-eid nil))]
                           (when results
                             (note-head! intermediate-eid (first results))
                             {:int-eid intermediate-eid
                              :results results}))))
                     intermediate-eids)
         first-pair (first pairs)
         result-seqs (map :results pairs)]
     {:results (if (seq result-seqs)
                 (merge-eid-seqs direction result-seqs)
                 [])
      ;; Inclusive frontier: the next page rechecks the first intermediate that
      ;; can still contribute above/below the current global result boundary.
      ;; If none can contribute, later pages in the same direction can skip the
      ;; path permanently.
      :frontier (if first-pair
                  (:int-eid first-pair)
                  :exhausted)})))

(defn direct-match-datoms-in-relationship-index
  [snapshot subject-type subject-eid relation-eid resource-type resource-eid]
  (let [resolved
        (subproblem/resolve-layered-bound!
         :projection
         [projection-key-version
          :direct-match?
          subject-type subject-eid relation-eid resource-type resource-eid]
         {:valid? boolean?
          :weight-fn (constantly 96)}
         relation-eid
         (fn []
           (record-backend-work! :direct-match-probes)
           (boolean
            (backend/invoke snapshot
                            :direct-match?
                            subject-type
                            subject-eid
                            relation-eid
                            resource-type
                            resource-eid))))]
    (when (:cached? resolved)
      (subproblem/record-avoided-backend-operation!))
    (if (:value resolved)
    [true]
    [])))

(defn- matching-relation-sub-paths
  [sub-paths subject-type]
  (filter #(and (= :relation (:type %))
                (= subject-type (:subject-type %)))
          sub-paths))

(defn all-permission-nodes
  [snapshot]
  (set (backend/invoke snapshot :all-permission-nodes)))

(defn traversal-permission?
  "True when a permission root transitively depends on a recursive permission SCC.
  These roots cannot be proven page-bounded in eid order without materialized
  grants, so list APIs evaluate them in deterministic traversal order.

  A stamped connection-backed client shares one classification for every
  permission node in a schema generation. Raw db evaluation and unstamped
  clients recompute the requested root."
  [db resource-type permission-name]
  (if-not (some? (:schema-version *schema-cache*))
    (recursive-permission-query? db resource-type permission-name)
    (if-let [analysis-atom (:traversal-analysis *schema-cache*)]
      (let [analysis-delay
            (or @analysis-atom
                (let [candidate
                      (delay (calc-traversal-analysis db))]
                  (swap! analysis-atom #(or % candidate))))
            analysis @analysis-delay]
        (boolean
         (get analysis
              (permission-paths-cache-key
               resource-type permission-name))))
      ;; Compatibility for externally constructed schema-cache maps.
      (let [cache-key
            (permission-paths-cache-key resource-type permission-name)
            cache-atom (:traversal-permissions *schema-cache*)
            snapshot @cache-atom]
        (if (contains? snapshot cache-key)
          (get snapshot cache-key)
          (let [recursive?
                (recursive-permission-query?
                 db resource-type permission-name)]
            (get (swap! cache-atom
                        #(if (contains? % cache-key)
                           %
                           (assoc % cache-key recursive?)))
                 cache-key)))))))

(defn traversal-nodes
  [db]
  (->> (all-permission-nodes db)
       (filter (fn [[resource-type permission-name]]
                 (traversal-permission? db resource-type permission-name)))
       set))

(def ^:private recursive-engine-version 1)

(def default-recursive-traversal-limits
  "Safety ceilings for one recursive traversal.

  These bound a SINGLE page computation. A cache-disabled recursive cursor may
  replay its traversal prefix when its relationship proof is still current.
  Cache-enabled cursors resume a retained continuation and fail explicitly
  when that continuation has expired.

  These are host-JVM memory bounds, not ordinary pagination controls. Use
  :count-limit for bounded counts, model large permissions acyclically, or tune
  :recursive-traversal-limits only after representative heap/load tests."
  {:max-derived-grants 100000
   :max-advanced-datoms 100000
   :max-queued-work 100000})

(defn normalize-recursive-traversal-limits
  [overrides]
  (let [overrides (or overrides {})
        known (set (keys default-recursive-traversal-limits))
        unknown (seq (remove known (keys overrides)))]
    (when-not (map? overrides)
      (throw (ex-info ":recursive-traversal-limits must be a map."
                      {:type :eacl/invalid-config
                       :recursive-traversal-limits overrides})))
    (when unknown
      (throw (ex-info "Unknown recursive traversal safety limit."
                      {:type :eacl/invalid-config
                       :unknown-keys (vec unknown)
                       :known-keys known})))
    (when-not (every? (fn [[_ value]]
                        (and (integer? value) (pos? value)))
                      overrides)
      (throw (ex-info "Recursive traversal safety limits must be positive integers."
                      {:type :eacl/invalid-config
                       :recursive-traversal-limits overrides})))
    (merge default-recursive-traversal-limits overrides)))

(def ^:dynamic *recursive-traversal-limits*
  default-recursive-traversal-limits)

(def ^:dynamic *recursive-traversal-stats* nil)

(def ^:dynamic *count-stats*
  "Optional atom recording bounded count-page work for tests/benchmarks."
  nil)

(defn- recursive-traversal-error!
  [message data]
  (throw (ex-info message data)))

(defn- inc-stat!
  [k]
  (when *recursive-traversal-stats*
    (swap! *recursive-traversal-stats* update k (fnil inc 0))))

(defn- add-stat!
  [k n]
  (when *recursive-traversal-stats*
    (swap! *recursive-traversal-stats* update k (fnil + 0) n)))

(defn- increment-counter
  [state counter-key limit-key]
  (let [n (inc (get-in state [:counters counter-key] 0))
        limit (get *recursive-traversal-limits* limit-key)]
    (when (and limit (> n limit))
      (recursive-traversal-error!
       (str "Recursive traversal safety limit exceeded (" (name counter-key) " > " limit ")."
            " A cache-disabled deep recursive page may replay its traversal prefix."
            " Use :count-limit for bounded counts,"
            " model the permission acyclically, or tune make-client's"
            " :recursive-traversal-limits only after heap/load testing.")
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
                           {:id (rule-id node :arrow-relation path [(:subject-type sub-path)
                                                                    (:relation-eid sub-path)])
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

(defn- rules-by-node
  [rules]
  (->> rules
       (group-by :node)
       (into {}
             (map (fn [[node node-rules]]
                    [node (vec (sort-by :id node-rules))])))))

(def ^:private recursive-component-version 1)

(defn- recursive-component-id
  [nodes]
  [:permission-scc recursive-component-version (vec (sort nodes))])

(defn- compile-recursive-components
  "Compiles the permission condensation DAG for one root.

  Component identities are structural and stable for a schema generation.
  Dependencies are ordered before their consumers, so a future fixed-point
  evaluator can publish only completed components without depending on host
  map/set iteration order."
  [db root-node]
  (let [nodes (sort (reachable-permission-query-nodes db root-node))
        graph (permission-graph db nodes)
        components (mapv (comp vec sort)
                         (graph-components nodes graph))
        node->component-id
        (into {}
              (mapcat
               (fn [component]
                 (let [component-id (recursive-component-id component)]
                   (map #(vector % component-id) component)))
               components))
        component-map
        (into {}
              (map
               (fn [component]
                 (let [component-id (recursive-component-id component)
                       dependencies
                       (->> component
                            (mapcat #(get graph %))
                            (map node->component-id)
                            (remove #{component-id})
                            set)
                       recursive?
                       (or (> (count component) 1)
                           (let [node (first component)]
                             (boolean (some #{node} (get graph node)))))]
                   [component-id
                    {:id component-id
                     :nodes component
                     :dependencies dependencies
                     :recursive? recursive?}]))
               components))
        ordered-ids
        (loop [remaining (set (keys component-map))
               completed #{}
               order []]
          (if (empty? remaining)
            order
            (let [ready
                  (->> remaining
                       (filter #(every? completed
                                        (:dependencies
                                         (get component-map %))))
                       sort
                       vec)]
              (when (empty? ready)
                (throw
                 (ex-info
                  "Permission SCC condensation graph is unexpectedly cyclic."
                  {:type :eacl/internal-schema-error
                   :root-node root-node
                   :remaining remaining})))
              (recur (reduce disj remaining ready)
                     (into completed ready)
                     (into order ready)))))]
    {:root-component-id (get node->component-id root-node)
     :node->component-id node->component-id
     :components
     (mapv (fn [index component-id]
             (-> (get component-map component-id)
                 (update :dependencies #(vec (sort %)))
                 (assoc :order index)))
           (range)
           ordered-ids)}))

(defn- compile-recursive-plan
  [db root-node]
  (inc-stat! :compiled-recursive-plans)
  (let [rules (compile-recursive-rules db root-node)
        component-plan (compile-recursive-components db root-node)
        rules-by-node' (rules-by-node rules)]
    (merge
     component-plan
     {:rules rules
      :forward-consumers (forward-consumers rules)
      :rules-by-node rules-by-node'
      :rules-by-component
      (into {}
            (map
             (fn [{:keys [id nodes]}]
               [id (vec (mapcat #(get rules-by-node' %) nodes))])
             (:components component-plan)))})))

(defn- recursive-plan
  "Returns the immutable traversal plan for one permission root.

  Compilation depends only on the schema proof, never on graph relationships,
  subject, resource, direction, or page boundary. A client generation may
  therefore share the plan across principals and requests without sharing an
  authorization answer or request-local traversal state."
  [db root-node]
  (let [cache-atom (:recursive-plans *schema-cache*)]
    (if-not (and cache-atom (some? (:schema-version *schema-cache*)))
      (compile-recursive-plan db root-node)
      (let [candidate (delay (compile-recursive-plan db root-node))
            plan-delay
            (get
             (swap! cache-atom
                    #(if (contains? % root-node)
                       %
                       (assoc % root-node candidate)))
             root-node)]
        @plan-delay))))

(defn recursive-component-plan
  "Returns the schema-derived SCC plan used by recursive evaluation.

  This diagnostic surface deliberately omits executable rules. It is useful
  to certification tests and cache-key audits without exposing mutable
  request-local traversal state."
  [db resource-type permission-name]
  (select-keys
   (recursive-plan
    db (permission-query-node resource-type permission-name))
   [:root-component-id :node->component-id :components]))

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

(defn continuation-weight
  [state]
  ;; Conservative retained-work estimate. Cache bounds are admission controls,
  ;; not claims about exact JVM object layout. Each queued work item is charged
  ;; for its maximum 64-EID chunk, scan descriptor, and closure. Keeping this
  ;; calculation O(1) in queue contents is important: walking the frontier to
  ;; weigh every emitted page would itself recreate quadratic pagination work.
  (+ 4096
     (* 2048 (count (:queue state)))
     ;; A reverse grant is retained both as a dedupe key and in its goal bucket.
     (* 256 (count (:seen-grants state)))
     (* 96 (count (:emitted-root state)))
     (* 128 (count (:emitted-subjects state)))
     (* 160 (count (:seen-goals state)))
     (* 160 (count (:grants-by-goal state)))
     ;; Reverse continuations retain the compiled rule graph. New states carry
     ;; the scalar count so weighing each emitted page stays O(1); the fallback
     ;; handles an in-process continuation created before a code reload.
     (* 128 (count (:rules-by-node state)))
     (* 512 (long (or (:rule-count state)
                      (reduce + 0
                              (map count
                                   (vals (:rules-by-node state)))))))
     (* 512 (long (or (:consumer-count state)
                      (count (:consumers state)))))))

(defn- cached-continuation
  [continuation-cache bound direction result-kind]
  (when-let [get-continuation (:get continuation-cache)]
    (when-let [{:keys [engine-version state] :as continuation}
               (try
                 (get-continuation bound)
                 (catch #?(:clj Exception :cljs :default) _
                   nil))]
      (when (and (= recursive-engine-version engine-version)
                 (= direction (:direction continuation))
                 (= result-kind (:result-kind continuation))
                 (= bound (:bound continuation))
                 (= (inc (:ordinal bound)) (:ordinal state)))
        (inc-stat! :continuation-hits)
        continuation))))

(defn- note-continuation-miss!
  "A missing continuation is never fatal: the caller replays the cursor's
  deterministic prefix against its pinned historical basis."
  [bound continuation]
  (when (and bound (nil? continuation))
    (inc-stat! :continuation-misses))
  continuation)

(defn- evict-continuation!
  [continuation-cache edge]
  (when-let [evict! (:evict! continuation-cache)]
    (try
      (evict! edge)
      (catch #?(:clj Exception :cljs :default) _
        false))))

(defn- store-continuation!
  [continuation-cache edge direction result-kind state]
  (when-let [put-continuation! (:put! continuation-cache)]
    (try
      (put-continuation!
       edge
       {:engine-version recursive-engine-version
        :direction direction
        :result-kind result-kind
        :bound edge
        :state state}
       (continuation-weight state))
      (catch #?(:clj Exception :cljs :default) _
        false))))

;; NOTE ON `traversal` VS `page-direction`
;;
;; `traversal` is the traversal axis and is CONSTANT per API: :forward for
;; lookup-resources, :reverse for lookup-subjects. It says nothing about which
;; way the caller is paginating.
;;
;; `page-direction` is the pagination axis: :asc for :first/:after, :desc for
;; :last/:before. It MUST be part of any key derived from the caller's bound,
;; because {:first N :after C} and {:last N :before C} carry the same bound and
;; size while naming completely different pages.

(defn- recursive-page-end-key
  "Physical key for a page by the ordinal it ENDS on.

  Deliberately not scoped by page-direction: a page ending at ordinal k with
  size N covers ordinals k-N+1..k whichever way it was produced, so an :asc
  page is a valid answer for a :desc request bounded just past it. An :asc page
  can only be short when it is the final page, in which case no :before cursor
  past it exists."
  [traversal result-kind end-ordinal size]
  [:end traversal result-kind end-ordinal size])

(defn- recursive-page-request-key
  "Physical key for one requested page, scoped by the caller's pagination axis.

  Omitting page-direction here let a :last/:before page be served to a later
  :first/:after request with the same cursor and size."
  [traversal result-kind page-direction bound size]
  [:request traversal result-kind page-direction bound size])

(defn- cached-recursive-request-page
  [continuation-cache traversal result-kind page-direction bound size]
  (when-let [get-page (:get-page continuation-cache)]
    (when-let [page (try
                      (get-page
                       (recursive-page-request-key
                        traversal result-kind page-direction bound size))
                      (catch #?(:clj Exception :cljs :default) _
                        nil))]
      (inc-stat! :recursive-page-hits)
      page)))

(defn- cached-recursive-previous-page
  [continuation-cache traversal result-kind bound size]
  (when-let [get-page (:get-page continuation-cache)]
    (when-let [page (try
                      (get-page
                       (recursive-page-end-key traversal
                                               result-kind
                                               (dec (:ordinal bound))
                                               size))
                      (catch #?(:clj Exception :cljs :default) _
                        nil))]
      (when (and (map? page)
                 (vector? (:data page))
                 (<= (count (:data page)) size)
                 (= (dec (:ordinal bound))
                    (get-in page [:page-info :end-cursor :ordinal])))
        (inc-stat! :recursive-page-hits)
        page))))

(defn- store-recursive-page!
  [continuation-cache traversal result-kind page-direction bound size page]
  (when-let [put-page! (:put-page! continuation-cache)]
    (let [weight (+ 512 (* 192 (count (:data page))))
          request-stored?
          (try
            (put-page! (recursive-page-request-key
                        traversal result-kind page-direction bound size)
                       page
                       weight)
            (catch #?(:clj Exception :cljs :default) _
              false))]
      (when-let [end-ordinal (get-in page [:page-info :end-cursor :ordinal])]
        (try
          (put-page! (recursive-page-end-key
                      traversal result-kind end-ordinal size)
                     page
                     weight)
          (catch #?(:clj Exception :cljs :default) _
            false)))
      request-stored?)))

(defn- valid-cursor-eid?
  [eid]
  (and (integer? eid)
       (<= eid #?(:clj Long/MAX_VALUE
                  :cljs js/Number.MAX_SAFE_INTEGER))
       (pos? eid)))

(defn- validate-lookup-eid-bound!
  [bound]
  (when bound
    (when-not (= :lookup-eid (:kind bound))
      (page-error! "Lookup page cursor has the wrong kind."
                   {:eacl/error :eacl.pagination/wrong-cursor-kind
                    :expected :lookup-eid
                    :actual (:kind bound)}))
    (when-not (valid-cursor-eid? (:result-eid bound))
      (page-error! "Lookup page cursor has an invalid result boundary."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :result-eid (:result-eid bound)}))
    (when-let [frontiers (:path-frontiers bound)]
      (when-not (map? frontiers)
        (page-error! "Lookup page cursor has invalid path frontiers."
                     {:eacl/error :eacl.pagination/invalid-cursor}))
      (when-not (= lookup-frontier-version (:frontier-version bound))
        (page-error! "Lookup page cursor has an unsupported frontier version."
                     {:eacl/error :eacl.pagination/invalid-cursor
                      :expected lookup-frontier-version
                      :actual (:frontier-version bound)}))
      (when-not (#{:asc :desc} (:frontier-direction bound))
        (page-error! "Lookup page cursor has an invalid frontier direction."
                     {:eacl/error :eacl.pagination/invalid-cursor
                      :frontier-direction (:frontier-direction bound)}))
      (when-not (every? (fn [[path-key frontier]]
                          (and (string? path-key)
                               (or (= :exhausted frontier)
                                   (valid-cursor-eid? frontier))))
                        frontiers)
        (page-error! "Lookup page cursor contains an invalid path frontier."
                     {:eacl/error :eacl.pagination/invalid-cursor})))))

(defn- valid-lookup-heads?
  [value]
  (and (map? value)
       (= lookup-continuation-version (:version value))
       (map? (:heads value))
       (every? (fn [[path-key heads]]
                 (and (string? path-key)
                      (map? heads)
                      (every? (fn [[intermediate-eid head]]
                                (and (valid-cursor-eid? intermediate-eid)
                                     (valid-cursor-eid? head)))
                              heads)))
               (:heads value))))

(defn- cached-lookup-heads
  "Per-intermediate stream heads left over from the page that minted `bound`."
  [continuation-cache bound]
  (when-let [get-heads (:get-heads continuation-cache)]
    (when-let [value (try
                       (get-heads bound)
                       (catch #?(:clj Exception :cljs :default) _
                         nil))]
      (when (valid-lookup-heads? value)
        (inc-stat! :lookup-head-hits)
        (:heads value)))))

(defn- store-lookup-heads!
  [continuation-cache edge heads]
  (when-let [put-heads! (:put-heads! continuation-cache)]
    (when (and edge (seq heads))
      (try
        (put-heads! edge
                    {:version lookup-continuation-version
                     :heads heads}
                    (+ 512 (* 96 (reduce + 0 (map count (vals heads))))))
        (catch #?(:clj Exception :cljs :default) _
          false)))))

(def ^:dynamic ^:private *stream-chunk-size*
  "Backend scan batch size selected by the enclosing recursive page.

  Small Relay windows use smaller batches to avoid realizing graph edges the
  caller did not ask for. Count operations and large pages keep the larger
  batch so index-seek overhead remains amortized."
  64)

(defn- page-stream-chunk-size
  [page-size]
  (cond
    (<= page-size 32) 16
    (<= page-size 256) 32
    :else 64))

(defn- subject-resource-scan
  [subject-type subject-eid relation-eid resource-type]
  {:scan-kind :subject-resources
   :subject-type subject-type
   :subject-eid subject-eid
   :relation-eid relation-eid
   :resource-type resource-type
   :bound-eid nil})

(defn- resource-subject-scan
  [resource-type resource-eid relation-eid subject-type]
  {:scan-kind :resource-subjects
   :resource-type resource-type
   :resource-eid resource-eid
   :relation-eid relation-eid
   :subject-type subject-type
   :bound-eid nil})

(defn- scan-eids
  [db {:keys [scan-kind subject-type subject-eid relation-eid
              resource-type resource-eid bound-eid]}]
  (let [page-opts {:direction :asc
                   :bound-eid bound-eid
                   :inclusive-bound? false}]
    (case scan-kind
      :subject-resources
      (subject->resources db subject-type subject-eid relation-eid
                          resource-type page-opts)

      :resource-subjects
      (resource->subjects db resource-type resource-eid relation-eid
                          subject-type page-opts))))

(defn- stream-work
  [scan on-eid]
  {:kind :stream
   :scan scan
   :eids []
   :more? true
   :on-eid on-eid})

(defn- enqueue-stream
  [state scan on-eid]
  (enqueue-work state (stream-work scan on-eid)))

(defn- fill-stream
  [db {:keys [scan] :as work}]
  (let [realized (vec (take (inc *stream-chunk-size*)
                            (scan-eids db scan)))
        _ (inc-stat! :stream-fills)
        _ (add-stat! :fetched-stream-datoms (count realized))
        more? (> (count realized) *stream-chunk-size*)
        eids (if more?
               (subvec realized 0 *stream-chunk-size*)
               realized)]
    (assoc work
           :eids eids
           :more? more?
           :scan (cond-> scan
                   (seq eids) (assoc :bound-eid (peek eids))))))

(defn- advance-stream
  [db state work]
  (let [{:keys [eids on-eid more?] :as work'}
        (if (seq (:eids work))
          work
          (fill-stream db work))]
    (if-let [eid (first eids)]
      (let [remaining (if (= 1 (count eids))
                        []
                        (subvec eids 1))
            state' (-> state
                       (increment-counter :advanced-datoms :max-advanced-datoms)
                       (enqueue-works (on-eid eid)))]
        (inc-stat! :advanced-stream-datoms)
        (if (or (seq remaining) more?)
          (enqueue-work state' (assoc work' :eids remaining))
          state'))
      state)))

(defn- forward-seed-state
  [db subject-type subject-eid
   {:keys [rules forward-consumers]}]
  (reduce
     (fn [state rule]
       (case (:rule rule)
         :relation
         (if (= subject-type (:subject-type rule))
           (enqueue-stream state
                           (subject-resource-scan
                            subject-type subject-eid
                            (:relation-eid rule)
                            (:resource-type rule))
                           (fn [resource-eid]
                             [{:kind :grant
                               :node (:node rule)
                               :resource-eid resource-eid}]))
           state)

         :arrow-relation
         (if (= subject-type (:target-subject-type rule))
           (enqueue-stream state
                           (subject-resource-scan
                            subject-type subject-eid
                            (:target-relation-eid rule)
                            (:intermediate-type rule))
                           (fn [intermediate-eid]
                             [(stream-work
                               (subject-resource-scan
                                (:intermediate-type rule)
                                intermediate-eid
                                (:via-relation-eid rule)
                                (:resource-type rule))
                               (fn [resource-eid]
                                 [{:kind :grant
                                   :node (:node rule)
                                   :resource-eid resource-eid}]))]))
           state)

         state))
     {:queue empty-queue
      :seen-grants #{}
      :emitted-root #{}
      :ordinal 0
      :counters {}
      :consumers forward-consumers
      :consumer-count (reduce + 0 (map count (vals forward-consumers)))}
     rules))

(defn- forward-consumer-work
  [db grant rule]
  (case (:rule rule)
    :self-permission
    [{:kind :grant
      :node (:node rule)
      :resource-eid (:resource-eid grant)}]

    :arrow-permission
    [(stream-work
      (subject-resource-scan
       (:intermediate-type rule)
       (:resource-eid grant)
       (:via-relation-eid rule)
       (:resource-type rule))
      (fn [resource-eid]
        [{:kind :grant
          :node (:node rule)
          :resource-eid resource-eid}]))]

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
  (forward-seed-state
   db subject-type subject-eid (recursive-plan db root-node)))

(defn- next-forward-item
  [db root-node result-type state]
  (loop [state state]
    (if (empty? (:queue state))
      [state nil]
      (let [[work state'] (pop-work state)]
        (case (:kind work)
          :stream
          (recur (advance-stream db state' work))

          :grant
          (let [[state'' item] (process-forward-grant db root-node result-type state' work)]
            (if item
              [state'' item]
              (recur state''))))))))

(defn- collect-forward-after
  [db root-node result-type state bound size]
  (loop [state state
         mode (if bound :seek :collect)
         items []
         page-end-state nil]
    (if (>= (count items) (inc size))
      {:items items
       :complete? false
       :page-end-state page-end-state}
      (let [[state' item] (next-forward-item db root-node result-type state)]
        (cond
          (nil? item)
          (if (= mode :seek)
            (recursive-traversal-error!
             "Recursive traversal cursor no longer exists."
             {:eacl/error :eacl.pagination/stale-cursor
              :bound bound})
            {:items items
             :complete? true
             :page-end-state page-end-state})

          (= mode :seek)
          (let [ordinal (get-in item [:cursor :ordinal])]
            (cond
              (< ordinal (:ordinal bound))
              (recur state' :seek items page-end-state)

              (= ordinal (:ordinal bound))
              (if (same-recursive-bound-result? bound item)
                (recur state' :collect items page-end-state)
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
          (let [items' (conj items item)]
            (recur state'
                   :collect
                   items'
                   (if (<= (count items') size)
                     state'
                     page-end-state))))))))

(defn- collect-forward-before
  [db root-node result-type state bound size]
  (loop [state state
         ring empty-queue
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

(defn- count-traversal-items
  "Counts results emitted by one traversal, stopping after `limit` when set.

  The paged alternative (repeatedly asking for the next max-page-size page)
  replays the whole traversal prefix per page, which is O(N^2) and trips
  :max-derived-grants long before a large grant set is counted."
  [next-item state limit]
  (loop [state state
         n 0]
    (let [[state' item] (next-item state)]
      (cond
        (nil? item)
        {:count n :truncated? false}

        (and limit (>= n limit))
        {:count n :truncated? true}

        :else
        (recur state' (unchecked-inc n))))))

(def ^:private recursive-denotation-version 1)

(defn- valid-recursive-denotation?
  [value]
  (and (vector? value)
       (= (count value) (count (distinct value)))
       (every? #(and (integer? %) (pos? %)) value)))

(defn- recursive-denotation-weight
  [value]
  (+ 256 (* 24 (count value))))

(defn- recursive-denotation-key
  [db direction root-node anchor-type anchor-eid result-type]
  (let [plan (recursive-plan db root-node)]
    [denotation-key-version
     :recursive-component
     recursive-denotation-version
     direction
     (:root-component-id plan)
     root-node
     anchor-type
     anchor-eid
     result-type
     *recursive-traversal-limits*]))

(defn- complete-forward-denotation
  [db root-node result-type state]
  (loop [state state
         values []]
    (let [[state' item]
          (next-forward-item db root-node result-type state)]
      (if item
        (recur state' (conj values (get-in item [:node :id])))
        values))))

(defn- resolve-forward-denotation
  [db subject-type subject-eid root-node result-type]
  (let [key
        (recursive-denotation-key
         db :forward root-node subject-type subject-eid result-type)
        resolved
        (subproblem/resolve-bound!
         :denotation
         key
         {:valid? valid-recursive-denotation?
          :weight-fn recursive-denotation-weight}
         #(complete-forward-denotation
           db root-node result-type
           (initial-forward-state
            db subject-type subject-eid root-node)))]
    (when (:cached? resolved)
      (subproblem/record-recursive-component-hit!))
    (:value resolved)))

(defn- lookup-forward-denotation
  [db subject-type subject-eid root-node result-type]
  (when-let [resolved
             (subproblem/lookup-bound!
              :denotation
              (recursive-denotation-key
               db :forward root-node subject-type subject-eid result-type)
              {:valid? valid-recursive-denotation?
               :weight-fn recursive-denotation-weight})]
    (subproblem/record-recursive-component-hit!)
    (:value resolved)))

(defn- publish-forward-denotation!
  [db subject-type subject-eid root-node result-type values]
  (when subproblem/*store*
    (:value
     (subproblem/resolve-bound!
      :denotation
      (recursive-denotation-key
       db :forward root-node subject-type subject-eid result-type)
      {:valid? valid-recursive-denotation?
       :weight-fn recursive-denotation-weight}
      #(vec values)))))

(defn- recursive-denotation-item
  [direction result-kind result-type ordinal eid]
  {:node (spice-object result-type eid)
   :cursor
   (recursive-edge
    direction result-kind ordinal result-type eid)})

(defn- recursive-denotation-items
  [direction result-kind result-type start-ordinal values]
  (mapv
   (fn [offset eid]
     (recursive-denotation-item
      direction result-kind result-type (+ start-ordinal offset) eid))
   (range)
   values))

(defn- validate-denotation-bound!
  [direction result-kind result-type values bound]
  (when bound
    (let [ordinal (:ordinal bound)]
      (when-not (and (integer? ordinal)
                     (<= 0 ordinal)
                     (< ordinal (count values)))
        (recursive-traversal-error!
         "Recursive traversal cursor no longer exists."
         {:eacl/error :eacl.pagination/stale-cursor
          :bound bound}))
      (let [actual
            (recursive-denotation-item
             direction result-kind result-type ordinal (nth values ordinal))]
        (when-not (same-recursive-bound-result? bound actual)
          (recursive-traversal-error!
           "Recursive traversal cursor points at a different result."
           {:eacl/error :eacl.pagination/stale-cursor
            :bound bound
            :actual (:cursor actual)}))))))

(defn- page-from-recursive-denotation
  [direction result-kind result-type values page-direction bound size]
  (let [value-count (count values)
        _ (validate-denotation-bound!
           direction result-kind result-type values bound)]
    (case page-direction
      :asc
      (let [start (if bound (inc (:ordinal bound)) 0)
            end (min value-count (+ start size))]
        (page-response
         {:items
          (recursive-denotation-items
           direction result-kind result-type start
           (subvec values start end))
          :has-next? (< end value-count)
          :has-previous? (boolean bound)}))

      :desc
      (let [end (:ordinal bound)
            start (max 0 (- end size))]
        (page-response
         {:items
          (recursive-denotation-items
           direction result-kind result-type start
           (subvec values start end))
          :has-next? true
          :has-previous? (pos? start)})))))

(defn- recursive-forward-page
  [db query continuation-cache]
  (let [{:keys [direction size bound]} (normalize-page-request query)
        _ (validate-recursive-bound! bound :forward :resource)
        _ (when (and (= :desc direction) (nil? bound))
            (page-error! "Bare :last is not supported for recursive traversal pagination because it requires a full closure traversal."
                         {:eacl/error :eacl.pagination/unsupported-recursive-last
                          :reason :requires-full-traversal}))
        {:keys [subject permission]} query
        subject-type (:type subject)
        subject-eid (object-eid db (:id subject))
        result-type (:resource/type query)
        root-node (permission-query-node result-type permission)
        cached-denotation
        (when subject-eid
          (lookup-forward-denotation
           db subject-type subject-eid root-node result-type))
        cached-page
        (case direction
          :asc (cached-recursive-request-page
                continuation-cache :forward :resource :asc bound size)
          :desc (cached-recursive-previous-page
                 continuation-cache :forward :resource bound size))]
    (or
     cached-page
     (when cached-denotation
       (page-from-recursive-denotation
        :forward :resource result-type cached-denotation
        direction bound size))
     (binding [*stream-chunk-size* (page-stream-chunk-size size)]
       (let [continuation (when (and bound (= :asc direction))
                            (note-continuation-miss!
                             bound
                             (cached-continuation continuation-cache
                                                  bound :forward :resource)))
             state (or (:state continuation)
                       (when subject-eid
                         (initial-forward-state
                          db subject-type subject-eid root-node)))
             replay-bound (when-not continuation bound)]
         (if-not state
           (page-response {:items []
                           :has-next? false
                           :has-previous? (boolean bound)})
           (case direction
             :asc
             (let [{:keys [items complete? page-end-state]}
                   (collect-forward-after
                    db root-node result-type state replay-bound size)
                   page-items (vec (take size items))
                   has-sentinel? (> (count items) size)
                   has-next? (and has-sentinel? (not complete?))
                   _ (when (and complete?
                                (nil? bound)
                                (nil? continuation))
                       (publish-forward-denotation!
                        db subject-type subject-eid root-node result-type
                        (mapv #(get-in % [:node :id]) page-items)))
                   page (page-response {:items page-items
                                        :has-next? has-next?
                                        :has-previous? (boolean bound)})
                   continuation-stored?
                   (when has-next?
                     (store-continuation!
                      continuation-cache
                      (some-> page-items last :cursor)
                      :forward :resource page-end-state))
                   page-stored?
                   (store-recursive-page!
                    continuation-cache :forward :resource :asc bound size page)]
               ;; Keep the input continuation until both retry data and the next
               ;; frontier are safely published. A cache failure must not consume
               ;; an otherwise usable cursor.
               (when (and continuation
                          page-stored?
                          (or (not has-next?) continuation-stored?))
                 (evict-continuation! continuation-cache bound))
               page)

             :desc
             (let [{:keys [items has-sentinel?]}
                   (collect-forward-before
                    db root-node result-type state bound size)
                   page (page-response {:items items
                                        :has-next? true
                                        :has-previous? has-sentinel?})]
               (store-recursive-page!
                continuation-cache :forward :resource :desc bound size page)
               page))))))))

(defn- reverse-consumer-key
  [node resource-eid]
  [node resource-eid])

(defn- add-reverse-consumer
  [state key consumer]
  (let [state' (-> state
                   (update-in [:consumers key] (fnil conj []) consumer)
                   (update :consumer-count (fnil inc 0)))
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
                      (resource-subject-scan
                       (:resource-type rule)
                       resource-eid
                       (:relation-eid rule)
                       (:subject-type state))
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

    ;; The sub-path relation only ever holds subjects of its declared type, so
    ;; a mismatched query subject-type can never produce a grant. forward-seed-
    ;; state already skips these; without the same guard the reverse engine
    ;; spent one index seek per intermediate proving the empty set empty.
    :arrow-relation
    (if (= (:subject-type state) (:target-subject-type rule))
      (enqueue-stream state
                      (resource-subject-scan
                       (:resource-type rule)
                       resource-eid
                       (:via-relation-eid rule)
                       (:intermediate-type rule))
                      (fn [intermediate-eid]
                        [(stream-work
                          (resource-subject-scan
                           (:intermediate-type rule)
                           intermediate-eid
                           (:target-relation-eid rule)
                           (:subject-type state))
                          (fn [subject-eid]
                            [{:kind :grant
                              :node (:node rule)
                              :resource-eid resource-eid
                              :subject-type (:subject-type state)
                              :subject-eid subject-eid}]))]))
      state)

    :arrow-permission
    (enqueue-stream state
                    (resource-subject-scan
                     (:resource-type rule)
                     resource-eid
                     (:via-relation-eid rule)
                     (:intermediate-type rule))
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
  (let [{:keys [rules rules-by-node]} (recursive-plan db root-node)]
    (enqueue-reverse-goal
     {:queue empty-queue
      :seen-goals #{}
      :seen-grants #{}
      :grants-by-goal {}
      :consumers {}
      :emitted-subjects #{}
      :ordinal 0
      :counters {}
      :subject-type subject-type
      :rules-by-node rules-by-node
      :rule-count (count rules)
      :consumer-count 0}
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
          (recur (advance-stream db state' work))

          :goal
          (recur (process-reverse-goal db state' work))

          :register-consumer
          (recur (add-reverse-consumer state' (:consumer-key work) (:consumer work)))

          :grant
          (let [[state'' item] (process-reverse-grant root-node root-resource-eid result-type state' work)]
            (if item
              [state'' item]
              (recur state''))))))))

(defn- complete-reverse-denotation
  [db root-node root-resource-eid result-type state]
  (loop [state state
         values []]
    (let [[state' item]
          (next-reverse-item
           db root-node root-resource-eid result-type state)]
      (if item
        (recur state' (conj values (get-in item [:node :id])))
        values))))

(defn- resolve-reverse-denotation
  [db resource-type resource-eid root-node subject-type]
  (let [key
        (recursive-denotation-key
         db :reverse root-node resource-type resource-eid subject-type)
        resolved
        (subproblem/resolve-bound!
         :denotation
         key
         {:valid? valid-recursive-denotation?
          :weight-fn recursive-denotation-weight}
         #(complete-reverse-denotation
           db root-node resource-eid subject-type
           (initial-reverse-state
            db subject-type root-node resource-eid)))]
    (when (:cached? resolved)
      (subproblem/record-recursive-component-hit!))
    (:value resolved)))

(defn- lookup-reverse-denotation
  [db resource-type resource-eid root-node subject-type]
  (when-let [resolved
             (subproblem/lookup-bound!
              :denotation
              (recursive-denotation-key
               db :reverse root-node resource-type resource-eid subject-type)
              {:valid? valid-recursive-denotation?
               :weight-fn recursive-denotation-weight})]
    (subproblem/record-recursive-component-hit!)
    (:value resolved)))

(defn- publish-reverse-denotation!
  [db resource-type resource-eid root-node subject-type values]
  (when subproblem/*store*
    (:value
     (subproblem/resolve-bound!
      :denotation
      (recursive-denotation-key
       db :reverse root-node resource-type resource-eid subject-type)
      {:valid? valid-recursive-denotation?
       :weight-fn recursive-denotation-weight}
      #(vec values)))))

(defn- collect-reverse-after
  [db root-node root-resource-eid result-type state bound size]
  (loop [state state
         mode (if bound :seek :collect)
         items []
         page-end-state nil]
    (if (>= (count items) (inc size))
      {:items items
       :complete? false
       :page-end-state page-end-state}
      (let [[state' item] (next-reverse-item db root-node root-resource-eid result-type state)]
        (cond
          (nil? item)
          (if (= mode :seek)
            (recursive-traversal-error!
             "Recursive traversal cursor no longer exists."
             {:eacl/error :eacl.pagination/stale-cursor
              :bound bound})
            {:items items
             :complete? true
             :page-end-state page-end-state})

          (= mode :seek)
          (let [ordinal (get-in item [:cursor :ordinal])]
            (cond
              (< ordinal (:ordinal bound))
              (recur state' :seek items page-end-state)

              (= ordinal (:ordinal bound))
              (if (same-recursive-bound-result? bound item)
                (recur state' :collect items page-end-state)
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
          (let [items' (conj items item)]
            (recur state'
                   :collect
                   items'
                   (if (<= (count items') size)
                     state'
                     page-end-state))))))))

(defn- collect-reverse-before
  [db root-node root-resource-eid result-type state bound size]
  (loop [state state
         ring empty-queue
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
  [db query continuation-cache]
  (when (:subject/relation query)
    (page-error! ":subject/relation is not supported for recursive lookup-subjects."
                 {:eacl/error :eacl.pagination/unsupported-filter
                  :filter :subject/relation}))
  (let [{:keys [direction size bound]} (normalize-page-request query)
        _ (validate-recursive-bound! bound :reverse :subject)
        _ (when (and (= :desc direction) (nil? bound))
            (page-error! "Bare :last is not supported for recursive traversal pagination because it requires a full closure traversal."
                         {:eacl/error :eacl.pagination/unsupported-recursive-last
                          :reason :requires-full-traversal}))
        {:keys [resource permission]} query
        resource-type (:type resource)
        resource-eid (object-eid db (:id resource))
        subject-type (:subject/type query)
        root-node (permission-query-node resource-type permission)
        cached-denotation
        (when resource-eid
          (lookup-reverse-denotation
           db resource-type resource-eid root-node subject-type))
        cached-page
        (case direction
          :asc (cached-recursive-request-page
                continuation-cache :reverse :subject :asc bound size)
          :desc (cached-recursive-previous-page
                 continuation-cache :reverse :subject bound size))]
    (or
     cached-page
     (when cached-denotation
       (page-from-recursive-denotation
        :reverse :subject subject-type cached-denotation
        direction bound size))
     (binding [*stream-chunk-size* (page-stream-chunk-size size)]
       (let [continuation (when (and bound (= :asc direction))
                            (note-continuation-miss!
                             bound
                             (cached-continuation continuation-cache
                                                  bound :reverse :subject)))
             state (or (:state continuation)
                       (when resource-eid
                         (initial-reverse-state
                          db subject-type root-node resource-eid)))
             replay-bound (when-not continuation bound)]
         (if-not state
           (page-response {:items []
                           :has-next? false
                           :has-previous? (boolean bound)})
           (case direction
             :asc
             (let [{:keys [items complete? page-end-state]}
                   (collect-reverse-after db root-node resource-eid
                                          subject-type state replay-bound size)
                   page-items (vec (take size items))
                   has-sentinel? (> (count items) size)
                   has-next? (and has-sentinel? (not complete?))
                   _ (when (and complete?
                                (nil? bound)
                                (nil? continuation))
                       (publish-reverse-denotation!
                        db resource-type resource-eid root-node subject-type
                        (mapv #(get-in % [:node :id]) page-items)))
                   page (page-response {:items page-items
                                        :has-next? has-next?
                                        :has-previous? (boolean bound)})
                   continuation-stored?
                   (when has-next?
                     (store-continuation!
                      continuation-cache
                      (some-> page-items last :cursor)
                      :reverse :subject page-end-state))
                   page-stored?
                   (store-recursive-page!
                    continuation-cache :reverse :subject :asc bound size page)]
               (when (and continuation
                          page-stored?
                          (or (not has-next?) continuation-stored?))
                 (evict-continuation! continuation-cache bound))
               page)

             :desc
             (let [{:keys [items has-sentinel?]}
                   (collect-reverse-before db root-node
                                           resource-eid subject-type
                                           state bound size)
                   page (page-response {:items items
                                        :has-next? true
                                        :has-previous? has-sentinel?})]
               (store-recursive-page!
                continuation-cache :reverse :subject :desc bound size page)
               page))))))))

(declare traverse-permission-path lookup-subject-eids* can*)

(defn traverse-permission-path-via-subject
  ([db subject-type subject-eid path resource-type page-opts intermediate-cursor-eid visited-paths]
   (traverse-permission-path-via-subject
    db subject-type subject-eid path resource-type page-opts
    intermediate-cursor-eid visited-paths nil))
  ([db subject-type subject-eid path resource-type page-opts intermediate-cursor-eid visited-paths head-state]
   (let [{:keys [direction]} (scan-opts page-opts)
         intermediate-opts {:direction direction
                            :bound-eid intermediate-cursor-eid
                            :inclusive-bound? true}]
    (case (:type path)
      :relation
      {:results (when (= subject-type (:subject-type path))
                  (subject->resources db
                                      subject-type
                                      subject-eid
                                      (:relation-eid path)
                                      resource-type
                                      page-opts))
       :frontier nil}

      :self-permission
      {:results (traverse-permission-path db
                                          subject-type
                                          subject-eid
                                          (:target-permission path)
                                          resource-type
                                          page-opts
                                          (or visited-paths #{}))
       :frontier nil}

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
                                                intermediate-opts)))
                     (filter seq))
                intermediate-eids (if (seq intermediate-seqs)
                                    (merge-eid-seqs direction intermediate-seqs)
                                    [])]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid resume-eid]
                                       (subject->resources db
                                                           intermediate-type
                                                           intermediate-eid
                                                           via-relation-eid
                                                           resource-type
                                                           (resume-scan-opts
                                                            direction page-opts resume-eid)))
                                     head-state))
          (let [target-permission (:target-permission path)
                intermediate-eids (traverse-permission-path db
                                                            subject-type
                                                            subject-eid
                                                            target-permission
                                                            intermediate-type
                                                            intermediate-opts
                                                            (or visited-paths #{}))]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid resume-eid]
                                       (subject->resources db
                                                           intermediate-type
                                                           intermediate-eid
                                                           via-relation-eid
                                                           resource-type
                                                           (resume-scan-opts
                                                            direction page-opts resume-eid)))
                                     head-state))))))))

(defn- traverse-permission-path-uncached
  [db subject-type subject-eid permission-name resource-type page-opts
   visited-paths]
  (let [{:keys [direction]} page-opts
        path-key [subject-type subject-eid permission-name resource-type]
        paths (get-permission-paths db resource-type permission-name)
        next-visited (conj visited-paths path-key)
        path-seqs
        (->> paths
             (map (fn [path]
                    (:results
                     (traverse-permission-path-via-subject
                      db subject-type subject-eid path resource-type
                      page-opts nil next-visited))))
             (filter seq))]
    (if (seq path-seqs)
      (merge-eid-seqs direction path-seqs)
      [])))

(defn traverse-permission-path
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path
    db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-or-opts
    visited-paths]
   (let [page-opts (scan-opts cursor-or-opts)
         path-key [subject-type subject-eid permission-name resource-type]]
     (cond
       (contains? visited-paths path-key)
       []

       ;; A DFS recursion guard is call-stack dependent. Only permissions whose
       ;; transitive schema graph is acyclic have context-free subproblem
       ;; denotations suitable for this cache.
       (traversal-permission? db resource-type permission-name)
       (traverse-permission-path-uncached
        db subject-type subject-eid permission-name resource-type
        page-opts visited-paths)

       :else
       (cached-acyclic-denotation
        :forward-permission
        [subject-type subject-eid permission-name resource-type]
        page-opts
        #(traverse-permission-path-uncached
          db subject-type subject-eid permission-name resource-type
          % #{}))))))

(defn traverse-permission-path-reverse
  ([db resource-type resource-eid path subject-type page-opts intermediate-cursor-eid visited-paths]
   (traverse-permission-path-reverse
    db resource-type resource-eid path subject-type page-opts
    intermediate-cursor-eid visited-paths nil))
  ([db resource-type resource-eid path subject-type page-opts intermediate-cursor-eid visited-paths head-state]
   (let [{:keys [direction]} (scan-opts page-opts)
         intermediate-opts {:direction direction
                            :bound-eid intermediate-cursor-eid
                            :inclusive-bound? true}]
    (case (:type path)
      :relation
      {:results (when (= subject-type (:subject-type path))
                  (resource->subjects db
                                      resource-type
                                      resource-eid
                                      (:relation-eid path)
                                      subject-type
                                      page-opts))
       :frontier nil}

      :self-permission
      {:results (lookup-subject-eids* db
                                      resource-type
                                      resource-eid
                                      (:target-permission path)
                                      subject-type
                                      page-opts
                                      (or visited-paths #{}))
       :frontier nil}

      :arrow
      (let [intermediate-type (:target-type path)
            via-relation-eid (:via-relation-eid path)
            intermediate-eids (resource->subjects db
                                                  resource-type
                                                  resource-eid
                                                  via-relation-eid
                                                  intermediate-type
                                                  intermediate-opts)]
        (if (:target-relation path)
          (let [matching-sub-paths (matching-relation-sub-paths (:sub-paths path) subject-type)]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid resume-eid]
                                       (let [opts (resume-scan-opts
                                                   direction page-opts resume-eid)
                                             subject-seqs (->> matching-sub-paths
                                                               (map (fn [sub-path]
                                                                      (resource->subjects db
                                                                                          intermediate-type
                                                                                          intermediate-eid
                                                                                          (:relation-eid sub-path)
                                                                                          subject-type
                                                                                          opts)))
                                                               (filter seq))]
                                         (if (seq subject-seqs)
                                           (merge-eid-seqs direction subject-seqs)
                                           [])))
                                     head-state))
          (let [target-permission (:target-permission path)]
            (arrow-via-intermediates direction intermediate-eids
                                     (fn [intermediate-eid resume-eid]
                                       (lookup-subject-eids* db
                                                             intermediate-type
                                                             intermediate-eid
                                                             target-permission
                                                             subject-type
                                                             (resume-scan-opts
                                                              direction page-opts resume-eid)
                                                             (or visited-paths #{})))
                                     head-state))))))))

(defn- lookup-subject-eids-uncached
  [db resource-type resource-eid permission-name subject-type page-opts
   visited-states]
  (let [{:keys [direction]} page-opts
        state [resource-type resource-eid permission-name subject-type]
        next-visited (conj visited-states state)
        paths (get-permission-paths db resource-type permission-name)
        path-seqs
        (->> paths
             (map (fn [path]
                    (:results
                     (traverse-permission-path-reverse
                      db resource-type resource-eid path subject-type
                      page-opts nil next-visited))))
             (filter seq))]
    (if (seq path-seqs)
      (merge-eid-seqs direction path-seqs)
      [])))

(defn- lookup-subject-eids*
  [db resource-type resource-eid permission-name subject-type cursor-or-opts
   visited-states]
  (let [page-opts (scan-opts cursor-or-opts)
        state [resource-type resource-eid permission-name subject-type]]
    (cond
      (contains? visited-states state)
      []

      (traversal-permission? db resource-type permission-name)
      (lookup-subject-eids-uncached
       db resource-type resource-eid permission-name subject-type
       page-opts visited-states)

      :else
      (cached-acyclic-denotation
       :reverse-permission
       [resource-type resource-eid permission-name subject-type]
       page-opts
       #(lookup-subject-eids-uncached
         db resource-type resource-eid permission-name subject-type
         % #{})))))

(def ^:private linear-probe-limit
  "How far a sorted-stream intersection walks an already-open scan before
  paying for a fresh seek. Re-seeking is O(log n) but allocates a new lazy
  index scan; walking is cheap per step but O(gap). Probing first keeps two
  densely interleaved streams near a linear merge, while a large gap — the case
  that matters — costs one seek."
  16)

(defn- advance-sorted-eids
  "Positions a sorted ascending eid stream at the first element >= `target`,
  re-seeking the index when the target is further than the probe limit.
  Returns nil when the stream is exhausted."
  [stream reseek target]
  (loop [s (seq stream) probes 0]
    (cond
      (nil? s) nil
      (>= (long (first s)) (long target)) s
      (>= probes linear-probe-limit) (seq (reseek target))
      :else (recur (next s) (inc probes)))))

(defn- sorted-eids-intersect?
  "True when two sorted ascending eid streams share an element.

  Leapfrog rather than nested loops: `can?` on an arrow asks whether the
  intermediates of the resource and the intermediates the subject holds
  overlap, and both sides come out of :eavt in ascending eid order. Scanning
  one side in full made the check O(fan-out) even when the other side had a
  single candidate."
  [stream-a reseek-a stream-b reseek-b]
  (loop [a (seq stream-a) b (seq stream-b)]
    (if (or (nil? a) (nil? b))
      false
      (let [x (long (first a))
            y (long (first b))]
        (cond
          (== x y) true
          (< x y) (recur (advance-sorted-eids a reseek-a y) b)
          :else (recur a (advance-sorted-eids b reseek-b x)))))))

(defn- ascending-from
  [bound-eid]
  (when bound-eid
    {:direction :asc :bound-eid bound-eid :inclusive-bound? true}))

(defn- calc-direct-grant-relations
  [db resource-type permission subject-type]
  (let [paths (get-permission-paths db resource-type permission)]
    {:relation-eids (into []
                          (comp (filter #(and (= :relation (:type %))
                                              (= subject-type (:subject-type %))))
                                (map :relation-eid))
                          paths)
     :exhaustive? (every? #(= :relation (:type %)) paths)}))

(defn- direct-grant-relations
  "Relation eids through which `subject-type` can hold `permission` on
  `resource-type` in ONE relationship, and whether that is the only way.

  `:exhaustive?` means every path of the permission is a plain relation, so an
  empty intersection is a definitive false. A path belonging to some other
  subject type still counts as exhaustive: it can never match this subject.

  Purely a function of the schema generation, and on the `can?` hot path for
  every arrow, so a stamped client memoises it rather than rebuilding the
  vector per check."
  [db resource-type permission subject-type]
  (let [cache-atom (:direct-grant-relations *schema-cache*)]
    (if-not (and cache-atom (some? (:schema-version *schema-cache*)))
      (calc-direct-grant-relations db resource-type permission subject-type)
      (let [cache-key [resource-type permission subject-type]
            snapshot @cache-atom]
        (if (contains? snapshot cache-key)
          (get snapshot cache-key)
          (let [computed (calc-direct-grant-relations
                          db resource-type permission subject-type)]
            (get (swap! cache-atom
                        #(if (contains? % cache-key)
                           %
                           (assoc % cache-key computed)))
                 cache-key)))))))

(defn- can-uncached*
  [db subject-type subject-eid permission resource-type resource-eid visited-states]
  (let [state [subject-type subject-eid permission resource-type resource-eid]
        ;; Already ordered cheapest-first by calc-permission-paths, so the
        ;; `some` below short-circuits on a direct relation before paying for
        ;; an arrow.
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
                    resource-side
                    (fn [bound]
                      (resource->subjects db resource-type resource-eid
                                          via-relation-eid intermediate-type
                                          (ascending-from bound)))
                    probe-each
                    (fn [intermediates]
                      (if (:target-relation path)
                        (let [sub-paths (matching-relation-sub-paths
                                         (:sub-paths path) subject-type)]
                          (some (fn [intermediate-eid]
                                  (some (fn [sub-path]
                                          (seq
                                           (direct-match-datoms-in-relationship-index
                                            db subject-type subject-eid
                                            (:relation-eid sub-path)
                                            intermediate-type intermediate-eid)))
                                        sub-paths))
                                intermediates))
                        (some (fn [intermediate-eid]
                                (can* db subject-type subject-eid
                                      (:target-permission path)
                                      intermediate-type intermediate-eid
                                      next-visited))
                              intermediates)))
                    intermediates (seq (resource-side nil))]
                (if (and intermediates (nil? (next intermediates)))
                  ;; Exactly one intermediate — by far the most common arrow
                  ;; shape (`server->account->admin`). A single point probe
                  ;; beats opening a second index scan to intersect against.
                  ;; `next` realizes at most two elements, which the
                  ;; intersection below needs anyway, so the wide case pays
                  ;; nothing for this check.
                  (probe-each intermediates)
                  (let [;; Relations through which the subject can satisfy the
                        ;; far side of the arrow in one relationship.
                        {:keys [relation-eids exhaustive?]}
                        (if (:target-relation path)
                          {:relation-eids (mapv :relation-eid
                                                (matching-relation-sub-paths
                                                 (:sub-paths path) subject-type))
                           :exhaustive? true}
                          (direct-grant-relations db intermediate-type
                                                  (:target-permission path)
                                                  subject-type))
                        subject-side
                        (fn [bound]
                          (let [opts (ascending-from bound)]
                            (merge-eid-seqs
                             :asc
                             (mapv #(subject->resources db subject-type subject-eid
                                                        % intermediate-type opts)
                                   relation-eids))))]
                    (cond
                      ;; `intermediates` is already open and positioned at the
                      ;; start; there is no reason to seek it again.
                      (and (seq relation-eids)
                           (sorted-eids-intersect? intermediates resource-side
                                                   (subject-side nil) subject-side))
                      true

                      ;; Every way to satisfy the far side was a single
                      ;; relationship, and none of them intersected.
                      exhaustive? false

                      ;; The target permission also has arrows or aliases of
                      ;; its own, so the intersection was only a positive fast
                      ;; path and a miss still has to be checked properly.
                      :else (probe-each intermediates)))))))
          paths))))))

(defn- can*
  [db subject-type subject-eid permission resource-type resource-eid
   visited-states]
  (let [state
        [subject-type subject-eid permission resource-type resource-eid]]
    (cond
      (contains? visited-states state)
      false

      (nil? subproblem/*store*)
      (can-uncached*
       db subject-type subject-eid permission resource-type resource-eid
       visited-states)

      (traversal-permission? db resource-type permission)
      (if-let [denotation
               (lookup-forward-denotation
                db
                subject-type
                subject-eid
                (permission-query-node resource-type permission)
                resource-type)]
        (boolean (some #{resource-eid} denotation))
        (can-uncached*
         db subject-type subject-eid permission resource-type resource-eid
         visited-states))

      :else
      (let [resolved
            (subproblem/resolve-bound!
             :denotation
             [denotation-key-version
              :can?
              subject-type subject-eid permission resource-type resource-eid]
             {:valid? boolean?
              :weight-fn (constantly 96)}
             #(can-uncached*
               db subject-type subject-eid permission resource-type
               resource-eid #{}))]
        (when (:cached? resolved)
          (subproblem/record-acyclic-denotation-hit!))
        (:value resolved)))))

(defn can?
  [db subject permission resource]
  (let [subject-type  (:type subject)
        subject-eid   (object-eid db (:id subject))
        resource-type (:type resource)
        resource-eid  (object-eid db (:id resource))]
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
  ([db direction query page-req]
   (lazy-merged-lookup db direction query page-req nil))
  ([db direction query page-req cached-heads]
   (let [{:keys [anchor-key traverse-fn perm-type-fn]} direction
         anchor      (get query anchor-key)
         anchor-type (:type anchor)
         anchor-eid  (object-eid db (:id anchor))
         permission  (:permission query)
         perm-type   (perm-type-fn query)
         result-type-key (if (= anchor-key :subject) :resource/type :subject/type)
         result-type (get query result-type-key)
         page-opts   {:direction (:direction page-req)
                      :bound-eid (get-in page-req [:bound :result-eid])}
         reusable-frontiers (when (and (= lookup-frontier-version
                                          (get-in page-req [:bound :frontier-version]))
                                       (= (:direction page-req)
                                          (get-in page-req [:bound :frontier-direction])))
                              (get-in page-req [:bound :path-frontiers]))
         paths       (frontier-permission-paths db perm-type permission)
         ;; One head map per path, collected as streams are opened. The caller
         ;; turns these into the next page's :cached heads.
         observed-heads (atom {})
         path-results (vec
                       (->> paths
                            (map
                             (fn [path]
                               (let [path-key (path-frontier-key db path)
                                     prior-frontier (get reusable-frontiers path-key)
                                     path-observed (atom {})
                                     head-state {:cached (get cached-heads path-key)
                                                 :observed path-observed}
                                     _ (swap! observed-heads assoc path-key path-observed)
                                     {:keys [results frontier]}
                                     (cond
                                       (= :exhausted prior-frontier)
                                       {:results []
                                        :frontier :exhausted}

                                       anchor-eid
                                       (traverse-fn db
                                                    anchor-type
                                                    anchor-eid
                                                    path
                                                    result-type
                                                    page-opts
                                                    prior-frontier
                                                    #{}
                                                    head-state)

                                       :else
                                       {:results []
                                        :frontier nil})]
                                 {:path-key path-key
                                  :results results
                                  :frontier frontier})))))]
     {:results (let [result-seqs (filter seq (map :results path-results))]
                 (if (seq result-seqs)
                   (merge-eid-seqs (:direction page-req) result-seqs)
                   []))
      :path-frontiers (into {}
                            (keep (fn [{:keys [path-key frontier]}]
                                    (when frontier
                                      [path-key frontier])))
                            path-results)
      :observed-heads observed-heads
      :path-results path-results})))

(defn- surviving-heads
  "The next page's cached heads: every stream head this page opened but did
  NOT consume.

  A head at or before the new boundary was drawn from, so that stream has to be
  re-opened next page — but only the handful of intermediates that actually
  contributed, never all of them. Heads beyond the boundary are still exactly
  where the next page would find them."
  [observed-heads boundary-eid]
  (when boundary-eid
    (persistent!
     (reduce-kv
      (fn [acc path-key path-observed]
        (let [kept (persistent!
                    (reduce-kv (fn [m intermediate-eid head]
                                 (if (> (long head) (long boundary-eid))
                                   (assoc! m intermediate-eid head)
                                   m))
                               (transient {})
                               @path-observed))]
          (if (seq kept)
            (assoc! acc path-key kept)
            acc)))
      (transient {})
      @observed-heads))))

(defn- lookup
  ([db direction query]
   (lookup db direction query nil))
  ([db direction query continuation-cache]
   (let [{:keys [result-type-fn]} direction
         page-req (normalize-page-request query)
         {:keys [size bound]} page-req
         _ (validate-lookup-eid-bound! bound)
         ;; Only forward pages resume. A backward walk revisits already-emitted
         ;; ground and its heads are ordered the other way; keeping the
         ;; continuation one-directional avoids a second, rarely-exercised
         ;; boundary rule.
         resumable? (= :asc (:direction page-req))
         cached-heads (when (and resumable? bound)
                        (cached-lookup-heads continuation-cache bound))
         {:keys [results path-frontiers observed-heads]}
         (lazy-merged-lookup db direction query page-req cached-heads)
         realized (doall (take (inc size) results))
         has-sentinel? (> (count realized) size)
         page-results-in-scan-order (take size realized)
         page-results (case (:direction page-req)
                        :asc page-results-in-scan-order
                        :desc (reverse page-results-in-scan-order))
         result-type (result-type-fn query)
         items       (lookup-items result-type
                                   page-results
                                   (:direction page-req)
                                   path-frontiers)
         page (page-response {:items items
                              :has-next? (case (:direction page-req)
                                           :asc has-sentinel?
                                           :desc (boolean bound))
                              :has-previous? (case (:direction page-req)
                                               :asc (boolean bound)
                                               :desc has-sentinel?)})]
     (when (and resumable? has-sentinel?)
       (store-lookup-heads! continuation-cache
                            (get-in page [:page-info :end-cursor])
                            (surviving-heads observed-heads
                                             (last page-results-in-scan-order))))
     page)))

(defn lookup-resources
  "Cursor maps returned in :page-info embed per-path frontiers (including
  :exhausted markers) that are valid only for the relationship state that
  minted them. Raw-impl callers must hold one DB value for a whole paginated
  walk; the public client authenticates cursor state and either proves the
  current relationship state equivalent or uses cache-resident exact state."
  ([db query]
   (lookup-resources db query nil))
  ([db query {:keys [continuation-cache continuation-cache-fn]}]
   (let [cache (or continuation-cache
                   (when continuation-cache-fn (continuation-cache-fn)))]
     (if (traversal-permission? db (:resource/type query) (:permission query))
       (recursive-forward-page db query cache)
       (lookup db forward-direction query cache)))))

(defn lookup-subjects
  "See lookup-resources: cursors are only valid against the minting db basis."
  ([db query]
   (lookup-subjects db query nil))
  ([db query {:keys [continuation-cache continuation-cache-fn]}]
   {:pre [(:type (:resource query)) (:id (:resource query))]}
   (when (:subject/relation query)
     ;; The recursive path has rejected this since v7.2; the non-recursive path
     ;; silently ignored it, returning subjects the caller did not filter for.
     (page-error! ":subject/relation is not supported by lookup-subjects."
                  {:eacl/error :eacl.pagination/unsupported-filter
                   :filter :subject/relation}))
   (let [cache (or continuation-cache
                   (when continuation-cache-fn (continuation-cache-fn)))]
     (if (traversal-permission? db (:type (:resource query)) (:permission query))
       (recursive-reverse-page db query cache)
       (lookup db reverse-direction query cache)))))

(def ^:private count-pagination-keys
  [:cursor :limit :first :last :before :after])

(defn- reject-count-pagination-keys!
  [op query]
  (when (some #(contains? query %) count-pagination-keys)
    (page-error! (str op " does not use list pagination keys.")
                 (select-keys query count-pagination-keys))))

(defn- query-count-limit
  [query]
  (when (contains? query :count-limit)
    (let [limit (:count-limit query)]
      (when-not (and (integer? limit) (not (neg? limit)))
        (page-error! ":count-limit must be a non-negative integer."
                     {:eacl/error :eacl.count/invalid-limit
                      :count-limit limit}))
      limit)))

(defn- count-response
  [{:keys [count truncated?]} limit]
  (cond-> {:count count
           :limit (or limit -1)}
    (some? limit) (assoc :truncated? truncated?)))

(defn- count-denotation
  [values limit]
  (let [total (count values)]
    (if (and limit (< limit total))
      {:count limit :truncated? true}
      {:count total :truncated? false})))

(defn- count-acyclic-pages
  "Counts through bounded, frontier-resuming pages.

  Each page realizes at most `count-page-size` EIDs. Unlike consuming the
  merged lazy result directly, this cannot retain the heads of every path for
  the full cardinality. Acyclic path frontiers make page-to-page work advance
  from the last EID rather than replaying a prefix."
  [db direction query limit]
  (loop [n 0
         bound nil]
    (let [remaining (when limit (- limit n))
          page-size (if remaining
                      (max 1 (min count-page-size (inc remaining)))
                      count-page-size)
          page-request {:direction :asc
                        :size page-size
                        :bound bound}
          {:keys [results path-frontiers]}
          (lazy-merged-lookup
           db direction (dissoc query :count-limit) page-request)
          {:keys [page-count last-eid has-sentinel?]}
          (loop [remaining (seq results)
                 page-count 0
                 last-eid nil]
            (cond
              (nil? remaining)
              {:page-count page-count
               :last-eid last-eid
               :has-sentinel? false}

              (= page-count page-size)
              {:page-count page-count
               :last-eid last-eid
               :has-sentinel? true}

              :else
              (recur (next remaining)
                     (unchecked-inc page-count)
                     (first remaining))))
          n' (+ n page-count)]
      (when *count-stats*
        (swap! *count-stats*
               (fn [stats]
                 (-> stats
                     (update :pages (fnil inc 0))
                     (update :max-page-eids
                             (fnil max 0)
                             page-count)))))
      (cond
        (and limit (> n' limit))
        {:count limit :truncated? true}

        has-sentinel?
        (recur n'
               (lookup-edge
                last-eid
                :asc
                path-frontiers))

        :else
        {:count n' :truncated? false}))))

(defn count-resources
  [db {:as query}]
  (reject-count-pagination-keys! "count-resources" query)
  (let [limit (query-count-limit query)]
    (if (traversal-permission? db (:resource/type query) (:permission query))
      (let [{:keys [subject permission]} query
            subject-eid (object-eid db (:id subject))
            result-type (:resource/type query)
            root-node   (permission-query-node result-type permission)]
        (count-response
         (if-not subject-eid
           {:count 0 :truncated? false}
           (if-let [cached
                    (lookup-forward-denotation
                     db (:type subject) subject-eid
                     root-node result-type)]
             (count-denotation cached limit)
             (if (nil? limit)
               (count-denotation
                (resolve-forward-denotation
                 db (:type subject) subject-eid
                 root-node result-type)
                nil)
               (count-traversal-items
                #(next-forward-item db root-node result-type %)
                (initial-forward-state
                 db (:type subject) subject-eid root-node)
                limit))))
         limit))
      (count-response
       (count-acyclic-pages db forward-direction query limit)
       limit))))

(defn count-subjects
  [db {:as query}]
  (reject-count-pagination-keys! "count-subjects" query)
  (when (:subject/relation query)
    (page-error! ":subject/relation is not supported by count-subjects."
                 {:eacl/error :eacl.pagination/unsupported-filter
                  :filter :subject/relation}))
  (let [limit (query-count-limit query)]
    (if (traversal-permission? db (:type (:resource query)) (:permission query))
      (let [{:keys [resource permission]} query
            resource-eid (object-eid db (:id resource))
            subject-type (:subject/type query)
            root-node    (permission-query-node (:type resource) permission)]
        (count-response
         (if-not resource-eid
           {:count 0 :truncated? false}
           (if-let [cached
                    (lookup-reverse-denotation
                     db (:type resource) resource-eid
                     root-node subject-type)]
             (count-denotation cached limit)
             (if (nil? limit)
               (count-denotation
                (resolve-reverse-denotation
                 db (:type resource) resource-eid
                 root-node subject-type)
                nil)
               (count-traversal-items
                #(next-reverse-item
                  db root-node resource-eid subject-type %)
                (initial-reverse-state
                 db subject-type root-node resource-eid)
                limit))))
         limit))
      (count-response
       (count-acyclic-pages db reverse-direction query limit)
       limit))))
