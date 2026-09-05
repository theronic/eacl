(ns eacl.formal.qualified.legacy-lookup-bridge
  "Exhaustive finite denotation and coordinate checks for native legacy plans."
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.engine.least-path-evidence-test :as fixture]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.formal.qualified.model-test :as contract]
            [eacl.formal.qualified.stable-route-bridge :as temporal]))

(defn oracle [permission subject resource a b time]
  (let [leaves {:a (bridge/model-value (temporal/active a 100 time))
                :b (bridge/model-value (temporal/active b 110 time))}]
    (reduce #(model/compose :union %1 %2) (model/value #{})
            (map (fn [path] (reduce #(model/compose :arrow %1 (leaves %2)) (model/value contract/universe) path))
                 (fixture/path-roles permission subject resource)))))

(deftest native-legacy-unions-refine-membership-order-and-temporal-evidence
  (let [env (fixture/fixture)
        inputs (remove evidence/fault? (take-nth 3 (bridge/inputs)))]
    (doseq [permission [:direct :delegated :via :inherited] traversal [:forward :reverse]
            width [1 2] a inputs b inputs]
      (let [a (temporal/active a 100 99) b (temporal/active b 110 99)
            asc (fixture/evaluate env permission traversal :asc width a b)
            desc (fixture/evaluate env permission traversal :desc width a b)
            names (if (= traversal :forward) ["d0" "d1" "d2"] ["u0" "u1"])
            at-time (fn [time]
                      (into {} (map (fn [name]
                                      [(get-in env [:ids name])
                                       (if (= traversal :forward)
                                         (oracle permission "u0" name a b time)
                                         (oracle permission name "d1" a b time))])) names))
            expected (into {} (remove (comp empty? :worlds val)) (at-time 99))]
        (is (= expected (into {} (map (juxt :value (comp bridge/model-value :evidence))) asc)))
        (is (= (count expected) (count asc)))
        (is (= (mapv #(select-keys % [:value :coords]) (reverse asc))
               (mapv #(select-keys % [:value :coords]) desc)))
        (doseq [time [100 109 110 120]]
          (let [expected (at-time time)]
            (is (every? (fn [{:keys [value evidence]}]
                          (or (not (evidence/reusable? evidence 99 time))
                              (= (get expected value) (bridge/model-value evidence))))
                        (concat asc desc)))))))))
