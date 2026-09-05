(ns eacl.relationships.endpoint-pair-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.storage :as storage]))

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

(deftest storage-contract-test
  (is (= 9 storage/version))
  (is (= 5 pair/value-arity storage/value-arity))
  (is (= 4 storage/identity-arity))
  (is (= :qualified storage/qualifier-capability))
  (is (= [:db.type/keyword :db.type/ref :db.type/keyword :db.type/ref :db.type/ref]
         storage/tuple-types))
  (is (= :eacl.v9.relationship/subject-type+relation+resource-type+resource+qualifier
         storage/forward-attribute))
  (is (= :eacl.v9.relationship/resource-type+relation+subject-type+subject+qualifier
         storage/reverse-attribute)))

(deftest qualifier-round-trip-and-serving-boundary-test
  (let [forward (pair/forward-value :user 2 :document 3 99)
        reverse (pair/reverse-value :document 2 :user 1 99)]
    (is (pair/endpoint-value? forward))
    (is (= reverse (:value (pair/peer-half :forward 1 forward))))
    (is (= 99 (:qualifier-eid (pair/decode-forward 1 forward))))
    (is (= [:user 2 :document 3] (pair/identity-prefix forward)))
    (is (= [[:db/retract 1 storage/forward-attribute forward]
            [:db/retract 3 storage/reverse-attribute reverse]]
           (pair/retractions :user 1 2 :document 3 99)))
    (is (= :eacl/unsupported-qualifier
           (try (pair/assert-supported! forward)
                (catch #?(:clj Exception :cljs :default) e (:type (ex-data e))))))
    (is (= :duplicate-identity
           (try (first (pair/checked-datoms
                        [{:e 1 :v (pair/forward-value :user 2 :document 3)}
                         {:e 1 :v forward}]))
                (catch #?(:clj Exception :cljs :default) e (:reason (ex-data e))))))))

(deftest endpoint-pair-round-trip-and-symmetry-test
  (doseq [{:keys [subject-type subject-eid relation-eid
                  resource-type resource-eid] :as expected}
          cases]
    (let [forward (pair/forward-value subject-type relation-eid
                                      resource-type resource-eid)
          reverse (pair/reverse-value resource-type relation-eid
                                      subject-type subject-eid)]
      (is (= (assoc expected :qualifier-eid nil) (pair/decode-forward subject-eid forward)))
      (is (= (assoc expected :qualifier-eid nil) (pair/decode-reverse resource-eid reverse)))
      (is (= {:direction :reverse
              :endpoint-eid resource-eid
              :value reverse}
             (select-keys (pair/peer-half :forward subject-eid forward)
                          [:direction :endpoint-eid :value])))
      (is (= {:direction :forward
              :endpoint-eid subject-eid
              :value forward}
             (select-keys (pair/peer-half :reverse resource-eid reverse)
                          [:direction :endpoint-eid :value]))))))

(deftest endpoint-value-validation-test
  (let [valid [:user 1 :document 2 nil]]
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
  (let [value [:user 11 :document 22 nil]]
    (doseq [prefix [[] [:user] [:user 11]
                    [:user 11 :document]
                    [:user 11 :document 22 nil]]]
      (is (pair/value-prefix? value prefix)))
    (testing "adjacent and oversized prefixes do not leak"
      (doseq [prefix [[:team]
                      [:user 10]
                      [:user 11 :folder]
                      [:user 11 :document 21]
                      [:user 11 :document 22 :extra]]]
        (is (not (pair/value-prefix? value prefix)))))
    (is (not (pair/value-prefix? [:user 11 :document] [:user])))))
