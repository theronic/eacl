(ns eacl.proof-frame-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.proof-frame :as proof-frame]))

(defn- adapter
  ([provider]
   (adapter provider true nil))
  ([provider supported?]
   (adapter provider supported? nil))
  ([provider supported? schema-generation]
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
       (assoc :snapshot-id (constantly {:basis 9})
              :source-lifecycle (constantly "lifecycle-a"))
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
           {:schema-stamp 7
            :relation-stamps
            (mapv (fn [relation-id]
                    [relation-id ({1 10, 2 21, 3 15} relation-id)])
                  relation-ids)}))
        frame (proof-frame/request-frame selected)
        proof (proof-frame/resolve! frame [1 2 3])]
    (is (= :complete (:status proof)))
    (is (= [1 2 3] (:relation-ids proof)))
    (is (= [[1 10] [2 21] [3 15]] (:relation-stamps proof)))
    (is (= {:schema-stamp 7 :dependency-stamp 21}
           (proof-frame/descriptor proof)))
    (is (= {:schema-stamp 7 :dependency-stamp 15}
           (proof-frame/subset-descriptor proof [1 3])))
    (is (nil? (proof-frame/subset-descriptor proof [4])))
    (is (identical? proof (proof-frame/resolve! frame [1 2 3])))
    (is (= [[1 2 3]] @calls)
        "one canonical closure is acquired once in one request")))

(deftest empty-closure-has-the-initial-frontier
  (let [proof
        (proof-frame/resolve!
         (proof-frame/request-frame
          (adapter (constantly {:schema-stamp 4
                                :relation-stamps []})))
         [])]
    (is (= :complete (:status proof)))
    (is (= {:schema-stamp 4 :dependency-stamp 0}
           (proof-frame/descriptor proof)))))

(deftest proof-frame-rejects-certified-schema-generation-mismatch
  (let [failure
        (try
          (proof-frame/resolve!
           (proof-frame/request-frame
            (adapter
             (constantly {:schema-stamp 4 :relation-stamps []})
             true
             5))
           [])
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
            (ex-data error)))]
    (is (= {:type :eacl/backend-integrity-error
            :eacl/error :eacl/backend-integrity-error
            :reason :schema-generation-mismatch
            :proof-schema-generation 4
            :certified-schema-generation 5}
           (select-keys
            failure
            [:type :eacl/error :reason :proof-schema-generation
             :certified-schema-generation])))))

(deftest every-incomplete-proof-shape-fails-closed
  (doseq [[label provider expected-reason]
          [[:missing-schema
            (constantly {:schema-stamp nil :relation-stamps [[1 2]]})
            :malformed-schema-generation]
           [:missing-relation
            (constantly {:schema-stamp 1 :relation-stamps [[1 nil]]})
            :incomplete-or-noncanonical-generations]
           [:wrong-order
            (constantly {:schema-stamp 1
                         :relation-stamps [[2 2] [1 2]]})
            :incomplete-or-noncanonical-generations]
           [:duplicate
            (constantly {:schema-stamp 1
                         :relation-stamps [[1 2] [1 2]]})
            :incomplete-or-noncanonical-generations]
           [:extra-field
            (constantly {:schema-stamp 1
                         :relation-stamps [[1 2]]
                         :partial? false})
            :malformed-proof]
           [:throwing
            (fn [_] (throw (ex-info "provider failed" {})))
            :proof-provider-failure]]]
    (testing (name label)
      (let [proof
            (proof-frame/resolve!
             (proof-frame/request-frame (adapter provider)) [1])]
        (is (= :unavailable (:status proof)))
        (is (= expected-reason (:reason proof)))
        (is (nil? (proof-frame/descriptor proof)))))))

(deftest unsupported-oversized-and-noncanonical-inputs-fail-closed
  (let [unsupported
        (proof-frame/resolve!
         (proof-frame/request-frame
          (adapter (constantly nil) false)) [])
        oversized
        (proof-frame/resolve!
         (proof-frame/request-frame
          (adapter (constantly {:schema-stamp 1 :relation-stamps []}))
          {:maximum-relation-count 1})
         [1 2])
        malformed
        (proof-frame/resolve!
         (proof-frame/request-frame
          (adapter (constantly {:schema-stamp 1 :relation-stamps []})))
         [1 :not-an-eid])
        unsorted
        (proof-frame/resolve!
         (proof-frame/request-frame
          (adapter (constantly {:schema-stamp 1 :relation-stamps []})))
         [2 1])
        duplicate
        (proof-frame/resolve!
         (proof-frame/request-frame
          (adapter (constantly {:schema-stamp 1 :relation-stamps []})))
         [1 1])]
    (is (= :unsupported-proof-capability (:reason unsupported)))
    (is (= :proof-bound-exceeded (:reason oversized)))
    (is (= :noncanonical-dependencies (:reason malformed)))
    (is (= :noncanonical-dependencies (:reason unsorted)))
    (is (= :noncanonical-dependencies (:reason duplicate)))))
