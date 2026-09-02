(ns eacl.datomic.consistency-test
  "Covers the surviving eacl.datomic.consistency surface: Zed signing-key
  derivation. Live tokens are issued and authenticated by the shared
  eacl.causal-token codec, covered by the consistency-v3 suites."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.datomic.consistency :as consistency]
            [eacl.spicedb.consistency :as descriptor]))

(defn- root-key
  [offset]
  (byte-array (map #(byte (mod % 128))
                   (range offset (+ offset 32)))))

(deftest derive-signing-key-test
  (testing "derivation is deterministic, purpose-bound, and key-separated"
    (is (= (vec (consistency/derive-signing-key (root-key 0)))
           (vec (consistency/derive-signing-key (root-key 0)))))
    (is (not= (vec (consistency/derive-signing-key (root-key 0)))
              (vec (consistency/derive-signing-key (root-key 32)))))
    (is (= 32 (alength ^bytes (consistency/derive-signing-key (root-key 0))))))
  (testing "empty or non-byte roots are rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (consistency/derive-signing-key (byte-array 0))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (consistency/derive-signing-key "not-bytes")))))

(deftest consistency-descriptors-retain-the-token-test
  ;; Descriptors treat the token as an opaque transport string; authentication
  ;; happens at the backend that consumes it.
  (let [token "eacl_c3_opaque-transport-token"]
    (is (= {:mode :minimize-latency}
           (descriptor/descriptor nil)))
    (is (= {:mode :minimize-latency}
           (descriptor/descriptor descriptor/minimize-latency)))
    (is (= {:mode :at-least-as-fresh :token token}
           (descriptor/descriptor (descriptor/fresh token))))
    (is (= {:mode :at-exact-snapshot :token token}
           (descriptor/descriptor (descriptor/at-exact-snapshot token))))
    (doseq [invalid [{:consistency/mode :at-least-as-fresh}
                     {:consistency/mode :at-exact-snapshot
                      :zed/token nil}
                     {:consistency/mode :at-exact-snapshot
                      :zed/token ""}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (descriptor/descriptor invalid))))))
