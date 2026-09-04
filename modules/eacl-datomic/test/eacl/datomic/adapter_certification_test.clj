(ns eacl.datomic.adapter-certification-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.adapter-certification :as certification]
            [eacl.backend.v8 :as v8]
            [eacl.core :as eacl]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(deftest datomic-adapter-certification-test
  (doseq [fixture (certification/coherent-fixtures [820084])]
    (testing (str "seed " (:seed fixture))
      (with-mem-conn [conn schema/v8-schema]
        (let [client
              (datomic/make-client
               conn
               {:security-key "datomic-adapter-certification000"})]
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
                (datomic-backend/basis-adapter
                 db
                 {:entid->object-id
                  (fn [snapshot internal-id]
                    (:eacl/id
                     (d/entity snapshot internal-id)))})
                report
                (certification/certify
                 {:adapter adapter
                  :fixture fixture
                  :runtime :clj})]
            (is (some? (v8/invoke adapter :schema-generation)))
            (is (= :certified-scalar-fallback-v1
                   (get-in (v8/operator-capability-identity adapter)
                           [:direct-membership :mode])))
            (is (:passed? report)
                (pr-str (:checks report)))
            (let [relation-ids
                  (->> (:relations fixture)
                       (mapcat
                        (fn [{:keys [resource-type relation-name]}]
                          (v8/invoke
                           adapter :relation-defs
                           resource-type relation-name)))
                       (map :relation-id)
                       sort
                       vec)
                  affected-relation-id
                  (:relation-id
                   (first
                    (v8/invoke adapter :relation-defs :group :member)))]
              (eacl/delete-relationship!
               client (first (:relationships fixture)))
              (let [after
                    (datomic-backend/basis-adapter
                     (d/db conn)
                     {:entid->object-id
                      (fn [snapshot internal-id]
                        (:eacl/id (d/entity snapshot internal-id)))})]
                (is (= :certified
                       (:status
                        (certification/certify-ordered-generation-transition!
                         {:before-adapter adapter
                          :after-adapter after
                          :relation-ids relation-ids
                          :affected-relation-ids
                          [affected-relation-id]}))))))))))))

(deftest datomic-memory-live-source-identity-certification-test
  (with-mem-conn [first-conn schema/v8-schema]
    (with-mem-conn [second-conn schema/v8-schema]
      (is (= :certified
             (:status
              (certification/certify-live-source-identity!
               {:backend :datomic
                :durability :non-durable
                :first-scope
                (datomic-backend/database-source-scope (d/db first-conn))
                :second-scope
                (datomic-backend/database-source-scope (d/db second-conn))})))))))
