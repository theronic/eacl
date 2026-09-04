(ns eacl.datahike.migrations.v7-to-v9-test
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.datahike.db :as db]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.core :as api]
            [eacl.datahike.storage :as admission]
            [eacl.datahike.migrations.v7-to-v9 :as migration]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.migration-contract :as contract]))

(defn open-source
  ([] (open-source {}))
  ([options]
   (let [config (update (merge schema/default-config options) :store #(assoc % :id (random-uuid)))
         _ (d/create-database config)
         !conn (atom (d/connect config))]
     (d/transact @!conn (legacy/source-schema schema/datahike-schema))
     {:directory (get-in config [:store :path])
      :write-schema! #(schema/write-schema! @!conn %)
     :client! #(api/make-client @!conn {})
     :snapshot #(d/db @!conn) :transact! #(d/transact @!conn %) :entid db/entid
      :migrate! #(migration/migrate! @!conn %) :evidence admission/evidence :revision :max-tx
      :rows #(d/datoms %1 {:index :aevt :components [%2]})
      :reopen! #(do (d/release @!conn) (reset! !conn (d/connect config)))
      :close! #(do (d/release @!conn) (d/delete-database config))})))

(deftest restartable-datahike-migration-test
  (doseq [attribute-refs? [false true]]
    (contract/exercise-resume! #(open-source {:attribute-refs? attribute-refs?}))))

(deftest concurrent-datahike-migration-test
  (doseq [attribute-refs? [false true]]
    (contract/exercise-concurrent-head! #(open-source {:attribute-refs? attribute-refs?}))))

(deftest durable-datahike-migration-test
  (contract/exercise-resume!
   #(let [dir (java.nio.file.Files/createTempDirectory "eacl-storage-upgrade-" (make-array java.nio.file.attribute.FileAttribute 0))]
      (open-source {:store {:backend :file :path (str dir "/db")}}))))

(deftest migrated-public-contract-test (contract/exercise-public! open-source))

(deftest native-startup-admission-test (contract/exercise-admission! open-source))
