(ns eacl.bench.qualified-authorization-test
  "Explicit nREPL-only release benchmark; no automatic benchmark test vars."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [eacl.authorization.qualification :as qualification]
            [eacl.bench.qualifier-storage-test :as systems]
            [eacl.caveats.jvm :as jvm]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]))

(def budget-path "docs/benchmarks/qualified-authorization-budgets.edn")
(def schema
  "caveat enabled(flag bool) { flag }
   definition user {}
   definition doc {
     relation member: user | user with enabled
     relation banned: user | user with enabled
     relation parent: doc | doc with enabled
     permission direct = member
     permission view = member - banned
     permission via = parent->direct
     permission walk = member + parent->walk
   }")
(def ordinary-schema
  "definition user {}
   definition doc {
     relation member: user
     relation banned: user
     relation parent: doc
     permission direct = member
     permission view = member - banned
     permission via = parent->direct
     permission walk = member + parent->walk
   }")

(defn percentile [values p]
  (nth (vec (sort values)) (min (dec (count values)) (long (* p (count values))))))

(defn measure [operation iterations samples]
  (let [started (System/nanoTime)
        _ (operation)
        first-call-ns (- (System/nanoTime) started)]
    (let [deadline (+ (System/nanoTime) 1000000000)]
      (loop [n 0]
        (when (or (< n (max 30 (* 10 iterations))) (< (System/nanoTime) deadline))
          (operation)
          (recur (inc n)))))
    (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
          tid (.threadId (Thread/currentThread))
          allocated #(.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
          _ (assert (.isThreadAllocatedMemorySupported ^com.sun.management.ThreadMXBean bean))
          _ (.setThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean bean true)
          batches (vec (for [_ (range samples)]
                         (let [bytes (allocated) started (System/nanoTime)]
                           (dotimes [_ iterations] (operation))
                           {:ns (/ (- (System/nanoTime) started) (double iterations))
                            :bytes (/ (- (allocated) bytes) (double iterations))})))]
      {:first-call-ns first-call-ns
       :median-ns (percentile (map :ns batches) 0.5)
       :p95-ns (percentile (map :ns batches) 0.95)
       :allocated-bytes (percentile (map :bytes batches) 0.5)
       :iterations-per-batch iterations :samples batches})))

(defn- qualify [scenario relationships]
  (let [density (case scenario :sparse-5 5 :ordinary 0 10)
        target (quot (* density (count relationships)) 100)
        indices (if (#{:sparse-5 :sparse-10} scenario)
                  (set (map #(quot (* % (count relationships)) target) (range target)))
                  (set (range target)))]
    (mapv (fn [i relationship]
            (if (indices i)
              (cond-> (assoc relationship :valid-until-ms
                             (if (= :expired-prefix-10 scenario) 100 200))
                (even? i) (assoc :caveat "enabled" :caveat-context {"flag" true}))
              relationship))
          (range) relationships)))

(defn fixture! [system scenario n]
  (let [client (:client system)
        alice (eacl/spice-object :user "alice")
        bob (eacl/spice-object :user "bob")
        root (eacl/spice-object :doc "root")
        cycle (eacl/spice-object :doc "cycle")
        documents (mapv #(eacl/spice-object :doc (format "document-%06d" %)) (range n))
        relationships (qualify scenario
                               (into [(eacl/->Relationship alice :member root)
                                      (eacl/->Relationship cycle :parent root)
                                      (eacl/->Relationship root :parent cycle)]
                                     (mapcat (fn [i doc]
                                               (cond-> [(eacl/->Relationship alice :member doc)
                                                        (eacl/->Relationship root :parent doc)]
                                                 (zero? (mod i 4))
                                                 (conj (eacl/->Relationship alice :banned doc))))
                                             (range) documents)))
        active? #(or (nil? (:valid-until-ms %)) (< 100 (:valid-until-ms %)))
        active (into #{} (comp (filter active?)
                               (map #(vector (:subject %) (:relation %) (:resource %)))) relationships)]
    (eacl/write-schema! client (if (= :ordinary scenario) ordinary-schema schema))
    ((:transact! system) (mapv #(hash-map :eacl/id (:id %)) (into [alice bob root cycle] documents)))
    (doseq [batch (partition-all 200 relationships)]
      (eacl/create-relationships! client (vec batch)))
    {:alice alice :bob bob :documents documents :root root :cycle cycle :scenario scenario
     :relationships relationships
     :direct? #(contains? active [alice :member %])
     :view? #(and (contains? active [alice :member %])
                  (not (contains? active [alice :banned %])))
     :via? #(and (contains? active [root :parent %])
                 (contains? active [alice :member root]))}))

(defn- read-profile [operation]
  (let [calls (atom {}) requests (atom 0) original qualification/request-from-adapter
        result (with-redefs [qualification/request-from-adapter
                             (fn [adapter options]
                               (let [request (original adapter options)
                                     id (swap! requests inc)
                                     lookup (:lookup request)]
                                 (assoc request :lookup
                                        (fn [eid]
                                          (swap! calls update [id eid] (fnil inc 0))
                                          (lookup eid)))))]
                 (operation))]
    {:reads (reduce + 0 (vals @calls)) :maximum-reads-per-entity (reduce max 0 (vals @calls))
     :entities-per-request (count @calls) :requests @requests :cached? (:cached? result)
     :result-type (str (type result))}))

(defn- operations [client {:keys [alice bob documents root]} mode]
  (let [options (fn [] (cond-> {:caveat-context {"flag" true}}
                         (= :cold mode) (assoc :cache? false)
                         (= :warm mode) (assoc :populate-cache? false)))
        index (volatile! -1)
        next-doc #(nth documents (mod (vswap! index + 37) (count documents)))
        point (fn [permission subject]
                #(eacl/check-permission client
                                        (merge (options) {:subject subject :resource (next-doc)
                                                          :permission permission})))
        query {:subject alice :resource/type :doc :permission :view}
        fixed {:subject alice :resource root :permission :direct
               :caveat-context {"flag" true "fixed" true}}]
    [[:direct :point 20 (point :direct alice)]
     [:negative :point 20 (point :direct bob)]
     [:exclusion :point 20 (point :view alice)]
     [:arrow :point 20 (point :via alice)]
     [:recursive :point 10 (point :walk bob)]
     [:page :page 3 #(eacl/lookup-resources client (merge query (options) {:first 50}))]
     [:continuation-roundtrip :page 3
      #(let [q (merge query (options) {:first 50})
             first-page (eacl/lookup-resources client q)]
         (eacl/lookup-resources client (assoc q :after (get-in first-page [:page-info :end-cursor]))))]
     [:count :count 5 #(eacl/count-resources client (merge query (options)))]
     [:cache-hit :cache-hit 50 #(eacl/check-permission client fixed)]]))

(defn- validate-fixture! [client {:keys [alice bob documents root direct? view? via? scenario]}]
  (when (#{:qualified-prefix-10 :expired-prefix-10} scenario)
    (let [prefix (:data (eacl/read-relationships client
                                                 {:subject/type :user :subject/id "alice"
                                                  :resource/type :doc :resource/relation :member
                                                  :first 20 :cache? false}))
          deadline (if (= :expired-prefix-10 scenario) 100 200)]
      (assert (= 20 (count prefix)))
      (assert (every? #(= deadline (:valid-until-ms %)) prefix)
              (pr-str [:native-prefix scenario (mapv :valid-until-ms prefix)]))))
  (doseq [doc (concat (take 20 documents) [(nth documents (quot (count documents) 2)) (peek documents)])
          [permission expected] [[:direct (direct? doc)] [:view (view? doc)] [:via (via? doc)]]]
    (assert (= expected (true?
                         (:allowed? (eacl/check-permission client
                                                           {:subject alice :resource doc :permission permission
                                                            :caveat-context {"flag" true} :cache? false}))))
            (pr-str [:point permission doc expected])))
  (assert (= false (:allowed? (eacl/check-permission client
                                                     {:subject bob :resource root :permission :walk
                                                      :caveat-context {"flag" true} :cache? false}))))
  (let [expected (+ (count (filter view? documents)) (if (direct? root) 1 0))
        actual (:count (eacl/count-resources client {:subject alice :resource/type :doc :permission :view
                                                     :caveat-context {"flag" true} :cache? false}))]
    (assert (= expected actual) (pr-str [:count expected actual]))))

(defn- warm-data! [client {:keys [documents root cycle]}]
  ;; Physical reads populate decoded data without publishing authorization
  ;; answers. Read-only authorization can then measure fresh work on both epochs.
  (doseq [resource (into [root cycle] documents)]
    (eacl/read-relationships client {:resource/type :doc :resource/id (:id resource) :first 10})))

(defn run-case! [backend scenario enabled? output]
  (binding [orchestration/*qualified-authorization-enabled?* enabled?]
    (let [budgets (edn/read-string (slurp budget-path))
          system (systems/open-system backend {:clock (constantly 100) :caveat-evaluator (jvm/evaluator)})]
      (try
        (let [fixture (fixture! system scenario (:documents budgets))
              client (:client system)
              _ (validate-fixture! client fixture)
              results (into {}
                            (for [mode [:cold :warm]
                                  :let [_ (when (= :warm mode) (warm-data! client fixture))]
                                  [name kind iterations operation] (operations client fixture mode)]
                              (let [metric (measure operation iterations (:samples budgets))
                                    reads (read-profile operation)]
                                (assert (<= (:maximum-reads-per-entity reads) 1)
                                        (pr-str [:duplicate-data-read scenario mode name reads]))
                                (when (and (= :warm mode) (not= :cache-hit name))
                                  (assert (not (:cached? reads)) (pr-str [:warm-answer-contamination scenario name])))
                                (when (= :cache-hit name)
                                  (assert (true? (:cached? reads)) (pr-str [:completed-answer-miss scenario mode])))
                                (when (or (= :ordinary scenario) (= :cache-hit name))
                                  (assert (zero? (:reads reads)) (pr-str [:unexpected-data-read scenario name reads])))
                                (println :measured backend scenario mode name (long (:median-ns metric)))
                                (flush)
                                [[mode name] (assoc metric :kind kind :data-reads reads)])))
              relationships (:relationships fixture)
              result {:backend backend :scenario scenario :enabled? enabled? :measurement-version 5
                      :java (System/getProperty "java.version") :os (System/getProperty "os.name")
                      :heap-max-bytes (.maxMemory (Runtime/getRuntime))
                      :validated-prefix-size (if (#{:qualified-prefix-10 :expired-prefix-10} scenario) 20 0)
                      :arch (System/getProperty "os.arch") :processors (.availableProcessors (Runtime/getRuntime))
                      :documents (:documents budgets) :relationships (count relationships)
                      :qualifiers (count (filter :valid-until-ms relationships))
                      :caveats (count (filter :caveat relationships))
                      :budgets budgets :metrics results}]
          (io/make-parents output)
          (spit output (pr-str result))
          (dissoc result :metrics :budgets))
        (finally ((:close! system)))))))

(defn check-results! [directory]
  (let [budgets (edn/read-string (slurp budget-path))
        reports (mapv #(edn/read-string (slurp %))
                      (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")) (file-seq (io/file directory))))
        index (into {} (map (juxt (juxt :backend :scenario :enabled?) identity)) reports)
        _ (assert (= 24 (count reports) (count index)) "Expected one report for each of the 24 cases")
        _ (doseq [report reports]
            (assert (= 5 (:measurement-version report)) "Mixed measurement versions")
            (assert (= budgets (:budgets report)) "Budget provenance changed")
            (assert (= 18 (count (:metrics report))) "Incomplete operation matrix")
            (doseq [[_ metric] (:metrics report)]
              (assert (= (:samples budgets) (count (:samples metric))) "Incomplete sample set")))
        failures
        (vec (for [backend (:backends budgets)
                   scenario (:cases budgets)
                   :let [baseline (get index [backend :ordinary false])
                         candidate (get index [backend scenario true])
                         _ (assert (and baseline candidate) (pr-str [:missing-report backend scenario]))
                         _ (assert (= budgets (:budgets baseline) (:budgets candidate)) "Budget provenance changed")]
                   [operation metric] (:metrics candidate)
                   :let [original (get-in baseline [:metrics operation])
                         {:keys [latency-factor latency-slack-ns allocation-factor allocation-slack-bytes]}
                         (get-in budgets [:relative-budgets scenario])]
                   [quantity actual limit]
                   [[:median-ns (:median-ns metric) (+ latency-slack-ns (* latency-factor (:median-ns original)))]
                    [:p95-ns (:p95-ns metric) (+ latency-slack-ns (* latency-factor (:p95-ns original)))]
                    [:absolute-p95-ns (:p95-ns metric) (get-in budgets [:absolute-p95-ns (:kind metric)])]
                    [:allocated-bytes (:allocated-bytes metric)
                     (+ allocation-slack-bytes (* allocation-factor (:allocated-bytes original)))]]
                   :when (> actual limit)]
               {:backend backend :scenario scenario :operation operation :quantity quantity
                :actual actual :limit limit}))]
    {:reports (count reports) :comparisons (* (count (:backends budgets)) (count (:cases budgets)) 18 4)
     :passed? (empty? failures) :failures failures}))
