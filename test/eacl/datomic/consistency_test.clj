(ns eacl.datomic.consistency-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.datomic.consistency :as consistency]
            [eacl.spicedb.consistency :as descriptor]))

(deftest zed-token-round-trip-and-database-binding-test
  (let [token (consistency/zed-token "db-a" 922337)]
    (is (string? token))
    (is (= {:database-id "db-a" :revision 922337}
           (consistency/token-data "db-a" token)))
    (is (= 922337 (consistency/token-revision "db-a" token)))
    (testing "the same revision cannot cross databases"
      (try
        (consistency/token-data "db-b" token)
        (is false "expected database mismatch")
        (catch clojure.lang.ExceptionInfo e
          (is (= :database-mismatch (:reason (ex-data e)))))))))

(deftest malformed-and-future-zed-tokens-are-rejected-test
  (doseq [token [nil "" "10" "eacl_z1_not-base64"]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Zed token"
         (consistency/token-data "db" token)))))

(deftest consistency-descriptors-retain-the-token-test
  (let [token (consistency/zed-token "db" 10)]
    (is (= {:mode :fully-consistent}
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
