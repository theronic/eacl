(ns eacl.datahike.consistency-v3-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.spicedb.consistency :as consistency])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Date]))

(def schema
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(def security-key "01234567890123456789012345678901")
(def user (eacl/spice-object :user "user"))
(def document (eacl/spice-object :document "document"))
(def relationship
  (eacl/->Relationship user :reader document))

(defn- client
  [conn]
  (datahike/make-client
   conn
   {:coherence-authority :managed
    :security-key security-key
    :consistency-sync-timeout-ms 5}))

(defn- seed!
  [conn authorization]
  (eacl/write-schema! authorization schema)
  (d/transact conn [{:eacl/id "user"}
                    {:eacl/id "document"}]))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest map-can-rejects-malformed-consistency-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
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
    (is (false? (:consistency error)))))

(deftest explicit-cache-expiry-installs-a-fresh-lifecycle-test
  (let [conn (datahike/create-conn)
        authorization (client conn)]
    (seed! conn authorization)
    (eacl/create-relationship! authorization relationship)
    (is (true? (eacl/can? authorization user :view document)))
    (is (true? (eacl/can? authorization user :view document)))
    (let [before (datahike/cache-stats authorization)]
      (is (pos? (:exact-hits before)))
      (datahike/expire-cache! authorization)
      (is (true? (eacl/can? authorization user :view document)))
      (let [after (datahike/cache-stats authorization)]
        (is (= (inc (:expirations before)) (:expirations after)))
        (is (= (inc (:misses before)) (:misses after)))
        (is (= (:exact-hits before) (:exact-hits after)))))))

(deftest schema-no-op-keeps-completed-cache-hot-test
  (let [conn (datahike/create-conn)
        authorization (client conn)]
    (seed! conn authorization)
    (eacl/create-relationship! authorization relationship)
    (is (true? (eacl/can? authorization user :view document)))
    (is (true? (eacl/can? authorization user :view document)))
    (let [before (datahike/cache-stats authorization)]
      (eacl/write-schema! authorization schema)
      (is (true? (eacl/can? authorization user :view document)))
      (let [after (datahike/cache-stats authorization)]
        (is (= (:expirations before) (:expirations after)))
        (is (= (:misses before) (:misses after)))
        (is (= (inc (:exact-hits before)) (:exact-hits after)))))))

(deftest current-cursor-pages-use-completed-cache-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
        document-ids ["doc-a" "doc-b" "doc-c"]
        documents (mapv #(eacl/spice-object :document %) document-ids)
        relationships
        (mapv #(eacl/->Relationship user :reader %) documents)
        _ (eacl/write-schema! authorization schema)
        _ (d/transact conn
                      (mapv (fn [id] {:eacl/id id})
                            (into ["user"] document-ids)))
        _ (eacl/create-relationships! authorization relationships)
        query {:subject user
               :permission :view
               :resource/type :document
               :first 1}
        page-1 (eacl/lookup-resources authorization query)
        page-2-query
        (assoc query :after (get-in page-1 [:page-info :end-cursor]))
        page-2 (eacl/lookup-resources authorization page-2-query)
        page-2-hit (eacl/lookup-resources authorization page-2-query)
        previous-query
        (-> query
            (dissoc :first)
            (assoc :last 1
                   :before (get-in page-2 [:page-info :start-cursor])))
        previous-page
        (eacl/lookup-resources authorization previous-query)
        previous-hit
        (eacl/lookup-resources authorization previous-query)]
    (testing "adjacent reverse navigation reuses the visited current page"
      (is (= [(second documents)] (:data page-2)))
      (is (false? (:cached? page-2)))
      (is (true? (:cached? page-2-hit)))
      (is (= [(first documents)] (:data previous-page)))
      (is (true? (:cached? previous-page)))
      (is (true? (:cached? previous-hit))))
    (testing "current recovery becomes cacheable after re-evaluation"
      (eacl/delete-relationship! authorization (second relationships))
      (let [before (datahike/cache-stats authorization)
            historical-1
            (eacl/lookup-resources authorization page-2-query)
            historical-2
            (eacl/lookup-resources authorization page-2-query)
            after (datahike/cache-stats authorization)]
        (is (= [(last documents)] (:data historical-1)))
        (is (= (:data historical-1) (:data historical-2)))
        (is (= :rebased
               (get-in historical-1 [:page-info :cursor-recovery])))
        (is (false? (:cached? historical-1)))
        (is (true? (:cached? historical-2)))
        (is (= (:bypasses before) (:bypasses after)))))))

