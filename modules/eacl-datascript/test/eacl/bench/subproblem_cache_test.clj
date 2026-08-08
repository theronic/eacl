(ns eacl.bench.subproblem-cache-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.subproblem-cache :as subproblem]))

(defn- percentile
  [samples p]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (long (Math/floor (* p (count ordered)))))]
    (nth ordered index)))

(defn- elapsed-ms
  [f]
  (let [started (System/nanoTime)
        value (f)]
    {:value value
     :elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)}))

(defn- benchmark-schema
  [permission-names depth]
  (str
   "definition user {}\n"
   "definition group_0 {
      relation member: user
      relation unrelated: user
      permission access = member
    }\n"
   (string/join
    "\n"
    (map
     (fn [n]
       (str "definition group_" n " {\n"
            "  relation parent: group_" (dec n) "\n"
            "  permission access = parent->access\n"
            "}"))
     (range 1 depth)))
   "\n"
   (str
    "definition server {\n"
    "  relation team: group_" (dec depth) "\n")
   (string/join
    "\n"
    (map #(str "permission " (name %) " = team->access")
         permission-names))
   "\n}"))

(defn- can-query
  [permission cache?]
  {:subject (eacl/spice-object :user "shared-user")
   :permission permission
   :resource (eacl/spice-object :server "server-0")
   :cache? cache?})

