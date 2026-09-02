(ns eacl.proof-frame
  "Request-scoped ordered-generation proofs for managed cache reuse.

  A frame is bound to one immutable adapter. It acquires only canonical,
  complete dependency evidence, derives bounded proof identities centrally,
  and never extends a completed closure with evidence from another snapshot."
  (:require [eacl.backend.v8 :as backend]))

(def default-maximum-relation-count 4096)

(defn generation?
  "True for a portable non-negative committed transaction generation."
  [value]
  (and
   #?(:clj (integer? value)
      :cljs (and (number? value) (js/Number.isSafeInteger value)))
   (not (neg? value))
   (<= value backend/maximum-exact-integer)))

(defn canonical-relation-ids
  [relation-ids]
  (when (and (vector? relation-ids)
             (every? generation? relation-ids))
    (loop [position 1]
      (if (< position (count relation-ids))
        (when (< (nth relation-ids (dec position))
                 (nth relation-ids position))
          (recur (inc position)))
        relation-ids))))

(defn request-frame
  "Creates a lazy proof frame for one immutable selected adapter.

  `:maximum-relation-count` bounds proof work. `:diagnostic-fn`, when supplied
  internally, observes typed unavailable results and cannot affect decisions."
  ([adapter]
   (request-frame adapter {}))
  ([adapter {:keys [maximum-relation-count diagnostic-fn
                    schema-generation-fn basis-identity]
             :or {maximum-relation-count
                  default-maximum-relation-count}}]
   (when-not (backend/adapter? adapter)
     (throw
      (ex-info
       "A proof frame requires a selected backend adapter."
       {:type :eacl/invalid-config :eacl/error :eacl/invalid-config})))
   (when (and (some? schema-generation-fn)
              (not (fn? schema-generation-fn)))
     (throw
      (ex-info
       "A proof frame schema-generation resolver must be a function."
       {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
        :key :schema-generation-fn})))
   {:adapter adapter
    :basis-identity basis-identity
    :snapshot-id-delay
    (delay
      (or (:backend-snapshot-id basis-identity)
          (backend/invoke adapter :snapshot-id)))
    :source-lifecycle-delay
    (delay (:source-lifecycle basis-identity))
    :revision-delay
    (delay
      (or (:revision basis-identity)
          (:revision (backend/invoke adapter :native-revision))))
    :schema-generation-delay
    (delay
      (if schema-generation-fn
        (schema-generation-fn)
        (backend/invoke adapter :schema-generation)))
    :maximum-relation-count maximum-relation-count
    :diagnostic-fn diagnostic-fn
    :resolutions (atom {})}))

(defn snapshot-id
  [frame]
  (force (:snapshot-id-delay frame)))

(defn source-lifecycle
  [frame]
  (force (:source-lifecycle-delay frame)))

(defn schema-generation
  [frame]
  (force (:schema-generation-delay frame)))

(defn revision
  [frame]
  (force (:revision-delay frame)))

