(ns eacl.datascript.operator-fault-demand-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(defn- outcome [f]
  (try (f)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
         (:type (ex-data error)))))

(deftest delegated-lookups-preserve-demanded-caveat-faults
  ;; A later arrow target grants plainly, but the earlier target faults.
  ;; Leveled membership used to visit targets backwards and publish a grant
  ;; which could also turn a later point check into a false positive.
  (doseq [body ["parent->reader + parent->readable"
                "parent->base + parent->readable"
                "parent->reader + (parent->readable & eligible)"]
          cache? [false true]]
    (testing (str body ", cache=" cache?)
      (let [conn (datascript/create-conn)
            client (datascript/make-client
                    conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})
            alice (eacl/spice-object :user "alice")
            folder #(eacl/spice-object :folder %)
            child (folder "child")
            request {:subject alice :permission :access :cache? cache?
                     :caveat-context {"m" {}}}
            point #(eacl/check-permission client (assoc request :resource child))
            lookup #(eacl/lookup-resources client (assoc request :resource/type :folder :first 100))
            count-resources #(eacl/count-resources client (assoc request :resource/type :folder))]
        (eacl/write-schema!
         client
         (str "caveat bad(m map<bool>) { m[\"x\"] }
               definition user {}
               definition folder {
                 relation parent: folder
                 relation reader: user | user with bad
                 relation eligible: user
                 permission base = reader
                 permission readable = " body "
                 permission access = readable & eligible
               }"))
        (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "low" "high" "child"]))
        (eacl/create-relationships!
         client
         [(assoc (eacl/->Relationship alice :reader (folder "low")) :caveat "bad")
          (eacl/->Relationship alice :reader (folder "high"))
          (eacl/->Relationship (folder "low") :parent child)
          (eacl/->Relationship (folder "high") :parent child)
          (eacl/->Relationship alice :eligible child)])
        (doseq [operation [point lookup count-resources point]]
          (is (= :eacl.authorization/evaluation-failure (outcome operation))))))))

(defn- corrupt-expiration-store [body damaged-relation exclusion?]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:clock (constantly 100)})
        alice (eacl/spice-object :user "alice")
        folder #(eacl/spice-object :folder %)]
    (eacl/write-schema!
     client
     (str "definition user {} definition folder {
             relation parent: folder
             relation reader: user
             relation eligible: user
             permission readable = " body "
             permission access = " (if exclusion? "eligible - readable" "readable & eligible") "
           }"))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "low" "high" "child"]))
    (eacl/create-relationships!
     client
     (mapv (fn [relationship]
             (cond-> relationship
               (and (= damaged-relation (:relation relationship))
                    (or (= "low" (get-in relationship [:subject :id]))
                        (= "low" (get-in relationship [:resource :id]))))
               (assoc :valid-until-ms 1000)))
           [(eacl/->Relationship alice :reader (folder "low"))
            (eacl/->Relationship alice :reader (folder "high"))
            (eacl/->Relationship (folder "low") :parent (folder "child"))
            (eacl/->Relationship (folder "high") :parent (folder "child"))
            (eacl/->Relationship alice :eligible (folder "child"))]))
    (let [qid (ds/q '[:find ?e . :where [?e :eacl.relationship-qualifier/valid-until-ms 1000]]
                    (ds/db conn))]
      (ds/transact! conn [[:db/add qid :unknown/private "invalid qualifier"]]))
    {:conn conn :client client :alice alice :child (folder "child")}))

(deftest expiration-only-operands-also-preserve-demanded-decoding-faults
  ;; These schemas declare no Caveats. Damaging an otherwise valid expiration
  ;; qualifier must not let reordered search publish a positive decision.
  (doseq [body ["parent->reader + parent->readable"
                "parent->reader + (parent->readable & eligible)"]
          damaged-relation [:reader :parent]
          exclusion? [false true]
          cache? [false true]]
    (testing (pr-str [body damaged-relation exclusion? cache?])
      (let [{:keys [client alice child]} (corrupt-expiration-store body damaged-relation exclusion?)
            request {:subject alice :permission :access :cache? cache?}
            point #(eacl/check-permission client (assoc request :resource child))]
        (doseq [operation [point
                           #(eacl/lookup-resources client (assoc request :resource/type :folder :first 100))
                           #(eacl/count-resources client (assoc request :resource/type :folder))
                           point]]
          (is (= :eacl.authorization/evaluation-failure (outcome operation))))))))

(deftest preflight-does-not-make-an-undemanded-decoding-fault-authoritative
  (let [{:keys [client alice child]}
        (corrupt-expiration-store "reader + parent->reader + (parent->readable & eligible)"
                                  :parent false)]
    (eacl/create-relationship! client (eacl/->Relationship alice :reader child))
    (doseq [cache? [false true]]
      (let [request {:subject alice :permission :access :cache? cache?}]
        (is (:allowed? (eacl/check-permission client (assoc request :resource child))))
        (is (= [child] (:data (eacl/lookup-resources client (assoc request :resource/type :folder :first 100)))))
        (is (= 1 (:count (eacl/count-resources client (assoc request :resource/type :folder)))))))))