(deftest repeated-relationship-page-uses-client-private-navigation-cache-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
        _ (seed! conn authorization)
        document-2 (eacl/spice-object :document "document-2")
        relationship-2 (eacl/->Relationship user :reader document-2)
        _ (d/transact conn [{:eacl/id "document-2"}])
        _ (eacl/create-relationships!
           authorization [relationship relationship-2])
        query {:subject/type :user
               :subject/id "user"
               :resource/type :document
               :resource/relation :reader
               :first 1}
        first-page (eacl/read-relationships authorization query)
        next-query
        (assoc query :after (get-in first-page [:page-info :end-cursor]))
        second-page (eacl/read-relationships authorization next-query)
        repeated-first-page
        (eacl/read-relationships authorization query)
        repeated-second-page
        (eacl/read-relationships authorization next-query)
        bypassed-page
        (eacl/read-relationships authorization (assoc query :cache? false))]
    (is (= #{relationship relationship-2}
           (set (concat (:data first-page) (:data second-page)))))
    (is (false? (:cached? first-page)))
    (is (false? (:cached? second-page)))
    (is (= (:data first-page) (:data repeated-first-page)))
    (is (= (:data second-page) (:data repeated-second-page)))
    (is (true? (:cached? repeated-first-page)))
    (is (true? (:cached? repeated-second-page)))
    (is (= (:data first-page) (:data bypassed-page)))
    (is (false? (:cached? bypassed-page)))))

(deftest per-request-cache-bypass-covers-public-read-shapes-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
        _ (seed! conn authorization)
        _ (eacl/create-relationship! authorization relationship)
        before (datahike/cache-stats authorization)
        resources {:subject user
                   :permission :view
                   :resource/type :document
                   :first 10
                   :cache? false}
        subjects {:resource document
                  :permission :view
                  :subject/type :user
                  :first 10
                  :cache? false}
        resource-count (dissoc resources :first)
        subject-count (dissoc subjects :first)]
    (with-redefs [cache/resolve-current!
                  (fn [& _]
                    (throw
                     (ex-info "cache resolution must be unreachable" {})))]
      (dotimes [_ 2]
        (is (true?
             (eacl/can? authorization
                        {:subject user
                         :permission :view
                         :resource document
                         :cache? false})))
        (is (false? (:cached?
                     (eacl/lookup-resources authorization resources))))
        (is (false? (:cached?
                     (eacl/lookup-subjects authorization subjects))))
        (is (false? (:cached?
                     (eacl/count-resources authorization resource-count))))
        (is (false? (:cached?
                     (eacl/count-subjects authorization subject-count))))))
    (is (= 1
           (count
            (:data
             (eacl/read-relationships
              authorization
              {:resource/type :document
               :first 10
               :cache? false})))))
    (is (= :eacl/invalid-request
           (:type
            (error-data
             #(eacl/can? authorization
                         {:subject user
                          :permission :view
                          :resource document
                          :cache? :invalid})))))
    (let [after (datahike/cache-stats authorization)]
      (is (= (+ 10 (:bypasses before)) (:bypasses after)))
      (doseq [metric [:exact-hits :managed-hits :misses :puts]]
        (is (= (metric before) (metric after))
            (str metric " must not change during request bypass"))))))

