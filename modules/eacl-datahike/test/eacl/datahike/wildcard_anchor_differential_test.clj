(ns eacl.datahike.wildcard-anchor-differential-test
  "Datahike run of the seeded wildcard anchor differential."
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.datahike.core :as datahike]
            [eacl.operator-engine.wildcard-anchor-differential :as differential]))

(defn- new-store [options]
  (let [conn (datahike/create-conn)]
    {:client (datahike/make-client conn options)
     :add-objects! (fn [ids]
                     (d/transact conn (mapv #(hash-map :eacl/id %) ids)))}))

(deftest wildcard-intersections-follow-the-reference-test
  (doseq [seed (range 101 107)]
    (differential/run-seed! {:new-store new-store} seed)))
