(ns eacl.engine.leveled-membership-refinement-test
  "Executable refinement of `eacl.engine.stable-route/check-many-eids` with
  expiring and caveated evidence, against formal/dafny/LeveledMembership.dfy.

  A case is a random union-only schema, random relationships of which some
  are plain, some already expired, some expiring at one of three later
  deadlines, and some caveated readers, and a random sequence of resource
  batches for one subject at one evaluation time. The search's graph is built
  from the sealed plan's rules and the generated relationships alone:

  - an edge or holding is absent (never stored, or expired), conditional
    (caveated, with no context), or decisive until its deadline (nil when
    plain);
  - an arrow-to-relation frame joins its via edge with the intermediate's
    holding, decisive until the earlier deadline.

  A transcription of `search-level` and `decide-leveled` (one memo per level,
  skipped deadlines retained with negatives and inherited by later searches,
  arrow frames re-expanded rather than retained) decides the same roots in
  the same order as production. After every call the campaign requires:

  - equal decisions, per-level memos and retained skip bounds;
  - every time-limited grant ending at the independently computed widest
    witness (the latest first-expiry over decisive witness paths, by a
    fixed point);
  - every plain grant having a permanent witness;
  - every denial having no decisive witness;
  - the permissionship `check-eids` returns, and `check-eids`' own value
    wherever production defers to it."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.engine.memoized-membership-refinement-test :as plain]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-route :as route]
            [eacl.operator.evaluator-test :as evaluator-fixtures]
            [eacl.relationships.staged :as staged]))

(def ^:private now 1000)

(def ^:private users ["u0" "u1"])

;; ---------------------------------------------------------------------------
;; Deterministic cases
;; ---------------------------------------------------------------------------

(defn- random-qualifier
  "Nil (plain), an already expired deadline, or one of three later ones;
  a reader may instead be caveated."
  [state reader? caveat]
  (let [roll (plain/next-int! state 100)]
    (cond
      (and reader? (< roll 10)) {:caveat caveat}
      (< roll 55) nil
      (< roll 65) {:valid-until-ms (- now 100)}
      :else {:valid-until-ms (plain/pick state [1100 1200 1300])})))

(defn- random-relationships
  "[subject-type subject relation resource-type resource] by object id.
  Folders f0.., teams t0..; parent edges may form cycles and self-loops."
  [state]
  (let [folders (mapv #(str "f" %) (range (+ 3 (plain/next-int! state 5))))
        teams (mapv #(str "t" %) (range (inc (plain/next-int! state 3))))]
    {:folders folders
     :teams teams
     :relationships
     (vec
      (distinct
       (concat
        (for [child folders parent folders :when (plain/chance? state 20)]
          [:folder parent :parent :folder child])
        (for [child teams parent teams :when (plain/chance? state 25)]
          [:team parent :parent :team child])
        (for [folder folders user users relation [:reader :owner]
              :when (plain/chance? state 18)]
          [:user user relation :folder folder])
        (for [folder folders team teams :when (plain/chance? state 30)]
          [:team team :team :folder folder])
        (for [team teams user users :when (plain/chance? state 35)]
          [:user user :member :team team]))))}))

(defn- store!
  "A DataScript store with the case's schema and relationships, each written
  with its qualifier; returns the database, id lookups, and each written
  relationship's qualifier by `[subject-eid relation-eid resource-eid]`."
  [state permissions {:keys [folders teams relationships]}]
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn (str "caveat enabled(flag bool) { flag }\n"
                                    (plain/render-schema permissions)))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) (concat users folders teams)))
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          relation-eid (fn [resource-type relation subject-type]
                         (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                       [resource-type relation subject-type]]))
          reader (relation-eid :folder :reader :user)
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn [{:db/id reader :eacl.relation/caveats [caveat]
                           :eacl.relation/allows-unqualified? true}])
      (let [qualifiers
            (into {}
                  (for [[subject-type subject relation resource-type resource]
                        relationships
                        :let [rel (relation-eid resource-type relation subject-type)
                              qualifier (random-qualifier state (= rel reader) caveat)]]
                    (do (staged/write! writer :create
                                       [subject-type (eid subject) rel resource-type (eid resource)]
                                       qualifier)
                        [[(eid subject) rel (eid resource)] qualifier])))]
        {:db (ds/db conn) :eid eid :qualifiers qualifiers}))))

;; ---------------------------------------------------------------------------
;; The search's graph, from the rules and the written relationships alone
;; ---------------------------------------------------------------------------

(defn- class-of
  "A written relationship's evidence at `now`: :absent, :conditional, or
  [:decisive deadline]."
  [qualifiers key]
  (if-not (contains? qualifiers key)
    :absent
    (let [{:keys [caveat valid-until-ms]} (get qualifiers key)]
      (cond
        (and valid-until-ms (<= valid-until-ms now)) :absent
        caveat :conditional
        :else [:decisive valid-until-ms]))))

