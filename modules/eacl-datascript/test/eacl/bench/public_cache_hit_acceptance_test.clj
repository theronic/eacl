(ns eacl.bench.public-cache-hit-acceptance-test
  "Explicit JVM acceptance gate for warmed public Core cache hits.

  This namespace is tagged `:benchmark`, so the ordinary CI suites exclude it.
  Run it on an otherwise idle JVM when changing cache or request orchestration.
  The measured page operations consume and hash every public item; the result
  therefore cannot pass by timing an unrealized page value."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript])
  (:import (java.lang.management ManagementFactory)))

(def ^:private default-options
  {:warmups 200
   :samples 501
   :page-size 64
   :maximum-hit-ms 1.0})

(def ^:private allocation-bean
  (let [candidate (ManagementFactory/getThreadMXBean)]
    (when (instance? com.sun.management.ThreadMXBean candidate)
      (let [bean ^com.sun.management.ThreadMXBean candidate]
        (try
          (when (.isThreadAllocatedMemorySupported bean)
            (when-not (.isThreadAllocatedMemoryEnabled bean)
              (.setThreadAllocatedMemoryEnabled bean true))
            bean)
          (catch SecurityException _
            nil))))))

(defn- allocated-bytes
  []
  (when allocation-bean
    (.getThreadAllocatedBytes
     ^com.sun.management.ThreadMXBean allocation-bean
     (.getId (Thread/currentThread)))))

(defn- percentile
  [ordered fraction]
  (nth ordered
       (long (Math/floor (* fraction (dec (count ordered)))))))

