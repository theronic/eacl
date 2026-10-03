(ns eacl.datahike.wildcard-anchor-test
  "Datahike run of the wildcard anchor contract."
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.datahike.core :as datahike]
            [eacl.operator-engine.wildcard-anchor-contract :as contract]))

(defn- new-store [options]
  (let [conn (datahike/create-conn)]
    {:client (datahike/make-client conn options)
     :add-objects! (fn [ids]
                     (d/transact conn (mapv #(hash-map :eacl/id %) ids)))}))

(deftest listing-work-is-independent-of-the-platform-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-listing-work-is-independent-of-the-platform!
     new-store fixture 50 500)))

(deftest listing-succeeds-under-the-default-limits-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-listing-succeeds-under-the-default-limits! new-store fixture 1500)))

(deftest answers-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-answers! new-store fixture 23)))

(deftest a-typed-anchor-answers-for-every-subject-type-test
  (contract/assert-a-typed-anchor-answers-for-every-subject-type! new-store))
