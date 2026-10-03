(ns eacl.operator.subject-type-differential-test
  "DataScript run of the seeded subject-type differential."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest]]
            [datascript.core :as ds]
            [eacl.datascript.core :as datascript]
            [eacl.operator-engine.subject-type-differential :as differential]))

(defn- new-store [options]
  (let [conn (datascript/create-conn)]
    {:client (datascript/make-client conn options)
     :add-objects! (fn [ids]
                     (ds/transact! conn (mapv #(hash-map :eacl/id %) ids)))}))

(deftest reads-for-either-subject-type-follow-the-reference-test
  ;; Seed 2 lists a direct exclusion for a subject type its subtracted
  ;; relation does not declare (EACL-FORMAL-101). Fewer seeds under
  ;; JavaScript, whose DataScript run is several times slower; the JVM run
  ;; covers the rest.
  (doseq [seed #?(:clj (range 1 41) :cljs [2 3 5])]
    (differential/run-seed! {:new-store new-store} seed)))
