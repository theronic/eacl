(ns eacl.engine.operand-order-refinement-test
  "Executable refinement for operand evaluation order and its certificate
  rule (design D2 of
  openspec/changes/2026-10-01-backport-rust-engine-performance).

  A case is a random operator schema (the delegation campaign's generator)
  whose relationships are plain, already expired, expiring at one of three
  later deadlines, conditional on `enabled(flag)` (readers and team
  members), or guarded by `bad(m)` (eligibility and folder parents), which
  faults when the context binds `m` to a map without `x`. A transcription
  computes, for every completion of the residual atoms, each permission's
  strong-Kleene value as the stratified least fixed point from false and,
  for a definite value, its widest-witness deadline: for true, the best
  derivation (latest over alternatives, earliest along one); for false, the
  greatest consistent assignment of blocking deadlines. The deadline of a
  decision is the earliest over its completions, and it is never later than
  the exact stability horizon, found by deciding again at every later
  expiry.

  At four evaluation times and four contexts, the campaign requires:

  - every check's permissionship and missing fields to equal the
    transcription, on the default route and, for recursive plans, with
    delegation and guarded search disabled;
  - every certificate to lie in the band (time, widest witness]: later than
    the evaluation time and never later than the widest witness;
  - detailed lookup items to equal the checks, residuals included, and
    detailed counts to equal the checks' categories;
  - a lookup's and a count's certificate to lie below the widest witness of
    every folder, member or not;
  - a client that keeps its answer cache while time advances to answer like
    a fresh evaluation at every time.

  Operands are decided in the sealed static cost order; the campaign also
  runs every case with that order randomly permuted, which must not change a
  single answer."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as backend]
            [eacl.datascript.core :as api]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.operator.delegation-refinement-test :as delegation]
            [eacl.operator.evaluator-test :as evaluator-fixtures]
            [eacl.operator.plan :as operator-plan]
            [eacl.operator.recursive :as operator-recursive]
            [eacl.relationships.staged :as staged]))

