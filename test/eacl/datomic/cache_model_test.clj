(ns eacl.datomic.cache-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(def ^:private owner-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner
   }")

(def ^:private owner-and-auditor-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner + auditor
   }")

(defn- object
  [type id]
  (spice-object type id))

(defn- page-answer
  [page]
  {:data (mapv (juxt :type :id) (:data page))
   :page-info (select-keys (:page-info page)
                           [:has-next-page? :has-previous-page?])})

(defn- assert-same-answers!
  [cached uncached user-id account-id label]
  (let [user (object :user user-id)
        account (object :account account-id)
        forward {:subject user
                 :permission :admin
                 :resource/type :account
                 :first 100}
        reverse {:resource account
                 :permission :admin
                 :subject/type :user
                 :first 100}]
    (is (= (eacl/can? uncached user :admin account)
           (eacl/can? cached user :admin account))
        (str label " can?"))
    (is (= (page-answer (eacl/lookup-resources uncached forward))
           (page-answer (eacl/lookup-resources cached forward)))
        (str label " lookup-resources"))
    (is (= (page-answer (eacl/lookup-subjects uncached reverse))
           (page-answer (eacl/lookup-subjects cached reverse)))
        (str label " lookup-subjects"))
    (is (= (eacl/count-resources uncached (dissoc forward :first))
           (eacl/count-resources cached (dissoc forward :first)))
        (str label " count-resources"))
    (is (= (eacl/count-subjects uncached (dissoc reverse :first))
           (eacl/count-subjects cached (dissoc reverse :first)))
        (str label " count-subjects"))))

(deftest randomized-cache-and-mutation-differential-test
  (doseq [seed (range 5)]
    (testing (str "seed " seed)
      (with-mem-conn [conn schema/v7-schema]
        (let [random (java.util.Random. seed)
              cached (core/make-client
                      conn
                      {:cache {:max-weight (* 8 1024 1024)
                               :max-entry-weight (* 2 1024 1024)
                               :max-entries 2048
                               :exact-results? true}})
              uncached (atom (core/make-client conn {:cache false}))
              user-ids (mapv #(str "user-" %) (range 8))
              account-ids (mapv #(str "account-" %) (range 8))]
          (eacl/write-schema! cached owner-schema)
          (reset! uncached (core/make-client conn {:cache false}))
          @(d/transact
            conn
            (mapv (fn [id] {:eacl/id id})
                  (concat user-ids account-ids)))
          (dotimes [step 50]
            (when (= 25 step)
              (eacl/write-schema! cached owner-and-auditor-schema)
              ;; Other clients are deliberately not polled for schema changes.
              (reset! uncached (core/make-client conn {:cache false})))
            (let [user-id (nth user-ids (.nextInt random (count user-ids)))
                  account-id (nth account-ids
                                  (.nextInt random (count account-ids)))
                  relation (if (.nextBoolean random) :owner :auditor)
                  operation (if (.nextBoolean random) :touch :delete)
                  relationship
                  (->Relationship (object :user user-id)
                                  relation
                                  (object :account account-id))]
              (eacl/write-relationship!
               cached
               {:operation operation
                :subject (:subject relationship)
                :relation relation
                :resource (:resource relationship)})
              ;; Exercise unrelated application basis churn as well as
              ;; relationship no-ops and relevant dependency changes.
              (when (zero? (mod step 7))
                @(d/transact conn [{:eacl/id (str "application-" seed "-" step)}]))
              (dotimes [sample 2]
                (let [sample-user
                      (nth user-ids (.nextInt random (count user-ids)))
                      sample-account
                      (nth account-ids
                           (.nextInt random (count account-ids)))]
                  (assert-same-answers!
                   cached @uncached sample-user sample-account
                   (str "step " step ", sample " sample)))))))))))
