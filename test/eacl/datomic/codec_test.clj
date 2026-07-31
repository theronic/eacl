(ns eacl.datomic.codec-test
  "Wire-format tests for the page-token codec.

  A cursor is opaque to callers, so a codec bug does not surface as a type
  error — it surfaces as a cursor that decodes to a subtly different query
  binding. Everything a real payload can contain is round-tripped here for
  identity, and everything it cannot is rejected at encode time."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.datomic.codec :as codec]))

(defn- round-trip
  [value]
  (codec/decode (codec/encode value)))

(deftest scalars-round-trip-test
  (doseq [value [nil true false
                 0 1 -1 Long/MAX_VALUE Long/MIN_VALUE
                 "" "plain" "unicode: åéîøü 日本語 🦅"
                 :kw :namespaced/kw :eacl.pagination/invalid-cursor
                 :a.very.long.namespace.indeed/with-a-long-name]]
    (is (= value (round-trip value)) (pr-str value))))

(deftest integer-widths-normalize-to-long-test
  ;; Cursor ordinals and eids arrive as Long from Datomic and Clojure
  ;; arithmetic, but an Integer must not round-trip into something that fails
  ;; an `=` against the Long it is compared with.
  (is (= 42 (round-trip (int 42))))
  (is (= 42 (round-trip (short 42))))
  (is (instance? Long (round-trip (int 42)))))

(deftest collections-round-trip-test
  (testing "empty"
    (is (= [] (round-trip [])))
    (is (= {} (round-trip {})))
    (is (= #{} (round-trip #{}))))

  (testing "flat"
    (is (= [1 :two "three" nil true] (round-trip [1 :two "three" nil true])))
    (is (= {:a 1 :b "two"} (round-trip {:a 1 :b "two"})))
    (is (= #{1 2 3} (round-trip #{1 2 3}))))

  (testing "nested"
    (let [value {:cache-scope [:relationships {:incarnation "abc" :uncertain 0 :revision 12}]
                 :edge {:kind :lookup-eid
                        :result-eid 17592186045424
                        :frontier-version 1
                        :frontier-direction :asc
                        :path-frontiers {"aGFzaC1vbmU" 17592186045000
                                         "aGFzaC10d28" :exhausted}}}]
      (is (= value (round-trip value)))))

  (testing "lazy sequences encode as vectors"
    (is (= [1 2 3] (round-trip (map inc [0 1 2]))))
    (is (vector? (round-trip (map inc [0 1 2]))))))

(deftest real-cursor-payloads-round-trip-test
  ;; The three edge shapes EACL actually mints.
  (doseq [edge [{:kind :lookup-eid :result-eid 17592186045424}
                {:kind :lookup-eid
                 :result-eid 17592186045424
                 :frontier-version 1
                 :frontier-direction :desc
                 :path-frontiers {"c29tZS1wYXRoLWtleQ" :exhausted}}
                {:kind :recursive-traversal
                 :engine-version 1
                 :direction :forward
                 :result-kind :resource
                 :ordinal 7
                 :result {:type :folder :eid 17592186045424}}
                {:kind :relationship
                 :scan :subject-forward
                 :e 17592186045424
                 :v [:user 17592186045000 :account 17592186045111]}]]
    (let [payload {:v 6
                   :op :lookup-resources
                   :database-id "6a6cb18c-adeb-4141-b16f-454e87fdb975"
                   :query-shape "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG0"
                   :basis-t 1234
                   :basis :stable
                   :schema-version "6a6cb33b-4e82-419e-bc82-eee9e0bc72f4"
                   :cache-scope [:basis 1234]
                   :edge edge
                   :exp 1799999999}]
      (is (= payload (round-trip payload)) (pr-str (:kind edge))))))

(deftest nil-schema-version-survives-test
  ;; A cursor minted on an unstamped database carries an explicit nil, which
  ;; must not decode as an absent key — validation compares the two.
  (let [payload {:v 6 :schema-version nil :cache-scope nil}
        decoded (round-trip payload)]
    (is (= payload decoded))
    (is (contains? decoded :schema-version))
    (is (nil? (:schema-version decoded)))))

(defrecord ARecord [a b])

(deftest unencodable-values-are-rejected-at-encode-time-test
  ;; Silently coercing these would mean a cursor decodes to a different value
  ;; than it was minted from. Fail where it is diagnosable instead.
  (doseq [value [1.5
                 (float 1.5)
                 1/2
                 (->ARecord 1 2)
                 (java.util.Date. 0)
                 (java.util.UUID/randomUUID)
                 'a-symbol
                 (byte-array 3)]]
    (is (thrown? clojure.lang.ExceptionInfo (codec/encode value))
        (pr-str (class value))))
  (is (= :eacl.codec/unencodable
         (try (codec/encode 1.5)
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
  (testing "and rejected when nested"
    (is (thrown? clojure.lang.ExceptionInfo
                 (codec/encode {:edge {:result-eid 1.5}})))))

(deftest truncated-input-is-an-exception-not-a-partial-value-test
  (let [bytes (codec/encode {:a 1 :b 2 :c 3})]
    (doseq [n (range 1 (alength bytes))]
      (is (thrown? Exception
                   (codec/decode (java.util.Arrays/copyOfRange bytes 0 n)))
          (str "truncated to " n " bytes")))))

(deftest unknown-tag-is-rejected-test
  (is (= :eacl.codec/malformed
         (try (codec/decode (byte-array [99]))
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