(defn- denotation-key-separation-schema
  []
  "definition user {}
   definition group {
     relation member: user
     relation alternate: user
     permission access = member
     permission other = alternate
   }
   definition server {
     relation team: group
     relation backup: group
     permission same_a = team->access
     permission same_b = team->access
     permission different_relation = backup->access
     permission different_target = team->other
   }")

(def benchmark-client-options
  {:coherence-authority :managed
   :security-key "01234567890123456789012345678901"})

(defn- seed-shared-arrow!
  [conn writer permission-names depth server-count]
  (let [user (eacl/spice-object :user "shared-user")
        groups
        (mapv
         (fn [n]
           (eacl/spice-object
            (keyword (str "group_" n))
            (str "shared-group-" n)))
         (range depth))
        servers
        (mapv #(eacl/spice-object :server (str "server-" %))
              (range server-count))]
    (eacl/write-schema! writer (benchmark-schema permission-names depth))
    (ds/transact!
     conn
     (mapv (fn [object] {:eacl/id (:id object)})
           (into [user] (concat groups servers))))
    (eacl/create-relationships!
     writer
     (into
      [(eacl/->Relationship user :member (first groups))]
      (concat
       (map (fn [[parent child]]
              (eacl/->Relationship parent :parent child))
            (partition 2 1 groups))
       (map #(eacl/->Relationship (peek groups) :team %) servers))))))

(defn- run-paired-distinct-queries
  [left-client left-permissions right-client right-permissions]
  (let [left-work (atom {})
        right-work (atom {})
        left-samples (atom [])
        right-samples (atom [])
        run-one
        (fn [client permission work]
          (binding [engine/*backend-work-stats* work]
            (let [{:keys [value elapsed-ms]}
                  (elapsed-ms
                   #(eacl/can?
                     client (can-query permission true)))]
              (when-not (true? value)
                (throw
                 (ex-info "Unexpected benchmark decision." {:value value})))
              elapsed-ms)))]
    (doseq [[index left-permission right-permission]
            (map vector
                 (range)
                 left-permissions
                 right-permissions)]
      (if (even? index)
        (do
          (swap! left-samples conj
                 (run-one left-client left-permission left-work))
          (swap! right-samples conj
                 (run-one right-client right-permission right-work)))
        (do
          (swap! right-samples conj
                 (run-one right-client right-permission right-work))
          (swap! left-samples conj
                 (run-one left-client left-permission left-work)))))
    (let [result
          (fn [samples work]
            {:samples samples
             :p50-ms (percentile samples 0.50)
             :p95-ms (percentile samples 0.95)
             :work work})]
      {:left (result @left-samples @left-work)
       :right (result @right-samples @right-work)})))

(deftest semantic-root-denotation-key-shares-only-equal-rule-bodies
  (testing "erasing the root name cannot collide across relation or target nodes"
    (let [conn (datascript/create-conn)
          writer
          (datascript/make-client
           conn
           (assoc benchmark-client-options :cache cache/no-cache))
          client (datascript/make-client conn benchmark-client-options)
          alice (eacl/spice-object :user "shared-user")
          bob (eacl/spice-object :user "other-user")
          primary (eacl/spice-object :group "primary")
          backup (eacl/spice-object :group "backup")
          server (eacl/spice-object :server "server-0")
          query #(eacl/can? client (can-query % true))]
      (eacl/write-schema! writer (denotation-key-separation-schema))
      (ds/transact!
       conn
       (mapv (fn [object] {:eacl/id (:id object)})
             [alice bob primary backup server]))
      (eacl/create-relationships!
       writer
       [(eacl/->Relationship alice :member primary)
        (eacl/->Relationship bob :alternate primary)
        (eacl/->Relationship bob :member backup)
        (eacl/->Relationship primary :team server)
        (eacl/->Relationship backup :backup server)])

      (is (true? (query :same_a)))
      (let [before (datascript/cache-stats client)
            before-hits
            (get-in before [:subproblems :denotation-hits] 0)]
        (is (true? (query :same_b)))
        (let [after-equal (datascript/cache-stats client)
              equal-hits
              (get-in after-equal [:subproblems :denotation-hits] 0)]
          (is (> equal-hits before-hits)
              "an identical normalized root body must reuse its denotation")
          (is (false? (query :different_relation)))
          (let [after-relation (datascript/cache-stats client)]
            (is (= equal-hits
                   (get-in after-relation
                           [:subproblems :denotation-hits]
                           0))
                "a different relation binding must not reuse the denotation")
            (is (false? (query :different_target)))
            (let [after-target (datascript/cache-stats client)]
              (is (= equal-hits
                     (get-in after-target
                             [:subproblems :denotation-hits]
                             0))
                  (str
                   "a different downstream permission node must not reuse "
                   "the denotation")))))))))

(defn- paired-samples
  [left right iterations warmup]
  (let [left-samples (atom [])
        right-samples (atom [])]
    (dotimes [iteration (+ iterations warmup)]
      (let [left-first? (even? iteration)
            run-left #(elapsed-ms left)
            run-right #(elapsed-ms right)
            [left-sample right-sample]
            (if left-first?
              [(:elapsed-ms (run-left))
               (:elapsed-ms (run-right))]
              (let [right-sample (:elapsed-ms (run-right))
                    left-sample (:elapsed-ms (run-left))]
                [left-sample right-sample]))]
        (when (>= iteration warmup)
          (swap! left-samples conj left-sample)
          (swap! right-samples conj right-sample))))
    {:left @left-samples
     :right @right-samples}))

(deftest ^:benchmark distinct-query-shared-subgraph-cache-benchmark
  (testing "shared semantic denotations beat completed-answer-only with zero final hits"
    (let [conn (datascript/create-conn)
          completed-permissions
          (mapv #(keyword (str "completed_" %)) (range 80))
          layered-permissions
          (mapv #(keyword (str "layered_" %)) (range 80))
          warm-permission :warm_shared
          all-permissions
          (into [warm-permission]
                (concat completed-permissions layered-permissions))
          writer
          (datascript/make-client
           conn
           (assoc benchmark-client-options :cache cache/no-cache))
          _ (seed-shared-arrow! conn writer all-permissions 48 1000)
          completed-only
          (datascript/make-client
           conn
           (assoc benchmark-client-options
                  :cache
                  {:subproblem-cache {:enabled? false}}))
          layered
          (datascript/make-client conn benchmark-client-options)
          ;; Compile every measured permission root without populating either
          ;; answer or subproblem state.
          _ (doseq [permission completed-permissions]
              (eacl/can?
               completed-only (can-query permission false)))
          _ (doseq [permission layered-permissions]
              (eacl/can?
               layered (can-query permission false)))
          ;; Warm the same graph shape. The old configuration retains only the
          ;; unrelated final answer; the layered configuration retains the
          ;; query-independent relationship prefixes.
          _ (eacl/can?
             completed-only (can-query warm-permission true))
          _ (eacl/can?
             layered (can-query warm-permission true))
          completed-before (datascript/cache-stats completed-only)
          layered-before (datascript/cache-stats layered)
          ;; Advance the exact graph generation through a relation that is
          ;; outside every measured permission dependency. The managed layered
          ;; cache should retain member/parent/team portions; the exact-only
          ;; completed-answer baseline has no reusable final key.
          _ (eacl/create-relationships!
             writer
             [(eacl/->Relationship
               (eacl/spice-object :user "shared-user")
               :unrelated
               (eacl/spice-object :group_0 "shared-group-0"))])
          paired
          (run-paired-distinct-queries
           completed-only completed-permissions
           layered layered-permissions)
          completed (:left paired)
          layered-result (:right paired)
          completed-after (datascript/cache-stats completed-only)
          layered-after (datascript/cache-stats layered)
          completed-ops
          (get-in completed [:work :executed-backend-operations] 0)
          layered-ops
          (get-in layered-result [:work :executed-backend-operations] 0)
          latency-ratio
          (/ (:p50-ms layered-result) (:p50-ms completed))
          work-ratio (if (pos? completed-ops)
                       (/ (double layered-ops) completed-ops)
                       ##Inf)
          report
          {:completed-answer-only completed
           :layered-subproblem layered-result
           :latency-ratio latency-ratio
           :backend-work-ratio work-ratio
           :completed-cache-delta
           {:exact-hits (- (:exact-hits completed-after)
                           (:exact-hits completed-before))}
           :layered-cache-delta
           {:exact-hits (- (:exact-hits layered-after)
                           (:exact-hits layered-before))
            ;; An unrelated write installs a new exact generation, so its
            ;; subproblem metrics start at zero. Report the active
            ;; generation's absolute semantic-denotation hits rather than
            ;; subtracting counters from the now-detached warm generation.
            :denotation-hits
            (get-in layered-after [:subproblems :denotation-hits])
            :managed-denotation-hits
            (get-in layered-after
                    [:subproblems :managed-denotation-hits])
            :acyclic-denotation-hits
            (get-in layered-after
                    [:subproblems :acyclic-denotation-hits])
            :new-generation-proof-reads
            (get-in layered-after
                    [:subproblems :managed-proof-reads])
            :avoided-backend-operations
            (get-in layered-after
                    [:subproblems :avoided-backend-operations])}}]
      (println "EACL shared-subgraph cache benchmark" (pr-str report))
      (is (zero? (get-in report [:completed-cache-delta :exact-hits])))
      (is (zero? (get-in report [:layered-cache-delta :exact-hits])))
      (is (pos? (get-in report
                        [:layered-cache-delta :denotation-hits])))
      (is (pos? (get-in report
                        [:layered-cache-delta
                         :managed-denotation-hits])))
      (is (pos? (get-in report
                        [:layered-cache-delta
                         :acyclic-denotation-hits])))
      (is (<= (get-in report
                      [:layered-cache-delta
                       :new-generation-proof-reads])
              50)
          (str "proof reads must be bounded by distinct relation "
               "dependencies, not chunks or top-level queries: " report))
      (is (<= work-ratio 0.50)
          (str "layered cache must execute at least 50% fewer backend "
               "operations: " report))
      (is (<= latency-ratio 0.75)
          (str "layered p50 must be at least 25% faster: " report)))))

(deftest ^:benchmark hot-hit-and-cache-free-regression-benchmark
  (testing "layered storage does not penalize existing hot or bypass paths"
    (let [conn (datascript/create-conn)
          permissions
          [:completed_hot :layered_hot
           :completed_bypass :layered_bypass]
          writer
          (datascript/make-client
           conn
           (assoc benchmark-client-options :cache cache/no-cache))
          _ (seed-shared-arrow! conn writer permissions 16 1)
          completed-only
          (datascript/make-client
           conn
           (assoc benchmark-client-options
                  :cache
                  {:subproblem-cache {:enabled? false}}))
          layered
          (datascript/make-client conn benchmark-client-options)
          hot-completed #(eacl/can?
                          completed-only
                          (can-query :completed_hot true))
          hot-layered #(eacl/can?
                        layered
                        (can-query :layered_hot true))
          bypass-completed #(eacl/can?
                             completed-only
                             (can-query :completed_bypass false))
          bypass-layered #(eacl/can?
                           layered
                           (can-query :layered_bypass false))
          ;; Compile both roots, then populate the completed-answer entries.
          _ (doseq [query [bypass-completed bypass-layered]]
              (query))
          _ (hot-completed)
          _ (hot-layered)
          hot (paired-samples hot-completed hot-layered 400 40)
          bypass (paired-samples
                  bypass-completed bypass-layered 200 20)
          hot-left (percentile (:left hot) 0.50)
          hot-right (percentile (:right hot) 0.50)
          bypass-left (percentile (:left bypass) 0.50)
          bypass-right (percentile (:right bypass) 0.50)
          report
          {:completed-answer-hot-p50-ms hot-left
           :layered-hot-p50-ms hot-right
           :hot-ratio (/ hot-right hot-left)
           :completed-answer-bypass-p50-ms bypass-left
           :layered-bypass-p50-ms bypass-right
           :bypass-ratio (/ bypass-right bypass-left)}]
      (println "EACL layered-cache regression benchmark" (pr-str report))
      (is (<= (:hot-ratio report) 1.05)
          (str "completed-answer hot hits regressed by more than 5%: "
               report))
      (is (<= (:bypass-ratio report) 1.05)
          (str ":cache? false regressed by more than 5%: " report)))))

(defn- populated-projection-store
  [entry-count]
  (let [store
        (subproblem/store
         {:projection-max-weight (inc entry-count)})]
    (dotimes [key entry-count]
      (subproblem/resolve!
       store :projection key {} (constantly key)))
    store))

(defn- repeated-hit-batch
  [store key repetitions]
  (dotimes [_ repetitions]
    (when-not (= key
                 (:value
                  (subproblem/lookup!
                   store :projection key {})))
      (throw (ex-info "Unexpected cache hit value." {:key key}))))
  nil)

(defn- distinct-miss-batch
  [store next-key repetitions]
  (dotimes [_ repetitions]
    (let [key (swap! next-key inc)
          resolved
          (subproblem/resolve!
           store :projection key {} (constantly key))]
      (when-not (= key (:value resolved))
        (throw
         (ex-info "Unexpected cache miss value."
                  {:key key
                   :resolved resolved})))))
  nil)

(deftest ^:benchmark hit-maintenance-does-not-regress-linearly-with-entry-count
  (testing "hit recency is O(1) state maintenance, not a full LRU-vector walk"
    (let [small-count 64
          large-count 4096
          small (populated-projection-store small-count)
          large (populated-projection-store large-count)
          batches
          (paired-samples
           #(repeated-hit-batch small (dec small-count) 100)
           #(repeated-hit-batch large (dec large-count) 100)
           80
           10)
          small-p50 (percentile (:left batches) 0.50)
          large-p50 (percentile (:right batches) 0.50)
          ratio (/ large-p50 small-p50)
          report
          {:small-entries small-count
           :large-entries large-count
           :entries-ratio (/ large-count small-count)
           :small-batch-p50-ms small-p50
           :large-batch-p50-ms large-p50
           :latency-ratio ratio}]
      (println "EACL subproblem hit-cardinality benchmark" (pr-str report))
      ;; A 64x entry-count increase must remain far below linear growth. This
      ;; intentionally leaves room for persistent-map depth and runtime noise.
      (is (<= ratio 4.0)
          (str "cache-hit maintenance appears entry-count-linear: "
               report)))))

(deftest ^:benchmark miss-finalization-does-not-regress-linearly-with-entry-count
  (testing "flight removal and publication do not scan represented entries"
    (let [small-count 64
          large-count 4096
          batch-size 20
          trial-count 60
          small
          (subproblem/store
           {:projection-max-weight
            (+ small-count (* batch-size trial-count) 1)})
          large
          (subproblem/store
           {:projection-max-weight
            (+ large-count (* batch-size trial-count) 1)})
          _ (dotimes [key small-count]
              (subproblem/resolve!
               small :projection key {} (constantly key)))
          _ (dotimes [key large-count]
              (subproblem/resolve!
               large :projection key {} (constantly key)))
          small-next-key (atom small-count)
          large-next-key (atom large-count)
          batches
          (paired-samples
           #(distinct-miss-batch small small-next-key batch-size)
           #(distinct-miss-batch large large-next-key batch-size)
           trial-count
           10)
          small-p50 (percentile (:left batches) 0.50)
          large-p50 (percentile (:right batches) 0.50)
          ratio (/ large-p50 small-p50)
          report
          {:resource-dimension :actual-host-computation-lifecycle
           :small-entries small-count
           :large-entries large-count
           :entries-ratio (/ large-count small-count)
           :misses-per-batch batch-size
           :small-batch-p50-ms small-p50
           :large-batch-p50-ms large-p50
           :latency-ratio ratio}]
      (println "EACL subproblem miss-finalization benchmark"
               (pr-str report))
      ;; This covers the store-lock acquisition added to make flight removal
      ;; share the lifecycle-selection linearization point. A 64x increase in
      ;; represented entries must not induce an entry scan on the miss path.
      (is (<= ratio 4.0)
          (str "cache-miss finalization appears entry-count-linear: "
               report)))))

(defn- render-recursive-page-batch
  [values repetitions]
  (dotimes [_ repetitions]
    (let [window
          (#'engine/keyset-window values :asc nil 20)]
      (when-not (= 21 (count window))
        (throw
         (ex-info "Unexpected recursive denotation window."
                  {:window window})))))
  nil)

(deftest ^:benchmark keyset-recursive-page-cost-is-page-bounded
  (testing "slicing a keyset page does not materialize the full closure"
    (let [small-count 64
          large-count 131072
          small-values (vec (range small-count))
          large-values (vec (range large-count))
          batches
          (paired-samples
           #(render-recursive-page-batch small-values 25)
           #(render-recursive-page-batch large-values 25)
           80
           10)
          small-p50 (percentile (:left batches) 0.50)
          large-p50 (percentile (:right batches) 0.50)
          ratio (/ large-p50 small-p50)
          report
          {:small-closure-items small-count
           :large-closure-items large-count
           :closure-size-ratio (/ large-count small-count)
           :page-size 20
           :small-batch-p50-ms small-p50
           :large-batch-p50-ms large-p50
           :latency-ratio ratio}]
      (println "EACL cached-recursive-page benchmark" (pr-str report))
      ;; A 2048x closure-size increase must remain far below linear growth.
      ;; The permitted ratio leaves room for runtime noise without permitting
      ;; the old whole-denotation materialization path.
      (is (<= ratio 4.0)
          (str "cached recursive page rendering appears closure-size-linear: "
               report)))))
