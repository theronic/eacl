(ns eacl.engine.stable-page-test
  "Section 6 gates: page composition, cursor fork/idempotence, checkpoint
  versus replay equivalence, lookahead survival across checkpoints,
  backward navigation, token rejection classes, the typed
  resource-exhaustion cliff, and the remaining continuation mutation
  controls (checkpoint regression, missing lookahead segment, stale
  basis)."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            #?(:clj [eacl.baseline.capture :as capture])
            [eacl.datascript.backend :as datascript-backend]
            [eacl.engine.checkpoint-fixtures :as portable]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-page :as page]
            [eacl.engine.stable-reducer :as reducer]))

(def ^:private security-key "stable-page-test-key-0123456789abcdef")

(defn- fixture-for
  [fixture-key]
  #?(:clj ((get capture/fixtures fixture-key))
     :cljs (portable/fixture fixture-key)))

(defn- seed-fixture-client!
  [fixture]
  #?(:clj (capture/seed-client! fixture)
     :cljs (portable/seed-client! fixture)))

(defn- basis-identity
  [adapter source-id source-lifecycle]
  (merge
   {:backend :datascript
    :source-id source-id
    :branch nil
    :source-lifecycle source-lifecycle
    :basis-kind (backend/invoke adapter :basis-kind)
    :backend-snapshot-id (backend/invoke adapter :snapshot-id)}
   (backend/invoke adapter :native-revision)))

(defn- seeded
  [fixture-key]
  (let [fixture (fixture-for fixture-key)
        {:keys [conn]} (seed-fixture-client! fixture)
        db (ds/db conn)
        source-id (str (random-uuid))
        source-lifecycle (str (random-uuid))
        adapter (datascript-backend/basis-adapter
                 db
                 {:object-id->entid
                  (fn [snapshot object-id]
                    (ds/entid snapshot [:eacl/id object-id]))
                  :entid->object-id
                  (fn [snapshot internal-id]
                    (:eacl/id (ds/entity snapshot internal-id)))})]
    {:fixture fixture
     :conn conn
     :db db
     :adapter adapter
     :source-id source-id
     :source-lifecycle source-lifecycle
     :basis-identity (basis-identity adapter source-id source-lifecycle)
     :plan (sealed-plan/seal-plan adapter [(:resource-type fixture)
                                           (:permission fixture)])}))

(defn- base-options
  [{:keys [adapter basis-identity plan fixture]} & [overrides]]
  (merge {:adapter adapter :basis-identity basis-identity
          :plan plan :direction :forward
          :anchor [:user (:id (val (first (:principals fixture))))]
          :subject-type :user :page-size 4
          :security-key security-key}
         overrides))

(defn- drain
  "Follows :after cursors to exhaustion; returns the concatenated data."
  [options]
  (loop [options options acc []]
    (let [{:keys [data page-info]} (page/page options)
          acc (into acc data)]
      (if (and (:has-next-page? page-info) (:end-cursor page-info))
        (recur (assoc options :after (:end-cursor page-info)) acc)
        acc))))

(defn- one-shot
  [{:keys [adapter plan db]} subject-id]
  (mapv #(:eacl/id (ds/entity db %))
        (:results (reducer/run-forward
                   {:adapter adapter :plan plan :subject-type :user
                    :subject-eid (ds/entid db [:eacl/id subject-id])
                    :target 100000}))))

(deftest page-composition-test
  (doseq [fixture-key [:explorer-acyclic :group-star :broad-union
                       :explorer-recursive]]
    (testing (str fixture-key)
      (let [env (seeded fixture-key)
            subject-id (:id (val (first (:principals (:fixture env)))))
            complete (one-shot env subject-id)]
        (doseq [page-size [1 3 4 7]]
          (testing (str "page size " page-size " with checkpoints")
            (is (= complete
                   (drain (base-options
                           env {:page-size page-size
                                :checkpoints (page/make-checkpoint-store)})))))
          (testing (str "page size " page-size " replay only")
            (is (= complete
                   (drain (base-options env {:page-size page-size}))))))))))

(deftest cursor-fork-and-idempotence-test
  (let [env (seeded :explorer-acyclic)
        store (page/make-checkpoint-store)
        options (base-options env {:checkpoints store})
        page-1 (page/page options)
        cursor-1 (get-in page-1 [:page-info :end-cursor])
        page-2 (page/page (assoc options :after cursor-1))
        cursor-2 (get-in page-2 [:page-info :end-cursor])
        page-3 (page/page (assoc options :after cursor-2))]
    (testing "repeated cursors are idempotent and side-effect free"
      (is (= (:data page-2) (:data (page/page (assoc options :after cursor-1)))))
      (is (= (:data page-1) (:data (page/page options)))))
    (testing "the parent cursor stays reusable after deeper navigation"
      (is (= (:data page-2) (:data (page/page (assoc options :after cursor-1)))))
      (is (= (:data page-3) (:data (page/page (assoc options :after cursor-2))))))
    (testing "checkpoint hit and pure replay agree"
      (is (= (:data page-2)
             (:data (page/page (-> options
                                   (dissoc :checkpoints)
                                   (assoc :after cursor-1)))))))))

