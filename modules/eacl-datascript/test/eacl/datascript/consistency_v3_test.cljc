(ns eacl.datascript.consistency-v3-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.spicedb.consistency :as consistency]))

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

(def ^:private source-lifecycle "datascript-consistency-v4-test")

(defn- reusable-subproblem-hits
  [stats]
  (+ (get-in stats [:subproblems :projection-hits] 0)
     (get-in stats [:subproblems :denotation-hits] 0)))

(defn- managed-client
  [conn options]
  (datascript/make-client
   conn
   (merge {:security-key security-key
           :source-lifecycle source-lifecycle
           :consistency-sync-timeout-ms 5}
          options)))

(defn- seed!
  [conn client]
  (eacl/write-schema! client schema)
  (ds/transact! conn [{:eacl/id "user"}
                      {:eacl/id "document"}]))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
           error
      (ex-data error))))

(deftest map-can-rejects-malformed-consistency-test
  (let [conn (datascript/create-conn)
        client (managed-client conn {})
        _ (seed! conn client)
        error
        (error-data
         #(eacl/can?
           client
           {:subject user
            :permission :view
            :resource document
            :consistency false}))]
    (is (= :eacl/unsupported-consistency (:type error)))
    (is (false? (:consistency error)))))

(deftest immutable-adapter-does-not-claim-authoritative-head-test
  (let [conn (datascript/create-conn)
        authorization (managed-client conn {})
        adapter
        (datascript-backend/snapshot-adapter
         (ds/db conn)
         (dissoc (:opts authorization) :conn))]
    (is (not
         (backend/supports?
          adapter :consistency :fully-consistent)))))

(deftest explicit-cache-expiry-installs-a-fresh-lifecycle-test
  (let [conn (datascript/create-conn)
        client (managed-client conn {})]
    (seed! conn client)
    (eacl/create-relationship! client relationship)
    (is (true? (eacl/can? client user :view document)))
    (is (true? (eacl/can? client user :view document)))
    (let [before (datascript/cache-stats client)]
      (is (pos? (:exact-hits before)))
      (datascript/expire-cache! client)
      (is (true? (eacl/can? client user :view document)))
      (let [after (datascript/cache-stats client)]
        (is (= (inc (:expirations before)) (:expirations after)))
        (is (= (inc (:misses before)) (:misses after)))
        (is (= (:exact-hits before) (:exact-hits after)))))))

(deftest schema-no-op-keeps-completed-cache-hot-test
  (let [conn (datascript/create-conn)
        client (managed-client conn {})]
    (seed! conn client)
    (eacl/create-relationship! client relationship)
    (is (true? (eacl/can? client user :view document)))
    (is (true? (eacl/can? client user :view document)))
    (let [before (datascript/cache-stats client)]
      (eacl/write-schema! client schema)
      (is (true? (eacl/can? client user :view document)))
      (let [after (datascript/cache-stats client)]
        (is (= (:expirations before) (:expirations after)))
        (is (= (:misses before) (:misses after)))
        (is (= (inc (:exact-hits before)) (:exact-hits after)))))))

