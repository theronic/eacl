(ns eacl.authorization.context-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.context :as context]
            [eacl.caveats.values :as values]))

(defn error-data [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error (ex-data error))))

(deftest whole-context-is-canonical-and-independent-of-one-parameter-set
  (let [input {"flag" true "count" 7 "at" [:timestamp 99]
               "names" ["Zoë" "Alice"] "roles" {"admin" false} "empty" []}
        prepared (context/prepare input)
        reversed (context/prepare (into (sorted-map-by #(compare %2 %1)) input))]
    (is (= input (context/value prepared)))
    (is (= (context/identity prepared) (context/identity reversed)))
    (is (= {"flag" true} (context/project prepared [["flag" :bool] ["missing" :int]])))
    (is (not= (context/identity prepared)
              (context/identity (context/prepare (assoc input "unused" false))))))
  (let [input (into {} (map (fn [n] [(str "field" n) true]) (range 64)))]
    (is (= input (context/value (context/prepare input)))))
  (is (identical? (context/prepare {}) (context/prepare {}))))

(def invalid-contexts
  [nil
   []
   {"unused" nil}
   {:unused true}
   {"true" true}
   {"unused" 1.5}
   {"unused" 9007199254740992}
   {"unused" #{1}}
   {"unused" '(1 2)}
   {"unused" [true 1]}
   {"unused" {1 true}}
   {"unused" {"nested" [1]}}
   {"unused" [:timestamp 253402300800000]}
   {"unused" (apply str (repeat 4097 "a"))}
   {"unused" (apply str (repeat 2049 "é"))}
   {"unused" (vec (range 129))}
   (into {} (map (fn [n] [(str "field" n) (vec (range 128))]) (range 8)))
   (into {} (map (fn [n] [(str "field" n) (apply str (repeat 4096 "a"))]) (range 4)))])

(deftest invalid-unused-fields-cannot-evade-request-admission
  (doseq [input invalid-contexts]
    (let [data (error-data #(context/prepare input))]
      (is (= :eacl.caveat/invalid (:type data)))
      (is (not (contains? data :value)))))
  (is (= :unknown-parameter
         (:reason (error-data #(values/merge-context [["flag" :bool]] {"unused" true} {})))))
  (is (= :unknown-parameter
         (:reason (error-data #(values/merge-context [["flag" :bool]] {} {"unused" true}))))))
