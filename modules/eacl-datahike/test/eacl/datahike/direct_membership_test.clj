(ns eacl.datahike.direct-membership-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.backend.direct-membership :as direct]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.direct-membership :as datahike-direct]
            [eacl.datahike.schema :as datahike-schema]
            [eacl.execution :as execution]
            [eacl.exact-integer :as exact-integer]))

(def schema
  "definition user {}
   definition resource {
     relation viewer: user
     permission view = viewer
   }")

(def modes
  {"keyword attributes" false
   "numeric attribute refs" true})

(defn- object [type id]
  (eacl/spice-object type id))

(defn- fixture [attribute-refs?]
  (let [conn (datahike/create-conn nil {:attribute-refs? attribute-refs?})
        client (datahike/make-client
                conn {:security-key
                      "0123456789-direct-membership-test-key"})
        anchor (object :user "anchor")
        users (mapv #(object :user (str "u-" %)) (range 12))
        resources (mapv #(object :resource (str "r-" %)) (range 16))]
    (eacl/write-schema! client schema)
    (d/transact conn (mapv #(hash-map :eacl/id (:id %))
                           (into [anchor] (concat users resources))))
    (eacl/create-relationships!
     client
     (into
      (mapv #(eacl/->Relationship anchor :viewer %)
            (take-nth 2 resources))
      (mapv #(eacl/->Relationship % :viewer (first resources))
            (take-nth 3 users))))
    (let [db (d/db conn)
          eid #(ddb/entid db [:eacl/id (:id %)])
          relation-eid
          (ddb/entid
           db
           [datahike-schema/relation-key-attr
            [:resource :viewer :user]])]
      {:conn conn
       :db db
       :adapter (datahike-backend/basis-adapter db {})
       :anchor-eid (eid anchor)
       :user-eids (mapv eid users)
       :resource-eids (mapv eid resources)
       :relation-eid relation-eid})))

(defn- forward-request
  [{:keys [anchor-eid relation-eid]} candidate-eids]
  {:direction :forward
   :descriptor {:subject-type :user
                :subject-eid anchor-eid
                :relation-eid relation-eid
                :resource-type :resource}
   :candidates (mapv #(vector :resource %) candidate-eids)})

(defn- reverse-request
  [{:keys [relation-eid]} resource-eid candidate-eids]
  {:direction :reverse
   :descriptor {:resource-type :resource
                :resource-eid resource-eid
                :relation-eid relation-eid
                :subject-type :user}
   :candidates (mapv #(vector :user %) candidate-eids)})

(defn- scalar-results [adapter request]
  (let [{:keys [direction descriptor candidates]} request]
    (mapv
     (fn [[_ candidate-eid]]
       (if (= :forward direction)
         (backend/invoke
          adapter :direct-match?
          (:subject-type descriptor) (:subject-eid descriptor)
          (:relation-eid descriptor)
          (:resource-type descriptor) candidate-eid)
         (backend/invoke
          adapter :direct-match?
          (:subject-type descriptor) candidate-eid
          (:relation-eid descriptor)
          (:resource-type descriptor) (:resource-eid descriptor))))
     candidates)))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest dense-and-sparse-batches-equal-scalar-in-both-directions-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [{:keys [conn adapter resource-eids user-eids] :as env}
            (fixture attribute-refs?)]
        (try
          (doseq [[case request expected-mode]
                  [[:forward-dense
                    (forward-request env
                                     (vec (reverse (subvec resource-eids 0 12))))
                    :dense-prefix-groups]
                   [:forward-all-present
                    (forward-request env (vec (take-nth 2 resource-eids)))
                    :dense-prefix-groups]
                   [:forward-all-absent
                    (forward-request env
                                     (vec (take-nth 2 (rest resource-eids))))
                    :dense-prefix-groups]
                   [:forward-sparse
                    (forward-request
                     env [(first resource-eids)
                          (peek resource-eids)
                          exact-integer/maximum])
                    :sparse-exact-groups]
                   [:reverse-dense
                    (reverse-request env (first resource-eids)
                                     (vec (reverse (subvec user-eids 0 10))))
                    :dense-prefix-groups]
                   [:reverse-sparse
                    (reverse-request
                     env (first resource-eids)
                     [(first user-eids)
                      (peek user-eids)
                      exact-integer/maximum])
                    :sparse-exact-groups]]]
            (testing (name case)
              (let [stats (atom {})
                    expected (scalar-results adapter request)
                    actual
                    (binding [datahike-direct/*physical-stats* stats]
                      (direct/direct-match-many? adapter request))]
                (is (= expected actual))
                (is (= 1 (get @stats expected-mode)))
                (is (= 1 (:physical-subgroups @stats)))
                (is (= 1 (:adapter-commands @stats)))
                (is (= (count (:candidates request))
                       (:scalar-equivalent-predicates @stats)))
                (when (= :dense-prefix-groups expected-mode)
                  (is (<= (:prefix-values @stats)
                          (inc (* datahike-direct/density-multiplier
                                  (count (:candidates request))))))))))
          (finally
            (d/release conn)))))))

(deftest as-of-batch-holds-the-realization-bound-test
  ;; The dense range kernel cannot honor its seek bound on a temporal
  ;; wrapper: Datahike's fallback realizes and sorts the whole endpoint
  ;; prefix. A dense-shaped batch on an as-of basis must therefore select
  ;; the exact-probe kernel and stay within its per-candidate bound even
  ;; when many tuples sit below the batch's first candidate.
  (let [{:keys [conn adapter resource-eids] :as env} (fixture false)]
    (try
      (let [as-of-db (d/as-of (d/db conn) (:max-tx (:db env)))
            as-of-adapter (datahike-backend/basis-adapter as-of-db {})
            ;; Upper half of the endpoint's resources: a dense span whose
            ;; seek bound would skip the five viewer tuples below it.
            candidates (vec (subvec resource-eids 10 16))
            request (forward-request env candidates)
            expected (scalar-results as-of-adapter request)
            stats (atom {})
            actual
            (binding [datahike-direct/*physical-stats* stats]
              (direct/direct-match-many? as-of-adapter request))]
        (is (= expected actual)
            "decisions equal repeated certified scalar membership")
        (is (nil? (:dense-prefix-groups @stats))
            "the range kernel is never selected on a wrapped basis")
        (is (= 1 (:sparse-exact-groups @stats)))
        (is (<= (:adapter-fetched-values @stats) (count candidates))
            "realized values stay within the exact-probe bound"))
      (finally
        (d/release conn)))))

(deftest density-mode-selection-boundary-test
  ;; span == 2k selects the range kernel on a direct basis; span == 2k+1 and
  ;; every wrapped basis select the exact probe. The pinned selection is the
  ;; certified physical-policy identity the adapter reports.
  (let [{:keys [conn adapter resource-eids user-eids] :as env}
        (fixture false)]
    (try
      (is (= :datahike-density-bounded-v2
             (:id datahike-direct/physical-policy-identity)))
      (is (= :direct-database-values
             (get-in datahike-direct/physical-policy-identity
                     [:parameters :dense-kernel-bases])))
      (let [as-of-db (d/as-of (d/db conn) (:max-tx (:db env)))
            as-of-adapter (datahike-backend/basis-adapter as-of-db {})
            forward-first (first resource-eids)
            reverse-first (first user-eids)
            boundary-cases
            (fn [first-eid]
              ;; k=4: span 8 == 2k is dense; span 9 == 2k+1 is sparse.
              ;; k=1: span 1 is dense. Candidate eids need not hold tuples.
              [[:span-exactly-2k
                [first-eid (+ first-eid 2) (+ first-eid 5) (+ first-eid 7)]
                :dense-prefix-groups]
               [:span-just-over-2k
                [first-eid (+ first-eid 2) (+ first-eid 5) (+ first-eid 8)]
                :sparse-exact-groups]
               [:single-candidate
                [first-eid]
                :dense-prefix-groups]])
            run-case
            (fn [target request expected-mode]
              (let [stats (atom {})
                    expected (scalar-results target request)
                    actual
                    (binding [datahike-direct/*physical-stats* stats]
                      (direct/direct-match-many? target request))]
                (is (= expected actual))
                (is (= 1 (get @stats expected-mode))
                    (str "selected mode must match the certified policy: "
                         expected-mode))))]
        (doseq [[case-name candidates expected-mode]
                (boundary-cases forward-first)]
          (testing (str "forward " (name case-name))
            (run-case adapter (forward-request env candidates)
                      expected-mode))
          (testing (str "forward as-of " (name case-name))
            (run-case as-of-adapter (forward-request env candidates)
                      :sparse-exact-groups)))
        (doseq [[case-name candidates expected-mode]
                (boundary-cases reverse-first)]
          (testing (str "reverse " (name case-name))
            (run-case adapter
                      (reverse-request env (first resource-eids) candidates)
                      expected-mode))
          (testing (str "reverse as-of " (name case-name))
            (run-case as-of-adapter
                      (reverse-request env (first resource-eids) candidates)
                      :sparse-exact-groups))))
      (finally
        (d/release conn)))))

(deftest empty-cancelled-and-concurrently-advanced-batches-are-basis-stable-test
  (let [{:keys [conn adapter anchor-eid relation-eid resource-eids] :as env}
        (fixture false)
        absent-eid (second resource-eids)
        request (forward-request env [absent-eid])]
    (try
      (is (= []
             (direct/direct-match-many?
              adapter (forward-request env []))))
      (let [token (execution/cancellation-token)
            contract (execution/normalize
                      {} :check-permission {:cancellation-token token})]
        (execution/cancel! token)
        (is (= :eacl.execution/cancelled
               (:type
                (error-data
                 #(binding [execution/*contract* contract]
                    (direct/direct-match-many? adapter request)))))))
      (is (= [false] (direct/direct-match-many? adapter request)))
      (d/transact
       conn
       [[:db/add anchor-eid
         :eacl.v9.relationship/subject-type+relation+resource-type+resource+qualifier
         [:user relation-eid :resource absent-eid nil]]])
      (is (= [false] (direct/direct-match-many? adapter request))
          "the already selected Datahike DB does not follow the live head")
      (let [as-of-adapter
            (datahike-backend/basis-adapter
             (d/as-of (d/db conn) (:max-tx (:db env))) {})]
        (is (= [false]
               (direct/direct-match-many? as-of-adapter request))))
      (let [new-adapter (datahike-backend/basis-adapter (d/db conn) {})]
        (is (= [true] (direct/direct-match-many? new-adapter request))))
      (is (= :eacl/unsupported-topology
             (:type
              (error-data
               #(datahike-backend/basis-adapter
                 (d/filter (d/db conn) (fn [_db _datom] true)) {})))))
      (finally
        (d/release conn)))))
