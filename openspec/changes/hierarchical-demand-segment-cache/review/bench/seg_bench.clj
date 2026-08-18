(ns seg-bench
  "REPL-only feasibility/perf harness for the segment-cache review. Not source."
  (:require [datomic.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.core :as spiceomic]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.backend :as dbackend]
            [eacl.backend.v8 :as backend]
            [eacl.engine.v8 :as engine]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-page :as stable-page]
            [eacl.subproblem-cache :as subproblem]
            [eacl.cache :as cache]))

;; ---------------------------------------------------------------------------
;; timing helpers
;; ---------------------------------------------------------------------------

(defn now-ns [] (System/nanoTime))

(defn median [xs]
  (let [s (vec (sort xs)) n (count s)]
    (if (zero? n) nil (nth s (quot n 2)))))

(defn pct [xs p]
  (let [s (vec (sort xs)) n (count s)]
    (nth s (min (dec n) (int (Math/floor (* p n)))))))

(defmacro timed-us
  "Runs body once, returns [micros result]."
  [& body]
  `(let [t0# (System/nanoTime)
         r# (do ~@body)]
     [(/ (double (- (System/nanoTime) t0#)) 1000.0) r#]))

(defn bench
  "Runs thunk `warm` times untimed then `n` times timed; returns summary in µs."
  [label thunk & {:keys [warm n] :or {warm 20 n 200}}]
  (dotimes [_ warm] (thunk))
  (let [samples (vec (repeatedly n #(first (timed-us (thunk)))))]
    {:label label
     :n n
     :median-us (median samples)
     :p90-us (pct samples 0.9)
     :min-us (apply min samples)}))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(def ->user (partial spice-object :user))
(def ->account (partial spice-object :account))
(def ->server (partial spice-object :server))
(def ->platform (partial spice-object :platform))
(def ->group (partial spice-object :group))
(def ->doc (partial spice-object :doc))

(defn mem-conn! []
  (let [uri (str "datomic:mem://segbench-" (java.util.UUID/randomUUID))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema/v7-schema)
      conn)))

(defn- tx-rels [db rels]
  (impl/optimistic-relationship-tx-data
   db (mapcat #(impl/tx-relationship db % {:allow-tempids? true}) rels)))

(def schema-a
  "definition user {}
   definition platform {
     relation super_admin: user
   }
   definition account {
     relation owner: user
     relation platform: platform
     permission admin = owner + platform->super_admin
   }
   definition server {
     relation account: account
     relation shared_admin: user
     permission view = account->admin + shared_admin
   }")

(defn seed-a!
  "Fixture A: super-user reaches every account via platform; each account has
  one owner (normal user) and `servers-per-account` servers."
  [conn {:keys [accounts servers-per-account]}]
  (let [acl0 (spiceomic/make-client conn {})]
    (eacl/write-schema! acl0 schema-a)
    @(d/transact conn
                 (concat
                  [{:db/id "platform" :eacl/id "platform"}
                   {:db/id "super" :eacl/id "super"}]
                  (tx-rels (d/db conn)
                           [(Relationship (->user "super") :super_admin (->platform "platform"))])))
    (doseq [a (range accounts)]
      (let [acc-id (str "acc-" a)
            owner-id (str "owner-" a)
            servers (for [s (range servers-per-account)] (str "srv-" a "-" s))]
        @(d/transact conn
                     (concat
                      [{:db/id acc-id :eacl/id acc-id}
                       {:db/id owner-id :eacl/id owner-id}]
                      (for [s servers] {:db/id s :eacl/id s})
                      (tx-rels (d/db conn)
                               (concat
                                [(Relationship (->platform "platform") :platform (->account acc-id))
                                 (Relationship (->user owner-id) :owner (->account acc-id))]
                                (for [s servers]
                                  (Relationship (->account acc-id) :account (->server s)))))))))
    (spiceomic/make-client conn {})))

(def schema-b
  "definition user {}
   definition group {
     relation member: user
   }
   definition doc {
     relation group: group
     permission view = group->member
   }")

(defn seed-b!
  "Fixture B: high-sharing sparse graph. `users` users, `groups` groups; each
  user is member of `groups-per-user` random groups; each group has 0..3 docs
  (`empty-fraction` of groups have none)."
  [conn {:keys [users groups groups-per-user empty-fraction seed]
         :or {empty-fraction 0.6 seed 42}}]
  (let [acl0 (spiceomic/make-client conn {})
        rng (java.util.Random. seed)]
    (eacl/write-schema! acl0 schema-b)
    ;; groups + docs
    (doseq [batch (partition-all 50 (range groups))]
      @(d/transact conn
                   (mapcat (fn [g]
                             (let [gid (str "grp-" g)
                                   ndocs (if (< (.nextDouble rng) empty-fraction)
                                           0 (inc (.nextInt rng 3)))
                                   docs (for [i (range ndocs)] (str "doc-" g "-" i))]
                               (concat [{:db/id gid :eacl/id gid}]
                                       (for [dd docs] {:db/id dd :eacl/id dd})
                                       (tx-rels (d/db conn)
                                                (for [dd docs]
                                                  (Relationship (->group gid) :group (->doc dd)))))))
                           batch)))
    ;; users + memberships
    (doseq [batch (partition-all 20 (range users))]
      @(d/transact conn
                   (mapcat (fn [u]
                             (let [uid (str "user-" u)
                                   gs (take groups-per-user (shuffle (range groups)))]
                               (concat [{:db/id uid :eacl/id uid}]
                                       (tx-rels (d/db conn)
                                                (for [g gs]
                                                  (Relationship (->user uid) :member (->group (str "grp-" g))))))))
                           batch)))
    (spiceomic/make-client conn {})))

;; ---------------------------------------------------------------------------
;; page helpers
;; ---------------------------------------------------------------------------

(defn page
  [acl subject rtype perm n & {:keys [cache? after] :or {cache? true}}]
  (eacl/lookup-resources acl (cond-> {:subject subject :permission perm
                                      :resource/type rtype :first n
                                      :cache? cache?}
                               after (assoc :after after))))

(defn page-with-stats
  "Runs a page with the traversal observer bound; returns [result stats]."
  [acl subject rtype perm n & opts]
  (let [stats (atom {})]
    (binding [engine/*recursive-traversal-stats* stats]
      (let [r (apply page acl subject rtype perm n opts)]
        [r @stats]))))

;; ---------------------------------------------------------------------------
;; raw engine access (bypasses the client layers)
;; ---------------------------------------------------------------------------

(defn adapter [conn] (dbackend/snapshot-adapter (d/db conn)))

(defn eid [db ext] (:db/id (d/entity db [:eacl/id ext])))

(defn plan-for [adapter root-node] (sealed-plan/seal-plan adapter root-node))

(defn counting-fetch-fn
  "Wraps a fetch-fn, counting calls and accumulating adapter time in ns."
  [fetch-fn counters]
  (fn [descriptor]
    (let [t0 (System/nanoTime)
          r (fetch-fn descriptor)]
      (swap! counters (fn [c] (-> c (update :calls (fnil inc 0))
                                  (update :ns (fnil + 0) (- (System/nanoTime) t0)))))
      r)))

(defn run-forward-raw
  "Runs the stable reducer directly to `target` results with a given fetch-fn."
  [adapter plan subject-type subject-eid target fetch-fn]
  (reducer/run-forward {:adapter adapter :fetch-fn fetch-fn :plan plan
                        :subject-type subject-type :subject-eid subject-eid
                        :target target}))

;; ---------------------------------------------------------------------------
;; PROTOTYPE: scan-prefix cache at the fetch-fn seam (REPL only)
;; ---------------------------------------------------------------------------
;; key: descriptor minus :bound-eid/:limit (validity stamps omitted here: the
;; adapter is bound to one immutable db value, so validity is trivially fixed
;; for the experiment).
;; value: {:prefix [eids ascending from scan start] :exhausted? bool}
;; contract preserved: returns exactly what the adapter would return for
;; (bound, limit): up to `limit` values strictly after bound; fewer only when
;; the scan is exhausted.

(defn- scan-key [descriptor] (dissoc descriptor :bound-eid :limit))

(defn- prefix-after
  "Values in prefix strictly greater than bound (nil bound = from start)."
  [^clojure.lang.PersistentVector prefix bound]
  (if (nil? bound)
    prefix
    ;; binary search for first index with value > bound
    (let [n (count prefix)]
      (loop [lo 0 hi n]
        (if (< lo hi)
          (let [mid (quot (+ lo hi) 2)]
            (if (<= (compare (nth prefix mid) bound) 0)
              (recur (inc mid) hi)
              (recur lo mid)))
          (subvec prefix lo))))))

(defn scan-prefix-fetch-fn
  "Returns [fetch-fn stats-atom]. `cache` is a java.util.concurrent.ConcurrentHashMap."
  [inner ^java.util.concurrent.ConcurrentHashMap cache]
  (let [stats (atom {:hits 0 :misses 0 :extends 0 :adapter-calls 0})]
    [(fn [{:keys [bound-eid limit] :as descriptor}]
       (let [k (scan-key descriptor)
             entry (.get cache k)
             avail (when entry (prefix-after (:prefix entry) bound-eid))]
         (cond
           ;; full hit: enough values or exhausted
           (and entry (or (>= (count avail) limit) (:exhausted? entry)))
           (do (swap! stats update :hits inc)
               (into [] (take limit) avail))

           ;; contiguous extend: bound lies within/at end of prefix; fetch tail
           ;; from the last cached value and append (this request would have
           ;; fetched these values anyway: no widening).
           (and entry (or (nil? bound-eid)
                          (<= (compare bound-eid (peek (:prefix entry))) 0)))
           (let [last-cached (peek (:prefix entry))
                 fetched (inner (assoc descriptor :bound-eid last-cached :limit limit))
                 exhausted? (< (count fetched) limit)
                 prefix' (into (:prefix entry) fetched)]
             (swap! stats #(-> % (update :extends inc) (update :adapter-calls inc)))
             (.put cache k {:prefix prefix' :exhausted? exhausted?})
             (into [] (take limit) (prefix-after prefix' bound-eid)))

           ;; miss (no entry, or bound beyond prefix): plain fetch; seed only when
           ;; the scan starts at the beginning
           :else
           (let [fetched (inner descriptor)]
             (swap! stats #(-> % (update :misses inc) (update :adapter-calls inc)))
             (when (nil? bound-eid)
               (.put cache k {:prefix (vec fetched) :exhausted? (< (count fetched) limit)}))
             fetched))))
     stats]))

;; ---------------------------------------------------------------------------
;; store-overhead microbench
;; ---------------------------------------------------------------------------

(defn store-microbench
  "Compares eacl.subproblem-cache lookup!/publish! against a ConcurrentHashMap
  for a per-scan style hit path, single-threaded and under contention."
  [& {:keys [threads ops] :or {threads 8 ops 20000}}]
  (let [store (subproblem/store {})
        chm (java.util.concurrent.ConcurrentHashMap.)
        keys* (vec (for [i (range 256)] [:scan i]))
        opts {:valid? (constantly true) :weight-fn (constantly 64)}]
    (doseq [k keys*]
      (subproblem/publish! store :projection k opts {:prefix [1 2 3]})
      (.put chm k {:prefix [1 2 3]}))
    (let [single-store (bench "store lookup! single"
                              #(subproblem/lookup! store :projection (keys* (rand-int 256)) opts)
                              :n 5000 :warm 500)
          single-chm (bench "CHM get single"
                            #(.get chm (keys* (rand-int 256)))
                            :n 5000 :warm 500)
          contended (fn [label f]
                      (let [latch (java.util.concurrent.CountDownLatch. 1)
                            done (java.util.concurrent.CountDownLatch. threads)
                            ts (doall (for [_ (range threads)]
                                        (doto (Thread. (fn []
                                                         (.await latch)
                                                         (dotimes [_ ops] (f))
                                                         (.countDown done)))
                                          .start)))
                            t0 (System/nanoTime)]
                        (.countDown latch)
                        (.await done)
                        (let [total-ns (- (System/nanoTime) t0)]
                          {:label label :threads threads :ops-per-thread ops
                           :ns-per-op (/ (double total-ns) (* threads ops))
                           :throughput-mops (/ (* threads ops) (/ total-ns 1e3))})))]
      {:single [single-store single-chm]
       :contended [(contended "store lookup! contended"
                              #(subproblem/lookup! store :projection (keys* (rand-int 256)) opts))
                   (contended "CHM get contended"
                              #(.get chm (keys* (rand-int 256))))]})))
