(ns eacl.bench.drive-parity-test
  "Acceptance gate of openspec/changes/2026-10-01-backport-rust-engine-performance
  on the eacl-rust Drive benchmark graph (`eacl.bench.drive-parity-fixture`).

  Tagged `:benchmark`: the ordinary suites exclude it. Every case interleaves
  the measured permission with its reference arms on one warm client
  (`eacl.bench.paired`), asserts every arm's answer before and during
  sampling, and compares thread-CPU medians, which on a shared machine
  exclude the time a thread waits to run. The budgets below were written
  before any sampling of the implementations they gate. A budget is asserted
  once its case is in `claimed`; until then the gate reports its ratio. Raw
  samples are written under ignored target/benchmarks/drive-parity/.

  Scale: `-Deacl.bench.drive-parity.scale=1e6` (default 1e5); 1e6 needs about
  1.5 GB of heap."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.bench.drive-parity-fixture :as fixture]
            [eacl.bench.paired :as paired]
            [eacl.client.orchestration :as orchestration]))

(def budgets
  "Authored before sampling (spec `operator-engine-performance`). A ratio
  budget bounds the measured arm's median by `factor` times the sum of its
  reference arms' medians; `:union-count-per-result` bounds the median in µs
  per counted result."
  {:operator-check 1.5
   :operator-count 1.5
   :operator-page 2.0
   :intersection 2.0
   :batch 32.0
   :union-count-per-result 1.0
   :non-linear 4.0
   :cache-off-walk 1.25})

(def work-bounds
  "Adapter commands of one request, measured once per case."
  {:direct-grant-commands 4
   :denied-through-groups-commands 64})

