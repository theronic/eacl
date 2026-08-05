(ns eacl.engine.v8
  (:require [eacl.backend.v8 :as backend]
            [eacl.core :refer [spice-object]]
            [eacl.lazy-merge-sort :as lazy-sort]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

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

(defn- legacy-normalize-page-request
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

(defn- portable-page-natural?
  [value]
  (and
   #?(:clj (integer? value)
      :cljs (and (number? value)
                 (js/Number.isSafeInteger value)))
   (<= 0 value backend/maximum-exact-integer)))

(defn- generated-page-request-encodable?
  [query]
  (every?
   (fn [field]
     (or (not (contains? query field))
         (nil? (get query field))
         (portable-page-natural? (get query field))))
   [:first :last]))

(defn- generated-page-presence
  [query field boundary?]
  (cond
    (not (contains? query field)) :absent
    (nil? (get query field)) :nil
    boundary? 0
    :else (get query field)))

(defn- generated-page-input
  [query]
  {:length 0
   :request
   {:first (generated-page-presence query :first false)
    :last (generated-page-presence query :last false)
    :after (generated-page-presence query :after true)
    :before (generated-page-presence query :before true)
    :has-legacy-limit? (contains? query :limit)
    :has-legacy-cursor? (contains? query :cursor)}
   :default-size default-page-size
   :maximum-size max-page-size})

(defn- generated-page-error!
  [query reason]
  (let [size (or (when (contains? query :first) (:first query))
                 (when (contains? query :last) (:last query))
                 default-page-size)]
    (case reason
      :legacy-pagination
      (if (contains? query :cursor)
        (page-error!
         ":cursor is not supported; use :first/:after or :last/:before."
         {:key :cursor})
        (page-error!
         ":limit is not supported for list pagination; use :first or :last."
         {:key :limit}))

      :both-directions
      (page-error!
       "Use exactly one of :first or :last."
       {:first (:first query) :last (:last query)})

      :both-bounds
      (page-error!
       "Use only one cursor boundary, :after or :before."
       {:after (:after query) :before (:before query)})

      :after-without-first
      (page-error! ":after is valid only with :first."
                   {:after (:after query)})

      :before-without-last
      (page-error! ":before is valid only with :last."
                   {:before (:before query)})

      :nil-after
      (page-error! ":after was passed as nil. Omit it for the first page."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :key :after})

      :nil-before
      (page-error! ":before was passed as nil. Omit it for the last page."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :key :before})

      :non-positive-size
      (page-error! "Page size must be a positive integer." {:size size})

      :oversized-page
      (page-error! "Page size exceeds configured maximum."
                   {:size size :max max-page-size})

      (page-error!
       "Generated page normalization returned an unknown error."
       {:type :eacl.verification/kernel-failure
        :operation :relationship-page
        :reason reason}))))

