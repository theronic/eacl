(ns eacl.exploration.forward-runtime-prototype
  "Exploration-only source-shaped forward reducer and frozen benchmark.

  This is deliberately not production code.  It consumes the existing
  normalized compiler seam and the ranked sealed-plan prototype, then runs one
  width-one chunked forward traversal with a right-edge stack and exact logical
  admission."
  (:require [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.bench.recursive-fixture :as recursive-fixture]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.exploration.sealed-plan-refinement-bridge :as sealed]
            [eacl.exploration.stable-discovery-source-benchmark :as baseline]))

(def ^:private compile-rules
  (deref (ns-resolve 'eacl.engine.v8 'compile-recursive-rules)))

(def ^:private baseline-run-once
  (deref
   (ns-resolve
    'eacl.exploration.stable-discovery-source-benchmark 'run-once)))

(def ^:private baseline-client-for
  (deref
   (ns-resolve
    'eacl.exploration.stable-discovery-source-benchmark 'client-for)))

(def ^:private baseline-run-recursive-page
  (deref
   (ns-resolve
    'eacl.exploration.stable-discovery-source-benchmark
    'run-recursive-page)))

(def ^:private baseline-canonical-root
  (deref
   (ns-resolve
    'eacl.exploration.stable-discovery-source-benchmark
    'canonical-root)))

(def ^:private allocation-bean
  (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
    (when (instance? com.sun.management.ThreadMXBean bean)
      (let [bean ^com.sun.management.ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          bean)))))

(defn- allocated-bytes
  []
  (when allocation-bean
    (.getThreadAllocatedBytes
     ^com.sun.management.ThreadMXBean allocation-bean
     (.getId (Thread/currentThread)))))

(defn- work-id
  [{:keys [kind rule subject-type subject-id intermediate-id resource-id
           bound-eid]}]
  (case kind
    :seed-relation
    [:seed-relation (:ordinal rule) subject-type subject-id]

    :seed-arrow-relation
    [:seed-arrow-relation (:ordinal rule) subject-type subject-id]

    :via-scan
    [:via-scan (:ordinal rule) intermediate-id]

    :grant
    [:grant (:node rule) resource-id]

    :consumer
    [:consumer (:ordinal rule) resource-id]

    :reverse-goal
    [:reverse-goal (:node rule) resource-id]

    :reverse-direct
    [:reverse-direct (:ordinal rule) resource-id]

    :reverse-via-permission
    [:reverse-via-permission (:ordinal rule) resource-id]

    :reverse-via-relation
    [:reverse-via-relation (:ordinal rule) resource-id]

    :reverse-base-subjects
    [:reverse-base-subjects (:ordinal rule) intermediate-id]

    :reverse-subject
    [:reverse-subject subject-type subject-id]))

(defn- schedule
  ([state items]
   (schedule state items nil nil))
  ([state items replaced residual]
   (when (and residual
              (not= (work-id replaced) (work-id residual)))
     (throw
      (ex-info
       "Residual scan changed logical occurrence identity."
       {:replaced (work-id replaced)
        :residual (work-id residual)})))
   (let [[admitted new-work]
         (reduce
          (fn [[admitted new-work] item]
            (let [identity (work-id item)]
              (if (contains? admitted identity)
                [admitted new-work]
                [(conj admitted identity) (conj new-work item)])))
          [(:admitted state) []]
          items)
         ;; The residual is replacement frontier state for an already-admitted
         ;; logical scan occurrence.  Its bound belongs to the physical read
         ;; descriptor, not to exact logical admission.  In the right-edge
         ;; stack it precedes the reverse of new canonical work so it executes
         ;; after that work and before the prior tail.
         stack (cond-> (:stack state) residual (conj residual))
         stack (into stack (rseq new-work))]
     (-> state
         (assoc :admitted admitted :stack stack)
         (update :admissions + (count new-work))
         (update :maximum-stack max (count stack))))))

(defn- scan-options
  [bound-eid]
  (cond-> {:direction :asc}
    (some? bound-eid)
    (assoc :bound-eid bound-eid :inclusive-bound? false)))

(defn- retain-sidecar-entry
  [sidecar oldest-first identity entry capacity]
  (let [oldest-first (into [] (remove #{identity}) oldest-first)
        sidecar (dissoc sidecar identity)
        [sidecar oldest-first]
        (if (and entry (pos? capacity))
          [(assoc sidecar identity entry) (conj oldest-first identity)]
          [sidecar oldest-first])
        overflow (max 0 (- (count oldest-first) capacity))
        evicted (take overflow oldest-first)
        oldest-first (vec (drop overflow oldest-first))
        sidecar (apply dissoc sidecar evicted)]
    {:sidecar sidecar :oldest-first oldest-first}))

(defn- scan-page
  [state scan-fn work logical-chunk-size physical-chunk-size]
  (let [identity (work-id work)
        sidecar-entry (get (:sidecar state) identity)
        sidecar-entry
        (when (= (:logical-bound sidecar-entry) (:bound-eid work))
          sidecar-entry)
        buffered (vec (:values sidecar-entry))
        drop-deferred-buffers? (:drop-deferred-buffers? state)
        fetch? (empty? buffered)
        fetched
        (if fetch?
          (vec
           (take physical-chunk-size
                 (scan-fn (scan-options (:bound-eid work)))))
          [])
        source (if fetch? fetched buffered)
        more-physical?
        (if fetch?
          (= physical-chunk-size (count fetched))
          (boolean (:more-physical? sidecar-entry)))
        values (vec (take logical-chunk-size source))
        remaining (vec (drop logical-chunk-size source))
        residual? (or (seq remaining) more-physical?)
        logical-next-bound (when (seq values) (peek values))
        residual
        (when residual?
          ;; Authoritative state advances only through values released to the
          ;; reducer.  Fetch end, response offset, and unread values never
          ;; enter the logical frame.
          (assoc work :bound-eid logical-next-bound))
        retain-entry?
        (and residual?
             (seq remaining)
             (not drop-deferred-buffers?))
        retained
        (retain-sidecar-entry
         (:sidecar state)
         (:sidecar-oldest-first state)
         identity
         (when retain-entry?
           {:logical-bound logical-next-bound
            :values remaining
            :more-physical? more-physical?})
         (:sidecar-buffer-cap state))
        sidecar (:sidecar retained)
        state
        (cond-> (assoc state
                       :sidecar sidecar
                       :sidecar-oldest-first (:oldest-first retained))
          fetch? (update :commands inc)
          fetch? (update :fetched-values + (count fetched))
          true (update :maximum-sidecar-buffers max (count sidecar))
          true (update :maximum-sidecar-values max
                       (reduce + 0 (map (comp count :values val) sidecar))))]
    [state values residual]))

(defn- grant
  [rule resource-id]
  {:kind :grant :rule rule :resource-id resource-id})

(defn- seed-work
  [subject-type subject-id rule]
  (case (:rule rule)
    :relation
    {:kind :seed-relation
     :rule rule
     :subject-type subject-type
     :subject-id subject-id
     :bound-eid nil}

    :arrow-relation
    {:kind :seed-arrow-relation
     :rule rule
     :subject-type subject-type
     :subject-id subject-id
     :bound-eid nil}))

(defn- consumers-for
  [plan node resource-id]
  (mapv (fn [rule]
          {:kind :consumer
           :rule rule
           :resource-id resource-id
           :bound-eid nil})
        (get-in plan [:indexes :forward-consumers node] [])))

(defn- step
  [adapter plan root state logical-chunk-size physical-chunk-size]
  (let [work (peek (:stack state))
        state (-> state
                  (update :stack pop)
                  (update :transitions inc))
        {:keys [kind rule subject-type subject-id intermediate-id resource-id]}
        work]
    (case kind
      :seed-relation
      (let [[state values residual]
            (scan-page
             state
             #(backend/invoke
               adapter :subject->resources
               subject-type subject-id (:relation-eid rule)
               (first (:node rule)) %)
             work logical-chunk-size physical-chunk-size)
            successors (mapv #(grant rule %) values)]
        (schedule state successors work residual))

      :seed-arrow-relation
      (let [[state values residual]
            (scan-page
             state
             #(backend/invoke
               adapter :subject->resources
               subject-type subject-id (:target-relation-eid rule)
               (:intermediate-type rule) %)
             work logical-chunk-size physical-chunk-size)
            successors
            (mapv (fn [intermediate]
                    {:kind :via-scan
                     :rule rule
                     :intermediate-id intermediate
                     :bound-eid nil})
                  values)]
        (schedule state successors work residual))

      :via-scan
      (let [[state values residual]
            (scan-page
             state
             #(backend/invoke
               adapter :subject->resources
               (:intermediate-type rule) intermediate-id
               (:via-relation-eid rule) (first (:node rule)) %)
             work logical-chunk-size physical-chunk-size)
            successors (mapv #(grant rule %) values)]
        (schedule state successors work residual))

      :grant
      (let [state (schedule state (consumers-for plan (:node rule) resource-id))]
        (if (= root (:node rule))
          (-> state
              (update :results conj resource-id)
              (update :discovered inc))
          state))

      :consumer
      (case (:rule rule)
        :self-permission
        (schedule state [(grant rule resource-id)])

        :arrow-permission
        (let [[state values residual]
              (scan-page
               state
               #(backend/invoke
                 adapter :subject->resources
                 (:intermediate-type rule) resource-id
                 (:via-relation-eid rule) (first (:node rule)) %)
               work logical-chunk-size physical-chunk-size)
              successors (mapv #(grant rule %) values)]
          (schedule state successors work residual))))))

(defn run-forward
  [{:keys [adapter plan root subject-type subject-id target chunk-size
           logical-chunk-size physical-chunk-size
           drop-deferred-buffers? sidecar-buffer-cap]
    :or {chunk-size 64 sidecar-buffer-cap 16}}]
  (let [logical-chunk-size (or logical-chunk-size chunk-size)
        physical-chunk-size (or physical-chunk-size chunk-size)
        _
        (when-not (and (pos-int? logical-chunk-size)
                       (pos-int? physical-chunk-size)
                       (<= logical-chunk-size physical-chunk-size)
                       (int? sidecar-buffer-cap)
                       (<= 0 sidecar-buffer-cap))
          (throw
           (ex-info
            "Prototype chunk widths must satisfy 0 < logical <= physical."
            {:logical-chunk-size logical-chunk-size
             :physical-chunk-size physical-chunk-size})))
        seeds
        (mapv #(seed-work subject-type subject-id %)
              (get-in plan [:indexes :forward-seeds subject-type] []))
        initial
        (schedule
         {:stack []
          :admitted #{}
          :admissions 0
          :transitions 0
          :commands 0
          :fetched-values 0
          :sidecar {}
          :sidecar-oldest-first []
          :sidecar-buffer-cap sidecar-buffer-cap
          :maximum-sidecar-buffers 0
          :maximum-sidecar-values 0
          :drop-deferred-buffers? (boolean drop-deferred-buffers?)
          :maximum-stack 0
          :discovered 0
          :results []}
         seeds)]
    (loop [state initial]
      (if (or (>= (:discovered state) target)
              (empty? (:stack state)))
        (assoc state :completed
               (- (count (:admitted state)) (count (:stack state))))
        (recur
         (step adapter plan root state
               logical-chunk-size physical-chunk-size))))))

(defn prepare-adversarial!
  [group-count]
  (let [{:keys [conn query]} (baseline/seed-adversarial! group-count)
        db (ds/db conn)
        adapter
        (datascript-backend/snapshot-adapter
         db
         {:object-id->entid
          (fn [snapshot object-id]
            (ds/entid snapshot [:eacl/id object-id]))
          :entid->object-id
          (fn [snapshot internal-id]
            (:eacl/id (ds/entity snapshot internal-id)))
          :conn conn})
        root [:document :view]
        rules (compile-rules adapter root)
        plan (sealed/seal-plan rules root)
        subject-id
        (backend/invoke adapter :object-id->internal
                        (get-in query [:subject :id]))]
    {:conn conn
     :query query
     :adapter adapter
     :root root
     :plan plan
     :subject-type (get-in query [:subject :type])
     :subject-id subject-id
     :group-count group-count}))

(defn- prototype-once
  [{:keys [adapter plan root subject-type subject-id] :as prepared}]
  (let [allocated-before (allocated-bytes)
        started (System/nanoTime)
        run
        (run-forward
         {:adapter adapter
          :plan plan
          :root root
          :subject-type subject-type
          :subject-id subject-id
          :target 2
          :chunk-size 64})
        data
        (mapv
         (fn [internal-id]
           (eacl/spice-object
            :document
            (backend/invoke adapter :internal-id->object internal-id)))
         (take 1 (:results run)))
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
        allocated-after (allocated-bytes)]
    {:engine :forward-prototype
     :elapsed-ms elapsed-ms
     :allocated-bytes
     (when (and allocated-before allocated-after)
       (- allocated-after allocated-before))
     :data data
     :lookahead (mapv #(backend/invoke adapter :internal-id->object %)
                      (:results run))
     :has-next? (> (count (:results run)) 1)
     :commands (:commands run)
     :fetched-values (:fetched-values run)
     :admissions (:admissions run)
     :transitions (:transitions run)
     :completed (- (count (:admitted run)) (count (:stack run)))
     :maximum-stack (:maximum-stack run)}))

(defn- percentile
  [values fraction]
  (let [ordered (vec (sort values))
        index (min (dec (count ordered))
                   (max 0 (dec (long (Math/ceil (* fraction
                                                   (count ordered)))))))]
    (nth ordered index)))

(defn- summarize
  [engine runs]
  {:engine engine
   :commands
   (set
    (map
     (fn [run]
       (case engine
         :legacy (get-in run [:acyclic :backend-scans])
         :cost-stable-wrapper (:generated-commands run)
         :forward-prototype (:commands run)))
     runs))
   :median-ms (percentile (map :elapsed-ms runs) 0.5)
   :p95-ms (percentile (map :elapsed-ms runs) 0.95)
   :median-allocated-bytes
   (when (every? :allocated-bytes runs)
     (percentile (map :allocated-bytes runs) 0.5))
   :data (set (map :data runs))
   :has-next (set (map :has-next? runs))})

(defn benchmark-adversarial!
  ([group-count] (benchmark-adversarial! group-count 20))
  ([group-count samples]
   (let [{:keys [conn query] :as prepared}
         (prepare-adversarial! group-count)
         legacy-client (baseline-client-for conn :legacy)
         cost-client (baseline-client-for conn :cost-stable)
         _ (baseline-run-once conn query :legacy legacy-client false)
         _ (baseline-run-once conn query :cost-stable cost-client false)
         _ (prototype-once prepared)
         legacy
         (mapv (fn [_]
                 (baseline-run-once
                  conn query :legacy legacy-client false))
               (range samples))
         cost
         (mapv (fn [_]
                 (baseline-run-once
                  conn query :cost-stable cost-client false))
               (range samples))
         prototype (mapv (fn [_] (prototype-once prepared))
                         (range samples))
         summaries
         [(summarize :legacy legacy)
          (summarize :cost-stable-wrapper cost)
          (summarize :forward-prototype prototype)]
         expected
         #{[(eacl/spice-object :document "document-direct-1")]
           [(eacl/spice-object :document "document-direct-2")]}
         prototype-pages (set (map :data prototype))]
     (when-not (and (= 1 (count prototype-pages))
                    (contains? expected (first prototype-pages))
                    (= #{true} (set (map :has-next? prototype)))
                    (= #{1} (set (map :commands prototype))))
       (throw (ex-info "Forward prototype benchmark failed correctness/work gate."
                       {:summaries summaries
                        :prototype (take 3 prototype)})))
     {:group-count group-count
      :samples samples
      :plan-rule-count (count (get-in prepared [:plan :rules]))
      :plan-fingerprint (get-in prepared [:plan :fingerprint])
      :summaries summaries
      :prototype-logical
      (select-keys (first prototype)
                   [:lookahead :commands :fetched-values :admissions
                    :transitions :completed :maximum-stack])})))

(defn- relation-resources
  "Fixture-level fact lookup used only by the independent denotation oracle."
  [facts relation subject]
  (into #{}
        (comp
         (filter #(and (= relation (:relation %))
                       (= subject (:subject %))))
         (map :resource))
        facts))

(defn- inherited-resources
  [facts relation allowed-subjects]
  (into #{}
        (comp
         (filter #(and (= relation (:relation %))
                       (contains? allowed-subjects (:subject %))))
         (map :resource))
        facts))

(defn- converge
  [initial advance]
  (loop [value initial]
    (let [next-value (advance value)]
      (if (= value next-value)
        value
        (recur next-value)))))

(defn- fixture-denotation
  "Independent least-fixed-point oracle for every recursive fixture schema.

  It reads only the public fixture relationships and the three fixture schema
  definitions.  It does not call the EACL compiler, reducer, adapter, or public
  lookup API."
  [config principal]
  (let [facts (vec (recursive-fixture/relationships config))
        owner (relation-resources facts :owner principal)
        children #(inherited-resources facts :parent %)
        result
        (case (:shape config)
          :mutual
          (:view
           (converge
            {:view owner
             :edit (relation-resources facts :editor principal)}
            (fn [{:keys [view edit]}]
              {:view (into owner (children edit))
               :edit (into (relation-resources facts :editor principal)
                           (children view))})))

          :broad-union
          (let [reader (relation-resources facts :reader principal)
                legal-view (relation-resources facts :admin principal)
                legal-accounts
                (inherited-resources facts :legal_entity legal-view)]
            (:read
             (converge
              {:administer owner
               :read (into (into owner reader) legal-accounts)}
              (fn [{:keys [administer read]}]
                (let [next-administer (into owner (children administer))]
                  {:administer next-administer
                   :read (into (into (into next-administer reader)
                                     legal-accounts)
                               (children read))})))))

          ;; star, chain, mixed, cycle, and diamond share view semantics.
          (converge owner #(into owner (children %))))]
    (into #{} (map :id) result)))

(defn prepare-recursive!
  [config subject]
  (let [{:keys [conn]} (baseline/seed-recursive! config)
        db (ds/db conn)
        adapter
        (datascript-backend/snapshot-adapter
         db
         {:object-id->entid
          (fn [snapshot object-id]
            (ds/entid snapshot [:eacl/id object-id]))
          :entid->object-id
          (fn [snapshot internal-id]
            (:eacl/id (ds/entity snapshot internal-id)))
          :conn conn})
        permission (recursive-fixture/view-permission config)
        root [:account permission]
        plan (sealed/seal-plan (compile-rules adapter root) root)
        subject-id
        (backend/invoke adapter :object-id->internal (:id subject))]
    {:conn conn
     :config config
     :subject subject
     :adapter adapter
     :root root
     :plan plan
     :subject-type (:type subject)
     :subject-id subject-id
     :oracle (fixture-denotation config subject)}))

(defn- prototype-recursive-once
  ([prepared page-size]
   (prototype-recursive-once prepared page-size 64 64))
  ([{:keys [adapter plan root subject-type subject-id] :as prepared}
    page-size logical-chunk-size physical-chunk-size]
   (prototype-recursive-once
    prepared page-size logical-chunk-size physical-chunk-size false))
  ([{:keys [adapter plan root subject-type subject-id] :as prepared}
    page-size logical-chunk-size physical-chunk-size
    drop-deferred-buffers?]
   (let [allocated-before (allocated-bytes)
         started (System/nanoTime)
         run
         (run-forward
          {:adapter adapter
           :plan plan
           :root root
           :subject-type subject-type
           :subject-id subject-id
           :target (inc page-size)
           :logical-chunk-size logical-chunk-size
           :physical-chunk-size physical-chunk-size
           :drop-deferred-buffers? drop-deferred-buffers?})
         object-ids
         (mapv #(backend/invoke adapter :internal-id->object %)
               (:results run))
         elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
         allocated-after (allocated-bytes)]
     {:engine :forward-prototype
      :logical-chunk-size logical-chunk-size
      :physical-chunk-size physical-chunk-size
      :drop-deferred-buffers? drop-deferred-buffers?
      :sidecar-buffer-cap (:sidecar-buffer-cap run)
      :elapsed-ms elapsed-ms
      :allocated-bytes
      (when (and allocated-before allocated-after)
        (- allocated-after allocated-before))
      :data (subvec object-ids 0 (min page-size (count object-ids)))
      :lookahead object-ids
      :has-next? (> (count object-ids) page-size)
      :commands (:commands run)
      :fetched-values (:fetched-values run)
      :maximum-sidecar-buffers (:maximum-sidecar-buffers run)
      :maximum-sidecar-values (:maximum-sidecar-values run)
      :admissions (:admissions run)
      :transitions (:transitions run)
      :completed (:completed run)
      :maximum-stack (:maximum-stack run)})))

(defn- measured-baseline-recursive-once
  [{:keys [conn config subject]} engine-kind page-size]
  (let [allocated-before (allocated-bytes)
        result (baseline-run-recursive-page
                conn config subject engine-kind page-size)
        allocated-after (allocated-bytes)]
    (assoc result :allocated-bytes
           (when (and allocated-before allocated-after)
             (- allocated-after allocated-before)))))

(defn- recursive-summary
  [engine runs]
  {:engine engine
   :commands (set (map :commands runs))
   :median-ms (percentile (map :elapsed-ms runs) 0.5)
   :p95-ms (percentile (map :elapsed-ms runs) 0.95)
   :median-allocated-bytes
   (when (every? :allocated-bytes runs)
     (percentile (map :allocated-bytes runs) 0.5))
   :pages (set (map :data runs))
   :has-next (set (map :has-next? runs))})

(def ^:private recursive-qualification-cases
  [{:config {:shape :star :accounts 200}
    :subject recursive-fixture/user-1}
   {:config {:shape :chain :accounts 200}
    :subject recursive-fixture/user-1}
   {:config {:shape :broad-union :accounts 200}
    :subject recursive-fixture/reader-1}
   {:config {:shape :broad-union :accounts 200}
    :subject recursive-fixture/le-viewer}
   {:config {:shape :star :accounts 200}
    :subject recursive-fixture/stranger}
   {:config {:shape :mutual :accounts 100}
    :subject recursive-fixture/user-1}
   {:config {:shape :mixed :accounts 201 :chains 10}
    :subject recursive-fixture/user-1}
   {:config {:shape :cycle :accounts 31}
    :subject recursive-fixture/user-1}
   {:config {:shape :diamond :accounts 4}
    :subject recursive-fixture/user-1}])

(defn qualify-recursive!
  "Checks stable pages and full exact denotations, then returns frozen-engine
  and prototype first-page work.  `samples` is deliberately small because the
  acceptance signal is logical work/allocation, not microbenchmark theatre."
  ([] (qualify-recursive! 5))
  ([samples]
   (mapv
    (fn [{:keys [config subject]}]
      (let [{:keys [oracle] :as prepared}
            (prepare-recursive! config subject)
            page-size 1
            expected-count (count oracle)
            full-size (inc expected-count)
            _ (prototype-recursive-once prepared page-size)
            _ (doseq [engine [:legacy :byte-stable :cost-stable]]
                (measured-baseline-recursive-once
                 prepared engine page-size))
            prototype-runs
            (mapv (fn [_]
                    (prototype-recursive-once prepared page-size))
                  (range samples))
            baseline-runs
            (into {}
                  (map
                   (fn [engine]
                     [engine
                      (mapv
                       (fn [_]
                         (let [run
                               (measured-baseline-recursive-once
                                prepared engine page-size)]
                           (assoc run :commands (:backend-commands run))))
                       (range samples))])
                   [:legacy :byte-stable :cost-stable]))
            prototype-full (prototype-recursive-once prepared full-size)
            baseline-full
            (into {}
                  (map
                   (fn [engine]
                     [engine
                      (measured-baseline-recursive-once
                       prepared engine full-size)])
                   [:legacy :byte-stable :cost-stable]))
            prototype-full-set (set (:data prototype-full))
            baseline-full-sets
            (into {}
                  (map (fn [[engine run]]
                         [engine (into #{} (map :id) (:data run))]))
                  baseline-full)
            stable-prototype?
            (= 1
               (count
                (set
                 (map #(select-keys % [:data :lookahead :has-next?
                                       :commands :fetched-values
                                       :admissions :transitions :completed
                                       :maximum-stack])
                      prototype-runs))))]
        (when-not
         (and stable-prototype?
              (= oracle prototype-full-set)
              (every? #(= oracle %) (vals baseline-full-sets))
              (= (min page-size expected-count)
                 (count (:data (first prototype-runs))))
              (= (> expected-count page-size)
                 (:has-next? (first prototype-runs))))
         (throw
          (ex-info
           "Recursive prototype failed exact-denotation or stability gate."
           {:config config
            :subject subject
            :oracle oracle
            :prototype-full prototype-full-set
            :baseline-full baseline-full-sets
            :prototype-runs prototype-runs})))
        {:config config
         :subject-id (:id subject)
         :expected-count expected-count
         :plan-rules (count (get-in prepared [:plan :rules]))
         :plan-fingerprint (get-in prepared [:plan :fingerprint])
         :summaries
         (conj
          (mapv (fn [engine]
                  (recursive-summary engine (get baseline-runs engine)))
                [:legacy :byte-stable :cost-stable])
          (recursive-summary :forward-prototype prototype-runs))
         :full-commands
         (assoc
          (into {}
                (map (fn [[engine run]]
                       [engine (:backend-commands run)]))
                baseline-full)
          :forward-prototype (:commands prototype-full))
         :prototype-full
         (select-keys prototype-full
                      [:commands :fetched-values :admissions :transitions
                       :completed
                       :maximum-stack])}))
    recursive-qualification-cases)))

(defn- reverse-rule-work
  [requested-subject-type resource-id rule]
  (case (:rule rule)
    :relation
    (when (= requested-subject-type (:subject-type rule))
      {:kind :reverse-direct
       :rule rule
       :resource-id resource-id
       :bound-eid nil})

    :arrow-relation
    (when (= requested-subject-type (:target-subject-type rule))
      {:kind :reverse-via-relation
       :rule rule
       :resource-id resource-id
       :bound-eid nil})

    :self-permission
    {:kind :reverse-goal
     :rule {:node (:target-node rule)}
     :resource-id resource-id}

    :arrow-permission
    {:kind :reverse-via-permission
     :rule rule
     :resource-id resource-id
     :bound-eid nil}))

(defn- reverse-subject
  [subject-type subject-id]
  {:kind :reverse-subject
   :subject-type subject-type
   :subject-id subject-id})

(defn- reverse-step
  [adapter plan requested-subject-type state
   logical-chunk-size physical-chunk-size]
  (let [work (peek (:stack state))
        state (-> state
                  (update :stack pop)
                  (update :transitions inc))
        {:keys [kind rule subject-type subject-id intermediate-id resource-id]}
        work]
    (case kind
      :reverse-goal
      (schedule
       state
       (into []
             (keep #(reverse-rule-work
                     requested-subject-type resource-id %))
             (get-in plan [:indexes :reverse-rules (:node rule)] [])))

      :reverse-direct
      (let [[state values residual]
            (scan-page
             state
             #(backend/invoke
               adapter :resource->subjects
               (:resource-type rule) resource-id (:relation-eid rule)
               (:subject-type rule) %)
             work logical-chunk-size physical-chunk-size)
            successors
            (mapv #(reverse-subject (:subject-type rule) %) values)]
        (schedule state successors work residual))

      :reverse-via-permission
      (let [[state values residual]
            (scan-page
             state
             #(backend/invoke
               adapter :resource->subjects
               (:resource-type rule) resource-id (:via-relation-eid rule)
               (:intermediate-type rule) %)
             work logical-chunk-size physical-chunk-size)
            successors
            (mapv (fn [intermediate]
                    {:kind :reverse-goal
                     :rule {:node (:target-node rule)}
                     :resource-id intermediate})
                  values)]
        (schedule state successors work residual))

      :reverse-via-relation
      (let [[state values residual]
            (scan-page
             state
             #(backend/invoke
               adapter :resource->subjects
               (:resource-type rule) resource-id (:via-relation-eid rule)
               (:intermediate-type rule) %)
             work logical-chunk-size physical-chunk-size)
            successors
            (mapv (fn [intermediate]
                    {:kind :reverse-base-subjects
                     :rule rule
                     :intermediate-id intermediate
                     :bound-eid nil})
                  values)]
        (schedule state successors work residual))

      :reverse-base-subjects
      (let [[state values residual]
            (scan-page
             state
             #(backend/invoke
               adapter :resource->subjects
               (:intermediate-type rule) intermediate-id
               (:target-relation-eid rule)
               (:target-subject-type rule) %)
             work logical-chunk-size physical-chunk-size)
            successors
            (mapv #(reverse-subject (:target-subject-type rule) %) values)]
        (schedule state successors work residual))

      :reverse-subject
      (-> state
          (update :results conj subject-id)
          (update :discovered inc)))))

(defn run-reverse
  "Minimal reverse traversal: one visited goal set, one work stack, and one
  emitted-subject set.  There are deliberately no dynamic goal cells or peer
  joins."
  [{:keys [adapter plan root resource-id subject-type target chunk-size
           logical-chunk-size physical-chunk-size
           drop-deferred-buffers? sidecar-buffer-cap]
    :or {chunk-size 64 sidecar-buffer-cap 16}}]
  (let [logical-chunk-size (or logical-chunk-size chunk-size)
        physical-chunk-size (or physical-chunk-size chunk-size)
        _
        (when-not (and (pos-int? logical-chunk-size)
                       (pos-int? physical-chunk-size)
                       (<= logical-chunk-size physical-chunk-size)
                       (int? sidecar-buffer-cap)
                       (<= 0 sidecar-buffer-cap))
          (throw
           (ex-info
            "Prototype chunk widths must satisfy 0 < logical <= physical."
            {:logical-chunk-size logical-chunk-size
             :physical-chunk-size physical-chunk-size})))
        initial
        (schedule
         {:stack []
          :admitted #{}
          :admissions 0
          :transitions 0
          :commands 0
          :fetched-values 0
          :sidecar {}
          :sidecar-oldest-first []
          :sidecar-buffer-cap sidecar-buffer-cap
          :maximum-sidecar-buffers 0
          :maximum-sidecar-values 0
          :drop-deferred-buffers? (boolean drop-deferred-buffers?)
          :maximum-stack 0
          :discovered 0
          :results []}
         [{:kind :reverse-goal
           :rule {:node root}
           :resource-id resource-id}])]
    (loop [state initial]
      (if (or (>= (:discovered state) target)
              (empty? (:stack state)))
        (assoc state :completed
               (- (count (:admitted state)) (count (:stack state))))
        (recur
         (reverse-step adapter plan subject-type state
                       logical-chunk-size physical-chunk-size))))))

(defn- fixture-reverse-denotation
  [config resource subject-type]
  (into #{}
        (comp
         (filter #(= subject-type (:type %)))
         (filter #(contains? (fixture-denotation config %) (:id resource)))
         (map :id))
        (recursive-fixture/objects config)))

(defn prepare-recursive-reverse!
  [config resource subject-type]
  (let [{:keys [conn]} (baseline/seed-recursive! config)
        db (ds/db conn)
        adapter
        (datascript-backend/snapshot-adapter
         db
         {:object-id->entid
          (fn [snapshot object-id]
            (ds/entid snapshot [:eacl/id object-id]))
          :entid->object-id
          (fn [snapshot internal-id]
            (:eacl/id (ds/entity snapshot internal-id)))
          :conn conn})
        permission (recursive-fixture/view-permission config)
        root [:account permission]
        plan (sealed/seal-plan (compile-rules adapter root) root)
        resource-id
        (backend/invoke adapter :object-id->internal (:id resource))]
    {:conn conn
     :config config
     :resource resource
     :subject-type subject-type
     :adapter adapter
     :root root
     :plan plan
     :resource-id resource-id
     :oracle (fixture-reverse-denotation config resource subject-type)}))

(defn- prototype-reverse-once
  ([prepared page-size]
   (prototype-reverse-once prepared page-size 64 64))
  ([{:keys [adapter plan root resource-id subject-type]}
    page-size logical-chunk-size physical-chunk-size]
   (prototype-reverse-once
    {:adapter adapter
     :plan plan
     :root root
     :resource-id resource-id
     :subject-type subject-type}
    page-size logical-chunk-size physical-chunk-size false))
  ([{:keys [adapter plan root resource-id subject-type]}
    page-size logical-chunk-size physical-chunk-size
    drop-deferred-buffers?]
   (let [allocated-before (allocated-bytes)
         started (System/nanoTime)
         run
         (run-reverse
          {:adapter adapter
           :plan plan
           :root root
           :resource-id resource-id
           :subject-type subject-type
           :target (inc page-size)
           :logical-chunk-size logical-chunk-size
           :physical-chunk-size physical-chunk-size
           :drop-deferred-buffers? drop-deferred-buffers?})
         object-ids
         (mapv #(backend/invoke adapter :internal-id->object %)
               (:results run))
         elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
         allocated-after (allocated-bytes)]
     {:engine :reverse-prototype
      :logical-chunk-size logical-chunk-size
      :physical-chunk-size physical-chunk-size
      :drop-deferred-buffers? drop-deferred-buffers?
      :sidecar-buffer-cap (:sidecar-buffer-cap run)
      :elapsed-ms elapsed-ms
      :allocated-bytes
      (when (and allocated-before allocated-after)
        (- allocated-after allocated-before))
      :data (subvec object-ids 0 (min page-size (count object-ids)))
      :lookahead object-ids
      :has-next? (> (count object-ids) page-size)
      :commands (:commands run)
      :fetched-values (:fetched-values run)
      :maximum-sidecar-buffers (:maximum-sidecar-buffers run)
      :maximum-sidecar-values (:maximum-sidecar-values run)
      :admissions (:admissions run)
      :transitions (:transitions run)
      :completed (:completed run)
      :maximum-stack (:maximum-stack run)})))

(defn- baseline-reverse-once
  [{:keys [conn config resource subject-type] :as prepared}
   engine-kind client page-size]
  (let [query {:resource resource
               :permission (recursive-fixture/view-permission config)
               :subject/type subject-type
               :first page-size
               :cache? false}
        acyclic (atom {})
        generated (atom {})
        trace (atom [])
        allocated-before (allocated-bytes)
        started (System/nanoTime)
        result
        (binding [engine/*acyclic-work-stats* acyclic
                  engine/*recursive-traversal-stats* generated
                  engine/*execution-trace* trace]
          (eacl/lookup-subjects client query))
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
        allocated-after (allocated-bytes)
        commands (filter #(= :generated-command (:event %)) @trace)]
    {:engine engine-kind
     :elapsed-ms elapsed-ms
     :allocated-bytes
     (when (and allocated-before allocated-after)
       (- allocated-after allocated-before))
     :data (:data result)
     :has-next? (get-in result [:page-info :has-next-page?])
     :commands
     (if (= :legacy engine-kind)
       (or (get-in @generated
                   [:generated-dimensional-counters :backend-commands])
           (:backend-scans @acyclic))
       (count commands))}))

(def ^:private reverse-qualification-cases
  [{:config {:shape :star :accounts 200} :resource-index 199}
   {:config {:shape :chain :accounts 200} :resource-index 199}
   {:config {:shape :broad-union :accounts 200} :resource-index 199}
   {:config {:shape :mutual :accounts 100} :resource-index 98}
   {:config {:shape :mutual :accounts 100} :resource-index 99}
   {:config {:shape :mixed :accounts 201 :chains 10} :resource-index 200}
   {:config {:shape :cycle :accounts 31} :resource-index 30}
   {:config {:shape :diamond :accounts 4} :resource-index 3}])

(defn qualify-reverse!
  ([] (qualify-reverse! 5))
  ([samples]
   (mapv
    (fn [{:keys [config resource-index]}]
      (let [resource
            (recursive-fixture/object
             :account (recursive-fixture/account-id resource-index))
            {:keys [conn oracle root] :as prepared}
            (prepare-recursive-reverse! config resource :user)
            root-map
            (baseline-canonical-root
             (first root) (second root))
            clients
            (into {}
                  (map (fn [engine]
                         [engine
                          (baseline-client-for conn engine root-map)]))
                  [:legacy :byte-stable :cost-stable])
            page-size 1
            expected-count (count oracle)
            full-size (inc expected-count)
            _ (prototype-reverse-once prepared page-size)
            _ (doseq [engine [:legacy :byte-stable :cost-stable]]
                (baseline-reverse-once
                 prepared engine (get clients engine) page-size))
            prototype-runs
            (mapv (fn [_] (prototype-reverse-once prepared page-size))
                  (range samples))
            baseline-runs
            (into {}
                  (map
                   (fn [engine]
                     [engine
                      (mapv
                       (fn [_]
                         (baseline-reverse-once
                          prepared engine (get clients engine) page-size))
                       (range samples))])
                   [:legacy :byte-stable :cost-stable]))
            prototype-full (prototype-reverse-once prepared full-size)
            baseline-full
            (into {}
                  (map
                   (fn [engine]
                     [engine
                      (baseline-reverse-once
                       prepared engine (get clients engine) full-size)])
                   [:legacy :byte-stable :cost-stable]))
            prototype-set (set (:data prototype-full))
            baseline-sets
            (into {}
                  (map (fn [[engine run]]
                         [engine (into #{} (map :id) (:data run))]))
                  baseline-full)
            stable?
            (= 1
               (count
                (set
                 (map #(select-keys % [:data :lookahead :has-next?
                                       :commands :fetched-values
                                       :admissions :transitions :completed
                                       :maximum-stack])
                      prototype-runs))))]
        (when-not
         (and stable?
              (= oracle prototype-set)
              (every? #(= oracle %) (vals baseline-sets))
              (= (min page-size expected-count)
                 (count (:data (first prototype-runs))))
              (= (> expected-count page-size)
                 (:has-next? (first prototype-runs))))
         (throw
          (ex-info
           "Static reverse prototype failed exact-denotation or stability gate."
           {:config config
            :resource resource
            :oracle oracle
            :prototype prototype-set
            :baseline baseline-sets
            :runs prototype-runs})))
        {:config config
         :resource-index resource-index
         :expected-count expected-count
         :summaries
         (conj
          (mapv (fn [engine]
                  (recursive-summary engine (get baseline-runs engine)))
                [:legacy :byte-stable :cost-stable])
          (recursive-summary :reverse-prototype prototype-runs))
         :full-commands
         (assoc
          (into {}
                (map (fn [[engine run]] [engine (:commands run)]))
                baseline-full)
          :reverse-prototype (:commands prototype-full))
         :prototype-full
         (select-keys prototype-full
                      [:commands :fetched-values :admissions :transitions
                       :completed
                       :maximum-stack])}))
    reverse-qualification-cases)))

(defn- prepare-custom!
  [schema objects relationships root]
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:cache {:remember-answers false}
          :source-lifecycle (str "stable-edge-" (random-uuid))})]
    (eacl/write-schema! client schema)
    (ds/transact!
     conn
     (mapv (fn [index {:keys [id]}]
             {:db/id (str "stable-edge-object-" index)
              :eacl/id id})
           (range)
           objects))
    (eacl/create-relationships! client relationships)
    (let [db (ds/db conn)
          adapter
          (datascript-backend/snapshot-adapter
           db
           {:object-id->entid
            (fn [snapshot object-id]
              (ds/entid snapshot [:eacl/id object-id]))
            :entid->object-id
            (fn [snapshot internal-id]
              (:eacl/id (ds/entity snapshot internal-id)))
            :conn conn})]
      {:conn conn
       :adapter adapter
       :root root
       :plan (sealed/seal-plan (compile-rules adapter root) root)})))

(defn- full-forward-ids
  ([prepared subject]
   (full-forward-ids prepared subject 2))
  ([prepared subject chunk-size]
   (full-forward-ids prepared subject chunk-size chunk-size))
  ([{:keys [adapter plan root]} subject
    logical-chunk-size physical-chunk-size]
   (let [subject-id
         (backend/invoke adapter :object-id->internal (:id subject))
         run
         (run-forward
          {:adapter adapter
           :plan plan
           :root root
           :subject-type (:type subject)
           :subject-id subject-id
           :target 1000
           :logical-chunk-size logical-chunk-size
           :physical-chunk-size physical-chunk-size})]
     {:ids (mapv #(backend/invoke adapter :internal-id->object %)
                 (:results run))
      :run run})))

(defn- full-reverse-ids
  [{:keys [adapter plan root]} resource subject-type]
  (let [resource-id
        (backend/invoke adapter :object-id->internal (:id resource))
        run
        (run-reverse
         {:adapter adapter
          :plan plan
          :root root
          :resource-id resource-id
          :subject-type subject-type
          :target 1000
          :chunk-size 2})]
    {:ids (mapv #(backend/invoke adapter :internal-id->object %)
                (:results run))
     :run run}))

(defn qualify-static-edge-cases!
  "Focused source cases outside the recursive fixture family: zero-cost alias
  cycles, overlapping arrow-to-relation derivations, multiple accepted subject
  types, and the deliberate chunk-width ordering counterexample."
  []
  (let [object #(eacl/spice-object %1 %2)
        relationship #(eacl/->Relationship %1 %2 %3)
        user-1 (object :user "user-1")
        user-2 (object :user "user-2")

        alias-account (object :account "alias-account")
        alias
        (prepare-custom!
         "definition user {}
          definition account {
            relation owner: user
            permission base = owner
            permission left = base + right
            permission right = left
          }"
         [user-1 user-2 alias-account]
         [(relationship user-1 :owner alias-account)]
         [:account :left])
        alias-forward (full-forward-ids alias user-1)
        alias-reverse (full-reverse-ids alias alias-account :user)

        team-a (object :team "team-a")
        team-b (object :team "team-b")
        document-1 (object :document "document-1")
        document-2 (object :document "document-2")
        arrow
        (prepare-custom!
         "definition user {}
          definition team {
            relation member: user
          }
          definition document {
            relation parent: team
            permission view = parent->member
          }"
         [user-1 user-2 team-a team-b document-1 document-2]
         [(relationship user-1 :member team-a)
          (relationship user-2 :member team-a)
          (relationship user-1 :member team-b)
          (relationship team-a :parent document-1)
          (relationship team-b :parent document-1)
          (relationship team-b :parent document-2)]
         [:document :view])
        arrow-forward-1 (full-forward-ids arrow user-1)
        arrow-forward-2 (full-forward-ids arrow user-2)
        arrow-reverse-1 (full-reverse-ids arrow document-1 :user)
        arrow-reverse-2 (full-reverse-ids arrow document-2 :user)

        robot-1 (object :robot "robot-1")
        service-1 (object :service "service-1")
        multiple-types
        (prepare-custom!
         "definition user {}
          definition robot {}
          definition service {
            relation viewer: user | robot
            permission view = viewer
          }"
         [user-1 robot-1 service-1]
         [(relationship user-1 :viewer service-1)
          (relationship robot-1 :viewer service-1)]
         [:service :view])
        user-forward (full-forward-ids multiple-types user-1)
        robot-forward (full-forward-ids multiple-types robot-1)
        user-reverse (full-reverse-ids multiple-types service-1 :user)
        robot-reverse (full-reverse-ids multiple-types service-1 :robot)

        overlap-account-a (object :account "overlap-a")
        overlap-account-b (object :account "overlap-b")
        overlap-account-c (object :account "overlap-c")
        chunk-overlap
        (prepare-custom!
         "definition user {}
          definition account {
            relation owner: user
            relation parent: account
            permission view = owner + parent->view
          }"
         [user-1 overlap-account-a overlap-account-b overlap-account-c]
         [(relationship user-1 :owner overlap-account-a)
          (relationship user-1 :owner overlap-account-b)
          (relationship user-1 :owner overlap-account-c)
          (relationship overlap-account-a :parent overlap-account-c)
          (relationship overlap-account-c :parent overlap-account-b)]
         [:account :view])
        chunk-one (full-forward-ids chunk-overlap user-1 1)
        chunk-wide (full-forward-ids chunk-overlap user-1 64)
        normalized-wide-read
        (full-forward-ids chunk-overlap user-1 1 64)
        chunk-observed {:chunk-one (:ids chunk-one)
                        :chunk-wide (:ids chunk-wide)
                        :logical-one-physical-wide
                        (:ids normalized-wide-read)
                        :commands
                        {:physical-one (get-in chunk-one [:run :commands])
                         :physical-wide
                         (get-in normalized-wide-read [:run :commands])}}
        observed
        {:alias {:forward (set (:ids alias-forward))
                 :reverse (set (:ids alias-reverse))}
         :arrow {:user-1-forward (set (:ids arrow-forward-1))
                 :user-2-forward (set (:ids arrow-forward-2))
                 :document-1-reverse (set (:ids arrow-reverse-1))
                 :document-2-reverse (set (:ids arrow-reverse-2))}
         :multiple-types
         {:user-forward (set (:ids user-forward))
          :robot-forward (set (:ids robot-forward))
          :user-reverse (set (:ids user-reverse))
          :robot-reverse (set (:ids robot-reverse))}}
        expected
        {:alias {:forward #{"alias-account"}
                 :reverse #{"user-1"}}
         :arrow {:user-1-forward #{"document-1" "document-2"}
                 :user-2-forward #{"document-1"}
                 :document-1-reverse #{"user-1" "user-2"}
                 :document-2-reverse #{"user-1"}}
         :multiple-types
         {:user-forward #{"service-1"}
          :robot-forward #{"service-1"}
          :user-reverse #{"user-1"}
          :robot-reverse #{"robot-1"}}}]
    (when-not (and (= expected observed)
                   (= #{"overlap-a" "overlap-b" "overlap-c"}
                      (set (:ids chunk-one))
                      (set (:ids chunk-wide)))
                   (= ["overlap-a" "overlap-c" "overlap-b"]
                      (:ids chunk-one)
                      (:ids normalized-wide-read))
                   (= ["overlap-a" "overlap-b" "overlap-c"]
                      (:ids chunk-wide))
                   (< (get-in normalized-wide-read [:run :commands])
                      (get-in chunk-one [:run :commands])))
      (throw
       (ex-info "Static edge-case qualification failed."
                {:expected expected
                 :observed observed
                 :chunk-observed chunk-observed})))
    {:cases 4
     :checks 16
     :observed (assoc observed :chunk-overlap chunk-observed)
     :work
     {:alias-forward (select-keys (:run alias-forward)
                                  [:commands :admissions :transitions
                                   :completed])
      :alias-reverse (select-keys (:run alias-reverse)
                                  [:commands :admissions :transitions
                                   :completed])
      :overlap-forward (select-keys (:run arrow-forward-1)
                                    [:commands :admissions :transitions
                                     :completed])
      :overlap-reverse (select-keys (:run arrow-reverse-1)
                                    [:commands :admissions :transitions
                                     :completed])}}))

(defn qualify-one-value-width-invariance!
  "Checks that one-value logical normalization produces one exact discovery
  sequence while physical fetch widths vary and while every deferred buffer
  is deliberately dematerialized.  This is the source-shaped counterpart to
  the flattened-chunk, logical-cursor, and one-value-normalization proofs."
  []
  (let [widths [1 2 7 64]
        forward
        (mapv
         (fn [{:keys [config subject]}]
           (let [{:keys [oracle] :as prepared}
                 (prepare-recursive! config subject)
                 page-size (inc (count oracle))
                 runs
                 (into {}
                       (map
                        (fn [width]
                          [width
                           (prototype-recursive-once
                            prepared page-size 1 width)]))
                       widths)
                 dropped-runs
                 (into {}
                       (map
                        (fn [width]
                          [width
                           (prototype-recursive-once
                            prepared page-size 1 width true)]))
                       widths)
                 sequences (mapv #(get-in runs [% :data]) widths)
                 dropped-sequences
                 (mapv #(get-in dropped-runs [% :data]) widths)
                 all-sequences (into sequences dropped-sequences)]
             (when-not
              (and (= 1 (count (set all-sequences)))
                   (every? #(= oracle (set %)) all-sequences)
                   (every? (comp zero? :maximum-sidecar-buffers)
                           (vals dropped-runs))
                   (every? (comp zero? :maximum-sidecar-values)
                           (vals dropped-runs)))
              (throw
               (ex-info
                "Forward one-value normalization changed across physical widths."
                {:config config
                 :subject subject
                 :oracle oracle
                 :retained-sequences (zipmap widths sequences)
                 :dematerialized-sequences
                 (zipmap widths dropped-sequences)})))
             {:config config
              :subject-id (:id subject)
              :results (count oracle)
              :commands
              {:retained
               (into {}
                     (map (fn [[width run]] [width (:commands run)]))
                     runs)
               :dematerialized
               (into {}
                     (map (fn [[width run]] [width (:commands run)]))
                     dropped-runs)}
              :transitions (set (map :transitions (vals runs)))
              :dematerialized-transitions
              (set (map :transitions (vals dropped-runs)))
              :maximum-sidecar-buffers
              (set (map :maximum-sidecar-buffers (vals runs)))
              :maximum-sidecar-values
              (set (map :maximum-sidecar-values (vals runs)))
              :maximum-stack (set (map :maximum-stack (vals runs)))}))
         recursive-qualification-cases)
        reverse
        (mapv
         (fn [{:keys [config resource-index]}]
           (let [resource
                 (recursive-fixture/object
                  :account (recursive-fixture/account-id resource-index))
                 {:keys [oracle] :as prepared}
                 (prepare-recursive-reverse! config resource :user)
                 page-size (inc (count oracle))
                 runs
                 (into {}
                       (map
                        (fn [width]
                          [width
                           (prototype-reverse-once
                            prepared page-size 1 width)]))
                       widths)
                 dropped-runs
                 (into {}
                       (map
                        (fn [width]
                          [width
                           (prototype-reverse-once
                            prepared page-size 1 width true)]))
                       widths)
                 sequences (mapv #(get-in runs [% :data]) widths)
                 dropped-sequences
                 (mapv #(get-in dropped-runs [% :data]) widths)
                 all-sequences (into sequences dropped-sequences)]
             (when-not
              (and (= 1 (count (set all-sequences)))
                   (every? #(= oracle (set %)) all-sequences)
                   (every? (comp zero? :maximum-sidecar-buffers)
                           (vals dropped-runs))
                   (every? (comp zero? :maximum-sidecar-values)
                           (vals dropped-runs)))
              (throw
               (ex-info
                "Reverse one-value normalization changed across physical widths."
                {:config config
                 :resource resource
                 :oracle oracle
                 :retained-sequences (zipmap widths sequences)
                 :dematerialized-sequences
                 (zipmap widths dropped-sequences)})))
             {:config config
              :resource-index resource-index
              :results (count oracle)
              :commands
              {:retained
               (into {}
                     (map (fn [[width run]] [width (:commands run)]))
                     runs)
               :dematerialized
               (into {}
                     (map (fn [[width run]] [width (:commands run)]))
                     dropped-runs)}
              :transitions (set (map :transitions (vals runs)))
              :dematerialized-transitions
              (set (map :transitions (vals dropped-runs)))
              :maximum-sidecar-buffers
              (set (map :maximum-sidecar-buffers (vals runs)))
              :maximum-sidecar-values
              (set (map :maximum-sidecar-values (vals runs)))
              :maximum-stack (set (map :maximum-stack (vals runs)))}))
         reverse-qualification-cases)]
    {:logical-chunk-size 1
     :physical-widths widths
     :cases (+ (count forward) (count reverse))
     :checks (* (+ (count forward) (count reverse))
                (inc (* 2 (count widths))))
     :forward forward
     :reverse reverse}))

(defn qualify-sidecar-cap!
  "Drives an adversarial deep sequence of distinct deferred physical buffers
  through the source-shaped newest-retained sidecar.  Values are shared so the
  check measures the retention policy rather than allocating a synthetic
  multi-gigabyte payload."
  []
  (let [depth 100000
        capacities [0 1 4 16]
        shared-values (vec (range 63))
        reports
        (mapv
         (fn [capacity]
           (let [{:keys [sidecar oldest-first maximum]}
                 (loop [index 0
                        sidecar {}
                        oldest-first []
                        maximum 0]
                   (if (= index depth)
                     {:sidecar sidecar
                      :oldest-first oldest-first
                      :maximum maximum}
                     (let [identity [:scan index]
                           retained
                           (retain-sidecar-entry
                            sidecar oldest-first identity
                            {:logical-bound index
                             :values shared-values
                             :more-physical? true}
                            capacity)
                           sidecar (:sidecar retained)
                           oldest-first (:oldest-first retained)]
                       (when (> (count sidecar) capacity)
                         (throw
                          (ex-info "Sidecar capacity exceeded."
                                   {:capacity capacity
                                    :index index
                                    :retained (count sidecar)})))
                       (recur (inc index)
                              sidecar
                              oldest-first
                              (max maximum (count sidecar))))))
                 expected-identities
                 (mapv #(vector :scan %)
                       (range (- depth (min depth capacity)) depth))]
             (when-not
              (and (= expected-identities oldest-first)
                   (= (set expected-identities) (set (keys sidecar)))
                   (= (min depth capacity) maximum))
               (throw
                (ex-info "Newest-sidecar retention refinement failed."
                         {:capacity capacity
                          :expected expected-identities
                          :actual oldest-first
                          :maximum maximum})))
             {:capacity capacity
              :depth depth
              :maximum-retained maximum
              :retained-at-end (count sidecar)
              :maximum-values (* maximum (count shared-values))}))
         capacities)]
    {:depth depth
     :capacities capacities
     :physical-width 64
     :reports reports
     :checks (* depth (count capacities))}))