(def ^:private next-int! @#'delegation/next-int!)
(def ^:private chance? @#'delegation/chance?)
(def ^:private pick @#'delegation/pick)
(def ^:private random-folder-permissions @#'delegation/random-folder-permissions)
(def ^:private render-schema @#'delegation/render-schema)
(def ^:private team-permissions @#'delegation/team-permissions)
(def ^:private references @#'delegation/references)
(def ^:private reach @#'delegation/reach)

(def ^:private users ["u0" "u1" "u2"])

(def ^:private now 1000)

(def ^:private expiries [1100 1200 1300])

(def ^:private times [1000 1150 1250 1350])

(def ^:private contexts
  [nil
   {"flag" true "m" {"x" false}}
   {"flag" false "m" {}}
   {"m" {}}])

(def ^:private forever ##Inf)

(def ^:private never ##-Inf)

;; ---------------------------------------------------------------------------
;; Cases
;; ---------------------------------------------------------------------------

(defn- random-relationships
  "[subject-type subject relation resource-type resource] tuples; folder
  and team parents may form cycles."
  [state]
  (let [folders (mapv #(str "f" %) (range (+ 3 (next-int! state 4))))
        teams ["t0" "t1"]]
    {:folders folders
     :teams teams
     :tuples
     (vec
      (distinct
       (concat
        (for [child folders parent folders :when (chance? state 18)]
          [:folder parent :parent :folder child])
        (for [child teams parent teams :when (chance? state 25)]
          [:team parent :parent :team child])
        (for [folder folders user users relation [:reader :owner :eligible]
              :when (chance? state 22)]
          [:user user relation :folder folder])
        (for [folder folders team teams :when (chance? state 30)]
          [:team team :team :folder folder])
        (for [team teams user users :when (chance? state 35)]
          [:user user :member :team team]))))}))

(def ^:private relation-caveats
  {[:folder :reader] "enabled"
   [:team :member] "enabled"
   [:folder :eligible] "bad"
   [:folder :parent] "bad"})

(defn- random-qualifier
  "Nil (plain), already expired, expiring at a later deadline, or, where
  the relation admits one, a Caveat with or without an expiry."
  [state resource-type relation]
  (let [roll (next-int! state 100)
        caveat (get relation-caveats [resource-type relation])
        expiry (pick state expiries)]
    (cond
      (< roll 45) nil
      (< roll 55) {:valid-until-ms (- now 100)}
      (< roll 75) {:valid-until-ms expiry}
      (nil? caveat) (when (< roll 88) {:valid-until-ms expiry})
      (< roll 90) {:caveat caveat}
      :else {:caveat caveat :valid-until-ms expiry})))

(defn- case-schema [permissions]
  (str "caveat enabled(flag bool) { flag }\n\n"
       "caveat bad(m map<bool>) { m[\"x\"] }\n\n"
       (-> (render-schema permissions)
           (str/replace "  relation reader: user\n"
                        "  relation reader: user | user with enabled\n")
           (str/replace "  relation eligible: user\n"
                        "  relation eligible: user | user with bad\n")
           (str/replace "  relation member: user\n"
                        "  relation member: user | user with enabled\n")
           (str/replace "  relation parent: folder\n"
                        "  relation parent: folder | folder with bad\n"))))

(defn- store!
  "A DataScript store holding the case; returns the connection and each
  tuple's qualifier."
  [state permissions {:keys [folders teams tuples]}]
  (let [conn (schema/create-conn)
        _ (eacl/write-schema! (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                              (case-schema permissions))
        _ (ds/transact! conn (mapv #(hash-map :eacl/id %) (concat users folders teams)))
        db (ds/db conn)
        eid #(ds/entid db [:eacl/id %])
        relation-eid (fn [resource-type relation subject-type]
                       (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                     [resource-type relation subject-type]]))
        caveat-eid #(ds/entid db [:eacl.caveat/name %])
        writer (qualifiers/writer conn)
        qualifiers
        (into {}
              (for [[subject-type subject relation resource-type resource :as tuple] tuples
                    :let [qualifier (random-qualifier state resource-type relation)]]
                (do (staged/write! writer :create
                                   [subject-type (eid subject) (relation-eid resource-type relation subject-type)
                                    resource-type (eid resource)]
                                   (cond-> qualifier
                                     (:caveat qualifier) (assoc :caveat (caveat-eid (:caveat qualifier)))))
                    [tuple qualifier])))]
    {:conn conn :qualifiers qualifiers}))

;; ---------------------------------------------------------------------------
;; Transcription: strong-Kleene values and widest-witness deadlines
;; ---------------------------------------------------------------------------

(defn- via-type [resource-type via] (if (= via :team) :team resource-type))

(defn- k-or [a b] (cond (or (= :T a) (= :T b)) :T (or (= :X a) (= :X b)) :X :else :F))
(defn- k-and [a b] (cond (or (= :F a) (= :F b)) :F (or (= :X a) (= :X b)) :X :else :T))
(defn- k-not [a] (case a :T :F :F :T :X))

(defn- edge
  "[value deadline] of a tuple in world `w`: a plain or unexpired edge holds
  until its expiry, a Caveat takes the completion's value, and an absent,
  expired or false edge is false forever."
  [{:keys [stored qualifiers time flag bad]} tuple]
  (if-not (contains? stored tuple)
    [:F forever]
    (let [{:keys [caveat valid-until-ms]} (get qualifiers tuple)
          end (or valid-until-ms forever)]
      (cond
        (and valid-until-ms (<= valid-until-ms time)) [:F forever]
        (nil? caveat) [:T end]
        (= "enabled" caveat) (if flag [:T end] [:F forever])
        :else (case bad :T [:T end] :F [:F forever] :X [:X nil])))))

(defn- vias
  "[intermediate via-tuple] of every stored via edge onto `id`."
  [w resource-type id via]
  (get-in w [:via-index [resource-type id via]]))

(defn- kleene-of [w values resource-type id expression]
  (let [subject (:subject w)]
    (case (first expression)
      :relation (first (edge w [:user subject (second expression) resource-type id]))
      :arrow-relation
      (let [[_ via relation] expression target-type (via-type resource-type via)]
        (reduce k-or :F (for [[x tuple] (vias w resource-type id via)]
                          (k-and (first (edge w tuple))
                                 (first (edge w [:user subject relation target-type x]))))))
      :arrow
      (let [[_ via permission] expression target-type (via-type resource-type via)]
        (reduce k-or :F (for [[x tuple] (vias w resource-type id via)]
                          (k-and (first (edge w tuple))
                                 (get-in values [[target-type permission] x] :F)))))
      :self (get-in values [[resource-type (second expression)] id] :F)
      :union (reduce k-or :F (map #(kleene-of w values resource-type id %) (rest expression)))
      :intersection (reduce k-and :T (map #(kleene-of w values resource-type id %) (rest expression)))
      :exclusion (k-and (kleene-of w values resource-type id (nth expression 1))
                        (k-not (kleene-of w values resource-type id (nth expression 2)))))))

(declare false-deadline)

(defn- true-deadline
  "The latest deadline of a derivation of `expression`, or `never`."
  [w wt wf resource-type id expression]
  (let [subject (:subject w)
        edge-t (fn [tuple] (let [[v end] (edge w tuple)] (if (= :T v) end never)))]
    (case (first expression)
      :relation (edge-t [:user subject (second expression) resource-type id])
      :arrow-relation
      (let [[_ via relation] expression target-type (via-type resource-type via)]
        (reduce max never (for [[x tuple] (vias w resource-type id via)]
                            (min (edge-t tuple) (edge-t [:user subject relation target-type x])))))
      :arrow
      (let [[_ via permission] expression target-type (via-type resource-type via)]
        (reduce max never (for [[x tuple] (vias w resource-type id via)]
                            (min (edge-t tuple) (get-in wt [[target-type permission] x] never)))))
      :self (get-in wt [[resource-type (second expression)] id] never)
      :union (reduce max never (map #(true-deadline w wt wf resource-type id %) (rest expression)))
      :intersection (reduce min forever (map #(true-deadline w wt wf resource-type id %) (rest expression)))
      :exclusion (min (true-deadline w wt wf resource-type id (nth expression 1))
                      (false-deadline w wt wf resource-type id (nth expression 2))))))

(defn- false-deadline
  "The deadline up to which `expression` stays blocked, or `never`."
  [w wt wf resource-type id expression]
  (let [subject (:subject w)
        edge-f (fn [tuple] (let [[v end] (edge w tuple)] (if (= :F v) end never)))]
    (case (first expression)
      :relation (edge-f [:user subject (second expression) resource-type id])
      :arrow-relation
      (let [[_ via relation] expression target-type (via-type resource-type via)]
        (reduce min forever (for [[x tuple] (vias w resource-type id via)]
                              (max (edge-f tuple) (edge-f [:user subject relation target-type x])))))
      :arrow
      (let [[_ via permission] expression target-type (via-type resource-type via)]
        (reduce min forever (for [[x tuple] (vias w resource-type id via)]
                              (max (edge-f tuple) (get-in wf [[target-type permission] x] never)))))
      :self (get-in wf [[resource-type (second expression)] id] never)
      :union (reduce min forever (map #(false-deadline w wt wf resource-type id %) (rest expression)))
      :intersection (reduce max never (map #(false-deadline w wt wf resource-type id %) (rest expression)))
      :exclusion (max (false-deadline w wt wf resource-type id (nth expression 1))
                      (true-deadline w wt wf resource-type id (nth expression 2))))))

(defn- components
  "Strongly connected permissions, each after every component it names."
  [bodies]
  (let [dependencies (into {} (for [[permission body] bodies]
                                [permission (references (first permission) body)]))
        component (fn [permission]
                    (conj (set (filter #(contains? (reach dependencies %) permission)
                                       (reach dependencies permission)))
                          permission))]
    (loop [pending (distinct (map component (keys bodies))) done #{} order []]
      (if (empty? pending)
        order
        (let [ready (first (filter (fn [members]
                                     (every? #(or (contains? members %) (contains? done %))
                                             (mapcat dependencies members)))
                                   pending))]
          (recur (remove #{ready} pending) (into done ready) (conj order (vec (sort ready)))))))))

(defn- fixpoint
  "Iterates `step` over the members of a component from `start` until no
  value changes."
  [state members ids start step]
  (loop [state (reduce #(assoc %1 %2 (zipmap (ids %2) (repeat start))) state members)]
    (let [next (reduce (fn [acc permission]
                         (assoc acc permission
                                (into {} (for [id (ids permission)]
                                           [id (step acc permission id)]))))
                       state members)]
      (if (= next state) state (recur next)))))

(defn- evaluate-world
  "{:values :wt :wf} of every permission on every entity in world `w`.
  Values are the least fixed point from false per component; true
  deadlines the least and false deadlines the greatest consistent
  assignment."
  [w bodies order entities]
  (let [ids (fn [[resource-type]] (get entities resource-type))]
    (reduce
     (fn [{:keys [values wt wf]} members]
       (let [values (fixpoint values members ids :F
                              (fn [values [resource-type :as p] id]
                                (kleene-of w values resource-type id (get bodies p))))
             wt (fixpoint wt members ids never
                          (fn [wt [resource-type :as p] id]
                            (true-deadline w wt wf resource-type id (get bodies p))))
             wf (fixpoint wf members ids forever
                          (fn [wf [resource-type :as p] id]
                            (false-deadline w wt wf resource-type id (get bodies p))))]
         {:values values :wt wt :wf wf}))
     {:values {} :wt {} :wf {}}
     order)))

(defn- inconsistency
  "Nil when every value agrees with its deadlines: true exactly where a
  true deadline exists, false exactly where a false deadline exists, both
  later than the world's time."
  [{:keys [values wt wf]} time]
  (first
   (for [[p by-id] values [id v] by-id
         :let [t (get-in wt [p id]) f (get-in wf [p id])]
         :when (not (and (= (= :T v) (> t never)) (= (= :F v) (> f never))
                         (or (not= :T v) (> t time)) (or (not= :F v) (> f time))))]
     {:permission p :id id :value v :true-deadline t :false-deadline f})))

(defn- completions
  "The atom assignments a context leaves open: `flag` when unbound, and
  `bad` true or false when `m` is unbound, faulting when `m` lacks `x`."
  [context]
  (for [flag (if (contains? context "flag") [(get context "flag")] [true false])
        bad (cond (not (contains? context "m")) [:T :F]
                  (contains? (get context "m") "x") [(if (get-in context ["m" "x"]) :T :F)]
                  :else [:X])]
    {:flag flag :bad bad}))

(defn- transcription
  "A memoized function of [subject time completion] to an evaluated world."
  [bodies {:keys [folders teams tuples]} qualifiers]
  (let [order (components bodies)
        entities {:folder folders :team teams}
        stored (set tuples)
        via-index (reduce (fn [index [_ x relation resource-type id :as tuple]]
                            (update index [resource-type id relation] (fnil conj []) [x tuple]))
                          {} tuples)
        cache (atom {})]
    (fn [subject time completion]
      (let [key [subject time completion]]
        (or (get @cache key)
            (let [w (merge completion {:subject subject :time time :stored stored
                                       :qualifiers qualifiers :via-index via-index})
                  world (evaluate-world w bodies order entities)]
              (swap! cache assoc key world)
              world))))))

(defn- decision
  "The transcription's decision of `permission` on `folder`: permissionship,
  missing fields when conditional, the widest-witness deadline and the
  exact stability horizon. Nil `:deadline` for an evaluation failure."
  [world-of subject permission folder time context]
  (let [p [:folder permission]
        outcome (fn [time]
                  (for [completion (completions context)
                        :let [{:keys [values wt wf]} (world-of subject time completion)
                              v (get-in values [p folder])]]
                    [completion v (case v :T (get-in wt [p folder]) :F (get-in wf [p folder]) nil)]))
        current (outcome time)
        terminals (map second current)
        permissionship (cond (some #{:X} terminals) :evaluation-failure
                             (every? #{:T} terminals) :has-permission
                             (every? #{:F} terminals) :no-permission
                             :else :conditional-permission)
        depends? (fn [atom-key]
                   (let [by-completion (into {} (map (juxt first second)) current)]
                     (some (fn [[completion v]]
                             (some (fn [[other w]]
                                     (and (not= v w)
                                          (= (dissoc completion atom-key) (dissoc other atom-key))))
                                   by-completion))
                           by-completion)))
        later (filter #(> % time) (sort (distinct (concat expiries times))))
        horizon (or (first (filter #(not= terminals (map second (outcome %))) later)) forever)]
    (cond-> {:permissionship permissionship}
      (= :conditional-permission permissionship)
      (assoc :missing-fields (vec (sort (cond-> []
                                          (depends? :flag) (conj "flag")
                                          (depends? :bad) (conj "m")))))
      (not= :evaluation-failure permissionship)
      (assoc :deadline (reduce min forever (map #(nth % 2) current))
             :horizon horizon))))

;; ---------------------------------------------------------------------------
;; The engine's answers
;; ---------------------------------------------------------------------------

(defn- error-type [error]
  (or (:type (ex-data error)) (:eacl/error (ex-data error)) :thrown))

(defn- public-check [client subject permission folder context cache?]
  (try
    (select-keys (eacl/check-permission
                  client (cond-> {:subject (eacl/spice-object :user subject)
                                  :permission permission
                                  :resource (eacl/spice-object :folder folder)
                                  :cache? cache?}
                           context (assoc :caveat-context context)))
                 [:permissionship :missing-fields :residual :cached?])
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
      {:error (error-type error)})))

(defn- comparable
  "A check or item as the transcription states it."
  [answer]
  (if (= :eacl.authorization/evaluation-failure (:error answer))
    {:permissionship :evaluation-failure}
    (select-keys answer [:permissionship :missing-fields :error])))

(defn- adapter
  "A snapshot adapter naming objects by their external ids."
  [db]
  (backend/basis-adapter
   db {:object-id->entid (fn [snapshot id] (ds/entid snapshot [:eacl/id id]))
       :entid->object-id (fn [snapshot eid] (:eacl/id (ds/entity snapshot eid)))}))

(defn- tabled
  "Runs `f` with delegation and guarded search disabled: the tabled
  recursive evaluator answers recursive plans."
  [f]
  (with-redefs [operator-plan/delegated-permissions (constantly nil)
                operator-plan/guarded-delegation (constantly nil)]
    (f)))

(defn- engine-evidence
  "The evidence of one point decision through the engine itself, with a
  fresh request: its permissionship and certificate."
  [db subject permission folder time context route]
  (try
    (let [adapter (adapter db)
          request (evaluator-fixtures/qualified-request db time context)
          run #(binding [engine/*qualification* request]
                 (engine/check-evidence adapter {:type :user :id subject} permission
                                        {:type :folder :id folder}))
          value (if (= :tabled route) (tabled run) (run))]
      {:permissionship (evidence/permissionship value)
       :deadline (or (evidence/valid-until value) forever)
       :complete? (evidence/complete? value)})
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
      {:error (error-type error)})))

(defn- collection-query
  "A detailed lookup or count of one subject's folders (`:forward`) or of
  one folder's users (`:reverse`)."
  [direction object permission]
  (case direction
    :forward {:subject object :permission permission :resource/type :folder
              :result-policy :detailed}
    :reverse {:resource object :permission permission :subject/type :user
              :result-policy :detailed}))

(defn- collection-certificate
  "The certificate of a detailed lookup or count, through the engine with a
  fresh request."
  [db direction id permission time context operation]
  (try
    (let [adapter (adapter db)
          request (evaluator-fixtures/qualified-request db time context)
          query (collection-query direction {:type (if (= :forward direction) :user :folder) :id id}
                                  permission)]
      (binding [engine/*qualification* request]
        (case [direction operation]
          [:forward :lookup] (count (:data (engine/lookup-resources adapter (assoc query :first 100))))
          [:forward :count] (engine/count-resources adapter query)
          [:reverse :lookup] (count (:data (engine/lookup-subjects adapter (assoc query :first 100))))
          [:reverse :count] (engine/count-subjects adapter query)))
      (let [{:keys [valid-until-ms]} (qualification/certificate request)]
        {:deadline (or valid-until-ms forever)}))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
      {:error (error-type error)})))

(defn- public-collection
  "A detailed lookup of every member, by id, and a detailed count."
  [client direction id permission context cache?]
  (let [query (cond-> (assoc (collection-query
                              direction (eacl/spice-object (if (= :forward direction) :user :folder) id)
                              permission)
                             :cache? cache?)
                context (assoc :caveat-context context))
        [lookup count-of] (if (= :forward direction)
                            [eacl/lookup-resources eacl/count-resources]
                            [eacl/lookup-subjects eacl/count-subjects])]
    {:lookup (try (into {} (for [item (:data (lookup client (assoc query :first 100)))]
                             [(get-in item [:object :id])
                              (select-keys item [:permissionship :missing-fields :residual])]))
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
                    {:error (error-type error)}))
     :count (try (select-keys (count-of client query)
                              [:count :definite-count :conditional-count])
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
                   {:error (error-type error)}))}))

(defn- expected-collection
  "The detailed lookup and count that the public checks of `ids` imply."
  [checks ids]
  (let [kind #(:permissionship (comparable (get checks %)))]
    (if (some #(= :evaluation-failure (kind %)) ids)
      {:lookup {:error :eacl.authorization/evaluation-failure}
       :count {:error :eacl.authorization/evaluation-failure}}
      (let [members (filter #(#{:has-permission :conditional-permission} (kind %)) ids)
            definite (count (filter #(= :has-permission (kind %)) members))]
        {:lookup (into {} (for [id members]
                            [id (select-keys (get checks id) [:permissionship :missing-fields :residual])]))
         :count {:count (count members) :definite-count definite
                 :conditional-count (- (count members) definite)}}))))

(defn- residual-values
  "A collection with each residual replaced by its decision diagram: a
  cached answer keeps the certificate it was computed with."
  [collection]
  (if (:error (:lookup collection))
    collection
    (update collection :lookup
            (fn [items]
              (into {} (for [[id item] items]
                         [id (cond-> (dissoc item :residual)
                               (:residual item)
                               (assoc :value (evidence/value (evidence/decode (:residual item)))))]))))))

(defn- in-band?
  "A certificate lies in (time, deadline]."
  [certificate time deadline]
  (and (> certificate time) (<= certificate deadline)))

;; ---------------------------------------------------------------------------
;; One case
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Operand orders
;; ---------------------------------------------------------------------------

(defn- shuffled
  "A Fisher-Yates permutation of `values` drawn from `state`."
  [state values]
  (loop [values (vec values) i (dec (count values))]
    (if (pos? i)
      (let [j (next-int! state (inc i))]
        (recur (assoc values i (nth values j) j (nth values i)) (dec i)))
      values)))

(defn- permute-operands
  "The operator plan with the operand order of every union and intersection
  node permuted: the evaluators decide them in that order."
  [state plan]
  (if-not (operator-plan/operator-plan? plan)
    plan
    (update plan :operand-orders
            (fn [orders]
              (into (sorted-map)
                    (for [[permission by-node] orders]
                      [permission (into (sorted-map)
                                        (for [[node-id order] by-node]
                                          [node-id (shuffled state order)]))]))))))

(defn- with-operand-order
  "Runs `f` with every operator plan sealed meanwhile evaluating its
  operands in an order drawn from `seed`; the plan's own order when nil."
  [seed f]
  (if (nil? seed)
    (f)
    (let [state (atom seed) seal operator-plan/seal-plan]
      (with-redefs [operator-plan/seal-plan (fn [adapter root]
                                              (permute-operands state (seal adapter root)))]
        (f)))))

(defn- routes
  "The default route, and the tabled one where delegation or guarded search
  would answer a recursive operator plan."
  [db permission]
  (let [plan (try (operator-plan/seal-plan (adapter db) [:folder permission])
                  (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (and plan (operator-plan/operator-plan? plan)
             (operator-recursive/recursive-plan? plan)
             (or (operator-plan/delegated-permissions plan) (operator-plan/guarded-delegation plan)))
      [:default :tabled]
      [:default])))

(declare run-case*)

(defn- run-case
  "{:stats ...} when every answer refines the transcription, else
  {:divergence ...}. Operands are decided in the plans' own order, or in
  orders drawn from `order-seed`."
  [seed order-seed]
  (with-operand-order order-seed #(run-case* seed order-seed)))

(defn- run-case*
  [seed order-seed]
  (let [state (atom seed)
        permissions (random-folder-permissions state)
        data (random-relationships state)
        bodies (merge (into {} (for [[p body] permissions] [[:folder p] body]))
                      (into {} (for [[p body] team-permissions] [[:team p] body])))
        {:keys [conn qualifiers]} (store! state permissions data)
        db (ds/db conn)
        world-of (transcription bodies data qualifiers)
        clock (atom now)
        fresh (api/make-client conn {:clock #(deref clock)
                                     :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
        cached (api/make-client conn {:clock #(deref clock)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
        folder-permissions (sort (keys permissions))
        route-of (into {} (for [p folder-permissions] [p (routes db p)]))
        folders (:folders data)
        pairs (for [u users f folders] [u f])
        stats (atom {})
        count! (fn [k] (swap! stats update k (fnil inc 0)))
        fail (fn [what detail]
               (merge {:seed seed :order-seed order-seed :what what :schema (case-schema permissions)
                       :qualified (into (sorted-map) (filter (comp some? val)) qualifiers)
                       :plain (sort (keep (fn [[tuple q]] (when-not q tuple)) qualifiers))}
                      detail))
        point-divergences
        (fn [where expected checks reused permission time context]
          (concat
           (for [[u f :as pair] pairs
                 :let [want (get expected pair) got (get checks pair) again (get reused pair)
                       _ (count! (:permissionship want))
                       _ (when (:cached? again) (count! :reused-checks))]
                 :when (or (not= (select-keys want [:permissionship :missing-fields]) (comparable got))
                           (not= (comparable got) (comparable again))
                           (and (:residual got)
                                (not= (evidence/value (evidence/decode (:residual got)))
                                      (evidence/value (evidence/decode (:residual again)))))
                           (and (:deadline want) (> (:deadline want) (:horizon want))))]
             (fail :check (assoc where :subject u :folder f :expected want :actual got :cached again)))
           (for [[u f :as pair] pairs
                 :let [want (get expected pair)]
                 route (get route-of permission)
                 :let [got (engine-evidence db u permission f time context route)
                       _ (count! route)
                       _ (when (and (:deadline want) (:deadline got) (< (:deadline got) (:deadline want)))
                           (count! :narrower-than-widest))]
                 :when (or (not= (:permissionship want) (:permissionship got))
                           (and (:deadline want) (not (in-band? (:deadline got) time (:deadline want)))))]
             (fail :certificate (assoc where :subject u :folder f :route route :expected want :actual got)))))
        collection-divergences
        (fn [where expected checks permission time context direction id]
          (let [ids (if (= :forward direction) folders users)
                pair #(if (= :forward direction) [id %] [% id])
                by-id (into {} (for [x ids] [x (get checks (pair x))]))
                implied (expected-collection by-id ids)
                actual (public-collection fresh direction id permission context false)
                again (public-collection cached direction id permission context true)]
            (concat
             (when (or (not= (residual-values implied) (residual-values actual))
                       (not= (residual-values implied) (residual-values again)))
               [(fail :collection (assoc where direction id :implied implied :actual actual :cached again))])
             ;; Items equal checks with their certificates: a conditional
             ;; residual is public, so every route reports the point check's.
             (when (not= implied actual)
               [(fail :collection-residual (assoc where direction id :implied implied :actual actual))])
             (when-not (:error (:lookup actual))
               (for [[x item] (:lookup actual)
                     :let [want (get expected (pair x))]
                     :when (and (:residual item) (:deadline want))
                     :let [end (or (evidence/valid-until (evidence/decode (:residual item))) forever)]
                     :when (not (in-band? end time (:deadline want)))]
                 (fail :item-certificate (assoc where direction id :item x :expected want :actual end))))
             (when (not-any? #(= :evaluation-failure (:permissionship (get expected (pair %)))) ids)
               (let [widest (reduce min forever (map #(:deadline (get expected (pair %))) ids))]
                 (for [operation [:lookup :count]
                       :let [got (collection-certificate db direction id permission time context operation)
                             _ (count! [direction operation])]
                       :when (not (and (:deadline got) (in-band? (:deadline got) time widest)))]
                   (fail :collection-certificate (assoc where direction id :operation operation
                                                        :widest widest :actual got))))))))]
    (if-let [divergence
             (first
              (for [time times
                    :let [_ (reset! clock time)]
                    divergence
                    (concat
                     (for [subject users
                           completion (for [flag [true false] bad [:T :F :X]] {:flag flag :bad bad})
                           :let [problem (inconsistency (world-of subject time completion) time)]
                           :when problem]
                       (fail :transcription-inconsistent {:subject subject :time time
                                                          :completion completion :problem problem}))
                     (for [context contexts
                           permission folder-permissions
                           :let [where {:permission permission :time time :context context}
                                 expected (into {} (for [[u f :as pair] pairs]
                                                     [pair (decision world-of u permission f time context)]))
                                 checks (into {} (for [[u f :as pair] pairs]
                                                   [pair (public-check fresh u permission f context false)]))
                                 reused (into {} (for [[u f :as pair] pairs]
                                                   [pair (public-check cached u permission f context true)]))]
                           divergence
                           (concat
                            (point-divergences where expected checks reused permission time context)
                            (mapcat #(collection-divergences where expected checks permission time context
                                                             :forward %)
                                    users)
                            (mapcat #(collection-divergences where expected checks permission time context
                                                             :reverse %)
                                    folders))]
                       divergence))]
                divergence))]
      {:divergence divergence}
      {:stats @stats})))

(defn run-campaign
  "Runs `n` cases from seed `start`, each in the plans' own operand order
  or, when `permuted?`, in random orders; returns the first divergence, or
  how many decisions of each kind were checked."
  [start n permuted?]
  (loop [seed start totals {:cases 0}]
    (if (= seed (+ start n))
      totals
      (let [{:keys [divergence stats]} (run-case seed (when permuted? (+ 7919 seed)))]
        (if divergence
          {:divergence divergence :cases (:cases totals)}
          (recur (inc seed) (-> (merge-with + totals stats) (update :cases inc))))))))

(deftest operand-order-refines-the-kleene-transcription-test
  (doseq [permuted? [false true]]
    (let [n #?(:clj 4 :cljs 1)
          ;; The same cases twice: in the plans' own operand order, then in
          ;; random orders, which must not change a single answer.
          {:keys [cases divergence] :as result} (run-campaign 1 n permuted?)]
      (is (nil? divergence) (pr-str divergence))
      (is (= n cases))
      (testing "the campaign reaches every decision kind, both routes and the cache"
        (doseq [k [:has-permission :no-permission :conditional-permission :tabled :reused-checks
                   [:forward :lookup] [:forward :count] [:reverse :lookup] [:reverse :count]]]
          (is (pos? (get result k 0)) (pr-str [permuted? k result])))))))
