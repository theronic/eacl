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


(def child-rules
  '[[(child-of ?c ?p)
     (?c :eacl/parent ?p)]
    [(child-of ?c ?p)
     (?c1 :eacl/parent ?p)
     (child-of ?c ?c1)]])

(defn coerce->id
  "Super gross. Why do we have this?"
  [ent-or-id]
  (if-let [eid (:db/id ent-or-id)]
    eid
    ent-or-id))

;; Naming: time window vs timespan? Is there another name for temporal/durative limits?
(s/def :eacl/timespan (s/keys :req [:eacl/start-date :eacl/end-date])) ;; todo inst type.

(s/def :eacl/when
  (s/or
    :event (s/keys :req [:event/key])
    :timespan :eacl/timespan
    ;; hmm
    ;; todo timestamp. [from until].
    :db/as-of any?))

(s/def :eacl/rule
  (s/keys
    :req [:eacl/who]
    ;; todo figure out all the combinations of who+where/what/why/how.
    :opt [:eacl/why :eacl/what :eacl/where :eacl/when :eacl/how]))

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

(defn build-rule-query
  "Note: we use the :query and :args forms."
  [{:as demand :eacl/keys [who what when where why how]}]
  {:find '[(pull ?rule [*])]                                ;; why are we pulling this out?
   :in   (conj->rule-args '[$ % ?allow] demand)             ;; can we construct this also based on presence?
   ;; todo optimize order
   :where
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

(defn build-big-query-map
  [db allowed? {:as rule :eacl/keys [who what when where why how]}]
  {:query (build-rule-query rule)
   :args  (conj->rule-arg-values [db child-rules allowed?] rule)})

(defn can?
  "This is the core of EACL. It's not possible to do this in one query because of something-something non-linear 'disjunction' or 'cutting' math.
  Todo: support korks by checking if req is map. Maybe handled by arity?

  Think about how to make the two queries in parallel and then compare at end.

  Omitting when implies 'now.
  Omitting why implies 'for any reason'."
  [db {:as rule :eacl/keys [who what why when where how]}]
  (log/debug "can? " (pr-str rule))
  (time
    (let [blocked (d/q (build-big-query-map db false rule))]
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

(defn can!
  "Like can? but throws AccessDeniedException if false."
  [db rule]
  (when-not (can? db rule)
    (throw (Exception. "EACL demand not granted: " (pr-str rule)))))
;(throw (AccessDeniedException. "EACL demand not granted: " (pr-str rule)))))

(s/fdef can?
  :args (s/cat :db dh-db/db?
               :demand :eacl/rule)
  :ret boolean?)

;; todo: rules for :eacl/role.

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

(defn tx!
  "Only transacts non-nil."
  [conn tx-data & args]                                     ;; args is just tx-meta?
  {:pre [(seq tx-data)]}
  (let [filtered (vec (remove nil? tx-data))]
    ;(log/debug "filtered:" filtered)
    (if-not (empty? filtered)
      (apply d/transact conn filtered args))))

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
