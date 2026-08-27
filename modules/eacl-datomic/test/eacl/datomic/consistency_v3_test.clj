(ns eacl.datomic.consistency-v3-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.source :as source]
            [eacl.causal-token :as causal-token]
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
   {:security-key security-key
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

(defn- token-data
  [authorization token]
  (causal-token/token-data
   (get-in authorization [:runtime :format-options])
   token))

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

(deftest public-datomic-reads-use-balanced-borrowed-provider-selections-test
  (with-mem-conn [conn schema/v7-schema]
    (let [authorization (client conn)
          _ (seed! conn authorization)
          _ (eacl/create-relationship! authorization relationship)
          operations
          [[:can? #(eacl/can? authorization user :view document)]
           [:read-schema #(eacl/read-schema authorization)]
           [:read-relationships
            #(eacl/read-relationships
              authorization {:subject/type :user :first 10})]
           [:lookup-resources
            #(eacl/lookup-resources
              authorization {:subject user
                             :permission :view
                             :resource/type :document
                             :first 10})]]]
      (doseq [[label operation] operations]
        (testing (name label)
          (let [calls (atom {})]
            (binding [source/*source-op-stats* calls]
              (is (some? (operation))))
            (is (= 1 (:acquire-current! @calls 0)))
            (is (= 1 (:release! @calls 0)))))))))

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

(deftest lagging-real-peer-exact-selection-targets-token-basis-test
  (with-mem-conns [writer-conn reader-conn schema/v7-schema]
    (let [client-opts {:security-key security-key
                       :source-lifecycle source-lifecycle
                       :consistency-sync-timeout-ms 5000}
          writer (datomic/make-client writer-conn client-opts)
          reader (datomic/make-client reader-conn client-opts)
          _ (seed! writer-conn writer)
          stale-reader-db @(d/sync reader-conn)
          token (:zed/token
                 (eacl/create-relationship! writer relationship))
          requested-t (:exact-locator (token-data reader token))
          original-db d/db
          original-sync d/sync
          sync-calls (atom [])]
      (is (< (d/basis-t stale-reader-db) requested-t)
          "the controlled reader snapshot genuinely predates the writer token")
      (with-redefs [d/db
                    (fn [connection]
                      (if (identical? connection reader-conn)
                        stale-reader-db
                        (original-db connection)))
                    d/sync
                    (fn
                      ([connection]
                       (original-sync connection))
                      ([connection basis]
                       (swap! sync-calls conj [connection basis])
                       (original-sync connection basis)))]
        (is (true?
             (eacl/can?
              reader user :view document
              (consistency/at-exact-snapshot token))))
        (is (= [[reader-conn requested-t]] @sync-calls)
            "real Datomic targeted sync catches the lagging Peer up exactly to T")))))

(deftest lagging-real-peer-resumes-historical-cursor-test
  (with-mem-conns [writer-conn reader-conn schema/v7-schema]
    (let [client-opts {:security-key security-key
                       :source-lifecycle source-lifecycle
                       :consistency-sync-timeout-ms 5000}
          writer (datomic/make-client writer-conn client-opts)
          reader (datomic/make-client reader-conn client-opts)
          second-document (eacl/spice-object :document "document-2")
          _ (seed! writer-conn writer)
          _ @(d/transact writer-conn [{:eacl/id "document-2"}])
          stale-reader-db @(d/sync reader-conn)
          _ (eacl/create-relationships!
             writer
             [relationship
              (eacl/->Relationship user :reader second-document)])
          query {:subject user
                 :permission :view
                 :resource/type :document
                 :first 1}
          first-page (eacl/lookup-resources writer query)
          cursor (get-in first-page [:page-info :end-cursor])
          expected-page (eacl/lookup-resources writer (assoc query :after cursor))
          original-db d/db
          original-sync d/sync
          sync-calls (atom [])]
      (is (some? cursor))
      (with-redefs [d/db
                    (fn [connection]
                      (if (identical? connection reader-conn)
                        stale-reader-db
                        (original-db connection)))
                    d/sync
                    (fn
                      ([connection]
                       (original-sync connection))
                      ([connection basis]
                       (swap! sync-calls conj [connection basis])
                       (original-sync connection basis)))]
        (is (= (:data expected-page)
               (:data
                (eacl/lookup-resources reader (assoc query :after cursor))))
            "a lagging Peer deterministically replays the cursor at its historical basis")
        (is (= 1 (count @sync-calls)))
        (is (identical? reader-conn (ffirst @sync-calls)))
        (is (< (d/basis-t stale-reader-db) (second (first @sync-calls)))
            "cursor replay catches the lagging Peer up to the authenticated basis")))))

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
            ;; The provider receives the remaining duration from one original
            ;; deadline; authentication and setup may consume part of the
            ;; configured five milliseconds before Datomic starts waiting.
            (is (<= 1 (:timeout-ms data) 5))))))))

(deftest authenticated-cache-lifts-only-across-equal-proofs-test
  (with-mem-conn [conn schema/v7-schema]
    (let [authorization
          (datomic/make-client
           conn
           {:security-key security-key
            :cache {}})
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
          cursor (get-in page-1 [:page-info :end-cursor])]
      @(d/transact
        conn
        [{:db/id (d/tempid :db.part/user)
          :db/doc "unrelated cursor churn"}])
      (testing "an unrelated write leaves the dependency proof equal: the
                continuation is reused on the current basis without recovery"
        (let [page-2
              (eacl/lookup-resources
               authorization
               (assoc query :after cursor))]
          (is (= ["doc-b"] (mapv :id (:data page-2))))
          (is (nil? (get-in page-2 [:page-info :cursor-recovery])))))
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
