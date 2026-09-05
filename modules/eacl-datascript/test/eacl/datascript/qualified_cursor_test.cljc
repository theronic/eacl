(ns eacl.datascript.qualified-cursor-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.context-test :as errors]
            [eacl.authorization.qualification :as qualification]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.datascript.qualified-lookup-test :as fixtures]))

(defn query [check direction permission]
  (assoc (fixtures/count-query (assoc check :permission permission) direction :detailed {"flag" true})
         :first 1 :evaluation :complete-denotation))

(defn resume [client direction query page]
  (fixtures/lookup! client direction (assoc query (if (:last query) :before :after)
                                            (get-in page [:page-info (if (:last query) :start-cursor :end-cursor)]))))

(deftest live-cursors-stop-at-expiry-and-pinned-cursors-retain-their-time
  (doseq [direction [:forward :reverse] evaluation [:demand :complete-denotation] order [:first :last]]
    (let [{:keys [client check now]} (fixtures/mixed-fixture direction 6)]
      (binding [orchestration/*qualified-authorization-enabled?* true]
        (let [query (assoc (dissoc (query check direction :direct) :first) order 1 :evaluation evaluation)
              pinned (eacl/snapshot client)]
          (try
            (let [live (fixtures/lookup! client direction query)
                  historical (fixtures/lookup! pinned direction query)]
              (is (seq (:data live)))
              (is (not (contains? live :qualification-certificate)))
              (reset! now 150)
              (let [next-page (resume client direction query live)]
                (is (seq (:data next-page)))
                (is (not= (:data live) (:data next-page)))
                (reset! now 200)
                (is (= :eacl.pagination/restart-required
                       (:type (errors/error-data #(resume client direction query next-page))))))
              (is (= :eacl.pagination/restart-required
                     (:type (errors/error-data #(resume client direction query live)))))
              (is (seq (:data (resume pinned direction query historical))))
              (is (empty? (:data (fixtures/lookup! client direction query))))
              (is (some? (:type (errors/error-data #(resume client direction query historical)))))
              (is (some? (:type (errors/error-data #(resume pinned direction query live))))))
            (finally (eacl/release! pinned))))))))

(deftest an-expiring-ban-before-the-boundary-requires-a-new-lookup
  (doseq [direction [:forward :reverse]
          cached? [true false] evaluation [:demand :complete-denotation]]
    (let [{:keys [client check now]} (fixtures/mixed-fixture direction 6)]
      (binding [orchestration/*qualified-authorization-enabled?* true]
        (let [query (assoc (query check direction :both) :cache? cached? :evaluation evaluation)
              first-page (fixtures/lookup! client direction query)
              initial-object (get-in first-page [:data 0 :object])]
          (is (seq (:data first-page)))
          (is (not= (get check (if (= :forward direction) :resource :subject)) initial-object))
          (reset! now 100)
          (is (= :eacl.pagination/restart-required
                 (:type (errors/error-data #(resume client direction query first-page)))))
          (is (= (get check (if (= :forward direction) :resource :subject))
                 (get-in (fixtures/lookup! client direction query) [:data 0 :object]))))))))

(deftest context-and-result-policy-mismatches-fail-before-qualification
  (let [{:keys [client check]} (fixtures/mixed-fixture :forward 6)]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (let [query (query check :forward :direct)
            page (fixtures/lookup! client :forward query)
            reads (atom 0)
            request-from-adapter qualification/request-from-adapter]
        (with-redefs [qualification/qualify (fn [& _] (swap! reads inc) (throw (ex-info "Unexpected traversal" {})))]
          (doseq [changed [(assoc query :caveat-context {"flag" false})
                           (assoc query :caveat-context {"flag" true "unused" 1})
                           (assoc query :result-policy :definite)]]
            (is (some? (:type (errors/error-data #(resume client :forward changed page)))))))
        (with-redefs [qualification/request-from-adapter
                      (fn [adapter options]
                        (let [engine (:evaluator options)
                              changed (reify evaluator/Evaluator
                                        (descriptor [_] (assoc (evaluator/descriptor engine) :fingerprint "test/changed-evaluator"))
                                        (-evaluate [_ entity context bound] (evaluator/-evaluate engine entity context bound)))]
                          (request-from-adapter adapter (assoc options :evaluator changed))))
                      qualification/qualify (fn [& _] (swap! reads inc) (throw (ex-info "Unexpected traversal" {})))]
          (is (some? (:type (errors/error-data #(resume client :forward query page))))))
        (is (zero? @reads))))))

(deftest incomplete-certificates-allow-only-the-original-time-without-proof-building
  (let [{:keys [client check now]} (fixtures/mixed-fixture :forward 6)
        certificate qualification/certificate]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (with-redefs [qualification/certificate #(assoc (certificate %) :complete? false)]
        (let [query (query check :forward :direct)
              page (fixtures/lookup! client :forward query)]
          (is (seq (:data (resume client :forward query page))))
          (reset! now 100)
          (let [reads (atom 0)
                failure (with-redefs [qualification/qualify (fn [& _] (swap! reads inc) (throw (ex-info "Unexpected traversal" {})))]
                          (errors/error-data #(resume client :forward query page)))]
            (is (= :eacl.pagination/restart-required (:type failure)))
            (is (= :temporal-certificate-incomplete (:reason failure)))
            (is (zero? @reads))))))))

(deftest cached-range-pages-retain-evidence-deadlines
  (doseq [direction [:forward :reverse]]
    (let [{:keys [client check now]} (fixtures/mixed-fixture direction 8)]
      (binding [orchestration/*qualified-authorization-enabled?* true]
        (let [query (query check direction :walk)
              _ (fixtures/lookup! client direction (assoc query :first 5))
              page (fixtures/lookup! client direction query)]
          (is (:cached? page))
          (is (seq (:data page)))
          (reset! now 200)
          (is (= :eacl.pagination/restart-required
                 (:type (errors/error-data #(resume client direction query page))))))))))

(deftest stored-inspection-cursors-are-timeless-but-active-inspection-is-certified
  (doseq [state [:stored :expiry-active]]
    (let [{:keys [client now]} (fixtures/mixed-fixture :forward 6)]
      (binding [orchestration/*qualified-authorization-enabled?* true]
        (let [query {:resource/type :folder :relationship-state state :first 1}
              page (eacl/read-relationships client query)]
          (is (seq (:data page)))
          (reset! now 200)
          (let [next-query (assoc query :after (get-in page [:page-info :end-cursor]))]
            (if (= :stored state)
              (is (seq (:data (eacl/read-relationships client next-query))))
              (is (= :eacl.pagination/restart-required
                     (:type (errors/error-data #(eacl/read-relationships client next-query))))))))))))
