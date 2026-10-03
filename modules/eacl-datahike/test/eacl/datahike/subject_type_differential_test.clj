(ns eacl.datahike.subject-type-differential-test
  "Datahike run of the seeded subject-type differential."
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.datahike.core :as datahike]
            [eacl.operator-engine.subject-type-differential :as differential]))

(defn- new-store [options]
  (let [conn (datahike/create-conn)]
    {:client (datahike/make-client conn options)
     :add-objects! (fn [ids]
                     (d/transact conn (mapv #(hash-map :eacl/id %) ids)))}))

(deftest reads-for-either-subject-type-follow-the-reference-test
  (doseq [seed (range 201 221)]
    (differential/run-seed! {:new-store new-store} seed)))
