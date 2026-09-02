(ns eacl.datomic.v8-characterization-test
  "Public behavior that must remain stable while the v8 engine is extracted
  from the Datomic adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.core :as eacl]
            [eacl.contract-support :as contract]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]))

(def ^:private recursive-schema
  "definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     permission read = reader + parent->read
   }")

(defn- user [id]
  (eacl/spice-object :user id))

(defn- folder [id]
  (eacl/spice-object :folder id))

(defn- page-ids [page]
  (mapv :id (:data page)))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- seed-recursive! [conn client]
  (eacl/write-schema! client recursive-schema)
  @(d/transact conn
               (mapv (fn [id] {:eacl/id id})
                     ["user-1" "folder-0" "folder-1" "folder-2" "folder-3"]))
  (eacl/create-relationships!
   client
   [(eacl/->Relationship (user "user-1") :reader (folder "folder-0"))
    (eacl/->Relationship (folder "folder-0") :parent (folder "folder-1"))
    (eacl/->Relationship (folder "folder-1") :parent (folder "folder-2"))
    (eacl/->Relationship (folder "folder-2") :parent (folder "folder-3"))]))

(deftest extraction-preserves-public-v8-behavior-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "v8-extraction-characterization00"
          client (core/make-client
                  conn
                  {:security-key token-key
                   :cache {}})
          uncached-client (core/make-client
                           conn
                           {:security-key token-key
                            :cache shared-cache/no-cache})
          query {:subject (user "user-1")
                 :permission :read
                 :resource/type :folder}]
      (seed-recursive! conn client)

      (testing "recursive checks, Relay pages, counts, and reverse traversal"
        (is (true? (eacl/can? client
                              (user "user-1")
                              :read
                              (folder "folder-3"))))
        (let [page-1 (eacl/lookup-resources client (assoc query :first 2))
              page-2 (eacl/lookup-resources
                      client
                      (assoc query
                             :first 2
                             :after (get-in page-1 [:page-info :end-cursor])))
              previous (eacl/lookup-resources
                        client
                        (assoc query
                               :last 2
                               :before (get-in page-2 [:page-info :start-cursor])))]
          (is (= ["folder-0" "folder-1"] (page-ids page-1)))
          (is (= ["folder-2" "folder-3"] (page-ids page-2)))
          (is (= (page-ids page-1) (page-ids previous)))
          (is (true? (get-in page-1 [:page-info :has-next-page?])))
          (is (true? (get-in page-2 [:page-info :has-previous-page?]))))
        (is (= 4 (:count (eacl/count-resources client query))))
        (is (= ["user-1"]
               (page-ids
                (eacl/lookup-subjects
                 client
                 {:resource (folder "folder-3")
                  :permission :read
                  :subject/type :user
                  :first 1}))))
        (is (= 1
               (:count
                (eacl/count-subjects
                 client
                 {:resource (folder "folder-3")
                  :permission :read
                  :subject/type :user})))))

      (testing "cache provenance and uncached parity"
        (let [cache-query (assoc query :first 3)
              miss (eacl/lookup-resources client cache-query)
              hit (eacl/lookup-resources client cache-query)
              uncached (eacl/lookup-resources uncached-client cache-query)]
          (is (= (page-ids miss) (page-ids hit) (page-ids uncached)))
          (is (false? (:cached? miss)))
          (is (true? (:cached? hit)))
          (is (false? (:cached? uncached)))))

      (testing "consistency selection"
        (let [exact (consistency/at-exact-snapshot
                     (core/current-zed-token client))]
          (is (= ["folder-0" "folder-1" "folder-2" "folder-3"]
                 (page-ids
                  (eacl/lookup-resources
                   client
                   (assoc query :first 10 :consistency exact)))))
          (is (= ["folder-0" "folder-1" "folder-2" "folder-3"]
                 (page-ids
                  (eacl/lookup-resources
                   client
                   (assoc query
                          :first 10
                          :consistency consistency/minimize-latency)))))))

      (testing "filter and cursor validation remains typed"
        (is (= :eacl.pagination/unsupported-filter
               (:eacl/error
                (error-data
                 #(eacl/lookup-subjects
                   client
                   {:resource (folder "folder-3")
                    :permission :read
                    :subject/type :user
                    :subject/relation :member
                    :first 2})))))
        (let [data (error-data
                    #(eacl/lookup-resources
                      client
                      (assoc query :first 2 :after "not-a-page-token")))]
          (is (= :eacl.pagination/invalid-cursor
                 (or (:type data) (:eacl/error data))))))

      (testing "object deletion invalidates authorization and cached results"
        (is (pos? (:retracted-datoms
                   (eacl/delete-object! client (folder "folder-3")))))
        (is (false? (eacl/can? client
                               (user "user-1")
                               :read
                               (folder "folder-3"))))
        (is (= 3 (:count (eacl/count-resources client query))))))))

(deftest public-api-arity-characterization-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:security-key "v8-public-api-arity-characterization"})]
      (eacl/write-schema! client contract/smoke-schema)
      @(d/transact conn
                   (mapv (fn [{:keys [id]}] {:eacl/id id})
                         contract/smoke-objects))
      (eacl/create-relationships! client contract/smoke-relationships)
      (contract/assert-public-api-arity-contract! client))))
