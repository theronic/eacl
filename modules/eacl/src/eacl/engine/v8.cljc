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
(def recursive-cursor-version 1)
(def recursive-order-abi 2)
(def ^:private projection-key-version 2)
(def ^:dynamic *backend-work-stats*
  "Optional atom populated by tests, benchmarks, and diagnostic callers.

  Counts backend operations actually invoked by the engine. Cache layers
  record avoided work separately; keeping executed and avoided counters
  distinct prevents a cache hit from being mistaken for database work."
  nil)

(def ^:dynamic *acyclic-work-stats*
  "Optional atom populated by deterministic acyclic list/count gates.

  These counters are deliberately separate from recursive traversal limits
  and counters. A certified acyclic request must never look recursive merely
  because it scans a large, valid authorization set."
  nil)

(def ^:dynamic *recursive-traversal-stats*
  "Optional atom populated by tests, benchmarks, and diagnostic callers.

  Counts recursive-traversal work only. Request-shape observers live in
  *request-shape-stats* so the enumeration-routing invariant — an
  acyclic route performs ZERO recursive work — stays assertable as
  (empty? @stats). Observation-only."
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

(def ^:dynamic *acyclic-route?* false)

(def ^:dynamic *inactive-recursive-cycle-guards*
  "In-SCC arrow prefixes proven empty in the selected snapshot.

  The generated routing decision permits the acyclic evaluator only when
  every such guard is empty. Binding the exact guard keys here erases their
  recursive contributions from the executable path, matching
  GuardedRecursiveDenotation in AcyclicEngine.dfy."
  #{})

(defn- add-acyclic-work!
  [counter amount]
  (when *acyclic-work-stats*
    (swap! *acyclic-work-stats* update counter (fnil + 0) amount))
  nil)

(defn- record-backend-work!
  [operation]
  (when *acyclic-route?*
    (add-acyclic-work! :backend-scans 1)
    (add-acyclic-work! operation 1))
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

(defn- host-normalize-page-request
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
  (when-let [unsupported
             (some #(when (contains? query %) %) [:cursor :limit])]
    (page-error!
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

(defn schema-version-stamp
  "String form of the schema proof visible in a snapshot."
  [snapshot]
  (some-> (schema-version snapshot) str))

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
    :traversal-analysis (atom nil)
    :recursive-cycle-guards (atom {})
    :relationship-dependencies (atom {})
    :recursive-plans (atom {})
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
   :recursive-plans (atom {})
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
   (some-> (:recursive-cycle-guards schema-cache) (reset! {}))
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

(defn- calc-recursive-cycle-guards
  "Returns the relationship prefixes that can carry a recursive SCC edge.

  Same-resource permission aliases are unguarded positive unions; the acyclic
  evaluator's visited set computes their reachable base grants exactly.
  An arrow-permission edge can add recursive grants only when its via relation
  has at least one tuple in the selected snapshot, so those in-SCC arrows are
  the complete data-dependent cycle guards."
  [db resource-type permission-name]
  (let [root (permission-query-node resource-type permission-name)
        nodes (sort (reachable-permission-query-nodes db root))
        graph (permission-graph db nodes)
        components (mapv (comp vec sort)
                         (graph-components nodes graph))
        component-by-node
        (into {}
              (mapcat
               (fn [component]
                 (map #(vector % component) component)))
              components)
        recursive-components
        (into
         #{}
         (filter
          (fn [component]
            (or (> (count component) 1)
                (let [node (first component)]
                  (some #{node} (get graph node))))))
         components)]
    (->> nodes
         (mapcat
          (fn [[node-resource-type node-permission :as node]]
            (keep
             (fn [path]
               (when (and (= :arrow (:type path))
                          (:target-permission path))
                 (let [target
                       (permission-query-node
                        (:target-type path)
                        (:target-permission path))
                       component (get component-by-node node)]
                   (when (and (= component
                                 (get component-by-node target))
                              (contains? recursive-components component))
                     {:subject-type (:target-type path)
                      :relation-id (:via-relation-eid path)
                      :resource-type node-resource-type}))))
             (get-permission-paths
              db node-resource-type node-permission))))
         distinct
         (sort-by pr-str)
         vec)))

(defn- recursive-cycle-guards
  [db resource-type permission-name]
  (if-let [cache-atom
           (and (derived-cache-active?)
                (:recursive-cycle-guards *schema-cache*))]
    (let [cache-key
          (permission-paths-cache-key resource-type permission-name)
          snapshot @cache-atom]
      (if (contains? snapshot cache-key)
        (get snapshot cache-key)
        (let [guards
              (calc-recursive-cycle-guards
               db resource-type permission-name)]
          (get
           (swap! cache-atom
                  #(if (contains? % cache-key)
                     %
                     (assoc % cache-key guards)))
           cache-key))))
    (calc-recursive-cycle-guards db resource-type permission-name)))

(defn- recursive-data-active?
  [db resource-type permission-name]
  (boolean
   (some
    (fn [{:keys [subject-type relation-id resource-type]}]
      (backend/invoke
       db
       :relation-populated?
       subject-type
       relation-id
       resource-type))
    (recursive-cycle-guards db resource-type permission-name))))

(declare traversal-permission?)
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
  (let [{:keys [nodes certificate-input]}
        (calc-traversal-artifacts db)
        decision
        (verified/decide
         subproblem/*decision-kernel*
         :recursive-routing-certificate
         certificate-input)]
    (when-not (= :accepted (:status decision))
      (routing-certificate-error!
       "Generated verification rejected recursive SCC routing."
       {:reason (:reason decision)
        :path-checks (:path-checks decision)
        :node-checks (:node-checks decision)
        :edge-checks (:edge-checks decision)}))
    (zipmap nodes (:traversal decision))))

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
  (if-not (derived-cache-active?)
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

(defn- routing-cache-error!
  [message data]
  (throw
   (ex-info
    message
    (merge
     {:type :eacl.routing/stale-certificate
      :eacl/error :eacl.routing/stale-certificate}
     data))))

(defn- validate-routing-cache-binding!
  "The generated classification is valid only for the adapter, source, and
  normalized schema proof that produced it.

  Normal clients receive their cache from `schema-cache-for!`; this check is
  principally a fail-closed guard against integration mistakes, development
  reloads, and externally constructed cache maps."
  [db]
  (when-let [cache *schema-cache*]
    (let [expected-backend (:backend-id cache)
          expected-source (:source-scope cache)
          expected-schema (:routing-schema-identity cache)
          actual-backend (backend/backend-id db)
          actual-source (backend/invoke db :source-scope)
          actual-schema (schema-version db)]
      (when (or (and expected-backend
                     (not= expected-backend actual-backend))
                (and expected-source
                     (not= expected-source actual-source))
                (and (some? expected-schema)
                     (not= expected-schema actual-schema)))
        (routing-cache-error!
         "Generated traversal certificate does not match the selected schema snapshot."
         {:expected {:backend expected-backend
                     :source expected-source
                     :schema expected-schema}
          :actual {:backend actual-backend
                   :source actual-source
                   :schema actual-schema}})))))

(defn enumeration-route
  "Returns the generated certificate's executable route for a defined root.

  `:acyclic` is selected when the verified routing certificate proves either
  that the root cannot reach a recursive permission SCC or that every
  relationship prefix capable of carrying an in-SCC arrow is empty in the
  selected snapshot. Undefined roots do not compile either enumerator."
  [db resource-type permission-name]
  (validate-routing-cache-binding! db)
  (let [root-defined?
        (permission-root-defined?
         db resource-type permission-name)]
    (if-not root-defined?
      :undefined
      (let [recursive?
            (traversal-permission?
             db resource-type permission-name)
            recursive-data-active?
            (and recursive?
                 (recursive-data-active?
                  db resource-type permission-name))
            actual-identity
            (schema-version-stamp db)
            certificate-identity
            (some->
             (or (:routing-schema-identity *schema-cache*)
                 (schema-version db))
             str)
            ;; An unstamped snapshot is exact-only. No derived schema state is
            ;; retained when `derived-cache-active?` is false, so the route
            ;; certificate above was computed from this same immutable value.
            ;; Give that request-local proof a non-persistent identity for the
            ;; generated equality check; never use this escape hatch with a
            ;; reusable derived cache.
            exact-local-identity
            (when (and (nil? actual-identity)
                       (nil? certificate-identity)
                       (not (derived-cache-active?)))
              "exact-request-local")
            decision
            (verified/decide
             subproblem/*decision-kernel*
             :enumeration-route
             {:schema-identity (or actual-identity
                                   exact-local-identity
                                   "")
              :certificate-schema-identity
              (or certificate-identity
                  exact-local-identity
                  "")
              :root-defined? true
              :recursive? recursive?
              :recursive-data-active?
              recursive-data-active?})]
        (if (= :accepted (:status decision))
          (:route decision)
          (routing-cache-error!
           "Generated enumeration route rejected its schema binding."
           {:reason (:reason decision)
            :actual-schema actual-identity
           :certificate-schema certificate-identity}))))))
(defn traversal-nodes
  [db]
  (->> (all-permission-nodes db)
       (filter (fn [[resource-type permission-name]]
                 (traversal-permission? db resource-type permission-name)))
       set))

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
        selection subproblem/*decision-kernel*]
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
           (assoc source :indexed-rules indexed-rules))]
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
              (verification-identity subject-type)})]
        (when-not (= {:status :certified} decision)
          (recursive-plan-schema-error!
           "Generated verification rejected the recursive traversal plan."
           {:root-node root-node
            :subject-type subject-type
            :reason (:reason decision)}))))))

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

(defn- assert-complete-dependency-closure!
  "Managed denotation reuse frames validity by the root's relation dependency
  closure. A compiled rule referencing a relation outside that closure would
  let a relevant write leave the managed stamp unchanged, so compilation fails
  instead of permitting under-framed reuse."
  [db root-node rules]
  (let [closure (set (permission-relationship-eids
                      db (first root-node) (second root-node)))
        referenced
        (into (sorted-set)
              (mapcat
               (fn [rule]
                 (keep rule [:relation-eid
                             :via-relation-eid
                             :target-relation-eid])))
              rules)
        missing (into [] (remove closure) referenced)]
    (when (seq missing)
      (recursive-traversal-error!
       "Compiled recursive rules reference relations outside the permission's dependency closure."
       {:eacl/error :eacl.recursive-traversal/incomplete-dependency-closure
        :root-node root-node
        :missing-relation-eids missing
        :dependency-closure (vec (sort closure))}))))

(defn- compile-recursive-plan
  [db root-node]
  (inc-stat! :compiled-recursive-plans)
  (let [rules (compile-recursive-rules db root-node)
        _ (assert-complete-dependency-closure! db root-node rules)
        component-plan (compile-recursive-components db root-node)
        rules-by-node' (rules-by-node rules)
        forward-seeds (forward-seeds-by-subject-type rules)
        verification-rules (mapv verification-indexed-rule rules)]
    (certify-recursive-plan!
     db root-node rules forward-seeds)
    (let [compiled-verification-plan
          (verified/compile-indexed-plan
           subproblem/*decision-kernel*
           {:indexed-rules verification-rules
            :seed-rules-by-subject-type
            (into
             {}
             (map
              (fn [[subject-type seed-rules]]
                [(verification-identity subject-type)
                 (mapv verification-indexed-rule seed-rules)]))
             forward-seeds)})]
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
    (if-not (and cache-atom (derived-cache-active?))
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
