(ns eacl.datalevin.qualified-write-test
  (:require [clojure.test :refer [deftest]]
            [datalevin.core :as d]
            [datalevin.util :as util]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.datalevin.core :as api]
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
