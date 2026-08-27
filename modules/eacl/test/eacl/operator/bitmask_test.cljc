(ns eacl.operator.bitmask-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.operator.bitmask :as bitmask]))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest primitive-and-portable-word-boundaries-test
  (let [selected [0 31 32 63 64 127 128 254 255]
        mask (bitmask/from-indexes 256 selected)]
    (is (= 8 (bitmask/word-count 256)))
    (is (= selected (bitmask/indexes mask)))
    (is (= {:width 256
            :words [-2147483647 -2147483647 1 -2147483648
                    1 0 0 -1073741824]}
           (bitmask/portable mask)))
    (bitmask/clear-bit! mask 31)
    (is (false? (bitmask/bit-set? mask 31)))
    (is (true? (bitmask/bit-set? mask 32)))))

(deftest empty-and-closed-bounds-test
  (is (bitmask/empty? (bitmask/native 0)))
  (is (= :eacl.operator/invalid-mask
         (:type (error-data #(bitmask/native 257)))))
  (is (= :eacl.operator/invalid-mask
         (:type (error-data #(bitmask/set-bit! (bitmask/native 1) 1))))))
