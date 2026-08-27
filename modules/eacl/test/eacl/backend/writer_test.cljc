(ns eacl.backend.writer-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.writer :as writer]))

(defn- operations
  []
  (into {}
        (map (fn [operation] [operation (constantly nil)]))
        writer/required-operations))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest writer-role-is-certified-at-construction
  (is (= writer/required-operations
         (set (keys writer/writer-obligations))))
  (let [role (writer/make-writer
              {:id :test
               :state {}
               :operations (operations)
               :max-attempts 3
               :max-transaction-size 100})]
    (is (writer/writer? role))
    (is (= :test (writer/backend-id role)))
    (is (= 3 (writer/max-attempts role)))
    (is (= 100 (writer/max-transaction-size role)))
    (is (ifn? (writer/operation role :transact!)))))

(deftest missing-writer-operation-fails-before-use
  (let [data (error-data
              #(writer/make-writer
                {:id :test
                 :state {}
                 :operations (dissoc (operations) :transact!)}))]
    (is (= :eacl/invalid-backend-role (:type data)))
    (is (= (:type data) (:eacl/error data)))
    (is (= :writer (:role data)))
    (is (= :transact! (:operation data)))
    (is (= [:transact!] (:missing-operations data)))))

(deftest writer-bounds-are-certified-at-construction
  (doseq [[field value]
          [[:max-attempts 0]
           [:max-transaction-size 0]
           [:max-transaction-size 9007199254740992]]]
    (testing (name field)
      (let [data (error-data
                  #(writer/make-writer
                    (merge {:id :test
                            :state {}
                            :operations (operations)}
                           {field value})))]
        (is (= :eacl/invalid-backend-role (:type data)))
        (is (= :writer (:role data)))))))
