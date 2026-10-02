(ns eacl.datascript.caveat-context-admission-test
  "Fail-fast admission of a request's Caveat context against the Caveats the
  request can reach: the Caveats admitted by the Relations of the requested
  permission's closure (and of a relationship filter clause). A supplied
  field that a reachable Caveat declares, but whose value fits none of the
  declared types, rejects the request before evaluation."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(def ^:private schema
  "caveat age_ok(age int) { age >= 18 }
   caveat region_ok(region string) { region == \"eu\" }
   caveat level_int(level int) { level > 1 }
   caveat level_string(level string) { level == \"high\" }
   caveat tags_ok(tags list<string>) { \"a\" in tags }
   caveat unreachable(secret int) { secret == 1 }
   definition user {}
   definition team {
     relation member: user with region_ok
     permission belongs = member
   }
   definition doc {
     relation viewer: user with age_ok
     relation team: team
     relation leveled: user with level_int | user with level_string
     relation tagged: user with tags_ok
     relation hidden: user with unreachable
     relation owner: user with unreachable
     permission view = viewer + team->belongs
     permission level = leveled
     permission tags = tagged
     permission secret = hidden
   }")

(defn- fixture []
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock (constantly 1000)})
        alice (eacl/spice-object :user "alice")
        doc (eacl/spice-object :doc "doc")
        team (eacl/spice-object :team "team")]
    (eacl/write-schema! client schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "doc" "team"]))
    (eacl/create-relationships!
     client
     [(assoc (eacl/->Relationship alice :viewer doc) :caveat "age_ok")
      (assoc (eacl/->Relationship alice :member team) :caveat "region_ok")
      (eacl/->Relationship team :team doc)
      (assoc (eacl/->Relationship alice :leveled doc) :caveat "level_string")
      (assoc (eacl/->Relationship alice :tagged doc) :caveat "tags_ok")
      (assoc (eacl/->Relationship alice :owner doc) :caveat "unreachable")])
    {:client client :alice alice :doc doc}))

(defn- error-data [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error (ex-data error))))

(defn- operations
  "Every authorization operation for `permission`, with `context`."
  [{:keys [client alice doc]} permission context]
  (let [check {:subject alice :permission permission :resource doc}]
    {:check #(eacl/check-permission client (assoc check :caveat-context context))
     :can? #(eacl/can? client (assoc check :caveat-context context))
     :batch #(eacl/check-permissions client {:checks [check] :caveat-context context})
     :lookup-resources #(eacl/lookup-resources client {:subject alice :permission permission :resource/type :doc
                                                       :caveat-context context :first 10})
     :lookup-subjects #(eacl/lookup-subjects client {:resource doc :permission permission :subject/type :user
                                                     :caveat-context context :first 10})
     :count-resources #(eacl/count-resources client {:subject alice :permission permission :resource/type :doc
                                                     :caveat-context context})
     :count-subjects #(eacl/count-subjects client {:resource doc :permission permission :subject/type :user
                                                   :caveat-context context})
     :authorized-read #(eacl/read-relationships client {:resource/type :doc :subject/type :user
                                                        :caveat-context context :first 10
                                                        :authorization {:subject alice :permission permission
                                                                        :on :resource}})
     :missing-subject #(eacl/check-permission client (assoc check :caveat-context context
                                                           :subject (eacl/spice-object :user "nobody")))}))

(defn- rejection [parameter expected caveats]
  {:type :eacl.caveat/invalid :eacl/error :eacl.caveat/invalid :reason :context-type
   :parameter parameter :expected expected :caveats caveats})

(deftest a-value-no-reachable-declaration-admits-is-rejected-before-evaluation
  (let [data (fixture)]
    (doseq [[permission context expected]
            [[:view {"age" "twenty"} (rejection "age" [:int] ["age_ok"])]
             ;; Reached through an arrow to another definition's permission.
             [:view {"region" 5} (rejection "region" [:string] ["region_ok"])]
             [:tags {"tags" [1 2]} (rejection "tags" [[:list :string]] ["tags_ok"])]
             [:tags {"tags" "a"} (rejection "tags" [[:list :string]] ["tags_ok"])]
             ;; The first offending field in parameter order is reported.
             [:view {"region" 5 "age" "twenty" "unused" true} (rejection "age" [:int] ["age_ok"])]]
            [operation run] (operations data permission context)]
      (testing (pr-str [permission context operation])
        (if (= :can? operation)
          ;; The Boolean boundary converts only evaluation failures.
          (is (= :eacl.caveat/invalid (:type (error-data run))))
          (is (= expected (select-keys (error-data run) (keys expected)))))))
    (let [batch (error-data #(eacl/check-permissions
                              (:client data)
                              {:caveat-context {"age" "twenty"}
                               :checks [{:subject (:alice data) :permission :level :resource (:doc data)}
                                        {:subject (:alice data) :permission :view :resource (:doc data)}]}))]
      (is (= 1 (:demand-index batch)))
      (is (= :context-type (:reason batch))))))

