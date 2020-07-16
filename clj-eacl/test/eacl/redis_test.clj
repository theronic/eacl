(ns eacl.redis-test
  (:require [taoensso.carmine :as car]
            [clojure.test :as t :refer (deftest is)]))

;; todo move
(def redis-spec {:pool {} :spec {:host "localhost"}})
(defmacro wcar* [& body] `(car/wcar redis-spec ~@body))

(defn bit-get [key offset]
  (car/bitfield key "GET" "u1" offset))

(defn bit-set! [key offset v]
  (car/bitfield key "SET" "u1" offset v))

(defn bitpos-bisect [key v max-len]
  (loop [start 0
         acc   #{}]
    (let [offset (wcar* (car/bitpos key v start))]
      (if (or (neg? offset) (>= start max-len))
        acc
        (recur (inc offset) (conj acc offset))))))

(defn bitop [op & args]
  ;; todo spec
  (apply car/bitop op args))

(defn bit-and [& args]
  (apply car/bitop "AND" args))

(defn bit-xor [& args]
  (apply car/bitop "XOR" args))

(deftest redis-test
  (wcar*
    (bitop "AND" "cap-temp" "a-page" "user-b")
    (bitop "XOR" "cap-temp" "a-page" "cap-temp")
    (car/bitcount "cap-temp"))
  (is (= 1 2)))
