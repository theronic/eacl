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