(defn normalize-page-request
  [query]
  (let [selection subproblem/*engine-selection*
        mode (if (map? selection) (:mode selection) selection)
        verified? (and (some? selection)
                       (not= :legacy-authoritative mode))]
    (if-not (and verified? (generated-page-request-encodable? query))
      (legacy-normalize-page-request query)
      (let [legacy
            #(let [{:keys [direction size]}
                   (legacy-normalize-page-request query)]
               {:status :valid
                :direction direction
                :size size
                :start 0
                :end 0
                :has-next? false
                :has-previous? false})
            decision
            (verified/decide
             selection
             :relationship-page
             (generated-page-input query)
             legacy)]
        (if (= :valid (:status decision))
          {:direction (:direction decision)
           :size (:size decision)
           :bound
           (case (:direction decision)
             :asc (:after query)
             :desc (:before query))}
          (generated-page-error! query (:reason decision)))))))

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
    :desc
    (lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc identity seqs)))

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
     (->> (:sub-paths path)
          (map path-frontier-identity)
          (sort-by pr-str)
          vec)]

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
  [operation key-args dependency opts raw-evaluate]
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
          (subproblem/resolve-layered-bound!
           :denotation
           key
           {:valid? #(valid-projection-chunk?
                      (:direction opts) chunk-size %)
            :weight-fn projection-weight}
           dependency
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
           dependency
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
    :permission-roots (atom {})
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
   (some-> (:permission-roots schema-cache) (reset! {}))
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

(defn- permission-root-defined?
  [db resource-type permission-name]
  (if-not (and (some? (:schema-version *schema-cache*))
               (some? (:permission-roots *schema-cache*)))
    (boolean
     (seq (find-permission-defs db resource-type permission-name)))
    (let [cache-key
          (permission-paths-cache-key resource-type permission-name)
          cache-atom (:permission-roots *schema-cache*)
          snapshot @cache-atom]
      (if (contains? snapshot cache-key)
        (get snapshot cache-key)
        (let [defined?
              (boolean
               (seq
                (find-permission-defs
                 db resource-type permission-name)))]
          (get
           (swap! cache-atom
                  #(if (contains? % cache-key)
                     %
                     (assoc % cache-key defined?)))
           cache-key))))))

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

(defn- routing-certificate-error!
  [message data]
  (throw
   (ex-info
    message
    (merge
     {:type :eacl/internal-schema-error
      :eacl/error :eacl/internal-schema-error}
     data))))

(defn- indexed-routing-path-descriptor
  "Translates one materialized permission path into the portable routing
  descriptor checked by Dafny. `node-count` is an invalid-index sentinel:
  an incomplete backend node catalogue therefore fails closed at the
  generated boundary instead of silently deleting a dependency."
  [node-index node-count head path]
  (let [head-index (get node-index head node-count)]
    (case (:type path)
      :relation
      {:kind :relation
       :head head-index}

      :self-permission
      {:kind :self-permission
       :head head-index
       :target
       (get
        node-index
        (permission-query-node
         (first head)
         (:target-permission path))
        node-count)}

      :arrow
      (if-let [target-permission (:target-permission path)]
        {:kind :arrow-permission
         :head head-index
         :target
         (get
          node-index
          (permission-query-node
           (:target-type path)
           target-permission)
          node-count)}
        {:kind :arrow-relation
         :head head-index}))))

(defn- routing-path-dependency-edge
  [{:keys [kind head target]}]
  (case kind
    :self-permission
    {:head head :target target}

    :arrow-permission
    {:head head :target target}

    :relation
    nil

    :arrow-relation
    nil))

(defn- component-parent-tree
  "Builds one linear-size reachability witness tree inside an SCC.

  `reverse?` means `graph` is transposed: discovering `child` from `parent`
  then records the original edge child -> parent, proving child reaches the
  component root."
  [graph component root edge-index reverse?]
  (let [component-set (set component)]
    (loop [stack [root]
           seen #{root}
           parents {root -1}
           depths {root 0}]
      (if-let [node (peek stack)]
        (let [state
              (reduce
               (fn [{:keys [stack seen parents depths] :as state}
                    neighbor]
                 (if (or (not (contains? component-set neighbor))
                         (contains? seen neighbor))
                   state
                   (let [edge-key
                         (if reverse?
                           [neighbor node]
                           [node neighbor])
                         parent-edge (get edge-index edge-key)]
                     (when-not (some? parent-edge)
                       (routing-certificate-error!
                        "Recursive routing witness references a missing edge."
                        {:edge edge-key :reverse? reverse?}))
                     {:stack (conj stack neighbor)
                      :seen (conj seen neighbor)
                      :parents (assoc parents neighbor parent-edge)
                      :depths
                      (assoc depths neighbor (inc (get depths node)))})))
               {:stack (pop stack)
                :seen seen
                :parents parents
                :depths depths}
               (get graph node []))]
          (recur
           (:stack state)
           (:seen state)
           (:parents state)
           (:depths state)))
        (do
          (when-not (= component-set seen)
            (routing-certificate-error!
             "Recursive routing witness does not span its claimed SCC."
             {:component component
              :root root
              :reverse? reverse?
              :missing (vec (sort-by pr-str (remove seen component-set)))}))
          {:parents parents :depths depths})))))

(defn- calc-traversal-artifacts
  "Produces the fast host classification and its Dafny-checkable certificate.

  The certificate is linear in the permission graph. Its two parent forests
  prove mutual reachability inside each claimed SCC; component ranks prove
  distinct SCCs cannot be mutually reachable; local witnesses prove recursion
  and exact reverse propagation through the condensation DAG."
  [db]
  (let [nodes
        (vec
         (sort-by
          pr-str
          (set (backend/invoke db :all-permission-nodes))))
        node-index (zipmap nodes (range))
        node-count (count nodes)
        path-descriptors
        (vec
         (mapcat
          (fn [head]
            (map
             #(indexed-routing-path-descriptor
               node-index
               node-count
               head
               %)
             (get-permission-paths db (first head) (second head))))
          nodes))
        indexed-edges
        (into []
              (keep routing-path-dependency-edge)
              path-descriptors)
        valid-indexed-edges
        (into []
              (keep-indexed
               (fn [index {:keys [head target] :as edge}]
                 (when (and (< head node-count) (< target node-count))
                   [index edge])))
              indexed-edges)
        graph
        (reduce
         (fn [result [_ {:keys [head target]}]]
           (update result (nth nodes head) conj (nth nodes target)))
         (zipmap nodes (repeat []))
         valid-indexed-edges)
        transposed (transpose-graph nodes graph)
        components (mapv vec (graph-components nodes graph))
        component-root
        (into {}
              (mapcat
               (fn [component]
                 (let [root (first component)]
                   (map #(vector % root) component)))
               components))
        component-rank
        (into {}
              (map-indexed
               (fn [rank component]
                 [(first component) rank])
               components))
        recursive-roots
        (->> components
             (filter
              (fn [component]
                (or (> (count component) 1)
                    (let [node (first component)]
                      (some #{node} (get graph node))))))
             (map first)
             set)
        recursive-nodes
        (into #{}
              (filter
               #(contains?
                 recursive-roots
                 (get component-root %)))
              nodes)
        traversal-nodes
        (reachable-from-many transposed recursive-nodes)
        analysis
        (into {}
              (map
               (fn [node]
                 [node (contains? traversal-nodes node)])
               nodes))
        edge-index
        (into {}
              (map
               (fn [[index {:keys [head target]}]]
                 [[(nth nodes head) (nth nodes target)] index])
               valid-indexed-edges))
        trees
        (reduce
         (fn [result component]
           (let [root (first component)
                 forward
                 (component-parent-tree
                  graph component root edge-index false)
                 reverse
                 (component-parent-tree
                  transposed component root edge-index true)]
             (-> result
                 (update :forward-parents merge (:parents forward))
                 (update :forward-depths merge (:depths forward))
                 (update :reverse-parents merge (:parents reverse))
                 (update :reverse-depths merge (:depths reverse)))))
         {:forward-parents {}
          :forward-depths {}
          :reverse-parents {}
          :reverse-depths {}}
         components)
        multiple-member-witnesses
        (into {}
              (keep
               (fn [component]
                 (when (> (count component) 1)
                   [(first component) (second component)])))
              components)
        self-loop-witnesses
        (reduce
         (fn [result [index {:keys [head target]}]]
           (if (= head target)
             (let [node (nth nodes head)]
               (assoc
                result
                (get component-root node)
                index))
             result))
         {}
         valid-indexed-edges)
        traversal-witnesses
        (reduce
         (fn [result [index {:keys [head target]}]]
           (let [head-node (nth nodes head)
                 target-node (nth nodes target)
                 head-root (get component-root head-node)
                 target-root (get component-root target-node)]
             (if (and (not= head-root target-root)
                      (contains? traversal-nodes target-node)
                      (not (contains? recursive-roots head-root))
                      (not (contains? result head-root)))
               (assoc result head-root index)
               result)))
         {}
         valid-indexed-edges)
        certificate
        {:component-root
         (mapv #(get node-index (get component-root %)) nodes)
         :forward-parent-edge
         (mapv #(get (:forward-parents trees) %) nodes)
         :reverse-parent-edge
         (mapv #(get (:reverse-parents trees) %) nodes)
         :forward-depth
         (mapv #(get (:forward-depths trees) %) nodes)
         :reverse-depth
         (mapv #(get (:reverse-depths trees) %) nodes)
         :component-rank
         (mapv #(get component-rank (get component-root %)) nodes)
         :multiple-member-witness
         (mapv
          (fn [node]
            (if-let [member (get multiple-member-witnesses node)]
              (get node-index member)
              -1))
          nodes)
         :self-loop-witness-edge
         (mapv #(get self-loop-witnesses % -1) nodes)
         :traversal (mapv #(get analysis %) nodes)
         :traversal-witness-edge
         (mapv #(get traversal-witnesses % -1) nodes)}]
    {:nodes nodes
     :analysis analysis
     :certificate-input
     {:node-count node-count
      :path-descriptors path-descriptors
      :edges indexed-edges
      :certificate certificate}}))

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

(declare verified-engine-selection?)

(defn- calc-traversal-analysis
  "Classifies every permission node into one shared schema-generation result.

  Once permission paths are materialized, deterministic node indexing costs
  O(V log V) `pr-str` comparisons; the iterative graph analysis and certificate
  construction are O(V+P+E). The generated checker performs exactly P+2V+E
  certified loop iterations, including exact validation of path-to-edge
  derivation. Each iteration performs constant-time generated operations.
  Sharing the result prevents a large schema from recompiling or
  recertifying recursive routing independently for every first-read root."
  [db]
  (let [{:keys [nodes analysis certificate-input]}
        (calc-traversal-artifacts db)]
    (if-not (verified-engine-selection?)
      analysis
      (let [expected
            {:status :accepted
             :traversal
             (mapv #(get analysis %) nodes)
             :path-checks (count (:path-descriptors certificate-input))
             :node-checks (* 2 (count nodes))
             :edge-checks (count (:edges certificate-input))}
            decision
            (verified/decide
             subproblem/*engine-selection*
             :recursive-routing-certificate
             certificate-input
             (constantly expected))]
        (when-not (= :accepted (:status decision))
          (routing-certificate-error!
           "Generated verification rejected recursive SCC routing."
           {:reason (:reason decision)
            :path-checks (:path-checks decision)
            :node-checks (:node-checks decision)
            :edge-checks (:edge-checks decision)}))
        (zipmap nodes (:traversal decision))))))

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

(def ^:private recursive-engine-version 2)

(def default-recursive-traversal-limits
  "Safety ceilings for one recursive traversal.

  These bound a SINGLE page computation. A cache-disabled recursive cursor may
  replay its traversal prefix when its relationship proof is still current.
  Cache-enabled cursors resume a retained continuation and fail explicitly
  when that continuation has expired.

  These are logical work/cardinality ceilings, not JVM-byte, live-heap, CPU,
  backend-command, or wall-time bounds. `:max-queued-work` bounds instantaneous
  queue depth; `:max-derived-grants` and `:max-advanced-datoms` bound unique
  derived grants and consumed projection values. Use :count-limit for bounded
  counts, model large permissions acyclically, or tune these limits only after
  representative heap/load tests."
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

(defn- verified-engine-selection?
  []
  (let [selection subproblem/*engine-selection*]
    (and (some? selection)
         (not= :legacy-authoritative selection)
         (not (and (map? selection)
                   (= :legacy-authoritative (:mode selection)))))))

(defn- generated-authoritative?
  []
  (let [selection subproblem/*engine-selection*]
    (= :verified-authoritative
       (if (map? selection)
         (:mode selection)
         selection))))

(defn- generated-shadow?
  []
  (let [selection subproblem/*engine-selection*]
    (= :verified-shadow
       (if (map? selection)
         (:mode selection)
         selection))))

(defn- indexed-authoritative-root?
  "Verified authority uses the generated indexed state machine for every
  permission root. Legacy and shadow-primary execution retain the optimized
  acyclic path unless the schema actually requires recursive traversal."
  [db resource-type permission]
  (and
   (permission-root-defined? db resource-type permission)
   (or
    (generated-authoritative?)
    (traversal-permission? db resource-type permission))))

(defn- shadow-authoritative-selection
  []
  (let [selection subproblem/*engine-selection*]
    (if (map? selection)
      (-> selection
          (assoc :mode :verified-authoritative)
          (dissoc :report-divergence))
      :verified-authoritative)))

(def ^:private dimensional-counter-keys
  [:backend-commands
   :adapter-fetched-values
   :engine-consumed-values
   :cumulative-enqueues
   :current-queue-depth
   :maximum-queue-depth
   :unique-grants
   :emitted-results
   :rule-applications
   :consumer-grant-joins
   :render-advances])

(def ^:private legacy-dimensional-counter-keys
  {:backend-commands :stream-fills
   :adapter-fetched-values :fetched-stream-datoms
   :engine-consumed-values :advanced-stream-datoms
   :cumulative-enqueues :cumulative-enqueues
   :current-queue-depth :current-queue-depth
   :maximum-queue-depth :maximum-queue-depth
   :unique-grants :derived-grants
   :emitted-results :emitted-results
   :rule-applications :rule-applications
   :consumer-grant-joins :consumer-grant-joins
   :render-advances :render-advances})

(defn- dimensional-counter-view
  [stats]
  (let [generated (:generated-dimensional-counters stats)]
    (into
     {}
     (map
      (fn [counter-key]
        [counter-key
         (long
          (get
           generated
           counter-key
           (get stats
                (get legacy-dimensional-counter-keys counter-key)
                0)))])
      dimensional-counter-keys))))

(defn- merge-dimensional-counters
  [current sample]
  (reduce-kv
   (fn [counters k value]
     (case k
       :current-queue-depth
       (assoc counters k value)

       :maximum-queue-depth
       (update counters k (fnil max 0) value)

       (update counters k (fnil + 0) value)))
   (or current {})
   sample))

(defn- merge-shadow-stats!
  [target source]
  (when target
    (swap!
     target
     (fn [current]
       (reduce-kv
        (fn [stats k value]
          (cond
            (= :current-queue-depth k)
            (assoc stats k value)

            (= :maximum-queue-depth k)
            (update stats k (fnil max 0) value)

            (number? value)
            (update stats k (fnil + 0) value)

            :else
            (assoc stats k value)))
        current
        source)))))

(defn- evaluate-with-shadow-stats
  [evaluation]
  (if (generated-shadow?)
    (let [outer-stats *recursive-traversal-stats*
          comparable-resource-path? (nil? subproblem/*store*)
          local-stats (atom {})]
      (try
        (try
          (let [result
                (binding [*recursive-traversal-stats* local-stats]
                  (evaluation))]
            [result
             (when comparable-resource-path?
               @local-stats)
             nil])
          (catch #?(:clj Exception :cljs :default) error
            [nil
             (when comparable-resource-path?
               @local-stats)
             error]))
        (finally
          (merge-shadow-stats! outer-stats @local-stats))))
    [(evaluation) nil nil]))

(defn- shadow-cursor-position
  [cursor]
  (when cursor
    (case (:kind cursor)
      :lookup-eid
      {:result-eid (:result-eid cursor)}

      :recursive-traversal
      {:result-eid (get-in cursor [:result :eid])}

      ;; Unknown cursor variants must remain visible to the comparison rather
      ;; than being collapsed into a position that could hide a divergence.
      cursor)))

(defn- public-page-shadow-value
  [value]
  (if (and (map? value) (map? (:page-info value)))
    (-> value
        (update-in
         [:page-info :start-cursor]
         shadow-cursor-position)
        (update-in
         [:page-info :end-cursor]
         shadow-cursor-position))
    value))

(defn- traversal-shadow-view
  [value stats compare-resources? normalize-page-cursors?]
  (cond->
   {:value
    (if normalize-page-cursors?
      (public-page-shadow-value value)
      value)}
    compare-resources?
    (assoc
     :counters (dimensional-counter-view stats))))

(defn- compare-generated-shadow!
  [operation legacy legacy-stats same-traversal-algorithm? generated-result]
  (let [selection subproblem/*engine-selection*
        generated-stats (atom {})
        compare-resources?
        (and same-traversal-algorithm? (some? legacy-stats))
        normalize-page-cursors? (not same-traversal-algorithm?)]
    (:value
     (verified/compare-shadow!
      selection
      operation
      (traversal-shadow-view
       legacy legacy-stats compare-resources? normalize-page-cursors?)
      #(let [value
             (binding [subproblem/*engine-selection*
                       (shadow-authoritative-selection)
                       subproblem/*store* nil
                       subproblem/*managed-store* nil
                       subproblem/*managed-key-fn* nil
                       subproblem/*managed-scope* nil
                       *recursive-traversal-stats* generated-stats]
               (generated-result))]
         (traversal-shadow-view
          value
          @generated-stats
          compare-resources?
          normalize-page-cursors?))))))

(defn- compare-generated-shadow-error!
  [operation legacy-error generated-result]
  (let [selection subproblem/*engine-selection*]
    (verified/compare-shadow!
     selection
     operation
     (verified/error-shadow-view legacy-error)
     #(binding [subproblem/*engine-selection*
                (shadow-authoritative-selection)
                subproblem/*store* nil
                subproblem/*managed-store* nil
                subproblem/*managed-key-fn* nil
                subproblem/*managed-scope* nil
                *recursive-traversal-stats* (atom {})]
        (try
          (generated-result)
          {:outcome :value}
          (catch #?(:clj Exception :cljs :default) error
            (verified/error-shadow-view error)))))
    (throw legacy-error)))

(defn- track-dimensional-counters?
  []
  (or (some? *recursive-traversal-stats*)
      (verified-engine-selection?)))

(defn- recursive-traversal-error!
  [message data]
  (throw (ex-info message data)))

(defn- stale-recursive-cursor!
  [message]
  (recursive-traversal-error!
   message
   {:eacl/error :eacl.pagination/stale-cursor}))

(defn- inc-stat!
  [k]
  (when *recursive-traversal-stats*
    (swap! *recursive-traversal-stats* update k (fnil inc 0))))

(defn- add-stat!
  [k n]
  (when *recursive-traversal-stats*
    (swap! *recursive-traversal-stats* update k (fnil + 0) n)))

(defn- set-stat!
  [k value]
  (when *recursive-traversal-stats*
    (swap! *recursive-traversal-stats* assoc k value)))

(defn- max-stat!
  [k value]
  (when *recursive-traversal-stats*
    (swap! *recursive-traversal-stats* update k (fnil max 0) value)))

(defn- account-dimensional-work
  [state counter-key amount]
  (add-stat! counter-key amount)
  (cond-> state
    (track-dimensional-counters?)
    (update-in [:counters counter-key] (fnil + 0) amount)))

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
        queue-depth (count queue')
        limit (:max-queued-work *recursive-traversal-limits*)]
    (when (and limit (> (count queue') limit))
      (recursive-traversal-error!
       "Recursive traversal queue safety limit exceeded."
       {:eacl/error :eacl.recursive-traversal/limit-exceeded
        :limit-kind :queued-work
        :limit limit}))
    (when (track-dimensional-counters?)
      (inc-stat! :cumulative-enqueues)
      (set-stat! :current-queue-depth queue-depth)
      (max-stat! :maximum-queue-depth queue-depth))
    (cond-> (assoc state :queue queue')
      (track-dimensional-counters?)
      (-> (update-in
           [:counters :cumulative-enqueues]
           (fnil inc 0))
          (assoc-in
           [:counters :current-queue-depth]
           (count queue'))
          (update-in
           [:counters :maximum-queue-depth]
           (fnil max 0)
           (count queue'))))))

(defn- enqueue-works
  [state works]
  (reduce enqueue-work state works))

(defn- pop-work
  [state]
  (let [queue' (pop (:queue state))]
    (when (track-dimensional-counters?)
      (set-stat! :current-queue-depth (count queue')))
    [(peek (:queue state))
     (cond-> (assoc state :queue queue')
       (track-dimensional-counters?)
       (assoc-in
        [:counters :current-queue-depth]
        (count queue')))]))

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

(defn- forward-seeds-by-subject-type
  [rules]
  (->> rules
       (keep (fn [rule]
               (case (:rule rule)
                 :relation
                 [(:subject-type rule) rule]

                 :arrow-relation
                 [(:target-subject-type rule) rule]

                 nil)))
       (group-by first)
       (into {}
             (map (fn [[subject-type pairs]]
                    [subject-type
                     (mapv second
                           (sort-by (comp :id second) pairs))])))))

(defn- rules-by-node
  [rules]
  (->> rules
       (group-by :node)
       (into {}
             (map (fn [[node node-rules]]
                    [node (vec (sort-by :id node-rules))])))))

(defn- verification-identity
  "Injective, portable representation of one schema identity at the generated
  boundary. `pr-str` keeps keywords, strings, symbols, and namespaces
  distinct; using `name` would silently conflate them."
  [value]
  (pr-str value))

(defn- verification-permission-node
  [[resource-type permission]]
  {:resource-type (verification-identity resource-type)
   :permission (verification-identity permission)})

(defn- verification-relation-node
  [{:keys [v]}]
  {:resource-type (verification-identity (nth v 0))
   :relation (verification-identity (nth v 1))
   :subject-type (verification-identity (nth v 2))})

(defn- verification-relation-binding
  [{:keys [e] :as datom}]
  {:eid e
   :relation (verification-relation-node datom)})

(defn- recursive-plan-schema-error!
  [message data]
  (throw
   (ex-info
    message
    (merge
     {:type :eacl/internal-schema-error
      :eacl/error :eacl/internal-schema-error}
     data))))

(defn- require-relation-datoms!
  [db resource-type relation-name context]
  (let [datoms (vec (relation-datoms db resource-type relation-name))]
    (when (empty? datoms)
      (recursive-plan-schema-error!
       "Recursive plan certification found a missing relation definition."
       {:resource-type resource-type
        :relation-name relation-name
        :context context}))
    datoms))

(defn- certification-permission-dependencies
  "Expands permission-to-permission edges from raw backend definitions.

  This intentionally does not call `get-permission-paths`: the source graph
  used to certify the optimized compiler must be independent of that
  compiler's path transformation."
  [db [resource-type permission :as node]]
  (mapcat
   (fn [{:eacl.permission/keys
         [source-relation-name target-type target-name]}]
     (cond
       (and (= :self source-relation-name)
            (= :permission target-type))
       [[resource-type target-name]]

       (= :self source-relation-name)
       []

       (= :permission target-type)
       (mapv
        (fn [datom]
          [(nth (:v datom) 2) target-name])
        (require-relation-datoms!
         db
         resource-type
         source-relation-name
         {:permission-node node
          :target-type target-type
          :target-name target-name}))

       (= :relation target-type)
       []

       :else
       (recursive-plan-schema-error!
        "Recursive plan certification found an unknown permission target."
        {:permission-node node
         :target-type target-type
         :target-name target-name})))
   (find-permission-defs db resource-type permission)))

(defn- certification-reachable-permission-nodes
  [db root-node]
  (loop [stack [root-node]
         seen #{}]
    (if-let [node (peek stack)]
      (if (contains? seen node)
        (recur (pop stack) seen)
        (recur
         (into
          (pop stack)
          (certification-permission-dependencies db node))
         (conj seen node)))
      (vec seen))))

(defn- verification-definition
  [kind resource-type permission fields]
  (merge
   {:kind kind
    :resource-type (verification-identity resource-type)
    :permission (verification-identity permission)}
   (into {}
         (map (fn [[key value]]
                [key (verification-identity value)]))
         fields)))

(defn- certification-definition-fragment
  [db [resource-type permission :as node]
   {:eacl.permission/keys
    [source-relation-name target-type target-name]}]
  (cond
    (and (= :self source-relation-name)
         (= :relation target-type))
    (let [targets
          (require-relation-datoms!
           db resource-type target-name
           {:permission-node node
            :target-type target-type})]
      {:definitions
       (mapv
        (fn [target]
          (verification-definition
           :direct-relation
           resource-type
           permission
           {:relation target-name
            :subject-type (nth (:v target) 2)}))
        targets)
       :relation-datoms targets})

    (and (= :self source-relation-name)
         (= :permission target-type))
    {:definitions
     [(verification-definition
       :self-permission
       resource-type
       permission
       {:target-permission target-name})]
     :relation-datoms []}

    (= :permission target-type)
    (let [via-datoms
          (require-relation-datoms!
           db resource-type source-relation-name
           {:permission-node node
            :target-type target-type
            :target-name target-name})]
      {:definitions
       [(verification-definition
         :arrow-permission
         resource-type
         permission
         {:via-relation source-relation-name
          :target-permission target-name})]
       :relation-datoms via-datoms})

    (= :relation target-type)
    (let [via-datoms
          (require-relation-datoms!
           db resource-type source-relation-name
           {:permission-node node
            :target-type target-type
            :target-name target-name})
          target-datoms
          (mapcat
           (fn [via]
             (require-relation-datoms!
              db
              (nth (:v via) 2)
              target-name
              {:permission-node node
               :via-relation source-relation-name
               :target-type target-type}))
           via-datoms)]
      {:definitions
       (->> target-datoms
            (map
             (fn [target]
               (verification-definition
                :arrow-relation
                resource-type
                permission
                {:via-relation source-relation-name
                 :target-relation target-name
                 :subject-type (nth (:v target) 2)})))
            distinct
            vec)
       :relation-datoms
       (into (vec via-datoms) target-datoms)})

    :else
    (recursive-plan-schema-error!
     "Recursive plan certification found an unknown permission target."
     {:permission-node node
      :source-relation-name source-relation-name
      :target-type target-type
      :target-name target-name})))

(defn- certification-source-plan
  [db root-node]
  (let [nodes
        (sort
         (certification-reachable-permission-nodes db root-node))
        actual-permissions (set (all-permission-nodes db))
        missing-permissions (seq (remove actual-permissions nodes))]
    (when missing-permissions
      (recursive-plan-schema-error!
       "Recursive plan certification found a missing permission definition."
       {:root-node root-node
        :missing-permission-nodes (vec missing-permissions)}))
    (let [fragments
          (mapcat
           (fn [[resource-type permission :as node]]
             (map
              #(certification-definition-fragment db node %)
              (find-permission-defs db resource-type permission)))
           nodes)
          definitions
          (->> fragments
               (mapcat :definitions)
               distinct
               (sort-by pr-str)
               vec)
          relation-datoms
          (->> fragments
               (mapcat :relation-datoms)
               (reduce
                (fn [by-eid datom]
                  (let [eid (:e datom)
                        prior (get by-eid eid)]
                    (when (and prior (not= prior datom))
                      (recursive-plan-schema-error!
                       "One relation eid resolved to inconsistent definitions."
                       {:relation-eid eid
                        :left prior
                        :right datom}))
                    (assoc by-eid eid datom)))
                {})
               vals
               (sort-by :e)
               vec)]
      {:relations (mapv verification-relation-node relation-datoms)
       :permissions
       ;; The generated plan is root-scoped. Including unrelated permissions
       ;; would make every root certificate scan the global schema even though
       ;; no compiled rule can reach those nodes.
       (->> nodes
            (sort-by pr-str)
            (mapv verification-permission-node))
       :definitions definitions
       :relation-bindings
       (mapv verification-relation-binding relation-datoms)})))

(defn- verification-indexed-rule
  [{:keys [rule node relation-eid subject-type target-node
           via-relation-eid intermediate-type target-relation-eid
           target-subject-type]}]
  (merge
   {:kind rule
    :head (verification-permission-node node)}
   (case rule
     :relation
     {:relation-eid relation-eid
      :subject-type (verification-identity subject-type)}

     :self-permission
     {:target-node (verification-permission-node target-node)}

     :arrow-relation
     {:via-relation-eid via-relation-eid
      :intermediate-type (verification-identity intermediate-type)
      :target-relation-eid target-relation-eid
      :target-subject-type
      (verification-identity target-subject-type)}

     :arrow-permission
     {:via-relation-eid via-relation-eid
      :intermediate-type (verification-identity intermediate-type)
      :target-node (verification-permission-node target-node)})))

(defn- indexed-root-denotation-identity
  "Canonical root body consumed by the denotation cache.

  `verification-rules` is the exact portable rule vector consumed by generated
  plan certification. Removing only `:head` from rules for this root therefore
  erases the queried permission name while retaining every indexed body field
  covered by `IndexedRootDenotation.dfy`. A set is intentional: authorization
  is a union, so duplicate equal root rules do not change the denotation, and
  structural set equality avoids a host-specific printed sort order."
  [root-node verification-rules]
  (let [root-head (verification-permission-node root-node)]
    [:permission-root-denotation
     2
     (verification-identity (first root-node))
     (into
      #{}
      (comp
       (filter #(= root-head (:head %)))
       (map #(dissoc % :head)))
      verification-rules)]))

(defn- verification-identity-catalog
  "Builds the inverse type catalog used only for generated scan commands.

  Generated code sees portable injective strings; backend adapters must receive
  the original schema values. A collision therefore fails plan compilation
  instead of selecting an arbitrary type. The catalog is compiled once with
  the schema plan and is never rebuilt or parsed on the query path."
  [root-node rules]
  (let [values
        (into
         [(first root-node)]
         (mapcat
          (fn [{:keys [node subject-type target-node intermediate-type
                       target-subject-type]}]
            (remove
             nil?
             [(first node)
              subject-type
              (first target-node)
              intermediate-type
              target-subject-type]))
          rules))]
    (reduce
     (fn [catalog value]
       (let [identity (verification-identity value)
             existing (get catalog identity ::missing)]
         (when (and (not= ::missing existing)
                    (not= existing value))
           (recursive-plan-schema-error!
            "Portable verification identities collide for backend types."
            {:identity identity
             :left existing
             :right value}))
         (assoc catalog identity value)))
     {}
     values)))

(defn- certify-recursive-plan!
  [db root-node rules forward-seeds]
  (when (verified-engine-selection?)
    (let [source (certification-source-plan db root-node)
          indexed-rules (mapv verification-indexed-rule rules)
          seed-subject-types
          (-> (set (keys forward-seeds))
              (into
               (keep
                (fn [rule]
                  (case (:rule rule)
                    :relation (:subject-type rule)
                    :arrow-relation (:target-subject-type rule)
                    nil))
                rules))
              (conj (first root-node))
              (->> (sort-by pr-str)))
          selection subproblem/*engine-selection*]
      (add-stat! :plan-certification-runs 1)
      (add-stat!
       :plan-certification-rules
       (count indexed-rules))
      (add-stat!
       :plan-certification-definitions
       (count (:definitions source)))
      (add-stat!
       :plan-certification-bindings
       (count (:relation-bindings source)))
      (add-stat!
       :plan-certification-seed-buckets
       (count seed-subject-types))
      (add-stat!
       :plan-certification-kernel-calls
       (inc (count seed-subject-types)))
      (let [decision
            (verified/decide
             selection
             :indexed-plan-certification
             (assoc source :indexed-rules indexed-rules)
             (constantly {:status :certified}))]
        (when-not (= {:status :certified} decision)
          (recursive-plan-schema-error!
           "Generated verification rejected the recursive traversal plan."
           {:root-node root-node
            :reason (:reason decision)})))
      (doseq [subject-type seed-subject-types]
        (let [decision
              (verified/decide
               selection
               :indexed-seed-certification
               {:indexed-rules indexed-rules
                :seed-rules
                (mapv
                 verification-indexed-rule
                 (get forward-seeds subject-type []))
                :subject-type
                (verification-identity subject-type)}
               (constantly {:status :certified}))]
          (when-not (= {:status :certified} decision)
            (recursive-plan-schema-error!
             "Generated verification rejected the recursive traversal plan."
             {:root-node root-node
              :subject-type subject-type
              :reason (:reason decision)})))))))

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
        rules-by-node' (rules-by-node rules)
        forward-seeds (forward-seeds-by-subject-type rules)
        verification-rules (mapv verification-indexed-rule rules)]
    (certify-recursive-plan!
     db root-node rules forward-seeds)
    (let [compiled-verification-plan
          (when (verified-engine-selection?)
            (verified/compile-indexed-plan
             subproblem/*engine-selection*
             {:indexed-rules verification-rules
              :seed-rules-by-subject-type
              (into
               {}
               (map
                (fn [[subject-type seed-rules]]
                  [(verification-identity subject-type)
                   (mapv verification-indexed-rule seed-rules)]))
               forward-seeds)}))]
      (merge
       component-plan
       {:rules rules
        :verification-indexed-rules verification-rules
        :root-denotation-identity
        (indexed-root-denotation-identity root-node verification-rules)
        :verification-compiled-plan compiled-verification-plan
        :verification-identity-catalog
        (verification-identity-catalog root-node rules)
        :forward-consumers (forward-consumers rules)
        :forward-seeds-by-subject-type
        forward-seeds
        :rules-by-node rules-by-node'
        :rules-by-component
        (into {}
              (map
               (fn [{:keys [id nodes]}]
                 [id (vec (mapcat #(get rules-by-node' %) nodes))])
               (:components component-plan)))}))))

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
  ;; for its maximum 64-EID chunk, scan descriptor, and continuation command.
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

(defn- buffered-stream-values
  [state]
  (reduce
   (fn [total work]
     (+ total
        (if (= :stream (:kind work))
          (count (:eids work))
          0)))
   0
   (:queue state)))

(defn- forward-retained-logical-units
  [state retained-delivered-units]
  (+ (count (:queue state))
     (buffered-stream-values state)
     (count (:consumers state))
     (count (:seen-grants state))
     (count (:emitted-root state))
     retained-delivered-units))

(defn- reverse-retained-logical-units
  [state retained-delivered-units]
  (+ (count (:queue state))
     (buffered-stream-values state)
     (count (:rules-by-node state))
     (count (:seen-goals state))
     (count (:seen-grants state))
     (count (:grants-by-goal state))
     (count (:consumers state))
     (count (:seen-consumers state))
     (count (:emitted-subjects state))
     retained-delivered-units))

(defn- record-retained-logical-units!
  [state retained-delivered-units]
  (when *recursive-traversal-stats*
    (set-stat!
     :legacy-retained-logical-units
     (if (contains? state :seen-goals)
       (reverse-retained-logical-units
        state retained-delivered-units)
       (forward-retained-logical-units
        state retained-delivered-units)))))

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

(defn- cached-generated-continuation
  [continuation-cache bound direction result-kind]
  (when-let [get-continuation (:get continuation-cache)]
    (when-let [{:keys [engine-version implementation state counters
                       retained-logical-units]
                :as continuation}
               (try
                 (get-continuation bound)
                 (catch #?(:clj Exception :cljs :default) _
                   nil))]
      (when (and (= recursive-engine-version engine-version)
                 (= :generated-indexed implementation)
                 (= direction (:direction continuation))
                 (= result-kind (:result-kind continuation))
                 (= bound (:bound continuation))
                 (some? state)
                 (map? counters)
                 (= (set dimensional-counter-keys)
                    (set (keys counters)))
                 (every? portable-page-natural? (vals counters))
                 (portable-page-natural? retained-logical-units))
        (inc-stat! :continuation-hits)
        continuation))))

(defn- generated-continuation-weight
  [retained-logical-units]
  ;; This is a constant-time admission estimate, not a JVM heap theorem.
  ;; Dafny computes retained logical units from the exact generated state;
  ;; direct JVM/Node peak measurements gate the estimate against real heaps.
  (+ 4096
     (* 256
        (min retained-logical-units
             (quot (- backend/maximum-exact-integer 4096) 256)))))

(defn- store-generated-continuation!
  [continuation-cache edge direction result-kind result]
  (when-let [put-continuation! (:put! continuation-cache)]
    (try
      (put-continuation!
       edge
       {:engine-version recursive-engine-version
        :implementation :generated-indexed
        :direction direction
        :result-kind result-kind
        :bound edge
        :state (:state result)
        :counters (:counters result)
        :retained-logical-units (:retained-logical-units result)}
       (generated-continuation-weight
        (:retained-logical-units result)))
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

(defn- generated-page-stream-chunk-size
  "Uses one generated scan round trip for the common small Relay window.

  The generated renderer needs `page-size + 1` results to decide has-next.
  Choosing exactly that size avoids a second FFI drive/resume boundary without
  increasing adapter lookahead beyond the one value already required by the
  scan contract. Larger windows retain the bounded 64-value batch."
  [page-size]
  (min 64 (inc page-size)))

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

(def ^:private generated-drive-fuel
  "Maximum internal generated transitions per host call.

  Generated state stays opaque, so yielding does not copy retained state.
  Keeping the recursive generated call depth bounded also avoids trading a
  formal fuel proof for a JVM/JavaScript stack-overflow risk."
  256)

(defonce ^:private generated-request-scope
  ;; One scalar identifies every live generated traversal. Request ids remain
  ;; traversal-local, so the pair prevents a response from another traversal
  ;; (even one running the same projection) from being accepted.
  (atom -1))

(defn- successor-generated-request-scope
  [scope]
  (when (>= scope backend/maximum-exact-integer)
    (throw
     (ex-info
      "Generated traversal request-scope space is exhausted."
      {:type :eacl/request-scope-exhausted
       :eacl/error :eacl/request-scope-exhausted
       :maximum backend/maximum-exact-integer})))
  (inc scope))

(defn- next-generated-request-scope!
  []
  (swap! generated-request-scope successor-generated-request-scope))

(defn- generated-type
  [plan identity]
  (let [catalog (:verification-identity-catalog plan)]
    (if (contains? catalog identity)
      (get catalog identity)
      (recursive-plan-schema-error!
       "Generated traversal requested a type outside its certified plan."
       {:identity identity
        :known-identities (vec (sort (keys catalog)))}))))

(defn- generated-command-scan
  [plan {:keys [kind subject-type subject-eid relation-eid
                resource-type resource-eid bound-eid]}]
  (case kind
    :subject->resources
    {:scan-kind :subject-resources
     :subject-type (generated-type plan subject-type)
     :subject-eid subject-eid
     :relation-eid relation-eid
     :resource-type (generated-type plan resource-type)
     :bound-eid bound-eid}

    :resource->subjects
    {:scan-kind :resource-subjects
     :resource-type (generated-type plan resource-type)
     :resource-eid resource-eid
     :relation-eid relation-eid
     :subject-type (generated-type plan subject-type)
     :bound-eid bound-eid}))

(defn- execute-generated-command
  [db plan {:keys [request-scope request-id projection chunk-size]}]
  (let [realized
        (vec
         (take
          (inc chunk-size)
          (scan-eids db (generated-command-scan plan projection))))
        more? (> (count realized) chunk-size)
        values
        (if more?
          (subvec realized 0 chunk-size)
          realized)]
    {:request-scope request-scope
     :request-id request-id
     :values values
     :terminal? (not more?)
     :fetched-values (count realized)}))

(def ^:private generated-limit-option
  {:derived-grants :max-derived-grants
   :advanced-datoms :max-advanced-datoms
   :queued-work :max-queued-work})

(defn- generated-traversal-error!
  [direction limits outcome]
  (case (:status outcome)
    :limit-exceeded
    (let [limit-kind (:limit-kind outcome)
          limit (get limits (get generated-limit-option limit-kind))]
      (recursive-traversal-error!
       "Generated recursive traversal safety limit exceeded."
       {:eacl/error :eacl.recursive-traversal/limit-exceeded
        :limit-kind limit-kind
        :limit limit}))

    :render-rejected
    (stale-recursive-cursor!
     "Generated recursive traversal rejected the cursor boundary.")

    :scan-rejected
    (throw
     (ex-info
      "Recursive traversal backend returned an invalid indexed scan."
      {:type :eacl/backend-contract-violation
       :eacl/error :eacl/backend-contract-violation
       :operation :indexed-recursive-scan
       :direction direction
       :reason (:reason outcome)}))

    (recursive-traversal-error!
     "Generated recursive traversal returned an unknown outcome."
     {:eacl/error :eacl.recursive-traversal/invalid-generated-outcome
      :direction direction
      :outcome-status (:status outcome)})))

(def ^:private generated-cumulative-counter-keys
  (disj (set dimensional-counter-keys)
        :current-queue-depth
        :maximum-queue-depth))

(defn- generated-counter-sample
  [counters baseline]
  (reduce-kv
   (fn [sample counter-key value]
     (assoc
      sample
      counter-key
      (if (contains? generated-cumulative-counter-keys counter-key)
        (max 0 (- value (get baseline counter-key 0)))
        value)))
   {}
   counters))

(defn- record-generated-stats!
  [result baseline]
  (when *recursive-traversal-stats*
    (let [sample
          (generated-counter-sample
           (:counters result)
           (or baseline {}))]
      (swap!
       *recursive-traversal-stats*
       (fn [stats]
         (let [stats
               (update
                stats
                :generated-dimensional-counters
                merge-dimensional-counters
                sample)]
           (-> (reduce-kv
                (fn [current counter-key value]
                  (let [legacy-key
                        (get legacy-dimensional-counter-keys counter-key)]
                    (case counter-key
                      :current-queue-depth
                      (assoc current legacy-key value)

                      :maximum-queue-depth
                      (update current legacy-key (fnil max 0) value)

                      (update current legacy-key (fnil + 0) value))))
                stats
                sample)
               (assoc
                :generated-retained-logical-units
                (:retained-logical-units result)))))))))

(defn- restore-generated-continuation
  [selection direction continuation render]
  ;; Opaque state cannot be validated structurally without duplicating the
  ;; generated runtime's datatype contract. In particular, an in-process cache
  ;; may survive a development-time generated-class reload. Treat failure to
  ;; restore the cached optimization as a typed rejection so the caller can
  ;; evict it and replay the authenticated prefix. Once restoration succeeds,
  ;; later drive/resume failures still fail closed.
  (try
    (verified/continue-indexed-page
     selection
     direction
     (:state continuation)
     {:size (:size render)
      :bound (:bound render)})
    (catch #?(:clj Exception :cljs :default) _
      {:status :rejected
       :reason :unusable-cached-state})))

(defn- run-generated-traversal
  ([db plan direction initialization]
   (run-generated-traversal db plan direction initialization nil))
  ([db plan direction initialization continuation]
   (let [selection subproblem/*engine-selection*
         initialization
         (assoc initialization
                :request-scope
                (next-generated-request-scope!))
         limits (:limits initialization)
         started
         (if continuation
           (restore-generated-continuation
            selection direction continuation (:render initialization))
           (verified/initialize-indexed
            selection direction initialization))]
     (if (and continuation
              (= :rejected (:status started)))
       {:status :continuation-rejected
        :reason (:reason started)}
       (do
         (when-not (contains? #{:initialized :continued} (:status started))
           (generated-traversal-error! direction limits started))
         (loop [state (:state started)]
           (let [outcome
                 (verified/drive-indexed
                  selection direction state limits generated-drive-fuel)]
             (case (:status outcome)
               :yielded
               (recur (:state outcome))

               :need-scan
               (let [response
                     (execute-generated-command db plan (:command outcome))
                     resumed
                     (verified/resume-indexed
                      selection direction (:state outcome) response limits)]
                 (if (= :resumed (:status resumed))
                   (recur (:state resumed))
                   (generated-traversal-error! direction limits resumed)))

               :complete
               (let [final-state (:state outcome)
                     result
                     (verified/read-indexed-result
                      selection direction final-state)]
                 (record-generated-stats!
                  result
                  (:counters continuation))
                 (assoc result :state final-state))

               (generated-traversal-error!
                direction limits outcome)))))))))

(defn- generated-render
  [direction size bound]
  (let [portable-bound
        (when bound
          {:ordinal (:ordinal bound)
           :eid (get-in bound [:result :eid])})]
    (case direction
      :asc
      {:kind :page
       :size size
       :bound portable-bound}

      :desc
      {:kind :backward-page
       :size size
       :bound portable-bound})))

(defn- generated-forward-result
  ([db plan subject-type subject-eid root-node result-type render]
   (generated-forward-result
    db plan subject-type subject-eid root-node result-type render nil))
  ([db plan subject-type subject-eid root-node result-type render continuation]
   (run-generated-traversal
    db
    plan
    :forward
    {:compiled-plan (:verification-compiled-plan plan)
     :subject-type (verification-identity subject-type)
     :subject-eid subject-eid
     :root-node (verification-permission-node root-node)
     :result-type (verification-identity result-type)
     :render render
     :chunk-size *stream-chunk-size*
     :limits
     {:max-derived-grants
      (:max-derived-grants *recursive-traversal-limits*)
      :max-advanced-datoms
      (:max-advanced-datoms *recursive-traversal-limits*)
      :max-queued-work
      (:max-queued-work *recursive-traversal-limits*)}}
    continuation)))

(defn- generated-reverse-result
  ([db plan subject-type root-node root-resource-eid result-type render]
   (generated-reverse-result
    db plan subject-type root-node root-resource-eid result-type render nil))
  ([db plan subject-type root-node root-resource-eid result-type render
    continuation]
   (run-generated-traversal
    db
    plan
    :reverse
    {:compiled-plan (:verification-compiled-plan plan)
     :subject-type (verification-identity subject-type)
     :root-node (verification-permission-node root-node)
     :root-resource-eid root-resource-eid
     :result-type (verification-identity result-type)
     :render render
     :chunk-size *stream-chunk-size*
     :limits
     {:max-derived-grants
      (:max-derived-grants *recursive-traversal-limits*)
      :max-advanced-datoms
      (:max-advanced-datoms *recursive-traversal-limits*)
      :max-queued-work
      (:max-queued-work *recursive-traversal-limits*)}}
    continuation)))

(defn- generated-page-response
  [direction result-kind result-type result]
  (let [start (:start-ordinal result)
        items
        (mapv
         (fn [offset eid]
           {:node (spice-object result-type eid)
            :cursor
            (recursive-edge
             direction
             result-kind
             (+ start offset)
             result-type
             eid)})
         (range)
         (:items result))]
    (page-response
     {:items items
      :has-next? (:has-next? result)
      :has-previous? (:has-previous? result)})))

(defn- stream-work
  [scan continuation]
  {:kind :stream
   :scan scan
   :eids []
   :more? true
   :continuation continuation})

(defn- enqueue-stream
  [state scan continuation]
  (enqueue-work state (stream-work scan continuation)))

(defn- continue-stream
  "Interprets one data-valued stream continuation.

  Continuations deliberately contain no host functions. A continuation can
  therefore cross a cache/persistence boundary, be weighed structurally, and
  be consumed by a generated state machine without relying on Clojure closure
  identity or captured heap state."
  [continuation eid]
  (case (:op continuation)
    :forward-grant
    [{:kind :grant
      :node (:node continuation)
      :resource-eid eid}]

    :forward-arrow-relation
    [(stream-work
      (subject-resource-scan
       (:intermediate-type continuation)
       eid
       (:via-relation-eid continuation)
       (:resource-type continuation))
      {:op :forward-grant
       :node (:node continuation)})]

    :reverse-grant
    [{:kind :grant
      :node (:node continuation)
      :resource-eid (:resource-eid continuation)
      :subject-type (:subject-type continuation)
      :subject-eid eid}]

    :reverse-arrow-relation
    [(stream-work
      (resource-subject-scan
       (:intermediate-type continuation)
       eid
       (:target-relation-eid continuation)
       (:subject-type continuation))
      {:op :reverse-grant
       :node (:node continuation)
       :resource-eid (:resource-eid continuation)
       :subject-type (:subject-type continuation)})]

    :reverse-arrow-permission
    [{:kind :register-consumer
      :consumer-key [(:target-node continuation) eid]
      :consumer {:op :reverse-propagate-grant
                 :node (:node continuation)
                 :resource-eid (:resource-eid continuation)}}
     {:kind :goal
      :node (:target-node continuation)
      :resource-eid eid}]

    (throw
     (ex-info
      "Unknown recursive stream continuation."
      {:eacl/error :eacl.recursive-traversal/invalid-continuation
       :continuation continuation}))))

(defn- scan-projection-command
  [{:keys [scan-kind subject-type subject-eid relation-eid
           resource-type resource-eid bound-eid]}]
  (case scan-kind
    :subject-resources
    {:kind :subject->resources
     :subject-type (str subject-type)
     :subject-eid subject-eid
     :relation-eid relation-eid
     :resource-type (str resource-type)
     :bound-eid bound-eid}

    :resource-subjects
    {:kind :resource->subjects
     :resource-type (str resource-type)
     :resource-eid resource-eid
     :relation-eid relation-eid
     :subject-type (str subject-type)
     :bound-eid bound-eid}))

(defn- legacy-scan-response-decision
  [{:keys [command response]}]
  (let [values (:values response)
        bound (get-in command [:projection :bound-eid])
        strict? (every? true? (map < values (rest values)))
        valid?
        (and (= (:request-scope command) (:request-scope response))
             (= (:request-id command) (:request-id response))
             (<= (count values) (:chunk-size command))
             (if (:terminal? response)
               (= (:fetched-values response) (count values))
               (and (= (count values) (:chunk-size command))
                    (= (:fetched-values response)
                       (inc (count values)))))
             (or (:terminal? response) (seq values))
             (every? #(and (integer? %) (not (neg? %))) values)
             strict?
             (or (nil? bound) (every? #(< bound %) values)))]
    (if valid?
      {:status :accepted
       :values values
       :terminal? (:terminal? response)
       :fetched-values (:fetched-values response)}
      {:status :rejected
       :reason :backend-contract-violation})))

(defn- validate-indexed-scan-response!
  [input]
  (let [selection subproblem/*engine-selection*
        legacy #(legacy-scan-response-decision input)
        decision
        (if (or (nil? selection)
                (= :legacy-authoritative selection)
                (and (map? selection)
                     (= :legacy-authoritative (:mode selection))))
          (legacy)
          (verified/decide
           selection :indexed-scan-response input legacy))]
    (when-not (= :accepted (:status decision))
      (throw
       (ex-info
        "Recursive traversal backend returned an invalid indexed scan."
        {:type :eacl/backend-contract-violation
         :eacl/error :eacl/backend-contract-violation
         :operation :indexed-recursive-scan
         :reason (:reason decision)})))
    decision))

(defn- fill-stream
  [db request-id {:keys [scan] :as work}]
  (let [realized (vec (take (inc *stream-chunk-size*)
                            (scan-eids db scan)))
        _ (inc-stat! :stream-fills)
        _ (add-stat! :fetched-stream-datoms (count realized))
        more? (> (count realized) *stream-chunk-size*)
        eids (if more?
               (subvec realized 0 *stream-chunk-size*)
               realized)
        validated
        (if (verified-engine-selection?)
          (validate-indexed-scan-response!
           {:command
            {:request-scope 0
             :request-id request-id
             :projection (scan-projection-command scan)
             :chunk-size *stream-chunk-size*}
            :response
            {:request-scope 0
             :request-id request-id
             :values eids
             :terminal? (not more?)
             :fetched-values (count realized)}})
          {:values eids
           :terminal? (not more?)})]
    (assoc work
           :eids (:values validated)
           :more? (not (:terminal? validated))
           :fetched-values (count realized)
           :scan (cond-> scan
                   (seq eids) (assoc :bound-eid (peek eids))))))

(defn- advance-stream
  [db state work]
  (let [refill? (empty? (:eids work))
        request-id (long (or (:next-request-id state) 0))
        state-with-request
        (if (and refill? (track-dimensional-counters?))
          (update state :next-request-id (fnil inc 0))
          state)
        {:keys [eids continuation more?] :as work'}
        (if refill?
          (fill-stream db request-id work)
          work)
        fetched-values (long (or (:fetched-values work') 0))
        work' (dissoc work' :fetched-values)
        state-with-request
        (if refill?
          (-> state-with-request
              (update-in
               [:counters :backend-commands]
               (fnil inc 0))
              (update-in
               [:counters :adapter-fetched-values]
               (fnil + 0)
               fetched-values))
          state-with-request)]
    (if-let [eid (first eids)]
      (let [remaining (if (= 1 (count eids))
                        []
                        (subvec eids 1))
            state' (-> state-with-request
                       (increment-counter :advanced-datoms :max-advanced-datoms)
                       (cond->
                        (track-dimensional-counters?)
                         (update-in
                          [:counters :engine-consumed-values]
                          (fnil inc 0)))
                       (enqueue-works (continue-stream continuation eid)))]
        (inc-stat! :advanced-stream-datoms)
        (if (or (seq remaining) more?)
          (enqueue-work state' (assoc work' :eids remaining))
          state'))
      state-with-request)))

(defn- forward-seed-state
  [db subject-type subject-eid
   {:keys [forward-consumers
           forward-seeds-by-subject-type]}]
  (let [seed-rules
        (get forward-seeds-by-subject-type subject-type [])
        initial
        (account-dimensional-work
         {:queue empty-queue
          :seen-grants #{}
          :emitted-root #{}
          :ordinal 0
          :next-request-id 0
          :counters {}
          :consumers forward-consumers
          :consumer-count
          (reduce + 0 (map count (vals forward-consumers)))}
         :rule-applications
         (count seed-rules))]
    (reduce
     (fn [state rule]
       (case (:rule rule)
         :relation
         (enqueue-stream state
                         (subject-resource-scan
                          subject-type subject-eid
                          (:relation-eid rule)
                          (:resource-type rule))
                         {:op :forward-grant
                          :node (:node rule)})

         :arrow-relation
         (enqueue-stream state
                         (subject-resource-scan
                          subject-type subject-eid
                          (:target-relation-eid rule)
                          (:intermediate-type rule))
                         {:op :forward-arrow-relation
                          :node (:node rule)
                          :intermediate-type (:intermediate-type rule)
                          :via-relation-eid (:via-relation-eid rule)
                          :resource-type (:resource-type rule)})

         state))
     initial
     seed-rules)))

(defn- forward-consumer-work
  [grant rule]
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
      {:op :forward-grant
       :node (:node rule)})]

    []))

(defn- process-forward-grant
  [db root-node result-type state {:keys [node resource-eid] :as grant}]
  (let [grant-key [node resource-eid]]
    (if (contains? (:seen-grants state) grant-key)
      (do
        (inc-stat! :deduped-grants)
        [state nil])
      (let [consumer-rules (get-in state [:consumers node] [])
            state' (-> state
                       (increment-counter :derived-grants :max-derived-grants)
                       (cond->
                        (track-dimensional-counters?)
                         (update-in
                          [:counters :unique-grants]
                          (fnil inc 0)))
                       (update :seen-grants conj grant-key)
                       (account-dimensional-work
                        :rule-applications
                        (count consumer-rules))
                       (account-dimensional-work
                        :consumer-grant-joins
                        (count consumer-rules)))
            state'' (enqueue-works state'
                                   (mapcat #(forward-consumer-work grant %)
                                           consumer-rules))]
        (inc-stat! :derived-grants)
        (if (and (= root-node node)
                 (not (contains? (:emitted-root state'') resource-eid)))
          (let [ordinal (:ordinal state'')
                item {:node (spice-object result-type resource-eid)
                      :cursor (recursive-edge :forward :resource ordinal result-type resource-eid)}]
            (inc-stat! :emitted-results)
            [(-> state''
                 (update :emitted-root conj resource-eid)
                 (update :ordinal inc)
                 (account-dimensional-work :render-advances 1)
                 (cond->
                  (track-dimensional-counters?)
                   (update-in
                    [:counters :emitted-results]
                    (fnil inc 0))))
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
      (do
        (record-retained-logical-units! state (count items))
        {:items items
         :complete? false
         :page-end-state page-end-state})
      (let [[state' item] (next-forward-item db root-node result-type state)]
        (cond
          (nil? item)
          (if (= mode :seek)
            (stale-recursive-cursor!
             "Recursive traversal cursor no longer exists.")
            (do
              (record-retained-logical-units!
               state' (count items))
              {:items items
               :complete? true
               :page-end-state page-end-state}))

          (= mode :seek)
          (let [ordinal (get-in item [:cursor :ordinal])]
            (cond
              (< ordinal (:ordinal bound))
              (recur state' :seek items page-end-state)

              (= ordinal (:ordinal bound))
              (if (same-recursive-bound-result? bound item)
                (recur state' :collect items page-end-state)
                (stale-recursive-cursor!
                 "Recursive traversal cursor points at a different result."))

              :else
              (stale-recursive-cursor!
               "Recursive traversal cursor was skipped.")))

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
        (stale-recursive-cursor!
         "Recursive traversal cursor no longer exists.")

        (= (:ordinal bound) (get-in item [:cursor :ordinal]))
        (if (same-recursive-bound-result? bound item)
          (let [items (vec ring)
                has-sentinel? (> (count items) size)
                page-items (if has-sentinel?
                             (subvec items (- (count items) size))
                             items)]
            (record-retained-logical-units!
             state' (count ring))
            {:items page-items
             :has-sentinel? has-sentinel?})
          (stale-recursive-cursor!
           "Recursive traversal cursor points at a different result."))

        (> (get-in item [:cursor :ordinal]) (:ordinal bound))
        (stale-recursive-cursor!
         "Recursive traversal cursor was skipped.")

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
        (do
          (record-retained-logical-units! state' 0)
          {:count n :truncated? false})

        (and limit (>= n limit))
        (do
          (record-retained-logical-units! state' 0)
          {:count n :truncated? true})

        :else
        (recur state' (unchecked-inc n))))))

(def ^:private recursive-denotation-version 2)

(defn- valid-recursive-denotation?
  [value]
  (and (vector? value)
       (= (count value) (count (distinct value)))
       (every? #(and (integer? %) (pos? %)) value)))

(defn- recursive-denotation-weight
  [value]
  (+ 256 (* 24 (count value))))

(defn- permission-denotation-identity
  "Certified indexed identity of one permission root's incoming rule bodies.

  The root permission name is intentionally absent. Two roots on the same
  resource type share only when the exact portable indexed bodies supplied to
  generated plan certification are structurally equal. Relation EIDs, subject
  and intermediate types, target relation EIDs, and target permission nodes
  therefore remain in the identity. The identity is compiled once per root
  and schema generation rather than rebuilt on the request path."
  [db root-node]
  (:root-denotation-identity (recursive-plan db root-node)))

(defn- recursive-denotation-key
  [db direction root-node anchor-type anchor-eid result-type]
  [denotation-key-version
   :permission-fixed-point
   recursive-denotation-version
   direction
   (permission-denotation-identity db root-node)
   anchor-type
   anchor-eid
   result-type
   *recursive-traversal-limits*])

(defn- recursive-denotation-dependencies
  [db root-node]
  (permission-relationship-eids db (first root-node) (second root-node)))

(defn- record-permission-denotation-hit!
  [db root-node]
  (if (traversal-permission? db (first root-node) (second root-node))
    (subproblem/record-recursive-component-hit!)
    (subproblem/record-acyclic-denotation-hit!)))

(defn- complete-forward-denotation
  [db root-node result-type state]
  (loop [state state
         values []]
    (let [[state' item]
          (next-forward-item db root-node result-type state)]
      (if item
        (recur state' (conj values (get-in item [:node :id])))
        (do
          (record-retained-logical-units!
           state' (count values))
          values)))))

(defn- complete-generated-forward-denotation
  [db subject-type subject-eid root-node result-type]
  (let [result
        (generated-forward-result
         db
         (recursive-plan db root-node)
         subject-type
         subject-eid
         root-node
         result-type
         {:kind :page
          :size (:max-derived-grants *recursive-traversal-limits*)
          :bound nil})]
    (when (:has-next? result)
      (recursive-traversal-error!
       "Generated fixed-point denotation exceeded its verified render bound."
       {:eacl/error :eacl.recursive-traversal/invalid-generated-outcome
        :direction :forward
        :outcome-status :incomplete-denotation}))
    (:items result)))

(defn- resolve-forward-denotation
  [db subject-type subject-eid root-node result-type]
  (let [key
        (recursive-denotation-key
         db :forward root-node subject-type subject-eid result-type)
        resolved
        (subproblem/resolve-layered-bound!
         :denotation
         key
         {:valid? valid-recursive-denotation?
          :weight-fn recursive-denotation-weight}
         (recursive-denotation-dependencies db root-node)
         #(if (generated-authoritative?)
            (complete-generated-forward-denotation
             db subject-type subject-eid root-node result-type)
            (complete-forward-denotation
             db root-node result-type
             (initial-forward-state
              db subject-type subject-eid root-node))))]
    (when (:cached? resolved)
      (record-permission-denotation-hit! db root-node))
    (:value resolved)))

(defn- lookup-forward-denotation
  [db subject-type subject-eid root-node result-type]
  (when-let [resolved
             (subproblem/lookup-layered-bound!
              :denotation
              (recursive-denotation-key
               db :forward root-node subject-type subject-eid result-type)
              {:valid? valid-recursive-denotation?
               :weight-fn recursive-denotation-weight}
              (recursive-denotation-dependencies db root-node))]
    (record-permission-denotation-hit! db root-node)
    (:value resolved)))

(defn- publish-forward-denotation!
  [db subject-type subject-eid root-node result-type values]
  (when subproblem/*store*
    (:value
     (subproblem/resolve-layered-bound!
      :denotation
      (recursive-denotation-key
       db :forward root-node subject-type subject-eid result-type)
      {:valid? valid-recursive-denotation?
       :weight-fn recursive-denotation-weight}
      (recursive-denotation-dependencies db root-node)
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
      (let [end (if bound (:ordinal bound) value-count)
            start (max 0 (- end size))]
        (page-response
         {:items
          (recursive-denotation-items
           direction result-kind result-type start
           (subvec values start end))
          :has-next? (boolean bound)
          :has-previous? (pos? start)})))))

(defn- rebase-recursive-bound
  [values bound]
  (if-not (:rebase? bound)
    {:bound bound
     :restarted? false}
    (let [bound-eid (get-in bound [:result :eid])
          decision
          (verified/decide-cursor-bound-rebase
           subproblem/*engine-selection*
           values
           bound-eid)]
      (case (:status decision)
        :rebased
        {:bound
         (-> bound
             (dissoc :rebase?)
             (assoc :ordinal (:ordinal decision)))
         :restarted? false}

        :restarted
        {:bound nil
         :restarted? true}))))

(defn- mark-recursive-restart
  [page restarted?]
  (cond-> page
    restarted?
    (assoc-in [:page-info :cursor-recovery] :restarted)))

(defn- restart-unroutable-rebase
  "Drops a recover-current cursor only when its permission root no longer
  exists. The authenticated query remains scoped to the same permission, so
  restarting can only return that permission's (now empty) first page."
  [defined-root? query]
  (let [bound (or (:after query) (:before query))
        restart?
        (and
         (not defined-root?)
         (true? (:rebase? bound)))]
    [(if restart?
       (dissoc query :after :before)
       query)
     restart?]))

(defn- recursive-forward-page
  [db query continuation-cache]
  (let [{:keys [direction size bound]} (normalize-page-request query)
        _ (validate-recursive-bound! bound :forward :resource)
        {:keys [subject permission]} query
        subject-type (:type subject)
        subject-eid (object-eid db (:id subject))
        result-type (:resource/type query)
        root-node (permission-query-node result-type permission)
        _ (when (and (= :desc direction)
                     (nil? bound)
                     (traversal-permission?
                      db result-type permission))
            (page-error! "Bare :last is not supported for recursive traversal pagination because it requires a full closure traversal."
                         {:eacl/error :eacl.pagination/unsupported-recursive-last
                          :reason :requires-full-traversal}))]
    (if (generated-authoritative?)
      (if-not subject-eid
        (page-response {:items []
                        :has-next? false
                        :has-previous? (boolean bound)})
        (let [cached-denotation
              (lookup-forward-denotation
               db subject-type subject-eid root-node result-type)
              cached-page
              (case direction
                :asc (cached-recursive-request-page
                      continuation-cache :forward :resource :asc bound size)
                :desc (cached-recursive-previous-page
                       continuation-cache :forward :resource bound size))]
          (or
           (when (:rebase? bound)
             (let [values
                   (or cached-denotation
                       (resolve-forward-denotation
                        db subject-type subject-eid root-node result-type))
                   {rebased-bound :bound
                    restarted? :restarted?}
                   (rebase-recursive-bound values bound)]
               (mark-recursive-restart
                (page-from-recursive-denotation
                 :forward
                 :resource
                 result-type
                 values
                 direction
                 rebased-bound
                 size)
                restarted?)))
           cached-page
           (when cached-denotation
             (page-from-recursive-denotation
              :forward :resource result-type cached-denotation
              direction bound size))
           (when (and (= :desc direction) (nil? bound))
             (page-from-recursive-denotation
              :forward
              :resource
              result-type
              (resolve-forward-denotation
               db subject-type subject-eid root-node result-type)
              direction
              nil
              size))
           (binding [*stream-chunk-size*
                     (generated-page-stream-chunk-size size)]
             (let [plan (recursive-plan db root-node)
                   continuation
                   (when (and bound (= :asc direction))
                     (note-continuation-miss!
                      bound
                      (cached-generated-continuation
                       continuation-cache bound :forward :resource)))
                   run-page
                   #(generated-forward-result
                     db plan subject-type subject-eid root-node result-type
                     (generated-render direction size bound)
                     %)
                   attempted (run-page continuation)
                   rejected?
                   (= :continuation-rejected (:status attempted))
                   _ (when rejected?
                       (evict-continuation! continuation-cache bound)
                       (inc-stat! :continuation-misses))
                   result (if rejected? (run-page nil) attempted)
                   page
                   (generated-page-response
                    :forward :resource result-type result)
                   _ (when (and (nil? bound)
                                (not (:has-next? result)))
                       (publish-forward-denotation!
                        db subject-type subject-eid root-node result-type
                        (:items result)))
                   continuation-stored?
                   (when (and (= :asc direction)
                              (:has-next? result))
                     (store-generated-continuation!
                      continuation-cache
                      (get-in page [:page-info :end-cursor])
                      :forward :resource result))
                   page-stored?
                   (store-recursive-page!
                    continuation-cache
                    :forward :resource direction bound size page)]
               (when (and continuation
                          (not rejected?)
                          page-stored?
                          (or (not (:has-next? result))
                              continuation-stored?))
                 (evict-continuation! continuation-cache bound))
               page)))))
      (let [cached-denotation
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
         (when (and subject-eid (:rebase? bound))
           (let [values
                 (or cached-denotation
                     (resolve-forward-denotation
                      db subject-type subject-eid root-node result-type))
                 {rebased-bound :bound
                  restarted? :restarted?}
                 (rebase-recursive-bound values bound)]
             (mark-recursive-restart
              (page-from-recursive-denotation
               :forward
               :resource
               result-type
               values
               direction
               rebased-bound
               size)
              restarted?)))
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
                   page))))))))))

(defn- reverse-consumer-key
  [node resource-eid]
  [node resource-eid])

(defn- reverse-consumer-work
  [consumer grant]
  (case (:op consumer)
    :reverse-propagate-grant
    [{:kind :grant
      :node (:node consumer)
      :resource-eid (:resource-eid consumer)
      :subject-type (:subject-type grant)
      :subject-eid (:subject-eid grant)}]

    (throw
     (ex-info
      "Unknown recursive reverse consumer."
      {:eacl/error :eacl.recursive-traversal/invalid-consumer
       :consumer consumer}))))

(defn- add-reverse-consumer
  [state key consumer]
  (let [registration [key consumer]]
    (if (contains? (:seen-consumers state) registration)
      (do
        (inc-stat! :deduped-consumers)
        state)
      (let [state' (-> state
                       (update :seen-consumers conj registration)
                       (update-in
                        [:consumers key]
                        (fnil conj [])
                        consumer)
                       (update :consumer-count (fnil inc 0)))
            existing (get-in state' [:grants-by-goal key])
            accounted-state (account-dimensional-work
                             state'
                             :consumer-grant-joins
                             (count existing))]
        (inc-stat! :registered-consumers)
        (enqueue-works
         accounted-state
         (mapcat #(reverse-consumer-work consumer %) existing))))))

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
                      {:op :reverse-grant
                       :node (:node rule)
                       :resource-eid resource-eid
                       :subject-type (:subject-type state)})
      state)

    :self-permission
    (let [target-key (reverse-consumer-key (:target-node rule) resource-eid)
          consumer {:op :reverse-propagate-grant
                    :node (:node rule)
                    :resource-eid resource-eid}]
      (enqueue-works
       state
       [{:kind :register-consumer
         :consumer-key target-key
         :consumer consumer}
        {:kind :goal
         :node (:target-node rule)
         :resource-eid resource-eid}]))

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
                      {:op :reverse-arrow-relation
                       :node (:node rule)
                       :resource-eid resource-eid
                       :subject-type (:subject-type state)
                       :intermediate-type (:intermediate-type rule)
                       :target-relation-eid (:target-relation-eid rule)})
      state)

    :arrow-permission
    (enqueue-stream state
                    (resource-subject-scan
                     (:resource-type rule)
                     resource-eid
                     (:via-relation-eid rule)
                     (:intermediate-type rule))
                    {:op :reverse-arrow-permission
                     :node (:node rule)
                     :resource-eid resource-eid
                     :target-node (:target-node rule)})))

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
      (let [rules (get-in state [:rules-by-node node] [])]
        (reduce (fn [acc rule]
                  (reverse-goal-rule-work db acc resource-eid rule))
                (-> state
                    (update :seen-goals conj goal-key)
                    (account-dimensional-work
                     :rule-applications
                     (count rules)))
                rules)))))

(defn- process-reverse-grant
  [root-node root-resource-eid result-type state {:keys [node resource-eid subject-type subject-eid] :as grant}]
  (let [grant-key [node resource-eid subject-type subject-eid]
        goal-key (reverse-consumer-key node resource-eid)]
    (if (contains? (:seen-grants state) grant-key)
      (do
        (inc-stat! :deduped-grants)
        [state nil])
      (let [consumers (get-in state [:consumers goal-key] [])
            state' (-> state
                       (increment-counter :derived-grants :max-derived-grants)
                       (cond->
                        (track-dimensional-counters?)
                         (update-in
                          [:counters :unique-grants]
                          (fnil inc 0)))
                       (update :seen-grants conj grant-key)
                       (update-in [:grants-by-goal goal-key] (fnil conj []) grant)
                       (account-dimensional-work
                        :consumer-grant-joins
                        (count consumers)))
            state'' (enqueue-works state'
                                   (mapcat #(reverse-consumer-work % grant)
                                           consumers))]
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
                 (update :ordinal inc)
                 (account-dimensional-work :render-advances 1)
                 (cond->
                  (track-dimensional-counters?)
                   (update-in
                    [:counters :emitted-results]
                    (fnil inc 0))))
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
      :seen-consumers #{}
      :emitted-subjects #{}
      :ordinal 0
      :next-request-id 0
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
        (do
          (record-retained-logical-units!
           state' (count values))
          values)))))

(defn- complete-generated-reverse-denotation
  [db resource-type resource-eid root-node subject-type]
  (let [result
        (generated-reverse-result
         db
         (recursive-plan db root-node)
         subject-type
         root-node
         resource-eid
         subject-type
         {:kind :page
          :size (:max-derived-grants *recursive-traversal-limits*)
          :bound nil})]
    (when (:has-next? result)
      (recursive-traversal-error!
       "Generated fixed-point denotation exceeded its verified render bound."
       {:eacl/error :eacl.recursive-traversal/invalid-generated-outcome
        :direction :reverse
        :outcome-status :incomplete-denotation
        :resource-type resource-type}))
    (:items result)))

(defn- resolve-reverse-denotation
  [db resource-type resource-eid root-node subject-type]
  (let [key
        (recursive-denotation-key
         db :reverse root-node resource-type resource-eid subject-type)
        resolved
        (subproblem/resolve-layered-bound!
         :denotation
         key
         {:valid? valid-recursive-denotation?
          :weight-fn recursive-denotation-weight}
         (recursive-denotation-dependencies db root-node)
         #(if (generated-authoritative?)
            (complete-generated-reverse-denotation
             db resource-type resource-eid root-node subject-type)
            (complete-reverse-denotation
             db root-node resource-eid subject-type
             (initial-reverse-state
              db subject-type root-node resource-eid))))]
    (when (:cached? resolved)
      (record-permission-denotation-hit! db root-node))
    (:value resolved)))

(defn- lookup-reverse-denotation
  [db resource-type resource-eid root-node subject-type]
  (when-let [resolved
             (subproblem/lookup-layered-bound!
              :denotation
              (recursive-denotation-key
               db :reverse root-node resource-type resource-eid subject-type)
              {:valid? valid-recursive-denotation?
               :weight-fn recursive-denotation-weight}
              (recursive-denotation-dependencies db root-node))]
    (record-permission-denotation-hit! db root-node)
    (:value resolved)))

(defn- publish-reverse-denotation!
  [db resource-type resource-eid root-node subject-type values]
  (when subproblem/*store*
    (:value
     (subproblem/resolve-layered-bound!
      :denotation
      (recursive-denotation-key
       db :reverse root-node resource-type resource-eid subject-type)
      {:valid? valid-recursive-denotation?
       :weight-fn recursive-denotation-weight}
      (recursive-denotation-dependencies db root-node)
      #(vec values)))))

(defn- collect-reverse-after
  [db root-node root-resource-eid result-type state bound size]
  (loop [state state
         mode (if bound :seek :collect)
         items []
         page-end-state nil]
    (if (>= (count items) (inc size))
      (do
        (record-retained-logical-units! state (count items))
        {:items items
         :complete? false
         :page-end-state page-end-state})
      (let [[state' item] (next-reverse-item db root-node root-resource-eid result-type state)]
        (cond
          (nil? item)
          (if (= mode :seek)
            (stale-recursive-cursor!
             "Recursive traversal cursor no longer exists.")
            (do
              (record-retained-logical-units!
               state' (count items))
              {:items items
               :complete? true
               :page-end-state page-end-state}))

          (= mode :seek)
          (let [ordinal (get-in item [:cursor :ordinal])]
            (cond
              (< ordinal (:ordinal bound))
              (recur state' :seek items page-end-state)

              (= ordinal (:ordinal bound))
              (if (same-recursive-bound-result? bound item)
                (recur state' :collect items page-end-state)
                (stale-recursive-cursor!
                 "Recursive traversal cursor points at a different result."))

              :else
              (stale-recursive-cursor!
               "Recursive traversal cursor was skipped.")))

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
        (stale-recursive-cursor!
         "Recursive traversal cursor no longer exists.")

        (= (:ordinal bound) (get-in item [:cursor :ordinal]))
        (if (same-recursive-bound-result? bound item)
          (let [items (vec ring)
                has-sentinel? (> (count items) size)
                page-items (if has-sentinel?
                             (subvec items (- (count items) size))
                             items)]
            (record-retained-logical-units!
             state' (count ring))
            {:items page-items
             :has-sentinel? has-sentinel?})
          (stale-recursive-cursor!
           "Recursive traversal cursor points at a different result."))

        (> (get-in item [:cursor :ordinal]) (:ordinal bound))
        (stale-recursive-cursor!
         "Recursive traversal cursor was skipped.")

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
        {:keys [resource permission]} query
        resource-type (:type resource)
        resource-eid (object-eid db (:id resource))
        subject-type (:subject/type query)
        root-node (permission-query-node resource-type permission)
        _ (when (and (= :desc direction)
                     (nil? bound)
                     (traversal-permission?
                      db resource-type permission))
            (page-error! "Bare :last is not supported for recursive traversal pagination because it requires a full closure traversal."
                         {:eacl/error :eacl.pagination/unsupported-recursive-last
                          :reason :requires-full-traversal}))]
    (if (generated-authoritative?)
      (if-not resource-eid
        (page-response {:items []
                        :has-next? false
                        :has-previous? (boolean bound)})
        (let [cached-denotation
              (lookup-reverse-denotation
               db resource-type resource-eid root-node subject-type)
              cached-page
              (case direction
                :asc (cached-recursive-request-page
                      continuation-cache :reverse :subject :asc bound size)
                :desc (cached-recursive-previous-page
                       continuation-cache :reverse :subject bound size))]
          (or
           (when (:rebase? bound)
             (let [values
                   (or cached-denotation
                       (resolve-reverse-denotation
                        db
                        resource-type
                        resource-eid
                        root-node
                        subject-type))
                   {rebased-bound :bound
                    restarted? :restarted?}
                   (rebase-recursive-bound values bound)]
               (mark-recursive-restart
                (page-from-recursive-denotation
                 :reverse
                 :subject
                 subject-type
                 values
                 direction
                 rebased-bound
                 size)
                restarted?)))
           cached-page
           (when cached-denotation
             (page-from-recursive-denotation
              :reverse :subject subject-type cached-denotation
              direction bound size))
           (when (and (= :desc direction) (nil? bound))
             (page-from-recursive-denotation
              :reverse
              :subject
              subject-type
              (resolve-reverse-denotation
               db
               resource-type
               resource-eid
               root-node
               subject-type)
              direction
              nil
              size))
           (binding [*stream-chunk-size*
                     (generated-page-stream-chunk-size size)]
             (let [plan (recursive-plan db root-node)
                   continuation
                   (when (and bound (= :asc direction))
                     (note-continuation-miss!
                      bound
                      (cached-generated-continuation
                       continuation-cache bound :reverse :subject)))
                   run-page
                   #(generated-reverse-result
                     db plan subject-type root-node resource-eid subject-type
                     (generated-render direction size bound)
                     %)
                   attempted (run-page continuation)
                   rejected?
                   (= :continuation-rejected (:status attempted))
                   _ (when rejected?
                       (evict-continuation! continuation-cache bound)
                       (inc-stat! :continuation-misses))
                   result (if rejected? (run-page nil) attempted)
                   page
                   (generated-page-response
                    :reverse :subject subject-type result)
                   _ (when (and (nil? bound)
                                (not (:has-next? result)))
                       (publish-reverse-denotation!
                        db resource-type resource-eid root-node subject-type
                        (:items result)))
                   continuation-stored?
                   (when (and (= :asc direction)
                              (:has-next? result))
                     (store-generated-continuation!
                      continuation-cache
                      (get-in page [:page-info :end-cursor])
                      :reverse :subject result))
                   page-stored?
                   (store-recursive-page!
                    continuation-cache
                    :reverse :subject direction bound size page)]
               (when (and continuation
                          (not rejected?)
                          page-stored?
                          (or (not (:has-next? result))
                              continuation-stored?))
                 (evict-continuation! continuation-cache bound))
               page)))))
      (let [cached-denotation
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
         (when (and resource-eid (:rebase? bound))
           (let [values
                 (or cached-denotation
                     (resolve-reverse-denotation
                      db
                      resource-type
                      resource-eid
                      root-node
                      subject-type))
                 {rebased-bound :bound
                  restarted? :restarted?}
                 (rebase-recursive-bound values bound)]
             (mark-recursive-restart
              (page-from-recursive-denotation
               :reverse
               :subject
               subject-type
               values
               direction
               rebased-bound
               size)
              restarted?)))
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
                   page))))))))))

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
        (permission-relationship-eids
         db resource-type permission-name)
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
       (permission-relationship-eids
        db resource-type permission-name)
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
                (cond
                  (nil? intermediates)
                  ;; No resource-side candidate can satisfy the arrow. Avoid
                  ;; far-side schema work and subject-side index scans.
                  false

                  (nil? (next intermediates))
                  ;; Exactly one intermediate — by far the most common arrow
                  ;; shape (`server->account->admin`). A single point probe
                  ;; beats opening a second index scan to intersect against.
                  ;; `next` realizes at most two elements, which the
                  ;; intersection below needs anyway, so the wide case pays
                  ;; nothing for this check.
                  (probe-each intermediates)

                  :else
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

(defn- recursive-can?
  "Evaluates one recursive point query with the monotone forward worklist.

  A cache-enabled request exhausts and publishes the complete fixed point so
  distinct point, page, and count consumers can reuse it. The cache-free
  oracle may stop once the target grant is derived because positive grants
  cannot be retracted by later least-fixed-point iterations."
  [db subject-type subject-eid root-node resource-type resource-eid]
  (if subproblem/*store*
    (boolean
     (some
      #{resource-eid}
      (resolve-forward-denotation
       db subject-type subject-eid root-node resource-type)))
    (if (generated-authoritative?)
      (:allowed?
       (generated-forward-result
        db
        (recursive-plan db root-node)
        subject-type
        subject-eid
        root-node
        resource-type
        {:kind :boolean :target-eid resource-eid}))
      (loop [state
             (initial-forward-state
              db subject-type subject-eid root-node)]
        (let [[state' item]
              (next-forward-item db root-node resource-type state)]
          (cond
            (nil? item)
            (do
              (record-retained-logical-units! state' 0)
              false)

            (= resource-eid (get-in item [:node :id]))
            (do
              (record-retained-logical-units! state' 1)
              true)

            :else (recur state')))))))

(defn- can*
  [db subject-type subject-eid permission resource-type resource-eid
   visited-states]
  (let [state
        [subject-type subject-eid permission resource-type resource-eid]]
    (cond
      (contains? visited-states state)
      false

      (indexed-authoritative-root? db resource-type permission)
      (recursive-can?
       db subject-type subject-eid
       (permission-query-node resource-type permission)
       resource-type resource-eid)

      (nil? subproblem/*store*)
      (can-uncached*
       db subject-type subject-eid permission resource-type resource-eid
       visited-states)

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
        resource-eid  (object-eid db (:id resource))
        defined-root?
        (and subject-eid
             resource-eid
             (permission-root-defined?
              db resource-type permission))
        shadow?
        (and defined-root? (generated-shadow?))
        recursive?
        (and shadow?
             (traversal-permission? db resource-type permission))
        [result legacy-stats legacy-error]
        (evaluate-with-shadow-stats
         #(if (or (nil? subject-eid) (nil? resource-eid))
            false
            (can*
             db subject-type subject-eid permission
             resource-type resource-eid #{})))]
    (cond
      legacy-error
      (compare-generated-shadow-error!
       :indexed-forward-boolean
       legacy-error
       #(can? db subject permission resource))

      shadow?
      (compare-generated-shadow!
       :indexed-forward-boolean
       result
       legacy-stats
       recursive?
       #(can? db subject permission resource))

      :else
      result)))

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

(defn- lookup-bound-authorized?
  "Whether a recover-current lookup boundary is still in the current result
  denotation. The cursor has already been authenticated and scoped by the
  adapter. This point check lets the streaming lookup refine the same stable
  result-identity rule as the generated complete-denotation rebase without
  materializing the whole result set."
  [db direction query result-eid]
  (let [permission (:permission query)]
    (case (:anchor-key direction)
      :subject
      (let [subject (:subject query)
            subject-eid (object-eid db (:id subject))]
        (and
         subject-eid
         (can*
          db
          (:type subject)
          subject-eid
          permission
          (:resource/type query)
          result-eid
          #{})))

      :resource
      (let [resource (:resource query)
            resource-eid (object-eid db (:id resource))]
        (and
         resource-eid
         (can*
          db
          (:subject/type query)
          result-eid
          permission
          (:type resource)
          resource-eid
          #{}))))))

(defn- rebase-lookup-query
  "Rebinds a recover-current lookup cursor by stable result identity.

  A surviving identity keeps its EID boundary but discards path frontiers
  minted under the old dependency proof. A missing identity restarts at the
  current first page. The returned boolean records only the latter case."
  [db direction query]
  (let [bound-field (cond
                      (contains? query :after) :after
                      (contains? query :before) :before)
        bound (get query bound-field)]
    (if-not (true? (:rebase? bound))
      [query false]
      (if (lookup-bound-authorized?
           db direction query (:result-eid bound))
        [(assoc query
                bound-field
                (select-keys bound [:kind :result-eid]))
         false]
        [(dissoc query :after :before) true]))))

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
   (let [[query restarted?] (rebase-lookup-query db direction query)
         {:keys [result-type-fn]} direction
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
         page (mark-recursive-restart
               (page-response {:items items
                               :has-next? (case (:direction page-req)
                                            :asc has-sentinel?
                                            :desc (boolean bound))
                               :has-previous? (case (:direction page-req)
                                                :asc (boolean bound)
                                                :desc has-sentinel?)})
               restarted?)]
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
                   (when continuation-cache-fn (continuation-cache-fn)))
         defined-root?
         (permission-root-defined?
          db (:resource/type query) (:permission query))
         indexed?
         (indexed-authoritative-root?
          db (:resource/type query) (:permission query))
         [query restart?]
         (restart-unroutable-rebase defined-root? query)
         [result legacy-stats legacy-error]
         (evaluate-with-shadow-stats
          #(if indexed?
             (recursive-forward-page db query cache)
             (lookup db forward-direction query cache)))
         result (mark-recursive-restart result restart?)]
     (cond
       legacy-error
       (compare-generated-shadow-error!
        :indexed-forward-page
        legacy-error
        #(lookup-resources
          db query
          {:continuation-cache cache}))

       (and defined-root? (generated-shadow?))
       (compare-generated-shadow!
        :indexed-forward-page
        result
        legacy-stats
        indexed?
        #(lookup-resources
          db query
          {:continuation-cache cache}))

       :else
       result))))

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
                   (when continuation-cache-fn (continuation-cache-fn)))
         defined-root?
         (permission-root-defined?
          db (:type (:resource query)) (:permission query))
         indexed?
         (indexed-authoritative-root?
          db (:type (:resource query)) (:permission query))
         [query restart?]
         (restart-unroutable-rebase defined-root? query)
         [result legacy-stats legacy-error]
         (evaluate-with-shadow-stats
          #(if indexed?
             (recursive-reverse-page db query cache)
             (lookup db reverse-direction query cache)))
         result (mark-recursive-restart result restart?)]
     (cond
       legacy-error
       (compare-generated-shadow-error!
        :indexed-reverse-page
        legacy-error
        #(lookup-subjects
          db query
          {:continuation-cache cache}))

       (and defined-root? (generated-shadow?))
       (compare-generated-shadow!
        :indexed-reverse-page
        result
        legacy-stats
        indexed?
        #(lookup-subjects
          db query
          {:continuation-cache cache}))

       :else
       result))))

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
  (let [limit (query-count-limit query)
        defined-root?
        (permission-root-defined?
         db (:resource/type query) (:permission query))
        indexed?
        (indexed-authoritative-root?
         db (:resource/type query) (:permission query))
        [result legacy-stats legacy-error]
        (evaluate-with-shadow-stats
         (fn []
           (if indexed?
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
                    (cond
                      ;; Generated authority owns the count semantics even
                      ;; when subproblem caching is enabled. Its traversal may
                      ;; reuse/cache bounded projections, but an unbounded
                      ;; scalar count must not materialize a complete final
                      ;; denotation merely to populate the cache.
                      (generated-authoritative?)
                      (select-keys
                       (generated-forward-result
                        db
                        (recursive-plan db root-node)
                        (:type subject)
                        subject-eid
                        root-node
                        result-type
                        (if (some? limit)
                          {:kind :count :limit limit}
                          {:kind :all-count}))
                       [:count :truncated?])

                      (and subproblem/*store* (nil? limit))
                      (count-denotation
                       (resolve-forward-denotation
                        db (:type subject) subject-eid
                        root-node result-type)
                       nil)

                      (nil? limit)
                      (count-denotation
                       (resolve-forward-denotation
                        db (:type subject) subject-eid
                        root-node result-type)
                       nil)

                      :else
                      (count-traversal-items
                       #(next-forward-item db root-node result-type %)
                       (initial-forward-state
                        db (:type subject) subject-eid root-node)
                       limit))))
                limit))
             (count-response
              (count-acyclic-pages db forward-direction query limit)
              limit))))]
    (cond
      legacy-error
      (compare-generated-shadow-error!
       :indexed-forward-count
       legacy-error
       #(count-resources db query))

      (and defined-root? (generated-shadow?))
      (compare-generated-shadow!
       :indexed-forward-count
       result
       legacy-stats
       indexed?
       #(count-resources db query))

      :else
      result)))

(defn count-subjects
  [db {:as query}]
  (reject-count-pagination-keys! "count-subjects" query)
  (when (:subject/relation query)
    (page-error! ":subject/relation is not supported by count-subjects."
                 {:eacl/error :eacl.pagination/unsupported-filter
                  :filter :subject/relation}))
  (let [limit (query-count-limit query)
        defined-root?
        (permission-root-defined?
         db (:type (:resource query)) (:permission query))
        indexed?
        (indexed-authoritative-root?
         db (:type (:resource query)) (:permission query))
        [result legacy-stats legacy-error]
        (evaluate-with-shadow-stats
         (fn []
           (if indexed?
             (let [{:keys [resource permission]} query
                   resource-eid (object-eid db (:id resource))
                   subject-type (:subject/type query)
                   root-node
                   (permission-query-node (:type resource) permission)]
               (count-response
                (if-not resource-eid
                  {:count 0 :truncated? false}
                  (if-let [cached
                           (lookup-reverse-denotation
                            db (:type resource) resource-eid
                            root-node subject-type)]
                    (count-denotation cached limit)
                    (cond
                      ;; As in the forward direction, retain only the
                      ;; generated traversal proof state and scalar count.
                      ;; Projection cache entries remain reusable; a complete
                      ;; final denotation is not required for correctness.
                      (generated-authoritative?)
                      (select-keys
                       (generated-reverse-result
                        db
                        (recursive-plan db root-node)
                        subject-type
                        root-node
                        resource-eid
                        subject-type
                        (if (some? limit)
                          {:kind :count :limit limit}
                          {:kind :all-count}))
                       [:count :truncated?])

                      (and subproblem/*store* (nil? limit))
                      (count-denotation
                       (resolve-reverse-denotation
                        db (:type resource) resource-eid
                        root-node subject-type)
                       nil)

                      (nil? limit)
                      (count-denotation
                       (resolve-reverse-denotation
                        db (:type resource) resource-eid
                        root-node subject-type)
                       nil)

                      :else
                      (count-traversal-items
                       #(next-reverse-item
                         db root-node resource-eid subject-type %)
                       (initial-reverse-state
                        db subject-type root-node resource-eid)
                       limit))))
                limit))
             (count-response
              (count-acyclic-pages db reverse-direction query limit)
              limit))))]
    (cond
      legacy-error
      (compare-generated-shadow-error!
       :indexed-reverse-count
       legacy-error
       #(count-subjects db query))

      (and defined-root? (generated-shadow?))
      (compare-generated-shadow!
       :indexed-reverse-count
       result
       legacy-stats
       indexed?
       #(count-subjects db query))

      :else
      result)))
