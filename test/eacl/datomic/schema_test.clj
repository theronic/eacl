(ns eacl.datomic.schema-test
  (:require [clojure.test :as t :refer (deftest testing is)]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.fixtures :as fixtures]))

(deftest eacl-datomic-schema-tests
  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (prn (schema/write-schema! conn fixtures/base-fixtures))))