(defn- lasts? [deadline level]
  (or (nil? deadline) (and (not= :plain level) (<= level deadline))))

(defn- later [a b] (cond (nil? a) b (nil? b) a :else (max a b)))

(defn- joined [via held]
  (cond
    (or (= :absent via) (= :absent held)) :absent
    (or (= :conditional via) (= :conditional held)) :conditional
    :else [:decisive (let [a (second via) b (second held)]
                       (cond (nil? a) b (nil? b) a :else (min a b)))]))

(defn- abstraction
  [plan qualifiers subject]
  (let [reverse-rules (get-in plan [:indexes :reverse-rules])
        stored (group-by (fn [[_ rel resource]] [rel resource]) (keys qualifiers))
        held (fn [relation-eid resource-eid]
               (class-of qualifiers [subject relation-eid resource-eid]))
        intermediates (fn [rule eid]
                        (vec (sort-by first
                                      (for [[intermediate] (get stored [(:via-relation-eid rule) eid])]
                                        [intermediate
                                         (class-of qualifiers
                                                   [intermediate (:via-relation-eid rule) eid])]))))
        subject-slices (set (for [[s rel resource] (keys qualifiers) :when (= subject s)]
                              rel))
        rules-by-ordinal (into {} (for [rules (vals reverse-rules) rule rules]
                                    [(:ordinal rule) rule]))
        seeds (set (concat
                    (for [[node rules] reverse-rules rule rules
                          :when (and (= :relation (:rule rule)) (= :user (:subject-type rule))
                                     (contains? subject-slices (:relation-eid rule)))]
                      node)))
        arrow-seeds (set (for [rule (vals rules-by-ordinal)
                               :when (and (= :arrow-relation (:rule rule))
                                          (= :user (:target-subject-type rule))
                                          (contains? subject-slices (:target-relation-eid rule)))]
                           (:ordinal rule)))
        targets (into {} (for [[node rules] reverse-rules]
                           [node (set (keep (fn [rule]
                                              (case (:rule rule)
                                                :self-permission (:target-node rule)
                                                :arrow-permission (:target-node rule)
                                                :arrow-relation (when (contains? arrow-seeds (:ordinal rule))
                                                                  ::seed)
                                                nil))
                                            rules))]))
        possible (loop [possible seeds]
                   (let [grown (into possible (for [[source ts] targets
                                                    :when (some #(or (= ::seed %) (possible %)) ts)]
                                                source))]
                     (if (= grown possible) possible (recur grown))))]
    {:reverse-rules reverse-rules :held held :intermediates intermediates
     :arrow-seeds arrow-seeds :possible possible}))

;; ---------------------------------------------------------------------------
;; Transcription of the leveled search
;; ---------------------------------------------------------------------------

(defn- note [{:keys [skipped conditional?] :as notes} level class]
  (cond
    (= :absent class) [notes :absent]
    (= :conditional class) [(assoc notes :conditional? true) :absent]
    (lasts? (second class) level) [notes :kept]
    :else [(assoc notes :skipped (later skipped (second class))) :absent]))

(defn- model-search-level
  "[outcome memos skips]: outcome is :found or {:skipped :conditional?}."
  [{:keys [reverse-rules held intermediates arrow-seeds possible]} memos skips level
   [root-node root-eid :as root]]
  (let [memo (get memos level {})]
    (loop [stack [root] visited #{} notes {:skipped nil :conditional? false} memo memo]
      (if (empty? stack)
        (let [{:keys [skipped conditional?]} notes]
          [(select-keys notes [:skipped :conditional?])
           (assoc memos level (reduce #(assoc %1 %2 false) memo visited))
           (if (or skipped conditional?)
             (update skips level #(reduce (fn [m state] (assoc m state [skipped conditional?]))
                                          (or % {}) visited))
             skips)])
        (let [frame (peek stack) stack (pop stack)]
          (if (= ::arrow (first frame))
            (let [[_ ordinal eid] frame
                  rule (some #(when (= ordinal (:ordinal %)) %) (mapcat val reverse-rules))
                  outcome
                  (reduce (fn [[notes stack] [intermediate via]]
                            (if (= :absent via)
                              [notes stack]
                              (if (= :arrow-permission (:rule rule))
                                (let [[notes kept] (note notes level via)]
                                  [notes (cond-> stack (= :kept kept)
                                           (conj [(:target-node rule) intermediate]))])
                                (let [[notes kept]
                                      (note notes level
                                            (joined via (held (:target-relation-eid rule) intermediate)))]
                                  (if (= :kept kept)
                                    (reduced [notes ::found])
                                    [notes stack])))))
                          [notes stack]
                          (rseq (intermediates rule eid)))]
              (if (= ::found (second outcome))
                [:found (assoc memos level (assoc memo root true)) skips]
                (recur (second outcome) visited (first outcome) memo)))
            (let [known (get memo frame)]
              (cond
                (true? known) [:found (assoc memos level (assoc memo root true)) skips]

                (false? known)
                (let [[s c] (get-in skips [level frame])]
                  (recur stack visited
                         (cond-> (update notes :skipped later s)
                           c (assoc :conditional? true))
                         memo))

                (or (contains? visited frame) (not (contains? possible (first frame))))
                (recur stack visited notes memo)

                :else
                (let [[node eid] frame
                      rules (get reverse-rules node)
                      [notes found?]
                      (reduce (fn [[notes _] rule]
                                (if (and (= :relation (:rule rule)) (= :user (:subject-type rule)))
                                  (let [[notes kept] (note notes level (held (:relation-eid rule) eid))]
                                    (if (= :kept kept) (reduced [notes true]) [notes false]))
                                  [notes false]))
                              [notes false]
                              rules)]
                  (if found?
                    [:found (assoc memos level (assoc memo root true)) skips]
                    (recur (into stack
                                 (keep (fn [rule]
                                         (case (:rule rule)
                                           :relation nil
                                           :self-permission (when (contains? possible (:target-node rule))
                                                              [(:target-node rule) eid])
                                           :arrow-permission (when (contains? possible (:target-node rule))
                                                               [::arrow (:ordinal rule) eid])
                                           :arrow-relation (when (contains? arrow-seeds (:ordinal rule))
                                                             [::arrow (:ordinal rule) eid])))
                                       (rseq (vec rules))))
                           (conj visited frame) notes memo)))))))))))

(defn- model-decide
  "[answer memos skips]: answer is true, [:until deadline], false, or
  :point (deferred to the exact point check)."
  [graph memos skips root]
  (loop [level :plain memos memos skips skips]
    (let [[outcome memos skips] (model-search-level graph memos skips level root)]
      (cond
        (= :found outcome) [(if (= :plain level) true [:until level]) memos skips]
        (:skipped outcome) (recur (:skipped outcome) memos skips)
        (:conditional? outcome) [:point memos skips]
        :else [false memos skips]))))

;; ---------------------------------------------------------------------------
;; Independent semantics: the widest decisive witness, by a fixed point
;; ---------------------------------------------------------------------------

(defn- wider [a b]
  (cond (nil? a) b (nil? b) a (= :forever a) a (= :forever b) b :else (max a b)))

(defn- narrower [a b]
  (cond (or (nil? a) (nil? b)) nil (= :forever a) b (= :forever b) a :else (min a b)))

(defn- widest-witness
  "{state widest}: the latest first-expiry over decisive witness paths from
  each state, :forever for a permanent one, absent for none. `entities`
  maps each resource type to its eids."
  [{:keys [reverse-rules held intermediates]} entities]
  (let [states (for [[resource-type :as node] (keys reverse-rules)
                     eid (get entities resource-type)]
                 [node eid])
        value (fn [widest [node eid]]
                (reduce
                 (fn [best rule]
                   (let [contribution
                         (case (:rule rule)
                           :relation (when (= :user (:subject-type rule))
                                       (let [c (held (:relation-eid rule) eid)]
                                         (when (vector? c) (or (second c) :forever))))
                           :self-permission (get widest [(:target-node rule) eid])
                           :arrow-permission
                           (reduce wider nil
                                   (for [[i via] (intermediates rule eid) :when (vector? via)]
                                     (narrower (or (second via) :forever)
                                               (get widest [(:target-node rule) i]))))
                           :arrow-relation
                           (reduce wider nil
                                   (for [[i via] (intermediates rule eid)
                                         :let [c (joined via (held (:target-relation-eid rule) i))]
                                         :when (vector? c)]
                                     (or (second c) :forever))))]
                     (wider best contribution)))
                 nil
                 (get reverse-rules node)))]
    (loop [widest {}]
      (let [next (into {} (for [state states :let [v (value widest state)] :when v] [state v]))]
        (if (= next widest) widest (recur next))))))

;; ---------------------------------------------------------------------------
;; One case
;; ---------------------------------------------------------------------------

(defn- production-state [context plan subject]
  (let [entry (get @context [:subject (:fingerprint plan) :user subject])]
    {:memos (into {} (for [[level memo] (some-> entry :memos deref)] [level @memo]))
     :skips (some-> entry :skips deref)}))

(defn- normalized-memos [memos]
  (into {} (for [[level memo] memos :when (seq memo)]
             [(if (keyword? level) :plain level) memo])))

(defn- normalized-skips [skips]
  (into {} (for [[level entries] skips :when (seq entries)]
             [(if (keyword? level) :plain level) entries])))

(defn- run-plan
  [state counters {:keys [seed db adapter plan qualifiers subject eids entities]}]
  (let [graph (abstraction plan qualifiers subject)
        widest (widest-witness graph entities)
        context (route/membership-context)
        qualification (evaluator-fixtures/qualified-request db now {})
        options {:adapter adapter :plan plan :subject-type :user :subject-eid subject
                 :context context :qualification qualification
                 :physical-chunk-size (plain/pick state [1 2 256])}
        fail (fn [counters what data]
               (assoc counters :failure (merge {:seed seed :what what :root (:root plan)} data)))]
    (loop [calls (inc (plain/next-int! state 4))
           memos {}
           skips {}
           answers {}
           counters (update counters :plans inc)]
      (if (zero? calls)
        counters
        (let [batch (vec (repeatedly (inc (plain/next-int! state 5)) #(plain/pick state eids)))
              actual (try (route/check-many-eids (assoc options :resource-eids batch))
                          (catch #?(:clj Exception :cljs :default) error
                            {:thrown (or (ex-data error) (str error))}))
              ;; Like production, a resource answered earlier in the request
              ;; is not searched again; deferred ones are.
              [expected memos skips answers]
              (reduce (fn [[expected memos skips answers] eid]
                        (if (contains? answers eid)
                          [(conj expected (get answers eid)) memos skips answers]
                          (let [[answer memos skips]
                                (model-decide graph memos skips [(:root plan) eid])]
                            [(conj expected answer) memos skips
                             (cond-> answers (not= :point answer) (assoc eid answer))])))
                      [[] memos skips answers]
                      batch)
              {production-memos :memos production-skips :skips}
              (production-state context plan subject)
              points (mapv #(route/check-eids (assoc options :resource-eid %)) batch)]
          (cond
            (:thrown actual)
            (fail counters :thrown {:batch batch :error (:thrown actual)})

            (not= (normalized-memos memos) (normalized-memos production-memos))
            (fail counters :memos {:batch batch :expected memos :actual production-memos})

            (not= (normalized-skips skips) (normalized-skips production-skips))
            (fail counters :skips {:batch batch :expected skips :actual production-skips})

            (some (fn [[eid want value point]]
                    (not
                     (case want
                       :point (= point value)
                       true (and (true? value) (= :forever (get widest [(:root plan) eid])))
                       false (and (false? value) (nil? (get widest [(:root plan) eid]))
                                  (= :no-permission (evidence/permissionship point)))
                       (and (evidence/has? value)
                            (= (second want) (evidence/valid-until value))
                            (= (second want) (get widest [(:root plan) eid]))))))
                  (map vector batch expected actual points))
            (fail counters :decisions {:batch batch :expected expected :actual actual
                                       :widest (select-keys widest (for [e batch] [(:root plan) e]))})

            (not= (mapv evidence/permissionship points) (mapv evidence/permissionship actual))
            (fail counters :permissionship {:batch batch :points points :actual actual})

            :else
            (recur (dec calls) memos skips answers
                   (-> counters
                       (update :calls inc)
                       (update :decisions + (count batch))
                       (update :timed + (count (filter vector? expected)))
                       (update :deferred + (count (filter #{:point} expected)))
                       (update :levels + (count (filter number? (keys memos))))))))))))

(defn- run-case [seed]
  (let [state (atom seed)
        permissions (plain/random-permissions state)
        generated (random-relationships state)
        {:keys [db eid qualifiers]} (store! state permissions generated)
        adapter (datascript-backend/basis-adapter db {})
        counters {:plans 0 :calls 0 :decisions 0 :timed 0 :deferred 0 :levels 0}
        holdings-limit (if (plain/chance? state 40) 1 route/holdings-limit)
        roots (concat (for [p (keys (:folder permissions))] [:folder p])
                      (for [t (keys (:team permissions))] [:team t]))]
    (with-redefs [route/holdings-limit holdings-limit]
      (reduce
       (fn [counters [root subject]]
         (let [plan (sealed-plan/seal-plan adapter root)
               entities {:folder (mapv eid (:folders generated))
                         :team (mapv eid (:teams generated))}
               counters (run-plan state counters
                                  {:seed seed :db db :adapter adapter :plan plan
                                   :qualifiers qualifiers :subject (eid subject)
                                   :eids (get entities (first root))
                                   :entities entities})]
           (if (:failure counters) (reduced counters) counters)))
       counters
       (for [root roots subject users] [root subject])))))

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

(deftest leveled-search-refines-its-model-test
  (let [report (run-campaign 1 #?(:clj 120 :cljs 25))]
    (is (nil? (:failure report)) (pr-str (:failure report)))
    (testing "the campaign reaches plain, timed, deferred and false answers"
      (is (pos? (:timed report)))
      (is (pos? (:deferred report)))
      (is (pos? (:levels report)))
      (is (< (:timed report) (:decisions report))))))
