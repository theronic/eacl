(ns eacl.datomic.consistency-test
  "Covers the surviving eacl.datomic.consistency surface: zed signing-key
  derivation and bounded observed revision checkpoints. The superseded v2
  zed-token constructors and their round-trip/tampering suites were deleted
  with the constructors (trusted-surface-hygiene 11.1); live tokens are
  issued and authenticated by the shared eacl.causal-token codec, covered by
  the consistency-v3 suites."
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

(deftest observed-checkpoints-are-bounded-and-age-selectable-test
  (let [now (atom 0)
        checkpoints
        (consistency/revision-checkpoints
         {:clock #(deref now)
          :interval-ms 1000
          :max-age-ms 10000
          :max-entries 3})]
    (consistency/observe! checkpoints 10)
    (reset! now 500)
    (consistency/observe! checkpoints 11)
    (is (= [{:captured-at 0 :basis-t 10}]
           (consistency/checkpoint-values checkpoints))
        "sampling does not retain every observed DB value")
    (doseq [[captured-at basis-t] [[1000 12] [2000 13] [3000 14]]]
      (reset! now captured-at)
      (consistency/observe! checkpoints basis-t))
    (is (= [12 13 14]
           (mapv :basis-t
                 (consistency/checkpoint-values checkpoints))))
    (reset! now 3500)
    (is (= 12
           (consistency/revision-at-least-seconds-ago
            checkpoints 2.5 15)))
    (is (= 15
           (consistency/revision-at-least-seconds-ago
            checkpoints 0.1 15))
        "no qualifying observation falls forward to the current revision")
    (is (= 15
           (consistency/revision-at-least-seconds-ago nil 100 15)))))
