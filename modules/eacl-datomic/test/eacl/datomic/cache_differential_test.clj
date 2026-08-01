(ns eacl.datomic.cache-differential-test
  "Differential tests for the v8.0 cache, run THROUGH eacl.datomic.core/make-client.

  eacl.datomic.differential-test encodes the same invariants but evaluates raw
  eacl.datomic.impl against a bare db, so it never enters the cache or the
  consistency plumbing at all. Every finding in the 2026-07-31 adversarial
  review lived in that gap — most sharply a recursive page-cache key that
  omitted the pagination direction, so a `:last/:before` page was served to a
  later `:first/:after` request with the same cursor and size.

  The oracle is a {:cache cache/no-cache} client sharing the same :page-token-key, so a
  cursor minted by one client is readable by the other and any divergence is
  attributable to caching alone."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]))

(def ^:private token-key "cache-differential-test-key")

(def ^:private recursive-schema
  "definition user {}
   definition folder {
     relation owner: user
     relation parent: folder
     permission view = owner + parent->view
   }")

(def ^:private acyclic-schema
  "definition user {}
   definition group { relation member: user
                      permission access = member }
   definition team  { relation lead: user
                      relation grp: group
                      permission access = lead + grp->access }
   definition doc   { relation owner: user
                      relation team: team
                      relation grp: group
                      permission view = owner + team->access + grp->access }")

(defn- cache-configurations
  "Every shape the store can take, including one that evicts constantly so the
  d/as-of replay path is exercised rather than the continuation fast path."
  []
  [[:disabled {:cache cache/no-cache}]
   [:default {}]
   [:remember-answers {:cache {:remember-answers true}}]
   [:constant-eviction {:cache {:max-weight 8192
                                :max-entry-weight 4096
                                :max-entries 1}}]])

(defn- client
  [conn config]
  (core/make-client conn (assoc config :page-token-key token-key)))

(defn- seed-recursive!
  "f0 <- f1 <- ... <- fN via :parent, alice owns f0, so `view` is recursive and
  every folder is reachable."
  [conn boot n]
  (eacl/write-schema! boot recursive-schema)
  @(d/transact conn (vec (concat [{:eacl/id "alice"}]
                                 (for [k (range n)] {:eacl/id (str "f" k)}))))
  (eacl/create-relationship!
   boot (->Relationship (spice-object :user "alice") :owner (spice-object :folder "f0")))
  (doseq [k (range 1 n)]
    (eacl/create-relationship!
     boot (->Relationship (spice-object :folder (str "f" (dec k)))
                          :parent
                          (spice-object :folder (str "f" k))))))

(defn- seed-acyclic!
  "Overlapping union paths (direct owner, team->access, grp->access) so merge
  and dedupe are exercised alongside the arrow frontiers."
  [conn boot n]
  (eacl/write-schema! boot acyclic-schema)
  @(d/transact conn (vec (concat [{:eacl/id "alice"}]
                                 (for [k (range n)] {:eacl/id (str "g" k)})
                                 (for [k (range n)] {:eacl/id (str "t" k)})
                                 (for [k (range (* 2 n))] {:eacl/id (str "d" k)}))))
  (doseq [k (range n)]
    (eacl/create-relationship!
     boot (->Relationship (spice-object :user "alice") :member (spice-object :group (str "g" k))))
    (eacl/create-relationship!
     boot (->Relationship (spice-object :group (str "g" k)) :grp (spice-object :team (str "t" k))))
    (eacl/create-relationship!
     boot (->Relationship (spice-object :team (str "t" k)) :team (spice-object :doc (str "d" k)))))
  (doseq [k (range 0 (* 2 n) 3)]
    (eacl/create-relationship!
     boot (->Relationship (spice-object :user "alice") :owner (spice-object :doc (str "d" k)))))
  (doseq [k (range 1 (* 2 n) 5)]
    (eacl/create-relationship!
     boot (->Relationship (spice-object :group (str "g" (mod k n))) :grp (spice-object :doc (str "d" k))))))

(defn- walk-forward
  [acl query size]
  (loop [after nil acc [] guard 0]
    (is (< guard 500) "forward pagination terminated")
    (if (>= guard 500)
      acc
      (let [{:keys [data page-info]}
            (eacl/lookup-resources acl (cond-> (assoc query :first size)
                                         after (assoc :after after)))
            acc' (into acc (map :id) data)]
        (if (:has-next-page? page-info)
          (recur (:end-cursor page-info) acc' (inc guard))
          acc')))))

(defn- walk-backward
  [acl query size]
  (loop [before nil acc [] guard 0]
    (is (< guard 500) "backward pagination terminated")
    (if (>= guard 500)
      acc
      (let [{:keys [data page-info]}
            (eacl/lookup-resources acl (cond-> (assoc query :last size)
                                         before (assoc :before before)))
            acc' (into (vec (map :id data)) acc)]
        (if (:has-previous-page? page-info)
          (recur (:start-cursor page-info) acc' (inc guard))
          acc')))))

(defn- collect-cursors
  "Every start- and end-cursor along a forward walk, so jumps can bind a cursor
  that is simultaneously one page's end and another page's start."
  [acl query size]
  (loop [after nil cursors [] guard 0]
    (if (>= guard 500)
      cursors
      (let [{:keys [page-info]}
            (eacl/lookup-resources acl (cond-> (assoc query :first size)
                                         after (assoc :after after)))
            cursors' (into cursors (remove nil?) [(:start-cursor page-info)
                                                  (:end-cursor page-info)])]
        (if (:has-next-page? page-info)
          (recur (:end-cursor page-info) cursors' (inc guard))
          cursors')))))

(defn- page-ids
  [acl query]
  (try
    (mapv :id (:data (eacl/lookup-resources acl query)))
    (catch clojure.lang.ExceptionInfo e
      [::threw (:eacl/error (ex-data e))])))

(defn- assert-matches-oracle!
  [label acl oracle query recursive?]
  (let [truth (walk-forward oracle query 1000)]
    (testing (str label " — forward walk at several page sizes")
      (doseq [size [1 2 3 5 13]]
        (is (= truth (walk-forward acl query size))
            (str label " forward size " size))))

    (when-not recursive?
      (testing (str label " — backward walk at several page sizes")
        (doseq [size [1 2 3 5 13]]
          (is (= truth (walk-backward acl query size))
              (str label " backward size " size)))))

    (testing (str label " — single page and count agree with the walk")
      (is (= truth (mapv :id (:data (eacl/lookup-resources acl (assoc query :first 10000))))))
      (is (= (count truth) (:count (eacl/count-resources acl query)))))

    (testing (str label " — random cursor jumps in BOTH directions")
      ;; The regression that motivated this file: a :desc page cached under a
      ;; cursor, then an :asc request bound by that same cursor and size.
      (let [rng (java.util.Random. 20260731)
            cursors (vec (collect-cursors oracle query 3))]
        (dotimes [i 120]
          (let [cursor (nth cursors (.nextInt rng (count cursors)))
                size (inc (.nextInt rng 4))
                forward? (zero? (.nextInt rng 2))
                jump (if forward?
                       (assoc query :first size :after cursor)
                       (assoc query :last size :before cursor))]
            (is (= (page-ids oracle jump) (page-ids acl jump))
                (str label " jump #" i " " (if forward? :asc :desc)
                     " size " size))))))))

(deftest cache-configurations-match-a-cache-disabled-oracle-test
  (testing "recursive traversal permission"
    (doseq [[label config] (cache-configurations)]
      (with-mem-conn [conn schema/v7-schema]
        (let [boot (client conn {:cache cache/no-cache})
              _ (seed-recursive! conn boot 14)
              acl (client conn config)
              oracle (client conn {:cache cache/no-cache})
              query {:subject (spice-object :user "alice")
                     :permission :view
                     :resource/type :folder}]
          (assert-matches-oracle! (str "recursive/" (name label))
                                  acl oracle query true)))))

  (testing "acyclic union-of-arrows permission"
    (doseq [[label config] (cache-configurations)]
      (with-mem-conn [conn schema/v7-schema]
        (let [boot (client conn {:cache cache/no-cache})
              _ (seed-acyclic! conn boot 8)
              acl (client conn config)
              oracle (client conn {:cache cache/no-cache})
              query {:subject (spice-object :user "alice")
                     :permission :view
                     :resource/type :doc}]
          (assert-matches-oracle! (str "acyclic/" (name label))
                                  acl oracle query false))))))

;; --- writes interleaved with reads -------------------------------------------
;;
;; Everything above seeds once and then only reads, so it cannot observe a
;; stale cache at all. That gap matters now that exact-result entries are keyed
;; by per-relation stamps: an answer stays cached until a relation in ITS
;; dependency set is written, and the dependency set comes from
;; idx/permission-relationship-eids walking the permission graph. If that walk
;; ever misses a relation — an arrow target, a nested self-permission, a
;; recursive back-edge — the epoch will not move for a write that changes the
;; answer, and the cache serves a revoked grant.
;;
;; Rather than reason about the completeness of that walk, mutate every
;; relation in turn and diff against an uncached oracle after each write.

(defn- recursive-mutations
  [n]
  (vec (concat
        (for [k (range n)]
          (->Relationship (spice-object :user "alice") :owner
                          (spice-object :folder (str "f" k))))
        (for [k (range 1 n)]
          (->Relationship (spice-object :folder (str "f" (dec k))) :parent
                          (spice-object :folder (str "f" k)))))))

(defn- acyclic-mutations
  [n]
  (vec (concat
        (for [k (range n)]
          (->Relationship (spice-object :user "alice") :member
                          (spice-object :group (str "g" k))))
        (for [k (range n)]
          (->Relationship (spice-object :user "alice") :lead
                          (spice-object :team (str "t" k))))
        (for [k (range n)]
          (->Relationship (spice-object :group (str "g" k)) :grp
                          (spice-object :team (str "t" k))))
        (for [k (range (* 2 n))]
          (->Relationship (spice-object :group (str "g" (mod k n))) :grp
                          (spice-object :doc (str "d" k))))
        (for [k (range (* 2 n))]
          (->Relationship (spice-object :team (str "t" (mod k n))) :team
                          (spice-object :doc (str "d" k))))
        (for [k (range (* 2 n))]
          (->Relationship (spice-object :user "alice") :owner
                          (spice-object :doc (str "d" k)))))))

(defn- assert-agrees-under-writes!
  "Applies random relationship writes through `acl` and diffs every read shape
  against `oracle` after each one."
  [label acl oracle query resource-type ids mutations seed rounds]
  (let [rng (java.util.Random. seed)
        subject (:subject query)
        permission (:permission query)]
    (dotimes [i rounds]
      (let [relationship (nth mutations (.nextInt rng (count mutations)))
            ;; :touch rather than :create so a repeat is idempotent instead of
            ;; an :eacl/relationship-conflict.
            operation (if (zero? (.nextInt rng 2)) :touch :delete)]
        (eacl/write-relationships! acl [{:operation operation
                                         :relationship relationship}])
        (let [expected (mapv :id (:data (eacl/lookup-resources
                                         oracle (assoc query :first 10000))))
              actual (mapv :id (:data (eacl/lookup-resources
                                       acl (assoc query :first 10000))))]
          (is (= expected actual)
              (str label " round " i " after " operation " "
                   (pr-str relationship) " — lookup-resources diverged"))
          (is (= (count expected) (:count (eacl/count-resources acl query)))
              (str label " round " i " — count-resources diverged"))
          (doseq [id ids]
            (let [resource (spice-object resource-type id)]
              (is (= (eacl/can? oracle subject permission resource)
                     (eacl/can? acl subject permission resource))
                  (str label " round " i " — can? diverged on " id)))))))))

(deftest cache-agrees-with-the-oracle-across-interleaved-writes-test
  (testing "recursive traversal permission"
    (doseq [[label config] (cache-configurations)]
      (with-mem-conn [conn schema/v7-schema]
        (let [boot (client conn {:cache cache/no-cache})
              _ (seed-recursive! conn boot 8)
              acl (client conn config)
              oracle (client conn {:cache cache/no-cache})]
          (assert-agrees-under-writes!
           (str "recursive/" (name label))
           acl oracle
           {:subject (spice-object :user "alice")
            :permission :view
            :resource/type :folder}
           :folder
           (mapv #(str "f" %) (range 8))
           (recursive-mutations 8)
           20260731 40)))))

  (testing "acyclic union-of-arrows permission"
    (doseq [[label config] (cache-configurations)]
      (with-mem-conn [conn schema/v7-schema]
        (let [boot (client conn {:cache cache/no-cache})
              _ (seed-acyclic! conn boot 6)
              acl (client conn config)
              oracle (client conn {:cache cache/no-cache})]
          (assert-agrees-under-writes!
           (str "acyclic/" (name label))
           acl oracle
           {:subject (spice-object :user "alice")
            :permission :view
            :resource/type :doc}
           :doc
           (mapv #(str "d" %) (range 12))
           (acyclic-mutations 6)
           20260732 40))))))

(deftest acyclic-pages-replay-without-unauthenticated-stream-heads-test
  ;; v7 used a process-local side cache of per-intermediate stream heads. Those
  ;; values were not covered by the v3 causal/proof envelope, so v8 deliberately
  ;; refuses to read them. A cursor page instead replays deterministically from
  ;; the authenticated cursor against the proof-equivalent selected snapshot.
  ;; The optimization can return only after its state is authenticated by the
  ;; same contract as completed answers.
  (with-mem-conn [conn schema/v7-schema]
    (let [boot (client conn {:cache cache/no-cache})
          _ (seed-acyclic! conn boot 12)
          acl (client conn {})
          oracle (client conn {:cache cache/no-cache})
          query {:subject (spice-object :user "alice")
                 :permission :view
                 :resource/type :doc}
          stats (atom {})]
      (binding [idx/*recursive-traversal-stats* stats]
        (let [page-1 (eacl/lookup-resources acl (assoc query :first 3))
              _ (is (get-in page-1 [:page-info :has-next-page?]))
              cursor (get-in page-1 [:page-info :end-cursor])
              page-2 (eacl/lookup-resources acl (assoc query :first 3 :after cursor))]
          (is (zero? (:lookup-head-hits @stats 0))
              "page two did not trust the unauthenticated v7 heads side cache")
          (is (= (mapv :id (:data (eacl/lookup-resources
                                   oracle (assoc query :first 3 :after cursor))))
                 (mapv :id (:data page-2)))
              "cursor replay still agrees with an uncached client")))

      (testing "a walk with the continuation matches one without it"
        (is (= (walk-forward oracle query 3)
               (walk-forward acl query 3)))))))

(deftest recursive-page-cache-is-direction-scoped-test
  ;; Minimal reproduction of the review's C1. recursive-page-request-key omitted
  ;; the pagination direction, so {:last N :before C} was stored under exactly
  ;; the key {:first N :after C} read back. The caller silently received the
  ;; page BEFORE the cursor — or an empty page, which stops a paginating client
  ;; early and under-reports what a subject may access.
  (doseq [[label config] (cache-configurations)]
    (with-mem-conn [conn schema/v7-schema]
      (let [boot (client conn {:cache cache/no-cache})
            _ (seed-recursive! conn boot 12)
            acl (client conn config)
            oracle (client conn {:cache cache/no-cache})]

        (testing (str (name label) " — lookup-resources")
          (let [query {:subject (spice-object :user "alice")
                       :permission :view
                       :resource/type :folder}
                cursor (get-in (eacl/lookup-resources acl (assoc query :first 3))
                               [:page-info :end-cursor])
                backward (assoc query :last 3 :before cursor)
                forward (assoc query :first 3 :after cursor)]
            (is (= (page-ids oracle backward) (page-ids acl backward)))
            ;; The forward request must not be answered by the backward page
            ;; just cached under the same cursor and size.
            (is (= (page-ids oracle forward) (page-ids acl forward))
                (str (name label) ": forward page after a backward page"))
            (is (= ["f3" "f4" "f5"] (page-ids acl forward)))))

        (testing (str (name label) " — lookup-subjects")
          (let [users (mapv #(str "u" %) (range 8))]
            @(d/transact conn (mapv (fn [u] {:eacl/id u}) users))
            (doseq [u users]
              (eacl/create-relationship!
               boot (->Relationship (spice-object :user u) :owner (spice-object :folder "f0"))))
            (let [query {:resource (spice-object :folder "f5")
                         :permission :view
                         :subject/type :user}
                  cursor (get-in (eacl/lookup-subjects acl (assoc query :first 3))
                                 [:page-info :end-cursor])
                  backward (assoc query :last 3 :before cursor)
                  forward (assoc query :first 3 :after cursor)
                  ids #(mapv :id (:data (eacl/lookup-subjects % %2)))]
              (is (= (ids oracle backward) (ids acl backward)))
              (is (= (ids oracle forward) (ids acl forward))
                  (str (name label) ": forward subjects after a backward page")))))))))