(deftest current-cursor-pages-use-completed-cache-test
  (let [conn (datascript/create-conn)
        authorization
        (managed-client conn {})
        document-ids ["doc-a" "doc-b" "doc-c"]
        documents (mapv #(eacl/spice-object :document %) document-ids)
        relationships
        (mapv #(eacl/->Relationship user :reader %) documents)
        _ (eacl/write-schema! authorization schema)
        _ (ds/transact! conn
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
      (let [data (error-data
                  #(eacl/lookup-resources authorization page-2-query))]
        (is (= :eacl.pagination/stale-cursor (:type data)))
        (is (= :dependency-proof-changed (:reason data)))))))

(deftest unrelated-write-preserves-authenticated-page-cache-identity-test
  (let [conn (datascript/create-conn)
        authorization
        (managed-client conn {})
        document-ids ["doc-a" "doc-b" "doc-c"]
        documents (mapv #(eacl/spice-object :document %) document-ids)
        _ (eacl/write-schema! authorization schema)
        _ (ds/transact! conn
                        (mapv (fn [id] {:eacl/id id})
                              (into ["user"] document-ids)))
        _ (eacl/create-relationships!
           authorization
           (mapv #(eacl/->Relationship user :reader %) documents))
        query {:subject user
               :permission :view
               :resource/type :document
               :evaluation :complete-denotation
               :first 1}
        original-page-1 (eacl/lookup-resources authorization query)
        original-page-2-query
        (assoc query
               :after
               (get-in original-page-1 [:page-info :end-cursor]))
        original-page-2
        (eacl/lookup-resources authorization original-page-2-query)]
    (is (= [(second documents)] (:data original-page-2)))
    (is (false? (:cached? original-page-2)))
    (ds/transact! conn [{:eacl/id "unrelated-page-cache-write"}])
    (let [recovered-page-2
          (eacl/lookup-resources authorization original-page-2-query)
          fresh-page-1
          (eacl/lookup-resources authorization query)
          fresh-page-2-query
          (assoc query
                 :after
                 (get-in fresh-page-1 [:page-info :end-cursor]))
          fresh-page-2
          (eacl/lookup-resources authorization fresh-page-2-query)]
      (testing "an unrelated write leaves the dependency proof equal: the
                continuation is reused without recovery"
        (is (= [(second documents)] (:data recovered-page-2)))
        (is (nil? (get-in recovered-page-2
                          [:page-info :cursor-recovery])))
        (is (true? (:cached? recovered-page-2))))
      (testing "a newly signed cursor for the same boundary also reuses it"
        (is (not=
             (:after original-page-2-query)
             (:after fresh-page-2-query)))
        (is (= [(second documents)] (:data fresh-page-2)))
        (is (true? (:cached? fresh-page-2)))))))

(deftest repeated-relationship-page-uses-client-private-navigation-cache-test
  (let [conn (datascript/create-conn)
        client (managed-client conn {})
        _ (seed! conn client)
        document-2 (eacl/spice-object :document "document-2")
        relationship-2 (eacl/->Relationship user :reader document-2)
        _ (ds/transact! conn [{:eacl/id "document-2"}])
        _ (eacl/create-relationships!
           client [relationship relationship-2])
        query {:subject/type :user
               :subject/id "user"
               :resource/type :document
               :resource/relation :reader
               :first 1}
        first-page (eacl/read-relationships client query)
        next-query
        (assoc query :after (get-in first-page [:page-info :end-cursor]))
        second-page (eacl/read-relationships client next-query)
        repeated-first-page (eacl/read-relationships client query)
        repeated-second-page (eacl/read-relationships client next-query)
        bypassed-page
        (eacl/read-relationships client (assoc query :cache? false))]
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
  (let [conn (datascript/create-conn)
        client (managed-client conn {})
        _ (seed! conn client)
        _ (eacl/create-relationship! client relationship)
        before (datascript/cache-stats client)
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
             (eacl/can? client
                        {:subject user
                         :permission :view
                         :resource document
                         :cache? false})))
        (is (false? (:cached?
                     (eacl/lookup-resources client resources))))
        (is (false? (:cached?
                     (eacl/lookup-subjects client subjects))))
        (is (false? (:cached?
                     (eacl/count-resources client resource-count))))
        (is (false? (:cached?
                     (eacl/count-subjects client subject-count))))))
    (is (= 1
           (count
            (:data
             (eacl/read-relationships
              client
              {:resource/type :document
               :first 10
               :cache? false})))))
    (is (= :eacl/invalid-request
           (:type
            (error-data
             #(eacl/can? client
                         {:subject user
                          :permission :view
                          :resource document
                          :cache? :invalid})))))
    (let [after (datascript/cache-stats client)]
      (is (= (+ 10 (:bypasses before)) (:bypasses after)))
      (doseq [metric [:exact-hits :managed-hits :misses :puts]]
        (is (= (metric before) (metric after))
            (str metric " must not change during request bypass"))))))

(deftest distinct-permissions-share-exact-relationship-projections-test
  (let [conn (datascript/create-conn)
        client (managed-client conn {})
        shared-schema
        "definition user {}
         definition team {
           relation member: user
           permission access = member
         }
         definition server {
           relation team: team
           permission view = team->access
           permission edit = team->access
         }"
        team (eacl/spice-object :team "shared-team")
        servers
        (mapv #(eacl/spice-object :server (str "shared-server-" %))
              (range 40))
        relationships
        (into [(eacl/->Relationship user :member team)]
              (map #(eacl/->Relationship team :team %) servers))
        query
        (fn [permission]
          {:subject user
           :permission permission
           :resource/type :server
           :evaluation :complete-denotation
           :first 20})
        work (atom {})]
    (eacl/write-schema! client shared-schema)
    (ds/transact!
     conn
     (mapv (fn [object]
             {:eacl/id (:id object)})
           (into [user team] servers)))
    (eacl/create-relationships! client relationships)
    (binding [engine/*backend-work-stats* work]
      (let [view-page (eacl/lookup-resources client (query :view))
            after-view (datascript/cache-stats client)
            edit-page (eacl/lookup-resources client (query :edit))
            after-edit (datascript/cache-stats client)]
        (is (= (:data view-page) (:data edit-page)))
        (is (false? (:cached? view-page)))
        (is (false? (:cached? edit-page)))
        (is (= (:exact-hits after-view) (:exact-hits after-edit))
            "different top-level permission keys cannot hit completed answers")
        (is (> (reusable-subproblem-hits after-edit)
               (reusable-subproblem-hits after-view))
            (str
             "the second permission must reuse a projection or the stronger "
             "complete semantic denotation"))
        (is (pos? (:executed-backend-operations @work))))
      (testing "acyclic point decisions reuse a shared target permission"
        (let [server (first servers)
              _ (is (true? (eacl/can? client
                                      {:subject user
                                       :permission :view
                                       :resource server
                                       :evaluation :complete-denotation})))
              after-view (datascript/cache-stats client)
              _ (is (true? (eacl/can? client
                                      {:subject user
                                       :permission :edit
                                       :resource server
                                       :evaluation :complete-denotation})))
              after-edit (datascript/cache-stats client)]
          (is (= (:exact-hits after-view) (:exact-hits after-edit)))
          (is (> (reusable-subproblem-hits after-edit)
                 (reusable-subproblem-hits after-view))
              (str
               "authority modes may reuse the exact projection or its "
               "complete semantic denotation")))))))

(deftest causal-anchor-survives-restart-and-detects-reset-test
  (let [conn (datascript/create-conn)
        client (managed-client conn {})
        _ (seed! conn client)
        pre-write (ds/db conn)
        token
        (:zed/token
         (eacl/create-relationship! client relationship))]
    (testing "a new client over the same durable family accepts the token"
      (let [restarted (managed-client conn {})]
        (is (true?
             (eacl/can?
              restarted user :view document
              (consistency/at-least-as-fresh token))))))
    (testing "a connection reset requires lifecycle rotation"
      ;; Reset reuses the connection object's source identity, so the operator
      ;; must rotate the lifecycle before accepting work on replacement state.
      (ds/reset-conn! conn pre-write)
      (ds/transact! conn [{:eacl/id "unrelated"}])
      (datascript/expire-cache! client
                                "datascript-consistency-reset-v4-test")
      (is (= :eacl.consistency/incomparable-scope
             (:type
              (error-data
               #(eacl/can?
                 client user :view document
                 (consistency/at-least-as-fresh token)))))))))

(deftest datascript-current-only-rejects-exact-snapshot-test
  (let [conn (datascript/create-conn)
        removed-option-error
        (error-data
         #(managed-client conn {:exact-snapshot-registry-size 2}))
        client (managed-client conn {})
        _ (seed! conn client)
        token
        (:zed/token
         (eacl/create-relationship! client relationship))
        before (datascript/cache-stats client)
        exact-error
        (error-data
         #(eacl/can?
           client user :view document
           (consistency/at-exact-snapshot token)))
        after (datascript/cache-stats client)]
    (is (= :eacl/invalid-config (:type removed-option-error)))
    (is (= [:exact-snapshot-registry-size]
           (:unknown-keys removed-option-error)))
    (is (= :eacl/unsupported-capability (:type exact-error)))
    (is (= :consistency (:capability exact-error)))
    (is (= :at-exact-snapshot (:requested exact-error)))
    (is (= before after)
        "unsupported exact selection must fail before cache access")))

(deftest low-level-db-entry-point-bypasses-completed-cache-test
  (let [conn (datascript/create-conn)
        client (managed-client conn {})
        _ (seed! conn client)
        _ (eacl/create-relationship! client relationship)
        before (datascript/cache-stats client)]
    (is (true?
         (datascript/datascript-can?
          (ds/db conn) (:opts client)
          user :view document consistency/fully-consistent)))
    (is (true?
         (datascript/datascript-can?
          (ds/db conn) (:opts client)
          user :view document consistency/fully-consistent)))
    (let [after (datascript/cache-stats client)]
      (is (= (+ 2 (:bypasses before))
             (:bypasses after)))
      (is (= (:exact-hits before)
             (:exact-hits after))))))

(deftest cloned-connections-are-distinct-sources-and-listener-independent-test
  (let [original-listen! ds/listen!
        conn (datascript/create-conn)
        authorization (managed-client conn {})]
    (with-redefs [ds/listen!
                  (fn [& _]
                    (throw
                     (ex-info
                      "v3 consistency must not install listeners."
                      {:type :unexpected-listener})))]
      (seed! conn authorization)
      (let [pre-token-db (ds/db conn)
            token
            (:zed/token
             (eacl/create-relationship! authorization relationship))
            post-token-db (ds/db conn)
            post-token-client
            (managed-client (ds/conn-from-db post-token-db) {})
            pre-token-client
            (managed-client (ds/conn-from-db pre-token-db) {})]
        (is (= :eacl.consistency/incomparable-scope
               (:type
                (error-data
                 #(eacl/can?
                   post-token-client user :view document
                   (consistency/at-least-as-fresh token))))))
        (is (= :eacl.consistency/incomparable-scope
               (:type
                (error-data
                 #(eacl/can?
                   pre-token-client user :view document
                   (consistency/at-least-as-fresh token))))))))
    ;; Keep the binding referenced in both runtimes so advanced compilation
    ;; cannot consider the interop var unused.
    (is (fn? original-listen!))))

(deftest independent-same-base-divergence-test
  (let [seed-conn (datascript/create-conn)
        seed-client (managed-client seed-conn {})
        _ (seed! seed-conn seed-client)
        base (ds/db seed-conn)
        left-conn (ds/conn-from-db base)
        right-conn (ds/conn-from-db base)
        left (managed-client left-conn {})
        right (managed-client right-conn {})
        token
        (:zed/token
         (eacl/create-relationship! left relationship))]
    ;; Both branches advance once from the same base, giving them the same
    ;; numeric max-tx while only the left contains the token anchor.
    (ds/transact! right-conn [{:eacl/id "unrelated"}])
    (is (= (:max-tx (ds/db left-conn))
           (:max-tx (ds/db right-conn))))
    (is (= :eacl.consistency/incomparable-scope
           (:type
            (error-data
             #(eacl/can?
               right user :view document
               (consistency/at-least-as-fresh token))))))))

(deftest current-cache-lifting-and-exact-rejection-test
  (let [conn (datascript/create-conn)
        authorization
        (managed-client
         conn
         {:cache {}})
        _ (seed! conn authorization)
        token
        (:zed/token
         (eacl/create-relationship! authorization relationship))
        query {:subject user
               :permission :view
               :resource/type :document
               :evaluation :complete-denotation
               :first 10}
        first-page (eacl/lookup-resources authorization query)
        exact-hit (eacl/lookup-resources authorization query)]
    (is (false? (:cached? first-page)))
    (is (true? (:cached? exact-hit)))
    (ds/transact! conn [{:eacl/id "unrelated"}])
    (is (true?
         (:cached?
          (eacl/lookup-resources authorization query))))
    (eacl/delete-relationship! authorization relationship)
    (is (= :eacl/unsupported-capability
           (:type
            (error-data
             #(eacl/can?
               authorization user :view document
               (consistency/at-exact-snapshot token))))))))

(deftest ordered-generations-track-only-supported-mutations-test
  (let [conn (datascript/create-conn)
        authorization
        (managed-client conn {})
        _ (seed! conn authorization)
        _ (eacl/create-relationship! authorization relationship)
        before-adapter
        (datascript-backend/snapshot-adapter
         (ds/db conn) (:opts authorization))
        relation-id
        (:relation-id
         (first
          (backend/invoke
           before-adapter :relation-defs :document :reader)))
        before-proof
        (backend/invoke before-adapter :proof-frame [relation-id])
        document-eid (ds/entid (ds/db conn) [:eacl/id "document"])
        reverse-datom
        (first
         (ds/datoms
          (ds/db conn) :eavt document-eid
          relationship-storage/reverse-attribute))]
    (is (integer? (:schema-stamp before-proof)))
    (is (= relation-id (ffirst (:relation-stamps before-proof))))
    (testing "unsupported raw mutation leaves the managed proof unchanged"
      (ds/transact!
       conn
       [[:db/retract
         document-eid
         relationship-storage/reverse-attribute
         (:v reverse-datom)]])
      (let [half-changed-adapter
            (datascript-backend/snapshot-adapter
             (ds/db conn) (:opts authorization))]
        (is (= before-proof
               (backend/invoke
                half-changed-adapter :proof-frame [relation-id]))))
      (eacl/write-relationship!
       authorization
       {:operation :touch
        :subject user
        :relation :reader
        :resource document})
      (let [after-proof
            (backend/invoke
             (datascript-backend/snapshot-adapter
              (ds/db conn) (:opts authorization))
             :proof-frame [relation-id])]
        (is (< (second (first (:relation-stamps before-proof)))
               (second (first (:relation-stamps after-proof)))))))))

(deftest relationship-cursor-changed-proof-is-stale-test
  (let [conn (datascript/create-conn)
        authorization
        (managed-client conn {})
        _ (eacl/write-schema! authorization schema)
        _ (ds/transact! conn [{:eacl/id "user"}
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
        cursor (get-in page-1 [:page-info :end-cursor])]
    (ds/transact! conn [{:eacl/id "unrelated-cursor-churn"}])
    (testing "an exact-proof relationship cursor cannot form a hybrid walk"
      (let [data
            (error-data
             #(eacl/read-relationships
               authorization
               (assoc query :after cursor)))]
        (is (= :eacl.pagination/stale-cursor (:type data)))
        (is (= :dependency-proof-changed (:reason data)))))
    (let [fresh-page-1
          (eacl/read-relationships authorization query)
          fresh-cursor
          (get-in fresh-page-1 [:page-info :end-cursor])
          delete-token
          (:zed/token
           (eacl/delete-relationship!
            authorization
            (second relationships)))]
      (testing "a relationship change rejects the old cursor"
        (let [data
              (error-data
               #(eacl/read-relationships
                 authorization
                 (assoc query :after fresh-cursor)))]
          (is (= :eacl.pagination/stale-cursor (:type data)))
          (is (= :dependency-proof-changed (:reason data)))))
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
                     delete-token)))))))))))
