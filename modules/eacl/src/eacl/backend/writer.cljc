(ns eacl.backend.writer
  "Certified mutation boundary for one backend connection."
  (:require [eacl.backend.v8 :as backend]))

(def writer-version 1)

(def required-operations
  #{:transact!
    :write-schema!
    :schema-generation
    :plan-relationship-update
    :plan-delete-object
    :prepare-relationship-tx
    :relation-id
    :affected-relations
    :retraction-count
    :contention?})

(def writer-obligations
  "Runtime-facing assumptions for mutation planning and submission. These are
  separate from immutable basis reads and source selection."
  {:transact!
   #{:atomic-commit :committed-value-returned :typed-contention-failure}
   :write-schema!
   #{:schema-fence :schema-generation-advance :committed-value-returned}
   :schema-generation
   #{:planning-basis-bound :transactionally-persisted-eacl-generation}
   :plan-relationship-update
   #{:planning-basis-bound :complete-relation-stamp-effects}
   :plan-delete-object
   #{:planning-basis-bound :complete-retraction-plan}
   :prepare-relationship-tx
   #{:complete-relation-stamp-effects :schema-fence}
   :relation-id
   #{:planning-basis-bound :canonical-relation-identity}
   :affected-relations
   #{:complete-relation-dependency-set}
   :retraction-count
   #{:exact-planned-retraction-count}
   :contention?
   #{:truthful-retryable-contention-classification}})

(def default-max-attempts 1)
(def default-max-transaction-size backend/maximum-exact-integer)

(defn- invalid-writer!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl/invalid-backend-role
            :eacl/error :eacl/invalid-backend-role
            :role :writer}
           data))))

(defn make-writer
  "Constructs and certifies a backend writer role.

  Connections and transaction services are permitted only inside this role;
  authorization snapshots never retain a writer."
  [{:keys [id state operations max-attempts max-transaction-size]
    :or {max-attempts default-max-attempts
         max-transaction-size default-max-transaction-size}
    :as input}]
  (when-not (map? input)
    (invalid-writer! "Writer input must be a map." {:value input}))
  (when-not (keyword? id)
    (invalid-writer! "Writer :id must be a keyword." {:backend id}))
  (when-not (map? state)
    (invalid-writer! "Writer :state must be a map." {:backend id}))
  (when-not (map? operations)
    (invalid-writer! "Writer :operations must be a map." {:backend id}))
  (when-let [missing
             (seq (remove #(ifn? (get operations %)) required-operations))]
    (invalid-writer!
     "Writer is missing required operations."
     {:backend id
      :operation (first missing)
      :missing-operations (vec missing)
      :required-operations required-operations}))
  (when-not (and (integer? max-attempts) (pos? max-attempts))
    (invalid-writer! "Writer :max-attempts must be positive."
                     {:backend id :max-attempts max-attempts}))
  (when-not (and (integer? max-transaction-size)
                 (pos? max-transaction-size)
                 (<= max-transaction-size backend/maximum-exact-integer))
    (invalid-writer!
     "Writer :max-transaction-size is outside the exact integer domain."
     {:backend id :max-transaction-size max-transaction-size}))
  {::writer true
   ::version writer-version
   ::id id
   ::state state
   ::max-attempts max-attempts
   ::max-transaction-size max-transaction-size
   ::operations operations})

(defn writer?
  [candidate]
  (and (map? candidate)
       (true? (::writer candidate))
       (= writer-version (::version candidate))
       (keyword? (::id candidate))
       (map? (::state candidate))
       (map? (::operations candidate))))

(defn- require-writer
  [candidate]
  (if (writer? candidate)
    candidate
    (invalid-writer! "Value is not a certified writer." {:value candidate})))

(defn backend-id [writer] (::id (require-writer writer)))
(defn state [writer] (::state (require-writer writer)))
(defn max-attempts [writer] (::max-attempts (require-writer writer)))
(defn max-transaction-size [writer]
  (::max-transaction-size (require-writer writer)))

(defn operation
  [writer operation-key]
  (let [writer (require-writer writer)]
    (if-let [implementation (get (::operations writer) operation-key)]
      implementation
      (invalid-writer!
       "Writer operation is not certified."
       {:backend (::id writer) :operation operation-key}))))

(defn invoke
  [writer operation-key & args]
  (apply (operation writer operation-key) args))
