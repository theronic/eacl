(ns eacl.datomic.cache-review-regressions-test
  "One test per finding of the 2026-07-31 v7.4 adversarial review.

  See docs/reports/2026-07-31-eacl-v7.4-cache-adversarial-review.md. The
  critical pagination finding (C1) is covered by
  eacl.datomic.cache-differential-test."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def ^:private token-key "cache-review-regressions-key")

(def ^:private direct-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(defn- live-client
  [conn context]
  (core/make-client conn {:page-token-key token-key
                          :cache (assoc context :live-results? true)}))

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

;; --- H1 ---------------------------------------------------------------------

(deftest failed-write-validation-does-not-flush-the-live-cache-test
  ;; A :create conflict and an unknown object id are detected before any
  ;; transaction is submitted. Treating them as possibly-committed bumped the
  ;; coordinator's :uncertain counter, which is part of every dependency proof,
  ;; so one routine application error made 100% of live entries unreachable.
  (with-mem-conn [conn schema/v7-schema]
    (let [context (cache/local-context)
          coordinator (:coordinator context)
          acl (live-client conn context)
          _ (seed-direct! conn acl 1)
          alice (spice-object :user "alice")
          account (spice-object :account "acct0")
          calls (atom 0)
          original impl/can?]
      (with-redefs [impl/can? (fn [db s p r]
                                (swap! calls inc)
                                (original db s p r))]
        (is (true? (eacl/can? acl alice :admin account)))
        (is (= 1 @calls) "first call computes")
        (let [before (cache/generation coordinator [])]

          (testing ":create against an existing relationship"
            (is (= :eacl/relationship-conflict
                   (:type (ex-data-of
                           #(eacl/create-relationship!
                             acl (->Relationship alice :owner account))))))
            (is (= before (cache/generation coordinator []))
                "nothing was committed, so nothing is uncertain"))

          (testing ":create naming an object that does not exist"
            (is (= :eacl/unknown-object
                   (:type (ex-data-of
                           #(eacl/create-relationship!
                             acl (->Relationship (spice-object :user "nobody")
                                                 :owner account))))))
            (is (= before (cache/generation coordinator []))))

          (testing "an unsupported operation"
            (is (= :eacl/unsupported-operation
                   (:type (ex-data-of
                           #(eacl/write-relationship!
                             acl :upsert alice :owner account)))))
            (is (= before (cache/generation coordinator []))))

          (is (true? (eacl/can? acl alice :admin account)))
          (is (= 1 @calls)
              "the live entry survived every failed write"))))))

;; --- H2 ---------------------------------------------------------------------

(deftest client-built-before-the-first-schema-write-can-paginate-test
  ;; make-client latches :eacl/schema-version once. A client constructed before
  ;; the database was stamped kept a nil generation for life, minted page-one
  ;; tokens carrying :schema-version nil, and then rejected its own page two
  ;; with :eacl.pagination/stale-schema — because the historical branch derives
  ;; the real stamp from the d/as-of database. can? kept working, so the
  ;; breakage was invisible until the first paginated read.
  (with-mem-conn [conn schema/v7-schema]
    (let [early (core/make-client conn {:page-token-key token-key})
          admin (core/make-client conn {:page-token-key token-key})]
      (is (nil? (:schema-version @(:schema-state early)))
          "the early client latched an unstamped database")
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
        (let [page-1 (eacl/read-relationships early {:subject/id "alice" :first 2})
              page-2 (eacl/read-relationships
                      early {:subject/id "alice"
                             :first 2
                             :after (get-in page-1 [:page-info :end-cursor])})]
          (is (= 2 (count (:data page-1))))
          (is (= 2 (count (:data page-2))))
          (is (empty? (clojure.set/intersection
                       (set (map (comp :id :resource) (:data page-1)))
                       (set (map (comp :id :resource) (:data page-2))))))))

      (testing "the client adopts the stamp, so result caching becomes available"
        (is (some? (:schema-version @(:schema-state early))))))))

