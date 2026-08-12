(ns eacl.datomic.api-contract-test
  "Regressions for API-surface bugs found in the 2026-07-29 full-source hunt:
  nil-valued anchor filters, nil page cursors, empty-page flags, the broken
  impl/can? map arity, untyped write exceptions, and recursive traversal
  limits/counting."
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.verified-kernel :as verified]))

(def ^:private acyclic-schema
  "definition user {}
   definition account { relation owner: user
                        permission admin = owner
   }")

(def ^:private recursive-schema
  "definition user {}
   definition folder { relation parent: folder
                       relation reader: user
                       permission read = reader + parent->read
   }")

(defn- ents [ids] (mapv (fn [id] {:eacl/id id}) ids))

(defn- ex-data-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest generated-authority-is-the-only-production-engine-test
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (with-mem-conn [conn schema/v7-schema]
    (let [default-selection
          (get-in (core/make-client conn {}) [:opts :decision-kernel])
          error
          (try
            (core/make-client conn {:engine-selection :anything})
            nil
            (catch clojure.lang.ExceptionInfo exception
              (ex-data exception)))]
      (is (satisfies? verified/DecisionKernel (:kernel default-selection)))
      (is (= :eacl/invalid-config (:type error)))
      (is (= [:engine-selection] (:unknown-keys error))))))

(defn- seed-acyclic!
  "n accounts, all owned by user u."
  [conn n]
  (schema/write-schema! conn acyclic-schema)
  @(d/transact conn (ents (cons "u" (map #(str "a-" %) (range n)))))
  (let [db (d/db conn)]
    @(d/transact conn (into [] (mapcat #(impl/tx-relationship db %))
                            (for [i (range n)]
                              (Relationship (spice-object :user "u")
                                            :owner
                                            (spice-object :account (str "a-" i)))))))
  (let [db (d/db conn)]
    {:db db :u (d/entid db [:eacl/id "u"])}))

(defn- seed-recursive!
  "A flat tree: `n` folders under one root the user reads."
  [conn n]
  (schema/write-schema! conn recursive-schema)
  @(d/transact conn (ents (concat ["u" "root"] (map #(str "f-" %) (range n)))))
  (let [db (d/db conn)]
    @(d/transact conn (into [] (mapcat #(impl/tx-relationship db %))
                            (cons (Relationship (spice-object :user "u") :reader (spice-object :folder "root"))
                                  (for [i (range n)]
                                    (Relationship (spice-object :folder "root")
                                                  :parent
                                                  (spice-object :folder (str "f-" i))))))))
  (let [db (d/db conn)]
    {:db db :u (d/entid db [:eacl/id "u"])}))

;; --- read-relationships anchors ---------------------------------------------

(deftest nil-anchor-filters-are-rejected-test
  ;; The guard used contains?, which is key PRESENCE. {:subject/id nil} — the
  ;; shape you get from {:subject/id (get-in req [:params :user-id])} when the
  ;; param is missing — satisfied it while every consumer treated the value as
  ;; absent, degrading the read to the global index scan the guard exists to
  ;; prevent.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db]} (seed-acyclic! conn 3)]
      (doseq [k [:subject/id :resource/id :subject/type :resource/type :resource/relation]]
        (let [data (ex-data-of #(impl/read-relationships db {k nil :first 5}))]
          (is (= :eacl.filters/missing-anchor (:eacl/error data))
              (str k " => nil must not count as an anchor"))
          (is (= [k] (:nil-anchor-keys data))
              "the error names the key that was passed as nil")))

      (testing "a real anchor still works, and an absent filter map still throws"
        (is (= 3 (count (:data (impl/read-relationships db {:resource/type :account :first 10})))))
        (is (= :eacl.filters/missing-anchor
               (:eacl/error (ex-data-of #(impl/read-relationships db {:first 5})))))))))

(deftest missing-relation-filter-does-not-scan-relationship-tuples-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db]} (seed-acyclic! conn 3)
          seeks (atom 0)
          seek-datoms d/seek-datoms]
      (with-redefs [d/seek-datoms
                    (fn [& args]
                      (swap! seeks inc)
                      (apply seek-datoms args))]
        (is (= []
               (:data
                (impl/read-relationships
                 db
                 {:resource/relation :does-not-exist
                  :first 10}))))
        (is (zero? @seeks)
            "an empty relation-definition set proves the result is empty")))))

(deftest relationship-pages-use-the-shared-index-edge-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db]} (seed-acyclic! conn 3)
          query {:resource/type :account :first 1}
          page-1 (impl/read-relationships db query)
          edge (get-in page-1 [:page-info :end-cursor])
          page-2 (impl/read-relationships db (assoc query :after edge))]
      (is (= {:kind :relationship-index
              :v 1
              :scan-index 0}
             (select-keys edge [:kind :v :scan-index])))
      (is (= #{:kind :v :scan-index :subject-id :resource-id}
             (set (keys edge))))
      (is (= 1 (count (:data page-2))))
      (testing "the superseded Datomic-private edge is rejected"
        (is (= :eacl.pagination/invalid-cursor
               (:eacl/error
                (ex-data-of
                 #(impl/read-relationships
                   db
                   (assoc query
                          :after {:kind :relationship
                                  :scan :global-forward
                                  :e 1
                                  :v [:user 1 :account 1]}))))))))))

;; --- page cursors ------------------------------------------------------------

(deftest nil-page-cursors-are-rejected-test
  ;; :after nil used to mean "start over", so a client looping on a page-info
  ;; that carried a nil cursor silently restarted at page 1 forever.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-acyclic! conn 5)
          query {:subject (spice-object :user u) :permission :admin :resource/type :account}]
      (is (= :eacl.pagination/invalid-cursor
             (:eacl/error (ex-data-of #(idx/lookup-resources db (assoc query :first 2 :after nil))))))
      (is (= :eacl.pagination/invalid-cursor
             (:eacl/error (ex-data-of #(idx/lookup-resources db (assoc query :last 2 :before nil))))))

      (testing "an omitted cursor is still the first/last page"
        (is (= 2 (count (:data (idx/lookup-resources db (assoc query :first 2))))))
        (is (= 2 (count (:data (idx/lookup-resources db (assoc query :last 2))))))))))

(deftest empty-pages-advertise-no-further-pages-test
  ;; has-next-page? true alongside a nil end-cursor is a loop with no exit.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-acyclic! conn 4)
          query   {:subject (spice-object :user u) :permission :admin :resource/type :account}
          all     (idx/lookup-resources db (assoc query :first 10))
          past-end (idx/lookup-resources db (assoc query :first 10
                                                  :after (get-in all [:page-info :end-cursor])))
          before-start (idx/lookup-resources db (assoc query :last 10
                                                       :before (get-in all [:page-info :start-cursor])))]
      (doseq [[label page] [["past the end" past-end] ["before the start" before-start]]]
        (is (empty? (:data page)) label)
        (is (nil? (get-in page [:page-info :start-cursor])) label)
        (is (nil? (get-in page [:page-info :end-cursor])) label)
        (is (false? (get-in page [:page-info :has-next-page?])) label)
        (is (false? (get-in page [:page-info :has-previous-page?])) label))

      (testing "and the same for relationship reads"
        (let [rels  (impl/read-relationships db {:resource/type :account :first 10})
              past  (impl/read-relationships db {:resource/type :account :first 10
                                                 :after (get-in rels [:page-info :end-cursor])})]
          (is (empty? (:data past)))
          (is (false? (get-in past [:page-info :has-next-page?])))
          (is (false? (get-in past [:page-info :has-previous-page?]))))))))

(deftest page-info-cursor-invariant-holds-across-a-full-walk-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-acyclic! conn 7)
          query {:subject (spice-object :user u) :permission :admin :resource/type :account}]
      (doseq [page-size [1 2 3]]
        (loop [cursor nil, seen 0, guard 0]
          (is (< guard 50) "a page walk must terminate")
          (let [page (idx/lookup-resources db (cond-> (assoc query :first page-size)
                                                cursor (assoc :after cursor)))
                {:keys [has-next-page? has-previous-page? start-cursor end-cursor]} (:page-info page)]
            (when has-next-page?
              (is (some? end-cursor) "has-next-page? must imply a usable end-cursor"))
            (when has-previous-page?
              (is (some? start-cursor) "has-previous-page? must imply a usable start-cursor"))
            (if has-next-page?
              (recur end-cursor (+ seen (count (:data page))) (inc guard))
              (is (= 7 (+ seen (count (:data page))))))))))))

;; --- impl/can? arities & typed errors ---------------------------------------

(deftest impl-can-map-arity-test
  ;; The map arity forwarded to a 2-arity impl.indexed/can? that does not
  ;; exist, so it threw ArityException on every call.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-acyclic! conn 1)
          a (d/entid db [:eacl/id "a-0"])]
      (is (true? (impl/can? db {:subject    (spice-object :user u)
                                :permission :admin
                                :resource   (spice-object :account a)})))
      (is (false? (impl/can? db {:subject    (spice-object :user u)
                                 :permission :nonexistent
                                 :resource   (spice-object :account a)})))
      (is (= (impl/can? db (spice-object :user u) :admin (spice-object :account a))
             (impl/can? db {:subject (spice-object :user u)
                            :permission :admin
                            :resource (spice-object :account a)}))))))