(deftest branch-refresh-exact-and-force-rewind-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
        _ (seed! conn authorization)
        pre-write (d/db conn)
        token
        (:zed/token
         (eacl/create-relationship! authorization relationship))]
    (is (true?
         (eacl/can?
          authorization user :view document
          (consistency/at-least-as-fresh token))))
    (eacl/delete-relationship! authorization relationship)
    (testing "retained commit reconstruction recovers the exact graph"
      (let [before (datahike/cache-stats authorization)]
        (is (true?
             (eacl/can?
              authorization user :view document
              (consistency/at-exact-snapshot token))))
        (is (true?
             (eacl/can?
              authorization user :view document
              (consistency/at-exact-snapshot token))))
        (let [after (datahike/cache-stats authorization)]
          (is (= (+ 2 (:bypasses before))
                 (:bypasses after)))
          (is (= (:exact-hits before)
                 (:exact-hits after))
              "exact requests never consult the completed-answer cache"))))
    (testing "force-moving the branch to a predecessor cannot pass by max-tx"
      (d/force-branch!
       pre-write
       :db
       #{(get-in pre-write [:meta :datahike/commit-id])})
      ;; force-branch! deliberately makes existing writer connections stale.
      ;; Reconnect before advancing the replacement history.
      (d/release conn)
      (let [rewound-conn (d/connect (:config pre-write))
            rewound-authorization (client rewound-conn)]
        (d/transact rewound-conn [{:eacl/id "unrelated"}])
        (is (= :eacl.consistency/freshness-unavailable
               (:type
                (error-data
                 #(eacl/can?
                   rewound-authorization
                   user
                   :view
                   document
                   (consistency/at-least-as-fresh token))))))))))

(deftest configuration-specific-head-and-history-capabilities-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
        adapter
        (datahike-backend/snapshot-adapter
         (d/db conn)
         (:opts authorization))]
    (is (backend/supports?
         adapter :consistency :fully-consistent))
    (is (backend/supports?
         adapter :consistency :at-exact-snapshot))
    (let [streaming-db
          (assoc-in (d/db conn)
                    [:config :writer]
                    {:backend :stream})]
      (is (not
           (backend/supports?
            (datahike-backend/snapshot-adapter
             streaming-db (:opts authorization))
            :consistency :fully-consistent)))))
  (let [conn
        (datahike/create-conn
         nil
         {:commit-graph? false
          :keep-history? false})
        authorization (client conn)
        adapter
        (datahike-backend/snapshot-adapter
         (d/db conn)
         (:opts authorization))]
    (is (not
         (backend/supports?
          adapter :consistency :at-exact-snapshot)))))

(deftest low-level-db-entry-point-bypasses-completed-cache-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
        _ (seed! conn authorization)
        _ (eacl/create-relationship! authorization relationship)
        before (datahike/cache-stats authorization)]
    (is (true?
         (datahike/datahike-can?
          (d/db conn) (:opts authorization)
          user :view document consistency/fully-consistent)))
    (is (true?
         (datahike/datahike-can?
          (d/db conn) (:opts authorization)
          user :view document consistency/fully-consistent)))
    (let [after (datahike/cache-stats authorization)]
      (is (= (+ 2 (:bypasses before))
             (:bypasses after)))
      (is (= (:exact-hits before)
             (:exact-hits after))))))

