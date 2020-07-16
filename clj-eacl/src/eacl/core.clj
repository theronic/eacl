(ns eacl.core                                               ;; consider eacl.auth.core
  (:require
    [taoensso.timbre :as log]
    ;[eacl.utils :refer [spy profile]]
    ;[clojure.core.async :refer [chan]]
    [clojure.spec.alpha :as s]
    [clj-time.core :as t]
    ;[bridge.auth.eacl.schema :as schema]
    [datahike.api :as d]                                    ;; how to make this data store pluggable?
    [datahike.db :as dh-db]
    ;[bridge.data.datahike :as d]
    [clojure.spec.test.alpha :as st]))
;(:import (bridge.auth.eacl AccessDeniedException)))

;; todo deploy for above

;[taoensso.carmine :as redis :refer (wcar)]))

;(def conn (d/create-conn schema/latest-schema))

;; Where is the 'how'?

;; Some implementation details:
;; All allow/deny permission entities pertaining without parent.
;; Have to be their own group/parent because it simplifies the queries.

;; Some schema ideas:
;; - user/action/resource
;; - group/action/resource
;; - user/group/action/resource

;(def child-rules
;  '[[(child-of ?e1 ?e2)
;     [?e1 :eacl/parent ?e2]]
;    [(child-of ?e1 ?e2)
;     [?e1 :eacl/parent ?t]
;     (child-of ?t ?e2)]])

(def child-rules
  '[[(child-of ?c ?p)
     (?c :eacl/parent ?p)]
    [(child-of ?c ?p)
     (?c1 :eacl/parent ?p)
     (child-of ?c ?c1)]])

;(def child-rules
;  "Recursive ancestor rule to support inheritance."
;  '[[(child-of ?c ?p)
;     (?c :eacl/parent ?p)]
;    [(child-of ?c ?p)
;     (?c1 :eacl/parent ?p)
;     (child-of ?c ?c1)]])
;; Role stuff:
;[(member-of ?member ?role)
; (?member :eacl/role ?role)]
;[(member-of ?member ?role)
; (?member1 :eacl/role ?role)
; (member-of ?member ?member1)]])

(defn coerce->id
  "Super gross. Why do we have this?"
  [ent-or-id]
  (if-let [eid (:db/id ent-or-id)]
    eid
    ent-or-id))

;; There is a class of "cutting math" you need to efficiently query across time.
;; Ties back to work with ropes / hitchhiker trees.

;; Naming: time window vs timespan? Is there another name for temporal/durative limits?
(s/def :eacl/timespan (s/keys :req [:eacl/start-date :eacl/end-date])) ;; todo inst type.

(s/def :eacl/when
  (s/or
    :event (s/keys :req [:event/key])
    :timespan :eacl/timespan
    ;; hmm
    ;; todo timestamp. [from until].
    :db/as-of any?))

(comment
  (s/conform :eacl/when {:as-of 123 :event/key 123})
  (s/conform :eacl/when {:eacl/start-date 123 :eacl/end-date 456})
  (s/conform :eacl/when {:event/key 123}))

;; do we need how?
(s/def :eacl/rule
  (s/keys
    :req [:eacl/who]
    ;; todo figure out all the combinations of who+where/what/why/how.
    :opt [:eacl/why :eacl/what :eacl/where :eacl/when :eacl/how]))

(comment)

;; todo unify
;; todo we need a way to check inputs, because specifying idents directly don't seem to work in Datahike.
;; Maybe it will work in

(defn rule-args->vec
  [allowed? {:as rule :eacl/keys [who what why when where how]}]
  ;; now what if these aren't present?
  ;; how to handle nil?
  [child-rules allowed? who what why when where how])

