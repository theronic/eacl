(ns eacl.datalevin.qualified-write-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [datalevin.util :as util]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.inspection-contract :as inspection]
            [eacl.caveats.cache-trace-contract :as cache-trace]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.datalevin.core :as api]
            [eacl.datalevin.db :as db]
            [eacl.relationships.storage :as storage]
            [eacl.schema.relation-allowance :as relation-allowance]
            [eacl.client.orchestration :as orchestration]
            [eacl.datalevin.caveat-schema-test :as schema-races]
            [eacl.datalevin.schema :as schema]
            [eacl.datalevin.qualifiers :as qualifiers]))

(deftest qualified-batches-publish-atomically
  (let [dir (util/tmp-dir (str "qualified-batch-" (random-uuid)))
        conn (schema/create-conn dir {:app/flag {:db/valueType :db.type/long}})
        token (:write-token (schema/ensure-physical-schema! conn))]
    (try
      (batch/check! {:write-schema! #(schema/write-schema! conn % {} (schema/current-schema-generation (d/db conn)) token)
                     :writer #(qualifiers/writer conn) :entid d/entid :strategy :inline
                     :allowance-stamps (fn [database]
                                         (let [eid (d/entid database [:eacl/id "schema-string"])]
                                           [[:db/add eid :eacl.datalevin/schema-generation :db/current-tx]
                                            [:db/add eid :eacl.datalevin/schema-write-fence :db/current-tx]]))})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest public-qualified-writes-preserve-identity-and-commit-atomically
  (let [dir (util/tmp-dir (str "public-qualified-" (random-uuid)))
        conn (schema/create-conn dir {:app/flag {:db/valueType :db.type/long}})
        now (atom 99)
        watermark (atom 0)]
    (try
      (public/check! {:client (api/make-client conn {:clock #(deref now)
                                                     :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                     :source-lifecycle "public-qualified-write"
                                                     :security-key "01234567890123456789012345678901"
                                                     :revision-watermark watermark
                                                     :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})
                      :writer #(qualifiers/writer conn) :entid d/entid :now now :speculative? false
                      :allowance-stamps (fn [database]
                                          (let [eid (d/entid database [:eacl/id "schema-string"])]
                                            [[:db/add eid :eacl.datalevin/schema-generation :db/current-tx]
                                             [:db/add eid :eacl.datalevin/schema-write-fence :db/current-tx]]))})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest schema-alternatives-preserve-relation-identities-and-retained-data
  (let [dir (util/tmp-dir (str "schema-allowance-" (random-uuid)))
        conn (schema/create-conn dir {})
        token (:write-token (schema/ensure-physical-schema! conn))
        watermark (atom 0)]
    (try
      (allowance/check! {:client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                        :source-lifecycle "schema-allowance"
                                                        :security-key "01234567890123456789012345678901"
                                                        :revision-watermark watermark
                                                        :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})
                         :writer #(qualifiers/writer conn)
                         :read-schema schema/read-schema :interleave! schema-races/interleave! :entid d/entid})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest schema-allowance-scan-keeps-every-row-across-native-batches
  (let [dir (util/tmp-dir (str "schema-allowance-scan-" (random-uuid)))
        conn (schema/create-conn dir {})
        token (:write-token (schema/ensure-physical-schema! conn))]
    (try
      (schema/write-schema! conn "definition user {}\ndefinition doc {\n relation viewer: user\n}"
                            {} (schema/current-schema-generation (d/db conn)) token)
      (d/transact! conn (mapv #(hash-map :eacl/id (str "scan/" %)) (range 1030)))
      (let [database (d/db conn)
            rid (d/entid database [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]])
            ids (mapv #(d/entid database [:eacl/id (str "scan/" %)]) (range 1030))
            ;; Equal endpoint tails cross the batch boundary; owner + complete
            ;; tuple value must advance without omitting or repeating rows.
            rows (mapv (fn [owner] [:db/add owner storage/forward-attribute [:user rid :doc (first ids) nil]]) ids)]
        (d/transact! conn (conj rows [:db/add rid :eacl.datalevin/relation-generation :db/current-tx])
                     {:datalevin/write-token token})
        (let [observed (db/qualified-relation-datoms (d/db conn) storage/forward-attribute [:user rid :doc])]
          (is (= ids (mapv :e observed)))
          (is (= 1030 (count observed)))))
      (finally (d/close conn) (util/delete-files dir)))))

(deftest schema-validation-and-commit-guards-share-one-owned-snapshot
  (let [dir (util/tmp-dir (str "schema-owned-race-" (random-uuid)))
        conn (schema/create-conn dir {})
        watermark (atom 0)
        before (d/active-read-snapshot-info)]
    (try
      (allowance/check!
       {:client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                       :source-lifecycle "schema-owned-race"
                                       :security-key "01234567890123456789012345678901"
                                       :revision-watermark watermark
                                       :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})
        :writer #(qualifiers/writer conn) :read-schema schema/read-schema :entid d/entid
        :interleave!
        (fn [competitor outer]
          (let [validate relation-allowance/validate-existing!
                armed (atom true)]
            (with-redefs [relation-allowance/validate-existing!
                          (fn [deltas referenced]
                            (let [result (validate deltas referenced)]
                              (when (compare-and-set! armed true false)
                                ;; A separate thread owns its own native reads.
                                ;; The schema reader remains pinned throughout.
                                (let [outcome (promise)
                                      worker (Thread. (fn []
                                                        (try (binding [orchestration/*qualified-authorization-enabled?* true] (competitor))
                                                             (deliver outcome nil)
                                                             (catch Throwable e (deliver outcome e)))))]
                                  (.start worker)
                                  (let [result (deref outcome 10000 ::timeout)]
                                    (when (= ::timeout result) (throw (ex-info "Competing writer timed out" {})))
                                    (when result (throw result)))))
                              result))]
              (outer))))})
      (is (= before (d/active-read-snapshot-info)))
      (finally (d/close conn) (util/delete-files dir)))))

(deftest stored-and-active-inspection-preserve-aligned-native-qualifiers
  (let [dir (util/tmp-dir (str "public-qualified-" (random-uuid)))
        conn (schema/create-conn dir {:app/flag {:db/valueType :db.type/long}})
        now (atom 99)
        watermark (atom 0)]
    (try
      (inspection/check! {:client (api/make-client conn {:clock #(deref now)
                                                         :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                         :source-lifecycle "public-qualified-write"
                                                         :security-key "01234567890123456789012345678901"
                                                         :revision-watermark watermark
                                                         :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})
                          :writer #(qualifiers/writer conn) :entid d/entid :now now :speculative? false
                          :allowance-stamps (fn [database]
                                              (let [eid (d/entid database [:eacl/id "schema-string"])]
                                                [[:db/add eid :eacl.datalevin/schema-generation :db/current-tx]
                                                 [:db/add eid :eacl.datalevin/schema-write-fence :db/current-tx]]))})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest qualified-cache-traces-match-uncached-authorization
  (let [dir (util/tmp-dir (str "qualified-cache-trace-" (random-uuid)))
        conn (schema/create-conn dir {}) now (atom 99) watermark (atom 0)
        lifecycle-file (str dir "/trace-lifecycle.txt")
        _ (spit lifecycle-file "qualified-cache-trace")
        make-client #(api/make-client conn {:clock (fn [] @now)
                                            :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                            :source-lifecycle (slurp lifecycle-file)
                                            :security-key "01234567890123456789012345678901"
                                            :revision-watermark watermark
                                            :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})]
    (try
      (cache-trace/check! {:client (make-client) :writer #(qualifiers/writer conn) :now now
                           :rotate-client! (fn [_ lifecycle] (spit lifecycle-file lifecycle) (make-client))})
      (finally (d/close conn) (util/delete-files dir)))))
