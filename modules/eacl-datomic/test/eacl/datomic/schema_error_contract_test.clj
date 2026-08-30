(ns eacl.datomic.schema-error-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.backend.source :as source]
            [eacl.core :as eacl]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]))

(def ^:private permission-schema
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(def ^:private evolved-schema
  "definition user {}
   definition document {
     relation editor: user
     permission edit = editor
   }")

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- assert-error!
  [expected-type expected-operation f]
  (let [data (error-data f)]
    (is (= expected-type (:type data)) data)
    (is (= expected-type (:eacl/error data)) data)
    (is (= expected-operation (:operation data)) data)
    data))

(defn- client!
  [conn]
  (let [client (core/make-client conn {:cache shared-cache/no-cache})]
    (eacl/write-schema! client permission-schema)
    @(d/transact conn [{:eacl/id "u1"}
                       {:eacl/id "d1"}])
    client))

(deftest relationship-contention-reacquires-replans-and-releases-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (client! conn)
          user (eacl/spice-object :user "u1")
          document (eacl/spice-object :document "d1")
          native-transact d/transact
          submissions (atom 0)
          source-ops (atom {})
          response
          (with-redefs
           [d/transact
            (fn [candidate tx-data]
              (if (= 1 (swap! submissions inc))
                (throw
                 (ex-info "injected Datomic CAS loser"
                          {:db/error :db.error/cas-failed}))
                (native-transact candidate tx-data)))]
            (binding [source/*source-op-stats* source-ops]
              (eacl/create-relationship!
               client user :reader document)))]
      (is (string? (:zed/token response)))
      (is (= 2 @submissions))
      (is (= 2 (:acquire-current! @source-ops 0))
          "every contention attempt selects a fresh planning basis")
      (is (= 2 (:release! @source-ops 0))
          "every failed or successful planning basis is released")
      (is (true? (eacl/can? client user :view document))))))

(deftest public-operations-report-unknown-schema-names-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (client! conn)
          user (eacl/spice-object :user "u1")
          document (eacl/spice-object :document "d1")]
      (testing "unknown permissions never collapse into false, empty, or zero"
        (doseq [[operation call]
                [[:check-permission
                  #(eacl/can? client user :missing document)]
                 [:check-permission
                  #(eacl/check-permission
                    client {:subject user
                            :permission :missing
                            :resource document})]
                 [:lookup-resources
                  #(eacl/lookup-resources
                    client {:subject user
                            :permission :missing
                            :resource/type :document})]
                 [:count-resources
                  #(eacl/count-resources
                    client {:subject user
                            :permission :missing
                            :resource/type :document})]
                 [:lookup-subjects
                  #(eacl/lookup-subjects
                    client {:resource document
                            :permission :missing
                            :subject/type :user})]
                 [:count-subjects
                  #(eacl/count-subjects
                    client {:resource document
                            :permission :missing
                            :subject/type :user})]
                 [:expand-permission-tree
                  #(eacl/expand-permission-tree
                    client {:resource document
                            :permission :missing})]]]
          (let [data (assert-error!
                      :eacl/unknown-relation-or-permission operation call)]
            (is (= :document (:definition data)))
            (is (= :missing (:relation-or-permission data))))))

      (testing "unknown endpoint definitions identify their role"
        (let [data
              (assert-error!
               :eacl/unknown-definition :check-permission
               #(eacl/can? client
                           (eacl/spice-object :ghost "u1")
                           :view document))]
          (is (= :ghost (:definition data)))
          (is (= :subject (:position data))))
        (let [data
              (assert-error!
               :eacl/unknown-definition :lookup-resources
               #(eacl/lookup-resources
                 client {:subject user
                         :permission :view
                         :resource/type :ghost}))]
          (is (= :ghost (:definition data)))
          (is (= :resource (:position data)))))

      (testing "relationship reads share the taxonomy"
        (assert-error!
         :eacl/unknown-definition :read-relationships
         #(eacl/read-relationships client {:resource/type :ghost}))
        (let [data
              (assert-error!
               :eacl/unknown-relation-or-permission :read-relationships
               #(eacl/read-relationships
                 client {:resource/type :document
                         :resource/relation :missing}))]
          (is (= :missing (:relation data)))))

      (testing "missing IDs under valid schema names retain data semantics"
        (let [missing-user (eacl/spice-object :user "absent-user")
              missing-doc (eacl/spice-object :document "absent-doc")]
          (is (false? (eacl/can? client missing-user :view document)))
          (is (empty? (:data
                       (eacl/lookup-resources
                        client {:subject missing-user
                                :permission :view
                                :resource/type :document}))))
          (is (= 0 (:count
                    (eacl/count-resources
                     client {:subject missing-user
                             :permission :view
                             :resource/type :document}))))
          (is (empty? (:data
                       (eacl/lookup-subjects
                        client {:resource missing-doc
                                :permission :view
                                :subject/type :user}))))
          (is (= 0 (:count
                    (eacl/count-subjects
                     client {:resource missing-doc
                             :permission :view
                             :subject/type :user})))))))))

(deftest schema-validation-uses-the-selected-snapshot-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:cache shared-cache/no-cache})
          old-token (:zed/token (eacl/write-schema! client permission-schema))
          user (eacl/spice-object :user "absent-user")
          document (eacl/spice-object :document "absent-document")]
      (eacl/write-schema! client evolved-schema)
      (is (= :eacl/unknown-relation-or-permission
             (:type
              (error-data
               #(eacl/can? client user :view document)))))
      (is (false?
           (eacl/can?
            client user :view document
            (consistency/at-exact-snapshot old-token)))))))

(deftest detailed-check-defaults-to-minimize-latency-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (client! conn)
          demand {:subject (eacl/spice-object :user "u1")
                  :permission :view
                  :resource (eacl/spice-object :document "d1")}
          calls (atom 0)
          sync d/sync]
      (with-redefs [d/sync
                    (fn [& args]
                      (swap! calls inc)
                      (apply sync args))]
        (eacl/check-permission client demand)
        (is (zero? @calls)
            "omitted consistency must use the current Peer DB basis")
        (eacl/check-permission
         client (assoc demand :consistency consistency/fully-consistent))
        (is (= 1 @calls)
            "fully consistent remains available when explicitly requested")))))

(deftest relationship-writes-share-the-schema-taxonomy-test
  ;; Writes validate schema names before any endpoint resolves, with the same
  ;; typed errors the reads use (an unknown relation, definition, or a
  ;; subject type the relation does not declare); a well-typed write may
  ;; still fail on an unknown object.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (client! conn)
          user (eacl/spice-object :user "u1")
          document (eacl/spice-object :document "d1")]
      (let [data (assert-error!
                  :eacl/unknown-relation-or-permission :write-relationships
                  #(eacl/create-relationship! client user :nope document))]
        (is (= :nope (:relation data)))
        (is (= :document (:definition data))))
      (let [data (assert-error!
                  :eacl/unknown-definition :write-relationships
                  #(eacl/create-relationship!
                    client user :reader (eacl/spice-object :ghost "g")))]
        (is (= :ghost (:definition data))))
      (let [data (assert-error!
                  :eacl/unknown-relation-or-permission :write-relationships
                  #(eacl/create-relationship!
                    client (eacl/spice-object :document "d1") :reader document))]
        (is (= :subject-type-not-declared (:reason data)))
        (is (= :document (:subject-type data))))
      (assert-error!
       :eacl/unknown-relation-or-permission :write-relationships
       #(eacl/create-relationship! client user :view document))
      (testing "the typed schema errors also apply to :touch and :delete"
        (assert-error!
         :eacl/unknown-relation-or-permission :write-relationships
         #(eacl/delete-relationship! client user :nope document)))
      (testing "a well-typed write with an unknown object keeps its data error"
        (let [data (error-data
                    #(eacl/create-relationship!
                      client (eacl/spice-object :user "absent") :reader document))]
          (is (= :eacl/unknown-object (:type data)))
          (is (= :eacl/unknown-object (:eacl/error data)))))
      (testing "duplicate :create carries the portable category"
        (eacl/create-relationship! client user :reader document)
        (let [data (error-data #(eacl/create-relationship! client user :reader document))]
          (is (= :eacl/relationship-conflict (:type data)))
          (is (= :eacl/relationship-conflict (:eacl/error data))))))))

(deftest repeated-and-conflicting-relationship-batch-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (client! conn)
          user (eacl/spice-object :user "u1")
          document (eacl/spice-object :document "d1")
          relationship (eacl/->Relationship user :reader document)
          update #(eacl/->RelationshipUpdate % relationship)]
      (doseq [operations
              (for [left [:create :touch :delete]
                    right [:create :touch :delete]
                    :when (not= left right)]
                [left right])]
        (let [data
              (error-data
               #(eacl/write-relationships!
                 client (mapv update operations)))]
          (is (= :eacl/invalid-relationship-update-batch (:type data)))
          (is (= :eacl/invalid-relationship-update-batch
                 (:eacl/error data)))
          (is (= :conflicting-operations (:reason data)))
          (is (= operations (:operations data)))))
      (is (empty? (:data
                   (eacl/read-relationships
                    client {:resource/type :document}))))
      (is (map?
           (eacl/write-relationships!
            client [(update :create) (update :create)])))
      (is (= 1
             (count
              (:data
               (eacl/read-relationships
                client {:resource/type :document})))))
      (is (map?
           (eacl/write-relationships!
            client [(update :touch) (update :touch)])))
      (is (= 1
             (count
              (:data
               (eacl/read-relationships
                client {:resource/type :document})))))
      (is (map?
           (eacl/write-relationships!
            client [(update :delete) (update :delete)])))
      (is (empty? (:data
                   (eacl/read-relationships
                    client {:resource/type :document})))))))

(deftest schema-and-page-request-errors-are-typed-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (client! conn)
          user (eacl/spice-object :user "u1")]
      (testing "reference validation failures carry a category"
        (let [data (error-data
                    #(eacl/write-schema! client "definition user {}
                                                 definition document {
                                                   relation reader: user
                                                   permission view = missing
                                                 }"))]
          (is (= :eacl.schema/expression-resolution-failed (:type data)))
          (is (= :eacl.schema/expression-resolution-failed
                 (:eacl/error data)))
          (is (some #(= :missing-reference (:type %))
                    (:errors data)))))
      (testing "an undefined relation subject type is rejected like SpiceDB does"
        (let [data (error-data
                    #(eacl/write-schema! client "definition user {}
                                                 definition document {
                                                   relation reader: nobody
                                                   permission view = reader
                                                 }"))]
          (is (= :eacl.schema/expression-resolution-failed (:type data)))
          (is (some #(= :type-invalid-reference (:type %))
                    (:errors data)))))
      (testing "unsupported schema features carry a category"
        (let [data (error-data
                    #(eacl/write-schema! client "definition user {}
                                                 definition document {
                                                   relation reader: user:*
                                                   permission view = reader
                                                 }"))]
          (is (= :eacl.schema/unsupported-feature (:type data)))
          (is (= :eacl.schema/unsupported-feature (:eacl/error data)))))
      (testing "page-size and page-shape errors carry a category"
        (let [data (error-data
                    #(eacl/lookup-resources client {:subject user :permission :view
                                                    :resource/type :document :first 10001}))]
          (is (= :eacl.pagination/invalid-page-size (:type data)))
          (is (= :eacl.pagination/invalid-page-size (:eacl/error data)))
          (is (= 10001 (:size data))))
        (let [data (error-data
                    #(eacl/lookup-resources client {:subject user :permission :view
                                                    :resource/type :document :first 0}))]
          (is (= :eacl.pagination/invalid-page-size (:eacl/error data))))
        (let [data (error-data
                    #(eacl/lookup-resources client {:subject user :permission :view
                                                    :resource/type :document :first 1 :last 1}))]
          (is (= :eacl.pagination/invalid-page-request (:eacl/error data)))
          (is (= :eacl.pagination/invalid-page-request (:type data))))
        (let [data (error-data
                    #(eacl/count-resources client {:subject user :permission :view
                                                   :resource/type :document :first 1}))]
          (is (= :eacl.pagination/invalid-page-request (:eacl/error data))))))))
