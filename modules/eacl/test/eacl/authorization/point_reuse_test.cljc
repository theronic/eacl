(ns eacl.authorization.point-reuse-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.point-reuse :as point-reuse]
            [eacl.authorization.qualification :as q]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.authorization.temporal :as temporal]
            [eacl.cache.key :as cache-key]
            [eacl.subproblem-cache :as subproblem]))

(def ^:private scope [:certified-point evidence/format-version 1 "{}" nil])

(defn- key-fn [semantic]
  (cache-key/exact-denotation-key
   {:tier :denotation
    :source-lifecycle #uuid "8ac290d0-0257-515a-b9b3-20d547d651a0"
    :abi :test-authorization-v2
    :semantic semantic
    :reuse [:basis 1]}))

(defn- at [time]
  (qualification-fixtures/request {:time time}))

(deftest a-qualified-point-key-ends-with-its-certified-scope
  (is (= [:point 1 scope] (point-reuse/scoped-key [:point 1] scope)))
  (is (= [:point 1] (point-reuse/scoped-key [:point 1] nil)))
  (is (point-reuse/certified-key? [:point 1 scope]))
  (doseq [key [[:point 1] [:point 1 nil] [:point [:other]] [] :point nil]]
    (is (not (point-reuse/certified-key? key)) (pr-str key)))
  (is (= (point-reuse/scope (at 100)) (point-reuse/scope (at 150)))
      "the scope omits the evaluation time")
  (is (nil? (point-reuse/scope nil))))

(deftest stored-values-must-match-their-key
  (let [answer (temporal/point-answer 100 (evidence/with-certificate true 200 true))]
    (is (point-reuse/stored-value-valid? [:point scope] answer))
    (is (point-reuse/stored-value-valid? [:point] false))
    (doseq [[label key value]
            [[:boolean-under-a-certified-key [:point scope] true]
             [:answer-under-a-plain-key [:point] answer]
             [:interval-disagrees-with-evidence [:point scope] (assoc answer :valid-until-ms 300)]
             [:kind-disagrees-with-evidence [:point scope] (assoc answer :kind :no-permission)]
             [:completeness-disagrees [:point scope] (assoc answer :complete? false)]
             [:open-shape [:point scope] (assoc answer :extra 1)]
             [:fault [:point scope]
              (assoc answer :value (evidence/encode (evidence/fault :test/fault :invalid)))]]]
      (is (not (point-reuse/stored-value-valid? key value)) (name label)))))

(deftest decisions-are-reused-exactly-while-their-certificates-admit-the-time
  (let [store (subproblem/store {:denotation-max-entries 16 :answer-max-entries 2})
        expiring (evidence/with-certificate true 200 true)
        incomplete (evidence/with-certificate false nil false)
        fault (evidence/fault :test/fault :invalid)
        keys-at (fn [request] (mapv #(point-reuse/scoped-key [:point %] (point-reuse/scope request))
                                    (range 4)))
        reuse (fn [request] (point-reuse/reuse! request (keys-at request) ::miss))]
    (binding [subproblem/*store* store
              subproblem/*exact-denotation-key-fn* key-fn]
      (let [computed (at 100)]
        (point-reuse/publish! computed (map vector (keys-at computed)
                                            [expiring true incomplete fault])))
      (testing "at the computing time, every published decision is reused"
        (is (= [expiring true incomplete ::miss] (reuse (at 100)))))
      (testing "later, only decisions with complete certificates that admit the time"
        (let [later (at 150)]
          (is (= [expiring true ::miss ::miss] (reuse later)))
          (is (= 200 (:valid-until-ms (q/certificate later)))
              "a reused decision's certificate bounds the request's answer")))
      (testing "at or after a certificate's end, the decision is recomputed"
        (is (= [::miss true ::miss ::miss] (reuse (at 200)))))
      (testing "a time before the computation never reuses it"
        (is (= [::miss ::miss ::miss ::miss] (reuse (at 50)))))
      (testing "unqualified decisions are Booleans under plain keys"
        (point-reuse/publish! nil [[[:plain 1] false]])
        (is (= [false ::miss] (point-reuse/reuse! nil [[:plain 1] [:plain 2]] ::miss))))
      (testing "a deferral marker is never stored"
        (point-reuse/publish! nil [[[:plain 4] :eacl.engine.stable-route/qualified]])
        (is (= [::miss] (point-reuse/reuse! nil [[:plain 4]] ::miss)))
        (is (thrown? #?(:clj Exception :cljs :default)
                     (point-reuse/publish! (at 100) [[(point-reuse/scoped-key [:plain 5] scope)
                                                      :eacl.engine.stable-route/qualified]]))
            "a qualified request fails closed rather than store it"))
      (testing "a read-only request publishes nothing"
        (binding [subproblem/*populate?* false]
          (point-reuse/publish! nil [[[:plain 3] true]]))
        (is (= [::miss] (point-reuse/reuse! nil [[:plain 3]] ::miss)))))))
