(ns eacl.authorization.qualification
  "One edge qualification seam for a selected immutable request basis.
   This layer resolves data only; traversal remains in the existing engine."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.cache.standard-lru :as lru]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.values :as values]
            [eacl.execution :as execution]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.qualifier :as qualifier]))

(def maximum-request-entries 100000)
(defrecord Qualification [time context evaluator entity version basis cache memos])

(defn request
  "Captures trusted time and exact source/basis identity supplied by request
   orchestration. Entity/version callbacks close over that same native basis.
   Decode retention is optional and always uses complete exact basis identity."
  [{:keys [time context evaluator entity version basis cache]}]
  (when-not (and (values/valid-time? time) (or (nil? context) (map? context))
                (fn? entity) (fn? version) (map? basis) (seq basis)
                (or (nil? cache) (lru/store? cache)))
    (qualifier/error! :qualification-context))
  (->Qualification time (or context {}) evaluator entity version basis cache (delay (volatile! {}))))

(defn- memo! [request key build]
  (let [memos (force (:memos request)) current @memos
        result
        (if (contains? current key)
          (get current key)
          (do
            (when (>= (count current) maximum-request-entries) (qualifier/error! :request-qualifier-limit))
            (let [result (try {:value (build)}
                              (catch #?(:clj Exception :cljs :default) error {:error error}))]
              (vswap! memos assoc key result)
              result)))]
    (if-let [error (:error result)] (throw error) (:value result))))

(defn- named-definition [request eid]
  (memo! request [:caveat eid]
         #(let [entity ((:entity request) eid)]
            {:entity entity :header (definition/decode-header entity)})))

(defn exact-reuse-identity
  "Complete collision-checked semantic scope for same-basis, same-time reuse.
   Cross-time acceptance additionally requires a certified evidence interval."
  [request]
  (memo! request [:reuse-identity]
         #(vector qualifier/format-version (:basis request) (:time request)
                  (values/encode-bounded (:context request)
                                         {:maximum-size (:context-utf8-bytes values/limits)
                                          :maximum-entries (:context-total-entries values/limits)
                                          :maximum-depth 8})
                  (when-let [engine (:evaluator request)]
                    (select-keys (evaluator/descriptor engine)
                                 [:profile :profile-fingerprint :fingerprint :capability-version])))))

(defn- decoded [request qid]
  (memo! request [:qualifier qid]
    #(let [key [(:basis request) qid qualifier/format-version]
           cached (when-let [cache (:cache request)] (lru/lookup! cache key))]
       (if (:found? cached)
         (:value cached)
         (let [entity ((:entity request) qid)
               caveat-id (get entity qualifier/caveat-attribute)
               _ (when (and (some? caveat-id) (not (qualifier/concrete-eid? caveat-id)))
                   (qualifier/error! :qualifier-ref))
               named (when caveat-id (named-definition request caveat-id))
               value (qualifier/decode entity (get-in named [:header :parameters] []))
               version ((:version request) qid)
               _ (when (and (some? version) (not (qualifier/concrete-eid? version)))
                   (qualifier/error! :qualifier-version))
               result {:qualifier value :definition named :version version}]
           (when-let [cache (:cache request)] (lru/put-if-absent! cache key result))
           result)))))

(defn- allowed! [request relation-id caveat-id]
  (let [allowed (memo! request [:relation relation-id]
                       #(let [relation ((:entity request) relation-id)]
                          (when-not (and (map? relation) (seq relation))
                            (qualifier/error! :missing-relation))
                          (qualifier/relation-allowance relation)))]
    (when-not (contains? allowed caveat-id) (qualifier/error! :caveat-not-allowed))))

(defn- caveat-evidence [request named bound]
  (let [result (evaluator/evaluate (:evaluator request) (:entity named) (:context request) bound)]
    (case (:outcome result)
      :true true
      :false false
      :conditional
      (evidence/conditional
       [values/profile-id evaluator/profile-fingerprint (:entity named)
        (get-in named [:header :parameters]) (:residual result)]
       (:missing-fields result))
      :error (evidence/fault :eacl.caveat/evaluation
                             (if (keyword? (:reason result)) (:reason result) :invalid-outcome))
      (evidence/fault :eacl.caveat/evaluation :invalid-outcome))))

(defn qualify
  "Returns evidence for one compact native edge. The ordinary integer branch
   allocates nothing and never dereferences request state. All authoritative
   faults remain faults, including faults on subtracting edges."
  [request relation-id compact-edge]
  (if-not (vector? compact-edge)
    (some? compact-edge)
    (do
      (execution/check! :qualifier-resolution)
      (try
        (when-not (edge/valid? compact-edge) (qualifier/error! :qualifier-ref))
        (let [{:keys [qualifier definition]} (decoded request (edge/qualifier-id compact-edge))
              {:keys [caveat caveat-context valid-until-ms]} qualifier]
          (allowed! request relation-id caveat)
          (if (and valid-until-ms (>= (:time request) valid-until-ms))
            false
            (evidence/with-certificate
             (if caveat (caveat-evidence request definition caveat-context) true)
             valid-until-ms true)))
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
          (let [{:keys [type reason]} (ex-data error)]
            (if (contains? #{:eacl.qualifier/invalid :eacl.caveat/invalid
                             :eacl.caveat/evaluator-unavailable
                             :eacl.authorization/invalid-evidence} type)
              (evidence/fault type (if (keyword? reason) reason :unavailable))
              (throw error))))))))
