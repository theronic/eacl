(ns eacl.datomic.cache-review-regressions-test
  "One test per finding of the 2026-07-31 v8.0 adversarial review.

  See docs/reports/2026-07-31-eacl-v8.0-cache-adversarial-review.md. The
  critical pagination finding (C1) is covered by
  eacl.datomic.cache-differential-test."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as shared-cache]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.cursor :as cursor]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.spicedb.consistency :as consistency])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def ^:private token-key "0123456789abcdef0123456789abcdef")

(def ^:private direct-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(defn- live-client
  "A client using the default private authorization cache."
  [conn]
  (core/make-client conn {:security-key token-key
                          :cache {}}))

(defn- seed-direct!
  [conn boot n-accounts]
  (eacl/write-schema! boot direct-schema)
  @(d/transact conn (vec (concat [{:eacl/id "alice"}]
                                 (for [k (range n-accounts)]
                                   {:eacl/id (str "acct" k)}))))
  (doseq [k (range n-accounts)]
    (eacl/create-relationship!
     boot (->Relationship (spice-object :user "alice")
                          :owner
                          (spice-object :account (str "acct" k))))))

(defn- ex-data-of
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; --- Follow-up review -------------------------------------------------------

(deftest proofless-cursor-falls-back-to-exact-snapshot-test
  (with-mem-conn [conn schema/v8-schema]
    (let [client
          (core/make-client
           conn
           {:security-key "proofless-exact-fallback00000000"})
          alice (spice-object :user "alice")
          account #(spice-object :account %)
          query {:subject alice
                 :permission :admin
                 :resource/type :account
                 :first 1}]
      (eacl/write-schema! client direct-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "a1"}
                         {:eacl/id "a2"}
                         {:eacl/id "a3"}])
      (eacl/create-relationships!
       client
       [(->Relationship alice :owner (account "a1"))
        (->Relationship alice :owner (account "a3"))])
      (let [page-1 (eacl/lookup-resources client query)]
        (is (= ["a1"] (mapv :id (:data page-1))))
        (eacl/create-relationship!
         client
         (->Relationship alice :owner (account "a2")))
        (let [page-2
              (eacl/lookup-resources
               client
               (assoc query
                      :after
                      (get-in page-1
                              [:page-info :end-cursor])))]
          (is (= ["a3"] (mapv :id (:data page-2))))
          (is (nil? (get-in page-2
                            [:page-info :cursor-recovery]))))))))

(deftest explicit-cache-true-does-not-fragment-answer-keys-test
  ;; :cache? selects how to obtain an answer, not which answer was requested.
  ;; It was removed from cursor identity but accidentally retained in finished
  ;; lookup/count keys, so an explicit true recomputed an answer already cached
  ;; by the equivalent request with the option omitted.
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (core/make-client conn {:security-key token-key
                                      :cache {}})
          _ (seed-direct! conn acl 3)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          lookup-calls (atom 0)
          count-calls (atom 0)
          lookup-resources engine/lookup-resources
          count-resources engine/count-resources]
      (with-redefs [engine/lookup-resources
                    (fn [& args]
                      (swap! lookup-calls inc)
                      (apply lookup-resources args))
                    engine/count-resources
                    (fn [& args]
                      (swap! count-calls inc)
                      (apply count-resources args))]
        (is (= 3 (count (:data (eacl/lookup-resources acl query)))))
        (is (= 3 (count (:data (eacl/lookup-resources
                               acl (assoc query :cache? true))))))
        (is (= 1 @lookup-calls))

        (is (= 3 (:count (eacl/count-resources acl query))))
        (is (= 3 (:count (eacl/count-resources
                         acl (assoc query :cache? true)))))
        (is (= 1 @count-calls))))))

(deftest transient-acl-exact-token-is-authenticated-once-test
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (live-client conn)
          _ (seed-direct! conn acl 1)
          selected (eacl/snapshot acl)
          token (try
                  (eacl/basis-token selected)
                  (finally
                    (eacl/release! selected)))
          request {:subject (spice-object :user "alice")
                   :permission :admin
                   :resource/type :account
                   :consistency (consistency/at-exact-snapshot token)}
          _ (eacl/count-resources acl request)
          calls (atom 0)
          original-token-data causal-token/token-data
          hit
          (with-redefs [causal-token/token-data
                        (fn [& args]
                          (swap! calls inc)
                          (apply original-token-data args))]
            (eacl/count-resources acl request))]
      (is (= 1 (:count hit)))
      (is (true? (:cached? hit)))
      (is (= 1 @calls)
          "exact selection authenticates once; delegated Snapshot reuses it"))))