(deftest lookahead-survives-checkpointing-test
  (let [env (seeded :group-star)
        store (page/make-checkpoint-store)
        options (base-options env {:checkpoints store :page-size 5})
        page-1 (page/page options)
        cursor-1 (get-in page-1 [:page-info :end-cursor])]
    (is (true? (get-in page-1 [:page-info :has-next-page?])))
    (testing "the pending lookahead is delivered, not lost to admission"
      (let [via-checkpoint (:data (page/page (assoc options :after cursor-1)))
            via-replay (:data (page/page (-> options
                                             (dissoc :checkpoints)
                                             (assoc :after cursor-1))))]
        (is (seq via-checkpoint))
        (is (= via-replay via-checkpoint))))
    (testing "mutation control: dropping the pending segment is killed"
      ;; Rebuild page 1 into a fresh store so the retained checkpoint is
      ;; exactly the page-1 boundary (latest-only replacement in the shared
      ;; store has already advanced past it), then corrupt its pending
      ;; segment.
      (let [reference (:data (page/page (assoc options :after cursor-1)))
            fresh-store (page/make-checkpoint-store)
            fresh-options (assoc options :checkpoints fresh-store)
            _ (page/page fresh-options)
            key (page/checkpoint-key
                 (page/execution-binding options))
            _ (swap! fresh-store update-in [:entries key]
                     (fn [entry] (assoc entry :pending [])))
            corrupted-ordinal (:ordinal (get-in @fresh-store [:entries key]))
            mutant (:data (page/page (assoc fresh-options :after cursor-1)))]
        (is (= 5 corrupted-ordinal)
            "the corrupted checkpoint is the page-1 boundary")
        (is (not= reference mutant)
            "a checkpoint without its lookahead segment corrupts the page")))))

