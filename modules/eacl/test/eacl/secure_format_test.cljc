(ns eacl.secure-format-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.causal-token :as token]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]))

(def old-key (vec (range 32)))
(def current-key (vec (range 32 64)))
(def options
  {:current-kid :current
   :keyring {:old old-key
             :current current-key}})

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(defn- tamper
  [value]
  (let [replacement (if (= "A" (subs value 0 1)) "B" "A")]
    (str replacement (subs value 1))))

(defn- tamper-authenticator
  [prefix token]
  (let [envelope
        (-> token
            (subs (count prefix))
            secure/b64url-decode
            secure/bytes->utf8
            secure/decode-canonical)
        envelope' (update envelope :tag tamper)]
    (str prefix
         (secure/b64url-encode
          (secure/utf8-bytes
           (secure/encode-canonical envelope'))))))

(deftest canonical-portable-format-test
  (is (= (secure/encode-canonical {:b #{3 2 1} :a [1 true nil]})
         (secure/encode-canonical {:a [1 true nil] :b #{1 2 3}})))
  (is (= {:a [1 true nil] :b #{1 2 3}}
         (secure/decode-canonical
          (secure/encode-canonical {:b #{3 2 1} :a [1 true nil]}))))
  (testing "hostile bounds fail before use"
    (is (= :integer-out-of-range
           (:reason
            (error-data
             #(secure/encode-canonical
               (inc secure/maximum-safe-integer))))))
    (is (= :too-deep
           (:reason
            (error-data
             #(secure/encode-canonical
               [[[[[:too-deep]]]]]
               {:maximum-depth 2})))))
    (is (= :too-large
           (:reason
            (error-data
             #(secure/decode-canonical
               (apply str (repeat 100 "x"))
               {:maximum-size 10})))))
    (is (= :unknown-fields
           (:reason
            (error-data
             #(secure/decode-canonical
               "{:allowed 1 :forged 2}"
               {:allowed-keys #{:allowed}})))))))

(deftest domain-separation-and-key-rotation-test
  (let [payload {:v 1 :answer true}
        old-options (assoc options :current-kid :old)
        old-token
        (secure/encode-authenticated
         (merge old-options {:domain "test/domain/a" :prefix "test_a_"})
         payload)]
    (is (= payload
           (secure/decode-authenticated
            (merge options
                   {:domain "test/domain/a"
                    :prefix "test_a_"
                    :payload-keys #{:v :answer}})
            old-token)))
    (is (= :malformed-token
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/b"
                       :prefix "test_b_"
                       :payload-keys #{:v :answer}})
               old-token)))))
    (is (= :authentication-failed
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/a"
                       :prefix "test_a_"
                       :payload-keys #{:v :answer}})
               (tamper-authenticator "test_a_" old-token))))))))

(deftest canonical-records-digest-test
  (let [records [[:alpha {:b 2 :a 1}]
                 [:beta #{3 1 2}]]
        digest (secure/canonical-records-digest
                "test/records/v1" records)]
    (is (= "sDmOlTDyZBZzLIccAloDY2q5FoRl_-dDELuWK27Q-6U"
           digest)
        "CLJ and CLJS must implement the same framing and digest contract")
    (is (= digest
           (secure/canonical-records-digest
            "test/records/v1"
            [[:alpha {:a 1 :b 2}]
             [:beta #{1 2 3}]])))
    (is (not=
         digest
         (secure/canonical-records-digest
          "test/records/v1" (reverse records))))
    (is (not=
         digest
         (secure/canonical-records-digest
          "test/records/v2" records)))
    (is (= 43 (count digest))))
  (testing "the complete sequence may exceed the whole-value format bound"
    (is (= 43
           (count
            (secure/canonical-records-digest
             "test/large-record-sequence/v1"
             (map (fn [n] [:record n]) (range 20000))))))))

(deftest constant-work-tag-comparison-test
  (is (secure/secure-equal? [0 1 2 3] [0 1 2 3]))
  (is (false? (secure/secure-equal? [9 1 2 3] [0 1 2 3])))
  (is (false? (secure/secure-equal? [0 1 2 9] [0 1 2 3])))
  (is (false? (secure/secure-equal? [0 1 2] [0 1 2 3]))))

(deftest causal-token-round-trip-and-rejection-test
  (let [payload {:backend :datascript
                 :source-id "family"
                 :branch nil
                 :graph-anchor "mutation"
                 :order-hint 7
                 :exact-locator "snapshot"}
        encoded (token/issue options payload)]
    (is (= (merge payload
                  {:version 3}
                  (select-keys (token/token-data options encoded)
                               [:issued-at :expires-at]))
           (token/token-data options
                             {:backend :datascript
                              :source-id "family"
                              :branch nil}
                             encoded)))
    (is (= :scope-mismatch
           (:reason
            (error-data
             #(token/token-data
               options
               {:backend :datascript
                :source-id "other"
                :branch nil}
               encoded)))))
    (is (= :expired
           (:reason
            (error-data
             #(token/token-data
               (assoc options :now-seconds
                      (inc (:expires-at
                            (token/token-data options encoded))))
               encoded)))))
    (is (= :malformed-token
           (:reason
            (error-data #(token/token-data options "17")))))))

(deftest authenticated-portable-cursor-test
  (let [value {:v 8 :edge {:kind :lookup-eid} :position [1 "a"]}
        encoded (cursor/cursor->token value options)]
    (is (= value (cursor/token->cursor encoded options)))
    (is (= :malformed-token
           (:reason
            (error-data
             #(cursor/token->cursor "eacl1_e30=" options)))))
    (is (= :authentication-failed
           (:reason
            (error-data
             #(cursor/token->cursor
               (tamper-authenticator cursor/cursor-prefix encoded)
               options)))))))
