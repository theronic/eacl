(ns eacl.datascript.consistency-v3-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.schema :as datascript-schema]
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

(defn- managed-client
  [conn options]
  (datascript/make-client
   conn
   (merge {:coherence-authority :managed
           :security-key security-key
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
        (managed-client conn {:exact-snapshot-registry-size 16})
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
    (testing "historical fallback never consults the current-answer cache"
      (eacl/delete-relationship! authorization (second relationships))
      (let [before (datascript/cache-stats authorization)
            historical-1
            (eacl/lookup-resources authorization page-2-query)
            historical-2
            (eacl/lookup-resources authorization page-2-query)
            after (datascript/cache-stats authorization)]
        (is (= [(second documents)] (:data historical-1)))
        (is (= (:data historical-1) (:data historical-2)))
        (is (false? (:cached? historical-1)))
        (is (false? (:cached? historical-2)))
        (is (= (+ 2 (:bypasses before)) (:bypasses after)))
        (is (= (:exact-hits before) (:exact-hits after)))
        (is (= (:managed-hits before) (:managed-hits after)))))))

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
    (testing "numeric progress cannot replace the missing anchor"
      ;; Install a same-family predecessor and advance its transaction counter
      ;; independently. The token mutation remains absent.
      (ds/reset-conn! conn pre-write)
      (ds/transact! conn [{:eacl/id "unrelated"}])
      (is (= :eacl.consistency/freshness-unavailable
             (:type
              (error-data
               #(eacl/can?
                 client user :view document
                 (consistency/at-least-as-fresh token)))))))))

(deftest bounded-exact-registry-test
  (let [conn (datascript/create-conn)
        client
        (managed-client
         conn
         {:exact-snapshot-registry-size 2})
        _ (seed! conn client)
        token
        (:zed/token
         (eacl/create-relationship! client relationship))]
    (is (true? (eacl/can? client user :view document)))
    (eacl/delete-relationship! client relationship)
    (is (false? (eacl/can? client user :view document)))
    (let [before (datascript/cache-stats client)]
      (is (true?
           (eacl/can?
            client user :view document
            (consistency/at-exact-snapshot token))))
      (is (true?
           (eacl/can?
            client user :view document
            (consistency/at-exact-snapshot token))))
      (let [after (datascript/cache-stats client)]
        (is (= (+ 2 (:bypasses before))
               (:bypasses after)))
        (is (= (:exact-hits before)
               (:exact-hits after))
            "exact requests never consult the completed-answer cache")))))

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

(deftest cloned-history-and-listener-independence-test
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
        (is (true?
             (eacl/can?
              post-token-client user :view document
              (consistency/at-least-as-fresh token))))
        (is (= :eacl.consistency/freshness-unavailable
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
    (is (= :eacl.consistency/freshness-unavailable
           (:type
            (error-data
             #(eacl/can?
               right user :view document
               (consistency/at-least-as-fresh token))))))))

(deftest exact-registry-eviction-and-cache-lifting-test
  (let [conn (datascript/create-conn)
        store (cache/local-store)
        authorization
        (managed-client
         conn
         {:cache store
          :exact-snapshot-registry-size 1})
        _ (seed! conn authorization)
        token
        (:zed/token
         (eacl/create-relationship! authorization relationship))
        query {:subject user
               :permission :view
               :resource/type :document
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
    (is (= :eacl.consistency/exact-snapshot-unavailable
           (:type
            (error-data
             #(eacl/can?
               authorization user :view document
               (consistency/at-exact-snapshot token))))))))

(deftest content-proofs-are-bounded-and-cover-public-identity-test
  (let [conn (datascript/create-conn)
        authorization
        (managed-client conn {:proof-mode :content})
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
        schema-proof (backend/invoke before-adapter :schema-proof)
        before-proof
        (backend/invoke before-adapter :relation-proof [relation-id])
        user-eid (ds/entid (ds/db conn) [:eacl/id "user"])
        document-eid (ds/entid (ds/db conn) [:eacl/id "document"])
        reverse-datom
        (first
         (ds/datoms
          (ds/db conn) :eavt document-eid
          datascript-schema/reverse-relationship-attr))]
    (is (= #{:content-digest} (set (keys schema-proof))))
    (is (= 43 (count (:content-digest schema-proof))))
    (is (= #{:content-digest} (set (keys before-proof))))
    (is (= 43 (count (:content-digest before-proof))))
    (testing "one out-of-band physical-half change invalidates the proof"
      (ds/transact!
       conn
       [[:db/retract
         document-eid
         datascript-schema/reverse-relationship-attr
         (:v reverse-datom)]])
      (let [half-changed-adapter
            (datascript-backend/snapshot-adapter
             (ds/db conn) (:opts authorization))]
        (is (not=
             before-proof
             (backend/invoke
              half-changed-adapter :relation-proof [relation-id]))))
      (ds/transact!
       conn
       [[:db/add
         document-eid
         datascript-schema/reverse-relationship-attr
         (:v reverse-datom)]])
      (is (= before-proof
             (backend/invoke
              (datascript-backend/snapshot-adapter
               (ds/db conn) (:opts authorization))
              :relation-proof [relation-id]))))
    ;; The stored relationship keeps the same endpoint eid. Only its public
    ;; identity changes, so this specifically proves the identity boundary is
    ;; part of full-content cache and cursor equivalence.
    (ds/transact! conn [[:db/retract user-eid :eacl/id "user"]
                        [:db/add user-eid :eacl/id "renamed-user"]])
    (let [after-adapter
          (datascript-backend/snapshot-adapter
           (ds/db conn) (:opts authorization))
          after-proof
          (backend/invoke after-adapter :relation-proof [relation-id])]
      (is (not= before-proof after-proof)))))

(deftest relationship-cursor-exact-snapshot-fallback-test
  (let [conn (datascript/create-conn)
        authorization
        (managed-client conn {:exact-snapshot-registry-size 16})
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
        cursor (get-in page-1 [:page-info :end-cursor])
        cursor-data
        (datascript/token->cursor cursor (:opts authorization))]
    (ds/transact! conn [{:eacl/id "unrelated-cursor-churn"}])
    (testing "an unrelated write still pins continuation to the original snapshot"
      (let [page-2
            (eacl/read-relationships
             authorization
             (assoc query :after cursor))
            rebased
            (datascript/token->cursor
             (get-in page-2 [:page-info :end-cursor])
             (:opts authorization))]
        (is (= [(second relationships)] (:data page-2)))
        (is (= (get-in cursor-data [:graph-head :exact-locator])
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
      (testing "a relationship change uses the retained original DB"
        (is (= [(second relationships)]
               (:data
                (eacl/read-relationships
                 authorization
                 (assoc query :after fresh-cursor))))))
      (testing "a newer at-least floor forbids exact fallback"
        (is (= :eacl.consistency/cursor-consistency-conflict
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
