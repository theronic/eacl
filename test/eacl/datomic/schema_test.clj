(ns eacl.datomic.schema-test
  (:require [clojure.test :as t :refer (deftest testing is)]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures]
            [eacl.datomic.schema :as schema]
            [eacl.schema-fixtures :as schema-fixtures]))
;
;; OK, so what we want to happen here,
;; is take schema as a map,
;; or ideally in Spice syntax.
;; what would be optimal?
;; the quick fix is :db/ident. We can do that, but you'll have to return to it.
;
;; Why not just try and parse it and resolve references?
;

(deftest eacl-schema-comparison-tests
  (testing "we can calculate additions & retractions"
    ; note we do not care about shape here.
    (is (= {:relations   {:additions   #{:added}
                          :retractions #{:deleted}}
            :permissions {:additions   #{:added}
                          :retractions #{:deleted :also-deleted}}}
          (schema/compare-schema
            {:relations   [:deleted :retain]
             :permissions [:deleted :retain :also-deleted]}
            {:relations   [:retain :added]
             :permissions [:retain :added]})))))

(deftest eacl-datomic-schema-tests
  (with-mem-conn [conn schema/v6-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db (d/db conn)]
      (is (= 3 (schema/count-relationships-using-relation db {:eacl.relation/resource-type :server
                                                              :eacl.relation/relation-name :account
                                                              :eacl.relation/subject-type  :account})))

      (is (= 2 (schema/count-relationships-using-relation db {:eacl.relation/resource-type :account
                                                              :eacl.relation/relation-name :owner
                                                              :eacl.relation/subject-type  :user})))

      (testing "TODO: this should throw for invalid Relation, but returns zero for now"
        (is (= 0 (schema/count-relationships-using-relation db {:eacl.relation/resource-type :account
                                                                :eacl.relation/relation-name :owner
                                                                :eacl.relation/subject-type  :missing-type})))))))

(deftest eacl-datomic-schema-write-tests                   ; rename to recon
  (with-mem-conn [conn schema/v6-schema]

    (testing "initial schema is empty"
      (is (= {:relations   #{}
              :permissions #{}}
            (schema/read-schema (d/db conn)))))

    (testing "write empty schema should succeed against empty"
      ; should be deal with the parsing first?
      (is (schema/write-schema! conn
            {:relations   #{}
             :permissions #{}}))))

    ;(testing "writing empty schema against empty should work")
    ;(is (schema/write-schema! conn schema-fixtures/schema-empty))
    ;@(d/transact conn fixtures/base-fixtures)
    ;(let [db (d/db conn)]
    ;  (is (schema/write-schema! conn schema-fixtures/schema-empty))))


  #_(deftest eacl-datomic-schema-write-tests
      (with-mem-conn [conn schema/v6-schema]
        (testing "initial schema is empty"
          (is (= {:relations   #{}
                  :permissions #{}}
                (eacl/read-schema client))))

        (testing "write empty schema should succeed against empty"
          (eacl/write-schema! client schema-fixtures/schema-empty))

        (testing "simple schema should succeed"
          (eacl/write-schema! client schema-fixtures/schema-user+document))

        (testing "writing same simple schema should succeed with empty diff")

        (testing "adding a new Relation + Permission should succeed with non-zero diff")

        (testing "removing a Relation used by a Permission should fail")

        (testing "removing an unused Relation should work")

        (testing "removing a Relation that orphans Relationships should fail")

        (testing "retracting Relation & dependent Permission should succeed"))))

;(testing "empty schema should succeed"
;  (eacl/write-schema! client))
;
;(testing "simple schema should succeed")
;
;(testing "writing same simple schema should succeed with empty diff")
;
;(testing "adding a new Relation + Permission should succeed with non-zero diff")
;
;(testing "removing a Relation used by a Permission should fail")
;
;(testing "removing an unused Relation should work")
;
;(testing "removing a Relation that orphans Relationships should fail")
;
;(testing "retracting Relation & dependent Permission should succeed")
;
;@(d/transact conn fixtures/base-fixtures)
;(prn (schema/write-schema! conn fixtures/base-fixtures))))

