(ns eacl.relationships.qualifier-sweep-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [eacl.core :as eacl]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.qualifier-integrity :as integrity]
            [eacl.relationships.staged :as staged]
            [eacl.relationships.storage :as storage]))

(def schema-source
  "definition user {}\ndefinition doc {\n relation viewer: user\n permission view = viewer\n}")

(defn seed! [{:keys [client snapshot transact! writer entid rows] :as fixture} q active]
  (eacl/write-schema! client {:schema schema-source})
  (transact! [{:db/id -1 :eacl/id "u"} {:db/id -2 :eacl/id "d"}])
  (let [db (snapshot)
        writer (writer)
        rid (:e (first (rows db :eacl.relation/relation-name)))
        identity [:user (entid db [:eacl/id "u"]) rid :doc (entid db [:eacl/id "d"])]]
    (dotimes [i active]
      (let [id (str "active-" i)]
        (transact! [{:db/id -1 :eacl/id id}])
        (eacl/create-relationship! client
                                   (assoc (eacl/->Relationship (eacl/spice-object :user "u") :viewer (eacl/spice-object :doc id))
                                          :valid-until-ms 100))))
    (assoc fixture :writer writer :identity identity
           :handles (mapv (fn [_] (staged/prepare! writer identity {:valid-until-ms 100})) (range q)))))

(defn orphan-oracle [{:keys [snapshot rows]}]
  (let [db (snapshot)
        qualifiers (set (map :e (rows db qualifier/marker-attribute)))
        attached (into #{} (keep #(nth (:v %) 4 nil))
                       (mapcat #(rows db %) storage/attributes))]
    (into #{} (remove attached) qualifiers)))

(defn traced-writer [writer counts]
  (let [native (:native writer)]
    (assoc writer :native
           (assoc native
                  :all-rows (fn [& args]
                              (swap! counts update :streams (fnil inc 0))
                              (map (fn [row] (swap! counts update :rows (fnil inc 0)) row)
                                   (apply (:all-rows native) args)))
                  :fact-rows (fn [& args]
                               (swap! counts update :fact-reads (fnil inc 0))
                               (map (fn [row] (swap! counts update :facts (fnil inc 0)) row)
                                    (apply (:fact-rows native) args)))
                  :transact! (fn [tx]
                               (swap! counts update :tx-sizes (fnil conj []) (count tx))
                               ((:transact! native) tx))))))

(defn work-outcome [fixture]
  (let [fixture (seed! fixture 8 5)
        expected (orphan-oracle fixture)
        counts (atom {})
        result (integrity/cleanup-sweep! (traced-writer (:writer fixture) counts) {:batch-size 2})]
    {:result result :counts @counts :expected expected :remaining (orphan-oracle fixture)}))

(defn exercise-work! [with-fixture]
  (doseq [q [4 8 16] active [0 5]]
    (with-fixture
      (fn [fixture]
        (let [fixture (seed! fixture q active)
              before (orphan-oracle fixture)
              counts (atom {})
              result (integrity/cleanup-sweep! (traced-writer (:writer fixture) counts) {:batch-size 2})]
          (is (= :complete (:status result)))
          (is (= before (set (:qualifiers result))))
          (is (empty? (orphan-oracle fixture)))
          (is (= 6 (:streams @counts)) "one pass through each of six global index streams")
          (is (= (+ (* 2 q) (* 4 active)) (:rows @counts)))
          (is (= (/ q 2) (:committed-batches result)))
          (is (= (repeat (/ q 2) 5) (:tx-sizes @counts)))
          (is (pos? (:fact-reads @counts)))
          (is (= active (count (seq ((:rows fixture) ((:snapshot fixture)) storage/forward-attribute))))))))))

(defn hostile-outcome [fixture transition]
  (let [{:keys [writer identity handles transact!] :as fixture} (seed! fixture 6 0)
        native (:native writer)
        attempts (atom 0)
        changed-source (atom false)
        source (:source native)
        attach! #(staged/write! writer :create identity (last handles))
        writer (assoc writer :native
                      (assoc native
                             :source (fn [db] (if @changed-source :reopened (source db)))
                             :transact!
                             (fn [tx]
                               (let [attempt (swap! attempts inc)]
                                 (when (and (= transition :failed-chunk) (= attempt 2))
                                   (throw (ex-info "injected native failure" {})))
                                 (when (and (= transition :attachment-at-commit) (= attempt 1)) (attach!))
                                 (let [report ((:transact! native) tx)]
                                   (when (= attempt 1)
                                     (case transition
                                       :attachment (attach!)
                                       :unrelated-write (transact! [{:db/id -1 :eacl/id "foreign"}])
                                       nil))
                                   (when-not (= transition :lost-result) report))))))
        polls (atom 0)
        cancelled? (fn []
                     (let [poll (swap! polls inc)]
                       (when (and (= transition :source-change) (= poll 2)) (reset! changed-source true))
                       (and (= transition :cancel) (= poll 2))))
        result (integrity/cleanup-sweep! writer {:batch-size 2 :cancelled? cancelled?})
        db ((:snapshot fixture))
        attached (keep #(nth (:v %) 4 nil) ((:rows fixture) db storage/forward-attribute))
        attached-survives? (every? #(seq ((:facts native) db %)) attached)]
    {:result result :attached-survives? attached-survives? :attached (vec attached)
     :fixture fixture}))

(defn exercise-hostile! [with-fixture]
  (doseq [transition (list :attachment :attachment-at-commit :unrelated-write :lost-result
                           :source-change :cancel :failed-chunk)]
    (testing (str transition)
      (with-fixture
        (fn [fixture]
          (let [{:keys [result attached-survives? attached fixture]} (hostile-outcome fixture transition)
                confirmed (if (contains? #{:lost-result :attachment-at-commit} transition) 0 2)]
            (is (= (if (= :cancel transition) :cancelled :restart-required) (:status result)))
            (is (= confirmed (count (:qualifiers result))))
            (is (= (- 6 confirmed) (:remaining-count result)))
            (is attached-survives?)
            (when (contains? #{:attachment :attachment-at-commit} transition) (is (= 1 (count attached))))
            (let [expected (orphan-oracle fixture)
                  restarted (integrity/cleanup-sweep! (:writer fixture) {:batch-size 2})]
              (is (= :complete (:status restarted)))
              (is (= expected (set (:qualifiers restarted)))))))))))

(defn exercise-budgets! [with-fixture]
  (doseq [options [{:max-qualifiers 2} {:max-capture-units 1}]]
    (with-fixture
      (fn [fixture]
        (let [fixture (seed! fixture 6 2)
              before (orphan-oracle fixture)
              counts (atom {})
              error (try (integrity/cleanup-sweep! (traced-writer (:writer fixture) counts) options) nil
                         (catch #?(:clj Exception :cljs :default) e e))]
          (is (= :eacl.integrity/capture-budget-exceeded (:type (ex-data error))))
          (is (empty? (:tx-sizes @counts)))
          (is (= before (orphan-oracle fixture)))
          (is (nil? (:fact-reads @counts)) "budget fails before materializing entity/fact maps"))))))
