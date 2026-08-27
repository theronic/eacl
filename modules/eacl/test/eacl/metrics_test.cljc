(ns eacl.metrics-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.metrics :as metrics]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]))

(def expression-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     permission view = reader & writer
   }")

(deftest structural-metrics-are-generation-cached-not-persisted-test
  (let [entity (-> expression-schema
                   resolver/validate-schema
                   persistence/candidate-schema
                   :permissions first)
        cache (atom {})]
    (is (not-any? #(contains? entity %)
                  persistence/retired-derived-metric-attributes))
    (binding [persistence/*structural-cache* cache]
      (let [first-result (persistence/decode-entity-with-metadata entity)
            second-result
            (persistence/decode-entity-with-metadata
             (assoc entity :eacl.permission/source-node-count 999999))]
        (is (= first-result second-result))
        (is (= 1 (count @cache)))
        (is (pos? (get-in first-result
                          [:metadata :normalized-metrics :node-count])))))))

(deftest relationship-observations-are-high-watermark-scoped-test
  (let [store (metrics/make-store)
        descriptor {:relation-eid 7 :endpoint [:user 11]}
        context {:backend :test
                 :source-id :db
                 :branch :main
                 :source-lifecycle :lifecycle
                 :high-watermark 41}
        advanced (assoc context :high-watermark 42)]
    (metrics/record-membership!
     store context descriptor :forward
     [[:document 1] [:document 2] [:document 3]]
     [true false true] nil)
    (is (= {:completeness :sample
            :sample-count 3
            :match-count 2
            :observed-lower-bound 2
            :last-io nil}
           (metrics/lookup store context descriptor :forward)))
    (is (nil? (metrics/lookup store advanced descriptor :forward)))
    (testing "a sample is never promoted to an invented exact cardinality"
      (is (= 0 (:exact-entry-count (metrics/stats store)))))
    (metrics/record-exhausted! store context descriptor :forward 9 nil)
    (is (= 9 (:exact-count
              (metrics/lookup store context descriptor :forward))))
    (metrics/refresh! store)
    (is (= 0 (:entry-count (metrics/stats store))))))

(deftest scan-observations-publish-exact-count-only-on-proven-exhaustion-test
  (let [store (metrics/make-store)
        context {:backend :test
                 :source-id :db
                 :branch :main
                 :source-lifecycle :lifecycle
                 :high-watermark 9}
        descriptor {:operation :subject->resources
                    :subject-type :user
                    :subject-eid 1
                    :relation-eid 2
                    :resource-type :document
                    :bound-eid nil
                    :direction :asc
                    :limit 4}
        normalized (dissoc descriptor :limit :bound-eid :direction)]
    (metrics/record-scan! store context descriptor [3 4 5 6])
    (is (= :sample
           (:completeness
            (metrics/lookup store context normalized :asc))))
    (metrics/record-scan! store context descriptor [3 4 5])
    (is (= {:completeness :exact
            :exact-count 3
            :observed-lower-bound 3
            :last-io nil}
           (metrics/lookup store context normalized :asc)))
    (testing "a later bounded probe cannot downgrade an exact observation"
      (metrics/record-scan! store context descriptor [3 4 5 6])
      (is (= :exact
             (:completeness
              (metrics/lookup store context normalized :asc)))))))

(deftest forced-count-observation-is-exact-only-when-untruncated-test
  (let [store (metrics/make-store)
        context {:backend :test
                 :source-id :db
                 :branch :main
                 :source-lifecycle :lifecycle
                 :high-watermark 12}
        descriptor {:operation :count-resources
                    :subject [:user 1]
                    :resource/type :document
                    :permission :view}]
    (metrics/record-count!
     store context descriptor :forward
     {:count 100 :limit 100 :truncated? true})
    (is (= :sample
           (:completeness
            (metrics/lookup store context descriptor :forward))))
    (is (= 100
           (:observed-lower-bound
            (metrics/lookup store context descriptor :forward))))
    (metrics/record-count!
     store context descriptor :forward
     {:count 137 :limit -1})
    (is (= {:completeness :exact
            :exact-count 137
            :observed-lower-bound 137
            :last-io nil}
           (metrics/lookup store context descriptor :forward)))))

(deftest relationship-observation-cap-evicts-without-growing-test
  (let [store (metrics/make-store)
        context {:backend :test
                 :source-id :db
                 :branch :main
                 :source-lifecycle :lifecycle
                 :high-watermark 12}]
    (with-redefs [metrics/maximum-entries 8]
      (dotimes [index 20]
        (metrics/record-exhausted!
         store context {:relation-eid index} :forward index nil)))
    (let [stats (metrics/stats store)]
      (is (= 8 (:entry-count stats)))
      (is (= 12 (:evictions stats)))
      (is (= 20 (:recorded-events stats))))))
