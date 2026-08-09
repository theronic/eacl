(ns eacl.engine.v8
  (:require [eacl.backend.v8 :as backend]
            [eacl.core :refer [spice-object]]
            [eacl.lazy-merge-sort :as lazy-sort]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(def engine-version 8)

(def ^:private default-page-size 1000)
(def ^:private max-page-size 10000)
(def ^:dynamic *count-window-size*
  "Maximum exact-count results certified in one bounded work window.

  Counting keeps one merged traversal across windows. This override exists for
  deterministic work tests; it is not a public client option."
  ;; The count loop replaces its consumed lazy tail, so the browser does not
  ;; retain earlier windows. A shared 16k bound amortizes generated-boundary
  ;; and vector realization overhead without changing results or logical work.
  16384)
(def ^:private lookup-continuation-version 2)

(def ^:private projection-key-version 1)
(def ^:private denotation-key-version 1)

(def ^:dynamic *projection-chunk-size*
  "Maximum relationship eids retained for one exact projection prefix.

  The cached prefix is deliberately smaller than a potentially unbounded
  adjacency list. A caller that consumes beyond it resumes with an exclusive
  backend seek and does not retain the tail."
  32)

(defn- count-projection-chunk-size
  [page-size]
  (max *projection-chunk-size* (inc page-size)))

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
   {:backend-id (backend/backend-id snapshot)
    :source-scope (backend/invoke snapshot :source-scope)
    :database-id (:database-id (backend/invoke snapshot :snapshot-id))
    :schema-version known-schema-version
    ;; The client-visible schema version may be a mutation journal token while
    ;; the shared engine adapter uses the normalized schema-content proof.
    ;; Bind generated routing certificates to the latter so proof modes cannot
    ;; make an unchanged schema look stale.
    :routing-schema-identity (schema-version snapshot)
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

(defn- acyclic-permission-paths
  [db resource-type permission-name]
  (remove
   (fn [path]
     (and
      (= :arrow (:type path))
      (contains?
       *inactive-recursive-cycle-guards*
       [(:target-type path)
        (:via-relation-eid path)
        resource-type])))
   (get-permission-paths db resource-type permission-name)))

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
                          (acyclic-permission-paths
                           db resource-type permission-name))))))]
    ;; `permission view = owner + admin` where `permission admin = owner`
    ;; expands to the same relation path twice: scanned twice, and both
    ;; collapsing onto one routing-path identity anyway.
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

