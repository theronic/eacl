(ns eacl.relationships.edge-test
  (:require [eacl.relationships.edge :as edge]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest sparse-values-preserve-the-ordered-identity-stream
  (doseq [density [0 5 10 100]]
    (let [ids (vec (range 100))
          qids (mapv #(when (< % density) (+ 1000 %)) ids)
          rows (mapv (fn [eid qid] {:v [:user 1 :document eid qid]}) ids qids)
          values (mapv edge/from-datom rows)]
      (is (= ids (mapv edge/endpoint values)))
      (is (= qids (mapv edge/qualifier-id values)))
      (is (= density (count (filter vector? values))))
      (is (every? edge/valid? values))
      (is (= (reverse ids) (map edge/endpoint (reverse values)))))))

(deftest malformed-compact-values-cannot-alias-an-ordinary-edge
  (doseq [value [nil false -1 1.5 [] [1] [1 nil] [1 0] [1 -2] [1 "qid"] [1 2 3] {:eid 1}]]
    (is (not (edge/valid? value)))))
