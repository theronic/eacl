(ns eacl.datomic.consistency-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.datomic.consistency :as consistency]
            [eacl.spicedb.consistency :as descriptor])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(defn- root-key
  [offset]
  (byte-array (map #(byte (mod % 128))
                   (range offset (+ offset 32)))))

(def ^:private signing-opts
  {:zed-token-current-kid :current
   :zed-token-keyring
   {:current (consistency/derive-signing-key (root-key 0))
    :old (consistency/derive-signing-key (root-key 32))}})

(defn- b64url-encode
  [^bytes value]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value))

(defn- b64url-decode
  [^String value]
  (.decode (Base64/getUrlDecoder) value))

(defn- encode-edn
  [value]
  (b64url-encode
   (.getBytes (pr-str value) StandardCharsets/UTF_8)))

(defn- encode-text
  [value]
  (b64url-encode
   (.getBytes value StandardCharsets/UTF_8)))

(defn- decode-edn
  [value]
  (edn/read-string
   (String. (b64url-decode value) StandardCharsets/UTF_8)))

(defn- tamper-envelope
  [token f]
  (str "eacl_z2_"
       (encode-edn
        (f (decode-edn (subs token (count "eacl_z2_")))))))

(deftest zed-token-round-trip-and-database-binding-test
  (let [token (consistency/zed-token signing-opts "db-a" 922337)]
    (is (string? token))
    (is (= {:database-id "db-a" :revision 922337}
           (consistency/token-data signing-opts "db-a" token)))
    (is (= 922337
           (consistency/token-revision signing-opts "db-a" token)))
    (testing "the same revision cannot cross databases"
      (try
        (consistency/token-data signing-opts "db-b" token)
        (is false "expected database mismatch")
        (catch clojure.lang.ExceptionInfo e
          (is (= :database-mismatch (:reason (ex-data e)))))))))

(deftest malformed-and-future-zed-tokens-are-rejected-test
  (let [deep-envelope
        (str "eacl_z2_"
             (encode-text
              (str (apply str (repeat 1000 "["))
                   "nil"
                   (apply str (repeat 1000 "]")))))]
    (doseq [token [nil "" "10" "eacl_z1_not-base64"
                   "eacl_z2_not-base64"
                   (str "eacl_z2_" (apply str (repeat 5000 "A")))
                   deep-envelope]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Zed token"
         (consistency/token-data signing-opts "db" token))))))

(deftest zed-token-tampering-and-key-selection-test
  (let [token (consistency/zed-token signing-opts "db" 42)
        change-payload
        (fn [f]
          (tamper-envelope
           token
           (fn [envelope]
             (update envelope :payload
                     (fn [encoded]
                       (encode-edn (f (decode-edn encoded))))))))
        tampered-db (change-payload #(assoc % :db "other-db"))
        tampered-t (change-payload #(assoc % :t 999999))
        tampered-kid (tamper-envelope token #(assoc % :kid :missing))
        tampered-tag
        (tamper-envelope
         token
         #(assoc % :tag
                 (str (if (= \A (first (:tag %))) "B" "A")
                      (subs (:tag %) 1))))]
    (doseq [forged [tampered-db tampered-t tampered-kid tampered-tag]]
      (try
        (consistency/token-data signing-opts "db" forged)
        (is false "forged token must fail authentication")
        (catch clojure.lang.ExceptionInfo e
          (is (= :eacl/invalid-zed-token (:type (ex-data e)))))))))

(deftest zed-token-key-rotation-test
  (let [old-signer (assoc signing-opts :zed-token-current-kid :old)
        old-token (consistency/zed-token old-signer "db" 10)
        new-token (consistency/zed-token signing-opts "db" 11)]
    (is (= 10
           (consistency/token-revision signing-opts "db" old-token))
        "retained old keys verify during the rotation overlap")
    (is (= 11
           (consistency/token-revision signing-opts "db" new-token)))
    (is (thrown?
         clojure.lang.ExceptionInfo
         (consistency/token-data
          (update signing-opts :zed-token-keyring dissoc :old)
          "db"
          old-token))
        "retiring an old key invalidates outstanding old tokens")))

(deftest consistency-descriptors-retain-the-token-test
  (let [token (consistency/zed-token signing-opts "db" 10)]
    (is (= {:mode :local-snapshot}
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
