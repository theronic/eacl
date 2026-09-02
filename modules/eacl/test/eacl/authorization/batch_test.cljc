(ns eacl.authorization.batch-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.authorization.batch :as batch]))

(def valid-demand
  {:subject {:type :user :id "u1"}
   :permission :view
   :resource {:type :document :id "d1"}})

(defn- caught-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest client-and-request-aggregate-limits-are-closed-test
  (let [configured
        (batch/normalize-client-limits {:max-batch-size 8})
        normalized
        (batch/validate-request!
         {:checks [valid-demand]
          :aggregate-limits {:max-commands 7}}
         configured)]
    (is (= 8 (get-in normalized [:aggregate-limits :max-batch-size])))
    (is (= 7 (get-in normalized [:aggregate-limits :max-commands])))
    (is (pos? (get-in normalized
                      [:aggregate-limits :max-allocation-proxy]))))
  (doseq [limits [false
                  {:unknown 1}
                  {:max-publication-attempts 1}
                  {:max-batch-size 0}
                  {:max-commands 1.5}]]
    (is (= :eacl/invalid-config
           (:type
            (caught-data #(batch/normalize-client-limits limits))))))
  (let [configured
        (batch/normalize-client-limits
         {:max-batch-size 2 :max-commands 10})]
    (is (= :aggregate-limit-weakening
           (:reason
            (caught-data
             #(batch/validate-request!
               {:checks [] :aggregate-limits {:max-commands 11}}
               configured)))))
    (is (= :batch-size
           (:limit-kind
            (caught-data
             #(batch/validate-request!
               {:checks [valid-demand valid-demand valid-demand]}
               configured)))))))

(deftest complete-batch-shape-is-validated-before-execution-test
  (let [configured (batch/normalize-client-limits nil)
        invalid
        [[nil :malformed-request nil]
         [{} :malformed-request nil]
         [{:checks ()} :malformed-request nil]
         [{:checks [] :surprise true} :unknown-request-key nil]
         [{:checks [valid-demand
                    (assoc valid-demand :timeout-ms 1)]}
          :per-demand-control 1]
         [{:checks [valid-demand
                    (assoc valid-demand :surprise true)]}
          :unknown-demand-key 1]
         [{:checks [valid-demand
                    (dissoc valid-demand :permission)]}
          :malformed-demand 1]
         [{:checks [valid-demand
                    (assoc valid-demand :permission "view")]}
          :malformed-demand 1]
         [{:checks [valid-demand
                    (assoc valid-demand :subject :u1)]}
          :malformed-demand 1]
         [{:checks [valid-demand
                    (assoc-in valid-demand [:resource :extra] true)]}
          :unknown-demand-key 1]]]
    (doseq [[request reason demand-index] invalid]
      (testing (pr-str request)
        (let [data
              (caught-data
               #(batch/validate-request! request configured))]
          (is (= :eacl.batch/invalid-request (:type data)))
          (is (= reason (:reason data)))
          (is (= demand-index (:demand-index data))))))))

(deftest aggregate-limit-errors-name-the-demand-and-safe-counters-test
  (let [limits
        (batch/normalize-client-limits
         {:max-commands 2})
        counters
        {:commands 3
         :transitions 1
         :fetched-values 2
         :candidates-examined 0
         :probes 0
         :output-units 1
         :allocation-proxy 7}
        data
        (caught-data
         #(batch/check-aggregate-limits! limits counters 4))]
    (is (= :eacl.execution/resource-limit-exceeded (:type data)))
    (is (= :commands (:limit-kind data)))
    (is (= 4 (:demand-index data)))
    (is (= counters (:aggregate-counters data)))))
