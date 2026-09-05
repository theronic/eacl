(ns eacl.operator.seekable-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.datascript.backend :as backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.operator.cover-plan :as cover]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.evaluator-test :as fixtures]
            [eacl.operator.plan :as plan]
            [eacl.operator.seekable :as seekable]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.staged :as staged]))

(defn fixture []
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn
                         "caveat enabled(flag bool) { flag }
                          definition user {}
                          definition doc {
                            relation reader: user
                            relation writer: user
                            relation banned: user
                            permission both = reader & writer
                            permission allowed = reader - banned
                          }")
    (ds/transact! conn (mapv #(hash-map :eacl/id (str %)) (range 5)))
    (let [ids (mapv #(ds/entid (ds/db conn) [:eacl/id (str %)]) (range 5))
          user (first ids) docs (subvec ids 1)
          relations (into {} (for [name [:reader :writer :banned]]
                               [name (ds/entid (ds/db conn) [:eacl.relation/resource-type+relation-name+subject-type [:doc name :user]])]))
          caveat (ds/entid (ds/db conn) [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)
          write! (fn [doc name value]
                   (staged/write! writer :create [:user user (relations name) :doc (nth docs doc)] value)
                   (staged/write! writer :create [:user (nth docs doc) (relations name) :doc user] value))]
      (ds/transact! conn (mapv #(hash-map :db/id % :eacl.relation/caveats [caveat]
                                         :eacl.relation/allows-unqualified? true) (vals relations)))
      (doseq [doc (range 4)]
        (write! doc :reader (when (zero? doc) {:caveat caveat}))
        (write! doc :writer (case doc 1 {:caveat caveat} 2 {:valid-until-ms 100} nil)))
      (write! 1 :banned {:caveat caveat :valid-until-ms 100})
      (write! 2 :banned {:valid-until-ms 100})
      (let [db (ds/db conn)]
        {:conn conn :db db :adapter (backend/basis-adapter db {}) :user user :docs docs}))))

(defn options [{:keys [adapter db user]} permission time context direction]
  (let [permission [:doc permission] sealed (plan/seal-plan adapter permission)]
    {:adapter adapter :plan sealed :cover-plan (cover/seal-plan adapter sealed permission)
     :specialization-node (get (plan/expression-roots sealed) permission)
     :traversal :forward :subject-type :user :anchor-eid user :order-direction direction
     :qualification (fixtures/qualified-request db time context)
     :traversal-limits {:physical-chunk-size 1} :width 1}))

(defn drain [options]
  (loop [boundary nil result []]
    (let [page (seekable/page (assoc options :boundary boundary))
          emissions (:emissions page)
          result (into result emissions)]
      (if (:exhausted? page) result
          (recur (:coords (peek emissions)) result)))))

(deftest direct-specializations-carry-exact-qualified-evidence
  (let [{:keys [adapter user docs] :as env} (fixture)]
    (doseq [permission [:both :allowed] time [99 100] context [{} {"flag" true} {"flag" false}]
            direction [:asc :desc] traversal [:forward :reverse]]
      (let [options (assoc (options env permission time context direction) :traversal traversal)
            expected (->> (if (= direction :asc) docs (rseq docs))
                          (keep (fn [doc]
                                  (let [answer (scalar/check-eids
                                                {:adapter adapter :plan (:plan options)
                                                 :subject-type :user
                                                 :subject-eid (if (= traversal :forward) user doc)
                                                 :resource-eid (if (= traversal :forward) doc user)
                                                 :qualification (:qualification options)})]
                                    (when-not (evidence/no? answer) [doc answer])))) vec)
            actual (drain options)]
        (is (= (mapv first expected) (mapv :value actual)))
        (is (= (mapv (comp evidence/value second) expected) (mapv (comp evidence/value :evidence) actual)))
        (is (every? #(evidence/complete? (:evidence %)) actual))
        (is (every? #(evidence/before? time (evidence/valid-until (:evidence %))) actual))))))

(defn error-data [f]
  (try (f) nil (catch #?(:clj Throwable :cljs :default) error (ex-data error))))

(deftest qualified-heads-share-request-resolution-and-keep-physical-bounds
  (let [env (fixture) base (options env :both 100 {"flag" false} :asc)
        request (:qualification base) reads (atom {})
        observed (qualification/request
                  (assoc request :entity (fn [eid]
                                           (swap! reads update eid (fnil inc 0))
                                           ((:entity request) eid))))]
    (is (= [(last (:docs env))] (mapv :value (drain (assoc base :qualification observed)))))
    (is (seq @reads))
    (is (every? #(= 1 %) (vals @reads)))
    (is (= :fetched-values
           (:dimension (error-data #(seekable/page (assoc-in base [:traversal-limits :max-values] 2))))))
    (is (= :commands
           (:dimension (error-data #(seekable/page (assoc-in base [:traversal-limits :max-commands] 1))))))))

(deftest malformed-heads-fault-instead-of-becoming-absent
  (let [{:keys [conn user docs] :as env} (fixture)
        relation (ds/entid (ds/db conn) [:eacl.relation/resource-type+relation-name+subject-type [:doc :reader :user]])
        qid (edge/qualifier-id (impl/direct-edge (ds/db conn) :user user relation :doc (first docs)))]
    (ds/transact! conn [[:db.fn/retractEntity qid]])
    (let [db (ds/db conn) env (assoc env :db db :adapter (backend/basis-adapter db {}))]
      (is (= :eacl.authorization/evaluation-failure
             (:type (error-data #(seekable/page (options env :both 100 {} :asc)))))))))
