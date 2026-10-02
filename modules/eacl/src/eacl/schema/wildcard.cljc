(ns eacl.schema.wildcard
  "SpiceDB wildcard subjects (`relation viewer: user:*`).

  A Relation keeps one entity per subject type. Its wildcard branch, when
  declared, is recorded by two optional attributes beside the concrete
  branch's Caveat alternatives. Every `T:*` subject is one EACL-owned subject
  entity: the subject-type slot of each endpoint tuple keeps `user:*` apart
  from `group:*`, so the reserved public ID `*` resolves without knowing the
  type."
  ;; `object?` names a wildcard object here; ClojureScript's core predicate
  ;; of the same name is not used.
  (:refer-clojure :exclude [object?]))

(def object-id
  "The reserved public object ID of a wildcard subject."
  "*")

(def entity-id
  "The `:eacl/id` of the EACL-owned wildcard subject entity."
  "eacl.wildcard-subject")

(def lookup-ref [:eacl/id entity-id])

(def entity
  "Transaction data that upserts the wildcard subject entity."
  {:eacl/id entity-id})

(def unqualified-attribute
  "Present exactly when a Relation declares a `T:*` branch; true when a bare
  `T:*` subject is allowed."
  :eacl.relation/allows-unqualified-wildcard?)

(def caveats-attribute
  "Named Caveats allowed on a Relation's `T:* with caveat` branches."
  :eacl.relation/wildcard-caveats)

(def attributes #{unqualified-attribute caveats-attribute})

(defn object?
  "True for a public object whose ID is the reserved wildcard ID."
  [object]
  (and (map? object) (= object-id (:id object))))

(defn relation-allows?
  "True when a Relation entity or definition declares a wildcard branch."
  [relation]
  (contains? relation unqualified-attribute))

(defn schema-uses-wildcards?
  "True when any Relation of a candidate schema declares a wildcard branch."
  [relations]
  (boolean (some relation-allows? relations)))

(defn wildcard-not-allowed!
  "Rejects the reserved wildcard ID where SpiceDB rejects it: as a resource
  anywhere, and as the subject of a check or a resource lookup/count."
  [operation position object]
  (throw
   (ex-info
    (str "The wildcard object ID \"*\" is not allowed as the "
         (name position) " of " (name operation) ".")
    {:type :eacl/wildcard-not-allowed
     :eacl/error :eacl/wildcard-not-allowed
     :operation operation
     :position position
     :object (select-keys object [:type :id])})))

(defn reserved-object-id!
  "A concrete object whose external ID is the reserved wildcard ID cannot be
  rendered without being mistaken for the wildcard."
  [internal-id]
  (throw
   (ex-info
    "An object uses the reserved wildcard object ID \"*\"."
    {:type :eacl/reserved-object-id
     :eacl/error :eacl/reserved-object-id
     :object-id object-id
     :entity-id internal-id})))

(defn require-concrete!
  "Rejects a wildcard object in a position that only accepts a concrete
  object. Returns the object."
  [operation position object]
  (when (object? object)
    (wildcard-not-allowed! operation position object))
  object)