(deftest warm-public-answer-hits-do-no-datomic-identity-lookups-test
  (with-mem-conn [conn schema/v8-schema]
    (let [boot (live-client conn)
          _ (seed-direct! conn boot 3)
          identity-lookups (atom 0)
          acl
          (core/make-client
           conn
           {:security-key token-key
            :cache {}
            ;; This is the default :eacl/id codec with an observation seam.
            ;; The explicit contract keeps it eligible for public-key lookup.
            :object-id->lookup-ref
            (fn [object-id]
              (swap! identity-lookups inc)
              [:eacl/id object-id])
            :adapter-fingerprint :test/default-observed-id-codec
            :adapter-deterministic? true
            :identity-immutable? true})
          alice (spice-object :user "alice")
          account (spice-object :account "acct0")
          check {:subject alice :permission :admin :resource account}
          resource-count {:subject alice
                          :permission :admin
                          :resource/type :account}
          subject-count {:resource account
                         :permission :admin
                         :subject/type :user}
          expansion {:resource account :permission :admin}
          relationship-query {:subject/type :user
                              :subject/id "alice"
                              :resource/type :account
                              :resource/relation :owner
                              :first 1}]
      (testing "point decisions hit before resolving either public object"
        (is (true? (:allowed? (eacl/check-permission acl check))))
        (reset! identity-lookups 0)
        (is (true? (:cached? (eacl/check-permission acl check))))
        (is (zero? @identity-lookups)))

      (testing "forward counts hit before resolving the subject"
        (is (= 3 (:count (eacl/count-resources acl resource-count))))
        (reset! identity-lookups 0)
        (is (true? (:cached? (eacl/count-resources acl resource-count))))
        (is (zero? @identity-lookups)))

      (testing "reverse counts hit before resolving the resource"
        (is (= 1 (:count (eacl/count-subjects acl subject-count))))
        (reset! identity-lookups 0)
        (is (true? (:cached? (eacl/count-subjects acl subject-count))))
        (is (zero? @identity-lookups)))

      (testing "permission-tree hits do not resolve the root again"
        (is (map? (:tree-root (eacl/expand-permission-tree acl expansion))))
        (reset! identity-lookups 0)
        (is (map? (:tree-root (eacl/expand-permission-tree acl expansion))))
        (is (zero? @identity-lookups)))

      (testing "relationship transport hits neither resolve filters nor render rows"
        (let [first-page (eacl/read-relationships acl relationship-query)
              continued-query
              (assoc relationship-query
                     :after (get-in first-page [:page-info :end-cursor]))
              _ (eacl/read-relationships acl continued-query)
              reverse-query
              (-> relationship-query (dissoc :first) (assoc :last 1))
              _ (eacl/read-relationships acl reverse-query)
              observed (atom [])]
          (reset! identity-lookups 0)
          (binding [backend/*invoke-observer*
                    #(swap! observed conj %)]
            (is (true? (:cached?
                        (eacl/read-relationships acl relationship-query))))
            (is (true? (:cached?
                        (eacl/read-relationships acl continued-query))))
            (is (true? (:cached?
                        (eacl/read-relationships acl reverse-query)))))
          (is (zero? @identity-lookups))
          (is (not-any?
               #{:object-id->internal :internal-id->object}
               (map :operation @observed))))))))

(deftest retained-snapshot-relationship-transport-hits-do-zero-backend-work-test
  (with-mem-conn [conn schema/v8-schema]
    (let [boot (live-client conn)
          _ (seed-direct! conn boot 3)
          identity-lookups (atom 0)
          acl
          (core/make-client
           conn
           {:security-key token-key
            :cache {}
            :object-id->lookup-ref
            (fn [object-id]
              (swap! identity-lookups inc)
              [:eacl/id object-id])
            :adapter-fingerprint :test/snapshot-observed-id-codec
            :adapter-deterministic? true
            :identity-immutable? true})
          snapshot (eacl/snapshot acl)
          first-query {:subject/type :user
                       :subject/id "alice"
                       :resource/type :account
                       :resource/relation :owner
                       :first 1}]
      (try
        (let [first-page (eacl/read-relationships snapshot first-query)
              continued-query
              (assoc first-query
                     :after (get-in first-page [:page-info :end-cursor]))
              _ (eacl/read-relationships snapshot continued-query)
              reverse-query (-> first-query (dissoc :first) (assoc :last 1))
              _ (eacl/read-relationships snapshot reverse-query)
              observed (atom [])
              codec-work (atom {})]
          (reset! identity-lookups 0)
          (binding [backend/*invoke-observer* #(swap! observed conj %)
                    cursor/*codec-work* codec-work]
            (doseq [query [first-query continued-query reverse-query]]
              (is (true? (:cached?
                          (eacl/read-relationships snapshot query))))))
          (is (zero? @identity-lookups))
          (is (empty? @observed)
              "a retained Snapshot hit needs no adapter operation")
          (is (empty? @codec-work)
              "an exact transport hit neither decodes nor rebuilds a cursor"))
        (finally
          (eacl/release! snapshot))))))

;; --- H2 ---------------------------------------------------------------------

(deftest client-built-before-the-first-schema-write-can-paginate-test
  (with-mem-conn [conn schema/v8-schema]
    (let [early (core/make-client conn {:security-key token-key})
          admin (core/make-client conn {:security-key token-key})]
      (is (nil? (:schema-state early))
          "the client has no mutable schema correctness latch")
      (seed-direct! conn admin 6)                ;; schema written by ANOTHER client
      (is (some? (idx/schema-version (d/db conn))))

      (testing "lookup-resources"
        (let [query {:subject (spice-object :user "alice")
                     :permission :admin
                     :resource/type :account
                     :first 2}
              page-1 (eacl/lookup-resources early query)
              page-2 (eacl/lookup-resources
                      early (assoc query :after (get-in page-1 [:page-info :end-cursor])))]
          (is (= ["acct0" "acct1"] (mapv :id (:data page-1))))
          (is (= ["acct2" "acct3"] (mapv :id (:data page-2))))))

      (testing "read-relationships"
        (let [page-1 (eacl/read-relationships early {:subject/type :user
                                                      :subject/id "alice" :first 2})
              page-2 (eacl/read-relationships
                      early {:subject/type :user
                             :subject/id "alice"
                             :first 2
                             :after (get-in page-1 [:page-info :end-cursor])})]
          (is (= 2 (count (:data page-1))))
          (is (= 2 (count (:data page-2))))
          (is (empty? (clojure.set/intersection
                       (set (map (comp :id :resource) (:data page-1)))
                       (set (map (comp :id :resource) (:data page-2))))))))

      (testing "schema derivation comes from each selected immutable snapshot"
        (is (pos? (get-in (core/cache-stats early)
                          [:structural-metrics :entry-count])))))))

(deftest cursor-minted-on-an-unstamped-database-still-paginates-test
  ;; The other half: when the database genuinely has no stamp at page-one time,
  ;; the historical branch selects an explicit nil schema version. Falling back
  ;; to the client's generation there (`or` rather than `contains?`) would fail
  ;; validation the moment the client later adopted a stamp.
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (core/make-client conn {:security-key token-key})]
      (is (nil? (:schema-state acl)))
      (let [page-1 (eacl/read-relationships acl {:resource/id "absent" :first 2})]
        (is (= [] (:data page-1))
            "an unstamped database has no relationships to read")))))

;; --- H3 ---------------------------------------------------------------------

(deftest unresolvable-result-objects-report-a-data-integrity-error-test
  ;; An entity retracted without delete-relationships! leaves a relationship
  ;; half that still grants. Coercing that result reported
  ;; :eacl.consistency/snapshot-unavailable — a cache/snapshot diagnosis for a
  ;; fault that also fires with {:cache shared-cache/no-cache}, naming only the first offender.
  (doseq [config [{:cache shared-cache/no-cache} {}]]
    (with-mem-conn [conn schema/v8-schema]
      (let [acl (core/make-client conn (assoc config :security-key token-key))
            _ (seed-direct! conn acl 3)
            gone (mapv #(d/entid (d/db conn) [:eacl/id (str "acct" %)]) [1 2])]
        @(d/transact conn (vec (for [[k eid] (map vector [1 2] gone)]
                                 [:db/retract eid :eacl/id (str "acct" k)])))
        (let [data (ex-data-of
                    #(eacl/lookup-resources acl {:subject (spice-object :user "alice")
                                                 :permission :admin
                                                 :resource/type :account}))]
          (is (= :eacl/unresolvable-object (:type data)))
          (is (= :eacl/unresolvable-object (:eacl/error data)))
          (is (= :lookup-resources (:operation data)))
          (is (false? (:historical? data)))
          (is (= (set gone) (set (:entity-ids data)))
              "every offending eid is reported, so one repair pass fixes them all")
          (is (str/includes? (:cause data "") "")))
        (testing "the message names the cause and the repair tool"
          (let [message (try
                          (eacl/lookup-resources acl {:subject (spice-object :user "alice")
                                                      :permission :admin
                                                      :resource/type :account})
                          nil
                          (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
            (is (str/includes? message "delete-relationships!"))
            (is (str/includes? message "dangling-relationship-report"))
            (is (not (str/includes? message "cache"))
                "this fires with {:cache shared-cache/no-cache} too; it must not be diagnosed as a cache fault")))))))

;; --- M1 ---------------------------------------------------------------------

(deftest fully-consistent-reads-reuse-the-basis-pinned-exact-entry-test
  ;; The exact key pins the complete source lineage, schema generation,
  ;; operation, query identity, and basis revision. One source at one revision
  ;; therefore maps to exactly one immutable database value.
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (core/make-client conn {:security-key token-key
                                      :cache {}})
          _ (seed-direct! conn acl 1)
          alice (spice-object :user "alice")
          account (spice-object :account "acct0")
          calls (atom 0)
          original engine/can?]
      (with-redefs [engine/can? (fn [& args]
                                  (swap! calls inc)
                                  (apply original args))]
        (dotimes [_ 3] (is (true? (eacl/can? acl alice :admin account))))
        (is (= 1 @calls) "identical fully-consistent reads at one basis compute once")

        (testing "a relationship change still invalidates, because basis-t moves"
          (eacl/delete-relationship! acl (->Relationship alice :owner account))
          (is (false? (eacl/can? acl alice :admin account)))
          (is (= 2 @calls)))))))

;; --- M2 ---------------------------------------------------------------------

(defn- forged-token
  [^String payload]
  (str "eacl4_" (.encodeToString (Base64/getUrlEncoder)
                                 (.getBytes payload StandardCharsets/UTF_8))))

(deftest hostile-page-tokens-are-typed-cursor-errors-test
  ;; decrypt-page-token EDN-parses the envelope before its AES-GCM tag can be
  ;; checked. It caught Exception but not Error, so deeply nested EDN threw a
  ;; StackOverflowError straight out of lookup-resources, and there was no
  ;; length bound at all on an unauthenticated caller-supplied parameter.
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (core/make-client conn {:security-key token-key})
          _ (seed-direct! conn acl 2)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account
                 :first 2}
          reject (fn [token]
                   (try
                     (eacl/lookup-resources acl (assoc query :after token))
                     ::no-throw
                     (catch clojure.lang.ExceptionInfo e (:eacl/error (ex-data e)))
                     (catch Throwable t (class t))))]

      (testing "deeply nested EDN does not escape as a StackOverflowError"
        (is (= :eacl.pagination/invalid-cursor
               (reject (forged-token (str (str/join (repeat 60000 "["))
                                          (str/join (repeat 60000 "]"))))))))

      (testing "an oversized token is rejected before any decoding"
        (is (= :eacl.pagination/invalid-cursor
               (reject (str "eacl4_" (str/join (repeat 4000000 "A")))))))

      (testing "ordinary garbage is also a typed cursor error"
        (is (= :eacl.pagination/invalid-cursor (reject "eacl4_not-base64!!")))
        (is (= :eacl.pagination/invalid-cursor (reject (forged-token "{:v 5}"))))
        (is (= :eacl.pagination/invalid-cursor (reject "not-an-eacl-token"))))

      (testing "cursor-identity rejections all carry the same error type"
        (let [cursor (get-in (eacl/lookup-resources acl query)
                             [:page-info :end-cursor])]
          ;; right token, wrong operation
          (is (= :eacl.pagination/invalid-cursor
                 (:eacl/error
                  (ex-data-of #(eacl/lookup-subjects
                                acl {:resource (spice-object :account "acct0")
                                     :permission :admin
                                     :subject/type :user
                                     :first 2
                                     :after cursor})))))
          ;; right token, different query binding
          (is (= :eacl.pagination/invalid-cursor
                 (:eacl/error
                  (ex-data-of #(eacl/lookup-resources
                                acl (assoc query
                                           :subject (spice-object :user "nobody")
                                           :after cursor)))))))))))

;; --- M4 ---------------------------------------------------------------------

(deftest current-cursor-pages-use-the-current-answer-cache-test
  ;; Non-exact cursor validation recovers on the current snapshot and may
  ;; publish only after re-evaluation there.
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (live-client conn)
          _ (seed-direct! conn acl 9)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account
                 :first 3}
          stats-before-page-1 (core/cache-stats acl)
          page-1 (eacl/lookup-resources acl query)
          stats-after-page-1 (core/cache-stats acl)
          page-2-query
          (assoc query :after (get-in page-1 [:page-info :end-cursor]))
          page-2 (eacl/lookup-resources acl page-2-query)
          stats-after-page-2 (core/cache-stats acl)
          page-2-hit (eacl/lookup-resources acl page-2-query)
          stats-after-hit (core/cache-stats acl)
          previous-query
          (-> query
              (dissoc :first)
              (assoc :last 3
                     :before (get-in page-2 [:page-info :start-cursor])))
          stats-before-previous (core/cache-stats acl)
          previous-page (eacl/lookup-resources acl previous-query)
          stats-after-previous (core/cache-stats acl)
          previous-hit (eacl/lookup-resources acl previous-query)
          stats-after-previous-hit (core/cache-stats acl)]
      (is (= ["acct0" "acct1" "acct2"] (mapv :id (:data page-1))))
      (is (= ["acct3" "acct4" "acct5"] (mapv :id (:data page-2))))
      (is (= (inc (:misses stats-before-page-1))
             (:misses stats-after-page-1)))
      (is (= (inc (:misses stats-after-page-1))
             (:misses stats-after-page-2))
          "a new current cursor page has one completed-answer miss")
      (is (= (inc (:rendered-page-hits stats-after-page-2))
             (:rendered-page-hits stats-after-hit))
          "an identical current cursor request has one rendered-page hit")
      (is (false? (:cached? page-2)))
      (is (true? (:cached? page-2-hit)))
      (is (= ["acct0" "acct1" "acct2"]
             (mapv :id (:data previous-page))))
      (is (false? (:cached? previous-page))
          "the first reverse request computes under its own semantic key")
      (is (true? (:cached? previous-hit))
          "the repeated reverse request reuses its exact rendered page")
      (is (= (inc (:misses stats-before-previous))
             (:misses stats-after-previous)))
      (is (= (inc (:rendered-page-hits stats-after-previous))
             (:rendered-page-hits stats-after-previous-hit)))
      (eacl/delete-relationship!
       acl
       (->Relationship (spice-object :user "alice")
                       :owner
                       (spice-object :account "acct3")))
      (let [before-recovery (core/cache-stats acl)
            recovered-1 (eacl/lookup-resources acl page-2-query)
            recovered-2 (eacl/lookup-resources acl page-2-query)
            after-recovery (core/cache-stats acl)]
        (is (= ["acct3" "acct4" "acct5"]
               (mapv :id (:data recovered-1))))
        (is (= (:data recovered-1) (:data recovered-2)))
        (is (nil? (get-in recovered-1
                          [:page-info :cursor-recovery])))
        (is (false? (:cached? recovered-1))
            "first historical recovery has its own complete exact key")
        (is (true? (:cached? recovered-2)))
        (is (= (:bypasses before-recovery)
               (:bypasses after-recovery)))))))

;; --- L2 ---------------------------------------------------------------------

(deftest read-relationships-rejects-a-bad-cursor-before-short-circuiting-test
  ;; A cursor is validated before the missing-object short-circuit, so an
  ;; invalid cursor is reported rather than silently reading as an empty page
  ;; whenever the filter also happens to name an object that does not resolve.
  ;; (validate-page-token-schema! moved above the short-circuit for the same
  ;; reason, matching lookup-resources; for a cursor its expectation is pinned
  ;; by the historical basis, so this identity check is the observable half.)
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (core/make-client conn {:security-key token-key})
          _ (seed-direct! conn acl 4)
          cursor (get-in (eacl/read-relationships acl {:subject/type :user
                                                       :subject/id "alice" :first 2})
                         [:page-info :end-cursor])
          result (ex-data-of
                  #(eacl/read-relationships acl {:subject/type :user
                                                 :subject/id "does-not-exist"
                                                 :first 2
                                                 :after cursor}))]
      (is (= :eacl.pagination/invalid-cursor (:eacl/error result))
          "an unusable cursor is an error, not an empty page")
      (is (= :query-mismatch (:reason result))))))

;; --- L4 ---------------------------------------------------------------------

(deftest result-shape-does-not-depend-on-cache-configuration-test
  (with-mem-conn [conn schema/v8-schema]
    (let [boot (core/make-client conn {:cache shared-cache/no-cache :security-key token-key})
          _ (seed-direct! conn boot 3)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}]
      (doseq [config [{:cache shared-cache/no-cache}
                      {}
                      {:cache {}}
                      {:cache {:max-entries 1}}]]
        (let [acl (core/make-client conn (assoc config :security-key token-key))
              data (:data (eacl/lookup-resources acl query))]
          (is (every? #(instance? eacl.core.SpiceObject %) data)
              (str "public result shape for " (pr-str config)))
          (is (= ["acct0" "acct1" "acct2"] (mapv :id data))))))))
