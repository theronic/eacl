(ns eacl.datomic.consistency-v3-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers
             :refer [with-mem-conn with-mem-conns]]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]))

(def authorization-schema
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(def security-key "01234567890123456789012345678901")
(def user (eacl/spice-object :user "user"))
(def document (eacl/spice-object :document "document"))
(def relationship (eacl/->Relationship user :reader document))

(def ^:private source-lifecycle "datomic-consistency-v4-test")

(defn- client
  [conn]
  (datomic/make-client
   conn
   {:coherence-authority :managed
    :zed-token-key security-key
    :source-lifecycle source-lifecycle
    :consistency-sync-timeout-ms 5}))

(defn- seed!
  [conn authorization]
  (eacl/write-schema! authorization authorization-schema)
  @(d/transact conn [{:eacl/id "user"}
                     {:eacl/id "document"}]))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest map-can-rejects-malformed-consistency-test
  (with-mem-conn [conn schema/v7-schema]
    (let [authorization (client conn)
          _ (seed! conn authorization)
          error
          (error-data
           #(eacl/can?
             authorization
             {:subject user
              :permission :view
              :resource document
              :consistency false}))]
      (is (= :eacl/unsupported-consistency (:type error)))
      (is (false? (:consistency error))))))

(deftest minimize-authoritative-and-targeted-sync-arities-test
  (with-mem-conn [conn schema/v7-schema]
    (let [authorization (client conn)
          _ (seed! conn authorization)
          token
          (:zed/token
           (eacl/create-relationship! authorization relationship))
          original-sync d/sync
          calls (atom [])]
      (with-redefs [d/sync
                    (fn
                      ([connection]
                       (swap! calls conj :authoritative)
                       (original-sync connection))
                      ([connection basis]
                       (swap! calls conj [:at-least basis])
                       (original-sync connection basis)))]
        (is (true? (eacl/can? authorization user :view document)))
        (is (empty? @calls)
            "minimize-latency must not synchronize")
        (is (true?
             (eacl/can?
              authorization user :view document
              consistency/fully-consistent)))
        (is (= [:authoritative] @calls)
            "fully-consistent uses the authoritative head barrier")
        (reset! calls [])
        (is (true?
             (eacl/can?
              authorization user :view document
              (consistency/at-least-as-fresh token))))
        (is (= 1 (count @calls)))
        (is (= :at-least (ffirst @calls)))))))

(deftest cross-connection-anchor-and-exact-identity-test
  (with-mem-conns [writer-conn reader-conn schema/v7-schema]
    (let [writer (client writer-conn)
          reader (client reader-conn)
          _ (seed! writer-conn writer)
          token
          (:zed/token
           (eacl/create-relationship! writer relationship))]
      (testing "a second Peer connection waits for the native revision"
        (is (true?
             (eacl/can?
              reader user :view document
              (consistency/at-least-as-fresh token)))))
      (eacl/delete-relationship! writer relationship)
      (testing "as-of reconstruction must match the exact native revision"
        (is (true?
             (eacl/can?
              reader user :view document
              (consistency/at-exact-snapshot token))))
        @(d/sync reader-conn)
        (with-redefs [d/as-of (fn [db _basis] db)]
          (is (= :eacl.consistency/history-divergence
                 (:type
                  (error-data
                   #(eacl/can?
                     reader user :view document
                     (consistency/at-exact-snapshot token)))))))))))

(deftest missing-anchor-and-bounded-wait-test
  (with-mem-conn [conn schema/v7-schema]
    (let [authorization (client conn)
          _ (seed! conn authorization)
          before-write (d/db conn)
          token
          (:zed/token
           (eacl/create-relationship! authorization relationship))]
      (testing "a source that remains numerically behind cannot satisfy freshness"
        (with-redefs [d/sync
                      (fn
                        ([_] (future before-write))
                        ([_ _] (future before-write)))]
          (is (= :eacl.consistency/freshness-unavailable
                 (:type
                  (error-data
                   #(eacl/can?
                     authorization user :view document
                     (consistency/at-least-as-fresh token))))))))
      (testing "a lagging Peer wait is bounded"
        (with-redefs [d/sync
                      (fn
                        ([_] (promise))
                        ([_ _] (promise)))]
          (let [data
                (error-data
                 #(eacl/can?
                   authorization user :view document
                   (consistency/at-least-as-fresh token)))]
            (is (= :eacl.consistency/freshness-unavailable
                   (:type data)))
            (is (= :freshness-timeout (:reason data)))
            (is (= 5 (:timeout-ms data)))))))))

