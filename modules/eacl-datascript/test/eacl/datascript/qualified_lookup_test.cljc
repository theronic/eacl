(ns eacl.datascript.qualified-lookup-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.context-test :as errors]
            [eacl.core :as eacl]
            [eacl.cache :as cache]
            [eacl.client.orchestration :as orchestration]
            [eacl.datascript.core :as datascript]
            [eacl.relationships.staged :as staged]
            [eacl.datascript.qualified-check-test :as fixture]))

(defn count-query [check direction policy context]
  (merge {:permission (:permission check) :result-policy policy :caveat-context context}
         (if (= :forward direction)
           {:subject (:subject check) :resource/type :folder}
           {:resource (:resource check) :subject/type :user})))

(defn count! [client direction query]
  ((if (= :forward direction) eacl/count-resources eacl/count-subjects) client query))

(deftest public-qualified-counts-distinguish-conditional-results-and-expiring-bans
  (let [{:keys [client now check]} (fixture/fixture)]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (doseq [time [99 100 200]]
        (reset! now time)
        (doseq [context [{} {"flag" true} {"flag" false}]
                permission [:direct :either :both :recursive]
                direction [:forward :reverse]
                policy [:definite :detailed]]
          (let [check (assoc check :permission permission :caveat-context context)
                membership (:permissionship (eacl/check-permission client check))
                definite (if (= :has-permission membership) 1 0)
                conditional (if (= :conditional-permission membership) 1 0)
                query (count-query check direction policy context)
                cold (count! client direction query)
                warm (count! client direction query)
                uncached (count! client direction (assoc query :cache? false))]
            (is (= (+ definite (if (= :detailed policy) conditional 0)) (:count cold)))
            (when (= :detailed policy)
              (is (= definite (:definite-count cold)))
              (is (= conditional (:conditional-count cold))))
            (is (= (dissoc cold :cached? :cache-basis) (dissoc warm :cached? :cache-basis)
                   (dissoc uncached :cached? :cache-basis)))
            (is (:cached? warm))
            (let [capped (count! client direction (assoc query :count-limit 0))]
              (is (zero? (:count capped)))
              (is (= (pos? (:count cold)) (:truncated? capped)))
              (when (= :detailed policy)
                (is (zero? (:definite-count capped))))
              (when (= :detailed policy)
                (is (zero? (:conditional-count capped)))))))))))

(deftest detailed-empty-counts-retain-both-categories
  (let [{:keys [client check]} (fixture/fixture)]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (doseq [direction [:forward :reverse]]
        (let [query (count-query check direction :detailed {})
              query (assoc-in query [(if (= :forward direction) :subject :resource) :id] "missing")]
          (is (= {:count 0 :limit -1 :definite-count 0 :conditional-count 0}
                 (dissoc (count! client direction query) :cached? :cache-basis))))))))

(deftest public-count-policies-are-admitted-before-clock-or-basis-work
  (let [conn (datascript/create-conn)
        ticks (atom 0)
        client (datascript/make-client conn {:clock #(swap! ticks inc)})]
    (doseq [direction [:forward :reverse]
            policy [nil false "detailed" :unknown]]
      (is (= :eacl.authorization/invalid-result-policy
             (:type (errors/error-data #(count! client direction {:result-policy policy}))))))
    (is (zero? @ticks))))

(deftest detailed-count-cache-ingress-requires-consistent-closed-categories
  (doseq [operation [:count-resources :count-subjects]
          limit [nil 2]]
    (let [query (cond-> {:result-policy :detailed} limit (assoc :count-limit limit))
          key {:operation operation :query {:public query}}
          valid (cond-> {:count 2 :limit (or limit -1) :definite-count 1 :conditional-count 1}
                  limit (assoc :truncated? true))]
      (is (cache/completed-answer-value-valid? operation key valid))
      (doseq [invalid [(dissoc valid :conditional-count)
                       (assoc valid :conditional-count 2)
                       (assoc valid :definite-count -1 :conditional-count 3)
                       (assoc valid :definite-count 0.5 :conditional-count 1.5)
                       (assoc valid :conditional-count nil)
                       (assoc valid :extra true)]]
        (is (false? (cache/completed-answer-value-valid? operation key invalid))))
      (is (false? (cache/completed-answer-value-valid?
                   operation (assoc-in key [:query :public :result-policy] :definite) valid))))))

(deftest qualified-counts-propagate-demanded-faults-in-both-policies
  (let [{:keys [conn client check]} (fixture/fixture)
        qid (ds/q '[:find ?e . :where [?e :eacl.relationship-qualifier/valid-until-ms 200]] (ds/db conn))]
    (ds/transact! conn [[:db/add qid :unknown/private "must not be disclosed"]])
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (doseq [direction [:forward :reverse]
              policy [:definite :detailed]
              permission [:direct :recursive]]
        (is (= :eacl.authorization/evaluation-failure
               (:type (errors/error-data
                       #(count! client direction (count-query (assoc check :permission permission)
                                                              direction policy {}))))))))))

(deftest qualified-counts-cross-recursive-batch-boundaries-with-mixed-categories
  (doseq [direction [:forward :reverse]]
    (let [{:keys [conn client check writer]} (fixture/fixture)
          candidates (into [(get check (if (= :forward direction) :resource :subject))]
                           (map #(eacl/spice-object (if (= :forward direction) :folder :user)
                                                    (str "candidate-" %)))
                           (range 99))
          db (ds/db conn)
          relation (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type [:folder :member :user]])
          caveat (ds/entid db [:eacl.caveat/name "enabled"])]
      (doseq [[index candidate] (map-indexed vector (next candidates))]
        (let [subject (if (= :forward direction) (:subject check) candidate)
              resource (if (= :forward direction) candidate (:resource check))]
          (ds/transact! conn [{:eacl/id (:id candidate)}])
          (eacl/create-relationship! client (eacl/->Relationship subject :member resource))
          (let [db (ds/db conn)]
            (staged/write! writer :replace
                           [:user (ds/entid db [:eacl/id (:id subject)]) relation
                            :folder (ds/entid db [:eacl/id (:id resource)])]
                           (cond-> {:caveat caveat :valid-until-ms 200}
                             (not= 0 (mod index 3))
                             (assoc :caveat-context {"flag" (= 1 (mod index 3))}))))))
      (binding [orchestration/*qualified-authorization-enabled?* true]
        (doseq [permission [:direct :recursive]
                policy [:definite :detailed]]
          (let [check (assoc check :permission permission)
                memberships (frequencies
                             (map (fn [candidate]
                                    (:permissionship
                                     (eacl/check-permission client (assoc check (if (= :forward direction) :resource :subject) candidate))))
                                  candidates))
                definite (get memberships :has-permission 0)
                conditional (get memberships :conditional-permission 0)
                total (+ definite (if (= :detailed policy) conditional 0))
                query (count-query check direction policy {})
                answer (count! client direction query)]
            (is (= total (:count answer)))
            (when (= :detailed policy)
              (is (= [definite conditional] ((juxt :definite-count :conditional-count) answer))))
            (doseq [limit [0 1 32 64 67 68]]
              (let [capped (count! client direction (assoc query :count-limit limit))]
                (is (= (min total limit) (:count capped)))
                (is (= (> total limit) (:truncated? capped)))
                (when (= :detailed policy)
                  (is (= (:count capped) (+ (:definite-count capped) (:conditional-count capped)))))
                (when (= :detailed policy)
                  (is (<= 0 (:definite-count capped) definite)))
                (when (= :detailed policy)
                  (is (<= 0 (:conditional-count capped) conditional)))))))))))
