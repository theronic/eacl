(ns eacl.datomic.wildcard-anchor-test
  "Datomic run of the wildcard anchor contract."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [datomic.api :as d]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :as helpers]
            [eacl.datomic.schema :as schema]
            [eacl.operator-engine.wildcard-anchor-contract :as contract]))

(def ^:private stores (atom []))

(defn- new-store [options]
  (let [uri (str "datomic:mem://wildcard-anchor-" (java.util.UUID/randomUUID))
        _ (assert (true? (d/create-database uri)))
        conn (d/connect uri)]
    (swap! stores conj [uri conn])
    (helpers/install-fixture-schema! conn schema/v8-schema)
    {:client (datomic/make-client
              conn (assoc options :security-key "datomic-wildcard-anchor-test0000"))
     :add-objects! (fn [ids]
                     @(d/transact conn (mapv #(hash-map :eacl/id %) ids)))}))

(use-fixtures :each
  (fn [test]
    (try
      (test)
      (finally
        (doseq [[uri conn] @stores]
          (d/release conn)
          (d/delete-database uri))
        (reset! stores [])))))

(deftest listing-work-is-independent-of-the-platform-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-listing-work-is-independent-of-the-platform!
     new-store fixture 50 500)))

(deftest listing-succeeds-under-the-default-limits-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-listing-succeeds-under-the-default-limits! new-store fixture 1500)))

(deftest answers-test
  (doseq [fixture (sort (keys contract/schemas))]
    (contract/assert-answers! new-store fixture 23)))
