(ns eacl.client.lookahead-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is]])
            [eacl.client.lookahead :as lookahead]))

(deftest option-validation-test
  (is (nil? (lookahead/validate-option! nil)))
  (is (= {:pages 2 :max-inflight 4}
         (lookahead/validate-option! {:pages 2 :max-inflight 4})))
  (doseq [bad [true 3 {:pages 1} {:pages 0 :max-inflight 1}
               {:pages 1 :max-inflight -1} {:pages 1 :max-inflight 1 :x 1}]]
    (is (= :eacl/invalid-config
           (try (lookahead/validate-option! bad) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:type (ex-data e)))))
        (str "rejects " (pr-str bad)))))

(deftest continuation-request-test
  (let [request {:subject :s :permission :view :resource/type :doc :first 20
                 :timeout-ms 5 :cache? true}
        page {:data [] :page-info {:end-cursor "c1" :has-next-page? true}}]
    (is (= {:subject :s :permission :view :resource/type :doc :first 20
            :cache? true :after "c1"}
           (lookahead/continuation-request request page)))
    (is (nil? (lookahead/continuation-request
               request (assoc-in page [:page-info :has-next-page?] false))))
    (is (nil? (lookahead/continuation-request
               (assoc request :cache? false) page)))
    (is (nil? (lookahead/continuation-request
               (-> request (dissoc :first) (assoc :last 20)) page)))
    (is (nil? (lookahead/continuation-request
               request (assoc-in page [:page-info :end-cursor] nil))))))

#?(:clj
   (deftest submit-runs-the-continuation-in-the-background-test
     (let [state (lookahead/state {:pages 2 :max-inflight 2})
           ran (promise)
           depths (atom [])
           page {:data [1] :page-info {:end-cursor "c1" :has-next-page? true}}
           request {:first 1 :cache? true}]
       (is (true? (lookahead/submit! state :lookup-resources request page
                                     (fn [continuation]
                                       (swap! depths conj lookahead/*depth*)
                                       (deliver ran continuation))
                                     nil)))
       (is (= (assoc request :after "c1") (deref ran 5000 :timeout)))
       (is (= [1] @depths) "the continuation runs at depth one")
       (testing "the same continuation is not submitted twice while in flight"
         (let [gate (promise)
               started (promise)
               state (lookahead/state {:pages 1 :max-inflight 2})]
           (is (true? (lookahead/submit! state :lookup-resources request page
                                         (fn [_] (deliver started true) @gate)
                                         nil)))
           (is (true? (deref started 5000 false)))
           (is (false? (lookahead/submit! state :lookup-resources request page
                                          (fn [_] nil) nil)))
           (deliver gate true)))
       (testing "depth budget"
         (binding [lookahead/*depth* 2]
           (is (nil? (lookahead/submit! state :lookup-resources request page
                                        (fn [_] nil) nil)))))
       (testing "failures reach only the report function"
         (let [reported (promise)
               state (lookahead/state {:pages 1 :max-inflight 1})]
           (is (true? (lookahead/submit! state :lookup-resources request page
                                         (fn [_] (throw (ex-info "boom" {:type :test/boom})))
                                         (fn [report] (deliver reported report)))))
           (is (= {:operation :lookup-resources :provenance :lookahead
                   :depth 1 :error :test/boom}
                  (deref reported 5000 :timeout))))))))

#?(:clj
   (deftest saturated-executor-drops-submissions-test
     (let [state (lookahead/state {:pages 1 :max-inflight 1})
           gate (promise)
           started (promise)
           page-for (fn [cursor] {:data [1] :page-info {:end-cursor cursor :has-next-page? true}})]
       (is (true? (lookahead/submit! state :lookup-resources {:first 1} (page-for "a")
                                     (fn [_] (deliver started true) @gate) nil)))
       (is (true? (deref started 5000 false)))
       (is (false? (lookahead/submit! state :lookup-resources {:first 1} (page-for "b")
                                      (fn [_] nil) nil))
           "a second distinct continuation finds no free thread and is dropped")
       (deliver gate true))))