(deftest cursor-minted-on-an-unstamped-database-still-paginates-test
  ;; The other half: when the database genuinely has no stamp at page-one time,
  ;; the historical branch selects an explicit nil schema version. Falling back
  ;; to the client's generation there (`or` rather than `contains?`) would fail
  ;; validation the moment the client later adopted a stamp.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:page-token-key token-key})]
      (is (nil? (:schema-version @(:schema-state acl))))
      (let [page-1 (eacl/read-relationships acl {:subject/id "alice" :first 2})]
        (is (= [] (:data page-1))
            "an unstamped database has no relationships to read")))))

;; --- H3 ---------------------------------------------------------------------

(deftest unresolvable-result-objects-report-a-data-integrity-error-test
  ;; An entity retracted without delete-relationships! leaves a relationship
  ;; half that still grants. Coercing that result reported
  ;; :eacl.consistency/snapshot-unavailable — a cache/snapshot diagnosis for a
  ;; fault that also fires with {:cache false}, naming only the first offender.
  (doseq [config [{:cache false} {}]]
    (with-mem-conn [conn schema/v7-schema]
      (let [acl (core/make-client conn (assoc config :page-token-key token-key))
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
                "this fires with {:cache false} too; it must not be diagnosed as a cache fault")))))))

;; --- M1 ---------------------------------------------------------------------

(deftest fully-consistent-reads-reuse-the-basis-pinned-exact-entry-test
  ;; :exact-results? true wrote an entry (and a :latest-result pointer) on every
  ;; call that the default consistency mode could never read, so it was pure
  ;; cost. exact-key pins database-id, schema generation, operation, query
  ;; identity AND basis-t, and one database at one t is one DB value.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:page-token-key token-key
                                      :cache {:exact-results? true}})
          _ (seed-direct! conn acl 1)
          alice (spice-object :user "alice")
          account (spice-object :account "acct0")
          calls (atom 0)
          original impl/can?]
      (with-redefs [impl/can? (fn [db s p r] (swap! calls inc) (original db s p r))]
        (dotimes [_ 3] (is (true? (eacl/can? acl alice :admin account))))
        (is (= 1 @calls) "identical fully-consistent reads at one basis compute once")

        (testing "a relationship change still invalidates, because basis-t moves"
          (eacl/delete-relationship! acl (->Relationship alice :owner account))
          (is (false? (eacl/can? acl alice :admin account)))
          (is (= 2 @calls)))))))

;; --- M2 ---------------------------------------------------------------------

(defn- forged-token
  [^String payload]
  (str "eacl3_" (.encodeToString (Base64/getUrlEncoder)
                                 (.getBytes payload StandardCharsets/UTF_8))))

(deftest hostile-page-tokens-are-typed-cursor-errors-test
  ;; decrypt-page-token EDN-parses the envelope before its AES-GCM tag can be
  ;; checked. It caught Exception but not Error, so deeply nested EDN threw a
  ;; StackOverflowError straight out of lookup-resources, and there was no
  ;; length bound at all on an unauthenticated caller-supplied parameter.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:page-token-key token-key})
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
               (reject (str "eacl3_" (str/join (repeat 4000000 "A")))))))

      (testing "ordinary garbage is also a typed cursor error"
        (is (= :eacl.pagination/invalid-cursor (reject "eacl3_not-base64!!")))
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

(deftest cursor-pages-do-not-write-unreachable-live-entries-test
  ;; A cursor page is forced to :at-exact-snapshot and reads exact-key. Its live
  ;; entry was keyed by a query identity containing an :after edge, and every
  ;; request carrying such an edge takes the historical branch — so the entry
  ;; could never be read while still consuming the weight and entry budget that
  ;; live page-one answers compete for.
  (with-mem-conn [conn schema/v7-schema]
    (let [context (cache/local-context)
          store (:store context)
          acl (live-client conn context)
          _ (seed-direct! conn acl 9)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account
                 :first 3}
          page-1 (eacl/lookup-resources acl query)
          puts-after-page-1 (:puts (cache/stats store))
          page-2 (eacl/lookup-resources
                  acl (assoc query :after (get-in page-1 [:page-info :end-cursor])))
          stats (cache/stats store)]
      (is (= ["acct0" "acct1" "acct2"] (mapv :id (:data page-1))))
      (is (= ["acct3" "acct4" "acct5"] (mapv :id (:data page-2))))
      (is (= 3 puts-after-page-1)
          "page one publishes exact + live + the latest-result pointer")
      (is (= 1 (- (:puts stats) puts-after-page-1))
          "a cursor page publishes only its exact entry")
      (is (= 1 (get-in stats [:by-kind :latest-result :puts]))
          "and no second latest-result pointer under the cursor prefix"))))

