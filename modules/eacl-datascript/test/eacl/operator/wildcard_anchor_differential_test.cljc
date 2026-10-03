(ns eacl.operator.wildcard-anchor-differential-test
  "DataScript run of the seeded wildcard anchor differential."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest]]
            [datascript.core :as ds]
            [eacl.datascript.core :as datascript]
            [eacl.operator-engine.wildcard-anchor-differential :as differential]))

(defn- new-store [options]
  (let [conn (datascript/create-conn)]
    {:client (datascript/make-client conn options)
     :add-objects! (fn [ids]
                     (ds/transact! conn (mapv #(hash-map :eacl/id %) ids)))}))

(deftest wildcard-intersections-follow-the-reference-test
  ;; Fewer seeds under JavaScript, whose DataScript run is several times
  ;; slower; the JVM run covers the rest.
  (doseq [seed (range 1 #?(:clj 13 :cljs 4))]
    (differential/run-seed! {:new-store new-store} seed)))
