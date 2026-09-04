(ns eacl.datascript.qualifier-storage-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as d]
            [eacl.datascript.core :as api]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.schema :as schema]
            [eacl.datascript.safe-retraction :as safe]
            [eacl.relationships.storage-contract :as contract]))

(defn direct-probe [& args]
  (let [calls (atom 0)
        [seek2 seek3 seek4 seek5 seek6]
        #?(:clj (repeat 5 d/seek-datoms)
           :cljs [(.-cljs$core$IFn$_invoke$arity$2 d/seek-datoms)
                  (.-cljs$core$IFn$_invoke$arity$3 d/seek-datoms)
                  (.-cljs$core$IFn$_invoke$arity$4 d/seek-datoms)
                  (.-cljs$core$IFn$_invoke$arity$5 d/seek-datoms)
                  (.-cljs$core$IFn$_invoke$arity$6 d/seek-datoms)])]
    (try
      ;; Preserve native multi-arity dispatch: advanced CLJS calls the arity
      ;; property directly, which neither a variadic nor single-arity fn has.
      ;; Capture each original entry point: the unoptimized dispatcher looks
      ;; up the global var again and would recurse through this wrapper.
      (with-redefs [d/seek-datoms
                    (fn
                      ([db index]
                       (swap! calls inc) (seek2 db index))
                      ([db index c0]
                       (swap! calls inc) (seek3 db index c0))
                      ([db index c0 c1]
                       (swap! calls inc) (seek4 db index c0 c1))
                      ([db index c0 c1 c2]
                       (swap! calls inc) (seek5 db index c0 c1 c2))
                      ([db index c0 c1 c2 c3]
                       (swap! calls inc) (seek6 db index c0 c1 c2 c3)))]
        (apply impl/direct-match? args))
      (finally (is (= 1 @calls) "one native seek per identity probe")))))

(deftest qualified-storage-fails-closed-and-cleans-exactly-test
  (let [conn (schema/create-conn)]
    (try
      (contract/exercise-qualified-corruption!
       {:client (api/make-client conn {}) :direct-probe direct-probe
          :read-identity impl/find-one-relationship-id
          :plan-create #(impl/tx-update-relationship %1 {:operation :create :relationship %2})
          :snapshot #(d/db conn)
        :transact! #(d/transact! conn %) :entid d/entid
        :stamp #(vector :db/add % :eacl/relation-version :db/current-tx)
        :rows #(d/datoms %1 :aevt %2) :safe-retract! #(d/transact! conn (safe/direct-retract-entity-tx-data %))})
      (finally nil))))
