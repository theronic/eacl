(ns eacl.backend.direct-membership-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.direct-membership :as direct]
            [eacl.backend.v8 :as backend]
            [eacl.execution :as execution]
            [eacl.exact-integer :as exact-integer]))

(defn- required-operations [direct-match]
  (into {}
        (map
         (fn [operation]
           [operation
            (case operation
              :direct-match? direct-match
              :permission-expression (constantly nil)
              (fn [& _]
                (case operation
                  :basis-kind :ordinary
                  :order-hint 0
                  :all-permission-nodes #{}
                  :relation-defs []
                  :permission-defs []
                  :subject->resources []
                  :resource->subjects []
                  :snapshot-id {}
                  :native-revision {}
                  nil)))])
         backend/required-snapshot-operations)))

(defn- adapter
  ([direct-match]
   (backend/make-adapter
    {:id :scalar-test
     :capabilities {}
     :runtime-guards? true
     :operations (required-operations direct-match)}))
  ([direct-match batch]
   (backend/make-adapter
    {:id :native-test
     :capabilities
     {:direct-membership-batch
      #{backend/direct-membership-batch-capability}}
     :runtime-guards? true
     :operations (assoc (required-operations direct-match)
                        :direct-match-many? batch)})))

(def forward-request
  {:direction :forward
   :descriptor {:subject-type :user
                :subject-eid 10
                :relation-eid 20
                :resource-type :document}
   :candidates [[:document 0]
                [:document 2]
                [:document exact-integer/maximum]]})

(def reverse-request
  {:direction :reverse
   :descriptor {:resource-type :document
                :resource-eid 30
                :relation-eid 20
                :subject-type :user}
   :candidates [[:user 1] [:user 2] [:user 3]]})

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest scalar-fallback-is-aligned-in-both-directions-test
  (let [calls (atom [])
        scalar
        (adapter
         (fn [subject-type subject-eid relation-eid resource-type resource-eid]
           (swap! calls conj [subject-type subject-eid relation-eid
                              resource-type resource-eid])
           (contains? #{[10 0] [10 2] [2 30]}
                      [subject-eid resource-eid])))]
    (is (= [true true false]
           (direct/direct-match-many? scalar forward-request)))
    (is (= [false true false]
           (direct/direct-match-many? scalar reverse-request)))
    (is (= [[:user 10 20 :document 0]
            [:user 10 20 :document 2]
            [:user 10 20 :document exact-integer/maximum]
            [:user 1 20 :document 30]
            [:user 2 20 :document 30]
            [:user 3 20 :document 30]]
           @calls))))

(deftest native-and-scalar-results-are-identical-test
  (let [scalar-match
        (fn [_subject-type subject-eid _relation-eid
             _resource-type resource-eid]
          (zero? (mod (+ subject-eid resource-eid) 3)))
        scalar (adapter scalar-match)
        native
        (adapter scalar-match
                 (fn [{:keys [direction descriptor candidates]}]
                   (mapv
                    (fn [[_ candidate-eid]]
                      (if (= :forward direction)
                        (scalar-match
                         (:subject-type descriptor)
                         (:subject-eid descriptor)
                         (:relation-eid descriptor)
                         (:resource-type descriptor)
                         candidate-eid)
                        (scalar-match
                         (:subject-type descriptor)
                         candidate-eid
                         (:relation-eid descriptor)
                         (:resource-type descriptor)
                         (:resource-eid descriptor))))
                    candidates)))]
    (is (= (direct/direct-match-many? scalar forward-request)
           (direct/direct-match-many? native forward-request)))
    (is (= (direct/direct-match-many? scalar reverse-request)
           (direct/direct-match-many? native reverse-request)))))

