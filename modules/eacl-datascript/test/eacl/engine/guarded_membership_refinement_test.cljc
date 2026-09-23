(ns eacl.engine.guarded-membership-refinement-test
  "Executable refinement of the guarded membership search: `check-many-eids`
  over the programs of `eacl.operator.plan/guarded-delegation`, against
  formal/dafny/GuardedMembership.dfy.

  A case is a random linearly guarded program: one or two folder permissions
  that recurse through an intersection guard, a subtracted guard, or both,
  with random witnesses. Guards and witnesses are relations, arrows to
  relations, and union-only permissions reached directly or through an arrow.
  Relationships are random: plain, already expired, expiring at one of three
  later deadlines, or caveated. For one subject at one evaluation time, the
  campaign decides random resource batches with the production search and
  with a transcription of it. The transcription covers:

  - guard evaluation;
  - one memo per level, with skipped deadlines retained with negatives;
  - deferral when a subtracted guard is anything but plainly absent or
    plainly present.

  Union-only permissions are decided for both by the production union
  engine, which `eacl.engine.leveled-membership-refinement-test` certifies.
  After every call the campaign requires:

  - equal decisions, per-level memos and retained skip bounds;
  - the same resources deferred to the exact evaluation;
  - every time-limited grant ending at the independently computed widest
    witness through open guards, every plain grant having a permanent one,
    and every denial having none;
  - every answer's permissionship equal to the tabled recursive
    evaluator's."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.engine.leveled-membership-refinement-test :as leveled]
            [eacl.engine.memoized-membership-refinement-test :as plain]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-route :as route]
            [eacl.operator.evaluator-test :as evaluator-fixtures]
            [eacl.operator.plan :as operator-plan]
            [eacl.operator.recursive :as operator-recursive]
            [eacl.relationships.staged :as staged]))

(def ^:private now 1000)

(def ^:private users ["u0" "u1"])

;; ---------------------------------------------------------------------------
;; Deterministic cases
;; ---------------------------------------------------------------------------

(def ^:private witness-terms
  ["reader" "owner" "team->member" "parent->readable" "readable" "team->t0"])

(def ^:private positive-guards
  ["eligible" "team->member" "readable" "parent->readable" "team->t0"])

(def ^:private negative-guards ["blocked" "team->member"])

(defn- guarded-term [state recursive]
  (case (plain/next-int! state 3)
    0 (str "(" recursive " & " (plain/pick state positive-guards) ")")
    1 (str "(" recursive " - " (plain/pick state negative-guards) ")")
    2 (str "((" recursive " & " (plain/pick state positive-guards) ") - "
           (plain/pick state negative-guards) ")")))

(defn- witnesses [state]
  (let [first-term (plain/pick state witness-terms)
        second-term (plain/pick state witness-terms)]
    (str/join " + " (distinct [first-term second-term]))))

(defn- random-program
  "The folder permissions of one case: `g0`, recursing through a guard on
  its parent's `g0`, or through a guard on `g1`, which recurses through a
  guard on its parent's `g0`."
  [state]
  (if (plain/chance? state 30)
    {:g0 (str (witnesses state) " + " (guarded-term state "g1"))
     :g1 (str (witnesses state) " + " (guarded-term state "parent->g0"))}
    {:g0 (str (witnesses state) " + " (guarded-term state "parent->g0"))}))

(defn- render-schema [program]
  (str "caveat enabled(flag bool) { flag }\n"
       "definition user {}\n"
       "definition team {\n"
       "  relation member: user\n"
       "  relation parent: team\n"
       "  permission t0 = member + parent->t0\n"
       "}\n"
       "definition folder {\n"
       "  relation parent: folder\n"
       "  relation reader: user\n"
       "  relation owner: user\n"
       "  relation eligible: user\n"
       "  relation blocked: user\n"
       "  relation team: team\n"
       "  permission readable = reader + parent->readable\n"
       (apply str (for [[p body] (sort-by key program)]
                    (str "  permission " (name p) " = " body "\n")))
       "}\n"))