;; todo combine two functions below and return a tuple.
(defn conj->rule-args [initial {:as rule :eacl/keys [who what when where why how]}]
  (cond-> initial
    who (conj '?who)
    what (conj '?what)
    why (conj '?why)
    when (conj '?when)
    where (conj '?where)
    how (conj '?how)))

(defn conj->rule-arg-values
  [initial {:as rule :eacl/keys [who what when where why how]}]
  (cond-> initial
    who (conj who)
    what (conj what)
    why (conj why)
    when (conj when)
    where (conj where)
    how (conj how)))

(comment
  (def *db (-> (dh-db/empty-db {:eacl/ident     {:db/unique :db.unique/identity}
                                :eacl/parent    {:db/valueType :db.type/ref}
                                :eacl/who       {:db/valueType :db.type/ref}
                                :eacl/where     {:db/valueType :db.type/ref}
                                :invoice/number {
                                                 ;:db/valueType   :db.type/string
                                                 :db/unique      :db.unique/identity
                                                 :db/cardinality :db.cardinality/one}})
             (d/db-with (concat
                          [{:db/id       "role"
                            :eacl/parent "role"
                            :role/name   "Managers"}
                           {:db/id       "user"
                            :eacl/ident  :petrus
                            :user/name   "petrus"
                            :eacl/parent "role"}
                           {:db/id      "cpt"
                            :eacl/ident :cape-town
                            :store/name "Cape Town"}
                           {:db/id      "rule"
                            :eacl/what  :invoice/number
                            :eacl/where "cpt"
                            ;:eacl/who "user" ;"role"
                            :eacl/who   "role"}]
                          (for [x (range 1000)]
                            {:invoice/number (str "INV" x)
                             :eacl/where     "cpt"}))))
    ;(time (let [db (dh-db/empty-db {:eacl/ident     {:db/unique :db.unique/identity}
    ;                                :eacl/parent    {:db/valueType :db.type/ref}
    ;                                :eacl/who       {:db/valueType :db.type/ref}
    ;                                :eacl/where     {:db/valueType :db.type/ref}
    ;                                :invoice/number {
    ;                                                 ;:db/valueType   :db.type/string
    ;                                                 :db/unique      :db.unique/identity
    ;                                                 :db/cardinality :db.cardinality/one}})
    ;            db (d/db-with db (concat
    ;                               [{:db/id "role"
    ;                                 :eacl/parent "role"
    ;                                 :role/name "Managers"}
    ;                                {:db/id "user"
    ;                                 :eacl/ident :petrus
    ;                                 :user/name "petrus"
    ;                                 :eacl/parent "role"}
    ;                                {:db/id "cpt"
    ;                                 :eacl/ident :cape-town
    ;                                 :store/name "Cape Town"}
    ;                                {:db/id      "rule"
    ;                                 :eacl/what  :invoice/number
    ;                                 :eacl/where "cpt"
    ;                                 ;:eacl/who "user" ;"role"
    ;                                 :eacl/who   "role"}]
    ;                               (for [x (range 1000)]
    ;                                 {:invoice/number (str "INV" x)
    ;                                  :eacl/where "cpt"})))]
    (time (d/q '[:find (pull ?who [*])                      ;; (pull ?rule [* {:eacl/who [*]}])
                 :in $ % ?who ?what ?where
                 :where
                 [?rule :eacl/who ?parent]
                 [?rule :eacl/where ?where]
                 [?rule :eacl/what ?what]
                 [?who :eacl/parent ?parent]
                 (child-of ?who ?parent)]
            *db
            '[[(child-of ?e1 ?e2)
               [?e1 :eacl/parent ?e2]]
              [(child-of ?e1 ?e2)
               [?e1 :eacl/parent ?t]
               (child-of ?t ?e2)]]
            [:eacl/ident :petrus] :invoice/number [:eacl/ident :cape-town])))

  '[[(follow ?e1 ?e2)
     [?e1 :follow ?e2]]
    [(follow ?e1 ?e2)
     (follow ?e2 ?e1)]]

  (conj->rule-args '[$ % ?allow] #:eacl{:who  [:bridge/ident :reeco]
                                        :what [:bridge/ident :what]}))

;;[[(ubersymbol ?c ?p)
;  (?c :ml/parent ?p)]
; [(ubersymbol ?c ?p)
;  ;; we bind a child of the ancestor, instead of a parent of the descendant
;  (?c1 :ml/parent ?p)
;  (ubersymbol ?c ?c1)]]

(defn build-rule-query
  "Note: we use the :query and :args forms."
  [{:as demand :eacl/keys [who what when where why how]}]
  {:find '[(pull ?rule [*])]                                ;; why are we pulling this out?
   :in   (conj->rule-args '[$ % ?allow] demand)             ;; can we construct this also based on presence?
   ;; todo optimize order
   :where
   ;;[?rule :eacl/who ?parent]
   ;                       [?rule :eacl/where ?where]
   ;                       [?rule :eacl/what ?what]
   ;                       [?who :eacl/parent ?parent]

   ;'[[?rule :eacl/allowed? ?allowed]]
         (cond-> '[[?rule :eacl/allowed? ?allow]]           ;; hack
           ;[?who :eacl/parent ?parent]
           ;(child-of ?who ?role)]
           ;; pass in true/false directly?
           who (conj '[?rule :eacl/who ?who-parent])
           what (conj '[?rule :eacl/what ?what-parent])
           why (conj '[?rule :eacl/why ?why])
           when (conj '[?rule :eacl/when ?when])
           where (conj '[?rule :eacl/where ?where])
           how (conj '[?rule :eacl/how ?how])
           who (conj '[?who :eacl/parent ?who-parent])
           who (conj '(child-of ?who ?who-parent))
           what (conj '[?what :eacl/parent ?what-parent])
           what (conj '(child-of ?what ?what-parent)))})


;;    ;(child-of ?what ?parent)             ;; optional parent
;                       ;(child-of ?user ?group)]
;                  db child-rules false
;                  (or (coerce->id who) #{})
;                  (or (coerce->id what) #{})
;                  (or (coerce->id why) #{})
;                  when
;                  (or (coerce->id where) #{})                                      ;; needs more involved rules.
;                  (or (coerce->id how) #{}))

(defn build-big-query-map
  [db allowed? {:as rule :eacl/keys [who what when where why how]}]
  {:query (build-rule-query rule)
   :args  (conj->rule-arg-values [db child-rules allowed?] rule)})

(comment
  (d/q (build-big-query-map
         (d/empty-db) true
         #:eacl{:who   [:db/ident :product/name]            ;nil ;[:bridge/ident :me]
                :what  2                                    ; ;nil ;[:bridge/ident :thing]
                :where 3                                    ;#{3} ; [:bridge/ident :anywhere]
                :how   4})))                                ;#{4}}))); :read})))

;(defn find-rule [db {:as rule :eacl/keys [who what where why when who]}]
;  ;; todo cond->
;  (d/q {:query {:find  '[?rule]
;                :where (cond-> []
;                         who (conj '[?rule :eacl/who ?who])
;                         what (conj '[?rule :eacl/what ?what]
;                                [?rule :eacl/where ?where]
;                                [?rule :eacl/when ?when]
;                                [?rule :eacl/how ?how]
;                                [?rule :eacl/why ?why]))}}))

(defn can?
  "This is the core of EACL. It's not possible to do this in one query because of something-something non-linear 'disjunction' or 'cutting' math.
  Todo: support korks by checking if req is map. Maybe handled by arity?

  Think about how to make the two queries in parallel and then compare at end.

  Omitting when implies 'now.
  Omitting why implies 'for any reason'."
  [db {:as rule :eacl/keys [who what why when where how]}]
  ;; you'll get nullpointerexception if any of the inputs are nil
  ; (log/debug "Checking EACL for" access-req)
  ;; super gross and super slow to do two queries.
  ;; todo write as pure index access
  ;; todo try to write as Redis queries

  ;; todo: make where & why optional.

  ;; big TODO fix parents

  (log/debug "can? " (pr-str rule))
  (time
    (let [blocked (d/q (build-big-query-map db false rule))]
      ;(or (coerce->id reason) #{}))]
      (if (seq blocked)
        (do
          (log/debug "EACL demand " rule " Blocked by " (vec blocked))
          false)
        (let [allowed (d/q (build-big-query-map db true rule))]
          (if (seq allowed)                                 ;; why not return the allowed value or blocked value?
            (do
              (log/debug "EACL demand " rule " allowed by " (vec allowed))
              true)
            (do
              (log/debug "No matching rules for " rule)
              false)))))))

(s/fdef can?
  :args (s/cat :db dh-db/db?
               :demand :eacl/rule)
  :ret boolean?)

;; ok, we need rules for :eacl/role too.

(defn coerce->id [ent-or-id]
  (if-let [eid (:db/id ent-or-id)]
    eid
    ent-or-id))

(defn format-rules [[perm-id perm]])

;; todo: impl. enumeration API.
;; need a thing that composes and aligns query parameters.

(defn who-can?
  "Returns a set of :eacl/who entries matching a given rule."
  [db {:as rule :eacl/keys [who what why when where how]}]
  ;; should this be called allowed?
  ;; demand entities?
  (let [qry (assoc-in (build-big-query-map db true rule)
              [:query :find] '[(pull ?who [*])])]
    ;(log/debug "about to query: " qry)
    (into [] (d/q qry))))
;'[$ % ?allow ?who ?what ?why ?when ?where ?how]
;(into [] (d/q '[:find (pull $ ?rule [* {:eacl/who [*]
;                                        :eacl/what [*]
;                                        :eacl/why [*]
;                                        :eacl/when [*]
;                                        :eacl/where [*]}])
;                :in $ ?who
;                :where
;                [?rule :eacl/who ?who]
;                [?rule :eacl/allowed? true]]
;           db user-id)))

(defn enum-user-rules [db user-id]
  ;; should this be called allowed?
  ;; demand entities?
  (into [] (d/q '[:find (pull $ ?rule [* {:eacl/who   [*]
                                          :eacl/what  [*]
                                          :eacl/why   [*]
                                          :eacl/when  [*]
                                          :eacl/where [*]}])
                  :in $ ?who
                  :where
                  [?rule :eacl/who ?who]
                  [?rule :eacl/allowed? true]]
             db user-id)))

(comment

  ;(enum-user-permissions )

  (into [] (juxt [:a :e] [1 2]))
  (into {} (map vector [:a :e] [1 2]))

  ;(let [conn (d/create-conn schema/latest-schema)]
  ;  (d/transact! conn [{:db/id -1 :age 32}])
  ;  (d/q '[:find ?e
  ;         :where
  ;         [?e :age ?age]]
  ;    @conn))

  (can? @(d/create-conn schema/latest-schema) {
                                               ;:eacl/resource {:db/id 2}
                                               :eacl/who {:db/id 1}}))
;:eacl/path "123"
;:eactl/resource :invoices
;:eacl/action :read}))

(defn tx!
  "Only transacts non-nil."
  [conn tx-data & args]                                     ;; args is just tx-meta?
  {:pre [(seq tx-data)]}
  (let [filtered (vec (remove nil? tx-data))]
    ;(log/debug "filtered:" filtered)
    (if-not (empty? filtered)
      (apply d/transact conn filtered args))))

;(comment)
;(vec (vec (remove nil? [1 nil 3]))))

(defn create-user!
  "Principle?"
  [conn user]
  (let [rx (tx! conn
             [(merge user
                {:db/id       -1
                 :eacl/parent -1                            ;; own parent
                 :eacl/user   -1                            ;; eww
                 :eacl/group  -1})])]
    (d/entity (:db-after rx) (get-in rx [:tempids -1]))))

(defn create-role!
  "Where role usually has :eacl.role/name or :eacl/ident."
  [conn role]
  (let [rx (tx! conn
             [(merge role
                {:db/id       "role"
                 :eacl/role   "role"                        ;; hmm
                 :eacl/parent "role"})])]                   ;; has to be own parent due to impl. detail (simplifies queries)
    ;:eacl/group  "role"})])]
    (d/entity (:db-after rx) (get-in rx [:tempids "role"]))))

(defn remove-nils
  "Takes a map or vector form and filters out nil values."
  [coll-or-map]
  (cond
    (vector? coll-or-map) (into [] (filter (complement nil?) coll-or-map))
    (map? coll-or-map) (into {} (filter (comp not nil? second) coll-or-map))))

(defn tx-rule
  [allowed? {:as rule :eacl/keys [role who what why when where how]}]
  (remove-nils
    {:db/id         "rule"
     :eacl/parent   "rule"                                  ;; NB implementation detail.
     :eacl/allowed? allowed?                                ;; consider special values :eacl/allow and :eacl/deny vs true and false. Performance, though?
     :eacl/role     (coerce->id role)                       ;; still contentious. vs group.
     :eacl/who      (coerce->id who)
     :eacl/what     (coerce->id what)
     :eacl/why      why
     :eacl/when     (coerce->id when)                       ;; how to handle when? as-of?
     :eacl/where    (coerce->id where)
     :eacl/how      (coerce->id how)}))

(s/fdef tx-rule
  :args (s/cat :allowed? boolean?
               :rule :eacl/rule)
  :ret map?)

;; For readme: the core of EACL is about fitting common access control operations into the six clauses: who what when where why & how.
;; Also for readme: the most complicated part of the EACL grammar is when to use why vs what vs how. Here is how I use them. Examples follow.
;; todo: need rule retraction queries

;; Think about the difference between a role and a group.

(defn create-group!
  "Where group usually has :eacl.group/name"
  [conn group]
  (let [rx (tx! conn
             [(merge group
                {:db/id       "group"
                 :eacl/parent "group"                       ;; own parent
                 :eacl/group  "group"})])]
    (d/entity (:db-after rx) (get-in rx [:tempids "group"]))))

(defn add-user-to-group! [conn user group]
  (tx! conn [[:db/add (coerce->id user) :eacl/group (coerce->id group)]]))

(defn remove-user-from-group! [conn user group]
  (tx! conn [[:db/retract (coerce->id user) :eacl/group (coerce->id group)]]))

(defn delete-group! [conn group]
  (tx! conn [[:db/retractEntity (coerce->id group)]]))

(defn create-entity! [conn entity]
  (let [rx (tx! conn
             [(merge
                {:eacl/parent -1}                           ;; good idea? not sure.
                entity
                {:db/id       -1
                 :eacl/entity -1})])]
    (d/entity (:db-after rx) (get-in rx [:tempids -1]))))

(defn create-rule! [conn allowed? {:as rule :eacl/keys [who what why when where how]}]
  ;; todo: consider not removing nil values for who and what.
  (d/transact conn [(tx-rule allowed? rule)]))

(comment)


;(let [conn (d/create-mem-conn schema/v1-eacl-schema)
;      {:as demand :eacl/keys [who what why when where how]}
;      {:eacl/who :petrus}
;      tx   (d/remove-nils
;             {:db/id         -1
;              :eacl/allowed? true                                  ;allowed?
;              :eacl/who      (coerce->id who)
;              :eacl/what     (coerce->id what)
;              :eacl/why      nil                                   ;why
;              ;:eacl/when (coerce->id when) ;; how to handle when? as-of?
;              :eacl/where    (coerce->id where)
;              :eacl/how      (coerce->id how)})]
;  (d/transact conn [{:db/id -1 :db/ident :petrus}
;                    tx])))

(s/fdef create-rule!
  :args (s/cat :conn any?                                   ;; tood need a type for d/conn?
               :allowed? boolean?
               :rule :eacl/rule))

(defn grant!
  [conn {:as rule :eacl/keys [who what why when where how]}]
  {:pre [conn]}
  ;; todo: should we retract directly conflicting deny rules? Or should we warn?
  ;; can we get a special value for "true" allowed.
  ;; todo if a deny rule exists that directly matches this rule, change allowed? to true.
  (create-rule! conn true rule))

;; todo figure out right naming. probably allow! and deny!

(def allow! grant!)

;; #idea Could we call it an access control grammar?

(defn assign-role-at!
  "Just sugar for grant! + :eacl/role."
  [conn who role where]                                     ;user-id]
  (grant! conn {:eacl/who   who
                :eacl/role  role
                :eacl/where where}))

(defn unassign-role-at! [conn role-id user-id]
  (d/transact conn
    [[:db/retract user-id :eacl/role role-id]]))


(defn can!
  "Like can? but throws AccessDeniedException if rule allowed."
  [db rule]
  (when-not (can? db rule)
    (throw (Exception. "EACL demand not granted: " (pr-str rule)))))
;(throw (AccessDeniedException. "EACL demand not granted: " (pr-str rule)))))

;; how is subscribe different from grant?
;; Subscribe should emit an event when certain conditions are met, e.g. order created. Needs differential dataflow.
;; needs to sit on the tx-log and listen for certain changes.

(defn subscribe!
  "Convenience wrapper for grant! against who + when + where."
  [conn who when-event-key where why]
  (grant! conn #:eacl{:who   who                            ;; inheritance
                      :how   :read                          ;; vs. view vs. see?
                      :what  '[*]                           ;; all aspects? what about children of the store?
                      :where where                          ; [:bridge/ident :test-store]
                      :when  {:event/key when-event-key}    ;:event/new-order} ;; better name
                      :why   :email/notification}))

