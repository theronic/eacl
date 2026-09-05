(ns eacl.formal.caveats.production-mutations
  "Executable implementation mutations, mapped to existing conformance gates."
  (:require [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.caveats.jvm :as jvm]
            [eacl.caveats.jvm.evaluator-test :as jvm-test]
            [eacl.caveats.partial :as partial]
            [eacl.caveats.partial-test :as partial-test]
            [eacl.caveats.values :as values]
            [eacl.caveats.publication-contract :as publication]
            [eacl.datascript.schema :as schema]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.caveat-schema-test :as persistence-test]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.qualifier-test :as qualifier-test]
            [eacl.relationships.staged :as staged]
            [eacl.formal.caveats.native-bridge :as bridge]))

(defn publication-gate []
  (let [conn (schema/create-conn {:app/flag {}})]
    (publication/check-publication!
     {:write-schema! #(schema/write-schema! conn %) :writer #(qualifiers/writer conn)
      :entid ds/entid :strategy :prepared :interleave! persistence-test/interleave!})))

(defn lifecycle-gate []
  (let [conn (schema/create-conn {:app/seen {:db/cardinality :db.cardinality/many}})]
    (schema/write-schema! conn bridge/schema-source)
    (bridge/run-lifecycle! (qualifiers/writer conn) ds/entid 901)))

(defn required-branch-gate []
  (let [required {:eacl.relation/caveats #{1} :eacl.relation/allows-unqualified? false}
        optional (assoc required :eacl.relation/allows-unqualified? true)]
    (is (= #{1} (qualifier/relation-allowance required)))
    (is (= #{nil 1} (qualifier/relation-allowance optional)))))

(defn failures [gate]
  (let [events (atom [])]
    (with-redefs [t/report (fn [event] (when (#{:fail :error} (:type event)) (swap! events conj event)))]
      (try (gate)
           (catch Throwable error (swap! events conj {:type :error :actual error}))))
    (count @events)))

(defn mutate-plan [original change]
  (fn [& args]
    (let [plan (apply original args)] (change args plan))))

(defn mutation-cases []
  (let [merge-context values/merge-context plan staged/plan prepare staged/prepare!
        decode qualifier/decode allowance qualifier/relation-allowance
        prepared-value @#'staged/prepared-value]
    {:bound-context-loses
     {:gate #'partial-test/partial-results-and-faults
      :redefs {#'values/merge-context (fn [p request bound] (merge-context p bound request))}}
     :returned-error-is-truthy
     {:gate #'jvm-test/native-outcomes-are-never-host-truthiness
      :redefs {#'jvm/classify (fn [value] {:outcome (if value :true :false)})}}
     :short-circuit-keeps-residual
     {:gate #'partial-test/partial-results-and-faults
      :redefs {#'partial/logical (fn [_ left _] left)}}
     :qualifier-mutates-in-place
     {:gate publication-gate
      :redefs {#'staged/plan
               (mutate-plan plan
                            (fn [[writer db op identity] tx]
                              (let [native (:native writer)
                                    old (:qid (#'staged/stored-pair native db identity))
                                    fresh (:qualifier-eid tx)]
                                (if (and (= :replace op) old fresh)
                                  (-> tx
                                      (assoc :qualifier-eid old)
                                      (update :tx-data
                                              (fn [datoms]
                                                (conj
                                                 (mapv (fn [d]
                                                         (if (and (= :db/add (first d)) (#{bridge/forward-attribute bridge/reverse-attribute} (nth d 2 nil)))
                                                           (assoc-in d [3 4] old) d))
                                                       (remove #(= [:db/retractEntity old] %) datoms))
                                                 [:db/add old bridge/until (get ((:entity native) db fresh) bridge/until)]))))
                                  tx))))}}
     :one-half-publication
     {:gate publication-gate
      :redefs {#'staged/plan
               (mutate-plan plan (fn [_ tx] (update tx :tx-data
                                                    #(filterv (fn [d] (not (and (= :db/add (first d))
                                                                                (= bridge/reverse-attribute (nth d 2 nil))))) %))))}}
     :missing-qualifier-becomes-nil
     {:gate #'qualifier-test/malformed-and-missing-qualifiers-fail
      :redefs {#'qualifier/decode (fn [entity parameters] (when entity (decode entity parameters)))}}
     :schema-generation-stalls
     {:gate #'persistence-test/named-caveat-persistence
      :redefs {#'schema/current-schema-generation (constantly 1)}}
     :prepared-qualifier-authorizes
     {:gate publication-gate
      :redefs {#'staged/prepare!
               (fn [writer identity value]
                 (let [handle (prepare writer identity value)]
                   ((get-in writer [:native :transact!]) (:tx-data (staged/plan-current writer :create identity handle)))
                   handle))}}
     :publication-stamp-stalls
     {:gate lifecycle-gate
      :redefs {#'staged/plan
               (mutate-plan plan (fn [[writer] tx]
                                   (update tx :tx-data
                                           #(filterv (fn [d] (not (and (= :db/add (first d))
                                                                       (= (get-in writer [:native :relation-version-attribute]) (nth d 2 nil))))) %))))}}
     :qualifier-is-shared
     {:gate publication-gate
      :redefs {#'staged/prepared-value
               (fn [writer db _ handle generation?]
                 (prepared-value writer db (.-relationship ^eacl.relationships.staged.PreparedQualifier handle) handle generation?))}}
     :required-caveat-omitted
     {:gate required-branch-gate
      :redefs {#'qualifier/relation-allowance (fn [relation] (conj (allowance relation) nil))}}}))

(deftest production-mutations-are-killed-by-mapped-gates
  (let [cases (mutation-cases)
        registered (set (map :id (:controls (edn/read-string (slurp "formal/caveats/mutations.edn")))))]
    (is (= registered (set (keys cases))))
    (doseq [[id {:keys [gate redefs]}] (sort-by key cases)]
      (is (zero? (failures gate)) (str id " unmutated gate must pass"))
      (is (pos? (with-redefs-fn redefs #(failures gate))) (str id " must be detected")))))
