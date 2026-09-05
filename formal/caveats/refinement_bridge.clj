(ns eacl.formal.caveats.refinement-bridge
  "Offline production/model comparisons; never part of authorization."
  (:require [clojure.test :refer [deftest is]]
            [eacl.caveats.values :as values]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.formal.caveats.model :as model]))

(defn actual-normalization [input]
  (try (qualifier/normalize input [["a" :bool]])
       (catch clojure.lang.ExceptionInfo e {:fault (:reason (ex-data e))})))

(deftest qualifier-normalization-refines-model
  (is (= (:bounds model/profile) values/limits))
  (doseq [caveat [nil 1 2 -1 "lookup" [:eacl/id "c"]]
          context [::absent nil {} {"a" true} {"a" false} "bad"]
          time [nil -62135596800000 0 253402300799999 -62135596800001 253402300800000 1.5]
          :let [input (cond-> {:caveat caveat :valid-until-ms time}
                        (not= ::absent context) (assoc :caveat-context context))]]
    (is (= (model/normalized-qualifier input) (actual-normalization input)))))

(deftest context-precedence-refines-model
  (doseq [request [nil true false] bound [nil true false]
          :let [r (if (nil? request) {} {"a" request})
                b (if (nil? bound) {} {"a" bound})]]
    (is (= (merge r b) (values/merge-context [["a" :bool]] r b)))))

(deftest generated-canonical-round-trips
  (let [random (java.util.Random. 214731)
        parameters [["flag" :bool] ["number" :int] ["text" :string] ["time" :timestamp]]
        strings ["" "\\n" "line\nvalue" "é" "😀" "￿" "\u0000"]]
    (dotimes [_ 1000]
      (let [context {"flag" (.nextBoolean random) "number" (- (.nextInt random 1000000) 500000)
                     "text" (nth strings (.nextInt random (count strings)))
                     "time" [:timestamp (- (.nextInt random 1000000) 500000)]}
            encoded (values/encode-context parameters context)]
        (is (= context (values/decode-context parameters encoded)))
        (is (= encoded (values/encode-context parameters (into (sorted-map) context))))))))