(deftest admission-ignores-undeclared-and-unreachable-fields-and-accepts-ambiguous-ones
  (let [{:keys [client alice doc] :as data} (fixture)
        check #(eacl/check-permission client {:subject alice :permission %1 :resource doc :caveat-context %2})]
    ;; No reachable Caveat declares these fields.
    (doseq [[permission context] [[:view {"unknown" "x"}]
                                  [:view {"secret" "not an int"}]
                                  [:level {"age" "twenty"}]]]
      (doseq [[operation run] (operations data permission context)]
        (is (not= :eacl.caveat/invalid (:type (error-data run))) (pr-str [permission context operation]))))
    (is (= :conditional-permission (:permissionship (check :view {"unknown" "x"}))))
    ;; `level` is declared int by one reachable Caveat and string by another:
    ;; a value either admits passes, and an edge whose Caveat rejects it
    ;; faults only when evaluation demands it.
    (is (= :has-permission (:permissionship (check :level {"level" "high"}))))
    (is (= :eacl.authorization/evaluation-failure
           (:type (error-data #(check :level {"level" 5})))))
    (is (false? (eacl/can? client {:subject alice :permission :level :resource doc :caveat-context {"level" 5}})))
    ;; A container value of the declared item type, or an empty one, fits.
    (is (= :has-permission (:permissionship (check :tags {"tags" ["a" "b"]}))))
    (is (= :no-permission (:permissionship (check :tags {"tags" []}))))
    (is (= :has-permission (:permissionship (check :view {"age" 21}))))))

(deftest a-relationship-filter-clause-adds-its-relation-to-the-reachable-caveats
  (let [{:keys [client alice doc]} (fixture)
        lookup (fn [context filtered?]
                 (error-data
                  #(eacl/lookup-resources
                    client (cond-> {:subject alice :permission :view :resource/type :doc
                                    :caveat-context context :first 10}
                             filtered? (assoc :resource/relationship {:relation :owner :subject alice})))))
        subjects (fn [context]
                   (error-data
                    #(eacl/lookup-subjects
                      client {:resource doc :permission :view :subject/type :user :first 10
                              :caveat-context context
                              :subject/relationship {:relation :owner :resource doc}})))]
    (is (nil? (lookup {"age" 21 "secret" "x"} false)))
    (is (= (rejection "secret" [:int] ["unreachable"])
           (select-keys (lookup {"age" 21 "secret" "x"} true)
                        [:type :eacl/error :reason :parameter :expected :caveats])))
    (is (= :context-type (:reason (subjects {"age" 21 "secret" "x"}))))))

(deftest a-wildcard-branch-caveat-is-reachable-like-any-other
  ;; Membership in `public` unions the subject's own relationship with the
  ;; wildcard's, so `gate_ok` on the `user:*` branch is reachable from every
  ;; operation on `view` and `edit`, the missing-subject path included.
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock (constantly 1000)})
        alice (eacl/spice-object :user "alice")
        doc (eacl/spice-object :doc "doc")
        expected (rejection "code" [:int] ["gate_ok"])]
    (eacl/write-schema! client "caveat gate_ok(code int) { code == 1 }
                                definition user {}
                                definition doc {
                                  relation public: user | user:* with gate_ok
                                  relation editor: user
                                  permission view = public
                                  permission edit = public & editor
                                }")
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "doc"]))
    (eacl/create-relationships!
     client [(assoc (eacl/->Relationship (eacl/spice-object :user "*") :public doc) :caveat "gate_ok")
             (eacl/->Relationship alice :editor doc)])
    (doseq [permission [:view :edit]
            [operation run] (operations {:client client :alice alice :doc doc} permission {"code" "one"})]
      (testing (pr-str [permission operation])
        (if (= :can? operation)
          (is (= :eacl.caveat/invalid (:type (error-data run))))
          (is (= expected (select-keys (error-data run) (keys expected)))))))
    (is (= :has-permission
           (:permissionship (eacl/check-permission
                             client {:subject alice :permission :edit :resource doc
                                     :caveat-context {"code" 1}}))))))

