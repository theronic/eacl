(ns eacl.engine.v8
  (:require [eacl.backend.v8 :as backend]
            [eacl.core :refer [spice-object]]
            [eacl.engine.physical :as physical]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-page :as stable-page]
            [eacl.engine.stable-reducer :as stable-reducer]
            [eacl.engine.stable-route :as stable-route]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(def engine-version 8)

(def ^:dynamic *evaluation-mode*
  "Normalized public evaluation mode. Cache state never changes this value."
  :demand)

(def ^:dynamic *proof-frame*
  "The request-scoped ordered-generation frame, or nil for raw evaluation."
  nil)

(def ^:private default-page-size 1000)
(def ^:private max-page-size 10000)
(def ^:private projection-key-version 2)
(def ^:dynamic *backend-work-stats*
  "Optional atom populated by tests, benchmarks, and diagnostic callers.

  Counts backend operations actually invoked by the engine. Cache layers
  record avoided work separately; keeping executed and avoided counters
  distinct prevents a cache hit from being mistaken for database work."
  nil)

(def ^:dynamic *recursive-traversal-stats*
  "Optional atom populated by tests, benchmarks, and diagnostic callers.

  Receives the stable reducer's per-run work deltas under the public
  counter names (:derived-grants, :advanced-datoms, :queued-work) and
  :continuation-hits on checkpoint hits. Request-shape observers live in
  *request-shape-stats*. Observation-only."
  nil)
(def ^:dynamic *request-shape-stats*
  "Optional atom counting request-shape work that is not traversal work:
  :permission-path-calcs (cold path walks), :denotation-key-builds and
  :denotation-dependency-calcs (cache-key construction). Kept separate
  from *recursive-traversal-stats* deliberately — see that var's
  docstring. Observation-only."
  nil)

(defn- inc-shape-stat!
  [k]
  (when *request-shape-stats*
    (swap! *request-shape-stats* update k (fnil inc 0))))

(defn- record-backend-work!
  [operation]
  (when *backend-work-stats*
    (swap! *backend-work-stats*
           (fn [stats]
             (-> stats
                 (update :executed-backend-operations (fnil inc 0))
                 (update operation (fnil inc 0))))))
  nil)

(def ^:dynamic *schema-warning-reporter*
  "Optional (fn [message data]) for schema-resolution warnings. nil uses
  the deduplicated default reporter."
  nil)

(def ^:private warned-schema-conditions (atom #{}))

(defn- warn
  "Reports a schema-resolution condition at most ONCE per distinct
  [message data] per process (a schema with one dangling relation
  reference previously wrote unbuffered stderr on EVERY authorization
  check — audited hot-path defect). `pr-str` runs only when the
  condition is new or a custom reporter is bound."
  [message data]
  (if *schema-warning-reporter*
    (*schema-warning-reporter* message data)
    (let [condition [message data]]
      (when-not (contains? @warned-schema-conditions condition)
        (when (contains?
               (swap! warned-schema-conditions
                      (fn [seen]
                        (if (or (contains? seen condition)
                                (>= (count seen) 256))
                          seen
                          (conj seen condition))))
               condition)
          #?(:clj
             (binding [*out* *err*]
               (println message (pr-str data)))
             :cljs
             (.warn js/console message (pr-str data))))))))

(defn object-eid
  "Resolves an external object id through the snapshot adapter."
  [snapshot id]
  (when (some? id)
    (backend/invoke snapshot :object-id->internal id)))

(defn- page-error!
  [message data]
  (throw (ex-info message data)))

(defn- page-request-error!
  "A malformed page request: typed `:eacl.pagination/invalid-page-request`
  unless the caller supplies a more specific `:eacl/error` (a nil boundary
  keeps `:eacl.pagination/invalid-cursor`, an out-of-range size
  `:eacl.pagination/invalid-page-size`)."
  [message data]
  (let [category (or (:eacl/error data) :eacl.pagination/invalid-page-request)]
    (throw (ex-info message (assoc data :eacl/error category :type category)))))

(defn- host-normalize-page-request
  [query]
  (let [has-first? (contains? query :first)
        has-last? (contains? query :last)
        has-after? (contains? query :after)
        has-before? (contains? query :before)]
    (cond
      (contains? query :cursor)
      (page-request-error! ":cursor is not supported; use :first/:after or :last/:before."
                   {:key :cursor})

      (contains? query :limit)
      (page-request-error! ":limit is not supported for list pagination; use :first or :last."
                   {:key :limit})

      (and has-first? has-last?)
      (page-request-error! "Use exactly one of :first or :last." {:first (:first query)
                                                          :last (:last query)})

      (and has-before? has-after?)
      (page-request-error! "Use only one cursor boundary, :after or :before." {:after (:after query)
                                                                       :before (:before query)})

      (and has-after? (not has-first?))
      (page-request-error! ":after is valid only with :first." {:after (:after query)})

      (and has-before? (not has-last?))
      (page-request-error! ":before is valid only with :last." {:before (:before query)})

      ;; A present-but-nil boundary used to mean "start over", so a client
      ;; looping on a page-info that carried a nil cursor silently restarted at
      ;; page 1 forever. Absent means first page; nil means the caller lost
      ;; their cursor and must be told.
      (and has-after? (nil? (:after query)))
      (page-request-error! ":after was passed as nil. Omit it for the first page."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :key :after})

      (and has-before? (nil? (:before query)))
      (page-request-error! ":before was passed as nil. Omit it for the last page."
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
        (page-request-error! "Page size must be a positive integer."
                             {:eacl/error :eacl.pagination/invalid-page-size :size size}))
      (when (> size max-page-size)
        (page-request-error! "Page size exceeds configured maximum."
                             {:eacl/error :eacl.pagination/invalid-page-size
                              :size size
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
    :before (generated-page-presence query :before true)}
   :default-size default-page-size
   :maximum-size max-page-size})

(defn- generated-page-error!
  [query reason]
  (let [size (or (when (contains? query :first) (:first query))
                 (when (contains? query :last) (:last query))
                 default-page-size)]
    (case reason
      :both-directions
      (page-request-error!
       "Use exactly one of :first or :last."
       {:first (:first query) :last (:last query)})

      :both-bounds
      (page-request-error!
       "Use only one cursor boundary, :after or :before."
       {:after (:after query) :before (:before query)})

      :after-without-first
      (page-request-error! ":after is valid only with :first."
                   {:after (:after query)})

      :before-without-last
      (page-request-error! ":before is valid only with :last."
                   {:before (:before query)})

      :nil-after
      (page-request-error! ":after was passed as nil. Omit it for the first page."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :key :after})

      :nil-before
      (page-request-error! ":before was passed as nil. Omit it for the last page."
                   {:eacl/error :eacl.pagination/invalid-cursor
                    :key :before})

      :non-positive-size
      (page-request-error! "Page size must be a positive integer."
                           {:eacl/error :eacl.pagination/invalid-page-size :size size})

      :oversized-page
      (page-request-error! "Page size exceeds configured maximum."
                           {:eacl/error :eacl.pagination/invalid-page-size
                            :size size :max max-page-size})

      (page-error!
       "Generated page normalization returned an unknown error."
       {:type :eacl.verification/kernel-failure
        :operation :relationship-page
        :reason reason}))))

(defn normalize-page-request
  [query]
  (when-let [unsupported
             (some #(when (contains? query %) %) [:cursor :limit])]
    (page-request-error!
     "EACL v8 pagination accepts only :first/:after or :last/:before."
     {:key unsupported}))
  (if-not (generated-page-request-encodable? query)
    ;; Reject host values that cannot cross the strict portable integer
    ;; boundary before invoking generated code.
    (host-normalize-page-request query)
    (let [decision
          (verified/decide
           subproblem/*decision-kernel*
           :relationship-page
           (generated-page-input query))]
      (if (= :valid (:status decision))
        {:direction (:direction decision)
         :size (:size decision)
         :bound
         (case (:direction decision)
           :asc (:after query)
           :desc (:before query))}
        (generated-page-error! query (:reason decision))))))

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
(defn subject->resources
  [snapshot subject-type subject-eid relation-eid resource-type cursor-or-opts]
  (let [opts (scan-opts cursor-or-opts)]
    (raw-subject->resources
     snapshot subject-type subject-eid relation-eid resource-type opts)))

(defn resource->subjects
  [snapshot resource-type resource-eid relation-eid subject-type cursor-or-opts]
  (let [opts (scan-opts cursor-or-opts)]
    (raw-resource->subjects
     snapshot resource-type resource-eid relation-eid subject-type opts)))

(defn relation-datoms
  "Compatibility shape used by the extracted algorithm while storage adapters
  return normalized relation-definition maps."
  [snapshot resource-type relation-name]
  (mapv (fn [{:keys [relation-id subject-type]}]
          {:e relation-id
           :v [resource-type relation-name subject-type]})
        (backend/invoke
         snapshot :relation-defs resource-type relation-name)))

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
;; the schema assertion generation visible in the selected immutable snapshot.
;; Supported schema writers publish that generation atomically. Missing or
;; malformed generations disable cross-snapshot derived-state reuse.
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
  "The schema generation visible in this immutable backend snapshot, or nil before
  the first supported schema write."
  [snapshot]
  (let [frame
        (if (and *proof-frame*
                 (identical? snapshot (:adapter *proof-frame*)))
          *proof-frame*
          (proof-frame/request-frame snapshot))
        proof (proof-frame/resolve! frame [])]
    (when (proof-frame/complete? proof)
      (:schema-stamp proof))))

(defn make-schema-cache
  "Creates a derived-schema generation for one selected schema proof.

  A nil proof deliberately disables derived-state latching."
  ([snapshot]
   (make-schema-cache snapshot (schema-version snapshot)))
  ([snapshot known-schema-generation]
   {:backend-id (backend/backend-id snapshot)
    :source-scope (backend/invoke snapshot :source-scope)
    :database-id (:database-id (backend/invoke snapshot :snapshot-id))
    :schema-version known-schema-generation
    ;; The validated assertion generation is already the canonical routing
    ;; identity. Reusing it avoids a semantically duplicate proof acquisition.
    :routing-schema-identity known-schema-generation
    :permission-roots (atom {})
    :permission-paths (atom {})
    :traversal-permissions (atom {})
    :recursive-cycle-guards (atom {})
    :relationship-dependencies (atom {})
    :direct-grant-relations (atom {})}))

(defn- derived-cache-active?
  "True when the bound schema cache may serve derived state.

  Two regimes: a stamped client generation (non-nil :schema-version,
  proof-keyed, shared across requests) or a request-local context
  (:request-local? true, one immutable snapshot, discarded at request
  end). Raw evaluation with no binding stays deliberately uncached."
  []
  (and *schema-cache*
       (or (some? (:schema-version *schema-cache*))
           (true? (:request-local? *schema-cache*)))))

(defn request-schema-cache
  "Derived-schema context scoped to ONE request on ONE immutable snapshot.

  Retains permission paths, roots, routing analysis inputs, cycle guards,
  dependency closures, and compiled/certified recursive plans for the
  duration of a single raw-facade call, eliminating the audited
  duplicate work (repeated cold path walks, duplicate plan
  compile+certify) without publishing anything across requests or
  snapshots — write-schema! remains the only cross-request invalidation
  signal, and speculative/filtered/historical database values get a
  fresh context per call by construction.

  Deliberately carries NO :traversal-analysis: raw routing keeps the
  per-root recursive-permission-query? classification (the compatibility
  branch of traversal-permission?), so a single-root raw call never pays
  the whole-schema certified analysis and the certified analysis remains
  exclusive to client generation caches. :schema-version stays nil so
  can? performs zero proof reads; enumeration identities read the
  adapter's memoized proof.

  DataScript/Datahike raw callers invoke the engine directly on an
  adapter — bind this via engine/*schema-cache* there; the Datomic raw
  facade binds it automatically."
  [snapshot]
  {:backend-id (backend/backend-id snapshot)
   :source-scope nil
   :schema-version nil
   :routing-schema-identity nil
   :request-local? true
   :permission-roots (atom {})
   :permission-paths (atom {})
   :traversal-permissions (atom {})
   :recursive-cycle-guards (atom {})
   :relationship-dependencies (atom {})
   :direct-grant-relations (atom {})})

(defn schema-cache-key
  "Identity of schema-derived state for one selected immutable snapshot.

  The key deliberately contains no listener/client counter. A missed callback
  cannot make a cache entry cross a source or schema proof boundary."
  ([snapshot]
   (schema-cache-key
    snapshot
    (schema-version snapshot)))
  ([snapshot schema-generation]
   [engine-version
    (backend/backend-id snapshot)
    (backend/fingerprint snapshot)
    (backend/invoke snapshot :source-scope)
    (backend/invoke snapshot :source-lifecycle)
    schema-generation]))

(def ^:private maximum-schema-cache-generations 64)

(defn- trim-schema-generations
  [generations protected-key]
  (let [overflow (- (count generations) maximum-schema-cache-generations)]
    (if (pos? overflow)
      (apply dissoc
             generations
             (take overflow
                   (sort-by pr-str (remove #{protected-key}
                                           (keys generations)))))
      generations)))

(defn schema-cache-for!
  "Returns a bounded derived generation keyed by selected source and proof.

  Installation is one nonblocking atomic update. A request retains its
  immutable generation even if a later install evicts it from the registry."
  [registry snapshot]
  (let [schema-generation (schema-version snapshot)
        key (schema-cache-key snapshot schema-generation)
        existing (get @registry key)]
    (if existing
      existing
      (let [created
            (make-schema-cache
             snapshot
             schema-generation)
            selected (volatile! created)]
        (swap! registry
               (fn [generations]
                 (if-let [installed (get generations key)]
                   (do
                     (vreset! selected installed)
                     generations)
                   (trim-schema-generations
                    (assoc generations key created)
                    key))))
        @selected))))

(defn- permission-paths-cache-key
  [resource-type permission-name]
  [resource-type permission-name])
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
  (if-not (and (derived-cache-active?)
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
  (inc-shape-stat! :permission-path-calcs)
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
  (if-not (derived-cache-active?)
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
  (if-not (derived-cache-active?)
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

(defn all-permission-nodes
  "The engine's only consumer of the required `:all-permission-nodes` adapter
  operation; kept so the backend-dispatch closure ledger covers every
  required operation. No routed path calls it (see the 2026-08-15 audit)."
  [snapshot]
  (set (backend/invoke snapshot :all-permission-nodes)))

(def default-recursive-traversal-limits
  "Safety ceilings for one recursive traversal.

  These bound a SINGLE page computation. A cache-disabled recursive cursor may
  replay its traversal prefix when its relationship proof is still current.
  Cache-enabled cursors resume a retained continuation when available and
  otherwise replay the authenticated logical prefix on the same snapshot.

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

(defn- complete-evaluation-required!
  [query]
  (page-error!
   "This recursive page shape requires :evaluation :complete-denotation."
   {:eacl/error :eacl.pagination/complete-evaluation-required
    :evaluation *evaluation-mode*
    :page-request (select-keys query [:first :after :last :before])}))

(def stable-order-abi 1)
(def stable-cursor-version 1)

(defonce ^:private stable-plan-cache
  (atom {:entries {} :order []}))

(defn- stable-plan
  "Seals (or reuses) the direction-specific plan for one root at the exact
  basis. Keyed by the adapter's declared source identity (scope AND
  lifecycle — either alone may be caller-fixed across distinct stores) plus
  basis and root, so re-wrapping the same source at the same basis reuses
  the plan across requests; bounded FIFO."
  [db root-node]
  (let [key [(backend/backend-id db)
             (backend/invoke db :source-scope)
             (backend/invoke db :source-lifecycle)
             (backend/invoke db :native-revision)
             root-node]]
    (or (get-in @stable-plan-cache [:entries key])
        (let [plan (sealed-plan/seal-plan db root-node)]
          (swap! stable-plan-cache
                 (fn [{:keys [entries order] :as cache}]
                   (if (contains? entries key)
                     cache
                     (let [order (conj order key)
                           entries (assoc entries key plan)]
                       (if (> (count order) 128)
                         {:entries (dissoc entries (first order))
                          :order (subvec order 1)}
                         {:entries entries :order order})))))
          plan))))

(defn- stable-edge
  [plan traversal ordinal eid]
  {:kind :stable-edge
   :version stable-cursor-version
   :order-abi stable-order-abi
   :fingerprint (:fingerprint plan)
   :traversal traversal
   :ordinal ordinal
   :result-eid eid})

(defn- validate-stable-bound!
  [plan traversal bound]
  (when bound
    (when-not (= :stable-edge (:kind bound))
      (page-error!
       "Lookup page cursor has the wrong kind."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :actual (:kind bound)}))
    (when (not= (:fingerprint plan) (:fingerprint bound))
      (page-error!
       "Cursor is bound to an incompatible sealed plan."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :plan-fingerprint-mismatch}))
    (when (not= traversal (:traversal bound))
      (page-error!
       "Cursor traversal direction does not match the request."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :actual (:traversal bound)}))
    (when-not (and (integer? (:ordinal bound))
                   (pos? (:ordinal bound))
                   (some? (:result-eid bound)))
      (page-error!
       "Cursor boundary is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-boundary}))))

(defn- stable-limits
  "Maps the public recursive-traversal limits onto the stable reducer's
  budgets with matching semantics: derived grants bound unique logical
  admissions, advanced datoms bound consumed projection values, and
  queued work bounds INSTANTANEOUS stack depth (never cumulative
  transitions — a long chain traversal legitimately takes many
  transitions while its queue stays shallow). Cumulative transition and
  command counts remain internal reducer safety ceilings."
  []
  (let [{:keys [max-derived-grants max-advanced-datoms max-queued-work]}
        *recursive-traversal-limits*]
    (cond-> {}
      max-derived-grants (assoc :max-admissions max-derived-grants)
      max-advanced-datoms (assoc :max-values max-advanced-datoms)
      max-queued-work (assoc :max-stack max-queued-work)
      ;; The internal runaway ceilings scale with the authorized public
      ;; work so raising the public limits actually authorizes it: each
      ;; admission and each consumed value costs a bounded number of
      ;; transitions, and every scan occurrence (bounded by admissions)
      ;; costs at least one command even when it reads nothing.
      (or max-derived-grants max-advanced-datoms)
      (assoc :max-transitions
             (max stable-reducer/default-max-transitions
                  (* 4 (+ (or max-derived-grants 0)
                          (or max-advanced-datoms 0))))
             :max-commands
             (max stable-reducer/default-max-commands
                  (+ (or max-derived-grants 0)
                     (or max-advanced-datoms 0)))))))

(def ^:private stable-limit-kinds
  {:max-admissions :derived-grants
   :max-values :advanced-datoms
   :max-stack :queued-work
   ;; Internal safety ceilings surface under the closest public kind.
   :max-commands :advanced-datoms
   :max-transitions :queued-work})

(defn- with-public-limit-errors
  "The stable reducer's typed limit failure surfaces under the public
  recursive-traversal error key; when the caller observes work stats, the
  reducer reports its per-run deltas under the public counter names."
  [thunk]
  (try
    (binding [stable-reducer/*observer-stats* *recursive-traversal-stats*]
      (thunk))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) error
      (if (contains? #{:eacl.reducer/limit-exceeded
                       :eacl.page/resource-exhausted}
                     (:eacl/error (ex-data error)))
        ;; The public shape is exactly {:eacl/error :limit-kind :limit}
        ;; with :limit as the caller's configured numeric ceiling; internal
        ;; counters and reducer budget keys never leak.
        (let [data (ex-data error)
              budget-key (:limit data)]
          (page-error!
           "Recursive traversal exceeded its configured limits."
           {:eacl/error :eacl.recursive-traversal/limit-exceeded
            :limit-kind (get stable-limit-kinds budget-key :derived-grants)
            :limit (get data budget-key)}))
        (throw error)))))

(defn- stable-cut-point
  "Execution enforcement (deadline and cancellation) for the routed stable
  engine: one bounded check per reducer transition, installed only when the
  caller runs under an execution contract so raw local evaluation keeps a
  bare hot path."
  []
  (when-let [contract execution/*contract*]
    (physical/execution-cut-point contract)))

(defn- stable-checkpoints
  "Accepts a stable-page checkpoint store (raw atom) or the client's scoped
  continuation context (fn-map with its own bounds and eviction); anything
  else degrades to deterministic replay."
  [cache]
  (cond
    (and (map? cache)
         (true? (:opaque-values? cache))
         (fn? (:get cache))
         (fn? (:put! cache)))
    cache

    (and cache
         #?(:clj (instance? clojure.lang.IAtom cache)
            :cljs (instance? Atom cache))
         (map? @cache)
         (contains? @cache :entries))
    cache))

(defn- stable-items
  [plan traversal result-type start-ordinal eids]
  (mapv
   (fn [offset eid]
     {:node (spice-object result-type eid)
      :cursor (stable-edge plan traversal
                           (+ start-ordinal offset 1) eid)})
   (range)
   eids))

(defn- with-stale-boundary-errors
  "A well-formed authenticated boundary that replay cannot validate means
  the selected basis no longer reproduces the cursor's edge — the public
  contract calls that a stale cursor, never an invalid one, and the shape
  carries no internal ordinal or identity diagnostics."
  [bound thunk]
  (if-not bound
    (thunk)
    (try
      (thunk)
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) error
        (if (= :eacl.page/invalid-cursor (:eacl/error (ex-data error)))
          (page-error! "The cursor's exact boundary is no longer reproducible."
                       {:eacl/error :eacl.pagination/stale-cursor})
          (throw error))))))

(defn- stable-lookup-page
  [db traversal query cache]
  (let [{:keys [direction size bound]} (normalize-page-request query)
        forward? (= :forward traversal)
        result-type (if forward?
                      (:resource/type query)
                      (:subject/type query))
        root-type (if forward?
                    (:resource/type query)
                    (:type (:resource query)))
        anchor (if forward? (:subject query) (:resource query))
        subject-type (if forward?
                       (:type (:subject query))
                       (:subject/type query))]
    (if-not (permission-root-defined? db root-type (:permission query))
      (page-response {:items [] :has-next? false
                      :has-previous? (boolean bound)})
      (let [root-node (permission-query-node root-type (:permission query))
            plan (stable-plan db root-node)
            ;; A bare :last window on a recursive schema exhausts a
            ;; data-dependent traversal; that cost stays opt-in via
            ;; :evaluation :complete-denotation (public v8 contract).
            _ (when (and (:recursive? plan)
                         (= :demand *evaluation-mode*)
                         (= :desc direction)
                         (nil? bound))
                (complete-evaluation-required! query))
            _ (validate-stable-bound! plan traversal bound)
            anchor-eid (object-eid db (:id anchor))
            edge (when bound {:ordinal (:ordinal bound)
                              :eid (:result-eid bound)})
            result (with-stale-boundary-errors
                     bound
                     (fn []
                       (with-public-limit-errors
                        #(stable-page/edge-page
                          (merge
                        (stable-limits)
                        {:adapter db
                         :plan plan
                         :direction traversal
                         :anchor-eid anchor-eid
                         :subject-type subject-type
                         :cut-point! (stable-cut-point)
                         :page-size size
                         :after (when (= :asc direction) edge)
                         :before (when (and (= :desc direction) edge) edge)
                         :last-window? (and (= :desc direction) (nil? edge))
                         :checkpoints (stable-checkpoints cache)
                         :checkpoint-key [(:fingerprint plan)
                                          (backend/invoke db :native-revision)
                                          traversal subject-type anchor-eid
                                          size]})))))]
        (page-response
         {:items (stable-items plan traversal result-type
                               (:start-ordinal result) (:eids result))
          :has-next? (:has-next? result)
          :has-previous? (:has-previous? result)})))))

(defn can?
  [db subject permission resource]
  (let [subject-type (:type subject)
        subject-eid (object-eid db (:id subject))
        resource-type (:type resource)
        resource-eid (object-eid db (:id resource))
        defined-root?
        (and subject-eid
             resource-eid
             (permission-root-defined?
              db resource-type permission))]
    (if defined-root?
      (let [root-node (permission-query-node resource-type permission)
            plan (stable-plan db root-node)]
        (with-public-limit-errors
          #(stable-route/check-eids
            (merge (stable-limits)
                   {:adapter db
                    :plan plan
                    :subject-type subject-type
                    :subject-eid subject-eid
                    :resource-eid resource-eid
                    :cut-point! (stable-cut-point)}))))
      false)))
(defn lookup-resources
  "Stable-discovery forward pagination.

  Raw-impl callers must hold one DB value for a whole exact-snapshot walk; the
  public client authenticates and scopes cursor state before it reaches here."
  ([db query]
   (lookup-resources db query nil))
  ([db query {:keys [continuation-cache continuation-cache-fn]}]
   (let [cache (or continuation-cache
                   (when continuation-cache-fn (continuation-cache-fn)))]
     (stable-lookup-page db :forward query cache))))

(defn lookup-subjects
  "Stable-discovery reverse pagination; cursors are only valid against the
  minting db basis."
  ([db query]
   (lookup-subjects db query nil))
  ([db query {:keys [continuation-cache continuation-cache-fn]}]
   {:pre [(:type (:resource query)) (:id (:resource query))]}
   (when (:subject/relation query)
     ;; Returning subjects while silently ignoring this filter would be
     ;; unsound, so the v8 contract rejects it.
     (page-error! ":subject/relation is not supported by lookup-subjects."
                  {:eacl/error :eacl.pagination/unsupported-filter
                   :filter :subject/relation}))
   (let [cache (or continuation-cache
                   (when continuation-cache-fn (continuation-cache-fn)))]
     (stable-lookup-page db :reverse query cache))))

(def ^:private count-pagination-keys
  [:cursor :limit :first :last :before :after])

(defn- reject-count-pagination-keys!
  [op query]
  (when (some #(contains? query %) count-pagination-keys)
    (page-request-error! (str op " does not use list pagination keys.")
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
(defn count-resources
  [db {:as query}]
  (reject-count-pagination-keys! "count-resources" query)
  (let [limit (query-count-limit query)
        {:keys [subject permission]} query
        result-type (:resource/type query)]
    (count-response
     (if-not (permission-root-defined? db result-type permission)
       {:count 0 :truncated? false}
       (let [plan (stable-plan
                   db (permission-query-node result-type permission))]
         (select-keys
          (with-public-limit-errors
            #(stable-route/count-resources
              (merge (stable-limits)
                     {:adapter db
                      :plan plan
                      :subject-type (:type subject)
                      :subject-id (:id subject)
                      :count-limit limit
                      :cut-point! (stable-cut-point)})))
          [:count :truncated?])))
     limit)))

(defn count-subjects
  [db {:as query}]
  (reject-count-pagination-keys! "count-subjects" query)
  (when (:subject/relation query)
    (page-error! ":subject/relation is not supported by count-subjects."
                 {:eacl/error :eacl.pagination/unsupported-filter
                  :filter :subject/relation}))
  (let [limit (query-count-limit query)
        {:keys [resource permission]} query]
    (count-response
     (if-not (permission-root-defined? db (:type resource) permission)
       {:count 0 :truncated? false}
       (let [plan (stable-plan
                   db (permission-query-node (:type resource) permission))]
         (select-keys
          (with-public-limit-errors
            #(stable-route/count-subjects
              (merge (stable-limits)
                     {:adapter db
                      :plan plan
                      :subject-type (:subject/type query)
                      :resource-id (:id resource)
                      :count-limit limit
                      :cut-point! (stable-cut-point)})))
          [:count :truncated?])))
     limit)))
