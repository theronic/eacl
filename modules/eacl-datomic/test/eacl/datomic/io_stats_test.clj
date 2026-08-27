(ns eacl.datomic.io-stats-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.io-stats :as io-stats]
            [eacl.datomic.schema :as schema]
            [eacl.metrics :as metrics]))

(deftest datomic-io-stats-remain-physical-telemetry-test
  (with-mem-conn [conn schema/v7-schema]
    @(d/transact conn [{:eacl/id "io-stats-object"}])
    (let [db (d/db conn)
          eid (d/entid db [:eacl/id "io-stats-object"])
          ordinary (io-stats/pull db [:eacl/id] eid :test/pull)
          store (metrics/make-store)
          context {:backend :datomic
                   :source-id "io-stats-source"
                   :branch nil
                   :source-lifecycle "io-stats-lifecycle"
                   :high-watermark (d/basis-t db)}
          observed
          (binding [metrics/*store* store
                    metrics/*context* context]
            (io-stats/pull db [:eacl/id] eid :test/pull))
          event (first (vals (:io-events @store)))]
      (is (= ordinary observed {:eacl/id "io-stats-object"}))
      (is (= 1 (:io-event-count (metrics/stats store))))
      (is (= :physical-cost-only (:classification event)))
      (testing "I/O telemetry does not invent a relationship count"
        (is (= 0 (:entry-count (metrics/stats store))))))))
