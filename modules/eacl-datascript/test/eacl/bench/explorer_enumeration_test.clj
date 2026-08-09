(ns eacl.bench.explorer-enumeration-test
  "Explorer-shaped correctness, work, and matched-v7 performance gates.

  These fixtures are intentionally heavy and are run explicitly through
  nREPL. The 10k suite is the diagnostic gate; the 50k suite is the release
  acceptance gate."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]))

(def manifest
  (edn/read-string
   (slurp "formal/verification/explorer-v7-performance.edn")))

(defn- median
  [values]
  (let [ordered (vec (sort values))]
    (nth ordered (quot (count ordered) 2))))

(def ^:private host-class-keys
  [:os :arch :os-version :cpu-model :logical-processors
   :total-memory-bytes :max-heap-bytes :jdk :vm-name :vm-vendor
   :runtime :measurement])

(defn- normalized-os
  [os-name]
  (cond
    (.startsWith os-name "Mac OS") :macos
    (.startsWith os-name "Linux") :linux
    (.startsWith os-name "Windows") :windows
    :else (keyword (.toLowerCase os-name))))

(defn- normalized-arch
  [arch]
  (case arch
    ("aarch64" "arm64") :arm64
    ("amd64" "x86_64") :x86-64
    (keyword (.toLowerCase arch))))

(defn- command-output
  [command]
  (try
    (let [process (.start (ProcessBuilder. ^java.util.List command))
          output (str/trim (slurp (.getInputStream process)))]
      (when (and (zero? (.waitFor process)) (not (str/blank? output)))
        output))
    (catch Exception _
      nil)))

(defn- linux-cpu-model
  []
  (try
    (some
     (fn [line]
       (second (re-matches #"(?:model name|Hardware)\s*:\s*(.+)" line)))
     (str/split-lines (slurp "/proc/cpuinfo")))
    (catch Exception _
      nil)))

(defn- cpu-model
  [os]
  (case os
    :macos (command-output ["sysctl" "-n" "machdep.cpu.brand_string"])
    :linux (linux-cpu-model)
    :windows (System/getenv "PROCESSOR_IDENTIFIER")
    nil))

(defn- total-memory-bytes
  []
  (let [bean (java.lang.management.ManagementFactory/getOperatingSystemMXBean)]
    (when (instance? com.sun.management.OperatingSystemMXBean bean)
      (.getTotalMemorySize ^com.sun.management.OperatingSystemMXBean bean))))

(defn- current-host-class
  []
  (let [os (normalized-os (System/getProperty "os.name"))
        runtime (Runtime/getRuntime)]
    {:os os
     :arch (normalized-arch (System/getProperty "os.arch"))
     :os-version (System/getProperty "os.version")
     :cpu-model (cpu-model os)
     :logical-processors (.availableProcessors runtime)
     :total-memory-bytes (total-memory-bytes)
     :max-heap-bytes (.maxMemory runtime)
     :jdk (System/getProperty "java.version")
     :vm-name (System/getProperty "java.vm.name")
     :vm-vendor (System/getProperty "java.vm.vendor")
     :runtime :datascript-clj
     :measurement :warmed-median}))

(defn- latency-gate-context
  [baseline-host current-host]
  (let [qualification (get-in manifest [:latency-gate :host-qualification])
        mismatch-policy (get-in manifest
                                [:latency-gate :mismatched-host-policy])]
    (when-not (= :exact-host-and-jvm-class qualification)
      (throw
       (ex-info
        "Explorer latency gate requires exact host-and-JVM qualification"
        {:qualification qualification})))
    (when-not (= :not-applicable-never-passed mismatch-policy)
      (throw
       (ex-info
        "Explorer latency gate requires fail-closed host-mismatch handling"
        {:mismatched-host-policy mismatch-policy})))
    (let [missing-baseline
          (filterv #(nil? (get baseline-host %)) host-class-keys)]
      (when (seq missing-baseline)
        (throw
         (ex-info
          "Explorer latency gate requires a complete baseline host class"
          {:missing-keys missing-baseline
           :required-keys host-class-keys
           :host-class baseline-host}))))
    (let [baseline (select-keys baseline-host host-class-keys)
          current (select-keys current-host host-class-keys)
          missing-current
          (filterv #(nil? (get current-host %)) host-class-keys)
          status (if (and (empty? missing-current) (= baseline current))
                   :enforced
                   :not-applicable)]
      {:status status
       :applicability-reason
       (cond
         (seq missing-current) :incomplete-current-host-class
         (= :enforced status) :exact-host-and-jvm-match
         :else :host-or-jvm-mismatch)
       :qualification qualification
       :mismatched-host-policy mismatch-policy
       :missing-current-keys missing-current
       :baseline-host baseline
       :current-host current})))

(defn- validate-latency-manifest!
  []
  (let [maximum-ratio
        (get-in manifest [:latency-gate :maximum-v8-to-v7-ratio])
        {:keys [samples statistic]}
        (get-in manifest [:latency-gate :variance-policy])]
    (when-not (and (number? maximum-ratio) (pos? maximum-ratio)
                   (pos-int? samples) (odd? samples)
                   (= :median statistic))
      (throw
       (ex-info
        "Explorer latency gate has an invalid variance or ratio policy"
        {:maximum-ratio maximum-ratio
         :samples samples
         :statistic statistic})))
    (doseq [[scenario data] (:scenarios manifest)]
      (let [raw (:v7-samples-ms data)
            recorded-median (:v7-median-ms data)
            ceiling (:maximum-v8-median-ms data)]
        (when-not (and (= samples (count raw))
                       (every? #(and (number? %)
                                     (Double/isFinite (double %))
                                     (not (neg? %)))
                               raw)
                       (= recorded-median (median raw))
                       (= ceiling (* maximum-ratio recorded-median)))
          (throw
           (ex-info
            "Explorer latency baseline samples, median, or ceiling disagree"
            {:scenario scenario
             :data data
             :expected-samples samples
             :maximum-ratio maximum-ratio})))))))

(defn- validate-measurement!
  [scenario {:keys [samples-ms median-ms] :as measurement}]
  (let [expected-samples
        (get-in manifest [:latency-gate :variance-policy :samples])]
    (when-not (and (= expected-samples (count samples-ms))
                   (every? #(and (number? %)
                                 (Double/isFinite (double %))
                                 (not (neg? %)))
                           samples-ms)
                   (= median-ms (median samples-ms)))
      (throw
       (ex-info
        "Explorer candidate latency measurement is malformed"
        {:scenario scenario
         :measurement measurement
         :expected-samples expected-samples})))
    measurement))

(def ^:private latency-gate
  (do
    (validate-latency-manifest!)
    (latency-gate-context (:host-class manifest) (current-host-class))))

(defn- assert-matched-latency!
  [scenario measurement]
  (let [{:keys [median-ms] :as measurement}
        (validate-measurement! scenario measurement)]
    (when (= :enforced (:status latency-gate))
      (is (<= median-ms
              (get-in manifest [:scenarios scenario :maximum-v8-median-ms]))
          (str scenario " exceeded its exact-host matched-v7 ceiling: "
               (pr-str measurement))))))

(defn- seed!
  ([shape]
   (seed! shape fixture/schema))
  ([shape schema]
   (seed! shape schema fixture/relationship-batches))
  ([shape schema relationship-batches]
   (let [conn (datascript/create-conn)
         client
         (datascript/make-client
          conn
          {:cache {:remember-answers false}})]
     (eacl/write-schema! client schema)
     (ds/transact! conn (vec (fixture/object-transactions shape)))
     (doseq [batch (relationship-batches shape)]
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

(defn- warmed-measurement
  [operation]
  (let [{:keys [warmups samples]}
        (get-in manifest [:latency-gate :variance-policy])]
    (dotimes [_ warmups]
      (operation))
    (let [samples-ms
          (mapv (fn [_] (:elapsed-ms (observe operation)))
                (range samples))]
      {:samples-ms samples-ms
       :median-ms (median samples-ms)})))

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
        page-latency
        (warmed-measurement
         #(eacl/lookup-resources
           client (assoc user-query :cache? false)))
        owner-latency
        (warmed-measurement
         #(eacl/count-resources
           client (assoc owner-query :cache? false)))]
    {:page-reports page-reports
     :owner-count owner-count
     :super-count super-count
     :latency-measurements
     {:user-1-forward-page page-latency
      :owner-0001-exact-count owner-latency}
     :latency-ms
     {:user-1-forward-page (:median-ms page-latency)
      :owner-0001-exact-count (:median-ms owner-latency)}}))

(defn run-50k!
  ([]
   (run-50k! fixture/schema))
  ([schema]
   (let [{:keys [client]} (seed! fixture/acceptance-shape schema)
         query (fixture/count-query fixture/super-user :view)
         report (observe #(eacl/count-resources client query))
         latency
         (warmed-measurement
          #(eacl/count-resources
            client (assoc query :cache? false)))]
     (assoc
      report
      :latency-measurement latency
      :warmed-median-ms (:median-ms latency)))))

(defn run-100k!
  []
  (let [{:keys [client]} (seed! fixture/acceptance-100k-shape)
        query (fixture/count-query fixture/super-user :view)
        report (observe #(eacl/count-resources client query))
        latency
        (warmed-measurement
         #(eacl/count-resources
           client (assoc query :cache? false)))]
    (assoc
     report
     :latency-measurement latency
     :warmed-median-ms (:median-ms latency))))

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

(defn run-populated-recursive!
  []
  (let [shape fixture/populated-recursive-shape
        {:keys [client]}
        (seed! shape
               fixture/recursive-schema
               fixture/populated-recursive-relationship-batches)
        query {:subject fixture/user-1
               :permission :view
               :resource/type :account}]
    {:bounded-count
     (observe #(eacl/count-resources client (assoc query :count-limit 25)))
     :exact-count
     (observe #(eacl/count-resources client (assoc query :count-limit 200)))
     :first-page
     (observe #(eacl/lookup-resources
                client
                (assoc query :first 20)))}))

(deftest ^:benchmark explorer-10000-correctness-work-and-latency-gate
  (let [{:keys [page-reports owner-count super-count
                latency-measurements latency-ms]}
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
    (testing "applicable warmed medians remain within the matched-v7 gate"
      (assert-matched-latency!
       :user-1-forward-page (:user-1-forward-page latency-measurements))
      (assert-matched-latency!
       :owner-0001-exact-count
       (:owner-0001-exact-count latency-measurements)))
    (println "EACL Explorer 10k report"
             (pr-str
              {:latency-ms latency-ms
               :latency-measurements latency-measurements
               :latency-gate latency-gate
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
    (assert-matched-latency!
     :super-user-exact-count-50000 (:latency-measurement report))
    (println "EACL Explorer 50k report"
             (pr-str
              {:elapsed-ms (:elapsed-ms report)
               :warmed-median-ms (:warmed-median-ms report)
               :latency-measurement (:latency-measurement report)
               :latency-gate latency-gate
               :work (:acyclic report)}))))

(deftest ^:benchmark ^:acceptance
  explorer-50000-empty-recursive-schema-stays-acyclic
  (let [report (run-50k! fixture/recursive-schema)
        envelope (get-in manifest [:work-envelopes :count-50000])]
    (is (= 50000 (get-in report [:value :count])))
    (is (empty? (:recursive report)))
    (is (= 1 (get-in report [:acyclic :routed-acyclic])))
    (assert-work-envelope! report envelope)
    (assert-matched-latency!
     :super-user-exact-count-50000 (:latency-measurement report))
    (println "EACL Explorer 50k empty-recursive report"
             (pr-str
              {:elapsed-ms (:elapsed-ms report)
               :warmed-median-ms (:warmed-median-ms report)
               :latency-measurement (:latency-measurement report)
               :latency-gate latency-gate
               :work (:acyclic report)}))))

(deftest ^:benchmark explorer-populated-recursive-subaccounts
  (let [{:keys [bounded-count exact-count first-page]}
    (run-populated-recursive!)]
    (testing "sub-account traversal is demand-bounded"
      (is (= 25 (get-in bounded-count [:value :count])))
      (is (true? (get-in bounded-count [:value :truncated?])))
      (is (= 60 (get-in exact-count [:value :count])))
      (is (false? (get-in exact-count [:value :truncated?])))
      (is (= 20 (count (get-in first-page [:value :data])))))
    (testing "bounded demand stops recursive work after its sentinel"
      (is (<= (get-in bounded-count [:recursive :emitted-results]) 26))
      (is (<= (get-in first-page [:recursive :emitted-results]) 21))
      (is (< (get-in bounded-count
                     [:recursive :generated-dimensional-counters
                      :backend-commands])
             (get-in exact-count
                     [:recursive :generated-dimensional-counters
                      :backend-commands])))
      (is (< (get-in bounded-count
                     [:recursive :generated-retained-logical-units])
             (get-in exact-count
                     [:recursive :generated-retained-logical-units]))))
    (testing "populated recursive facts route through the recursive engine"
      (is (seq (:recursive bounded-count)))
      (is (seq (:recursive exact-count)))
      (is (seq (:recursive first-page))))
    (println "EACL Explorer populated-recursive report"
             (pr-str
              {:shape fixture/populated-recursive-shape
               :bounded-count (:recursive bounded-count)
               :exact-count (:recursive exact-count)
               :first-page (:recursive first-page)}))))

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

(deftest ^:benchmark ^:acceptance
  explorer-100000-super-user-count-builds-one-merged-traversal
  (let [report (run-100k!)
        envelope (get-in manifest [:work-envelopes :count-100000])]
    (is (= 100000 (get-in report [:value :count])))
    (is (empty? (:recursive report)))
    (is (= 1 (get-in report [:acyclic :routed-acyclic])))
    (is (= 4 (get-in report [:acyclic :permission-paths]))
        "count windows must not rebuild the four canonical server permission paths")
    (assert-work-envelope! report envelope)
    (assert-matched-latency!
     :super-user-exact-count-100000 (:latency-measurement report))
    (println "EACL Explorer 100k report"
             (pr-str
              {:elapsed-ms (:elapsed-ms report)
               :warmed-median-ms (:warmed-median-ms report)
               :latency-measurement (:latency-measurement report)
               :latency-gate latency-gate
               :work (:acyclic report)}))))

(deftest explorer-latency-gate-is-exact-host-qualified
  (let [baseline (:host-class manifest)]
    (is (= :enforced
           (:status (latency-gate-context baseline baseline))))
    (is (= :not-applicable
           (:status
            (latency-gate-context
             baseline
             (assoc baseline :jdk "deliberately-different")))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires exact host-and-JVM qualification"
         (with-redefs [manifest (assoc-in manifest
                                          [:latency-gate :host-qualification]
                                          :unqualified)]
           (latency-gate-context baseline baseline))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires fail-closed host-mismatch handling"
         (with-redefs [manifest
                       (assoc-in manifest
                                 [:latency-gate :mismatched-host-policy]
                                 :compare-incomparable-hosts)]
           (latency-gate-context baseline baseline))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires a complete baseline host class"
         (latency-gate-context (dissoc baseline :jdk) baseline)))
    (is (= {:status :not-applicable
            :applicability-reason :incomplete-current-host-class
            :missing-current-keys [:cpu-model]}
           (select-keys
            (latency-gate-context baseline (dissoc baseline :cpu-model))
            [:status :applicability-reason :missing-current-keys])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"candidate latency measurement is malformed"
         (validate-measurement!
          :user-1-forward-page
          {:samples-ms [1.0]
           :median-ms 1.0})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"baseline samples, median, or ceiling disagree"
         (with-redefs [manifest
                       (update-in
                        manifest
                        [:scenarios :user-1-forward-page
                         :maximum-v8-median-ms]
                        inc)]
           (validate-latency-manifest!))))))
