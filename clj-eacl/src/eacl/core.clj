(ns eacl.core
  (:require
    [taoensso.timbre :as log]
    [eacl.utils :refer [spy profile]]
    [clojure.core.async :refer [chan]]
    [clj-time.core :as t]
    [datascript.core :as d]
    [eacl.data :as data]
    [taoensso.carmine :as redis :refer (wcar)]))

(def schema
  {:eacl/ident       {:db/unique :db.unique/identity}
   :eacl/path        {:db/unique :db.unique/identity}       ;; hmm. not identity?
   :eacl/entity      {:db/valueType :db.type/ref
                      :db/index     true}
   :eacl/group       {:db/valueType   :db.type/ref
                      :db/cardinality :db.cardinality/many
                      :db/indxe       true}
   :eacl/role        {:db/valueType   :db.type/ref
                      :db/cardinality :db.cardinality/many
                      :db/index       true}
   :eacl/allowed?    {:db/index true}                       ;; hmm? access code? vs :eacl/permission?
   :eacl/location    {:db/valueType :db.type/ref
                      :db/index     true}
   :eacl/user        {:db/valueType :db.type/ref
                      :db/index     true}
   :eacl/resource    {:db/valueType :db.type/ref
                      :db/index     true}
   :eacl/action      {:db/cardinality :db.cardinality/many
                      :db/index       true}
   :eacl/parent      {:db/valueType :db.type/ref
                      :db/index     true}
   :eacl/access-time {:db/index true}})

(def conn (d/create-conn schema))

;:eacl/allowed-users {:db/cardinality :db.cardinality/many
;                     :db/valueType :db.type/ref}})

;export interface AccessRequest {
;                                user?:   string, // the "who"
;                                         resource?: string, // the "what" . should resource be location based?
;                                action?: string, // the "how"
;                                         reason?: string, // the "why"
;                                when?:   Date, // defaults to now}
;

(def child-rules
  '[[(child-of ?c ?p)
     (?c :eacl/parent ?p)]
    [(child-of ?c ?p)
     (?c1 :eacl/parent ?p)
     (child-of ?c ?c1)]])

;function can(auth_req: AccessRequest): boolean {
;	let db = ds.db(conn);
;
;	let qry =
;		`[:find ?user ?group
;		 :in $ % ?user ?action ?resource ?location
;		 :where
;		 [?group :eacl/permission ?perm]
;		 [?perm :eacl/resource ?resource]
;		 [?perm :eacl/action ?action]
;		 (child-of ?user ?group)
;		 ]`;
;
;	let results = ds.q(
;		qry,
;		db,
;		child_rules,
;		auth_req.user,
;		auth_req.action,
;		auth_req.resource);
;
;	client.get('my_key', x => console.log);
;	console.log(client.get('my_key'));
;	return false // not allowed!
;}
;
;function demand(req: AccessRequest) {
;	if (!can(req)) {
;		throw "Access Denied" // configure message and level of detail
;	}
;}

(defn coerce->id [ent-or-id]
  (if-let [eid (:db/id ent-or-id)]
    eid
    ent-or-id))
;(if (datascript.impl.entity/entity? ent-or-id)
;  (:db/id ent-or-id)
;  ent-or-id))

(defn format-permission [[perm-id perm]])

(defn can? [db {:as req :eacl/keys [as-of user action resource location reason]}]
  ;; you'll get nullpointerexception if any of the inputs are nil
  ;(log/debug "Checking EACL for" access-req)

  ;; super gross and super slow to do two queries.

  (let [blocked (data/q '[:find ?block
                          :in $ % ?as-of ?user ?action ?resource ?location ?reason
                          :where
                          [?block :eacl/group ?group]
                          [?block :eacl/resource ?resource-group]
                          [?block :eacl/action ?action]
                          [?block :eacl/allowed? false]     ;; vs. :eacl/blocked? true
                          (child-of ?resource ?resource-group)
                          (child-of ?user ?group)]
                        db child-rules
                        as-of
                        (or (coerce->id user) #{})
                        (or (coerce->id action) #{})
                        (or (coerce->id resource) #{})
                        (or (coerce->id location) #{})
                        (or (coerce->id reason) #{}))]
    (if (seq blocked)
      false
      (let [allowed (data/q '[:find
                              (pull ?allow [*])
                              (pull ?user [*])
                              ?action
                              (pull ?resource [*])
                              (pull ?group [*])
                              ;(pull ?location [*])
                              :in $ % ?as-of ?user ?action ?resource ?location ?reason
                              :where
                              [?allow :eacl/group ?group]   ;; not sure
                              [?allow :eacl/resource ?resource]
                              [?allow :eacl/action ?action]
                              [?allow :eacl/allowed? true]
                              (child-of ?user ?group)]

                            ;[?block :eacl/group ?group1]
                            ;[?block :eacl/resource ?resource]
                            ;[?block :eacl/action ?action]
                            ;(not [?block :eacl/allowed? false]) ;; /blocked?
                            ;(child-of ?user ?group1)]
                            db child-rules
                            as-of
                            (or (coerce->id user) #{})
                            (or (coerce->id action) #{})
                            (or (coerce->id resource) #{})
                            (or (coerce->id location) #{})
                            (or (coerce->id reason) #{}))]
        (if (seq allowed)
          true
          false)))))
;(log/debug "EACL " req " ACL result:" (seq res))
;;res
;(if (seq res) true false)))))
;(if (seq res) true false)))

(defn enum-user-permissions [db user-id]
  ;; demand entities?
  (into [] (data/q '[:find (pull $ ?perm [*])
                     :in $ ?user
                     :where
                     [?perm :eacl/user ?user]
                     [?perm :eacl/allowed? true]]
                   db user-id)))

(comment

  ;(enum-user-permissions )

  (into [] (juxt [:a :e] [1 2]))
  (into {} (map vector [:a :e] [1 2]))

  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:db/id -1 :age 32}])
    (data/q '[:find ?e
              :where
              [?e :age ?age]]
            @conn))

  (can? @(d/create-conn schema) {
                                 ;:eacl/resource {:db/id 2}
                                 :eacl/user {:db/id 1}}))
