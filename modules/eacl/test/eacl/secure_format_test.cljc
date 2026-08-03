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

(def portable-cursor-vector
  "eacl_c3_ezpraWQgOmN1cnJlbnQsIDpwYXlsb2FkICJlenBqZFhKemIzSWdlenByYVc1a0lEcHlaV3hoZEdsdmJuTm9hWEJ6TENBNmIyWm1jMlYwSURJc0lEcHpZMjl3WlNCYk9uSmxZV1FnZXpwemRXSnFaV04wTDJsa0lDSjFNU0o5WFN3Z09uWWdPWDBzSURwbGVIQnBjbVZ6TFdGMElERXdOU3dnT21semMzVmxaQzFoZENBeE1EQXNJRHAyWlhKemFXOXVJRE45IiwgOnRhZyAiMnRLbjR3YmIzT3Rnc3MySkpFWFo1WUg2U05SVEh6d3ZJOGpnNmE4aWNDWSIsIDp2IDF9")

(def legacy-jvm-cursor-vector
  "eacl_c3_ezpraWQgOmN1cnJlbnQsIDpwYXlsb2FkICJlenBqZFhKemIzSWdlenByYVc1a0lEcHlaV3hoZEdsdmJuTm9hWEJ6TENBNmIyWm1jMlYwSURJc0lEcHpZMjl3WlNCYk9uSmxZV1FnSXpwemRXSnFaV04wZXpwcFpDQWlkVEVpZlYwc0lEcDJJRGw5TENBNlpYaHdhWEpsY3kxaGRDQXhNRFVzSURwcGMzTjFaV1F0WVhRZ01UQXdMQ0E2ZG1WeWMybHZiaUF6ZlEiLCA6dGFnICJtRlhBR2lBLXQ5VEZtc0dTaFNsUlJsODg0YzJyYnIybmdQQVRPbTNfekZzIiwgOnYgMX0")

