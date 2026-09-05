(ns eacl.relationships.endpoint-pair
  "Pure, backend-neutral representation of EACL's two endpoint relationship
  halves. Database adapters own index access and transaction semantics; this
  namespace only owns the value shape and its symmetry."
  (:require [eacl.relationships.storage :as storage]))

(def value-arity storage/value-arity)

(def constructor-form
  "Portable constructor embedded in Datomic transaction functions. The
  transactor need not load EACL namespaces to use the same fixed storage ABI."
  '(fn [owner-type relation-eid endpoint-type endpoint-eid qualifier-eid]
     [owner-type relation-eid endpoint-type endpoint-eid qualifier-eid]))

(defn forward-value
  ([subject-type relation-eid resource-type resource-eid]
   (forward-value subject-type relation-eid resource-type resource-eid nil))
  ([subject-type relation-eid resource-type resource-eid qualifier-eid]
   [subject-type relation-eid resource-type resource-eid qualifier-eid]))

(defn reverse-value
  ([resource-type relation-eid subject-type subject-eid]
   (reverse-value resource-type relation-eid subject-type subject-eid nil))
  ([resource-type relation-eid subject-type subject-eid qualifier-eid]
   [resource-type relation-eid subject-type subject-eid qualifier-eid]))

(defn identity-prefix
  "The qualifier-independent identity of one endpoint value (owner separate)."
  [value]
  (subvec value 0 storage/identity-arity))

(defn seek-bound
  "Full-arity inclusive bound for an opposite endpoint. Qualifier refs sort
  after nil; descending bounds must include the whole identity group."
  [prefix endpoint-eid direction maximum-eid]
  (conj prefix
        (or endpoint-eid (if (= :desc direction) maximum-eid 0))
        (when (= :desc direction) maximum-eid)))

(defn retractions
  "Both physical retractions for one logical relationship."
  ([subject-type subject-eid relation-eid resource-type resource-eid]
   (retractions subject-type subject-eid relation-eid resource-type resource-eid nil))
  ([subject-type subject-eid relation-eid resource-type resource-eid qualifier-eid]
   [[:db/retract subject-eid storage/forward-attribute
     (forward-value subject-type relation-eid resource-type resource-eid qualifier-eid)]
    [:db/retract resource-eid storage/reverse-attribute
     (reverse-value resource-type relation-eid subject-type subject-eid qualifier-eid)]]))

(defn endpoint-value?
  "True for a stored endpoint value with the expected heterogeneous shape.
  Eids are non-negative integers in committed Datomic, Datahike, and
  DataScript database values."
  [value]
  (and (vector? value)
       (= value-arity (count value))
       (keyword? (nth value 0))
       (nat-int? (nth value 1))
       (keyword? (nth value 2))
       (nat-int? (nth value 3))
       (or (nil? (nth value 4)) (nat-int? (nth value 4)))))

(defn assert-supported!
  "Checks a value at a serving boundary; integrity and migration decoders may
  inspect structurally valid future qualifiers without authorizing them. The
  explicit compact-scan mode exposes refs for qualification, never membership."
  ([value] (assert-supported! value false))
  ([value include-qualifier?]
  (when-not (endpoint-value? value)
    (throw (ex-info "Malformed EACL Relationship endpoint value."
                    {:type :eacl/invalid-relationship-storage
                     :eacl/error :eacl/invalid-relationship-storage
                     :value value})))
  (when (and (some? (nth value 4)) (not (true? include-qualifier?)))
    (throw (ex-info "Relationship qualifiers are not enabled in this release."
                    {:type :eacl/unsupported-qualifier
                     :eacl/error :eacl/unsupported-qualifier
                     :qualifier-eid (nth value 4)})))
  value))

(defn checked-datoms
  "Validates one ordered stream before publishing each row. A single-row
  lookahead detects competing qualifier variants before either can authorize."
  ([datoms] (checked-datoms datoms false))
  ([datoms include-qualifier?]
  (lazy-seq
   (when-let [rows (seq datoms)]
     (let [row (first rows)
           next-row (second rows)
           value (:v row)]
       (when (and next-row (= (:e row) (:e next-row))
                  (endpoint-value? value) (endpoint-value? (:v next-row))
                  (= (identity-prefix value) (identity-prefix (:v next-row))))
         (throw (ex-info "Duplicate logical Relationship identity."
                         {:type :eacl/invalid-relationship-storage
                          :eacl/error :eacl/invalid-relationship-storage
                          :reason :duplicate-identity
                          :endpoint-eid (:e row)
                          :identity (identity-prefix value)})))
       (assert-supported! value include-qualifier?)
       (cons row (checked-datoms (rest rows) include-qualifier?)))))))

