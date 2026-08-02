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
   [eacl.formal.production-kernel :as production]
   [eacl.verified-kernel :as verified]))

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

(def formal-objects
  [{:type "user" :id "user"}
   {:type "document" :id "document-1"}
   {:type "document" :id "document-2"}])

(def formal-schema
  {:relations
   [{:resource-type "document"
     :relation "reader"
     :subject-type "user"}]
   :permissions
   [{:resource-type "document"
     :permission "view"}]
   :definitions
   [{:kind :direct-relation
     :resource-type "document"
     :permission "view"
     :relation "reader"
     :subject-type "user"}]})

(def formal-limits
  {:max-derived-grants 1000
   :max-advanced-datoms 1000
   :max-queued-work 1000})

(defn- formal-object
  [object]
  {:type (name (:type object))
   :id (:id object)})

(defn- formal-relationship
  [{:keys [subject relation resource]}]
  {:subject (formal-object subject)
   :relation (name relation)
   :resource (formal-object resource)})

(defn- formal-evaluate
  [relationships request]
  (verified/decide
   engine-selection
   :authorization-evaluation
   {:objects formal-objects
    :schema formal-schema
    :relationships (mapv formal-relationship relationships)
    :request request
    :limits formal-limits}
   #(throw (ex-info "legacy authorization oracle must not run" {}))))

(defn- public-object-set
  [page]
  (into #{} (map formal-object) (:data page)))

(defn- assert-public-authorization!
  [label cached uncached relationships]
  (let [resource-query
        {:subject user
         :permission :view
         :resource/type :document
         :first 10}
        formal-resource-query
        {:operation :lookup-resources
         :subject (formal-object user)
         :permission "view"
         :resource-type "document"}
        reverse-query
        {:resource document-2
         :permission :view
         :subject/type :user
         :first 10}
        formal-reverse-query
        {:operation :lookup-subjects
         :resource (formal-object document-2)
         :permission "view"
         :subject-type "user"}
        formal-resources
        (formal-evaluate relationships formal-resource-query)
        formal-subjects
        (formal-evaluate relationships formal-reverse-query)
        formal-count-resources
        (formal-evaluate
         relationships
         (assoc formal-resource-query
                :operation :count-resources
                :count-limit 1))
        formal-count-subjects
        (formal-evaluate
         relationships
         (assoc formal-reverse-query
                :operation :count-subjects
                :count-limit 1))]
    (doseq [[mode client]
            [[:cache-enabled cached]
             [:cache-disabled uncached]]]
      (let [actual-resources
            (eacl/lookup-resources client resource-query)
            actual-subjects
            (eacl/lookup-subjects client reverse-query)
            actual-count-resources
            (eacl/count-resources
             client
             (-> resource-query
                 (dissoc :first)
                 (assoc :count-limit 1)))
            actual-count-subjects
            (eacl/count-subjects
             client
             (-> reverse-query
                 (dissoc :first)
                 (assoc :count-limit 1)))]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :lookup-resources]
                  :values
                  [[:verified-generated-java
                    (set (:items formal-resources))]
                   [:public-client
                    (public-object-set actual-resources)]]}))))
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :lookup-subjects]
                  :values
                  [[:verified-generated-java
                    (set (:items formal-subjects))]
                   [:public-client
                    (public-object-set actual-subjects)]]}))))
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :count-resources]
                  :values
                  [[:verified-generated-java
                    (select-keys
                     formal-count-resources
                     [:count :truncated?])]
                   [:public-client
                    (select-keys
                     actual-count-resources
                     [:count :truncated?])]]}))))
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :count-subjects]
                  :values
                  [[:verified-generated-java
                    (select-keys
                     formal-count-subjects
                     [:count :truncated?])]
                   [:public-client
                    (select-keys
                     actual-count-subjects
                     [:count :truncated?])]]}))))))
    (doseq [resource [document-1 document-2]]
      (let [formal-result
            (formal-evaluate
             relationships
             {:operation :can?
              :subject (formal-object user)
              :permission "view"
              :resource (formal-object resource)})]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label :can? (:id resource)]
                  :values
                  [[:verified-generated-java
                    (:allowed? formal-result)]
                   [:public-cache-enabled
                    (eacl/can? cached user :view resource)]
                   [:public-cache-disabled
                    (eacl/can? uncached user :view resource)]]}))))))))

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
      (assert-public-authorization!
       label cached uncached [relationship-1 relationship-2])
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
              cached user :view document-2))))
      (assert-public-authorization!
       label cached uncached [relationship-2]))))

(deftest generated-cache-and-cursor-state-traces-across-jvm-adapters
  (testing "DataScript"
    (let [conn (datascript/create-conn)
          common
          {:engine-selection engine-selection
           :coherence-authority :managed
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
           :coherence-authority :managed
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
