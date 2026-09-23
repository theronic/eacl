(ns eacl.operator.delegation-refinement-test
  "Certification of operator delegation over random schemas.

  A case is a random schema mixing union-only recursive permissions with
  intersection and exclusion permissions over them (sometimes a union that
  names an operator permission, sometimes an operator permission that
  recurses through itself), random relationship tuples on an in-memory
  adapter, and every operator root of it. For each root the campaign
  requires:

  - `delegated-permissions` equal to an oracle computed from the generated
    expressions alone: nil when a permission that reaches an operator lies on
    a cycle, else the root closure's permissions that reach no operator;
  - otherwise the guarded members of `guarded-delegation` equal to an oracle
    of linear guardedness computed from the same expressions;
  - the delegated generator, when the root's anchor chain ends at one of those
    permissions, to be that permission and to cover the root on the data;
  - otherwise (a union at the root or as an anchor, say) the flattened
    generator's rows, evaluated by this campaign's own semantics, to cover
    the root on the data;
  - every lookup walk (page sizes 1, 2, 5 and 100), count, check, reverse
    lookup and reverse count equal to an independent stratified least-fixed-
    point evaluation of the generated expressions, with each walk's order
    independent of its page size;
  - the same answers when delegation is disabled and the tabled recursive
    evaluator answers instead. Forward walks under a delegated operand's plan
    keep the same order; other walks are compared as sets, because a walk's
    order follows its generator, which recursive operator cursors
    authenticate.

  The operator-delegat* mutation controls in
  `eacl.formal.executed-mutation-controls` pin the same obligations on a fixed
  chart."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [eacl.engine.v8 :as engine]
            [eacl.operator.cover-plan :as cover-plan]
            [eacl.operator.plan :as operator-plan]
            [eacl.operator.recursive :as operator-recursive]
            [eacl.test-support.tuple-adapter :as tuple-adapter]))

;; ---------------------------------------------------------------------------
;; Deterministic schemas and tuples
;; ---------------------------------------------------------------------------

(defn- next-int!
  "Park-Miller minimal standard: exact in JavaScript doubles too."
  [state bound]
  (let [value (mod (* 48271 @state) 2147483647)]
    (reset! state value)
    (mod (quot value 256) bound)))

(defn- chance? [state percent] (< (next-int! state 100) percent))

(defn- pick [state values] (nth values (next-int! state (count values))))

