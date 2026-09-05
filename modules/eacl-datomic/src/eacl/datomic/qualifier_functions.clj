(ns eacl.datomic.qualifier-functions
  (:require [datomic.api :as d]))

(def assert-facts
  {:db/ident :eacl.fn/assert-qualifier-facts
   :db/fn
   (d/function
     {:lang "clojure" :params '[db eid expected] :requires '[[datomic.api :as d]]
      :code '(let [actual (mapv (fn [datom] [(:a datom) (:v datom) (:tx datom)])
                               (d/datoms db :eavt eid))]
               (when-not (= expected actual)
                 (throw (ex-info "Qualifier changed before publication."
                                 {:type :eacl.qualifier/staged-write :eacl/error :eacl.qualifier/staged-write
                                  :reason :qualifier-changed-at-commit})))
               [])})})
