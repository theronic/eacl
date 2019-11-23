(ns eacl.core-test
  (:require
    [eacl.utils :refer [spy profile]]
    [taoensso.timbre :as log]
    [clojure.test :refer [deftest is]]
    [eacl.core :as eacl]
    [datascript.core :as d]))

(defn my-fixtures [f]
  (spy :fixtures
       (let [conn     (d/create-conn eacl/schema)
             invoices (eacl/create-entity! conn {:eacl/path "invoices"})
             user     (eacl/create-user! conn {:user/name "Petrus"})]
         (doseq [invoice (range 100000)]
           (eacl/create-entity! conn {:invoice/number invoice
                                      :eacl/parent    (:db/id invoices)})))))

(deftest enum-tests
  (let [conn     (d/create-conn eacl/schema)
        invoices (eacl/create-entity! conn {:eacl/path "invoices"})
        user     (eacl/create-user! conn {:user/name "Petrus"})]
    (doseq [invoice (range 100000)]
      (eacl/create-entity! conn {:invoice/number invoice
                                 :eacl/parent (:db/id invoices)}))
    (log/debug "Enum 1:" (eacl/enum-user-permissions @conn (:db/id user)))
    (let [db @conn]
      (is (false? (eacl/can? db {:eacl/user     user        ;(:db/id user)
                                 :eacl/action   :read
                                 :eacl/resource invoices}))) ; (:db/id invoices)}))))
      (eacl/grant! conn {:eacl/user     user
                         :eacl/resource invoices
                         :eacl/action   :read})
      (log/debug "Enum 2:" (eacl/enum-user-permissions @conn (:db/id user)))
      (is (true? (eacl/can? @conn {:eacl/user     user      ; (:db/id user)
                                   :eacl/action   :read
                                   :eacl/resource invoices}))) ; (:db/id invoices)}))))))
      (eacl/deny! conn {:eacl/user     user
                        :eacl/resource invoices
                        :eacl/action   :read})
      (eacl/grant! conn {:eacl/user     user
                         :eacl/action   :write
                         :eacl/resource invoices})
      (log/debug "Enum 3:" (eacl/enum-user-permissions @conn (:db/id user)))
      (let [db1 @conn]
        (is (false? (eacl/can? db1 {:eacl/user     user
                                    :eacl/action   :read
                                    :eacl/resource invoices})))
        (is (true? (eacl/can? db1 {:eacl/user     user
                                   :eacl/action   :write
                                   :eacl/resource invoices})))))))

(comment
  (enum-tests)
  (let [conn     (d/create-conn eacl/schema)
        user     (eacl/create-user! conn {:user/name "Test User"})
        invoices (eacl/create-entity! conn {:eacl/path "invoices"})]
    (eacl/grant! conn
                 {:eacl/user     user
                  :eacl/action   :read
                  :eacl/resource invoices})
    (eacl/deny! conn
                {:eacl/user     user
                 :eacl/action   :write
                 :eacl/resource invoices})

    (d/q '[:find ?perm
           (pull ?user [*])
           ?action
           (pull ?resource [*])
           (pull ?group [*])
           (pull ?perm [*])
           :in
           $ %
           ?user ?action ?resource
           :where
           [?perm :eacl/group ?group]                       ;; not sure
           [?perm :eacl/resource ?resource]
           [?perm :eacl/action :read]                       ; :read]
           [?perm :eacl/allowed? true]                      ; true]
           (not [?perm :eacl/allowed? false])               ;; /blocked?
           (child-of ?user ?group)]
         @conn
         eacl/child-rules
         (:db/id user) :read (:db/id invoices))))
;(d/q '[:find
;       (pull ?user [*])
;       ?allowed
;       ?action
;       (pull ?resource [*])
;       (pull ?group [*])
;       (pull ?perm [*])
;       :in $ %
;       :where
;       ;[?perm :eacl/user ?user]
;       [?perm :eacl/resource ?resource]
;       [?perm :eacl/allowed? ?allowed]
;       [?perm :eacl/action ?action]
;       (child-of ?user ?group)]
;     @conn
;     eacl/child-rules)))

;(deftest eacl-tests
;  (let [conn eacl/conn ;; eww
;        invoices (eacl/create-entity! conn {:eacl/ident "invoices"})
;        user (eacl/create-user! conn {:user/name "Petrus Theron"})]
;    (eacl/grant-access! conn {:eacl/user user
;                              :eacl/resource (:db/id invoices)
;                              ;:eacl/path "invoices/*"
;                              :eacl/action :read})
;    (log/debug "enum:" (eacl/enum-user-permissions @conn user))
;    (is (true? (eacl/can-user? @conn {:eacl/user     user
;                                      :eacl/resource (:db/id invoices)
;                                      ;:eacl/path     "invoices/*"      ;; hmm
;                                      ;:eacl/location "Cape Town"
;                                      :eacl/action   :read})))))


(enum-tests)
;(enum)

;(clojure.test/)
;(clojure.test/run-tests)