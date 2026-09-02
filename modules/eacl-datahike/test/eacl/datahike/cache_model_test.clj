(ns eacl.datahike.cache-model-test
  "The randomized cached-versus-cache-free oracle, on Datahike.

  Port of eacl.datomic.cache-model-test (managed-reuse-certification 8.3):
  seeded interleaved EACL-API relationship and schema writes with checks,
  lookups, and counts compared against a no-cache client at every step. Every
  write below uses an EACL writer, so automatic proof-backed reuse is in
  contract and any stamped stale answer is a divergence."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.cache :as cache]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datahike.core :as datahike]))

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

;; Lehmer/Park-Miller PRNG, matching the DataScript port for determinism.
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
    (dissoc value :cached? :cache-basis)
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

(deftest randomized-cache-and-mutation-differential-test
  ;; Three seeds: Datahike transactions are the slow step and
  ;; the oracle's power comes from the interleaving, not the seed count.
  (doseq [seed (range 3)]
    (testing (str "automatic managed coherence, seed " seed)
      (let [conn (datahike/create-conn)
            rng (volatile! (inc seed))
            next-int! (fn [bound]
                        (mod (vswap! rng lehmer-next) bound))
            cached (datahike/make-client
                    conn
                    {:cache {}})
            uncached
            (atom (datahike/make-client conn {:cache cache/no-cache}))
            user-ids (mapv #(str "user-" %) (range 8))
            account-ids (mapv #(str "account-" %) (range 8))]
        (eacl/write-schema! cached owner-schema)
        (reset! uncached
                (datahike/make-client conn {:cache cache/no-cache}))
        (d/transact
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
                    (datahike/make-client conn {:cache cache/no-cache})))
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
            (when (zero? (mod step 7))
              (d/transact
               conn [{:eacl/id (str "application-" seed "-" step)}]))
            (dotimes [sample 2]
              (let [sample-user
                    (nth user-ids (next-int! (count user-ids)))
                    sample-account
                    (nth account-ids (next-int! (count account-ids)))]
                (assert-same-answers!
                 cached @uncached sample-user sample-account
                 (str "step " step ", sample " sample))))))))))