(deftest checkpoint-nonregression-test
  (let [store (page/make-checkpoint-store)
        newer {:ordinal 8 :boundary "b8" :pending []
               :state {:transitions 100 :admitted #{}}}
        older {:ordinal 4 :boundary "b4" :pending []
               :state {:transitions 50 :admitted #{}}}]
    (page/checkpoint-put! store "k" newer)
    (page/checkpoint-put! store "k" older)
    (testing "mutation control: older progress never replaces newer"
      (is (= newer (get-in @store [:entries "k"]))))
    (testing "overweight checkpoints are dropped without failing"
      (let [bounded (page/make-checkpoint-store {:max-entry-admissions 1})
            heavy {:ordinal 1 :boundary "b" :pending []
                   :state {:transitions 1 :admitted #{1 2 3}}}]
        (page/checkpoint-put! bounded "k" heavy)
        (is (nil? (get-in @bounded [:entries "k"])))))))

(deftest backward-navigation-test
  (let [env (seeded :explorer-acyclic)
        options (base-options env {:page-size 4})
        page-1 (page/page options)
        page-2 (page/page (assoc options
                                 :after (get-in page-1 [:page-info :end-cursor])))
        page-3 (page/page (assoc options
                                 :after (get-in page-2 [:page-info :end-cursor])))
        edge-3 (get-in page-3 [:page-info :start-cursor])]
    (testing "before returns the preceding window in forward order"
      (is (= (:data page-2)
             (:data (page/page (assoc options :before edge-3))))))
    (testing "a short first window clamps to the sequence start"
      (let [edge-2 (get-in page-2 [:page-info :start-cursor])
            previous (page/page (assoc options :before edge-2))]
        (is (= (:data page-1) (:data previous)))
        (is (false? (get-in previous [:page-info :has-previous-page?])))))))

(deftest token-rejection-test
  (let [env (seeded :folder-chain)
        options (base-options env {:page-size 3})
        page-1 (page/page options)
        cursor (get-in page-1 [:page-info :end-cursor])
        error-of (fn [options]
                   (try (page/page options) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core.ExceptionInfo) e
                          (:eacl/error (ex-data e)))))]
    (testing "tampered payloads are rejected"
      (is (= :eacl.page/invalid-cursor
             (error-of (assoc options :after (str cursor "x"))))))
    (testing "a different page size is incompatible"
      (is (= :eacl.page/invalid-cursor
             (error-of (assoc options :after cursor :page-size 5)))))
    (testing "a different signing key fails authentication"
      (is (= :eacl.page/invalid-cursor
             (error-of (assoc options :after cursor
                              :security-key
                              "another-key-entirely-0123456789abcdef")))))
    (testing "mutation control: a basis change rejects continuation typed"
      (ds/transact! (:conn env) [{:db/id -1 :eacl/id "basis-mover"}])
      (let [moved (datascript-backend/basis-adapter
                   (ds/db (:conn env))
                   {:object-id->entid
                    (fn [snapshot object-id]
                      (ds/entid snapshot [:eacl/id object-id]))
                    :entid->object-id
                    (fn [snapshot internal-id]
                      (:eacl/id (ds/entity snapshot internal-id)))})
            moved-identity
            (basis-identity moved
                            (:source-id env)
                            (:source-lifecycle env))]
        (is (= :eacl.page/stale-cursor
               (error-of (assoc options
                                :adapter moved
                                :basis-identity moved-identity
                                :after cursor))))
        (is (= :eacl.page/cursor-consistency-conflict
               (error-of (assoc options
                                :adapter moved
                                :basis-identity moved-identity
                                :after cursor
                                :consistency :fully-consistent))))))))

(deftest resource-exhaustion-is-distinct-test
  (let [env (seeded :explorer-acyclic)
        options (base-options env {:page-size 4})
        page-1 (page/page options)
        cursor (get-in page-1 [:page-info :end-cursor])]
    (testing "a starved replay budget is typed resource exhaustion, not stale"
      (is (= :eacl.page/resource-exhausted
             (try (page/page (assoc options :after cursor :max-commands 1))
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo) e
                    (:eacl/error (ex-data e)))))))))

(deftest resumed-checkpoint-enforces-cumulative-limits-like-replay-test
  (let [env (seeded :folder-chain)
        store (page/make-checkpoint-store)
        options (base-options env {:page-size 3 :checkpoints store})
        page-1 (page/page options)
        cursor (get-in page-1 [:page-info :end-cursor])
        key (page/checkpoint-key (page/execution-binding options))
        admissions (get-in @store [:entries key :state :admissions])
        error-data
        (fn [candidate]
          (try (page/page candidate) nil
               (catch #?(:clj clojure.lang.ExceptionInfo
                         :cljs cljs.core.ExceptionInfo) error
                 (ex-data error))))
        via-checkpoint
        (error-data (assoc options :after cursor
                           :max-admissions admissions))
        via-replay
        (error-data (-> options
                        (dissoc :checkpoints)
                        (assoc :after cursor
                               :max-admissions admissions)))]
    (is (pos-int? admissions))
    (is (= :eacl.page/resource-exhausted
           (:eacl/error via-checkpoint)
           (:eacl/error via-replay)))
    (is (= :max-admissions (:limit via-checkpoint) (:limit via-replay)))
    (is (= (select-keys via-replay
                        [:limit :maximum :admissions :transitions
                         :commands :discovered])
           (select-keys via-checkpoint
                        [:limit :maximum :admissions :transitions
                         :commands :discovered]))
        "checkpoint resume carries cumulative counters exactly as replay")))

(deftest reverse-direction-pagination-test
  (let [env (seeded :explorer-acyclic)
        resource (val (first (:reverse-resources (:fixture env))))
        options (base-options
                 env {:direction :reverse
                      :anchor [(:type resource) (:id resource)]
                      :page-size 2
                      :checkpoints (page/make-checkpoint-store)})
        complete (drain options)]
    (is (seq complete))
    (is (= complete (distinct complete)))
    (testing "reverse pagination composes like forward pagination"
      (is (= complete (drain (dissoc options :checkpoints)))))))

(deftest checkpoint-identity-includes-the-basis-test
  ;; Mutation control: a checkpoint recorded at one basis must never serve a
  ;; continuation whose token was minted at another basis, even when both
  ;; bases produce the same page-1 boundary. Before the fix the checkpoint
  ;; key omitted the basis, so page 1 at the new basis (whose transition
  ;; count is not strictly greater) failed nonregression and left the old
  ;; basis's reducer state under the shared key for the new token to resume.
  (let [env (seeded :folder-chain)
        store (page/make-checkpoint-store)
        options (base-options env {:page-size 3 :checkpoints store})
        page-1 (page/page options)
        key-at (fn [adapter]
                 (page/checkpoint-key
                  (page/execution-binding (assoc options :adapter adapter))))
        _ (ds/transact! (:conn env) [{:db/id -1 :eacl/id "basis-mover"}])
        moved (datascript-backend/basis-adapter
               (ds/db (:conn env))
               {:object-id->entid
                (fn [snapshot object-id]
                  (ds/entid snapshot [:eacl/id object-id]))
                :entid->object-id
                (fn [snapshot internal-id]
                  (:eacl/id (ds/entity snapshot internal-id)))})
        moved-options
        (assoc options
               :adapter moved
               :basis-identity
               (basis-identity moved
                               (:source-id env)
                               (:source-lifecycle env)))
        page-1-moved (page/page moved-options)]
    (testing "the same page-1 boundary at two bases has two checkpoint identities"
      (is (= (:data page-1) (:data page-1-moved)))
      (is (not= (key-at (:adapter env)) (key-at moved)))
      (is (= 2 (count (:entries @store))))
      (is (contains? (:entries @store) (key-at (:adapter env))))
      (is (contains? (:entries @store) (key-at moved))))
    (testing "the new basis's continuation equals its own pure replay"
      (let [cursor (get-in page-1-moved [:page-info :end-cursor])
            via-store (:data (page/page (assoc moved-options :after cursor)))
            via-replay (:data (page/page (-> moved-options
                                             (dissoc :checkpoints)
                                             (assoc :after cursor))))]
        (is (seq via-store))
        (is (= via-replay via-store))))))