(defn valid-prefix?
  "True for the complete typed three-component endpoint index prefix."
  [prefix]
  (and (vector? prefix)
       (= 3 (count prefix))
       (keyword? (nth prefix 0))
       (nat-int? (nth prefix 1))
       (keyword? (nth prefix 2))))

(defn value-prefix?
  "Whether a valid endpoint value begins with `prefix`. Oversized prefixes and
  malformed stored values never match. Compares component-wise, so a scan
  pays no allocation per datom."
  [value prefix]
  (let [n (count prefix)]
    (and (endpoint-value? value)
         (<= n value-arity)
         (loop [i 0]
           (or (== i n)
               (and (= (nth prefix i) (nth value i))
                    (recur (inc i))))))))

(defn decode-forward
  [subject-eid value]
  (when (endpoint-value? value)
    (let [[subject-type relation-eid resource-type resource-eid qualifier-eid] value]
      {:subject-type subject-type
       :subject-eid subject-eid
       :relation-eid relation-eid
       :resource-type resource-type
       :resource-eid resource-eid
       :qualifier-eid qualifier-eid})))

(defn decode-reverse
  [resource-eid value]
  (when (endpoint-value? value)
    (let [[resource-type relation-eid subject-type subject-eid qualifier-eid] value]
      {:subject-type subject-type
       :subject-eid subject-eid
       :relation-eid relation-eid
       :resource-type resource-type
       :resource-eid resource-eid
       :qualifier-eid qualifier-eid})))

(defn peer-half
  "Returns the exact peer endpoint and value for one decoded physical half."
  [direction endpoint-eid value]
  (case direction
    :forward
    (when-let [{:keys [subject-type subject-eid relation-eid
                       resource-type resource-eid qualifier-eid] :as decoded}
               (decode-forward endpoint-eid value)]
      (assoc decoded
             :direction :reverse
             :endpoint-eid resource-eid
             :value (reverse-value resource-type relation-eid
                                   subject-type subject-eid qualifier-eid)))

    :reverse
    (when-let [{:keys [subject-type subject-eid relation-eid
                       resource-type resource-eid qualifier-eid] :as decoded}
               (decode-reverse endpoint-eid value)]
      (assoc decoded
             :direction :forward
             :endpoint-eid subject-eid
             :value (forward-value subject-type relation-eid
                                   resource-type resource-eid qualifier-eid)))

    nil))

(defn dangling-report
  "Summarizes a dangling-half stream without retaining its scan head."
  [halves {:keys [sample-size] :or {sample-size 20}}]
  (when-not (and (integer? sample-size) (not (neg? sample-size)))
    (throw
     (ex-info
      ":sample-size must be a non-negative integer."
      {:type :eacl.integrity/invalid-options
       :eacl/error :eacl.integrity/invalid-options
       :sample-size sample-size})))
  (let [{:keys [count by-half sample]}
        (reduce
         (fn [{:keys [count] :as report} half]
           (cond-> (-> report
                       (assoc :count (inc count))
                       (update-in [:by-half (:half half)] (fnil inc 0)))
             (< count sample-size) (update :sample conj half)))
         {:count 0 :by-half {:forward 0 :reverse 0} :sample []}
         halves)]
    {:valid? (zero? count)
     :dangling-count count
     :by-half by-half
     :sample sample}))

(defn half-retractions
  "Exact pair cleanup derived from either physical half, preserving qualifier."
  [direction owner value]
  (if-let [{:keys [subject-type subject-eid relation-eid resource-type
                   resource-eid qualifier-eid]}
           (peer-half direction owner value)]
    (retractions subject-type subject-eid relation-eid resource-type resource-eid qualifier-eid)
    (throw (ex-info "Cannot repair a malformed Relationship endpoint value."
                    {:type :eacl/invalid-relationship-storage
                     :eacl/error :eacl/invalid-relationship-storage
                     :endpoint-eid owner :value value}))))

(defn orphaned-halves
  "Offline diagnostics over one direction. Native peer lookup stays in the adapter."
  [direction datoms peer-exists?]
  (keep (fn [{:keys [e v]}]
          (let [peer (peer-half direction e v)]
            (when (or (nil? peer) (not (peer-exists? peer)))
              {:half direction :e e
               :attr (if (= :forward direction) storage/forward-attribute storage/reverse-attribute)
               :v v
               :subject-eid (:subject-eid peer)
               :resource-eid (:resource-eid peer)
               :relation-eid (:relation-eid peer)
               :qualifier-eid (:qualifier-eid peer)
               :value-arity (when (counted? v) (count v))})))
        datoms))
