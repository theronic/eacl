(ns eacl.engine.v8
  (:require [eacl.backend.v8 :as backend]
            [eacl.cache.derived-schema :as derived-schema]
            [eacl.core :refer [spice-object]]
            [eacl.engine.least-path :as least-path]
            [eacl.engine.physical :as physical]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-page :as stable-page]
            [eacl.engine.stable-reducer :as stable-reducer]
            [eacl.engine.stable-route :as stable-route]
            [eacl.execution :as execution]
            [eacl.exact-integer :as exact-integer]
            [eacl.operator.batch-schedule :as operator-batch-schedule]
            [eacl.operator.cover-plan :as operator-cover-plan]
            [eacl.operator.cursor-scope :as operator-cursor-scope]
            [eacl.operator.lookup :as operator-lookup]
            [eacl.operator.plan :as operator-plan]
            [eacl.operator.recursive :as operator-recursive]
            [eacl.operator.vector-evaluator :as operator-vector]
            [eacl.request.counters :as request-counters]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.secure-format :as secure-format]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(def engine-version 8)

(def ^:dynamic *evaluation-mode*
  "Normalized public evaluation mode. Cache state never changes this value."
  :demand)

(def ^:dynamic *operator-routing-enabled?*
  "Public intersection/exclusion routing gate. Union-only plans never consult
  it. Dynamic binding remains available for release-gate regression tests."
  true)

(def ^:dynamic *proof-frame*
  "The request-scoped ordered-generation frame, or nil for raw evaluation."
  nil)

(def ^:dynamic *request-lineage*
  "The request context's canonical source-scope/lifecycle lineage."
  nil)

(def ^:dynamic *request-frame*
  "The request context's canonical complete frame descriptor, or a delay of
  it. The descriptor covers the full public query closure and is shared with
  cursor and answer-cache decisions."
  nil)

(def ^:private default-page-size 1000)
(def ^:private max-page-size 10000)
(def ^:dynamic *backend-work-stats*
  "Optional atom populated by tests, benchmarks, and diagnostic callers.

  Counts backend operations actually invoked by the engine. Cache layers
  record avoided work separately; keeping executed and avoided counters
  distinct prevents a cache hit from being mistaken for database work."
  nil)

(def ^:dynamic *recursive-traversal-stats*
  "Optional atom populated by tests, benchmarks, and diagnostic callers.

  Receives per-run work deltas under the public counter names from every
  stable route. The reducer and the witness probe-checks report
  :derived-grants, :advanced-datoms, :queued-work, and :fetched-values; the least-path
  evaluator reports its emissions as :derived-grants, its physical
  commands as :advanced-datoms, and its scan opens as :stream-opens (it
  keeps no work queue, so it never reports :queued-work).
  :continuation-hits counts checkpoint hits and :adapter-attempts counts
  physical attempts on the routed path. Request-shape observers live in
  *request-shape-stats*. Observation-only."
  nil)