(deftest concurrent-create-preserves-conflict-semantics-test
  ;; Both writers used to check absence against the same immutable db and then
  ;; submit idempotent cardinality-many adds. Both calls returned success even
  ;; though :create promises exactly one winner and one conflict.
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn acyclic-schema)
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    (let [client-a (core/make-client conn {})
          client-b (core/make-client conn {})
          relationship
          (Relationship (spice-object :user "u")
                        :owner
                        (spice-object :account "a"))
          ready (java.util.concurrent.CountDownLatch. 2)
          transact d/transact
          relationship-transaction?
          (fn [tx-data]
            (some #(and (vector? %)
                        (= :db.fn/cas (first %))
                        (= :eacl/relation-version (nth % 2 nil)))
                  tx-data))]
      (with-redefs [d/transact
                    (fn [connection tx-data]
                      (when (relationship-transaction? tx-data)
                        (.countDown ready)
                        (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
                      (transact connection tx-data))]
        (let [write
              (fn [client]
                (future
                  (try
                    (eacl/create-relationship! client relationship)
                    :ok
                    (catch clojure.lang.ExceptionInfo e
                      (:type (ex-data e))))))
              results (mapv deref [(write client-a)
                                   (write client-b)])]
          (is (= #{:ok :eacl/relationship-conflict}
                 (set results))))))))

