(ns eacl.bench.cross-backend-workload-test
  "Explicit all-backend workload matrix for latency-regression evidence.

  This heavy namespace is excluded from the normal suite. Wall time, logical
  result cardinality, and backend work counters are recorded independently."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [datascript.core :as ds]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datascript.core :as datascript]
            [eacl.datomic.cache :as datomic-cache]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.engine.v8 :as engine])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(def ^:private user-count 32)
(def ^:private permitted-user-count 16)
(def ^:private group-depth 32)
(def ^:private document-count 256)
(def ^:private page-size 20)
(def ^:private warmup-samples 3)
(def ^:private measurement-samples 11)

(def ^:private allocation-bean
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (and (instance? ThreadMXBean bean)
               (.isThreadAllocatedMemorySupported ^ThreadMXBean bean))
      (when-not (.isThreadAllocatedMemoryEnabled ^ThreadMXBean bean)
        (.setThreadAllocatedMemoryEnabled ^ThreadMXBean bean true))
      bean)))

(def ^:private schema-text
  "definition user {}
   definition group {
     relation member: user
     relation parent: group
     permission access = member + parent->access
   }
   definition document {
     relation reader: user
     relation owner: user
     relation group: group
     permission direct = reader
     permission acyclic = reader + owner
     permission shared = group->access
     permission shared_alt = group->access
   }")

(defn- user
  [index]
  (eacl/spice-object :user (format "user-%02d" index)))

(defn- group
  [index]
  (eacl/spice-object :group (format "group-%02d" index)))

(defn- document
  [index]
  (eacl/spice-object :document (format "document-%03d" index)))

(defn- percentile
  [samples p]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (long (Math/floor (* p (count ordered)))))]
    (nth ordered index)))

(defn- allocated-bytes
  []
  (when allocation-bean
    (let [allocated
          (.getThreadAllocatedBytes
           ^ThreadMXBean allocation-bean
           (.getId (Thread/currentThread)))]
      (when-not (neg? allocated)
        allocated))))

