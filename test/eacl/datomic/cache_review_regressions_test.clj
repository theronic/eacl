(ns eacl.datomic.cache-review-regressions-test
  "One test per finding of the 2026-07-31 v8.0 adversarial review.

  See docs/reports/2026-07-31-eacl-v8.0-cache-adversarial-review.md. The
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
  "A client that retains results. This used to need an explicit coordinator
  plus :live-results? true; it is now just :remember-answers."
  [conn context]
  (core/make-client conn {:page-token-key token-key
                          :cache (assoc context :remember-answers true)}))

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

(deftest explicit-cache-true-does-not-fragment-answer-keys-test
  ;; :cache? selects how to obtain an answer, not which answer was requested.
  ;; It was removed from cursor identity but accidentally retained in finished
  ;; lookup/count keys, so an explicit true recomputed an answer already cached
  ;; by the equivalent request with the option omitted.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:page-token-key token-key
                                      :cache {:remember-answers true}})
          _ (seed-direct! conn acl 3)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          lookup-calls (atom 0)
          count-calls (atom 0)
          lookup-resources impl/lookup-resources
          count-resources impl/count-resources]
      (with-redefs [impl/lookup-resources
                    (fn [& args]
                      (swap! lookup-calls inc)
                      (apply lookup-resources args))
                    impl/count-resources
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

(defn- append-token-byte
  [token byte-value]
  (let [prefix "eacl4_"
        decoder (Base64/getUrlDecoder)
        encoder (.withoutPadding (Base64/getUrlEncoder))
        raw (.decode decoder (subs token (count prefix)))
        tainted (byte-array (inc (alength raw)))]
    (System/arraycopy raw 0 tainted 0 (alength raw))
    (aset-byte tainted (alength raw) (byte byte-value))
    (str prefix (.encodeToString encoder tainted))))

(deftest page-token-envelope-rejects-unauthenticated-trailing-bytes-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:page-token-key token-key})
          opts (:opts acl)
          token (core/page-token opts {:op :test})]
      (is (= :eacl.pagination/invalid-cursor
             (:eacl/error
              (ex-data-of
               #(core/token->page-bound
                 opts (append-token-byte token 42)))))))))

(deftest page-token-encoder-never-mints-a-token-its-decoder-refuses-test
  (with-mem-conn [conn schema/v7-schema]
    (let [opts (:opts (core/make-client
                       conn {:page-token-key token-key}))
          data (ex-data-of
                #(core/page-token
                  opts {:oversized (apply str (repeat 20000 "x"))}))]
      (is (= :eacl.pagination/cursor-too-large (:type data)))
      (is (< (:maximum-length data) (:encoded-length data))))))

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
  ;; fault that also fires with {:cache cache/no-cache}, naming only the first offender.
  (doseq [config [{:cache cache/no-cache} {}]]
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
                "this fires with {:cache cache/no-cache} too; it must not be diagnosed as a cache fault")))))))

;; --- M1 ---------------------------------------------------------------------

(deftest fully-consistent-reads-reuse-the-basis-pinned-exact-entry-test
  ;; :remember-answers true wrote an entry (and a :latest-result pointer) on every
  ;; call that the default consistency mode could never read, so it was pure
  ;; cost. exact-key pins database-id, schema generation, operation, query
  ;; identity AND basis-t, and one database at one t is one DB value.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:page-token-key token-key
                                      :cache {:remember-answers true}})
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
  (str "eacl4_" (.encodeToString (Base64/getUrlEncoder)
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

(deftest cursor-pages-do-not-write-unreachable-live-entries-test
  ;; A cursor page is forced to :at-exact-snapshot and reads exact-key. Its live
  ;; entry was keyed by a query identity containing an :after edge, and every
  ;; request carrying such an edge takes the historical branch — so the entry
  ;; could never be read while still consuming the weight and entry budget that
  ;; live page-one answers compete for.
  (with-mem-conn [conn schema/v7-schema]
    (let [store (cache/local-store)
          context {:store store}
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
      (is (= 2 puts-after-page-1)
          "page one publishes the exact entry + the latest-result pointer")
      (is (= 1 (- (:puts stats) puts-after-page-1))
          "a cursor page publishes only its exact entry")
      (is (= 1 (get-in stats [:by-kind :latest-result :puts]))
          "and no second latest-result pointer under the cursor prefix"))))

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
    (let [boot (core/make-client conn {:cache cache/no-cache :page-token-key token-key})
          _ (seed-direct! conn boot 3)
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}]
      (doseq [config [{:cache cache/no-cache}
                      {}
                      {:cache {:remember-answers true}}
                      {:cache {:remember-answers false}}]]
        (let [acl (core/make-client conn (assoc config :page-token-key token-key))
              data (:data (eacl/lookup-resources acl query))]
          (is (every? #(instance? eacl.core.SpiceObject %) data)
              (str "public result shape for " (pr-str config)))
          (is (= ["acct0" "acct1" "acct2"] (mapv :id data))))))))
