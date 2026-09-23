(ns eacl.operator-engine.recursive-intersection-differential
  "Randomized differential for operators over recursive permissions: every
  public operation on `delete = delete_granted & read_account` (and on an
  exclusion and an inline-arrow intersection over the same operands) equals
  set algebra over the operands' own union answers.

  Charts are random forests with extra parent edges, so parent cycles and
  shared ancestors occur; relationships expire at random times before, between
  and after the three evaluation times. Backend-agnostic: the caller supplies
  `new-store`, which returns a client over an empty store whose clock reads
  the supplied atom, and an `add-objects!` that makes object ids resolvable."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.set :as set]
            [eacl.core :as eacl]))

(def schema
  "definition user {}

   definition ledger {
     relation owner: user
     relation viewer: user

     permission administer = owner
     permission view = administer + viewer
   }

   definition account {
     relation ledger: ledger
     relation parent: account
     relation reader: user
     relation deleter: user
     relation manager: user

     permission direct_read = reader + manager + ledger->viewer
     permission read_account = direct_read + ledger->view + parent->read_account
     permission delete_granted = deleter + manager + ledger->administer + parent->delete_granted
     permission grant_path = deleter + parent->delete_granted

     permission delete = delete_granted & read_account
     permission prune = delete_granted - read_account
     permission gated = (deleter + parent->delete_granted) & read_account
   }")

(def operators
  "Each operator permission and the set algebra over union permissions that
  defines it. `grant_path` spells the inline operand of `gated` as a union
  permission of its own."
  {:delete [:intersection :delete_granted :read_account]
   :prune [:exclusion :delete_granted :read_account]
   :gated [:intersection :grant_path :read_account]})

(def ^:private start-time 1000)
(def evaluation-times
  "Before any expiry, between the two expiry bands, and after both."
  [start-time (+ start-time 10) (+ start-time 40)])

;; ---------------------------------------------------------------------------
;; A portable seeded generator (Park-Miller; exact in JavaScript numbers)
;; ---------------------------------------------------------------------------

(defn- next-state [state] (mod (* state 48271) 2147483647))

(defn- generator [seed] (volatile! (inc (mod seed 2147483646))))

(defn- draw!
  "A uniform integer in [0, n)."
  [rng n]
  (vswap! rng next-state)
  (mod @rng n))

(defn- chance? [rng percent] (< (draw! rng 100) percent))

(defn- expiry!
  "No expiry, an already expired one, or one inside either later band."
  [rng]
  (case (draw! rng 8)
    0 (- start-time 1 (draw! rng 5))
    1 (+ start-time 1 (draw! rng 8))
    2 (+ start-time 12 (draw! rng 20))
    nil))

(defn- object [type id] (eacl/spice-object type id))

(defn chart
  "A random chart: accounts, ledgers, users and relationships."
  [seed]
  (let [rng (generator seed)
        accounts (mapv #(object :account (str "account-" %))
                       (range (+ 6 (draw! rng 19))))
        ledgers (mapv #(object :ledger (str "ledger-" %)) (range (inc (draw! rng 2))))
        users (mapv #(object :user %) ["alice" "bob" "carol" "dave"])
        relationship (fn [subject relation resource]
                       (let [end (expiry! rng)]
                         (cond-> (eacl/->Relationship subject relation resource)
                           end (assoc :valid-until-ms end))))
        parents
        (concat
         (for [index (range 1 (count accounts))
               :when (chance? rng 85)]
           (relationship (accounts (draw! rng index)) :parent (accounts index)))
         ;; Extra parents: shared ancestors, back edges and self-loops.
         (for [index (range (count accounts))
               :when (chance? rng 20)]
           (relationship (accounts (draw! rng (count accounts))) :parent
                         (accounts index))))
        placements
        (for [account accounts
              :when (chance? rng 50)]
          (relationship (ledgers (draw! rng (count ledgers))) :ledger account))
        grants
        (concat
         (for [user users
               account accounts
               [relation percent] [[:reader 12] [:deleter 12] [:manager 4]]
               :when (chance? rng percent)]
           (relationship user relation account))
         (for [user users
               ledger ledgers
               [relation percent] [[:owner 20] [:viewer 20]]
               :when (chance? rng percent)]
           (relationship user relation ledger)))]
    {:accounts accounts
     :ledgers ledgers
     :users users
     ;; One relationship per endpoint triple: the store keeps one row.
     :relationships (vals (into {}
                                (map (juxt (juxt :subject :relation :resource) identity))
                                (concat parents placements grants)))}))

;; ---------------------------------------------------------------------------
;; Public operations
;; ---------------------------------------------------------------------------

(defn- walk
  "Every page of a forward or reverse cursor walk, concatenated. Asserts
  that each page makes progress."
  [client direction query page-size]
  (let [lookup (if (= :forward direction) eacl/lookup-resources eacl/lookup-subjects)]
    (loop [after nil pages 0 acc []]
      (let [page (lookup client (cond-> (assoc query :first page-size)
                                  after (assoc :after after)))
            acc (into acc (:data page))]
        (when (> pages 200)
          (throw (ex-info "Walk did not terminate." {:query query})))
        (if (get-in page [:page-info :has-next-page?])
          (let [cursor (get-in page [:page-info :end-cursor])]
            (is (and cursor (not= after cursor)) "each page advances the cursor")
            (recur cursor (inc pages) acc))
          acc)))))

(defn- answer
  "The set algebra over two operand sets."
  [[op _ _] left right]
  (case op
    :intersection (set/intersection left right)
    :exclusion (set/difference left right)))

(defn- check-forward
  [client {:keys [accounts]} user cache?]
  (let [query (fn [permission]
                {:subject user :permission permission
                 :resource/type :account :cache? cache?})
        operand (memoize #(set (walk client :forward (query %) 1000)))]
    (doseq [[permission [_ left right :as definition]] operators]
      (let [expected (answer definition (operand left) (operand right))
            walks (mapv #(walk client :forward (query permission) %) [1 3 7 1000])]
        (testing (str (:id user) " " permission)
          (is (= expected (set (first walks))) "lookup-resources")
          (is (apply = walks) "every page size walks the same sequence")
          (is (= (count (first walks)) (count (set (first walks)))) "no duplicates")
          (is (= (count expected)
                 (:count (eacl/count-resources client (query permission))))
              "count-resources")
          (doseq [account accounts]
            (let [request {:subject user :permission permission
                           :resource account :cache? cache?}
                  allowed? (contains? expected account)]
              (is (= allowed? (eacl/can? client request)) (str "can? " (:id account)))
              (is (= allowed? (:allowed? (eacl/check-permission client request)))
                  (str "check-permission " (:id account))))))))))

(defn- check-reverse
  [client {:keys [accounts]} cache?]
  (doseq [account accounts]
    (let [query (fn [permission]
                  {:resource account :permission permission
                   :subject/type :user :cache? cache?})
          operand (memoize #(set (walk client :reverse (query %) 1000)))]
      (doseq [[permission definition] operators]
        (let [expected (answer definition (operand (nth definition 1))
                               (operand (nth definition 2)))]
          (testing (str (:id account) " " permission " subjects")
            (is (= expected (set (walk client :reverse (query permission) 2)))
                "lookup-subjects")
            (is (= (count expected)
                   (:count (eacl/count-subjects client (query permission))))
                "count-subjects")))))))

(defn run-seed!
  "Seeds one random chart into a fresh store and checks every operation at
  every evaluation time, with and without the shared caches."
  [{:keys [new-store]} seed]
  (let [now (atom start-time)
        {:keys [client add-objects!]} (new-store now)
        {:keys [accounts ledgers users relationships] :as generated} (chart seed)]
    (eacl/write-schema! client schema)
    (add-objects! (map :id (concat users ledgers accounts)))
    (eacl/create-relationships! client (vec relationships))
    (doseq [time evaluation-times
            cache? [false true]]
      (reset! now time)
      (testing (str "seed " seed ", time " time ", cache? " cache?)
        (doseq [user users]
          (check-forward client generated user cache?))
        (check-reverse client generated cache?)))))
