(ns eacl.relationships.endpoint-pair-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.relationships.endpoint-pair :as pair]))

(def cases
  [{:subject-type :user
    :subject-eid 1
    :relation-eid 2
    :resource-type :document
    :resource-eid 3}
   {:subject-type :team/member
    :subject-eid 10
    :relation-eid 11
    :resource-type :folder/item
    :resource-eid 12}
   {:subject-type :z
    :subject-eid 0
    :relation-eid 9007199254740991
    :resource-type :A/namespaced
    :resource-eid 42}])

(deftest endpoint-pair-round-trip-and-symmetry-test
  (doseq [{:keys [subject-type subject-eid relation-eid
                  resource-type resource-eid] :as expected}
          cases]
    (let [forward (pair/forward-value subject-type relation-eid
                                      resource-type resource-eid)
          reverse (pair/reverse-value resource-type relation-eid
                                      subject-type subject-eid)]
      (is (= expected (pair/decode-forward subject-eid forward)))
      (is (= expected (pair/decode-reverse resource-eid reverse)))
      (is (= {:direction :reverse
              :endpoint-eid resource-eid
              :value reverse}
             (select-keys (pair/peer-half :forward subject-eid forward)
                          [:direction :endpoint-eid :value])))
      (is (= {:direction :forward
              :endpoint-eid subject-eid
              :value forward}
             (select-keys (pair/peer-half :reverse resource-eid reverse)
                          [:direction :endpoint-eid :value])))
      (is (= [:forward subject-eid forward]
             (pair/half-identity :forward subject-eid forward)))
      (is (= [:reverse resource-eid reverse]
             (pair/half-identity :reverse resource-eid reverse))))))

(deftest endpoint-value-validation-test
  (let [valid [:user 1 :document 2]]
    (is (pair/endpoint-value? valid))
    (is (= pair/value-arity (count valid)))
    (doseq [malformed
            [nil
             '(:user 1 :document 2)
             [:user 1 :document]
             [:user 1 :document 2 :extra]
             ["user" 1 :document 2]
             [:user "1" :document 2]
             [:user -1 :document 2]
             [:user 1 "document" 2]
             [:user 1 :document -2]]]
      (is (not (pair/endpoint-value? malformed))
          (pr-str malformed))
      (is (nil? (pair/decode-forward 7 malformed)))
      (is (nil? (pair/decode-reverse 7 malformed))))))

(deftest exact-prefix-predicate-test
  (let [value [:user 11 :document 22]]
    (doseq [prefix [[] [:user] [:user 11]
                    [:user 11 :document]
                    [:user 11 :document 22]]]
      (is (pair/value-prefix? value prefix)))
    (testing "adjacent and oversized prefixes do not leak"
      (doseq [prefix [[:team]
                      [:user 10]
                      [:user 11 :folder]
                      [:user 11 :document 21]
                      [:user 11 :document 22 :extra]]]
        (is (not (pair/value-prefix? value prefix)))))
    (is (not (pair/value-prefix? [:user 11 :document] [:user])))))