(deftest schema-independent-admission-still-precedes-reachability
  ;; spec/04 §3.7 admission: malformed values fail before any schema work,
  ;; with the established error shape and no parameter.
  (let [{:keys [client alice doc]} (fixture)
        data (error-data #(eacl/check-permission client {:subject alice :permission :view :resource doc
                                                         :caveat-context {"age" [[1]]}}))]
    (is (= :eacl.caveat/invalid (:type data)))
    (is (= :context-type (:reason data)))
    (is (not (contains? data :parameter)))))

(deftest bound-contexts-are-already-admitted-at-write-time
  (let [{:keys [client alice doc]} (fixture)
        data (error-data #(eacl/write-relationship!
                           client (assoc (eacl/->Relationship alice :viewer doc)
                                         :operation :touch
                                         :caveat "age_ok" :caveat-context {"age" "twenty"})))]
    (is (= :eacl.caveat/invalid (:type data)))))

(deftest the-eacl-rust-context-type-repros-are-rejected-at-admission
  ;; EACL-RS-007 and EACL-RS-008 fault through a string for an `int`
  ;; parameter. That fault is certain before evaluation, so every operation
  ;; rejects the request alike instead of a walk failing while checks pass.
  ;; The repros never read `n`; SpiceDB's schema reader rejects an unused
  ;; parameter, so `c1` here also tests `n >= 0`.
  (let [rs007 (fn [schema]
                (let [conn (datascript/create-conn)
                      client (datascript/make-client
                              conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                    :clock (constantly 2000000)})
                      project (eacl/spice-object :project "a")]
                  (eacl/write-schema! client schema)
                  (ds/transact! conn (mapv #(hash-map :eacl/id %) ["a" "r" "o"]))
                  (eacl/create-relationship!
                   client (assoc (eacl/->Relationship project :member (eacl/spice-object :group "r"))
                                 :caveat "c1" :caveat-context {"n" 0}))
                  (let [request {:subject project :permission :share :caveat-context {"n" "b"}}]
                    (mapv #(select-keys (error-data %) [:type :reason :parameter :expected :caveats])
                          [#(eacl/lookup-resources client (assoc request :resource/type :org :first 1000))
                           #(eacl/count-resources client (assoc request :resource/type :org))
                           #(eacl/check-permission client (assoc request :resource (eacl/spice-object :org "o")))
                           #(eacl/check-permission client (assoc request :resource (eacl/spice-object :group "r")))]))))
        expected {:type :eacl.caveat/invalid :reason :context-type
                  :parameter "n" :expected [:int] :caveats ["c1"]}]
    (doseq [org ["relation manager: group\n permission share = manager->share"
                 "relation reader: org\n relation manager: group\n permission share = manager->share + reader->share"]]
      (is (= (repeat 4 expected)
             (rs007 (str "caveat c1(n int, s string) { n >= 0 && s != \"b\" }
                          definition project {}
                          definition group {
                            relation member: project with c1
                            permission share = member
                          }
                          definition org {\n " org "\n}"))))))
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock (constantly 1000000)})
        u (eacl/spice-object :user "u")
        o (eacl/spice-object :user "o")
        d (eacl/spice-object :doc "d")]
    (eacl/write-schema! client "caveat c1(n int, s string) { n >= 0 && s != \"ab\" }
                                caveat c2(role string) { role != \"b\" }
                                definition user {}
                                definition doc {
                                  relation viewer: user with c2
                                  relation owner: user with c1
                                  permission view = viewer
                                }")
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["u" "o" "d"]))
    (eacl/create-relationships! client [(assoc (eacl/->Relationship u :viewer d) :caveat "c2")
                                        (assoc (eacl/->Relationship o :owner d) :caveat "c1")])
    (doseq [policy [:definite :detailed] context [{"n" "x"} {"n" "x" "role" "a"} {"n" "x" "role" "b"}]]
      (is (= {:type :eacl.caveat/invalid :reason :context-type :parameter "n"}
             (select-keys (error-data #(eacl/lookup-resources
                                        client {:subject u :permission :view :resource/type :doc
                                                :caveat-context context :first 10 :result-policy policy
                                                :resource/relationship {:relation :owner :subject o}}))
                          [:type :reason :parameter]))))
    ;; Without the filter clause, `n` reaches no Caveat and is ignored.
    (is (= :conditional-permission
           (:permissionship (eacl/check-permission client {:subject u :permission :view :resource d
                                                           :caveat-context {"n" "x"}}))))))