(defn- diagnose!
  [frame result]
  (when-let [diagnostic-fn (:diagnostic-fn frame)]
    (try
      (diagnostic-fn result)
      (catch #?(:clj Throwable :cljs :default) _)))
  result)

(defn- unavailable
  [frame reason details]
  (let [result
        {:status :unavailable
         :reason reason
         :backend (backend/backend-id (:adapter frame))
         :details details}]
    (diagnose! frame result)))

(defn- contract-violation
  [frame reason details]
  (diagnose!
   frame
   {:status :contract-violation
    :reason reason
    :backend (backend/backend-id (:adapter frame))
    :operation :proof-frame
    :details details}))

(defn contract-violation?
  [proof]
  (= :contract-violation (:status proof)))

(defn- duplicate-value
  [values]
  (loop [seen #{}
         remaining (seq values)]
    (when remaining
      (let [value (first remaining)]
        (if (contains? seen value)
          value
          (recur (conj seen value) (next remaining)))))))

(defn- complete-result
  [frame relation-ids raw]
  (let [selected-revision (revision frame)
        certified-generation (schema-generation frame)
        entries? (and (vector? raw)
                      (every? #(and (vector? %) (= 2 (count %))) raw))
        returned-ids (when entries? (mapv first raw))
        generations (when entries? (mapv second raw))
        duplicate-id (when returned-ids (duplicate-value returned-ids))]
    (cond
      (not (generation? selected-revision))
      (unavailable
       frame :revision-unavailable {:revision selected-revision})

      (nil? certified-generation)
      (unavailable
       frame :schema-generation-unavailable {})

      (not (generation? certified-generation))
      (contract-violation
       frame :invalid-schema-generation
       {:schema-generation certified-generation
        :revision selected-revision})

      (> certified-generation selected-revision)
      (contract-violation
       frame :schema-generation-above-revision
       {:schema-generation certified-generation
        :revision selected-revision})

      (not entries?)
      (contract-violation
       frame :malformed-shape
       {:relation-generations raw})

      (not= (count relation-ids) (count raw))
      (contract-violation
       frame :wrong-cardinality
       {:expected-count (count relation-ids)
        :actual-count (count raw)})

      duplicate-id
      (contract-violation
       frame :duplicate-relation-id
       {:relation-id duplicate-id
        :relation-ids returned-ids})

      (not= relation-ids returned-ids)
      (contract-violation
       frame :noncanonical-relation-ids
       {:expected relation-ids :actual returned-ids})

      (some nil? generations)
      (unavailable
       frame :relation-generation-unavailable
       {:relation-id
        (first
         (keep-indexed
          (fn [index generation]
            (when (nil? generation) (nth relation-ids index)))
          generations))})

      (not-every? generation? generations)
      (contract-violation
       frame :invalid-relation-generation
       {:relation-generations raw})

      (some #(> % selected-revision) generations)
      (contract-violation
       frame :relation-generation-above-revision
       {:revision selected-revision
        :relation-generations raw})

      :else
      {:status :complete
       :snapshot-id (snapshot-id frame)
       :source-lifecycle (source-lifecycle frame)
       :revision selected-revision
       :schema-generation certified-generation
       :relation-ids relation-ids
       :relation-generations raw
       :dependency-stamp
       (reduce
        (fn [frontier [_ generation]]
          (max frontier generation))
        0
        raw)})))

(defn- acquire
  [frame relation-ids]
  (cond
    (nil? relation-ids)
    (unavailable frame :noncanonical-dependencies {})

    (> (count relation-ids) (:maximum-relation-count frame))
    (unavailable
     frame :proof-bound-exceeded
     {:count (count relation-ids)
      :maximum (:maximum-relation-count frame)})

    (not (backend/supports?
          (:adapter frame) :cache-proofs :ordered-generations))
    (unavailable frame :unsupported-proof-capability {})

    :else
    (try
      (complete-result
       frame
       relation-ids
       (backend/invoke (:adapter frame) :proof-frame relation-ids))
      (catch #?(:clj Throwable :cljs :default) error
        (if (= :eacl/backend-contract-violation
               (:type (ex-data error)))
          (contract-violation
           frame :adapter-runtime-guard
           (select-keys (ex-data error)
                        [:backend :operation :obligation :value]))
          (unavailable
           frame :proof-provider-failure
           {:error-class #?(:clj (.getName (class error))
                            :cljs (or (.-name error) "Error"))}))))))

(defn resolve!
  "Returns one immutable complete or typed-unavailable proof result.

  Equal canonical dependency closures share one delay inside the request."
  [frame relation-ids]
  (let [canonical (canonical-relation-ids relation-ids)
        key (or canonical ::noncanonical)
        resolutions (:resolutions frame)
        selected
        (or (get @resolutions key)
            (let [created (delay (acquire frame canonical))]
              (get (swap! resolutions
                          #(if (contains? % key) % (assoc % key created)))
                   key)))]
    @selected))

(defn complete?
  [proof]
  (= :complete (:status proof)))

(defn- dependency-identity?
  [value]
  (and (vector? value)
       (every? #(and (vector? %)
                     (= 2 (count %))
                     (generation? (first %))
                     (generation? (second %)))
               value)
       (some? (canonical-relation-ids (mapv first value)))))

(defn- dependency-frontier
  [dependency-identity]
  (reduce
   (fn [frontier [_ generation]]
     (max frontier generation))
   0
   dependency-identity))

(defn descriptor?
  "True only for a closed, canonical managed-reuse proof descriptor."
  [value]
  (and (map? value)
       (= #{:schema-generation :dependency-identity :dependency-stamp}
          (set (keys value)))
       (generation? (:schema-generation value))
       (dependency-identity? (:dependency-identity value))
       (generation? (:dependency-stamp value))
       (= (:dependency-stamp value)
          (dependency-frontier (:dependency-identity value)))))

(defn descriptor
  "Returns the complete completed-cache identity for a complete proof."
  [proof]
  (when (complete? proof)
    {:schema-generation (:schema-generation proof)
     :dependency-identity (:relation-generations proof)
     :dependency-stamp (:dependency-stamp proof)}))
