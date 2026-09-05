(ns eacl.exploration.caveats.publication-probe
  (:require [cljs.test :refer-macros [deftest is run-tests]]
            [datascript.core :as d]
            [eacl.datascript.schema :as schema]
            [eacl.relationships.storage :as storage]))

(deftest real-native-nested-reference-allocation
  (doseq [mode [:inline :prepared]]
    (let [conn (schema/create-conn {:eacl.relationship-qualifier/format-version {}})
          _ (d/transact! conn [{:eacl/id "s"} {:eacl/id "r"} {:eacl/id "rel"}])
          s (d/entid @conn [:eacl/id "s"])
          r (d/entid @conn [:eacl/id "r"])
          relation (d/entid @conn [:eacl/id "rel"])
          qualifier {:db/id "q" :eacl.relationship-qualifier/format-version 1}
          preparation (when (= :prepared mode) (d/transact! conn [qualifier]))
          ref (if preparation (get (:tempids preparation) "q") "q")
          report (d/transact! conn
                             (cond-> []
                               (= :inline mode) (conj qualifier)
                               true (conj [:db/add s storage/forward-attribute [:user relation :doc r ref]]
                                          [:db/add r storage/reverse-attribute [:doc relation :user s ref]])))
          qid (or (get (:tempids report) "q") ref)
          pair #(mapv :v (concat (d/datoms @conn :aevt storage/forward-attribute)
                                (d/datoms @conn :aevt storage/reverse-attribute)))
          before (pair)]
      (is (pos-int? qid))
      (is (= 2 (count before)))
      (is (every? #(= (if (= :inline mode) "q" qid) (peek %)) before))
      (is (= (= :prepared mode) (every? #(= qid (peek %)) before)))
      (d/transact! conn [[:db/retractEntity qid]])
      (is (= before (pair)))
      (is (empty? (d/datoms @conn :eavt qid))))))

(defn -main [] (run-tests 'eacl.exploration.caveats.publication-probe))
(set! *main-cli-fn* -main)
