(ns eacl.verified-kernel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eacl.verified-kernel :as verified]))

(def valid-cache-input
  {:deterministic? true
   :dependency-scope-nonempty? true
   :expected-key "key"
   :expected-source "source"
   :selected-graph 0
   :ancestors #{1}
   :selected-proof "proof"
   :entry {:status :candidate
           :authenticated? true
           :key "key"
           :source "source"
           :graph 1
           :proof "proof"}})

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

(defrecord FunctionKernel [f]
  verified/DecisionKernel
  (-decide [_ operation input]
    (f operation input)))

(deftest strict-boundary-rejects-unknown-and-unsafe-values
  (let [kernel
        (->FunctionKernel
         (fn [_ _]
           {:status :hit :provenance :exact-hit}))]
    (doseq [input
            [(assoc valid-cache-input :unknown true)
             (assoc valid-cache-input
                    :selected-graph 9007199254740992)
             (assoc-in valid-cache-input [:entry :proof] 7)]]
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           #"boundary"
           (verified/decide
            {:mode :verified-authoritative :kernel kernel}
            :cache-validation
            input
            #(throw (ex-info "legacy must not run" {})))))))
  (let [kernel
        (->FunctionKernel
         (fn [_ _]
           {:status :hit
            :provenance :exact-hit
            :unknown true}))]
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (verified/decide
          {:mode :verified-authoritative :kernel kernel}
          :cache-validation
          valid-cache-input
          #(throw (ex-info "legacy must not run" {})))))))

(deftest authoritative-kernel-controls-the-result
  (let [kernel
        (->FunctionKernel
         (fn [_ _]
           {:status :miss :reason :proof-mismatch}))]
    (is (= {:status :miss :reason :proof-mismatch}
           (verified/decide
            {:mode :verified-authoritative :kernel kernel}
            :cache-validation
            valid-cache-input
            (constantly
             {:status :hit :provenance :exact-hit}))))))

(deftest current-cache-stage-boundary-is-strict
  (let [input {:stage :exact-entry :available? true}
        decide
        (fn [kernel value]
          (verified/decide
           {:mode :verified-authoritative :kernel kernel}
           :current-cache-decision
           value
           #(throw (ex-info "legacy must not run" {}))))]
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
  [{:keys [mode capability-supported? managed-authority?]}]
  (cond
    (not capability-supported?)
    (case mode
      (:local-snapshot :minimize-latency) :unsupported-capability
      :at-exact-snapshot :exact-snapshot-unavailable
      :unsupported-head-barrier)

    (and (#{:at-least-as-fresh :at-exact-snapshot} mode)
         (not managed-authority?))
    :unsupported-head-barrier

    :else
    (case mode
      (:local-snapshot :minimize-latency) :select-current
      (:fully-consistent :synchronized-head) :select-authoritative
      :at-least-as-fresh :authenticate-and-select-at-least
      :at-exact-snapshot :authenticate-and-select-exact)))

(defn- expected-consistency-validation
  [{:keys [kind selection-present? selected-adapter?
           same-source-scope? anchor-satisfied?]}]
  (cond
    (not selection-present?)
    (if (= :exact kind)
      :exact-snapshot-unavailable
      :invalid-selected-adapter)

    (not selected-adapter?) :invalid-selected-adapter
    (not same-source-scope?) :incomparable-scope
    (and (#{:at-least :exact} kind)
         (not anchor-satisfied?))
    :history-divergence
    :else :accept))

(deftest consistency-boundaries-are-total-and-strict
  (testing "all plan observations have one exact admissible decision"
    (doseq [mode
            [:local-snapshot :minimize-latency
             :fully-consistent :synchronized-head
             :at-least-as-fresh :at-exact-snapshot]
            capability-supported? [false true]
            managed-authority? [false true]]
      (let [input {:mode mode
                   :capability-supported? capability-supported?
                   :managed-authority? managed-authority?}
            expected (expected-consistency-plan input)]
        (is (= expected
               (verified/decide
                {:mode :verified-authoritative
                 :kernel
                 (->FunctionKernel (fn [_ _] expected))}
                :consistency-plan
                input
                #(throw (ex-info "legacy must not run" {}))))))))
  (testing "all well-formed post-selection observations are exact"
    (doseq [kind [:current :authoritative :at-least :exact]
            selection-present? [false true]
            selected-adapter? [false true]
            :when (or selection-present? (not selected-adapter?))
            same-source-scope? [false true]
            anchor-satisfied? [false true]]
      (let [input {:kind kind
                   :selection-present? selection-present?
                   :selected-adapter? selected-adapter?
                   :same-source-scope? same-source-scope?
                   :anchor-satisfied? anchor-satisfied?}
            expected (expected-consistency-validation input)]
        (is (= expected
               (verified/decide
                {:mode :verified-authoritative
                 :kernel
                 (->FunctionKernel (fn [_ _] expected))}
                :consistency-validation
                input
                #(throw (ex-info "legacy must not run" {}))))))))
  (testing "snapshot absence and a malformed present value remain distinct"
    (let [absent {:kind :exact
                  :selection-present? false
                  :selected-adapter? false
                  :same-source-scope? false
                  :anchor-satisfied? false}
          malformed (assoc absent
                           :selection-present? true)]
      (is (= :exact-snapshot-unavailable
             (expected-consistency-validation absent)))
      (is (= :invalid-selected-adapter
             (expected-consistency-validation malformed)))))
  (testing "unknown fields, impossible observations, and lying kernels fail"
    (let [plan {:mode :local-snapshot
                :capability-supported? true
                :managed-authority? false}
          validation {:kind :exact
                      :selection-present? true
                      :selected-adapter? true
                      :same-source-scope? true
                      :anchor-satisfied? true}
          decide
          (fn [operation input result]
            (verified/decide
             {:mode :verified-authoritative
              :kernel (->FunctionKernel (fn [_ _] result))}
             operation input
             #(throw (ex-info "legacy must not run" {}))))]
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
               :recursive-self? false
               :candidate :missing}
        decide
        (fn [input result]
          (verified/decide
           {:mode :verified-authoritative
            :kernel (->FunctionKernel (fn [_ _] result))}
           :subproblem-cache-decision
           input
           #(throw (ex-info "legacy must not run" {}))))]
    (is (= :start-computation
           (decide input :start-computation)))
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (decide input :bypass-recursive-self)))
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
           {:mode :verified-authoritative
            :kernel (->FunctionKernel (fn [_ _] result))}
           :ordered-merge-chunk
           input
           #(throw (ex-info "legacy must not run" {}))))]
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
          {:mode :verified-authoritative :kernel kernel}
          :relationship-keyset-page
          %
          (constantly nil))]
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
            {:mode :verified-authoritative
             :kernel oversized-result-kernel}
            :relationship-keyset-page
            input
            (constantly nil)))))))

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
          {:mode :verified-authoritative :kernel kernel}
          :authorization-evaluation
          % (constantly nil))]
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
            {:mode :verified-authoritative
             :kernel invalid-result-kernel}
            :authorization-evaluation
            valid-authorization-input
            (constantly nil)))))))

(deftest shadow-never-alters-legacy-result
  (testing "disagreement"
    (let [diagnostics (atom [])
          legacy {:status :hit :provenance :exact-hit}
          kernel
          (->FunctionKernel
           (fn [_ _]
             {:status :miss :reason :proof-mismatch}))]
      (is (= legacy
             (verified/decide
              {:mode :verified-shadow
               :kernel kernel
               :report-divergence #(swap! diagnostics conj %)}
              :cache-validation
              valid-cache-input
              (constantly legacy))))
      (is (= :eacl.verification/shadow-divergence
             (:type (first @diagnostics))))
      (is (= [:provenance :reason :status]
             (:changed-fields (first @diagnostics))))
      (is (= {:status :hit :provenance :exact-hit}
             (:legacy-variant (first @diagnostics))))
      (is (= {:status :miss :reason :proof-mismatch}
             (:verified-variant (first @diagnostics))))
      (is (nil? (:input-digest (first @diagnostics))))
      (is (nil? (:legacy (first @diagnostics))))
      (is (nil? (:verified (first @diagnostics))))))
  (testing "kernel failure"
    (let [diagnostics (atom [])
          legacy {:status :miss :reason :missing}
          kernel
          (->FunctionKernel
           (fn [_ _]
             (throw (ex-info "broken provider" {}))))]
      (is (= legacy
             (verified/decide
              {:mode :verified-shadow
               :kernel kernel
               :report-divergence #(swap! diagnostics conj %)}
              :cache-validation
              valid-cache-input
              (constantly legacy))))
      (is (= :eacl.verification/shadow-kernel-failure
             (:type (first @diagnostics)))))))

(deftest stateful-shadow-comparison-is-redacted-and-never-authoritative
  (let [diagnostics (atom [])
        kernel (->FunctionKernel (fn [_ _] nil))
        legacy {:data [{:type :folder :id 41}]
                :page-info {:has-next-page? false}}
        selection
        {:mode :verified-shadow
         :kernel kernel
         :report-divergence #(swap! diagnostics conj %)}]
    (is (= legacy
           (verified/compare-shadow!
            selection
            :indexed-forward-page
            legacy
            (constantly
             {:data [{:type :folder :id 99}]
              :page-info {:has-next-page? true}}))))
    (is (= {:type :eacl.verification/shadow-divergence
            :operation :indexed-forward-page
            :changed-fields [:data :page-info]
            :legacy-variant {}
            :verified-variant {}}
           (first @diagnostics)))
    (is (nil? (:legacy (first @diagnostics))))
    (is (nil? (:verified (first @diagnostics)))))
  (testing "generated failure is fail-open and value-free"
    (let [diagnostics (atom [])
          legacy true
          kernel (->FunctionKernel (fn [_ _] nil))]
      (is (true?
           (verified/compare-shadow!
            {:mode :verified-shadow
             :kernel kernel
             :report-divergence #(swap! diagnostics conj %)}
            :indexed-forward-boolean
            legacy
            #(throw
              (ex-info
               "sensitive authorization value"
               {:type :generated-failure
                :secret "must-not-escape"})))))
      (is (= {:type :eacl.verification/shadow-kernel-failure
              :operation :indexed-forward-boolean
              :error-type :generated-failure}
             (first @diagnostics)))))
  (testing "non-shadow modes never pay for the duplicate traversal"
    (let [called? (atom false)]
      (is (= :legacy
             (verified/compare-shadow!
              :legacy-authoritative
              :indexed-forward-page
              :legacy
              #(do (reset! called? true) :generated))))
      (is (false? @called?)))))
