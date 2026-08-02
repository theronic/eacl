(ns eacl.formal.java-round-trip-test
  (:require
   [clojure.test :refer [deftest is]]
   [eacl.formal.java-round-trip :as round-trip]))

(deftest generated-java-value-collection-and-error-round-trip
  (is (= {:status :accepted
          :values [0N 7N 42N]}
         (round-trip/round-trip
          "eacl.round-trip/v1"
          [0 7 42]
          3)))
  (is (= :rejected
         (:status
          (round-trip/round-trip
           "unknown"
           [1]
           1))))
  (is (= :rejected
         (:status
          (round-trip/round-trip
           "eacl.round-trip/v1"
           [1 -1]
           2))))
  (is (= :rejected
         (:status
          (round-trip/round-trip
           "eacl.round-trip/v1"
           [1 2]
           1)))))
