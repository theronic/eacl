(ns eacl.datahike.recursive-intersection-differential-test
  "Datahike run of the randomized recursive-operator differential."
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.datahike.core :as datahike]
            [eacl.operator-engine.recursive-intersection-differential :as differential]))

(defn- new-store [now]
  (let [conn (datahike/create-conn)]
    {:client (datahike/make-client conn {:clock #(deref now)})
     :add-objects! (fn [ids]
                     (d/transact conn (mapv #(hash-map :eacl/id %) ids)))}))

(deftest recursive-operators-equal-set-algebra-over-their-operands-test
  (doseq [seed (range 101 109)]
    (differential/run-seed! {:new-store new-store} seed)))