(defn- measurement
  [operation expected {:keys [warmups samples]}]
  (dotimes [_ warmups]
    (when-not (= expected (operation))
      (throw (ex-info "Cache-hit warmup returned a different answer."
                      {:expected expected}))))
  (let [observations
        (loop [remaining samples
               elapsed-ns (transient [])
               allocations (transient [])]
          (if (zero? remaining)
            {:elapsed-ns (persistent! elapsed-ns)
             :allocations (persistent! allocations)}
            (let [allocated-before (allocated-bytes)
                  started (System/nanoTime)
                  actual (operation)
                  elapsed (- (System/nanoTime) started)
                  allocated-after (allocated-bytes)]
              (when-not (= expected actual)
                (throw (ex-info "Measured cache hit returned a different answer."
                                {:expected expected :actual actual})))
              (recur (dec remaining)
                     (conj! elapsed-ns elapsed)
                     (cond-> allocations
                       (and allocated-before allocated-after)
                       (conj! (- allocated-after allocated-before)))))))
        ordered-ns (vec (sort (:elapsed-ns observations)))
        ordered-allocations (vec (sort (:allocations observations)))]
    (cond->
     {:warmups warmups
      :samples samples
      :p50-ms (/ (double (percentile ordered-ns 0.50)) 1000000.0)
      :p95-ms (/ (double (percentile ordered-ns 0.95)) 1000000.0)
      :maximum-ms (/ (double (peek ordered-ns)) 1000000.0)
      :samples-over-1ms (count (filter #(> % 1000000) ordered-ns))}
      (seq ordered-allocations)
      (assoc :p50-allocated-bytes
             (percentile ordered-allocations 0.50)))))

(defn- seed-client!
  [page-size]
  (let [shape {:accounts 1
               :teams-per-account 1
               :vpcs-per-account 1
               :servers-per-account (* 2 page-size)
               :user-1-account-count 1}
        conn (datascript/create-conn)
        client (datascript/make-client
                conn
                {:security-key "01234567890123456789012345678901"
                 :cache {:max-entries 256
                         :denotation-max-entries 256
                         :telemetry? true}})]
    (eacl/write-schema! client fixture/schema)
    (ds/transact! conn (vec (fixture/object-transactions shape)))
    (doseq [batch (fixture/relationship-batches shape)]
      (eacl/create-relationships! client (vec batch)))
    {:client client :expected-count (* 2 page-size)}))

(defn- page-observation
  [client query expected-items]
  (let [{:keys [data page-info cached?]} (eacl/lookup-resources client query)
        ;; `vec` forces a seq returned by a regressed implementation; hashing
        ;; traverses every public object even when the production value is the
        ;; expected vector.
        realized-data (vec data)]
    (when-not cached?
      (throw (ex-info "Expected an exact page-cache hit."
                      {:query (dissoc query :after :before)})))
    (when-not (= expected-items (count realized-data))
      (throw (ex-info "Cached page has the wrong number of items."
                      {:expected expected-items
                       :actual (count realized-data)})))
    (hash [realized-data
           (select-keys page-info
                        [:has-next-page? :has-previous-page? :bounded?])])))

(defn- count-observation
  [client query]
  (let [{:keys [count cached?]} (eacl/count-resources client query)]
    (when-not cached?
      (throw (ex-info "Expected an exact count-cache hit." {})))
    count))

(defn- subject-count-observation
  [client query]
  (let [{:keys [count cached?]} (eacl/count-subjects client query)]
    (when-not cached?
      (throw (ex-info "Expected an exact reverse-count cache hit." {})))
    count))

(defn- check-observation
  [client query]
  (let [{:keys [allowed? cached?]} (eacl/check-permission client query)]
    (when-not cached?
      (throw (ex-info "Expected an exact point-decision cache hit." {})))
    allowed?))

(defn- relationship-page-observation
  [client query expected-items]
  (let [{:keys [data page-info cached?]}
        (eacl/read-relationships client query)
        realized-data (vec data)]
    (when-not cached?
      (throw (ex-info "Expected an exact relationship-page cache hit." {})))
    (when-not (= expected-items (count realized-data))
      (throw (ex-info "Cached relationship page has the wrong size."
                      {:expected expected-items
                       :actual (count realized-data)})))
    (hash [realized-data
           (select-keys page-info
                        [:has-next-page? :has-previous-page? :bounded?])])))

(defn- permission-tree-observation
  [client query]
  (let [{:keys [expanded-at tree-root]}
        (eacl/expand-permission-tree client query)]
    (when-not (string? expanded-at)
      (throw (ex-info "Permission-tree hit did not issue a basis token." {})))
    (hash tree-root)))

(defn run-submillisecond-cache-hit-gate!
  "Runs the isolated cache-hit gate and returns its report.

  Options may override `:warmups`, `:samples`, `:page-size`, and
  `:maximum-hit-ms`. The fixture contains exactly two full pages. This runner
  throws on a miss or changed answer; latency assertions live in the tagged
  test so callers may inspect measurements from slower diagnostic hosts."
  ([]
   (run-submillisecond-cache-hit-gate! {}))
  ([options]
   (let [{:keys [warmups samples page-size maximum-hit-ms] :as options}
         (merge default-options options)]
     (when-not (and (pos-int? warmups)
                    (pos-int? samples)
                    (odd? samples)
                    (pos-int? page-size)
                    (<= page-size 1000)
                    (number? maximum-hit-ms)
                    (pos? maximum-hit-ms))
       (throw (ex-info "Invalid cache-hit benchmark options."
                       {:options options})))
     (let [{:keys [client expected-count]} (seed-client! page-size)
           count-query (fixture/count-query fixture/user-1 :view)
           first-query (fixture/resource-query fixture/user-1 :view page-size)
           cold-count (eacl/count-resources client count-query)
           cold-first (eacl/lookup-resources client first-query)
           continued-query
           (assoc first-query :after
                  (get-in cold-first [:page-info :end-cursor]))
           cold-continued (eacl/lookup-resources client continued-query)
           reverse-query
           (-> first-query (dissoc :first) (assoc :last page-size))
           cold-reverse (eacl/lookup-resources client reverse-query)
           point-resource (first (:data cold-first))
           point-query {:subject fixture/user-1
                        :permission :view
                        :resource point-resource}
           cold-point (eacl/check-permission client point-query)
           subject-count-query {:resource point-resource
                                :permission :view
                                :subject/type :user}
           cold-subject-count
           (eacl/count-subjects client subject-count-query)
           relationship-query {:resource/type :server :first page-size}
           cold-relationship-first
           (eacl/read-relationships client relationship-query)
           relationship-continued-query
           (assoc relationship-query :after
                  (get-in cold-relationship-first
                          [:page-info :end-cursor]))
           cold-relationship-continued
           (eacl/read-relationships client relationship-continued-query)
           relationship-reverse-query
           (-> relationship-query (dissoc :first) (assoc :last page-size))
           cold-relationship-reverse
           (eacl/read-relationships client relationship-reverse-query)
           tree-query {:resource point-resource :permission :view}
           cold-tree (eacl/expand-permission-tree client tree-query)
           expected-first
           (hash [(vec (:data cold-first))
                  (select-keys (:page-info cold-first)
                               [:has-next-page? :has-previous-page? :bounded?])])
           expected-continued
           (hash [(vec (:data cold-continued))
                  (select-keys
                   (:page-info cold-continued)
                   [:has-next-page? :has-previous-page? :bounded?])])
           expected-reverse
           (hash [(vec (:data cold-reverse))
                  (select-keys
                   (:page-info cold-reverse)
                   [:has-next-page? :has-previous-page? :bounded?])])
           expected-relationship-first
           (hash [(vec (:data cold-relationship-first))
                  (select-keys
                   (:page-info cold-relationship-first)
                   [:has-next-page? :has-previous-page? :bounded?])])
           expected-relationship-continued
           (hash [(vec (:data cold-relationship-continued))
                  (select-keys
                   (:page-info cold-relationship-continued)
                   [:has-next-page? :has-previous-page? :bounded?])])
           expected-relationship-reverse
           (hash [(vec (:data cold-relationship-reverse))
                  (select-keys
                   (:page-info cold-relationship-reverse)
                   [:has-next-page? :has-previous-page? :bounded?])])
           expected-tree (hash (:tree-root cold-tree))
           _ (when-not (= expected-count (:count cold-count))
               (throw (ex-info "Cold count disagrees with the fixture."
                               {:expected expected-count
                                :actual (:count cold-count)})))
           _ (when-not (and (= page-size (count (:data cold-first)))
                            (= page-size (count (:data cold-continued)))
                            (= page-size (count (:data cold-reverse)))
                            (= page-size
                               (count (:data cold-relationship-first)))
                            (= page-size
                               (count (:data cold-relationship-continued)))
                            (= page-size
                               (count (:data cold-relationship-reverse)))
                            (true? (:allowed? cold-point))
                            (pos-int? (:count cold-subject-count))
                            (map? (:tree-root cold-tree))
                            (true? (get-in cold-first
                                           [:page-info :has-next-page?]))
                            (false? (get-in cold-continued
                                            [:page-info :has-next-page?])))
               (throw (ex-info "Fixture did not produce two full pages."
                               {:first-page (:page-info cold-first)
                                :continued-page
                                (:page-info cold-continued)})))
           ;; Prove all three exact entries are resident before starting the
           ;; warmup phase; every later operation independently rejects a miss.
           _ (count-observation client count-query)
           _ (subject-count-observation client subject-count-query)
           _ (check-observation client point-query)
           _ (page-observation client first-query page-size)
           _ (page-observation client continued-query page-size)
           _ (page-observation client reverse-query page-size)
           _ (relationship-page-observation
              client relationship-query page-size)
           _ (relationship-page-observation
              client relationship-continued-query page-size)
           _ (relationship-page-observation
              client relationship-reverse-query page-size)
           _ (permission-tree-observation client tree-query)
           stats-before (datascript/cache-stats client)
           operations
           {:count-resources
            (measurement #(count-observation client count-query)
                         expected-count options)
            :count-subjects
            (measurement
             #(subject-count-observation client subject-count-query)
             (:count cold-subject-count) options)
            :check-permission
            (measurement #(check-observation client point-query)
                         true options)
            :first-page-64
            (measurement #(page-observation client first-query page-size)
                         expected-first options)
            :continued-page-64
            (measurement #(page-observation client continued-query page-size)
                         expected-continued options)
            :reverse-page-64
            (measurement #(page-observation client reverse-query page-size)
                         expected-reverse options)
            :relationship-first-page-64
            (measurement
             #(relationship-page-observation
               client relationship-query page-size)
             expected-relationship-first options)
            :relationship-continued-page-64
            (measurement
             #(relationship-page-observation
               client relationship-continued-query page-size)
             expected-relationship-continued options)
            :relationship-reverse-page-64
            (measurement
             #(relationship-page-observation
               client relationship-reverse-query page-size)
             expected-relationship-reverse options)
            :permission-tree
            (measurement #(permission-tree-observation client tree-query)
                         expected-tree options)}
           stats-after (datascript/cache-stats client)
           expected-count-hits (+ warmups samples)]
       (when-not (>= (- (:exact-hits stats-after 0)
                        (:exact-hits stats-before 0))
                     (* 4 expected-count-hits))
         (throw (ex-info "Scalar/tree samples did not register as exact cache hits."
                         {:before stats-before :after stats-after})))
       (when-not (and (>= (:rendered-page-entries stats-after 0) 6)
                      (= (:rendered-page-misses stats-before 0)
                         (:rendered-page-misses stats-after 0))
                      (= (:rendered-page-store-errors stats-before 0)
                         (:rendered-page-store-errors stats-after 0))
                      (= (:rendered-page-rejections stats-before 0)
                         (:rendered-page-rejections stats-after 0)))
         (throw (ex-info "Rendered-page hits re-entered or failed the cache."
                         {:before stats-before :after stats-after})))
       {:runtime {:java (System/getProperty "java.version")
                  :vm (System/getProperty "java.vm.name")
                  :processors (.availableProcessors (Runtime/getRuntime))}
        :fixture {:matching-items expected-count
                  :page-size page-size}
        :maximum-hit-ms maximum-hit-ms
        :all-cache-hits? true
        :checksums-equal? true
        :operations operations
        :cache-delta
        {:exact-hits (- (:exact-hits stats-after 0)
                        (:exact-hits stats-before 0))
         :rendered-page-misses
         (- (:rendered-page-misses stats-after 0)
            (:rendered-page-misses stats-before 0))
         :rendered-page-store-errors
         (- (:rendered-page-store-errors stats-after 0)
            (:rendered-page-store-errors stats-before 0))}}))))

(deftest ^{:benchmark true :acceptance true}
  warmed-public-core-cache-hits-are-submillisecond
  (let [{:keys [maximum-hit-ms operations all-cache-hits? checksums-equal?]
         :as report}
        (run-submillisecond-cache-hit-gate!)]
    (println "EACL submillisecond public cache-hit gate" (pr-str report))
    (is all-cache-hits?)
    (is checksums-equal?)
    (doseq [[operation {:keys [maximum-ms]}] operations]
      (testing (name operation)
        (is (< maximum-ms maximum-hit-ms)
            (str operation " every sampled hit must be under " maximum-hit-ms
                 " ms: " (pr-str report)))))))
