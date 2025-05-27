(ns eacl.benchmark
  (:require [criterium.core :as crit]
            [eacl.datomic.impl :as eacl :refer [Relation Relationship Permission]]
            [eacl.datomic.schema :as schema]
            [datomic.api :as d]
            [eacl.datomic.fixtures :as fixtures]
            [clojure.test :as t :refer [deftest testing is]]))

;(defn rand-subject [])

(defn tx! [conn tx-data]
  ;(prn tx-data)
  @(d/transact conn tx-data))

(deftest eacl-benchmarks
  ; todo switch to with-mem-conn.
  (def datomic-uri "datomic:mem://eacl-benchmark")
  (d/delete-database datomic-uri)
  (d/create-database datomic-uri)
  (def conn (d/connect datomic-uri))

  (tx! conn (concat schema/v3-schema))
  (tx! conn fixtures/base-fixtures)
  (tx! conn [{:db/ident       :entity/id
              :db/valueType   :db.type/uuid
              :db/unique      :db.unique/identity
              :db/cardinality :db.cardinality/one}

             {:db/ident       :company/name
              :db/valueType   :db.type/string
              :db/cardinality :db.cardinality/one}

             {:db/ident       :product/title
              :db/valueType   :db.type/string
              :db/cardinality :db.cardinality/one}

             {:db/ident       :user/username
              :db/valueType   :db.type/string
              :db/cardinality :db.cardinality/one}])

  (let [cids (repeatedly 100 d/squuid)]

    ; insert 100 companies
    (tx! conn (for [cid cids] {:company/name (str "company-" cid)
                               :entity/id    cid}))

    ; Relation so :company/owner can view & edit company
    (tx! conn [(eacl/Relation :company :company/owner [:company/view, :company/edit])])

    (doseq [cid cids]                                       ; for each company,
      ; make users:
      (let [company (d/entity (d/db conn) [:entity/id cid])]
        (let [uids (repeatedly 100 d/squuid)]
          (doseq [uid uids]
            (let [t-uid (d/tempid :db.part/user)]
              (tx! conn [{:db/id         t-uid
                          :user/username (str "user-" uid)
                          :entity/id     uid}
                         (eacl/Relationship t-uid :company/owner (:db/id company))]))))
        ; make the user an owner of the company:
        ;(tx! conn [(eacl/Relationship [:entity/id uid] :company/owner [:entity/id cid])]))))

        ; todo use multi-cardinality subject

        ; for each company, make 100 products
        (let [pids (repeatedly 100 d/squuid)]
          (doseq [pid pids]
            (let [t-pid (d/tempid :db.part/user)]
              (tx! conn [{:db/id         t-pid
                          :product/title (str "product-" pid)
                          :entity/id     pid}
                         (eacl/Relationship t-pid :product/company (:db/id company))]))))))

    (d/q '[:find ?user ?])))

(comment

  (let [db        (d/db conn)
        ; pull some relations
        users-cos (d/q '[:find ?user ?company
                         :where
                         [?user :user/username]
                         [?rel :eacl/subject ?user]
                         [?rel :eacl/relation :company/owner]
                         [?rel :eacl/resource ?company]]
                       db)]
    (prn (count users-cos) 'users-cos)
    (time
      (doall (frequencies (->> users-cos (pmap (fn [[user company]]
                                                 (eacl/can? db user :company/view company))))))))

  (let [subjects (d/q '[:find [?subject ...]
                        :where
                        [?subject :eacl/subject]]
                      (d/db conn))
        subject  (rand-nth subjects)]))
;(prn 'can? (eacl/can? (d/db conn) (:db/id subject) :company/view [:entity/id (first cids)]))))

