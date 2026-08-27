(ns eacl.verified-kernel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eacl.verified-kernel :as verified]))

(def valid-authorization-input
  {:objects [{:type "user" :id "u1"}
             {:type "document" :id "d1"}]
   :schema
   {:relations [{:resource-type "document"
                 :relation "viewer"
                 :subject-type "user"}]
    :permissions [{:resource-type "document"
                   :permission "view"}]
    :definitions [{:kind :direct-relation
                   :resource-type "document"
                   :permission "view"
                   :relation "viewer"
                   :subject-type "user"}]}
   :relationships
   [{:subject {:type "user" :id "u1"}
     :relation "viewer"
     :resource {:type "document" :id "d1"}}]
   :request {:operation :can?
             :subject {:type "user" :id "u1"}
             :permission "view"
             :resource {:type "document" :id "d1"}}
   :limits {:max-derived-grants 100
            :max-advanced-datoms 100
            :max-queued-work 100}})

(def valid-routing-certificate-input
  {:node-count 2
   :path-descriptors
   [{:kind :self-permission :head 0 :target 1}
    {:kind :arrow-permission :head 1 :target 1}]
   :edges [{:head 0 :target 1}
           {:head 1 :target 1}]
   :certificate
   {:component-root [0 1]
    :forward-parent-edge [-1 -1]
    :reverse-parent-edge [-1 -1]
    :forward-depth [0 0]
    :reverse-depth [0 0]
    :component-rank [0 1]
    :multiple-member-witness [-1 -1]
    :self-loop-witness-edge [-1 1]
    :traversal [true true]
    :traversal-witness-edge [0 -1]}})

(defrecord FunctionKernel [f]
  verified/DecisionKernel
  (-decide [_ operation input]
    (f operation input)))

(deftest normalized-kernel-selection-is-reused
  (let [selection
        {:kernel (->FunctionKernel (fn [_ _] :use-exact-entry))}]
    (is (identical? selection (verified/normalize-selection selection)))
    (is (= selection
           (verified/normalize-selection (:kernel selection))))))

(deftest routing-certificate-result-is-bound-to-its-input
  (let [accepted
        {:status :accepted
         :traversal [true true]
         :path-checks 2
         :node-checks 4
         :edge-checks 2}
        decide
        (fn [result]
          (verified/decide
           {:kernel (->FunctionKernel (fn [_ _] result))}
           :recursive-routing-certificate
           valid-routing-certificate-input))]
    (is (= accepted (decide accepted)))
    (is
     (thrown?
      #?(:clj clojure.lang.ExceptionInfo
         :cljs cljs.core.ExceptionInfo)
      (verified/decide
       {:kernel (->FunctionKernel (fn [_ _] accepted))}
       :recursive-routing-certificate
       (assoc-in
        valid-routing-certificate-input
        [:path-descriptors 0]
        {:kind :relation :head 0 :target 1}))))
    (doseq [result
            [(assoc accepted :traversal [true])
             (assoc accepted :path-checks 1)
             (assoc accepted :node-checks 3)
             (assoc accepted :edge-checks 1)
             {:status :rejected
              :reason :invalid-component-witness
              :path-checks 2
              :node-checks 5
              :edge-checks 2}
             {:status :rejected
              :reason :invalid-dependency-edge
              :path-checks 2
              :node-checks 2
              :edge-checks 3}]]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (decide result))))))

(deftest current-cache-stage-boundary-is-strict
  (let [input {:stage :exact-entry :available? true}
        decide
        (fn [kernel value]
          (verified/decide
           {:kernel kernel}
           :current-cache-decision
           value))]
    (is (= :use-exact-entry
           (decide
            (->FunctionKernel
             (fn [_ _] :use-exact-entry))
            input)))
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (decide
          (->FunctionKernel
           (fn [_ _] :use-exact-entry))
          (assoc input :unknown true))))
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (decide
          (->FunctionKernel
           (fn [_ _] :use-managed-entry))
          input)))))

(defn- expected-consistency-plan
  [{:keys [mode capability-supported?]}]
  (cond
    (not capability-supported?)
    (case mode
      :minimize-latency :unsupported-capability
      :at-exact-snapshot :exact-snapshot-unavailable
      :unsupported-head-barrier)

    :else
    (case mode
      :minimize-latency :select-current
      :fully-consistent :select-authoritative
      :at-least-as-fresh :authenticate-and-select-at-least
      :at-exact-snapshot :authenticate-and-select-exact)))

