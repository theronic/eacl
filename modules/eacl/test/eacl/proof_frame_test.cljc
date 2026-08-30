(ns eacl.proof-frame-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.proof-frame :as proof-frame]))

(defn- adapter
  ([provider]
   (adapter provider true 7 30))
  ([provider supported?]
   (adapter provider supported? (when supported? 7) 30))
  ([provider supported? schema-generation]
   (adapter provider supported? schema-generation 30))
  ([provider supported? schema-generation revision]
   (backend/make-adapter
    {:id :proof-test
     :capabilities
     {:cache-proofs (if supported? #{:ordered-generations} #{})}
     :operations
     (cond->
      (into {}
            (map (fn [operation]
                   [operation (fn [& _] nil)]))
            backend/required-snapshot-operations)
       true
       (assoc :snapshot-id (constantly {:basis revision})
              :native-revision
              (constantly {:revision revision :exact-locator revision}))
       (some? schema-generation)
       (assoc :schema-generation (constantly schema-generation))
       supported?
       (assoc :proof-frame provider))})))

(deftest complete-frame-is-canonical-scalar-and-request-local
  (let [calls (atom [])
        selected
        (adapter
         (fn [relation-ids]
           (swap! calls conj relation-ids)
           (mapv (fn [relation-id]
                   [relation-id ({1 10, 2 21, 3 15} relation-id)])
                 relation-ids)))
        frame (proof-frame/request-frame selected)
        proof (proof-frame/resolve! frame [1 2 3])]
    (is (= :complete (:status proof)))
    (is (= 30 (:revision proof)))
    (is (= 7 (:schema-generation proof)))
    (is (= [1 2 3] (:relation-ids proof)))
    (is (= [[1 10] [2 21] [3 15]] (:relation-generations proof)))
    (is (= {:schema-generation 7
            :dependency-identity [[1 10] [2 21] [3 15]]
            :dependency-stamp 21}
           (proof-frame/descriptor proof)))
    (is (identical? proof (proof-frame/resolve! frame [1 2 3])))
    (is (= [[1 2 3]] @calls)
        "one canonical closure is acquired once in one request")))

(deftest empty-closure-has-the-initial-frontier
  (let [proof
        (proof-frame/resolve!
         (proof-frame/request-frame
          (adapter (constantly []) true 4))
         [])]
    (is (= :complete (:status proof)))
    (is (= {:schema-generation 4
            :dependency-identity []
            :dependency-stamp 0}
           (proof-frame/descriptor proof)))))

(deftest descriptor-shape-is-closed-and-portable
  (is (true?
       (proof-frame/descriptor?
        {:schema-generation 4
         :dependency-identity [[1 9]]
         :dependency-stamp 9})))
  (doseq [invalid
          [nil
           {}
           {:schema-generation 4}
           {:schema-generation 4 :dependency-stamp 9}
           {:schema-generation 4
            :dependency-identity [[1 9]]
            :dependency-stamp 9
            :mode :exact-basis}
           {:schema-generation -1
            :dependency-identity [[1 9]]
            :dependency-stamp 9}
           {:schema-generation 4
            :dependency-identity [[1 9]]
            :dependency-stamp 1.5}
           {:schema-generation 4
            :dependency-identity [[2 9] [1 9]]
            :dependency-stamp 9}
           {:schema-generation 4
            :dependency-identity [[1 8]]
            :dependency-stamp 9}]]
    (is (false? (proof-frame/descriptor? invalid)) (pr-str invalid))))

(deftest proof-frame-reads-schema-generation-only-through-certified-operation
  (let [schema-reads (atom 0)
        selected
        (backend/make-adapter
         {:id :proof-test
          :capabilities {:cache-proofs #{:ordered-generations}}
          :operations
          (-> (into {}
                    (map (fn [operation]
                           [operation (fn [& _] nil)]))
                    backend/required-snapshot-operations)
              (assoc :snapshot-id (constantly {:basis 9})
                     :native-revision
                     (constantly {:revision 9 :exact-locator 9})
                     :schema-generation
                     (fn [] (swap! schema-reads inc) 4)
                     :proof-frame (constantly [])))})
        proof
        (proof-frame/resolve!
         (proof-frame/request-frame selected) [])]
    (is (= :complete (:status proof)))
    (is (= 4 (:schema-generation proof)))
    (is (= 1 @schema-reads))))

(deftest unavailable-frame-outcomes-remain-retryable
  (doseq [[label frame relation-ids expected-reason]
          [[:missing-schema
            (proof-frame/request-frame
             (adapter (constantly [[1 2]]) true nil))
            [1]
            :schema-generation-unavailable]
           [:missing-relation-generation
            (proof-frame/request-frame
             (adapter (constantly [[1 nil]])))
            [1]
            :relation-generation-unavailable]
           [:throwing-provider
            (proof-frame/request-frame
             (adapter
              (fn [_] (throw (ex-info "provider failed" {})))))
            [1]
            :proof-provider-failure]
           [:unsupported
            (proof-frame/request-frame
             (adapter (constantly nil) false))
            []
            :unsupported-proof-capability]
           [:closure-bound
            (proof-frame/request-frame
             (adapter (constantly [[1 1] [2 1]]))
             {:maximum-relation-count 1})
            [1 2]
            :proof-bound-exceeded]
           [:non-exact-revision
            (proof-frame/request-frame
             (adapter (constantly []) true 1 nil))
            []
            :revision-unavailable]]]
    (testing (name label)
      (let [proof (proof-frame/resolve! frame relation-ids)]
        (is (= :unavailable (:status proof)))
        (is (= expected-reason (:reason proof)))
        (is (nil? (proof-frame/descriptor proof)))))))

(deftest malformed-or-future-adapter-evidence-is-a-contract-violation
  (doseq [[label provider schema-generation revision relation-ids reason]
          [[:map-shape
            (constantly {:relation-generations [[1 2]]}) 1 10 [1]
            :malformed-shape]
           [:entry-shape
            (constantly [[1 2 3]]) 1 10 [1] :malformed-shape]
           [:wrong-cardinality
            (constantly []) 1 10 [1] :wrong-cardinality]
           [:wrong-order
            (constantly [[2 2] [1 2]]) 1 10 [1 2]
            :noncanonical-relation-ids]
           [:duplicate
            (constantly [[1 2] [1 2]]) 1 10 [1 2]
            :duplicate-relation-id]
           [:non-integer-generation
            (constantly [[1 "2"]]) 1 10 [1]
            :invalid-relation-generation]
           [:negative-generation
            (constantly [[1 -1]]) 1 10 [1]
            :invalid-relation-generation]
           [:future-relation-generation
            (constantly [[1 11]]) 1 10 [1]
            :relation-generation-above-revision]
           [:non-integer-schema-generation
            (constantly [[1 2]]) "1" 10 [1]
            :invalid-schema-generation]
           [:future-schema-generation
            (constantly [[1 2]]) 11 10 [1]
            :schema-generation-above-revision]]]
    (testing (name label)
      (let [proof
            (proof-frame/resolve!
             (proof-frame/request-frame
              (adapter provider true schema-generation revision))
             relation-ids)]
        (is (= :contract-violation (:status proof)))
        (is (= reason (:reason proof)))
        (is (nil? (proof-frame/descriptor proof)))))))

(deftest diagnostics-observe-typed-outcomes-without-changing-them
  (let [diagnostics (atom [])
        frame
        (proof-frame/request-frame
         (adapter (constantly [[1 31]]))
         {:diagnostic-fn #(swap! diagnostics conj %)})
        violation (proof-frame/resolve! frame [1])]
    (is (proof-frame/contract-violation? violation))
    (is (= [violation] @diagnostics))))

(deftest noncanonical-request-dependencies-fail-closed-before-provider-work
  (let [calls (atom 0)
        frame
        (proof-frame/request-frame
         (adapter (fn [_] (swap! calls inc) [])))]
    (doseq [relation-ids [[1 :not-an-eid] [2 1] [1 1]]]
      (let [proof (proof-frame/resolve! frame relation-ids)]
        (is (= :unavailable (:status proof)))
        (is (= :noncanonical-dependencies (:reason proof)))))
    (is (zero? @calls))))
