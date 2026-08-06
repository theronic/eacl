(ns eacl.bench.explorer-enumeration-test
  "Explorer-shaped correctness, work, and matched-v7 performance gates.

  These fixtures are intentionally heavy and are run explicitly through
  nREPL. The 10k suite is the diagnostic gate; the 50k suite is the release
  acceptance gate."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]))

(def manifest
  (edn/read-string
   (slurp "formal/verification/explorer-v7-performance.edn")))

(defn- seed!
  ([shape]
   (seed! shape fixture/schema))
  ([shape schema]
   (let [conn (datascript/create-conn)
         client
         (datascript/make-client
          conn
          {:cache {:remember-answers false}})]
     (eacl/write-schema! client schema)
     (ds/transact! conn (vec (fixture/object-transactions shape)))
     (doseq [batch (fixture/relationship-batches shape)]
       (eacl/create-relationships! client batch))
     {:conn conn :client client})))

(defn- observe
  [operation]
  (let [acyclic (atom {})
        recursive (atom {})
        started (System/nanoTime)
        value
        (binding [engine/*acyclic-work-stats* acyclic
                  engine/*recursive-traversal-stats* recursive]
          (operation))]
    {:value value
     :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
     :acyclic @acyclic
     :recursive @recursive}))

(defn- median
  [values]
  (let [ordered (vec (sort values))]
    (nth ordered (quot (count ordered) 2))))

(defn- warmed-median
  [operation]
  (let [{:keys [warmups samples]}
        (get-in manifest [:latency-gate :variance-policy])]
    (dotimes [_ warmups]
      (operation))
    (median
     (repeatedly samples
                 #(:elapsed-ms (observe operation))))))

(defn- successive-pages
  [client query page-count]
  (loop [index 0
         cursor nil
         reports []]
    (if (= index page-count)
      reports
      (let [report
            (observe
             #(eacl/lookup-resources
               client
               (cond-> query cursor (assoc :after cursor))))
            next-cursor
            (get-in report [:value :page-info :end-cursor])]
        (recur (inc index)
               next-cursor
               (conj reports report))))))

(defn- assert-work-envelope!
  [report envelope]
  (is (empty? (:recursive report)))
  (is (<= (get-in report [:acyclic :backend-scans] 0)
          (:maximum-backend-scans envelope)))
  (is (<= (get-in report [:acyclic :merge-advances] 0)
          (:maximum-merge-advances envelope))))

(defn run-10k!
  []
  (let [{:keys [client]} (seed! fixture/default-shape)
        user-query
        (fixture/resource-query fixture/user-1 :view 20)
        page-reports (successive-pages client user-query 10)
        owner-query
        (fixture/count-query fixture/owner-0001 :view)
        super-query
        (fixture/count-query fixture/super-user :view)
        owner-count (observe #(eacl/count-resources client owner-query))
        super-count (observe #(eacl/count-resources client super-query))
        page-median
        (warmed-median
         #(eacl/lookup-resources
           client (assoc user-query :cache? false)))
        owner-median
        (warmed-median
         #(eacl/count-resources
           client (assoc owner-query :cache? false)))]
    {:page-reports page-reports
     :owner-count owner-count
     :super-count super-count
     :latency-ms
     {:user-1-forward-page page-median
      :owner-0001-exact-count owner-median}}))

(defn run-50k!
  ([]
   (run-50k! fixture/schema))
  ([schema]
   (let [{:keys [client]} (seed! fixture/acceptance-shape schema)
         query (fixture/count-query fixture/super-user :view)
         report (observe #(eacl/count-resources client query))]
     (assoc
      report
      :warmed-median-ms
      (warmed-median
       #(eacl/count-resources
         client (assoc query :cache? false)))))))

(defn run-40k-cold-user!
  []
  (let [shape
        (assoc
         fixture/default-shape
         :accounts 20
         :user-1-account-count 6)
        {:keys [client]} (seed! shape fixture/recursive-schema)
        query (fixture/count-query fixture/user-1 :view)]
    (observe #(eacl/count-resources client query))))

(deftest ^:benchmark explorer-10000-correctness-work-and-latency-gate
  (let [{:keys [page-reports owner-count super-count latency-ms]}
        (run-10k!)
        page-envelope (get-in manifest [:work-envelopes :page])
        count-envelope (get-in manifest [:work-envelopes :count-10000])
        emitted
        (mapcat #(get-in % [:value :data]) page-reports)]
    (testing "successive pages are exact, duplicate-free, and continuation-bound"
      (is (= 200 (count emitted)))
      (is (= 200 (count (distinct emitted))))
      (doseq [report page-reports]
        (assert-work-envelope! report page-envelope))
      (doseq [report (rest page-reports)]
        (is (= 1 (get-in report [:acyclic :continuation-hits])))))
    (testing "owner and super-user exact counts remain acyclic and deduplicated"
      (is (= 2000 (get-in owner-count [:value :count])))
      (is (= 10000 (get-in super-count [:value :count])))
      (assert-work-envelope! owner-count count-envelope)
      (assert-work-envelope! super-count count-envelope))
    (testing "warmed medians remain within the checked-in matched-v7 gate"
      (is (<= (:user-1-forward-page latency-ms)
              (get-in manifest
                      [:scenarios :user-1-forward-page
                       :maximum-v8-median-ms])))
      (is (<= (:owner-0001-exact-count latency-ms)
              (get-in manifest
                      [:scenarios :owner-0001-exact-count
                       :maximum-v8-median-ms]))))
    (println "EACL Explorer 10k report"
             (pr-str
              {:latency-ms latency-ms
               :owner-work (:acyclic owner-count)
               :super-work (:acyclic super-count)
               :page-work (mapv :acyclic page-reports)}))))

(deftest ^:benchmark ^:acceptance
  explorer-50000-super-user-exact-acyclic-acceptance
  (let [report (run-50k!)
        envelope (get-in manifest [:work-envelopes :count-50000])]
    (is (= 50000 (get-in report [:value :count])))
    (is (empty? (:recursive report)))
    (is (= 1 (get-in report [:acyclic :routed-acyclic])))
    (assert-work-envelope! report envelope)
    (is (<= (:warmed-median-ms report)
            (get-in manifest
                    [:scenarios :super-user-exact-count-50000
                     :maximum-v8-median-ms])))
    (println "EACL Explorer 50k report"
             (pr-str
              {:elapsed-ms (:elapsed-ms report)
               :warmed-median-ms (:warmed-median-ms report)
               :work (:acyclic report)}))))

(deftest ^:benchmark ^:acceptance
  explorer-50000-empty-recursive-schema-stays-acyclic
  (let [report (run-50k! fixture/recursive-schema)
        envelope (get-in manifest [:work-envelopes :count-50000])]
    (is (= 50000 (get-in report [:value :count])))
    (is (empty? (:recursive report)))
    (is (= 1 (get-in report [:acyclic :routed-acyclic])))
    (assert-work-envelope! report envelope)
    (is (<= (:warmed-median-ms report)
            (get-in manifest
                    [:scenarios :super-user-exact-count-50000
                     :maximum-v8-median-ms])))
    (println "EACL Explorer 50k empty-recursive report"
             (pr-str
              {:elapsed-ms (:elapsed-ms report)
               :warmed-median-ms (:warmed-median-ms report)
               :work (:acyclic report)}))))

(deftest ^:benchmark ^:acceptance
  explorer-40000-cold-user-count-amortizes-projection-seeks
  (let [report (run-40k-cold-user!)
        envelope
        (get-in manifest [:work-envelopes :cold-user-count-40000])]
    (is (= 12000 (get-in report [:value :count])))
    (is (empty? (:recursive report)))
    (is (= 1 (get-in report [:acyclic :routed-acyclic])))
    (assert-work-envelope! report envelope)
    (println "EACL Explorer 40k cold user report"
             (pr-str
              {:elapsed-ms (:elapsed-ms report)
               :work (:acyclic report)}))))