(deftest write-errors-are-typed-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-acyclic! conn 1)
          a   (d/entid db [:eacl/id "a-0"])
          rel (Relationship (spice-object :user "u") :owner (spice-object :account "a-0"))]
      (testing ":create on an existing relationship"
        (is (= :eacl/relationship-conflict
               (:type (ex-data-of #(impl/tx-update-relationship (d/db conn)
                                                                {:operation :create :relationship rel}))))))
      (testing ":unspecified"
        (is (= :eacl/unsupported-operation
               (:type (ex-data-of #(impl/tx-update-relationship (d/db conn)
                                                                {:operation :unspecified :relationship rel}))))))
      (testing "nil and arbitrary operations are rejected before endpoint resolution"
        (doseq [operation [nil :replace "delete"]]
          (let [data (ex-data-of #(impl/tx-update-relationship
                                  (d/db conn)
                                  {:operation operation
                                   :relationship nil}))]
            (is (= :eacl/unsupported-operation (:type data)))
            (is (= operation (:operation data))))))
      (testing "the public batch validates every operation before coercing endpoints"
        (let [client (core/make-client conn {})]
          (doseq [operation [nil :unspecified :replace]]
            (let [data
                  (ex-data-of
                   #(eacl/write-relationships!
                     client
                     [{:operation operation
                       :relationship nil}]))]
              (is (= :eacl/unsupported-operation (:type data)))
              (is (= operation (:operation data)))))))
      (testing "can!"
        (is (true? (impl/can! db (spice-object :user u) :admin (spice-object :account a))))
        (is (= :eacl/unauthorized
               (:type (ex-data-of #(impl/can! db (spice-object :user u) :nope (spice-object :account a))))))))))

;; --- recursive traversal: limits, counting, subjects -------------------------

(deftest recursive-count-does-not-replay-the-traversal-test
  ;; count-resources used to page with :first max-page-size and :after, and
  ;; each page replayed the whole prefix — O(N^2), and it tripped
  ;; :max-derived-grants long before a large grant set was counted.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-recursive! conn 60)
          query {:subject (spice-object :user u) :permission :read :resource/type :folder}]
      (is (true? (idx/traversal-permission? db :folder :read)))
      (is (= 61 (:count (idx/count-resources db query))) "root + 60 children")

      (testing "counting derives each grant once"
        (let [stats (atom {})]
          (binding [idx/*recursive-traversal-stats* stats]
            (idx/count-resources db query))
          (is (<= (:derived-grants @stats) 70)
              (str "one pass over 61 results, got " (:derived-grants @stats)))))

      (testing "a limit far below N^2 but above N no longer breaks counting"
        (binding [idx/*recursive-traversal-limits* {:max-derived-grants 200
                                                    :max-advanced-datoms 100000
                                                    :max-queued-work 100000}]
          (is (= 61 (:count (idx/count-resources db query))))))

      (testing "the limit still fires, and says how to raise it"
        (binding [idx/*recursive-traversal-limits* {:max-derived-grants 5
                                                    :max-advanced-datoms 100000
                                                    :max-queued-work 100000}]
          (let [data (ex-data-of #(idx/count-resources db query))]
            (is (= :eacl.recursive-traversal/limit-exceeded (:eacl/error data)))
            (is (= :derived-grants (:limit-kind data)))))))))

(deftest recursive-traversal-limits-are-configurable-test
  (with-mem-conn [conn schema/v7-schema]
    (seed-recursive! conn 40)
    (testing "a client can raise the ceiling"
      (let [tight (core/make-client conn {:recursive-traversal-limits {:max-derived-grants 3}})
            roomy (core/make-client conn {:recursive-traversal-limits {:max-derived-grants 100000}})
            query {:subject (spice-object :user "u")
                   :permission :read
                   :resource/type :folder
                   :first 10
                   :evaluation :complete-denotation}]
        (is (= :eacl.recursive-traversal/limit-exceeded
               (:eacl/error (ex-data-of #(eacl/lookup-resources tight query)))))
        (is (= 10 (count (:data (eacl/lookup-resources roomy query)))))))

    (testing "a partial override keeps the other defaults instead of disabling them"
      (let [client (core/make-client conn {:recursive-traversal-limits {:max-derived-grants 3}})]
        (is (= :eacl.recursive-traversal/limit-exceeded
               (:eacl/error (ex-data-of #(eacl/lookup-resources
                                          client
                                          {:subject (spice-object :user "u")
                                           :permission :read
                                           :resource/type :folder
                                           :first 10
                                           :evaluation :complete-denotation})))))))

    (testing "a malformed limits map is rejected at construction"
      (doseq [bad [{:no-such-limit 1} {:max-derived-grants 0} {:max-derived-grants "many"} :not-a-map]]
        (is (= :eacl/invalid-config
               (:type (ex-data-of #(core/make-client conn {:recursive-traversal-limits bad}))))
            (pr-str bad))))))

(deftest recursive-last-and-count-subjects-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-recursive! conn 5)
          root  (d/entid db [:eacl/id "root"])
          leaf  (d/entid db [:eacl/id "f-0"])
          query {:subject (spice-object :user u) :permission :read :resource/type :folder}]

      (testing "bare :last serves the tail of the canonical recursive denotation"
        (let [full      (:data (idx/lookup-resources db (assoc query :first 100)))
              last-page (binding [engine/*evaluation-mode* :complete-denotation]
                          (idx/lookup-resources db (assoc query :last 2)))]
          (is (= (take-last 2 full) (:data last-page)))
          (is (true? (get-in last-page [:page-info :has-previous-page?])))
          (is (false? (get-in last-page [:page-info :has-next-page?])))
          (is (= :recursive-logical
                 (get-in last-page [:page-info :start-cursor :kind])))))

      (testing "count-subjects agrees with lookup-subjects on a recursive permission"
        (doseq [[label resource] [["root" root] ["leaf" leaf]]]
          (let [q {:resource (spice-object :folder resource)
                   :permission :read
                   :subject/type :user}]
            (is (= (count (:data (idx/lookup-subjects db (assoc q :first 100))))
                   (:count (idx/count-subjects db q)))
                label)
            (is (= 1 (:count (idx/count-subjects db q))) label))))

      (testing "count-subjects rejects the filters lookup-subjects rejects"
        (is (= :eacl.pagination/unsupported-filter
               (:eacl/error (ex-data-of #(idx/count-subjects db {:resource (spice-object :folder root)
                                                                 :permission :read
                                                                 :subject/type :user
                                                                 :subject/relation :member})))))))))

(deftest bounded-counts-stop-before-a-full-recursive-traversal-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [db u]} (seed-recursive! conn 60)
          root   (d/entid db [:eacl/id "root"])
          client (core/make-client conn {})
          forward-query {:subject (spice-object :user u)
                         :permission :read
                         :resource/type :folder}
          reverse-query {:resource (spice-object :folder root)
                         :permission :read
                         :subject/type :user}]
      (is (= {:count 5 :limit 5 :truncated? true}
             (idx/count-resources db (assoc forward-query :count-limit 5))))
      (is (= {:count 61 :limit 100 :truncated? false}
             (idx/count-resources db (assoc forward-query :count-limit 100))))
      (is (= {:count 0 :limit 0 :truncated? true}
             (idx/count-resources db (assoc forward-query :count-limit 0))))

      (testing "count-subjects is exposed on the public client"
        (is (= {:count 1 :limit 1 :truncated? false}
               (dissoc (eacl/count-subjects
                        client
                        (-> reverse-query
                            (assoc :resource (spice-object :folder "root"))
                            (assoc :count-limit 1)))
                       :cached? :cache-basis))))

      (testing "invalid limits are typed"
        (is (= :eacl.count/invalid-limit
               (:eacl/error
                (ex-data-of #(idx/count-subjects db (assoc reverse-query :count-limit -1))))))))))
