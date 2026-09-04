(ns eacl.datalevin.migrations.v7-to-v9-test
  (:require [clojure.test :refer [deftest]]
            [clojure.walk :as walk]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.datalevin.schema :as schema]
            [eacl.datalevin.core :as api]
            [eacl.datalevin.storage :as admission]
            [eacl.datalevin.migrations.v7-to-v9 :as migration]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.migration-contract :as contract]))

(defn old-policy [conn]
  (let [policy (walk/postwalk-replace
                {storage/forward-attribute legacy/forward-attribute
                 storage/reverse-attribute legacy/reverse-attribute}
                (#'schema/expected-write-policy conn))]
    (-> policy
        (update :guarded-attributes disj :eacl.storage/migration-state :eacl.storage/migration-generation)
        (update :frozen-attributes disj :eacl.storage/migration-state :eacl.storage/migration-generation)
        (update :commit-generation-attributes disj :eacl.storage/migration-generation)
        (update :stamp-rules #(filterv (fn [rule] (not= :eacl.storage/migration-state (:when-attribute rule))) %)))))

(defn open-source
  ([] (open-source false))
  ([protected?]
   (let [dir (str (java.nio.file.Files/createTempDirectory "eacl-v7-to-v9-" (make-array java.nio.file.attribute.FileAttribute 0)))
         physical (-> schema/datalevin-schema
                      (dissoc storage/forward-attribute storage/reverse-attribute)
                      (assoc legacy/forward-attribute {:db/valueType :db.type/tuple :db/tupleTypes legacy/tuple-types
                                                       :db/cardinality :db.cardinality/many :db/index true}
                             legacy/reverse-attribute {:db/valueType :db.type/tuple :db/tupleTypes legacy/tuple-types
                                                       :db/cardinality :db.cardinality/many :db/index true}))
         watermark (atom 0)
         !conn (atom (d/get-conn dir physical))]
     {:directory dir
      :write-schema!
      (fn [text]
        (d/transact! @!conn [{:eacl/id "schema-string"}
                             {:eacl/id "datalevin-metadata" :eacl.datalevin/source-id (random-uuid)}])
        (let [token (:write-token (d/install-write-policy! @!conn (old-policy @!conn)))]
          (schema/prepare-cache-coherence! @!conn token)
          (schema/write-schema! @!conn text {} ::schema/read-current-generation token)))
      :client! (fn [] (api/make-client @!conn {:security-key "01234567890123456789012345678901"
                                               :source-lifecycle "migration-contract"
                                               :revision-watermark watermark
                                               :advance-revision-watermark! #(swap! watermark max %)}))
      :snapshot #(d/db @!conn) :transact! (fn [operations]
                                            (let [policy (d/write-policy @!conn)
                                                  token (when policy (:write-token (d/install-write-policy! @!conn policy)))]
                                              (let [relations (into #{} (keep #(when (and (vector? %) (legacy/attributes (nth % 2 nil)))
                                                                                 (nth (nth % 3) 1))) operations)
                                                    operations (into (vec operations)
                                                                     (map #(vector :db/add % :eacl.datalevin/relation-generation :db/current-tx))
                                                                     relations)]
                                                (d/transact! @!conn operations (when token {:datalevin/write-token token}))))) :entid d/entid
      :migrate! #(migration/migrate! @!conn %) :evidence admission/evidence :revision :max-tx
      :rows #(d/datoms %1 :ave %2)
      :prepare-source! (when protected?
                         #(do (d/close @!conn) (reset! !conn (d/get-conn dir))))
      :reopen! #(do (d/close @!conn) (reset! !conn (d/get-conn dir)))
      :close! #(do (d/close @!conn) (u/delete-files dir))})))

(deftest restartable-datalevin-migration-test (contract/exercise-resume! open-source))

(deftest protected-datalevin-migration-test
  (contract/exercise-resume! #(open-source true)))

(deftest concurrent-datalevin-migration-test
  (contract/exercise-concurrent-head! #(open-source true)))

(deftest migrated-public-contract-test (contract/exercise-public! open-source))

(deftest native-startup-admission-test (contract/exercise-admission! open-source))
