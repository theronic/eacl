(ns eacl.datomic.migrations.v7-to-v9-test
  (:require [clojure.test :refer [deftest]]
            [datomic.api :as d]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.core :as api]
            [eacl.datomic.storage :as admission]
            [eacl.datomic.migrations.v7-to-v9 :as migration]
            [eacl.relationships.migration-contract :as contract]))

(defn open-source []
  (let [uri (str "datomic:mem://v7-to-v9-" (random-uuid))
        _ (d/create-database uri)
        !conn (atom (d/connect uri))]
    @(d/transact @!conn schema/v7-schema)
    {:write-schema! #(schema/write-schema! @!conn %)
     :client! #(api/make-client @!conn {})
     :snapshot #(d/db @!conn) :transact! #(deref (d/transact @!conn %)) :entid d/entid
     :migrate! #(migration/migrate! @!conn %) :evidence admission/evidence :revision d/basis-t
     :rows #(d/datoms %1 :aevt %2)
     :close! #(do (d/release @!conn) (d/delete-database uri))}))

(deftest restartable-datomic-migration-test (contract/exercise-resume! open-source))

(deftest concurrent-datomic-migration-test (contract/exercise-concurrent-head! open-source))

(deftest migrated-public-contract-test (contract/exercise-public! open-source))

(deftest native-startup-admission-test (contract/exercise-admission! open-source))
