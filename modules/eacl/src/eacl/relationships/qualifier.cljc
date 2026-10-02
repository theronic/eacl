(ns eacl.relationships.qualifier
  "Sparse immutable qualifier format. This namespace does not activate serving."
  (:require [clojure.set :as set]
            [eacl.caveats.values :as values]))

(def format-version 1)
(def marker-attribute :eacl.relationship-qualifier/format-version)
(def caveat-attribute :eacl.relationship-qualifier/caveat)
(def context-attribute :eacl.relationship-qualifier/caveat-context)
(def expiration-attribute :eacl.relationship-qualifier/valid-until-ms)
(def attributes #{marker-attribute caveat-attribute context-attribute expiration-attribute})
(def ^:private input-keys #{:caveat :caveat-context :valid-until-ms})

(defn error! [reason]
  (throw (ex-info "Invalid EACL Relationship qualifier."
                  {:type :eacl.qualifier/invalid :eacl/error :eacl.qualifier/invalid :reason reason})))

(defn concrete-eid? [eid]
  (and #?(:clj (integer? eid) :cljs (and (number? eid) (js/Number.isSafeInteger eid)))
       (pos? eid)))

(def relation-many-attributes
  "The Relation attributes with cardinality many: each branch's Caveat refs."
  #{:eacl.relation/caveats :eacl.relation/wildcard-caveats})

(defn- branch-allowance
  [refs refs? plain]
  (when (and refs? (not (and (set? refs) (seq refs) (every? concrete-eid? refs))))
    (error! :malformed-relation-allowance))
  (cond-> (or refs #{}) plain (conj nil)))

(defn relation-branch-allowances
  "Native Caveat alternatives for one selected Relation by subject form:
   `{:concrete #{caveat-eid nil?} :wildcard #{caveat-eid nil?}}`. Legacy plain
   Relations have exactly the concrete nil alternative. Qualified branches
   carry both the native ref set and the explicit plain-branch bit. A
   Relation whose only branch is `T:*` stores
   `:eacl.relation/allows-unqualified? false` without concrete refs."
  [relation]
  (let [refs (:eacl.relation/caveats relation)
        refs? (contains? relation :eacl.relation/caveats)
        plain? (contains? relation :eacl.relation/allows-unqualified?)
        plain (if plain? (:eacl.relation/allows-unqualified? relation) true)
        wildcard-refs (:eacl.relation/wildcard-caveats relation)
        wildcard-refs? (contains? relation :eacl.relation/wildcard-caveats)
        wildcard? (contains? relation :eacl.relation/allows-unqualified-wildcard?)
        wildcard-plain (:eacl.relation/allows-unqualified-wildcard? relation)]
    (when (or (not (boolean? plain))
              (and refs? (not plain?))
              (and plain? (not refs?) (not (and (false? plain) wildcard?)))
              (and wildcard-refs? (not wildcard?))
              (and wildcard? (not (boolean? wildcard-plain)))
              (and wildcard? (false? wildcard-plain) (not wildcard-refs?)))
      (error! :malformed-relation-allowance))
    {:concrete (branch-allowance refs refs? plain)
     :wildcard (if wildcard?
                 (branch-allowance wildcard-refs wildcard-refs? wildcard-plain)
                 #{})}))

(defn relation-allowance
  "Every Caveat alternative of one selected Relation, across its concrete and
   wildcard branches. Reads check this union; writes and schema replacement
   check the branch named by the subject form."
  [relation]
  (let [{:keys [concrete wildcard]} (relation-branch-allowances relation)]
    (into concrete wildcard)))

(defn normalize
  "Normalizes resolved semantic input; nil and an empty map allocate nothing.
  Parameters belong to the selected named Caveat, and are checked by the caller
  against the selected Relation allowance before publication."
  ([input] (normalize input []))
  ([input parameters]
   (when-not (or (nil? input) (map? input)) (error! :qualifier-shape))
   (when (seq (set/difference (set (keys input)) input-keys)) (error! :qualifier-unknown-field))
   (let [{:keys [caveat caveat-context valid-until-ms]} input]
     (when (and (contains? input :caveat-context) (nil? caveat)) (error! :context-without-caveat))
     (when (and (some? caveat) (not (concrete-eid? caveat))) (error! :qualifier-ref))
     (when (and (some? caveat-context) (not (map? caveat-context))) (error! :qualifier-context))
     (when (and (some? valid-until-ms) (not (values/valid-time? valid-until-ms))) (error! :qualifier-time))
     (let [context (when (some? caveat-context) (values/normalize-context parameters caveat-context))
           result (cond-> {}
                    (some? caveat) (assoc :caveat caveat)
                    (seq context) (assoc :caveat-context context)
                    (some? valid-until-ms) (assoc :valid-until-ms valid-until-ms))]
       (when (seq result) result)))))

(defn entity-data
  "Emits only sparse qualifier facts. The eid may be a native top-level tempid;
  a backend must certify resolution before using it inside an endpoint tuple."
  [eid input parameters]
  (when-let [{:keys [caveat caveat-context valid-until-ms]} (normalize input parameters)]
    (cond-> {:db/id eid marker-attribute format-version}
      (some? caveat) (assoc caveat-attribute caveat)
      (seq caveat-context) (assoc context-attribute (values/encode-context parameters caveat-context))
      (some? valid-until-ms) (assoc expiration-attribute valid-until-ms))))

(defn decode
  "Strictly decodes a present entity with concrete refs. A missing entity is a
  fault; only a nil endpoint component selects the unconditional fast path."
  [entity parameters]
  (when-not (and (map? entity) (seq entity)) (error! :missing-qualifier))
  (when (and (contains? entity :db/id) (not (concrete-eid? (:db/id entity)))) (error! :qualifier-ref))
  (when (seq (set/difference (set (keys entity)) (conj attributes :db/id))) (error! :qualifier-unknown-field))
  (when-not (= format-version (get entity marker-attribute)) (error! :qualifier-format))
  (let [context (when (contains? entity context-attribute)
                  (values/decode-context parameters (get entity context-attribute)))
        input (cond-> {}
                (contains? entity caveat-attribute) (assoc :caveat (get entity caveat-attribute))
                (contains? entity expiration-attribute) (assoc :valid-until-ms (get entity expiration-attribute))
                (contains? entity context-attribute) (assoc :caveat-context context))
        normalized (normalize input parameters)
        canonical (cond-> {marker-attribute format-version}
                    (some? (:caveat normalized)) (assoc caveat-attribute (:caveat normalized))
                    (seq (:caveat-context normalized)) (assoc context-attribute (get entity context-attribute))
                    (some? (:valid-until-ms normalized)) (assoc expiration-attribute (:valid-until-ms normalized)))]
    (when-not normalized (error! :empty-qualifier))
    (when (and (contains? entity context-attribute) (empty? context)) (error! :nonsparse-context))
    ;; decode-context already checked the original payload's canonical bytes
    ;; and types. Preserve those bytes when checking sparse field presence;
    ;; rebuilding an entity would normalize and encode the same context again.
    (when-not (= (dissoc entity :db/id) canonical)
      (error! :noncanonical-qualifier))
    normalized))
