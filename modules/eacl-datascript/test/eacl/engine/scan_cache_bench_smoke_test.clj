(ns eacl.engine.scan-cache-bench-smoke-test
  "Keeps the paired scan-cache harness executable in ordinary CI: one small
  sparse fixture on DataScript, one trial, and the shape and elision the
  gate reads."
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.datascript.core :as datascript]
            [eacl.scan-cache-bench :as bench]
            [eacl.scan-cache-fixture :as fixture]))

(deftest paired-harness-runs-and-reports-elision-test
  (let [config {:users 24 :groups 40 :groups-per-user 6 :seed 5}
        conn (datascript/create-conn)
        seeded (datascript/make-client conn {})
        _ (fixture/seed! seeded config
                         (fn [ids] (ds/transact! conn (mapv (fn [id] {:eacl/id id}) ids))))
        result (bench/paired-run
                {:disabled-client (datascript/make-client conn {:scan-cache false})
                 :enabled-client (datascript/make-client conn {})
                 :users (:users config)
                 :page-size 5
                 :trials 1
                 :warm-ups 1
                 :cache-stats-fn datascript/cache-stats})]
    (is (pos? (get-in result [:disabled :us-per-page])))
    (is (pos? (get-in result [:disabled :commands-per-sweep])))
    (is (< (get-in result [:enabled :commands-per-sweep])
           (get-in result [:disabled :commands-per-sweep])))
    (is (<= 0.9 (:elision result) 1.0)
        "the sparse fixture elides at least nine in ten commands after warm-up")
    (is (pos? (get-in result [:scan-cache :hits])))))
