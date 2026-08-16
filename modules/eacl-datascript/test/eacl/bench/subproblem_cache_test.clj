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
   :cache? cache?
   :evaluation :complete-denotation})

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
  {:security-key "01234567890123456789012345678901"})

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
          ;; The stable engine answers point checks by anchored traversal
          ;; and never shares state across root nodes — even semantically
          ;; equal bodies get their own sealed plan. What must still hold
          ;; is the anti-collision half below: distinct bindings never
          ;; reuse another root's answer.
          (is (= equal-hits before-hits)
              "point checks take no denotation-cache dependency at all")
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
