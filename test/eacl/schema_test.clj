(ns eacl.schema-test
  (:require
    [clojure.test :as t :refer [deftest testing is]]
    [eacl.core :as eacl]
    [eacl.datomic.impl]
    [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
    [eacl.datomic.core :as eacl.datomic]
    [eacl.datomic.schema :as schema]
    [eacl.schema-fixtures :as schema-fixtures]))

;(deftest schema-recon-tests
;  (with-mem-conn [conn schema/v6-schema]
;    (let [client (eacl.datomic/make-client conn
;                   {:entity->object-id (fn [ent] (:db/ident ent))
;                    :object-id->ident  (fn [obj-id] [:db/ident obj-id])})]
;
;      (testing "initial schema is empty"
;        (is (= {:relations #{}
;                :permissions #{}}
;              (eacl/read-schema client))))
;
;      (testing "write empty schema should succeed against empty"
;        (eacl/write-schema! client schema-fixtures/schema-empty))
;
;      (testing "simple schema should succeed"
;        (eacl/write-schema! client schema-fixtures/schema-user+document)))
;
;    (testing "writing same simple schema should succeed with empty diff")
;
;    (testing "adding a new Relation + Permission should succeed with non-zero diff")
;
;    (testing "removing a Relation used by a Permission should fail")
;
;    (testing "removing an unused Relation should work")
;
;    (testing "removing a Relation that orphans Relationships should fail")
;
;    (testing "retracting Relation & dependent Permission should succeed")))