(deftest reader-branch-and-merge-metadata-test
  (let [writer-conn (datahike/create-conn)
        config (:config (d/db writer-conn))
        reader-conn (d/connect config)
        writer (client writer-conn)
        reader (client reader-conn)
        _ (seed! writer-conn writer)
        token
        (:zed/token
         (eacl/create-relationship! writer relationship))]
    (testing "another direct-store connection refreshes to the token anchor"
      (is (true?
           (eacl/can?
            reader user :view document
            (consistency/at-least-as-fresh token)))))
    (d/branch! writer-conn :db :feature)
    (let [feature-conn
          (d/connect (assoc config :branch :feature))
          feature (client feature-conn)
          feature-commit
          (str
           (get-in (d/db feature-conn)
                   [:meta :datahike/commit-id]))]
      (testing "branch scope is authenticated even when the initial commit is shared"
        (is (= :eacl.consistency/incomparable-scope
               (:type
                (error-data
                 #(eacl/can?
                   feature user :view document
                   (consistency/at-least-as-fresh token)))))))
      (d/merge-db
       writer-conn
       #{:feature}
       [{:db/id -1 :db/doc "unrelated merge metadata"}])
      (let [adapter
            (datahike-backend/snapshot-adapter
             (d/db writer-conn)
             (:opts writer))]
        (is (= [feature-commit]
               (:parent-commit-ids (backend/state adapter)))))
      (d/release feature-conn))
    (d/release reader-conn)
    (d/release writer-conn)))

