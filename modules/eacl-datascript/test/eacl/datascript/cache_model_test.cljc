(ns eacl.datascript.cache-model-test
  "The randomized cached-versus-cache-free oracle, on DataScript.

  Port of eacl.datomic.cache-model-test (managed-reuse-certification 8.3):
  seeded interleaved EACL-API relationship and schema writes with checks,
  lookups, and counts compared against a no-cache client at every step. Every
  write below uses an EACL writer, so automatic proof-backed reuse is in
  contract and any stamped stale answer is a divergence."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.cache :as cache]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datascript.core :as datascript]))

(def ^:private owner-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner
   }")

(def ^:private owner-and-auditor-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner + auditor
   }")

;; Lehmer/Park-Miller PRNG: deterministic and portable (products stay well
;; inside 2^53, so CLJS doubles compute them exactly).
(defn- lehmer-next
  [state]
  (mod (* state 48271) 2147483647))

(defn- page-answer
  [page]
  {:data (mapv (juxt :type :id) (:data page))
   :page-info (select-keys (:page-info page)
                           [:has-next-page? :has-previous-page?])})

(defn- without-cache-provenance
  [value]
  (if (map? value)
    ;; Separate live requests may mint different time-scoped cursor bytes.
    ;; Compare authorization and page bounds; cursor validity has its own suite.
    (cond-> (dissoc value :cached? :cache-basis)
      (:page-info value) (update :page-info dissoc :start-cursor :end-cursor))
    value))

(defn- assert-same-answers!
  [cached uncached user-id account-id label]
  (let [user (spice-object :user user-id)
        account (spice-object :account account-id)
        forward {:subject user
                 :permission :admin
                 :resource/type :account
                 :first 100}
        reverse-query {:resource account
                       :permission :admin
                       :subject/type :user
                       :first 100}]
    (is (= (eacl/can? uncached user :admin account)
           (eacl/can? cached user :admin account))
        (str label " can?"))
    (is (= (page-answer (eacl/lookup-resources uncached forward))
           (page-answer (eacl/lookup-resources cached forward)))
        (str label " lookup-resources"))
    (is (= (page-answer (eacl/lookup-subjects uncached reverse-query))
           (page-answer (eacl/lookup-subjects cached reverse-query)))
        (str label " lookup-subjects"))
    ;; Cache provenance is about HOW an answer was obtained; only the answer
    ;; itself must not differ.
    (let [answer #(dissoc % :cached? :cache-basis)]
      (is (= (answer (eacl/count-resources uncached (dissoc forward :first)))
             (answer (eacl/count-resources cached (dissoc forward :first))))
          (str label " count-resources"))
      (is (= (answer (eacl/count-subjects uncached (dissoc reverse-query :first)))
             (answer (eacl/count-subjects cached (dissoc reverse-query :first))))
          (str label " count-subjects")))
    (doseq [[operation call request]
            [[:check-permission
              #(eacl/check-permission cached %)
              {:subject user :permission :admin :resource account}]
             [:lookup-resources
              #(eacl/lookup-resources cached %) forward]
             [:lookup-subjects
              #(eacl/lookup-subjects cached %) reverse-query]
             [:count-resources
              #(eacl/count-resources cached %) (dissoc forward :first)]
             [:count-subjects
              #(eacl/count-subjects cached %)
              (dissoc reverse-query :first)]]]
      (let [enabled (call request)
            repeated (call request)
            bypassed (call (assoc request :cache? false))]
        (is (= (without-cache-provenance enabled)
               (without-cache-provenance repeated)
               (without-cache-provenance bypassed))
            (str label " " operation " per-request bypass"))))))

(defn- assert-retained-publication-order!
  [conn cached user-id account-id seed step newer-first?]
  (let [request {:subject (spice-object :user user-id)
                 :permission :admin
                 :resource (spice-object :account account-id)}
        answer #(select-keys % [:allowed?])
        older (eacl/snapshot cached)]
    (try
      (ds/transact!
       conn [{:eacl/id (str "application-" seed "-" step)}])
      (let [newer (eacl/snapshot cached)]
        (try
          (let [[older-result newer-result]
                #?(:clj
                   (let [delayed-target (if newer-first? older newer)
                         immediate-target (if newer-first? newer older)
                         started (promise)
                         release (promise)
                         delayed
                         (future
                           (deliver started true)
                           @release
                           (answer
                            (eacl/check-permission
                             delayed-target request)))]
                     @started
                     (let [immediate
                           (answer
                            (eacl/check-permission
                             immediate-target request))]
                       (deliver release true)
                       (if newer-first?
                         [@delayed immediate]
                         [immediate @delayed])))
                   :cljs
                   (if newer-first?
                     (let [newer-result
                           (answer
                            (eacl/check-permission newer request))
                           older-result
                           (answer
                            (eacl/check-permission older request))]
                       [older-result newer-result])
                     [(answer (eacl/check-permission older request))
                      (answer (eacl/check-permission newer request))]))]
            (is (= older-result
                   (answer
                    (eacl/check-permission
                     older (assoc request :cache? false))))
                (str "retained older basis, seed " seed ", step " step))
            (is (= newer-result
                   (answer
                    (eacl/check-permission
                     newer (assoc request :cache? false))))
                (str "newer basis, seed " seed ", step " step)))
          (finally
            (eacl/release! newer))))
      (finally
        (eacl/release! older)))))

(deftest randomized-cache-and-mutation-differential-test
  (doseq [seed (range 5)]
    (testing (str "automatic managed coherence, seed " seed)
      (let [conn (datascript/create-conn)
            rng (volatile! (inc seed))
            next-int! (fn [bound]
                        (mod (vswap! rng lehmer-next) bound))
            cached (datascript/make-client
                    conn
                    {:cache {}})
            uncached
            (atom (datascript/make-client conn {:cache cache/no-cache}))
            user-ids (mapv #(str "user-" %) (range 8))
            account-ids (mapv #(str "account-" %) (range 8))]
        (eacl/write-schema! cached owner-schema)
        (reset! uncached
                (datascript/make-client conn {:cache cache/no-cache}))
        (ds/transact!
         conn
         (vec (map-indexed
               (fn [index id]
                 {:db/id (- (inc index))
                  :eacl/id id})
               (concat user-ids account-ids))))
        (dotimes [step 50]
          (when (= 25 step)
            (eacl/write-schema! cached owner-and-auditor-schema)
            ;; Other clients are deliberately not polled for schema changes.
            (reset! uncached
                    (datascript/make-client conn {:cache cache/no-cache})))
          (let [user-id (nth user-ids (next-int! (count user-ids)))
                account-id (nth account-ids (next-int! (count account-ids)))
                relation (if (zero? (next-int! 2)) :owner :auditor)
                operation (if (zero? (next-int! 2)) :touch :delete)]
            (eacl/write-relationship!
             cached
             {:operation operation
              :subject (spice-object :user user-id)
              :relation relation
              :resource (spice-object :account account-id)})
            ;; Unrelated application basis churn alongside relationship
            ;; no-ops and relevant dependency changes.
            (when (zero? (mod step 7))
              (assert-retained-publication-order!
               conn cached user-id account-id seed step
               (zero? (next-int! 2))))
            (dotimes [sample 2]
              (let [sample-user
                    (nth user-ids (next-int! (count user-ids)))
                    sample-account
                    (nth account-ids (next-int! (count account-ids)))]
                (assert-same-answers!
                 cached @uncached sample-user sample-account
                 (str "step " step ", sample " sample))))))))))
