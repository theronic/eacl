(ns eacl.formal.generators
  "Deterministic coherent generators and shrinkers for formal differential work.

  These functions deliberately avoid production traversal helpers. A seed is
  the complete reproduction handle; all arithmetic stays in the portable safe
  integer range so the same fixtures are produced in CLJ and CLJS."
  (:require [eacl.causal-model :as causal]))

(def ^:private rng-modulus 2147483647)
(def ^:private rng-multiplier 48271)

(defn- next-rng
  [state]
  (let [state (mod (* (max 1 (long state)) rng-multiplier)
                   rng-modulus)]
    [state state]))

(defn- choose
  [state size]
  (let [[state value] (next-rng state)]
    [state (mod value size)]))

(defn- object
  [type id]
  {:type type :id id})

(defn- relationship
  [subject relation resource]
  {:subject subject :relation relation :resource resource})

(defn coherent-schema
  "Builds one finite valid fixture with aliases, relation/permission arrows,
  a recursive SCC, multiple subject types, duplicate semantic paths, a
  disconnected node, cycles, a diamond, fan-in/fan-out, and empty relations.
  Malformed variants are retained separately so valid cases stay coherent."
  [seed]
  (let [[state extra-user] (choose seed 3)
        [state extra-folder] (choose state 3)
        suffix (str "-s" seed)
        users (mapv #(object :user (str "u" % suffix))
                    (range (+ 2 extra-user)))
        groups (mapv #(object :group (str "g" % suffix)) (range 2))
        documents (mapv #(object :document (str "d" % suffix)) (range 3))
        folders (mapv #(object :folder (str "f" % suffix))
                      (range (+ 4 extra-folder)))
        disconnected (object :archive (str "disconnected" suffix))
        extreme (object :user
                        (str "unicode-\u03bb-" seed "-"
                             "9007199254740991"))
        [u0 u1] users
        [g0 g1] groups
        [d0 d1 d2] documents
        [f0 f1 f2 f3] folders
        base-relationships
        [(relationship u0 :member g0)
         (relationship u1 :member g0)
         (relationship extreme :member g1)
         (relationship u0 :reader d0)
         (relationship g0 :reader d1)
         (relationship g0 :parent d0)
         (relationship g1 :parent d2)
         (relationship u1 :editor d0)
         (relationship u0 :reader f0)
         ;; f0 reaches f1 and f2, which converge on f3; f3 closes a cycle.
         (relationship f0 :parent f1)
         (relationship f0 :parent f2)
         (relationship f1 :parent f3)
         (relationship f2 :parent f3)
         (relationship f3 :parent f0)]
        extra-relationships
        (mapv
         (fn [index]
           (relationship
            (nth users (mod (+ index state) (count users)))
            :reader
            (nth folders (+ 4 index))))
         (range extra-folder))
        rules
        {[:group :view]
         [:relation :member]

         [:document :edit]
         [:relation :editor]

         [:document :read]
         [:union
          [:relation :reader]
          [:permission :edit]
          [:arrow :parent [:relation :member]]]

         [:document :view]
         [:permission :read]

         [:folder :read]
         [:union
          [:relation :reader]
          [:arrow :parent [:permission :read]]]

         [:folder :duplicate]
         [:union
          [:permission :read]
          [:relation :reader]
          [:arrow :parent [:permission :read]]]

         [:folder :seedless]
         [:arrow :parent [:permission :seedless]]}
        ;; Intersection and exclusion are valid operator variants over the
        ;; same relationship graph. They are carried separately from the
        ;; union-only `:rules` so the union-only generated authority keeps
        ;; its exact vocabulary while the operator differential evaluates
        ;; the combined schema.
        operator-rules
        {[:document :confidential]
         [:intersection [:relation :reader] [:relation :editor]]

         [:document :disclosed]
         [:exclusion [:permission :read] [:relation :editor]]

         [:folder :cleared]
         [:exclusion [:permission :read] [:relation :reader]]}
        fixture
        {:seed seed
         :objects
         (vec (concat users groups documents folders
                      [disconnected extreme]))
         :relationships
         (vec (distinct (concat base-relationships extra-relationships)))
         :rules rules
         :operator-rules operator-rules
         :empty-relations #{[:document :unused] [:folder :blocked]}
         :features
         #{:alias :arrow-relation :arrow-permission :recursive-scc
           :multiple-subject-types :duplicate-semantic-path
           :disconnected :cycle :diamond :fan-in :fan-out
           :empty-relation :extreme-id
           :intersection :exclusion :exclusion-over-recursion}}]
    (assoc
     fixture
     :malformed-variants
     [(assoc fixture
             :id :unknown-rule-operator
             :rules (assoc rules
                           [:document :broken]
                           [:xor
                            [:relation :reader]
                            [:relation :editor]]))
      (assoc fixture
             :id :malformed-arrow-target
             :rules (assoc rules
                           [:document :broken]
                           [:arrow :parent [:unknown :member]]))
      (assoc fixture
             :id :malformed-rule-shape
             :rules (assoc rules [:document :broken] {:not :an-ast}))])))

(defn coherent-graph
  "Returns the graph portion plus explicit unknown request identities."
  [seed]
  (let [fixture (coherent-schema seed)]
    {:seed seed
     :objects (:objects fixture)
     :relationships (:relationships fixture)
     :features (select-keys fixture [:empty-relations :features])
     :unknown-objects
     [(object :user (str "unknown-user-s" seed))
      (object :document (str "unknown-document-s" seed))]}))

(defn request-cases
  "Generates public-operation and page cases, including every valid direction,
  boundary form, random jump, count, direct check, and invalid combination."
  [seed item-count]
  (let [[_ jump] (choose seed (max 1 item-count))
        user (object :user (str "u0-s" seed))
        document (object :document (str "d0-s" seed))]
    {:seed seed
     :operations
     [{:operation :can?
       :subject user :permission :view :resource document}
      {:operation :lookup-resources
       :subject user :resource-type :document :permission :view}
      {:operation :lookup-subjects
       :resource document :subject-type :user :permission :view}
      {:operation :count-resources
       :subject user :resource-type :document :permission :view}
      {:operation :count-subjects
       :resource document :subject-type :user :permission :view}]
     :valid-pages
     [{:first 1}
      {:first (max 1 item-count)}
      {:first 2 :after jump}
      {:last 1}
      {:last (max 1 item-count)}
      {:last 2 :before jump}]
     :invalid-pages
     [{:first 1 :last 1}
      {:first 1 :after 0 :before 1}
      {:after 0}
      {:before 0}
      {:first nil}
      {:last 0}
      {:first -1}
      {:first 10001}
      {:limit 1}
      {:cursor "legacy"}]
     :random-jump jump
     :unknown-request
     {:operation :can?
      :subject (object :user (str "unknown-user-s" seed))
      :permission :view
      :resource document}}))

(defn state-command-trace
  "Generates a deterministic adversarial history vocabulary. Commands marked
  `:harness-only?` are injected at real provider/token boundaries by the
  differential runner rather than interpreted by the pure causal model."
  [fixture]
  (let [query [(first (filter #(= :user (:type %)) (:objects fixture)))
               :view
               (first (filter #(= :document (:type %)) (:objects fixture)))]
        changed-relationships (vec (rest (:relationships fixture)))
        changed-rules (dissoc (:rules fixture) [:document :edit])]
    [(causal/read-command query)
     (causal/cache-put :authorization :genesis false)
     (causal/cache-read :authorization)
     (causal/unrelated-write)
     (causal/graph-write changed-relationships)
     (causal/schema-write changed-rules)
     (causal/clone-head :genesis)
     (causal/reset-head :genesis)
     (causal/restore-head :genesis)
     (causal/branch-head :genesis)
     (causal/force-head :genesis)
     (causal/expire-snapshot :genesis)
     {:operation :cursor-mint :arguments {:query query} :harness-only? true}
     {:operation :cursor-replay :arguments {:direction :asc}
      :harness-only? true}
     {:operation :cursor-replay :arguments {:direction :desc}
      :harness-only? true}
     {:operation :exact-read
      :arguments {:anchor :genesis}
      :harness-only? true}
     {:operation :history-read
      :arguments {:anchor :genesis}
      :harness-only? true}
     {:operation :exact-unavailable :arguments {} :harness-only? true}
     {:operation :cache-disabled-read
      :arguments {:query query :cache? false}
      :harness-only? true}
     {:operation :cache-expire :arguments {} :harness-only? true}
     {:operation :traversal-limit
      :arguments
      {:max-derived-grants 1
       :max-advanced-datoms 1
       :max-queued-work 1}
      :harness-only? true}
     {:operation :proof-provider-failure :arguments {} :harness-only? true}
     {:operation :compute-failure :arguments {:stage :projection}
      :harness-only? true}
     {:operation :concurrent-identical-reads
      :arguments {:query query :callers 4}
      :harness-only? true}
     {:operation :concurrent-read-write
      :arguments
      {:query query
       :write :unrelated
       :schedules [:read-first :write-first :overlap]}
      :harness-only? true}
     {:operation :cache-tamper :arguments {:field :proof}
      :harness-only? true}
     {:operation :cursor-tamper :arguments {:field :scope}
      :harness-only? true}]))

(defn- referenced-objects
  [relationships]
  (into #{}
        (mapcat (juxt :subject :resource))
        relationships))

(defn shrink-graph
  "Returns strictly smaller fixtures while retaining every relationship
  endpoint in `:objects`. The rule map is unchanged, so request/schema
  coherence is preserved."
  [fixture]
  (let [relationships (:relationships fixture)]
    (mapv
     (fn [index]
       (let [relationships'
             (vec (concat (subvec relationships 0 index)
                          (subvec relationships (inc index))))
             referenced (referenced-objects relationships')]
         (assoc fixture
                :relationships relationships'
                :objects
                (vec
                 (filter
                  #(or (contains? referenced %)
                       (not= :archive (:type %)))
                  (:objects fixture))))))
     (range (count relationships)))))

(defn shrink-schema
  "Shrinks union operands without deleting permission nodes referenced by
  another rule."
  [fixture]
  (into
   []
   (mapcat
    (fn [[rule-key rule]]
      (when (= :union (first rule))
        (for [index (range 1 (count rule))]
          (assoc-in
           fixture
           [:rules rule-key]
           (into [:union]
                 (concat (subvec rule 1 index)
                         (subvec rule (inc index)))))))))
   (:rules fixture)))

(defn shrink-page
  "Returns smaller positive page requests while preserving direction/bound."
  [page]
  (let [size-key (cond
                   (contains? page :first) :first
                   (contains? page :last) :last)]
    (if-let [size (and size-key (get page size-key))]
      (if (and (integer? size) (> size 1))
        [(assoc page size-key (max 1 (quot size 2)))
         (assoc page size-key 1)]
        [])
      [])))

(defn shrink-trace
  "Deletes one command at a time and retains only traces executable by the
  pure model. Harness-only boundary injections are always safe to delete."
  [initial-state trace]
  (->> (range (count trace))
       (map
        (fn [index]
          (vec (concat (subvec trace 0 index)
                       (subvec trace (inc index))))))
       (filter
        (fn [candidate]
          (try
            (doseq [command candidate
                    :when (not (:harness-only? command))]
              (causal/apply-command initial-state command))
            true
            (catch #?(:clj Exception :cljs :default) _
              false))))
       vec))
