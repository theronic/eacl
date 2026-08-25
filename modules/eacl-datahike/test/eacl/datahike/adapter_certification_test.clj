(ns eacl.datahike.adapter-certification-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.adapter-certification :as certification]
            [eacl.backend.v8 :as v8]
            [eacl.core :as eacl]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.core :as datahike]))

(defn- seed-adapter
  [fixture config]
  (let [conn (datahike/create-conn nil config)
        client (datahike/make-client conn {})]
    (eacl/write-schema! client (:schema fixture))
    (d/transact
     conn
     (vec
      (map-indexed
       (fn [index {:keys [id]}]
         {:db/id (- (inc index))
          :eacl/id id})
       (:objects fixture))))
    (eacl/create-relationships! client (:relationships fixture))
    (let [db (d/db conn)]
      (datahike-backend/basis-adapter
       db
       {:object-id->entid
        (fn [snapshot object-id]
          (:db/id (d/entity snapshot [:eacl/id object-id])))
        :entid->object-id
        (fn [snapshot internal-id]
          (:eacl/id (d/entity snapshot internal-id)))}))))

(deftest datahike-adapter-certification-test
  (doseq [[label config]
          [["attribute keywords" nil]
           ["numeric attribute refs" {:attribute-refs? true}]]
          fixture (certification/coherent-fixtures [820084])]
    (testing (str label ", seed " (:seed fixture))
      (let [adapter (seed-adapter fixture config)
            report
            (certification/certify
             {:adapter adapter
              :fixture fixture
              :runtime :clj})]
        (is (some? (v8/invoke adapter :schema-generation)))
        (is (= v8/direct-membership-batch-capability
               (get-in (v8/operator-capability-identity adapter)
                       [:direct-membership :mode])))
        (is (:passed? report)
            (pr-str (:checks report)))))))

(deftest datahike-ordered-generation-transition-certification-test
  (let [fixture (certification/coherent-fixture 820084)
        conn (datahike/create-conn)
        client (datahike/make-client conn {})
        adapter-for
        (fn []
          (datahike-backend/basis-adapter
           (d/db conn)
           {:object-id->entid
            (fn [snapshot object-id]
              (:db/id (d/entity snapshot [:eacl/id object-id])))
            :entid->object-id
            (fn [snapshot internal-id]
              (:eacl/id (d/entity snapshot internal-id)))}))]
    (eacl/write-schema! client (:schema fixture))
    (d/transact
     conn
     (vec
      (map-indexed
       (fn [index {:keys [id]}]
         {:db/id (- (inc index)) :eacl/id id})
       (:objects fixture))))
    (eacl/create-relationships! client (:relationships fixture))
    (let [before (adapter-for)
          relation-ids
          (->> (:relations fixture)
               (mapcat
                (fn [{:keys [resource-type relation-name]}]
                  (v8/invoke
                   before :relation-defs resource-type relation-name)))
               (map :relation-id)
               sort
               vec)
          affected
          (:relation-id
           (first (v8/invoke before :relation-defs :group :member)))]
      (eacl/delete-relationship! client (first (:relationships fixture)))
      (is (= :certified
             (:status
              (certification/certify-ordered-generation-transition!
               {:before-adapter before
                :after-adapter (adapter-for)
                :relation-ids relation-ids
                :affected-relation-ids [affected]})))))))

(deftest datahike-memory-live-source-identity-certification-test
  (let [fixed-id (random-uuid)
        config {:store {:backend :memory :id fixed-id}}
        first-conn (datahike/create-conn nil config)
        first-db-config (:config (d/db first-conn))
        first-scope
        (try
          (datahike-backend/database-source-scope (d/db first-conn))
          (finally
            (d/release first-conn)
            (d/delete-database first-db-config)))
        second-conn (datahike/create-conn nil config)]
    (try
      (is (= :certified
             (:status
              (certification/certify-live-source-identity!
               {:backend :datahike
                :durability :non-durable
                :first-scope first-scope
                :second-scope
                (datahike-backend/database-source-scope
                 (d/db second-conn))}))))
      (finally
        (let [second-db-config (:config (d/db second-conn))]
          (d/release second-conn)
          (d/delete-database second-db-config))))))

(deftest current-db-reference-identity-test
  (testing "the exact-basis cache can use immutable DB object identity"
    (let [conn (datahike/create-conn)
          before-1 (d/db conn)
          before-2 (d/db conn)
          _ (d/transact conn [{:eacl/id "datahike-reference-identity"}])
          after-1 (d/db conn)
          after-2 (d/db conn)]
      (is (identical? before-1 before-2)
          "an unchanged connection must return the same immutable DB object")
      (is (not (identical? before-1 after-1))
          "a committed transaction must replace the immutable DB object")
      (is (identical? after-1 after-2)
          "the replacement remains stable until the next commit"))))
