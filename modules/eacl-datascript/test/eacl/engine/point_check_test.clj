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
        adapter (datascript-backend/snapshot-adapter
                 db
                 {:object-id->entid
                  (fn [snapshot object-id]
                    (ds/entid snapshot [:eacl/id object-id]))
                  :entid->object-id
                  (fn [snapshot internal-id]
                    (:eacl/id (ds/entity snapshot internal-id)))
                  :conn conn
                  :source-lifecycle (str (gensym (str "point-check-"
                                                      (name fixture-key)
                                                      "-")))})]
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
