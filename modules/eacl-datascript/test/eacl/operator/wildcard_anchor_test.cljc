(ns eacl.operator.wildcard-anchor-test
  "DataScript run of the wildcard anchor contract."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.operator-engine.wildcard-anchor-contract :as contract]
            [eacl.operator.plan :as operator-plan]))

(defn- store-on [conn]
  (fn [options]
    {:client (datascript/make-client conn options)
     :add-objects! (fn [ids]
                     (ds/transact! conn (mapv #(hash-map :eacl/id %) ids)))}))

(defn- new-store [options]
  ((store-on (datascript/create-conn)) options))

(deftest listing-work-is-independent-of-the-platform-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-listing-work-is-independent-of-the-platform!
     new-store fixture 50 500)))

(deftest listing-succeeds-under-the-default-limits-test
  ;; EACL-FORMAL-100: generating from the wildcard operand read 68 values per
  ;; ledger of the platform and passed the default 100,000 at 1,471 ledgers.
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-listing-succeeds-under-the-default-limits! new-store fixture 1500)))

(deftest answers-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-answers! new-store fixture 23)))

(deftest a-typed-anchor-answers-for-every-subject-type-test
  (contract/assert-a-typed-anchor-answers-for-every-subject-type! new-store))

(deftest a-last-page-of-a-recursive-anchor-needs-complete-evaluation-test
  (contract/assert-a-last-page-of-a-recursive-anchor-needs-complete-evaluation! new-store))

(deftest a-cursor-of-the-plan-another-anchor-seals-is-refused-test
  ;; A cursor carries its plan's fingerprint and the anchors are inside it:
  ;; a page of the plan that generated from the wildcard operand does not
  ;; continue on the plan that generates from `view`.
  (let [conn (datascript/create-conn)
        options {:security-key "wildcard-anchor-cursor-test00000"}
        _ (contract/build! (store-on conn) :named 23)
        client #(datascript/make-client conn options)
        query {:subject (eacl/spice-object :user "platform") :permission :open
               :resource/type :ledger :first 2}
        cursor-of #(get-in (eacl/lookup-resources % query) [:page-info :end-cursor])
        structural-cursor
        (with-redefs [operator-plan/select-intersection-anchor
                      (fn [children costs]
                        (first (sort-by #(get-in costs [% :tuple]) children)))]
          (cursor-of (client)))
        current (client)
        continue #(eacl/lookup-resources current (assoc query :after %))]
    (is (= 2 (count (:data (continue (cursor-of current))))))
    (is (= {:eacl/error :eacl.pagination/invalid-cursor :reason :operator-scope-mismatch}
           (try (continue structural-cursor)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                  (select-keys (ex-data error) [:eacl/error :reason])))))))
