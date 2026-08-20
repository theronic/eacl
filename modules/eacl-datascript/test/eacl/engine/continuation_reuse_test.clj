(ns eacl.engine.continuation-reuse-test
  "Cross-request cache reuse gates (operator-reported regression).

  Repeated pagination through the public client must reuse latest-only
  checkpoints from the client's continuation store instead of replaying the
  canonical prefix on every page, and sealed plans must be reused across
  snapshot re-wraps of the same source at the same basis. The original
  defect: the stable engine rejected the client's continuation context
  (fn-map) outright, and keyed plans by JVM object identity, so both caches
  missed on every request in a real service."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.baseline.capture :as capture]
            [eacl.continuation :as continuation]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.stable-page :as page]
            [eacl.engine.v8 :as v8]
            [datascript.core :as ds]))

(defn- seeded-caching-client
  "Public DataScript client with default caching (the production shape:
  answer cache on, continuation store present)."
  [fixture-key]
  (let [{:keys [schema objects relationships] :as fixture}
        ((get capture/fixtures fixture-key))
        conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client schema)
    (ds/transact! conn
                  (vec (map-indexed
                        (fn [index {:keys [id]}]
                          {:db/id (- (inc index)) :eacl/id id})
                        objects)))
    (doseq [batch (partition-all 500 relationships)]
      (eacl/create-relationships! client (vec batch)))
    {:fixture fixture :conn conn :client client}))

(deftest repeated-pagination-reuses-checkpoints-test
  ;; Checkpoints belong to RECURSIVE plans (order ABI v2:
  ;; acyclic-keyset-pagination gives acyclic roots self-contained keyset
  ;; cursors that never touch the continuation store — see
  ;; acyclic-pagination-needs-no-checkpoints-test below).
  (let [{:keys [fixture client]} (seeded-caching-client :folder-chain)
        store (get-in client [:opts :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 5}
        one-shot (mapv :id (:data (eacl/lookup-resources
                                   client (assoc query :first 1000))))]
    (is (continuation/store? store)
        "the default client construction must provide a continuation store")
    (let [page-1 (eacl/lookup-resources client query)
          puts-after-1 (:puts (continuation/stats store))
          hits-after-1 (:hits (continuation/stats store))
          cursor-1 (get-in page-1 [:page-info :end-cursor])
          page-2 (eacl/lookup-resources client (assoc query :after cursor-1))
          hits-after-2 (:hits (continuation/stats store))]
      (is (pos? puts-after-1)
          "page 1 must publish its checkpoint to the client store")
      (is (> hits-after-2 hits-after-1)
          "page 2 must continue from the stored checkpoint, not replay")
      (is (= (take 5 one-shot) (mapv :id (:data page-1))))
      (is (= (take 5 (drop 5 one-shot)) (mapv :id (:data page-2))))
      (testing "full pagination composes exactly under checkpoint reuse"
        (loop [q query acc []]
          (let [{:keys [data page-info]} (eacl/lookup-resources client q)
                acc (into acc (map :id) data)]
            (if (and (:has-next-page? page-info) (:end-cursor page-info)
                     (seq data))
              (recur (assoc q :after (:end-cursor page-info)) acc)
              (is (= one-shot acc)))))))))

(deftest repeated-page-request-is-served-from-cache-test
  ;; Operator-reported: repeating the identical page request must not
  ;; recompute. The relay page-navigation cache serves the exact page back
  ;; and marks it :cached? under the same immutable snapshot.
  (let [{:keys [fixture client]} (seeded-caching-client :explorer-acyclic)
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 5}
        page-1 (eacl/lookup-resources client query)
        page-1-again (eacl/lookup-resources client query)
        cursor-1 (get-in page-1 [:page-info :end-cursor])
        page-2 (eacl/lookup-resources client (assoc query :after cursor-1))
        page-2-again (eacl/lookup-resources client (assoc query :after cursor-1))]
    (is (true? (:cached? page-1-again))
        "the identical first-page request must be served from cache")
    (is (= (:data page-1) (:data page-1-again)))
    (is (true? (:cached? page-2-again))
        "the identical cursor-page request must be served from cache")
    (is (= (:data page-2) (:data page-2-again)))))

(deftest repeated-count-is-served-from-cache-test
  (let [{:keys [fixture client]} (seeded-caching-client :explorer-acyclic)
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)}
        count-1 (eacl/count-resources client query)
        count-2 (eacl/count-resources client query)]
    (is (pos? (:count count-1)))
    (is (true? (:cached? count-2))
        "the identical count request must be served from cache")
    (is (= (:count count-1) (:count count-2)))))

(defn- limited-client
  [fixture-key limits]
  (let [{:keys [schema objects relationships] :as fixture}
        ((get capture/fixtures fixture-key))
        conn (datascript/create-conn)
        client (datascript/make-client
                conn {:recursive-traversal-limits limits})]
    (eacl/write-schema! client schema)
    (ds/transact! conn
                  (vec (map-indexed
                        (fn [index {:keys [id]}]
                          {:db/id (- (inc index)) :eacl/id id})
                        objects)))
    (doseq [batch (partition-all 500 relationships)]
      (eacl/create-relationships! client (vec batch)))
    {:fixture fixture :client client}))

