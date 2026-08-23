(ns eacl.proof-frame
  "Request-scoped ordered-generation proofs for managed cache reuse.

  A frame is bound to one immutable adapter. It acquires only canonical,
  complete dependency evidence, derives scalar frontiers centrally, and never
  extends a completed closure with evidence from another snapshot."
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
                    schema-generation-fn]
             :or {maximum-relation-count
                  default-maximum-relation-count}}]
   (when-not (backend/adapter? adapter)
     (throw
      (ex-info
       "A proof frame requires a selected backend adapter."
       {:type :eacl/invalid-config})))
   (when (and (some? schema-generation-fn)
              (not (fn? schema-generation-fn)))
     (throw
      (ex-info
       "A proof frame schema-generation resolver must be a function."
       {:type :eacl/invalid-config
        :key :schema-generation-fn})))
   {:adapter adapter
    :snapshot-id-delay (delay (backend/invoke adapter :snapshot-id))
    :source-lifecycle-delay
    (delay (backend/invoke adapter :source-lifecycle))
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

(defn- schema-generation-mismatch!
  [frame proof-generation certified-generation]
  (throw
   (ex-info
    "Ordered-generation proof disagrees with the certified schema generation."
    {:type :eacl/backend-integrity-error
     :eacl/error :eacl/backend-integrity-error
     :backend (backend/backend-id (:adapter frame))
     :reason :schema-generation-mismatch
     :proof-schema-generation proof-generation
     :certified-schema-generation certified-generation})))

(defn- unavailable
  [frame reason details]
  (let [result
        {:status :unavailable
         :reason reason
         :backend (backend/backend-id (:adapter frame))
         :details details}]
    (when-let [diagnostic-fn (:diagnostic-fn frame)]
      (try
        (diagnostic-fn result)
        (catch #?(:clj Throwable :cljs :default) _)))
    result))

(defn- complete-result
  [frame relation-ids raw]
  (let [expected-keys #{:schema-stamp :relation-stamps}
        relation-stamps (:relation-stamps raw)]
    (cond
      (not (map? raw))
      (unavailable frame :malformed-proof {:value raw})

      (not= expected-keys (set (keys raw)))
      (unavailable
       frame :malformed-proof
       {:expected-keys expected-keys :actual-keys (set (keys raw))})

      (not (generation? (:schema-stamp raw)))
      (unavailable
       frame :malformed-schema-generation
       {:value (:schema-stamp raw)})

      (not (and (vector? relation-stamps)
                (= relation-ids (mapv first relation-stamps))
                (every?
                 (fn [entry]
                   (and (vector? entry)
                        (= 2 (count entry))
                        (generation? (first entry))
                        (generation? (second entry))))
                 relation-stamps)))
      (unavailable
       frame :incomplete-or-noncanonical-generations
       {:relation-ids relation-ids :relation-stamps relation-stamps})

      :else
      (let [proof-generation (:schema-stamp raw)
            certified-generation (schema-generation frame)]
        (when (and (some? certified-generation)
                   (not= certified-generation proof-generation))
          (schema-generation-mismatch!
           frame proof-generation certified-generation))
        {:status :complete
         :snapshot-id (snapshot-id frame)
         :source-lifecycle (source-lifecycle frame)
         :schema-stamp proof-generation
         :relation-ids relation-ids
         :relation-stamps relation-stamps
         :relation-stamp-map (into {} relation-stamps)
         :dependency-stamp
         (reduce
          (fn [frontier [_ generation]]
            (max frontier generation))
          0
          relation-stamps)}))))

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
        (if (= :eacl/backend-integrity-error
               (:type (ex-data error)))
          (throw error)
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
        created (delay (acquire frame canonical))
        selected
        (get
         (swap! (:resolutions frame)
                #(if (contains? % key) % (assoc % key created)))
         key)]
    @selected))

(defn complete?
  [proof]
  (= :complete (:status proof)))

(defn descriptor
  "Returns the constant-size completed-cache identity for a complete proof."
  [proof]
  (when (complete? proof)
    (select-keys proof [:schema-stamp :dependency-stamp])))

(defn subset-descriptor
  "Derives a subset frontier only from a complete request proof.

  Relations outside the proved closure fail closed; no provider call extends
  the frame."
  [proof relation-ids]
  (when (complete? proof)
    (let [canonical (canonical-relation-ids
                     (if (vector? relation-ids)
                       relation-ids
                       [relation-ids]))
          stamps (:relation-stamp-map proof)]
      (when (and canonical
                 (every? #(contains? stamps %) canonical))
        {:schema-stamp (:schema-stamp proof)
         :dependency-stamp
         (reduce
          (fn [frontier relation-id]
            (max frontier (get stamps relation-id)))
          0
          canonical)}))))
