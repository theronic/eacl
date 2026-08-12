(ns eacl.formal.indexed-semantics-bridge-test
  "CLJS twin of the JVM semantics bridge. It compares the handwritten CLJC
  indexed engine with the generated JavaScript-backed v8 engine and the
  independent fixed-point oracle on identical coherent fixtures."
  (:require
   [cljs.test :refer [deftest is testing]]
   [eacl.adapter-certification :as certification]
   [eacl.authorization-oracle :as oracle]
   [eacl.engine.indexed :as indexed]
   [eacl.engine.v8 :as v8]
   [eacl.formal.differential-runner :as differential]
   [eacl.formal.indexed-bridge-fixture :as bridge]
   [eacl.formal.production-kernel-cljs :as production-cljs]
   [eacl.formal.production-kernel-js :as production-js]
   [eacl.subproblem-cache :as subproblem]))

(def generated-selection
  {:kernel production-js/generated-javascript-kernel})

(defn- with-selection
  [selection evaluate]
  (binding [subproblem/*decision-kernel* selection]
    (evaluate)))

(defn- compare-case!
  [seed case-id values]
  (is (= :passed
         (:status
          (differential/compare-values!
           {:seed seed
            :case-id case-id
            :values values})))
      (pr-str {:seed seed :case-id case-id})))

(deftest indexed-cljs-agrees-with-generated-js-and-oracle-test
  (doseq [fixture
          (certification/coherent-fixtures
           (range 820084 820116))]
    (testing (str "seed " (:seed fixture))
      (let [{v8-adapter :v8
             indexed-adapter :indexed
             :keys [external->internal internal->object]}
            (bridge/pure-adapters fixture)
            expected
            (oracle/authorization-set
             (select-keys fixture [:objects :relationships :rules]))
            resources
            (filterv #(= :document (:type %)) (:objects fixture))
            subjects
            (filterv #(= :user (:type %)) (:objects fixture))]
        (doseq [subject subjects
                resource resources]
          (let [wanted (contains? expected [subject :view resource])
                portable
                (with-selection
                  production-cljs/default-selection
                  #(v8/can? v8-adapter subject :view resource))
                generated
                (with-selection
                  generated-selection
                  #(v8/can? v8-adapter subject :view resource))
                indexed-subject
                (assoc subject :id (get external->internal subject))
                indexed-resource
                (assoc resource :id (get external->internal resource))]
            (compare-case!
             (:seed fixture)
             [:can? (:id subject) (:id resource)]
             [[:oracle wanted]
              [:portable-cljs portable]
              [:generated-javascript generated]
              [:indexed-cljs
               (indexed/can?
                indexed-adapter
                indexed/calc-permission-paths
                indexed-subject
                :view
                indexed-resource)]])))

        (doseq [subject subjects]
          (let [wanted
                (into
                 #{}
                 (for [[grant-subject permission resource] expected
                       :when (and (= subject grant-subject)
                                  (= :view permission))]
                   resource))
                query
                {:subject subject
                 :permission :view
                 :resource/type :document
                 :first 100}
                portable
                (->> (with-selection
                       production-cljs/default-selection
                       #(v8/lookup-resources v8-adapter query))
                     :data
                     (map #(get internal->object (:id %)))
                     set)
                generated
                (->> (with-selection
                       generated-selection
                       #(v8/lookup-resources v8-adapter query))
                     :data
                     (map #(get internal->object (:id %)))
                     set)
                indexed-query
                {:subject
                 (assoc subject :id (get external->internal subject))
                 :permission :view
                 :resource/type :document
                 :limit 100}
                indexed-values
                (->> (indexed/lookup
                      indexed-adapter
                      indexed/forward-direction
                      indexed/calc-permission-paths
                      indexed-query)
                     :data
                     (map #(get internal->object (:id %)))
                     set)
                count-query
                {:subject subject
                 :permission :view
                 :resource/type :document}
                portable-count
                (:count
                 (with-selection
                   production-cljs/default-selection
                   #(v8/count-resources v8-adapter count-query)))
                generated-count
                (:count
                 (with-selection
                   generated-selection
                   #(v8/count-resources v8-adapter count-query)))
                indexed-count
                (:count
                 (indexed/count-results
                  indexed-adapter
                  indexed/forward-direction
                  indexed/calc-permission-paths
                  (assoc indexed-query :limit -1)
                  :resource))]
            (compare-case!
             (:seed fixture)
             [:lookup-resources (:id subject)]
             [[:oracle wanted]
              [:portable-cljs portable]
              [:generated-javascript generated]
              [:indexed-cljs indexed-values]])
            (compare-case!
             (:seed fixture)
             [:count-resources (:id subject)]
             [[:oracle (count wanted)]
              [:portable-cljs portable-count]
              [:generated-javascript generated-count]
              [:indexed-cljs indexed-count]])))

        (doseq [resource resources]
          (let [wanted
                (into
                 #{}
                 (for [[subject permission grant-resource] expected
                       :when (and (= resource grant-resource)
                                  (= :view permission)
                                  (= :user (:type subject)))]
                   subject))
                query
                {:resource resource
                 :permission :view
                 :subject/type :user
                 :first 100}
                portable
                (->> (with-selection
                       production-cljs/default-selection
                       #(v8/lookup-subjects v8-adapter query))
                     :data
                     (map #(get internal->object (:id %)))
                     set)
                generated
                (->> (with-selection
                       generated-selection
                       #(v8/lookup-subjects v8-adapter query))
                     :data
                     (map #(get internal->object (:id %)))
                     set)
                indexed-query
                {:resource
                 (assoc resource :id (get external->internal resource))
                 :permission :view
                 :subject/type :user
                 :limit 100}
                indexed-values
                (->> (indexed/lookup
                      indexed-adapter
                      indexed/reverse-direction
                      indexed/calc-permission-paths
                      indexed-query)
                     :data
                     (map #(get internal->object (:id %)))
                     set)
                count-query
                {:resource resource
                 :permission :view
                 :subject/type :user}
                portable-count
                (:count
                 (with-selection
                   production-cljs/default-selection
                   #(v8/count-subjects v8-adapter count-query)))
                generated-count
                (:count
                 (with-selection
                   generated-selection
                   #(v8/count-subjects v8-adapter count-query)))
                indexed-count
                (:count
                 (indexed/count-results
                  indexed-adapter
                  indexed/reverse-direction
                  indexed/calc-permission-paths
                  (assoc indexed-query :limit -1)
                  :subject))]
            (compare-case!
             (:seed fixture)
             [:lookup-subjects (:id resource)]
             [[:oracle wanted]
              [:portable-cljs portable]
              [:generated-javascript generated]
              [:indexed-cljs indexed-values]])
            (compare-case!
             (:seed fixture)
             [:count-subjects (:id resource)]
             [[:oracle (count wanted)]
              [:portable-cljs portable-count]
              [:generated-javascript generated-count]
              [:indexed-cljs indexed-count]])))))))