;; --- H4 ---------------------------------------------------------------------

(defrecord CountingCoordinator [delegate mutations]
  cache/RelationshipCoordinator
  (generation [_] (cache/generation delegate))
  (generation [_ dependency-keys] (cache/generation delegate dependency-keys))
  (with-read [_ f] (cache/with-read delegate f))
  (with-mutation [_ f]
    (swap! mutations inc)
    (cache/with-mutation delegate f)))

(deftest delete-object-takes-the-coordinator-barrier-per-batch-test
  ;; delete-object! held the write barrier across its whole batch loop, so every
  ;; concurrent lookup blocked for the full multi-transaction delete (277ms for
  ;; 20k relationships in-memory; far worse against a real transactor). The
  ;; batches are separate Datomic transactions either way, so per-batch
  ;; publication is equally coherent and lets readers interleave.
  (with-mem-conn [conn schema/v7-schema]
    (let [coordinator (->CountingCoordinator (cache/local-coordinator) (atom 0))
          acl (core/make-client conn {:page-token-key token-key
                                      :cache {:store (cache/local-store)
                                              :coordinator coordinator}})
          ;; > 1000 tx ops (two retractions per relationship) forces > 1 batch
          n 1200
          _ (seed-direct! conn acl n)
          mutations-before (do (reset! (:mutations coordinator) 0)
                               @(:mutations coordinator))
          result (eacl/delete-object! acl (spice-object :user "alice"))]
      (is (= (* 2 n) (:retracted-datoms result))
          "both halves of every relationship are still accounted for")
      (is (> @(:mutations coordinator) 1)
          "the barrier is taken per batch, not once around the loop")
      (is (= mutations-before 0))
      (is (= [] (mapv :id (:data (eacl/lookup-resources
                                  acl {:subject (spice-object :user "alice")
                                       :permission :admin
                                       :resource/type :account}))))))))

;; --- L2 ---------------------------------------------------------------------

(deftest read-relationships-rejects-a-bad-cursor-before-short-circuiting-test
  ;; A cursor is validated before the missing-object short-circuit, so an
  ;; invalid cursor is reported rather than silently reading as an empty page
  ;; whenever the filter also happens to name an object that does not resolve.
  ;; (validate-page-token-schema! moved above the short-circuit for the same
  ;; reason, matching lookup-resources; for a cursor its expectation is pinned
  ;; by the historical basis, so this identity check is the observable half.)
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:page-token-key token-key})
          _ (seed-direct! conn acl 4)
          cursor (get-in (eacl/read-relationships acl {:subject/id "alice" :first 2})
                         [:page-info :end-cursor])
          result (ex-data-of
                  #(eacl/read-relationships acl {:subject/id "does-not-exist"
                                                 :first 2
                                                 :after cursor}))]
      (is (= :eacl.pagination/invalid-cursor (:eacl/error result))
          "an unusable cursor is an error, not an empty page")
      (is (= :query-mismatch (:reason result))))))

;; --- L4 ---------------------------------------------------------------------

(deftest result-shape-does-not-depend-on-cache-configuration-test
  (with-mem-conn [conn schema/v7-schema]
    (let [boot (core/make-client conn {:cache false :page-token-key token-key})
          _ (seed-direct! conn boot 3)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}]
      (doseq [config [{:cache false}
                      {}
                      {:cache {:exact-results? true}}
                      {:cache (assoc (cache/local-context) :live-results? true)}]]
        (let [acl (core/make-client conn (assoc config :page-token-key token-key))
              data (:data (eacl/lookup-resources acl query))]
          (is (every? #(instance? eacl.core.SpiceObject %) data)
              (str "public result shape for " (pr-str config)))
          (is (= ["acct0" "acct1" "acct2"] (mapv :id data))))))))