(defn- random-relationships [state]
  (let [folders (mapv #(str "f" %) (range (+ 3 (plain/next-int! state 5))))
        teams (mapv #(str "t" %) (range (inc (plain/next-int! state 3))))]
    {:folders folders
     :teams teams
     :relationships
     (vec
      (distinct
       (concat
        (for [child folders parent folders :when (plain/chance? state 22)]
          [:folder parent :parent :folder child])
        (for [child teams parent teams :when (plain/chance? state 25)]
          [:team parent :parent :team child])
        (for [folder folders user users
              relation [:reader :owner :eligible :blocked]
              :when (plain/chance? state 22)]
          [:user user relation :folder folder])
        (for [folder folders team teams :when (plain/chance? state 30)]
          [:team team :team :folder folder])
        (for [team teams user users :when (plain/chance? state 35)]
          [:user user :member :team team]))))}))

(def ^:private caveated-relations
  #{[:folder :reader :user] [:folder :eligible :user] [:folder :blocked :user]
    [:team :member :user]})

(defn- store!
  "A DataScript store with the case's schema and relationships, each written
  with a random qualifier; the relations the guards and witnesses read from
  the subject may be caveated."
  [state program {:keys [folders teams relationships]}]
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn (render-schema program))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) (concat users folders teams)))
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          relation-eid (fn [resource-type relation subject-type]
                         (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                       [resource-type relation subject-type]]))
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          caveatable (into #{} (map #(apply relation-eid %)) caveated-relations)
          writer (qualifiers/writer conn)]
      (ds/transact! conn (vec (for [rel caveatable]
                                {:db/id rel :eacl.relation/caveats [caveat]
                                 :eacl.relation/allows-unqualified? true})))
      (let [qualifiers
            (into {}
                  (for [[subject-type subject relation resource-type resource] relationships
                        :let [rel (relation-eid resource-type relation subject-type)
                              qualifier (leveled/random-qualifier
                                         state (contains? caveatable rel) caveat)]]
                    (do (staged/write! writer :create
                                       [subject-type (eid subject) rel resource-type (eid resource)]
                                       qualifier)
                        [[(eid subject) rel (eid resource)] qualifier])))]
        {:db (ds/db conn) :eid eid :qualifiers qualifiers}))))

;; ---------------------------------------------------------------------------
;; The guarded graph, from the program, the relationships and the oracle
;; ---------------------------------------------------------------------------

(defn- value-class
  "An oracle value as the search classifies it."
  [value]
  (cond
    (true? value) [:decisive nil]
    (or (false? value) (nil? value)) :absent
    (evidence/fault? value) :fault
    (not (evidence/complete? value)) :conditional
    (evidence/has? value) [:decisive (evidence/valid-until value)]
    (evidence/no? value) :absent
    :else :conditional))

(defn- joined [via held]
  (cond
    (= :absent held) :absent
    (= :fault held) :fault
    (or (= :conditional via) (= :conditional held)) :conditional
    :else [:decisive (let [a (second via) b (second held)]
                       (cond (nil? a) b (nil? b) a :else (min a b)))]))