(deftest authenticated-cache-lifts-only-across-equal-proofs-test
  (with-mem-conn [conn schema/v7-schema]
    (let [authorization
          (datomic/make-client
           conn
           {:coherence-authority :managed
            :zed-token-key security-key
            :cache {:remember-answers true}})
          _ (seed! conn authorization)
          _ (eacl/create-relationship! authorization relationship)
          query {:subject user
                 :permission :view
                 :resource/type :document
                 :evaluation :complete-denotation
                 :first 10}
          first-page (eacl/lookup-resources authorization query)
          exact-hit (eacl/lookup-resources authorization query)]
      (is (= ["document"] (mapv :id (:data first-page))))
      (is (false? (:cached? first-page)))
      (is (true? (:cached? exact-hit)))
      @(d/transact
        conn
        [{:db/id (d/tempid :db.part/user)
          :db/doc "unrelated application data"}])
      (testing "an unrelated basis advance lifts the authenticated answer"
        (is (true?
             (:cached?
              (eacl/lookup-resources authorization query)))))
      (eacl/delete-relationship! authorization relationship)
      (let [after-revocation
            (eacl/lookup-resources authorization query)]
        (is (empty? (:data after-revocation)))
        (is (false? (:cached? after-revocation)))))))

(deftest encrypted-cursor-current-recovery-test
  (with-mem-conn [conn schema/v7-schema]
    (let [authorization (client conn)
          _ (eacl/write-schema! authorization authorization-schema)
          _ @(d/transact conn [{:eacl/id "user"}
                               {:eacl/id "doc-a"}
                               {:eacl/id "doc-b"}
                               {:eacl/id "doc-c"}])
          documents
          (mapv #(eacl/spice-object :document %)
                ["doc-a" "doc-b" "doc-c"])
          _ (doseq [resource documents]
              (eacl/create-relationship!
               authorization
               (eacl/->Relationship user :reader resource)))
          query {:subject user
                 :permission :view
                 :resource/type :document
                 :first 1}
          page-1 (eacl/lookup-resources authorization query)
          cursor (get-in page-1 [:page-info :end-cursor])
          cursor-data
          (datomic/token->page-bound (:opts authorization) cursor)
          cursor-basis (:basis-t cursor-data)]
      @(d/transact
        conn
        [{:db/id (d/tempid :db.part/user)
          :db/doc "unrelated cursor churn"}])
      (testing "an unrelated write leaves the dependency proof equal: the
                continuation is reused on the current basis without recovery"
        (let [page-2
              (eacl/lookup-resources
               authorization
               (assoc query :after cursor))
              continued
              (datomic/token->page-bound
               (:opts authorization)
               (get-in page-2 [:page-info :end-cursor]))]
          (is (= ["doc-b"] (mapv :id (:data page-2))))
          (is (nil? (get-in page-2 [:page-info :cursor-recovery])))
          (is (not= cursor-basis (:basis-t continued)))))
      (let [fresh-page-1
            (eacl/lookup-resources authorization query)
            fresh-cursor
            (get-in fresh-page-1 [:page-info :end-cursor])
            delete-token
            (:zed/token
             (eacl/delete-relationship!
              authorization
              (eacl/->Relationship
               user :reader (second documents))))]
        (testing "a relationship change resumes on the exact cursor snapshot"
          (let [page
                (eacl/lookup-resources
                 authorization
                 (assoc query :after fresh-cursor))]
            (is (= ["doc-b"] (mapv :id (:data page))))
            (is (nil? (get-in page [:page-info :cursor-recovery])))))
        (testing "a changed causal floor is a different query scope"
          (is (= :eacl.pagination/invalid-cursor
                 (:type
                  (error-data
                   #(eacl/lookup-resources
                     authorization
                     (assoc
                      query
                      :after fresh-cursor
                      :consistency
                      (consistency/at-least-as-fresh
                       delete-token))))))))))))
