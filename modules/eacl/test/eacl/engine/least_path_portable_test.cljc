(ns eacl.engine.least-path-portable-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
            :refer [deftest is]]
            [eacl.backend.v8 :as backend]
            [eacl.engine.least-path :as least-path]
            [eacl.engine.v8 :as engine]))

(deftest descriptor-limit-crosses-the-direct-adapter-boundary-test
  (let [calls (atom [])
        fetch (least-path/adapter-fetch-fn :test-adapter)]
    (with-redefs [backend/invoke
                  (fn [_ operation & args]
                    (swap! calls conj [operation args])
                    [10 11 12])]
      (is (= [10 11 12]
             (fetch {:operation :subject->resources
                     :subject-type :user :subject-eid 1
                     :relation-eid 2 :resource-type :document
                     :direction :desc :bound-eid 20 :limit 3})))
      (is (= {:direction :desc
              :bound-eid 20
              :inclusive-bound? false
              :limit 3}
             (last (second (first @calls))))))))

(deftest routed-bounded-vector-is-not-recopied-test
  (let [routed [10 11 12]
        context (least-path/make-context
                 {:fetch-fn (constantly routed)
                  :physical-chunk-size 3
                  :max-commands 4
                  :max-values 4})
        fetched ((:fetch! context)
                 {:operation :subject->resources
                  :subject-type :user :subject-eid 1
                  :relation-eid 2 :resource-type :document
                  :direction :asc :bound-eid nil :limit 3})]
    (is (identical? routed fetched))
    (is (= {:commands 1 :fetched-values 3
            :stream-opens 1 :emissions 0}
           @(:counters context)))))

(deftest stable-demand-minima-saturate-at-the-exact-integer-bound-test
  (let [maximum backend/maximum-exact-integer]
    (binding [engine/*recursive-traversal-limits*
              {:max-derived-grants maximum
               :max-advanced-datoms maximum
               :max-queued-work maximum}]
      (is (= {:max-admissions maximum
              :max-values maximum
              :max-stack maximum
              :max-transitions maximum
              :max-commands maximum}
             (engine/stable-limits))))
    (binding [engine/*recursive-traversal-limits*
              {:max-derived-grants 2000000
               :max-advanced-datoms 3000000}]
      (is (= 5000000 (:max-commands (engine/stable-limits))))
      (is (= 20000000 (:max-transitions (engine/stable-limits)))))))