(defn- abstraction
  [program qualifiers subject oracle-value]
  (let [reverse-rules (get-in program [:indexes :reverse-rules])
        stored (group-by (fn [[_ rel resource]] [rel resource]) (keys qualifiers))
        held (fn [relation-eid eid] (leveled/class-of qualifiers [subject relation-eid eid]))
        intermediates (fn [rule eid]
                        (vec (sort-by first
                                      (for [[intermediate] (get stored [(:via-relation-eid rule) eid])]
                                        [intermediate
                                         (leveled/class-of qualifiers
                                                           [intermediate (:via-relation-eid rule) eid])]))))
        oracle (fn [target eid] (value-class (oracle-value target eid)))
        subject-slices (set (for [[s rel] (keys qualifiers) :when (= subject s)] rel))
        arrow-seed? (fn [rule]
                      (and (= :user (:target-subject-type rule))
                           (contains? subject-slices (:target-relation-eid rule))))
        possible (loop [possible #{}]
                   (let [grown
                         (into possible
                               (for [[node rules] reverse-rules
                                     :when (some (fn [rule]
                                                   (case (:rule rule)
                                                     (:self-permission :arrow-permission)
                                                     (contains? possible (:target-node rule))
                                                     (:oracle :arrow-oracle) true
                                                     :relation (and (= :user (:subject-type rule))
                                                                    (contains? subject-slices
                                                                               (:relation-eid rule)))
                                                     :arrow-relation (arrow-seed? rule)))
                                                 rules)]
                                 node))]
                     (if (= grown possible) possible (recur grown))))]
    {:reverse-rules reverse-rules :held held :intermediates intermediates
     :oracle oracle :arrow-seed? arrow-seed? :possible possible}))

(defn- alternative-classes
  [{:keys [held intermediates oracle]} rule eid]
  (case (:rule rule)
    :relation (if (= :user (:subject-type rule)) [(held (:relation-eid rule) eid)] [])
    :oracle [(oracle (:target-node rule) eid)]
    (:arrow-relation :arrow-oracle)
    (into []
          (keep (fn [[intermediate via]]
                  (cond
                    (= :absent via) nil
                    (= :fault via) :fault
                    (= :arrow-oracle (:rule rule))
                    (joined via (oracle (:target-node rule) intermediate))
                    (= :user (:target-subject-type rule))
                    (joined via (held (:target-relation-eid rule) intermediate))
                    :else nil)))
          (intermediates rule eid))))

;; ---------------------------------------------------------------------------
;; Transcription of the guarded search
;; ---------------------------------------------------------------------------

(defn- note [notes level class]
  (if (= :fault class)
    [notes :fault]
    (leveled/note notes level class)))

(defn- guards-outcome
  "[notes outcome]: :kept, :absent, or :fault."
  [graph notes level guards eid]
  (loop [guards guards notes notes]
    (if (empty? guards)
      [notes :kept]
      (let [guard (first guards)
            classes (into [] (mapcat #(alternative-classes graph % eid)) (:alternatives guard))
            [notes outcome]
            (if (= :negative (:sign guard))
              [notes (cond
                       (some #{:fault} classes) :fault
                       (some #(and (vector? %) (nil? (second %))) classes) :absent
                       (some #(not= :absent %) classes) :fault
                       :else :kept)]
              (loop [classes classes notes notes]
                (if (empty? classes)
                  [notes :absent]
                  (let [[notes outcome] (note notes level (first classes))]
                    (if (= :absent outcome)
                      (recur (rest classes) notes)
                      [notes outcome])))))]
        (if (= :kept outcome)
          (recur (rest guards) notes)
          [notes outcome])))))

(defn- rule-class
  "A base rule's own class at `eid`: a relation's grant or the oracle's value."
  [{:keys [held oracle]} rule eid]
  (if (= :oracle (:rule rule))
    (oracle (:target-node rule) eid)
    (held (:relation-eid rule) eid)))

(defn- expand
  "A rule frame: [notes outcome], outcome ::found, :fault, or the grown stack."
  [{:keys [held intermediates oracle] :as graph} notes level stack rule eid]
  (let [[notes guarded] (if (:guards rule)
                          (guards-outcome graph notes level (:guards rule) eid)
                          [notes :kept])]
    (cond
      (= :fault guarded) [notes :fault]
      (= :absent guarded) [notes stack]
      (= :self-permission (:rule rule)) [notes (conj stack [(:target-node rule) eid])]
      :else
      (reduce
       (fn [[notes stack] [intermediate via]]
         (cond
           (= :absent via) [notes stack]
           (= :fault via) (reduced [notes :fault])

           (= :arrow-permission (:rule rule))
           (let [[notes outcome] (note notes level via)]
             (cond
               (= :kept outcome) [notes (conj stack [(:target-node rule) intermediate])]
               (= :fault outcome) (reduced [notes :fault])
               :else [notes stack]))

           :else
           (let [target (if (= :arrow-oracle (:rule rule))
                          (oracle (:target-node rule) intermediate)
                          (held (:target-relation-eid rule) intermediate))
                 [notes outcome] (note notes level (joined via target))]
             (cond
               (= :kept outcome) (reduced [notes ::found])
               (= :fault outcome) (reduced [notes :fault])
               :else [notes stack]))))
       [notes stack]
       (rseq (intermediates rule eid))))))

(defn- base
  "A state's rules without successors: [notes outcome], outcome :found,
  :fault, or nil."
  [graph notes level rules eid]
  (reduce
   (fn [[notes _] rule]
     (if (or (and (= :relation (:rule rule)) (= :user (:subject-type rule)))
             (= :oracle (:rule rule)))
       (let [[notes guarded] (if (:guards rule)
                               (guards-outcome graph notes level (:guards rule) eid)
                               [notes :kept])]
         (cond
           (= :fault guarded) (reduced [notes :fault])
           (= :absent guarded) [notes nil]
           :else
           (let [[notes outcome] (note notes level (rule-class graph rule eid))]
             (cond
               (= :kept outcome) (reduced [notes :found])
               (= :fault outcome) (reduced [notes :fault])
               :else [notes nil]))))
       [notes nil]))
   [notes nil]
   rules))

(defn- successors [{:keys [possible arrow-seed?]} stack rules eid]
  (into stack
        (keep (fn [rule]
                (case (:rule rule)
                  (:relation :oracle) nil
                  :self-permission (when (contains? possible (:target-node rule))
                                     (if (:guards rule)
                                       [::rule rule eid]
                                       [(:target-node rule) eid]))
                  :arrow-permission (when (contains? possible (:target-node rule))
                                      [::rule rule eid])
                  :arrow-relation (when (arrow-seed? rule) [::rule rule eid])
                  :arrow-oracle [::rule rule eid])))
        (rseq (vec rules))))

(defn- model-search-level
  "[outcome memos skips]: outcome is :found, :fault, or {:skipped
  :conditional?}."
  [{:keys [reverse-rules possible] :as graph} memos skips level root]
  (let [memo (get memos level {})
        found [:found (assoc memos level (assoc memo root true)) skips]]
    (loop [stack [root] visited #{} notes {:skipped nil :conditional? false}]
      (if (empty? stack)
        (let [{:keys [skipped conditional?]} notes]
          [(select-keys notes [:skipped :conditional?])
           (assoc memos level (reduce #(assoc %1 %2 false) memo visited))
           (if (or skipped conditional?)
             (update skips level #(reduce (fn [m state] (assoc m state [skipped conditional?]))
                                          (or % {}) visited))
             skips)])
        (let [frame (peek stack) stack (pop stack)]
          (if (= ::rule (first frame))
            (let [[_ rule eid] frame
                  [notes outcome] (expand graph notes level stack rule eid)]
              (cond
                (= ::found outcome) found
                (= :fault outcome) [:fault memos skips]
                :else (recur outcome visited notes)))
            (let [known (get memo frame)]
              (cond
                (true? known) found

                (false? known)
                (let [[s c] (get-in skips [level frame])]
                  (recur stack visited (cond-> (update notes :skipped leveled/later s)
                                         c (assoc :conditional? true))))

                (or (contains? visited frame) (not (contains? possible (first frame))))
                (recur stack visited notes)

                :else
                (let [[node eid] frame
                      rules (get reverse-rules node)
                      [notes outcome] (base graph notes level rules eid)]
                  (cond
                    (= :found outcome) found
                    (= :fault outcome) [:fault memos skips]
                    :else (recur (successors graph stack rules eid)
                                 (conj visited frame) notes)))))))))))

(defn- model-decide
  "[answer memos skips]: true, [:until deadline], false, or :point."
  [graph memos skips root]
  (loop [level :plain memos memos skips skips]
    (let [[outcome memos skips] (model-search-level graph memos skips level root)]
      (cond
        (= :found outcome) [(if (= :plain level) true [:until level]) memos skips]
        (= :fault outcome) [:point memos skips]
        (:skipped outcome) (recur (:skipped outcome) memos skips)
        (:conditional? outcome) [:point memos skips]
        :else [false memos skips]))))

;; ---------------------------------------------------------------------------
;; Independent semantics: the widest decisive witness through open guards
;; ---------------------------------------------------------------------------

(defn- deadline [class] (when (vector? class) (or (second class) :forever)))

(defn- guard-widest
  "How long a guard stays open along a witness: a positive guard's widest
  decisive alternative, :forever for a plainly absent subtracted guard, and
  nil when a subtracted guard is present or uncertain (it grants nothing
  now)."
  [graph guard eid]
  (let [classes (into [] (mapcat #(alternative-classes graph % eid)) (:alternatives guard))]
    (if (= :negative (:sign guard))
      (when (every? #(= :absent %) classes) :forever)
      (reduce leveled/wider nil (keep deadline classes)))))

(defn- widest-witness
  [{:keys [reverse-rules held intermediates oracle] :as graph} entities]
  (let [states (for [[resource-type :as node] (keys reverse-rules)
                     eid (get entities resource-type)]
                 [node eid])
        guards-widest (fn [rule eid]
                        (reduce (fn [widest guard] (leveled/narrower widest (guard-widest graph guard eid)))
                                :forever (:guards rule)))
        value (fn [widest [node eid]]
                (reduce
                 (fn [best rule]
                   (let [through
                         (case (:rule rule)
                           :relation (when (= :user (:subject-type rule))
                                       (deadline (held (:relation-eid rule) eid)))
                           :oracle (deadline (oracle (:target-node rule) eid))
                           :self-permission (get widest [(:target-node rule) eid])
                           :arrow-permission
                           (reduce leveled/wider nil
                                   (for [[i via] (intermediates rule eid)]
                                     (leveled/narrower (deadline via)
                                                       (get widest [(:target-node rule) i]))))
                           :arrow-relation
                           (when (= :user (:target-subject-type rule))
                             (reduce leveled/wider nil
                                     (for [[i via] (intermediates rule eid) :when (vector? via)]
                                       (deadline (joined via (held (:target-relation-eid rule) i))))))
                           :arrow-oracle
                           (reduce leveled/wider nil
                                   (for [[i via] (intermediates rule eid) :when (vector? via)]
                                     (deadline (joined via (oracle (:target-node rule) i))))))]
                     (leveled/wider best (leveled/narrower (guards-widest rule eid) through))))
                 nil
                 (get reverse-rules node)))]
    (loop [widest {}]
      (let [next (into {} (for [state states :let [v (value widest state)] :when v] [state v]))]
        (if (= next widest) widest (recur next))))))

;; ---------------------------------------------------------------------------
;; One case
;; ---------------------------------------------------------------------------

(defn- run-program
  [state counters {:keys [seed db adapter operator-plan member qualifiers subject eids entities]}]
  (let [program (operator-plan/guarded-program operator-plan member)
        qualification (evaluator-fixtures/qualified-request db now {})
        union-context (route/membership-context)
        oracle-value (memoize
                      (fn [target eid]
                        (first (route/check-many-eids
                                {:adapter adapter :plan (sealed-plan/seal-plan adapter target)
                                 :subject-type :user :subject-eid subject
                                 :resource-eids [eid] :qualification qualification
                                 :context union-context}))))
        graph (abstraction program qualifiers subject oracle-value)
        widest (widest-witness graph entities)
        context (route/membership-context)
        deferred (atom [])
        tabled (fn [eids]
                 (:decisions
                  (operator-recursive/evaluate-many
                   {:adapter adapter :plan operator-plan :permission member
                    :scope-identity :guarded-campaign :qualification qualification
                    :candidates (mapv #(hash-map :direction :forward :subject-type :user
                                                 :subject-eid subject :resource-eid %)
                                      eids)})))
        options {:adapter adapter :plan program :subject-type :user :subject-eid subject
                 :context context :qualification qualification
                 :physical-chunk-size (plain/pick state [1 2 256])
                 :oracle oracle-value
                 :fallback (fn [eid] (swap! deferred conj eid) ::deferred)}
        fail (fn [counters what data]
               (assoc counters :failure (merge {:seed seed :what what :member member} data)))]
    (loop [calls (inc (plain/next-int! state 4))
           memos {}
           skips {}
           answers {}
           counters (update counters :programs inc)]
      (if (zero? calls)
        counters
        (let [batch (vec (repeatedly (inc (plain/next-int! state 5)) #(plain/pick state eids)))
              _ (reset! deferred [])
              actual (try (route/check-many-eids (assoc options :resource-eids batch))
                          (catch #?(:clj Exception :cljs :default) error
                            {:thrown (or (ex-data error) (str error))}))
              [expected memos skips answers]
              (reduce (fn [[expected memos skips answers] eid]
                        (if (contains? answers eid)
                          [(conj expected (get answers eid)) memos skips answers]
                          (let [[answer memos skips]
                                (model-decide graph memos skips [member eid])]
                            [(conj expected answer) memos skips
                             (cond-> answers (not= :point answer) (assoc eid answer))])))
                      [[] memos skips answers]
                      batch)
              {production-memos :memos production-skips :skips}
              (leveled/production-state context program subject)
              exact (tabled batch)]
          (cond
            (:thrown actual)
            (fail counters :thrown {:batch batch :error (:thrown actual)})

            (not= (leveled/normalized-memos memos) (leveled/normalized-memos production-memos))
            (fail counters :memos {:batch batch :expected memos :actual production-memos})

            (not= (leveled/normalized-skips skips) (leveled/normalized-skips production-skips))
            (fail counters :skips {:batch batch :expected skips :actual production-skips})

            (not= (set (keep (fn [[eid want]] (when (= :point want) eid))
                             (map vector batch expected)))
                  (set @deferred))
            (fail counters :deferred {:batch batch :expected expected :deferred @deferred})

            (some (fn [[eid want value]]
                    (not
                     (case want
                       :point (= ::deferred value)
                       true (and (true? value) (= :forever (get widest [member eid])))
                       false (and (false? value) (nil? (get widest [member eid])))
                       (and (evidence/has? value)
                            (= (second want) (evidence/valid-until value))
                            (= (second want) (get widest [member eid]))))))
                  (map vector batch expected actual))
            (fail counters :decisions {:batch batch :expected expected :actual actual
                                       :widest (select-keys widest (for [e batch] [member e]))})

            (some (fn [[value exact]]
                    (and (not= ::deferred value)
                         (not= (evidence/permissionship exact) (evidence/permissionship value))))
                  (map vector actual exact))
            (fail counters :permissionship {:batch batch :actual actual :exact exact})

            :else
            (recur (dec calls) memos skips answers
                   (-> counters
                       (update :calls inc)
                       (update :decisions + (count batch))
                       (update :granted + (count (filter #(or (true? %) (vector? %)) expected)))
                       (update :timed + (count (filter vector? expected)))
                       (update :deferred + (count (filter #{:point} expected)))
                       (update :levels + (count (filter number? (keys memos))))))))))))

(defn- run-case [seed]
  (let [state (atom seed)
        program (random-program state)
        generated (random-relationships state)
        {:keys [db eid qualifiers]} (store! state program generated)
        adapter (datascript-backend/basis-adapter db {})
        counters {:programs 0 :calls 0 :decisions 0 :granted 0 :timed 0 :deferred 0
                  :levels 0}
        holdings-limit (if (plain/chance? state 40) 1 route/holdings-limit)
        entities {:folder (mapv eid (:folders generated))
                  :team (mapv eid (:teams generated))}]
    (with-redefs [route/holdings-limit holdings-limit]
      (reduce
       (fn [counters [member subject]]
         (let [operator-plan (operator-plan/seal-plan adapter member)
               counters
               (if (nil? (operator-plan/guarded-delegation operator-plan))
                 (assoc counters :failure {:seed seed :what :not-guarded :member member
                                           :schema (render-schema program)})
                 (run-program state counters
                              {:seed seed :db db :adapter adapter :operator-plan operator-plan
                               :member member :qualifiers qualifiers :subject (eid subject)
                               :eids (:folder entities) :entities entities}))]
           (if (:failure counters) (reduced counters) counters)))
       counters
       (for [p (sort (keys program)) subject users] [[:folder p] subject])))))

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

(deftest guarded-search-refines-its-model-test
  (let [report (run-campaign 1 #?(:clj 120 :cljs 25))]
    (is (nil? (:failure report)) (pr-str (:failure report)))
    (testing "the campaign reaches granted, timed, deferred and false answers"
      (is (pos? (:granted report)))
      (is (pos? (:timed report)))
      (is (pos? (:deferred report)))
      (is (pos? (:levels report)))
      (is (< (:granted report) (:decisions report))))))
