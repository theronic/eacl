(ns eacl.operator.batch-schedule-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.operator.batch-schedule :as schedule]))

(deftest demand-sized-growth-and-window-boundary-test
  (let [initial (schedule/initial 21 1000)
        second (schedule/advance initial 21 1)
        third (schedule/advance second 42 0)
        fourth (schedule/advance third 84 20)]
    (is (= 21 (:next-width initial)))
    (is (= 42 (:next-width second)))
    (is (= 84 (:next-width third)))
    (is (schedule/done? fourth))
    (is (= 147 (:examined fourth)))
    (is (= 21 (:accepted fourth))))
  (is (= 0 (:next-width (schedule/initial 0 1000))))
  (is (= 7 (:next-width (schedule/initial 20 7))))
  (is (= 256 (:next-width (schedule/initial 1000 1000)))))

(deftest low-selectivity-grows-only-to-cap-and-stops-at-window-test
  (loop [state (schedule/initial 20 700)
         widths []]
    (if (schedule/done? state)
      (is (= [20 40 80 160 256 144] widths))
      (recur (schedule/advance state (:next-width state) 0)
             (conj widths (:next-width state))))))
