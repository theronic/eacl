(ns eacl.datomic.self-counterexample-test
  "Replays EACL-FORMAL-099 (`use self`) on Datomic from its ledger fixture."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.test-support.repo :as repo]))

(defn- ledger [file]
  (edn/read-string (slurp (repo/file "formal" "counterexamples" "EACL-FORMAL-099" file))))

(defn- ids [page] (vec (sort (map :id (:data page)))))

(deftest self-counterexample-replays-on-datomic-test
  (let [{:keys [schema objects relationships]} (ledger "fixture.edn")
        expected (ledger "expected.edn")]
    (with-mem-conn [conn schema/v8-schema]
      (let [client (datomic/make-client conn {:security-key "self-counterexample-099-key00000"})]
        (eacl/write-schema! client schema)
        @(d/transact conn (mapv #(hash-map :db/id % :eacl/id %) objects))
        (eacl/create-relationships!
         client
         (mapv (fn [[subject-type subject-id relation resource-type resource-id]]
                 (eacl/->Relationship (eacl/spice-object subject-type subject-id)
                                      relation
                                      (eacl/spice-object resource-type resource-id)))
               relationships))
        (is (:accepted expected))
        (is (= (:checks expected)
               (mapv (fn [[resource-type resource-id permission subject-type subject-id]]
                       [resource-type resource-id permission subject-type subject-id
                        (:allowed? (eacl/check-permission
                                    client {:subject (eacl/spice-object subject-type subject-id)
                                            :permission permission
                                            :resource (eacl/spice-object resource-type resource-id)}))])
                     (:checks expected))))
        (is (= (:lookup-resources expected)
               (mapv (fn [[resource-type permission subject-type subject-id]]
                       [resource-type permission subject-type subject-id
                        (ids (eacl/lookup-resources
                              client {:subject (eacl/spice-object subject-type subject-id)
                                      :permission permission :resource/type resource-type
                                      :first 10}))])
                     (:lookup-resources expected))))
        (is (= (:lookup-subjects expected)
               (mapv (fn [[resource-type resource-id permission subject-type]]
                       [resource-type resource-id permission subject-type
                        (ids (eacl/lookup-subjects
                              client {:resource (eacl/spice-object resource-type resource-id)
                                      :permission permission :subject/type subject-type
                                      :first 10}))])
                     (:lookup-subjects expected))))))))