(defn- inactive-recursive-cycle-guard-keys
  [db resource-type permission-name route]
  (if (and (= :acyclic route)
           (traversal-permission?
            db resource-type permission-name))
    (into
     #{}
     (map
      (fn [{:keys [subject-type relation-id resource-type]}]
        [subject-type relation-id resource-type]))
     (recursive-cycle-guards db resource-type permission-name))
    #{}))

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
            decision
            (verified/decide
             subproblem/*decision-kernel*
             :enumeration-route
             {:schema-identity (or actual-identity "")
              :certificate-schema-identity
              (or certificate-identity "")
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

(def ^:dynamic *count-stats*
  "Optional atom recording bounded count-page work for tests/benchmarks."
  nil)


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

(def ^:private diagnostic-counter-keys
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

(def ^:dynamic ^:private *stream-chunk-size*
  "Backend scan batch size selected by the enclosing recursive page.

  Small Relay windows use smaller batches to avoid realizing graph edges the
  caller did not ask for. Count operations and large pages keep the larger
  batch so index-seek overhead remains amortized."
  64)


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
                  (let [diagnostic-key
                        (get diagnostic-counter-keys counter-key)]
                    (case counter-key
                      :current-queue-depth
                      (assoc current diagnostic-key value)

                      :maximum-queue-depth
                      (update current diagnostic-key (fnil max 0) value)

                      (update current diagnostic-key (fnil + 0) value))))
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
   (let [selection subproblem/*decision-kernel*
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

(def ^:private recursive-denotation-version 3) ; v3: sorted canonical form (5.1)

(defn- canonical-denotation
  "Sorts a completed fixed-point eid vector into the canonical strictly
  ascending form. The count assert is the permutation guard: sorting
  must neither drop nor invent grants."
  [items]
  (let [sorted (vec (sort items))]
    (assert (= (count sorted) (count items))
            "denotation sort changed cardinality")
    sorted))

(defn- strictly-ascending-eids?
  [value]
  (loop [index 1
         previous (nth value 0 nil)]
    (if (>= index (count value))
      true
      (let [current (nth value index)]
        (if (< (long previous) (long current))
          (recur (inc index) current)
          false)))))

(defn- valid-recursive-denotation?
  "Canonical denotation form: a vector of strictly ascending positive
  eids. Strict ascent subsumes distinctness and is the invariant every
  cache read re-checks (the sort itself is trusted host code guarded
  here, at the sort site's permutation assert, and by the certified
  per-slice ordering of DecideAcyclicPage)."
  [value]
  (and (vector? value)
       (every? #(and (integer? %) (pos? %)) value)
       (or (empty? value)
           (strictly-ascending-eids? value))))

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
  (inc-shape-stat! :denotation-key-builds)
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
  (inc-shape-stat! :denotation-dependency-calcs)
  (permission-relationship-eids db (first root-node) (second root-node)))

(defn- record-permission-denotation-hit!
  [db root-node]
  (if (traversal-permission? db (first root-node) (second root-node))
    (subproblem/record-recursive-component-hit!)
    (subproblem/record-acyclic-denotation-hit!)))


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
    (canonical-denotation (:items result))))

(defn- resolve-forward-denotation
  [db subject-type subject-eid root-node result-type]
  (if-not subproblem/*store*
    ;; No store: key construction would compile and certify the plan and
    ;; walk the dependency closure purely to build keys no cache can use
    ;; (audited raw-path waste). Behaviorally identical: resolve-bound!
    ;; with a nil store computes uncached, and the :cached? gate meant
    ;; the hit recorder never fired on this branch.
    (complete-generated-forward-denotation
     db subject-type subject-eid root-node result-type)
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
           #(complete-generated-forward-denotation
             db subject-type subject-eid root-node result-type))]
      (when (:cached? resolved)
        (record-permission-denotation-hit! db root-node))
      (:value resolved))))

(defn- lookup-forward-denotation
  [db subject-type subject-eid root-node result-type]
  (when subproblem/*store*
    (when-let [resolved
               (subproblem/lookup-layered-bound!
                :denotation
                (recursive-denotation-key
                 db :forward root-node subject-type subject-eid result-type)
                {:valid? valid-recursive-denotation?
                 :weight-fn recursive-denotation-weight}
                (recursive-denotation-dependencies db root-node))]
      (record-permission-denotation-hit! db root-node)
      (:value resolved))))

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

(declare valid-cursor-eid?)

(defn- lookup-edge
  [eid]
  {:kind :lookup-eid
   :result-eid eid})

(defn- lookup-items
  [result-type eids]
  (mapv
   (fn [eid]
     {:node (spice-object result-type eid)
      :cursor (lookup-edge eid)})
   eids))

(defn- validate-lookup-eid-bound!
  [bound]
  (when bound
    (when-not (= :lookup-eid (:kind bound))
      (page-error!
       "Lookup page cursor has the wrong kind."
       {:eacl/error :eacl.pagination/wrong-cursor-kind
        :expected :lookup-eid
        :actual (:kind bound)}))
    (when-not (valid-cursor-eid? (:result-eid bound))
      (page-error!
       "Lookup page cursor has an invalid result boundary."
       {:eacl/error :eacl.pagination/invalid-cursor
        :result-eid (:result-eid bound)}))))

(defn- eid-insertion-point
  "Index of the first eid in the canonical sorted vector satisfying
  (pred eid). Binary search: values is strictly ascending."
  [values pred]
  (loop [low 0
         high (count values)]
    (if (>= low high)
      low
      (let [mid (quot (+ low high) 2)]
        (if (pred (nth values mid))
          (recur low mid)
          (recur (inc mid) high))))))

(defn- denotation-member?
  "O(log n) membership on the canonical sorted denotation."
  [values eid]
  (let [index (eid-insertion-point values #(<= eid %))]
    (and (< index (count values))
         (= eid (nth values index)))))

(defn- keyset-window
  "Realized scan-order window (at most size+1 eids, sentinel included)
  from the canonical sorted denotation for one keyset bound. :desc
  windows are returned in descending scan order for the certified page
  decision, exactly like the acyclic streaming route."
  [values page-direction bound-eid size]
  (let [value-count (count values)]
    (case page-direction
      :asc
      (let [start (if bound-eid
                    (eid-insertion-point values #(> % bound-eid))
                    0)]
        (subvec values start (min value-count (+ start (inc size)))))

      :desc
      (let [end (if bound-eid
                  (eid-insertion-point values #(>= % bound-eid))
                  value-count)
            start (max 0 (- end (inc size)))]
        (vec (rseq (subvec values start end)))))))

(defn- keyset-rebase
  "FORMAL-047 contract on the sorted denotation: a recover-current bound
  survives only when its result identity is a member of the CURRENT
  denotation (binary search - the page needs the denotation anyway).
  Hit: exclusive resume from the same eid. Miss: drop the bound and
  restart honestly."
  [values bound]
  (if-not (:rebase? bound)
    [bound false]
    (let [eid (:result-eid bound)]
      (if (and eid (denotation-member? values eid))
        [{:kind :lookup-eid :result-eid eid} false]
        [nil true]))))

(defn- certified-keyset-page
  "Certified slice shared by the acyclic streaming route and the keyset
  recursive route: every emitted page window passes the generated
  DecideAcyclicPage ordering/window decision (strict per-direction
  order revalidated at the kernel boundary)."
  [result-type page-direction size bound? realized restarted?]
  (let [page-decision
        (verified/decide
         subproblem/*decision-kernel*
         :acyclic-page
         {:direction page-direction
          :realized-eids realized
          :size size
          :bound? bound?})
        scan-order
        (vec (take (:take-count page-decision) realized))
        page-eids
        (if (:reverse? page-decision)
          (vec (reverse scan-order))
          scan-order)
        page
        (mark-recursive-restart
         (page-response
          {:items (lookup-items result-type page-eids)
           :has-next? (:has-next? page-decision)
           :has-previous? (:has-previous? page-decision)})
         restarted?)]
    {:page page
     :page-decision page-decision
     :scan-order scan-order
     :has-sentinel? (> (count realized) size)}))

(defn- continue-probe-to-closure
  "Extends a bounded probe's machine state to the complete fixed point
  through the verified page continuation - zero replay - and returns
  the canonical concatenated emission."
  [db plan direction run-continued probe]
  (let [items (:items probe)
        last-ordinal (dec (+ (:start-ordinal probe 0) (count items)))
        continued
        (run-continued
         {:kind :page
          :size (:max-derived-grants *recursive-traversal-limits*)
          :bound {:ordinal last-ordinal :eid (peek items)}}
         {:state (:state probe)
          :counters (:counters probe)})]
    (when (= :continuation-rejected (:status continued))
      (recursive-traversal-error!
       "Probe continuation was rejected by the generated runtime."
       {:eacl/error :eacl.recursive-traversal/invalid-generated-outcome
        :direction direction
        :outcome-status :probe-continuation-rejected}))
    (when (:has-next? continued)
      (recursive-traversal-error!
       "Recursive denotation exceeds :max-derived-grants, so no sorted page can be produced. Raise :recursive-traversal-limits, attach the subproblem store (a client caches the one-time closure), use :count-limit for bounded counts, or model the permission acyclically."
       {:eacl/error :eacl.recursive-traversal/limit-exceeded
        :direction direction
        :limit (:max-derived-grants *recursive-traversal-limits*)
        :outcome-status :incomplete-denotation}))
    (canonical-denotation (into (vec items) (:items continued)))))

(defn- probe-then-continue-forward
  "No-store denotation materialization at streaming cost for small
  results: a bounded probe keeps today's early-stop economics (the
  machine halts the fixed point once size+1 results are delivered); a
  result that fits one page IS the complete denotation. Larger results
  pay closure-minus-probe - the irreducible price of sorted first
  pages - via the verified continuation on the same state."
  [db subject-type subject-eid root-node result-type size]
  (let [plan (recursive-plan db root-node)
        probe (generated-forward-result
               db plan subject-type subject-eid root-node result-type
               {:kind :page :size size :bound nil})]
    (if-not (:has-next? probe)
      (canonical-denotation (:items probe))
      (continue-probe-to-closure
       db plan :forward
       (fn [render continuation]
         (generated-forward-result
          db plan subject-type subject-eid root-node result-type
          render continuation))
       probe))))

(defn- probe-then-continue-reverse
  [db resource-type resource-eid root-node subject-type size]
  (let [plan (recursive-plan db root-node)
        probe (generated-reverse-result
               db plan subject-type root-node resource-eid subject-type
               {:kind :page :size size :bound nil})]
    (if-not (:has-next? probe)
      (canonical-denotation (:items probe))
      (continue-probe-to-closure
       db plan :reverse
       (fn [render continuation]
         (generated-reverse-result
          db plan subject-type root-node resource-eid subject-type
          render continuation))
       probe))))

(defn- recursive-forward-page
  [db query continuation-cache]
  (let [{:keys [direction size bound]} (normalize-page-request query)
        _ (validate-lookup-eid-bound! bound)
        {:keys [subject permission]} query
        subject-type (:type subject)
        subject-eid (object-eid db (:id subject))
        result-type (:resource/type query)
        root-node (permission-query-node result-type permission)]
    (if-not subject-eid
      (page-response {:items []
                      :has-next? false
                      :has-previous? (boolean bound)})
      (let [values
            (if subproblem/*store*
              (or (lookup-forward-denotation
                   db subject-type subject-eid root-node result-type)
                  (resolve-forward-denotation
                   db subject-type subject-eid root-node result-type))
              (probe-then-continue-forward
               db subject-type subject-eid root-node result-type size))
            [bound' restarted?] (keyset-rebase values bound)
            realized (keyset-window
                      values direction (:result-eid bound') size)]
        (:page
         (certified-keyset-page
          result-type direction size (boolean bound')
          realized restarted?))))))

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
    (canonical-denotation (:items result))))

(defn- resolve-reverse-denotation
  [db resource-type resource-eid root-node subject-type]
  (if-not subproblem/*store*
    ;; Mirror of resolve-forward-denotation's nil-store fast path.
    (complete-generated-reverse-denotation
     db resource-type resource-eid root-node subject-type)
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
           #(complete-generated-reverse-denotation
             db resource-type resource-eid root-node subject-type))]
      (when (:cached? resolved)
        (record-permission-denotation-hit! db root-node))
      (:value resolved))))

(defn- lookup-reverse-denotation
  [db resource-type resource-eid root-node subject-type]
  (when subproblem/*store*
    (when-let [resolved
               (subproblem/lookup-layered-bound!
                :denotation
                (recursive-denotation-key
                 db :reverse root-node resource-type resource-eid subject-type)
                {:valid? valid-recursive-denotation?
                 :weight-fn recursive-denotation-weight}
                (recursive-denotation-dependencies db root-node))]
      (record-permission-denotation-hit! db root-node)
      (:value resolved))))

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



(defn- recursive-reverse-page
  [db query continuation-cache]
  (when (:subject/relation query)
    (page-error! ":subject/relation is not supported for recursive lookup-subjects."
                 {:eacl/error :eacl.pagination/unsupported-filter
                  :filter :subject/relation}))
  (let [{:keys [direction size bound]} (normalize-page-request query)
        _ (validate-lookup-eid-bound! bound)
        {:keys [resource permission]} query
        resource-type (:type resource)
        resource-eid (object-eid db (:id resource))
        subject-type (:subject/type query)
        root-node (permission-query-node resource-type permission)]
    (if-not resource-eid
      (page-response {:items []
                      :has-next? false
                      :has-previous? (boolean bound)})
      (let [values
            (if subproblem/*store*
              (or (lookup-reverse-denotation
                   db resource-type resource-eid root-node subject-type)
                  (resolve-reverse-denotation
                   db resource-type resource-eid root-node subject-type))
              (probe-then-continue-reverse
               db resource-type resource-eid root-node subject-type size))
            [bound' restarted?] (keyset-rebase values bound)
            realized (keyset-window
                      values direction (:result-eid bound') size)]
        (:page
         (certified-keyset-page
          subject-type direction size (boolean bound')
          realized restarted?))))))

(defn- recursive-can?
  "Evaluates one point query with the generated monotone indexed machine.

  A cache-enabled request exhausts and publishes the complete fixed point so
  distinct point, page, and count consumers can reuse it. The cache-free
  machine may stop once the target grant is derived because positive grants
  cannot be retracted by later least-fixed-point iterations."
  [db subject-type subject-eid root-node resource-type resource-eid]
  (if subproblem/*store*
    (denotation-member?
     (resolve-forward-denotation
      db subject-type subject-eid root-node resource-type)
     resource-eid)
    (let [plan (recursive-plan db root-node)
          reverse-result
          ;; A point authorization query is anchored by one concrete resource.
          ;; Drive the verified reverse machine from that resource toward the
          ;; requested subject instead of enumerating every resource reachable
          ;; from the subject. Both indexed directions refine the same least
          ;; fixed point; the Boolean renderer stops on the requested subject
          ;; and exhausts the reverse search before returning false.
          (generated-reverse-result
           db
           plan
           subject-type
           root-node
           resource-eid
           subject-type
           {:kind :boolean :target-eid subject-eid})]
      (if (:allowed? reverse-result)
        true
        ;; Datomic-compatible relationship storage is deliberately
        ;; denormalized. A consumer that bypasses EACL and retracts a target
        ;; entity can leave only the subject-owned tuple behind. That state
        ;; violates the adapter's forward/reverse equivalence obligation, but
        ;; storage migration compatibility requires the surviving direct grant
        ;; to remain visible to raw-eid callers. Probe only the exact direct
        ;; tuple before paying for generated forward recovery.
        (let [relation-eids
              (into
               []
               (comp
                (filter
                 #(and (= :relation (:type %))
                       (= subject-type (:subject-type %))))
                (map :relation-eid))
               (frontier-permission-paths
                db (first root-node) (second root-node)))]
          (boolean
           (when
            (some
             #(seq
               (direct-match-datoms-in-relationship-index
                db subject-type subject-eid %
                resource-type resource-eid))
             relation-eids)
             (:allowed?
              (generated-forward-result
               db
               plan
               subject-type
               subject-eid
               root-node
               resource-type
               {:kind :boolean :target-eid resource-eid})))))))))

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
              db resource-type permission))]
    (if defined-root?
      (recursive-can?
       db subject-type subject-eid
       (permission-query-node resource-type permission)
       resource-type resource-eid)
      false)))

;; --- Certified acyclic enumeration -----------------------------------------
;;
;; The generated routing certificate distinguishes roots that can reach a
;; recursive SCC from roots whose permission dependency graph is acyclic. The
;; v8 authority cutover accidentally ignored that distinction and drove every
;; list/count through the fixed-point machine. The functions below are the
;; bounded indexed evaluator for the certified acyclic route. They compose only
;; backend scans that are strictly ordered by internal EID, merge/deduplicate
;; those streams, and stop at the requested page boundary.

(defn- merge-eid-seqs
  [direction seqs]
  (case direction
    :asc
    (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs)

    :desc
    (lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc identity seqs)))

(defn- path-frontier-key
  [path]
  ;; `path-frontier-identity` is injective over compiled path variants and
  ;; contains only normalized schema identities and relation EIDs.
  (pr-str (path-frontier-identity path)))

(defn- resume-scan-opts
  [direction page-opts resume-eid]
  (if resume-eid
    {:direction direction
     :bound-eid resume-eid
     :inclusive-bound? false}
    page-opts))

(defn- matching-relation-sub-paths
  [sub-paths subject-type]
  (filter
   #(and (= :relation (:type %))
         (= subject-type (:subject-type %)))
   sub-paths))

(defn- arrow-via-intermediates
  "Lazily merges one result stream per intermediate.

  Cached heads are private continuation state. A missing cache merely opens
  the ordered backend scans again at the authenticated global result bound."
  [direction intermediate-eids result-fn head-state]
  (let [cached (:cached head-state)
        observed (:observed head-state)
        note-head!
        (fn [intermediate-eid head]
          (when observed
            (swap! observed assoc intermediate-eid head)))
        pairs
        (keep
         (fn [intermediate-eid]
           (if-let [head (get cached intermediate-eid)]
             (do
               (note-head! intermediate-eid head)
               {:intermediate-eid intermediate-eid
                :results
                (cons
                 head
                 (lazy-seq
                  (result-fn intermediate-eid head)))})
             (let [results (seq (result-fn intermediate-eid nil))]
               (when results
                 (note-head! intermediate-eid (first results))
                 {:intermediate-eid intermediate-eid
                  :results results}))))
         intermediate-eids)
        first-pair (first pairs)
        result-seqs (map :results pairs)]
    {:results
     (if (seq result-seqs)
       (merge-eid-seqs direction result-seqs)
       [])
     ;; Inclusive intermediate frontier for the next page. Exhausted paths can
     ;; be skipped without consulting the backend.
     :frontier
     (if first-pair
       (:intermediate-eid first-pair)
       :exhausted)}))

(defn- valid-cursor-eid?
  [eid]
  (and (integer? eid)
       (pos? eid)
       (<= eid backend/maximum-exact-integer)))

(defn- valid-lookup-continuation?
  [value]
  (and
   (map? value)
   (= lookup-continuation-version (:version value))
   (map? (:frontiers value))
   (map? (:heads value))
   (every?
    (fn [[path-key frontier]]
      (and
       (string? path-key)
       (or (= :exhausted frontier)
           (valid-cursor-eid? frontier))))
    (:frontiers value))
   (every?
    (fn [[path-key heads]]
      (and
       (string? path-key)
       (map? heads)
       (every?
        (fn [[intermediate-eid head]]
          (and
           (valid-cursor-eid? intermediate-eid)
           (valid-cursor-eid? head)))
        heads)))
    (:heads value))))

(defn- cached-lookup-continuation
  [continuation-cache bound]
  (when (and bound continuation-cache)
    (let [get-heads (:get-heads continuation-cache)
          ;; The continuation contract validated at construction is the
          ;; authenticated, scope-committed store handle: its key digest
          ;; commits the schema, query, and snapshot identities, so a store
          ;; consultation through it is definitionally scoped to the current
          ;; identities — an entry under a different identity is simply
          ;; absent under this key. Compute the decision inputs from that
          ;; contract instead of asserting them as literals.
          contract?
          (and (map? continuation-cache)
               (false? (:required? continuation-cache))
               (true? (:opaque-values? continuation-cache))
               (fn? get-heads))
          value
          (when contract?
            (try
              (get-heads bound)
              (catch #?(:clj Exception :cljs :default) _
                nil)))
          valid? (valid-lookup-continuation? value)
          action
          (verified/decide
           subproblem/*decision-kernel*
           :acyclic-continuation
           {:authenticated? contract?
            :schema-matches? contract?
            :query-matches? contract?
            :snapshot-matches? contract?
            :entry-present? (some? value)
            :entry-valid? valid?})]
      (case action
        :resume
        (do
          (add-acyclic-work! :continuation-hits 1)
          value)

        :replay
        (do
          (add-acyclic-work! :continuation-misses 1)
          nil)

        (routing-cache-error!
         "Generated continuation authority rejected authenticated replay."
         {:reason action})))))

(defn- store-lookup-continuation!
  [continuation-cache edge frontiers heads]
  (when-let [put-heads! (:put-heads! continuation-cache)]
    (when edge
      (try
        (put-heads!
         edge
         {:version lookup-continuation-version
          :frontiers (or frontiers {})
          :heads (or heads {})}
         (+ 512
            (* 64 (count frontiers))
            (* 96 (reduce + 0 (map count (vals heads))))))
        (catch #?(:clj Exception :cljs :default) _
          false)))))

(declare traverse-acyclic-forward lookup-acyclic-subject-eids)

(defn- traverse-acyclic-forward-path
  [db subject-type subject-eid path resource-type page-opts
   intermediate-cursor-eid visited head-state]
  (let [{:keys [direction]} (scan-opts page-opts)
        intermediate-opts
        {:direction direction
         :bound-eid intermediate-cursor-eid
         :inclusive-bound? true}]
    (case (:type path)
      :relation
      {:results
       (when (= subject-type (:subject-type path))
         (subject->resources
          db subject-type subject-eid (:relation-eid path)
          resource-type page-opts))
       :frontier nil}

      :self-permission
      {:results
       (traverse-acyclic-forward
        db subject-type subject-eid (:target-permission path)
        resource-type page-opts visited)
       :frontier nil}

      :arrow
      (let [intermediate-type (:target-type path)
            via-relation-eid (:via-relation-eid path)]
        (if (:target-relation path)
          (let [intermediate-seqs
                (->> (matching-relation-sub-paths
                      (:sub-paths path)
                      subject-type)
                     (map
                      (fn [sub-path]
                        (subject->resources
                         db subject-type subject-eid
                         (:relation-eid sub-path)
                         intermediate-type
                         intermediate-opts)))
                     (filter seq))
                intermediate-eids
                (if (seq intermediate-seqs)
                  (merge-eid-seqs direction intermediate-seqs)
                  [])]
            (arrow-via-intermediates
             direction
             intermediate-eids
             (fn [intermediate-eid resume-eid]
               (subject->resources
                db intermediate-type intermediate-eid via-relation-eid
                resource-type
                (resume-scan-opts direction page-opts resume-eid)))
             head-state))
          (let [intermediate-eids
                (traverse-acyclic-forward
                 db subject-type subject-eid (:target-permission path)
                 intermediate-type intermediate-opts visited)]
            (arrow-via-intermediates
             direction
             intermediate-eids
             (fn [intermediate-eid resume-eid]
               (subject->resources
                db intermediate-type intermediate-eid via-relation-eid
                resource-type
                (resume-scan-opts direction page-opts resume-eid)))
             head-state)))))))

(defn- traverse-acyclic-forward
  [db subject-type subject-eid permission-name resource-type page-opts visited]
  (let [state [subject-type subject-eid permission-name resource-type]]
    (if (contains? visited state)
      []
      (let [visited' (conj visited state)
            paths
            (acyclic-permission-paths
             db resource-type permission-name)
            result-seqs
            (->> paths
                 (map
                  (fn [path]
                    (:results
                     (traverse-acyclic-forward-path
                      db subject-type subject-eid path resource-type
                      page-opts nil visited' nil))))
                 (filter seq))]
        (if (seq result-seqs)
          (merge-eid-seqs (:direction page-opts) result-seqs)
          [])))))

(defn- traverse-acyclic-reverse-path
  [db resource-type resource-eid path subject-type page-opts
   intermediate-cursor-eid visited head-state]
  (let [{:keys [direction]} (scan-opts page-opts)
        intermediate-opts
        {:direction direction
         :bound-eid intermediate-cursor-eid
         :inclusive-bound? true}]
    (case (:type path)
      :relation
      {:results
       (when (= subject-type (:subject-type path))
         (resource->subjects
          db resource-type resource-eid (:relation-eid path)
          subject-type page-opts))
       :frontier nil}

      :self-permission
      {:results
       (lookup-acyclic-subject-eids
        db resource-type resource-eid (:target-permission path)
        subject-type page-opts visited)
       :frontier nil}

      :arrow
      (let [intermediate-type (:target-type path)
            via-relation-eid (:via-relation-eid path)
            intermediate-eids
            (resource->subjects
             db resource-type resource-eid via-relation-eid
             intermediate-type intermediate-opts)]
        (if (:target-relation path)
          (let [sub-paths
                (matching-relation-sub-paths
                 (:sub-paths path)
                 subject-type)]
            (arrow-via-intermediates
             direction
             intermediate-eids
             (fn [intermediate-eid resume-eid]
               (let [opts
                     (resume-scan-opts direction page-opts resume-eid)
                     result-seqs
                     (->> sub-paths
                          (map
                           (fn [sub-path]
                             (resource->subjects
                              db intermediate-type intermediate-eid
                              (:relation-eid sub-path)
                              subject-type opts)))
                          (filter seq))]
                 (if (seq result-seqs)
                   (merge-eid-seqs direction result-seqs)
                   [])))
             head-state))
          (arrow-via-intermediates
           direction
           intermediate-eids
           (fn [intermediate-eid resume-eid]
             (lookup-acyclic-subject-eids
              db intermediate-type intermediate-eid
              (:target-permission path)
              subject-type
              (resume-scan-opts direction page-opts resume-eid)
              visited))
           head-state))))))

(defn- lookup-acyclic-subject-eids
  [db resource-type resource-eid permission-name subject-type
   page-opts visited]
  (let [state [resource-type resource-eid permission-name subject-type]]
    (if (contains? visited state)
      []
      (let [visited' (conj visited state)
            paths
            (acyclic-permission-paths
             db resource-type permission-name)
            result-seqs
            (->> paths
                 (map
                  (fn [path]
                    (:results
                     (traverse-acyclic-reverse-path
                      db resource-type resource-eid path subject-type
                      page-opts nil visited' nil))))
                 (filter seq))]
        (if (seq result-seqs)
          (merge-eid-seqs (:direction page-opts) result-seqs)
          [])))))

(def ^:private forward-acyclic-direction
  {:anchor-key :subject
   :permission-type (fn [query] (:resource/type query))
   :result-type (fn [query] (:resource/type query))
   :traverse traverse-acyclic-forward-path})

(def ^:private reverse-acyclic-direction
  {:anchor-key :resource
   :permission-type (fn [query] (:type (:resource query)))
   :result-type (fn [query] (:subject/type query))
   :traverse traverse-acyclic-reverse-path})

(defn- acyclic-bound-authorized?
  [db direction query result-eid]
  ;; Revalidating a recover-current cursor is part of the certified acyclic
  ;; route. Entering recursive-can? here made a relationship write turn a
  ;; harmless cursor rebase into an unbounded fixed-point walk (and eventually
  ;; a recursive safety-limit error) for super-user-sized result sets.
  ;;
  ;; Each indexed stream is strictly ascending. An inclusive scan beginning at
  ;; the authenticated result EID therefore proves membership exactly when its
  ;; first deduplicated result is that EID; no prefix enumeration is required.
  (let [point-opts
        {:direction :asc
         :bound-eid result-eid
         :inclusive-bound? true}]
    (case (:anchor-key direction)
      :subject
      (let [subject (:subject query)
            subject-eid (object-eid db (:id subject))
            result-type (:resource/type query)]
        (and
         subject-eid
         (= result-eid
            (first
             (traverse-acyclic-forward
              db
              (:type subject)
              subject-eid
              (:permission query)
              result-type
              point-opts
              #{})))))

      :resource
      (let [resource (:resource query)
            resource-eid (object-eid db (:id resource))]
        (and
         resource-eid
         (= result-eid
            (first
             (lookup-acyclic-subject-eids
              db
              (:type resource)
              resource-eid
              (:permission query)
              (:subject/type query)
              point-opts
              #{}))))))))

(defn- rebase-acyclic-query
  [db direction query]
  (let [bound-field
        (cond
          (contains? query :after) :after
          (contains? query :before) :before)
        bound (get query bound-field)]
    (if-not (true? (:rebase? bound))
      [query false]
      (if (acyclic-bound-authorized?
           db direction query (:result-eid bound))
        [(assoc
          query
          bound-field
          (select-keys bound [:kind :result-eid]))
         false]
        [(dissoc query :after :before) true]))))

(defn- lazy-merged-acyclic-lookup
  [db direction query page-request continuation]
  (let [{:keys [anchor-key traverse permission-type]} direction
        anchor (get query anchor-key)
        anchor-type (:type anchor)
        anchor-eid (object-eid db (:id anchor))
        permission (:permission query)
        permission-type' (permission-type query)
        result-type-key
        (if (= :subject anchor-key)
          :resource/type
          :subject/type)
        result-type (get query result-type-key)
        page-opts
        {:direction (:direction page-request)
         :bound-eid (get-in page-request [:bound :result-eid])
         :inclusive-bound? false}
        paths
        (frontier-permission-paths
         db permission-type' permission)
        observed-heads (atom {})
        path-results
        (mapv
         (fn [path]
           (let [path-key (path-frontier-key path)
                 prior-frontier
                 (get-in continuation [:frontiers path-key])
                 path-observed (atom {})
                 head-state
                 {:cached (get-in continuation [:heads path-key])
                  :observed path-observed}
                 _ (swap! observed-heads assoc path-key path-observed)
                 result
                 (cond
                   (= :exhausted prior-frontier)
                   {:results [] :frontier :exhausted}

                   anchor-eid
                   (traverse
                    db
                    anchor-type
                    anchor-eid
                    path
                    result-type
                    page-opts
                    prior-frontier
                    #{}
                    head-state)

                   :else
                   {:results [] :frontier nil})]
             {:path-key path-key
              :results (:results result)
              :frontier (:frontier result)}))
         paths)
        result-seqs (filter seq (map :results path-results))]
    (add-acyclic-work! :permission-paths (count paths))
    {:results
     (if (seq result-seqs)
       (merge-eid-seqs (:direction page-request) result-seqs)
       [])
     :path-frontiers
     (into
      {}
      (keep
       (fn [{:keys [path-key frontier]}]
         (when frontier
           [path-key frontier])))
      path-results)
     :observed-heads observed-heads}))

(defn- surviving-heads
  [observed-heads boundary-eid]
  (if-not boundary-eid
    {}
    (persistent!
     (reduce-kv
      (fn [result path-key path-observed]
        (let [kept
              (persistent!
               (reduce-kv
                (fn [heads intermediate-eid head]
                  (if (> (long head) (long boundary-eid))
                    (assoc! heads intermediate-eid head)
                    heads))
                (transient {})
                @path-observed))]
          (if (seq kept)
            (assoc! result path-key kept)
            result)))
      (transient {})
      @observed-heads))))

(defn- acyclic-lookup
  [db route query continuation-cache]
  (let [[query restarted?] (rebase-acyclic-query db route query)
        page-request (normalize-page-request query)
        {page-direction :direction
         :keys [size bound]} page-request
        _ (validate-lookup-eid-bound! bound)
        resumable? (= :asc page-direction)
        continuation
        (when (and resumable? bound)
          (cached-lookup-continuation continuation-cache bound))
        {:keys [results path-frontiers observed-heads]}
        (lazy-merged-acyclic-lookup
         db route query page-request continuation)
        realized (vec (take (inc size) results))
        page-decision
        (verified/decide
         subproblem/*decision-kernel*
         :acyclic-page
         {:direction page-direction
          :realized-eids realized
          :size size
          :bound? (boolean bound)})
        has-sentinel? (> (count realized) size)
        scan-order
        (vec (take (:take-count page-decision) realized))
        page-eids
        (if (:reverse? page-decision)
          (vec (reverse scan-order))
          scan-order)
        page
        (mark-recursive-restart
         (page-response
          {:items (lookup-items ((:result-type route) query) page-eids)
           :has-next?
           (:has-next? page-decision)
           :has-previous?
           (:has-previous? page-decision)})
         restarted?)]
    (add-acyclic-work! :pages 1)
    (add-acyclic-work!
     :merge-advances (:merge-advances page-decision))
    (add-acyclic-work!
     :emitted-results (:emitted-results page-decision))
    (when-not
     (= :accepted
        (verified/decide
         subproblem/*decision-kernel*
         :acyclic-work
         {:requested-window size
          :merge-advances (:merge-advances page-decision)
          :emitted-results (:emitted-results page-decision)
          :recursive-work (:recursive-work page-decision)}))
      (routing-cache-error!
       "Generated acyclic page work authority rejected the page."
       {:page-decision page-decision}))
    (when (and resumable? has-sentinel?)
      (store-lookup-continuation!
       continuation-cache
       (get-in page [:page-info :end-cursor])
       path-frontiers
       (surviving-heads observed-heads (peek scan-order))))
    page))

(defn- count-acyclic-pages
  [db direction query limit]
  ;; Exact count is one logical merge traversal. Rebuilding that traversal at
  ;; every internal count window made fan-out scans proportional to
  ;; `window-count * intermediate-count`; a 100k Explorer count reopened the
  ;; same account/team/VPC streams thousands of times. Keep one lazy tail and
  ;; consume it in bounded certified windows instead. Recur replaces the prior
  ;; tail, so already-counted pages are not retained.
  (binding [*projection-chunk-size*
            (count-projection-chunk-size *count-window-size*)]
    (loop [total 0
           results
           (:results
            (lazy-merged-acyclic-lookup
             db
             direction
             (dissoc query :count-limit)
             {:direction :asc
              :size *count-window-size*
              :bound nil}
             nil))]
      (let [remaining (when limit (- limit total))
            page-size
            (if remaining
              (max 1 (min *count-window-size* (inc remaining)))
              *count-window-size*)
            realized (vec (take (inc page-size) results))
            has-sentinel? (> (count realized) page-size)
            page-eids (if has-sentinel? (pop realized) realized)
            page-count (count page-eids)
            total' (+ total page-count)]
        (add-acyclic-work! :count-pages 1)
        (add-acyclic-work! :merge-advances (count realized))
        (add-acyclic-work! :counted-results page-count)
        (when-not
         (= :accepted
            (verified/decide
             subproblem/*decision-kernel*
             :acyclic-work
             {:requested-window page-size
              :merge-advances (count realized)
              :emitted-results page-count
              :recursive-work 0}))
          (routing-cache-error!
           "Generated acyclic count work authority rejected the page."
           {:page-size page-size
            :realized-count (count realized)
            :page-count page-count}))
        (when *count-stats*
          (swap!
           *count-stats*
           (fn [stats]
             (-> stats
                 (update :pages (fnil inc 0))
                 (update :max-page-eids (fnil max 0) page-count)))))
        (cond
          (and limit (> total' limit))
          (select-keys
           (verified/decide
            subproblem/*decision-kernel*
            :acyclic-count
            {:unique-count total'
             :more? has-sentinel?
             :limit limit})
           [:count :truncated?])

          has-sentinel?
          (recur
           total'
           ;; The sentinel is the first value of the next certified window.
           ;; Advance only by the values counted in this window.
           (drop page-size results))

          :else
          (select-keys
           (verified/decide
            subproblem/*decision-kernel*
            :acyclic-count
            {:unique-count total'
             :more? false
             :limit limit})
           [:count :truncated?]))))))

(defn lookup-resources
  "Runs generated forward pagination.

  Raw-impl callers must hold one DB value for a whole exact-snapshot walk; the
  public client authenticates and scopes cursor state before it reaches here."
  ([db query]
   (lookup-resources db query nil))
  ([db query {:keys [continuation-cache continuation-cache-fn]}]
   (let [cache (or continuation-cache
                   (when continuation-cache-fn (continuation-cache-fn)))
         route
         (enumeration-route
          db (:resource/type query) (:permission query))
         [query restart?]
         (restart-unroutable-rebase (not= :undefined route) query)
         result
         (case route
           :recursive
           (recursive-forward-page db query cache)

           :acyclic
           (binding [*acyclic-route?* true
                     *inactive-recursive-cycle-guards*
                     (inactive-recursive-cycle-guard-keys
                      db (:resource/type query) (:permission query) route)]
             (add-acyclic-work! :routed-acyclic 1)
             (acyclic-lookup
              db forward-acyclic-direction query cache))

           (let [{:keys [bound]} (normalize-page-request query)]
             (when bound
               (case (:kind bound)
                 :lookup-eid (validate-lookup-eid-bound! bound)
                 (page-error!
                  "Lookup page cursor has the wrong kind."
                  {:eacl/error :eacl.pagination/wrong-cursor-kind
                   :actual (:kind bound)})))
             (page-response
              {:items []
               :has-next? false
               :has-previous? (boolean bound)})))]
     (mark-recursive-restart result restart?))))

(defn lookup-subjects
  "See lookup-resources: cursors are only valid against the minting db basis."
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
                   (when continuation-cache-fn (continuation-cache-fn)))
         route
         (enumeration-route
          db (:type (:resource query)) (:permission query))
         [query restart?]
         (restart-unroutable-rebase (not= :undefined route) query)
         result
         (case route
           :recursive
           (recursive-reverse-page db query cache)

           :acyclic
           (binding [*acyclic-route?* true
                     *inactive-recursive-cycle-guards*
                     (inactive-recursive-cycle-guard-keys
                      db
                      (:type (:resource query))
                      (:permission query)
                      route)]
             (add-acyclic-work! :routed-acyclic 1)
             (acyclic-lookup
              db reverse-acyclic-direction query cache))

           (let [{:keys [bound]} (normalize-page-request query)]
             (when bound
               (case (:kind bound)
                 :lookup-eid (validate-lookup-eid-bound! bound)
                 (page-error!
                  "Lookup page cursor has the wrong kind."
                  {:eacl/error :eacl.pagination/wrong-cursor-kind
                   :actual (:kind bound)})))
             (page-response
              {:items []
               :has-next? false
               :has-previous? (boolean bound)})))]
     (mark-recursive-restart result restart?))))

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

(defn count-resources
  [db {:as query}]
  (reject-count-pagination-keys! "count-resources" query)
  (let [limit (query-count-limit query)
        route
        (enumeration-route
         db (:resource/type query) (:permission query))]
    (count-response
     (case route
       :undefined
       {:count 0 :truncated? false}

       :acyclic
       (binding [*acyclic-route?* true
                 *inactive-recursive-cycle-guards*
                 (inactive-recursive-cycle-guard-keys
                  db (:resource/type query) (:permission query) route)]
         (add-acyclic-work! :routed-acyclic 1)
         (count-acyclic-pages
          db forward-acyclic-direction query limit))

       :recursive
       (let [{:keys [subject permission]} query
             subject-eid (object-eid db (:id subject))
             result-type (:resource/type query)
             root-node (permission-query-node result-type permission)]
         (if-not subject-eid
           {:count 0 :truncated? false}
           (if subproblem/*store*
             ;; Store-bound counts resolve (and therefore PUBLISH) the
             ;; complete denotation so distinct/overlapping counts, pages,
             ;; and point checks stop repaying the full fixed point (the
             ;; audited count-amortization gap).
             (count-denotation
              (resolve-forward-denotation
               db (:type subject) subject-eid root-node result-type)
              limit)
             ;; Cache-free counts keep the bounded generated renders so
             ;; :count-limit truncation still stops the machine early.
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
              [:count :truncated?])))))
     limit)))

(defn count-subjects
  [db {:as query}]
  (reject-count-pagination-keys! "count-subjects" query)
  (when (:subject/relation query)
    (page-error! ":subject/relation is not supported by count-subjects."
                 {:eacl/error :eacl.pagination/unsupported-filter
                  :filter :subject/relation}))
  (let [limit (query-count-limit query)
        route
        (enumeration-route
         db (:type (:resource query)) (:permission query))]
    (count-response
     (case route
       :undefined
       {:count 0 :truncated? false}

       :acyclic
       (binding [*acyclic-route?* true
                 *inactive-recursive-cycle-guards*
                 (inactive-recursive-cycle-guard-keys
                  db
                  (:type (:resource query))
                  (:permission query)
                  route)]
         (add-acyclic-work! :routed-acyclic 1)
         (count-acyclic-pages
          db reverse-acyclic-direction query limit))

       :recursive
       (let [{:keys [resource permission]} query
             resource-eid (object-eid db (:id resource))
             subject-type (:subject/type query)
             root-node (permission-query-node (:type resource) permission)]
         (if-not resource-eid
           {:count 0 :truncated? false}
           (if subproblem/*store*
             (count-denotation
              (resolve-reverse-denotation
               db (:type resource) resource-eid root-node subject-type)
              limit)
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
              [:count :truncated?])))))
     limit)))
