(ns eacl.datomic.schema-test
  "WIP."
  (:require [clojure.test :as t :refer (deftest testing is)]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
<<<<<<< Updated upstream
            [eacl.datomic.fixtures :as fixtures]))
=======
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

(deftest eacl-schema-stable-ident-tests
  (with-mem-conn [conn schema/v6-schema]
    (testing "we can transact Realtions & Permissions twice without datom conflicts after introduction of :eacl/id for Relation & Permission."
      (is @(d/transact conn fixtures/relations+permissions))
      (is @(d/transact conn fixtures/relations+permissions))
      (is (schema/read-schema (d/db conn))))))

(deftest eacl-schema-comparison-tests
  (testing "we can calculate additions & retractions"
    ; note we do not care about shape of set elements here.
    (is (= {:additions   #{:added}
            :unchanged   #{:retained}
            :retractions #{:deleted}}
          (schema/calc-set-deltas
            #{:deleted :retained}
            #{:retained :added})))

    (is (= {:relations   {:additions   #{:added}
                          :unchanged   #{:retained}
                          :retractions #{:deleted}}
            :permissions {:additions   #{:added}
                          :unchanged   #{:retained}
                          :retractions #{:deleted :also-deleted}}}
          (schema/compare-schema
            {:relations   [:deleted :retained]
             :permissions [:deleted :retained :also-deleted]}
            {:relations   [:retained :added]
             :permissions [:retained :added]})))))
>>>>>>> Stashed changes

(deftest eacl-datomic-schema-tests
  (with-mem-conn [conn schema/v6-schema]
    @(d/transact conn fixtures/base-fixtures)
    (prn (schema/write-schema! conn fixtures/base-fixtures))))