(deftest content-proofs-are-bounded-and-cover-public-identity-test
  (let [conn (datahike/create-conn)
        authorization
        (datahike/make-client
         conn
         {:coherence-authority :managed
          :proof-mode :content
          :security-key security-key})
        _ (seed! conn authorization)
        _ (eacl/create-relationship! authorization relationship)
        before-adapter
        (datahike-backend/snapshot-adapter
         (d/db conn) (:opts authorization))
        relation-id
        (:relation-id
         (first
          (backend/invoke
           before-adapter :relation-defs :document :reader)))
        schema-proof (backend/invoke before-adapter :schema-proof)
        before-proof
        (backend/invoke before-adapter :relation-proof [relation-id])
        user-eid (ddb/entid (d/db conn) [:eacl/id "user"])
        document-eid
        (ddb/entid (d/db conn) [:eacl/id "document"])]
    (is (= #{:content-digest} (set (keys schema-proof))))
    (is (= 43 (count (:content-digest schema-proof))))
    (is (= #{:content-digest} (set (keys before-proof))))
    (is (= 43 (count (:content-digest before-proof))))
    (let [reverse
          (first
           (ddb/eavt-datoms
            (d/db conn)
            document-eid
            relationship-storage/reverse-attribute))]
      (d/transact
       conn
       [[:db/retract
         document-eid
         relationship-storage/reverse-attribute
         (vec (:v reverse))]])
      (let [half-proof
            (backend/invoke
             (datahike-backend/snapshot-adapter
              (d/db conn) (:opts authorization))
             :relation-proof
             [relation-id])]
        (is (not= before-proof half-proof)
            "a missing physical half invalidates a content-proof cache hit"))
      (eacl/write-relationship!
       authorization
       {:operation :touch
        :subject user
        :relation :reader
        :resource document})
      (is (= before-proof
             (backend/invoke
              (datahike-backend/snapshot-adapter
               (d/db conn) (:opts authorization))
              :relation-proof
              [relation-id]))
          "repairing the pair restores the same content proof"))
    (d/transact conn [[:db/retract user-eid :eacl/id "user"]
                      [:db/add user-eid :eacl/id "renamed-user"]])
    (let [after-adapter
          (datahike-backend/snapshot-adapter
           (d/db conn) (:opts authorization))
          after-proof
          (backend/invoke after-adapter :relation-proof [relation-id])]
      (is (not= before-proof after-proof)))
    (d/release conn)))

(deftest temporal-fallback-and-commit-garbage-collection-test
  (testing "history can reconstruct exact state when the commit graph is off"
    (let [conn
          (datahike/create-conn
           nil
           {:commit-graph? false
            :keep-history? true})
          authorization (client conn)
          _ (seed! conn authorization)
          token
          (:zed/token
           (eacl/create-relationship! authorization relationship))]
      (eacl/delete-relationship! authorization relationship)
      (is (true?
           (eacl/can?
            authorization user :view document
            (consistency/at-exact-snapshot token))))
      (d/release conn)))
  (testing "collected commit history expires exact, not causal anchor membership"
    ;; Datahike 0.8.1759's in-memory konserve backend cannot mark its
    ;; persistent-set roots (`:flush-before-marking`). Exercise the public GC
    ;; contract on the file backend, where the persisted roots are flushable.
    (let [temp-dir
          (Files/createTempDirectory
           "eacl-datahike-gc-"
           (make-array FileAttribute 0))
          conn
          (datahike/create-conn
           nil
           {:store {:backend :file
                    :path (str temp-dir "/db")}})
          config (:config (d/db conn))
          authorization (client conn)
          _ (seed! conn authorization)
          token
          (:zed/token
           (eacl/create-relationship! authorization relationship))]
      (try
        (eacl/delete-relationship! authorization relationship)
        (is (true?
             (eacl/can?
              authorization user :view document
              (consistency/at-exact-snapshot token))))
        @(d/gc-storage conn (Date. (+ 1000 (System/currentTimeMillis))))
        (is (= :eacl.consistency/exact-snapshot-unavailable
               (:type
                (error-data
                 #(eacl/can?
                   authorization user :view document
                   (consistency/at-exact-snapshot token))))))
        (is (false?
             (eacl/can?
              authorization user :view document
              (consistency/at-least-as-fresh token))))
        (finally
          (d/release conn)
          (d/delete-database config))))))

(deftest relationship-cursor-current-recovery-test
  (let [conn (datahike/create-conn)
        authorization (client conn)
        _ (eacl/write-schema! authorization schema)
        _ (d/transact conn [{:eacl/id "user"}
                            {:eacl/id "doc-a"}
                            {:eacl/id "doc-b"}
                            {:eacl/id "doc-c"}])
        documents
        (mapv #(eacl/spice-object :document %)
              ["doc-a" "doc-b" "doc-c"])
        relationships
        (mapv #(eacl/->Relationship user :reader %) documents)
        _ (doseq [value relationships]
            (eacl/create-relationship! authorization value))
        query {:subject/id "user" :first 1}
        page-1 (eacl/read-relationships authorization query)
        cursor (get-in page-1 [:page-info :end-cursor])
        cursor-data
        (datahike/token->cursor cursor (:opts authorization))]
    (try
      (d/transact conn [{:eacl/id "unrelated-cursor-churn"}])
      (testing "an unrelated write rebases continuation to the current commit"
        (let [page-2
              (eacl/read-relationships
               authorization
               (assoc query :after cursor))
              rebased
              (datahike/token->cursor
               (get-in page-2 [:page-info :end-cursor])
               (:opts authorization))]
          (is (= [(second relationships)] (:data page-2)))
          (is (= :rebased
                 (get-in page-2 [:page-info :cursor-recovery])))
          (is (not= (get-in cursor-data [:graph-head :exact-locator])
                    (get-in rebased [:graph-head :exact-locator])))))
      (let [fresh-page-1
            (eacl/read-relationships authorization query)
            fresh-cursor
            (get-in fresh-page-1 [:page-info :end-cursor])
            delete-token
            (:zed/token
             (eacl/delete-relationship!
              authorization
              (second relationships)))]
        (testing "a relationship change resumes on the current commit"
          (let [page
                (eacl/read-relationships
                 authorization
                 (assoc query :after fresh-cursor))]
            (is (= [(last relationships)] (:data page)))
            (is (= :rebased
                   (get-in page [:page-info :cursor-recovery])))))
        (testing "a changed consistency contract is a different query scope"
          (is (= :eacl.pagination/invalid-cursor
                 (:type
                  (error-data
                   #(eacl/read-relationships
                     authorization
                     (assoc
                      query
                      :after fresh-cursor
                      :consistency
                      (consistency/at-least-as-fresh
                       delete-token)))))))))
      (finally
        (d/release conn)))))