(deftest queued-work-bounds-depth-not-cumulative-transitions-test
  ;; Operator-reported: a ~24k-result exhaustive count failed the public
  ;; :max-queued-work limit because it was mapped onto CUMULATIVE reducer
  ;; transitions. The public contract bounds instantaneous queue depth: a
  ;; deep chain takes many transitions while its stack stays shallow, so
  ;; a tight :max-queued-work must not fail it.
  (let [{:keys [fixture client]}
        (limited-client :folder-chain {:max-queued-work 25
                                       :max-derived-grants 100000
                                       :max-advanced-datoms 100000})
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)}
        looked (count (:data (eacl/lookup-resources
                              client (assoc query :first 1000))))
        counted (:count (eacl/count-resources client query))]
    (is (pos? counted))
    (is (= looked counted)
        "the exhaustive count agrees with the lookup denotation")))

(deftest advanced-datoms-limit-binds-consumed-values-test
  (let [{:keys [fixture client]}
        (limited-client :explorer-acyclic {:max-advanced-datoms 3
                                           :max-derived-grants 100000
                                           :max-queued-work 100000})
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 20}
        data (try (eacl/lookup-resources client query)
                  nil
                  (catch clojure.lang.ExceptionInfo error
                    (ex-data error)))]
    (is (= :eacl.recursive-traversal/limit-exceeded (:eacl/error data)))
    (is (= :advanced-datoms (:limit-kind data))
        "consumed projection values surface under the public limit kind")))

(defn- adapter-opts
  [conn extra]
  (merge {:object-id->entid
          (fn [snapshot object-id]
            (ds/entid snapshot [:eacl/id object-id]))
          :entid->object-id
          (fn [snapshot internal-id]
            (:eacl/id (ds/entity snapshot internal-id)))
          :conn conn}
         extra))

(deftest plan-cache-survives-snapshot-rewraps-test
  ;; Two adapter wraps of the same source at the same basis are distinct JVM
  ;; objects; the sealed plan must be shared between them.
  (let [{:keys [conn]} (capture/seed-client!
                        ((get capture/fixtures :explorer-acyclic)))
        opts (adapter-opts conn {:source-lifecycle "plan-rewrap-test"})
        stable-plan @#'v8/stable-plan
        plan-1 (stable-plan (datascript-backend/snapshot-adapter
                             (ds/db conn) opts)
                            [:server :view])
        plan-2 (stable-plan (datascript-backend/snapshot-adapter
                             (ds/db conn) opts)
                            [:server :view])]
    (is (identical? plan-1 plan-2)
        "re-wrapping the same source at the same basis must hit the plan cache")))

(deftest fixture-stores-mint-distinct-lifecycles-test
  ;; Distinct stores sharing one caller-fixed lifecycle violated the adapter
  ;; source-identity contract and poisoned identity-keyed caches; the fixture
  ;; harness must never reintroduce it.
  (let [{client-a :client} (capture/seed-client!
                            ((get capture/fixtures :explorer-acyclic)))
        {client-r :client} (capture/seed-client!
                            ((get capture/fixtures :explorer-recursive)))]
    (is (not= (get-in client-a [:opts :source-lifecycle])
              (get-in client-r [:opts :source-lifecycle])))))

(deftest checkpoint-store-adopts-client-context-test
  (let [{:keys [conn]} (capture/seed-client!
                        ((get capture/fixtures :explorer-acyclic)))
        adapter (datascript-backend/snapshot-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        context (continuation/private-context
                 store adapter :lookup-resources {:query {:q 1}})
        checkpoint-hit #'page/checkpoint-hit
        checkpoint {:ordinal 2 :boundary 42 :pending [7]
                    :state {:transitions 10 :admitted #{1 2} :stack []}}]
    (is (identical? context (@#'v8/stable-checkpoints context))
        "the engine must accept the client's continuation context")
    (is (nil? (@#'v8/stable-checkpoints (atom {})))
        "an unrecognized cache shape must degrade to replay")
    (page/checkpoint-put! context [:k] checkpoint)
    (is (= checkpoint (checkpoint-hit context [:k] 2 42)))
    (is (nil? (checkpoint-hit context [:k] 3 42))
        "an ordinal mismatch must miss")
    (is (nil? (checkpoint-hit context [:k] 2 41))
        "a boundary mismatch must miss")
    (testing "an older checkpoint never replaces newer progress"
      (page/checkpoint-put!
       context [:k] (assoc-in checkpoint [:state :transitions] 5))
      (is (= 10 (get-in (checkpoint-hit context [:k] 2 42)
                        [:state :transitions]))))))

(deftest acyclic-pagination-needs-no-checkpoints-test
  ;; The keyset regime: an acyclic root paginates statelessly — exact
  ;; pages, zero continuation-store traffic (acyclic-keyset-pagination).
  (let [{:keys [fixture client]} (seeded-caching-client :explorer-acyclic)
        store (get-in client [:opts :continuation-cache-store])
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 5}
        one-shot (mapv :id (:data (eacl/lookup-resources
                                   client (assoc query :first 1000))))
        puts-before (:puts (continuation/stats store))
        walked
        (loop [q query acc []]
          (let [{:keys [data page-info]} (eacl/lookup-resources client q)
                acc (into acc (map :id) data)]
            (if (and (:has-next-page? page-info) (:end-cursor page-info)
                     (seq data))
              (recur (assoc q :after (:end-cursor page-info)) acc)
              acc)))]
    (is (= one-shot walked)
        "keyset pages compose exactly with no server-side state")
    (is (= puts-before (:puts (continuation/stats store)))
        "acyclic pagination publishes nothing to the continuation store")))
