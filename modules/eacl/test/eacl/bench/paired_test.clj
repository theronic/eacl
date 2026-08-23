(ns eacl.bench.paired-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.bench.paired :as paired]))

(defn- advancing-reader
  [step]
  (let [value (atom 0)]
    #(swap! value + step)))

(deftest paired-arms-interleave-and-retain-raw-samples
  (let [order (atom [])
        report
        (paired/run-paired!
         {:arms
          [[:baseline (fn [iteration]
                        (swap! order conj [:baseline iteration])
                        iteration)]
           [:candidate (fn [iteration]
                         (swap! order conj [:candidate iteration])
                         iteration)]]
          :warmups 1
          :samples 2
          :nano-time (advancing-reader 1000)
          :allocated-bytes (advancing-reader 64)
          :environment
          {:os "TestOS" :architecture "test-arch" :java-version "26.0.2"}
          :comparisons
          [{:baseline :baseline
            :candidate :candidate
            :minimum-latency-reduction 0.0
            :minimum-allocation-reduction 0.0}]
          :absolute-ceilings
          {["TestOS" "test-arch" "26"]
           {:candidate {:latency-p50-us 1.0
                        :allocation-p50-bytes 64}}}})]
    (is (= [[:baseline 0] [:candidate 0]
            [:candidate 1] [:baseline 1]
            [:baseline 2] [:candidate 2]]
           @order))
    (is (= [1.0 1.0]
           (get-in report [:arms :baseline :latency-us :raw])))
    (is (= [64 64]
           (get-in report [:arms :candidate :allocated-bytes :raw])))
    (is (true? (get-in report [:comparisons 0 :passed?])))
    (is (= :applicable (get-in report [:absolute-ceilings :status])))
    (is (true? (get-in report [:absolute-ceilings :passed?])))))

(deftest absolute-ceilings-are-host-class-specific
  (let [report
        (paired/run-paired!
         {:arms [[:a identity] [:b identity]]
          :warmups 0
          :samples 1
          :nano-time (advancing-reader 1000)
          :allocated-bytes (constantly nil)
          :environment
          {:os "OtherOS" :architecture "other" :java-version "25"}
          :absolute-ceilings
          {["TestOS" "test-arch" "26"]
           {:a {:latency-p50-us 1.0}}}})]
    (is (= :not-applicable
           (get-in report [:absolute-ceilings :status])))
    (is (= [:os :architecture :java-major]
           (get-in report [:absolute-ceilings :mismatched-fields])))))

(deftest paired-run-shape-is-closed
  (testing "duplicate arm names are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"distinct"
         (paired/run-paired!
          {:arms [[:same identity] [:same identity]]
           :warmups 0
           :samples 1})))))
