(ns eacl.bench.qualifier-cleanup-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [eacl.datomic.qualifier-storage-test :as datomic]
            [eacl.datascript.qualifier-storage-test :as datascript]
            [eacl.datahike.qualifier-storage-test :as datahike]
            [eacl.relationships.qualifier-integrity :as integrity]
            [eacl.relationships.qualifier-sweep-contract :as contract])
  (:import [java.lang.management ManagementFactory]
           [com.sun.management ThreadMXBean]))

(deftest ^:benchmark cleanup-sweep-native-resource-budgets
  (let [budget (edn/read-string (slurp "formal/baselines/qualifier-cleanup-sweep.edn"))
        bean ^ThreadMXBean (ManagementFactory/getThreadMXBean)
        tid (.threadId (Thread/currentThread))
        samples (atom [])]
    (doseq [[backend with-fixture] [[:datomic datomic/with-sweep-fixture]
                                   [:datascript datascript/with-sweep-fixture]
                                   [:datahike datahike/with-sweep-fixture]]
            q (:orphans budget)]
      (with-fixture
        (fn [fixture]
          (let [fixture (contract/seed! fixture q (:active budget))
                counts (atom {})
                writer (contract/traced-writer (:writer fixture) counts)
                allocated-before (.getThreadAllocatedBytes bean tid)
                start (System/nanoTime)
                result (integrity/cleanup-sweep! writer {:batch-size (:batch-size budget)})
                elapsed-ms (/ (- (System/nanoTime) start) 1e6)
                allocated (- (.getThreadAllocatedBytes bean tid) allocated-before)]
            (is (= :complete (:status result)))
            (is (= q (count (:qualifiers result))))
            (is (= (:global-streams budget) (:streams @counts)))
            (is (< elapsed-ms (:max-elapsed-ms budget)))
            (is (<= 0 allocated (:max-caller-allocated-bytes budget)))
            (swap! samples conj {:backend backend :orphans q :active (:active budget)
                                 :elapsed-ms elapsed-ms :caller-allocated-bytes allocated :work @counts})))))
    (io/make-parents "target/benchmarks/adversarial-review/cleanup-sweep.edn")
    (spit "target/benchmarks/adversarial-review/cleanup-sweep.edn"
          (pr-str {:java (System/getProperty "java.version") :os (System/getProperty "os.name")
                   :arch (System/getProperty "os.arch") :samples @samples}))))