(defn- timed-samples
  [operation]
  (let [samples (atom [])
        allocation-samples (atom [])
        checksum (atom 0)
        work (atom {})]
    (dotimes [iteration (+ warmup-samples measurement-samples)]
      (let [allocated-before (allocated-bytes)
            started (System/nanoTime)
            value
            (binding [engine/*backend-work-stats* work]
              (operation iteration))
            elapsed
            (/ (double (- (System/nanoTime) started)) 1000000.0)
            allocated-after (allocated-bytes)]
        (swap!
         checksum
         #(unchecked-add (long %) (long (hash value))))
        (when (>= iteration warmup-samples)
          (swap! samples conj elapsed)
          (when (and allocated-before allocated-after)
            (swap!
             allocation-samples
             conj
             (- allocated-after allocated-before))))))
    {:samples-ms @samples
     :p50-ms (percentile @samples 0.50)
     :p95-ms (percentile @samples 0.95)
     :allocated-bytes @allocation-samples
     :p50-allocated-bytes
     (when (seq @allocation-samples)
       (percentile @allocation-samples 0.50))
     :p95-allocated-bytes
     (when (seq @allocation-samples)
       (percentile @allocation-samples 0.95))
     :checksum @checksum
     :backend-work @work}))

(defn- normalize-page
  [page]
  {:ids (mapv :id (:data page))
   :has-next-page?
   (get-in page [:page-info :has-next-page?])
   :has-previous-page?
   (get-in page [:page-info :has-previous-page?])})

(defn- fixture-objects
  []
  (vec
   (concat
    (map user (range user-count))
    (map group (range group-depth))
    (map document (range document-count)))))

(defn- fixture-relationships
  []
  (vec
   (concat
    ;; Sixteen permitted and sixteen denied principals.
    (map
     #(eacl/->Relationship (user %) :member (group 0))
     (range permitted-user-count))
    ;; A deep recursive chain plus a cycle. The direct members seed the least
    ;; fixed point, so every group remains reachable without an infinite walk.
    (map
     #(eacl/->Relationship
       (group (dec %)) :parent (group %))
     (range 1 group-depth))
    [(eacl/->Relationship
      (group (dec group-depth)) :parent (group 0))]
    ;; Wide shared fan-out: every document points to the same terminal group.
    (map
     #(eacl/->Relationship
       (group (dec group-depth)) :group (document %))
     (range document-count))
    ;; A separate acyclic wide projection supports backwards pagination
    ;; without asking the recursive engine for an inherently full `:last`.
    (map
     #(eacl/->Relationship (user 1) :owner (document %))
     (range document-count))
    [(eacl/->Relationship (user 0) :reader (document 0))])))

(defn- cache-config
  [mode datomic?]
  (case mode
    :cache-free
    (if datomic? datomic-cache/no-cache shared-cache/no-cache)

    :completed-answer-only
    {:remember-answers true
     :subproblem-cache {:enabled? false}}

    :layered-current
    {:remember-answers true}))

(defn- make-clients
  [make-client connection datomic?]
  (into
   {}
   (map
    (fn [mode]
      [mode
       (make-client
        connection
        (merge
         {:cache (cache-config mode datomic?)}
         (if datomic?
           {:page-token-key "cross-backend-workload-page"
            :zed-token-key "cross-backend-workload-zed"}
           {:security-key
            "01234567890123456789012345678901"})))])
    [:cache-free :completed-answer-only :layered-current])))

(defn- seed!
  [{:keys [writer transact-objects!]}]
  (eacl/write-schema! writer schema-text)
  (transact-objects! (fixture-objects))
  (eacl/create-relationships! writer (fixture-relationships)))

(defn- scenario-operations
  [client]
  (let [forward-query
        {:subject (user 0)
         :permission :shared
         :resource/type :document
         :first page-size}
        reverse-query
        {:resource (document (dec document-count))
         :permission :shared
         :subject/type :user
         :first page-size}
        acyclic-forward-query
        {:subject (user 1)
         :permission :acyclic
         :resource/type :document
         :first page-size}
        forward-first (eacl/lookup-resources client forward-query)
        reverse-last
        (eacl/lookup-resources
         client
         (-> acyclic-forward-query
             (dissoc :first)
             (assoc :last page-size)))
        forward-cursor
        (get-in forward-first [:page-info :end-cursor])
        reverse-cursor
        (get-in reverse-last [:page-info :start-cursor])]
    {:direct-can
     (fn [_]
       (eacl/can?
        client (user 0) :direct (document 0)))

     :acyclic-can
     (fn [_]
       (eacl/can?
        client (user 1) :acyclic (document 0)))

     :recursive-scc-deep-chain-can
     #(eacl/can?
       client (user 0) :shared (document (mod % document-count)))

     :shared-arrow-distinct-top-level
     #(eacl/can?
       client
       (user 0)
       (if (even? %) :shared :shared_alt)
       (document (mod % document-count)))

     :shared-negative-probe
     #(eacl/can?
       client
       (user (dec user-count))
       :shared
       (document (mod % document-count)))

     :wide-fanout-forward-lookup
     (fn [_]
       (normalize-page
        (eacl/lookup-resources client forward-query)))

     :reverse-lookup-mixed-principals
     (fn [_]
       (normalize-page
        (eacl/lookup-subjects client reverse-query)))

     :count-resources
     (fn [_]
       (select-keys
        (eacl/count-resources
         client
         (dissoc forward-query :first))
        [:count :truncated?]))

     :count-subjects
     (fn [_]
       (select-keys
        (eacl/count-subjects
         client
         (dissoc reverse-query :first))
        [:count :truncated?]))

     :mixed-principal-batch
     #(mapv
       (fn [principal-index]
         (eacl/can?
          client
          (user principal-index)
          :shared
          (document (mod % document-count))))
       (range user-count))

     :forward-cursor-continuation
     (fn [_]
       (normalize-page
        (eacl/lookup-resources
         client
         (assoc forward-query :after forward-cursor))))

     :reverse-cursor-continuation
     (fn [_]
       (normalize-page
        (eacl/lookup-resources
         client
         (-> acyclic-forward-query
             (dissoc :first)
             (assoc :last page-size
                    :before reverse-cursor)))))}))

(defn- assert-fixture-correct!
  [client]
  (is (true? (eacl/can?
              client (user 0) :direct (document 0))))
  (is (true? (eacl/can?
              client (user 1) :acyclic (document 0))))
  (is (true? (eacl/can?
              client
              (user 0) :shared (document (dec document-count)))))
  (is (false? (eacl/can?
               client
               (user (dec user-count))
               :shared
               (document (dec document-count)))))
  (is (= document-count
         (:count
          (eacl/count-resources
           client
           {:subject (user 0)
            :permission :shared
            :resource/type :document}))))
  (is (= permitted-user-count
         (:count
          (eacl/count-subjects
           client
           {:resource (document (dec document-count))
            :permission :shared
            :subject/type :user})))))

(defn- run-backend!
  [{:keys [label clients close!]}]
  (try
    (doseq [[mode client] clients]
      (testing (str (name label) " " (name mode))
        (assert-fixture-correct! client)))
    {:backend label
     :modes
     (into
      {}
      (map
       (fn [[mode client]]
         [mode
          (into
           {}
           (map
            (fn [[scenario operation]]
              [scenario (timed-samples operation)]))
           (scenario-operations client))]))
      clients)}
    (finally
      (close!))))

(defn- datomic-fixture
  []
  (let [uri
        (str "datomic:mem://eacl-cross-backend-workload-" (random-uuid))
        _ (d/create-database uri)
        connection (d/connect uri)
        _ @(d/transact connection datomic-schema/v7-schema)
        clients (make-clients datomic/make-client connection true)
        fixture
        {:writer (:layered-current clients)
         :transact-objects!
         (fn [objects]
           @(d/transact
             connection
             (mapv
              (fn [object]
                {:eacl/id (:id object)})
              objects)))}]
    (seed! fixture)
    {:label :datomic
     :clients clients
     :close! #(d/delete-database uri)}))

(defn- datahike-fixture
  []
  (let [connection (datahike/create-conn)
        clients (make-clients datahike/make-client connection false)
        fixture
        {:writer (:layered-current clients)
         :transact-objects!
         (fn [objects]
           (dh/transact
            connection
            (mapv
             (fn [object]
               {:eacl/id (:id object)})
             objects)))}]
    (seed! fixture)
    {:label :datahike
     :clients clients
     :close! #(dh/release connection)}))

(defn- datascript-fixture
  []
  (let [connection (datascript/create-conn)
        clients (make-clients datascript/make-client connection false)
        fixture
        {:writer (:layered-current clients)
         :transact-objects!
         (fn [objects]
           (ds/transact!
            connection
            (mapv
             (fn [object]
               {:eacl/id (:id object)})
             objects)))}]
    (seed! fixture)
    {:label :datascript
     :clients clients
     :close! (constantly nil)}))

(defn run-matrix!
  "Executes the complete benchmark and returns its raw sample map."
  []
  (mapv
   (fn [fixture-fn]
     (run-backend! (fixture-fn)))
   [datomic-fixture datahike-fixture datascript-fixture]))

(deftest ^:benchmark all-backend-cache-mode-workload-matrix-test
  (let [results (run-matrix!)]
    (println "EACL cross-backend workload samples" (pr-str results))
    (is (= [:datomic :datahike :datascript]
           (mapv :backend results)))
    (doseq [result results]
      (is (= #{:cache-free
               :completed-answer-only
               :layered-current}
             (set (keys (:modes result)))))
      (doseq [[_ scenarios] (:modes result)]
        (is (= #{:direct-can
                 :acyclic-can
                 :recursive-scc-deep-chain-can
                 :shared-arrow-distinct-top-level
                 :shared-negative-probe
                 :wide-fanout-forward-lookup
                 :reverse-lookup-mixed-principals
                 :count-resources
                 :count-subjects
                 :mixed-principal-batch
                 :forward-cursor-continuation
                 :reverse-cursor-continuation}
               (set (keys scenarios))))
        (doseq [[scenario measurement] scenarios]
          (testing (str (name (:backend result)) " " (name scenario))
            (is (= measurement-samples
                   (count (:samples-ms measurement))))
            (is (= measurement-samples
                   (count (:allocated-bytes measurement))))
            (is (pos? (:p50-ms measurement)))
            (is (pos? (:p50-allocated-bytes measurement)))
            (is (not (zero? (:checksum measurement))))))))
    (doseq [scenario
            (keys (get-in (first results) [:modes :cache-free]))]
      (is
       (apply
        =
        (for [result results
              mode [:cache-free
                    :completed-answer-only
                    :layered-current]]
          (get-in result [:modes mode scenario :checksum])))
       (str (name scenario)
            " returned different values between a backend or cache mode")))))