(def portable-cache-vector
  "eacl_ce3_ezpraWQgOmN1cnJlbnQsIDpwYXlsb2FkICJlenBqYjIxd2RYUmxaQzFoZENCN09tZHlZWEJvSURkOUxDQTZaR1Z3Wlc1a1pXNWplUzF6WTI5d1pTQjdPbkpsYkdGMGFXOXVjeUJiTVRGZExDQTZjMk5vWlcxaElGdGJPbVJ2WTNWdFpXNTBJRHAyYVdWM1hWMTlMQ0E2YTJWNUlIczZjMlZ0WVc1MGFXTXRhMlY1SUZzNlkyRnVQeUFpZFRFaVhYMHNJRHByYVc1a0lEcGliMjlzWldGdUxDQTZjRzl5ZEdGaWJHVXRkbVZ5YzJsdmJpQXhMQ0E2Y0hKdmIyWWdlenB5Wld4aGRHbHZibk1nZXpFeElDSnlNU0o5TENBNmMyTm9aVzFoSUNKek1TSjlMQ0E2ZG1Gc2FXUmhkR1ZrTFdGMElIczZaM0poY0dnZ04zMHNJRHAyWVd4MVpTQjBjblZsTENBNmRtVnljMmx2YmlBemZRIiwgOnRhZyAiNEk0ZmxKQUZGcjZVczFGQnZiVl9uWDZ6ekczaDVEWlM1d3ExVjBQRUxPWSIsIDp2IDF9")

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
  (is (= "{:scope [:read {:subject/id \"u1\"}]}"
         (secure/encode-canonical
          {:scope [:read {:subject/id "u1"}]}))
      "canonical maps never depend on host namespace-map abbreviations")
  (is (= {:a [1 true nil] :b #{1 2 3}}
         (secure/decode-canonical
          (secure/encode-canonical {:b #{3 2 1} :a [1 true nil]}))))
  (is (= {:minimum secure/minimum-safe-integer
          :maximum secure/maximum-safe-integer}
         (secure/decode-canonical
          (secure/encode-canonical
           {:maximum secure/maximum-safe-integer
            :minimum secure/minimum-safe-integer}))))
  (is (= "\"quote:\\\" slash:\\\\ controls:\\b\\t\\n\\f\\r\\u0001\""
         (secure/encode-canonical
          "quote:\" slash:\\ controls:\b\t\n\f\r\u0001")))
  (testing "hostile bounds fail before use"
    (is (= :integer-out-of-range
           (:reason
            (error-data
             #(secure/encode-canonical
               (inc secure/maximum-safe-integer))))))
    (is (= :integer-out-of-range
           (:reason
            (error-data
             #(secure/encode-canonical
               (dec secure/minimum-safe-integer))))))
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
               {:allowed-keys #{:allowed}})))))
    (is (= :malformed
           (:reason
            (error-data
             #(secure/decode-canonical
               "{:duplicate 1, :duplicate 2}"))))
        "duplicate map fields never receive last-write-wins semantics")
    (is (= :malformed
           (:reason
            (error-data
             #(secure/decode-canonical
               "#eacl/unknown {:value true}"))))
        "unknown tagged values are outside the accepted wire language")))

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
                      {:domain "test/domain/b"
                       :prefix "test_a_"
                       :payload-keys #{:v :answer}})
               old-token))))
        "domain separation still applies when an attacker reuses the prefix")
    (is (= :authentication-failed
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/a"
                       :prefix "test_a_"
                       :keyring {:old current-key}})
               old-token))))
        "the key identifier cannot substitute a different key")
    (is (= :unknown-fields
           (:reason
            (error-data
             #(secure/decode-authenticated
               (merge options
                      {:domain "test/domain/a"
                       :prefix "test_a_"
                       :payload-keys #{:v}})
               old-token))))
        "an authenticated but context-invalid payload still fails closed")
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

(deftest portable-cursor-expiry-boundary-test
  (let [value {:v 8 :edge {:kind :lookup-eid} :position [1 "a"]}
        issued-at 100
        encoded
        (cursor/cursor->token
         value
         (assoc options
                :cursor-ttl-seconds 1
                :now-seconds issued-at))]
    (is (= value
           (cursor/token->cursor
            encoded
            (assoc options :now-seconds issued-at))))
    (is (= :expired
           (:reason
            (error-data
             #(cursor/token->cursor
               encoded
               (assoc options :now-seconds (inc issued-at))))))
        "expires-at is the first invalid second, matching Datomic cursors")))

(deftest authenticated-cross-runtime-vectors-test
  (let [vector-options
        {:current-kid :current
         :keyring {:current current-key}
         :now-seconds 100
         :cursor-ttl-seconds 5}
        cursor-payload
        {:v 9
         :kind :relationships
         :scope [:read {:subject/id "u1"}]
         :offset 2}
        cache-payload
        {:version 3
         :portable-version 1
         :key {:semantic-key [:can? "u1"]}
         :kind :boolean
         :computed-at {:graph 7}
         :validated-at {:graph 7}
         :dependency-scope
         {:schema [[:document :view]]
          :relations [11]}
         :proof
         {:schema "s1"
          :relations {11 "r1"}}
         :value true}
        cache-options
        (merge
         vector-options
         {:domain "eacl/cache-entry/envelope/v3"
          :prefix "eacl_ce3_"})]
    (is (= portable-cursor-vector
           (cursor/cursor->token
            cursor-payload
            vector-options)))
    (is (= cursor-payload
           (cursor/token->cursor
            portable-cursor-vector
            vector-options)))
    (is (= cursor-payload
           (cursor/token->cursor
            legacy-jvm-cursor-vector
            vector-options))
        "tokens emitted by the former JVM renderer remain readable")
    (is (= portable-cache-vector
           (secure/encode-authenticated
            cache-options
            cache-payload)))
    (is (= cache-payload
           (secure/decode-authenticated
            (assoc
             cache-options
             :payload-keys
             #{:version
               :portable-version
               :key
               :kind
               :computed-at
               :validated-at
               :dependency-scope
               :proof
               :value})
            portable-cache-vector)))))
