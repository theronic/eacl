(ns eacl.datomic.adapter-certification-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.adapter-certification :as certification]
            [eacl.core :as eacl]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(deftest datomic-adapter-certification-test
  (doseq [fixture (certification/coherent-fixtures [820084])]
    (testing (str "seed " (:seed fixture))
      (with-mem-conn [conn schema/v7-schema]
        (let [client
              (datomic/make-client
               conn
               {:page-token-key "datomic-adapter-certification"})]
          (eacl/write-schema! client (:schema fixture))
          @(d/transact
            conn
            (mapv
             (fn [{:keys [id]}]
               {:db/id id
                :eacl/id id})
             (:objects fixture)))
          (eacl/create-relationships!
           client (:relationships fixture))
          (let [db (d/db conn)
                adapter
                (datomic-backend/snapshot-adapter
                 db
                 {:entid->object-id
                  (fn [snapshot internal-id]
                    (:eacl/id
                     (d/entity snapshot internal-id)))
                  :conn conn
                  :coherence-authority :managed
                  :proof-mode :content})
                report
                (certification/certify
                 {:adapter adapter
                  :fixture fixture
                  :runtime :clj})]
            (is (:passed? report)
                (pr-str (:checks report)))))))))
