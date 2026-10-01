(ns eacl.datascript.wildcard-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            #?(:clj [clojure.java.io :as io])
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.backend.direct-membership :as direct]
            [eacl.cache :as cache]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.schema.wildcard :as wildcard]
            [eacl.wildcard-contract-support :as contract]))

(defn- seed!
  [conn ids]
  (ds/transact! conn (mapv (fn [id] {:eacl/id id}) ids)))

(defn- seed-objects!
  [conn]
  (seed! conn contract/objects))

(deftest definite-concrete-membership-does-not-evaluate-wildcard-caveat-test
  (let [conn (datascript/create-conn)
        calls (atom 0)
        stats (atom {})
        portable (fixtures/portable-evaluator (atom 0))
        client (datascript/make-client
                conn {:caveat-evaluator
                      (reify evaluator/Evaluator
                        (descriptor [_] (evaluator/descriptor portable))
                        (-evaluate [_ _ _ _]
                          (swap! calls inc)
                          {:outcome :error :reason :resource-limit}))})]
    (seed-objects! conn)
    (eacl/write-schema! client contract/caveat-schema)
    (eacl/create-relationships!
     client [(eacl/->Relationship (contract/->user "alice") :amixed (contract/->area "a1"))
             (eacl/->Relationship (contract/->user "alice") :editor (contract/->area "a1"))
             (assoc (eacl/->Relationship (contract/->user "*") :amixed (contract/->area "a1"))
                    :caveat "is_weekday")])
    (binding [direct/*physical-stats* stats]
      (is (true? (eacl/can? client (contract/->user "alice") :edit (contract/->area "a1")))))
    (is (zero? @calls) "the definite concrete branch discharges direct membership")
    (is (= 2 (:scalar-equivalent-predicates @stats))
        "the two concrete grants suffice; no wildcard probe is needed")))

(deftest wildcard-identity-bypasses-custom-codecs-test
  (let [conn (datascript/create-conn)
        writer (datascript/make-client conn {})]
    (seed-objects! conn)
    (eacl/write-schema! writer contract/wildcard-schema)
    (eacl/create-relationship! writer (contract/->user "*") :viewer (contract/->area "a1"))
    (doseq [reject-internal? [false true]]
      (let [client (datascript/make-client
                    conn
                    {:object-id->lookup-ref (fn [id] [:eacl/id (subs id 3)])
                     :entid->object-id
                     (fn [db eid]
                       (let [id (:eacl/id (ds/entity db eid))]
                         (when (and reject-internal? (= wildcard/entity-id id))
                           (throw (ex-info "Application codec requires an application object." {})))
                         (str "id:" id)))})
            query {:resource (contract/->area "id:a1") :permission :view
                   :subject/type :user :first 1}]
        (testing "wildcards are not application objects"
          (is (= ["*"] (mapv :id (:data (eacl/lookup-subjects client query)))))
          (is (= ["*"]
                 (mapv (comp :id :subject)
                       (:data (eacl/read-relationships
                               client {:subject/type :user :subject/id "*"}))))))))))

(deftest wildcard-entity-cannot-be-addressed-by-an-application-alias-test
  (let [conn (datascript/create-conn {:app/id {:db/unique :db.unique/identity}})
        writer (datascript/make-client conn {})]
    (seed-objects! conn)
    (eacl/write-schema! writer contract/wildcard-schema)
    (eacl/create-relationship! writer (contract/->user "*") :viewer (contract/->area "a1"))
    (ds/transact! conn [{:db/id wildcard/lookup-ref :app/id "alias"}
                        {:db/id [:eacl/id "a1"] :app/id "a1"}])
    (let [client (datascript/make-client
                  conn {:object-id->lookup-ref #(vector :app/id %)})]
      (is (false? (eacl/can? client (contract/->user "alias") :view (contract/->area "a1")))
          "an alias must not turn the wildcard into a concrete subject"))))

(deftest wildcard-subjects-stay-inside-the-public-identity-boundary-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})
        error-reason (fn [f]
                       (try (f) nil
                            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                              (:reason (ex-data error)))))]
    (seed-objects! conn)
    (eacl/write-schema! client contract/wildcard-schema)
    (eacl/create-relationship! client (contract/->user "*") :viewer (contract/->area "a1"))
    (testing "a wildcard with a subject relation is rejected, not widened to every user"
      (is (= :unsupported-subject-relation
             (error-reason #(eacl/create-relationship!
                           client (assoc (contract/->user "*") :relation :member)
                           :viewer (contract/->area "a2")))))
      (is (false? (eacl/can? client (contract/->user "alice") :view (contract/->area "a2")))))
    (testing "a numeric public ID equal to the wildcard entity's eid names no object"
      (let [wildcard-eid (ds/entid (ds/db conn) wildcard/lookup-ref)]
        (is (zero? (:retracted-datoms
                    (eacl/delete-object! client (contract/->user wildcard-eid))))
            "deleting it must not remove every type's wildcard relationships")
        (is (true? (eacl/can? client (contract/->user "alice") :view (contract/->area "a1"))))
        (is (false? (eacl/can? client (contract/->user wildcard-eid) :view (contract/->area "a1")))
            "an unknown subject holds nothing")))))

(deftest unused-wildcard-branch-keeps-subject-lookups-bounded-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})
        banned (mapv #(str "banned-" %) (range 512))
        resource (contract/->area "a1")
        query {:resource resource :permission :enter :subject/type :user}]
    (seed! conn (into ["alice" "a1"] banned))
    (eacl/write-schema! client contract/wildcard-schema)
    (eacl/create-relationships!
     client (into [(eacl/->Relationship (contract/->user "alice") :viewer resource)]
                  (map #(eacl/->Relationship (contract/->user %) :banned resource))
                  banned))
    (doseq [operation [:lookup :count]]
      (let [stats (atom {})]
        (binding [engine/*recursive-traversal-stats* stats]
          (case operation
            :lookup (is (= ["alice"]
                           (mapv :id (:data (eacl/lookup-subjects client (assoc query :first 10))))))
            :count (is (= 1 (:count (eacl/count-subjects client query))))))
        (is (< (:fetched-values @stats 0) 64)
            (str operation " must not enumerate unrelated bans without a wildcard tuple: " @stats))))))

(deftest datascript-wildcard-contract-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (seed-objects! conn)
    (contract/assert-wildcard-contract! client #(seed! conn %))))

(deftest datascript-wildcard-without-cache-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})]
    (seed-objects! conn)
    (contract/assert-wildcard-contract! client #(seed! conn %))))

(deftest datascript-wildcard-speculative-contract-test
  (let [conn (datascript/create-conn)]
    (seed-objects! conn)
    (contract/assert-wildcard-speculative-contract!
     (datascript/make-client conn {}))))

(deftest datascript-wildcard-portable-caveat-contract-test
  (let [conn (datascript/create-conn)
        now-ms (atom 1790000000000)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock #(deref now-ms)})]
    (seed-objects! conn)
    (contract/assert-wildcard-caveat-contract! client now-ms)))

#?(:clj
   (defn- jvm-evaluator
     "The certified JVM CEL evaluator. The workspace test classpath has
     eacl-caveats-jvm; an isolated eacl-datascript classpath does not."
     []
     (when-let [evaluator (try (requiring-resolve 'eacl.caveats.jvm/evaluator)
                               (catch java.io.FileNotFoundException _ nil))]
       (evaluator))))

#?(:clj
   (deftest datascript-wildcard-jvm-caveat-contract-test
     (if-let [evaluator (jvm-evaluator)]
       (let [conn (datascript/create-conn)
             now-ms (atom 1790000000000)
             client (datascript/make-client
                     conn {:caveat-evaluator evaluator
                           :clock #(deref now-ms)})]
         (seed-objects! conn)
         (contract/assert-wildcard-caveat-contract! client now-ms))
       (is (nil? (io/resource "eacl/caveats/jvm.clj"))
           "only an isolated module classpath lacks the JVM evaluator"))))

(deftest datascript-connection-without-wildcard-attributes-test
  ;; A connection created from an older EACL schema cannot store wildcard
  ;; Caveat refs; the schema write fails before any transaction.
  (let [conn (ds/create-conn
              (dissoc (schema/merge-schema) :eacl.relation/wildcard-caveats))]
    (is (= :eacl.schema/wildcard-attributes-missing
           (:type
            (try
              (schema/write-schema!
               conn contract/caveat-schema {:allow-caveats? true})
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                (ex-data error))))))))
