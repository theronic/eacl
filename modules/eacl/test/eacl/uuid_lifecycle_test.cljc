(ns eacl.uuid-lifecycle-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.uuid :as uuid]
            [eacl.secure-format :as secure]
            [eacl.causal-token :as token]
            [eacl.cursor :as cursor]))

(def a #uuid "854e138f-b8a4-42ee-a8f9-49c01ac19fc1")
(def b #uuid "954e138f-b8a4-42ee-a8f9-49c01ac19fc1")

(def golden-texts
  ["00000000-0000-0000-0000-000000000000"
   "ffffffff-ffff-ffff-ffff-ffffffffffff"
   "80000000-0000-0000-8000-000000000000"
   "00000000-0000-0001-0000-000000000000"
   "00000000-0000-0000-0000-000000000001"
   "854e138f-b8a4-42ee-a8f9-49c01ac19fc1"])

(def malformed-wires
  ["#uuid \"854E138F-b8a4-42ee-a8f9-49c01ac19fc1\""
   "#uuid \"1-1-1-1-1\"" "#uuid \"not-a-uuid\""
   "#uuid \"\\u003800000000-0000-0000-0000-000000000000\""
   "#uuid\n\"854e138f-b8a4-42ee-a8f9-49c01ac19fc1\""
   "#unknown {:safe true}" "#inst \"2026-09-08\""
   "#_nil #uuid \"854e138f-b8a4-42ee-a8f9-49c01ac19fc1\""])

; Independent oracle: fixtures/uuid_vectors.py (Python/OpenSSL).
(def native-causal-vector
  "eacl_z5_ezpraWQgOnRlc3QsIDpwYXlsb2FkICJlenBpWVdOclpXNWtJRHBrWVhSaGMyTnlhWEIwTENBNlluSmhibU5vSUc1cGJDd2dPbVY0WVdOMExXeHZZMkYwYjNJZ2JtbHNMQ0E2Wlhod2FYSmxjeTFoZENBeU1EQXNJRHBwYzNOMVpXUXRZWFFnTVRBd0xDQTZjbVYyYVhOcGIyNGdOeXdnT25OdmRYSmpaUzFwWkNCN09tTnZibTVsWTNScGIyNHRhV1FnSW05dVpTSjlMQ0E2YzI5MWNtTmxMV3hwWm1WamVXTnNaU0FqZFhWcFpDQWlPRFUwWlRFek9HWXRZamhoTkMwME1tVmxMV0U0WmprdE5EbGpNREZoWXpFNVptTXhJaXdnT25abGNuTnBiMjRnTlgwIiwgOnRhZyAiSG03U1JNU01udkpYREgtOFFiTXdhLUFsRmRPaVllSmNITk5oNzkwenlfTSIsIDp2IDJ9")

(def native-cursor-vector
  "eacl_c7_OmN1cnJlbnQ.AAECAwQFBgcICQoL.RmZwbCL-cll8Ycf5ZJP_LQqTA67vBgzBFPttb0t-WMdpgwBPjuD10OU2LwdVLlyp5D939v1QByIAkBYTXwS0N46tuDxbhsg5B1uLtXj-20Np_GIEl8u2HY1KlgITRy0w6wr405Kr9wXsNLyk1y-cPFWt1qk7FcI.RVRMRj5UGOzoggbJGMB7OyorW-utuM6sS7rf7cXsSDg")

(defn error-data [f]
  (try (f) nil (catch #?(:clj Exception :cljs :default) e (ex-data e))))

(deftest explicit-native-domain-test
  (doseq [value [a b uuid/initial]]
    (is (= value (token/validate-source-lifecycle! value)))
    (is (uuid/owned? (token/validate-source-lifecycle! value))))
  (doseq [value [(str a) "" :old {:epoch 1} [:epoch 1]]]
    (is (= :eacl/source-lifecycle-upgrade-required
           (:type (error-data #(token/validate-source-lifecycle! value))))))
  (doseq [value [nil false true 1 1.5 #{}]]
    (is (= :eacl/invalid-source-lifecycle
           (:type (error-data #(token/validate-source-lifecycle! value)))))))

(deftest canonical-uuid-golden-and-type-separation-test
  (doseq [text golden-texts]
    (let [wire (str "#uuid \"" text "\"")
          value (uuid/parse-canonical text)
          decoded (secure/decode-canonical wire)
          input {value [:uuid #{value}] text [:string text]}
          roundtrip (secure/decode-canonical (secure/encode-canonical input))]
      (is (= 44 (count wire)))
      (is (= wire (secure/encode-canonical value)))
      (is (= value decoded))
      (is (uuid/owned? decoded))
      (is (= (hash value) (hash decoded)))
      (is (= input roundtrip))
      (is (= 2 (count roundtrip)))
      (is (not= (secure/encode-canonical value) (secure/encode-canonical text))))))

(deftest malformed-and-reader-aliases-fail-test
  (doseq [wire malformed-wires]
    (is (= :eacl.format/invalid (:type (error-data #(secure/decode-canonical wire)))) wire))
  (let [text "not a tag: #uuid \"arbitrary\""]
    (is (= text (secure/decode-canonical (secure/encode-canonical text)))))
  (let [encoded (secure/encode-canonical a)
        nested (secure/encode-canonical [[a]])]
    (is (= :too-large (:reason (error-data #(secure/decode-canonical encoded {:maximum-size 43})))))
    (is (= :too-deep (:reason (error-data #(secure/decode-canonical nested {:maximum-depth 1})))))))

(deftest uuid-opt-out-applies-at-every-canonical-boundary-test
  (doseq [value [a [a] {:nested a} #{a}]]
    (let [limits {:allow-uuids? false} wire (secure/encode-canonical value)]
      (doseq [f [#(secure/encode-canonical value limits)
                 #(secure/canonicalize value limits)
                 #(secure/decode-canonical wire limits)]]
        (is (= :eacl.format/invalid (:type (error-data f))))))))

(deftest scalar-fast-path-preserves-all-resource-and-shape-checks-test
  (let [wire (secure/encode-canonical a)]
    (is (= a (secure/decode-canonical wire {:maximum-size 44 :maximum-depth 0 :maximum-entries 1})))
    (doseq [limits [{:maximum-size 43} {:maximum-depth -1} {:maximum-entries 0}
                    {:allowed-keys #{:source-lifecycle}}]]
      (is (= :eacl.format/invalid (:type (error-data #(secure/decode-canonical wire limits))))))))

(deftest uuid-hash-collision-does-not-collapse-identity-test
  (let [zero #uuid "00000000-0000-0000-0000-000000000000"
        collision #uuid "00000001-0000-0001-0000-000000000000"
        values {zero :initial collision :different}]
    #?(:clj (is (= (hash zero) (hash collision))))
    (is (not= zero collision))
    (is (= 2 (count values)))
    (is (= values (secure/decode-canonical (secure/encode-canonical values))))))

(deftest portable-capture-shares-only-validated-owned-values-test
  (let [owned (uuid/capture a) value {:lifecycle owned :nested [#{owned}]}]
    (is (identical? value (secure/capture-portable value {})))
    (doseq [limits [{:maximum-depth 0} {:maximum-entries 1} {:allow-uuids? false}]]
      (is (= :eacl.format/invalid (:type (error-data #(secure/capture-portable value limits))))))
    (let [with-meta (with-meta value {:host-only true})
          captured (secure/capture-portable with-meta {})]
      (is (= value captured))
      (is (nil? (meta captured)))
      (is (not (identical? with-meta captured))))))

(deftest native-cursor-cross-runtime-golden-test
  (let [options {:current-kid :current :keyring {:current (vec (range 32 64))}
                 :now-seconds 100 :cursor-ttl-seconds 5}
        payload {:source-lifecycle a}]
    (is (= native-cursor-vector
           (with-redefs [secure/random-bytes (fn [_] (vec (range 12)))]
             (cursor/cursor->token payload options))))
    (let [decoded (cursor/token->cursor native-cursor-vector options)]
      (is (= payload decoded))
      (is (uuid/owned? (:source-lifecycle decoded))))))

(deftest scope-is-composite-and-legacy-artifacts-do-not-select-test
  (let [scope {:backend :datascript :source-id {:connection-id "one"}
               :branch nil :source-lifecycle a}
        opts {:keyring {:test "uuid-test-fixture-key-00000000000"}
              :current-kid :test :now-seconds 100}
        payload (assoc scope :revision 7 :exact-locator nil :issued-at 100 :expires-at 200)
        encoded (token/issue opts payload)]
    (is (= native-causal-vector encoded))
    (is (= a (:source-lifecycle (token/token-data opts scope native-causal-vector))))
    (doseq [different (map #(merge scope %) [{:source-lifecycle b} {:source-id {:connection-id "two"}} {:branch :sibling} {:backend :datomic}])]
      (is (= :scope-mismatch (:reason (error-data #(token/token-data opts different encoded))))))
    (doseq [bad [(assoc payload :source-id {:nested a})
                 (assoc payload :branch [a]) (assoc payload :exact-locator a)]]
      (is (some? (error-data #(token/issue opts bad)))))
    (doseq [legacy ["eacl_z3_obsolete" "eacl_z4_obsolete"]]
      (is (= :eacl/zed-token-upgrade-required
             (:type (error-data #(token/token-data opts scope legacy))))))
    (doseq [legacy ["eacl_c5_obsolete" "eacl_c6_obsolete"]]
      (is (= :eacl.pagination/cursor-upgrade-required
             (:type (error-data #(cursor/token->cursor legacy opts))))))
    (is (= :eacl.pagination/invalid-cursor
           (:type (error-data #(cursor/token->cursor {:v 14 :source-lifecycle a} opts)))))))