(deftest request-boundaries-fail-before-provider-work-test
  (let [calls (atom 0)
        scalar (adapter (fn [& _] (swap! calls inc) true))]
    (doseq [request
            [(assoc forward-request :unknown true)
             (assoc forward-request :direction :sideways)
             (assoc-in forward-request [:descriptor :subject-eid] -1)
             (assoc forward-request :candidates [[:user 1]])
             (assoc forward-request :candidates [[:document 1]
                                                  [:document 1]])
             (assoc forward-request :candidates
                    (vec (repeat
                          (inc backend/maximum-direct-membership-batch-width)
                          [:document 1])))]]
      (is (= :eacl.backend/invalid-direct-membership-batch
             (:type (error-data
                     #(direct/direct-match-many? scalar request))))))
    (is (zero? @calls))))

(deftest cancellation-and-provider-failure-publish-no-partial-vector-test
  (let [token (execution/cancellation-token)
        contract (execution/normalize
                  {} :check-permissions
                  {:checks [] :cancellation-token token})
        calls (atom 0)
        scalar
        (adapter
         (fn [& _]
           (let [call (swap! calls inc)]
             (when (= 2 call)
               (throw (ex-info "injected" {:type :injected/provider})))
             true)))]
    (is (= :injected/provider
           (:type
            (error-data
             #(binding [execution/*contract* contract]
                (direct/direct-match-many?
                 scalar
                 (assoc forward-request
                        :candidates [[:document 1] [:document 2]])))))))
    (is (= 2 @calls))
    (reset! calls 0)
    (execution/cancel! token)
    (is (= :eacl.execution/cancelled
           (:type
            (error-data
             #(binding [execution/*contract* contract]
                (direct/direct-match-many? scalar forward-request))))))
    (is (zero? @calls))))

(deftest native-response-is-validated-atomically-test
  (doseq [[response obligation]
          [[[true] :aligned-cardinality]
           [[true :not-boolean false] :boolean-vector]]]
    (testing (name obligation)
      (let [native (adapter (constantly false) (constantly response))
            data (error-data
                  #(direct/direct-match-many? native forward-request))]
        (is (= :eacl/backend-contract-violation (:type data)))
        (is (contains? #{obligation :aligned-boolean-vector}
                       (:obligation data)))))))

(deftest selected-native-basis-does-not-follow-concurrent-head-test
  (let [selected #{0 2}
        head (atom selected)
        native
        (adapter
         (constantly false)
         (fn [{:keys [candidates]}]
           (reset! head #{exact-integer/maximum})
           (mapv #(contains? selected (second %)) candidates)))]
    (is (= [true true false]
           (direct/direct-match-many? native forward-request)))
    (is (= #{exact-integer/maximum} @head))))

(deftest dispatcher-elides-cache-hits-groups-deduplicates-and-scatters-test
  (let [requests (atom [])
        stats (atom {})
        native
        (adapter
         (constantly false)
         (fn [{:keys [candidates] :as request}]
           (swap! requests conj request)
           (mapv #(even? (second %)) candidates)))
        forward (:descriptor forward-request)
        reverse (:descriptor reverse-request)
        probes [{:direction :forward :descriptor forward
                 :candidate [:document 4]}
                {:direction :reverse :descriptor reverse
                 :candidate [:user 3]}
                {:direction :forward :descriptor forward
                 :candidate [:document 2]}
                {:direction :forward :descriptor forward
                 :candidate [:document 4]}
                {:direction :reverse :descriptor reverse
                 :candidate [:user 2]}]
        result
        (binding [direct/*physical-stats* stats]
          (direct/dispatch
           native probes
           (fn [probe]
             (if (= [:user 3] (:candidate probe))
               true
               direct/cache-miss))))]
    (is (= [true true true true true] result))
    (is (= 2 (count @requests)))
    (is (= [[:document 2] [:document 4]]
           (:candidates (first @requests))))
    (is (= [[:user 2]] (:candidates (second @requests))))
    (is (= 1 (:cache-hits @stats)))
    (is (= 2 (:physical-subgroups @stats)))
    (is (= 3 (:scalar-equivalent-predicates @stats)))
    (is (= 2 (:adapter-commands @stats)))
    (is (= 0 (:galloping-reseeks @stats)))
    (is (= 0 (:batch-overread @stats)))))

(deftest dispatcher-chunks-at-the-certified-width-test
  (let [widths (atom [])
        native
        (adapter
         (constantly true)
         (fn [{:keys [candidates]}]
           (swap! widths conj (count candidates))
           (vec (repeat (count candidates) true))))
        descriptor (:descriptor forward-request)
        probes
        (mapv (fn [eid]
                {:direction :forward
                 :descriptor descriptor
                 :candidate [:document eid]})
              (range (inc backend/maximum-direct-membership-batch-width)))]
    (is (every? true? (direct/dispatch native probes)))
    (is (= [backend/maximum-direct-membership-batch-width 1] @widths))))
