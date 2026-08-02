(ns eacl.formal.state-trace-differential-test
  "Public-client state traces with generated cache/cursor authority.

  The same trace runs with cache enabled and disabled on every JVM adapter.
  Expected authorization sets come from the declarative fixture, while all
  decoded cache and cursor decisions pass through generated Java."
  (:require
   [clojure.test :refer [deftest is testing]]
   [datahike.api :as dh]
   [datomic.api :as d]
   [datascript.core :as ds]
   [eacl.cache :as shared-cache]
   [eacl.core :as eacl]
   [eacl.datahike.core :as datahike]
   [eacl.datascript.core :as datascript]
   [eacl.datomic.cache :as datomic-cache]
   [eacl.datomic.core :as datomic]
   [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
   [eacl.datomic.schema :as datomic-schema]
   [eacl.formal.differential-runner :as differential]
   [eacl.formal.production-kernel :as production]))

(def authorization-schema
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(def user (eacl/spice-object :user "user"))
(def document-1 (eacl/spice-object :document "document-1"))
(def document-2 (eacl/spice-object :document "document-2"))
(def relationship-1
  (eacl/->Relationship user :reader document-1))
(def relationship-2
  (eacl/->Relationship user :reader document-2))

(def engine-selection
  {:mode :verified-authoritative
   :kernel production/generated-java-kernel})

(defn- ids
  [page]
  (mapv :id (:data page)))

(defn- assert-public-trace!
  [label cached uncached unrelated-write!]
  (testing label
    (eacl/create-relationships!
     cached [relationship-1 relationship-2])
    (let [all-query
          {:subject user
           :permission :view
           :resource/type :document
           :first 10}
          first-query
          (assoc all-query :first 1)
          first-cached (eacl/lookup-resources cached all-query)
          first-uncached (eacl/lookup-resources uncached all-query)
          exact-hit (eacl/lookup-resources cached all-query)
          page-1 (eacl/lookup-resources cached first-query)
          page-2
          (eacl/lookup-resources
           cached
           (assoc first-query
                  :after
                  (get-in page-1 [:page-info :end-cursor])))]
      (is (= ["document-1" "document-2"]
             (ids first-cached)
             (ids first-uncached)))
      (is (= :passed
             (:status
              (differential/compare-values!
               {:seed 820084
                :case-id [label :initial]
                :values
                [[:formal-semantics
                  ["document-1" "document-2"]]
                 [:public-cache-enabled (ids first-cached)]
                 [:public-cache-disabled (ids first-uncached)]]}))))
      (is (false? (:cached? first-cached)))
      (is (false? (:cached? first-uncached)))
      (is (true? (:cached? exact-hit)))
      (is (= ["document-1" "document-2"]
             (into (ids page-1) (ids page-2))))
      (is (false? (get-in page-2
                          [:page-info :has-next-page?])))
      (unrelated-write!)
      (let [lifted (eacl/lookup-resources cached all-query)
            fresh (eacl/lookup-resources uncached all-query)]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label :unrelated-write]
                  :values
                  [[:formal-semantics
                    ["document-1" "document-2"]]
                   [:public-cache-enabled (ids lifted)]
                   [:public-cache-disabled (ids fresh)]]}))))
        (is (true? (:cached? lifted))))
      (eacl/delete-relationship! cached relationship-1)
      (let [after-revocation
            (eacl/lookup-resources cached all-query)
            fresh-after-revocation
            (eacl/lookup-resources uncached all-query)]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label :revocation]
                  :values
                  [[:formal-semantics ["document-2"]]
                   [:public-cache-enabled
                    (ids after-revocation)]
                   [:public-cache-disabled
                    (ids fresh-after-revocation)]]}))))
        (is (false? (:cached? after-revocation)))
        (is (false?
             (eacl/can?
              cached user :view document-1)))
        (is (true?
             (eacl/can?
              cached user :view document-2)))))))

(deftest generated-cache-and-cursor-state-traces-across-jvm-adapters
  (testing "DataScript"
    (let [conn (datascript/create-conn)
          common
          {:engine-selection engine-selection
           :security-key
           "01234567890123456789012345678901"
           :exact-snapshot-registry-size 16}
          cached (datascript/make-client conn common)
          uncached
          (datascript/make-client
           conn (assoc common :cache shared-cache/no-cache))]
      (eacl/write-schema! cached authorization-schema)
      (ds/transact!
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}])
      (assert-public-trace!
       "DataScript generated authority"
       cached uncached
       #(ds/transact!
         conn [{:db/doc "unrelated"}]))))

  (testing "Datahike"
    (let [conn (datahike/create-conn)
          common
          {:engine-selection engine-selection
           :security-key
           "01234567890123456789012345678901"}
          cached (datahike/make-client conn common)
          uncached
          (datahike/make-client
           conn (assoc common :cache shared-cache/no-cache))]
      (eacl/write-schema! cached authorization-schema)
      (dh/transact
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}])
      (assert-public-trace!
       "Datahike generated authority"
       cached uncached
       #(dh/transact
         conn [{:db/doc "unrelated"}]))))

  (testing "Datomic"
    (with-mem-conn [conn datomic-schema/v7-schema]
      (let [common
            {:engine-selection engine-selection
             :coherence-authority :managed
             :page-token-key
             "01234567890123456789012345678901"
             :zed-token-key
             "12345678901234567890123456789012"}
            cached
            (datomic/make-client
             conn
             (assoc common
                    :cache {:remember-answers true}))
            uncached
            (datomic/make-client
             conn
             (assoc common :cache datomic-cache/no-cache))]
        (eacl/write-schema! cached authorization-schema)
        @(d/transact
          conn
          [{:db/id (d/tempid :db.part/user)
            :eacl/id "user"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-1"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-2"}])
        (assert-public-trace!
         "Datomic generated authority"
         cached uncached
         #(deref
           (d/transact
            conn
            [{:db/id (d/tempid :db.part/user)
              :db/doc "unrelated"}])))))))
