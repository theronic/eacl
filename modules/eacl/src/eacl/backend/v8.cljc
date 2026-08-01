(ns eacl.backend.v8
  "Validated capability and operation contract for v8 backend snapshots.

  The legacy six-function map SPI remains in eacl.backend.spi. This namespace
  describes the richer snapshot boundary needed by recursive traversal,
  Relay pagination, deletion, consistency selection, and exact cache proofs."
  (:require [eacl.spicedb.consistency :as consistency]))

(def adapter-version 1)

(def legacy-spi-operations
  #{:cache-stamp
    :relation-defs
    :permission-defs
    :subject->resources
    :resource->subjects
    :direct-match?})

(def required-snapshot-operations
  #{:snapshot-id
    :object-id->internal
    :internal-id->object
    :relation-defs
    :permission-defs
    :subject->resources
    :resource->subjects
    :direct-match?
    :all-permission-nodes
    :frontier-key
    :schema-proof
    :relation-proof})

(def known-consistency-modes
  #{:fully-consistent
    :minimize-latency
    :at-least-as-fresh
    :at-exact-snapshot})

(def empty-capabilities
  {:consistency #{}
   :snapshots #{}
   :cursor #{}
   :transactions #{}
   :cache-proofs #{}
   :runtime #{}})

(defn- invalid-adapter!
  [message data]
  (throw (ex-info message
                  (assoc data
                         :type :eacl/invalid-backend-adapter
                         :eacl/error :eacl/invalid-backend-adapter))))

(defn- unsupported!
  [backend-id capability requested supported]
  (throw
   (ex-info
    (str "Backend " (pr-str backend-id)
         " does not support " (pr-str capability)
         (when (some? requested)
           (str " " (pr-str requested)))
         ".")
    {:type :eacl/unsupported-capability
     :eacl/error :eacl/unsupported-capability
     :backend backend-id
     :capability capability
     :requested requested
     :supported supported})))

(defn normalize-capabilities
  [backend-id capabilities]
  (when-not (map? capabilities)
    (invalid-adapter! "Backend :capabilities must be a map."
                      {:backend backend-id
                       :capabilities capabilities}))
  (let [normalized (merge empty-capabilities capabilities)
        unknown-keys (seq (remove (set (keys empty-capabilities))
                                  (keys normalized)))]
    (when unknown-keys
      (invalid-adapter! "Backend declares unknown capability groups."
                        {:backend backend-id
                         :unknown-capabilities (vec unknown-keys)
                         :known-capabilities (set (keys empty-capabilities))}))
    (doseq [[capability values] normalized]
      (when-not (set? values)
        (invalid-adapter! "Backend capability groups must contain sets."
                          {:backend backend-id
                           :capability capability
                           :value values})))
    (when-let [unknown-modes
               (seq (remove known-consistency-modes
                            (:consistency normalized)))]
      (invalid-adapter! "Backend declares unknown consistency modes."
                        {:backend backend-id
                         :unknown-consistency-modes (vec unknown-modes)
                         :known-consistency-modes known-consistency-modes}))
    normalized))

(defn legacy-adapter?
  "True when a map implements the original six-function SPI exactly enough to
  remain usable by eacl.engine.indexed."
  [candidate]
  (and (map? candidate)
       (every? (fn [operation]
                 (fn? (get candidate operation)))
               legacy-spi-operations)))

(defn validate-legacy-adapter!
  [candidate]
  (when-not (legacy-adapter? candidate)
    (invalid-adapter! "Legacy backend map is missing a six-function SPI operation."
                      {:required-operations legacy-spi-operations
                       :provided-operations
                       (set (for [[operation implementation] candidate
                                  :when (fn? implementation)]
                              operation))}))
  candidate)

(defn make-adapter
  [{:keys [id capabilities operations state]}]
  (when-not (keyword? id)
    (invalid-adapter! "Backend :id must be a keyword."
                      {:backend id}))
  (when-not (map? operations)
    (invalid-adapter! "Backend :operations must be a map."
                      {:backend id
                       :operations operations}))
  (let [missing (seq (remove #(fn? (get operations %))
                             required-snapshot-operations))]
    (when missing
      (invalid-adapter! "Backend is missing required snapshot operations."
                        {:backend id
                         :missing-operations (vec missing)
                         :required-operations required-snapshot-operations})))
  {::adapter true
   ::version adapter-version
   ::id id
   ::capabilities (normalize-capabilities id capabilities)
   ::operations operations
   ::state state})

(defn adapter?
  [candidate]
  (and (map? candidate)
       (true? (::adapter candidate))
       (= adapter-version (::version candidate))
       (keyword? (::id candidate))
       (map? (::capabilities candidate))
       (map? (::operations candidate))))

(defn backend-id
  [adapter]
  (if (adapter? adapter)
    (::id adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn capabilities
  [adapter]
  (if (adapter? adapter)
    (::capabilities adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn supports?
  ([adapter capability]
   (boolean (seq (get (capabilities adapter) capability))))
  ([adapter capability requested]
   (contains? (get (capabilities adapter) capability #{}) requested)))

(defn require-supported!
  [backend-id capabilities capability requested]
  (let [normalized (normalize-capabilities backend-id capabilities)
        supported (get normalized capability #{})]
    (if (contains? supported requested)
      requested
      (unsupported! backend-id capability requested supported))))

(defn require-capability!
  [adapter capability requested]
  (require-supported! (backend-id adapter)
                      (capabilities adapter)
                      capability
                      requested))

(defn require-consistency!
  "Normalizes a public consistency descriptor and verifies that the backend
  promises the selected mode. Returns the normalized descriptor."
  [adapter value]
  (let [{:keys [mode] :as descriptor} (consistency/descriptor value)]
    (require-capability! adapter :consistency mode)
    descriptor))

(defn operation
  [adapter operation-key]
  (when-not (adapter? adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter}))
  (if-let [implementation (get (::operations adapter) operation-key)]
    implementation
    (unsupported! (::id adapter)
                  :operation
                  operation-key
                  (set (keys (::operations adapter))))))

(defn invoke
  [adapter operation-key & args]
  (apply (operation adapter operation-key) args))