;:eacl/path "123"
;:eactl/resource :invoices
;:eacl/action :read}))

(defn tx!
  "Only transacts non-nil."
  [conn tx-data & args]                                     ;; args is just tx-meta?
  (let [filtered (seq (remove nil? tx-data))]
    ;(log/debug "filtered:" filtered)
    (if-not (empty? filtered)
      (apply d/transact! conn filtered args))))

(defn create-user! [conn user]
  (let [rx (tx! conn
                [(merge user
                        {:db/id       -1
                         :eacl/parent -1                    ;; own parent
                         :eacl/user   -1                    ;; eww
                         :eacl/group  -1})])]
    (d/entity (:db-after rx) (get-in rx [:tempids -1]))))

(defn create-group! [conn group]
  (let [rx (tx! conn
                [(merge group
                        {:db/id       -1
                         :eacl/parent -1                    ;; own parent
                         :eacl/group  -1})])]
    (d/entity (:db-after rx) (get-in rx [:tempids -1]))))

(defn add-user-to-group! [conn user group]
  (tx! conn [[:db/add (coerce->id user) :eacl/group (coerce->id group)]]))

(defn remove-user-from-group! [conn user group]
  (tx! conn [[:db/retract (coerce->id user) :eacl/group (coerce->id group)]]))

(defn delete-group! [conn group]
  (tx! conn [[:db/retractEntity (coerce->id group)]]))

(defn create-entity! [conn entity]
  (let [rx (tx! conn
                [(merge
                   {:eacl/parent -1}                        ;; good idea? not sure.
                   entity
                   {:db/id       -1
                    :eacl/entity -1})])]
    (d/entity (:db-after rx) (get-in rx [:tempids -1]))))

(defn grant! [conn {:as req :eacl/keys [user group action resource location]}]
  (tx! conn [[:db/add -1 :eacl/allowed? true]               ;; hmm
             (when user [:db/add -1 :eacl/user (coerce->id user)])
             (when user [:db/add -1 :eacl/group (coerce->id user)])
             ;(when group [:db/add -1 :eacl/group (coerce->id group)])
             (when action [:db/add -1 :eacl/action action])
             (when resource [:db/add -1 :eacl/resource (coerce->id resource)])
             (when location [:db/add -1 :eacl/location (coerce->id location)])]))

(defn deny! [conn {:as req :eacl/keys [user group action resource location]}]
  ;; first also have to check if access, then revoke that.
  (tx! conn [[:db/add -1 :eacl/allowed? false]
             (when user [:db/add -1 :eacl/user (coerce->id user)])
             (when user [:db/add -1 :eacl/group (coerce->id user)])
             ;(when group [:db/add -1 :eacl/group (coerce->id group)])
             (when action [:db/add -1 :eacl/action action])
             (when resource [:db/add -1 :eacl/resource (coerce->id resource)])
             (when location [:db/add -1 :eacl/location (coerce->id location)])]))

(comment
  (let [conn (d/create-conn schema)
        user (create-user! conn {:user/name "Petrus Theron"})]
    ;(grant-access! conn {:eacl/user user
    ;                     :eacl/path "invoices/*"
    ;                     :eacl/action :read
    ;                     :eacl/location "Cape Town"})
    (can? @conn {:eacl/user     user
                 :eacl/reason   "hi"
                 :eacl/as-of    (t/now)
                 :eacl/path     "invoices/*"                ;; hmm
                 :eacl/location "Cape Town"
                 :eacl/action   :read})))
