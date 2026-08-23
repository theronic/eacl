(ns eacl.engine.point-check-test
  "Membership-probe point check (membership-probe-point-check): the probe
  search must equal the reverse-enumeration oracle on every frozen baseline,
  its cost must be bounded by reachable intermediates rather than by the
  number of subjects holding the permission, and it must honour the
  reducer's typed limits, the cut-point hook and the observer counters."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.baseline.capture :as capture]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-route :as route]))

(defn- seeded
  [fixture-key]
  (let [fixture ((get capture/fixtures fixture-key))
        {:keys [conn]} (capture/seed-client! fixture)
        db (ds/db conn)
        adapter (datascript-backend/basis-adapter
                 db
                 {:object-id->entid
                  (fn [snapshot object-id]
                    (ds/entid snapshot [:eacl/id object-id]))
                  :entid->object-id
                  (fn [snapshot internal-id]
                    (:eacl/id (ds/entity snapshot internal-id)))})]
    {:fixture fixture :db db :adapter adapter
     :plan (sealed-plan/seal-plan adapter [(:resource-type fixture)
                                           (:permission fixture)])}))

(defn- eid [db object-id] (ds/entid db [:eacl/id object-id]))

(deftest probe-check-equals-enumeration-oracle-test
  ;; Every principal against every resource that any frozen point sample
  ;; names, plus the frozen expectation where one exists.
  (doseq [fixture-key [:explorer-acyclic :folder-chain :mutual-mixed
                       :cyclic-data :broad-union :group-star]]
    (testing (str fixture-key)
      (let [{:keys [fixture db adapter plan]} (seeded fixture-key)
            snapshot (capture/read-snapshot fixture-key)
            ;; Every resource the frozen point samples name, plus up to 200
            ;; objects of the root resource type from the fixture itself.
            resource-ids (->> (concat
                               (->> (:points snapshot)
                                    vals
                                    (mapcat vals)
                                    (map #(second (string/split (:resource %) #":" 2))))
                               (->> (:objects fixture)
                                    (filter #(= (:resource-type fixture) (:type %)))
                                    (map :id)
                                    (take 200)))
                              distinct
                              vec)
            options {:adapter adapter :plan plan :subject-type :user}]
        (is (seq resource-ids))
        (doseq [[principal-key principal] (:principals fixture)
                resource-id resource-ids]
          (let [subject-eid (eid db (:id principal))
                resource-eid (eid db resource-id)
                probe (route/check-eids
                       (assoc options :subject-eid subject-eid
                              :resource-eid resource-eid))
                oracle (route/enumeration-check-eids
                        (assoc options :subject-eid subject-eid
                               :resource-eid resource-eid))]
            (is (= oracle probe)
                (str fixture-key " " principal-key " " resource-id))))
        (doseq [[principal-key samples] (:points snapshot)
                [sample-key {:keys [resource can?]}] samples]
          (let [[_ resource-id] (string/split resource #":" 2)
                principal (get-in fixture [:principals principal-key])]
            (is (= can?
                   (route/check {:adapter adapter :plan plan
                                 :subject-type :user
                                 :subject-id (:id principal)
                                 :resource-id resource-id}))
                (str fixture-key " " principal-key " " sample-key))))))))

(deftest probe-check-cost-is-bounded-by-intermediates-test
  ;; A resource with a million direct subjects: the probe check answers a
  ;; deny and an allow with one exact-bound probe each; the enumeration
  ;; oracle would scan every subject.
  (let [rule {:rule :relation :node [:server :view] :resource-type :server
              :permission :view :relation-eid 100 :subject-type :user
              :ordinal 0 :rank 1}
        plan {:root [:server :view] :recursive? false
              :indexes {:forward-seeds {:user [rule]}
                        :forward-consumers {}
                        :reverse-rules {[:server :view] [rule]}}}
        n 1000000
        commands (atom 0)
        fetch-fn (fn [{:keys [bound-eid limit]}]
                   (swap! commands inc)
                   (let [start (inc (or bound-eid 0))]
                     (range start (min (inc n) (+ start limit)))))
        run (fn [subject-eid]
              (reset! commands 0)
              [(route/check-eids {:fetch-fn fetch-fn :plan plan
                                  :subject-type :user
                                  :subject-eid subject-eid
                                  :resource-eid 1})
               @commands])]
    (testing "denied subject beyond every owner: one probe"
      (is (= [false 1] (run (+ n 5)))))
    (testing "allowed subject with the largest eid: one probe"
      (is (= [true 1] (run n))))
    (testing "allowed subject with the smallest eid: one probe"
      (is (= [true 1] (run 1))))))

(deftest probe-check-limits-cut-point-and-stats-test
  (let [{:keys [fixture db adapter plan]} (seeded :explorer-acyclic)
        [_ principal] (first (:principals fixture))
        snapshot (capture/read-snapshot :explorer-acyclic)
        resource-id (->> (:points snapshot) vals (mapcat vals) first
                         :resource (#(second (string/split % #":" 2))))
        options {:adapter adapter :plan plan :subject-type :user
                 :subject-eid (eid db (:id principal))
                 :resource-eid (eid db resource-id)}]
    (testing "typed limit failure names the reducer budget"
      (let [data (try (route/check-eids (assoc options :max-admissions 1))
                      nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (when data
          (is (= :eacl.reducer/limit-exceeded (:eacl/error data)))
          (is (= :max-admissions (:limit data)))
          (is (= 1 (:max-admissions data))))))
    (testing "the cut-point hook runs at every visit"
      (let [calls (atom 0)]
        (route/check-eids (assoc options :cut-point! (fn [_] (swap! calls inc))))
        (is (pos? @calls))))
    (testing "observer counters report visits, probes and states"
      (let [stats (atom {})]
        (binding [reducer/*observer-stats* stats]
          (route/check-eids options))
        (is (pos? (:derived-grants @stats 0)))
        (is (pos? (:advanced-datoms @stats 0)))
        (is (pos? (:queued-work @stats 0)))))
    (testing "nil ids never hold"
      (is (false? (route/check-eids (assoc options :subject-eid nil))))
      (is (false? (route/check-eids (assoc options :resource-eid nil)))))))

;; ---------------------------------------------------------------------------
;; Bidirectional two-layer arrow arms (bidirectional-arrow-point-check)
;; ---------------------------------------------------------------------------

(defn- two-layer-fetch-fn
  "Synthetic base-tuple store for one document behind one via relation (200)
  whose intermediates carry one membership relation (300):
  `vias` is the document's sorted via-set, `member-pairs` the set of
  [subject-eid intermediate-eid] membership tuples. Answers the three scan
  shapes the routes issue, honouring :bound-eid (exclusive) and :limit."
  [commands doc-eid vias member-pairs]
  (fn [{:keys [operation bound-eid limit] :as descriptor}]
    (swap! commands inc)
    (let [after (fn [values]
                  (cond->> (sort values)
                    bound-eid (drop-while #(<= % bound-eid))
                    limit (take limit)))]
      (case operation
        :resource->subjects
        (condp = (:relation-eid descriptor)
          200 (do (assert (= doc-eid (:resource-eid descriptor)))
                  (after vias))
          300 (after (keep (fn [[subject intermediate]]
                             (when (= intermediate
                                      (:resource-eid descriptor))
                               subject))
                           member-pairs)))
        :subject->resources
        (do (assert (= 300 (:relation-eid descriptor)))
            (after (keep (fn [[subject intermediate]]
                           (when (= subject (:subject-eid descriptor))
                             intermediate))
                         member-pairs)))))))

(def ^:private arrow-permission-plan
  {:root [:doc :view]
   :recursive? false
   :indexes
   {:reverse-rules
    {[:doc :view] [{:rule :arrow-permission :node [:doc :view]
                    :resource-type :doc :permission :view
                    :via-relation-eid 200 :intermediate-type :org
                    :target-node [:org :view] :ordinal 0 :rank 2}]
     [:org :view] [{:rule :relation :node [:org :view]
                    :resource-type :org :permission :view
                    :relation-eid 300 :subject-type :user
                    :ordinal 1 :rank 1}]}}})

(def ^:private arrow-relation-plan
  {:root [:doc :view]
   :recursive? false
   :indexes
   {:reverse-rules
    {[:doc :view] [{:rule :arrow-relation :node [:doc :view]
                    :resource-type :doc :permission :view
                    :via-relation-eid 200 :intermediate-type :org
                    :target-relation-eid 300 :target-subject-type :user
                    :ordinal 0 :rank 2}]}}})

(deftest bidirectional-arrow-cost-is-bounded-by-smaller-side-test
  ;; A document shared with a million intermediates: the arm must cost the
  ;; subject's side (here at most one holding), never the via fan-in — and
  ;; symmetrically, a subject holding a million memberships against a
  ;; single-via document must cost the via side.
  (let [n 1000000
        doc-eid 5000001
        subject 6000001
        run (fn [plan vias member-pairs]
              (let [commands (atom 0)]
                [(route/check-eids
                  {:fetch-fn (two-layer-fetch-fn commands doc-eid
                                                 vias member-pairs)
                   :plan plan :subject-type :user
                   :subject-eid subject :resource-eid doc-eid})
                 @commands]))
        wide-vias (range 1 (inc n))]
    (doseq [plan [arrow-permission-plan arrow-relation-plan]]
      (testing "grant: a million vias, one holding"
        (let [[answer commands]
              (run plan wide-vias #{[subject 500000]})]
          (is (true? answer))
          (is (<= commands 6) (str commands " commands"))))
      (testing "deny: a million vias, no holdings"
        (let [[answer commands] (run plan wide-vias #{})]
          (is (false? answer))
          (is (<= commands 4) (str commands " commands"))))
      (testing "grant: one via, a million holdings"
        (let [[answer commands]
              (run plan [777777]
                   (into #{} (map (fn [i] [subject i]))
                         (range 1 (inc n))))]
          (is (true? answer))
          (is (<= commands 4) (str commands " commands"))))
      (testing "deny: one via outside a million holdings"
        (let [[answer commands]
              (run plan [2000001]
                   (into #{} (map (fn [i] [subject i]))
                         (range 1 (inc n))))]
          (is (false? answer))
          (is (<= commands 6) (str commands " commands")))))))

(deftest bidirectional-arrow-equals-enumeration-oracle-test
  ;; Randomized differential over dense small universes: the bidirectional
  ;; decision must agree with the reverse-enumeration oracle on every
  ;; subject, for both two-layer arm shapes.
  (let [random (java.util.Random. 424242)
        doc-eid 900001
        random-subset (fn [universe density]
                        (set (filter (fn [_] (< (.nextDouble random) density))
                                     universe)))]
    (dotimes [round 60]
      (let [vias (random-subset (range 1 41) (+ 0.05 (* 0.4 (.nextDouble random))))
            subjects (range 101 116)
            member-pairs (set (for [subject subjects
                                    intermediate (range 1 41)
                                    :when (< (.nextDouble random) 0.08)]
                                [subject intermediate]))]
        (doseq [plan [arrow-permission-plan arrow-relation-plan]
                subject subjects]
          (let [options {:plan plan :subject-type :user
                         :subject-eid subject :resource-eid doc-eid}
                probe (route/check-eids
                       (assoc options
                              :fetch-fn (two-layer-fetch-fn
                                         (atom 0) doc-eid vias member-pairs)))
                oracle (route/enumeration-check-eids
                        (assoc options
                               :fetch-fn (two-layer-fetch-fn
                                          (atom 0) doc-eid vias member-pairs)))]
            (is (= oracle probe)
                (str "round " round " subject " subject
                     " vias " (vec (sort vias))
                     " members " (vec (sort (filter #(= subject (first %))
                                                    member-pairs)))))))))))