(def claimed
  "Cases whose budget is asserted, and work bounds that are asserted. A case
  is claimed by the pull request that meets its budget, or here when the
  budget already held when the gate was added; later pull requests add the
  cases they meet, and none may drop one."
  {:cases #{"check, through a group"
            "check, 64-deep folder chain"
            "check, denied, 64-deep folder chain"
            "check, denied, typical document"
            "check, denied through the group tree"}
   :work #{}})

(def expected-answers
  "Answers on each scale, as the eacl-rust report records them for both
  implementations."
  {"1e4" {:count-broad 2003 :count-broad-union 2020 :count-narrow 37
          :review-small 16 :review-large 302 :batch-allowed 168}
   "1e5" {:count-broad 18095 :count-broad-union 18270 :count-narrow 41
          :review-small 12 :review-large 3088 :batch-allowed 152}
   "1e6" {:count-broad 162679 :count-broad-union 164302 :count-narrow 44
          :review-small 11 :review-large 33832 :batch-allowed 137}})

(def ^:private policies
  {:check {:warmups 50 :samples 201}
   :page {:warmups 10 :samples 41}
   :batch {:warmups 3 :samples 15}
   :count {:warmups 2 :samples 9}
   :walk {:warmups 1 :samples 5}
   :chain {:warmups 10 :samples 41}})

(defn- policy [kind label]
  (let [{:keys [warmups samples]} (get policies kind)]
    (if (= "1e6" label)
      {:warmups (max 1 (quot warmups 3)) :samples (max 3 (quot samples 3))}
      {:warmups warmups :samples samples})))

(def ^:private off {:cache? false})

(defn- cases
  "Each case: label, budget key, sample policy, measured arm, reference arms
  (`[keyword (fn [acl iteration] answer)]`), and `correct?` over every arm's
  first answer. `:work` names a work bound and the arm it is measured on."
  [{:keys [scale]} chain-env]
  (let [label (:label scale)
        answers (get expected-answers label)
        typical (fixture/typical-denied-doc scale)
        check-arms (fn [subject resource]
                     [[:view (fn [acl _] (fixture/check acl subject :view :doc resource off))]
                      [:view-any (fn [acl _] (fixture/check acl subject :view_any :doc resource off))]])
        same (fn [expected] (fn [values] (every? #(= expected %) values)))
        batch-docs (fixture/ids "d" 256)
        page (fn [subject permission] (fn [acl _] (fixture/page-ids acl subject permission 50 off)))
        cnt (fn [subject permission limit] (fn [acl _] (fixture/count-of acl subject permission limit off)))]
    [{:case "check, direct grant" :budget :operator-check :kind :check
      :arms (check-arms "u1" "d7") :correct? (same :has-permission)
      :work {:bound :direct-grant-commands :arm :view}}
     {:case "check, through a group" :budget :operator-check :kind :check
      :arms (check-arms "u0" "dg") :correct? (same :has-permission)}
     {:case "check, 64-deep folder chain" :budget :operator-check :kind :check
      :arms (check-arms "u3" "dc") :correct? (same :has-permission)}
     {:case "check, denied, 64-deep folder chain" :budget :operator-check :kind :check
      :arms (check-arms "u1" "dc") :correct? (same :no-permission)}
     {:case "check, denied, typical document" :budget :operator-check :kind :check
      :arms (check-arms "u1" typical) :correct? (same :no-permission)}
     {:case "check, denied through the group tree" :budget :operator-check :kind :check
      :arms (check-arms "u1" "da") :correct? (same :no-permission)
      :work {:bound :denied-through-groups-commands :arm :view}}
     {:case "check-permissions, 256 documents" :budget :batch :kind :batch
      :arms [[:batch (fn [acl _] (fixture/checks acl "u0" :view batch-docs off))]
             [:single #(fixture/check %1 "u0" :view :doc (nth batch-docs (mod %2 256)) off)]]
      :correct? (fn [[batch _]]
                  (= (:batch-allowed answers) (count (filter #{:has-permission} batch))))}
     {:case "first page of 50, broad subject" :budget :operator-page :kind :page
      :arms [[:view (page "u0" :view)] [:view-any (page "u0" :view_any)]]
      :correct? (fn [[view view-any]] (= 50 (count view) (count view-any)))}
     {:case "first page of 50, narrow subject" :budget :operator-page :kind :page
      :arms [[:view (page "u1" :view)] [:view-any (page "u1" :view_any)]]
      :correct? (fn [[view view-any]]
                  (and (= (min 50 (:count-narrow answers)) (count view))
                       (= (set view) (set view-any))))}
     {:case "lookup-subjects, first 50" :budget :operator-page :kind :page
      :arms [[:view (fn [acl _] (fixture/subject-ids acl "da" :view 50 off))]
             [:view-any (fn [acl _] (fixture/subject-ids acl "da" :view_any 50 off))]]
      :correct? (fn [[view view-any]] (= 50 (count view) (count view-any)))}
     {:case "count, broad subject" :budget :operator-count :kind :count
      :arms [[:view (cnt "u0" :view nil)] [:view-any (cnt "u0" :view_any nil)]]
      :correct? (fn [[view view-any]] (and (= (:count-broad answers) view)
                                           (= (:count-broad-union answers) view-any)))}
     {:case "count, broad subject, limit 1" :budget :operator-count :kind :page
      :arms [[:view (cnt "u0" :view 1)] [:view-any (cnt "u0" :view_any 1)]]
      :correct? (same 1)}
     {:case "count, narrow subject" :budget :operator-count :kind :page
      :arms [[:view (cnt "u1" :view nil)] [:view-any (cnt "u1" :view_any nil)]]
      :correct? (same (:count-narrow answers))}
     {:case "count, narrow subject, limit 1" :budget :operator-count :kind :page
      :arms [[:view (cnt "u1" :view 1)] [:view-any (cnt "u1" :view_any 1)]]
      :correct? (same 1)}
     {:case "count of a union-only permission, per result" :budget :union-count-per-result :kind :count
      ;; The per-result budget reads only the first arm; the bounded count is
      ;; the paired harness's second arm.
      :arms [[:view-any (cnt "u0" :view_any nil)] [:view-any-limit-1 (cnt "u0" :view_any 1)]]
      :per-result (:count-broad-union answers)
      :correct? (fn [[exact bounded]] (and (= (:count-broad-union answers) exact) (= 1 bounded)))}
     {:case "intersection, first page, small operand" :budget :intersection :kind :page
      :arms [[:review (page "u0" :review)]
             [:parent-view (page "u0" :parent_view)]
             [:reviewed (page "u0" :reviewed)]]
      :correct? (fn [[review]] (= (:review-small answers) (count review)))}
     {:case "intersection, count, small operand" :budget :intersection :kind :page
      :arms [[:review (cnt "u0" :review nil)]
             [:parent-view (cnt "u0" :parent_view nil)]
             [:reviewed (cnt "u0" :reviewed nil)]]
      :correct? (fn [[review]] (= (:review-small answers) review))}
     {:case "intersection, count, large operand" :budget :intersection :kind :count
      :arms [[:review (cnt "u2" :review nil)]
             [:parent-view (cnt "u2" :parent_view nil)]
             [:reviewed (cnt "u2" :reviewed nil)]]
      :correct? (fn [[review]] (= (:review-large answers) review))}
     {:case "walk in pages of 1000, broad subject" :budget :cache-off-walk :kind :walk
      :arms [[:cache-off (fn [acl _] (count (fixture/walk-ids acl "u0" :view 1000 off)))]
             [:cache-miss (fn [acl _]
                            (orchestration/clear-answer-cache! acl)
                            (count (fixture/walk-ids acl "u0" :view 1000 {})))]]
      :correct? (same (:count-broad answers))}
     {:case "non-linear recursion, granted" :budget :non-linear :kind :chain :env chain-env
      :arms [[:reach (fn [acl _] (fixture/check acl "u-yes" :reach :folder (str "f" (dec (:n chain-env))) off))]
             [:reach-union (fn [acl _] (fixture/check acl "u-yes" :reach_union :folder (str "f" (dec (:n chain-env))) off))]]
      :correct? (same :has-permission)}
     {:case "non-linear recursion, denied" :budget :non-linear :kind :chain :env chain-env
      :arms [[:reach (fn [acl _] (fixture/check acl "u-no" :reach :folder (str "f" (dec (:n chain-env))) off))]
             [:reach-union (fn [acl _] (fixture/check acl "u-no" :reach_union :folder (str "f" (dec (:n chain-env))) off))]]
      :correct? (same :no-permission)}]))

(defn- commands-of
  "Adapter commands of one request through an observing client."
  [conn f]
  (let [meters (atom nil)
        acl (fixture/make-client conn {:io-observer (fn [m] (reset! meters (:meters m)))})]
    (f acl 0)
    (f acl 0)
    (:commands @meters)))

(defn- p50 [summary] (get-in summary [:p50]))

(defn- measure
  [label {:keys [conn] :as env} {:keys [case budget kind arms correct? work per-result] :as spec}]
  (let [{:keys [acl] :as env} (or (:env spec) env)
        first-answers (mapv (fn [[_ f]] (f acl 0)) arms)
        result (paired/run-paired!
                (merge (policy kind label)
                       {:arms (mapv (fn [[arm f] answer]
                                      [arm (fn [iteration]
                                             (let [value (f acl iteration)]
                                               (when (and (not= :single arm) (not= answer value))
                                                 (throw (ex-info "A measured arm changed its answer."
                                                                 {:case case :arm arm})))
                                               value))])
                                    arms first-answers)}))
        stat (fn [arm] (let [{:keys [cpu-us latency-us]} (get-in result [:arms arm])]
                         (p50 (or cpu-us latency-us))))
        wall (fn [arm] (p50 (get-in result [:arms arm :latency-us])))
        [measured & references] (map first arms)
        measured-us (stat measured)
        factor (get budgets budget)
        [ratio limit] (if per-result
                        [(/ measured-us per-result) factor]
                        (let [reference (reduce + (map stat references))]
                          [(/ measured-us reference) factor]))
        work-count (when work (commands-of (:conn (or (:env spec) env)) (second (first (filter #(= (:arm work) (first %)) arms)))))]
    {:case case
     :budget budget
     :arms (mapv first arms)
     :correct? (boolean (correct? first-answers))
     :cpu-p50-us (into {} (map (fn [arm] [arm (stat arm)])) (map first arms))
     :wall-p50-us (into {} (map (fn [arm] [arm (wall arm)])) (map first arms))
     :ratio ratio
     :factor limit
     :within-budget? (<= ratio limit)
     :work (when work {:bound (:bound work) :commands work-count
                       :maximum (get work-bounds (:bound work))
                       :within? (<= work-count (get work-bounds (:bound work)))})
     :samples (into {} (map (fn [arm] [arm (dissoc (get-in result [:arms arm]) :checksums)])) (map first arms))}))

(defn- write-report!
  [label facts results]
  (let [file (io/file "target/benchmarks/drive-parity"
                      (str "gate-" label "-" (System/currentTimeMillis) ".edn"))]
    (io/make-parents file)
    (spit file (with-out-str
                 (pprint/pprint {:scale label :facts facts :budgets budgets :work-bounds work-bounds
                                 :claimed claimed :environment (paired/environment)
                                 :results results})))
    (str file)))

(defn run-gate
  "Loads the graph of `scale` (default 1e5) and the 256-folder chain, measures
  every case whose label `select` accepts, and returns the results and the
  report file."
  ([] (run-gate {}))
  ([{:keys [scale select] :or {scale "1e5" select (constantly true)}}]
   (let [env (fixture/load! scale)
         chain-env (fixture/load-chain! 256)
         results (->> (cases env chain-env)
                      (filter (comp select :case))
                      (mapv #(measure scale env %)))]
     {:facts (:facts env) :results results
      :report (write-report! scale (:facts env) results)})))

(defn table
  "A Markdown table of a gate run: thread-CPU medians and the budget ratio."
  [{:keys [results]}]
  (str/join
   "\n"
   (into ["| case | measured CPU p50 | references CPU p50 | ratio | budget | work |"
          "|---|---:|---|---:|---:|---:|"]
         (for [{:keys [case arms cpu-p50-us ratio factor budget work]} results
               :let [[measured & references] arms]]
           (format "| %s | %.1f µs | %s | %.2f | %s %.2f | %s |"
                   case (double (get cpu-p50-us measured))
                   (str/join ", " (map #(format "%s %.1f µs" (name %) (double (get cpu-p50-us %))) references))
                   (double ratio) (name budget) (double factor)
                   (if work (format "%d ≤ %d" (:commands work) (:maximum work)) ""))))))

(deftest generator-matches-the-rust-generator
  ;; The gate's numbers are comparable with the eacl-rust report only while
  ;; both generators build the same graph.
  (is (= "e220a8397b1dcdaf" (fixture/first-draw)))
  (let [rels (fixture/relationships (fixture/scale "1e4"))]
    (is (= (get fixture/expected "1e4")
           {:relationships (count rels) :checksum (fixture/checksum rels)}))))

(deftest ^:benchmark drive-parity-gate
  (let [scale (System/getProperty "eacl.bench.drive-parity.scale" "1e5")
        {:keys [facts results report]} (run-gate {:scale scale})]
    (is (= (get fixture/expected scale) (select-keys facts [:relationships :checksum])))
    (doseq [{:keys [case budget correct? within-budget? ratio factor work]} results]
      (testing (format "%s, %s: %.2f (budget %.2f), report %s" scale case (double ratio) (double factor) report)
        (is correct?)
        (when (contains? (:cases claimed) case)
          (is within-budget?))
        (when (and work (contains? (:work claimed) (:bound work)))
          (is (:within? work)))))))
