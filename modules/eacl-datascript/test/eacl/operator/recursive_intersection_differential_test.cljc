(ns eacl.operator.recursive-intersection-differential-test
  "DataScript run of the randomized recursive-operator differential."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest]]
            [datascript.core :as ds]
            [eacl.datascript.core :as datascript]
            [eacl.operator-engine.recursive-intersection-differential :as differential]))

(defn- new-store [now]
  (let [conn (datascript/create-conn)]
    {:client (datascript/make-client conn {:clock #(deref now)})
     :add-objects! (fn [ids]
                     (ds/transact! conn (mapv #(hash-map :eacl/id %) ids)))}))

(deftest recursive-operators-equal-set-algebra-over-their-operands-test
  ;; Fewer seeds under JavaScript, whose DataScript run is several times
  ;; slower; the JVM run covers the rest.
  (doseq [seed (range 1 #?(:clj 9 :cljs 4))]
    (differential/run-seed! {:new-store new-store} seed)))
