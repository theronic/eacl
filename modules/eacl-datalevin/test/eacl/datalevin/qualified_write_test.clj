(ns eacl.datalevin.qualified-write-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [datalevin.util :as util]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.relation-removal-contract :as removal]
            [eacl.caveats.inspection-contract :as inspection]
            [eacl.caveats.partial-scan-contract :as partial-scan]
            [eacl.caveats.permission-tree-contract :as permission-tree]
            [eacl.caveats.deletion-contract :as deletion]
            [eacl.caveats.cache-trace-contract :as cache-trace]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.caveats.definition-test :as errors]
            [eacl.caveats.schema-admission-test :as schemas]
            [eacl.core :as eacl]
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
                                                     :source-lifecycle #uuid "25d66e31-e6ec-5407-8ceb-10415d4ef400"
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
                                                        :source-lifecycle #uuid "79b4b68c-73d2-5c2d-98c1-1002de29c7e2"
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

(deftest qualified-relation-stream-resumes-each-batch-inside-its-boundary-value
  (let [dir (util/tmp-dir (str "qualified-relation-stream-" (random-uuid)))
        conn (schema/create-conn dir {})
        token (:write-token (schema/ensure-physical-schema! conn))]
    (try
      (schema/write-schema! conn "definition user {}\ndefinition doc {\n relation viewer: user\n}"
                            {} (schema/current-schema-generation (d/db conn)) token)
      (d/transact! conn (mapv #(hash-map :eacl/id (str "stream/" %)) (range 3102)))
      (let [database (d/db conn)
            rid (d/entid database [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]])
            owners (vec (sort (map #(d/entid database [:eacl/id (str "stream/" %)]) (range 3102))))
            ;; Owners fall along the stream, so every value after a batch
            ;; boundary holds a smaller owner than the boundary row. Datalevin
            ;; applies a seek's owner component to every value it returns.
            descending (vec (rest (rseq owners)))
            ;; [endpoint qualifier] in index order. The batch boundaries
            ;; (1024, 2048, 3072) fall inside a ten-owner value, on a
            ;; qualified value, and on a plain value whose endpoint also has a
            ;; qualified row.
            values (concat (map #(vector % nil) (range 1 1025))
                           (repeat 10 [1025 nil])
                           (map #(vector % nil) (range 1026 2039))
                           [[2039 nil] [2039 7] [2039 9]]
                           (map #(vector % nil) (range 2040 3062))
                           [[3062 nil] [3062 5]]
                           (map #(vector % nil) (range 3063 3090)))
            rows (map-indexed (fn [i [endpoint qualifier]]
                                [:db/add (nth descending i) storage/forward-attribute
                                 [:user rid :doc endpoint qualifier]])
                              values)
            neighbours (for [resource-type [:dob :dod]]
                         [:db/add (peek owners) storage/forward-attribute [:user rid resource-type 1 nil]])]
        (d/transact! conn (-> (vec rows)
                              (into neighbours)
                              (conj [:db/add rid :eacl.datalevin/relation-generation :db/current-tx]))
                     {:datalevin/write-token token})
        (let [database (d/db conn)
              prefix [:user rid :doc]
              index (filterv #(= prefix (subvec (:v %) 0 3))
                             (d/datoms database :ave storage/forward-attribute))
              tail #(subvec (:v (nth index %)) 3)]
          (is (= 3101 (count index)))
          (is (= [1025 nil] (tail 1024) (tail 1025)))
          (is (= [[2039 7] [2039 9]] [(tail 2048) (tail 2049)]))
          (is (= [[3062 nil] [3062 5]] [(tail 3072) (tail 3073)]))
          (is (= index (vec (db/qualified-relation-datoms database storage/forward-attribute prefix))))))
      (finally (d/close conn) (util/delete-files dir)))))

(deftest removing-a-caveat-sees-relationships-past-the-first-native-batch
  (let [dir (util/tmp-dir (str "caveat-past-batch-" (random-uuid)))
        conn (schema/create-conn dir {})
        watermark (atom 0)
        client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                      :source-lifecycle #uuid "5a0e2f7c-3d41-4b8e-9c62-81f4d7a3e095"
                                      :security-key "01234567890123456789012345678901"
                                      :revision-watermark watermark
                                      :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})
        write-schema! (fn [source]
                        (binding [orchestration/*qualified-authorization-enabled?* true]
                          (eacl/write-schema! client {:schema source})))]
    (try
      (write-schema! (schemas/source "user | user with enabled"))
      (let [ids (mapv #(str "batch/" %) (range 2054))
            _ ((:transact! (:native (qualifiers/writer conn))) (mapv #(hash-map :eacl/id %) ids))
            database (d/db conn)
            ranked (vec (sort-by #(d/entid database [:eacl/id %]) ids))
            ;; Eid order: small resources < hidden resource < big resource,
            ;; and likewise for subjects. The big subject's 1,025 rows fill
            ;; the first forward batch and the big resource's fill the first
            ;; reverse batch, so the Caveated row follows a larger boundary
            ;; owner in both directions.
            [small-resources [hidden-resource big-resource]] (split-at 1025 (subvec ranked 0 1027))
            [small-subjects [hidden-subject big-subject]] (split-at 1025 (subvec ranked 1027))
            user #(eacl/spice-object :user %)
            doc #(eacl/spice-object :doc %)
            hidden (eacl/->Relationship (user hidden-subject) :viewer (doc hidden-resource))
            relationships (concat (map #(eacl/->Relationship (user big-subject) :viewer (doc %)) small-resources)
                                  (map #(eacl/->Relationship (user %) :viewer (doc big-resource)) small-subjects)
                                  [(assoc hidden :caveat "enabled" :caveat-context {"flag" true})])]
        (binding [orchestration/*qualified-authorization-enabled?* true]
          (eacl/write-relationships! client (mapv #(hash-map :operation :touch :relationship %) relationships)))
        (let [database (d/db conn)
              rid (d/entid database [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]])
              eid #(d/entid database [:eacl/id %])]
          (doseq [[attribute prefix owner] [[storage/forward-attribute [:user rid :doc] (eid hidden-subject)]
                                            [storage/reverse-attribute [:doc rid :user] (eid hidden-resource)]]]
            (let [index (filterv #(= prefix (subvec (:v %) 0 3)) (d/datoms database :ave attribute))
                  position (first (keep-indexed #(when (some? (nth (:v %2) 4)) %1) index))]
              (is (= 2051 (count index)))
              (is (= owner (:e (nth index position))))
              (is (< 1024 position))
              (is (< owner (:e (nth index 1024)))))))
        (is (= :eacl.schema/relationship-qualifier-in-use
               (errors/error-type #(write-schema! (schemas/source "user")))))
        (is (= [[:eacl.caveat/name "enabled"]]
               (:eacl.relation/caveats (first (:relations (schema/read-schema (d/db conn)))))))
        (is (true? (binding [orchestration/*qualified-authorization-enabled?* true]
                     (eacl/can? client {:subject (user hidden-subject) :permission :view
                                        :resource (doc hidden-resource)})))))
      (finally (d/close conn) (util/delete-files dir)))))

(deftest schema-validation-and-commit-guards-share-one-owned-snapshot
  (let [dir (util/tmp-dir (str "schema-owned-race-" (random-uuid)))
        conn (schema/create-conn dir {})
        watermark (atom 0)
        before (d/active-read-snapshot-info)]
    (try
      (allowance/check!
       {:client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                       :source-lifecycle #uuid "943ebf6a-083f-5d2e-863f-099c01d31a33"
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

(deftest partial-relationship-walks-over-qualified-rows-are-total-and-terminate
  (let [dir (util/tmp-dir (str "partial-scan-" (random-uuid)))
        conn (schema/create-conn dir {})
        now (atom 1000)
        watermark (atom 0)]
    (try
      (partial-scan/check! {:client (api/make-client conn {:clock #(deref now)
                                                           :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                           :source-lifecycle #uuid "7d0f3c1e-5b8a-4f62-9e31-2c4a8b6d0e17"
                                                           :security-key "01234567890123456789012345678901"
                                                           :revision-watermark watermark
                                                           :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})
                            :writer #(qualifiers/writer conn) :now now})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest permission-trees-list-qualified-relationships-without-evaluating-them
  (let [dir (util/tmp-dir (str "permission-tree-" (random-uuid)))
        conn (schema/create-conn dir {})
        now (atom 1000)
        watermark (atom 0)]
    (try
      (permission-tree/check! {:client (api/make-client conn {:clock #(deref now)
                                                              :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                              :source-lifecycle #uuid "3b8e2f61-9c4d-4a07-b5e2-6f1d0c7a9e44"
                                                              :security-key "01234567890123456789012345678901"
                                                              :revision-watermark watermark
                                                              :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})
                               :writer #(qualifiers/writer conn) :now now})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest stored-and-active-inspection-preserve-aligned-native-qualifiers
  (let [dir (util/tmp-dir (str "public-qualified-" (random-uuid)))
        conn (schema/create-conn dir {:app/flag {:db/valueType :db.type/long}})
        now (atom 99)
        watermark (atom 0)]
    (try
      (inspection/check! {:client (api/make-client conn {:clock #(deref now)
                                                         :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                         :source-lifecycle #uuid "25d66e31-e6ec-5407-8ceb-10415d4ef400"
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
        lifecycle-file (str dir "/trace-lifecycle.edn")
        _ (spit lifecycle-file (pr-str #uuid "943ebf6a-083f-5d2e-863f-099c01d31a33"))
        make-client #(api/make-client conn {:clock (fn [] @now)
                                            :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                            :source-lifecycle (edn/read-string (slurp lifecycle-file))
                                            :security-key "01234567890123456789012345678901"
                                            :revision-watermark watermark
                                            :advance-revision-watermark! (fn [revision] (swap! watermark max revision))})]
    (try
      (cache-trace/check! {:client (make-client) :writer #(qualifiers/writer conn) :now now
                           :rotate-client! (fn [_ lifecycle] (spit lifecycle-file (pr-str lifecycle)) (make-client))})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest qualified-object-deletion-is-atomic-and-bounded
  (let [dir (util/tmp-dir (str "qualified-deletion-" (random-uuid)))
        conn (schema/create-conn dir {}) watermark (atom 0)]
    (try
      (deletion/check! {:client (api/make-client conn {:clock (constantly 200)
                                                       :caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                       :source-lifecycle #uuid "f1845938-c92f-54bd-bdd9-94f6944a90cc"
                                                       :security-key "01234567890123456789012345678901"
                                                       :revision-watermark watermark
                                                       :advance-revision-watermark! #(swap! watermark max %)})
                        :writer #(qualifiers/writer conn)})
      (finally (d/close conn) (util/delete-files dir)))))

(deftest removing-a-relation-with-qualified-relationships-reports-relation-in-use
  (let [dir (util/tmp-dir (str "relation-removal-" (random-uuid)))
        conn (schema/create-conn dir {}) watermark (atom 0)]
    (try
      (removal/check! {:client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                                                      :source-lifecycle #uuid "3f6c2f0e-5b8d-5d6a-9a43-4c1e2b7d8a90"
                                                      :security-key "01234567890123456789012345678901"
                                                      :revision-watermark watermark
                                                      :advance-revision-watermark! #(swap! watermark max %)})
                       :writer #(qualifiers/writer conn) :speculative? false})
      (finally (d/close conn) (util/delete-files dir)))))
