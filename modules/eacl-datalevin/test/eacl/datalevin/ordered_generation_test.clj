(ns eacl.datalevin.ordered-generation-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datalevin.core :as datalevin]
            [eacl.proof-frame :as proof-frame]
            [eacl.spicedb.consistency :as consistency]))

(def ^:private test-key "01234567890123456789012345678901")

(def ^:private logical-schema
  "definition user {}
   definition document {
     relation viewer: user
     relation editor: user
     permission view = viewer
   }")

(defn- with-system
  [f]
  (let [dir (u/tmp-dir (str "eacl-datalevin-ordered-" (random-uuid)))
        conn (datalevin/create-conn dir)
        watermark (atom 0)
        client
        (datalevin/make-client
         conn
         {:security-key test-key
          :source-lifecycle "ordered-generation-test"
          :revision-watermark watermark
          :advance-revision-watermark! #(swap! watermark max %)})]
    (try
      (eacl/write-schema! client logical-schema)
      (d/transact!
       conn
       (mapv (fn [id] {:eacl/id id})
             ["alice" "bob" "document-1" "document-2"]))
      (f {:conn conn :client client :watermark watermark})
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn- selected-adapter
  [client f]
  (let [selection (source/acquire! (:source client) :current)]
    (try
      (f (source/adapter selection))
      (finally
        (source/release! selection)))))

(deftest exact-managed-and-invalidated-cache-paths-test
  (with-system
    (fn [{:keys [client]}]
      (testing "schema generation is one exact EAV probe with no identity lookup"
        (selected-adapter
         client
         (fn [adapter]
           (with-redefs [d/entid
                         (fn [& _]
                           (throw
                            (ex-info "unexpected schema identity lookup"
                                     {:type :test/unexpected-entid})))]
             (is (integer? (backend/invoke adapter
                                            :schema-generation)))))))
      (let [alice (eacl/spice-object :user "alice")
            bob (eacl/spice-object :user "bob")
            document-1 (eacl/spice-object :document "document-1")
            document-2 (eacl/spice-object :document "document-2")
            viewer (eacl/->Relationship alice :viewer document-1)
            editor (eacl/->Relationship bob :editor document-2)
            demand {:subject alice :permission :view :resource document-1}]
        (eacl/create-relationship! client viewer)
        (is (false? (:cached? (eacl/check-permission client demand))))

        (testing "an identical-basis hit performs no proof-frame read"
          (let [operations (atom {})
                response
                (binding [backend/*backend-op-stats* operations]
                  (eacl/check-permission client demand))]
            (is (true? (:cached? response)))
            (is (zero? (get @operations :proof-frame 0)))))

        (testing "an unrelated relation commit is a managed hit"
          (let [before (:managed-hits (datalevin/cache-stats client))]
            (eacl/create-relationship! client editor)
            (let [operations (atom {})
                  response
                  (binding [backend/*backend-op-stats* operations]
                    (eacl/check-permission client demand))]
              (is (true? (:cached? response)))
              (is (= 1 (get @operations :proof-frame 0)))
              (is (= (inc before)
                     (:managed-hits (datalevin/cache-stats client)))))))

        (testing "a relevant relation commit invalidates the answer"
          (eacl/delete-relationship! client viewer)
          (let [response (eacl/check-permission client demand)]
            (is (false? (:cached? response)))
            (is (false? (:allowed? response)))))))))

(deftest missing-and-above-ceiling-generations-are-distinguished-test
  (with-system
    (fn [{:keys [conn client]}]
      (let [alice (eacl/spice-object :user "alice")
            document (eacl/spice-object :document "document-1")
            viewer (eacl/->Relationship alice :viewer document)]
        (eacl/create-relationship! client viewer)
        (selected-adapter
         client
         (fn [adapter]
           (testing "a missing relation generation is typed unavailable"
             (let [result
                   (proof-frame/resolve!
                    (proof-frame/request-frame adapter)
                    [9007199254740000])]
               (is (= :unavailable (:status result)))
               (is (= :relation-generation-unavailable
                      (:reason result)))))))

        (let [db (d/db conn)
              schema-generation
              (:eacl.datalevin/schema-generation
               (d/entity db [:eacl/id "schema-string"]))
              relation-id
              (selected-adapter
               client
               (fn [adapter]
                 (:relation-id
                  (first
                   (backend/invoke adapter :relation-defs
                                   :document :viewer)))))
              original-revision-info d/read-snapshot-revision-info]
          (with-redefs
            [d/read-snapshot-revision-info
             (fn [snapshot]
               (assoc (original-revision-info snapshot)
                      :max-tx schema-generation))]
            (selected-adapter
             client
             (fn [adapter]
               (testing "a relation generation above the selected ceiling is a contract violation"
                 (let [result
                       (proof-frame/resolve!
                        (proof-frame/request-frame adapter)
                        [relation-id])]
                   (is (= :contract-violation (:status result)))
                   (is (= :relation-generation-above-revision
                          (:reason result)))))))))))))

(deftest provider-restart-preserves-cursors-tokens-and-managed-state-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-restart-proof-"
                            (random-uuid)))
        watermark (atom 0)
        options
        {:security-key test-key
         :source-lifecycle "ordered-restart-test"
         :revision-watermark watermark
         :advance-revision-watermark! #(swap! watermark max %)}
        alice (eacl/spice-object :user "alice")
        bob (eacl/spice-object :user "bob")
        document-1 (eacl/spice-object :document "document-1")
        document-2 (eacl/spice-object :document "document-2")
        viewer-1 (eacl/->Relationship alice :viewer document-1)
        viewer-2 (eacl/->Relationship alice :viewer document-2)
        editor (eacl/->Relationship bob :editor document-2)
        demand {:subject alice :permission :view :resource document-1}
        page-query
        {:subject alice
         :permission :view
         :resource/type :document
         :first 1}
        first-conn (datalevin/create-conn dir)]
    (try
      (let [first-client (datalevin/make-client first-conn options)]
        (eacl/write-schema! first-client logical-schema)
        (d/transact!
         first-conn
         (mapv (fn [id] {:eacl/id id})
               ["alice" "bob" "document-1" "document-2"]))
        (let [write-response
              (eacl/create-relationships!
               first-client [viewer-1 viewer-2])
              token (:zed/token write-response)
              _ (eacl/check-permission first-client demand)
              first-page (eacl/lookup-resources first-client page-query)
              oracle-stream
              (:data
               (eacl/lookup-resources
                first-client
                (assoc page-query
                       :first 10
                       :cache? false
                       :populate-cache? false)))
              cursor (get-in first-page [:page-info :end-cursor])]
          (is (string? token))
          (is (string? cursor))
          (d/close first-conn)
          (let [second-conn (datalevin/create-conn dir)]
            (try
              (let [second-client (datalevin/make-client second-conn options)]
                (testing "the pre-restart causal token remains comparable"
                  (is (true?
                       (:allowed?
                        (eacl/check-permission
                         second-client alice :view document-1
                         (consistency/at-least-as-fresh token))))))
                (testing "the pre-restart cursor resumes on the same revision"
                  (contract/assert-cursor-source-transition!
                   {:client second-client
                    :query page-query
                    :first-page first-page
                    :oracle-stream oracle-stream
                    :durability :durable}))
                (testing "managed reuse remains enabled after reopen"
                  (eacl/create-relationship! second-client editor)
                  (let [before (:managed-hits
                                (datalevin/cache-stats second-client))
                        response (eacl/check-permission second-client demand)]
                    (is (true? (:cached? response)))
                    (is (= (inc before)
                           (:managed-hits
                            (datalevin/cache-stats second-client)))))))
              (finally
                (d/close second-conn))))))
      (finally
        (d/close first-conn)
        (u/delete-files dir)))))
