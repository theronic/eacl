(ns eacl.caveats.portable.datascript-test
  "EACL on DataScript serving named Caveats with this module's evaluator,
   through the public client API. Runs in ClojureScript and on the JVM."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.caveats.portable :as portable]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(def schema
  "caveat on_days(weekday int, days list<int>) {
  weekday in days
}

caveat empty_handed(carrying list<string>) {
  !(\"launch-codes\" in carrying) && !(\"draft-tweets\" in carrying)
}

definition user {}

definition building {
  relation cleaner: user with on_days
  relation visitor: user | user with empty_handed
  permission enter = cleaner + visitor
}")

(def ^:private hq (eacl/spice-object :building "hq"))
(defn- user [id] (eacl/spice-object :user id))

(defn- building
  "ACME HQ: the cleaner may enter on weekdays, the intern empty-handed until
   time 2000, and the ceo at any time."
  [options]
  (let [conn (datascript/create-conn)
        now (atom 1000)
        client (datascript/make-client conn (assoc options :clock #(deref now)))]
    (eacl/write-schema! client schema)
    (ds/transact! conn [{:eacl/id "cleaner"} {:eacl/id "intern"} {:eacl/id "ceo"} {:eacl/id "hq"}])
    (eacl/write-relationships!
     client
     [{:operation :touch
       :relationship (assoc (eacl/->Relationship (user "cleaner") :cleaner hq)
                            :caveat "on_days" :caveat-context {"days" [1 2 3 4 5]})}
      {:operation :touch
       :relationship (assoc (eacl/->Relationship (user "intern") :visitor hq)
                            :caveat "empty_handed" :valid-until-ms 2000)}
      {:operation :touch :relationship (eacl/->Relationship (user "ceo") :visitor hq)}])
    {:client client :now now}))

(defn- request [id context]
  {:subject (user id) :permission :enter :resource hq :caveat-context context})

(defn- decision [client id context]
  (select-keys (eacl/check-permission client (request id context))
               [:allowed? :permissionship :missing-fields]))

(defn- failure [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (select-keys (ex-data e) [:type :faults]))))

(def ^:private configurations
  ;; The process default serves a client that names no evaluator. In
  ;; ClojureScript that is this module's evaluator; on the JVM, the JVM module
  ;; keeps priority when it is also loaded.
  {"explicit evaluator" {:caveat-evaluator (portable/evaluator)}
   "process default" {}})

(deftest caveated-checks-distinguish-grants-denials-and-missing-context
  (doseq [[label options] configurations
          :let [{:keys [client]} (building options)]]
    (testing label
      (is (= {:allowed? true :permissionship :has-permission}
             (decision client "cleaner" {"weekday" 3})))
      (is (= {:allowed? false :permissionship :no-permission}
             (decision client "cleaner" {"weekday" 6})))
      (is (= {:allowed? false :permissionship :conditional-permission :missing-fields ["weekday"]}
             (decision client "cleaner" {})))
      (is (string? (:residual (eacl/check-permission client (request "cleaner" {})))))
      (is (false? (eacl/can? client (request "cleaner" {}))) "a conditional result is not a grant")
      (is (true? (eacl/can? client (request "cleaner" {"weekday" 5}))))
      (is (= {:allowed? false :permissionship :no-permission}
             (decision client "cleaner" {"weekday" 6 "days" [6]}))
          "the relationship's bound days override the request's")
      (is (= {:allowed? true :permissionship :has-permission}
             (decision client "intern" {"carrying" ["badge"]})))
      (is (= {:allowed? false :permissionship :no-permission}
             (decision client "intern" {"carrying" ["badge" "launch-codes"]})))
      (is (= {:allowed? false :permissionship :no-permission}
             (decision client "intern" {"carrying" ["draft-tweets"]})))
      (is (= {:allowed? true :permissionship :has-permission} (decision client "ceo" {}))))))

(deftest detailed-lookups-and-counts-show-conditional-results
  (let [{:keys [client]} (building {:caveat-evaluator (portable/evaluator)})
        by-subject {:resource hq :permission :enter :subject/type :user}
        summary #(mapv (juxt (comp :id :object) :permissionship :missing-fields) (:data %))]
    (is (= [["hq" :conditional-permission ["weekday"]]]
           (summary (eacl/lookup-resources client {:subject (user "cleaner") :permission :enter
                                                   :resource/type :building :result-policy :detailed}))))
    (is (= [] (:data (eacl/lookup-resources client {:subject (user "cleaner") :permission :enter
                                                    :resource/type :building})))
        "definite lookups omit conditional results")
    (is (= [["cleaner" :conditional-permission ["weekday"]]
            ["intern" :has-permission nil]
            ["ceo" :has-permission nil]]
           (summary (eacl/lookup-subjects client (assoc by-subject :result-policy :detailed
                                                        :caveat-context {"carrying" []})))))
    (is (= ["cleaner" "intern" "ceo"]
           (mapv :id (:data (eacl/lookup-subjects client (assoc by-subject :caveat-context
                                                                {"carrying" [] "weekday" 3}))))))
    (is (= {:count 3 :definite-count 2 :conditional-count 1}
           (select-keys (eacl/count-subjects client (assoc by-subject :result-policy :detailed
                                                           :caveat-context {"carrying" []}))
                        [:count :definite-count :conditional-count])))))

(deftest expiring-caveated-relationship-ends-without-a-write
  (let [{:keys [client now]} (building {:caveat-evaluator (portable/evaluator)})]
    (reset! now 1999)
    (is (true? (eacl/can? client (request "intern" {"carrying" []}))))
    (reset! now 2000)
    (is (= {:allowed? false :permissionship :no-permission}
           (decision client "intern" {"carrying" []})))
    (is (= {:allowed? false :permissionship :no-permission} (decision client "intern" {}))
        "an expired relationship's Caveat is not evaluated")))

(deftest evaluation-faults-deny-and-are-reported
  (let [{:keys [client]} (building {:caveat-evaluator (portable/evaluator)})]
    (is (= {:type :eacl.authorization/evaluation-failure :faults [[:eacl.caveat/evaluation :context-type]]}
           (failure #(eacl/check-permission client (request "cleaner" {"weekday" "wednesday"})))))
    (is (false? (eacl/can? client (request "cleaner" {"weekday" "wednesday"}))))
    ;; Profile 1 preflights work before evaluating. An absent list<string> is
    ;; charged at its declared maximum size, and two membership tests against
    ;; it exceed the limit, so the result is a fault rather than conditional.
    (is (= {:type :eacl.authorization/evaluation-failure :faults [[:eacl.caveat/evaluation :resource-limit]]}
           (failure #(eacl/check-permission client (request "intern" {})))))
    (is (false? (eacl/can? client (request "intern" {}))))))