(def ^:private operator-kinds #{:intersection :exclusion})

(defn- render-term [[kind a b]]
  (case kind
    (:relation :self) (name a)
    (:arrow :arrow-relation) (str (name a) "->" (name b))))

(declare render-expression)

(defn- render-operand [expression]
  (if (contains? #{:union :intersection :exclusion} (first expression))
    (str "(" (render-expression expression) ")")
    (render-term expression)))

(defn- render-expression [expression]
  (case (first expression)
    :union (str/join " + " (map render-operand (rest expression)))
    :intersection (str (render-operand (nth expression 1)) " & "
                       (render-operand (nth expression 2)))
    :exclusion (str (render-operand (nth expression 1)) " - "
                    (render-operand (nth expression 2)))
    (render-term expression)))

(def ^:private team-permissions
  {:t0 [:union [:relation :member] [:arrow :parent :t0]]
   :t1 [:union [:relation :member]]})

(defn- render-schema [folder-permissions]
  (str "definition user {}\n\n"
       "definition team {\n"
       "  relation member: user\n"
       "  relation parent: team\n"
       (apply str (for [[p body] team-permissions]
                    (str "  permission " (name p) " = "
                         (render-expression body) "\n")))
       "}\n\n"
       "definition folder {\n"
       "  relation parent: folder\n"
       "  relation reader: user\n"
       "  relation owner: user\n"
       "  relation eligible: user\n"
       "  relation team: team\n"
       (apply str (for [[p body] folder-permissions]
                    (str "  permission " (name p) " = "
                         (render-expression body) "\n")))
       "}\n"))

(defn- random-folder-permissions
  "Union-only permissions u0.., operator permissions o0.. over them (each
  operator may name only earlier operators), sometimes a union v0 that names
  an operator, and sometimes a last operator that recurses through its
  intersection operand or its exclusion's left operand."
  [state]
  (let [unions (mapv #(keyword (str "u" %)) (range (+ 2 (next-int! state 2))))
        operators (mapv #(keyword (str "o" %)) (range (inc (next-int! state 2))))
        leaves (vec (concat [[:relation :reader] [:relation :owner]
                             [:relation :eligible]
                             [:arrow-relation :team :member]
                             [:arrow :team :t0] [:arrow :team :t1]]
                            (for [u unions] [:arrow :parent u])
                            (for [u unions] [:self u])))
        union-body (fn [own]
                     (let [terms (->> (repeatedly (inc (next-int! state 3))
                                                  #(pick state leaves))
                                      distinct
                                      (remove #{[:self own]})
                                      vec)]
                       (into [:union] (if (seq terms) terms [[:relation :reader]]))))
        operand (fn [earlier]
                  (let [refs (vec (concat (for [u unions] [:self u])
                                          (for [u unions] [:arrow :parent u])
                                          (for [o earlier] [:self o])
                                          [[:relation :eligible] [:relation :owner]]))
                        ;; Half the operands name a union-only permission
                        ;; directly, the shape a delegated generator anchors.
                        one (if (chance? state 50)
                              [:self (pick state unions)]
                              (pick state refs))]
                    (if (chance? state 30)
                      (let [two (pick state refs)]
                        (if (= one two) one [:union one two]))
                      one)))
        operator-body (fn [index]
                        (let [earlier (subvec operators 0 index)
                              left (operand earlier)
                              right (loop [right (operand earlier)]
                                      (if (= left right) (recur (operand earlier)) right))
                              core [(if (chance? state 50) :intersection :exclusion)
                                    left right]]
                          (if (chance? state 25)
                            [:union (pick state leaves) core]
                            core)))
        recursive? (chance? state 20)
        with-recursion (fn [permissions]
                         (let [own (peek operators)
                               body (get permissions own)
                               core (if (= :union (first body)) (peek body) body)
                               recursive-core (assoc core 1 [:union (nth core 1)
                                                             [:arrow :parent own]])]
                           (assoc permissions own
                                  (if (= :union (first body))
                                    (conj (pop body) recursive-core)
                                    recursive-core))))
        permissions (merge (into (sorted-map) (for [u unions] [u (union-body u)]))
                           (into (sorted-map)
                                 (map-indexed (fn [index o] [o (operator-body index)])
                                              operators)))
        permissions (cond-> permissions recursive? with-recursion)]
    (cond-> permissions
      (chance? state 25)
      (assoc :v0 [:union (pick state leaves) [:self (pick state operators)]]))))

(def ^:private users [300 301 302])

(defn- random-tuples [state]
  (let [folders (vec (range 100 (+ 100 4 (next-int! state 5))))
        teams [200 201]]
    {:folders folders
     :teams teams
     :tuples
     (set
      (concat
       (for [child folders parent folders :when (chance? state 16)]
         [:folder parent :parent :folder child])
       (for [child teams parent teams :when (chance? state 25)]
         [:team parent :parent :team child])
       (for [folder folders user users relation [:reader :owner :eligible]
             :when (chance? state 20)]
         [:user user relation :folder folder])
       (for [folder folders team teams :when (chance? state 30)]
         [:team team :team :folder folder])
       (for [team teams user users :when (chance? state 35)]
         [:user user :member :team team])))}))

;; ---------------------------------------------------------------------------
;; Independent semantics from the generated expressions
;; ---------------------------------------------------------------------------

(defn- permission-bodies [folder-permissions]
  (merge (into {} (for [[p body] folder-permissions] [[:folder p] body]))
         (into {} (for [[p body] team-permissions] [[:team p] body]))))

(defn- via-type [resource-type via] (if (= via :team) :team resource-type))

(defn- references
  "The permissions `expression` of a `resource-type` permission names."
  [resource-type expression]
  (case (first expression)
    (:union :intersection :exclusion)
    (into #{} (mapcat #(references resource-type %)) (rest expression))
    :self #{[resource-type (second expression)]}
    :arrow #{[(via-type resource-type (nth expression 1)) (nth expression 2)]}
    #{}))

(defn- reach
  "Permissions reachable from `permission` through one or more references."
  [dependencies permission]
  (loop [pending (vec (get dependencies permission)) seen #{}]
    (if-let [current (peek pending)]
      (if (contains? seen current)
        (recur (pop pending) seen)
        (recur (into (pop pending) (get dependencies current)) (conj seen current)))
      seen)))

(defn- operator-expression? [expression]
  (or (contains? operator-kinds (first expression))
      (and (= :union (first expression))
           (some operator-expression? (rest expression)))))

(defn- oracle-delegated
  [bodies root]
  (let [dependencies (into {} (for [[permission body] bodies]
                                [permission (references (first permission) body)]))
        closure (conj (reach dependencies root) root)
        operators (set (filter #(operator-expression? (get bodies %)) closure))
        reaches-operator? (fn [permission]
                            (or (contains? operators permission)
                                (some operators (reach dependencies permission))))
        operator-reaching (set (filter reaches-operator? closure))]
    (when-not (some #(contains? (reach dependencies %) %) operator-reaching)
      (into (sorted-set) (remove operator-reaching) closure))))

(defn- oracle-guarded
  "The members of the root closure's cyclic components that reach an
  operator, when each is linearly guarded: every intersection in a member's
  expression has exactly one operand that names the component, and an
  exclusion's left operand does; every other operand is a single term naming
  no member of the component; every permission named outside the component
  is union-only or such a member. Nil otherwise."
  [bodies root]
  (let [dependencies (into {} (for [[permission body] bodies]
                                [permission (references (first permission) body)]))
        closure (conj (reach dependencies root) root)
        operators (set (filter #(operator-expression? (get bodies %)) closure))
        operator-reaching (set (filter #(or (contains? operators %)
                                            (some operators (reach dependencies %)))
                                       closure))
        members (set (filter #(contains? (reach dependencies %) %) operator-reaching))
        union-only (set (remove operator-reaching closure))
        component (fn [member]
                    (conj (set (filter #(contains? (reach dependencies %) member)
                                       (reach dependencies member)))
                          member))
        linear? (fn [member]
                  (let [resource-type (first member)
                        inside (component member)
                        names (fn [expression] (references resource-type expression))
                        depends? #(some inside (names %))
                        named-ok? (fn [expression]
                                    (every? #(or (inside %) (union-only %) (members %))
                                            (names expression)))
                        guard? (fn [expression]
                                 (and (not (contains? #{:union :intersection :exclusion}
                                                      (first expression)))
                                      (not (depends? expression))
                                      (named-ok? expression)))
                        walk (fn walk [expression]
                               (case (first expression)
                                 :union (every? walk (rest expression))
                                 :intersection
                                 (let [operands (rest expression)
                                       recursive (filter depends? operands)]
                                   (and (= 1 (count recursive))
                                        (every? guard? (remove #{(first recursive)} operands))
                                        (walk (first recursive))))
                                 :exclusion
                                 (let [[_ left right] expression]
                                   (and (depends? left) (guard? right) (walk left)))
                                 (named-ok? expression)))]
                    (walk (get bodies member))))]
    (when (and (seq members) (every? linear? members))
      members)))

(defn- denotation
  "Stratified least fixed point of every permission: {permission {eid
  #{subject}}}. Strongly connected permissions iterate together once the
  permissions they reference outside their component are complete."
  [bodies {:keys [folders teams tuples]}]
  (let [dependencies (into {} (for [[permission body] bodies]
                                [permission (references (first permission) body)]))
        entities {:folder folders :team teams}
        relation-subjects (fn [relation resource-type eid]
                            (set (for [[subject-type subject relation' type' eid'] tuples
                                       :when (and (= :user subject-type) (= relation relation')
                                                  (= resource-type type') (= eid eid'))]
                                   subject)))
        intermediates (fn [resource-type eid via]
                        (for [[subject-type subject relation type' eid'] tuples
                              :when (and (= (via-type resource-type via) subject-type)
                                         (= via relation) (= resource-type type')
                                         (= eid eid'))]
                          subject))
        evaluate (fn evaluate [values resource-type eid expression]
                   (case (first expression)
                     :relation (relation-subjects (second expression) resource-type eid)
                     :arrow-relation
                     (let [[_ via relation] expression]
                       (into #{} (mapcat #(relation-subjects
                                           relation (via-type resource-type via) %))
                             (intermediates resource-type eid via)))
                     :arrow
                     (let [[_ via permission] expression
                           target [(via-type resource-type via) permission]]
                       (into #{} (mapcat #(get-in values [target %] #{}))
                             (intermediates resource-type eid via)))
                     :self (get-in values [[resource-type (second expression)] eid] #{})
                     :union (into #{} (mapcat #(evaluate values resource-type eid %))
                                  (rest expression))
                     :intersection (set/intersection
                                    (evaluate values resource-type eid (nth expression 1))
                                    (evaluate values resource-type eid (nth expression 2)))
                     :exclusion (set/difference
                                 (evaluate values resource-type eid (nth expression 1))
                                 (evaluate values resource-type eid (nth expression 2)))))
        component (fn [permission]
                    (conj (set (filter #(contains? (reach dependencies %) permission)
                                       (reach dependencies permission)))
                          permission))
        components (distinct (map component (keys bodies)))]
    (loop [values {} pending components]
      (if (empty? pending)
        values
        (let [ready (first (filter (fn [members]
                                     (every? #(or (contains? members %)
                                                  (contains? values %))
                                             (mapcat dependencies members)))
                                   pending))
              step (fn [values]
                     (reduce (fn [values permission]
                               (let [resource-type (first permission)]
                                 (assoc values permission
                                        (into {} (for [eid (get entities resource-type)]
                                                   [eid (evaluate values resource-type eid
                                                                  (get bodies permission))])))))
                             values ready))
              completed (loop [values (reduce #(assoc %1 %2 {}) values ready)]
                          (let [next (step values)]
                            (if (= next values) values (recur next))))]
          (recur completed (remove #{ready} pending)))))))

;; ---------------------------------------------------------------------------
;; The engine's answers
;; ---------------------------------------------------------------------------

(defn- walk [lookup query page-size]
  (loop [after nil acc [] pages 0]
    (let [page (lookup (cond-> (assoc query :first page-size) after (assoc :after after)))
          acc (into acc (map :id) (:data page))]
      (if (and (get-in page [:page-info :has-next-page?]) (< pages 1000))
        (recur (get-in page [:page-info :end-cursor]) acc (inc pages))
        acc))))

(defn- answers
  "Every public answer the engine gives for `permission` on this adapter."
  [adapter permission {:keys [folders]}]
  (let [user #(hash-map :type :user :id %)
        folder #(hash-map :type :folder :id %)
        count-of (fn [result] (if (map? result) (:count result) result))]
    {:walks (into {} (for [subject users page-size [1 2 5 100]]
                       [[subject page-size]
                        (walk #(engine/lookup-resources adapter %)
                              {:subject (user subject) :permission permission
                               :resource/type :folder}
                              page-size)]))
     :counts (into {} (for [subject users]
                        [subject (count-of (engine/count-resources
                                            adapter {:subject (user subject)
                                                     :permission permission
                                                     :resource/type :folder}))]))
     :checks (into {} (for [subject users eid folders]
                        [[subject eid] (engine/can? adapter (user subject) permission
                                                    (folder eid))]))
     :subject-walks (into {} (for [eid folders page-size [1 100]]
                               [[eid page-size]
                                (walk #(engine/lookup-subjects adapter %)
                                      {:resource (folder eid) :permission permission
                                       :subject/type :user}
                                      page-size)]))
     :subject-counts (into {} (for [eid folders]
                                [eid (count-of (engine/count-subjects
                                                adapter {:resource (folder eid)
                                                         :permission permission
                                                         :subject/type :user}))]))}))

(defn- expected-answers
  [values permission {:keys [folders]}]
  (let [members (get values [:folder permission])
        resources (fn [subject] (set (filter #(contains? (get members %) subject) folders)))]
    {:resources (into {} (for [subject users] [subject (resources subject)]))
     :subjects (into {} (for [eid folders] [eid (get members eid #{})]))}))

(defn- answer-divergence
  "Nil when `actual` states exactly `expected`, else what differs."
  [expected actual]
  (let [{:keys [resources subjects]} expected]
    (or (some (fn [[[subject page-size] ids]]
                (when-not (and (= (count ids) (count (set ids)))
                               (= (get resources subject) (set ids)))
                  {:walk [subject page-size] :expected (get resources subject) :actual ids}))
              (:walks actual))
        (some (fn [[subject n]]
                (when-not (= (count (get resources subject)) n)
                  {:count subject :expected (count (get resources subject)) :actual n}))
              (:counts actual))
        (some (fn [[[subject eid] allowed]]
                (when-not (= (contains? (get resources subject) eid) allowed)
                  {:check [subject eid] :actual allowed}))
              (:checks actual))
        (some (fn [[[eid page-size] ids]]
                (when-not (and (= (count ids) (count (set ids)))
                               (= (get subjects eid) (set ids)))
                  {:subject-walk [eid page-size] :expected (get subjects eid) :actual ids}))
              (:subject-walks actual))
        (some (fn [[eid n]]
                (when-not (= (count (get subjects eid)) n)
                  {:subject-count eid :expected (count (get subjects eid)) :actual n}))
              (:subject-counts actual))
        (some (fn [[subject _]]
                (let [orders (set (for [page-size [1 2 5 100]]
                                    (get (:walks actual) [subject page-size])))]
                  (when (< 1 (count orders))
                    {:order-depends-on-page-size subject :orders orders})))
              (group-by first (keys (:walks actual)))))))

;; ---------------------------------------------------------------------------
;; One case
;; ---------------------------------------------------------------------------

(defn- outcome [f]
  (try (f)
       (catch #?(:clj Exception :cljs :default) error
         {:thrown (or (:eacl/error (ex-data error)) (:type (ex-data error))
                      (str error))})))

(defn- comparable-across-routes
  "Answers two routes must share exactly: every count and check, reverse
  walks as sets, and forward walks in order when `ordered?`, else as sets."
  [answers ordered?]
  (let [as-sets (fn [walks] (into {} (for [[k ids] walks] [k (set ids)])))]
    (if (:thrown answers)
      answers
      (cond-> (update answers :subject-walks as-sets)
        (not ordered?) (update :walks as-sets)))))

(defn- row-term
  "One flattened generator row in this campaign's expression form."
  [{:keys [source-relation-name target-type target-name]}]
  (cond
    (and (= :self source-relation-name) (= :relation target-type))
    [:relation target-name]
    (= :self source-relation-name) [:self target-name]
    (= :relation target-type) [:arrow-relation source-relation-name target-name]
    :else [:arrow source-relation-name target-name]))

(defn- flattened-generator
  "The flattened generator of a delegating plan as extra permission bodies,
  one per generator node, and the root's node."
  [plan root delegated]
  (let [nodes (for [{:keys [permission]} (:expressions plan)
                    :when (not (contains? delegated permission))
                    :let [rows (cover-plan/generator-definitions plan delegated permission)]]
                [permission
                 [(first permission) (:permission-name (first rows))]
                 (into [:union] (map row-term) rows)])]
    {:bodies (into {} (map (fn [[_ node body]] [node body])) nodes)
     :root (some (fn [[permission node]] (when (= root permission) node)) nodes)}))

(defn- run-root
  [counters {:keys [seed schema adapter bodies data values root]}]
  (let [plan (operator-plan/seal-plan adapter root)
        recursive? (operator-recursive/recursive-plan? plan)
        delegated (operator-plan/delegated-permissions plan)
        expected-delegated (oracle-delegated bodies root)
        ;; Only a recursive plan routes through delegation; an acyclic one
        ;; keeps its least-path cover whatever the analysis says.
        delegating? (and recursive? (some? delegated))
        generator (when delegating?
                    (operator-plan/delegated-generator plan root delegated))
        guarded (when (and recursive? (nil? delegated))
                  (operator-plan/guarded-delegation plan))
        expected-guarded (when (and recursive? (nil? expected-delegated))
                           (oracle-guarded bodies root))
        flattened (cond
                    (and delegating? (nil? generator))
                    (flattened-generator plan root delegated)
                    guarded
                    (flattened-generator plan root (:union-only guarded)))
        fail (fn [what detail]
               (assoc counters :failure
                      (merge {:seed seed :what what :root root :schema schema
                              :tuples (sort (:tuples data))}
                             detail)))
        expected (expected-answers values (second root) data)
        delegated-answers (outcome #(answers adapter (second root) data))
        tabled-answers (when (or delegating? guarded)
                         (outcome #(with-redefs [operator-plan/delegated-permissions
                                                 (constantly nil)
                                                 operator-plan/guarded-delegation
                                                 (constantly nil)]
                                     (answers adapter (second root) data))))
        covers? (fn [values generator]
                  (let [generator-members (get values generator)]
                    (every? (fn [[eid subjects]]
                              (set/subset? subjects (get generator-members eid #{})))
                            (get values root))))]
    (cond
      (not (operator-plan/operator-plan? plan)) counters

      (not= expected-delegated delegated)
      (fail :delegated-permissions {:expected expected-delegated :actual delegated})

      (not= expected-guarded (:members guarded))
      (fail :guarded-members {:expected expected-guarded :actual (:members guarded)})

      ;; A root whose anchor chain ends at no delegated permission (a union
      ;; at the root, say) is generated by the flattened generator.
      (and generator (not (contains? delegated generator)))
      (fail :generator-not-delegated {:generator generator :delegated delegated})

      (and generator (not (covers? values generator)))
      (fail :generator-does-not-cover-root {:generator generator})

      (and flattened
           (not (covers? (denotation (merge bodies (:bodies flattened)) data)
                         (:root flattened))))
      (fail :flattened-generator-does-not-cover-root {:generator (:bodies flattened)})

      (:thrown delegated-answers)
      (fail :engine-error {:error (:thrown delegated-answers)})

      (answer-divergence expected delegated-answers)
      (fail :answers (answer-divergence expected delegated-answers))

      ;; Forward walks under a delegated operand's plan keep the tabled
      ;; route's order. Other walks may order their results differently: the
      ;; generator changed, and recursive operator cursors authenticate its
      ;; fingerprint.
      (and (or delegating? guarded)
           (not= (comparable-across-routes delegated-answers (some? generator))
                 (comparable-across-routes tabled-answers (some? generator))))
      (fail :tabled-evaluator-disagrees {:delegated delegated-answers
                                         :tabled tabled-answers})

      :else
      (-> counters
          (update :roots inc)
          (update (cond delegating? :delegated-roots
                        guarded :guarded-roots
                        recursive? :tabled-roots
                        :else :acyclic-roots)
                  (fnil inc 0))
          (update :operand-generators (fnil + 0) (if generator 1 0))
          (update :flattened-generators (fnil + 0) (if flattened 1 0))
          (update :members + (reduce + (map count (vals (get values root)))))))))

(defn- run-case
  [seed]
  (let [state (atom seed)
        folder-permissions (random-folder-permissions state)
        schema (render-schema folder-permissions)
        data (random-tuples state)
        adapter (outcome #(tuple-adapter/from-schema schema (:tuples data)))
        counters {:roots 0 :delegated-roots 0 :guarded-roots 0 :tabled-roots 0
                  :acyclic-roots 0 :members 0}]
    (if (:thrown adapter)
      (assoc counters :rejected-schemas 1)
      (let [bodies (permission-bodies folder-permissions)
            values (denotation bodies data)
            roots (for [[p body] folder-permissions :when (operator-expression? body)]
                    [:folder p])]
        (reduce (fn [counters root]
                  (let [counters (if (:thrown (outcome #(operator-plan/seal-plan adapter root)))
                                   (update counters :rejected-plans (fnil inc 0))
                                   (run-root counters {:seed seed :schema schema
                                                       :adapter adapter :bodies bodies
                                                       :data data :values values
                                                       :root root}))]
                    (if (:failure counters) (reduced counters) counters)))
                counters
                (concat roots
                        ;; A union naming an operator is an operator plan too.
                        (when (contains? folder-permissions :v0) [[:folder :v0]])))))))

(defn run-campaign
  "Runs cases seeded `first-seed`..; stops at the first divergence, returned
  under `:failure`."
  [first-seed cases]
  (reduce (fn [totals seed]
            (let [result (run-case seed)
                  totals (merge-with + totals (dissoc result :failure))]
              (if-let [failure (:failure result)]
                (reduced (assoc totals :failure failure))
                (update totals :cases inc))))
          {:cases 0}
          (range first-seed (+ first-seed cases))))

(deftest delegation-refines-the-stratified-semantics-test
  (let [report (run-campaign 1 #?(:clj 50 :cljs 10))]
    (is (nil? (:failure report)) (pr-str (:failure report)))
    (testing "the campaign reaches delegated and tabled plans"
      (is (pos? (:delegated-roots report)))
      (is (pos? (:tabled-roots report)))
      (is (pos? (:members report))))
    (testing "and both generators of delegated plans"
      (is (pos? (:operand-generators report)))
      (is (pos? (:flattened-generators report))))))
