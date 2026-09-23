(ns eacl.engine.membership-test
  "Memoized membership of one subject (`route/check-many-eids`), the union
  oracle that delegated operator plans use: each value must equal the
  membership-probe point check's for the same point, however the resources
  are ordered and batched over one request-scoped context."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.baseline.capture :as capture]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-route :as route]
            [eacl.operator.evaluator-test :as fixtures]
            [eacl.relationships.staged :as staged]))

(defn- seeded
  [fixture-key]
  (let [fixture ((get capture/fixtures fixture-key))
        {:keys [conn]} (capture/seed-client! fixture)
        db (ds/db conn)
        adapter (datascript-backend/basis-adapter db {})]
    {:fixture fixture :db db :adapter adapter
     :plan (sealed-plan/seal-plan adapter [(:resource-type fixture)
                                           (:permission fixture)])}))

(defn- eid [db object-id] (ds/entid db [:eacl/id object-id]))

(defn- shuffled
  "A deterministic permutation, so a failure replays."
  [values]
  (mapv second (sort-by first (map-indexed (fn [i v] [(mod (* 7919 (inc i)) 104729) v])
                                           values))))

(deftest check-many-equals-point-check-on-frozen-baselines-test
  (doseq [fixture-key (keys capture/fixtures)]
    (testing (str fixture-key)
      (let [{:keys [fixture db adapter plan]} (seeded fixture-key)
            resources (->> (:objects fixture)
                           (filter #(= (:resource-type fixture) (:type %)))
                           (map #(eid db (:id %)))
                           (take 200)
                           vec)
            options {:adapter adapter :plan plan :subject-type :user}]
        (is (seq resources))
        (doseq [[principal-key principal] (:principals fixture)]
          (let [subject-eid (eid db (:id principal))
                expected (mapv #(route/check-eids (assoc options
                                                         :subject-eid subject-eid
                                                         :resource-eid %))
                               resources)
                context (route/membership-context)
                many (fn [eids]
                       (route/check-many-eids (assoc options
                                                     :subject-eid subject-eid
                                                     :resource-eids eids
                                                     :context context)))]
            (testing (str principal-key)
              ;; One context across three orders and two batchings: later
              ;; calls answer from memo state the earlier calls completed.
              (is (= expected (many resources)))
              (is (= (vec (rseq expected)) (many (vec (rseq resources)))))
              (let [order (shuffled (range (count resources)))]
                (is (= (mapv expected order)
                       (into [] (mapcat #(many (mapv resources %)))
                             (partition-all 3 order))))))))))))

(deftest check-many-reuses-completed-work-and-keeps-typed-limits-test
  (let [{:keys [fixture db adapter plan]} (seeded :cyclic-data)
        [_ principal] (first (:principals fixture))
        resources (->> (:objects fixture)
                       (filter #(= (:resource-type fixture) (:type %)))
                       (mapv #(eid db (:id %))))
        commands (atom 0)
        counting (let [fetch (reducer/adapter-fetch-fn adapter)]
                   (fn [descriptor] (swap! commands inc) (fetch descriptor)))
        context (route/membership-context)
        options {:adapter adapter :plan plan :subject-type :user
                 :subject-eid (eid db (:id principal))
                 :fetch-fn counting :context context}
        first-answer (route/check-many-eids (assoc options :resource-eids resources))
        first-commands @commands]
    (is (pos? first-commands))
    (testing "every root decision is memoized for the request"
      (reset! commands 0)
      (is (= first-answer (route/check-many-eids (assoc options :resource-eids resources))))
      (is (zero? @commands)))
    (testing "limits fail typed and unanswered"
      (doseq [[limit value] [[:max-admissions 1] [:max-commands 1] [:max-transitions 1]]]
        (let [error (try
                      (route/check-many-eids
                       (assoc options :context (route/membership-context)
                              :resource-eids resources limit value))
                      nil
                      (catch clojure.lang.ExceptionInfo error (ex-data error)))]
          (is (= :eacl.reducer/limit-exceeded (:eacl/error error)) (str limit))
          (is (= limit (:limit error))))))))

(deftest qualified-evidence-routes-to-the-exact-point-check-test
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn
                          "caveat enabled(flag bool) { flag }
                          definition user {}
                          definition doc {
                            relation reader: user
                            relation parent: doc
                            permission view = reader + parent->view
                          }")
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["user" "a" "b" "c" "d" "e"]))
    (let [eid #(ds/entid (ds/db conn) [:eacl/id %])
          relation (fn [name type]
                     (ds/entid (ds/db conn)
                               [:eacl.relation/resource-type+relation-name+subject-type
                                [:doc name type]]))
          reader (relation :reader :user)
          parent (relation :parent :doc)
          caveat (ds/entid (ds/db conn) [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)
          user (eid "user")
          [a b c d e] (mapv eid ["a" "b" "c" "d" "e"])]
      (ds/transact! conn [(hash-map :db/id reader :eacl.relation/caveats [caveat]
                                    :eacl.relation/allows-unqualified? true)])
      ;; a -> b -> c -> a is a parent cycle; d hangs off it through an
      ;; expiring edge; e is reachable only through a caveated grant.
      (staged/write! writer :create [:user user reader :doc a] nil)
      (staged/write! writer :create [:doc a parent :doc b] nil)
      (staged/write! writer :create [:doc b parent :doc c] nil)
      (staged/write! writer :create [:doc c parent :doc a] nil)
      (staged/write! writer :create [:doc c parent :doc d] {:valid-until-ms 100})
      (staged/write! writer :create [:user user reader :doc e] {:caveat caveat})
      (let [db (ds/db conn)
            adapter (datascript-backend/basis-adapter db {})
            plan (sealed-plan/seal-plan adapter [:doc :view])
            resources [a b c d e]]
        (doseq [time [99 100] context [{} {"flag" true} {"flag" false}]]
          (testing (str "time " time ", context " context)
            (let [qualification (fixtures/qualified-request db time context)
                  options {:adapter adapter :plan plan :subject-type :user
                           :subject-eid user :qualification qualification}
                  expected (mapv #(route/check-eids (assoc options :resource-eid %))
                                 resources)
                  membership (route/membership-context)
                  actual (route/check-many-eids (assoc options :resource-eids resources
                                                       :context membership))]
              (is (= (mapv evidence/permissionship expected)
                     (mapv evidence/permissionship actual)))
              (doseq [[point plain value] (map vector resources expected actual)]
                ;; A plain answer is exact; anything else is the point
                ;; check's own value, certificate included.
                (if (boolean? value)
                  (is (= (evidence/has? plain) value) (str point))
                  (is (= plain value) (str point))))
              (is (= actual (route/check-many-eids
                             (assoc options :resource-eids resources
                                    :context membership)))))))))))

(deftest evidence-classes-for-the-leveled-search-test
  (let [evidence-class @#'route/evidence-class]
    (is (= [:decisive nil] (evidence-class true)))
    (is (= :absent (evidence-class false)))
    (is (= :absent (evidence-class nil)))
    (is (= [:decisive 100] (evidence-class (evidence/with-certificate true 100 true))))
    (is (= :conditional (evidence-class (evidence/with-certificate true 100 false))))
    (is (= :absent (evidence-class (evidence/with-certificate false 100 true))))
    (is (= :conditional (evidence-class (evidence/conditional [:caveat 1] ["flag"]))))
    (is (= :fault (evidence-class (evidence/fault :eacl.qualifier/invalid :qualifier-ref))))))

(deftest expiring-grants-are-decided-by-their-widest-witness-test
  ;; x has two witness paths: through p1 (edge until 500, reader until 800)
  ;; and through p2 (plain edge, reader until 200); its grant ends at 500. y
  ;; is read directly until 300 and through r (edge until 400, plain reader);
  ;; its grant ends at 400. z has a plain witness beside an expiring path. w
  ;; reads itself through a parent self-loop until 250. v's only decisive
  ;; witness expires at 600 beside a caveated one; k is caveated only; u has
  ;; no access.
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn
                          "caveat enabled(flag bool) { flag }
                          definition user {}
                          definition doc {
                            relation reader: user
                            relation parent: doc
                            permission view = reader + parent->view
                          }")
    (ds/transact! conn (mapv #(hash-map :eacl/id %)
                             ["user" "x" "p1" "p2" "y" "q" "r" "z" "w" "v" "vp" "k" "u"]))
    (let [eid #(ds/entid (ds/db conn) [:eacl/id %])
          relation (fn [name type]
                     (ds/entid (ds/db conn)
                               [:eacl.relation/resource-type+relation-name+subject-type
                                [:doc name type]]))
          reader (relation :reader :user)
          parent (relation :parent :doc)
          caveat (ds/entid (ds/db conn) [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)
          user (eid "user")
          grant! (fn [doc qualifier]
                   (staged/write! writer :create [:user user reader :doc (eid doc)] qualifier))
          parent! (fn [parent-doc child qualifier]
                    (staged/write! writer :create
                                   [:doc (eid parent-doc) parent :doc (eid child)] qualifier))]
      (ds/transact! conn [(hash-map :db/id reader :eacl.relation/caveats [caveat]
                                    :eacl.relation/allows-unqualified? true)])
      (parent! "p1" "x" {:valid-until-ms 500})
      (grant! "p1" {:valid-until-ms 800})
      (parent! "p2" "x" nil)
      (grant! "p2" {:valid-until-ms 200})
      (grant! "y" {:valid-until-ms 300})
      (parent! "q" "y" nil)
      (parent! "r" "q" {:valid-until-ms 400})
      (grant! "r" nil)
      (grant! "q" {:valid-until-ms 300})
      (parent! "p1" "z" nil)
      (grant! "z" nil)
      (parent! "w" "w" nil)
      (grant! "w" {:valid-until-ms 250})
      (grant! "v" {:caveat caveat})
      (parent! "vp" "v" nil)
      (grant! "vp" {:valid-until-ms 600})
      (grant! "k" {:caveat caveat})
      (let [db (ds/db conn)
            adapter (datascript-backend/basis-adapter db {})
            plan (sealed-plan/seal-plan adapter [:doc :view])
            resources (mapv eid ["x" "y" "z" "w" "v" "k" "u"])
            deadline (fn [value] (when-not (boolean? value) (evidence/valid-until value)))]
        (doseq [[time expected] [[100 [500 400 nil 250 600 :point nil]]
                                 [450 [500 nil nil nil 600 :point nil]]]]
          (testing (str "time " time)
            (let [qualification (fixtures/qualified-request db time {})
                  options {:adapter adapter :plan plan :subject-type :user
                           :subject-eid user :qualification qualification}
                  points (mapv #(route/check-eids (assoc options :resource-eid %)) resources)
                  stats (atom {})
                  many (binding [route/*membership-stats* stats]
                         (route/check-many-eids (assoc options :resource-eids resources
                                                       :context (route/membership-context))))]
              (is (= (mapv evidence/permissionship points)
                     (mapv evidence/permissionship many))
                  "permissionship always equals the point check")
              (doseq [[resource point value want] (map vector resources points many expected)]
                (cond
                  (= :point want)
                  (is (= point value) (str resource ": the point check's own value"))

                  (evidence/has? value)
                  (do (is (= want (deadline value))
                          (str resource ": the widest witness's first expiry"))
                      (is (or (nil? (deadline value))
                              (<= (deadline point) (deadline value)))
                          (str resource ": never shorter than the point check")))

                  :else
                  (is (and (false? value) (nil? want)) (str resource))))
              (is (= 1 (:fallbacks @stats)) "only the caveat-only resource falls back")
              (is (pos? (:levels @stats 0)) "expiring witnesses are found below the plain level"))))))))
