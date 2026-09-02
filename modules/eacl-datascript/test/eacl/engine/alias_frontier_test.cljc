(ns eacl.engine.alias-frontier-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.sealed-plan :as sealed-plan]))

(def ^:private alias-schema
  "definition user {}
   definition organization {
     relation member: user
     permission base = member
     permission alias = base
   }
   definition document {
     relation organization: organization
     permission view = organization->base + organization->alias
   }")

(defn- public-object [type id]
  (eacl/spice-object type id))

(defn- fixture
  []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})
        user (public-object :user "u")
        organization (public-object :organization "o")
        documents (mapv #(public-object :document (str "d" %)) (range 7))]
    (eacl/write-schema! client alias-schema)
    (ds/transact! conn
                  (into [{:eacl/id "u"} {:eacl/id "o"}]
                        (map (fn [document] {:eacl/id (:id document)}))
                        documents))
    (eacl/create-relationships!
     client
     (into [(eacl/->Relationship user :member organization)]
           (map #(eacl/->Relationship organization :organization %))
           documents))
    {:client client :user user :documents documents}))

(defn- page-sweep
  [client query]
  (loop [request (assoc query :first 2)
         values []]
    (let [page (eacl/lookup-resources client request)
          values (into values (map :id) (:data page))
          more? (get-in page [:page-info :has-next-page?])
          cursor (get-in page [:page-info :end-cursor])]
      (if more?
        (recur (assoc query :first 2 :after cursor) values)
        values))))

(defn- observe
  [canonicalize?]
  (let [{:keys [client user documents]} (fixture)
        query {:subject user
               :permission :view
               :resource/type :document
               :cache? false}
        scans (atom {})
        resolve sealed-plan/resolve-pure-alias
        disabled-resolver
        (fn [_ target]
          {:target target :changed? false :stop :reference-disabled})]
    (with-redefs [sealed-plan/resolve-pure-alias
                  (if canonicalize? resolve disabled-resolver)]
      (binding [backend/*backend-op-stats* scans]
        (let [result
              {:page-ids (page-sweep client query)
               :count (select-keys (eacl/count-resources client query)
                                   [:count :limit])
               :point-decisions
               (mapv #(eacl/can? client user :view %) documents)}]
          (assoc result :physical-scans
                 (+ (get @scans :subject->resources 0)
                    (get @scans :resource->subjects 0))))))))

(deftest acyclic-alias-frontier-is-a-work-only-refinement-test
  (let [reference (observe false)
        canonical (observe true)]
    (is (= (dissoc reference :physical-scans)
           (dissoc canonical :physical-scans)))
    (is (= (range 7)
           (map #(parse-long (subs % 1)) (:page-ids canonical))))
    (is (= 7 (count (distinct (:page-ids canonical)))))
    (is (= {:count 7 :limit -1}
           (:count canonical)))
    (is (every? true? (:point-decisions canonical)))
    (is (< (:physical-scans canonical) (:physical-scans reference)))))
