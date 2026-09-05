(ns eacl.datascript.qualified-check-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.context-test :as errors]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.backend.v8 :as backend]
            [eacl.client.orchestration :as orchestration]
            [eacl.caveats.plan :as caveat-plan]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.relationships.staged :as staged]))

(defn fixture []
  (let [conn (datascript/create-conn)
        now (atom 99)
        evaluator (qualification-fixtures/portable-evaluator (atom 0))
        client (datascript/make-client conn {:clock #(deref now) :caveat-evaluator evaluator})
        user (eacl/spice-object :user "user")
        folder (eacl/spice-object :folder "folder")]
    (eacl/write-schema! client
                       "caveat enabled(flag bool) { flag }
                        definition user {}
                        definition folder {
                          relation member: user
                          relation writer: user
                          relation banned: user
                          relation parent: folder
                          permission direct = member
                          permission either = member + writer
                          permission both = (member & writer) - banned
                          permission recursive = member + (parent->recursive & writer)
                        }")
    (ds/transact! conn [{:eacl/id "user"} {:eacl/id "folder"}])
    (doseq [relation [:member :writer :banned]]
      (eacl/create-relationship! client (eacl/->Relationship user relation folder)))
    (eacl/create-relationship! client (eacl/->Relationship folder :parent folder))
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          relation #(ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                  [:folder % :user]])
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          identity #(vector :user (eid "user") (relation %) :folder (eid "folder"))
          writer (qualifiers/writer conn)]
      (ds/transact! conn [{:db/id (relation :member) :eacl.relation/caveats [caveat]
                          :eacl.relation/allows-unqualified? true}])
      (staged/write! writer :replace (identity :member) {:caveat caveat :valid-until-ms 200})
      (staged/write! writer :replace (identity :banned) {:valid-until-ms 100})
      {:conn conn :client client :now now :writer writer :identity identity
       :check {:subject user :resource folder :permission :direct}})))

(deftest public-point-routes-preserve-conditional-evidence-and-expiring-bans
  (let [{:keys [client now check]} (fixture)]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (doseq [time [99 100 200]
              [context member] [[{} :conditional-permission]
                                [{"flag" true} :has-permission]
                                [{"flag" false} :no-permission]]
              permission [:direct :either :both :recursive]]
        (reset! now time)
        (let [request (assoc check :permission permission :caveat-context context)
              expected (case permission
                         :either :has-permission
                         :both (if (= time 99) :no-permission
                                   (if (< time 200) member :no-permission))
                         (if (< time 200) member :no-permission))
              cold (eacl/check-permission client request)
              warm (eacl/check-permission client request)
              uncached (eacl/check-permission client (assoc request :cache? false))]
          (is (= expected (:permissionship cold)))
          (is (= (= expected :has-permission) (eacl/can? client request)))
          (is (= (dissoc cold :cached? :cache-basis)
                 (dissoc warm :cached? :cache-basis)
                 (dissoc uncached :cached? :cache-basis)))
          (is (:cached? warm))
          (is (= (when (= expected :conditional-permission) ["flag"])
                 (:missing-fields cold))))))))

(deftest expired-public-points-never-compile-undemanded-caveats
  (let [{:keys [client now check]} (fixture)]
    (reset! now 200)
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (with-redefs [caveat-plan/compile-plan (fn [& _] (throw (ex-info "Unexpected program compilation" {})))]
        (doseq [permission [:direct :both :recursive]]
          (is (= :no-permission
                 (:permissionship (eacl/check-permission client (assoc check :permission permission :cache? false))))))))))

(deftest public-batch-shares-qualifier-resolution-across-all-point-routes
  (let [{:keys [client now check]} (fixture)
        invoke backend/invoke
        reads (atom [])]
    (reset! now 100)
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (with-redefs [backend/invoke
                    (fn [adapter operation & args]
                      (when (= operation :qualification-data) (swap! reads conj (first args)))
                      (apply invoke adapter operation args))]
        (let [checks (mapv #(assoc check :permission %) [:direct :either :both :recursive])
              decisions (eacl/check-permissions client {:checks checks :cache? false})]
          (is (= [:conditional-permission :has-permission :conditional-permission :conditional-permission]
                 (mapv :permissionship decisions)))
          (is (= 5 (count @reads)))
          (is (every? #(= 1 %) (vals (frequencies @reads)))))))))

(deftest exact-point-reuse-does-not-alias-unused-context-time-or-unstamped-mutation
  (let [{:keys [conn client now check]} (fixture)
        request (assoc check :caveat-context {"flag" true "unused" 1})]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (is (:allowed? (eacl/check-permission client request)))
      (is (:cached? (eacl/check-permission client request)))
      (is (false? (:cached? (eacl/check-permission client (assoc-in request [:caveat-context "unused"] 2)))))
      (reset! now 100)
      (is (false? (:cached? (eacl/check-permission client request))))
      (let [snapshot (eacl/snapshot client)]
        (try
          (reset! now 200)
          (is (eacl/can? snapshot request))
          (is (false? (eacl/can? client request)))
          (finally (eacl/release! snapshot))))
      (let [qid (ds/q '[:find ?e . :where [?e :eacl.relationship-qualifier/valid-until-ms 200]] (ds/db conn))]
        (ds/transact! conn [[:db/add qid :eacl.relationship-qualifier/valid-until-ms 300]])
        (is (eacl/can? client request))
        (ds/transact! conn [[:db/add qid :unknown/private "must not be disclosed"]])
        (is (= :eacl.authorization/evaluation-failure
               (:type (errors/error-data #(eacl/check-permission client request)))))
        (is (false? (eacl/can? client request)))))))