(def ^:dynamic *aggregate-work-stats*
  "Request-owned semantic work meter for aggregate authorization limits."
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

(defn- claim-bounded-once!
  [claimed value limit]
  (loop []
    (let [current @claimed]
      (cond
        (contains? current value) false
        (>= (count current) limit) false
        (compare-and-set! claimed current (conj current value)) true
        :else (recur)))))

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
      (when (claim-bounded-once! warned-schema-conditions condition 256)
        #?(:clj
           (binding [*out* *err*]
             (println message (pr-str data)))
           :cljs
           (.warn js/console message (pr-str data)))))))

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
      (page-request-error! "Use exactly one of :first or :last."
                           {:first (:first query)
                            :last (:last query)})

      (and has-before? has-after?)
      (page-request-error! "Use only one cursor boundary, :after or :before."
                           {:after (:after query)
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

(defn- generated-page-request-encodable?
  [query]
  (every?
   (fn [field]
     (or (not (contains? query field))
         (nil? (get query field))
         (exact-integer/natural? (get query field))))
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
        :eacl/error :eacl.verification/kernel-failure
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

;; --- Selected-snapshot derived schema cache ----------------------------------
;;
;; Certified client requests retain individual immutable artifacts in one
;; standard cache. Complete source/lifecycle, adapter, schema-generation,
;; artifact, and semantic identity are ordinary opaque keys; there is no outer
;; generation registry or nested shared atom map.  Uncertified/raw requests use
;; only the plain atom maps created by `request-schema-cache` and discard them
;; with the request.

(def ^:dynamic *schema-cache*
  "The selected request's shared derived partitions or request-local memos.

  nil means raw/arbitrary-db evaluation and is deliberately uncached."
  nil)

(def derived-schema-cache-abi
  "Compatibility identity for individual cross-request schema derivations."
  {:format :eacl.engine.v8/derived-schema-v1
   :engine-version engine-version
   :backend-adapter-version backend/adapter-version
   :permission-expression backend/permission-expression-capability
   :sealed-plan-version sealed-plan/plan-version
   :operator-plan-version operator-plan/plan-version})

(defn schema-version
  "The certified EACL schema generation visible in this immutable backend
  snapshot, or nil when the adapter cannot certify one. This read is
  independent of ordered relationship-generation proofs."
  [snapshot]
  (backend/invoke snapshot :schema-generation))

(defn- local-schema-cache
  []
  {:schema-version nil
   :request-local? true
   :parsed-schema (atom nil)
   :validation-catalog (atom nil)
   :expression-metrics (atom {})
   :sealed-plans (atom {})
   :permission-roots (atom {})
   :permission-paths (atom {})
   :relationship-dependencies (atom {})})

(defn- derived-cache-active?
  "True when the bound schema cache may serve derived state.

  Two regimes: a stamped client context backed by bounded cache partitions or
  a plain request-local context discarded at request end. Raw evaluation with
  no binding stays deliberately uncached."
  []
  (and *schema-cache*
       (or (some? (:schema-version *schema-cache*))
           (true? (:request-local? *schema-cache*)))))

(defn request-schema-cache
  "Derived-schema context scoped to ONE request on ONE immutable snapshot.

  Retains only the parsed schema/catalog, expression decodes, permission
  roots/paths/dependencies, and compiled plans used more than once during a
  raw-facade call. Nothing is published across requests or snapshots.
  :schema-version stays nil so raw can? performs no schema-proof read.

  DataScript/Datahike raw callers invoke the engine directly on an
  adapter — bind this via engine/*schema-cache* there; the Datomic raw
  facade binds it automatically.

  Sealed plans and validation catalogs live in this context too, so an
  uncertified request still prepares each root at most once without publishing
  any artifact across requests."
  ([]
   (local-schema-cache))
  ([_snapshot]
   (local-schema-cache))
  ([_snapshot _options]
   (local-schema-cache)))

(defn- derived-value-valid?
  [artifact value]
  (case artifact
    :parsed-schema (map? value)
    :validation-catalog (map? value)
    :expression-decodes (map? value)
    :sealed-plans (map? value)
    :permission-roots (boolean? value)
    :permission-paths (vector? value)
    :relationship-dependencies (vector? value)
    false))

(defn- derived-semantic-key
  [semantic]
  {:semantic semantic
   :expression-limits
   (expression-persistence/effective-expression-limits)})

(defn- memoized-partition-derived!
  [partition semantic build]
  (let [artifact (:artifact partition)
        valid? #(derived-value-valid? artifact %)
        semantic (derived-semantic-key semantic)
        found (derived-schema/lookup! partition semantic)]
    (if (:found? found)
      (:value found)
      (let [value (build)]
        (derived-schema/publish! partition semantic value valid?)
        value))))

(defn memoized-derived!
  "Reads one installed immutable artifact before allocating or building.

  Concurrent misses build independently under their own request context and
  make one bounded compare-and-install attempt. A publication loser may use
  its own completed value; failures are never installed or shared."
  [slot build]
  (if (derived-schema/partition? slot)
    (memoized-partition-derived! slot :singleton build)
    (let [current @slot]
      (if (some? current)
        current
        (let [value (build)]
          (compare-and-set! slot nil value)
          value)))))

(def ^:private memo-miss
  ;; Identity-safe miss sentinel: CLJS does not intern keyword literals,
  ;; so `(identical? ::miss ...)` is false between two occurrences there.
  #?(:clj (Object.) :cljs (js/Object.)))

(defn- memoized-map-derived!
  "Map-keyed counterpart of `memoized-derived!`.
  A miss builds under the caller and makes one bounded installation attempt;
  no request ever waits on another request's delay or inherits its failure."
  [registry key build]
  (if (derived-schema/partition? registry)
    (memoized-partition-derived! registry key build)
    (let [semantic (derived-semantic-key key)
          snapshot @registry
          hit (get snapshot semantic memo-miss)]
      (if-not (identical? memo-miss hit)
        hit
        (let [value (build)
              state @registry]
          (when-not (contains? state semantic)
            (compare-and-set!
             registry state
             (assoc state semantic value)))
          value)))))

(defn- schema-cache-identity
  [snapshot basis-identity schema-generation]
  (let [backend-id (backend/backend-id snapshot)]
    {:abi derived-schema-cache-abi
     :source
     (select-keys basis-identity
                  [:backend :source-id :branch :source-lifecycle])
     :adapter
     {:backend backend-id
      :fingerprint (backend/fingerprint snapshot)
      :identity-contract (backend/identity-contract snapshot)
      :operator-capability (backend/operator-capability-identity snapshot)}
     :schema-generation schema-generation}))

(defn- shared-schema-cache
  [store snapshot basis-identity schema-generation]
  (let [identity
        (schema-cache-identity snapshot basis-identity schema-generation)
        part #(derived-schema/artifact-partition store identity %)]
    {:schema-version schema-generation
     :parsed-schema (part :parsed-schema)
     :validation-catalog (part :validation-catalog)
     :expression-metrics (part :expression-decodes)
     :sealed-plans (part :sealed-plans)
     :permission-roots (part :permission-roots)
     :permission-paths (part :permission-paths)
     :relationship-dependencies (part :relationship-dependencies)}))

(defn schema-cache-for!
  "Returns stateless LRU partitions for one complete certified schema identity.

  Without a schema generation or complete basis identity, returns a fresh
  request-local memo context. Individual misses compute independently and
  publish only completed artifacts into the shared count-bounded store."
  ([_registry snapshot]
   (request-schema-cache snapshot))
  ([_registry snapshot _schema-generation]
   (request-schema-cache snapshot))
  ([store snapshot basis-identity schema-generation]
   (if (or (nil? schema-generation)
           (nil? basis-identity)
           (nil? store))
     (request-schema-cache snapshot)
     (shared-schema-cache
      store snapshot basis-identity schema-generation))))

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

(defn- permission-root-defined-uncached?
  [db resource-type permission-name]
  (try
    (boolean
     (seq (find-permission-defs db resource-type permission-name)))
    (catch #?(:clj Exception :cljs :default) error
      (if (= :eacl.schema/operator-plan-required (:type (ex-data error)))
        true
        (throw error)))))

(defn- permission-root-defined?
  [db resource-type permission-name]
  (if-not (and (derived-cache-active?)
               (some? (:permission-roots *schema-cache*)))
    (permission-root-defined-uncached? db resource-type permission-name)
    (memoized-map-derived!
     (:permission-roots *schema-cache*)
     (permission-paths-cache-key resource-type permission-name)
     #(permission-root-defined-uncached?
       db resource-type permission-name))))

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
    (memoized-map-derived!
     (:permission-paths *schema-cache*)
     (permission-paths-cache-key resource-type permission-name)
     #(calc-permission-paths db resource-type permission-name))))
(defn- permission-query-node
  [resource-type permission-name]
  [resource-type permission-name])

(defn- node-relation-eids
  "Relation-definition eids for a node name that is a relation, not a
  permission.

  Permission-tree expansion accepts a relation as its root and then reads that
  relation's relationships directly, so those definitions belong in the
  dependency closure even though no permission path names them. Without this,
  a relation root closes over nothing, its answer is proof-equal at every later
  snapshot, and a cached tree survives the relationship writes it reports."
  [db resource-type relation-name]
  (into #{}
        (map :e)
        (relation-datoms db resource-type relation-name)))

(defn- calc-permission-relationship-eids
  [db resource-type permission-name]
  (loop [stack [(permission-query-node resource-type permission-name)]
         seen #{}
         relationship-eids #{}]
    (if-let [[node-resource-type node-permission :as node] (peek stack)]
      (if (contains? seen node)
        (recur (pop stack) seen relationship-eids)
        (let [paths (get-permission-paths db node-resource-type node-permission)
              ;; Only a name with no permission paths can be a relation, so
              ;; ordinary permission roots never pay this lookup.
              relationship-eids
              (if (seq paths)
                relationship-eids
                (into relationship-eids
                      (node-relation-eids
                       db node-resource-type node-permission)))
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

(declare stable-plan)

(defn permission-relationship-eids
  "Returns the sorted relation-definition eids whose relationship tuples can
  affect one permission lookup. A stamped client memoises the vector for its
  schema generation, so live-result reads do not sort dependencies."
  [db resource-type permission-name]
  (try
    (if-not (derived-cache-active?)
      (calc-permission-relationship-eids db resource-type permission-name)
      (memoized-map-derived!
       (:relationship-dependencies *schema-cache*)
       (permission-paths-cache-key resource-type permission-name)
       #(calc-permission-relationship-eids
         db resource-type permission-name)))
    (catch #?(:clj Exception :cljs :default) error
      (if (= :eacl.schema/operator-plan-required (:type (ex-data error)))
        (let [root [resource-type permission-name]
              plan (stable-plan db root)]
          (if (operator-plan/operator-plan? plan)
            (get-in plan [:relation-closures root :all])
            (throw error)))
        (throw error)))))

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
  (try
    (set
     (reachable-permission-query-nodes
      db
      (permission-query-node resource-type permission-name)))
    (catch #?(:clj Exception :cljs :default) error
      (if (= :eacl.schema/operator-plan-required (:type (ex-data error)))
        (let [plan (stable-plan db [resource-type permission-name])]
          (if (operator-plan/operator-plan? plan)
            (into #{} (map :permission) (:expressions plan))
            (throw error)))
        (throw error)))))

(defn direct-match-datoms-in-relationship-index
  [snapshot subject-type subject-eid relation-eid resource-type resource-eid]
  (record-backend-work! :direct-match-probes)
  (if (backend/invoke snapshot
                      :direct-match?
                      subject-type
                      subject-eid
                      relation-eid
                      resource-type
                      resource-eid)
    [true]
    []))

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
  (let [overrides (or overrides {})]
    (when-not (map? overrides)
      (throw (ex-info ":recursive-traversal-limits must be a map."
                      {:type :eacl/invalid-config
                       :eacl/error :eacl/invalid-config
                       :recursive-traversal-limits overrides})))
    (let [known (set (keys default-recursive-traversal-limits))
          unknown (seq (remove known (keys overrides)))]
      (when unknown
        (throw (ex-info "Unknown recursive traversal safety limit."
                        {:type :eacl/invalid-config
                         :eacl/error :eacl/invalid-config
                         :unknown-keys (vec unknown)
                         :known-keys known})))
      (when-not (every? (fn [[_ value]]
                          (and (integer? value) (pos? value)))
                        overrides)
        (throw (ex-info "Recursive traversal safety limits must be positive integers."
                        {:type :eacl/invalid-config
                         :eacl/error :eacl/invalid-config
                         :recursive-traversal-limits overrides})))
      (merge default-recursive-traversal-limits overrides))))

(def ^:dynamic *recursive-traversal-limits*
  default-recursive-traversal-limits)

(defn- complete-evaluation-required!
  [query]
  (page-error!
   "This recursive page shape requires :evaluation :complete-denotation."
   {:eacl/error :eacl.pagination/complete-evaluation-required
    :evaluation *evaluation-mode*
    :page-request (select-keys query [:first :after :last :before])}))

(def stable-order-abi 2)
(def stable-cursor-version 2)

(def compiler-plan-compatibility
  "Complete compatibility identity for completed values produced by the v8
  planner. This is deliberately a constant-time cache-key input, not a plan
  fingerprint: scalar answers do not need to seal a plan on a cache hit.

  Any change to the fingerprint algorithm, sealed/operator plan ordering or
  ranking, execution-frontier interpretation, or cursor/checkpoint semantics
  that can change a produced value must change this identity. Work-only
  adapter capabilities remain part of the adapter/cache ABI instead."
  {:identity-version 1
   :fingerprint-algorithm
   {:digest :sha-256
    :canonical-format secure-format/canonical-version
    :record-framing :unsigned-32-bit-big-endian-length-prefix}
   :sealed-plan
   {:plan-version sealed-plan/plan-version
    :fingerprint-domain sealed-plan/fingerprint-domain
    :rank-contract sealed-plan/rank-contract
    :order-contract sealed-plan/order-contract
    :execution-frontier
    {:version 1
     :normalization :exact-same-resource-single-body-alias
     :deduplication :complete-rule-identity-excluding-ordinal
     :retention :first-canonical-pre-normalization-position}}
   :operator-plan
   {:plan-version operator-plan/plan-version
    :fingerprint-domain operator-plan/fingerprint-domain
    :order-contract operator-plan/order-contract
    :versions
    {:cover operator-plan/cover-version
     :witness operator-plan/witness-version
     :predicate operator-plan/predicate-version
     :physical-policy operator-plan/physical-policy-version}}
   :cursor
   {:stable-version stable-cursor-version
    :stable-order-abi stable-order-abi
    :operator-scope-version operator-cursor-scope/scope-version
    :operator-scope-domain operator-cursor-scope/scope-domain
    :operator-recursive-checkpoint-version
    operator-recursive/checkpoint-version}})

(defn certify-plan-read-scope!
  "Rejects a compiled plan that can read outside its permission closure.

  The closure and the sealed rules are derived independently from the schema.
  This executable guard is the implementation witness for
  `ReducerReadScope.dfy`: an equal ordered-generation frame covers every
  relation slice that can influence the reducer's transitions or stream."
  [plan relation-ids]
  (let [closure (set relation-ids)
        plan-relations
        (if (operator-plan/operator-plan? plan)
          (->> (:relation-closures plan)
               vals
               (mapcat :all)
               distinct
               sort
               vec)
          (sealed-plan/relation-ids plan))
        outside (vec (remove closure plan-relations))]
    (when (seq outside)
      (throw
       (ex-info
        "Sealed plan reads outside its certified relation closure."
        {:type :eacl.plan/compile-error
         :eacl/error :eacl.plan/compile-error
         :reason :relation-outside-dependency-closure
         :outside-relation-ids outside
         :plan-relation-ids plan-relations
         :dependency-relation-ids (vec relation-ids)})))
    plan))

(defn- seal-and-certify-plan
  [db [resource-type permission :as root-node]]
  (let [plan (binding [expression-persistence/*structural-cache*
                       (:expression-metrics *schema-cache*)]
               (operator-plan/seal-plan db root-node))]
    (certify-plan-read-scope!
     plan
     (if (operator-plan/operator-plan? plan)
       (get-in plan [:relation-closures root-node :all])
       (permission-relationship-eids db resource-type permission)))))

(defn- require-enabled-plan [plan]
  (when (and (operator-plan/operator-plan? plan)
             (not *operator-routing-enabled?*))
    (throw
     (ex-info
      "Public operator routing is disabled by the pre-release gate."
      {:type :eacl.operator/routing-disabled
       :eacl/error :eacl.operator/routing-disabled
       :root (:root plan)})))
  plan)

(defn ^:no-doc stable-plan
  "Seals each normalized root once in the bound generation-owned cache. An
  uncertified request receives the same behavior in its request-local floor;
  an unbound raw engine call seals without publishing."
  [db root-node]
  (if-let [plans (:sealed-plans *schema-cache*)]
    (require-enabled-plan
     (memoized-map-derived!
      plans root-node
      #(do
         (request-counters/add! :definition-reads)
         (request-counters/add! :seals)
         (seal-and-certify-plan db root-node))))
    (do
      (request-counters/add! :definition-reads)
      (request-counters/add! :seals)
      (require-enabled-plan (seal-and-certify-plan db root-node)))))

(defn- stable-edge
  [plan traversal ordinal eid]
  {:kind :stable-edge
   :version stable-cursor-version
   :anchor :progress
   :order-abi stable-order-abi
   :fingerprint (:fingerprint plan)
   :traversal traversal
   :ordinal ordinal
   :result-eid eid})

(defn- least-path-edge
  "The keyset cursor of an acyclic (:least-path) plan: the boundary
  result's full per-scan coordinate sequence. Self-contained — resume is
  a per-level seek (LeastPathResume.dfy), no checkpoint store and no
  replay (acyclic-keyset-pagination)."
  [plan traversal coords]
  {:kind :least-path-edge
   :version stable-cursor-version
   :anchor :progress
   :order-abi stable-order-abi
   :fingerprint (:fingerprint plan)
   :traversal traversal
   :coords coords})

(defn- operator-snapshot-proof-identity [db]
  (or (when *request-frame* (force *request-frame*))
      (:basis-identity *proof-frame*)
      {:selected-snapshot (backend/invoke db :snapshot-id)}))

(defn- stable-cover-plan
  "Seals the operator cover once per (plan fingerprint, root) in the bound
  generation-owned cache instead of once per page. The sealed cover is
  closure-free schema-derived data (`sealed-plan/seal-plan` is pure with
  respect to relationship data), so generation-scoped reuse is sound; an
  unbound raw engine call still seals per request without publishing."
  [db plan]
  (if-let [plans (:sealed-plans *schema-cache*)]
    (memoized-map-derived!
     plans
     [::operator-cover (:fingerprint plan) (:root plan)]
     #(operator-cover-plan/seal-plan db plan))
    (operator-cover-plan/seal-plan db plan)))

(defn- operator-edge
  [plan cover-plan traversal semantic-scope coords]
  {:kind :operator-least-path-edge
   :version stable-cursor-version
   :anchor :progress
   :order-abi stable-order-abi
   :fingerprint (:fingerprint plan)
   :cover-fingerprint (:fingerprint cover-plan)
   :semantic-scope semantic-scope
   :traversal traversal
   :coords coords})

(defn- validate-operator-bound!
  [plan cover-plan traversal scope-delay bound]
  (when bound
    (when-not (= :operator-least-path-edge (:kind bound))
      (page-error!
       "Operator lookup cursor has the wrong kind."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :actual (:kind bound)}))
    (when-not (and (= stable-cursor-version (:version bound))
                   (= stable-order-abi (:order-abi bound)))
      (page-error!
       "Operator cursor uses an incompatible execution ABI."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :operator-abi-mismatch}))
    (when-not (and (= (:fingerprint plan) (:fingerprint bound))
                   (= (:fingerprint cover-plan)
                      (:cover-fingerprint bound))
                   (= (force scope-delay) (:semantic-scope bound)))
      (page-error!
       "Operator cursor is bound to an incompatible semantic scope."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :operator-scope-mismatch}))
    (when-not (= traversal (:traversal bound))
      (page-error!
       "Operator cursor traversal direction does not match the request."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :actual (:traversal bound)}))
    (when-not (= :progress (:anchor bound))
      (page-error!
       "Operator cursor progress anchor is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-progress-anchor}))
    (when-not (and (vector? (:coords bound))
                   (seq (:coords bound))
                   (every? integer? (:coords bound)))
      (page-error!
       "Operator cursor boundary is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-boundary}))))

(defn- recursive-operator-edge
  [plan cover-plan traversal semantic-scope cover-edge]
  {:kind :operator-recursive-edge
   :version stable-cursor-version
   :anchor :progress
   :order-abi stable-order-abi
   :fingerprint (:fingerprint plan)
   :cover-fingerprint (:fingerprint cover-plan)
   :semantic-scope semantic-scope
   :recursive-checkpoint-version operator-recursive/checkpoint-version
   :traversal traversal
   :cover-edge cover-edge})

(defn- validate-recursive-operator-bound!
  [plan cover-plan traversal scope-delay bound]
  (when bound
    (when-not (= :operator-recursive-edge (:kind bound))
      (page-error!
       "Recursive operator cursor has the wrong kind."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :actual (:kind bound)}))
    (when-not (and (= stable-cursor-version (:version bound))
                   (= stable-order-abi (:order-abi bound))
                   (= operator-recursive/checkpoint-version
                      (:recursive-checkpoint-version bound)))
      (page-error!
       "Recursive operator cursor uses an incompatible execution ABI."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :operator-abi-mismatch}))
    (when-not (and (= (:fingerprint plan) (:fingerprint bound))
                   (= (:fingerprint cover-plan)
                      (:cover-fingerprint bound))
                   (= (force scope-delay) (:semantic-scope bound)))
      (page-error!
       "Recursive operator cursor is bound to an incompatible semantic scope."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :operator-scope-mismatch}))
    (when-not (= traversal (:traversal bound))
      (page-error!
       "Recursive operator cursor traversal does not match the request."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :actual (:traversal bound)}))
    (when-not (and (= :progress (:anchor bound))
                   (map? (:cover-edge bound)))
      (page-error!
       "Recursive operator cursor boundary is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-boundary}))))

(defn- validate-least-path-bound!
  [plan traversal bound]
  (when bound
    (when-not (= :least-path-edge (:kind bound))
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
    (when-not (= :progress (:anchor bound))
      (page-error!
       "Cursor progress anchor is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-progress-anchor}))
    (when-not (and (vector? (:coords bound))
                   (seq (:coords bound))
                   (every? integer? (:coords bound)))
      (page-error!
       "Cursor boundary is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-boundary}))))

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
    (when-not (= :progress (:anchor bound))
      (page-error!
       "Cursor progress anchor is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-progress-anchor}))
    (when-not (and (integer? (:ordinal bound))
                   (pos? (:ordinal bound))
                   (some? (:result-eid bound)))
      (page-error!
       "Cursor boundary is malformed."
       {:eacl/error :eacl.pagination/invalid-cursor
        :reason :malformed-boundary}))))

(defn- saturating-add
  [limit left right]
  (if (> right (- limit left))
    limit
    (+ left right)))

(defn- saturating-multiply
  [limit factor value]
  (if (> value (quot limit factor))
    limit
    (* factor value)))

(defn ^:no-doc stable-limits
  "Maps the public recursive-traversal limits onto the stable reducer's
  budgets with matching semantics: derived grants bound unique logical
  admissions, advanced datoms bound consumed projection values, and
  queued work bounds INSTANTANEOUS stack depth (never cumulative
  transitions — a long chain traversal legitimately takes many
  transitions while its queue stays shallow). Cumulative transition and
  command counts remain internal reducer safety ceilings."
  []
  (let [{:keys [max-derived-grants max-advanced-datoms max-queued-work]}
        *recursive-traversal-limits*
        authorized-work
        (saturating-add backend/maximum-exact-integer
                        (or max-derived-grants 0)
                        (or max-advanced-datoms 0))]
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
                  (saturating-multiply backend/maximum-exact-integer
                                       4 authorized-work))
             :max-commands
             (max stable-reducer/default-max-commands
                  authorized-work)))))

(defn- recursive-operator-limits []
  (let [{:keys [max-derived-grants max-advanced-datoms max-queued-work]}
        *recursive-traversal-limits*]
    (cond-> {}
      max-derived-grants
      (assoc :maximum-questions max-derived-grants
             :maximum-facts max-derived-grants
             :maximum-anchor-states max-derived-grants)
      max-advanced-datoms
      (assoc :maximum-values max-advanced-datoms)
      max-queued-work
      (assoc :maximum-queue max-queued-work))))

(def ^:private stable-limit-kinds
  {:max-admissions :derived-grants
   :max-values :advanced-datoms
   :max-stack :queued-work
   ;; Internal safety ceilings surface under the closest public kind.
   :max-commands :advanced-datoms
   :max-transitions :queued-work})

(def ^:private recursive-limit-kinds
  {:questions :derived-grants
   :facts :derived-grants
   :anchor-states :derived-grants
   :join-slots :derived-grants
   :join-words :derived-grants
   :components :derived-grants
   :strata :derived-grants
   :checkpoint-weight :derived-grants
   :commands :advanced-datoms
   :values :advanced-datoms
   :probes :advanced-datoms
   :transitions :queued-work
   :queue :queued-work})

(defn- with-public-limit-errors
  "The stable reducer's typed limit failure surfaces under the public
  recursive-traversal error key; when the caller observes work stats, the
  reducer reports its per-run deltas under the public counter names."
  [thunk]
  (try
    (binding [stable-reducer/*observer-stats* *recursive-traversal-stats*
              stable-reducer/*reducer-work-stats* *aggregate-work-stats*]
      (thunk))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) error
      (if (contains? #{:eacl.reducer/limit-exceeded
                       :eacl.page/resource-exhausted
                       :eacl.operator/recursive-limit-exceeded}
                     (:eacl/error (ex-data error)))
        ;; The public shape is exactly {:eacl/error :limit-kind :limit}
        ;; with :limit as the caller's configured numeric ceiling; internal
        ;; counters and reducer budget keys never leak.
        (let [data (ex-data error)
              recursive? (= :eacl.operator/recursive-limit-exceeded
                            (:eacl/error data))
              budget-key (:limit data)
              limit-kind
              (if recursive?
                (get recursive-limit-kinds (:dimension data)
                     :derived-grants)
                (get stable-limit-kinds budget-key :derived-grants))
              configured-limit
              (if recursive?
                (get *recursive-traversal-limits*
                     (case limit-kind
                       :advanced-datoms :max-advanced-datoms
                       :queued-work :max-queued-work
                       :max-derived-grants))
                (get data budget-key))]
          (page-error!
           "Recursive traversal exceeded its configured limits."
           {:eacl/error :eacl.recursive-traversal/limit-exceeded
            :limit-kind limit-kind
            :limit configured-limit}))
        (throw error)))))

(defn- stable-cut-point
  "Execution enforcement (deadline and cancellation) for the routed stable
  engine: one bounded check per reducer transition (and, on the point-check
  route, before every adapter command), installed only when the caller runs
  under an execution contract so raw local evaluation keeps a bare hot
  path."
  []
  (when-let [contract execution/*contract*]
    (physical/execution-cut-point contract)))

(def ^:dynamic *service-admission*
  "The client's service-edge admission (an atom from
  `eacl.engine.physical/make-service-admission`), bound per request by the
  public clients from their `:service-admission` option. nil disables the
  bulkhead and the replay ledger."
  nil)

(def default-physical-attempts
  "Attempts per read-demand descriptor for `:retryable` adapter failures on
  the routed path (the original absolute deadline still bounds every retry)."
  3)

(defn- routed-fetch-fn
  "The classification/retry envelope shared by the reducer and least-path
  physical read paths; `raw-fetch-fn` selects the adapter seam."
  [raw-fetch-fn]
  (let [attempts (when *recursive-traversal-stats* (atom 0))]
    {:fetch-fn (physical/retrying-fetch-fn
                raw-fetch-fn
                {:max-attempts default-physical-attempts
                 :deadline-nanos (:deadline-nanos execution/*contract*)
                 :attempts attempts})
     :attempts attempts}))

(defn- stable-fetch-fn
  "The routed physical read path (bounded-physical-execution): the adapter
  scan realized inside the three-outcome classification boundary (complete
  | classified failure with a cause | cancelled — typed EACL errors pass
  through unwrapped and unretried) and retried for `:retryable` failures
  under the request's original absolute deadline. Returns the fetch-fn and
  the attempt counter that feeds `:adapter-attempts` in the observer stats."
  [db]
  (routed-fetch-fn (stable-reducer/adapter-fetch-fn db)))

(defn- least-path-fetch-fn
  "The routed physical read path for the least-path evaluator: identical
  classification/retry envelope to `stable-fetch-fn`, over the
  direction-aware seam (descending windows issue :desc scans)."
  [db]
  (routed-fetch-fn (least-path/adapter-fetch-fn db)))

(defn- report-adapter-attempts!
  [attempts]
  (when-let [stats (and attempts *recursive-traversal-stats*)]
    (swap! stats update :adapter-attempts (fnil + 0) @attempts))
  nil)

(defn- report-least-path-run!
  "The least-path evaluator's per-run work deltas for
  *recursive-traversal-stats*, mirroring the reducer's report: emissions
  are the logical admissions (:derived-grants) and commands the physical
  commands (:advanced-datoms); :stream-opens has no reducer analog and
  reports under its own name."
  [run]
  (request-counters/add-commands!
   (get-in run [:counters :commands] 0))
  (request-counters/add-fetched-values!
   (get-in run [:counters :fetched-values] 0))
  (let [{:keys [emissions commands fetched-values stream-opens]}
        (:counters run)]
    (stable-reducer/report-work-stats!
     [*recursive-traversal-stats* *aggregate-work-stats*]
     {:derived-grants (or emissions 0)
      :advanced-datoms (or commands 0)
      :fetched-values (or fetched-values 0)
      :stream-opens (or stream-opens 0)}))
  nil)

(defn- with-service-admission
  "Runs `thunk` holding one enumeration slot when the client configured a
  service-edge bulkhead; the slot is held for the full synchronous duration
  of the routed work (at width one the enumeration is the physical call
  chain)."
  [thunk]
  (physical/with-admission *service-admission* thunk))

(defn ^:no-doc stable-checkpoints
  "Accepts a standard-cache-backed stable-page checkpoint store or the client's
  scoped continuation context; anything else degrades to deterministic
  replay."
  [cache]
  (cond
    (and (map? cache)
         (true? (:opaque-values? cache))
         (fn? (:get cache))
         (fn? (:hit! cache))
         (fn? (:miss! cache))
         (fn? (:put! cache)))
    cache

    (stable-page/checkpoint-store? cache)
    cache))

(defn checkpoint-key
  "Returns the frame-scoped private checkpoint identity for a sealed plan.

  A key exists only when the request owns both its canonical lineage and a
  complete ordered-generation frame over every relation the reducer may read.
  Native revision is deliberately absent: equal frames in one lineage denote
  equal plan slices and therefore equal history-free reducer state. An
  unavailable frame disables acceleration and leaves deterministic replay as
  the correctness path."
  [plan traversal subject-type anchor-eid page-size]
  (when (and *request-lineage* *request-frame*)
    (when-let [frame (force *request-frame*)]
      [*request-lineage*
       frame
       (:fingerprint plan)
       traversal
       subject-type
       anchor-eid
       page-size])))

(defn- stable-items
  [plan traversal result-type start-ordinal eids]
  (mapv
   (fn [offset eid]
     {:node (spice-object result-type eid)
      :cursor (stable-edge plan traversal
                           (+ start-ordinal offset 1) eid)})
   (range)
   eids))

(defn- bound-result-eid
  [bound]
  (case (:kind bound)
    :stable-edge (:result-eid bound)
    :least-path-edge (peek (:coords bound))
    nil))

(defn- fetch-inclusive-candidates
  "Replays an accepted sentinel once on the next filtered page.

  The encrypted progress edge still names the last examined candidate; the
  inclusive marker tells this layer to present that candidate once before it
  resumes the ordinary exclusive authorized stream."
  [result-type fetch-exclusive bound limit]
  (if (:resume-inclusive? bound)
    (let [exclusive-bound (dissoc bound :resume-inclusive?)
          boundary-eid (bound-result-eid exclusive-bound)]
      (when-not (some? boundary-eid)
        (page-error!
         "Filtered lookup cursor has no result identity."
         {:type :eacl.pagination/invalid-cursor
          :eacl/error :eacl.pagination/invalid-cursor
          :reason :malformed-inclusive-boundary}))
      (into [{:node (spice-object result-type boundary-eid)
              :cursor exclusive-bound}]
            (when (> limit 1)
              (fetch-exclusive exclusive-bound (dec limit)))))
    (fetch-exclusive bound limit)))

(defn- execute-filtered-lookup-window
  [result-type {:keys [direction size bound]}
   {:keys [candidate-window accept? accept-many? maximum-batch-width]}
   fetch-exclusive]
  (when-not (and (integer? candidate-window) (pos? candidate-window))
    (page-error!
     "The authorization candidate window must be a positive integer."
     {:type :eacl.execution/resource-limit-exceeded
      :eacl/error :eacl.execution/resource-limit-exceeded
      :limit-kind :candidate-window
      :value candidate-window}))
  (when-not (or (fn? accept?) (fn? accept-many?))
    (page-error!
     "A filtered lookup window requires an accept predicate."
     {:type :eacl/invalid-config :eacl/error :eacl/invalid-config}))
  (when-not (or (nil? maximum-batch-width)
                (and (integer? maximum-batch-width)
                     (pos? maximum-batch-width)))
    (page-error!
     "The filtered lookup batch width must be a positive integer."
     {:type :eacl.execution/resource-limit-exceeded
      :eacl/error :eacl.execution/resource-limit-exceeded
      :limit-kind :maximum-batch-width
      :value maximum-batch-width}))
  (let [fetch-candidates
        #(fetch-inclusive-candidates result-type fetch-exclusive %1 %2)
        result
        (loop [cursor bound
               examined 0
               accepted []
               last-examined nil
               exhausted? false]
          (cond
            (= (count accepted) (inc size))
            {:accepted accepted
             :last-examined last-examined
             :more? true
             :sentinel? true
             :bounded? false}

            exhausted?
            {:accepted accepted
             :last-examined last-examined
             :more? false
             :sentinel? false
             :bounded? false}

            (= examined candidate-window)
            (let [more? (boolean
                         (seq (fetch-exclusive cursor 1)))]
              {:accepted accepted
               :last-examined last-examined
               :more? more?
               :sentinel? false
               :bounded? more?})

            :else
            (let [remaining-window (- candidate-window examined)
                  remaining-sentinel (- (inc size) (count accepted))
                  chunk-limit (min remaining-window remaining-sentinel
                                   (or maximum-batch-width
                                       remaining-window))
                  chunk (vec (fetch-candidates cursor chunk-limit))
                  decisions
                  (if accept-many?
                    (let [values (vec (accept-many? (mapv :node chunk)))]
                      (when-not (and (= (count chunk) (count values))
                                     (every? boolean? values))
                        (page-error!
                         "A filtered lookup batch returned malformed decisions."
                         {:type :eacl/backend-contract-violation
                          :eacl/error :eacl/backend-contract-violation
                          :obligation :aligned-filter-decisions}))
                      values)
                    (mapv #(boolean (accept? (:node %))) chunk))
                  [accepted examined last-examined]
                  (reduce
                   (fn [[accepted examined _] [candidate accepted?]]
                     (request-counters/add-candidates-examined!)
                     [(cond-> accepted
                        accepted?
                        (conj candidate))
                      (inc examined)
                      candidate])
                   [accepted examined last-examined]
                   (map vector chunk decisions))
                  last-cursor (some-> last-examined :cursor)]
              (recur last-cursor examined accepted last-examined
                     (< (count chunk) chunk-limit)))))
        selected-direction (vec (take size (:accepted result)))
        selected (if (= :desc direction)
                   (vec (reverse selected-direction))
                   selected-direction)
        progress
        (cond-> (some-> (:last-examined result) :cursor)
          (:sentinel? result) (assoc :resume-inclusive? true))
        first-selected (some-> selected first :cursor)
        last-selected (some-> selected last :cursor)
        start-cursor (case direction
                       :asc (or first-selected progress)
                       :desc progress)
        end-cursor (case direction
                     :asc progress
                     :desc (or last-selected progress))]
    {:data (mapv :node selected)
     :page-info
     {:start-cursor start-cursor
      :end-cursor end-cursor
      :has-next-page?
      (case direction
        :asc (boolean (:more? result))
        :desc (boolean bound))
      :has-previous-page?
      (case direction
        :asc (boolean bound)
        :desc (boolean (:more? result)))
      :bounded? (boolean (:bounded? result))}}))

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

(declare first-discovery-lookup-page)

(defn- operator-lookup-page
  [db plan traversal {:keys [direction size bound]}
   result-type anchor subject-type candidate-filter]
  (let [cover-plan (stable-cover-plan db plan)
        proof-identity (operator-snapshot-proof-identity db)
        scope-delay (delay (operator-cursor-scope/digest
                            plan cover-plan traversal proof-identity))
        edge (fn [coords]
               (operator-edge plan cover-plan traversal
                              (force scope-delay) coords))
        _ (validate-operator-bound! plan cover-plan traversal
                                    scope-delay bound)
        anchor-eid (object-eid db (:id anchor))]
    (if (nil? anchor-eid)
      {:data []
       :page-info {:start-cursor nil :end-cursor nil
                   :has-next-page? false :has-previous-page? false
                   :bounded? false}}
      (let [accept? (:accept? candidate-filter)
            candidate-window (or (:candidate-window candidate-filter)
                                 operator-lookup/default-candidate-window)
            run
            (with-stale-boundary-errors
              bound
              (fn []
                (with-public-limit-errors
                  #(with-service-admission
                     (fn []
                       (operator-lookup/lookup-page
                        {:adapter db
                         :plan plan
                         :cover-plan cover-plan
                         :traversal traversal
                         :subject-type subject-type
                         :anchor-eid anchor-eid
                         :page-size size
                         :candidate-window candidate-window
                         :order-direction direction
                         :boundary (:coords bound)
                         :scope-identity proof-identity
                         :accept-result?
                         (when accept?
                           (fn [eid]
                             (accept? (spice-object result-type eid))))
                         :cut-point! (stable-cut-point)
                         :traversal-limits (stable-limits)}))))))
            emissions (:emissions run)
            ordered (if (= :desc direction)
                      (vec (reverse emissions)) emissions)
            items
            (mapv (fn [{:keys [value coords]}]
                    {:node (spice-object result-type value)
                     :cursor (edge coords)})
                  ordered)
            progress
            (some-> (:resume-coords run) edge)
            first-selected (some-> items first :cursor)
            last-selected (some-> items last :cursor)
            start-cursor (if (= :asc direction)
                           (or first-selected progress)
                           progress)
            end-cursor (if (= :asc direction)
                         progress
                         (or last-selected progress))]
        (report-least-path-run! run)
        {:data (mapv :node items)
         :page-info
         {:start-cursor start-cursor
          :end-cursor end-cursor
          :has-next-page?
          (if (= :asc direction)
            (boolean (:has-more? run))
            (boolean bound))
          :has-previous-page?
          (if (= :asc direction)
            (boolean bound)
            (boolean (:has-more? run)))
          :bounded? (boolean (:bounded? run))}}))))

(defn- least-path-lookup-page
  "Keyset pagination for an acyclic plan: ascending pages resume strictly
  past the boundary coordinates; :before/:last run descending and return
  the window in canonical forward order. No checkpoint store is
  consulted and no replay exists (acyclic-keyset-pagination)."
  [db plan traversal query {:keys [direction size bound]}
   result-type anchor subject-type]
  (validate-least-path-bound! plan traversal bound)
  (let [anchor-eid (object-eid db (:id anchor))]
    (if (nil? anchor-eid)
      (page-response {:items [] :has-next? false
                      :has-previous? (boolean bound)})
      (let [{:keys [fetch-fn attempts]} (least-path-fetch-fn db)
            descending? (or (= :desc direction) (some? (:before query)))
            run-options
            (merge
             (stable-limits)
             {:plan plan
              :fetch-fn fetch-fn
              :subject-type subject-type
              :page-size size
              :cut-point! (stable-cut-point)}
             (if (= :forward traversal)
               {:subject-eid anchor-eid}
               {:resource-eid anchor-eid})
             (cond
               (and bound (= :asc direction))
               {:after-coords (:coords bound)}
               (and bound (= :desc direction))
               {:before-coords (:coords bound)}
               (= :desc direction)
               {:last? true}
               :else {}))
            ;; Coordinate resume that cannot reproduce against this plan
            ;; (arity, ordinal, or emptiness defects) surfaces as the
            ;; public stale-cursor error, exactly like the replay route.
            run (with-stale-boundary-errors
                  bound
                  (fn []
                    (with-public-limit-errors
                      #(with-service-admission
                         (fn []
                           (if (= :forward traversal)
                             (least-path/forward-page run-options)
                             (least-path/reverse-page run-options)))))))
            emissions (:emissions run)
            ;; Descending runs return descending coordinates; the public
            ;; page is canonical forward order in both modes.
            ordered (if descending? (vec (reverse emissions)) emissions)
            items (mapv (fn [{:keys [value coords]}]
                          {:node (spice-object result-type value)
                           :cursor (least-path-edge plan traversal coords)})
                        ordered)]
        (report-least-path-run! run)
        (report-adapter-attempts! attempts)
        (page-response
         {:items items
          :has-next? (if descending?
                       (boolean bound)
                       (boolean (:has-more? run)))
          :has-previous? (if descending?
                           (boolean (:has-more? run))
                           (boolean bound))})))))

(defn- filtered-lookup-page
  [db plan traversal query {:keys [direction size bound] :as page-req}
   cache-fn result-type anchor subject-type candidate-filter]
  (let [least-path? (= :least-path (:order-mode plan))
        _ (if least-path?
            (validate-least-path-bound! plan traversal bound)
            (validate-stable-bound! plan traversal bound))
        _ (when (and (not least-path?)
                     (:recursive? plan)
                     (= :demand *evaluation-mode*)
                     (= :desc direction)
                     (nil? bound))
            (complete-evaluation-required! query))
        anchor-eid (object-eid db (:id anchor))]
    (if (nil? anchor-eid)
      {:data []
       :page-info {:start-cursor nil
                   :end-cursor nil
                   :has-next-page? false
                   :has-previous-page? false
                   :bounded? false}}
      (let [{:keys [fetch-fn attempts]}
            ((if least-path? least-path-fetch-fn stable-fetch-fn) db)
            cache (when-not least-path?
                    (when cache-fn (cache-fn)))
            checkpoints (stable-checkpoints cache)
            checkpoint-key
            (when checkpoints
              (checkpoint-key
               plan traversal subject-type anchor-eid size))
            fetch-exclusive
            (fn [candidate-bound limit]
              (if least-path?
                (let [run-options
                      (merge
                       (stable-limits)
                       {:plan plan
                        :fetch-fn fetch-fn
                        :subject-type subject-type
                        :page-size limit
                        :raw-candidates? true
                        :cut-point! (stable-cut-point)}
                       (if (= :forward traversal)
                         {:subject-eid anchor-eid}
                         {:resource-eid anchor-eid})
                       (cond
                         (and candidate-bound (= :asc direction))
                         {:after-coords (:coords candidate-bound)}

                         (and candidate-bound (= :desc direction))
                         {:before-coords (:coords candidate-bound)}

                         (= :desc direction)
                         {:last? true}

                         :else {}))
                      run
                      (with-stale-boundary-errors
                        candidate-bound
                        (fn []
                          (with-public-limit-errors
                            #(with-service-admission
                               (fn []
                                 (if (= :forward traversal)
                                   (least-path/forward-page run-options)
                                   (least-path/reverse-page run-options)))))))
                      items
                      (mapv
                       (fn [{:keys [value coords]}]
                         {:node (spice-object result-type value)
                          :cursor (least-path-edge plan traversal coords)})
                       (:emissions run))]
                  (report-least-path-run! run)
                  ;; Raw descending least-path emissions are already in
                  ;; examination order.
                  items)
                (let [edge
                      (when candidate-bound
                        {:ordinal (:ordinal candidate-bound)
                         :eid (:result-eid candidate-bound)})
                      result
                      (with-stale-boundary-errors
                        candidate-bound
                        (fn []
                          (with-public-limit-errors
                            #(with-service-admission
                               (fn []
                                 (stable-page/edge-page
                                  (merge
                                   (stable-limits)
                                   {:adapter db
                                    :basis-identity
                                    (:basis-identity *proof-frame*)
                                    :fetch-fn fetch-fn
                                    :plan plan
                                    :direction traversal
                                    :anchor-eid anchor-eid
                                    :subject-type subject-type
                                    :cut-point! (stable-cut-point)
                                    :page-size limit
                                    :raw-candidates? true
                                    :after (when (= :asc direction) edge)
                                    :before (when (= :desc direction) edge)
                                    :last-window?
                                    (and (= :desc direction) (nil? edge))
                                    :checkpoints checkpoints
                                    :service-admission *service-admission*
                                    :checkpoint-key checkpoint-key})))))))
                      items
                      (stable-items plan traversal result-type
                                    (:start-ordinal result) (:eids result))]
                  ;; Stable-page returns canonical order for both directions;
                  ;; filtering examines backward windows in reverse order.
                  (if (= :desc direction)
                    (vec (reverse items))
                    items))))
            page
            (execute-filtered-lookup-window
             result-type page-req candidate-filter fetch-exclusive)]
        (report-adapter-attempts! attempts)
        page))))

(defn- recursive-operator-lookup-page
  "Enumerates the sealed recursive cover and evaluates exact recursive
  operator membership in bounded aligned vectors. The public cursor advances
  only through the cover edge actually examined; physical predicate batching
  is never cursor progress."
  [db plan traversal query {:keys [bound] :as page-req} cache-fn
   result-type anchor subject-type candidate-filter]
  (let [cover-plan (stable-cover-plan db plan)
        proof-identity (operator-snapshot-proof-identity db)
        scope-delay (delay (operator-cursor-scope/digest
                            plan cover-plan traversal proof-identity))
        recursive-edge (fn [cover-edge]
                         (recursive-operator-edge
                          plan cover-plan traversal
                          (force scope-delay) cover-edge))
        _ (validate-recursive-operator-bound!
           plan cover-plan traversal scope-delay bound)
        anchor-eid (object-eid db (:id anchor))]
    (if (nil? anchor-eid)
      {:data []
       :page-info {:start-cursor nil
                   :end-cursor nil
                   :has-next-page? false
                   :has-previous-page? false
                   :bounded? false}}
      (let [external-accept? (:accept? candidate-filter)
            external-accept-many? (:accept-many? candidate-filter)
            evaluate-batch
            (fn [nodes]
              (let [candidates
                    (mapv
                     (fn [node]
                       {:direction traversal
                        :subject-type subject-type
                        :subject-eid (if (= :forward traversal)
                                       anchor-eid (:id node))
                        :resource-eid (if (= :forward traversal)
                                        (:id node) anchor-eid)})
                     nodes)
                    recursive-decisions
                    (:decisions
                     (with-public-limit-errors
                       #(with-service-admission
                          (fn []
                            (operator-recursive/evaluate-cached-many
                             {:adapter db
                              :plan plan
                              :candidates candidates
                              :scope-identity proof-identity
                              :limits (recursive-operator-limits)})))))
                    external-decisions
                    (cond
                      external-accept-many?
                      (vec (external-accept-many? nodes))

                      external-accept?
                      (mapv #(boolean (external-accept? %)) nodes)

                      :else
                      (vec (repeat (count nodes) true)))]
                (mapv #(and %1 %2)
                      recursive-decisions external-decisions)))
            cover-page
            (filtered-lookup-page
             db cover-plan traversal query
             (assoc page-req :bound (:cover-edge bound))
             cache-fn result-type anchor subject-type
             {:candidate-window
              (or (:candidate-window candidate-filter)
                  operator-lookup/default-candidate-window)
              :maximum-batch-width operator-batch-schedule/maximum-width
              :accept-many? evaluate-batch})]
        (update
         cover-page :page-info
         (fn [page-info]
           (reduce
            (fn [result field]
              (update result field #(when % (recursive-edge %))))
            page-info [:start-cursor :end-cursor])))))))

(defn- stable-lookup-page
  "`cache-fn` is a thunk producing the continuation cache (or nil): the
  least-path route consults no continuation state, so the context —
  query canonicalization, proof-frame resolution, its backend reads —
  must only be built when the plan actually routes to first-discovery."
  [db traversal query cache-fn candidate-filter]
  (let [{:keys [bound] :as page-req} (normalize-page-request query)
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
            plan (stable-plan db root-node)]
        (if (operator-plan/operator-plan? plan)
          (if (operator-recursive/recursive-plan? plan)
            (recursive-operator-lookup-page
             db plan traversal query page-req cache-fn result-type anchor
             subject-type candidate-filter)
            (operator-lookup-page
             db plan traversal page-req result-type anchor subject-type
             candidate-filter))
          (if candidate-filter
            (filtered-lookup-page
             db plan traversal query page-req cache-fn result-type anchor
             subject-type candidate-filter)
            (if (= :least-path (:order-mode plan))
              (least-path-lookup-page db plan traversal query page-req
                                      result-type anchor subject-type)
              (first-discovery-lookup-page
               db plan traversal query page-req cache-fn result-type anchor
               subject-type))))))))

(defn- first-discovery-lookup-page
  [db plan traversal query {:keys [direction size bound]} cache-fn
   result-type anchor subject-type]
  (let [cache (when cache-fn (cache-fn))
        checkpoints (stable-checkpoints cache)
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
        {:keys [fetch-fn attempts]} (stable-fetch-fn db)
        result (with-stale-boundary-errors
                 bound
                 (fn []
                   (with-public-limit-errors
                     #(with-service-admission
                        (fn []
                          (stable-page/edge-page
                           (merge
                            (stable-limits)
                            {:adapter db
                             :basis-identity
                             (:basis-identity *proof-frame*)
                             :fetch-fn fetch-fn
                             :plan plan
                             :direction traversal
                             :anchor-eid anchor-eid
                             :subject-type subject-type
                             :cut-point! (stable-cut-point)
                             :page-size size
                             :after (when (= :asc direction) edge)
                             :before (when (and (= :desc direction) edge) edge)
                             :last-window? (and (= :desc direction) (nil? edge))
                             :checkpoints checkpoints
                             :service-admission *service-admission*
                             :checkpoint-key
                             (when checkpoints
                               (checkpoint-key
                                plan traversal subject-type anchor-eid
                                size))})))))))]
    (report-adapter-attempts! attempts)
    (page-response
     {:items (stable-items plan traversal result-type
                           (:start-ordinal result) (:eids result))
      :has-next? (:has-next? result)
      :has-previous? (:has-previous? result)})))

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
        (if (operator-plan/operator-plan? plan)
          (with-public-limit-errors
            #(with-service-admission
               (fn []
                 (let [recursive? (operator-recursive/recursive-plan? plan)
                       base-options
                       {:adapter db :plan plan
                        :subject-type subject-type
                        :subject-eid subject-eid
                        :resource-eid resource-eid}
                       options
                       (if recursive?
                         (assoc base-options
                                :direction :forward
                                :scope-identity
                                (operator-snapshot-proof-identity db)
                                :limits (recursive-operator-limits))
                         base-options)]
                   (if recursive?
                     (operator-recursive/check-cached-eids options)
                     (first
                      (operator-vector/check-cached-many-eids
                       {:adapter db :plan plan
                        :scope-identity
                        (operator-snapshot-proof-identity db)
                        :candidates
                        [{:direction :forward
                          :subject-type subject-type
                          :subject-eid subject-eid
                          :resource-type resource-type
                          :resource-eid resource-eid}]})))))))
          (let [{:keys [fetch-fn attempts]} (stable-fetch-fn db)
                allowed?
                (with-public-limit-errors
                  #(with-service-admission
                     (fn []
                       (stable-route/check-eids
                        (merge (stable-limits)
                               {:adapter db
                                :fetch-fn fetch-fn
                                :plan plan
                                :subject-type subject-type
                                :subject-eid subject-eid
                                :resource-eid resource-eid
                                :cut-point! (stable-cut-point)})))))]
            (report-adapter-attempts! attempts)
            allowed?)))
      false)))
(defn lookup-resources
  "Stable-discovery forward pagination.

  Raw-impl callers must hold one DB value for a whole exact-snapshot walk; the
  public client authenticates and scopes cursor state before it reaches here."
  ([db query]
   (lookup-resources db query nil))
  ([db query {:keys [continuation-cache continuation-cache-fn
                     candidate-filter]}]
   ;; Deferred: an acyclic (least-path) plan never touches continuation
   ;; state, so the cache context is only built on the recursive route.
   (let [cache-fn (fn [] (or continuation-cache
                             (when continuation-cache-fn
                               (continuation-cache-fn))))]
     (stable-lookup-page db :forward query cache-fn candidate-filter))))

(defn lookup-subjects
  "Stable-discovery reverse pagination; cursors are only valid against the
  minting db basis."
  ([db query]
   (lookup-subjects db query nil))
  ([db query {:keys [continuation-cache continuation-cache-fn
                     candidate-filter]}]
   {:pre [(:type (:resource query)) (:id (:resource query))]}
   (when (:subject/relation query)
     ;; Returning subjects while silently ignoring this filter would be
     ;; unsound, so the v8 contract rejects it.
     (page-error! ":subject/relation is not supported by lookup-subjects."
                  {:eacl/error :eacl.pagination/unsupported-filter
                   :filter :subject/relation}))
   (let [cache-fn (fn [] (or continuation-cache
                             (when continuation-cache-fn
                               (continuation-cache-fn))))]
     (stable-lookup-page db :reverse query cache-fn candidate-filter))))

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

(defn- recursive-operator-count
  "Streams exact recursive operator pages with bounded retained continuation
  state. A bounded count asks for precisely its limit plus one lookahead;
  an exact count remains explicitly exhaustive."
  [db plan traversal query result-type anchor subject-type count-limit]
  (let [target (when (some? count-limit) (inc count-limit))
        continuation-cache (stable-page/make-checkpoint-store)]
    (loop [bound nil
           accumulated 0]
      (execution/check! execution/*contract*
                        :operator-recursive/count-page
                        {:count accumulated})
      (let [remaining (when target (- target accumulated))
            page-size (if remaining
                        (min operator-batch-schedule/maximum-width remaining)
                        operator-batch-schedule/maximum-width)
            page
            (recursive-operator-lookup-page
             db plan traversal query
             {:direction :asc :size page-size :bound bound}
             (constantly continuation-cache)
             result-type anchor subject-type nil)
            next-count (+ accumulated (count (:data page)))
            more? (get-in page [:page-info :has-next-page?])
            next-bound (get-in page [:page-info :end-cursor])]
        (cond
          (and target (>= next-count target))
          {:count count-limit :truncated? true}

          more?
          (do
            (when-not next-bound
              (page-error!
               "Recursive count continuation made no cursor progress."
               {:type :eacl.page/invalid-cursor
                :eacl/error :eacl.page/invalid-cursor}))
            (recur next-bound next-count))

          :else
          {:count next-count :truncated? false})))))

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
         (if (operator-plan/operator-plan? plan)
           (if-let [subject-eid (object-eid db (:id subject))]
             (if (operator-recursive/recursive-plan? plan)
               (recursive-operator-count
                db plan :forward query result-type subject
                (:type subject) limit)
               (select-keys
                (with-public-limit-errors
                  #(with-service-admission
                     (fn []
                       (operator-lookup/count-results
                        {:adapter db :plan plan :traversal :forward
                         :subject-type (:type subject)
                         :anchor-eid subject-eid :count-limit limit
                         :scope-identity
                         (operator-snapshot-proof-identity db)
                         :cut-point! (stable-cut-point)
                         :traversal-limits (stable-limits)}))))
                [:count :truncated?]))
             {:count 0 :truncated? false})
           (let [{:keys [fetch-fn attempts]} (stable-fetch-fn db)
                 counted
                 (with-public-limit-errors
                   #(with-service-admission
                      (fn []
                        (stable-route/count-resources
                         (merge (stable-limits)
                                {:adapter db
                                 :fetch-fn fetch-fn
                                 :plan plan
                                 :subject-type (:type subject)
                                 :subject-id (:id subject)
                                 :count-limit limit
                                 :cut-point! (stable-cut-point)})))))]
             (report-adapter-attempts! attempts)
             (select-keys counted [:count :truncated?])))))
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
         (if (operator-plan/operator-plan? plan)
           (if-let [resource-eid (object-eid db (:id resource))]
             (if (operator-recursive/recursive-plan? plan)
               (recursive-operator-count
                db plan :reverse query (:subject/type query) resource
                (:subject/type query) limit)
               (select-keys
                (with-public-limit-errors
                  #(with-service-admission
                     (fn []
                       (operator-lookup/count-results
                        {:adapter db :plan plan :traversal :reverse
                         :subject-type (:subject/type query)
                         :anchor-eid resource-eid :count-limit limit
                         :scope-identity
                         (operator-snapshot-proof-identity db)
                         :cut-point! (stable-cut-point)
                         :traversal-limits (stable-limits)}))))
                [:count :truncated?]))
             {:count 0 :truncated? false})
           (let [{:keys [fetch-fn attempts]} (stable-fetch-fn db)
                 counted
                 (with-public-limit-errors
                   #(with-service-admission
                      (fn []
                        (stable-route/count-subjects
                         (merge (stable-limits)
                                {:adapter db
                                 :fetch-fn fetch-fn
                                 :plan plan
                                 :subject-type (:subject/type query)
                                 :resource-id (:id resource)
                                 :count-limit limit
                                 :cut-point! (stable-cut-point)})))))]
             (report-adapter-attempts! attempts)
             (select-keys counted [:count :truncated?])))))
     limit)))
