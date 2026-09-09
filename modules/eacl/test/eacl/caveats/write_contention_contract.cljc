(ns eacl.caveats.write-contention-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.core :as eacl]
            [eacl.relationships.staged :as staged]))

(defn check! [client writer]
  (eacl/write-schema! client "definition user {}
 definition doc {
   relation viewer: user
   permission view = viewer
 }")
  ((get-in (writer) [:native :transact!]) [{:eacl/id "race/user"} {:eacl/id "race/a"} {:eacl/id "race/b"}])
  (let [subject (eacl/spice-object :user "race/user")
        a (eacl/spice-object :doc "race/a") b (eacl/spice-object :doc "race/b")
        original staged/plan-batch-current
        interleave? (atom true) calls (atom 0)]
    (with-redefs [staged/plan-batch-current
                  (fn [writer prepared app-data]
                    (swap! calls inc)
                    (let [plan (original writer prepared app-data)]
                      (when (compare-and-set! interleave? true false)
                        (eacl/create-relationship! client (eacl/->Relationship subject :viewer b)))
                      plan))]
      (eacl/create-relationship! client (eacl/->Relationship subject :viewer a)))
    (is (= 3 @calls) "one outer plan, one competing commit, and exactly one fresh retry")
    (is (true? (eacl/can? client {:subject subject :permission :view :resource a})))
    (is (true? (eacl/can? client {:subject subject :permission :view :resource b})))
    (reset! calls 0)
    (let [failure (with-redefs [staged/plan-batch-current
                                (fn [& _] (swap! calls inc)
                                  (throw (ex-info "Concurrent native write" {:error :transact/cas})))]
                    (try (eacl/delete-relationship! client (eacl/->Relationship subject :viewer a)) nil
                         (catch #?(:clj Throwable :cljs :default) error (ex-data error))))]
      (is (= 8 @calls) "contention retries have a fixed upper bound")
      (is (= :eacl/relationship-contention (:type failure)))
      (is (true? (eacl/can? client {:subject subject :permission :view :resource a}))))))

(defn terminal-validation-check! [client]
  (let [calls (atom 0)
        failure (with-redefs [staged/plan-batch-current
                              (fn [& _] (swap! calls inc) (staged/error! :qualifier-changed-at-commit))]
                  (try (eacl/delete-relationship!
                        client (eacl/->Relationship (eacl/spice-object :user "race/user")
                                                    :viewer (eacl/spice-object :doc "race/a"))) nil
                       (catch #?(:clj Throwable :cljs :default) error (ex-data error))))]
    (is (= 1 @calls) "validation faults are not retried as contention")
    (is (= :qualifier-changed-at-commit (:reason failure)))))