(defn enum-event-triggers                                   ;; enum-subscriptions?
  ;; todo split query
  ;; untested.
  "Returns a lazy seq of rules triggered by an event."
  [db event-key]
  (d/q {:query '[:find (pull ?rule [*])
                 :in $ ?event                               ;; todo children?
                 :where
                 [?rule :eacl/who ?who]
                 [?rule :eacl/when ?when]
                 [?rule :eacl/trigger ?trigger]
                 [?trigger :event/key ?event]]
        :args  [db event-key]}))

;; how about a macro version of this?

(defn deny! [conn {:as demand :eacl/keys [who what why when where how]}]
  (create-rule! conn false demand))

;(s/fdef grant!
;  :args (s/cat :conn d/conn
;               :demand :eacl/demand))
;
;(s/fdef deny!
;  :args (s/cat :conn d/conn
;               :demand :eacl/demand))

;(st/instrument)

;(comment
;  (let [conn (d/create-mem-conn [{:db/ident       :some-bool
;                                  :db/valueType   :db.type/boolean
;                                  :db/cardinality :db.cardinality/many}])]
;    (d/transact conn [{:db/id     -1
;                       :some-bool true}])
;    (d/q '{:find  [?e]
;           :in    [$ % ?x]
;           :where [[?e :some-bool false]]}
;      (d/db conn)
;      nil))
;
;
;  (let [conn   (d/create-mem-conn schema/v1-eacl-schema)
;        _      (d/transact conn
;                 [{:db/id -1 :db/ident :petrus}
;                  {:db/id -2 :db/ident :invoices}])
;        demand {:eacl/who  [:db/ident :petrus]
;                :eacl/how  :read
;                :eacl/what [:db/ident :invoices]}]
;    ;:eacl/when 'hi}]
;    (grant! conn demand)
;    ;(can? (d/db conn) demand)
;    (prn (d/datoms (d/db conn) :eavt))
;    (prn (build-rule-query demand))
;    (d/q (build-rule-query demand) (d/db conn) child-rules false))
;  ;(d/transact conn [{:eacl}])
;  ;(deny!)
;  ;(qry-blocked {:eacl/who  [:db/ident :petrus]
;  ;              :eacl/when 'hi}))
;
;  (st/unstrument)
;  (st/instrument)
;  (create-rule! nil)
;  ;datahike.connector/con
;
;  (st/instrument)
;  ;(d/transact conn [{:db/ident       :user/ident
;  ;                   :db/valueType   :db.type/string
;  ;                   :db/cardinality :db.cardinality/one
;  ;                   :db/unique      :db.unique/identity}])
;  @*1
;
;  (create-group! conn)
;
;  (d/transact conn [{:db/id             -1
;                     :user/ident        "petrus"
;                     :user/system-role  "admin"
;                     :user/active?      true
;                     :telegram/username "PetrusTheron"}])
;
;  ;(d/transact conn [{:db/id
;  ;                   :db/ident :bridge.auth.group/admin
;  ;                   :group/name "Admins"}])
;
;  ;'(can 'petrus :read)
;
;  (can? (d/db conn)
;    {:eacl/user     [:user/ident "petrus"]
;     :eacl/action   :change
;     :eacl/resource [:telegram/store-token "BridgeChatBot"]})
;
;  (can! conn
;    [{:eacl/user     [:user/ident "petrus"]
;      :eacl/action   :change
;      :eacl/resource [:telegram/store-token "BridgeChatBot"]}])
;
;  (grant! conn
;    [{:eacl/user     [:user/ident "petrus"]
;      :eacl/action   :change
;      :eacl/resource [:telegram/store-token "BridgeChatBot"]}])
;
;  @*1
;
;  (can? (d/db conn)
;    {:eacl/user     [:user/ident "petrus"]
;     :eacl/action   "read"
;     :eacl/resource [:user/ident "petrus"]})
;
;  (let [conn (d/create-conn schema/latest-schema)
;        user (create-user! conn {:user/name "Petrus Theron"})]
;    ;(grant-access! conn {:eacl/user user
;    ;                     :eacl/path "invoices/*"
;    ;                     :eacl/action :read
;    ;                     :eacl/location "Cape Town"})
;    (can? @conn {:eacl/user     user
;                 :eacl/reason   "hi"
;                 :eacl/as-of    (t/now)
;                 :eacl/path     "invoices/*"                ;; hmm
;                 :eacl/location "Cape Town"
;                 :eacl/action   :read})))