(defn- expected-consistency-validation
  [{:keys [kind selection-present? selected-adapter?
           same-source-scope? revision-satisfied?]}]
  (cond
    (not selection-present?)
    (if (= :exact kind)
      :exact-snapshot-unavailable
      :invalid-selected-adapter)

    (not selected-adapter?) :invalid-selected-adapter
    (not same-source-scope?) :incomparable-scope
    (and (#{:at-least :exact} kind)
         (not revision-satisfied?))
    :history-divergence
    :else :accept))

(deftest consistency-boundaries-are-total-and-strict
  (testing "all plan observations have one exact admissible decision"
    (doseq [mode
            [:minimize-latency :fully-consistent
             :at-least-as-fresh :at-exact-snapshot]
            capability-supported? [false true]]
      (let [input {:mode mode
                   :capability-supported? capability-supported?}
            expected (expected-consistency-plan input)]
        (is (= expected
               (verified/decide
                {:kernel
                 (->FunctionKernel (fn [_ _] expected))}
                :consistency-plan
                input))))))
  (testing "all well-formed post-selection observations are exact"
    (doseq [kind [:current :authoritative :at-least :exact]
            selection-present? [false true]
            selected-adapter? [false true]
            :when (or selection-present? (not selected-adapter?))
            same-source-scope? [false true]
            revision-satisfied? [false true]]
      (let [input {:kind kind
                   :selection-present? selection-present?
                   :selected-adapter? selected-adapter?
                   :same-source-scope? same-source-scope?
                   :revision-satisfied? revision-satisfied?}
            expected (expected-consistency-validation input)]
        (is (= expected
               (verified/decide
                {:kernel
                 (->FunctionKernel (fn [_ _] expected))}
                :consistency-validation
                input))))))
  (testing "snapshot absence and a malformed present value remain distinct"
    (let [absent {:kind :exact
                  :selection-present? false
                  :selected-adapter? false
                  :same-source-scope? false
                  :revision-satisfied? false}
          malformed (assoc absent
                           :selection-present? true)]
      (is (= :exact-snapshot-unavailable
             (expected-consistency-validation absent)))
      (is (= :invalid-selected-adapter
             (expected-consistency-validation malformed)))))
  (testing "unknown fields, impossible observations, and lying kernels fail"
    (let [plan {:mode :minimize-latency
                :capability-supported? true}
          validation {:kind :exact
                      :selection-present? true
                      :selected-adapter? true
                      :same-source-scope? true
                      :revision-satisfied? true}
          decide
          (fn [operation input result]
            (verified/decide
             {:kernel (->FunctionKernel (fn [_ _] result))}
             operation input))]
      (doseq [[operation input result]
              [[:consistency-plan
                (assoc plan :unknown true)
                :select-current]
               [:consistency-plan
                plan
                :select-authoritative]
               [:consistency-validation
                (assoc validation
                       :selection-present? false)
                :accept]
               [:consistency-validation
                validation
                :history-divergence]]]
        (is (thrown?
             #?(:clj clojure.lang.ExceptionInfo
                :cljs cljs.core.ExceptionInfo)
             (decide operation input result)))))))

(deftest subproblem-cache-transition-boundary-is-strict
  (let [input {:decision :lookup
               :candidate :missing}
        decide
        (fn [input result]
          (verified/decide
           {:kernel (->FunctionKernel (fn [_ _] result))}
           :subproblem-cache-decision
           input))]
    (is (= :start-independent-computation
           (decide input :start-independent-computation)))
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (decide input :use-completed-value)))
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (decide
          {:decision :publication
           :ticket-current? true
           :complete? true
           :valid? true
           :weight 1
           :budget 1}
          :drop-publication)))))

(deftest ordered-merge-chunk-boundary-is-strict
  (let [input {:direction :asc
               :left [1 3 5]
               :right [2 4 6]}
        result {:values [1 2 3 4 5]
                :left-consumed 3
                :right-consumed 2}
        decide
        (fn [input result]
          (verified/decide
           {:kernel (->FunctionKernel (fn [_ _] result))}
           :ordered-merge-chunk
           input))]
    (is (= result (decide input result)))
    (doseq [invalid-input
            [(assoc input :unknown true)
             (assoc input :left [3 1])
             (assoc input :right [2 2])
             (assoc input :left [-1 3])]]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (decide invalid-input result))))
    (doseq [invalid-result
            [(assoc result :left-consumed 4)
             (assoc result :right-consumed -1)
             {:values [1]
              :left-consumed 1
              :right-consumed 0}
             (assoc result :values [1 2 4 5])
             (assoc result :unknown true)]]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (decide input invalid-result))))
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (decide
          {:direction :asc :left [] :right [2 4]}
          {:values [2] :left-consumed 0 :right-consumed 1})))))

(deftest keyset-page-boundary-enforces-one-row-lookahead
  (let [input {:direction :asc
               :size 20
               :bound? false
               :realized-count 21}
        result {:take-count 20
                :reverse? false
                :has-next? true
                :has-previous? false}
        kernel (->FunctionKernel (fn [_ _] result))
        decide
        #(verified/decide
          {:kernel kernel}
          :relationship-keyset-page
          %)]
    (is (= result (decide input)))
    (doseq [invalid
            [(assoc input :unknown true)
             (assoc input :size 0)
             (assoc input :realized-count 22)]]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (decide invalid))))
    (let [oversized-result-kernel
          (->FunctionKernel
           (fn [_ _]
             (assoc result :take-count 21)))]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (verified/decide
            {:kernel oversized-result-kernel}
            :relationship-keyset-page
            input))))))

(deftest full-authorization-boundary-is-strict
  (let [result {:status :complete
                :operation :can?
                :allowed? true
                :counters {:derived-grants 1
                           :advanced-datoms 1
                           :queued-work 1}}
        kernel (->FunctionKernel (fn [_ _] result))
        decide
        #(verified/decide
          {:kernel kernel}
          :authorization-evaluation
          %)]
    (is (= result (decide valid-authorization-input)))
    (doseq [invalid
            [(assoc valid-authorization-input :unknown true)
             (assoc-in valid-authorization-input
                       [:objects 0 :type] "")
             (update-in valid-authorization-input
                        [:schema :definitions 0]
                        assoc :unknown true)
             (assoc-in valid-authorization-input
                       [:request :permission] :view)
             (assoc-in valid-authorization-input
                       [:objects 0 :id]
                       (apply str (repeat 65537 "x")))
             (assoc-in valid-authorization-input
                       [:limits :max-derived-grants] 0)]]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (decide invalid))))
    (let [invalid-result-kernel
          (->FunctionKernel
           (fn [_ _]
             (assoc result :raw-object {:type "user" :id "u1"})))]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (verified/decide
            {:kernel invalid-result-kernel}
            :authorization-evaluation
            valid-authorization-input))))))
